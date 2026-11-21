#!/usr/bin/env python3
import base64
import json
import os
import re
import sys


def decode_base64_to_file(b64_value: str, out_path: str, label: str) -> bool:
    cleaned = re.sub(r"[^A-Za-z0-9+/=]", "", b64_value or "")
    if not cleaned:
        return False
    # Fix missing base64 padding if truncated at '='
    rem = len(cleaned) % 4
    if rem:
        cleaned += "=" * (4 - rem)
    try:
        raw = base64.b64decode(cleaned)
        parent = os.path.dirname(out_path)
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(out_path, "wb") as f:
            f.write(raw)
        print(f"[ci_injector] Wrote {out_path} ({len(raw)} bytes) from {label}.")
        return True
    except Exception as exc:
        print(f"[ci_injector] Warning: Failed to decode {label}: {exc}")
        return False


def inject_google_services() -> None:
    target = os.path.join("app", "google-services.json")
    b64 = os.environ.get("GOOGLE_SERVICES_JSON_B64", "").strip()
    if b64 and decode_base64_to_file(b64, target, "GOOGLE_SERVICES_JSON_B64"):
        return
    if os.path.exists(target):
        print(f"[ci_injector] Using existing {target}.")
    else:
        print(f"[ci_injector] Info: {target} not found and GOOGLE_SERVICES_JSON_B64 not set; skipping.")


def inject_release_keystore() -> None:
    target = "my-upload-key.jks"
    if os.path.exists(target) and os.path.getsize(target) > 0:
        print(f"[ci_injector] Using existing {target} in repository root.")
        return

    b64 = os.environ.get("KEYSTORE_BASE64", "").strip()
    if b64:
        if decode_base64_to_file(b64, target, "KEYSTORE_BASE64"):
            return
        print("[ci_injector] Warning: KEYSTORE_BASE64 was invalid; removing partial file.")
        if os.path.exists(target):
            os.remove(target)


def sync_version_info() -> None:
    gradle_path = os.path.join("app", "build.gradle.kts")
    meta_path = "metadata.json"
    if not os.path.exists(gradle_path):
        return

    with open(gradle_path, "r", encoding="utf-8") as f:
        content = f.read()

    v_name_match = re.search(r'versionName\s*=\s*"([^"]+)"', content)
    v_code_match = re.search(r'versionCode\s*=\s*(\d+)', content)
    version_name = v_name_match.group(1) if v_name_match else "1.0.0"
    version_code = int(v_code_match.group(1)) if v_code_match else 1

    if os.path.exists(meta_path):
        try:
            with open(meta_path, "r", encoding="utf-8") as f:
                meta = json.load(f)
            if isinstance(meta, dict):
                meta.setdefault("versionName", version_name)
                meta.setdefault("versionCode", version_code)
                with open(meta_path, "w", encoding="utf-8") as f:
                    json.dump(meta, f, indent=2)
                    f.write("\n")
        except Exception as exc:
            print(f"[ci_injector] Info: Skipped metadata.json sync ({exc}).")

    print(f"[ci_injector] App version: {version_name} (code {version_code})")


def main() -> int:
    inject_google_services()
    inject_release_keystore()
    sync_version_info()
    return 0


if __name__ == "__main__":
    sys.exit(main())
