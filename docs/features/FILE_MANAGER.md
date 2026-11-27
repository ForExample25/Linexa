# File Manager

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/filemanager/`,
`app/src/main/java/com/fem/linexa/domain/filemanager/`,
`app/src/main/java/com/fem/linexa/ui/misc/LinexaDocumentsProvider.kt`,
`app/src/main/java/com/fem/linexa/service/runtime/WorkspaceFileManager.kt`

---

## 1. Purpose

The file manager browses and edits files inside a workspace's RootFS, browses
shared Android storage through the Storage Access Framework (SAF), and exposes
installed workspace rootfs trees and the shared storage mount as SAF document
providers. The same
`FileManager` interface also provides the guest-side primitives used by the
installer and other features.

---

## 2. Scope

In scope:

- File manager screen, selection, copy/cut/paste, rename, delete, search, sort
- File creation from templates and folder creation
- Text editor with syntax highlighting and dirty-state tracking
- Text import/export and read-only file viewer
- SAF document provider (`LinexaDocumentsProvider`)
- Guest-side file operations (`FileManager`, `WorkspaceFileManagerImpl`)

Out of scope:

- RootFS installation: [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md)
- Storage summary panels: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Architecture

| Component | Responsibility |
|---|---|
| `FileManagerScreen` | Compose UI: breadcrumbs, list, create/rename/delete actions, editor and viewer overlays |
| `FileManagerViewModel` | Navigation state, selection, search/sort, transfer clipboard, import/export, editor state |
| `FileManager` / `WorkspaceFileManagerImpl` | One-shot guest commands for list/read/write/create/delete/rename/copy/stat |
| `WorkspaceFileManager` | RootFS path resolution, storage measurement, and guest filesystem helpers |
| `LinexaDocumentsProvider` | SAF `DocumentsProvider` for installed workspace rootfs trees and the shared mount |
| `SyntaxHighlighter` | Token coloring for the text editor |

`FileManager` is bound to `WorkspaceFileManagerImpl` in `di/AppModule.kt`.
`WorkspaceFileManager` is a service-level type used by the runtime supervisor
and installer; it is exposed to the domain through narrow interfaces such as
`WorkspaceDiskStore` and `RootfsInspector`.

### 3.1 File listing

When the workspace rootfs is known and the path is not under a PRoot bind
mount, `listFiles` reads the directory directly from the host rootfs through
`HostFileAccess` and returns sorted entries with metadata. This avoids a PRoot
launch per navigation (F-003 / ADR-19).

Otherwise it runs `LC_ALL=C ls -lA -- '<path>'` in the guest and parses the
result with `LsLongFormatParser`. Paths are single-quoted and passed after
`--`, so names beginning with `-` are treated as operands. A non-zero exit
returns `FileOpResult.Failure` with the guest's own error text rather than an
empty list.

The host fast path covers `list`, `read`, `stat`, `exists` and regular-file
`size` only. It resolves guest paths component-by-component with `NOFOLLOW`:
an absolute symlink target is re-anchored at the rootfs, a relative target is
resolved in the same stack, `..` never climbs above `/`, and expansion is
bounded at 40 hops. A cycle, a relative input path, an unknown rootfs, or any
path under a bind mount falls back to the guest. Mutations never use the host
path.

Bind-mounted guest prefixes are excluded because their host directory is not
what PRoot shows the guest: `/dev`, `/proc`, `/sys`, `/run`, `/mnt/shared`,
and every `linexa.launch.bindMounts` destination from `config.yaml`.

### 3.2 Reading and writing

- **Read:** on the host fast path, the file is read as bytes and decoded as
  UTF-8. On the guest fallback, `base64 -- '<path>'` is decoded host-side,
  which keeps trailing whitespace and empty files byte-exact even though the
  one-shot exec bridge trims captured output. Invalid base64 fails the read
  instead of returning corrupted text.
- **Write:** a quoted heredoc (`cat > '<path>' <<'LINEXA_EOF'`) whose
  delimiter is chosen so it cannot occur in the content. The body is passed
  verbatim: no variable, backtick, or backslash expansion. Empty content
  produces a truly empty file; content that does not end in a newline gets
  exactly one added because the heredoc terminator must start a line.
- **Other operations:** `mkdir`, `touch`, `rm -rf`, `mv -n`, `cp -r`, and
  `du -sk` follow the same rule: assert the expected preconditions, pass the
  guest's failure through, and report success only on exit code 0.

### 3.3 Editor and save pipeline

The editor keeps one authoritative buffer in `FileEditorState.text`. The
Compose text field owns IME and selection state and reports changes through
`onTextChange`; it is initialized once when asynchronous content arrives.

Saving:

```
save tap
  → saving = true synchronously
  → snapshot textToWrite = current text
  → FileManager.writeFile(..., textToWrite)
  → guest heredoc runs to completion; exit code becomes FileOpResult
  → success: current (path-guarded) state gets original = textToWrite
             and saving = false; text typed during the write stays dirty
  → failure: saving = false, buffer intact, guest reason shown
```

Reopening a file reads the bytes on disk; no cached buffer is reused. Read
completions are path-guarded so a late result cannot populate a different
file's editor or reopen a closed one.

### 3.4 Import, export, viewing

Text import is capped at `MAX_IMPORT_BYTES` (512 KiB), rejects NUL-containing
content, and writes through the same heredoc path. Export uses the SAF create
flow. The read-only viewer is used for content that is too large to edit or is
detected as binary; editing eligibility is otherwise based on the file type.

### 3.5 SAF document provider

`LinexaDocumentsProvider` exposes two roots: `Linexa`, with one entry per
installed workspace whose status is `Ready` and whose rootfs passes the launch
validator, and `Public`, the shared mount bound into every guest at
`/mnt/shared`. Directories advertise create support; files advertise write,
delete, and rename. `openDocument` supports read and write modes.
`isChildDocument` lets clients test containment.

Document IDs are validated by `LinexaDocumentsMapping`: no `..`, no absolute
paths, and no empty segments. Every resolved `File` is checked canonically
against its root before use, and create/rename names are validated before any
filesystem call.

Mutations are refused while the workspace runtime is `Starting`, `Running`, or
`Stopping` (`WorkspaceMutationPolicy`). This prevents writes racing a live
guest that may have the same files open or cached. The rootfs root itself is
never mutable through SAF, and the `Public` root stays writable because it is
the shared mount rather than workspace state.

### 3.6 Create menu

The "+" control is a sibling of the file list inside the screen's root `Box`,
anchored to the bottom center with navigation-bar padding. It is not a child
of the scrolling list, so it does not scroll away. A transparent scrim closes
the menu on an outside tap and blocks taps to rows and header actions while
the menu is open.

---

## 4. Limitations

- **Guest-write size ceiling.** A write travels through `sh -c` as one argv
  element, heredoc included. The kernel limits a single argument to roughly
  128 KiB (`MAX_ARG_STRLEN`), so larger editor buffers fail at exec with
  `E2BIG`. The UI reports the failure; it never truncates silently. The editor
  and import caps (512 KiB) are above this kernel limit, so very large text
  files can still hit it.
- **Text-only writes.** The guest write path is a shell heredoc. Binary
  content is not editable or importable; import rejects NUL bytes.
- **UTF-8 decoding.** Reads are decoded as UTF-8 host-side. Files in other
  encodings may not round-trip.
- **Android storage browsing.** Shared-storage access depends on the SAF tree
  grant the user selected; without it only app-private locations are visible.
  The overview's all-files path (`MANAGE_EXTERNAL_STORAGE`) exists only on
  Android 11+; on Android 9/10 the app declares no legacy broad-storage
  permission, so the Android-storage section reports access as not granted and
  SAF import/export remains the supported route.

---

## 5. Related documentation

- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — RootFS layout and installation
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — runtime and the one-shot exec bridge still used for mutations and bind-mounted paths
- [`decisions/ADR_REGISTER.md`](../decisions/ADR_REGISTER.md) — ADR-19, the host fast path and its exclusions
- [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md) — workspace storage summary
