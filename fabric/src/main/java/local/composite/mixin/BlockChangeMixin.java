package local.composite.mixin;
import local.composite.Digging;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Every block change on the server passes through here, whatever caused it. */
@Mixin(ServerLevel.class)
public class BlockChangeMixin {
 @Inject(method="updatePOIOnBlockStateChange",at=@At("HEAD"))
 private void composite$changed(BlockPos pos,BlockState before,BlockState after,CallbackInfo ci){Digging.changed((ServerLevel)(Object)this,pos,before,after);}
}
