# Working on this repository

Two lines of work share this repository: the Linux side, where the project is
developed, and the Windows port. These rules exist so that either can pull the other's
work without conflicts.

## Branches

- `main` is the Linux side and the reference. It must always build and run on Linux.
- The Windows port lives on `windows`, branched from `main`.
- `windows` takes `main` in often (`git merge main`, at least before each working
  session). Do not rebase `windows` once it is pushed.
- Windows work reaches `main` by pull request, in small pieces, each of which leaves
  Linux behaviour unchanged.
- Nobody force-pushes `main`.

## Who edits what

| Files | Rule |
|---|---|
| `ship/Protocol.h`, the offsets at the top of `ship/CompositeBridge.cpp`, `kart/CompositeBridge.cpp` and their mirrors in `fabric/` | The shared-memory layout. Changed on `main` only. If the port needs a change, ask for it there. |
| `versions.json` | The pinned versions. Changed on `main` only, by agreement: both sides build from the same commits. |
| `patches/*.patch` | Generated (`./hyrule save-patch`), never edited by hand, changed on `main` only. A change the port needs in an engine's sources goes in its own file, `patches/windows-*.patch`, applied after the shared ones. |
| `ship/`, `kart/`, `fabric/`, `tools/` (existing files) | Shared. The port touches them only at a platform seam, as small as possible: an `#ifdef _WIN32` block, an `if sys.platform == 'win32'` branch, or a call to a helper that lives in a new file. No reformatting, no reordering, no renaming in passing. |
| New files such as `ship/PlatformWindows.h`, `tools/windows/*`, `hyrule.cmd`, `docs/WINDOWS.md` | The port's own. Prefer adding a file to editing one. |
| `README.md`, `PLAN.md` | `main`. The port's notes go in `docs/WINDOWS.md`. |

## Keeping diffs small

- Line endings are LF everywhere (`.gitattributes`); only `.cmd`, `.bat` and `.ps1` are
  CRLF. Set `git config core.autocrlf false` on Windows and let `.gitattributes` decide.
- The Java sources are written compactly, several statements to a line. Keep the style
  of the file you are in and do not run a formatter over it: a reformatted file
  conflicts with every later change to it.
- One concern per commit, and say in the message what was verified and how.

## Never in Git

ROMs, anything extracted from them, built engines or jars, worlds, saves, launcher
profiles, account files, server data. They live in `.local/`, `dist/` and
`local.json`, all ignored. Run `python3 tools/audit.py` before pushing.

## Reporting state

When something is untested on a platform, say so in the commit and in the document
that describes it. "Builds" and "runs" and "verified in the game" are three different
claims.
