"""Blueprint: eight cobblestone generators side by side feeding a self-repairing tube.

Seen from the end the tube is a wall, a ceiling, a second wall and a floor, each eight
blocks wide. Eight pistons under the generators push the new cobblestone up as the
first wall; at each corner eight more turn the rows, about 2 s after the ones before.
Under the floor is a lava pit, so breaking through drops you in until the floor has
repaired itself. One 10 s repeater loop under the ground is the clock for all four
sets of pistons; a stone button starts it with its 1 s pulse.

Coordinates are (east, up, south) from the first generator's cobblestone cell.
Height 0 is the first layer above the ground: -1 and -2 are out of sight.

Every entry is one thing the player does by hand: an item put in a cell while looking
a given way (pistons end up facing the player, repeaters carry the signal the way the
player looks), or a bucket poured onto the block below."""

W, H, D = 8, 12, 12   # lanes; wall height and ceiling depth (a piston moves twelve blocks)
CASE, STOP = 'stone_bricks', 'obsidian'
LANES = range(W)


def placements():
    out = []

    def put(part, at, item, look=None, delay=None, text=None):
        block = {'redstone': 'redstone_wire', 'water_bucket': 'water', 'lava_bucket': 'lava'}.get(item, item)
        out.append({'part': part, 'at': tuple(at), 'item': 'minecraft:' + item, 'block': block, 'look': look, 'delay': delay,
                    'pour': item.endswith('_bucket')})

    def repeaters(part, cells, look, delay=4):
        for at in cells:
            put(part, at, 'repeater', look, delay)

    def dust(part, cells):
        for at in cells:
            put(part, at, 'redstone')

    # 1. What the hidden redstone and the fluids stand on (height -2).
    floor = {(a, -3) for a in range(-6, 11)} | {(10, -4), (11, -4)} | {(a, -2) for a in LANES} | {(9, z) for z in range(-2, 16)} \
        | {(a, z) for a in (10, 11) for z in range(-3, 11)} | {(a, 1) for a in LANES} | {(a, z) for a in LANES for z in range(4, 12)}
    for a, z in sorted(floor):
        put('foundation', (a, -2, z), CASE)
    # 2. Height -1: the blocks that carry power to the first pistons (and hold the lava above them),
    #    the floor under the water, the sides of the drain and of the lava pit, the stoppers under the far wall.
    for a in LANES:
        put('underground_casing', (a, -1, -1), CASE)
        put('underground_casing', (a, -1, 2), CASE)
        put('underground_casing', (a, -1, 3), CASE)
        put('underground_casing', (a, -1, 12), STOP)
        put('underground_casing', (a, -1, 14), CASE)
        put('underground_casing', (a, -1, 15), CASE)
    for a in (-1, W):
        for z in [1] + list(range(4, 12)):
            put('underground_casing', (a, -1, z), CASE)
    put('underground_casing', (W, -1, 15), CASE)
    # 3. Height 0: the trench that holds the lava, the generators and the water.
    for a in LANES:
        put('generator_casing', (a, 0, -2), CASE)
        put('generator_casing', (a, 0, 3), STOP)
    for a in (-1, W):
        for z in (-1, 0, 1, 2):
            put('generator_casing', (a, 0, z), CASE)
    # 4. The pistons under the generators: they push the first wall up.
    for a in LANES:
        put('wall_pistons', (a, -1, 0), 'piston', 'down')
    # 5. The clock, and the line that brings its pulse to the first pistons.
    repeaters('clock', [(10, -1, z) for z in range(-2, 10)], 'south')
    dust('clock', [(10, -1, 10), (11, -1, 10)])
    repeaters('clock', [(11, -1, z) for z in range(9, -4, -1)], 'north')
    dust('clock', [(11, -1, -4), (10, -1, -4), (10, -1, -3), (9, -1, -3), (W, -1, -3)])
    dust('first_line', [(a, -1, -3) for a in reversed(LANES)])
    repeaters('first_line', [(a, -1, -2) for a in LANES], 'south', 1)
    # 6. Six seconds of delay under the ground, to the pistons that push the floor.
    repeaters('floor_delay', [(9, -1, z) for z in range(-2, 13)], 'south')
    dust('floor_delay', [(9, -1, 13), (9, -1, 14), (9, -1, 15), (W, 0, 15)] + [(a, 0, 15) for a in reversed(LANES)])
    repeaters('floor_pistons', [(a, 0, 14) for a in LANES], 'north', 1)
    for a in LANES:
        put('floor_pistons', (a, 0, 13), 'piston', 'south')
    # 7. Two seconds of delay, then the spiral of redstone that climbs to the top behind the first wall.
    repeaters('riser', [(a, -1, -3) for a in range(-1, -6, -1)], 'west')
    dust('riser', [(-6, -1, -3)])
    corners = [(-7, -3), (-7, -4), (-8, -4), (-8, -3)]
    for k in range(H + 1):
        a, z = corners[k % 4]
        put('riser', (a, k - 1, z), CASE)
        put('riser', (a, k, z), 'redstone')
    # 8. Along the top: to the pistons that push the ceiling, then two seconds more to those that push the far wall down.
    for a in range(-6, W + 1):
        put('top_shelf', (a, H - 1, -3), CASE)
    for a in LANES:
        put('top_shelf', (a, H - 1, -2), CASE)
    for z in range(-2, D + 3):
        put('top_shelf', (W, H - 1, z), CASE)
    for a in LANES:
        put('top_shelf', (a, H, D + 1), STOP)
        put('top_shelf', (a, H, D + 2), CASE)
    repeaters('ceiling_pistons', [(-6, H, -3)], 'east', 1)
    dust('ceiling_pistons', [(a, H, -3) for a in range(-5, W + 1)])
    repeaters('ceiling_pistons', [(a, H, -2) for a in LANES], 'south', 1)
    for a in LANES:
        put('ceiling_pistons', (a, H, -1), 'piston', 'north')
    repeaters('far_wall_pistons', [(W, H, z) for z in range(-2, 2)], 'south')
    dust('far_wall_pistons', [(W, H, z) for z in range(2, 9)])
    repeaters('far_wall_pistons', [(W, H, 9)], 'south')
    dust('far_wall_pistons', [(W, H, z) for z in range(10, D + 3)] + [(a, H + 1, D + 2) for a in reversed(LANES)])
    repeaters('far_wall_pistons', [(a, H + 1, D + 1) for a in LANES], 'north', 1)
    for a in LANES:
        put('far_wall_pistons', (a, H + 1, D), 'piston', 'up')
    # 9. The button, the lava pit under the floor, then the generators' water and lava.
    put('button', (10, 0, -3), CASE)
    put('button', (10, 1, -3), 'stone_button')
    for a in LANES:
        for z in range(4, 12):
            put('lava_pit', (a, -1, z), 'lava_bucket')
    for a in LANES:
        put('water', (a, 0, 2), 'water_bucket')
    for a in LANES:
        put('generator_lava', (a, 0, -1), 'lava_bucket')
    return out


# A part may only be started once these stand; within that, the order is the builder's choice.
NEEDS = {
    'underground_casing': ['foundation'], 'generator_casing': ['underground_casing'], 'wall_pistons': ['underground_casing'],
    'clock': ['foundation'], 'first_line': ['clock', 'underground_casing'], 'floor_delay': ['clock', 'underground_casing'],
    'floor_pistons': ['floor_delay'], 'riser': ['first_line'], 'top_shelf': ['riser'], 'ceiling_pistons': ['top_shelf'],
    'far_wall_pistons': ['ceiling_pistons'], 'button': ['clock'], 'lava_pit': ['underground_casing', 'floor_pistons'],
    'water': ['generator_casing', 'wall_pistons'], 'generator_lava': ['water'],
}
BUTTON = (10, 1, -3)
# Where cobblestone should appear first once the clock runs: above the first generator.
FIRST = (0, 1, 0)

if __name__ == '__main__':
    # Checks that need no game: nothing placed twice, and whatever must stand on a block has one.
    cells, seen = placements(), {}
    for p in cells:
        assert p['at'] not in seen, ('twice', p['at'])
        seen[p['at']] = p
    for p in cells:
        if p['block'] in ('repeater', 'redstone_wire', 'stone_button'):
            below = (p['at'][0], p['at'][1] - 1, p['at'][2])
            assert below in seen and seen[below]['block'] in (CASE, STOP), ('unsupported', p)
            assert cells.index(seen[below]) < cells.index(p), ('support comes later', p)
    count = {}
    for p in cells:
        count[p['block']] = count.get(p['block'], 0) + 1
    print(len(cells), 'placements:', count)
