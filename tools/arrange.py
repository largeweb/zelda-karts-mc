#!/usr/bin/env python3
"""Hyprland layout helper: Minecraft is the play window, Zelda's window is parked on its own workspace."""
import json, subprocess, sys, time
from common import config
from manage import sessions


def clients():
    return json.loads(subprocess.check_output(['hyprctl', 'clients', '-j']))


def dispatch(code):
    subprocess.run(['hyprctl', 'dispatch', code], check=True, stdout=subprocess.DEVNULL)


def main():
    c = config()
    deadline = time.monotonic() + (120 if '--wait' in sys.argv else 0)
    while True:
        owned = sessions(c)
        windows = clients()
        world = next((w for w in windows if owned.get(w['pid']) == 'Zelda' and w['title'] != 'Console'), None)
        player = next((w for w in windows if owned.get(w['pid']) == 'Minecraft'), None)
        if world and player:
            break
        if time.monotonic() >= deadline:
            raise RuntimeError('Launch both games first, then run ./hyrule arrange.')
        time.sleep(1)
    monitor = next(m for m in json.loads(subprocess.check_output(['hyprctl', 'monitors', '-j'])) if m['focused'])
    # Windows are addressed directly, so this never takes keyboard focus from the user.
    for w in (world, player):
        target = 'window=' + json.dumps('address:' + w['address'])
        if not w['floating']:
            dispatch('hl.dsp.window.float({action="set",' + target + '})')
        if w is world:
            dispatch('hl.dsp.window.move({workspace="name:Zelda-renderer",follow=false,' + target + '})')
        if not w.get('fullscreen'):
            dispatch('hl.dsp.window.resize({x=1280,y=720,relative=false,' + target + '})')
            # Centre on the monitor; a window left off-screen is throttled by the compositor.
            x, y = (monitor['width'] - 1280) // 2, (monitor['height'] - 720) // 2
            dispatch('hl.dsp.window.move({x=%d,y=%d,relative=false,%s})' % (monitor['x'] + x, monitor['y'] + y, target))
    print('Minecraft is the play window. Zelda renders on workspace Zelda-renderer.')


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError) as e:
        print(e, file=sys.stderr)
        sys.exit(1)
