package local.composite;
import java.io.File;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;
/**
 * Runs the native game that belongs to the open world. Opening an Ocarina of Time world
 * starts its engine with that world's own save; leaving the world stops it. A plain
 * Minecraft world runs no engine and plays as normal Minecraft.
 */
public final class Engine {
 private static Process process;private static Path world;
 static{Runtime.getRuntime().addShutdownHook(new Thread(Engine::stop));}
 public static void tick(Minecraft mc){
  var server=mc.getSingleplayerServer();
  Path open=server==null?null:server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
  report(mc);
  if(java.util.Objects.equals(open,world))return;
  stop();
  world=open;
  if(open==null)return;
  var game=Games.of(open);
  if(game==Games.MINECRAFT)return;
  if(!game.available()){tell(mc,game.title()+" is not installed: "+game.missing());return;}
  try{start(game,open);}
  catch(Exception e){System.err.println("Could not start "+game.title()+": "+e);tell(mc,"Could not start "+game.title()+": "+e.getMessage());}
 }
 /**
  * The engine keeps saves and settings in its home directory, so each world gets its own
  * folder inside the world.
  */
 private static void start(Games.Game game,Path world)throws Exception{
  var runtime=game.runtime();
  // Zelda keeps a save per world. The kart engine has nothing to save and only finds
  // its game archive in its home folder, so it runs from its shared folder.
  var home=game==Games.OCARINA?world.resolve(game.family()):runtime;
  Files.createDirectories(home);
  // Only settings are copied in. The engine finds its program and game archives beside
  // its executable; Minecraft refuses to open worlds that contain symbolic links.
  try(var files=Files.list(runtime)){
   for(var source:(Iterable<Path>)files::iterator){
    var name=source.getFileName().toString();
    if(name.endsWith(".json")&&!Files.exists(home.resolve(name)))Files.copy(source,home.resolve(name));
   }
  }
  var shm=System.getProperty("composite.shm");
  var builder=new ProcessBuilder(runtime.resolve(game.engine()).toString()).directory(runtime.toFile());
  var env=builder.environment();
  env.put("SHIP_HOME",home.toString());env.put("SDL_VIDEODRIVER","x11");
  env.put("COMPOSITE_SHM",shm);env.put("COMPOSITE_FRAME",shm+".rgba");
  if(game.map()!=null)env.put("COMPOSITE_TRACK",game.map());
  var arms=Games.config().get("arms");if(arms!=null)env.put("COMPOSITE_ARMS",arms.getAsString());
  // For testing: begin at a chosen entrance instead of where the save resumes.
  var entrance=Games.config().get("start");if(entrance!=null)env.put("COMPOSITE_START",entrance.getAsString());
  var log=new File(world.toFile(),"engine.log");
  builder.redirectErrorStream(true).redirectOutput(log);
  process=builder.start();
  // Optional helper that parks the engine's window out of the way (desktop specific).
  var arrange=Games.config().get("arrange");
  if(arrange!=null){
   var command=new java.util.ArrayList<String>();for(var part:arrange.getAsJsonArray())command.add(part.getAsString());
   new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
  }
 }
 private static String pending;
 private static void tell(Minecraft mc,String text){pending=text;}
 /** Messages wait until the player is in the world to read them. */
 public static void report(Minecraft mc){if(pending!=null&&mc.player!=null){mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(pending));pending=null;}}
 public static synchronized void stop(){
  var running=process;process=null;
  if(running==null||!running.isAlive())return;
  running.destroy();
  try{
   // The engine saves as it goes; it can hang in teardown after releasing everything.
   if(!running.waitFor(4,TimeUnit.SECONDS))running.destroyForcibly();
  }catch(InterruptedException e){running.destroyForcibly();}
 }
}
