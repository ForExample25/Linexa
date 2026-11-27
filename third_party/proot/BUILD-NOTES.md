# proot-build — build notes (Linexa proot 5.1.107.89 rebuild)

This file records the exact source and patch set used for the vendored
PRoot pair, so that the corresponding source required by GPL-2.0-or-later
can be reconstructed. It ships with the source release.

**Sources:** `jniLibs/BINARY-MANIFEST.md`, the patch headers in this
directory, and termux-packages' public `packages/proot/build.sh`
(https://github.com/termux/termux-packages/blob/master/packages/proot/build.sh,
fetched 2026-08-31; master now at 5.1.107.92 — the flag pattern below is
identical). No patch beyond 0004 exists or is planned; this directory
documents only the 0001–0004 chain.

## 1. Provenance chain (VERIFIED)

- Upstream: `termux/proot` tag `v5.1.107.89`, commit
  `a89b3732ec6ae1db674510f0843b2f3db54d0a2f` (verified with
  `git ls-remote`).
- Patches applied in order with `patch -p1`:
  1. `0001-readlinkat-empty-pathname-fix.patch` (2026-08-04) — proot-me/proot#182
     readlinkat(fd, "") SIGABRT guard. The termux binary already carried
     this guard; the patch is the provenance record of the fix.
  2. `0002-l2s-relative-target-resolution.patch` (2026-08-25) — link2symlink
     resolves `.l2s.*` targets against the link's own directory; returns
     `-errno` instead of raw `-1` (the dpkg "unable to stat ... Operation not
     permitted" fix).
  3. `0003-ashmem-memfd-string-include.patch` (2026-08-25) — `<string.h>`
     for strcmp/memset; required by NDK r29 clang; no behavior change.
  4. `0004-guest-env-filter.patch` (2026-08-25) —
     `PROOT_GUEST_ENV_FILTER` scrubs host-only env vars at the execve
     enter side; set by `PRootLauncher` on every guest launch. The marker
     string is present in BOTH shipped `libproot.so` ABIs (grep-verified
     2026-08-31).
- Patch applicability: 0002/0004 `patch -p1` against pristine
  `termux/proot` v5.1.107.89 → byte-identical to the built source
  (verified 2026-08-25).

## 2. Build configuration (VERIFIED against BINARY-MANIFEST.md and the build flags)

- NDK r29, Android API 28+, dynamic bionic linking (interpreters
  `/system/bin/linker64` / `/system/bin/linker`).
- Flags from termux-packages `packages/proot/build.sh`:
  - `PROOT_WITH_LIBANDROID_SHMEM=true` (via `TERMUX_PKG_EXTRA_MAKE_ARGS`)
  - `PROOT_UNBUNDLE_LOADER` — upstream points it at
    `$TERMUX_PREFIX/libexec/proot`; the Linexa build points it at
    `{filesDir}/bootstrap/bin` (the loader is vendored, not installed)
  - `CPPFLAGS += -DARG_MAX=131072 -DVERSION=\"5.1.107.89\"`
  - dynamic deps per build.sh: `libtalloc`, `libandroid-shmem`
- arm64-v8a: links the *unchanged* vendored termux `libtalloc.so.2`
  (2.4.3) + `libandroid-shmem.so` (0.7) — byte-identical runtime closure
  to the previous termux pair; only proot and its loader changed.
- armeabi-v7a: the previous static-musl v5.3.0 pair had no vendored deps;
  the rebuild vendors NDK-built talloc 2.4.2 + libandroid-shmem 0.7
  (manifest buildId `linexa-l2s-fix-2026-08-25`).

## 3. Loader vendoring (VERIFIED)

The `loader` produced by the same build is vendored per ABI as
`lib__bin__loader__.so` (statically linked, no deps); `PROOT_LOADER`
always points there at runtime. AGP only auto-packages `lib*.so` jniLibs
entries and unpacks them to real files only with
`useLegacyPackaging = true` — hence the `lib__*__.so` naming (see
`BINARY-MANIFEST.md` and the `packaging { jniLibs }` block of
`app/build.gradle.kts`).

## 4. Hash re-pin procedure

1. Rebuild per sections 1–2; confirm both test drivers (section 5) pass on the result.
2. `sha256sum` all four artifacts
   (`{arm64-v8a,armeabi-v7a}/{libproot.so,lib__bin__loader__.so}`).
3. Update the full hashes in `app/src/main/assets/bootstrap-manifest.json`
   (40 artifacts; loader must share the proot buildId — the manifest's
   `pairs` lockstep) and the abbreviated table + footnote in
   `BINARY-MANIFEST.md`.
4. Never ship a loader from a different build than its proot.

## 5. Verification commands (VERIFIED — headers of the vendored drivers)

```sh
# link2symlink relative-target regression (dpkg shape):
gcc -o test-l2s-relative-targets test-l2s-relative-targets.c
proot --link2symlink -r <rootfs> ./test-l2s-relative-targets <dir>
# Expected: exit 0, "L2S-RELATIVE-TARGETS OK"
# Unpatched proot: FAIL lstat(member) (errno 1, EPERM)

# proot-me/proot#182 regression:
gcc -o test-readlinkat-empty-pathname test-readlinkat-empty-pathname.c
proot ./test-readlinkat-empty-pathname
# Expected: exit 0
# Unpatched proot: Assertion failed: length2 > 0 (path.c:547)
```

The x86_64 end-to-end verification build of the same patched source
(real ptrace + real dpkg) is reproducible from the same upstream tag and
patch chain; its drivers are the two test programs in this directory.

## 6. On-device status (honest limitation)

x86_64 source-level + real-binary verification was completed on
2026-08-25; Android arm64 on-device execution of the rebuilt pair was not
possible in that environment and remains an open verification item.
