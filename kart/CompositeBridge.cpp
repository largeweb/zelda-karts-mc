// Mario Kart 64 side of the Minecraft bridge. Runs inside SpaghettiKart.
// Minecraft owns the controls, camera and player position; the game supplies the
// track, its scenery and collision. The game's free camera is driven from Minecraft.
//
// The shared-memory layout is the one the Ocarina of Time bridge uses, and so are its
// units: everything published here is scaled so that 40 units make one block.
#include "Protocol.h"
#include <SDL2/SDL.h>
#include <libultraship/libultraship.h>
#include <spdlog/spdlog.h>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <unordered_map>
#include <vector>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include "port/Game.h"
#include "engine/TrackBrowser.h"
extern "C" {
#include "main.h"
#include "defines.h"
#include "camera.h"
#include "menus.h"
#include "racing/collision.h"
#include "code_800029B0.h"
extern s32 gGamestate, gGamestateNext, gModeSelection, gPlayerCountSelection1, gCCSelection;
extern s32 gIsHUDVisible;
}

namespace {
using namespace composite;

// Mario Kart's own units per Minecraft block, and the factor to the shared 40-per-block units.
constexpr float UNITS_PER_BLOCK = 10.0f, K = SCALE / UNITS_PER_BLOCK;
constexpr int AVATAR = 640, STATUS = 672, CAMERA = 1280, CAMERA_QUERY = 1344, CAMERA_REPLY = 1376, FLOOR = 1536,
              DIG_HIT = 4608;
constexpr float DEGREES = 3.14159265358979f / 180;
constexpr s32 STATE_RACING = 4;

struct NativeCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid; };
struct RenderedCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid, sequence, scene; float light; };
static_assert(sizeof(RenderedCamera) == 44);
struct Avatar { uint32_t epoch, form; float radius, height, eye; uint32_t mask, flags; };
struct Status { uint32_t epoch, damage; int32_t rupees, magic, magicMax; uint32_t age, flags, reserved; };

uint8_t* shared = nullptr;
bool failed = false;
Port port{};
Minecraft mc{};
RenderedCamera renderedCamera{};
float renderedNear = 0, renderedFar = 0;
bool active = false;
uint32_t lastTick = 0, lastReply = 0;
uint64_t lastMinecraft = 0;
int frames = 0;
// The free camera's matrices, replaced at the last moment with Minecraft's exact view.
Mtx* perspectiveMatrix = nullptr;
Mtx* lookAtMatrix = nullptr;

bool racing() { return gGamestate == STATE_RACING && gPlayerOne != nullptr; }
bool live() { return active && mc.epoch == port.epoch && lastMinecraft && SDL_GetTicks64() - lastMinecraft < 1000; }
bool finite(std::initializer_list<float> values) {
    for (float f : values)
        if (!std::isfinite(f) || std::abs(f) > 1000000) return false;
    return true;
}

bool init() {
    if (shared) return true;
    const char* path = std::getenv("COMPOSITE_SHM");
    if (!path || failed) return false;
    int descriptor = open(path, O_RDWR | O_CREAT | O_NOFOLLOW, 0600);
    struct stat st {};
    if (descriptor < 0 || flock(descriptor, LOCK_EX | LOCK_NB) != 0 || fstat(descriptor, &st) || st.st_uid != getuid() ||
        !S_ISREG(st.st_mode) || ftruncate(descriptor, SIZE) != 0) {
        spdlog::error("[Composite] Shared memory unavailable or already in use");
        failed = true;
        return false;
    }
    auto mapped = mmap(nullptr, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    if (mapped == MAP_FAILED) {
        failed = true;
        return false;
    }
    shared = (uint8_t*)mapped;
    std::memset(shared, 0, SIZE);
    release(shared, MAGIC);
    release(shared + 4, VERSION);
    port.epoch = (uint32_t)getpid();
    // The game's free camera is what Minecraft drives; it also hides the race display.
    CVarSetInteger("gFreecam", 1);
    CVarSetInteger("gVsyncEnabled", 0);
    CVarSetInteger("gMatchRefreshRate", 0);
    CVarSetInteger("gInterpolationFPS", 60);
    spdlog::info("[Composite] Bridge ready");
    return true;
}

// Skip the menus: one kart, no opponents, on the track named by COMPOSITE_TRACK.
void startTrack() {
    const char* track = std::getenv("COMPOSITE_TRACK");
    gModeSelection = TIME_TRIALS;
    gPlayerCountSelection1 = gPlayerCount = 1;
    gScreenModeSelection = SCREEN_MODE_1P;
    gActiveScreenMode = SCREEN_MODE_1P;
    gCCSelection = CC_150;
    gCharacterSelections[0] = MARIO;
    gDemoMode = 0;
    TrackBrowser_SetTrack(track ? track : "mk:luigi_raceway");
    gCurrentCourseId = TrackBrowser_GetTrackIndex();
    gGamestateNext = STATE_RACING;
}

// Height of the track surface under a point (game units), or far below when there is none.
float floorUnder(float x, float y, float z) {
    float h = spawn_actor_on_surface(x, y, z);
    return h > -2900.0f && h < 2900.0f ? h : -32000.0f;
}

// Move the Minecraft player through the track's collision: walls push back, the surface holds.
Reply collide(const Request& r) {
    if (!racing() || !active || r.epoch != port.epoch || !finite({ r.x, r.y, r.z, r.dx, r.dy, r.dz })) return { 0, 0, 0 };
    if (std::abs(r.dx) > 200 || std::abs(r.dy) > 200 || std::abs(r.dz) > 200) return { 0, 0, 0 };
    const float radius = 3.0f;
    float x = r.x / K, y = r.y / K, z = r.z / K;
    int steps = std::clamp((int)std::ceil(std::max({ std::abs(r.dx), std::abs(r.dy), std::abs(r.dz) }) / K / 2), 1, 32);
    for (int i = 0; i < steps; i++) {
        float nx = x + r.dx / K / steps, ny = y + r.dy / K / steps, nz = z + r.dz / K / steps;
        // The game's terrain test works on a sphere; sit one on the ground at the player's feet.
        Collision collision{};
        actor_terrain_collision(&collision, radius, nx, ny + radius, nz, x, y + radius, z);
        if (collision.surfaceDistance[0] < 0.0f && collision.unk30 == 1) {
            nx -= collision.unk48[0] * collision.surfaceDistance[0];
            nz -= collision.unk48[2] * collision.surfaceDistance[0];
        }
        if (collision.surfaceDistance[1] < 0.0f && collision.unk32 == 1) {
            nx -= collision.unk54[0] * collision.surfaceDistance[1];
            nz -= collision.unk54[2] * collision.surfaceDistance[1];
        }
        float floor = floorUnder(nx, y + 6, nz);
        if (floor < -31000) return { (x - r.x / K) * K, (y - r.y / K) * K, (z - r.z / K) * K }; // edge of the track's world
        if (ny < floor && r.dy <= 0) ny = floor;
        x = nx, y = ny, z = nz;
    }
    return { x * K - r.x, y * K - r.y, z * K - r.z };
}

void service() {
    // Third-person camera pull-in is not checked against the track yet: allow it as asked.
    auto cameraSerial = acquire(shared + CAMERA_QUERY);
    if (cameraSerial != acquire(shared + CAMERA_REPLY)) {
        std::memcpy(shared + CAMERA_REPLY + 4, shared + CAMERA_QUERY + 20, 12);
        release(shared + CAMERA_REPLY, cameraSerial);
    }
    auto serial = acquire(shared + REQUEST);
    if (serial == lastReply) return;
    Request r{};
    std::memcpy(&r, shared + REQUEST + 4, sizeof(r));
    if (serial != acquire(shared + REQUEST)) return;
    Reply result = collide(r);
    std::memcpy(shared + REPLY + 4, &result, sizeof(result));
    release(shared + REPLY, serial);
    lastReply = serial;
}

void publish() {
    StoryPort story{};
    story.flags = 1;
    std::memset(story.items, 255, sizeof(story.items));
    write(shared, STORY_PORT, story);
    // The player is an ordinary Minecraft-sized body here.
    // Flag 64: this game draws no body for the player, so Minecraft shows its own.
    Avatar avatar{ port.epoch, 0, 12.0f, 72.0f, 64.8f, 0, story.flags | 64 };
    write(shared, AVATAR, avatar);
    Status status{ port.epoch, 0, 0, 0, 0, 0, story.flags, 0 };
    write(shared, STATUS, status);
    struct Hit { uint32_t epoch, valid; float x, y, z, nx, ny, nz; uint32_t material; } hit{ port.epoch };
    write(shared, DIG_HIT, hit);
    // Floor heights around the player, so dropped items and mobs have ground.
    if (live() && port.frame % 4 == 0) {
        struct Floors { uint32_t epoch; int32_t x, z; float y[81]; } f{};
        f.epoch = port.epoch;
        f.x = (int)std::floor(mc.x / SCALE) - 4;
        f.z = (int)std::floor(mc.z / SCALE) - 4;
        for (int i = 0; i < 81; i++) {
            float h = floorUnder(((f.x + i % 9) * SCALE + SCALE / 2) / K, mc.y / K + 6, ((f.z + i / 9) * SCALE + SCALE / 2) / K);
            f.y[i] = h < -31000 ? -32000 : h * K;
        }
        write(shared, FLOOR, f);
    }
}
} // namespace

// Once per game frame.
extern "C" void CompositeTick() {
    if (!init()) return;
    frames++;
    if (!racing()) {
        active = false;
        // Past the opening logos, go straight to the track.
        if (frames == 90) startTrack();
    } else if (!active) {
        port.epoch++;
        // Begin on foot beside the kart on the starting grid.
        port.x = (gPlayerOne->pos[0] + 20) * K;
        port.z = gPlayerOne->pos[2] * K;
        float floor = floorUnder(gPlayerOne->pos[0] + 20, gPlayerOne->pos[1] + 10, gPlayerOne->pos[2]);
        port.y = (floor < -31000 ? gPlayerOne->pos[1] : floor) * K;
        mc = {};
        lastMinecraft = 0;
        lastTick = 0;
        active = true;
        spdlog::info("[Composite] Track {} ready, epoch {}", (int)TrackBrowser_GetTrackIndex(), port.epoch);
    }
    service();
    if (active) {
        Minecraft candidate{};
        if (read(shared, MC, candidate) && candidate.epoch == port.epoch && candidate.tick != lastTick &&
            finite({ candidate.x, candidate.y, candidate.z, candidate.yaw, candidate.pitch })) {
            mc = candidate;
            lastTick = mc.tick;
            lastMinecraft = SDL_GetTicks64();
        }
        publish();
        gIsHUDVisible = 0;
    }
    port.active = active ? 2 : 0;
    port.buttons = 0;
    // Tracks sit far from Zelda's scene numbers in Minecraft's block world.
    port.scene = racing() ? 1000 + (uint32_t)TrackBrowser_GetTrackIndex() : 0xFFFFFFFF;
    port.frame++;
    write(shared, PORT, port);
}

extern "C" bool CompositePollCollision() {
    if (!shared) return false;
    release(shared + 56, acquire(shared + 56) + 1);
    service();
    return true;
}

// The game's free camera, placed where Minecraft's camera is. Returns true when driven.
extern "C" bool CompositeDriveCamera(Camera* camera, Mtx* perspective, Mtx* lookAt) {
    perspectiveMatrix = perspective;
    lookAtMatrix = lookAt;
    if (!shared || !live()) return shared != nullptr;
    NativeCamera v{};
    if (!read(shared, CAMERA, v) || v.epoch != port.epoch || !v.valid || !finite({ v.x, v.y, v.z, v.yaw, v.pitch }) ||
        v.fov < 20 || v.fov > 150)
        return true;
    float yaw = v.yaw * DEGREES, pitch = v.pitch * DEGREES;
    camera->pos[0] = v.x / K, camera->pos[1] = v.y / K, camera->pos[2] = v.z / K;
    camera->lookAt[0] = camera->pos[0] - std::sin(yaw) * std::cos(pitch) * 100;
    camera->lookAt[1] = camera->pos[1] - std::sin(pitch) * 100;
    camera->lookAt[2] = camera->pos[2] + std::cos(yaw) * std::cos(pitch) * 100;
    camera->up[0] = 0, camera->up[1] = 1, camera->up[2] = 0;
    camera->fieldOfView = v.fov;
    return true;
}

#include "FrameExport.h"

// Late-latch the view for every displayed (including interpolated) frame so the
// track's image and Minecraft's blocks share exactly one camera.
void CompositeLateCamera(std::unordered_map<Mtx*, MtxF>& replacements) {
    renderedCamera = {};
    renderedNear = renderedFar = 0;
    if (!shared || !racing() || !perspectiveMatrix || !lookAtMatrix) return;
    auto* props = CM_GetProps();
    renderedNear = props->NearPersp * K;
    renderedFar = props->FarPersp * K;
    renderedCamera.light = 1.0f;
    if (!live()) return;
    NativeCamera v{};
    if (!read(shared, CAMERA, v) || v.epoch != port.epoch || !v.valid || v.fov < 20 || v.fov > 150) return;
    if (!finite({ v.x, v.y, v.z, v.yaw, v.pitch, v.fov })) return;
    const float yaw = v.yaw * DEGREES, pitch = v.pitch * DEGREES;
    const float x = v.x / K, y = v.y / K, z = v.z / K;
    MtxF look{}, projection{};
    u16 norm;
    guLookAtF(look.mf, x, y, z, x - std::sin(yaw) * std::cos(pitch) * 100, y - std::sin(pitch) * 100,
              z + std::cos(yaw) * std::cos(pitch) * 100, 0, 1, 0);
    guPerspectiveF(projection.mf, &norm, v.fov, gScreenAspect, props->NearPersp, props->FarPersp, 1.0f);
    replacements[lookAtMatrix] = look;
    replacements[perspectiveMatrix] = projection;
    renderedCamera = { v.epoch, v.x, v.y, v.z, v.yaw, v.pitch, v.fov, 1, acquire(shared + CAMERA), port.scene, 1.0f };
}
