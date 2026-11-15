# Uptodown Listing

**Status:** `WAITING_FOR_USER` — free Developers Console account and acceptance
of the developer terms.
**Portal:** https://www.uptodown.com/consola-de-desarrolladores
**Sign-up:** https://www.uptodown.dev/sign-up
**Developer terms:** https://en.uptodown.com/developers-zone/services

---

## Listing fields

| Field | Value |
|---|---|
| App name | `Linexa — Linux Workspaces` |
| Package | `com.fem.linexa` |
| Category | Tools / Utilidades |
| Price | Free |
| In-app purchases | None |
| Privacy policy | https://forexample25.github.io/Linexa/privacy.html |
| Support | https://github.com/ForExample25/Linexa/issues |
| Website | https://forexample25.github.io/Linexa/ |

The console is Spanish-first. Both languages are prepared so the listing can
be entered without machine translation.

### Short description — English

```
Run Linux workspaces on Android with a terminal, files, and desktop.
```

### Short description — Spanish

```
Ejecuta espacios de trabajo Linux en Android con terminal, archivos y escritorio.
```

### Full description — English

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

### Full description — Spanish

```
Linexa lleva entornos Linux a Android, sin root.

Crea espacios de trabajo aislados, instala un sistema de archivos raíz de Linux, abre una terminal PTY completa, gestiona archivos y paquetes, y conéctate a un escritorio gráfico. Cada espacio de trabajo mantiene sus propios archivos, sesiones y ajustes.

QUÉ PUEDES HACER
• Espacios de trabajo — ejecuta varios entornos Linux aislados y cambia entre ellos.
• Instalador RootFS — importa un archivo .tar.gz, .tar.xz o .tar.zst, o descarga Alpine, Ubuntu, Arch Linux ARM, Debian o Fedora desde el catálogo integrado.
• Terminal real — una PTY persistente con renderizado ANSI/cursor, historial, selección de texto, copiar/pegar, zoom con pellizco y una fila de teclas extra.
• Escritorio — conéctate mediante el cliente VNC integrado o un WebView apuntado a un servidor web del invitado. El modo VNC requiere Xvnc en el invitado; Debian y Ubuntu tienen un instalador guiado.
• Gestor de paquetes — busca, instala, actualiza y elimina paquetes con apt, pacman, apk, dnf o zypper.
• Gestor de archivos — explora, copia, renombra, elimina, importa y edita archivos, con resaltado de sintaxis y un proveedor de Storage Access Framework.
• Sesiones y tareas — mantén varias sesiones de terminal, ejecuta trabajos largos en segundo plano e inspecciona o detén procesos del espacio de trabajo.
• Redes — DNS por espacio de trabajo, /etc/hosts, réplicas de paquetes y una réplica RootFS personalizada. Las sumas de comprobación siguen viniendo de la fuente oficial.
• Local primero — los metadatos de los espacios de trabajo viven en Room en tu dispositivo. Sin cuenta, sin dependencia de la nube.

BUENO SABER
• Software beta: las interfaces, los invitados compatibles y el comportamiento en el dispositivo pueden cambiar.
• Android 9 o posterior, en dispositivos arm64-v8a o armeabi-v7a.
• No se requiere root. PRoot proporciona un entorno de invitado en espacio de usuario.
• Linexa es software libre bajo GPL-3.0. Los componentes de terceros conservan sus propias licencias; consulta Ajustes → Acerca de → Licencias de código abierto.
• Código y seguimiento de incidencias: github.com/ForExample25/Linexa
```

---

## Graphics

| Asset | Spec | Path |
|---|---|---|
| Icon | 512×512 PNG | `assets/store-icon/icon-512.png` |
| Feature graphic | 1024×500 PNG | `assets/feature-graphic/feature-graphic-1024x500.png` |
| Screenshots | 1080×1920, 8 images | `assets/screenshots/phone/` |
| Promo video | None | Optional |

Uptodown's upload form is the source of truth for size limits. The prepared
phone screenshots are 9:16 and unmodified apart from padding; if the form
requires landscape, use the Amazon set.

---

## Technical data

| Field | Value |
|---|---|
| Artifact | `app-release.apk` |
| Size | 57,257,408 bytes |
| SHA-256 | `6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680` |
| Version | `0.1.0-beta` (1) |
| minSdk / targetSdk | 28 / 34 |
| ABIs | arm64-v8a, armeabi-v7a |
| Signature | v2, valid |
| Debuggable | No |

Uptodown hosts the binary and serves it through its own download flow. The
developer console may add a Uptodown downloader/installer wrapper; that is a
distribution-layer change, not a change to Linexa's code. Verify the final
public download's SHA-256 against the developer-uploaded APK after publication,
and record any difference in `../PUBLICATION_STATUS.md`.

### Review notes

- Free software under GPL-3.0-only; source and issue tracker are public.
- No analytics, ads, accounts, or tracking SDKs.
- The app downloads Linux root filesystems only after an explicit user action.
- The app does not request root or install other Android apps.
- Optional all-files access is explained in the listing and the privacy policy.

Uptodown's developer terms are governed by Spanish law. The owner must read and
accept them; the agent has not accepted any agreement on the owner's behalf.

---

## Submission steps

1. Create a Developers Console account at https://www.uptodown.dev/sign-up
   with the owner's email; verify it.
2. Accept the developer terms as the owner.
3. Submit the app: name, package, category, price (free), privacy URL, support
   URL.
4. Upload `app-release.apk`; wait for the scan/QA result.
5. Add the English and Spanish descriptions, icon, screenshots, and feature
   graphic.
6. Submit for review; answer QA questions with the reviewer instructions from
   `metadata/google-play.md`.
7. Record the public Uptodown URL and the hosted APK's SHA-256 after approval.

Do not claim publication until the public Uptodown page resolves.
