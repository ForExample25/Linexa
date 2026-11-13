# README-tmux

tmux is vendored (`lib__bin__tmux__.so`, see
[BINARY-MANIFEST.md](BINARY-MANIFEST.md)) but is out of scope on the
launch path — a deferred, opt-in, device-gated optional backend. The
workspace terminal is a direct proot pty via `WorkspaceTerminalManager`
(see root `README.md`); the binary is dead code on the launch path.
