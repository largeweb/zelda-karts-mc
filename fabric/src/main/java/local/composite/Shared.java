package local.composite;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.concurrent.locks.LockSupport;

public final class Shared implements AutoCloseable {
    public static final int MAGIC=0x434D4D31, SIZE=2048, PORT=64, MC=192, REQUEST=320, REPLY=384;
    private static final VarHandle I=MethodHandles.byteBufferViewVarHandle(int[].class,ByteOrder.LITTLE_ENDIAN);
    private final FileChannel file;
    public final MappedByteBuffer mem;
    private int serial;
    public Shared(Path path) throws IOException {
        file=FileChannel.open(path,StandardOpenOption.READ,StandardOpenOption.WRITE);
        if(file.size()!=SIZE)throw new IOException("Wrong shared memory size");
        mem=file.map(FileChannel.MapMode.READ_WRITE,0,SIZE); mem.order(ByteOrder.LITTLE_ENDIAN);
        if(get(0)!=MAGIC || get(4)!=3)throw new IOException("Wrong bridge protocol");
        serial=get(REQUEST);
    }
    public int get(int o){return (int)I.getAcquire(mem,o);}
    public void set(int o,int v){I.setRelease(mem,o,v);}
    public float f(int o){return mem.getFloat(o);}
    public void f(int o,float v){mem.putFloat(o,v);}
    public ByteBuffer snapshot(int offset,int bytes){
        for(int tries=0;tries<4;tries++){
            int a=get(offset);if((a&1)!=0)continue;
            byte[] data=new byte[bytes];mem.get(offset+4,data);VarHandle.acquireFence();
            if(a==get(offset))return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        }return null;
    }
    public void publish(ByteBuffer data){publish(192,data);}
    public void publish(int offset,ByteBuffer data){
        int a=get(offset);set(offset,a+1);VarHandle.fullFence();
        mem.put(offset+4,data.array());set(offset,a+2);
    }
    public float[] collide(int epoch,float x,float y,float z,float dx,float dy,float dz){
        int q=serial+1; if(q==0)q=1; serial=q;
        mem.putInt(REQUEST+4,epoch);
        float[] v={x,y,z,dx,dy,dz};for(int j=0;j<6;j++)f(REQUEST+8+4*j,v[j]);
        set(REQUEST,q);
        long started=System.nanoTime(),deadline=started+200_000_000L;
        while(get(REPLY)!=q){if(System.nanoTime()>deadline)return null;LockSupport.parkNanos(250_000);}
        if(Boolean.getBoolean("composite.passthrough"))set(52,(int)((System.nanoTime()-started)/1000));
        return new float[]{f(REPLY+4),f(REPLY+8),f(REPLY+12)};
    }
    public float[] camera(int epoch,float x,float y,float z,float ex,float ey,float ez){
        int q=get(1344)+1;if(q==0)q=1;
        mem.putInt(1348,epoch);float[] v={x,y,z,ex,ey,ez};for(int j=0;j<6;j++)f(1352+j*4,v[j]);set(1344,q);
        long deadline=System.nanoTime()+50_000_000L;
        while(get(1376)!=q){if(System.nanoTime()>deadline)return null;LockSupport.parkNanos(100_000);}
        return new float[]{f(1380),f(1384),f(1388)};
    }
    public void close() throws IOException{file.close();}
}
