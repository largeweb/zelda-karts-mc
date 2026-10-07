#pragma once
#include <cstdint>
#include <cstring>
#include <cmath>
// Little-endian, x86-64, 2048 bytes. Offsets mirrored by Shared.java.
namespace composite {
constexpr uint32_t MAGIC=0x434D4D31, VERSION=3;
constexpr int SIZE=2048, PORT=64, MC=192, REQUEST=320, REPLY=384;
constexpr float SCALE=40.0f;
struct Port { uint32_t epoch, active, buttons; int32_t mouseX, mouseY; float x,y,z; uint32_t target; float tx,ty,tz; uint32_t scene, frame; };
struct Minecraft { uint32_t epoch, tick; float x,y,z,yaw,pitch; uint32_t grounded, block, event; float bx,by,bz; };
constexpr int STORY_PORT=512, STORY_MC=768, BLOCKS=1024;
constexpr int MAX_BLOCKS=16;
struct BlockPos { float x,y,z; };
struct BlockLayer { uint32_t epoch,count,remaining,aimed; BlockPos positions[MAX_BLOCKS]; };
static_assert(sizeof(BlockLayer)==208);
struct StoryPort { uint32_t flags; float yaw; uint8_t items[48]; int8_t ammo[24]; uint32_t sword, commandSeq, commandKind, commandValue; int32_t wheel; };
struct StoryMinecraft { uint32_t epoch, controls, selected, inventoryOpen, thirdPerson; uint32_t hotbar[9]; uint32_t commandAck; };
static_assert(sizeof(StoryPort)==100 && sizeof(StoryMinecraft)==60);
struct Request { uint32_t epoch; float x,y,z,dx,dy,dz; };
struct Reply { float dx,dy,dz; };
static_assert(sizeof(Port)==56 && sizeof(Minecraft)==52 && sizeof(Request)==28);
inline uint32_t acquire(const void* p) { return __atomic_load_n((const uint32_t*)p,__ATOMIC_ACQUIRE); }
inline void release(void* p,uint32_t v) { __atomic_store_n((uint32_t*)p,v,__ATOMIC_RELEASE); }
template<class T> bool read(const uint8_t* mem,int offset,T& value) {
 for(int i=0;i<4;i++) { auto a=acquire(mem+offset); if(a&1)continue;
 std::memcpy(&value,mem+offset+4,sizeof(T)); __atomic_thread_fence(__ATOMIC_ACQUIRE);
 if(a==acquire(mem+offset))return true; } return false;
}
template<class T> void write(uint8_t* mem,int offset,const T& value) {
 auto n=acquire(mem+offset); release(mem+offset,n+1); __atomic_thread_fence(__ATOMIC_SEQ_CST);
 std::memcpy(mem+offset+4,&value,sizeof(T)); release(mem+offset,n+2);
}
}
