package local.composite;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Bridge {
    private static final Logger LOG=LoggerFactory.getLogger("ClockTownComposite");
    private static Shared shm;
    private static BridgePlayer player;
    private static int epoch,ticks,lastButtons,lastMouseX,lastMouseY,event,lastFrame;
    private static double baseX,baseZ;
    private static long lastFrameTime,retryAt;
    private static float yaw,pitch;
    private static AABB block;
    private static BlockState blockState;
    private static boolean hidden, timedOut;
    private static final double Y=1024.0, SCALE=40.0;
    private static final Path PATH=Path.of(System.getProperty("composite.shm","/dev/shm/clocktown-composite-"+System.getProperty("user.name")));
    public static void tick(Minecraft mc){
        try {
            if(shm==null){
                if(System.nanoTime()<retryAt)return;
                retryAt=System.nanoTime()+1_000_000_000L;
                if(!Files.exists(PATH))return;
                shm=new Shared(PATH);LOG.info("Connected to {}",PATH);
            }
            ByteBuffer p=shm.snapshot(Shared.PORT,56);if(p==null)return;
            int frame=p.getInt(52);
            if(frame!=lastFrame){lastFrame=frame;lastFrameTime=System.nanoTime();}
            if(System.nanoTime()-lastFrameTime>1_000_000_000L){deactivate(mc);return;}
            if(mc.level==null||mc.player==null||p.getInt(4)==0){deactivate(mc);return;}
            if(p.getInt(4)==2){
                if(Passthrough.ENABLED){Passthrough.tick(mc,p,shm);return;}
                mc.options.pauseOnLostFocus=false;
                if(!hidden){mc.setScreenAndShow(null);SDLVideo.SDL_HideWindow(mc.getWindow().handle());hidden=true;}
                Story.tick(mc,p,shm);return;
            }
            if(player==null||epoch!=p.getInt(0)){
                epoch=p.getInt(0);player=new BridgePlayer(mc);block=null;event=0;
                baseX=mc.player.getX();baseZ=mc.player.getZ();
                player.setPos(baseX+p.getFloat(20)/SCALE,Y+p.getFloat(24)/SCALE,baseZ+p.getFloat(28)/SCALE);
                player.setOnGround(true);yaw=0;pitch=15;
                lastMouseX=p.getInt(12);lastMouseY=p.getInt(16);lastButtons=0;
                LOG.info("Minecraft LocalPlayer attached to Clock Town, epoch {}",epoch);
            }
            mc.options.pauseOnLostFocus=false;
            // Minecraft's title/world selection remains available until F8 in 2ship.
            if(!hidden){mc.setScreenAndShow(null);SDLVideo.SDL_HideWindow(mc.getWindow().handle());hidden=true;}
            int buttons=p.getInt(8),mx=p.getInt(12),my=p.getInt(16);
            yaw+=(mx-lastMouseX)*0.15f;pitch=Math.clamp(pitch+(my-lastMouseY)*0.15f,-85,85);
            yaw=((yaw+180)%360+360)%360-180;
            lastMouseX=mx;lastMouseY=my;player.setYRot(yaw);player.setXRot(pitch);
            float forward=((buttons&1)!=0?1:0)-((buttons&2)!=0?1:0);
            float side=((buttons&4)!=0?1:0)-((buttons&8)!=0?1:0);
            Vec3 movement=new Vec3(side,0,forward);if(movement.lengthSqr()>1)movement=movement.normalize();
            if((buttons&16)!=0 && player.onGround())player.jumpFromGround();
            timedOut=false;
            player.travel(movement.scale(0.98)); // Vanilla friction, acceleration, gravity and jump.
            if(timedOut){player.setDeltaMovement(Vec3.ZERO);}
            int pressed=buttons&~lastButtons;lastButtons=buttons;
            if((pressed&32)!=0 && block!=null){
                Vec3 eye=player.position().add(0,1.62,0);
                if(block.clip(eye,eye.add(player.getLookAngle().scale(5))).isPresent()){
                    block=null;event=2;LOG.info("Broke dirt block");
                }
            }
            if((pressed&64)!=0 && block==null && p.getInt(32)!=0){
                // Port raycast supplies a supported floor hit; Minecraft owns the block.
                double x=baseX+p.getFloat(36)/SCALE,y=Y+p.getFloat(40)/SCALE,z=baseZ+p.getFloat(44)/SCALE;
                AABB candidate=new AABB(x,y,z,x+1,y+1,z+1);
                if(!candidate.intersects(player.getBoundingBox())){
                    block=candidate;blockState=Blocks.DIRT.defaultBlockState();event=1;LOG.info("Placed {} at {},{},{}",Blocks.DIRT,x-baseX,y-Y,z-baseZ);
                }
            }
            ByteBuffer out=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
            out.putInt(epoch).putInt(++ticks);
            out.putFloat((float)((player.getX()-baseX)*SCALE)).putFloat((float)((player.getY()-Y)*SCALE)).putFloat((float)((player.getZ()-baseZ)*SCALE));
            out.putFloat(yaw).putFloat(pitch).putInt(player.onGround()?1:0).putInt(block==null?0:1).putInt(event);
            out.putFloat(block==null?0:(float)((block.minX-baseX)*SCALE));
            out.putFloat(block==null?0:(float)((block.minY-Y)*SCALE));
            out.putFloat(block==null?0:(float)((block.minZ-baseZ)*SCALE));
            shm.publish(out);event=0;
        }catch(Exception e){
            LOG.error("Bridge stopped safely",e);deactivate(mc);
            try{if(shm!=null)shm.close();}catch(Exception ignored){}shm=null;
            retryAt=System.nanoTime()+2_000_000_000L;
        }
    }
    private static void deactivate(Minecraft mc){
        player=null;block=null;Story.suspend();
        if(hidden){SDLVideo.SDL_ShowWindow(mc.getWindow().handle());hidden=false;}
    }
    public static void move(BridgePlayer entity,Vec3 desired){
        AABB box=entity.getBoundingBox();
        // Minecraft VoxelShape collision gives the dirt block a full 1m cube.
        Vec3 clipped=clip(box,desired);
        float[] result=shm.collide(epoch,(float)((entity.getX()-baseX)*SCALE),(float)((entity.getY()-Y)*SCALE),(float)((entity.getZ()-baseZ)*SCALE),
            (float)(clipped.x*SCALE),(float)(clipped.y*SCALE),(float)(clipped.z*SCALE));
        if(result==null){timedOut=true;return;}
        Vec3 actual=new Vec3(result[0]/SCALE,result[1]/SCALE,result[2]/SCALE);
        // Wall sliding from the world must not push the player into the cube.
        actual=clip(box,actual);
        entity.setPos(entity.position().add(actual));
        boolean vertical=Math.abs(actual.y-desired.y)>1e-5;
        entity.horizontalCollision=Math.abs(actual.x-desired.x)>1e-5||Math.abs(actual.z-desired.z)>1e-5;
        entity.verticalCollision=vertical;
        entity.setOnGround(vertical&&desired.y<0);
        Vec3 velocity=entity.getDeltaMovement();
        entity.setDeltaMovement(Math.abs(actual.x-desired.x)>1e-5?0:velocity.x,vertical?0:velocity.y,Math.abs(actual.z-desired.z)>1e-5?0:velocity.z);
    }
    static Vec3 clip(AABB box,Vec3 delta){
        if(block==null)return delta;
        VoxelShape shape=player==null ? Shapes.create(block) : blockState.getCollisionShape(player.level(),BlockPos.containing(block.minX,block.minY,block.minZ)).move(block.minX,block.minY,block.minZ);
        List<VoxelShape> shapes=List.of(shape);
        double y=Shapes.collide(Axis.Y,box,shapes,delta.y);box=box.move(0,y,0);
        double x=Shapes.collide(Axis.X,box,shapes,delta.x);box=box.move(x,0,0);
        double z=Shapes.collide(Axis.Z,box,shapes,delta.z);
        return new Vec3(x,y,z);
    }
}
