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
#include "code_80005FD0.h"
#include "mk64.h"
#include "common_structs.h"
extern s32 gGamestate, gGamestateNext, gModeSelection, gPlayerCountSelection1, gCCSelection;
extern s32 gIsHUDVisible;
}

namespace {
using namespace composite;

// Mario Kart's own units per Minecraft block, and the factor to the shared 40-per-block units.
// Six game units to the block makes a kart about a block and a half wide beside the player.
constexpr float UNITS_PER_BLOCK = 6.0f, BLOCK = UNITS_PER_BLOCK, K = SCALE / UNITS_PER_BLOCK;
constexpr int AVATAR = 640, STATUS = 672, CONTROL = 832, CAMERA = 1280, CAMERA_QUERY = 1344, CAMERA_REPLY = 1376,
              FLOOR = 1536, DIG_HIT = 4608, CLASSIFY_REQUEST = 4672, CLASSIFY_REPLY = 4928, CLASSIFY_MAX = 16, CARVED = 5120,
              CARVED_SIDE = 16;
constexpr uint8_t CELL_OPEN = 0, CELL_SOLID = 1, CELL_SURFACE_NO_FLOOR = 2, CELL_SURFACE_FLOOR = 3;
// Controls from Minecraft, and requests: a character number mounts a kart of that
// character at the player; MOUNT and DISMOUNT get on and off the kart that is there.
constexpr uint32_t KEY_FORWARD = 1, KEY_BACK = 2, KEY_LEFT = 4, KEY_RIGHT = 8, KEY_JUMP = 16, KEY_A = 1 << 19, KEY_SNEAK = 1 << 28;
constexpr uint32_t REQUEST_MOUNT = 254, REQUEST_DISMOUNT = 255, FLAG_RIDING = 8, FLAG_NO_BODY = 64;
constexpr float DEGREES = 3.14159265358979f / 180;
constexpr s32 STATE_RACING = 4;

struct NativeCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid; };
struct RenderedCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid, sequence, scene; float light; };
static_assert(sizeof(RenderedCamera) == 44);
struct Avatar { uint32_t epoch, form; float radius, height, eye; uint32_t mask, flags; };
struct Status { uint32_t epoch, damage; int32_t rupees, magic, magicMax; uint32_t age, flags, reserved; };
struct Control { uint32_t epoch, heldItem, requestSerial, request, respawnSerial, flags, reserved[2]; };
struct CarvedGrid { uint32_t epoch, count; int32_t x, y, z; uint8_t bits[CARVED_SIDE * CARVED_SIDE * CARVED_SIDE / 8]; };

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
StoryMinecraft controls{};
CarvedGrid carvedGrid{};
bool riding = false;
uint32_t requestSeen = 0, previousKeys = 0;
u16 previousButtons = 0;

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
    const char* character = std::getenv("COMPOSITE_CHARACTER");
    gModeSelection = TIME_TRIALS;
    gPlayerCountSelection1 = gPlayerCount = 1;
    gScreenModeSelection = SCREEN_MODE_1P;
    gActiveScreenMode = SCREEN_MODE_1P;
    gCCSelection = CC_150;
    gCharacterSelections[0] = character ? (s8)std::clamp((int)std::strtol(character, nullptr, 0), 0, 7) : MARIO;
    gDemoMode = 0;
    TrackBrowser_SetTrack(track ? track : "mk:luigi_raceway");
    gCurrentCourseId = TrackBrowser_GetTrackIndex();
    gGamestateNext = STATE_RACING;
}

// --- the track's collision triangles, queried directly ------------------------------
// The game has no general ray test, and digging needs one that can skip dug cells.

// Game units to block cells, as Minecraft numbers them within this track.
int cellOf(float units) { return (int)std::floor(units / UNITS_PER_BLOCK); }
bool carvedAt(float x, float y, float z) {
    if (!carvedGrid.count) return false;
    int cx = cellOf(x) - carvedGrid.x, cy = cellOf(y) - carvedGrid.y, cz = cellOf(z) - carvedGrid.z;
    if (cx < 0 || cy < 0 || cz < 0 || cx >= CARVED_SIDE || cy >= CARVED_SIDE || cz >= CARVED_SIDE) return false;
    int index = (cy * CARVED_SIDE + cz) * CARVED_SIDE + cx;
    return carvedGrid.bits[index >> 3] >> (index & 7) & 1;
}
struct Hit { float x, y, z, nx, ny, nz; u16 surface; };
// Nearest surface facing the start of a line segment. With skipDug, surfaces whose cell
// (the one just behind them) has been dug out are passed through.
bool segment(float ax, float ay, float az, float bx, float by, float bz, Hit* out, bool skipDug) {
    float dx = bx - ax, dy = by - ay, dz = bz - az;
    float length = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (length < 0.001f || !gCollisionMesh) return false;
    float ux = dx / length, uy = dy / length, uz = dz / length;
    float lox = std::min(ax, bx), hix = std::max(ax, bx), loy = std::min(ay, by), hiy = std::max(ay, by),
          loz = std::min(az, bz), hiz = std::max(az, bz);
    float best = 2.0f;
    for (int i = 0; i < gCollisionMeshCount; i++) {
        const CollisionTriangle& t = gCollisionMesh[i];
        if (t.maxX < lox || t.minX > hix || t.maxY < loy || t.minY > hiy || t.maxZ < loz || t.minZ > hiz) continue;
        // One-sided, like the game's own collision: only surfaces facing the line's start.
        float facing = dx * t.normalX + dy * t.normalY + dz * t.normalZ;
        if (facing >= 0 || !t.vtx1 || !t.vtx2 || !t.vtx3) continue;
        const s16* p1 = t.vtx1->v.ob; const s16* p2 = t.vtx2->v.ob; const s16* p3 = t.vtx3->v.ob;
        float e1x = p2[0] - p1[0], e1y = p2[1] - p1[1], e1z = p2[2] - p1[2];
        float e2x = p3[0] - p1[0], e2y = p3[1] - p1[1], e2z = p3[2] - p1[2];
        float px = dy * e2z - dz * e2y, py = dz * e2x - dx * e2z, pz = dx * e2y - dy * e2x;
        float det = e1x * px + e1y * py + e1z * pz;
        if (std::abs(det) < 1e-6f) continue;
        float inv = 1.0f / det, sx = ax - p1[0], sy = ay - p1[1], sz = az - p1[2];
        float u = (sx * px + sy * py + sz * pz) * inv;
        if (u < -0.001f || u > 1.001f) continue;
        float qx = sy * e1z - sz * e1y, qy = sz * e1x - sx * e1z, qz = sx * e1y - sy * e1x;
        float v = (dx * qx + dy * qy + dz * qz) * inv;
        if (v < -0.001f || u + v > 1.002f) continue;
        float along = (e2x * qx + e2y * qy + e2z * qz) * inv;
        if (along < 0 || along > 1 || along >= best) continue;
        float hx = ax + dx * along, hy = ay + dy * along, hz = az + dz * along;
        if (skipDug && carvedAt(hx + ux * 0.05f * BLOCK, hy + uy * 0.05f * BLOCK, hz + uz * 0.05f * BLOCK)) continue;
        best = along;
        *out = { hx, hy, hz, t.normalX, t.normalY, t.normalZ, t.surfaceType };
    }
    return best <= 1.0f;
}
// Height of the track surface under a point (game units), or far below when there is none.
float floorUnder(float x, float y, float z, bool* skipped = nullptr) {
    Hit hit{};
    if (skipped) {
        Hit raw{};
        *skipped = segment(x, y, z, x, y - 3000, z, &raw, false) && carvedAt(raw.x, raw.y - 0.05f * BLOCK, raw.z);
    }
    return segment(x, y, z, x, y - 3000, z, &hit, true) ? hit.y : -32000.0f;
}

// Move the Minecraft player through the track's collision: walls push back, the surface
// holds. Scenery inside dug cells is ignored.
Reply collide(const Request& r) {
    if (!racing() || !active || r.epoch != port.epoch || !finite({ r.x, r.y, r.z, r.dx, r.dy, r.dz })) return { 0, 0, 0 };
    if (std::abs(r.dx) > 200 || std::abs(r.dy) > 200 || std::abs(r.dz) > 200) return { 0, 0, 0 };
    const float radius = 0.3f * BLOCK;
    float x = r.x / K, y = r.y / K, z = r.z / K;
    int steps = std::clamp((int)std::ceil(std::max({ std::abs(r.dx), std::abs(r.dy), std::abs(r.dz) }) / K / 2), 1, 32);
    for (int i = 0; i < steps; i++) {
        float nx = x + r.dx / K / steps, ny = y + r.dy / K / steps, nz = z + r.dz / K / steps;
        // Feel outwards for walls at knee and chest height and step back from them.
        for (float level : { 0.65f * BLOCK, 1.4f * BLOCK }) {
            Hit hit{};
            if (segment(x, y + level, z, nx, ny + level, nz, &hit, true)) nx = x, nz = z; // never through a wall in one step
            for (int direction = 0; direction < 8; direction++) {
                float ax = std::cos(direction * 0.7853982f), az = std::sin(direction * 0.7853982f);
                if (!segment(nx, ny + level, nz, nx + ax * radius, ny + level, nz + az * radius, &hit, true)) continue;
                if (hit.ny > 0.7f) continue; // a slope underfoot is ground, not a wall
                float gap = std::sqrt((hit.x - nx) * (hit.x - nx) + (hit.z - nz) * (hit.z - nz));
                nx -= ax * (radius - gap);
                nz -= az * (radius - gap);
            }
        }
        bool skipped = false;
        float floor = floorUnder(nx, y + 0.6f * BLOCK, nz, &skipped);
        // No ground at all is the edge of the track's world, unless a dug floor is why.
        if (floor < -31000 && ny < y - 0.2f * BLOCK && !skipped) return { (x - r.x / K) * K, (y - r.y / K) * K, (z - r.z / K) * K };
        if (ny < floor && r.dy <= 0) ny = floor;
        x = nx, y = ny, z = nz;
    }
    return { x * K - r.x, y * K - r.y, z * K - r.z };
}

// The game's surface types as the bridge's shared material numbers (what a surface mines as).
uint32_t material(u16 surface) {
    switch (surface) {
        case SAND: case SAND_OFFROAD: case WET_SAND: return 1;
        case ASPHALT: case STONE: case CLIFF: case CAVE: case TRAIN_TRACK: case BOOST_RAMP_ASPHALT: case RAMP: return 2;
        case BRIDGE: case ROPE_BRIDGE: case WOOD_BRIDGE: case BOOST_RAMP_WOOD: return 9;
        case SNOW: case SNOW_OFFROAD: case ICE: return 12;
        case WATER_SURFACE: return 4;
        default: return 8; // grass and dirt
    }
}
// What the crosshair rests on: for mining it, and for placing a first block on bare ground.
void publishAim() {
    struct DigHit { uint32_t epoch, valid; float x, y, z, nx, ny, nz; uint32_t material; } out{ port.epoch };
    port.target = 0;
    if (live() && racing() && !riding) {
        float yaw = mc.yaw * DEGREES, pitch = mc.pitch * DEGREES;
        float ex = mc.x / K, ey = mc.y / K + 1.62f * BLOCK, ez = mc.z / K, reach = 5.5f * BLOCK;
        Hit hit{};
        if (segment(ex, ey, ez, ex - std::sin(yaw) * std::cos(pitch) * reach, ey - std::sin(pitch) * reach, ez + std::cos(yaw) * std::cos(pitch) * reach, &hit, true)) {
            out = { port.epoch, 1, hit.x * K, hit.y * K, hit.z * K, hit.nx, hit.ny, hit.nz, material(hit.surface) };
            if (hit.ny > 0.5f) {
                // Upward-facing ground: the block cell standing on it.
                float cx = std::floor(hit.x / UNITS_PER_BLOCK) * UNITS_PER_BLOCK, cz = std::floor(hit.z / UNITS_PER_BLOCK) * UNITS_PER_BLOCK;
                float h = floorUnder(cx + BLOCK / 2, hit.y + 0.6f * BLOCK, cz + BLOCK / 2);
                if (h > -31000 && std::abs(h - hit.y) < 1.1f * BLOCK) {
                    port.target = 1;
                    port.tx = cx * K, port.ty = h * K, port.tz = cz * K;
                }
            }
        }
    }
    write(shared, DIG_HIT, out);
}
// Is a block cell open air, solid, or crossed by the track's surfaces?
uint8_t classify(int cx, int cy, int cz) {
    const float size = UNITS_PER_BLOCK, lo = 0.05f * size, hi = size - lo;
    float bx = cx * size, by = cy * size, bz = cz * size;
    Hit hit{};
    bool crossed = false;
    for (int axis = 0; axis < 3 && !crossed; axis++)
        for (int i = 0; i < 9 && !crossed; i++) {
            float u = lo + (i % 3) * (hi - lo) / 2, v = lo + (i / 3) * (hi - lo) / 2;
            float a[3] = { bx, by, bz }, b[3] = { bx, by, bz };
            a[axis] += lo, b[axis] += hi;
            a[(axis + 1) % 3] += u, b[(axis + 1) % 3] += u;
            a[(axis + 2) % 3] += v, b[(axis + 2) % 3] += v;
            crossed = segment(a[0], a[1], a[2], b[0], b[1], b[2], &hit, false) || segment(b[0], b[1], b[2], a[0], a[1], a[2], &hit, false);
        }
    float mx = bx + size / 2, mz = bz + size / 2;
    if (crossed) {
        if (!segment(mx, by + size * 0.98f, mz, mx, by, mz, &hit, false)) return CELL_SURFACE_NO_FLOOR;
        return (uint8_t)(CELL_SURFACE_FLOOR + std::clamp((hit.y - by) / size, 0.0f, 1.0f) * 252);
    }
    return segment(mx, by + size / 2, mz, mx, by - 3000, mz, &hit, false) ? CELL_OPEN : CELL_SOLID;
}
void serviceClassify() {
    auto serial = acquire(shared + CLASSIFY_REQUEST);
    if (serial == acquire(shared + CLASSIFY_REPLY) || !racing()) return;
    struct Cells { uint32_t epoch, count; int32_t cells[CLASSIFY_MAX][3]; } request;
    std::memcpy(&request, shared + CLASSIFY_REQUEST + 4, sizeof(request));
    if (serial != acquire(shared + CLASSIFY_REQUEST)) return;
    uint8_t classes[CLASSIFY_MAX]{};
    if (request.epoch == port.epoch && request.count <= CLASSIFY_MAX)
        for (uint32_t i = 0; i < request.count; i++) classes[i] = classify(request.cells[i][0], request.cells[i][1], request.cells[i][2]);
    std::memcpy(shared + CLASSIFY_REPLY + 4, classes, sizeof(classes));
    release(shared + CLASSIFY_REPLY, serial);
}

// --- the kart ---------------------------------------------------------------------
void placeKart(float x, float y, float z, float yawDegrees) {
    auto* kart = gPlayerOne;
    kart->pos[0] = kart->oldPos[0] = x;
    kart->pos[1] = kart->oldPos[1] = y + kart->boundingBoxSize;
    kart->pos[2] = kart->oldPos[2] = z;
    kart->velocity[0] = kart->velocity[1] = kart->velocity[2] = 0;
    kart->speed = 0;
    kart->rotation[1] = (s16)((180 - yawDegrees) * (32768.0f / 180));
}
void kartRequests() {
    Control control{};
    if (!read(shared, CONTROL, control) || control.epoch != port.epoch || control.requestSerial == requestSeen) return;
    requestSeen = control.requestSerial;
    if (!live()) return;
    if (control.request == REQUEST_DISMOUNT) riding = false;
    else {
        // Bring the kart to the player unless they are getting back on where it stands.
        if (control.request != REQUEST_MOUNT) placeKart(mc.x / K, mc.y / K, mc.z / K, mc.yaw);
        riding = true;
    }
}

void service() {
    CarvedGrid grid;
    if (read(shared, CARVED, grid) && grid.epoch == port.epoch) carvedGrid = grid;
    else carvedGrid.count = 0;
    serviceClassify();
    // Pull Minecraft's third-person camera in front of anything between it and the player.
    auto cameraSerial = acquire(shared + CAMERA_QUERY);
    if (cameraSerial != acquire(shared + CAMERA_REPLY)) {
        float eye[3], end[3];
        std::memcpy(eye, shared + CAMERA_QUERY + 8, 12);
        std::memcpy(end, shared + CAMERA_QUERY + 20, 12);
        Hit hit{};
        if (racing() && finite({ eye[0], eye[1], eye[2], end[0], end[1], end[2] }) &&
            segment(eye[0] / K, eye[1] / K, eye[2] / K, end[0] / K, end[1] / K, end[2] / K, &hit, true)) {
            float dx = eye[0] / K - hit.x, dy = eye[1] / K - hit.y, dz = eye[2] / K - hit.z, len = std::sqrt(dx * dx + dy * dy + dz * dz);
            float back = len > 0.1f ? 0.2f * BLOCK / len : 0;
            end[0] = (hit.x + dx * back) * K, end[1] = (hit.y + dy * back) * K, end[2] = (hit.z + dz * back) * K;
        }
        std::memcpy(shared + CAMERA_REPLY + 4, end, 12);
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
    // While riding, the kart carries the player: Minecraft follows it and hides its own body.
    story.flags = 1 | (riding ? FLAG_RIDING : 0);
    story.yaw = 180 - gPlayerOne->rotation[1] * (180.0f / 32768);
    std::memset(story.items, 255, sizeof(story.items));
    write(shared, STORY_PORT, story);
    // The player is an ordinary Minecraft-sized body here.
    // On foot this game draws no body for the player, so Minecraft shows its own.
    Avatar avatar{ port.epoch, 0, 12.0f, 72.0f, 64.8f, 0, story.flags | (riding ? 0 : FLAG_NO_BODY) };
    write(shared, AVATAR, avatar);
    Status status{ port.epoch, 0, 0, 0, 0, 0, story.flags, 0 };
    write(shared, STATUS, status);
    publishAim();
    // Floor heights around the player, so dropped items and mobs have ground.
    if (live() && port.frame % 4 == 0) {
        struct Floors { uint32_t epoch; int32_t x, z; float y[81]; } f{};
        f.epoch = port.epoch;
        f.x = (int)std::floor(mc.x / SCALE) - 4;
        f.z = (int)std::floor(mc.z / SCALE) - 4;
        for (int i = 0; i < 81; i++) {
            float h = floorUnder(((f.x + i % 9) * SCALE + SCALE / 2) / K, mc.y / K + 0.6f * BLOCK, ((f.z + i / 9) * SCALE + SCALE / 2) / K);
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
        port.x = (gPlayerOne->pos[0] + 2 * BLOCK) * K;
        port.z = gPlayerOne->pos[2] * K;
        float floor = floorUnder(gPlayerOne->pos[0] + 2 * BLOCK, gPlayerOne->pos[1] + BLOCK, gPlayerOne->pos[2]);
        port.y = (floor < -31000 ? gPlayerOne->pos[1] : floor) * K;
        // Restarted to change character: the kart goes back where the player asked for it.
        float at[4];
        const char* kartAt = std::getenv("COMPOSITE_KART_AT");
        riding = false;
        if (kartAt && std::sscanf(kartAt, "%f,%f,%f,%f", &at[0], &at[1], &at[2], &at[3]) == 4) {
            placeKart(at[0] / K, at[1] / K, at[2] / K, at[3]);
            port.x = at[0], port.y = at[1], port.z = at[2];
            riding = true;
        }
        mc = {};
        controls = {};
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
        StoryMinecraft next{};
        if (read(shared, STORY_MC, next) && next.epoch == port.epoch) controls = next;
        uint32_t keys = live() ? controls.controls : 0, pressed = keys & ~previousKeys;
        previousKeys = keys;
        kartRequests();
        // Sneak gets off; using the kart with an empty hand from beside it gets on.
        if (riding && (pressed & KEY_SNEAK)) riding = false;
        float kx = gPlayerOne->pos[0] - mc.x / K, kz = gPlayerOne->pos[2] - mc.z / K;
        if (!riding && (pressed & KEY_A) && kx * kx + kz * kz < 9 * BLOCK * BLOCK) riding = true;
        if (riding) {
            port.x = gPlayerOne->pos[0] * K;
            port.y = (gPlayerOne->pos[1] - gPlayerOne->boundingBoxSize) * K;
            port.z = gPlayerOne->pos[2] * K;
        }
        // Free roaming, not a race: there is never a last lap. (The game's own start
        // sequence is left to run; it also opens the picture up from the centre.)
        gLapCountByPlayerId[0] = 0;
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

// The kart's controller: Minecraft's movement keys while riding, nothing otherwise.
extern "C" bool CompositeController(struct Controller* controller) {
    if (!shared) return false;
    uint32_t keys = riding && live() ? controls.controls : 0;
    u16 buttons = 0;
    if (keys & KEY_FORWARD) buttons |= A_BUTTON;
    if (keys & KEY_BACK) buttons |= B_BUTTON;
    if (keys & KEY_JUMP) buttons |= R_TRIG; // hop and drift
    controller->rawStickX = ((keys & KEY_RIGHT) ? 70 : 0) - ((keys & KEY_LEFT) ? 70 : 0);
    controller->rawStickY = 0;
    controller->rightRawStickX = controller->rightRawStickY = 0;
    controller->buttonPressed = buttons & (buttons ^ previousButtons);
    controller->buttonDepressed = previousButtons & (buttons ^ previousButtons);
    controller->button = previousButtons = buttons;
    controller->stickDirection = controller->stickPressed = controller->stickDepressed = 0;
    return true;
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
    // The game decides which karts are in view, and which side of them to draw, from
    // the camera's angles rather than from where it looks.
    float dx = camera->lookAt[0] - camera->pos[0], dy = camera->lookAt[1] - camera->pos[1], dz = camera->lookAt[2] - camera->pos[2];
    camera->rot[1] = (s16)(std::atan2(dx, dz) * (32768.0f / 3.14159265f));
    camera->rot[0] = (s16)(std::atan2(std::sqrt(dx * dx + dz * dz), dy) * (32768.0f / 3.14159265f));
    camera->rot[2] = 0;
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
