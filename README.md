# Hyrule Composite

Minecraft Java × Ocarina of Time. Both games run as native processes: Minecraft
supplies controls, camera, inventory, health and blocks; a patched Ship of
Harkinian supplies Hyrule, its actors and story, and Link's body. Minecraft
shows the combined picture.

Single-player prototype, tested only on x86-64 Arch Linux (Omarchy/Hyprland).
See [PLAN.md](PLAN.md) for where this is going (dedicated server, modpack).

This repository holds bridge source, patches and tooling only. It contains no
Minecraft, ROM, extracted assets, saves or account data. You need your own
Minecraft account and your own supported Ocarina of Time ROM dump.

## Versions

Pinned in [versions.json](versions.json): Minecraft 26.3, Fabric Loader 0.19.5,
JDK 25, Ship of Harkinian 9.3.0.

## Running

```bash
./hyrule start      # start the pair
./hyrule status
./hyrule arrange    # put the Minecraft window centre-screen, park Zelda's window
./hyrule restart
./hyrule stop       # Minecraft saves and quits; other applications are untouched
```

Play in the **Minecraft x Ocarina of Time** window. The Ship of Harkinian window
lives on the `Zelda-renderer` workspace and must stay open.

## First-time setup

`local.json` (private, ignored by Git) overrides the defaults in
`tools/common.py`; set `prism_dir` to a Prism Launcher data directory that has
your account and has launched Minecraft 26.3 with Fabric once.

```bash
./hyrule fetch-source          # clone pinned Ship of Harkinian into .local/soh
./hyrule build                 # apply patches, build the game and the Fabric mod
./hyrule extract /path/to/your/oot.z64
./hyrule setup                 # Prism instance, world, item icons, dimension
./hyrule start
```

## Controls

| Input | Action |
| --- | --- |
| WASD, mouse, Space | Minecraft movement, look and jump, against Hyrule's collision |
| F5 | First person, or third person showing Link |
| E, Esc, T, `/` | Minecraft inventory, menu, chat, commands |
| Left / right click, empty hand | Zelda B / A: open doors, talk, grab, climb |
| Left / right click, Zelda sword held | Slash / raise shield |
| Right click, other Zelda item held | Use it; hold to aim the bow, hookshot or slingshot |
| Left / right click, Minecraft item held | Normal Minecraft mining, attacking, placing |
| Enter / Backspace | Zelda A / B whatever is held |
| Space in dialogue | Advance text |
| Arrow keys, Space | Ocarina notes (C buttons) and A |
| O | Play the ocarina |
| Left Alt | Z-target |
| Tab | Zelda pause screen (map, quest status) |
| `/link child`, `/link adult` | Switch Link's age |

Zelda items are in Creative → Tools & Utilities, or search by name. Holding one
is enough to use it. In Creative, magic and ammunition do not run out.

## How health works

Minecraft hearts are the only health. Zelda's hearts stay full; every hit Link
takes is passed to Minecraft as damage. The experience number shows rupees and
the experience bar shows magic. Creative mode takes no damage.

## Known gaps

- Minecraft blocks show through Zelda walls (no shared depth yet).
- The session always starts as adult Link in Kokiri Forest on a debug save with
  everything unlocked. A Minecraft world is not yet tied to its own Zelda save.
- Shields, tunics and boots are not items yet; bottles and trade items neither.
- Not yet multiplayer.

## Development

`"dev": true` in `local.json` enables a local test channel; `tools/console.py`
can then move the player, press buttons, run commands and take screenshots.
After editing Ship of Harkinian sources in `.local/soh`, run
`./hyrule save-patch` to refresh `patches/soh.patch`.
