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
 public record Game(String id,String title,String engine,String assets,String missing){
  /** Folder holding this game's engine and extracted assets, or null if not configured. */
  public Path runtime(){var element=config().get(id+"_runtime");return element==null?null:Path.of(element.getAsString());}
  public boolean available(){
   if(id.equals("minecraft"))return true;
   var runtime=runtime();
   return engine!=null&&runtime!=null&&Files.isExecutable(runtime.resolve(engine))&&Files.isRegularFile(runtime.resolve(assets));
  }
 }
 public static final Game MINECRAFT=new Game("minecraft","Minecraft",null,null,null);
 public static final Game OCARINA=new Game("oot","Ocarina of Time","soh-composite.elf","oot.o2r","Needs your Ocarina of Time ROM: run ./hyrule extract");
 public static final List<Game> ALL=List.of(MINECRAFT,OCARINA,
  new Game("goldeneye","GoldenEye 007",null,null,"GoldenEye maps are not supported yet"),
  new Game("mk64","Mario Kart 64",null,null,"Mario Kart tracks are not supported yet"));
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
