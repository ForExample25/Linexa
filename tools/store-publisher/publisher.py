#!/usr/bin/env python3
"""Linexa store-publisher helper.

What this tool does:
  * verifies the public APK against the recorded size, hash, package, SDK,
    signature, and ABI facts;
  * lints the Fastlane metadata and prepared store assets;
  * checks the public GitHub Release asset through the read-only API;
  * builds a manual submission packet for a store;
  * writes an honest status report.

What it does not do:
  * create accounts, solve CAPTCHAs, enter OTPs, or handle identity documents;
  * store passwords, keystores, or tokens;
  * submit to a store console without an authenticated, approved API;
  * claim that a store listing is published.

Only the Python standard library is required. Android build-tools are used when
available for APK verification.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

TOOL_ROOT = Path(__file__).resolve().parent
REPO_ROOT = TOOL_ROOT.parents[1]
CONFIG_DIR = TOOL_ROOT / "config"

sys.path.insert(0, str(TOOL_ROOT))

from adapters import GitHubReleaseAdapter, build_adapters  # noqa: E402
from adapters.base import ManualActionRequired  # noqa: E402

STATUS_ORDER = [
    "PUBLISHED",
    "UNDER_REVIEW",
    "SUBMITTED",
    "UPLOADED",
    "APPROVED",
    "DRAFT_CREATED",
    "ACCOUNT_CREATED",
    "READY",
    "WAITING_FOR_USER",
    "INVESTIGATING",
    "NOT_STARTED",
    "PENDING",
    "BLOCKED",
    "NOT_ELIGIBLE",
    "REJECTED",
]

SECRET_PATTERNS = [
    ("private key", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{20,}\b")),
    ("Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("AWS access key", re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("literal store password", re.compile(r"(?<![\w.])storePassword\s*=\s*[^\s<]+")),
]


def load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def config() -> tuple[dict, dict, dict]:
    return (
        load_json(CONFIG_DIR / "stores.json"),
        load_json(CONFIG_DIR / "limits.json"),
        load_json(CONFIG_DIR / "expected-artifact.json"),
    )


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def png_size(path: Path) -> tuple[int, int]:
    with path.open("rb") as handle:
        header = handle.read(24)
    if len(header) < 24 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise ValueError(f"{path} is not a PNG")
    if header[12:16] != b"IHDR":
        raise ValueError(f"{path} has no IHDR chunk")
    return struct.unpack(">II", header[16:24])


def run(command: list[str]) -> tuple[int, str, str]:
    try:
        completed = subprocess.run(
            command,
            cwd=str(REPO_ROOT),
            capture_output=True,
            text=True,
            timeout=180,
        )
        return completed.returncode, completed.stdout, completed.stderr
    except FileNotFoundError:
        return 127, "", f"{command[0]} not found"
    except subprocess.TimeoutExpired:
        return 124, "", f"{command[0]} timed out"


def find_android_tool(name: str) -> str | None:
    found = shutil.which(name)
    if found:
        return found
    env_roots = [
        os.environ.get("ANDROID_HOME"),
        os.environ.get("ANDROID_SDK_ROOT"),
    ]
    versions = ["34.0.0", "35.0.0", "36.0.0"]
    for root in env_roots:
        if not root:
            continue
        for version in versions:
            candidate = Path(root) / "build-tools" / version / name
            if candidate.is_file():
                return str(candidate)
    return None


def parse_apksigner(output: str) -> dict:
    schemes = {}
    for match in re.finditer(r"Verified using (v\d) scheme[^:]*:\s*(true|false)", output, re.I):
        schemes[match.group(1).lower()] = match.group(2).lower() == "true"
    cert = re.search(
        r"Signer #1 certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", output
    )
    dn = re.search(r"Signer #1 certificate DN:\s*(.+)", output)
    signers = re.search(r"Number of signers:\s*(\d+)", output)
    return {
        "verified": "Verifies" in output,
        "schemes": schemes,
        "certificate_sha256": cert.group(1).upper() if cert else None,
        "certificate_dn": dn.group(1).strip() if dn else None,
        "signer_count": int(signers.group(1)) if signers else None,
    }


def parse_badging(output: str) -> dict:
    result: dict[str, Any] = {}
    package = re.search(
        r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
        output,
    )
    if package:
        result["package"] = package.group(1)
        result["versionCode"] = int(package.group(2))
        result["versionName"] = package.group(3)
    min_sdk = re.search(r"sdkVersion:'(\d+)'", output)
    target_sdk = re.search(r"targetSdkVersion:'(\d+)'", output)
    if min_sdk:
        result["minSdk"] = int(min_sdk.group(1))
    if target_sdk:
        result["targetSdk"] = int(target_sdk.group(1))
    native = re.search(r"native-code: (.+)", output)
    if native:
        result["abis"] = re.findall(r"'([^']+)'", native.group(1))
    result["debuggable"] = "application-debuggable" in output
    result["testOnly"] = "testOnly='true'" in output
    return result


def verify_apk(apk: Path, expected: dict, require_tools: bool = False) -> dict:
    checks: dict[str, Any] = {}
    errors: list[str] = []
    warnings: list[str] = []

    if not apk.is_file():
        return {
            "ok": False,
            "errors": [f"APK not found: {apk}"],
            "warnings": [],
            "checks": {},
        }

    size = apk.stat().st_size
    digest = sha256_file(apk)
    checks["size"] = {
        "expected": expected["size"],
        "actual": size,
        "ok": size == expected["size"],
    }
    checks["sha256"] = {
        "expected": expected["sha256"],
        "actual": digest,
        "ok": digest.lower() == expected["sha256"].lower(),
    }
    if not checks["size"]["ok"]:
        errors.append(f"Size mismatch: expected {expected['size']}, got {size}")
    if not checks["sha256"]["ok"]:
        errors.append(
            f"SHA-256 mismatch: expected {expected['sha256']}, got {digest}"
        )

    apksigner = find_android_tool("apksigner")
    if apksigner:
        code, out, err = run([apksigner, "verify", "--print-certs", "--verbose", str(apk)])
        info = parse_apksigner(out + "\n" + err)
        schemes = info["schemes"]
        cert_ok = (
            (info["certificate_sha256"] or "").upper()
            == expected["signerCertificateSha256"].upper()
        )
        checks["signature"] = {
            "ok": code == 0 and info["verified"] and cert_ok and schemes.get("v2", False),
            "returncode": code,
            "verified": info["verified"],
            "schemes": schemes,
            "certificate_sha256": info["certificate_sha256"],
            "certificate_dn": info["certificate_dn"],
            "signer_count": info["signer_count"],
            "certificate_matches": cert_ok,
        }
        if code != 0 or not info["verified"]:
            errors.append(f"apksigner verification failed (exit {code})")
        if not cert_ok:
            errors.append("Signer certificate SHA-256 does not match the manifest")
        if not schemes.get("v2", False):
            errors.append("APK Signature Scheme v2 was not verified")
        if info["signer_count"] not in (None, 1):
            errors.append(f"Expected one signer, got {info['signer_count']}")
    else:
        warnings.append("apksigner not found; signature was not re-verified")
        checks["signature"] = {"ok": None, "reason": "apksigner not found"}
        if require_tools:
            errors.append("apksigner is required but was not found")

    aapt2 = find_android_tool("aapt2")
    if aapt2:
        code, out, err = run([aapt2, "dump", "badging", str(apk)])
        badging = parse_badging(out + "\n" + err)
        expected_pairs = {
            "package": expected["package"],
            "versionName": expected["versionName"],
            "versionCode": expected["versionCode"],
            "minSdk": expected["minSdk"],
            "targetSdk": expected["targetSdk"],
            "abis": expected["abis"],
            "debuggable": expected["debuggable"],
            "testOnly": expected["testOnly"],
        }
        mismatches = {
            key: {"expected": value, "actual": badging.get(key)}
            for key, value in expected_pairs.items()
            if badging.get(key) != value
        }
        checks["badging"] = {
            "ok": code == 0 and not mismatches,
            "returncode": code,
            "actual": badging,
            "mismatches": mismatches,
        }
        if code != 0:
            errors.append(f"aapt2 dump badging failed (exit {code})")
        for key, mismatch in mismatches.items():
            errors.append(
                f"{key} mismatch: expected {mismatch['expected']!r}, "
                f"got {mismatch['actual']!r}"
            )
    else:
        warnings.append("aapt2 not found; package/SDK/ABI facts were not re-read")
        checks["badging"] = {"ok": None, "reason": "aapt2 not found"}
        if require_tools:
            errors.append("aapt2 is required but was not found")

    zipalign = find_android_tool("zipalign")
    if zipalign:
        code, _out, err = run([zipalign, "-c", "-v", "4", str(apk)])
        checks["zipalign"] = {"ok": code == 0, "returncode": code}
        if code != 0:
            errors.append(f"zipalign check failed: {err.strip()[:400]}")
    else:
        warnings.append("zipalign not found; alignment was not re-verified")
        checks["zipalign"] = {"ok": None, "reason": "zipalign not found"}
        if require_tools:
            errors.append("zipalign is required but was not found")

    return {
        "ok": not errors,
        "artifact": str(apk),
        "size": size,
        "sha256": digest,
        "checks": checks,
        "errors": errors,
        "warnings": warnings,
    }


def sz(path: Path) -> str:
    return f"{path.stat().st_size:,} bytes"


def lint(root: Path, strict: bool = False) -> dict:
    errors: list[str] = []
    warnings: list[str] = []
    info: list[str] = []
    limits = load_json(CONFIG_DIR / "limits.json")["fastlane"]
    stores, _limits, expected = config()

    def check_text(path: Path, limit: int, label: str) -> None:
        if not path.is_file():
            errors.append(f"{label}: missing {path.relative_to(root)}")
            return
        text = path.read_text(encoding="utf-8").strip()
        if not text:
            errors.append(f"{label}: empty")
        if len(text) > limit:
            errors.append(f"{label}: {len(text)} chars exceeds {limit}")
        else:
            info.append(f"{label}: {len(text)}/{limit} chars")
        if "TODO" in text:
            errors.append(f"{label}: contains TODO")
        if "<" in text or ">" in text:
            warnings.append(f"{label}: contains angle brackets; confirm plain text")

    base = root / "fastlane" / "metadata" / "android" / "en-US"
    check_text(base / "title.txt", limits["title"], "title")
    check_text(base / "short_description.txt", limits["short_description"], "short description")
    check_text(base / "full_description.txt", limits["full_description"], "full description")
    check_text(base / "changelogs" / "1.txt", limits["changelog"], "changelog 1")

    icon = base / "images" / "icon.png"
    if icon.is_file():
        size = png_size(icon)
        expected_icon = tuple(limits["icon"])
        info.append(f"icon: {size[0]}x{size[1]}")
        if size != expected_icon:
            errors.append(f"icon: expected {expected_icon}, got {size}")
    else:
        errors.append(f"icon: missing {icon.relative_to(root)}")

    feature = base / "images" / "featureGraphic.png"
    if feature.is_file():
        size = png_size(feature)
        expected_feature = tuple(limits["feature_graphic"])
        info.append(f"feature graphic: {size[0]}x{size[1]}")
        if size != expected_feature:
            errors.append(f"feature graphic: expected {expected_feature}, got {size}")
    else:
        errors.append(f"feature graphic: missing {feature.relative_to(root)}")

    phone_dir = base / "images" / "phoneScreenshots"
    phone = sorted(phone_dir.glob("*.png")) if phone_dir.is_dir() else []
    if not (limits["screenshots_min"] <= len(phone) <= limits["screenshots_max"]):
        errors.append(
            f"phone screenshots: {len(phone)} found; expected "
            f"{limits['screenshots_min']}–{limits['screenshots_max']}"
        )
    expected_phone = tuple(limits["phone_screenshot"])
    for path in phone:
        size = png_size(path)
        if size != expected_phone:
            errors.append(f"{path.name}: expected {expected_phone}, got {size}")
    info.append(f"phone screenshots: {len(phone)}")

    amazon_dir = root / "docs" / "distribution" / "assets" / "screenshots" / "amazon-landscape"
    amazon = sorted(amazon_dir.glob("*.png")) if amazon_dir.is_dir() else []
    if amazon:
        if not (3 <= len(amazon) <= 10):
            errors.append(f"Amazon screenshots: {len(amazon)} found; expected 3–10")
        for path in amazon:
            size = png_size(path)
            if size != (1920, 1080):
                errors.append(f"{path.name}: expected (1920, 1080), got {size}")
        info.append(f"Amazon landscape screenshots: {len(amazon)}")
    else:
        warnings.append("Amazon landscape screenshots are missing")

    for path, expected_size, label in [
        (root / "docs/distribution/assets/store-icon/icon-512.png", (512, 512), "store icon 512"),
        (root / "docs/distribution/assets/store-icon/icon-216.png", (216, 216), "store icon 216"),
        (root / "docs/distribution/assets/store-icon/icon-114.png", (114, 114), "store icon 114"),
        (
            root / "docs/distribution/assets/feature-graphic/feature-graphic-1024x500.png",
            (1024, 500),
            "distribution feature graphic",
        ),
    ]:
        if not path.is_file():
            errors.append(f"{label}: missing {path.relative_to(root)}")
            continue
        size = png_size(path)
        if size != expected_size:
            errors.append(f"{label}: expected {expected_size}, got {size}")

    metadata_dir = root / "docs" / "distribution" / "metadata"
    for path in sorted(metadata_dir.glob("*.md")):
        text = path.read_text(encoding="utf-8")
        if "**Status:**" not in text:
            errors.append(f"{path.name}: missing **Status:** line")
        if "TODO" in text:
            warnings.append(f"{path.name}: contains TODO")
        if "OWNER_MUST" in text or "OWNER MUST" in text:
            warnings.append(f"{path.name}: contains an owner placeholder")

    for relative in [
        "docs/distribution/README.md",
        "docs/distribution/PUBLICATION_STATUS.md",
        "docs/distribution/BLOCKED_ACTIONS.md",
        "docs/distribution/AUTOMATION_GUIDE.md",
        "docs/distribution/STORE_CREDENTIAL_SETUP.md",
        "docs/distribution/policies/PRIVACY_POLICY.md",
        "docs/distribution/policies/CONTENT_RATING_NOTES.md",
        ".github/workflows/release.yml",
    ]:
        if not (root / relative).is_file():
            errors.append(f"missing required file: {relative}")

    privacy = root / "docs/distribution/policies/PRIVACY_POLICY.md"
    if privacy.is_file() and "Effective date" not in privacy.read_text(encoding="utf-8"):
        errors.append("privacy policy has no effective date")

    scan_roots = [
        root / "docs" / "distribution",
        root / "fastlane",
        root / "tools" / "store-publisher",
        root / ".github" / "workflows",
    ]
    for scan_root in scan_roots:
        if not scan_root.exists():
            continue
        for path in scan_root.rglob("*"):
            if not path.is_file() or path.stat().st_size > 1_000_000:
                continue
            if path.suffix.lower() in {".png", ".jpg", ".jpeg", ".webp", ".apk", ".aab", ".jar"}:
                continue
            try:
                text = path.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            for label, pattern in SECRET_PATTERNS:
                if pattern.search(text):
                    errors.append(f"possible {label} in {path.relative_to(root)}")

    result = {
        "ok": not errors,
        "errors": errors,
        "warnings": warnings,
        "info": info,
        "strict": strict,
        "stores": len(stores["stores"]),
        "expected_version": expected["versionName"],
    }
    if strict and warnings:
        result["ok"] = False
    return result


def status(as_json: bool = False) -> dict:
    stores, _limits, expected = config()
    entries = []
    for store in stores["stores"]:
        entries.append(
            {
                "id": store["id"],
                "name": store["name"],
                "status": store["status"],
                "artifact": store["artifact"],
                "portal": store["portal"],
                "blockers": store.get("blockers", []),
            }
        )
    entries.sort(key=lambda item: (STATUS_ORDER.index(item["status"]) if item["status"] in STATUS_ORDER else 99, item["name"]))
    result = {
        "app": stores["app"],
        "expected_artifact": expected,
        "stores": entries,
        "generated_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
    }
    if as_json:
        return result
    print(f"{result['app']['name']} {result['app']['versionName']} ({result['app']['package']})")
    print(f"Artifact: {expected['artifact']}  SHA-256: {expected['sha256']}")
    print()
    print(f"{'Store':24} {'Status':18} {'Artifact':9} Blockers")
    print("-" * 86)
    for entry in entries:
        print(
            f"{entry['name'][:24]:24} {entry['status']:18} "
            f"{entry['artifact']:9} {len(entry['blockers'])}"
        )
    print()
    print("Status is read from tools/store-publisher/config/stores.json.")
    print("No store listing is PUBLISHED unless the platform has confirmed it.")
    return result


def report(root: Path, as_json: bool = False) -> dict:
    stores, _limits, expected = config()
    result = status(as_json=True)
    reports_dir = TOOL_ROOT / "reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    json_path = reports_dir / "distribution-status.json"
    md_path = reports_dir / "distribution-status.md"
    json_path.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")

    lines = [
        "# Distribution Status Report",
        "",
        f"Generated: {result['generated_at']}",
        "",
        f"App: **{stores['app']['name']}** `{stores['app']['package']}` "
        f"version `{stores['app']['versionName']}` (code {stores['app']['versionCode']})",
        "",
        f"Verified artifact: `{expected['artifact']}`",
        "",
        f"- Size: {expected['size']:,} bytes",
        f"- SHA-256: `{expected['sha256']}`",
        f"- Minsdk / targetSdk: {expected['minSdk']} / {expected['targetSdk']}",
        f"- ABIs: {', '.join(expected['abis'])}",
        f"- License: {expected['license']}",
        "",
        "## Stores",
        "",
        "| Store | Status | Artifact | Blockers |",
        "|---|---|---|---|",
    ]
    for entry in result["stores"]:
        blockers = "<br>".join(entry["blockers"]) if entry["blockers"] else "—"
        lines.append(
            f"| {entry['name']} | `{entry['status']}` | {entry['artifact']} | {blockers} |"
        )
    lines += [
        "",
        "## Meaning",
        "",
        "- `PUBLISHED` means the public platform URL has been verified.",
        "- `WAITING_FOR_USER` means an account, OTP, identity check, payment, or",
        "  legal acceptance is required.",
        "- `BLOCKED` means a technical or policy gate cannot be cleared with the",
        "  current artifact.",
        "- `NOT_ELIGIBLE` means the store's current requirements exclude this build.",
        "",
        "No store status is advanced by this report. It only reflects the",
        "configuration and the verified artifact.",
        "",
        "## References",
        "",
        "- `docs/distribution/PUBLICATION_STATUS.md`",
        "- `docs/distribution/BLOCKED_ACTIONS.md`",
        "- `docs/distribution/STORE_COMPATIBILITY_MATRIX.md`",
        "- `docs/distribution/RELEASE_MANIFEST.md`",
        "",
    ]
    md_path.write_text("\n".join(lines), encoding="utf-8")
    result["report_paths"] = [str(md_path), str(json_path)]
    if not as_json:
        print(f"wrote {md_path}")
        print(f"wrote {json_path}")
    return result


def copy_if_exists(source: Path, destination: Path, copied: list[str]) -> None:
    if source.is_file():
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        copied.append(str(destination))


def packet(store_id: str, root: Path, output: Path | None = None, as_json: bool = False) -> dict:
    stores, _limits, expected = config()
    entry = next((item for item in stores["stores"] if item["id"] == store_id), None)
    if entry is None:
        raise SystemExit(f"Unknown store: {store_id}")
    if not entry.get("packet"):
        raise SystemExit(f"{store_id} does not have a manual packet (see its metadata file)")

    destination = output or TOOL_ROOT / "reports" / "packets" / store_id
    if destination.exists():
        shutil.rmtree(destination)
    destination.mkdir(parents=True)
    copied: list[str] = []

    copy_if_exists(root / entry["metadata"], destination / "LISTING.md", copied)
    for relative in [
        "docs/distribution/policies/PRIVACY_POLICY.md",
        "docs/distribution/policies/DATA_SAFETY_ANSWERS.md",
        "docs/distribution/policies/PERMISSION_DECLARATIONS.md",
        "docs/distribution/policies/CONTENT_RATING_NOTES.md",
    ]:
        copy_if_exists(root / relative, destination / Path(relative).name, copied)

    asset_root = root / "docs" / "distribution" / "assets"
    for relative in [
        "store-icon/icon-512.png",
        "store-icon/icon-216.png",
        "store-icon/icon-114.png",
        "feature-graphic/feature-graphic-1024x500.png",
    ]:
        copy_if_exists(asset_root / relative, destination / "assets" / Path(relative).name, copied)

    if store_id == "amazon":
        source_dir = asset_root / "screenshots" / "amazon-landscape"
        target_dir = destination / "assets" / "screenshots"
        for source in sorted(source_dir.glob("*.png")):
            copy_if_exists(source, target_dir / source.name, copied)
    else:
        source_dir = asset_root / "screenshots" / "phone"
        target_dir = destination / "assets" / "screenshots"
        for source in sorted(source_dir.glob("*.png")):
            copy_if_exists(source, target_dir / source.name, copied)

    readme = [
        f"# {entry['name']} Submission Packet",
        "",
        f"Status: `{entry['status']}`",
        "",
        f"Portal: {entry['portal']}",
        "",
        "This packet was generated from the repository. It does not create an",
        "account or submit anything. Upload the files manually in the store",
        "console and record the platform's response.",
        "",
        "## Artifact",
        "",
        f"- File: `{expected['artifact']}`",
        f"- Size: {expected['size']:,} bytes",
        f"- SHA-256: `{expected['sha256']}`",
        f"- Package: `{expected['package']}`",
        f"- Version: `{expected['versionName']}` (code {expected['versionCode']})",
        f"- minSdk / targetSdk: {expected['minSdk']} / {expected['targetSdk']}",
        f"- ABIs: {', '.join(expected['abis'])}",
        f"- License: {expected['license']}",
        f"- Release: {stores['app']['releaseUrl']}",
        "",
        "## Blockers",
        "",
    ]
    if entry.get("blockers"):
        readme += [f"- {blocker}" for blocker in entry["blockers"]]
    else:
        readme.append("- None recorded.")
    readme += [
        "",
        "## Checklist",
        "",
        "- [ ] Sign in to the portal with the owner's account.",
        "- [ ] Complete any OTP/CAPTCHA/identity/payment step yourself.",
        "- [ ] Upload the signed APK from the verified GitHub Release.",
        "- [ ] Enter the listing text from `LISTING.md`.",
        "- [ ] Upload the icons and screenshots from `assets/`.",
        "- [ ] Enter the privacy policy URL.",
        "- [ ] Complete content rating and permission questions.",
        "- [ ] Submit and record the submission ID.",
        "- [ ] Do not claim publication until the public page resolves.",
        "",
        "## Files",
        "",
    ]
    for path in sorted(copied):
        readme.append(f"- `{Path(path).relative_to(destination)}`")
    readme.append("")
    (destination / "README.md").write_text("\n".join(readme), encoding="utf-8")

    result = {
        "ok": True,
        "store": entry["name"],
        "status": entry["status"],
        "portal": entry["portal"],
        "output": str(destination),
        "files": sorted(str(Path(path).relative_to(destination)) for path in copied),
    }
    if as_json:
        return result
    print(f"wrote {destination}")
    print(f"{len(copied)} files copied")
    print("Upload manually; this tool does not submit to the store.")
    return result


def fetch(repo: str, tag: str, asset: str, output: Path, force: bool = False) -> dict:
    if output.exists() and not force:
        raise SystemExit(f"Refusing to overwrite {output}; pass --force")
    url = f"https://github.com/{repo}/releases/download/{tag}/{asset}"
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "linexa-store-publisher/1.0"},
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    digest = hashlib.sha256()
    size = 0
    with urllib.request.urlopen(request, timeout=120) as response, output.open("wb") as handle:
        for chunk in iter(lambda: response.read(1024 * 1024), b""):
            handle.write(chunk)
            digest.update(chunk)
            size += len(chunk)
    return {
        "url": url,
        "path": str(output),
        "size": size,
        "sha256": digest.hexdigest(),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Verify and package Linexa distribution artifacts without faking store submissions."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=REPO_ROOT,
        help="Repository root (default: the checkout containing this tool)",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    p_status = sub.add_parser("status", help="Show the configured store status")
    p_status.add_argument("--json", action="store_true")

    p_lint = sub.add_parser("lint", help="Lint Fastlane metadata and assets")
    p_lint.add_argument("--strict", action="store_true", help="Treat warnings as errors")
    p_lint.add_argument("--json", action="store_true")

    p_fetch = sub.add_parser("fetch", help="Download the public release APK")
    p_fetch.add_argument("--repo", default="ForExample25/Linexa")
    p_fetch.add_argument("--tag", default="v0.1.0-beta")
    p_fetch.add_argument("--asset", default="app-release.apk")
    p_fetch.add_argument("--output", type=Path, default=TOOL_ROOT / "artifacts" / "app-release.apk")
    p_fetch.add_argument("--force", action="store_true")
    p_fetch.add_argument("--json", action="store_true")

    p_verify = sub.add_parser("verify", help="Verify an APK against the recorded facts")
    p_verify.add_argument("--apk", type=Path, default=TOOL_ROOT / "artifacts" / "app-release.apk")
    p_verify.add_argument("--expected", type=Path, default=CONFIG_DIR / "expected-artifact.json")
    p_verify.add_argument("--require-tools", action="store_true")
    p_verify.add_argument("--json", action="store_true")

    p_release = sub.add_parser("verify-release", help="Check the public GitHub Release via API")
    p_release.add_argument("--repo", default="ForExample25/Linexa")
    p_release.add_argument("--tag", default="v0.1.0-beta")
    p_release.add_argument("--asset", default="app-release.apk")
    p_release.add_argument("--json", action="store_true")

    p_packet = sub.add_parser("packet", help="Build a manual submission packet")
    p_packet.add_argument("--store", required=True)
    p_packet.add_argument("--output", type=Path)
    p_packet.add_argument("--json", action="store_true")

    p_report = sub.add_parser("report", help="Write an honest status report")
    p_report.add_argument("--json", action="store_true")

    p_adapters = sub.add_parser("adapters", help="List adapter capabilities")
    p_adapters.add_argument("--json", action="store_true")

    p_submit = sub.add_parser("submit", help="Attempt a submission (manual stores refused)")
    p_submit.add_argument("--store", required=True)

    args = parser.parse_args(argv)
    root = args.root.resolve()

    if args.command == "status":
        result = status(args.json)
        if args.json:
            print(json.dumps(result, indent=2))
        return 0

    if args.command == "lint":
        result = lint(root, strict=args.strict)
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            for item in result["info"]:
                print(f"info: {item}")
            for item in result["warnings"]:
                print(f"warning: {item}")
            for item in result["errors"]:
                print(f"error: {item}")
            print("lint: OK" if result["ok"] else "lint: FAILED")
        return 0 if result["ok"] else 1

    if args.command == "fetch":
        try:
            result = fetch(args.repo, args.tag, args.asset, args.output, args.force)
        except urllib.error.HTTPError as exc:
            print(f"download failed: HTTP {exc.code}", file=sys.stderr)
            return 1
        except urllib.error.URLError as exc:
            print(f"download failed: {exc.reason}", file=sys.stderr)
            return 1
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(f"downloaded {result['path']}")
            print(f"size: {result['size']:,} bytes")
            print(f"sha256: {result['sha256']}")
        return 0

    if args.command == "verify":
        expected = load_json(args.expected)
        result = verify_apk(args.apk, expected, require_tools=args.require_tools)
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(f"artifact: {result.get('artifact')}")
            print(f"size: {result.get('size'):,} bytes" if result.get("size") else "")
            print(f"sha256: {result.get('sha256')}")
            for warning in result["warnings"]:
                print(f"warning: {warning}")
            for error in result["errors"]:
                print(f"error: {error}")
            print("verify: OK" if result["ok"] else "verify: FAILED")
        return 0 if result["ok"] else 1

    if args.command == "verify-release":
        _stores, _limits, expected = config()
        adapter = GitHubReleaseAdapter()
        try:
            result = adapter.verify(
                args.repo,
                args.tag,
                args.asset,
                expected["sha256"],
                expected["size"],
            )
        except RuntimeError as exc:
            print(f"verify-release: FAILED: {exc}", file=sys.stderr)
            return 1
        if args.json:
            print(json.dumps(result, indent=2))
        else:
            print(f"release: {result['html_url']}")
            print(f"asset: {result['asset']['name']} ({result['asset']['size']:,} bytes)")
            print(f"digest: {result['asset']['digest']}")
            print("verify-release: OK")
        return 0

    if args.command == "packet":
        result = packet(args.store, root, args.output, args.json)
        if args.json:
            print(json.dumps(result, indent=2))
        return 0

    if args.command == "report":
        result = report(root, args.json)
        if args.json:
            print(json.dumps(result, indent=2))
        return 0

    if args.command == "adapters":
        stores, _limits, _expected = config()
        adapters = build_adapters(stores["stores"])
        rows = []
        for store_id, adapter in adapters.items():
            cap = adapter.capabilities()
            rows.append(
                {
                    "id": store_id,
                    "name": adapter.name,
                    "portal": adapter.portal,
                    "can_verify": cap.can_verify,
                    "can_submit": cap.can_submit,
                    "reason": cap.reason,
                }
            )
        if args.json:
            print(json.dumps(rows, indent=2))
        else:
            for row in rows:
                print(
                    f"{row['name']}: verify={row['can_verify']} submit={row['can_submit']} "
                    f"— {row['reason']}"
                )
        return 0

    if args.command == "submit":
        stores, _limits, _expected = config()
        adapters = build_adapters(stores["stores"])
        adapter = adapters.get(args.store)
        if adapter is None:
            print(f"unknown store: {args.store}", file=sys.stderr)
            return 1
        try:
            result = adapter.submit()
        except ManualActionRequired as exc:
            print(f"MANUAL ACTION REQUIRED: {exc}", file=sys.stderr)
            return 2
        print(json.dumps(result.__dict__, indent=2))
        return 0

    return 1


if __name__ == "__main__":
    raise SystemExit(main())
