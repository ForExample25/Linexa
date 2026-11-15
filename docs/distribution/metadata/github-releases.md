# GitHub Releases and GitHub Pages

**Status:** `PUBLISHED` — `v0.1.0-beta` is live on GitHub Releases. The public
Pages site was deployed by this distribution pass; see
`../PUBLICATION_STATUS.md` for the deployment commit and verification date.
**Release:** https://github.com/ForExample25/Linexa/releases/tag/v0.1.0-beta
**Direct APK:** https://github.com/ForExample25/Linexa/releases/download/v0.1.0-beta/app-release.apk
**Site:** https://forexample25.github.io/Linexa/
**Privacy:** https://forexample25.github.io/Linexa/privacy.html

GitHub Releases is not an app store. It is the canonical direct-download and
provenance channel: the source tag, the signed APK, the checksum, and the
signing certificate live in one public place. Every other store listing should
point back to it for verification.

---

## Published artifact

| Field | Value |
|---|---|
| Tag | `v0.1.0-beta` (annotated tag `c30bd47d66ed0e49c95987d149bf066f87ecb424`) |
| Commit | `d7e678618b59a27283a07260ab1efd05d6eb8c95` |
| Asset | `app-release.apk` |
| Size | 57,257,408 bytes (54.60 MiB) |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| Signature | APK Signature Scheme v2, RSA-3072, single signer |
| Certificate DN | `CN=Linexa, OU=Development, O=FemProject, C=US` |
| Certificate SHA-256 | `AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20` |
| Version | `0.1.0-beta` (versionCode 1) |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| License | GPL-3.0-only |
| Prerelease | Yes (beta) |

The release page should always carry the SHA-256 and certificate fingerprint.
If GitHub's generated source archives are used, verify them against the tag;
they are not the signed APK.

---

## Suggested release notes (current release)

The live release notes were written by the project owner. The distribution
pass appends installation and verification information; it does not rewrite
the product notes. A suitable block is:

```markdown
## Install

1. Download `app-release.apk` below.
2. Allow installation from your browser or file manager when Android asks.
3. Open the APK and install it.
4. Launch Linexa, choose **Add a Workspace**, then import a RootFS archive or
   download a distribution.

Requires Android 9 (API 28) or newer on an `arm64-v8a` or `armeabi-v7a`
device. About 60 MB for the APK, plus space for workspaces.

## Verify the download

```bash
sha256sum app-release.apk
# 6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680

apksigner verify --print-certs app-release.apk
# Certificate SHA-256:
# AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20
```

The APK is signed with the project's release key. Future updates must use the
same key; do not install a build with a different certificate over this one.

## Links

- Source: https://github.com/ForExample25/Linexa
- Privacy: https://forexample25.github.io/Linexa/privacy.html
- Issues: https://github.com/ForExample25/Linexa/issues

Linexa is free software under GPL-3.0-only. Third-party components keep their
own licences; see `THIRD_PARTY_NOTICES.md` and **Settings → About → Open
source licenses**.
```

---

## Update channel

For now, GitHub Releases is the only update channel. The app does **not**
contain a self-updater and does not request `REQUEST_INSTALL_PACKAGES`. Users
update by downloading a newer APK from the release page.

Recommended policy:

- Keep the same package ID and signing key for every release.
- Increment `versionCode` for every published build; never reuse it.
- Mark beta releases as prereleases until the owner decides otherwise.
- Publish the SHA-256 and certificate fingerprint in every release.
- Keep the previous release available for rollback.
- Do not delete a release that a store may reference.

### Obtainium

Users who want automatic update checks can add the repository to
[Obtainium](https://github.com/ImranR98/Obtainium):

```
https://github.com/ForExample25/Linexa
```

Obtainium reads GitHub Releases and installs the APK. This is a user-side
client, not a store listing; no submission is required. Linexa itself remains
self-update-free.

---

## GitHub Pages

The Pages site is a static, dependency-free landing page:

- `/` — download, version, checksum, requirements, features, screenshots,
  install and verification steps.
- `/privacy.html` — the privacy policy.
- `/release.json` — machine-readable release metadata.
- `/assets/` — icon, feature graphic, screenshots.
- `/404.html`, `/robots.txt`, `/.nojekyll`.

Pages is served from the orphan `gh-pages` branch. It is intentionally
separate from `main` so the source tree, Gradle configuration, and release tag
are not modified by documentation or site changes.

Deployment and verification commands are in
`../AUTOMATION_GUIDE.md`. Do not claim the site is live until the public URL
returns HTTP 200 and the expected version string.

---

## Release checklist for the next version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`; do not change
   the signing key.
2. Build `assembleRelease` and `bundleRelease` in CI (see
   `.github/workflows/release.yml`). The workflow is inert until the owner
   adds the four signing secrets.
3. Run `tools/store-publisher/publisher.py verify` against the downloaded APK.
4. Publish the APK, AAB (if built), `SHA256SUMS`, and `release.json`.
5. Update the Pages site's version, checksum, size, and changelog.
6. Update each store listing that is live; do not assume a GitHub release
   updates store listings automatically.
7. Record the release URL, checksum, and store statuses in
   `../PUBLICATION_STATUS.md`.

Do not publish an APK built from a different commit than the tag, and do not
sign with a new key.
