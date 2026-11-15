# Linexa Store Publisher

A small, dependency-free helper for the Linexa distribution workflow. It
verifies the public APK, lints the store metadata and assets, checks the GitHub
Release, builds manual submission packets, and writes an honest status report.

It does **not** create accounts, solve CAPTCHAs, enter OTPs, handle identity
documents, store passwords, or submit to a store console without an approved,
authenticated API. Manual adapters refuse rather than pretend.

---

## Requirements

- Python 3.10+ (standard library only).
- Optional: Android build-tools `apksigner`, `aapt2`, and `zipalign` on `PATH`
  or under `ANDROID_HOME`/`ANDROID_SDK_ROOT`.

The machine's centralized Android toolchain is discovered automatically when
`ANDROID_HOME` is set (see `~/Tools/env/toolchain.sh`).

---

## Quick start

```bash
# 1. Download the public APK
python3 tools/store-publisher/publisher.py fetch

# 2. Verify size, SHA-256, package, SDK, signature, and ABIs
python3 tools/store-publisher/publisher.py verify --require-tools

# 3. Check the public GitHub Release asset through the API
python3 tools/store-publisher/publisher.py verify-release

# 4. Lint the Fastlane metadata and prepared assets
python3 tools/store-publisher/publisher.py lint

# 5. Show the store status
python3 tools/store-publisher/publisher.py status

# 6. Build a manual packet for a store
python3 tools/store-publisher/publisher.py packet --store amazon
```

`fetch` writes to `artifacts/app-release.apk`, which is gitignored. The APK is
never committed.

---

## Commands

| Command | What it does | What it never does |
|---|---|---|
| `fetch` | Downloads the public release asset and prints its SHA-256 | Does not upload or sign |
| `verify` | Compares the APK to `config/expected-artifact.json`; re-runs `apksigner`, `aapt2`, and `zipalign` when available | Does not trust the filename or size alone |
| `verify-release` | Reads the public GitHub Release API and checks the asset digest/size | Read-only; no token required |
| `lint` | Checks Fastlane lengths, PNG dimensions, screenshot counts, required docs, and accidental secrets | Does not contact stores |
| `status` | Prints the configured status and blockers | Does not advance a status |
| `report` | Writes `reports/distribution-status.{md,json}` | Does not claim publication |
| `packet` | Copies the listing, policies, icons, and screenshots for a manual submission | Does not submit |
| `adapters` | Lists what each adapter can actually do | — |
| `submit` | Calls an adapter; manual stores raise `MANUAL ACTION REQUIRED` | Does not fake a submission |

Exit codes:

- `0` — success / verified.
- `1` — verification or lint failure.
- `2` — a human must act in a store portal.

---

## Configuration

| File | Purpose |
|---|---|
| `config/stores.json` | App facts, store portals, statuses, blockers, metadata paths |
| `config/limits.json` | Fastlane and per-store character/image limits |
| `config/expected-artifact.json` | The verified APK facts from the release manifest |

The expected artifact is intentionally pinned. If a new release is published,
update this file from the new `RELEASE_MANIFEST.md` after verification. Do not
edit it to make a mismatch pass.

---

## Adapters

- `adapters/github_release.py` — read-only GitHub Releases API verification.
- `adapters/manual.py` — manual-store refusal with actionable blockers.
- `adapters/base.py` — capability and result types.

There is no Play/Samsung/Huawei/Amazon/Aptoide/Uptodown/APKPure scraper. Those
consoles require owner authentication, and some use CAPTCHA/Cloudflare. Adding
a browser-automation adapter that stores a password would violate the project's
security rules and the stores' terms.

---

## Tests

```bash
python3 -m unittest discover -s tools/store-publisher/tests -v
```

The tests assert that:

- every configured store has a metadata file;
- the expected artifact matches the published facts;
- manual adapters refuse to submit;
- the Fastlane metadata and assets pass lint;
- the Amazon packet uses 1920×1080 landscape screenshots;
- the status report is generated without overstating any status.

---

## Reports and packets

Generated files are gitignored:

```
tools/store-publisher/artifacts/   # downloaded APKs
tools/store-publisher/reports/     # status reports and submission packets
tools/store-publisher/logs/        # command logs, if you redirect them
```

A packet is a convenience copy for a human. It is not proof of submission.
Record the store's submission ID and public URL in
`docs/distribution/PUBLICATION_STATUS.md` only after the portal confirms it.
