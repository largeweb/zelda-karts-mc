# The agent: a decision model plays the game

`tools/agent.py` plays the running Minecraft client on the server. It does not read the
screen or press keys: the mod's local test channel (`"dev": true` in `local.json`)
reports position, inventory, nearby blocks and chat, and takes bounded actions
(walk to a spot, hold an item, place, till, sow, pour, dig, run a command).

## Two layers

| Layer | Runs | Does |
|---|---|---|
| Planner | rarely (minutes) | Writes the brief: ten fields in `.local/agent/brief.json`. A person or a language model. |
| Controller | several times a second | Reads the state, lists the actions possible right now, asks a decision model to choose one, carries it out, checks the result in the next state. |

The controller is built for a System One decision model such as TypeSafe's Jev: the
model gets the state as JSON and a `choice` question whose options are the possible
actions, and returns one option with probabilities. Two more questions ride in the
same request (`stuck`, `danger`). It never invents coordinates or commands.

When the model is unsure three times running, an action fails the same way five times,
or nothing possible is left, the controller stops and hands back to the planner.
Each decision is appended to `.local/agent/journal.jsonl`; one screenshot a minute
(the last five kept) lands in `.local/session/screenshots/agent-*.png` for the planner.

## Use

    ./hyrule start --multiplayer
    python3 tools/agent.py brief faction=Lonlon mode=survival site=82952,1024,30
    python3 tools/agent.py run                  # Jev decides
    python3 tools/agent.py run --backend script # no model: first possible action, to test the game side
    python3 tools/agent.py report

The Jev key comes from `TYPESAFE_API_KEY`, or `"jev": {"key": "..."}` in `local.json`
(never committed). `"base_url"` there points the same request at a compatible server,
hosted or local; `"model"` names the model.

## Blueprints

| `build` | What | How it is placed |
|---|---|---|
| `farm` | 3x3 irrigated plot, a faction, a claim (survival) | by hand; `/kit farmer` supplies it |
| `ring` | one cobblestone generator feeding a self-repairing ring (`tools/ring.py`) | by `/setblock` and `/fill`, a part at a time: a test of the design, not of the player |
| `tube` | eight generators feeding a wall, ceiling, wall and floor, eight wide, over a lava pit (`tools/tube.py`) | by hand in creative: 576 placements, each read back from the game |

For `tube` the model chooses among the nearest cells that can be placed now (their
part's prerequisites stand, and so does the block under them), flying to another part,
breaking a wrong block, or waiting. A placement is an item, a view direction and a
click: pistons end up facing the player, repeaters carry the signal the way the player
looks, and a repeater's delay is further clicks. The state the model sees is the
position inside the blueprint, progress per part, what is in the hand, the operator's
`focus` and `avoid` lists, and the last results.

`tube` has not been built yet: its redstone follows the `ring` design, which runs, but
the eight-lane layout itself is untested.

## The operator

The planner is meant to be replaceable: a person, a coding agent, or a language model
on a timer. Its whole interface is four commands and the brief.

    python3 tools/agent.py run --forever         # the controller; waits for a new brief when it hands back
    python3 tools/agent.py observe               # one JSON document: brief, controller state, progress, last
                                                 # actions, failures, unsure decisions, new messages, notes, frames
    python3 tools/agent.py brief focus=clock,riser avoid=lava_pit notes="..."
    python3 tools/agent.py remember "pours fail unless hovering right above the cell"
    python3 tools/agent.py tell "build it nearer the castle"    # the person, to the operator
    python3 tools/agent.py say "moved the site; 212 of 576 placed"

An operator tick is: `observe`, look at the frames it lists, change the brief, optionally
`remember` and `say`. The controller reads the brief again before every decision.

## Limits

The player walks and flies in straight lines. The controller reads blocks within six
blocks of the player (five up and down); it does not yet read the Zelda ground under
them, and nothing is decided from the picture: frames are for the operator only.
