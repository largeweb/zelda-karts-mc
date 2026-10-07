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
./hyrule start      # start Minecraft
./hyrule status
./hyrule arrange    # put the Minecraft window centre-screen, park Zelda's window
./hyrule restart
./hyrule stop       # Minecraft saves and quits, taking the game engine with it
```

In Minecraft, **Singleplayer → Create New World** has a **Map** button (bottom
left). It steps through the games that are installed:

- **Minecraft** creates an ordinary Minecraft world.
- **Ocarina of Time** creates a world paired with its own Zelda save. Opening the
  world starts Zelda; leaving it stops Zelda. A new world begins as adult Link in
  Kokiri Forest with everything unlocked; after that it continues from where
  Zelda last saved (it saves automatically).

- **Mario Kart 64 — <track>** (ten tracks) creates a world on that track. You are
  on foot as your Minecraft character and can place blocks and dig into the track
  as in Hyrule. `/spawnkart` asks for a character (1–8) and puts you in that
  character's kart: W accelerate, S brake, A/D steer, Space hop and drift, Shift
  get off; right-click beside a parked kart with an empty hand to get back on.

Games that are not installed are listed in the button's tooltip with the reason.
GoldenEye is listed but not supported yet.

The Ship of Harkinian window lives on the `Zelda-renderer` workspace and must
stay open while an Ocarina of Time world is.

## First-time setup

`local.json` (private, ignored by Git) overrides the defaults in
`tools/common.py`; set `prism_dir` to a Prism Launcher data directory that has
your account and has launched Minecraft 26.3 with Fabric once.

```bash
./hyrule fetch-source          # clone pinned Ship of Harkinian into .local/soh
./hyrule build                 # apply patches, build the game and the Fabric mod
./hyrule extract /path/to/your/oot.z64
./hyrule setup                 # Prism instance, item icons, dimension, mod settings
./hyrule start
```

Without the extract step only plain Minecraft worlds can be created.

For Mario Kart tracks, also run `./hyrule mk64 /path/to/your/mk64.z64` (it fetches
and builds SpaghettiKart, the Mario Kart 64 PC port, and extracts your ROM), then
`./hyrule install`.

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
| `/help`, `/help 2` … | In-game guide |

Zelda items are in Creative → Tools & Utilities, or search by name. Holding one
is enough to use it. In Creative, magic and ammunition do not run out.

## How health works

Minecraft hearts are the only health. Zelda's hearts stay full; every hit Link
takes is passed to Minecraft as damage. The experience number shows rupees and
the experience bar shows magic. Creative mode takes no damage.

## Minecraft and Zelda acting on each other

- Minecraft swords, axes, bows and crossbows hurt Zelda enemies.
- Minecraft explosions (TNT and the like) hurt Zelda actors as a bomb would.
- Fire arrows, Din's Fire and Zelda bomb blasts ignite Minecraft TNT.
- Blocks are hidden behind Zelda's walls and ground; Link and Zelda actors hide blocks behind them.

## Digging

Hold attack on Zelda scenery, floor or wall, while holding any Minecraft item.
Minecraft's cracks appear on the Zelda surface and it breaks like a block of that
material (grass, sand, stone, wood), leaving a block-sized opening. Behind it is
soil for a few blocks, then stone, with bedrock 24 blocks in. Dug space is always
lined with blocks. Water, lava, doors and moving platforms cannot be dug. What
has been dug is saved with the Minecraft world in `hyrule-digging.dat`.

To put a dug spot back, place any block in it and right-click that block with a
hoe. The block is returned and the spot becomes what it was: Zelda's original
scenery, or the soil or stone that was behind it.

In game, `/help` and `/help 2` … `/help 8` page through a guide to all of this.

## Known gaps

- Zelda enemies and items still treat a dug floor as solid.
- Shields, tunics and boots are not items yet; bottles and trade items neither.
- Not yet multiplayer.

## Development

`"dev": true` in `local.json` enables a local test channel; `tools/console.py`
can then move the player, press buttons, run commands and take screenshots.
After editing Ship of Harkinian sources in `.local/soh`, run
`./hyrule save-patch` to refresh `patches/soh.patch`.
