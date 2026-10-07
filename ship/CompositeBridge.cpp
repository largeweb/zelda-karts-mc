// Ocarina of Time side of the Minecraft bridge. Runs inside Ship of Harkinian.
// Minecraft owns the controls, camera and player position; Zelda owns scenery,
// actors, story and Link's body. All gameplay writes happen on the game thread.
#include "Protocol.h"
#include "soh/Enhancements/game-interactor/GameInteractor.h"
#include "soh/Enhancements/game-interactor/GameInteractor_Hooks.h"
#include "soh/Enhancements/SwitchAge.h"
#include "soh/ShipInit.hpp"
#include "soh/SaveManager.h"
#include <SDL2/SDL.h>
#include <libultraship/libultraship.h>
#include <spdlog/spdlog.h>
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <unordered_map>
#include <vector>
#include <fcntl.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
extern "C" {
#include <z64.h>
#include "macros.h"
#include "functions.h"
#include "variables.h"
#include "overlays/actors/ovl_En_Arrow/z_en_arrow.h"
#include "overlays/actors/ovl_En_Bom/z_en_bom.h"
#include "overlays/actors/ovl_Magic_Fire/z_magic_fire.h"
#include "objects/gameplay_keep/gameplay_keep.h"
extern SaveContext gSaveContext;
extern PlayState* gPlayState;
void EnArrow_Fly(EnArrow*, PlayState*);
void Sram_InitDebugSave(void);
void Sram_OpenSave(void);
void Save_SaveFile(void);
void Play_Init(GameState*);
}

namespace {
using namespace composite;

// Offsets beyond Protocol.h's legacy layout; mirrored by the Fabric mod.
constexpr int AVATAR = 640, STATUS = 672, CONTROL = 832, FIRE = 928, CAMERA = 1280, CAMERA_QUERY = 1344, CAMERA_REPLY = 1376,
              COMBAT = 1408, FLOOR = 1536, ENEMIES = 1888;
// Digging. Minecraft tells Zelda which cells have been dug out (CARVED); Zelda tells
// Minecraft what the crosshair hits (DIG_HIT) and answers questions about whether cells
// are open air, solid, or have scenery passing through them (CLASSIFY).
constexpr int DIG_HIT = 4608, CLASSIFY_REQUEST = 4672, CLASSIFY_REPLY = 4928, CLASSIFY_MAX = 16, CARVED = 5120, CARVED_SIDE = 16,
              CLASSIFY_PLANES = 5700;
// For a cell with scenery through it: that surface as a plane in the cell's own 0..1
// coordinates (normal towards the open side, and its offset). What lies behind the
// plane is the solid part of the cell, which Minecraft draws and collides with.
struct CellPlane { float nx, ny, nz, d; };
// Answers to "what is in this cell": open air, solid, or scenery passing through it.
// A surface cell also says how far up the cell its floor is (3..255 = 0..1), or that it
// has none (a wall or ceiling runs through it).
constexpr uint8_t CELL_OPEN = 0, CELL_SOLID = 1, CELL_SURFACE_NO_FLOOR = 2, CELL_SURFACE_FLOOR = 3;

// The cells the player has dug out, around the player, one bit each. Zelda's collision
// and crosshair ignore scenery inside them; everything else collides as normal.
struct CarvedGrid { uint32_t epoch, count; int32_t x, y, z; uint8_t bits[CARVED_SIDE * CARVED_SIDE * CARVED_SIDE / 8]; };
constexpr uint32_t FLAG_ACTIVE = 1, FLAG_DIALOGUE = 2, FLAG_PAUSE = 4, FLAG_SCRIPTED = 8, FLAG_INSTRUMENT = 16, FLAG_AIMING = 32;
// Control bits published by Minecraft.
constexpr uint32_t KEY_FORWARD = 1, KEY_BACK = 2, KEY_LEFT = 4, KEY_RIGHT = 8, KEY_JUMP = 16, KEY_ATTACK = 32,
                   KEY_USE = 128, KEY_SHIFT = 1 << 17, KEY_CTRL = 1 << 18, KEY_A = 1 << 19, KEY_B = 1 << 20,
                   KEY_START = 1 << 22, KEY_CUP = 1 << 23, KEY_CDOWN = 1 << 24, KEY_CLEFT = 1 << 25,
                   KEY_CRIGHT = 1 << 26, KEY_OCARINA = 1 << 27;
constexpr float DEGREES = 3.14159265358979f / 180;
constexpr float BINANG = 32768.0f / 180;

struct NativeCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid; };
struct RenderedCamera { uint32_t epoch; float x, y, z, yaw, pitch, fov; uint32_t valid, sequence, scene; float light; };
static_assert(sizeof(RenderedCamera) == 44);
struct Avatar { uint32_t epoch, form; float radius, height, eye; uint32_t mask, flags; };
struct Status { uint32_t epoch, damage; int32_t rupees, magic, magicMax; uint32_t age, flags, reserved; };
struct Control { uint32_t epoch, heldItem, ageSerial, age, respawnSerial, flags, reserved[2]; };
constexpr uint32_t CONTROL_CREATIVE = 1;
struct CombatEvent { uint32_t epoch, sequence, kind; float x, y, z, dx, dy, dz, power; };
static_assert(sizeof(CombatEvent) == 40);

uint8_t* shared = nullptr;
Port port{};
Minecraft mc{};
BlockLayer blocks{};
StoryPort storyPort{};
StoryMinecraft storyMC{};
Control control{};
RenderedCamera renderedCamera{};
// Near and far planes of the frame being rendered, for converting its depth buffer.
float renderedNear = 0, renderedFar = 0;
bool active = false, sceneReady = false;
// A new world's save is first written once the game is running; saving during boot races.
bool saveWhenReady = false;
uint32_t lastTick = 0, lastReply = 0, damageTotal = 0;
uint64_t lastMinecraft = 0;
uint32_t previousControls = 0, ageSeen = 0, respawnSeen = 0, lastCombat = 0;
u16 previousPad = 0;
// The audio thread polls instrument input separately; hand it one expiring snapshot.
std::atomic<uint64_t> instrumentInput{ 0 };

Player* link() { return GET_PLAYER(gPlayState); }
bool world() { return sceneReady && gPlayState && link(); }
bool live() { return active && mc.epoch == port.epoch && lastMinecraft && SDL_GetTicks64() - lastMinecraft < 1000; }
bool finite(std::initializer_list<float> values) {
    for (float f : values)
        if (!std::isfinite(f) || std::abs(f) > 100000) return false;
    return true;
}
float bodyHeight() { return link()->ageProperties ? link()->ageProperties->ceilingCheckHeight : 56.0f; }
// What Minecraft uses for its player box and camera: Link's visible height, which is
// taller than the ceiling check the game uses for collision.
float avatarHeight() { return LINK_IS_ADULT ? 66.0f : 44.0f; }
float avatarEye() { return LINK_IS_ADULT ? 60.0f : 38.0f; }
float bodyRadius() { return link()->ageProperties ? link()->ageProperties->wallCheckRadius : 14.0f; }

// Zelda keeps Link when a cutscene, climb, ride or similar scripted motion is running.
// Some interiors (shops, houses, the Market) are a flat painted backdrop that only makes
// sense from the game's own fixed camera; a free first-person view shows nothing there.
bool paintedRoom() {
    auto* mesh = gPlayState->roomCtx.curRoom.meshHeader;
    return mesh && mesh->base.type == 1;
}
bool nativeOwnsMotion() {
    auto* p = link();
    return Player_InCsMode(gPlayState) || paintedRoom() ||
           (p->stateFlags1 & (PLAYER_STATE1_HANGING_OFF_LEDGE | PLAYER_STATE1_CLIMBING_LEDGE |
                              PLAYER_STATE1_CLIMBING_LADDER | PLAYER_STATE1_ON_HORSE | PLAYER_STATE1_GETTING_ITEM |
                              PLAYER_STATE1_TALKING)) ||
           (p->stateFlags2 & (PLAYER_STATE2_GRABBED_BY_ENEMY | PLAYER_STATE2_CRAWLING | PLAYER_STATE2_FROZEN));
}
bool cameraFree() {
    return world() && !nativeOwnsMotion() && !gPlayState->msgCtx.msgLength && !gPlayState->pauseCtx.state &&
           gPlayState->activeCamera == CAM_ID_MAIN && !link()->focusActor && !(storyMC.controls & KEY_CTRL);
}

void stop() {
    active = false;
    port.active = 0;
    lastMinecraft = 0;
    previousControls = 0;
    previousPad = 0;
    lastCombat = 0;
    blocks = {};
    instrumentInput.store(0, std::memory_order_release);
}
void start() {
    if (!world()) return;
    auto* p = link();
    port.epoch++;
    port.active = 2;
    port.x = p->actor.world.pos.x;
    port.y = p->actor.world.pos.y;
    port.z = p->actor.world.pos.z;
    mc = {};
    blocks = {};
    storyMC = {};
    lastMinecraft = 0;
    lastTick = 0;
    active = true;
    if (saveWhenReady) {
        saveWhenReady = false;
        SaveManager::Instance->SaveSection(gSaveContext.fileNum, SECTION_ID_BASE, false);
    }
    spdlog::info("[Composite] Scene {} ready, epoch {}", gPlayState->sceneNum, port.epoch);
}

#include "BlockColliders.h"

CarvedGrid carvedGrid{};
void readCarved() {
    CarvedGrid next;
    if (read(shared, CARVED, next) && next.epoch == port.epoch) carvedGrid = next;
    else carvedGrid.count = 0;
}
bool carvedAt(float x, float y, float z) {
    if (!carvedGrid.count) return false;
    int cx = (int)std::floor(x / SCALE) - carvedGrid.x, cy = (int)std::floor(y / SCALE) - carvedGrid.y,
        cz = (int)std::floor(z / SCALE) - carvedGrid.z;
    if (cx < 0 || cy < 0 || cz < 0 || cx >= CARVED_SIDE || cy >= CARVED_SIDE || cz >= CARVED_SIDE) return false;
    int index = (cy * CARVED_SIDE + cz) * CARVED_SIDE + cx;
    return carvedGrid.bits[index >> 3] >> (index & 7) & 1;
}
// First scenery along a line that has not been dug away. A surface belongs to the cell
// just behind it, so that cell decides whether it is still there.
bool lineHit(Vec3f from, Vec3f to, Vec3f* hit, CollisionPoly** poly, s32* bg) {
    float dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z, length = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (length < 0.01f) return false;
    dx /= length, dy /= length, dz /= length;
    for (int i = 0; i < 6; i++) {
        if (!BgCheck_EntityLineTest1(&gPlayState->colCtx, &from, &to, hit, poly, true, true, true, true, bg)) return false;
        if (!carvedAt(hit->x + dx * 2, hit->y + dy * 2, hit->z + dz * 2)) return true;
        from = { hit->x + dx * 2, hit->y + dy * 2, hit->z + dz * 2 };
    }
    return false;
}
// Highest floor at or below a point that has not been dug away.
float floorBelow(Vec3f ray, Actor* actor, bool* skipped) {
    for (int i = 0; i < 8; i++) {
        CollisionPoly* floor = nullptr;
        s32 bg = 0;
        float h = BgCheck_EntityRaycastFloor5(gPlayState, &gPlayState->colCtx, &floor, &bg, actor, &ray);
        if (h < -31000 || !carvedAt(ray.x, h - 2, ray.z)) return h;
        *skipped = true;
        ray.y = h - 2;
    }
    return -32000;
}

// Sweep the Minecraft player capsule through Zelda's collision world. Minecraft
// resolves its own block shapes before and after, so mirrored cubes are skipped.
Reply collide(const Request& r) {
    if (!world() || !active || r.epoch != port.epoch) return { 0, 0, 0 };
    if (!finite({ r.x, r.y, r.z, r.dx, r.dy, r.dz })) return { 0, 0, 0 };
    if (std::abs(r.dx) > 200 || std::abs(r.dy) > 200 || std::abs(r.dz) > 200) return { 0, 0, 0 };
    auto* actor = &link()->actor;
    float height = bodyHeight(), radius = bodyRadius();
    float requested;
    std::memcpy(&requested, shared + 44, 4);
    if (std::isfinite(requested) && requested >= 8 && requested <= height) height = requested;
    CubeQueryGuard cubes;
    // Near dug-out cells the game's own wall test cannot be used: it pushes away from a
    // whole wall, including the part that has been removed.
    const bool dug = carvedGrid.count > 0;
    Vec3f cur{ r.x, r.y, r.z };
    int steps = std::clamp((int)std::ceil(std::max({ std::abs(r.dx), std::abs(r.dy), std::abs(r.dz) }) / 8), 1, 32);
    for (int i = 0; i < steps; i++) {
        Vec3f next{ cur.x + r.dx / steps, cur.y + r.dy / steps, cur.z + r.dz / steps }, out = next;
        CollisionPoly* wall = nullptr;
        s32 bg = 0;
        if (!dug) {
            BgCheck_EntitySphVsWall3(&gPlayState->colCtx, &out, &next, &cur, radius, &wall, &bg, actor,
                                     std::min(26.8f, height - 2.0f));
            // Test near the head too, so low archways cannot be walked through upright.
            float headOffset = std::max(0.0f, height - 20);
            Vec3f oldHead{ cur.x, cur.y + headOffset, cur.z }, newHead{ out.x, out.y + headOffset, out.z }, head = newHead;
            BgCheck_EntitySphVsWall3(&gPlayState->colCtx, &head, &newHead, &oldHead, radius, &wall, &bg, actor, 20.0f);
            out.x = head.x;
            out.z = head.z;
        } else {
            // Feel outwards for walls at knee, waist and head height and step back from
            // any that are still there. Above step height, so low ledges are climbed.
            const float reach = std::min(radius, 12.0f);
            for (float level : { 27.0f, height * .5f, std::max(28.0f, height - 6) }) {
                Vec3f hit{};
                // Do not pass through a wall in one step.
                Vec3f from{ cur.x, cur.y + level, cur.z }, to{ out.x, out.y + level, out.z };
                if (lineHit(from, to, &hit, &wall, &bg)) {
                    out.x = cur.x;
                    out.z = cur.z;
                }
                for (int direction = 0; direction < 8; direction++) {
                    float ax = std::cos(direction * 0.7853982f), az = std::sin(direction * 0.7853982f);
                    Vec3f centre{ out.x, out.y + level, out.z }, edge{ out.x + ax * reach, out.y + level, out.z + az * reach };
                    if (!lineHit(centre, edge, &hit, &wall, &bg)) continue;
                    float gap = std::sqrt((hit.x - centre.x) * (hit.x - centre.x) + (hit.z - centre.z) * (hit.z - centre.z));
                    out.x -= ax * (reach - gap);
                    out.z -= az * (reach - gap);
                }
            }
        }
        float floorY = -32000;
        bool skipped = false;
        for (auto offset : { Vec3f{ 0, 0, 0 }, Vec3f{ 9, 0, 9 }, Vec3f{ -9, 0, 9 }, Vec3f{ 9, 0, -9 }, Vec3f{ -9, 0, -9 } }) {
            Vec3f ray{ out.x + offset.x, std::max(cur.y, out.y) + 24, out.z + offset.z };
            float h = floorBelow(ray, actor, &skipped);
            if (h <= cur.y + 24.01f) floorY = std::max(floorY, h);
        }
        if (out.y < floorY && r.dy <= 0) out.y = floorY;
        float ceilingY = out.y;
        CollisionPoly* ceiling = nullptr;
        if (BgCheck_EntityCheckCeiling(&gPlayState->colCtx, &ceilingY, &out, height, &ceiling, &bg, actor) &&
            !carvedAt(out.x, ceilingY + height + 2, out.z))
            out.y = std::min(out.y, ceilingY);
        // No floor at all means the edge of the world, unless a dug floor is why.
        if (floorY < -31000 && out.y < cur.y - 20 && !skipped) return { 0, 0, 0 };
        cur = out;
    }
    return { cur.x - r.x, cur.y - r.y, cur.z - r.z };
}

// What the crosshair rests on in Zelda's scenery, floor or wall, for mining it.
void publishDigHit() {
    struct Hit { uint32_t epoch, valid; float x, y, z, nx, ny, nz; uint32_t material; } out{};
    out.epoch = port.epoch;
    if (live() && world()) {
        {
            float yaw = mc.yaw * DEGREES, pitch = mc.pitch * DEGREES;
            Vec3f eye{ mc.x, mc.y + avatarEye(), mc.z };
            Vec3f end{ eye.x - std::sin(yaw) * std::cos(pitch) * 220, eye.y - std::sin(pitch) * 220,
                       eye.z + std::cos(yaw) * std::cos(pitch) * 220 };
            Vec3f hit{};
            CollisionPoly* poly = nullptr;
            s32 bg = 0;
            CubeQueryGuard cubes;
            // Only the scene itself can be dug, not doors, platforms and other moving pieces.
            if (lineHit(eye, end, &hit, &poly, &bg) && poly && bg == BGCHECK_SCENE) {
                out = { port.epoch, 1, hit.x, hit.y, hit.z, poly->normal.x / 32767.0f, poly->normal.y / 32767.0f,
                        poly->normal.z / 32767.0f, func_80041F10(&gPlayState->colCtx, poly, bg) };
            }
        }
    }
    write(shared, DIG_HIT, out);
}

// Is a block cell open air, solid, or crossed by scenery? Minecraft lines dug space
// with blocks only where the answer is solid, and treats cells with scenery through
// them as solid until they are dug too. Scenery is a hollow shell, so "solid" means no
// surface runs through the cell and there is no floor anywhere beneath it.
uint8_t classify(int cx, int cy, int cz, CellPlane* plane) {
    const float lo = 2, hi = SCALE - 2;
    *plane = {};
    Vec3f base{ cx * SCALE, cy * SCALE, cz * SCALE };
    bool crossed = false;
    // A 3x3 bundle of lines along each axis, so a surface that only clips a corner counts.
    for (int axis = 0; axis < 3 && !crossed; axis++)
        for (int i = 0; i < 9 && !crossed; i++) {
            float u = lo + (i % 3) * (hi - lo) / 2, v = lo + (i / 3) * (hi - lo) / 2;
            Vec3f a = base, b = base;
            if (axis == 0) a.x += lo, b.x += hi, a.y += u, b.y += u, a.z += v, b.z += v;
            if (axis == 1) a.y += lo, b.y += hi, a.x += u, b.x += u, a.z += v, b.z += v;
            if (axis == 2) a.z += lo, b.z += hi, a.x += u, b.x += u, a.y += v, b.y += v;
            Vec3f hit{};
            CollisionPoly* poly = nullptr;
            s32 bg = 0;
            // Surfaces are one-sided, so look along each line in both directions.
            crossed = BgCheck_EntityLineTest1(&gPlayState->colCtx, &a, &b, &hit, &poly, true, true, true, true, &bg) ||
                      BgCheck_EntityLineTest1(&gPlayState->colCtx, &b, &a, &hit, &poly, true, true, true, true, &bg);
            if (crossed && poly) {
                float nx = poly->normal.x / 32767.0f, ny = poly->normal.y / 32767.0f, nz = poly->normal.z / 32767.0f;
                *plane = { nx, ny, nz, (nx * (hit.x - base.x) + ny * (hit.y - base.y) + nz * (hit.z - base.z)) / SCALE };
            }
        }
    Vec3f centre{ base.x + SCALE / 2, base.y + SCALE / 2, base.z + SCALE / 2 }, top{ centre.x, base.y + SCALE - 1, centre.z };
    CollisionPoly* floor = nullptr;
    s32 bg = 0;
    if (crossed) {
        float h = BgCheck_EntityRaycastFloor3(&gPlayState->colCtx, &floor, &bg, &top);
        if (h <= base.y || h > base.y + SCALE) return CELL_SURFACE_NO_FLOOR;
        return (uint8_t)(CELL_SURFACE_FLOOR + std::clamp((h - base.y) / SCALE, 0.0f, 1.0f) * 252);
    }
    return BgCheck_EntityRaycastFloor3(&gPlayState->colCtx, &floor, &bg, &centre) > -31000 ? CELL_OPEN : CELL_SOLID;
}
void serviceClassify() {
    if (!world()) return;
    auto serial = acquire(shared + CLASSIFY_REQUEST);
    if (serial == acquire(shared + CLASSIFY_REPLY)) return;
    struct Request { uint32_t epoch, count; int32_t cells[CLASSIFY_MAX][3]; } request;
    std::memcpy(&request, shared + CLASSIFY_REQUEST + 4, sizeof(request));
    if (serial != acquire(shared + CLASSIFY_REQUEST)) return;
    uint8_t classes[CLASSIFY_MAX]{};
    CellPlane planes[CLASSIFY_MAX]{};
    if (request.epoch == port.epoch && request.count <= CLASSIFY_MAX) {
        CubeQueryGuard cubes;
        for (uint32_t i = 0; i < request.count; i++) {
            auto& c = request.cells[i];
            bool sane = std::abs(c[0]) < 2000 && std::abs(c[1]) < 2000 && std::abs(c[2]) < 2000;
            classes[i] = sane ? classify(c[0], c[1], c[2], &planes[i]) : CELL_OPEN;
        }
    }
    std::memcpy(shared + CLASSIFY_PLANES, planes, sizeof(planes));
    std::memcpy(shared + CLASSIFY_REPLY + 4, classes, sizeof(classes));
    release(shared + CLASSIFY_REPLY, serial);
}

// Pull Minecraft's third-person camera out of Zelda walls.
void serviceCamera() {
    if (!world()) return;
    auto serial = acquire(shared + CAMERA_QUERY);
    if (serial == acquire(shared + CAMERA_REPLY)) return;
    uint32_t epoch;
    Vec3f eye, end;
    std::memcpy(&epoch, shared + CAMERA_QUERY + 4, 4);
    std::memcpy(&eye, shared + CAMERA_QUERY + 8, 12);
    std::memcpy(&end, shared + CAMERA_QUERY + 20, 12);
    if (serial != acquire(shared + CAMERA_QUERY)) return;
    if (epoch == port.epoch && finite({ eye.x, eye.y, eye.z, end.x, end.y, end.z })) {
        Vec3f hit{};
        CollisionPoly* poly = nullptr;
        s32 bg = 0;
        if (BgCheck_EntityLineTest1(&gPlayState->colCtx, &eye, &end, &hit, &poly, true, true, true, true, &bg)) {
            float dx = eye.x - hit.x, dy = eye.y - hit.y, dz = eye.z - hit.z, len = std::sqrt(dx * dx + dy * dy + dz * dz);
            end = len > 0.1f ? Vec3f{ hit.x + dx / len * 8, hit.y + dy / len * 8, hit.z + dz / len * 8 } : eye;
        }
    } else {
        end = eye;
    }
    std::memcpy(shared + CAMERA_REPLY + 4, &end, 12);
    release(shared + CAMERA_REPLY, serial);
}
void service() {
    readCarved();
    serviceCamera();
    serviceClassify();
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

// Where a Minecraft block may be placed on bare Zelda ground.
void target() {
    port.target = 0;
    if (!live()) return;
    float yaw = mc.yaw * DEGREES, pitch = mc.pitch * DEGREES;
    Vec3f eye{ mc.x, mc.y + avatarEye(), mc.z };
    Vec3f end{ eye.x - std::sin(yaw) * std::cos(pitch) * 200, eye.y - std::sin(pitch) * 200,
               eye.z + std::cos(yaw) * std::cos(pitch) * 200 };
    Vec3f hit{};
    CollisionPoly* poly = nullptr;
    s32 bg = 0;
    if (!BgCheck_EntityLineTest1(&gPlayState->colCtx, &eye, &end, &hit, &poly, true, true, true, true, &bg)) return;
    if (!poly || poly->normal.y < 16000) return;
    float x = std::floor(hit.x / SCALE) * SCALE, z = std::floor(hit.z / SCALE) * SCALE;
    Vec3f ray{ x + 20, hit.y + 25, z + 20 };
    CollisionPoly* floor = nullptr;
    float y = BgCheck_EntityRaycastFloor5(gPlayState, &gPlayState->colCtx, &floor, &bg, &link()->actor, &ray);
    // The floor under the cell's centre may sit well above or below the aimed-at point on a slope.
    if (y < -31000 || std::abs(y - hit.y) > 45) return;
    port.target = 1;
    port.tx = x;
    port.ty = y;
    port.tz = z;
}

void publishEnemies() {
    if (!live() || !world()) return;
    struct Enemy { int32_t id; float x, y, z; uint32_t health; };
    struct Enemies { uint32_t epoch, count; Enemy entries[7]; } out{};
    out.epoch = port.epoch;
    std::vector<Actor*> enemies;
    for (auto* a = gPlayState->actorCtx.actorLists[ACTORCAT_ENEMY].head; a; a = a->next)
        if (a->update) enemies.push_back(a);
    auto distance = [](Actor* a) {
        float x = a->world.pos.x - mc.x, z = a->world.pos.z - mc.z;
        return x * x + z * z;
    };
    std::sort(enemies.begin(), enemies.end(), [&](Actor* a, Actor* b) { return distance(a) < distance(b); });
    for (auto* a : enemies) {
        if (out.count == 7) break;
        out.entries[out.count++] = { a->id, a->world.pos.x, a->world.pos.y, a->world.pos.z, a->colChkInfo.health };
    }
    write(shared, ENEMIES, out);
}
// A 9x9 grid of floor heights so Minecraft drops and mobs near the player have ground.
void publishFloor() {
    if (!live() || !world() || port.frame % 4) return;
    struct Floors { uint32_t epoch; int32_t x, z; float y[81]; } f{};
    f.epoch = port.epoch;
    f.x = (int)std::floor(mc.x / 40) - 4;
    f.z = (int)std::floor(mc.z / 40) - 4;
    // Sample from standing height: a jump must not lift the rays above a ledge and
    // report its top as the floor.
    static float standingY = 0;
    if (mc.grounded || std::abs(mc.y - standingY) > 400) standingY = mc.y;
    for (int i = 0; i < 81; i++) {
        Vec3f ray{ (f.x + i % 9) * 40.0f + 20, standingY + 80, (f.z + i / 9) * 40.0f + 20 };
        CollisionPoly* poly = nullptr;
        s32 bg = 0;
        f.y[i] = BgCheck_EntityRaycastFloor5(gPlayState, &gPlayState->colCtx, &poly, &bg, &link()->actor, &ray);
        if (isBlockCollider(bg)) f.y[i] = -32000;
    }
    write(shared, FLOOR, f);
}

// Zelda fire and explosions that Minecraft should react to (igniting TNT): fire arrows
// in flight, Din's Fire and exploding bombs. Each is a swept sphere from its previous
// position to its current one, so a fast arrow cannot skip over a block between frames.
void publishFire() {
    if (!live() || !world()) return;
    struct Source { float x, y, z, px, py, pz, radius; };
    struct Sources { uint32_t epoch, count; Source entries[3]; } out{};
    static_assert(sizeof(Sources) == 92);
    out.epoch = port.epoch;
    auto add = [&](Actor* a, float radius) {
        if (out.count < 3)
            out.entries[out.count++] = { a->world.pos.x, a->world.pos.y, a->world.pos.z, a->prevPos.x, a->prevPos.y, a->prevPos.z, radius };
    };
    for (auto* a = gPlayState->actorCtx.actorLists[ACTORCAT_ITEMACTION].head; a; a = a->next) {
        if (!a->update) continue;
        if (a->id == ACTOR_EN_ARROW && a->params == ARROW_FIRE && !a->parent) add(a, 30);
        if (a->id == ACTOR_MAGIC_FIRE) add(a, std::max(40.0f, (float)((MagicFire*)a)->collider.dim.radius));
    }
    for (auto* a = gPlayState->actorCtx.actorLists[ACTORCAT_EXPLOSIVE].head; a; a = a->next)
        if (a->update && a->id == ACTOR_EN_BOM && a->params == BOMB_EXPLOSION) add(a, 100);
    write(shared, FIRE, out);
}

// Minecraft weapons hitting Zelda actors.
ColliderCylinder swordColliders[3]{};
ColliderCylinder blastCollider{};
bool swordReady = false;
void combat() {
    if (!live() || !world()) return;
    auto* actor = &link()->actor;
    if (swordReady) {
        bool hit = false;
        for (auto& sword : swordColliders) {
            hit |= (sword.base.atFlags & AT_HIT) != 0;
            sword.base.atFlags &= ~(AT_HIT | AT_BOUNCED);
        }
        if (hit) release(shared + 1480, acquire(shared + 1480) + 1);
    }
    if (!swordReady) {
        static ColliderCylinderInit sword = {
            { COLTYPE_HIT0, AT_ON | AT_TYPE_PLAYER, AC_NONE, OC1_NONE, OC2_NONE, COLSHAPE_CYLINDER },
            { ELEMTYPE_UNK0, { DMG_SLASH_MASTER, 0, 1 }, { 0, 0, 0 }, TOUCH_ON | TOUCH_SFX_NORMAL, BUMP_NONE, OCELEM_NONE },
            { 22, 56, 0, { 0, 0, 0 } },
        };
        static ColliderCylinderInit blast = {
            { COLTYPE_HIT0, AT_ON | AT_TYPE_PLAYER, AC_NONE, OC1_NONE, OC2_NONE, COLSHAPE_CYLINDER },
            { ELEMTYPE_UNK0, { DMG_EXPLOSIVE, 0, 2 }, { 0, 0, 0 }, TOUCH_ON | TOUCH_SFX_NONE, BUMP_NONE, OCELEM_NONE },
            { 100, 200, 0, { 0, 0, 0 } },
        };
        for (auto& collider : swordColliders) {
            Collider_InitCylinder(gPlayState, &collider);
            Collider_SetCylinder(gPlayState, &collider, actor, &sword);
        }
        Collider_InitCylinder(gPlayState, &blastCollider);
        Collider_SetCylinder(gPlayState, &blastCollider, actor, &blast);
        swordReady = true;
    }
    CombatEvent e{};
    if (!read(shared, COMBAT, e) || e.epoch != port.epoch || e.sequence == lastCombat) return;
    lastCombat = e.sequence;
    release(shared + 1472, e.sequence);
    if (!finite({ e.x, e.y, e.z, e.dx, e.dy, e.dz, e.power })) return;
    if (e.kind == 4) {
        // A Minecraft explosion (TNT, creeper): one frame of bomb damage around its centre.
        if (!swordReady || e.power < 1) return;
        float radius = std::min(e.power, 600.0f);
        blastCollider.dim.radius = (s16)radius;
        blastCollider.dim.height = (s16)(radius * 2);
        blastCollider.dim.yShift = 0;
        blastCollider.dim.pos = { (s16)e.x, (s16)(e.y - radius), (s16)e.z };
        blastCollider.base.atFlags = AT_ON | AT_TYPE_PLAYER;
        blastCollider.info.toucherFlags &= ~TOUCH_HIT;
        CollisionCheck_SetAT(gPlayState, &gPlayState->colChkCtx, &blastCollider.base);
        return;
    }
    if (!cameraFree()) return;
    if (std::abs(e.x - mc.x) > 100 || std::abs(e.y - mc.y) > 100 || std::abs(e.z - mc.z) > 100) return;
    if (e.kind == 3) {
        float magnitude = std::sqrt(e.dx * e.dx + e.dy * e.dy + e.dz * e.dz);
        if (magnitude < .05f || magnitude > 10) return;
        s16 yaw = Math_Atan2S(e.dz, e.dx), pitch = Math_Atan2S(std::sqrt(e.dx * e.dx + e.dz * e.dz), -e.dy);
        auto* arrow = (EnArrow*)Actor_Spawn(&gPlayState->actorCtx, gPlayState, ACTOR_EN_ARROW, e.x, e.y, e.z, pitch, yaw,
                                            0, ARROW_NORMAL);
        if (arrow) {
            // Enter free flight directly; the normal setup expects Link to be holding a bow.
            arrow->actionFunc = EnArrow_Fly;
            arrow->unk_210 = arrow->actor.world.pos;
            arrow->timer = 12;
            Actor_SetProjectileSpeed(&arrow->actor, std::clamp(magnitude * 40.0f, 10.0f, 150.0f));
            release(shared + 1484, acquire(shared + 1484) + 1);
            Player_PlaySfx(actor, NA_SE_IT_ARROW_SHOT);
        }
    } else if (e.kind == 1 || e.kind == 2) {
        // Overlapping samples cover the full reach; Zelda takes the maximum, not the sum.
        Vec3f from{ e.x, e.y, e.z }, to{ e.x + e.dx * 110, e.y + e.dy * 110, e.z + e.dz * 110 }, hit{};
        float reach = 85;
        CollisionPoly* poly = nullptr;
        s32 bg = 0;
        if (BgCheck_EntityLineTest1(&gPlayState->colCtx, &from, &to, &hit, &poly, true, true, true, true, &bg)) {
            float dx = hit.x - e.x, dy = hit.y - e.y, dz = hit.z - e.z;
            reach = std::min(reach, std::sqrt(dx * dx + dy * dy + dz * dz) - 24);
        }
        if (reach > 0)
            for (int i = 0; i < 3; i++) {
                float distance = std::min(20.0f + i * 32.5f, reach);
                auto& sword = swordColliders[i];
                sword.dim.pos = { (s16)(e.x + e.dx * distance), (s16)(e.y + e.dy * distance - 36),
                                  (s16)(e.z + e.dz * distance) };
                sword.info.toucher.damage = e.kind == 2 ? 4 : 1;
                sword.base.atFlags = AT_ON | AT_TYPE_PLAYER;
                sword.info.toucherFlags &= ~TOUCH_HIT;
                CollisionCheck_SetAT(gPlayState, &gPlayState->colChkCtx, &sword.base);
                if (distance == reach) break;
            }
        release(shared + 1476, acquire(shared + 1476) + 1);
    }
}

bool isSword(uint32_t item) { return item >= ITEM_SWORD_KOKIRI && item <= ITEM_SWORD_BGS; }
// The held Minecraft item decides what Link has in hand. Ownership is not checked.
void equipHeld(uint32_t held) {
    u8 wanted = isSword(held) ? (u8)held : (u8)ITEM_NONE;
    if (gSaveContext.equips.buttonItems[0] != wanted) {
        gSaveContext.equips.buttonItems[0] = wanted;
        Inventory_ChangeEquipment(EQUIP_TYPE_SWORD, wanted == ITEM_NONE ? EQUIP_VALUE_SWORD_NONE : held - ITEM_SWORD_KOKIRI + 1);
        if (wanted == ITEM_SWORD_BGS) gSaveContext.bgsFlag = 1;
        Player_SetEquipmentData(gPlayState, link());
        if (wanted != ITEM_NONE) Interface_LoadItemIcon1(gPlayState, 0);
    }
    if (held >= ITEM_SWORD_KOKIRI) return;
    // Magic arrows are used through the bow, as when the game equips them to a button.
    u8 button = held == ITEM_ARROW_FIRE ? ITEM_BOW_ARROW_FIRE : held == ITEM_ARROW_ICE ? ITEM_BOW_ARROW_ICE
              : held == ITEM_ARROW_LIGHT ? ITEM_BOW_ARROW_LIGHT : (u8)held;
    if (gSaveContext.equips.buttonItems[1] != button) {
        gSaveContext.inventory.items[gItemSlots[held]] = held;
        gSaveContext.equips.buttonItems[1] = button;
        gSaveContext.equips.cButtonSlots[0] = gItemSlots[held];
        Interface_LoadItemIcon1(gPlayState, 1);
    }
}

void playerRequests() {
    if (control.ageSerial != ageSeen) {
        ageSeen = control.ageSerial;
        if (cameraFree() && (control.age == LINK_AGE_ADULT || control.age == LINK_AGE_CHILD) &&
            (int)control.age != gSaveContext.linkAge)
            SwitchAge();
    }
    if (control.respawnSerial != respawnSeen) {
        respawnSeen = control.respawnSerial;
        if (gPlayState->transitionTrigger == TRANS_TRIGGER_OFF) {
            gPlayState->nextEntranceIndex = gSaveContext.entranceIndex;
            gPlayState->transitionTrigger = TRANS_TRIGGER_START;
            gPlayState->transitionType = TRANS_TYPE_FADE_BLACK;
        }
    }
}

// Publish Zelda state, read Minecraft's controls and feed them to the game as a pad.
void bridgePlay() {
    auto* p = link();
    // Minecraft hearts are the only health: Zelda's stay full and hits are forwarded.
    gSaveContext.healthCapacity = gSaveContext.health = 20 * 0x10;
    bool free = cameraFree();
    if (live() && free) {
        p->actor.prevPos = p->actor.world.pos;
        p->actor.world.pos = { mc.x, mc.y, mc.z };
        p->actor.velocity = { 0, 0, 0 };
        p->fallStartHeight = (s16)mc.y;
        p->fallDistance = 0;
        p->actor.shape.rot.y = (s16)(-mc.yaw * BINANG);
    }
    port.x = p->actor.world.pos.x;
    port.y = p->actor.world.pos.y;
    port.z = p->actor.world.pos.z;
    auto mode = gPlayState->msgCtx.msgMode;
    storyPort.flags = FLAG_ACTIVE;
    if (gPlayState->msgCtx.msgLength) storyPort.flags |= FLAG_DIALOGUE;
    if (gPlayState->pauseCtx.state) storyPort.flags |= FLAG_PAUSE;
    if (nativeOwnsMotion()) storyPort.flags |= FLAG_SCRIPTED;
    if (mode >= MSGMODE_OCARINA_STARTING && mode < MSGMODE_TEXT_DONE) storyPort.flags |= FLAG_INSTRUMENT;
    if (p->unk_6AD != 0) storyPort.flags |= FLAG_AIMING;
    storyPort.yaw = -(float)p->actor.shape.rot.y / BINANG;
    std::memset(storyPort.items, 255, sizeof(storyPort.items));
    std::memcpy(storyPort.items, gSaveContext.inventory.items, 24);
    std::memcpy(storyPort.ammo, gSaveContext.inventory.ammo, 16);
    storyPort.sword = gSaveContext.equips.buttonItems[0];
    write(shared, STORY_PORT, storyPort);
    Avatar avatar{ port.epoch, (uint32_t)gSaveContext.linkAge, bodyRadius(), avatarHeight(), avatarEye(), p->currentMask, storyPort.flags };
    write(shared, AVATAR, avatar);
    Status status{ port.epoch, damageTotal, gSaveContext.rupees, gSaveContext.magic, gSaveContext.magicCapacity,
                   (uint32_t)gSaveContext.linkAge, storyPort.flags, 0 };
    write(shared, STATUS, status);

    StoryMinecraft next{};
    if (read(shared, STORY_MC, next) && next.epoch == port.epoch && next.selected < 9) storyMC = next;
    Control nextControl{};
    if (read(shared, CONTROL, nextControl) && nextControl.epoch == port.epoch) control = nextControl;
    else control.heldItem = ITEM_NONE;
    uint32_t b = live() ? storyMC.controls : 0, press = b & ~previousControls;
    previousControls = b;
    uint32_t held = live() ? control.heldItem : (uint32_t)ITEM_NONE;
    bool nativeUI = (storyPort.flags & (FLAG_DIALOGUE | FLAG_PAUSE)) != 0;
    if (live() && free) {
        equipHeld(held);
        playerRequests();
    }
    if (live() && (control.flags & CONTROL_CREATIVE)) {
        // Creative: nothing runs out.
        gSaveContext.isMagicAcquired = 1;
        if (gSaveContext.magicCapacity < 0x30) gSaveContext.magicLevel = 1, gSaveContext.magicCapacity = 0x30;
        if (gSaveContext.magicState == MAGIC_STATE_IDLE) gSaveContext.magic = gSaveContext.magicCapacity;
        for (int slot : { SLOT_STICK, SLOT_NUT, SLOT_BOMB, SLOT_BOW, SLOT_SLINGSHOT, SLOT_BOMBCHU })
            gSaveContext.inventory.ammo[slot] = std::max<s8>(gSaveContext.inventory.ammo[slot], 20);
    }
    u16 pad = 0;
    if (b & KEY_SHIFT) pad |= BTN_R;
    if (b & KEY_CTRL) pad |= BTN_Z;
    if (b & KEY_A) pad |= BTN_A;
    if (b & KEY_B) pad |= BTN_B;
    if (b & KEY_START) pad |= BTN_START;
    if (b & KEY_CUP) pad |= BTN_CUP;
    if (b & KEY_CDOWN) pad |= BTN_CDOWN;
    if (b & KEY_CLEFT) pad |= BTN_CLEFT;
    if (b & KEY_CRIGHT) pad |= BTN_CRIGHT;
    if ((b & KEY_JUMP) && (nativeUI || (storyPort.flags & FLAG_INSTRUMENT))) pad |= BTN_A;
    // A held Zelda item: attack swings a sword, use fires or raises whatever is in hand.
    if (b & KEY_ATTACK) pad |= BTN_B;
    if (b & KEY_USE) pad |= isSword(held) ? BTN_R : held < ITEM_SWORD_KOKIRI ? BTN_CLEFT : BTN_A;
    if ((press & KEY_OCARINA) && free) {
        equipHeld(ITEM_OCARINA_TIME);
        pad |= BTN_CLEFT;
    }
    instrumentInput.store((SDL_GetTicks64() << 16) | pad, std::memory_order_release);

    auto& input = gPlayState->state.input[0];
    input.prev = input.cur;
    input.prev.button = previousPad;
    input.cur.button = pad;
    input.cur.stick_x = ((b & KEY_RIGHT) ? 80 : 0) - ((b & KEY_LEFT) ? 80 : 0);
    input.cur.stick_y = ((b & KEY_FORWARD) ? 80 : 0) - ((b & KEY_BACK) ? 80 : 0);
    if (input.cur.stick_x && input.cur.stick_y) {
        input.cur.stick_x = input.cur.stick_x * 71 / 100;
        input.cur.stick_y = input.cur.stick_y * 71 / 100;
    }
    input.press.button = pad & ~previousPad;
    input.rel.button = previousPad & ~pad;
    input.press.stick_x = input.cur.stick_x - input.prev.stick_x;
    input.press.stick_y = input.cur.stick_y - input.prev.stick_y;
    PadUtils_UpdateRelXY(&input);
    previousPad = pad;

    BlockLayer nextBlocks{};
    if (live() && read(shared, BLOCKS, nextBlocks) && nextBlocks.epoch == port.epoch && nextBlocks.count <= MAX_BLOCKS) {
        bool valid = true;
        for (uint32_t i = 0; i < nextBlocks.count; i++)
            valid &= finite({ nextBlocks.positions[i].x, nextBlocks.positions[i].y, nextBlocks.positions[i].z });
        if (valid) blocks = nextBlocks;
    }
    updateBlockColliders();
}

void pump() {
    if (!shared) return;
    if (!active && world() && gSaveContext.gameMode == GAMEMODE_NORMAL) start();
    if (active && !world()) stop();
    service();
    if (active) {
        Minecraft candidate{};
        if (read(shared, MC, candidate) && candidate.epoch == port.epoch && candidate.tick != lastTick &&
            finite({ candidate.x, candidate.y, candidate.z, candidate.yaw, candidate.pitch })) {
            mc = candidate;
            lastTick = mc.tick;
            lastMinecraft = SDL_GetTicks64();
        }
        bridgePlay();
        target();
    }
    port.active = active ? 2 : 0;
    port.buttons = 0;
    port.scene = world() ? gPlayState->sceneNum : 0xFFFFFFFF;
    release(shared + 20, gSaveContext.gameMode);
    publishFloor();
    publishDigHit();
    publishEnemies();
    publishFire();
    port.frame++;
    write(shared, PORT, port);
}

void bootIntoGame(void* gameState) {
    static bool booted = false;
    if (booted) return;
    booted = true;
    auto* state = (GameState*)gameState;
    // The engine's home directory belongs to one Minecraft world, so file 1 there is
    // that world's Zelda save: continue it, or start it with everything unlocked.
    gSaveContext.gameMode = GAMEMODE_NORMAL;
    const bool continuing = SaveManager::Instance->SaveFile_Exist(0);
    if (continuing) {
        gSaveContext.fileNum = 0;
        Sram_OpenSave();
    } else {
        gSaveContext.fileNum = 0xFE;
        Sram_InitDebugSave();
        gSaveContext.fileNum = 0;
        gSaveContext.linkAge = LINK_AGE_ADULT;
        gSaveContext.nightFlag = 0;
        gSaveContext.skyboxTime = gSaveContext.dayTime = 0x8000;
    }
    gSaveContext.sceneLayer = 0;
    gSaveContext.cutsceneIndex = 0;
    for (auto& status : gSaveContext.buttonStatus) status = BTN_ENABLED;
    gSaveContext.nextHudVisibilityMode = gSaveContext.hudVisibilityMode = gSaveContext.hudVisibilityModeTimer = 0;
    gSaveContext.forceRisingButtonAlphas = 0;
    const char* entrance = std::getenv("COMPOSITE_START");
    if (entrance) gSaveContext.entranceIndex = (s32)std::strtol(entrance, nullptr, 0);
    else if (!continuing) gSaveContext.entranceIndex = ENTR_KOKIRI_FOREST_0;
    saveWhenReady = !continuing;
    gSaveContext.seqId = (u8)NA_BGM_DISABLED;
    gSaveContext.natureAmbienceId = 0xFF;
    gSaveContext.showTitleCard = false;
    state->running = false;
    SET_NEXT_GAMESTATE(state, Play_Init, PlayState);
    GameInteractor_ExecuteOnLoadGame(gSaveContext.fileNum);
}

void init() {
    const char* path = std::getenv("COMPOSITE_SHM");
    if (!path) return;
    int descriptor = open(path, O_RDWR | O_CREAT | O_NOFOLLOW, 0600);
    if (descriptor < 0 || flock(descriptor, LOCK_EX | LOCK_NB) != 0) {
        spdlog::error("[Composite] Shared memory already in use or unavailable");
        std::exit(73);
    }
    struct stat st {};
    fstat(descriptor, &st);
    if (st.st_uid != getuid() || !S_ISREG(st.st_mode) || ftruncate(descriptor, SIZE) != 0) {
        spdlog::error("[Composite] Unsafe shared memory file");
        return;
    }
    shared = (uint8_t*)mmap(nullptr, SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, descriptor, 0);
    if (shared == MAP_FAILED) {
        shared = nullptr;
        return;
    }
    CVarSetInteger("gVsyncEnabled", 0);
    CVarSetInteger(CVAR_SETTING("MatchRefreshRate"), 0);
    CVarSetInteger(CVAR_SETTING("InterpolationFPS"), 60);
    // Aiming and targeting add a cinematic letterbox that would cover Minecraft's view.
    CVarSetInteger(CVAR_ENHANCEMENT("DisableBlackBars"), 1);
    // Progress is written to the world's save as it happens; there is no file select to return to.
    CVarSetInteger(CVAR_ENHANCEMENT("Autosave"), 1);
    port.epoch = (uint32_t)getpid();
    std::memset(shared, 0, SIZE);
    release(shared, MAGIC);
    release(shared + 4, VERSION);
    auto* gi = GameInteractor::Instance;
    gi->RegisterGameHook<GameInteractor::OnZTitleUpdate>(bootIntoGame);
    gi->RegisterGameHook<GameInteractor::OnSceneInit>([](int16_t) { sceneReady = true; });
    gi->RegisterGameHook<GameInteractor::OnPlayDestroy>([] {
        blocks = {};
        updateBlockColliders();
        swordReady = false;
        sceneReady = false;
        stop();
    });
    gi->RegisterGameHook<GameInteractor::OnGameStateMainStart>(pump);
    gi->RegisterGameHook<GameInteractor::OnPlayerUpdate>([] {
        if (live() && cameraFree()) link()->actor.world.pos = { mc.x, mc.y, mc.z };
        combat();
    });
    gi->RegisterGameHook<GameInteractor::OnPlayerHealthChange>([](int16_t amount) {
        if (amount < 0 && live()) damageTotal += (uint32_t)-amount;
    });
    spdlog::info("[Composite] Bridge ready");
}
static RegisterShipInitFunc registration(init);
} // namespace

extern "C" bool CompositePollCollision() {
    if (!shared) return false;
    release(shared + 56, acquire(shared + 56) + 1);
    service();
    return true;
}
// Experimental: with COMPOSITE_GUEST set, this game draws only Link and its actors, so
// its picture can be laid over another game's world.
extern "C" int CompositeGuest() {
    static const bool guest = std::getenv("COMPOSITE_GUEST") != nullptr;
    return guest;
}
extern "C" int CompositeOcarinaInput(u16* pad) {
    const auto snapshot = instrumentInput.load(std::memory_order_acquire);
    if (!snapshot) return 0;
    *pad = SDL_GetTicks64() - (snapshot >> 16) < 250 ? (u16)snapshot : 0;
    return 1;
}
// While Link aims an item in first person the game draws just his arms and the item,
// which stands in for Minecraft's held-item view.
extern "C" int CompositeFirstPersonArms() {
    return shared && live() && storyMC.thirdPerson == 0 && cameraFree() && link()->unk_6AD != 0;
}
// Minecraft's camera is at the eyes; the game's own aiming camera sits a little behind
// them. Drawing Link slightly ahead gives the same view of his arms without moving the
// camera Minecraft's blocks are registered to. COMPOSITE_ARMS tunes it: "forward,up".
extern "C" void CompositeArmsOffset(Vec3f* offset) {
    if (!CompositeFirstPersonArms()) return;
    static float forward = 18, up = -4;
    static bool parsed = false;
    if (!parsed) {
        parsed = true;
        if (const char* tune = std::getenv("COMPOSITE_ARMS")) std::sscanf(tune, "%f,%f", &forward, &up);
    }
    float yaw = mc.yaw * DEGREES, pitch = mc.pitch * DEGREES;
    offset->x = -std::sin(yaw) * std::cos(pitch) * forward;
    offset->y = -std::sin(pitch) * forward + up;
    offset->z = std::cos(yaw) * std::cos(pitch) * forward;
}
extern "C" int CompositeHidePlayer() {
    return shared && live() && storyMC.thirdPerson == 0 && cameraFree() && link()->unk_6AD == 0;
}
extern "C" void CompositePlayerInput(Player* player, Input*) {
    if (!shared || !live() || !cameraFree()) return;
    s16 yaw = (s16)(-mc.yaw * BINANG);
    player->actor.world.rot.y = player->actor.shape.rot.y = player->yaw = yaw;
    player->actor.focus.rot.y = yaw;
    player->actor.focus.rot.x = (s16)(mc.pitch * BINANG);
    if (mc.grounded) player->actor.bgCheckFlags |= BGCHECKFLAG_GROUND;
    else player->actor.bgCheckFlags &= ~BGCHECKFLAG_GROUND;
}
// Minecraft's camera drives culling, audio and anything else that reads the game camera.
extern "C" void CompositeCamera(Camera* c) {
    if (!shared || !live() || !cameraFree() || c != GET_ACTIVE_CAM(gPlayState)) return;
    NativeCamera v{};
    if (!read(shared, CAMERA, v) || v.epoch != port.epoch || !v.valid || !std::isfinite(v.fov) || v.fov < 20 || v.fov > 150) return;
    if (!finite({ v.x, v.y, v.z, v.yaw, v.pitch })) return;
    float yaw = v.yaw * DEGREES, pitch = v.pitch * DEGREES;
    c->eye = { v.x, v.y, v.z };
    c->eyeNext = c->eye;
    c->at = { v.x - std::sin(yaw) * std::cos(pitch) * 100, v.y - std::sin(pitch) * 100, v.z + std::cos(yaw) * std::cos(pitch) * 100 };
    c->up = { 0, 1, 0 };
    c->fov = v.fov;
    c->roll = 0;
    c->inputDir.y = c->camDir.y = (s16)(-v.yaw * BINANG);
    gPlayState->view.fovy = v.fov;
    func_800AA358(&gPlayState->view, &c->eye, &c->at, &c->up);
}
#include "FrameExport.h"

// Late-latch the world view for every displayed (including interpolated) frame so
// Zelda's image and Minecraft's blocks share exactly one camera.
void CompositeLateCamera(std::unordered_map<Mtx*, MtxF>& replacements) {
    renderedCamera = {};
    renderedNear = renderedFar = 0;
    if (!shared || !world()) return;
    renderedNear = gPlayState->view.zNear;
    renderedFar = gPlayState->view.zFar;
    auto& lights = gPlayState->envCtx.lightSettings;
    float brightness = 0;
    for (int i = 0; i < 3; i++) brightness += std::min(255, (int)lights.ambientColor[i] + (int)lights.light1Color[i]);
    renderedCamera.light = std::clamp(brightness / (3 * 255.0f), .12f, 1.0f);
    if (!live() || !cameraFree()) return;
    NativeCamera v{};
    if (!read(shared, CAMERA, v) || v.epoch != port.epoch || !v.valid || v.fov < 20 || v.fov > 150) return;
    if (!finite({ v.x, v.y, v.z, v.yaw, v.pitch, v.fov })) return;
    auto& view = gPlayState->view;
    if (!view.viewingPtr || !view.projectionPtr) return;
    const float yaw = v.yaw * DEGREES, pitch = v.pitch * DEGREES;
    MtxF look{}, projection{};
    u16 norm;
    guLookAtF((float (*)[4]) & look, v.x, v.y, v.z, v.x - std::sin(yaw) * std::cos(pitch) * 100, v.y - std::sin(pitch) * 100,
              v.z + std::cos(yaw) * std::cos(pitch) * 100, 0, 1, 0);
    // The game clips away anything within a quarter of a block of the camera, which up
    // close reads as seeing through nearby walls and ground. Clip as close as Minecraft does.
    const float nearPlane = 0.05f * SCALE;
    guPerspectiveF((float (*)[4]) & projection, &norm, v.fov,
                   (float)(view.viewport.rightX - view.viewport.leftX) / (view.viewport.bottomY - view.viewport.topY),
                   nearPlane, view.zFar, view.scale);
    renderedNear = nearPlane;
    replacements[view.viewingPtr] = look;
    replacements[view.projectionPtr] = projection;
    renderedCamera = { v.epoch, v.x, v.y, v.z, v.yaw, v.pitch, v.fov, 1, acquire(shared + CAMERA), port.scene, renderedCamera.light };
}
