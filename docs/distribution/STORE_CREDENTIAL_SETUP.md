# Store and CI Credential Setup

**Audience:** the repository owner, on their own machine and accounts.
**Rule:** credentials are never committed, pasted into issues, printed in logs,
or sent to the agent. This document describes names and procedures only.

---

## 1. What already exists

| Item | Location | Status |
|---|---|---|
| Release keystore | `keystore/linexa-release.jks` | Local, gitignored, **do not regenerate** |
| Keystore passwords / alias | `local.properties` (gitignored) | Local, never commit |
| APK signing certificate | SHA-256 `AA:AB:44:EC:CF:4D:B1:5C:88:16:BD:34:F6:08:64:D7:70:54:32:BF:4F:60:5D:52:0C:00:D5:73:EE:63:C5:20` | Published in the release manifest |
| GitHub Actions signing secrets | Repository secrets | **Not configured** |
| Store API credentials | — | **Not configured** |

The APK is already published under this key. A new key would make every future
update uninstallable over the existing install. Reuse the existing keystore.

---

## 2. GitHub Actions signing secrets

The release workflow expects exactly four repository (or environment) secrets:

| Secret | Contents | Sensitivity |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64 of `keystore/linexa-release.jks` | High — the private key |
| `SIGNING_STORE_PASSWORD` | Keystore password | High |
| `SIGNING_KEY_ALIAS` | Key alias inside the keystore | Medium |
| `SIGNING_KEY_PASSWORD` | Key password | High |

Base64 is encoding, **not encryption**. Treat `ANDROID_KEYSTORE_BASE64` as the
private key itself.

### 2.1 Create the base64 file without printing it

Run locally, in the repository root:

```bash
umask 077
base64 -w 0 keystore/linexa-release.jks > "$HOME/.linexa-keystore.b64"
wc -c "$HOME/.linexa-keystore.b64"   # sanity check; do not cat it
```

On a system whose `base64` lacks `-w`, use `base64 keystore/linexa-release.jks
| tr -d '\n' > "$HOME/.linexa-keystore.b64"`.

Do not paste the output into a chat, issue, commit, or shell history. The file
is readable only by the owner (`umask 077`).

### 2.2 Set the secrets with GitHub CLI

Authenticate as the repository owner:

```bash
gh auth status
gh auth login   # only if needed
```

Set the binary secret from the file:

```bash
gh secret set ANDROID_KEYSTORE_BASE64 \
  --repo ForExample25/Linexa \
  < "$HOME/.linexa-keystore.b64"
```

Set the text secrets interactively so they are not written to shell history:

```bash
gh secret set SIGNING_STORE_PASSWORD --repo ForExample25/Linexa
gh secret set SIGNING_KEY_ALIAS     --repo ForExample25/Linexa
gh secret set SIGNING_KEY_PASSWORD --repo ForExample25/Linexa
```

`gh secret set` prompts for the value; type it, press Enter, and clear the
clipboard if you used one. Do not pass the value as an argument.

Verify only the names:

```bash
gh secret list --repo ForExample25/Linexa
```

The list shows names and update times, never values.

### 2.3 Remove the temporary base64 file

```bash
shred -u "$HOME/.linexa-keystore.b64" 2>/dev/null || rm -f "$HOME/.linexa-keystore.b64"
```

`shred` is best-effort on flash storage; the important part is deleting the
file and not copying it elsewhere.

### 2.4 Prefer an environment with reviewers

For stronger control, create a `release` environment and require a reviewer:

```bash
gh api --method PUT repos/ForExample25/Linexa/environments/release
gh secret set ANDROID_KEYSTORE_BASE64 --env release --repo ForExample25/Linexa < /path/to/file
```

Then change the workflow's job to `environment: release`. The job will wait for
the reviewer before secrets are released. This is optional but recommended.

---

## 3. What the release workflow does with the secrets

`.github/workflows/release.yml`:

1. Decodes `ANDROID_KEYSTORE_BASE64` into `$RUNNER_TEMP/linexa-release.jks`.
2. Writes a temporary `local.properties` containing:
   - `signing.storeFile=$RUNNER_TEMP/linexa-release.jks`
   - `signing.storePassword`, `signing.keyAlias`, `signing.keyPassword`
3. Runs `./gradlew :app:assembleRelease :app:bundleRelease`.
4. Verifies the APK with `apksigner verify`.
5. Publishes the APK/AAB and checksums to the GitHub Release.
6. Deletes the temporary keystore and `local.properties` in an `always()` step.

The workflow never echoes the secrets. Do not add `set -x`, `env`, `cat
local.properties`, or debug steps that print the environment.

### Java properties escaping

Passwords containing `\`, `:`, `=`, or leading spaces must be escaped in a Java
`.properties` file. The workflow writes the file with Python and escapes those
characters. If you edit the workflow, preserve that escaping.

---

## 4. GitHub token permissions

The release workflow requests:

```yaml
permissions:
  contents: write
```

That is enough to create a release and upload assets. Do not grant `packages`,
`actions`, or `id-token` unless a later step needs them. The default
`GITHUB_TOKEN` is scoped to the repository and expires with the job.

For opening a pull request, the token needs `pull-requests: write` on that job
only. Keep release and PR permissions separate.

---

## 5. Store API credentials

Only add these if the owner decides to automate a store. None are required for
the manual listing packets in this package.

### Google Play

Prerequisites: a Play Console account, the app record, and the first AAB
uploaded manually.

1. Create a Google Cloud project and a service account.
2. Enable the Google Play Android Developer API.
3. In Play Console → Users and permissions, invite the service account email
   with **Release to testing tracks** and **View app information** (least
   privilege).
4. Create a JSON key and store it as `PLAY_SERVICE_ACCOUNT_JSON`.
5. Never commit the JSON. Rotate/delete the key if it leaks.

Do not grant **Admin** or **Manage app** to a CI service account unless a
specific automation requires it.

### Samsung Galaxy Store

Samsung's Content Publish API uses a service account ID and a private key.
Store them as `SAMSUNG_SERVICE_ACCOUNT_ID` and `SAMSUNG_PRIVATE_KEY`. The
private key is as sensitive as the app signing key. Confirm the current API
scope and seller permissions in Seller Portal before creating it.

### Huawei AppGallery Connect

AppGallery Connect uses a client ID/secret and app ID:
`HUAWEI_CLIENT_ID`, `HUAWEI_CLIENT_SECRET`, `HUAWEI_APP_ID`. The API must be
enabled for the app in AGC. Do not reuse the signing key password.

### Amazon Appstore

The App Submission API uses Login with Amazon credentials:
`AMAZON_CLIENT_ID`, `AMAZON_CLIENT_SECRET`, `AMAZON_REFRESH_TOKEN`. The refresh
token is long-lived; store it as a secret and revoke it if leaked.

### Aptoide, Uptodown, APKPure

No public submission API was verified during this audit. Their consoles are
session-based and may use CAPTCHA or Cloudflare. Do **not** store the account
password in GitHub Actions to automate a browser session. Use the manual
console and a password manager.

### F-Droid / IzzyOnDroid / Accrescent

- F-Droid and IzzyOnDroid use GitLab/Codeberg accounts and merge requests/issues.
  Use an SSH key or a fine-grained token for Git operations, not the account
  password.
- Accrescent's console may use a token; create it only after the app is
  eligible, and scope it to the app.

---

## 6. Account security baseline

- Use a unique password for every store account; store it in a password manager.
- Enable 2FA/TOTP. Prefer a hardware key or an authenticator app over SMS.
- Use the owner's real identity and contact details. Do not create accounts with
  borrowed or invented data.
- Keep recovery codes offline.
- Do not share one email inbox without 2FA protection.
- Review authorised sessions/devices periodically.
- Do not let a contractor or agent hold the only copy of a store credential.

The agent did not create any store account, did not read the keystore, and did
not upload any secret. Every account step remains an owner action.

---

## 7. If a credential leaks

1. **Revoke first:** delete the GitHub secret, revoke the store token, or
   disable the API key.
2. **Rotate:** create a new secret/key and update the workflow.
3. **Audit:** check release assets, store versions, and account activity for
   unauthorised changes.
4. **If the app signing key leaks:** this is the most serious case. An attacker
   can sign an update that Android accepts. There is no way to revoke an APK
   signing key for already-installed apps without Play App Signing or a store
   key-rotation flow. Contact each store, publish a security advisory, and
   plan a package migration if necessary. Do not quietly generate a new key and
   publish an update that cannot install.
5. **Notify:** publish a security advisory on GitHub and tell users which
   builds are affected.

Do not delete evidence. Record what happened, when, and which credentials were
rotated.

---

## 8. Verification checklist

- [ ] `gh secret list` shows the four signing secret names.
- [ ] No keystore, `local.properties`, `.b64`, or password appears in
      `git status`, `git log -p`, or the repository's file list.
- [ ] The release workflow is triggered by a tag, not by an untrusted pull
      request.
- [ ] The workflow's `permissions:` block is minimal.
- [ ] The temporary keystore is deleted in an `always()` step.
- [ ] The APK's certificate SHA-256 still matches
      `AA:AB:44:EC:...:63:C5:20`.
- [ ] Store API tokens are scoped to one app and one action where possible.
- [ ] 2FA is enabled on every store account.
