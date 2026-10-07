package local.composite.mixin;
import local.composite.NativeAvatar;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(LivingEntity.class)
public class AvatarDimensionsMixin {
 @Inject(method="getDimensions",at=@At("HEAD"),cancellable=true)
 private void composite$dimensions(Pose pose,CallbackInfoReturnable<EntityDimensions> ci){
  var dimensions=NativeAvatar.dimensions((Entity)(Object)this,pose);if(dimensions!=null)ci.setReturnValue(dimensions);
 }
}
