#!/usr/bin/env python3
"""Drive the running Minecraft client for local tests. Needs "dev": true in local.json.

  console.py status
  console.py screenshot NAME.png
  console.py move TICKS [forward|back|left|right|jump]
  console.py zelda ITEM_KEY          (e.g. hookshot; adds the item to the inventory)
  console.py attack TICKS | use TICKS | view YAW PITCH | slot N | camera N | close-screen | quit
  console.py <any server command, e.g. gamemode survival>"""
import json, sys, time, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / '.local/session'


def send(q):
    q['id'] = str(uuid.uuid4())
    path = ROOT / 'command.json'
    tmp = path.with_suffix('.tmp')
    tmp.write_text(json.dumps(q))
    tmp.replace(path)
    for _ in range(200):
        try:
            if json.loads((ROOT / 'result.json').read_text()).get('id') == q['id']:
                return
        except (OSError, ValueError):
            pass
        time.sleep(.05)
    raise SystemExit('Minecraft did not acknowledge the command')


def main(args):
    if args[:1] == ['status']:
        print((ROOT / 'status.json').read_text())
        return
    verb = args[0]
    if verb == 'screenshot':
        send({'screenshot': args[1]})
        time.sleep(1)
        print(ROOT / 'screenshots' / args[1])
    elif verb == 'move':
        send({'move': int(args[1]), 'direction': args[2] if len(args) > 2 else 'forward'})
    elif verb in ('attack', 'use'):
        send({verb: int(args[1])})
    elif verb == 'view':
        send({'view': [float(args[1]), float(args[2])]})
    elif verb in ('slot', 'camera'):
        send({verb: int(args[1])})
    elif verb == 'zelda':
        send({'zelda': args[1]})
    elif verb == 'respawn':
        send({'respawn': True})
    elif verb == 'close-screen':
        send({'closeScreen': True})
    elif verb == 'quit':
        send({'quit': True})
    else:
        send({'command': ' '.join(args)})


if __name__ == '__main__':
    main(sys.argv[1:])
