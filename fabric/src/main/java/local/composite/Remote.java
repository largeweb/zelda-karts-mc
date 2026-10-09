package local.composite;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
/**
 * Playing on a server. The server holds the blocks, the players and their inventories;
 * each player's own Zelda engine still supplies Hyrule, on their own machine. The
 * server knows nothing of Zelda, so what it must be told goes as ordinary commands
 * (/hyrule ...) that the server's Hyrule plugin answers.
 *
 * Hyrule's areas lie side by side in the server's world, centred 1024 blocks apart, so where
 * the server puts a player says which area their engine should be showing.
 */
public final class Remote {
 /** Where each outdoor area is entered, by Zelda's scene number: the engine starts there when the server moves a player to that area. */
 private static final Map<Integer,Integer> ENTRANCES=Map.ofEntries(
  Map.entry(81,0xCD),Map.entry(82,0xDB),Map.entry(83,0xE4),Map.entry(84,0xEA),Map.entry(85,0xEE),Map.entry(86,0xFC),Map.entry(87,0x102),
  Map.entry(88,0x108),Map.entry(89,0x10E),Map.entry(90,0x117),Map.entry(91,0x11E),Map.entry(92,0x123),Map.entry(93,0x129),Map.entry(94,0x130),
  Map.entry(95,0x138),Map.entry(96,0x13D),Map.entry(97,0x147),Map.entry(98,0x14D),Map.entry(99,0x157));
 public static final int HUB=85; // Kokiri Forest
 /** Where the server last put the player; they are set down there once the engine shows that area. */
 static Vec3 destination;
 private static boolean greeted;
 public static boolean on(){var mc=Minecraft.getInstance();return mc.getSingleplayerServer()==null&&mc.getConnection()!=null&&mc.level!=null;}
 /** On a server and in Hyrule, whether or not the engine is up yet. */
 public static boolean inHyrule(){return on()&&NativeBlocks.inDimension();}
 /** Each area is centred on a multiple of 1024 blocks and reaches about 500 either way. */
 public static int sceneAt(double x){return (int)Math.floor(x/1024+.5);}
 public static Integer entrance(int scene){return ENTRANCES.get(scene);}
 public static void command(String command){var connection=Minecraft.getInstance().getConnection();if(connection!=null)connection.sendCommand(command);}
 public static void say(String text){var mc=Minecraft.getInstance();if(mc.player!=null)mc.player.sendSystemMessage(Component.literal(text));}
 /** A folder of this player's own for the server: their Zelda save and engine logs. */
 static Path home(Minecraft mc){
  var server=mc.getCurrentServer();
  var name=(server==null?"server":server.ip).replaceAll("[^A-Za-z0-9._-]","_");
  return mc.gameDirectory.toPath().resolve("hyrule-servers").resolve(name).toAbsolutePath().normalize();
 }
 /** The settings to start the engine with so it shows the area the player is standing in. */
 static Map<String,String> startFor(Vec3 position){
  var entrance=entrance(sceneAt(position.x));
  destination=entrance==null?null:position;
  return Map.of("COMPOSITE_START",String.format("0x%X",entrance==null?ENTRANCES.get(HUB):entrance));
 }
 /** Once per connection: tell the server's plugin this player has the mod. */
 static void greet(){if(!greeted){greeted=true;command("hyrule hello 1");}}
 static void reset(){greeted=false;destination=null;}
 /** Commands this mod answers itself when on a server. True when the command was one of them. */
 public static boolean ownCommand(String command){
  if(!on())return false;
  var parts=command.trim().split("\\s+");
  switch(parts[0]){
   case "link"->{
    if(parts.length==2&&(parts[1].equals("adult")||parts[1].equals("child"))){ZeldaStatus.requestAge(parts[1].equals("adult")?0:1);say("Link becomes "+parts[1]+".");}
    else say("/link adult or /link child");
   }
   case "spawnkart"->{
    try{if(parts.length>1)Karts.spawn(Integer.parseInt(parts[1]));else Karts.ask();}
    catch(NumberFormatException e){say("/spawnkart or /spawnkart 1-8");}
   }
   case "guide"->{
    int number=1;
    try{if(parts.length>1)number=Math.clamp(Integer.parseInt(parts[1]),1,Guide.PAGES.size());}catch(NumberFormatException e){}
    var page=Guide.PAGES.get(number-1);
    say("— "+page.title()+" ("+number+"/"+Guide.PAGES.size()+") —");
    for(var line:page.lines())say(line);
    say(number<Guide.PAGES.size()?"/guide "+(number+1)+" for "+Guide.PAGES.get(number).title():"/guide 1 to start again");
   }
   default->{return false;}
  }
  return true;
 }
}
