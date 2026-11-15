# Automation Guide

**Scope:** what is automated in this repository, what remains manual, and how
to run the automated parts without exposing credentials or faking a store
submission.

The short version:

- **Automated:** artifact verification, metadata linting, GitHub Release
  verification, manual-packet generation, status reporting, signed CI builds,
  and Pages deployment.
- **Manual by design:** store account creation, OTP/CAPTCHA/identity/payment,
  developer agreements, content-rating certification, and every store console
  submission.

---

## 1. Toolchain

```bash
# From the repository root
python3 tools/store-publisher/publisher.py --help
```

The helper uses only the Python standard library. Android build-tools are
optional but recommended:

```bash
# Termux
. ~/Tools/env/toolchain.sh
# Ubuntu proot
. /mnt/termux/Tools/env/toolchain.sh
```

`apksigner`, `aapt2`, and `zipalign` are discovered on `PATH` or under
`ANDROID_HOME` / `ANDROID_SDK_ROOT`.

---

## 2. Verify the public artifact

```bash
python3 tools/store-publisher/publisher.py fetch
python3 tools/store-publisher/publisher.py verify --require-tools
python3 tools/store-publisher/publisher.py verify-release
```

What `verify` checks:

- File size and SHA-256 against `config/expected-artifact.json`.
- Package ID, version name, version code, minSdk, targetSdk, ABIs, debuggable,
  and testOnly via `aapt2 dump badging`.
- APK Signature Scheme v2, signer count, certificate DN, and certificate
  SHA-256 via `apksigner`.
- ZIP alignment via `zipalign -c -v 4`.

What `verify-release` checks:

- The public GitHub Release tag, asset name, size, and GitHub-reported digest.
- It is read-only; it does not edit the release.

A mismatch is a failure. Never edit the expected file to make a bad artifact
pass.

---

## 3. Lint metadata and assets

```bash
python3 tools/store-publisher/publisher.py lint
python3 tools/store-publisher/publisher.py lint --strict   # warnings fail
```

Checks:

- Fastlane title ≤30, short description ≤80, full description ≤4,000,
  changelog ≤500.
- Icon 512×512, feature graphic 1024×500.
- 8 phone screenshots at 1080×1920.
- 8 Amazon landscape screenshots at 1920×1080 (if the directory exists).
- Required policy/status docs are present.
- Every metadata file has a `**Status:**` line.
- Accidental secret patterns in docs, Fastlane, tooling, and workflows.

The lint does not contact a store and does not validate the store's live form.

---

## 4. Signed CI builds

Workflow: [`.github/workflows/release.yml`](../../.github/workflows/release.yml)

Triggers:

- Push a tag matching `v*`.
- Manual `workflow_dispatch` with an existing tag; optionally publish the
  GitHub Release.

Required secrets (names only):

- `ANDROID_KEYSTORE_BASE64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Setup: [`STORE_CREDENTIAL_SETUP.md`](./STORE_CREDENTIAL_SETUP.md).

The workflow:

1. Checks out the tag.
2. Installs JDK 17 and Android platform 34 / build-tools 34.0.0.
3. Decodes the existing keystore into the runner temp directory.
4. Writes a temporary `local.properties` with escaped signing properties.
5. Builds `:app:assembleRelease` and `:app:bundleRelease`.
6. Verifies the APK with `apksigner` and `zipalign`.
7. Writes `SHA256SUMS`.
8. Uploads the APK, AAB, and checksums as workflow artifacts.
9. Creates or updates the GitHub Release when the trigger allows it.
10. Deletes the temporary keystore and `local.properties` in an `always()` step.

It never creates a new signing key. It never prints secret values. It does not
run on pull requests.

**Before a new release:** bump `versionCode` and `versionName` in
`app/build.gradle.kts`, update `config/expected-artifact.json` after verifying
the build, and update the Fastlane changelog. A tag alone does not bump the
version.

---

## 5. Pages deployment

Source: `docs/distribution/site/`.

The site is deployed from an **orphan `gh-pages` branch**, not from `main`.
This keeps the source tree, Gradle configuration, and release tag untouched.

```bash
# Clean temporary directory
rm -rf /tmp/linexa-pages
mkdir -p /tmp/linexa-pages
cd /tmp/linexa-pages
git init
git checkout --orphan gh-pages
cp -R /path/to/repo/docs/distribution/site/. .
rm -f README.md
git add -A
git -c user.name=ForExample25 \
    -c user.email=ForExample25@users.noreply.github.com \
    commit -m "Deploy Linexa distribution site"
git remote add origin https://github.com/ForExample25/Linexa.git
git push -u origin gh-pages
```

Pages is already enabled with source `gh-pages` / `/`. Verify:

```bash
gh api repos/ForExample25/Linexa/pages --jq '{status,html_url,source,commit}'
curl -fsS -o /dev/null -w '%{http_code}\n' https://forexample25.github.io/Linexa/
curl -fsS https://forexample25.github.io/Linexa/ | grep -F 0.1.0-beta
curl -fsS https://forexample25.github.io/Linexa/privacy.html | grep -F 'Effective date'
```

The live deployment is recorded in
[`PUBLICATION_STATUS.md`](./PUBLICATION_STATUS.md). If a future deployment
fails, mark Pages `PENDING`/`FAILED` there; do not leave a stale `PUBLISHED`
claim.

---

## 6. Manual submission packets

```bash
python3 tools/store-publisher/publisher.py packet --store amazon
python3 tools/store-publisher/publisher.py packet --store uptodown
```

Packets are written under `tools/store-publisher/reports/packets/<store>/`
(gitignored). Each contains:

- `LISTING.md` — the store-specific listing copy.
- `PRIVACY_POLICY.md`, `DATA_SAFETY_ANSWERS.md`,
  `PERMISSION_DECLARATIONS.md`, `CONTENT_RATING_NOTES.md`.
- `assets/` — icons, feature graphic, and the correct screenshots (Amazon gets
  the 1920×1080 landscape set).
- `README.md` — portal, blockers, artifact facts, and a checklist.

A packet is not a submission. Upload it manually and record the portal's
response.

### Why there is no store scraper

- Play, Samsung, Huawei, Xiaomi, Amazon, Aptoide, Uptodown, and APKPure require
  an authenticated owner session.
- APKPure uses a Cloudflare challenge.
- Automating CAPTCHA, OTP, or identity checks violates the stores' terms and
  this project's rules.
- Storing a store password in GitHub Actions would be a security regression.

The `submit` command exists only to raise `MANUAL ACTION REQUIRED` with the
portal and blockers.

---

## 7. GitHub Release checksum and notes

The existing release carries `SHA256SUMS` and install/verification notes. To
re-verify or re-upload the checksum after an owner-approved rebuild of the same
APK:

```bash
cd /path/to/verified/artifact
sha256sum app-release.apk > SHA256SUMS
sha256sum -c SHA256SUMS
gh release upload v0.1.0-beta SHA256SUMS \
  --repo ForExample25/Linexa --clobber

# Prepare notes from metadata/github-releases.md, then:
gh release edit v0.1.0-beta \
  --repo ForExample25/Linexa \
  --notes-file /path/to/release-notes.md
```

The public checksum is
https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/SHA256SUMS.

Do not replace the APK on an existing release with a different build. If a new
build is needed, publish a new version code and tag.

---

## 8. Status changes

The source of truth for store status is
`tools/store-publisher/config/stores.json`, mirrored in
`PUBLICATION_STATUS.md`.

When a store confirms a step:

1. Record the submission ID and public URL in `PUBLICATION_STATUS.md`.
2. Update the store's `status` in `stores.json`.
3. Add the confirmed URL to `metadata/<store>.md`.
4. Update `release.json` on the Pages site if the channel is public.
5. Re-run `publisher.py lint` and `publisher.py report`.

Allowed transitions:

```
NOT_STARTED → INVESTIGATING → READY → ACCOUNT_CREATED → DRAFT_CREATED
→ UPLOADED → SUBMITTED → UNDER_REVIEW → APPROVED → PUBLISHED
```

`BLOCKED`, `WAITING_FOR_USER`, `REJECTED`, and `NOT_ELIGIBLE` are terminal or
side states until the blocker is cleared. Never skip from `DRAFT_CREATED` to
`PUBLISHED` without the platform's public URL.

---

## 9. Reporting

```bash
python3 tools/store-publisher/publisher.py status
python3 tools/store-publisher/publisher.py report
```

`report` writes:

- `tools/store-publisher/reports/distribution-status.md`
- `tools/store-publisher/reports/distribution-status.json`

The report is generated from the config and the pinned artifact facts. It does
not query stores and does not promote a status.

---

## 10. Safety rules

- Never commit a keystore, password, token, service-account JSON, or
  `local.properties`.
- Never paste a secret into an issue, chat, pull request, or log.
- Never bypass a CAPTCHA, OTP, rate limit, or identity check.
- Never create an account with invented identity, address, tax, or payment
  data.
- Never claim a store listing is live without the public URL.
- Never replace a published APK with a build signed by a different key.
