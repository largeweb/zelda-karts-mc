import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import local.composite.Shared;

// Exercise the real Fabric transport class, without Minecraft or account access.
public final class MappingPeer {
 public static void main(String[] args)throws Exception{
  try(var shared=new Shared(Path.of(System.getenv("COMPOSITE_TEST_PATH")))){
   var snapshot=shared.snapshot(Shared.MC,52);
   if(snapshot==null||snapshot.getInt(4)!=42)throw new AssertionError("Native publication missing");
   var value=ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
   value.putInt(4,99);shared.publish(value);
  }
 }
}
