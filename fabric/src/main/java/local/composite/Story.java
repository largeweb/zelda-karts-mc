package local.composite;

import java.nio.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;

/** Input/inventory passthrough. Zelda is authoritative for physics and earned items. */
public final class Story {
    static final int ATTACK=254, DIRT=253, EMPTY=255;
    static final int RIGHT=128, ENTER=1<<19, ESCAPE=1<<20, F5=1<<21;
    private static int epoch,tick,lastButtons,lastX,lastY,lastWheel,lastCommand,scene=-1;
    private static float yaw,pitch;
    private static boolean open;
    private static int view; // 0 first person, 1 rear third person, 2 front third person
    private static int selected;
    private static final int[] hotbar={DIRT,ATTACK,EMPTY,EMPTY,EMPTY,EMPTY,EMPTY,EMPTY,EMPTY};
    private static final int[] owned=new int[48];
    private static SimpleContainer inventory;
    private static final int CAPACITY=16;
    private static final List<AABB> blocks=new ArrayList<>();
    private static int dirtCount=CAPACITY;
    private static boolean contains(int[] values,int value){for(int v:values)if(v==value)return true;return false;}
    public static void suspend(){open=false;epoch=0;}
    public static void tick(Minecraft client,ByteBuffer p,Shared shm){
        ByteBuffer s=shm.snapshot(512,100);if(s==null)return;
        if(inventory==null)inventory=new SimpleContainer(54);
        int flags=s.getInt(0),buttons=p.getInt(8),currentScene=p.getInt(48);
        boolean dialogue=(flags&2)!=0,locked=(flags&8)!=0,paused=(flags&4)!=0;
        if(epoch!=p.getInt(0)){
            epoch=p.getInt(0);yaw=s.getFloat(4);pitch=15;blocks.clear();dirtCount=CAPACITY;
            lastX=p.getInt(12);lastY=p.getInt(16);lastButtons=buttons;lastWheel=s.getInt(96);open=false;
        }
        if(scene!=currentScene){scene=currentScene;blocks.clear();dirtCount=CAPACITY;}
        for(int i=0;i<48;i++){
            int id=s.get(8+i)&255;
            if(owned[i]!=id || inventory.getItem(i).isEmpty()!=(id==255)){
                owned[i]=id;
                // UI retains Zelda icons; the Minecraft container mirrors possession.
                inventory.setItem(i,id==255?ItemStack.EMPTY:new ItemStack(i>=24?Items.LEATHER_HELMET:id==1?Items.BOW:id==6?Items.TNT:id>=18&&id<=39?Items.GLASS_BOTTLE:Items.PAPER));
            }
        }
        for(int i=0;i<48;i++)if(owned[i]!=255 && !contains(hotbar,i)){
            for(int h=2;h<9;h++)if(hotbar[h]==EMPTY){hotbar[h]=i;break;}
        }
        int press=buttons&~lastButtons;lastButtons=buttons;
        if((press&64)!=0&&!dialogue&&!locked&&!paused)open=!open;
        if((press&ESCAPE)!=0&&open)open=false;
        for(int h=0;h<9;h++)if((press&(1<<(8+h)))!=0)selected=h;
        int wheel=s.getInt(96);selected=Math.floorMod(selected-(wheel-lastWheel),9);lastWheel=wheel;
        int command=s.getInt(84);
        if(command!=lastCommand){
            int kind=s.getInt(88),value=s.getInt(92);
            if(kind==1&&((value>=0&&value<48&&owned[value]!=255)||value==DIRT||value==ATTACK))hotbar[selected]=value;
            if(kind==2&&value>=0&&value<9)selected=value;
            lastCommand=command;
        }
        int mx=p.getInt(12),my=p.getInt(16);
        if(!open&&!locked&&!dialogue&&!paused){
            yaw+=(mx-lastX)*.15f;pitch=Math.clamp(pitch+(my-lastY)*.15f,-65,70);
            if((press&F5)!=0)view=(view+1)%3;
        }
        lastX=mx;lastY=my;yaw=((yaw+180)%360+360)%360-180;
        int event=0,controls=open?0:buttons;
        Vec3 feet=new Vec3(p.getFloat(20),p.getFloat(24),p.getFloat(28));
        double y=Math.toRadians(yaw),a=Math.toRadians(pitch);
        Vec3 eye=feet.add(0,50,0),dir=new Vec3(-Math.sin(y)*Math.cos(a),-Math.sin(a),Math.cos(y)*Math.cos(a));
        int aimed=-1;double nearest=201;
        for(int i=0;i<blocks.size();i++){
            var hit=blocks.get(i).clip(eye,eye.add(dir.scale(200)));
            if(hit.isPresent()&&eye.distanceTo(hit.get())<nearest){nearest=eye.distanceTo(hit.get());aimed=i;}
        }
        if(!open&&!locked&&!dialogue&&!paused&&hotbar[selected]==DIRT){
            controls&=~(32|RIGHT);
            if((press&32)!=0&&aimed>=0){blocks.remove(aimed);dirtCount++;event=2;aimed=-1;}
            if((press&RIGHT)!=0&&dirtCount>0&&p.getInt(32)!=0){
                double x=p.getFloat(36),by=p.getFloat(40),z=p.getFloat(44);
                AABB next=new AABB(x,by,z,x+40,by+40,z+40);
                boolean occupied=next.intersects(new AABB(feet.x-12,feet.y,feet.z-12,feet.x+12,feet.y+60,feet.z+12));
                for(AABB placed:blocks)if(next.intersects(placed))occupied=true;
                if(!occupied){blocks.add(next);dirtCount--;event=1;}
            }
        }
        inventory.setItem(48,dirtCount>0?new ItemStack(Items.DIRT,dirtCount):ItemStack.EMPTY);
        AABB block=blocks.isEmpty()?null:blocks.getFirst();
        ByteBuffer layer=ByteBuffer.allocate(208).order(ByteOrder.LITTLE_ENDIAN);
        layer.putInt(epoch).putInt(blocks.size()).putInt(dirtCount).putInt(aimed);
        for(AABB placed:blocks)layer.putFloat((float)placed.minX).putFloat((float)placed.minY).putFloat((float)placed.minZ);
        shm.publish(1024,layer);
        ByteBuffer out=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(epoch).putInt(++tick).putFloat((float)feet.x).putFloat((float)feet.y).putFloat((float)feet.z);
        out.putFloat(yaw).putFloat(pitch).putInt(1).putInt(block==null?0:1).putInt(event);
        out.putFloat(block==null?0:(float)block.minX).putFloat(block==null?0:(float)block.minY).putFloat(block==null?0:(float)block.minZ);shm.publish(out);
        ByteBuffer response=ByteBuffer.allocate(60).order(ByteOrder.LITTLE_ENDIAN);
        response.putInt(epoch).putInt(controls).putInt(selected).putInt(open?1:0).putInt(view);
        for(int binding:hotbar)response.putInt(binding);
        response.putInt(lastCommand);shm.publish(768,response);
    }
}
