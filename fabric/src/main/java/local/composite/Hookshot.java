package local.composite;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
/**
 * The hookshot and Minecraft blocks. Zelda only knows about blocks close to the player,
 * so while a hookshot is held the block it points at is found here and handed to Zelda
 * as something solid, and as something to grapple to when it is the kind of thing a
 * hook bites into.
 */
public final class Hookshot {
 private static final int HOOKSHOT=10,LONGSHOT=11;
 /** How far the chain goes, in blocks: 13 or 26 steps of half a block, and a little for the arm. */
 private static double reach(int item){return item==LONGSHOT?13.5:7;}
 /** The block a held hookshot points at within its reach, or null. */
 public static BlockPos target(Minecraft mc){
  int item=ZeldaItems.held(mc);
  if(item!=HOOKSHOT&&item!=LONGSHOT)return null;
  var eye=mc.player.getEyePosition();
  var end=eye.add(mc.player.getLookAngle().scale(reach(item)));
  var hit=mc.level.clip(new ClipContext(eye,end,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,mc.player));
  if(hit.getType()!=HitResult.Type.BLOCK)return null;
  var pos=hit.getBlockPos();
  // Barriers are the invisible support under Zelda's floors, not something in the way.
  return mc.level.getBlockState(pos).is(Blocks.BARRIER)?null:pos.immutable();
 }
 /** Wood, and the few other things a hook would stick in. Everything else it bounces off. */
 public static boolean grips(BlockState state){
  return state.is(BlockTags.LOGS)||state.is(BlockTags.PLANKS)||state.is(BlockTags.WOODEN_SLABS)||state.is(BlockTags.WOODEN_STAIRS)
   ||state.is(BlockTags.WOODEN_FENCES)||state.is(BlockTags.FENCE_GATES)||state.is(BlockTags.WOODEN_DOORS)||state.is(BlockTags.WOODEN_TRAPDOORS)
   ||state.is(Blocks.TARGET)||state.is(Blocks.HAY_BLOCK)||state.is(Blocks.CHEST)||state.is(Blocks.TRAPPED_CHEST)||state.is(Blocks.BARREL)
   ||state.is(Blocks.BOOKSHELF)||state.is(Blocks.CHISELED_BOOKSHELF)||state.is(Blocks.CRAFTING_TABLE)||state.is(Blocks.NOTE_BLOCK)
   ||state.is(Blocks.JUKEBOX)||state.is(Blocks.LADDER)||state.is(Blocks.SCAFFOLDING)||state.is(Blocks.COMPOSTER)||state.is(Blocks.BEEHIVE)||state.is(Blocks.BEE_NEST);
 }
}
