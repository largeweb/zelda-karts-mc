"""Blueprint: a cobblestone generator that feeds a self-repairing ring of cobblestone.

Seen from the side the ring is a floor, a wall, a ceiling and a second wall. One
piston pushes each new cobblestone along the floor; at every corner another piston
turns the row, 2 s after the one before it, so broken cells refill from behind. A
10 s repeater loop under the ground is the clock; a stone button starts it with its
1 s pulse. Coordinates below are (east, up, south) from the generator's cobblestone
cell; height 0 is the first layer above the ground, so -1 and -2 are out of sight."""

CASE, STOP = 'stone_bricks', 'obsidian'


def parts(size=6):
    """Ordered parts: name -> {text, needs, blocks or fill commands, test}. size is the ring's edge."""
    L, out = size, {}

    def part(name, text, needs, blocks, test=None):
        out[name] = {'text': text, 'needs': needs, 'blocks': blocks, 'test': test or blocks[-1]}

    def rep(flow):  # a repeater passes its signal away from the side it "faces"
        return f"repeater[facing={ {'south': 'north', 'north': 'south', 'west': 'east', 'east': 'west'}[flow] },delay=4]"

    part('foundation', 'Lay the hidden floor that the underground redstone stands on.', [],
         [('fill', (1, -2, -2), (5, -2, L + 7), CASE)], ((5, -2, -2), CASE))
    part('generator_casing', 'Build the trench that holds the water and the lava, with the drain hole between them.', [],
         [((a, h, z), CASE) for a, h, z in [(-3, 0, 0), (2, 0, 0), (-2, 0, -1), (-2, 0, 1), (-1, 0, -1), (-1, 0, 1), (1, 0, -1), (1, 0, 1),
                                           (-2, -1, 0), (0, -1, 0), (1, -1, 0), (-1, -1, -1), (-1, -1, 1), (-1, -2, 0), (0, -1, -1)]])
    part('stoppers', 'Place the four obsidian stoppers that end each side of the ring.', [],
         [((0, 0, L + 1), STOP), ((0, L + 1, L), STOP), ((0, L, 0), STOP), ((0, -1, 1), STOP)])
    part('pusher', 'Place the piston that pushes each new cobblestone out along the floor.', ['generator_casing'],
         [((0, 0, -1), 'piston[facing=south]')])
    part('corner_pistons', 'Place the three pistons that turn the row at the corners: up, back along the top, and down.', ['stoppers'],
         [((0, -1, L), 'piston[facing=up]'), ((0, L, L + 1), 'piston[facing=north]'), ((0, L + 1, 1), 'piston[facing=down]')])
    part('clock', 'Lay the 10 s clock: a loop of 25 repeaters under the ground.', ['foundation'],
         [('fill', (4, -1, 0), (4, -1, 11), rep('south')), ('fill', (5, -1, -1), (5, -1, 11), rep('north'))]
         + [((a, -1, z), 'redstone_wire') for a, z in [(4, 12), (5, 12), (5, -2), (4, -2), (4, -1), (3, -1), (2, -1), (1, -1)]])
    part('first_delay', 'Lay the 2 s delay from the clock to the first corner piston.', ['foundation', 'corner_pistons'],
         [('fill', (2, -1, 0), (2, -1, 4), rep('south'))] + [((a, -1, z), 'redstone_wire') for a, z in [(2, 5), (2, 6), (1, 6)]])
    part('riser', 'Build the stair of redstone that carries the signal up to the ceiling.', ['first_delay'],
         [((2, k - 1, L + 1 + k), CASE) for k in range(L + 1)] + [((2, k, L + 1 + k), 'redstone_wire') for k in range(L + 1)])
    top = 2 * L + 1
    part('top_delays', 'Lay the two 2 s delays along the top, to the pistons that fill the ceiling and the far wall.', ['riser', 'corner_pistons'],
         [('fill', (1, L - 1, 2), (1, L - 1, top), CASE), ((1, L, top), 'redstone_wire'),
          ('fill', (1, L, L + 2), (1, L, top - 1), rep('north')), ((1, L, L + 1), CASE),
          ('fill', (1, L, 2), (1, L, L), rep('north')), ((1, L, 1), CASE),
          ((1, L + 1, 1), 'redstone_wire[north=side,south=side,east=side,west=side]')])
    part('button', 'Put the stone button on its pedestal, above the clock.', ['clock'],
         [((3, 0, -1), CASE), ((3, 1, -1), 'stone_button[face=floor,facing=north]')])
    part('water', 'Pour the water into its end of the trench.', ['generator_casing', 'pusher'], [((-2, 0, 0), 'water')])
    part('lava', 'Pour the lava into its end of the trench. Only once the water is in: lava first would run loose.', ['generator_casing', 'pusher', 'water'],
         [((1, 0, 0), 'lava')])
    return out


def cells(size=6):
    """The ring's cells, in the order cobblestone travels through them."""
    L = size
    return ([(0, 0, z) for z in range(1, L + 1)] + [(0, h, L) for h in range(1, L + 1)]
            + [(0, L, z) for z in range(L - 1, 0, -1)] + [(0, h, 1) for h in range(L - 1, 0, -1)])


BUTTON, STAND = (3, 1, -1), (3.5, -3.5)
