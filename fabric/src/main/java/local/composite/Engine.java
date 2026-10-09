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
 private static Process process,guest,kart;private static Path world;
 /** Whether the Mario Kart engine is running as a guest, for karts in a Zelda world. */
 public static boolean hasKart(){return kart!=null&&kart.isAlive();}
 /** The Mario Kart engine, if it is installed: any of its tracks will do to find it. */
 public static Games.Game kartGame(){
  for(var candidate:Games.ALL)if("mk64".equals(candidate.family())&&candidate.available())return candidate;
  return null;
 }
 /**
  * Start (or restart) the Mario Kart engine as a guest of the open Zelda world. It
  * draws only a kart and drives it over Zelda's ground. Started on demand, so worlds
  * where no kart is ever asked for run two programs, not three.
  */
 public static void startKart(java.util.Map<String,String> settings)throws Exception{
  var karts=kartGame();if(karts==null||world==null)return;
  stop(kart);kart=null;
  var runtime=karts.runtime();var shm=System.getProperty("composite.shm");
  var builder=new ProcessBuilder(runtime.resolve(karts.engine()).toString()).directory(runtime.toFile());
  var env=builder.environment();
  env.put("SHIP_HOME",runtime.toString());env.put("SDL_VIDEODRIVER","x11");
  env.put("COMPOSITE_SHM",shm+Guest.KART.suffix);env.put("COMPOSITE_FRAME",shm+Guest.KART.suffix+".rgba");
  env.put("COMPOSITE_GUEST","1");env.put("COMPOSITE_COLLISION",shm+".collision");
  env.put("COMPOSITE_TRACK",karts.map()); // any track: only its kart is used
  env.putAll(settings);
  if(Games.config().has("debug"))env.put("COMPOSITE_DEBUG","1");
  builder.redirectErrorStream(true).redirectOutput(new File(world.toFile(),"kart-engine.log"));
  Guest.KART.reset();
  kart=builder.start();
  arrange();
 }
 /** Whether a second Zelda engine is drawing Link over the host game. */
 public static boolean hasGuest(){return guest!=null&&guest.isAlive();}
 private static Games.Game game;private static java.util.Map<String,String> extra=java.util.Map.of();
 /** The game the open world runs on, or null. */
 public static Games.Game game(){return process!=null&&process.isAlive()?game:null;}
 /** Start the open world's engine again with extra settings (the kart engine loads a character with the track). */
 public static void restart(java.util.Map<String,String> settings){
  if(game==null||world==null)return;
  stop();extra=settings;
  try{start(game,world);}catch(Exception e){System.err.println("Could not restart "+game.title()+": "+e);}
 }
 static{Runtime.getRuntime().addShutdownHook(new Thread(Engine::stop));}
 public static void tick(Minecraft mc){
  var server=mc.getSingleplayerServer();
  Path open=server==null?null:server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
  // On a server, Hyrule is the server's Zelda dimension; the engine runs from a folder of the player's own.
  boolean remote=server==null&&Remote.inHyrule()&&mc.player!=null;
  if(remote)open=Remote.home(mc);
  else if(server==null)Remote.reset();
  report(mc);
  if(java.util.Objects.equals(open,world))return;
  stop();
  world=open;extra=java.util.Map.of();game=null;
  if(open==null)return;
  if(remote){
   try{Files.createDirectories(open);}catch(java.io.IOException e){System.err.println("Could not create "+open+": "+e);return;}
   extra=Remote.startFor(mc.player.position());
  }
  var game=remote?Games.OCARINA:Games.of(open);
  if(game==Games.MINECRAFT)return;
  if(!game.available()){tell(mc,game.title()+" is not installed: "+game.missing());return;}
  Engine.game=game;
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
  env.putAll(extra);
  var arms=Games.config().get("arms");if(arms!=null)env.put("COMPOSITE_ARMS",arms.getAsString());
  // For testing: begin at a chosen entrance instead of where the save resumes.
  var entrance=Games.config().get("start");if(entrance!=null)env.put("COMPOSITE_START",entrance.getAsString());
  var log=new File(world.toFile(),"engine.log");
  builder.redirectErrorStream(true).redirectOutput(log);
  process=builder.start();
  // On a kart track Link comes along: a second engine that draws only him, if Zelda is installed.
  if(game.family().equals("mk64")&&Games.OCARINA.available()&&!Games.config().has("no_guest")){
   var zelda=Games.OCARINA.runtime();var guestHome=world.resolve("oot-guest");
   Files.createDirectories(guestHome);
   try(var files=Files.list(zelda)){
    for(var source:(Iterable<Path>)files::iterator){
     var name=source.getFileName().toString();
     if(name.endsWith(".json")&&!Files.exists(guestHome.resolve(name)))Files.copy(source,guestHome.resolve(name));
    }
   }
   var second=new ProcessBuilder(zelda.resolve(Games.OCARINA.engine()).toString()).directory(zelda.toFile());
   var guestEnv=second.environment();
   guestEnv.put("SHIP_HOME",guestHome.toString());guestEnv.put("SDL_VIDEODRIVER","x11");
   guestEnv.put("COMPOSITE_SHM",shm+Guest.LINK.suffix);guestEnv.put("COMPOSITE_FRAME",shm+Guest.LINK.suffix+".rgba");
   guestEnv.put("COMPOSITE_GUEST","1");guestEnv.put("COMPOSITE_START","0xEE"); // Kokiri Forest for its even daylight, cleared of everything but Link
   second.redirectErrorStream(true).redirectOutput(new File(world.toFile(),"guest-engine.log"));
   Guest.LINK.reset();
   guest=second.start();
  }
  arrange();
 }
 /** Optional helper that parks the engines' windows out of the way (desktop specific). */
 private static void arrange()throws java.io.IOException{
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
  for(var running:new Process[]{process,guest,kart})stop(running);
  process=null;guest=null;kart=null;Karts.reset();
 }
 private static void stop(Process running){
  if(running==null||!running.isAlive())return;
  running.destroy();
  try{
   // The engine saves as it goes; it can hang in teardown after releasing everything.
   if(!running.waitFor(4,TimeUnit.SECONDS))running.destroyForcibly();
  }catch(InterruptedException e){running.destroyForcibly();}
 }
}
