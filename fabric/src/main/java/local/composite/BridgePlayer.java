package local.composite;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

// A genuine Minecraft LocalPlayer, detached from the server entity list. Only its
// vanilla travel/jump methods run; it sends no packets and never edits the save.
public final class BridgePlayer extends LocalPlayer {
    public BridgePlayer(Minecraft mc){
        super(mc,mc.level,mc.player.connection,mc.player.getStats(),mc.player.getRecipeBook(),
              Input.EMPTY,false,mc.player.chatAbilities(),mc.player.itemActivation());
        setSpeed(0.1f);
    }
    @Override public boolean onClimbable(){return false;}
    @Override public void move(MoverType type,Vec3 desired){Bridge.move(this,desired);}
}
