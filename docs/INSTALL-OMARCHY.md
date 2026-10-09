# Setup on Omarchy / Arch Linux

This is the platform the project is developed on. Everything below was done on
Omarchy (Arch Linux, Hyprland, NVIDIA).

## What you need

- Your own ROMs: Ocarina of Time (a version Ship of Harkinian supports) and, for
  karts and tracks, Mario Kart 64 (USA). They are never copied into this repository.
- A Minecraft Java account, signed in to Prism Launcher.
- About 6 GB of disk and a GPU with OpenGL 3.3.

Packages:

```bash
sudo pacman -S --needed base-devel git cmake ninja clang python \
  jdk25-openjdk prismlauncher \
  sdl2-compat sdl2_net libpng libzip nlohmann-json tinyxml2 spdlog boost-libs \
  libogg libvorbis opus opusfile bzip2 mesa libglvnd zenity xorg-xwayland
```

The game engines run as X11 programs (through XWayland on Hyprland); the mod sets
that itself.

## Once: Minecraft

1. Start Prism Launcher, add your Microsoft account.
2. Create an instance with Minecraft 26.3 and Fabric Loader 0.19.5 and launch it once,
   so its libraries are downloaded. (The versions are pinned in `versions.json`.)
3. Tell the project where Prism keeps its data. Create `local.json` in the repository
   root (it is ignored by Git):

   ```json
   { "prism_dir": "~/.local/share/PrismLauncher" }
   ```

## Build and run

```bash
./hyrule fetch-source                     # Ship of Harkinian at the pinned commit, into .local/
./hyrule build                            # patch and build the Zelda engine and the mod (10-20 min)
./hyrule extract /path/to/your/oot.z64    # your ROM's assets, into .local/
./hyrule mk64 /path/to/your/mk64.z64      # optional: Mario Kart engine and assets
./hyrule setup                            # the Prism instance "hyrule-composite"
./hyrule doctor                           # says what is still missing
./hyrule start                            # single player
```

In Minecraft: Create New World, set **Map** to Ocarina of Time or a Mario Kart track.

To join a server instead: `./hyrule start --multiplayer some.host` (no address: a
server on this machine). To run one: see "Multiplayer server" in the README.

`./hyrule stop` closes Minecraft and the engines cleanly. `./hyrule status` shows
what is running.

## Things that differ on other setups

- **Hyprland**: the engine windows are parked on a special workspace by
  `tools/arrange.py` (it uses `hyprctl`). Without Hyprland nothing is parked and the
  engine windows simply stay open behind Minecraft; they must not be minimised, or
  they stop drawing.
- **NVIDIA**: the engine can fault in the driver when the display changes or sleeps
  for a long time. The mod restarts an engine that stops (see README).
- **Shared memory**: Minecraft and the engines talk through files in `/dev/shm`.
  `"shm"` in `local.json` changes the name; it must stay under `/dev/shm`.
- **Java**: `"java_home"` in `local.json` if JDK 25 is not at `/usr/lib/jvm/java-25-openjdk`.
