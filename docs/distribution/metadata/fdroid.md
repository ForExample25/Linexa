# F-Droid Listing and Inclusion Notes

**Status:** `BLOCKED` — the current release build gate requires signing
credentials, and the APK ships prebuilt native binaries that F-Droid's
inclusion policy does not automatically trust. Packaging is prepared;
inclusion needs an owner decision and a build-recipe change.
**Portal:** https://f-droid.org/
**Inclusion policy:** https://f-droid.org/en/docs/Inclusion_Policy/
**Submission guide:** https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/
**Data repository:** https://gitlab.com/fdroid/fdroiddata

---

## Why this is blocked

### 1. The release build gate refuses to build without a keystore

`app/build.gradle.kts` contains:

```kotlin
gradle.taskGraph.whenReady {
    val wantsReleaseArtifact = allTasks.any {
        (it.name.startsWith("assemble") || it.name.startsWith("bundle")) &&
            it.name.contains("Release")
    }
    if (wantsReleaseArtifact && storeFilePath.isEmpty()) {
        throw GradleException("Release build has no signing credentials...")
    }
}
```

F-Droid's buildserver does not receive the project's private keystore. A plain
`assembleRelease` therefore fails before compilation. Options:

| Option | Consequence |
|---|---|
| Add an F-Droid-specific build flag that skips signing when no credentials are present | Changes release behaviour; needs owner approval and tests |
| Add a `prebuild` script that creates a throwaway keystore in `local.properties` | F-Droid re-signs the APK anyway, but the recipe becomes unusual and must be reviewed |
| Build only debug | Not acceptable for an F-Droid production listing |

Do not weaken the gate silently. The owner must choose a supported path.

### 2. Prebuilt native binaries

The APK ships PRoot, BusyBox, GNU tar, tmux, zstd, and their libraries from
`app/src/main/jniLibs/`. F-Droid allows prebuilt binaries only from trusted
sources and may require a source build or a documented provenance chain.
`app/src/main/jniLibs/BINARY-MANIFEST.md` records versions, hashes, and
licences, and `proot-build/BUILD-NOTES.md` documents PRoot's build recipe, but
the other binaries do not have an integrated source build in this repository.

F-Droid reviewers may:

- accept the binaries with the manifest and upstream source links,
- require build recipes for each binary, or
- reject the app.

There is no guarantee. Do not submit until the owner decides whether to invest
in source builds or ask reviewers for an exception.

### 3. No GitLab account or merge request

F-Droid submissions use GitLab (`fdroiddata` merge requests) or the RFP issue
tracker. No GitLab account or authenticated session exists.

---

## Proposed `fdroiddata` metadata (draft only)

Save as `metadata/com.fem.linexa.yml` in a fork of `fdroiddata` **after** the
build-gate and prebuilt-binary issues are resolved. This is a draft, not a
submitted file.

```yaml
Categories:
  - System
  - Development
License: GPL-3.0-only
AuthorName: Linexa project
WebSite: https://forexample25.github.io/Linexa/
SourceCode: https://github.com/ForExample25/Linexa
IssueTracker: https://github.com/ForExample25/Linexa/issues
Changelog: https://github.com/ForExample25/Linexa/releases

AutoName: Linexa

RepoType: git
Repo: https://github.com/ForExample25/Linexa.git

Builds:
  - versionName: 0.1.0-beta
    versionCode: 1
    commit: d7e678618b59a27283a07260ab1efd05d6eb8c95
    subdir: app
    gradle:
      - yes
    # prebuild: NOT DEFINED — see the build-gate blocker above.

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.1.0-beta
CurrentVersionCode: 1
```

`AuthorEmail` is intentionally omitted. F-Droid needs a maintainer contact in
the merge request, but the public metadata should not invent an address. The
owner must supply a monitored address if F-Droid asks for one.

Do **not** add `AllowedAPKSigningKeys`: that field applies to reproducible
builds signed by the upstream key. F-Droid builds and signs with its own key.

---

## Listing copy (Fastlane-compatible)

F-Droid reads Fastlane metadata from the app repository. The prepared tree is
`fastlane/metadata/android/en-US/`.

### Summary (≤80 characters)

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

### Description

```
Linexa brings Linux environments to Android — without root.

Create isolated workspaces, install a Linux root filesystem, open a full PTY terminal, manage files and packages, and connect to a graphical desktop. Each workspace keeps its own files, sessions, and settings.

WHAT YOU CAN DO
• Workspaces — run multiple isolated Linux environments and switch between them.
• RootFS installer — import a .tar.gz, .tar.xz, or .tar.zst archive, or download Alpine, Ubuntu, Arch Linux ARM, Debian, or Fedora from the built-in catalog.
• Real terminal — a persistent PTY with ANSI/cursor rendering, scrollback, text selection, copy/paste, pinch-to-zoom, and an extra-keys row.
• Desktop — connect through the built-in VNC client or a WebView pointed at a guest web server. VNC mode requires Xvnc in the guest; Debian and Ubuntu have a guided installer.
• Package manager — search, install, update, and remove packages through apt, pacman, apk, dnf, or zypper.
• File manager — browse, copy, rename, delete, import, and edit files, with syntax highlighting and a Storage Access Framework provider.
• Sessions and tasks — keep multiple terminal sessions, background long-running work, and inspect or stop workspace processes.
• Networking — per-workspace DNS, /etc/hosts, package mirrors, and a rootfs mirror override. Checksums still come from the official source.
• Local-first — workspace metadata lives in Room on your device. No account, no cloud dependency.

GOOD TO KNOW
• Beta software: interfaces, supported guests, and device behaviour may change.
• Android 9 or newer, on arm64-v8a or armeabi-v7a devices.
• Root is not required. PRoot provides a user-space guest environment.
• Linexa is free software under GPL-3.0. Third-party components keep their own licences; see Settings → About → Open source licenses.
• Source and issue tracker: github.com/ForExample25/Linexa
```

### Anti-features

None of the standard anti-features apply:

| Anti-feature | Why not |
|---|---|
| Ads | No advertising |
| Tracking | No analytics, crash reporting, or tracking SDK |
| NonFreeNet | Distribution mirrors are public free-software mirrors |
| NonFreeDep | No proprietary dependency |
| NonFreeAdd | No proprietary add-on |
| UpstreamNonFree | Upstream source is GPL-3.0 |
| DisabledAlgorithm | APK Signature Scheme v2 with RSA-3072 |
| KnownVuln | No known vulnerable dependency declared by the project |
| NoSourceSince | Source is public at the tagged commit |

If F-Droid's scanner flags the rootfs download behaviour, explain that the
downloads are user-initiated and the archives are free-software distribution
images. Do not hide the behaviour.

---

## Compatibility and signing consequences

- F-Droid builds and signs with the F-Droid key. Users cannot update an
  F-Droid-installed Linexa over a GitHub-signed install (and vice versa)
  without uninstalling. Document this clearly if both channels are live.
- A reproducible build could later align the two signatures, but the current
  build is not reproducible and Amazon/other stores may re-sign independently.
- `versionCode 1` is already published on GitHub. F-Droid can build the same
  version code with a different signature because it is a separate package
  channel, but users cannot cross-upgrade in place.

---

## Submission steps (only after the blockers are resolved)

1. Create a GitLab account with the owner's email.
2. Fork `fdroiddata` and add `metadata/com.fem.linexa.yml`.
3. Add the Fastlane metadata to the app repository (already prepared in this
   package) and merge it upstream first, so F-Droid can read it from the tag.
4. Open a merge request; describe the PRoot runtime, the prebuilt binaries and
   their provenance, and the user-initiated downloads.
5. Answer reviewer questions; do not argue for an exception you cannot
   substantiate.
6. After inclusion, verify the F-Droid package page and record the version code,
   signing certificate, and repository URL in `../PUBLICATION_STATUS.md`.

Do not claim F-Droid inclusion until the package page and index are live.
