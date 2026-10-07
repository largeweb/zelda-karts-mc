# Plan

Decisions recorded 2026-10-06. Nothing is built or launched yet.

## Decided

**Feel**
- Minecraft is the game the player operates: movement, FOV, F5, pause menu,
  video settings, chat, hotbar. Zelda supplies scenery, actors, story and Link.
- F5 shows Link, adult by default (68 units ≈ 1.7 blocks at 40 units/block).
  A command switches to child so a story can start in Kokiri Forest.
- Empty hand: left/right click are Zelda B/A (doors, talking, climbing).
  Held item: that item's behaviour. Space advances dialogue; arrow keys play
  ocarina notes.

**Items**
- Every OoT item is a registered Minecraft item with its own behaviour, held in
  the Minecraft inventory, stored by the server. Zelda's item screen is not used.
- Holding an item is enough to use it; Zelda ownership is never checked.
- Creative for everyone for now; block placing unrestricted. Survival later.
- Room for non-Zelda modded items later (portal gun).

**HUD**
- Minecraft HUD. XP number shows rupees (stored server side).
- Magic: proposed as the hunger bar, refilled by magic jar pickups. Unconfirmed.
- Zelda draws only what Minecraft cannot: dialogue, ocarina staff, boss bar.

**Health**
- Minecraft health is the only health. Zelda hearts are pinned full; Zelda
  damage is converted to Minecraft damage; Minecraft death triggers respawn.
- Armour applies but is not rendered.
- Hitting a player prints their remaining health in the attacker's chat only.

**Multiplayer**
- Each player's own Ship of Harkinian process simulates Zelda actors.
- Other players appear as puppet Links spawned in each client's Zelda process.
- Story is per player. Any player can skip ahead and fight anywhere, but gates
  and bosses follow their own save.
- Vanilla spawn eggs spawn server-side Minecraft mobs. OoT spawn eggs make every
  client in the scene spawn that native enemy.
- Server spawn is a safe zone in Kokiri Forest, intro skipped, with a teleport
  out to Hyrule Field.

**Single player**
- Creating a Minecraft world creates a Zelda save; the world name is the save
  file name. Standard Minecraft menus throughout.
- Later: a map choice on world creation (OoT, GoldenEye maps, ...).

## Components

| Part | Role |
|---|---|
| `protocol/` | One definition of every shared-memory and network message; C++ and Java are generated from it. |
| `soh/` | Patches and hooks for Ship of Harkinian: frame export, collision service, input, item use, puppets, damage out, enemy spawn. |
| `mod/` | One Fabric mod with client and server entry points: items, HUD, compositing, relay, rupees, saves. |
| `tools/` | Fetch/build/extract, collision export from the player's own `oot.o2r`, launcher, pack builder. |
| `pack/` | Client modpack and server pack definitions. No ROM, no game assets. |

The client mod relays between the server (Minecraft custom packets) and the
local Zelda process (shared memory). The server never talks to a Zelda process.

## Order of work

1. **Port the renderer path.** Pin Ship of Harkinian, port the libultraship
   capture/pacing patch, show Kokiri Forest behind Minecraft in single player.
2. **Movement and avatar.** Collision service, Link in F5, adult/child switch,
   empty-hand A/B, dialogue and ocarina input.
3. **Items.** Register OoT items; hold-to-use through the native item code;
   age-restricted items; infinite ammo in creative.
4. **Health, rupees, magic HUD.** Damage conversion both ways, death, XP as rupees.
5. **World = save.** Per-world Zelda save created and loaded with the Minecraft world.
6. **Dedicated server.** Server mod, per-player Zelda save and rupees stored on
   the server, scene regions, puppets, PvP, chat health line.
7. **Server-side collision.** Export scene collision so Minecraft mobs and drops
   stand on Hyrule; vanilla and OoT spawn eggs.
8. **Modpack.** Client pack plus server pack; setup that takes the player's ROM.
9. **Later.** Survival rules, Mario Kart, GoldenEye maps, portal gun, map picker.

## Constraints

- Minecraft 26.3, Fabric Loader 0.19.5, Java 25, matching the sibling projects.
- `~/Work` belongs to Codex. Its Prism directory is shared; create this
  project's instance and launch only when Codex's Minecraft session is stopped.
- No supported OoT ROM is on this machine yet. Step 1 cannot run without one.
- Each player needs their own ROM and Minecraft account.

## Open

- Magic on the hunger bar, or keep hunger and show magic separately?
- Is Anchor (co-op sync) in Ship of Harkinian mainline? Its puppet code would
  save work in step 6. Not yet confirmed.
- Which Ship of Harkinian commit to pin (9.2.3 is current stable).
- Blocks still show through Zelda walls unless depth is shared; decide after step 1.
