#!/usr/bin/env python3
"""
BSA-YourView CI/CD Auto-Adoption Injector (ci_injector.py)
==========================================================
Automatically adapts GitHub Actions and local CI builds to every Gradle sub-module
and BSA-YourView Kotlin feature module built in the repository:

1. Auto-discovers Gradle sub-modules & ensures unregistered modules are included in settings.gradle.kts.
2. Auto-discovers all BSA-YourView feature modules (engines, services, data, models, UI screens/components)
   and verifies critical Android packaging resources (manifest, adaptive icon, FileProvider paths).
3. Decodes CI secrets safely (GOOGLE_SERVICES_JSON_B64, KEYSTORE_BASE64) and provisions .env fallback.
4. Injects GITHUB_UPDATE_REPO & UPDATE_MANIFEST_URL into BuildConfig (without modifying signingConfigs).
5. Supports automatic CI versionCode monotonicity (via GITHUB_RUN_NUMBER) & workflow_dispatch versionName.
6. Generates SHA-256 verified `update-manifest.json` (consumed by InAppUpdaterService.kt) and
   auto-populated `release-notes.md` + `build-module-manifest.json` for GitHub Releases.
"""

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, List, Optional, Tuple


IGNORED_DIRS = {
    ".git",
    ".github",
    ".gradle",
    ".idea",
    ".aistudio",
    ".build-outputs",
    "build",
    "gradle",
    "public",
    "node_modules",
    "__pycache__",
}


def _detect_keystore_format(raw: bytes) -> str:
    """Identifies raw keystore container format by magic bytes (never exposes secrets)."""
    if len(raw) >= 4 and raw[:4] == b"\xfe\xed\xfe\xed":
        return "JKS"
    if len(raw) >= 2 and raw[:2] == b"\x30\x82":
        return "PKCS12"
    if raw.lstrip().startswith(b"-----BEGIN"):
        return "PEM"
    return f"UNKNOWN(magic={raw[:4].hex() if raw else 'empty'})"


def _decode_base64_bytes(b64_value: str) -> bytes:
    """
    Decodes standard or URL-safe base64, safely stripping:
    - Windows `certutil -encode` / PEM armor lines (`-----BEGIN ...-----`, `-----END ...-----`)
    - Data URI prefixes (`data:...;base64,`)
    - Surrounding quotes and whitespace/newlines
    """
    text = (b64_value or "").strip()
    if not text:
        return b""
    # Strip surrounding quotes if pasted with quotes
    if len(text) >= 2 and text[0] == text[-1] and text[0] in ("'", '"'):
        text = text[1:-1].strip()
    # Strip data:...;base64, prefix if present
    text = re.sub(r"(?is)^data:[^;,]+;base64,", "", text)
    # Strip Windows certutil / PEM header & footer lines (e.g. -----BEGIN CERTIFICATE-----)
    lines = [
        ln.strip()
        for ln in text.splitlines()
        if ln.strip() and not re.match(r"^-{3,}\s*(BEGIN|END)\b.*-{3,}$", ln.strip(), re.IGNORECASE)
    ]
    joined = "".join(lines)
    # Handle URL-safe base64 alphabet
    normalized = joined.replace("-", "+").replace("_", "/")
    cleaned = re.sub(r"[^A-Za-z0-9+/=]", "", normalized)
    if not cleaned:
        return b""
    rem = len(cleaned) % 4
    if rem:
        cleaned += "=" * (4 - rem)
    return base64.b64decode(cleaned)


def decode_base64_to_file(b64_value: str, out_path: str, label: str, unwrap_double_b64: bool = False) -> bool:
    try:
        raw = _decode_base64_bytes(b64_value)
        if not raw:
            return False

        # Auto-unwrap accidental double-base64 encoding (e.g., base64 of debug.keystore.base64)
        if unwrap_double_b64 and _detect_keystore_format(raw).startswith("UNKNOWN"):
            try:
                as_text = raw.decode("ascii")
                inner = _decode_base64_bytes(as_text)
                inner_fmt = _detect_keystore_format(inner)
                if inner_fmt in ("JKS", "PKCS12"):
                    print(
                        f"[ci_injector] Detected double-base64 encoded {inner_fmt} keystore in {label}; "
                        f"unwrapped {len(raw)} -> {len(inner)} bytes automatically."
                    )
                    raw = inner
            except Exception:
                pass

        parent = os.path.dirname(out_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(out_path, "wb") as f:
            f.write(raw)
        fmt_note = f", format={_detect_keystore_format(raw)}" if unwrap_double_b64 else ""
        print(f"[ci_injector] Wrote {out_path} ({len(raw)} bytes{fmt_note}) from {label}.")
        return True
    except Exception as exc:
        print(f"[ci_injector] Warning: Failed to decode {label}: {exc}")
        return False


def ensure_env_file() -> None:
    """Ensure .env exists in CI runners so Secrets Gradle Plugin never fails on clean checkouts."""
    if os.path.exists(".env"):
        return
    if os.path.exists(".env.example"):
        shutil.copyfile(".env.example", ".env")
        print("[ci_injector] Created .env from .env.example for Secrets Gradle Plugin.")


def inject_google_services(app_dir: str) -> None:
    target = os.path.join(app_dir, "google-services.json")
    b64 = os.environ.get("GOOGLE_SERVICES_JSON_B64", "").strip()
    if b64 and decode_base64_to_file(b64, target, "GOOGLE_SERVICES_JSON_B64"):
        return
    if os.path.exists(target):
        print(f"[ci_injector] Using existing {target}.")
    else:
        print(f"[ci_injector] Info: {target} not found and GOOGLE_SERVICES_JSON_B64 not set; skipping.")


def _run_keytool(args: List[str], stdin_text: Optional[str] = None) -> Tuple[int, str, str]:
    try:
        proc = subprocess.run(
            ["keytool"] + args,
            input=stdin_text,
            capture_output=True,
            text=True,
            check=False,
        )
        return (proc.returncode, proc.stdout or "", proc.stderr or "")
    except FileNotFoundError:
        return (127, "", "keytool not found on PATH")


def _extract_keystore_info(keystore_path: str, store_pass: str) -> Tuple[List[str], str, str]:
    """
    Returns (aliases, sha256_fingerprint, error_summary) when opening `keystore_path` with `store_pass`.
    """
    code, out, err = _run_keytool(["-list", "-v", "-keystore", keystore_path, "-storepass", store_pass])
    if code != 0:
        first_err = next((ln.strip() for ln in (out + "\n" + err).splitlines() if ln.strip()), f"exit code {code}")
        return ([], "", first_err)
    aliases = re.findall(r"(?im)^Alias name:\s*(.+?)\s*$", out)
    fp_match = re.search(r"(?im)SHA-?256:\s*([0-9A-Fa-f:]{40,})", out)
    fingerprint = fp_match.group(1).upper() if fp_match else ""
    return (aliases, fingerprint, "")


def _peek_jks_public_metadata(keystore_path: str) -> Tuple[List[str], str]:
    """
    JKS stores public certificate entries unencrypted; listing with empty stdin reveals aliases &
    SHA-256 fingerprint for clear diagnostics even if STORE_PASSWORD is missing or wrong.
    """
    code, out, _ = _run_keytool(["-list", "-v", "-keystore", keystore_path], stdin_text="\n")
    if code != 0:
        return ([], "")
    aliases = re.findall(r"(?im)^Alias name:\s*(.+?)\s*$", out)
    fp_match = re.search(r"(?im)SHA-?256:\s*([0-9A-Fa-f:]{40,})", out)
    fingerprint = fp_match.group(1).upper() if fp_match else ""
    return (aliases, fingerprint)


def _extract_apk_signing_cert_sha256(apk_path: str) -> str:
    """Extracts the signing certificate SHA-256 fingerprint from a built APK using keytool."""
    code, out, _ = _run_keytool(["-printcert", "-jarfile", apk_path])
    if code == 0:
        m = re.search(r"(?im)SHA-?256:\s*([0-9A-Fa-f:]{40,})", out)
        if m:
            return m.group(1).upper()
    return ""


def _collect_env_values(names: List[str]) -> Tuple[List[str], List[str]]:
    """
    Returns (deduplicated_candidate_values, set_env_var_names).
    Preserves both trimmed and raw values if they differ (never logs secret contents).
    """
    values: List[str] = []
    seen = set()
    set_names: List[str] = []
    for name in names:
        raw = os.environ.get(name, "")
        if not raw:
            continue
        set_names.append(name)
        for cand in (raw.strip(), raw):
            if cand and cand not in seen:
                seen.add(cand)
                values.append(cand)
    return (values, set_names)


def _verify_key_password_and_align_alias(
    keystore_path: str,
    store_pass: str,
    chosen_alias: str,
    key_pass_candidates: List[str],
) -> Tuple[str, str]:
    """
    Verifies which key password unlocks `chosen_alias` and ensures the final alias inside
    `keystore_path` is `'upload'` (as required by app/build.gradle.kts) without altering the certificate.
    Returns (verified_key_pass, error_message).
    """
    last_err = ""
    if chosen_alias != "upload":
        for kp in key_pass_candidates:
            rc, out, err = _run_keytool([
                "-changealias",
                "-keystore", keystore_path,
                "-alias", chosen_alias,
                "-destalias", "upload",
                "-storepass", store_pass,
                "-keypass", kp,
            ])
            if rc == 0:
                print(f"[ci_injector] Normalized CI temp keystore alias '{chosen_alias}' -> 'upload' (certificate unchanged).")
                return (kp, "")
            last_err = next((ln.strip() for ln in (out + "\n" + err).splitlines() if ln.strip()), f"exit {rc}")
        return ("", last_err)

    # Alias is already 'upload': verify which candidate unlocks the private key via dry temp export
    import tempfile
    for kp in key_pass_candidates:
        tmp_dest = os.path.join(tempfile.gettempdir(), f"ci_ks_verify_{os.getpid()}.p12")
        try:
            if os.path.exists(tmp_dest):
                os.remove(tmp_dest)
            rc, out, err = _run_keytool([
                "-importkeystore",
                "-srckeystore", keystore_path,
                "-destkeystore", tmp_dest,
                "-deststoretype", "PKCS12",
                "-srcalias", "upload",
                "-destalias", "upload",
                "-srcstorepass", store_pass,
                "-srckeypass", kp,
                "-deststorepass", store_pass,
                "-destkeypass", kp,
                "-noprompt",
            ])
            if rc == 0:
                return (kp, "")
            last_err = next((ln.strip() for ln in (out + "\n" + err).splitlines() if ln.strip()), f"exit {rc}")
        finally:
            if os.path.exists(tmp_dest):
                os.remove(tmp_dest)
    return ("", last_err)


def configure_ci_signing_keystores(dry_run: bool = False) -> Dict[str, str]:
    """
    Authoritative CI keystore configurator that prevents 'package conflicts with an existing package':
    1. Supports all common GitHub Secret names for keystore base64, store password, key password, and alias.
    2. Handles standard base64, URL-safe base64, and accidental double-base64 encoding automatically.
    3. Auto-detects whether the secret keystore is a Debug keystore (alias 'androiddebugkey' / pass 'android')
       or a Release keystore (JKS or PKCS12, with any custom alias or password):
       - If Debug keystore: writes it directly to `debug.keystore` so `debugConfig` signs with the exact
         GitHub Secret certificate.
       - If Release keystore: writes it to `my-upload-key.jks`, auto-resolves working store/key password
         from configured secrets (or 'android' if created with default password), and normalizes any custom
         alias to 'upload' on the temporary CI copy without changing the private key or cert SHA-256!
    4. Fails fast with secret-safe diagnostics if a provided GitHub Secret keystore cannot be opened.
    """
    result = {
        "hasReleaseKeystore": "false",
        "signingMode": "debug-repo",
        "signingCertSha256": "",
        "effectiveStorePassword": "",
        "effectiveKeyPassword": "",
    }

    if dry_run:
        return result

    # 1. Always prepare repo debug.keystore baseline first (can be overridden below by GitHub Secret)
    if os.path.exists("debug.keystore.base64"):
        raw_b64 = Path("debug.keystore.base64").read_text(encoding="utf-8", errors="ignore")
        if decode_base64_to_file(raw_b64, "debug.keystore", "debug.keystore.base64", unwrap_double_b64=True):
            _, repo_fp, _ = _extract_keystore_info("debug.keystore", "android")
            if repo_fp:
                result["signingCertSha256"] = repo_fp
                print(f"[ci_injector] Baseline debug.keystore ready (SHA-256: {repo_fp}).")

    # 2. Check explicit DEBUG_KEYSTORE_BASE64 secret first (overrides debug.keystore directly)
    debug_secret_b64 = os.environ.get("DEBUG_KEYSTORE_BASE64", "").strip()
    if debug_secret_b64:
        if not decode_base64_to_file(debug_secret_b64, "debug.keystore", "DEBUG_KEYSTORE_BASE64", unwrap_double_b64=True):
            raise RuntimeError("DEBUG_KEYSTORE_BASE64 secret could not be base64-decoded.")
        aliases, fp, err_msg = _extract_keystore_info("debug.keystore", "android")
        if "androiddebugkey" not in [a.lower() for a in aliases]:
            raise RuntimeError(
                f"DEBUG_KEYSTORE_BASE64 did not open with password 'android' and alias 'androiddebugkey' "
                f"(found aliases: {aliases}, keytool: {err_msg})."
            )
        result["signingMode"] = "debug-secret"
        result["signingCertSha256"] = fp
        print(f"[ci_injector] Adopted DEBUG_KEYSTORE_BASE64 into debug.keystore (SHA-256: {fp}).")

    # 3. Check KEYSTORE_BASE64 / RELEASE_KEYSTORE_BASE64 / SIGNING_KEY secret (or pre-existing my-upload-key.jks)
    secret_b64 = ""
    secret_label = "KEYSTORE_BASE64"
    for ks_env in (
        "KEYSTORE_BASE64",
        "RELEASE_KEYSTORE_BASE64",
        "SIGNING_KEY",
        "KEYSTORE_B64",
        "ANDROID_KEYSTORE_BASE64",
    ):
        val = os.environ.get(ks_env, "").strip()
        if val:
            secret_b64 = val
            secret_label = ks_env
            break

    store_pass_env_names = [
        "STORE_PASSWORD",
        "KEYSTORE_PASSWORD",
        "KEY_STORE_PASSWORD",
        "RELEASE_STORE_PASSWORD",
        "SIGNING_STORE_PASSWORD",
        "ANDROID_KEYSTORE_PASSWORD",
        "KEYSTORE_PASS",
        "STORE_PASS",
        "UPLOAD_STORE_PASSWORD",
    ]
    key_pass_env_names = [
        "KEY_PASSWORD",
        "RELEASE_KEY_PASSWORD",
        "SIGNING_KEY_PASSWORD",
        "ANDROID_KEY_PASSWORD",
        "KEY_PASS",
        "UPLOAD_KEY_PASSWORD",
        "ALIAS_PASSWORD",
    ]
    alias_env_names = [
        "KEY_ALIAS",
        "RELEASE_KEY_ALIAS",
        "SIGNING_KEY_ALIAS",
        "ANDROID_KEY_ALIAS",
        "ALIAS",
    ]

    store_cands, set_store_envs = _collect_env_values(store_pass_env_names)
    key_cands, set_key_envs = _collect_env_values(key_pass_env_names)
    alias_cands, _ = _collect_env_values(alias_env_names)
    requested_alias = alias_cands[0] if alias_cands else ""

    # Ordered candidate store passwords: explicit store secrets -> key secrets -> default 'android' -> 'changeit'
    all_store_candidates: List[str] = []
    for cand in store_cands + key_cands + ["android", "changeit"]:
        if cand not in all_store_candidates:
            all_store_candidates.append(cand)

    target_release = "my-upload-key.jks"
    has_secret_input = bool(secret_b64)
    if secret_b64:
        if not decode_base64_to_file(secret_b64, target_release, secret_label, unwrap_double_b64=True):
            raise RuntimeError(
                f"{secret_label} secret could not be base64-decoded. Check for truncation or invalid base64."
            )

    if os.path.exists(target_release) and os.path.getsize(target_release) > 0:
        raw_bytes = Path(target_release).read_bytes()
        ks_format = _detect_keystore_format(raw_bytes)

        matched_store_pass = ""
        aliases: List[str] = []
        fp = ""
        last_keytool_err = ""

        for cand_pass in all_store_candidates:
            a_list, f_print, k_err = _extract_keystore_info(target_release, cand_pass)
            if a_list:
                matched_store_pass = cand_pass
                aliases = a_list
                fp = f_print
                break
            if k_err:
                last_keytool_err = k_err

        if aliases and matched_store_pass:
            alias_lower_map = {a.lower(): a for a in aliases}

            # Case A: Debug keystore placed in KEYSTORE_BASE64 (pass 'android', alias 'androiddebugkey')
            if (
                matched_store_pass == "android"
                and "androiddebugkey" in alias_lower_map
                and requested_alias.lower() != "upload"
            ):
                shutil.copyfile(target_release, "debug.keystore")
                os.remove(target_release)
                result["hasReleaseKeystore"] = "false"
                result["signingMode"] = "debug-from-keystore-secret"
                result["signingCertSha256"] = fp
                print(
                    f"[ci_injector] Adopted debug keystore (alias 'androiddebugkey') from {secret_label} "
                    f"into debug.keystore (SHA-256: {fp}) so every build matches your existing installed package."
                )
                return result

            # Case B: Genuine Release Keystore (with any alias and any valid password, including 'android')
            chosen_alias = (
                alias_lower_map.get(requested_alias.lower())
                if requested_alias
                else alias_lower_map.get("upload", aliases[0])
            ) or aliases[0]

            all_key_candidates: List[str] = []
            for kp in key_cands + [matched_store_pass] + store_cands + ["android"]:
                if kp and kp not in all_key_candidates:
                    all_key_candidates.append(kp)

            verified_key_pass, key_err = _verify_key_password_and_align_alias(
                target_release,
                matched_store_pass,
                chosen_alias,
                all_key_candidates,
            )
            if not verified_key_pass:
                raise RuntimeError(
                    f"{secret_label} ({ks_format}, alias '{chosen_alias}', SHA-256: {fp or 'N/A'}) opened with "
                    f"store password, but the private key could not be unlocked with KEY_PASSWORD or STORE_PASSWORD "
                    f"(set key secrets: {set_key_envs or 'NONE'}, keytool: {key_err}). "
                    f"Please set GitHub Secret KEY_PASSWORD to match the key password for alias '{chosen_alias}'."
                )

            result["hasReleaseKeystore"] = "true"
            result["signingMode"] = "release-secret"
            result["signingCertSha256"] = fp
            result["effectiveStorePassword"] = matched_store_pass
            result["effectiveKeyPassword"] = verified_key_pass
            print(f"[ci_injector] Verified release keystore ({ks_format}, alias: upload, SHA-256: {fp}).")
            return result

        # Case C: Secret keystore provided, but could NOT be opened -> FAIL FAST with clear, secret-safe diagnostics!
        if has_secret_input:
            peek_aliases, peek_fp = _peek_jks_public_metadata(target_release) if ks_format == "JKS" else ([], "")
            peek_note = ""
            if peek_aliases:
                peek_note = f" Public JKS header shows alias(es) {peek_aliases}, cert SHA-256: {peek_fp or 'N/A'}."

            if not set_store_envs and not set_key_envs:
                action_hint = (
                    "No STORE_PASSWORD or KEY_PASSWORD secret was set in GitHub Actions! "
                    "Go to GitHub Repo -> Settings -> Secrets and variables -> Actions and add "
                    "STORE_PASSWORD (and KEY_PASSWORD if different) matching this keystore."
                )
            else:
                action_hint = (
                    f"Password secret(s) present: {set_store_envs + set_key_envs}, but none unlocked this keystore. "
                    "Verify STORE_PASSWORD (and KEY_PASSWORD) in GitHub -> Settings -> Secrets and variables -> Actions "
                    "match the exact password used when creating this keystore."
                )

            raise RuntimeError(
                f"{secret_label} decoded to {len(raw_bytes)} bytes ({ks_format}), but could not be opened.{peek_note} "
                f"{action_hint} [keytool: {last_keytool_err}]"
            )

    # 4. Ensure debug.keystore exists even if repo didn't have debug.keystore.base64 (last resort)
    if not os.path.exists("debug.keystore"):
        print("[ci_injector] Warning: No GitHub Secret keystore and no debug.keystore.base64 found; generating temporary debug.keystore.")
        _run_keytool([
            "-genkey", "-v", "-keystore", "debug.keystore",
            "-storepass", "android", "-alias", "androiddebugkey", "-keypass", "android",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
            "-dname", "CN=Android Debug,O=Android,C=US",
        ])
        _, gen_fp, _ = _extract_keystore_info("debug.keystore", "android")
        result["signingMode"] = "debug-generated"
        result["signingCertSha256"] = gen_fp

    return result


def discover_and_adopt_gradle_modules(dry_run: bool = False) -> Dict[str, object]:
    """
    Scans repository root for Gradle sub-modules (any direct child folder containing
    build.gradle.kts or build.gradle), auto-registers any newly added module in
    settings.gradle.kts, and identifies the primary Android application module.
    """
    root = Path(".")
    discovered_modules: List[Dict[str, object]] = []
    app_module_name = "app"

    for entry in sorted(root.iterdir(), key=lambda p: p.name):
        if not entry.is_dir() or entry.name in IGNORED_DIRS or entry.name.startswith("."):
            continue
        gradle_kts = entry / "build.gradle.kts"
        gradle_groovy = entry / "build.gradle"
        build_file = gradle_kts if gradle_kts.exists() else (gradle_groovy if gradle_groovy.exists() else None)
        if not build_file:
            continue

        content = build_file.read_text(encoding="utf-8", errors="ignore")
        is_android_app = (
            "android.application" in content
            or "com.android.application" in content
            or "applicationId" in content
        )
        is_android_lib = (
            "android.library" in content
            or "com.android.library" in content
        )
        has_unit_tests = (entry / "src" / "test").exists()
        if is_android_app:
            app_module_name = entry.name

        discovered_modules.append(
            {
                "name": entry.name,
                "gradlePath": f":{entry.name}",
                "buildFile": str(build_file),
                "kind": "android-application" if is_android_app else ("android-library" if is_android_lib else "jvm-module"),
                "hasUnitTests": has_unit_tests,
            }
        )

    # Auto-register any newly created Gradle module missing from settings.gradle.kts
    settings_path = root / "settings.gradle.kts"
    auto_registered: List[str] = []
    if settings_path.exists():
        settings_text = settings_path.read_text(encoding="utf-8")
        missing = []
        for mod in discovered_modules:
            gpath = str(mod["gradlePath"])
            if not re.search(rf'include\([^)]*"{re.escape(gpath)}"', settings_text):
                missing.append(gpath)
        if missing:
            auto_registered.extend(missing)
            if not dry_run:
                addition = "\n" + "\n".join(f'include("{m}")' for m in missing) + "\n"
                settings_path.write_text(settings_text.rstrip() + addition, encoding="utf-8")
                print(f"[ci_injector] Auto-adopted Gradle module(s) into settings.gradle.kts: {', '.join(missing)}")
            else:
                print(f"[ci_injector] [dry-run] Would auto-adopt Gradle module(s): {', '.join(missing)}")

    test_tasks = [
        f"{m['gradlePath']}:testDebugUnitTest"
        if m["kind"] in ("android-application", "android-library")
        else f"{m['gradlePath']}:test"
        for m in discovered_modules
        if m["hasUnitTests"]
    ]

    print(
        f"[ci_injector] Discovered {len(discovered_modules)} Gradle module(s): "
        f"{', '.join(str(m['gradlePath']) for m in discovered_modules)} (primary app: :{app_module_name})"
    )

    return {
        "appModuleDir": app_module_name,
        "appGradlePath": f":{app_module_name}",
        "modules": discovered_modules,
        "autoRegisteredModules": auto_registered,
        "testTasks": test_tasks,
    }


def discover_bsa_feature_modules(app_dir: str) -> Dict[str, List[str]]:
    """
    Auto-discovers every built BSA-YourView Kotlin feature module across engine, service,
    data, model, ui/screens, and ui/components so build manifests & release notes stay in sync.
    """
    bsa_root = Path(app_dir) / "src" / "main" / "java" / "com" / "example" / "bsa"
    categories = {
        "engines": bsa_root / "engine",
        "services": bsa_root / "service",
        "data": bsa_root / "data",
        "models": bsa_root / "model",
        "screens": bsa_root / "ui" / "screens",
        "components": bsa_root / "ui" / "components",
    }

    inventory: Dict[str, List[str]] = {}
    total_count = 0
    for cat, folder in categories.items():
        if folder.exists():
            items = sorted(p.stem for p in folder.glob("*.kt"))
        else:
            items = []
        inventory[cat] = items
        total_count += len(items)

    # Also capture top-level app files (BankAnalysierApp, BsaViewModel)
    top_level = []
    for candidate in (bsa_root / "BankAnalysierApp.kt", bsa_root / "ui" / "BsaViewModel.kt"):
        if candidate.exists():
            top_level.append(candidate.stem)
            total_count += 1
    inventory["appShell"] = top_level

    print(
        f"[ci_injector] Auto-adopted {total_count} BSA-YourView Kotlin modules "
        f"({len(inventory['engines'])} engines, {len(inventory['services'])} services, "
        f"{len(inventory['screens'])} screens, {len(inventory['components'])} component packs)."
    )
    return inventory


def verify_critical_android_assets(app_dir: str) -> Dict[str, bool]:
    """Verifies essential Android packaging resources exist before CI builds the APK."""
    checks = {
        "androidManifest": os.path.exists(os.path.join(app_dir, "src", "main", "AndroidManifest.xml")),
        "stringsXml": os.path.exists(os.path.join(app_dir, "src", "main", "res", "values", "strings.xml")),
        "fileProviderPaths": os.path.exists(os.path.join(app_dir, "src", "main", "res", "xml", "file_paths.xml")),
        "adaptiveLauncherIcon": os.path.exists(os.path.join(app_dir, "src", "main", "res", "mipmap-anydpi-v26", "ic_launcher.xml")),
    }
    missing = [k for k, ok in checks.items() if not ok]
    if missing:
        print(f"[ci_injector] Warning: Missing recommended Android asset(s): {', '.join(missing)}")
    else:
        print("[ci_injector] Verified critical Android resources (Manifest, Strings, FileProvider, Adaptive Icon).")
    return checks


def read_and_sync_version_and_repo(
    app_dir: str,
    version_name_override: str = "",
    auto_bump_code: bool = False,
    dry_run: bool = False,
) -> Tuple[str, int, str]:
    """
    Reads versionName/versionCode from app/build.gradle.kts, optionally applies CI
    version overrides (never touching signingConfigs/buildTypes), and injects both
    GITHUB_UPDATE_REPO and UPDATE_MANIFEST_URL so InAppUpdaterService works out-of-the-box.
    """
    gradle_path = os.path.join(app_dir, "build.gradle.kts")
    if not os.path.exists(gradle_path):
        return ("1.0.0", 1, "")

    with open(gradle_path, "r", encoding="utf-8") as f:
        original = f.read()
    content = original

    v_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    v_code_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    version_name = v_name_match.group(1) if v_name_match else "1.0.0"
    version_code = int(v_code_match.group(1)) if v_code_match else 1

    # Apply explicit versionName override if provided via workflow_dispatch or CLI
    clean_override = version_name_override.strip().lstrip("vV")
    if clean_override:
        version_name = clean_override
        content, _ = re.subn(
            r'(versionName\s*=\s*")[^"]+(")',
            rf'\g<1>{version_name}\g<2>',
            content,
            count=1,
        )

    # Auto-bump versionCode using GITHUB_RUN_NUMBER in CI so Android always permits in-place upgrades
    run_number_raw = os.environ.get("GITHUB_RUN_NUMBER", "").strip()
    if auto_bump_code and run_number_raw.isdigit():
        run_num = int(run_number_raw)
        if run_num > version_code:
            version_code = run_num
            content, _ = re.subn(
                r'(versionCode\s*=\s*)\d+',
                rf'\g<1>{version_code}',
                content,
                count=1,
            )

    # Inject GITHUB_UPDATE_REPO and UPDATE_MANIFEST_URL when running in GitHub Actions
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    if repo and "/" in repo:
        manifest_url = f"https://github.com/{repo}/releases/latest/download/update-manifest.json"
        content, repo_cnt = re.subn(
            r'(buildConfigField\("String",\s*"GITHUB_UPDATE_REPO",\s*)"(?:[^"\\]|\\.)*"(\))',
            lambda m: f'{m.group(1)}"\\"{repo}\\""{m.group(2)}',
            content,
            count=1,
        )
        content, url_cnt = re.subn(
            r'(buildConfigField\("String",\s*"UPDATE_MANIFEST_URL",\s*)"(?:[^"\\]|\\.)*"(\))',
            lambda m: f'{m.group(1)}"\\"{manifest_url}\\""{m.group(2)}',
            content,
            count=1,
        )
        if repo_cnt > 0 or url_cnt > 0:
            print(f"[ci_injector] Configured in-app updater BuildConfig for {repo}.")

    if content != original:
        if not dry_run:
            with open(gradle_path, "w", encoding="utf-8") as f:
                f.write(content)
            print(f"[ci_injector] Updated {gradle_path} (v{version_name}, code {version_code}).")
        else:
            print(f"[ci_injector] [dry-run] Would update {gradle_path} (v{version_name}, code {version_code}).")
    else:
        print(f"[ci_injector] App version: v{version_name} (code {version_code})")

    return (version_name, version_code, repo)


def build_feature_highlights(inventory: Dict[str, List[str]]) -> List[str]:
    """Maps auto-discovered Kotlin modules to concise human-readable release note highlights."""
    all_mods = set()
    for items in inventory.values():
        all_mods.update(items)

    highlights = []
    mapping = [
        ("PortfolioRiskEngine", "Multi-Account Statement Queue & Consolidated Portfolio Risk view with cross-bank contra transfer elimination"),
        ("PdfSummaryReportGenerator", "Branded 2-page client-ready BSA-YourView PDF Summary export (.pdf) & ShareSheet support"),
        ("EligibilityCalculatorService", "4-Segment Indian Banking Eligibility Calculator (Auto Loan, Business Loan, Working Capital, Home Loan)"),
        ("ExcelReportGenerator", "18-Tab Institutional Underwriting Excel workbook (.xlsx) with live formulas & deep analytics"),
        ("DeepUnderwritingAnalytics", "Internal Credits, Cheque Bounces, EMI/NACH Returns, Cash Mix & Counterparty Concentration tracking"),
        ("UnderwriterCamModels", "Underwriter 360 CAM Workbench (CIBIL/Bureau, GST/ITR DSCR, Drawing Power & Collateral LTV)"),
        ("PortfolioAndTooltipComponents", "Interactive tap-to-explain metric & eligibility formula tooltips across the dashboard"),
        ("YourViewDesignComponents", "Unified YourView branded UI components with responsive adaptive layouts"),
        ("FraudScreeningService", "10-Point Forensic Fraud & Behavioural Screening engine with weighted Risk Score"),
        ("InAppUpdaterService", "100% In-App SHA-256 verified APK auto-updater"),
    ]
    for module_name, desc in mapping:
        if module_name in all_mods:
            highlights.append(desc)
    return highlights


def write_github_env_and_outputs(
    gradle_info: Dict[str, object],
    inventory: Dict[str, List[str]],
    version_name: str,
    version_code: int,
    manifest_path: str,
    signing_info: Optional[Dict[str, str]] = None,
) -> None:
    app_module = str(gradle_info["appGradlePath"])
    app_dir = str(gradle_info["appModuleDir"])
    test_tasks = " ".join(gradle_info["testTasks"])  # type: ignore[arg-type]
    total_bsa_modules = sum(len(v) for v in inventory.values())
    s_info = signing_info or {}

    pairs = {
        "APP_MODULE": app_module,
        "APP_MODULE_DIR": app_dir,
        "GRADLE_TEST_TASKS": test_tasks,
        "VERSION_NAME": version_name,
        "VERSION_CODE": str(version_code),
        "BSA_MODULE_COUNT": str(total_bsa_modules),
        "MODULE_MANIFEST_PATH": manifest_path,
        "HAS_RELEASE_KEYSTORE": s_info.get("hasReleaseKeystore", "false"),
        "SIGNING_MODE": s_info.get("signingMode", "debug-repo"),
        "SIGNING_CERT_SHA256": s_info.get("signingCertSha256", ""),
    }
    if s_info.get("effectiveStorePassword"):
        pairs["EFFECTIVE_STORE_PASSWORD"] = s_info["effectiveStorePassword"]
    if s_info.get("effectiveKeyPassword"):
        pairs["EFFECTIVE_KEY_PASSWORD"] = s_info["effectiveKeyPassword"]

    gh_env = os.environ.get("GITHUB_ENV", "").strip()
    if gh_env:
        with open(gh_env, "a", encoding="utf-8") as f:
            for k, v in pairs.items():
                f.write(f"{k}={v}\n")

    gh_out = os.environ.get("GITHUB_OUTPUT", "").strip()
    if gh_out:
        with open(gh_out, "a", encoding="utf-8") as f:
            for k, v in pairs.items():
                if "PASSWORD" in k:
                    continue
                f.write(f"{k.lower()}={v}\n")


def generate_update_manifest_and_notes(
    apk_path: str,
    version_name: str,
    version_code: int,
    repo: str,
    inventory: Dict[str, List[str]],
    manifest_out: str = "update-manifest.json",
    notes_out: str = "release-notes.md",
) -> None:
    """
    Computes SHA-256 & file size of the built APK and emits:
    - update-manifest.json (strictly matching InAppUpdaterService.parseManifestJson)
    - release-notes.md (auto-populated from discovered BSA-YourView modules)
    """
    if not os.path.isfile(apk_path):
        raise FileNotFoundError(f"APK not found at {apk_path}")

    sha256_hash = hashlib.sha256()
    with open(apk_path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            sha256_hash.update(chunk)
    digest_hex = sha256_hash.hexdigest()
    file_size = os.path.getsize(apk_path)
    apk_filename = os.path.basename(apk_path)
    apk_signing_cert_sha256 = (
        _extract_apk_signing_cert_sha256(apk_path)
        or os.environ.get("SIGNING_CERT_SHA256", "").strip()
    )

    tag = f"v{version_name}"
    apk_url = (
        f"https://github.com/{repo}/releases/download/{tag}/{apk_filename}"
        if repo and "/" in repo
        else ""
    )
    published_utc = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    highlights = build_feature_highlights(inventory)
    short_notes = " | ".join(highlights[:4]) if highlights else f"BSA-YourView release {tag}"

    update_manifest = {
        "versionCode": version_code,
        "versionName": version_name,
        "apkFileName": apk_filename,
        "apkUrl": apk_url,
        "sha256": digest_hex,
        "signingCertSha256": apk_signing_cert_sha256,
        "fileSizeBytes": file_size,
        "releaseNotes": short_notes,
        "publishedAtUtc": published_utc,
        "modulesAdopted": {
            "engines": len(inventory.get("engines", [])),
            "services": len(inventory.get("services", [])),
            "screens": len(inventory.get("screens", [])),
            "components": len(inventory.get("components", [])),
        },
    }
    with open(manifest_out, "w", encoding="utf-8") as f:
        json.dump(update_manifest, f, indent=2)
        f.write("\n")

    md_lines = [
        f"## BSA-YourView {tag} (Build {version_code})",
        "",
        "### Auto-Adopted Modules & Capabilities",
    ]
    for item in highlights:
        md_lines.append(f"- {item}")
    md_lines.extend(
        [
            "",
            "### Package Verification",
            f"- **APK Artifact**: `{apk_filename}` ({file_size / (1024 * 1024):.2f} MB)",
            f"- **APK SHA-256**: `{digest_hex}`",
            f"- **Signing Cert SHA-256**: `{apk_signing_cert_sha256 or 'N/A'}`",
            f"- **Built At (UTC)**: `{published_utc}`",
            f"- **Detected Kotlin Modules**: {sum(len(v) for v in inventory.values())} files across `{', '.join(k for k, v in inventory.items() if v)}`",
            "",
            "> Installed BSA-YourView apps will automatically detect this release via `update-manifest.json`, verify the SHA-256 checksum, install in-app, and reopen automatically.",
        ]
    )
    with open(notes_out, "w", encoding="utf-8") as f:
        f.write("\n".join(md_lines) + "\n")

    print(
        f"[ci_injector] Generated {manifest_out} (APK SHA-256: {digest_hex[:12]}..., "
        f"Signing Cert: {apk_signing_cert_sha256[:17] if apk_signing_cert_sha256 else 'N/A'}) and {notes_out}."
    )


def parse_args(argv: List[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="BSA-YourView CI/CD Auto-Adoption Injector")
    parser.add_argument("--ci", action="store_true", help="Run full CI preparation with automatic versionCode bump")
    parser.add_argument("--dry-run", action="store_true", help="Inspect modules without modifying Gradle/settings files")
    parser.add_argument("--version-name", default="", help="Override versionName (e.g. 1.2.0)")
    parser.add_argument("--auto-bump-code", action="store_true", help="Sync versionCode with GITHUB_RUN_NUMBER when higher")
    parser.add_argument("--generate-update-manifest", action="store_true", help="Generate update-manifest.json & release-notes.md for built APK")
    parser.add_argument("--apk", default="", help="Path to built APK when using --generate-update-manifest")
    parser.add_argument("--manifest-out", default="update-manifest.json", help="Output path for update-manifest.json")
    parser.add_argument("--notes-out", default="release-notes.md", help="Output path for release-notes.md")
    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv if argv is not None else sys.argv[1:])

    gradle_info = discover_and_adopt_gradle_modules(dry_run=args.dry_run)
    app_dir = str(gradle_info["appModuleDir"])
    inventory = discover_bsa_feature_modules(app_dir)

    version_override = args.version_name or os.environ.get("INPUT_VERSION_NAME", "").strip()
    auto_bump = args.auto_bump_code or args.ci

    if args.generate_update_manifest:
        version_name, version_code, repo = read_and_sync_version_and_repo(
            app_dir=app_dir,
            version_name_override=version_override,
            auto_bump_code=False,
            dry_run=True,
        )
        generate_update_manifest_and_notes(
            apk_path=args.apk,
            version_name=version_name,
            version_code=version_code,
            repo=repo,
            inventory=inventory,
            manifest_out=args.manifest_out,
            notes_out=args.notes_out,
        )
        return 0

    signing_info: Dict[str, str] = {}
    if not args.dry_run:
        ensure_env_file()
        inject_google_services(app_dir)
        signing_info = configure_ci_signing_keystores(dry_run=False)

    checks = verify_critical_android_assets(app_dir)
    version_name, version_code, repo = read_and_sync_version_and_repo(
        app_dir=app_dir,
        version_name_override=version_override,
        auto_bump_code=auto_bump,
        dry_run=args.dry_run,
    )

    module_manifest_path = "build-module-manifest.json"
    if not args.dry_run:
        manifest_payload = {
            "appName": "BSA-YourView",
            "versionName": version_name,
            "versionCode": version_code,
            "repository": repo,
            "signingMode": signing_info.get("signingMode", ""),
            "signingCertSha256": signing_info.get("signingCertSha256", ""),
            "gradle": gradle_info,
            "bsaModules": inventory,
            "resourceChecks": checks,
            "highlights": build_feature_highlights(inventory),
        }
        with open(module_manifest_path, "w", encoding="utf-8") as f:
            json.dump(manifest_payload, f, indent=2)
            f.write("\n")
        print(f"[ci_injector] Wrote {module_manifest_path}.")

    write_github_env_and_outputs(
        gradle_info=gradle_info,
        inventory=inventory,
        version_name=version_name,
        version_code=version_code,
        manifest_path=module_manifest_path,
        signing_info=signing_info,
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
