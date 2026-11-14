# Third-Party Notices and Corresponding Source

**Linexa 0.1.0-beta** (`versionCode` 1)

Copyright (C) 2026 ForExample25.

Linexa's own source is licensed under the GNU General Public License,
version 3 only (`GPL-3.0-only`). The complete license text is in
[`LICENSE`](./LICENSE). That license covers Linexa's own source code only.
The third-party components listed below remain under their own licenses.
Copies published before 2026-11-14 were offered under `GPL-3.0-or-later`;
that grant remains available for those copies.

This file records every third-party project whose code or artwork is
distributed in the Linexa APK, with the version shipped, the license, the
upstream source, and the required copyright notices. The same component list
is shown in the app at **Settings → About → Open source licenses**, together
with the full license texts. The machine-readable catalog is
`app/src/main/java/com/fem/linexa/domain/attribution/AttributionCatalog.kt`;
the native binary pinning record is
`app/src/main/jniLibs/BINARY-MANIFEST.md`.

## 1. Bundled native programs and libraries

Linexa ships 20 native artifact files for each supported ABI (`arm64-v8a` and
`armeabi-v7a`), covering 17 upstream projects, in
`app/src/main/jniLibs/`. Linexa does not compile these programs; it conveys
builds from the
termux-packages lineage, except for the PRoot pair, which Linexa rebuilt from
the termux source tag with the patches recorded below.

| Project | Version shipped | License | Upstream source |
|---|---|---|---|
| PRoot (`libproot.so`, tracee loader) | 5.1.107.89 + Linexa patches 0001–0004 | `GPL-2.0-or-later` | <https://github.com/termux/proot> |
| BusyBox (`bin/busybox`, `libbusybox.so.1.38.0`) | 1.38.0 | `GPL-2.0-only` | <https://git.busybox.net/busybox/> |
| GNU tar | 1.35 | `GPL-3.0-or-later` | <https://www.gnu.org/software/tar/> |
| Zstandard (`zstd`, `libzstd.so.1`) | 1.5.7 | `BSD-3-Clause` (selected; upstream also offers `GPL-2.0`) | <https://github.com/facebook/zstd> |
| tmux | 3.7b | `ISC` | <https://github.com/tmux/tmux> |
| acl (`libacl.so`) | 2.4.0 | `LGPL-2.1-or-later` | <https://savannah.nongnu.org/projects/acl/> |
| libandroid-glob | 0.6 | `BSD-3-Clause` | <https://github.com/termux/termux-packages/tree/master/packages/libandroid-glob> |
| libandroid-selinux | 14.0.0.11 | Public domain | <https://github.com/termux/termux-packages/tree/master/packages/libandroid-selinux> |
| libandroid-support | 29 | `Apache-2.0` and `MIT` | <https://github.com/termux/termux-packages/tree/master/packages/libandroid-support> |
| GNU libiconv (`libiconv.so`) | 1.18 | `LGPL-2.1-or-later` | <https://www.gnu.org/software/libiconv/> |
| PCRE2 (`libpcre2-8.so`) | 10.47 | `BSD-3-Clause WITH PCRE2-exception` | <https://github.com/PCRE2Project/pcre2> |
| zlib (`libz.so.1`) | 1.3.2 | `Zlib` | <https://zlib.net/> |
| liblzma (XZ Utils) | 5.8.3 | `0BSD` | <https://github.com/tukaani-project/xz> |
| ncurses (`libncursesw.so.6`) | 6.6 | `MIT` | <https://invisible-island.net/ncurses/> |
| libevent (`libevent_core-2.1.so`) | 2.1.13 | `BSD-3-Clause` | <https://libevent.org/> |
| talloc (`libtalloc.so.2`) | 2.4.3 (arm64-v8a), 2.4.2 (armeabi-v7a) | `LGPL-3.0-or-later` | <https://talloc.samba.org/> |
| libandroid-shmem | 0.7 | `BSD-3-Clause` | <https://github.com/termux/libandroid-shmem> |

`lib__bin__loader__.so` is PRoot's tracee-side loader and is part of the PRoot
project; it is not a separate component.

`tmux` is currently not on the terminal launch path, but it is present in the
APK and is therefore listed here.

### 1.1 Copyright notices and required statements

- **PRoot** — Copyright (C) STMicroelectronics and the PRoot contributors.
  The original author is Cédric Vincent; the upstream `COPYING` file names
  the STMicroelectronics contributors. The exact source is the
  `termux/proot` tag `v5.1.107.89`, commit
  `a89b3732ec6ae1db674510f0843b2f3db54d0a2f`, plus the four patches in
  `app/src/main/jniLibs/proot-build/`. The build recipe, flags, and
  verification commands are in
  `app/src/main/jniLibs/proot-build/BUILD-NOTES.md`.
- **BusyBox** — Copyright (C) 1998-2024 Erik Andersen, Rob Landley,
  Denys Vlasenko and contributors. BusyBox states that version 2 of the GPL
  is the only version under which it may be distributed.
- **GNU tar** — Copyright (C) 1988-2024 Free Software Foundation, Inc.
- **Zstandard** — Copyright (c) Meta Platforms, Inc. and affiliates.
  Linexa selects the BSD-3-Clause option.
- **tmux** — Copyright (c) Various Authors.
- **acl** — Copyright (C) 1999-2002 Andreas Gruenbacher and contributors.
  The shipped library is `LGPL-2.1-or-later`; the GPL applies to the acl
  command-line tools, which Linexa does not ship.
- **libandroid-glob** — Copyright (c) 1989, 1993 The Regents of the
  University of California; derived from software contributed by Guido van
  Rossum; later adaptions by Natanael Arndt and the Termux project.
- **libandroid-selinux** — Android's libselinux fork. The SELinux userland
  code is public domain; there is no license text to reproduce.
- **libandroid-support** — Copyright (c) the Termux project contributors.
  The library contains Apache-2.0 support code and an MIT-licensed `wcwidth`
  implementation; both licenses apply to their respective parts.
- **GNU libiconv** — Copyright (C) 1998-2024 Free Software Foundation, Inc.
  The shipped part is the library (`LGPL-2.1-or-later`); the GPL applies to
  the `iconv` program, which Linexa does not ship.
- **PCRE2** — Copyright (c) 1997-2024 University of Cambridge; Copyright (c)
  2007-2024 Philip Hazel; Copyright (c) 2010-2024 Zoltan Herczeg; other
  contributions retain their authors' copyright. Distributed under
  `BSD-3-Clause WITH PCRE2-exception`. The exception is reproduced with the
  license text.
- **zlib** — Copyright (C) 1995-2024 Jean-loup Gailly and Mark Adler.
- **liblzma (XZ Utils)** — Copyright (C) Lasse Collin, Igor Pavlov and
  contributors. The shipped library is `0BSD`; XZ Utils is a mixed-license
  package, and its GPL-licensed scripts are not included.
- **ncurses** — Copyright 1998-2018 Free Software Foundation, Inc.;
  Copyright 2018-2024 Thomas E. Dickey. The compiled terminfo entries in
  `app/src/main/assets/terminfo` are ncurses data files.
- **libevent** — Copyright (c) 2000-2007 Niels Provos; Copyright (c) 2007-2012
  Niels Provos and Nick Mathewson.
- **talloc** — Copyright (C) Andrew Tridgell 2004; Copyright (C) Stefan
  Metzmacher 2006; and other Samba Team contributors. The shipped library is
  `LGPL-3.0-or-later`.
- **libandroid-shmem** — Copyright (c) 2013 Sergii Pylypenko; Copyright (c)
  2017 Fredrik Fornwall.

### 1.2 License compatibility with GPL-3.0-only

Linexa's own source is GPL-3.0-only. The third-party components remain under
their own licenses; this summary records why the aggregate can be conveyed
alongside GPLv3 code. It does not replace the license texts.

- **GPL-2.0-or-later** (PRoot): the "or later" option permits use under GPLv3.
- **GPL-3.0-or-later** (GNU tar): directly compatible with GPLv3.
- **GPL-2.0-only** (BusyBox): conveyed as a separate executable (`bin/busybox`
  plus the `libbusybox.so` it loads) and invoked as a host process. It is not
  linked into the Linexa app; it remains GPL-2.0-only, and its complete source
  is offered separately under section 5.
- **Apache-2.0** (Termux terminal modules and app dependencies): compatible
  with GPLv3.
- **LGPL-2.1-or-later / LGPL-3.0-or-later** (acl, talloc, and other LGPL
  libraries): the LGPL permits conveying the combined work under GPLv3 when
  the LGPL notices and corresponding-source offer are preserved.
- **Permissive licenses** (BSD-3-Clause, MIT, ISC, 0BSD, Zlib, public domain):
  compatible with GPLv3.
- **Zstandard** is conveyed under its BSD-3-Clause option.

No third-party component is relicensed under Linexa's GPL-3.0-only. All
copyright notices and license texts remain in this file and in
`app/src/main/assets/licenses/`.

## 2. App dependencies compiled into the APK

| Project | Version | License | Upstream source |
|---|---|---|---|
| Termux terminal-emulator (vendored module) | termux-app v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c` | `Apache-2.0` | <https://github.com/termux/termux-app> |
| Termux terminal-view (vendored module) | termux-app v0.118.3, commit `5b657c6adf4304e5198951ce815fe0205dcac29c` | `Apache-2.0` | <https://github.com/termux/termux-app> |
| zstd-jni | 1.5.6-5 | `BSD-2-Clause` | <https://github.com/luben/zstd-jni> |
| Apache Commons Compress | 1.26.1 | `Apache-2.0` | <https://commons.apache.org/proper/commons-compress/> |
| SnakeYAML | 2.2 | `Apache-2.0` | <https://bitbucket.org/snakeyaml/snakeyaml/> |
| XZ for Java | 1.9 | Public domain | <https://tukaani.org/xz/java.html> |
| Kotlin Standard Library | 2.1.20 | `Apache-2.0` | <https://github.com/JetBrains/kotlin> |
| kotlinx.coroutines | 1.8.1 | `Apache-2.0` | <https://github.com/Kotlin/kotlinx.coroutines> |
| kotlinx.serialization | 1.6.3 | `Apache-2.0` | <https://github.com/Kotlin/kotlinx.serialization> |
| AndroidX (Core, Activity, Lifecycle, Navigation, Room) | pinned per artifact in `app/build.gradle.kts` | `Apache-2.0` | <https://github.com/androidx/androidx> |
| Jetpack Compose and Material 3 | Compose BOM 2024.06.00 | `Apache-2.0` | <https://github.com/androidx/androidx> |
| Dagger and Hilt | 2.56.2 | `Apache-2.0` | <https://github.com/google/dagger> |

The two Termux terminal modules are vendored verbatim from the same upstream
commit. The `terminal-view` table row above deliberately repeats the
`terminal-emulator` version; see `terminal-emulator/PROVENANCE.md` and
`terminal-view/PROVENANCE.md` for the exact file list and the local Gradle
wrappers. The upstream repository is GPLv3, but its `LICENSE.md` names
`terminal-emulator` and `terminal-view` explicitly as the Apache-2.0
exception (the Jackpal Android-Terminal-Emulator lineage).

The Java/Kotlin test-scope dependencies (JUnit, Mockito, Robolectric,
`kotlinx-coroutines-test`, and the JVM `zstd-jni` jar) are not included in
the APK and are not listed as distributed components.

### 2.1 Terminal module NOTICE

The vendored Termux modules carry this notice:

> Termux terminal-emulator / terminal-view
>
> Copyright (c) 2012-2024 the Termux project contributors (termux/termux-app)
> and the Jackpal Android-Terminal-Emulator lineage this library derives
> from.
>
> Vendored into the Linexa project from termux/termux-app, tag v0.118.3,
> commit 5b657c6adf4304e5198951ce815fe0205dcac29c. Licensed under the Apache
> License, Version 2.0.
>
> This product includes software developed as part of the Termux project
> (<https://github.com/termux/termux-app>).

## 3. Icon artwork

| Project | License | Upstream source |
|---|---|---|
| Material Symbols | `Apache-2.0` | <https://github.com/google/material-design-icons> |
| Devicon | `MIT` | <https://github.com/devicons/devicon> |
| Simple Icons | `CC0-1.0` | <https://github.com/simple-icons/simple-icons> |
| gilbarbara/logos | `CC0-1.0` | <https://github.com/gilbarbara/logos> |

The `ic_custom_*` drawables are derived from Material Symbols path data.
The `ic_distro_*` drawables combine Devicon's official-color vectors, Simple
Icons' monochrome silhouettes, and the gilbarbara/logos collection's
multicolor marks. Distribution names and logos are trademarks of their
respective owners; they are used only to identify the distribution a
workspace runs. CC0-1.0 does not grant trademark rights.

## 4. Full license texts

The verbatim texts shipped in the APK are under
`app/src/main/assets/licenses/`:

| SPDX identifier | File |
|---|---|
| `0BSD` | `0BSD.txt` |
| `Apache-2.0` | `Apache-2.0.txt` |
| `BSD-2-Clause` | `BSD-2-Clause.txt` |
| `BSD-3-Clause` | `BSD-3-Clause.txt` |
| `BSD-3-Clause WITH PCRE2-exception` | `BSD-3-Clause-WITH-PCRE2-exception.txt` |
| `CC0-1.0` | `CC0-1.0.txt` |
| `GPL-2.0-only` and `GPL-2.0-or-later` | `GPL-2.0-or-later.txt` |
| `GPL-3.0-or-later` | `GPL-3.0-or-later.txt` |
| `ISC` | `ISC.txt` |
| `LGPL-2.1-or-later` | `LGPL-2.1-or-later.txt` |
| `LGPL-3.0-or-later` | `LGPL-3.0-or-later.txt` |
| `MIT` | `MIT.txt` |
| `Zlib` | `Zlib.txt` |

`GPL-2.0-only` and `GPL-2.0-or-later` are different grants over the same
license document, which is why they share one file. Public-domain components
have no document to reproduce and are identified as such in the app.

## 5. Corresponding source — written offer

Linexa conveys the GPL- and LGPL-licensed binaries listed in section 1 in
object-code form inside the APK. Their complete corresponding source is
available as follows:

- **PRoot** — upstream `termux/proot` tag `v5.1.107.89`, commit
  `a89b3732ec6ae1db674510f0843b2f3db54d0a2f`, with Linexa patches
  0001–0004 in `app/src/main/jniLibs/proot-build/`; build flags and
  verification commands are in `BUILD-NOTES.md` in that directory.
- **BusyBox, GNU tar, acl, GNU libiconv, talloc, PCRE2, ncurses, libevent,
  zlib, liblzma, libandroid-glob, libandroid-shmem and libandroid-support**
  — the upstream releases named in section 1, built with the termux-packages
  recipes for the pinned versions. The recipes are public at
  <https://github.com/termux/termux-packages/tree/master/packages>.
- **Linexa's own source** — the repository that contains this file, which is
  the complete source of the GPL-3.0-only application.

**Written offer.** Linexa hereby offers, valid for at least three years from
the date of the 0.1.0-beta release (2026-11-14), to give any third party a
complete machine-readable copy of the corresponding source code for the
GPL- and LGPL-licensed binaries listed above, on a medium customarily used
for software interchange, for a charge no more than the cost of physically
performing source distribution. To request it, open an issue at the
project's GitHub issue tracker (the repository that contains this file) and
state which component and version you need. This offer is made in addition
to, not instead of, the source links above.

## 6. No warranty

The third-party components are distributed in the hope that they will be
useful, but **WITHOUT ANY WARRANTY**; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. Each component's
license contains its own disclaimer. See the full texts in section 4.
