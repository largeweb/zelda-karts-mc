package local.composite.mixin;
import local.composite.Passthrough;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Entity.class)
public class PlayerMoveMixin {
 @Inject(method="move",at=@At("HEAD"),cancellable=true)
 private void composite$move(MoverType type,Vec3 movement,CallbackInfo ci){if((Object)this instanceof LocalPlayer player&&Passthrough.move(player,movement))ci.cancel();}
}
