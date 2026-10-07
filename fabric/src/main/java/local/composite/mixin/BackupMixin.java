package local.composite.mixin;
import local.composite.Games;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/**
 * Worlds made from a native game use a custom dimension, which Minecraft treats as
 * experimental and asks about on every load. That is how these worlds are meant to be.
 */
@Mixin(WorldOpenFlows.class)
public class BackupMixin {
 @Inject(method="askForBackup",at=@At("HEAD"),cancellable=true)
 private void composite$backup(LevelStorageSource.LevelStorageAccess world,boolean customized,Runnable proceed,Runnable cancel,CallbackInfo ci){
  var folder=Minecraft.getInstance().gameDirectory.toPath().resolve("saves").resolve(world.getLevelId());
  if(Games.of(folder)==Games.MINECRAFT)return;
  proceed.run();ci.cancel();
 }
}
