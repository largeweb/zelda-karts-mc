# Windows 11: status and porting notes

**Status: partial native port; isolated transport verified on Windows 11.** Neither
game engine has been built or run here, and Minecraft composition has not been
verified in the game. The first milestone is still incomplete. Read
`CONTRIBUTING.md`; its branch and shared-file rules keep the sides mergeable.

The Minecraft mod (`fabric/`) and server (`server/`, Paper) are Java; their full
Windows builds and gameplay have not been tested here. The platform work is in
the native bridges, process launch, launcher tools and build.

## Progress recorded 2026-10-09

| Component | Builds | Runs | Verified in the game |
|---|---|---|---|
| Isolated native mapping test, LLVM-MinGW Clang 22.1.8 x64 | Yes | Yes | Not applicable |
| Existing `Shared.java` with JDK 25.0.4.1 and a test peer | Yes | Yes, exchanging data with native mapping | Not applicable |
| Ship of Harkinian with bridge | In progress; vcpkg dependencies built | No | No |
| Full Fabric mod | Not attempted; owner must provide Prism data path | No | No |
| SpaghettiKart / Paper multiplayer | Not attempted | No | No |

Implemented:

- `ship/PlatformWindows.h`: ordinary files mapped with `CreateFileW`,
  `CreateFileMappingW` and `MapViewOfFile`. A companion `.engine-lock` file held
  with no sharing excludes other engine writers without locking Java's transport
  bytes. Handles live as long as the mapping; process exit releases ownership.
  Transport handles allow read/write/delete sharing. Directory and final-component
  reparse-point files are rejected. Use a private, user-owned transport directory.
- All three native mapping sites select that helper under `_WIN32`. Their Linux
  mapping code, the protocol header and all offsets are unchanged.
- Collision export uses wide filenames and `MoveFileExW` replacement on Windows.
  Actual collision export from the game has not been tested.
- New `patches/windows-libultraship.patch`, generated from the pinned submodule
  after applying the shared patch: Windows collision polling during frame waits
  and ordinary bridge symbol declarations on Windows (ELF weak declarations remain
  on Linux). Pacing and linking inside the engine are still unverified.
- `tools/windows/build.py` fetches pinned source, checks both revisions, applies
  shared patches followed by the Windows patch, copies bridge sources, and provides
  a native-only build entry point. Repeated `prepare` was checked successfully.
  `configure-window` selects OpenGL **backend ID 2**, confirmed in the pinned
  `libultraship/include/fast/Fast3dWindow.h`, with a windowed 1280x720 configuration.

Checks passed: native/Python two-way mapped data and seqlock bytes; duplicate
engine rejection; owner exit/restart while a consumer retains its mapping; paths
with spaces and Unicode through the wide environment API; 8192-byte control and
29,491,264-byte frame files; resizing an unmapped file; retry after a failed open;
JDK 25 `FileChannel.map` through the unchanged `Shared.java`. These are transport
tests, not colour/depth readback or gameplay tests.

Local prerequisites fetched into ignored `.local/toolchains/`: portable
[LLVM-MinGW](https://github.com/mstorsjo/llvm-mingw/releases/tag/20260616),
[Temurin JDK 25](https://adoptium.net/installation/ci-scripts/) (archive checksum
verified) and CMake 4.4.4. No ROMs or Nintendo game assets were downloaded.
On 2026-10-10 the owner authorized installing the missing prerequisites. Visual
Studio Build Tools 2022 17.14.41, Windows SDK 10.0.26100 and ClangCL 19.1.5 are
installed. The same native/JDK transport tests now also pass with ClangCL.
All 21 vcpkg dependencies built successfully; the full engine configure/build is
in progress and is not yet verified. Stock MSVC cannot compile `Protocol.h`'s
atomic builtins, so the Windows entry point selects ClangCL instead.
The installer requested a reboot, but compiler and transport tests worked without
one; no reboot has been performed.

On 2026-10-10 the owner supplied their ROM directory. The Ocarina of Time dump's
SHA-1 matches **NTSC 1.2 (US)** in the pinned upstream `docs/supportedHashes.json`.
The Mario Kart 64 dump was also located; its extraction has not been tested.
Private filenames and hashes are recorded only in ignored `.local/windows-inputs.json`.
Neither ROM has been copied or extracted. Prism Launcher 11.1.1 and Python 3.13
were installed with the owner's authorization. A new private Prism data folder
was prepared in ignored `.local/prism-windows`; existing account folders were not
searched. Microsoft sign-in is left to the owner. GitHub CLI is installed and
authenticated by the owner; commits use the verified account's noreply address
in repository-local Git configuration. After the native build,
verify the ordinary standalone game, then finish the Java executable-name/SDL
seams and Windows mod configuration before manual composition testing. Launcher
install/setup/start, kart builds and multiplayer remain to be ported and tested.

Surprises:

- Fresh submodules inherited Windows CRLF checkout settings even after configuring
  the parent checkout. The unchanged shared patches failed to apply until the fresh
  submodules were checked out with LF. The Windows fetch helper passes
  `-c core.autocrlf=false` to recursive submodule fetching.
- Passing a Unicode filename directly as a Java launcher argument on this machine
  lost characters to the system code page. The transport test reads the filename
  from the wide Windows environment instead, which succeeds. Unicode transport
  through Prism's JVM argument handling is **not verified**; test it separately.

## Native preparation and checks

Run with Python 3 from the repository root. The original Linux `tools/manage.py`
still imports `fcntl` and is not a Windows entry point.

```powershell
python tools/windows/build.py fetch-source   # only if .local/soh does not exist
python tools/windows/build.py prepare
python tools/windows/build.py doctor
python tools/windows/build.py build --jobs 4
```

Install the C++ requirements from the pinned upstream
[Windows build guide](https://github.com/HarbourMasters/Shipwright/blob/ecd889c2019b87e78b0a3d942a6a3fe98b7efaed/docs/BUILDING.md),
adding the LLVM Clang tools for Windows / ClangCL component. The helper uses
`Visual Studio 17 2022`, x64, `-T ClangCL`, and Release. Upstream CMake fetches
code dependencies with vcpkg. `--cmake PATH` accepts an explicit CMake executable.
The proposed output is `.local/runtime/soh-composite.exe` plus required upstream
files; that output has not yet been produced.

To reproduce the isolated checks with the portable tools fetched in this session:

```powershell
python tests/windows/test_mapping.py --compiler .local/toolchains/llvm-mingw-20260616-ucrt-x86_64/bin/clang++.exe --java-home .local/toolchains/jdk-25.0.4.1+1
python tools/audit.py
```

`--java-home` is optional; without it only native/Python transport is tested.
Alternatively use `--compiler clang-cl` in an x64 VS developer shell; that variant
also passed after installing the build tools. Test binaries and classes stay in
ignored `tests/build/`.

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
