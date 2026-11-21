#!/usr/bin/env python3
"""
BSA-YourView (YourView | yourview.in) — GitHub Actions CI Injector & Update Manifest Generator

Responsibilities:
  1. `inject`: Patches versionCode, versionName, GITHUB_UPDATE_REPO, and UPDATE_MANIFEST_URL
     inside app/build.gradle.kts before Gradle compilation, and optionally restores
     app/google-services.json from base64 CI secret without modifying signingConfigs.
  2. `manifest`: Computes the SHA-256 digest and byte size of the compiled APK and emits
     `update-manifest.json` consumed directly by the BSA-YourView in-app auto-updater.
"""

from __future__ import annotations

import argparse
import base64
import datetime
import hashlib
import json
import os
import re
import sys
from pathlib import Path

ROOT_DIR = Path(__file__).resolve().parent
APP_GRADLE = ROOT_DIR / "app" / "build.gradle.kts"
GOOGLE_SERVICES_PATH = ROOT_DIR / "app" / "google-services.json"


def _escape_kotlin_string(value: str) -> str:
    return value.replace("\\", "\\\\").replace('"', '\\"')


def _replace_required(content: str, pattern: str, replacement_fn, label: str, gradle_path: Path) -> str:
    updated, count = re.subn(pattern, replacement_fn, content, count=1, flags=re.MULTILINE)
    if count == 0:
        raise RuntimeError(f"ci_injector.py could not locate {label} in {gradle_path}")
    return updated


def cmd_inject(args: argparse.Namespace) -> None:
    gradle_path = Path(args.gradle_file).resolve() if getattr(args, "gradle_file", None) else APP_GRADLE
    if not gradle_path.exists():
        raise FileNotFoundError(f"Missing Gradle file: {gradle_path}")

    repo = (args.repo or os.environ.get("GITHUB_REPOSITORY", "")).strip()
    run_number = int(os.environ.get("GITHUB_RUN_NUMBER", "1") or "1")
    version_code = int(args.version_code) if args.version_code else max(1, run_number)

    ref_name = os.environ.get("GITHUB_REF_NAME", "").strip()
    if args.version_name:
        version_name = args.version_name.strip().lstrip("vV")
    elif ref_name.startswith(("v", "V")) and re.match(r"^[vV]?\d+\.\d+", ref_name):
        version_name = ref_name.lstrip("vV")
    else:
        version_name = f"1.0.{version_code}"

    manifest_url = (
        args.manifest_url.strip()
        if args.manifest_url
        else (f"https://github.com/{repo}/releases/latest/download/update-manifest.json" if repo else "")
    )

    escaped_ver = _escape_kotlin_string(version_name)
    escaped_repo = _escape_kotlin_string(repo)
    escaped_manifest = _escape_kotlin_string(manifest_url)

    content = gradle_path.read_text(encoding="utf-8")
    content = _replace_required(
        content,
        r"^(\s*versionCode\s*=\s*)\d+",
        lambda m: f"{m.group(1)}{version_code}",
        "versionCode",
        gradle_path,
    )
    content = _replace_required(
        content,
        r'^(\s*versionName\s*=\s*)"[^"]*"',
        lambda m: f'{m.group(1)}"{escaped_ver}"',
        "versionName",
        gradle_path,
    )
    content = _replace_required(
        content,
        r'^(\s*buildConfigField\("String",\s*"GITHUB_UPDATE_REPO",\s*)(.+?)(\)\s*)$',
        lambda m: f'{m.group(1)}"\\"{escaped_repo}\\""{m.group(3)}',
        "GITHUB_UPDATE_REPO",
        gradle_path,
    )
    content = _replace_required(
        content,
        r'^(\s*buildConfigField\("String",\s*"UPDATE_MANIFEST_URL",\s*)(.+?)(\)\s*)$',
        lambda m: f'{m.group(1)}"\\"{escaped_manifest}\\""{m.group(3)}',
        "UPDATE_MANIFEST_URL",
        gradle_path,
    )
    gradle_path.write_text(content, encoding="utf-8")

    # Decode Firebase google-services.json from CI secret (strips accidental whitespace/newlines)
    gs_b64 = "".join(os.environ.get("GOOGLE_SERVICES_JSON_B64", "").split())
    if gs_b64 and not GOOGLE_SERVICES_PATH.exists():
        GOOGLE_SERVICES_PATH.parent.mkdir(parents=True, exist_ok=True)
        GOOGLE_SERVICES_PATH.write_bytes(base64.b64decode(gs_b64))
        print(f"[ci_injector] Restored {GOOGLE_SERVICES_PATH} from GOOGLE_SERVICES_JSON_B64 secret.")
    elif not GOOGLE_SERVICES_PATH.exists():
        print(f"[ci_injector] Warning: {GOOGLE_SERVICES_PATH} not found and GOOGLE_SERVICES_JSON_B64 is empty.")

    print(
        f"[ci_injector] Injected versionCode={version_code}, versionName={version_name}, "
        f"repo={repo or '(none)'}, manifestUrl={manifest_url or '(none)'}"
    )

    # Export resolved values to GITHUB_OUTPUT when running inside GitHub Actions
    gh_output = os.environ.get("GITHUB_OUTPUT", "").strip()
    if gh_output:
        with open(gh_output, "a", encoding="utf-8") as out:
            out.write(f"version_code={version_code}\n")
            out.write(f"version_name={version_name}\n")
            out.write(f"tag_name=v{version_name}\n")
            out.write(f"apk_filename=BSA-YourView-v{version_name}.apk\n")


def compute_sha256(file_path: Path) -> str:
    h = hashlib.sha256()
    with file_path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def cmd_manifest(args: argparse.Namespace) -> None:
    apk_path = Path(args.apk).resolve()
    if not apk_path.exists():
        raise FileNotFoundError(f"Built APK not found at: {apk_path}")

    repo = (args.repo or os.environ.get("GITHUB_REPOSITORY", "")).strip()
    version_code = int(args.version_code)
    version_name = args.version_name.strip().lstrip("vV")
    tag_name = args.tag or f"v{version_name}"
    apk_filename = args.apk_filename or apk_path.name

    apk_url = (
        args.apk_url.strip()
        if args.apk_url
        else f"https://github.com/{repo}/releases/download/{tag_name}/{apk_filename}"
    )
    sha256_hex = compute_sha256(apk_path)
    size_bytes = apk_path.stat().st_size

    release_notes = (args.release_notes or "").strip() or (
        f"BSA-YourView v{version_name} (Build {version_code}) — "
        "Offline & Online Hybrid Indian Bank Statement Analyzer updates and improvements."
    )

    manifest = {
        "appName": "BSA-YourView",
        "packageName": "in.bsa.yourview",
        "versionCode": version_code,
        "versionName": version_name,
        "tagName": tag_name,
        "apkFileName": apk_filename,
        "apkUrl": apk_url,
        "sha256": sha256_hex,
        "fileSizeBytes": size_bytes,
        "releaseNotes": release_notes,
        "publishedAtUtc": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }

    out_path = Path(args.output).resolve()
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"[ci_injector] Wrote {out_path} (v{version_name} / code {version_code}, sha256={sha256_hex[:12]}...)")


def main() -> int:
    parser = argparse.ArgumentParser(description="BSA-YourView CI version injector & update manifest tool")
    sub = parser.add_subparsers(dest="command", required=True)

    p_inj = sub.add_parser("inject", help="Inject build version & GitHub update endpoints into app/build.gradle.kts")
    p_inj.add_argument("--version-code", type=int, default=None)
    p_inj.add_argument("--version-name", type=str, default=None)
    p_inj.add_argument("--repo", type=str, default=None, help="GitHub owner/repo")
    p_inj.add_argument("--manifest-url", type=str, default=None)
    p_inj.add_argument("--gradle-file", type=str, default=None, help="Optional override path to app/build.gradle.kts")
    p_inj.set_defaults(func=cmd_inject)

    p_man = sub.add_parser("manifest", help="Generate update-manifest.json with APK SHA-256 digest")
    p_man.add_argument("--apk", required=True, help="Path to compiled APK file")
    p_man.add_argument("--version-code", type=int, required=True)
    p_man.add_argument("--version-name", type=str, required=True)
    p_man.add_argument("--tag", type=str, default=None)
    p_man.add_argument("--repo", type=str, default=None)
    p_man.add_argument("--apk-filename", type=str, default=None)
    p_man.add_argument("--apk-url", type=str, default=None)
    p_man.add_argument("--release-notes", type=str, default="")
    p_man.add_argument("--output", default="dist/update-manifest.json")
    p_man.set_defaults(func=cmd_manifest)

    args = parser.parse_args()
    args.func(args)
    return 0


if __name__ == "__main__":
    sys.exit(main())
