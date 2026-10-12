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

## What it can build so far

One blueprint: a 3x3 irrigated plot (water in the middle, eight sown cells), a faction
and a claim on the chunk it stands in, optionally a sapling beside it. The `farmer` kit
(`/kit farmer`, hourly) supplies the materials. The water is poured through the cell
facing the agent, so that cell has to stay open until the water is in: the one ordering
trap in the task, and the model is told about it rather than prevented from making it.

Not built yet: a language-model planner loop, more blueprints, pathfinding around
Zelda scenery (it walks in a straight line), and anything that needs the picture.
