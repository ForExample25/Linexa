# Device and kernel compatibility

F-019 notes that the PRoot runtime is ptrace-based and therefore sensitive to
Android's SELinux policy, seccomp filters and vendor kernel hardening. This
page records what has actually been exercised, so "supported" is a claim with
evidence rather than an assumption.

## Evidence levels

| Level | Meaning |
|---|---|
| Verified | Full install, terminal, package manager, file manager and desktop start were exercised on the device. |
| Smoke | App installed, a workspace launched to a shell prompt, and one command ran. |
| Build-only | The APK builds and JVM tests pass; no device run was recorded. |

## Recorded matrix

| Device / OS | ABI | Level | Notes |
|---|---|---|---|
| Xiaomi `2412DPC0AG`, Android 16 (API 36) | arm64-v8a | Smoke | Debug build installed; workspace shell and one-shot exec verified. Release R8 artifact verified separately. |
| GitHub Actions `ubuntu-latest` (JVM) | x86_64 host | Build-only | Full JVM unit suite and lint. This is not an Android device and does not exercise PRoot. |

No Android 10–13, armeabi-v7a, Chromebook, or x86_64-emulator run is recorded
in this remediation pass. Those rows are deliberately absent rather than
inferred.

## Known constraints

- **x86_64 is not supported.** No x86_64 PRoot/BusyBox binary is bundled
  (ADR-17). Emulators and x86_64 Chromebooks cannot launch a workspace.
- **Root is emulated.** PRoot's `-0` is a fake identity, not Android root.
  `sudo` is the app-side root-exec bridge, not a setuid binary.
- **Same-uid boundary.** Guest processes run as the app uid; workspace
  isolation is path translation, not a kernel namespace (ADR-01, ADR-25).
- **Vendor hardening.** A kernel that blocks `ptrace` for app processes, or an
  SELinux policy that denies `exec` from the app's native library directory,
  will prevent PRoot from starting. The runtime reports the failing probe;
  there is no fallback backend.

## When adding a device row

Record the exact model, Android release and API level, ABI, build variant
(debug/release), and which flows were exercised. Do not record a device that
was only used to install the APK as "verified". Update this table in the same
change as the test evidence.
