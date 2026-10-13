"""Blueprint: a homestead in Hyrule Field. A timber house, a cellar base under it, farms and a pen.

Coordinates are (east, up, south) from the house's north-west floor corner; height 0 is the
first layer above the Zelda ground. Farms use the layer below it (-1): soil, sand and water sit
flush under the grass the game draws, so crops, cane and fences stand on Hyrule's own ground.

The cellar is dug with the server's dig command from the layer under the ground down, which
leaves the ground above intact; only the ladder shaft opens it. Dug cells take blocks, and a
water bucket waterlogs one, but water does not flow into them.

Each entry is one thing done by hand: an item put in a cell (looking a given way where the
block takes its direction from the player), a bucket poured, a cell dug, or an animal released."""

GROUND = 0          # the lowest height a player can hover at, relative to the site
SAME = ('observer', 'door', 'stairs', 'bed', 'fence_gate')   # these face the way the player looks; the rest face the player


def placements():
    out = []

    def put(part, at, item, look=None, on=False, grows=False, zone=None, block=None, expect=None):
        name = block or {'redstone': 'redstone_wire', 'wheat_seeds': 'wheat', 'carrot': 'carrots', 'potato': 'potatoes',
                         'beetroot_seeds': 'beetroots', 'melon_seeds': 'melon_stem', 'pumpkin_seeds': 'pumpkin_stem'}.get(item, item)
        if look and not expect and look not in ('up', 'down') or look in ('up', 'down') and item in ('piston', 'observer'):
            opposite = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east', 'up': 'down', 'down': 'up'}
            expect = 'facing=' + (look if any(item.endswith(kind) for kind in SAME) else opposite[look])
        out.append({'part': part, 'at': tuple(at), 'item': 'minecraft:' + item, 'block': name, 'look': look, 'delay': None, 'pour': False,
                    'kind': 'place', 'on': on, 'grows': grows, 'zone': zone, 'expect': expect})

    def pour(part, at, item='water_bucket', zone=None, logged=False):
        out.append({'part': part, 'at': tuple(at), 'item': 'minecraft:' + item, 'block': 'light' if logged else item.split('_')[0], 'look': None,
                    'delay': None, 'pour': True, 'kind': 'pour', 'on': not logged, 'grows': False, 'zone': zone,
                    'expect': 'waterlogged=true' if logged else None})

    def dig(part, at):
        out.append({'part': part, 'at': tuple(at), 'item': None, 'block': 'light', 'look': None, 'delay': None, 'pour': False,
                    'kind': 'dig', 'on': False, 'grows': False, 'zone': None, 'expect': None})

    def egg(part, at, animal):
        out.append({'part': part, 'at': tuple(at), 'item': f'minecraft:{animal}_spawn_egg', 'block': animal, 'look': None, 'delay': None,
                    'pour': False, 'kind': 'egg', 'on': True, 'grows': True, 'zone': None, 'expect': None})

    def ring(x0, x1, z0, z1):
        return [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if x in (x0, x1) or z in (z0, z1)]

    # --- The cellar: dug top-down so each layer can be seen from the surface, then lined and furnished from inside.
    SHAFT = (1, 1)
    dig('cellar_dig', (SHAFT[0], -1, SHAFT[1]))
    for y in (-2, -3, -4, -5):
        for x in range(0, 7):
            for z in range(0, 6):
                dig('cellar_dig', (x, y, z))
    beds = {(5, 1), (5, 2), (5, 3), (5, 4), (4, 1), (4, 2), (4, 3), (4, 4)}
    for x in range(0, 7):
        for z in range(0, 6):
            if (x, z) not in beds:
                put('cellar_floor', (x, -5, z), 'spruce_planks', zone='base')
    for y, block in ((-4, 'deepslate_bricks'), (-3, 'stone_bricks'), (-2, 'stone_bricks')):
        for x, z in ring(0, 6, 0, 5):
            put('cellar_walls', (x, y, z), block, zone='base')
    # Underground farm: nether wart on soul sand, mushrooms on podzol, a carrot bed beside a water cell.
    for z in (1, 2, 3, 4):
        put('cellar_farm', (5, -5, z), 'soul_sand', zone='base')
        put('cellar_farm', (5, -4, z), 'nether_wart', on=True, grows=True, zone='base')
    put('cellar_farm', (4, -5, 1), 'podzol', zone='base')
    put('cellar_farm', (4, -4, 1), 'red_mushroom', on=True, grows=True, zone='base')
    put('cellar_farm', (4, -5, 2), 'podzol', zone='base')
    put('cellar_farm', (4, -4, 2), 'brown_mushroom', on=True, grows=True, zone='base')
    put('cellar_farm', (4, -5, 3), 'farmland', zone='base')
    put('cellar_farm', (4, -4, 3), 'carrot', on=True, grows=True, zone='base')
    pour('cellar_farm', (4, -5, 4), zone='base', logged=True)
    for y in (-4, -3, -2, -1):
        put('cellar_fittings', (SHAFT[0], y, SHAFT[1]), 'ladder', 'north', zone='base' if y < -1 else None, expect='facing=south')
    put('cellar_fittings', (1, -4, 4), 'chest', 'south', zone='base', expect='facing=north')
    put('cellar_fittings', (2, -4, 4), 'chest', 'south', zone='base', expect='facing=north')
    put('cellar_fittings', (3, -4, 4), 'barrel', zone='base')
    put('cellar_fittings', (1, -4, 3), 'furnace', 'west', zone='base', expect='facing=east')
    put('cellar_fittings', (1, -4, 2), 'crafting_table', zone='base')
    put('cellar_fittings', (3, -4, 1), 'soul_lantern', zone='base')
    put('cellar_fittings', (2, -4, 1), 'brewing_stand', zone='base')

    # --- The house: a raised plank floor, a stone course, plaster walls between timber posts, a stepped dark roof.
    for x in range(0, 7):
        for z in range(0, 6):
            if (x, z) == SHAFT:
                put('house_floor', (x, 0, z), 'spruce_trapdoor')
            else:
                put('house_floor', (x, 0, z), 'spruce_planks')
    corners = {(0, 0), (6, 0), (0, 5), (6, 5)}
    windows = {(1, 5), (5, 5), (1, 0), (3, 0), (5, 0), (0, 2), (0, 3), (6, 2), (6, 3)}
    door = (3, 5)
    for y in (1, 2, 3):
        for x, z in ring(0, 6, 0, 5):
            if (x, z) in corners:
                put('house_walls', (x, y, z), 'stripped_oak_log')
            elif (x, z) == door and y < 3:
                if y == 1:
                    put('house_walls', (x, y, z), 'oak_door', 'north', on=True)
            elif y == 2 and (x, z) in windows:
                put('house_walls', (x, y, z), 'glass_pane')
            else:
                put('house_walls', (x, y, z), 'stone_bricks' if y == 1 else 'white_terracotta')
    for x in range(0, 7):
        for z in range(0, 6):
            put('house_roof', (x, 4, z), 'spruce_planks')
    for x, z in ring(-1, 7, -1, 6):
        put('house_roof', (x, 4, z), 'dark_oak_slab')
    for x in range(1, 6):
        for z in range(1, 5):
            if (x, z) != (5, 1):
                put('house_roof', (x, 5, z), 'dark_oak_slab', on=True)
    put('house_roof', (5, 5, 1), 'bricks')
    put('house_roof', (5, 6, 1), 'campfire', on=True)
    put('house_fittings', (5, 1, 1), 'red_bed', 'south', on=True, expect='part=foot')
    put('house_fittings', (1, 1, 4), 'chest', 'north', on=True, expect='facing=south')
    put('house_fittings', (2, 1, 1), 'crafting_table', on=True)
    put('house_fittings', (3, 1, 1), 'furnace', 'north', on=True, expect='facing=south')
    put('house_fittings', (5, 1, 4), 'lantern', on=True)
    put('house_fittings', (3, 0, 6), 'oak_stairs', 'north')
    for x, flower in ((0, 'poppy'), (1, 'blue_orchid'), (5, 'allium'), (6, 'oxeye_daisy')):
        put('garden', (x, -1, 6), 'dirt')
        put('garden', (x, 0, 6), flower, on=True, grows=True)
    # A well east of the house.
    for x, z in ring(10, 12, 1, 3):
        put('well', (x, 0, z), 'mossy_stone_bricks')
    put('well', (11, -1, 2), 'stone_bricks')
    pour('well', (11, 0, 2))
    for x, z in ((10, 1), (12, 1), (10, 3), (12, 3)):
        put('well', (x, 1, z), 'oak_fence', on=True)
        put('well', (x, 2, z), 'oak_fence', on=True)
    for x in (10, 11, 12):
        for z in (1, 2, 3):
            put('well', (x, 3, z), 'dark_oak_slab')
    put('well', (11, 2, 2), 'lantern', 'up', expect='hanging=true')

    # --- Farm 1: four crops either side of a hidden water channel, fenced.
    for x in range(0, 9):
        put('crop_field', (x, -2, 11), 'stone_bricks')
    put('crop_field', (-1, -1, 11), 'dirt')
    put('crop_field', (9, -1, 11), 'dirt')
    for z, seed in ((9, 'wheat_seeds'), (10, 'carrot'), (12, 'potato'), (13, 'beetroot_seeds')):
        for x in range(0, 9):
            put('crop_soil', (x, -1, z), 'farmland')
    for x in range(0, 9):
        pour('crop_water', (x, -1, 11))
    for z, seed in ((9, 'wheat_seeds'), (10, 'carrot'), (12, 'potato'), (13, 'beetroot_seeds')):
        for x in range(0, 9):
            put('crops', (x, 0, z), seed, on=True, grows=True)
    for x, z in ring(-1, 9, 8, 14):
        put('field_fence', (x, 0, z), 'oak_fence_gate' if (x, z) == (4, 8) else 'oak_fence', 'south' if (x, z) == (4, 8) else None)
    for x, z in ((-1, 8), (9, 8), (-1, 14), (9, 14)):
        put('field_fence', (x, 1, z), 'lantern', on=True)

    # --- Farm 2 (automatic): sugar cane. An observer above each piston sees the cane reach three high and the row cuts it.
    for x in range(0, 6):
        put('cane_bed', (x, -2, 18), 'stone_bricks')
        put('cane_bed', (x, -1, 17), 'sand', on=True)
        put('cane_soil', (x, -1, 19), 'farmland')
        put('cane_bed', (x, -1, 20), 'dirt')
    put('cane_bed', (-1, -1, 18), 'dirt')
    put('cane_bed', (6, -1, 18), 'dirt')
    for x in range(0, 6):
        pour('cane_water', (x, -1, 18))
    for x in range(0, 6):
        put('cane_machine', (x, 0, 16), 'stone_bricks')
        put('cane_machine', (x, 0, 15), 'stone_bricks')
        put('cane_machine', (x, 1, 15), 'stone_bricks', on=True)
        put('cane_machine', (x, 1, 16), 'piston', 'north')
        put('cane_machine', (x, 2, 16), 'observer', 'south', on=True)
        put('cane_machine', (x, 2, 15), 'redstone', on=True)
    for x in range(0, 6):
        put('cane', (x, 0, 17), 'sugar_cane', on=True, grows=True)
    # --- Farm 3: melons and pumpkins on the other bank of the same water.
    for x in range(0, 6):
        put('vines', (x, 0, 19), 'melon_seeds' if x % 2 == 0 else 'pumpkin_seeds', on=True, grows=True)
    # --- Farm 4 (automatic): cactus breaks itself against the rail beside it as it grows.
    for z in (16, 18, 20):
        put('cactus', (8, -1, z), 'sand', on=True)
        put('cactus', (8, 0, z), 'cactus', on=True, grows=True)
        put('cactus', (9, 0, z + 1), 'oak_fence')
        put('cactus', (9, 1, z + 1), 'oak_fence', on=True)
        put('cactus', (9, 1, z), 'oak_fence')
    # --- Farm 5: a tree nursery.
    for (x, z), tree in zip(((10, 23), (13, 23), (10, 27), (13, 27), (12, 25)), ('oak', 'birch', 'spruce', 'cherry', 'acacia')):
        put('nursery', (x, -1, z), 'dirt')
        put('nursery', (x, 0, z), tree + '_sapling', on=True, grows=True)
    # --- The pen: a hidden grass floor for the animals to stand on, a fence, and stock.
    for x in range(0, 7):
        for z in range(23, 28):
            put('pen', (x, -1, z), 'grass_block')
    for x, z in ring(-1, 7, 22, 28):
        put('pen', (x, 0, z), 'oak_fence_gate' if (x, z) == (3, 22) else 'oak_fence', 'south' if (x, z) == (3, 22) else None)
    for x, z in ((-1, 22), (7, 22), (-1, 28), (7, 28)):
        put('pen', (x, 1, z), 'lantern', on=True)
    for (x, z), animal in zip(((1, 24), (2, 26), (4, 24), (5, 26), (3, 25), (5, 24)), ('cow', 'cow', 'sheep', 'sheep', 'chicken', 'chicken')):
        egg('stock', (x, 0, z), animal)
    # Sand falls: under the ground there is nothing to hold it, so each sand cell gets a block beneath.
    # (Added after the first run; kept at the end so earlier entries keep their numbers.)
    for x in range(0, 6):
        put('sand_base', (x, -2, 17), 'stone_bricks')
    for z in (16, 18, 20):
        put('sand_base', (8, -2, z), 'stone_bricks')
    return out


NEEDS = {
    'cane_bed': ['sand_base'], 'cactus': ['sand_base'],
    'cellar_floor': ['cellar_dig'], 'cellar_walls': ['cellar_dig'], 'cellar_farm': ['cellar_floor', 'cellar_walls'],
    'cellar_fittings': ['cellar_farm'],
    'house_floor': ['cellar_fittings'], 'house_walls': ['house_floor'], 'house_roof': ['house_walls'], 'house_fittings': ['house_floor'],
    'garden': ['house_walls'],
    # Farmland dries to dirt within a minute unless water is beside it: the water goes in first, the soil after.
    'crop_water': ['crop_field'], 'crop_soil': ['crop_water'], 'crops': ['crop_soil'], 'field_fence': ['crops'],
    'cane_water': ['cane_bed'], 'cane_soil': ['cane_water'], 'cane_machine': ['cane_water'], 'cane': ['cane_machine'], 'vines': ['cane_soil'],
    'stock': ['pen'],
}
# Between the surface and the cellar the player passes these two points, top first.
PORTAL = [(1.5, 1.6, 1.5), (1.5, -3.9, 1.5)]
# The open inside of the cellar: where a player in it can stand (east, south ranges; the height of the feet).
ROOM = ((1, 5), (1, 4), -4)

if __name__ == '__main__':
    cells, seen = placements(), {}
    for p in cells:
        if p['kind'] == 'place':
            assert (p['at'], 'place') not in seen, ('twice', p)
            seen[(p['at'], 'place')] = p
    count = {}
    for p in cells:
        count[p['kind']] = count.get(p['kind'], 0) + 1
    parts = {}
    for p in cells:
        parts[p['part']] = parts.get(p['part'], 0) + 1
    print(len(cells), count)
    print(parts)
    xs, ys, zs = zip(*(p['at'] for p in cells))
    print('extent', (min(xs), max(xs)), (min(ys), max(ys)), (min(zs), max(zs)))
