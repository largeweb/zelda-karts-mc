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

import ring
import tube

ROOT = Path(__file__).resolve().parents[1]
SESSION = ROOT / '.local/session'
HOME = ROOT / '.local/agent'
BRIEF = HOME / 'brief.json'
JOURNAL = HOME / 'journal.jsonl'

# What the planner decides. Everything else the controller works out from the game.
DEFAULT_BRIEF = {
    'objective': 'Found a faction, claim the land under the farm site, and build a small irrigated wheat plot there.',
    'build': 'farm',               # farm: an irrigated plot. ring: one-lane generator, by commands. tube: tools/tube.py, by hand
    'focus': [],                   # tube: parts the operator wants worked on first
    'avoid': [],                   # tube: parts the operator wants left alone for now
    'size': 6,                     # ring only: blocks along each side
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


class Machine:
    """The cobblestone ring: parts placed with commands (creative, operator), in an order the model chooses."""

    def __init__(self, brief, status, memory):
        self.brief, self.status, self.memory = brief, status, memory
        self.site = tuple(brief['site'])
        self.parts = ring.parts(brief['size'])
        self.built = memory.setdefault('built', [])

    def world(self, at):
        return [self.site[0] + at[0], self.site[1] + at[1], self.site[2] + at[2]]

    def stand(self):
        return self.site[0] + ring.STAND[0], self.site[2] + ring.STAND[1]

    def distance(self):
        x, z = self.stand()
        return math.hypot(self.status['x'] - x, self.status['z'] - z)

    def facts(self):
        return {'game_mode': self.status.get('mode'), 'health': self.status.get('health'),
                'blocks_from_standing_spot': round(self.distance(), 1),
                'parts_built': list(self.built), 'parts_missing': [name for name in self.parts if name not in self.built],
                'clock_started': bool(self.memory.get('started')), 'cobblestone_on_the_floor': bool(self.memory.get('running'))}

    def done(self):
        return bool(self.memory.get('running'))

    def commands(self, part):
        for entry in part['blocks']:
            if entry[0] == 'fill':
                yield 'fill %d %d %d %d %d %d %s' % (*self.world(entry[1]), *self.world(entry[2]), entry[3])
            else:
                yield 'setblock %d %d %d %s' % (*self.world(entry[0]), entry[1])

    def options(self):
        out = {}
        if self.status.get('mode') != self.brief['mode']:
            out['set_game_mode'] = (f"Switch to {self.brief['mode']} mode, as the brief asks.", {'run': f"gamemode {self.brief['mode']}"})
        if self.distance() >= .9:
            out['walk_to_site'] = ('Walk to the standing spot beside the button pedestal.', {'goto': self.stand()})
        elif self.status.get('mode') == 'creative':
            for name, part in self.parts.items():
                if name not in self.built and all(need in self.built for need in part['needs']):
                    at, block = part['test']
                    out['build_' + name] = (part['text'], {'runs': list(self.commands(part)), 'part': name,
                                                           'test': 'execute if block %d %d %d %s' % (*self.world(at), block.split('[')[0])})
            if 'button' in self.built and not self.memory.get('started'):
                note = '' if len(self.built) == len(self.parts) else ' The machine is not complete yet: pressing now starts a clock with nothing to drive.'
                out['press_button'] = ('Press the stone button once to start the clock.' + note, {'useOn': self.world(ring.BUTTON), 'started': True})
            if self.memory.get('started'):
                out['check_floor'] = ('Look whether the first cobblestone has been pushed onto the floor of the ring.',
                                      {'check': 'execute if block %d %d %d cobblestone' % tuple(self.world((0, 0, 1)))})
        out['wait'] = ('Do nothing for a few seconds (the generator and the clock need time).', {'wait': 4})
        out['ask_planner'] = ('Stop and hand back to the planner: the brief cannot be carried out as written.', {'stop': 'planner'})
        return out


OPPOSITE = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east', 'up': 'down', 'down': 'up'}
VIEW = {'south': (0, 0), 'west': (90, 0), 'north': (180, 0), 'east': (-90, 0), 'down': (0, 90), 'up': (0, -90)}
REACH = 4.4


class Build:
    """A blueprint of hand placements (tools/tube.py): the player flies to the work, picks the item, looks the
    right way and clicks. What stands is read back from the blocks around the player, never assumed."""

    def __init__(self, brief, status, memory):
        self.brief, self.status, self.memory = brief, status, memory
        self.site = tuple(brief['site'])
        self.plan = tube.placements()
        self.placed = memory.setdefault('placed', set())
        self.wrong = {}
        self.seen = {(x, y, z): (name, extra[0] if extra else '') for x, y, z, name, *extra in status.get('blocks', [])}
        px, py, pz = math.floor(status['x']), math.floor(status['y']), math.floor(status['z'])
        for index, p in enumerate(self.plan):
            at = self.world(p['at'])
            if abs(at[0] - px) > 6 or abs(at[1] - py) > 5 or abs(at[2] - pz) > 6:
                continue  # out of sight: what was seen last stands
            found = self.seen.get(tuple(at))
            if found and self.right(p, found):
                self.placed.add(index)
            else:
                self.placed.discard(index)
                if found and not (p['pour'] and found[0] in ('water', 'lava')):
                    self.wrong[index] = found[0]
        self.eye = (status['x'], status['y'] + 1.62, status['z'])

    def world(self, at):
        return [self.site[0] + at[0], self.site[1] + at[1], self.site[2] + at[2]]

    @staticmethod
    def right(p, found):
        name, props = found
        if name != p['block']:
            return False
        if p['look'] and f"facing={OPPOSITE[p['look']]}" not in props:
            return False
        return not p['delay'] or f"delay={p['delay']}" in props

    def complete(self, part):
        return all(i in self.placed for i, p in enumerate(self.plan) if p['part'] == part)

    def ready(self):
        """Placements that can be made now: their part's prerequisites stand, and so does what they rest on."""
        index = {p['at']: i for i, p in enumerate(self.plan)}
        open_parts = {part for part in {p['part'] for p in self.plan}
                      if all(self.complete(need) for need in tube.NEEDS.get(part, [])) and part not in self.brief['avoid']}
        for i, p in enumerate(self.plan):
            if i in self.placed or p['part'] not in open_parts:
                continue
            below = index.get((p['at'][0], p['at'][1] - 1, p['at'][2]))
            if p['block'] in ('repeater', 'redstone_wire', 'stone_button') or p['pour']:
                if below is None or below not in self.placed:
                    continue
            yield i, p

    def reach(self, at):
        return math.dist(self.eye, (at[0] + .5, at[1] + .5, at[2] + .5))

    def stand(self, p):
        at = self.world(p['at'])
        return [at[0] + .5, max(at[1] + 1.3, self.site[1] + .1), at[2] + .5]

    def above(self, p):
        at = self.world(p['at'])
        return math.hypot(self.status['x'] - at[0] - .5, self.status['z'] - at[2] - .5) < .5 and self.status['y'] > at[1] + 1

    def facts(self):
        parts = {}
        for i, p in enumerate(self.plan):
            done, total = parts.get(p['part'], (0, 0))
            parts[p['part']] = (done + (i in self.placed), total + 1)
        return {'game_mode': self.status.get('mode'), 'flying': self.status.get('flying'),
                'position_in_blueprint': [round(self.status[k] - o, 1) for k, o in zip('xyz', self.site)],
                'holding': self.status.get('item'),
                'parts': {name: f'{done}/{total}' for name, (done, total) in parts.items()},
                'placed': len(self.placed), 'to_place': len(self.plan),
                'wrong_blocks_in_sight': len(self.wrong),
                'operator_focus': self.brief['focus'], 'operator_avoid': self.brief['avoid'],
                'clock_started': bool(self.memory.get('started')), 'cobblestone_made': bool(self.memory.get('running'))}

    def done(self):
        return bool(self.memory.get('running'))

    def options(self):
        out = {}
        if self.status.get('mode') != 'creative':
            out['set_game_mode'] = ('Switch to creative mode: this build needs flight and unlimited items.', {'run': 'gamemode creative'})
            out['ask_planner'] = ('Stop and hand back to the operator.', {'stop': 'planner'})
            return out
        for i, name in sorted(self.wrong.items(), key=lambda entry: self.reach(self.world(self.plan[entry[0]]['at'])))[:3]:
            at = self.world(self.plan[i]['at'])
            if self.reach(at) < REACH:
                out[f'remove_{i}'] = (f"Break the {name} standing where a {self.plan[i]['block']} belongs ({self.plan[i]['part']}).", {'dig': at})
        ready = sorted(self.ready(), key=lambda entry: self.reach(self.world(entry[1]['at'])))
        focus = [entry for entry in ready if entry[1]['part'] in self.brief['focus']]
        near = [(i, p) for i, p in (focus + ready) if i not in self.wrong and self.reach(self.world(p['at'])) < REACH
                and (not p['pour'] or self.above(p))]
        for i, p in list(dict(near).items())[:10]:
            at = self.world(p['at'])
            how = {'hold': p['item'], 'verify': i}
            what = f"{p['block']} for {p['part']} at {list(p['at'])}, {self.reach(at):.1f} blocks away"
            if p['pour']:
                out[f'pour_{i}'] = (f'Pour {what}.', {**how, 'pour': [at[0], at[1] - 1, at[2]]})
            else:
                if p['look']:
                    how['view'], what = VIEW[p['look']], what + f", looking {p['look']}"
                if p['delay'] and p['delay'] > 1:
                    how['clicks'], what = p['delay'] - 1, what + f", set to {p['delay']} ticks"
                out[f'place_{i}'] = (f'Place {what}.', {**how, 'place': at})
        parts = {}
        for i, p in ready:
            parts.setdefault(p['part'], (i, p))
        for part, (i, p) in parts.items():
            if not any(q['part'] == part for _, q in near):
                left = sum(1 for _, q in ready if q['part'] == part)
                note = ' The operator asked for this part first.' if part in self.brief['focus'] else ''
                out[f'fly_to_{part}'] = (f'Fly to the nearest unfinished cell of {part} ({left} ready to place there).{note}', {'flyto': self.stand(p)})
        if len(self.placed) == len(self.plan):
            button, first = self.world(tube.BUTTON), self.world(tube.FIRST)
            if not self.memory.get('started'):
                if self.reach(button) < REACH:
                    out['press_button'] = ('Press the stone button once to start the clock.', {'useOn': button, 'started': True})
                else:
                    out['fly_to_button'] = ('Fly to the stone button.', {'flyto': [button[0] + .5, button[1] + 1.2, button[2] + 2.5]})
            elif self.seen.get(tuple(first), ('',))[0] == 'cobblestone':
                out['report_running'] = ('Report that the first wall has started to rise: the build is finished.', {'running': True})
            else:
                out['fly_to_generators'] = ('Fly to where the first wall should start rising, to watch for cobblestone.',
                                            {'flyto': [first[0] + .5, first[1] + 1, first[2] - 3.5]})
        out['wait'] = ('Do nothing for a few seconds.', {'wait': 3})
        out['ask_planner'] = ('Stop and hand back to the operator: nothing here helps.', {'stop': 'planner'})
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
    if 'running' in how:
        memory['running'] = True
        return 'reported'
    if 'flyto' in how:
        game.send(flyto=how['flyto'])
        for _ in range(110):
            time.sleep(.1)
            if not game.status().get('busy'):
                break
        status = game.fresh()
        return f"now {math.dist((status['x'], status['y'], status['z']), how['flyto']):.1f} blocks from that spot"
    if 'verify' in how:
        # One hand placement: item, view, click, the repeater's delay; then the game says what stands there.
        if not game.send(hold=how['hold'], spare=sorted({p['item'] for p in tube.placements()})).get('ok'):
            return f"failed: no {how['hold']} and no free slot for it"
        time.sleep(.2)
        if 'pour' in how:
            game.send(pour=how['pour'])
        else:
            if 'view' in how:
                game.send(view=list(how['view']))
                time.sleep(.25)
            game.send(place=how['place'], aimed=True)
            for _ in range(how.get('clicks', 0)):
                time.sleep(.2)
                game.send(useOn=how['place'])
        time.sleep(.4)
        world = Build(memory['brief'], game.fresh(), memory)
        if how['verify'] in world.placed:
            return 'placed'
        found = world.seen.get(tuple(world.world(world.plan[how['verify']]['at'])))
        return f"failed: there is {'nothing' if not found else ' '.join(found).strip()} there"
    if 'runs' in how:
        for command in how['runs']:
            game.send(command=command)
            time.sleep(.12)
        if 'Test passed' in game.command(how['test'], .8)[-1:]:
            memory['built'].append(how['part'])
            return 'built'
        return 'failed: the part is not there afterwards'
    if 'check' in how:
        memory['running'] = 'Test passed' in game.command(how['check'], .8)[-1:]
        return 'cobblestone is there' if memory['running'] else 'not yet'
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
            if ok and how.get('started'):
                memory['started'] = True
            return 'done' if ok else 'failed: the click did nothing'
    if 'use' in how:
        game.send(use=how['use'])
        time.sleep(how['use'] / 20 + .2)
        return 'done'
    time.sleep(how.get('wait', 0))
    return 'waited'


INBOX, OUTBOX, NOTES, PLACED = HOME / 'inbox.jsonl', HOME / 'outbox.jsonl', HOME / 'memory.md', HOME / 'placed.json'


def note(path, text):
    HOME.mkdir(parents=True, exist_ok=True)
    with path.open('a') as out:
        out.write(json.dumps({'time': time.strftime('%Y-%m-%d %H:%M:%S'), 'text': text}) + '\n')


def observe():
    """Everything an operator needs for one tick, as one JSON document: the brief it wrote, what the controller
    did since, where the build stands, messages from the person, its own notes, and the frames to look at."""
    entries = [json.loads(line) for line in JOURNAL.read_text().splitlines()] if JOURNAL.is_file() else []
    actions = {}
    for e in entries[-200:]:
        key = (e.get('action') or e.get('outcome') or '').split('_')[0]
        actions[key] = actions.get(key, 0) + 1
    read = HOME / 'inbox.read'
    seen = int(read.read_text()) if read.is_file() else 0
    messages = INBOX.read_text().splitlines() if INBOX.is_file() else []
    read.parent.mkdir(parents=True, exist_ok=True)
    read.write_text(str(len(messages)))
    low = [e for e in entries[-200:] if (e.get('confidence') or 1) < .5]
    return {
        'brief': load_brief(),
        'controller': json.loads((HOME / 'state.json').read_text()) if (HOME / 'state.json').is_file() else 'not started',
        'facts': entries[-1].get('facts') if entries else None,
        'last_actions': [{k: e.get(k) for k in ('step', 'action', 'outcome', 'result', 'confidence') if e.get(k) is not None} for e in entries[-12:]],
        'action_counts_last_200': actions,
        'failures_last_200': sum(1 for e in entries[-200:] if str(e.get('result', '')).startswith('failed')),
        'unsure_decisions_last_200': [{'step': e['step'], 'action': e.get('action'), 'confidence': e.get('confidence'),
                                       'probabilities': e.get('probabilities')} for e in low[-5:]],
        'new_messages': [json.loads(line) for line in messages[seen:]],
        'memory': NOTES.read_text() if NOTES.is_file() else '',
        'frames': [str(f) for f in sorted((SESSION / 'screenshots').glob('agent-*.png'))[-5:]],
    }


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


def state(text, **more):
    HOME.mkdir(parents=True, exist_ok=True)
    (HOME / 'state.json').write_text(json.dumps({'state': text, 'time': time.strftime('%Y-%m-%d %H:%M:%S'), **more}))


def run(args):
    while True:
        state('running', backend=args.backend)
        result = run_once(args)
        state(result)
        if not args.forever or result == 'done':
            return
        # Handed back to the operator: carry on when the brief changes.
        changed = BRIEF.stat().st_mtime
        while BRIEF.stat().st_mtime == changed:
            time.sleep(2)


def run_once(args):
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
    if brief['build'] == 'tube':
        # What stood when the controller last ran; corrected from the blocks in sight as the player moves.
        saved = json.loads(PLACED.read_text()) if PLACED.is_file() else {}
        memory['placed'] = set(saved['placed']) if saved.get('site') == brief['site'] else set()
    elif brief['build'] == 'ring':
        # Which parts already stand, from an earlier run.
        machine = Machine(brief, status, memory)
        for name, part in machine.parts.items():
            at, block = part['test']
            if 'Test passed' in game.command('execute if block %d %d %d %s' % (*machine.world(at), block.split('[')[0]), .6)[-1:]:
                memory['built'].append(name)
    else:
        sense(game, memory)
        memory['claimed_here'] = memory['claimed_here'] and memory['land'] > 0
    print(f"Brief: {brief['objective']}\nSite {brief['site']}, backend {backend.name}.")
    for step in range(args.steps):
        last_frame = frame(game, last_frame)
        if BRIEF.is_file():
            brief.update(json.loads(BRIEF.read_text()))  # the operator may have changed it since the last step
        memory['brief'] = brief
        if 'placed' in memory:
            PLACED.write_text(json.dumps({'site': brief['site'], 'placed': sorted(memory['placed'])}))
        world = {'ring': Machine, 'tube': Build}.get(brief['build'], World)(brief, game.fresh(), memory)
        if world.done():
            journal({'time': time.time(), 'step': step, 'outcome': 'done', 'facts': world.facts()})
            print(f'Done after {step} decisions.' + (f' {backend.tokens} input tokens.' if hasattr(backend, 'tokens') else ''))
            game.send(screenshot='agent-done.png')
            return 'done'
        options = world.options()
        if set(options) <= {'wait', 'ask_planner'}:
            journal({'time': time.time(), 'step': step, 'outcome': 'needs_planner', 'facts': world.facts(), 'recent': recent[-6:]})
            print('Stopped for the planner: nothing left that the agent can do, and the brief is not met.')
            return 'waiting for the operator: nothing possible is left'
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
            return 'waiting for the operator: the model is unsure or reports being stuck'
        result = carry_out(game, how, memory)
        recent.append({'action': key, 'result': result})
        if len(recent) >= 5 and all(r == recent[-1] for r in recent[-5:]) and key != 'wait':
            journal({'time': time.time(), 'step': step, 'outcome': 'needs_planner', 'facts': world.facts(), 'recent': recent[-6:]})
            print(f'Stopped for the planner: {key} gave "{result}" five times running.')
            return f'waiting for the operator: {key} keeps giving "{result}"'
        journal({'time': time.time(), 'step': step, 'action': key, 'result': result, 'confidence': confidence, 'seconds': round(took, 3),
                 'options': list(options), 'probabilities': decision.get('probabilities'), 'facts': state['facts']})
        print(f"{step:3d} {key:22s} {'' if confidence is None else f'{confidence:.2f} '}{result}")
    print('Out of steps.')
    return 'waiting for the operator: out of steps'


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
    r.add_argument('--forever', action='store_true', help='when handed back to the operator, wait for a new brief and go on')
    sub.add_parser('report')
    sub.add_parser('observe', help='one JSON document for the operator: state, journal, messages, memory, frames')
    for verb, text in (('tell', 'a message from the person to the operator'), ('say', 'the operator answers the person'),
                       ('remember', 'the operator keeps a note for its later ticks')):
        sub.add_parser(verb, help=text).add_argument('text', nargs='+')
    args = parser.parse_args()
    if args.verb == 'brief':
        brief = load_brief()
        for field in args.fields:
            key, _, value = field.partition('=')
            if key not in DEFAULT_BRIEF:
                raise SystemExit(f'Unknown field {key}: the brief has {", ".join(DEFAULT_BRIEF)}')
            if key == 'site':
                value = [int(v) for v in value.split(',')] if value else None
            elif key == 'size':
                value = int(value)
            elif key == 'sapling':
                value = value.lower() in ('1', 'true', 'yes')
            elif key in ('focus', 'avoid'):
                value = [v.strip() for v in value.split(',') if v.strip()]
            elif key == 'rules':
                value = [v.strip() for v in value.split(';') if v.strip()]
            brief[key] = value
        if args.fields:
            HOME.mkdir(parents=True, exist_ok=True)
            BRIEF.write_text(json.dumps(brief, indent=1) + '\n')
        print(json.dumps(brief, indent=1))
    elif args.verb == 'run':
        run(args)
    elif args.verb == 'observe':
        print(json.dumps(observe(), indent=1))
    elif args.verb in ('tell', 'say'):
        note(INBOX if args.verb == 'tell' else OUTBOX, ' '.join(args.text))
    elif args.verb == 'remember':
        HOME.mkdir(parents=True, exist_ok=True)
        with NOTES.open('a') as out:
            out.write(f"- {time.strftime('%Y-%m-%d %H:%M')} {' '.join(args.text)}\n")
    else:
        report()


if __name__ == '__main__':
    main()
