package local.composite;
import java.util.List;
/** The in-game guide shown by /help. One short page per topic. */
public final class Guide {
 public record Page(String title,List<String> lines){}
 public static final List<Page> PAGES=List.of(
  new Page("Getting around",List.of(
   "Move, look, jump, sprint and fly exactly as in Minecraft.",
   "F5 switches between first person and seeing Link.",
   "Hyrule's ground, walls and ledges are solid; Link climbs low ledges by himself.",
   "In shops, some houses and the Market the game takes over the camera; walk with WASD.",
   "/link child and /link adult switch Link's age.")),
  new Page("Empty hands: talk, open, grab",List.of(
   "With nothing held, left click is Zelda's B and right click is Zelda's A.",
   "Right click to talk, open doors and chests, grab, climb and read signs.",
   "Enter and Backspace are A and B whatever you are holding.",
   "Space advances dialogue. Tab opens Zelda's own pause screen (map, quest status).",
   "Left Alt is Z-targeting.")),
  new Page("Zelda items",List.of(
   "Zelda items are in Creative under Tools & Utilities, or search by name.",
   "Holding one puts it in Link's hand; you do not need to have earned it.",
   "Swords: left click slashes, right click raises the shield.",
   "Bow, slingshot, hookshot: hold right click to aim, release to fire where you look.",
   "Bombs, magic and other items: right click to use.",
   "O plays the ocarina; arrow keys are the notes and Space is A.",
   "In Creative, magic and ammunition never run out.")),
  new Page("Hearts, rupees and magic",List.of(
   "Minecraft hearts are your only health. Zelda's hearts stay full.",
   "Every hit Link takes is passed on to your Minecraft hearts.",
   "At zero you die the Minecraft way and wake up at the area's entrance.",
   "The experience number is your rupees; the experience bar is your magic.",
   "Creative mode takes no damage.")),
  new Page("Minecraft and Zelda together",List.of(
   "Minecraft swords, axes, bows and crossbows hurt Zelda enemies.",
   "TNT and other explosions hurt them too, like a bomb.",
   "Fire arrows, Din's Fire and Zelda bomb blasts set off TNT.",
   "Blocks you place are hidden behind Hyrule's walls and ground, as you would expect.",
   "Place blocks, chests, furnaces and redstone anywhere; they are saved with the world.")),
  new Page("Digging into Hyrule",List.of(
   "Hold left click on Hyrule's ground or walls with any Minecraft item in hand.",
   "It cracks and breaks like a block: grass, sand, stone or wood, depending on the surface.",
   "Behind it is dirt for a few blocks, then stone, then bedrock far in.",
   "From inside a hole you can keep mining in any direction.",
   "Water, lava, doors and moving platforms cannot be dug.")),
  new Page("Putting Hyrule back",List.of(
   "Place any block in a spot you dug out, then right click it with a hoe.",
   "The block comes back to you and the spot returns to what it was:",
   "Hyrule's original ground or wall, or the dirt or stone that was behind it.",
   "Any hoe works. A hoe does nothing to blocks placed anywhere else.")),
  new Page("Worlds and saving",List.of(
   "Create New World has a Map button: plain Minecraft, or Ocarina of Time.",
   "An Ocarina of Time world has its own Zelda save and starts Zelda when you open it.",
   "Zelda saves by itself as you play. Reopening continues from where Zelda puts you,",
   "which for adult Link is usually the Temple of Time.",
   "Blocks, inventory and everything you dug are saved with the Minecraft world.")));
}
