package local.composite.mixin;
import local.composite.NativeAvatar;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(EntityRenderDispatcher.class)
public class AvatarRenderMixin {
 @Inject(method="shouldRender",at=@At("HEAD"),cancellable=true)
 private void composite$avatar(Entity entity,Frustum frustum,double x,double y,double z,float tick,CallbackInfoReturnable<Boolean> ci){if(NativeAvatar.owns(entity))ci.setReturnValue(false);}
}
