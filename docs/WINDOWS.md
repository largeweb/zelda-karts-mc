# Windows 11: status and porting notes

**Status: not ported.** Nothing here has been built or run on Windows. This page is
for whoever does that work (a person or a coding agent): what is Linux-only today,
where the seams are, and how to work without colliding with the Linux side. Read
`CONTRIBUTING.md` first; the rules there about branches and shared files are what
keep the two sides mergeable.

The Minecraft mod (`fabric/`) is plain Java and the server (`server/`, Paper) runs on
Windows as it is. The work is in the two native bridges, the launcher tools and the
build.

## What a working Windows build has to do

1. Build Ship of Harkinian (and optionally SpaghettiKart) at the commits in
   `versions.json`, with `patches/*.patch` applied and the bridge sources from `ship/`
   and `kart/` compiled in. On Linux `tools/manage.py build` does this with CMake and
   Ninja; upstream documents an MSVC + vcpkg build for Windows.
2. Let Minecraft and the engine share memory and frames (below).
3. Launch: install the mod, resource pack, dimension datapack and `config/hyrule.json`
   into a Prism Launcher (or other) instance, as `tools/manage.py install` does, and
   start it. The mod itself launches the engines when a world opens (`Engine.java`).

## Linux-only pieces, by file

| Where | What it does on Linux | What Windows needs |
|---|---|---|
| `ship/CompositeBridge.cpp` `init()`, `kart/CompositeBridge.cpp` `init()` | Opens the control file named by `COMPOSITE_SHM` with `open`/`flock`/`fstat`/`ftruncate`/`mmap` (8192 bytes, layout in `ship/Protocol.h`). | The same file mapped with `CreateFileW` + `CreateFileMappingW` + `MapViewOfFile`, and a share mode or lock so two engines cannot take one file. The Java side (`Shared.java`) maps the same path with `FileChannel.map`, which already works on Windows on an ordinary file. |
| `ship/FrameExport.h` | Maps `COMPOSITE_FRAME` (about 30 MB) the same way and writes each frame into it: colour read back with `glReadPixels`, then depth. | The same mapping. **Upstream's default renderer on Windows is DirectX 11; this readback is OpenGL.** Either force the OpenGL backend for the engine or write a D3D11 readback. Forcing OpenGL is the short path. |
| `ship/CompositeBridge.cpp` `exportCollision()` | Writes `<COMPOSITE_SHM>.collision` with `fopen` and an atomic `rename`. | `rename` over an existing file fails on Windows: use `MoveFileExW(..., MOVEFILE_REPLACE_EXISTING)`. |
| `patches/libultraship.patch` (`gfx_sdl2.cpp`, `SyncFramerateWithTime`) | Inside `#ifndef _WIN32`: while waiting for the next frame it keeps answering Minecraft's collision requests (`CompositePollCollision`). | The `_WIN32` branch is upstream's and does not poll. Player movement will stutter until the Windows branch polls too (wait in 1 ms slices and call `CompositePollCollision` between them). |
| `fabric/.../Engine.java` | Starts engines with `SDL_VIDEODRIVER=x11`, by the file names in `Games.java` (`soh-composite.elf`, `mk64-composite.elf`), stops them with `destroy()`. | No `SDL_VIDEODRIVER`; `.exe` names (make the name depend on the OS in one place, `Games.java`). |
| `fabric/.../Bridge.java`, `Lifecycle.java`, `Dev.java`, `Guest.java`, `Karts.java`... | Read the path from the Java property `composite.shm`, default under `/dev/shm`. | Nothing in the code: pass a Windows path in the property (the tools set it as a JVM argument, see `jvm_args` in `tools/manage.py`). Keep every derived name (`.rgba`, `-guest`, `-kart`, `.collision`, `.quit`, `.dev`) as a suffix of that one path. |
| `tools/common.py` | Defaults: `/dev/shm/...`, `/usr/lib/jvm/java-25-openjdk`, `prismlauncher`; refuses an `shm` outside `/dev/shm`. | Windows defaults (a file under `%LOCALAPPDATA%\HyruleComposite`, Prism at `%APPDATA%\PrismLauncher`), and the `/dev/shm` check made Linux-only. |
| `tools/manage.py` | Finds running sessions by reading `/proc`, uses `fcntl` locks, POSIX signals, `start_new_session`, checks `sys.platform == 'linux'` in `doctor`. | Equivalents (`tasklist`/`psutil`, `msvcrt` locks, `taskkill`), or a separate Windows launcher that reuses `install()` and `setup()`. |
| `tools/server.py` | Sends console commands through a named pipe (`mkfifo`), finds the server through `/proc`. | The server itself needs none of this: the bundle's `start.sh` is three lines, write a `start.cmd`. Port the tool only if you want `hyrule server ...` on Windows. |
| `tools/arrange.py` | Parks engine windows with `hyprctl`. | Not needed to run. Engine windows must stay un-minimised; moving them off-screen or behind Minecraft is enough. |
| `hyrule` | Bash wrapper around `tools/manage.py`. | `hyrule.cmd` or `hyrule.ps1` beside it. |

Not platform-specific, in case it looks it: the seqlock helpers and structures in
`ship/Protocol.h`, everything in `fabric/src/main/java` except the items above, the
shaders, the Paper plugin.

## Suggested order

1. Engine builds and runs standalone on Windows from the pinned commit with the
   patches applied (no bridge behaviour yet: without `COMPOSITE_SHM` set it is the
   ordinary game).
2. File mapping in the three native files, behind one small helper so the Linux code
   path stays as it is.
3. Launch by hand: set the environment variables `Engine.java` sets, start Minecraft
   with `-Dcomposite.shm=...` and `-Dcomposite.passthrough=true`, open an Ocarina of
   Time world. Hyrule should appear behind Minecraft's HUD.
4. Tools: a Windows `install`/`setup`/`start`.
5. Mario Kart engine, then the multiplayer checks in the README.

## What to verify before calling it working

The same things that were checked on Linux, in this order: Hyrule visible with correct
depth against a placed block; walking and jumping on Zelda's ground at 60 fps without
stutter; an item (bow, hookshot) in first person; digging a hole and restoring it with
a hoe; a kart on a track; `/spawnkart` in Hyrule; joining a server with
`--multiplayer`.

## Things that will bite

- The engine must keep drawing while it is not the focused window, and must not be
  minimised.
- Frame pacing: both programs run at 60 fps and the engine is late-latched to
  Minecraft's camera (`CompositeLateCamera`); with vsync forced on by a driver the
  picture lags behind the blocks.
- Paths with spaces (`C:\Users\First Last\...`) go through environment variables and
  JVM arguments; quote them.
- Minecraft refuses to open worlds containing symbolic links; nothing here creates
  any, keep it that way.
- Do not commit anything from a ROM, an extracted archive, a build, a world or a
  launcher profile. `python tools/audit.py` checks the index; `.gitignore` already
  covers the usual files.
