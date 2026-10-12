#!/usr/bin/env python3
"""Play the running Minecraft client from game state. Needs "dev": true in local.json.

Two layers. A planner (a person, or a language model) writes a brief: a handful of
fields saying what to achieve. A controller then loops a few times a second: it reads
the game state, works out which bounded actions are possible right now, and asks a
System One decision model (Jev, or anything that speaks the same /v1/systemone
request) to choose one. The model never produces text or coordinates of its own; it
picks among actions this file knows how to carry out and can check afterwards.

  agent.py brief                      print the brief in use (.local/agent/brief.json)
  agent.py brief KEY=VALUE ...        change fields, e.g. faction=Lonlon site=82950,1024,40
  agent.py run [--backend jev|script] [--steps N]
  agent.py report                     where it stands, the last decisions, the last frames

Backends: "jev" calls the decision model. The key is TYPESAFE_API_KEY, or "jev":
{"key": ..., "base_url": ..., "model": ...} in local.json; base_url also takes a
compatible local or hosted server. "script" asks no model and takes the first possible
action in the order they are listed: it is there to test the game side."""
import argparse, json, math, os, re, sys, time, urllib.error, urllib.request, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SESSION = ROOT / '.local/session'
HOME = ROOT / '.local/agent'
BRIEF = HOME / 'brief.json'
JOURNAL = HOME / 'journal.jsonl'

# What the planner decides. Everything else the controller works out from the game.
DEFAULT_BRIEF = {
    'objective': 'Found a faction, claim the land under the farm site, and build a small irrigated wheat plot there.',
    'mode': 'survival',            # survival or creative
    'faction': 'Lonlon',
    'site': None,                  # [x, y, z] of the water block; None: a few blocks from where the player stands
    'kit': 'farmer',               # Essentials kit that supplies the materials
    'crop': 'minecraft:wheat_seeds',
    'sapling': True,               # plant one tree beside the plot
    'rules': ['Never break blocks that are not part of the plot.', 'Stay out of Kokiri Forest: it is the protected hub.'],
    'notes': '',                   # free text from the planner, shown to the decision model
    'done_when': 'The faction exists, the land is claimed, and every plot cell is planted.',
}

RING = [(-1, -1), (0, -1), (1, -1), (-1, 0), (1, 0), (-1, 1), (1, 1), (0, 1)]
GAP = (0, 1)        # the cell between where the agent stands and the water
STAND = (0.5, 3.1)  # where it stands, relative to the water block
TREE = (3, 0)


def load_brief():
    brief = dict(DEFAULT_BRIEF)
    if BRIEF.is_file():
        brief.update(json.loads(BRIEF.read_text()))
    return brief


def local_settings():
    path = ROOT / 'local.json'
    return json.loads(path.read_text()) if path.is_file() else {}


class Game:
    """The client's test channel: one command in, a status file out."""

    def status(self):
        for _ in range(40):
            try:
                status = json.loads((SESSION / 'status.json').read_text())
                if time.time() * 1000 - status['time'] < 3000:
                    return status
            except (OSError, ValueError, KeyError):
                pass
            time.sleep(.1)
        raise SystemExit('Minecraft is not reporting: is it running with "dev": true?')

    def fresh(self):
        """A status written after this moment, so it shows the effect of the last action."""
        since = time.time() * 1000
        for _ in range(60):
            status = self.status()
            if status['time'] > since + 250:
                return status
            time.sleep(.1)
        return self.status()

    def send(self, **command):
        command['id'] = str(uuid.uuid4())
        temp = SESSION / 'command.tmp'
        temp.write_text(json.dumps(command))
        temp.replace(SESSION / 'command.json')
        for _ in range(100):
            try:
                result = json.loads((SESSION / 'result.json').read_text())
                if result.get('id') == command['id']:
                    return result
            except (OSError, ValueError):
                pass
            time.sleep(.05)
        return {}

    def command(self, text, wait=1.5):
        """A server command as the player; returns the chat lines it produced."""
        before = self.status().get('chat', [])
        self.send(command=text)
        time.sleep(wait)
        after = self.fresh().get('chat', [])
        for size in range(min(len(before), len(after)), 0, -1):
            if before[-size:] == after[:size]:
                return after[size:]
        return after[-4:]


class World:
    """Facts the controller derives from one status, relative to the brief."""

    def __init__(self, brief, status, memory):
        self.brief, self.status, self.memory = brief, status, memory
        self.site = tuple(brief['site'])
        self.blocks = {(x, y, z): name for x, y, z, name in status.get('blocks', [])}
        self.items = status.get('inventory', {})

    def at(self, dx, dy, dz):
        x, y, z = self.site
        return self.blocks.get((x + dx, y + dy, z + dz))

    def cell(self, dx, dz):
        x, y, z = self.site
        return [x + dx, y, z + dz]

    def stand(self):
        return self.site[0] + STAND[0], self.site[2] + STAND[1]

    def distance(self):
        x, z = self.stand()
        return math.hypot(self.status['x'] - x, self.status['z'] - z)

    def has(self, item, count=1):
        return self.items.get(item, 0) >= count

    def facts(self):
        ring = {cell: self.at(cell[0], 0, cell[1]) for cell in RING}
        crops = {cell: self.at(cell[0], 1, cell[1]) for cell in RING}
        return {
            'game_mode': self.status.get('mode'),
            'health': self.status.get('health'), 'food': self.status.get('food'),
            'faction': self.memory.get('faction') or 'none',
            'claimed_chunks': self.memory.get('land', 0),
            'site_chunk_claimed': bool(self.memory.get('claimed_here')),
            'blocks_from_standing_spot': round(self.distance(), 1),
            'floor_under_water_placed': self.at(0, -1, 0) is not None,
            'water_placed': self.at(0, 0, 0) == 'water',
            'soil_cells_placed': sum(1 for v in ring.values() if v in ('dirt', 'farmland')),
            'soil_cells_tilled': sum(1 for v in ring.values() if v == 'farmland'),
            'cells_planted': sum(1 for cell in RING if ring[cell] == 'farmland' and crops[cell]),
            'cell_facing_agent_open': ring[GAP] is None,
            'tree_planted': self.at(TREE[0], 1, TREE[1]) is not None,
            'carrying': {k.split(':')[1]: v for k, v in self.items.items()
                         if k in ('minecraft:dirt', 'minecraft:wooden_hoe', 'minecraft:water_bucket', 'minecraft:bucket',
                                  self.brief['crop'], 'minecraft:oak_sapling', 'minecraft:bread')},
        }

    def done(self):
        f = self.facts()
        return (f['faction'] != 'none' and f['site_chunk_claimed'] and f['water_placed'] and f['cells_planted'] == len(RING)
                and (f['tree_planted'] or not self.brief['sapling']))

    def options(self):
        """Every action that is possible right now: key -> (what it means, how to do it)."""
        f, out = self.facts(), {}
        ring = {cell: self.at(cell[0], 0, cell[1]) for cell in RING}
        near = self.distance() < .9

        def add(key, text, **how):
            out[key] = (text, how)

        if f['game_mode'] != self.brief['mode']:
            add('set_game_mode', f"Switch to {self.brief['mode']} mode, as the brief asks.", run=f"gamemode {self.brief['mode']}")
        supplied = self.has('minecraft:dirt', 10) and self.has('minecraft:wooden_hoe') and self.has(self.brief['crop'], 8) \
            and (self.has('minecraft:water_bucket') or f['water_placed']) \
            and (self.has('minecraft:oak_sapling') or f['tree_planted'] or not self.brief['sapling'])
        if not supplied and self.memory.get('kit_tries', 0) < 2:
            add('take_kit', 'Ask the server for the farming kit: soil, hoe, water bucket, seeds.', run=f"kit {self.brief['kit']}", kit=True)
        if f['faction'] == 'none':
            add('create_faction', f"Found the faction {self.brief['faction']} (costs money).", run=f"f create {self.brief['faction']}", faction=True)
        if not near:
            add('walk_to_site', 'Walk to the standing spot beside the farm site.', goto=self.stand())
        if near and f['faction'] != 'none' and not f['site_chunk_claimed']:
            add('claim_land', 'Claim the chunk the agent stands in for the faction (costs money).', run='f claim', faction=True)
        if near and supplied:
            dirt, hoe, seed = 'minecraft:dirt', 'minecraft:wooden_hoe', self.brief['crop']
            if not f['floor_under_water_placed']:
                add('place_floor', 'Put a soil block under where the water goes, so the water cannot drain away.', hold=dirt, place=[self.site[0], self.site[1] - 1, self.site[2]])
            def soil(cell):
                name = f"{cell[0]:+d}{cell[1]:+d}"
                if ring[cell] is None or ring[cell] == 'water':
                    note = ' This is the cell the water is poured through: closing it first blocks the pour.' if cell == GAP and not f['water_placed'] else ''
                    add(f'place_soil_{name}', f'Place soil in plot cell {name}.{note}', hold=dirt, place=self.cell(*cell))
                elif ring[cell] == 'dirt' and f['water_placed']:
                    add(f'till_{name}', f'Till plot cell {name} into farmland.', hold=hoe, useOn=self.cell(*cell))
                elif ring[cell] == 'farmland' and not self.at(cell[0], 1, cell[1]):
                    add(f'plant_{name}', f'Sow plot cell {name}.', hold=seed, useOn=self.cell(*cell))
            for cell in RING:
                if cell != GAP:
                    soil(cell)
            if not f['water_placed'] and f['floor_under_water_placed'] and self.has('minecraft:water_bucket'):
                if ring[GAP] is None:
                    add('pour_water', 'Pour the water into the middle. Best once the other seven soil cells are in, so it cannot spread.',
                        hold='minecraft:water_bucket', pour=[self.site[0], self.site[1] - 1, self.site[2]])
                else:
                    add('reopen_facing_cell', 'Dig out the soil in the cell facing the agent, so the water can be poured through it.', dig=self.cell(*GAP))
            soil(GAP)
            if self.brief['sapling'] and not f['tree_planted'] and self.has('minecraft:oak_sapling'):
                tree = [self.site[0] + TREE[0], self.site[1], self.site[2] + TREE[1]]
                if self.at(TREE[0], 0, TREE[1]) is None:
                    add('place_tree_soil', 'Place a soil block beside the plot for a tree.', hold=dirt, place=tree)
                else:
                    add('plant_tree', 'Plant an oak sapling beside the plot.', hold='minecraft:oak_sapling', useOn=tree)
        if self.status.get('food', 20) < 10 and self.has('minecraft:bread'):
            add('eat', 'Eat bread.', hold='minecraft:bread', use=40)
        add('wait', 'Do nothing for a moment (only if something is still settling).', wait=1)
        add('ask_planner', 'Stop and hand back to the planner: the brief cannot be carried out as written.', stop='planner')
        return out


class Script:
    """No model: the first possible action. For testing the game side."""
    name = 'script'

    def decide(self, state, options):
        return {'action': next(iter(options)), 'confidence': None, 'stuck': None, 'probabilities': {}}


class Jev:
    """TypeSafe's System One API, or any server that takes the same request."""
    name = 'jev'

    def __init__(self):
        settings = local_settings().get('jev', {})
        self.key = os.environ.get('TYPESAFE_API_KEY') or settings.get('key')
        self.url = (os.environ.get('TYPESAFE_BASE_URL') or settings.get('base_url') or 'https://api.typesafe.ai').rstrip('/') + '/v1/systemone'
        self.model = settings.get('model', 'jev-latest')
        if not self.key and 'api.typesafe.ai' in self.url:
            raise SystemExit('No Jev key: set TYPESAFE_API_KEY, or "jev": {"key": "..."} in local.json (keys come from console.typesafe.ai).')
        self.tokens = 0

    def decide(self, state, options):
        body = {'model': self.model, 'state': state, 'questions': {
            'action': {'type': 'choice', 'instructions': 'Choose the one action that best advances the objective from the current facts. '
                       'Respect the rules and the planner notes. Prefer finishing what the plot needs next over waiting.',
                       'criteria': {key: text for key, (text, _) in options.items()}},
            'stuck': {'type': 'noul', 'instructions': 'Do the recent results show the same action failing repeatedly, so that the planner should step in?'},
            'danger': {'type': 'score', 'instructions': 'How much danger is the player in right now?',
                       'criteria': ['none', 'low health or hunger', 'about to die']},
        }}
        headers = {'Content-Type': 'application/json'}
        if self.key:
            headers['Authorization'] = 'Bearer ' + self.key
        request = urllib.request.Request(self.url, json.dumps(body).encode(), headers)
        try:
            with urllib.request.urlopen(request, timeout=20) as reply:
                answer = json.load(reply)
        except urllib.error.HTTPError as e:
            raise SystemExit(f'Decision model refused the request: {e.code} {e.read()[:300].decode(errors="replace")}')
        self.tokens += answer.get('usage', {}).get('input_tokens', 0)
        answers = answer['answers']
        return {'action': answers['action']['choice'], 'confidence': answers['action'].get('confidence'),
                'probabilities': answers['action'].get('probabilities', {}), 'stuck': answers['stuck'].get('noul'),
                'danger': answers['danger'].get('score')}


def sense(game, memory, lines=None):
    """Faction membership and land are only known to the server: ask it."""
    lines = game.command('f info') if lines is None else lines
    text = '\n'.join(lines)
    name = re.search(r'== (.+?) ==', text)
    land = re.search(r'Land: (\d+)', text)
    if name or land:
        memory['faction'] = name.group(1) if name else memory.get('faction')
        memory['land'] = int(land.group(1)) if land else memory.get('land', 0)
    elif 'not found' in text:
        memory['faction'], memory['land'] = None, 0


def carry_out(game, how, memory):
    """Do one action; returns a short result for the journal and for the model's next look."""
    if 'hold' in how and not game.send(hold=how['hold']).get('ok'):
        return f"failed: no {how['hold']} to hold"
    if 'run' in how:
        lines = game.command(how['run'], 2)
        said = ' / '.join(lines[-3:])
        if how.get('kit'):
            memory['kit_tries'] = memory.get('kit_tries', 0) + 1
        if how.get('faction'):
            if re.search(r'Chunk claimed|already claimed', said):
                memory['claimed_here'] = True
                (HOME / 'claimed.json').write_text(json.dumps(memory['site']))
            sense(game, memory)
        return 'server said: ' + (said or 'nothing')
    if 'goto' in how:
        x, z = how['goto']
        game.send(goto=[x, z], ticks=160)
        for _ in range(100):
            time.sleep(.1)
            if not game.status().get('busy'):
                break
        status = game.fresh()
        return f"now {math.hypot(status['x'] - x, status['z'] - z):.1f} blocks from the spot"
    if 'dig' in how:
        game.send(dig=how['dig'], ticks=120)
        for _ in range(70):
            time.sleep(.1)
            if not game.status().get('busy'):
                break
        return 'dug'
    for verb in ('place', 'useOn', 'pour'):
        if verb in how:
            time.sleep(.15)  # the held item reaches the server before the click
            ok = game.send(**{verb: how[verb]}).get('ok')
            time.sleep(.35)
            return 'done' if ok else 'failed: the click did nothing'
    if 'use' in how:
        game.send(use=how['use'])
        time.sleep(how['use'] / 20 + .2)
        return 'done'
    time.sleep(how.get('wait', 0))
    return 'waited'


def journal(entry):
    HOME.mkdir(parents=True, exist_ok=True)
    with JOURNAL.open('a') as out:
        out.write(json.dumps(entry) + '\n')


def frame(game, last):
    """One picture a minute, the last five kept: for the planner, not for the controller."""
    if time.time() - last < 60:
        return last
    name = f'agent-{int(time.time())}.png'
    game.send(screenshot=name)
    for old in sorted((SESSION / 'screenshots').glob('agent-*.png'))[:-5]:
        old.unlink()
    return time.time()


def run(args):
    game, brief = Game(), load_brief()
    status = game.status()
    if not status.get('server'):
        raise SystemExit('The agent plays on the server: start with ./hyrule start --multiplayer.')
    if status.get('screen'):
        game.send(closeScreen=True)
    if not brief['site']:
        # Seven blocks north of the player, at foot level: the plot rises one block out of the ground.
        brief['site'] = [math.floor(status['x']), math.floor(status['y']), math.floor(status['z']) - 10]
        HOME.mkdir(parents=True, exist_ok=True)
        BRIEF.write_text(json.dumps(brief, indent=1) + '\n')
    backend = Jev() if args.backend == 'jev' else Script()
    memory, recent, last_frame, low = {'site': brief['site']}, [], 0, 0
    claimed = HOME / 'claimed.json'
    memory['claimed_here'] = claimed.is_file() and json.loads(claimed.read_text()) == brief['site']
    sense(game, memory)
    memory['claimed_here'] = memory['claimed_here'] and memory['land'] > 0
    print(f"Brief: {brief['objective']}\nSite {brief['site']}, backend {backend.name}.")
    for step in range(args.steps):
        last_frame = frame(game, last_frame)
        world = World(brief, game.fresh(), memory)
        if world.done():
            journal({'time': time.time(), 'step': step, 'outcome': 'done', 'facts': world.facts()})
            print(f'Done after {step} decisions.' + (f' {backend.tokens} input tokens.' if hasattr(backend, 'tokens') else ''))
            game.send(screenshot='agent-done.png')
            return
        options = world.options()
        if set(options) <= {'wait', 'ask_planner'}:
            journal({'time': time.time(), 'step': step, 'outcome': 'needs_planner', 'facts': world.facts(), 'recent': recent[-6:]})
            print('Stopped for the planner: nothing left that the agent can do, and the brief is not met.')
            return
        state = {'objective': brief['objective'], 'rules': brief['rules'], 'planner_notes': brief['notes'], 'done_when': brief['done_when'],
                 'facts': world.facts(), 'recent_results': recent[-6:], 'server_chat': world.status.get('chat', [])[-4:]}
        started = time.time()
        decision = backend.decide(state, options)
        took = time.time() - started
        key = decision['action'] if decision['action'] in options else 'wait'
        text, how = options[key]
        confidence = decision.get('confidence')
        low = low + 1 if confidence is not None and confidence < .35 else 0
        if 'stop' in how or low >= 3 or (decision.get('stuck') or 0) > .85:
            journal({'time': time.time(), 'step': step, 'outcome': 'needs_planner', 'decision': decision, 'facts': world.facts(), 'recent': recent[-6:]})
            print('Stopped for the planner: see agent.py report.')
            return
        result = carry_out(game, how, memory)
        recent.append({'action': key, 'result': result})
        if len(recent) >= 5 and all(r == recent[-1] for r in recent[-5:]) and key != 'wait':
            journal({'time': time.time(), 'step': step, 'outcome': 'needs_planner', 'facts': world.facts(), 'recent': recent[-6:]})
            print(f'Stopped for the planner: {key} gave "{result}" five times running.')
            return
        journal({'time': time.time(), 'step': step, 'action': key, 'result': result, 'confidence': confidence, 'seconds': round(took, 3),
                 'options': list(options), 'probabilities': decision.get('probabilities'), 'facts': state['facts']})
        print(f"{step:3d} {key:22s} {'' if confidence is None else f'{confidence:.2f} '}{result}")
    print('Out of steps.')


def report():
    brief = load_brief()
    print(json.dumps(brief, indent=1))
    if JOURNAL.is_file():
        lines = JOURNAL.read_text().splitlines()
        print(f'\n{len(lines)} journal entries; the last ten:')
        for line in lines[-10:]:
            e = json.loads(line)
            print(' ', e.get('step'), e.get('action') or e.get('outcome'), '-', e.get('result', ''))
        print('\nFacts:', json.dumps(json.loads(lines[-1]).get('facts'), indent=1))
    frames = sorted((SESSION / 'screenshots').glob('agent-*.png'))
    print('\nFrames:', *frames[-6:], sep='\n  ')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='verb', required=True)
    b = sub.add_parser('brief')
    b.add_argument('fields', nargs='*')
    r = sub.add_parser('run')
    r.add_argument('--backend', choices=('jev', 'script'), default='jev')
    r.add_argument('--steps', type=int, default=120)
    sub.add_parser('report')
    args = parser.parse_args()
    if args.verb == 'brief':
        brief = load_brief()
        for field in args.fields:
            key, _, value = field.partition('=')
            if key not in DEFAULT_BRIEF:
                raise SystemExit(f'Unknown field {key}: the brief has {", ".join(DEFAULT_BRIEF)}')
            if key == 'site':
                value = [int(v) for v in value.split(',')] if value else None
            elif key == 'sapling':
                value = value.lower() in ('1', 'true', 'yes')
            elif key == 'rules':
                value = [v.strip() for v in value.split(';') if v.strip()]
            brief[key] = value
        if args.fields:
            HOME.mkdir(parents=True, exist_ok=True)
            BRIEF.write_text(json.dumps(brief, indent=1) + '\n')
        print(json.dumps(brief, indent=1))
    elif args.verb == 'run':
        run(args)
    else:
        report()


if __name__ == '__main__':
    main()
