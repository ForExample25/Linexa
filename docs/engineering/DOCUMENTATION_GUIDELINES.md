# Documentation guidelines

F-031 found that design-history KDoc in service/runtime and installer classes
often exceeded the code it documented and duplicated rationale that drifts.
The rule below keeps the contract where a reader needs it and moves history
where it can be maintained.

## The rule

1. **Declaration KDoc states the contract.** What the type/function does, its
   inputs and outputs, its failure modes, and any invariant a caller must
   preserve. Aim for ≤ 15 lines.
2. **Design history and audit narrative go to `docs/decisions/`** (or the
   relevant `docs/architecture/`, `docs/features/` page) and the KDoc links to
   it. A finding id (for example `F-003`) may appear in one line as a pointer,
   not as a retelling.
3. **Do not restate the implementation.** A comment that repeats the next
   three lines is noise; a comment that explains *why* a non-obvious choice was
   made is not.
4. **Keep security and protocol contracts at the declaration.** Shell quoting,
   path containment, wire formats and replay guards are contracts, not
   history. They stay, even when long.
5. **Delete stale references.** When a section number or audit path moves, the
   comment moves with it or is removed. A dead pointer is worse than no
   pointer.

## Applying it

New code follows the rule. Existing classes are trimmed opportunistically when
they are already being edited; this is a ratchet, not a mass rewrite. The
decision register records the rationale that was moved out, so the information
is still discoverable.

## Where rationale lives

- `docs/decisions/ADR_REGISTER.md` — architectural decisions and their costs.
- `docs/architecture/` — how the current system is put together.
- `docs/features/` — user-visible behaviour and its implementation notes.
- `docs/audits/` — historical audits; immutable once published.
- `audit/remediation/` — the 2026-11 audit remediation register and evidence.
