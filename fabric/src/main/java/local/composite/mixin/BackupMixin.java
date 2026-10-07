package local.composite.mixin;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(WorldOpenFlows.class) public class BackupMixin {
 @Inject(method="askForBackup",at=@At("HEAD"),cancellable=true) private void backup(LevelStorageSource.LevelStorageAccess world,boolean customized,Runnable proceed,Runnable cancel,CallbackInfo ci){
  if(!world.getLevelId().equals("Hyrule_World"))return;
  try{world.makeWorldBackup();proceed.run();ci.cancel();}catch(java.io.IOException e){System.err.println("Automatic backup failed; leaving the normal prompt: "+e);}
 }
}
