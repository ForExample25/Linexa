# Linexa bundled binary manifest

Every native binary the app ships in `jniLibs/`, with source, version, and
verified SHA-256 per ABI. All binaries are termux-packages lineage and were
verified twice: SHA-256 at download time against the termux apt index
(`packages-cf.termux.dev`, 2026-08-02), and again when vendored into this
repository.

All are Android ELF, NDK-built, and dynamically linked for API 24+, except
`lib__bin__loader__.so`, which is PRoot's tracee-side static loader stub.

The `libproot.so` / `lib__bin__loader__.so` pair is rebuilt from the
`termux/proot` tag `v5.1.107.89`, commit
`a89b3732ec6ae1db674510f0843b2f3db54d0a2f`, with the four Linexa patches in
`third_party/proot/` applied. The patches add the empty-pathname `readlinkat` guard,
resolve link2symlink targets relative to the link's own directory, fix the
`<string.h>` include required by NDK r29, and add the guest-environment filter.
Both ABIs use the same source lineage:

- **arm64-v8a** links against the unchanged vendored termux `libtalloc.so.2`
  (2.4.3) and `libandroid-shmem.so` (0.7).
- **armeabi-v7a** links against NDK-built talloc 2.4.2 and
  libandroid-shmem 0.7, because the previous static PRoot had no vendored
  runtime dependencies.

Build recipe: `third_party/proot/` patches over `termux/proot` tag `v5.1.107.89`,
termux `build.sh` flags (`PROOT_WITH_LIBANDROID_SHMEM=true`,
`PROOT_UNBUNDLE_LOADER` pointing at `{filesDir}/bootstrap/bin`,
`CPPFLAGS += -DARG_MAX=131072 -DVERSION="5.1.107.89"`). `loader` from the same
build is vendored as `lib__bin__loader__.so`; `PROOT_LOADER` always points
there at runtime. The rebuilt pair was verified end-to-end on x86_64 with the
same source and patches (real ptrace + real dpkg), using the test drivers in
`third_party/proot/`.

They must keep the `lib__*__.so` names: AGP auto-packages only `lib*.so`
jniLibs entries (see the `packaging { jniLibs }` block of
`app/build.gradle.kts`), and `BootstrapManager` re-maps them to friendly names
under `{filesDir}/bootstrap/{bin,lib}` at runtime.

## Binaries

| file | source pkg | version | arm64-v8a sha256 | armeabi-v7a sha256 |
|---|---|---|---|---|
| `lib__bin__busybox__.so` | busybox | 1.38.0-1 | `db7f2a84…c84058` | `4b318987…8a11e9` |
| `lib__bin__tar__.so` | tar | 1.35-3 | `66b641de…3c39cf` | `3729470b…8f33f` |
| `lib__bin__zstd__.so` | zstd | 1.5.7-1 | `2764819f…9f8` | `8702180f…4ce75` |
| `lib__bin__tmux__.so` | tmux | 3.7b | `d9d5479f…dee7` | `1e82b34b…8bb32` |
| `lib__bin__loader__.so` | PRoot loader from the Linexa 5.1.107.89 build (patches 0001–0004) | 5.1.107.89+linexa | `b5b856b8…7b451e` | `24ec27c8…5b90` |
| `libproot.so` | PRoot, termux-lineage 5.1.107.89 + Linexa patches 0001–0004 (NDK r29, dynamic, API 28+) | 5.1.107.89+linexa | `8a222000…857743` | `6f116962…480cd3` |

Full SHA-256 values for every artifact are pinned in
`app/src/main/assets/bootstrap-manifest.json`; the abbreviated values above
are proot `8a222000…857743` and loader `b5b856b8…7b451e` for arm64-v8a.

## Dependency libraries

| file | source pkg | version | arm64-v8a sha256 | armeabi-v7a sha256 |
|---|---|---|---|---|
| `lib__lib__libbusybox.so.1.38.0__.so` | busybox | 1.38.0-1 | `b8153ac1…57cc1` | `1a441814…74767` |
| `lib__lib__libacl.so__.so` | libacl | 2.4.0 | `650b7bea…a1563` | `2fb1e644…8bc55e` |
| `lib__lib__libandroid-glob.so__.so` | libandroid-glob | 0.6-3 | `e47405b2…e850` | `5653efd7…c854e` |
| `lib__lib__libandroid-selinux.so__.so` | libandroid-selinux | 14.0.0.11-1 | `d769bc37…a810` | `9e1b0da7…6dd0` |
| `lib__lib__libandroid-support.so__.so` | libandroid-support | 29-1 | `739cf829…4430` | `2d569da2…f999` |
| `lib__lib__libiconv.so__.so` | libiconv | 1.18-1 | `53349c7a…3667` | `1d4f74e0…9381` |
| `lib__lib__libpcre2-8.so__.so` | pcre2 | 10.47 | `a6bbeb41…f5c` | `34eec127…d4f5` |
| `lib__lib__libzstd.so.1__.so` | zstd | 1.5.7-1 | `1677d5e8…74f7` | `50e5f96b…c17b` |
| `lib__lib__libz.so.1__.so` | zlib | 1.3.2 | `c47448ed…b711` | `da58b50b…ac90` |
| `lib__lib__liblzma.so.5__.so` | liblzma | 5.8.3 | `daa0cf43…588b` | `a3466368…9e54e` |
| `lib__lib__libncursesw.so.6__.so` | ncurses | 6.6 | `795f855f…c4c5` | `e7c301bc…ca9` |
| `lib__lib__libevent_core-2.1.so__.so` | libevent | 2.1.13 | `049d2d08…eadc` | `ea5e49da…cd67` |
| `lib__lib__libtalloc.so.2__.so` | libtalloc | 2.4.3 (arm64-v8a) / 2.4.2 (armeabi-v7a, NDK build) | `3c9b207c…6fbb` | `a4f0c168…dbf0b5` |
| `lib__lib__libandroid-shmem.so__.so` | libandroid-shmem | 0.7 | `84475798…e731` | `488e7365…52e79` |

Hashes are abbreviated to 8+4 characters. Recompute the full values with
`sha256sum` when bumping a binary.

## Dependency closure (readelf NEEDED)

Everything below resolves to Android system libraries (`libc.so`, `libm.so`,
`liblog.so`, `libdl.so`) or to the vendored set above — nothing else is
missing:

- busybox → `libbusybox.so.1.38.0`, libc (note: `bin/busybox` is a small
  launcher; the real applets live in `libbusybox.so.1.38.0`)
- tar → libandroid-glob, libacl, libandroid-selinux, libiconv, libc
- zstd → libzstd.so.1, libz.so.1, liblzma.so.5, libc
- tmux → libandroid-support, libandroid-glob, libncursesw.so.6,
  libevent_core-2.1.so, libm, libc
- proot (arm64-v8a) → libtalloc.so.2 (2.4.3), libandroid-shmem.so (0.7),
  libdl.so, libc.so (bionic; interpreter `/system/bin/linker64`)
- proot (armeabi-v7a) → libtalloc.so.2 (2.4.2 NDK build),
  libandroid-shmem.so (0.7 NDK build), libdl.so, libc.so (bionic;
  interpreter `/system/bin/linker`)
- loader (both ABIs) → statically linked, no dependencies

## Licenses

termux-packages ships binaries under their upstream licenses; this set is
mixed (BusyBox GPL-2.0-only, GNU tar GPL-3.0-or-later, Zstandard
BSD-3-Clause, tmux ISC, PCRE2 BSD-3-Clause WITH PCRE2-exception, and the
remaining libraries LGPL-2.1-or-later, LGPL-3.0-or-later, MIT, 0BSD, zlib,
or public domain). The rebuilt PRoot binaries are **GPL-2.0-or-later**
(PRoot's actual grant), built from the termux source tag with the Linexa
patches in `third_party/proot/`. The complete component-by-component record,
copyright notices, and corresponding-source offer are in the repository-root
`THIRD_PARTY_NOTICES.md`.

## Regenerating

1. **proot pair (both ABIs):** fetch `termux/proot` tag `v5.1.107.89`,
   apply `third_party/proot/000{1,2,3,4}-*.patch` in order, and build with the
   termux `build.sh` flags. arm64-v8a links the vendored termux
   `libtalloc.so.2` / `libandroid-shmem.so`; armeabi-v7a builds talloc 2.4.2
   and libandroid-shmem 0.7 from source with the NDK. Verify with the two
   test drivers in `third_party/proot/`.
2. **Everything else:** fetch
   `https://packages-cf.termux.dev/apt/termux-main/dists/stable/main/binary-{aarch64,arm}/Packages`,
   download the debs for the versions above, and compare `sha256sum` with the
   index's `SHA256` field.
3. `dpkg-deb -x`; copy `data/data/com.termux/files/usr/{bin,lib}/…` to
   `jniLibs/<abi>/` with the `lib__…__` names above.
4. Update this table, `app/src/main/assets/bootstrap-manifest.json`, and
   `BootstrapManager`'s soname/applet lists.
