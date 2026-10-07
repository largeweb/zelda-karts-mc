package local.composite.mixin;
import local.composite.Passthrough;
import local.composite.NativeAvatar;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(BlockBehaviour.BlockStateBase.class)
public class FloorCollisionMixin {
 @Inject(method="getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",at=@At("HEAD"),cancellable=true)
 private void composite$floor(BlockGetter world,BlockPos pos,CollisionContext context,CallbackInfoReturnable<VoxelShape> ci){
  if(Passthrough.active()&&((BlockState)(Object)this).is(Blocks.BARRIER)&&context instanceof EntityCollisionContext e&&e.getEntity()!=null&&NativeAvatar.owns(e.getEntity()))ci.setReturnValue(Shapes.empty());
 }
}
