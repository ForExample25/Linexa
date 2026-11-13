# File Manager

**Status:** Current — describes the implemented file manager, verified against source.

**Project:** Linexa Linux Workspace (`com.fem.linexa`)

**Source locations:** `app/src/main/java/com/fem/linexa/ui/screens/filemanager/`, `app/src/main/java/com/fem/linexa/domain/filemanager/`, `app/src/main/java/com/fem/linexa/ui/misc/LinexaDocumentsProvider.kt`, `app/src/main/java/com/fem/linexa/service/installer/WorkspaceFileManagerImpl.kt`

---

## 1. Purpose

This document is the feature-level reference for Linexa's file manager: browse, copy/paste, rename, delete, import, editor with syntax highlighting, SAF write pipeline, and the save pipeline.

---

## 2. Scope

In scope:

- File manager screen and ViewModel (browse, copy/paste, rename, delete, import, editor)
- `SyntaxHighlighter` for the editor
- `LinexaDocumentsProvider` (SAF `DocumentsProvider`)
- `WorkspaceFileManagerImpl` (read/write, base64 transport)
- Save pipeline fixes (editor synchronization, atomic save, byte-exact reads, empty-file handling)
- "+" button anchoring and outside-tap scrim

Out of scope:

- RootFS installation: [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md)
- Storage UI panels: [`features/WORKSPACE_AND_SESSIONS.md`](./WORKSPACE_AND_SESSIONS.md)

---

## 3. Current implementation status

| Area | Status | Evidence |
|---|---|---|
| File manager browse/copy/paste/rename/delete/import | Implemented | `ui/screens/filemanager/FileManagerScreen.kt`, `FileManagerViewModel.kt` |
| Editor with `SyntaxHighlighter` | Implemented | `ui/screens/filemanager/` |
| SAF write pipeline | Implemented | `LinexaDocumentsProvider.kt` |
| `WorkspaceFileManagerImpl.readFile`/`writeFile` | Implemented | `service/installer/WorkspaceFileManagerImpl.kt` |
| Save pipeline (editor sync, atomic save, base64 reads, empty file) | Implemented | `FileManagerViewModel.kt`, `FileManagerScreen.kt`, `WorkspaceFileManagerImpl.kt` |
| "+" button anchored overlay with outside-tap scrim | Implemented | `FileManagerScreen.kt` |
| 128 KiB single-argv write ceiling (E2BIG, fails loudly) | Known limitation | `WorkspaceFileManagerImpl.kt` heredoc via `sh -c` |
| Binary files opened as text (name-based editability) | Known limitation | `FileManagerScreen.kt` |

---

## 4. Technical overview

### 4.1 Data flow (corrected save pipeline)

```
editor keystroke
  → field = onValueChange (composition-local, selection/IME owner)
  → onTextChange(field.text) → updateEditorText → state.text  (single authoritative buffer)
save tap
  → saveEditor(): saving=true (synchronous) + snapshot textToWrite = state.text
  → FileManager.writeFile(binder, ws, path, textToWrite)
      → quoted heredoc: cat > '<path>' <<'DELIM'…DELIM (no expansion, injection-safe)
  → execCommand → containerRuntime.exec → /bin/sh -c (guest) → exit code decides Result
on Ok
  → MutableStateFlow.update (current state, path-guarded): original = exactly the written
    bytes, saving = false; unsaved text typed during the write stays dirty (Modified)
on Failure/Exception
  → saving = false, buffer intact for retry, guest reason surfaced; never reported as success
reopen
  → editFile → readFile → base64-transported bytes decoded host-side = exactly the file on disk
```

Key invariants: one authoritative text state (`FileEditorState.text`), the save writes one immutable snapshot of that state, success/failure is only reported after the guest command's process has fully exited, and reopening reads raw disk bytes rather than any cache.

### 4.2 Root causes fixed

1. **Editor never received asynchronously loaded content.** `FileEditor` remembered its buffer on `state.path` only, initialized from the empty loading state (`text = ""`), and never re-synchronized when content landed. Opening a non-empty file rendered an empty editor; typing then overwrote the file with just the typed characters (silent data loss). Fixed: field remembers on `(state.path, state.loading)`, initialized exactly once when loaded content arrives.
2. **Stale save snapshot and unsynchronized `saving` flag.** `saveEditor` captured `state` before the write and clobbered newer buffers on completion; `saving` only ever went down. Fixed: `saving = true` set synchronously before launch; exact buffer snapshotted as bytes to write; completion updates the **current** state via `MutableStateFlow.update` (path-guarded), touching only `original` + `saving`.
3. **Empty saves produced a 1-byte `"\n"` file.** `writeFile` built the heredoc body as `content + "\n"` even for empty content. Fixed: empty content yields a truly empty heredoc body.
4. **`readFile` via `cat` lost trailing whitespace** (host-side `readText().trim()`). Fixed: reads via `base64 -- '<quoted path>'` decoded host-side — byte-exact reads; invalid payload fails loudly.
5. **Unguarded async completions.** Reads finishing after the user backed out could reopen a closed editor or load file A's content under file B's viewer. Fixed: completions guarded by `editorIsLoading(path)` / viewer-target checks; `CancellationException` rethrown.

### 4.3 "+" button

Already a correct anchored overlay: sibling of the file list inside the screen's root `Box` (`align(BottomCenter)`), never a child of the scrolling `LazyColumn`, with `navigationBarsPadding()` + fixed 34 dp offset, and the list's `contentPadding = 120.dp` keeps the last row clear. Fixed: while the create menu is open, a transparent outside-tap scrim closes the menu and blocks misdirected taps to rows/header actions. Position derivation is purely inset- and constraint-based (no hard-coded pixels).

### 4.4 SAF provider

`LinexaDocumentsProvider` is a SAF `DocumentsProvider` exposing a diagnostic rootfs browser. It is no longer read-only (create/delete/rename/write with canonical-path containment), though the manifest comment still says "read-only".

---

## 5. Limitations and known issues

- **Guest-write size ceiling:** every guest write travels through `sh -c` as a single argv element (heredoc included); Linux caps one argument at ~128 KiB (`MAX_ARG_STRLEN`), so buffers above roughly a hundred KiB fail at exec with E2BIG. The failure is loud (reported in the UI), never silent truncation. Removing the ceiling requires a new transport (e.g. bind-mounted temp file) — out of scope.
- **Binary files opened as text:** editability is decided by file name, not content, so a binary `.txt` still opens a garbled editor. A byte-sniff gate is a separate feature.
- File manager flows still need on-device verification; see [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md).

---

## 6. Related documentation

- [`architecture/DATA_AND_PERSISTENCE.md`](../architecture/DATA_AND_PERSISTENCE.md) — RootFS installation, `config.yaml`
- [`architecture/RUNTIME_ARCHITECTURE.md`](../architecture/RUNTIME_ARCHITECTURE.md) — `execInternal` one-shot bridge used by file writes
- [`engineering/TESTING_STRATEGY.md`](../engineering/TESTING_STRATEGY.md) — verification status
