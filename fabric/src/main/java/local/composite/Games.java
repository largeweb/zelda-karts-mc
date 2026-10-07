package local.composite;
import java.nio.file.*;
import java.util.*;
import com.google.gson.*;
import net.minecraft.client.Minecraft;
/**
 * The games a new world can be made from. Each needs its own engine and the assets
 * extracted from the player's own ROM; one that is not installed is listed but disabled.
 * Paths come from config/hyrule.json in the game directory, written by the installer.
 */
public final class Games {
 /**
  * @param family which engine runs it; its folder is the "<family>_runtime" setting
  * @param map    for engines with several maps, the one this world is on
  */
 public record Game(String id,String title,String family,String engine,String assets,String map,String missing){
  /** Folder holding this game's engine and extracted assets, or null if not configured. */
  public Path runtime(){var element=config().get(family+"_runtime");return element==null?null:Path.of(element.getAsString());}
  public boolean available(){
   if(this==MINECRAFT)return true;
   var runtime=runtime();
   return engine!=null&&runtime!=null&&Files.isExecutable(runtime.resolve(engine))&&Files.isRegularFile(runtime.resolve(assets));
  }
 }
 public static final Game MINECRAFT=new Game("minecraft","Minecraft",null,null,null,null,null);
 public static final Game OCARINA=new Game("oot","Ocarina of Time","oot","soh-composite.elf","oot.o2r",null,"Needs your Ocarina of Time ROM: run ./hyrule extract");
 public static final List<Game> ALL=all();
 private static List<Game> all(){
  var games=new ArrayList<Game>(List.of(MINECRAFT,OCARINA));
  String[][] tracks={{"luigi_raceway","Luigi Raceway"},{"mario_raceway","Mario Raceway"},{"moo_moo_farm","Moo Moo Farm"},{"koopa_troopa_beach","Koopa Troopa Beach"},
   {"kalimari_desert","Kalimari Desert"},{"choco_mountain","Choco Mountain"},{"royal_raceway","Royal Raceway"},{"bowsers_castle","Bowser's Castle"},
   {"rainbow_road","Rainbow Road"},{"block_fort","Block Fort"}};
  for(var track:tracks)games.add(new Game("mk64/"+track[0],"Mario Kart 64 — "+track[1],"mk64","mk64-composite.elf","mk64.o2r","mk:"+track[0],"Needs your Mario Kart 64 ROM: run ./hyrule mk64 ROM"));
  games.add(new Game("goldeneye","GoldenEye 007","goldeneye",null,null,null,"GoldenEye maps are not supported yet"));
  return List.copyOf(games);
 }
 private static final String MARKER="hyrule-world.json";
 private static JsonObject config;
 public static JsonObject config(){
  if(config==null){
   config=new JsonObject();
   var path=Minecraft.getInstance().gameDirectory.toPath().resolve("config/hyrule.json");
   try{if(Files.isRegularFile(path))config=JsonParser.parseString(Files.readString(path)).getAsJsonObject();}
   catch(Exception e){System.err.println("Could not read "+path+": "+e);}
  }
  return config;
 }
 /** The game a saved world was made from; plain Minecraft when it carries no marker. */
 public static Game of(Path world){
  try{
   var marker=world.resolve(MARKER);
   if(Files.isRegularFile(marker)){
    var id=JsonParser.parseString(Files.readString(marker)).getAsJsonObject().get("game").getAsString();
    for(var game:ALL)if(game.id().equals(id))return game;
   }
  }catch(Exception e){System.err.println("Could not read world type in "+world+": "+e);}
  return MINECRAFT;
 }
 public static void mark(Path world,Game game)throws java.io.IOException{
  var o=new JsonObject();o.addProperty("game",game.id());Files.writeString(world.resolve(MARKER),o.toString());
 }
}
