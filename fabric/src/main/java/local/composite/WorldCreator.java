package local.composite;
import java.nio.file.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
/**
 * Creates the Minecraft world that pairs with a native game: an ordinary save whose
 * extra empty dimension holds the blocks placed in that game's world.
 */
public final class WorldCreator {
 private static final int DATA_VERSION=5023;private static final String VERSION="26.3";
 public static void create(Minecraft mc,Screen parent,Games.Game game,String title,String folder,int gameType,boolean commands){
  try{
   var world=mc.gameDirectory.toPath().resolve("saves").resolve(folder);
   if(Files.exists(world))throw new java.io.IOException("A world folder named "+folder+" already exists");
   var pack=mc.gameDirectory.toPath().resolve("config/hyrule/hyrule-dimension");
   if(!Files.isDirectory(pack))throw new java.io.IOException("The Hyrule dimension pack is missing; run the installer");
   Files.createDirectories(world.resolve("datapacks"));
   copy(pack,world.resolve("datapacks/hyrule-dimension"));
   var version=new CompoundTag();version.putByte("Snapshot",(byte)0);version.putString("Series","main");version.putInt("Id",DATA_VERSION);version.putString("Name",VERSION);
   var enabled=new ListTag();enabled.add(StringTag.valueOf("vanilla"));enabled.add(StringTag.valueOf("file/hyrule-dimension"));
   var packs=new CompoundTag();packs.put("Enabled",enabled);packs.put("Disabled",new ListTag());
   var data=new CompoundTag();
   data.putInt("DataVersion",DATA_VERSION);data.put("Version",version);data.putString("LevelName",title);
   data.putInt("GameType",gameType);data.putByte("initialized",(byte)1);data.putByte("allowCommands",(byte)(commands?1:0));
   data.putByte("WasModded",(byte)1);data.putInt("version",19133);data.put("DataPacks",packs);
   var level=new CompoundTag();level.put("Data",data);
   NbtIo.writeCompressed(level,world.resolve("level.dat"));
   var dimensions=new CompoundTag();
   dimensions.put("minecraft:overworld",dimension("minecraft:overworld",noise("minecraft:overworld","minecraft:multi_noise","minecraft:overworld")));
   dimensions.put("minecraft:the_nether",dimension("minecraft:the_nether",noise("minecraft:nether","minecraft:multi_noise","minecraft:nether")));
   dimensions.put("minecraft:the_end",dimension("minecraft:the_end",noise("minecraft:end","minecraft:the_end",null)));
   var settings=new CompoundTag();settings.putLong("seed",new java.util.Random().nextLong());settings.putByte("bonus_chest",(byte)0);settings.putByte("generate_structures",(byte)0);settings.put("dimensions",dimensions);
   var gen=new CompoundTag();gen.putInt("DataVersion",DATA_VERSION);gen.put("data",settings);
   Files.createDirectories(world.resolve("data/minecraft"));
   NbtIo.writeCompressed(gen,world.resolve("data/minecraft/world_gen_settings.dat"));
   Games.mark(world,game);
   mc.createWorldOpenFlows().openWorld(folder,()->mc.gui.setScreen(parent));
  }catch(Exception e){
   System.err.println("Could not create "+game.title()+" world: "+e);
  }
 }
 private static CompoundTag dimension(String type,CompoundTag generator){var d=new CompoundTag();d.putString("type",type);d.put("generator",generator);return d;}
 private static CompoundTag noise(String settings,String biomeType,String preset){
  var biome=new CompoundTag();biome.putString("type",biomeType);if(preset!=null)biome.putString("preset",preset);
  var g=new CompoundTag();g.putString("type","minecraft:noise");g.putString("settings",settings);g.put("biome_source",biome);return g;
 }
 private static void copy(Path from,Path to)throws java.io.IOException{
  try(var files=Files.walk(from)){
   for(var source:(Iterable<Path>)files::iterator){
    var target=to.resolve(from.relativize(source).toString());
    if(Files.isDirectory(source))Files.createDirectories(target);else Files.copy(source,target);
   }
  }
 }
}
