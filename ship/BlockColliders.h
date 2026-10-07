// Nearby full Minecraft cubes mirrored into Zelda as dynamic colliders, so native
// actors and projectiles are stopped by blocks. Included in the bridge namespace.
DynaPolyActor cubeActors[MAX_BLOCKS]{};
s32 cubeBg[MAX_BLOCKS];
bool cubesInitialized = false;
Vec3s cubeVertices[8] = { { 0, 0, 0 }, { 40, 0, 0 }, { 40, 40, 0 }, { 0, 40, 0 }, { 0, 0, 40 }, { 40, 0, 40 }, { 40, 40, 40 }, { 0, 40, 40 } };
CollisionPoly cubePolys[12]{};
SurfaceType cubeSurface[1]{};
CamData cubeCam[1]{};
CollisionHeader cubeCollision{};

bool isBlockCollider(s32 bg) {
    if (!cubesInitialized) return false;
    for (int i = 0; i < MAX_BLOCKS; i++)
        if (cubeBg[i] != BG_ACTOR_MAX && cubeBg[i] == bg) return true;
    return false;
}
void updateBlockColliders() {
    if (!cubesInitialized) {
        std::fill(std::begin(cubeBg), std::end(cubeBg), BG_ACTOR_MAX);
        cubesInitialized = true;
    }
    if (!gPlayState) return;
    for (int i = 0; i < MAX_BLOCKS; i++)
        if (i >= (int)blocks.count && cubeBg[i] != BG_ACTOR_MAX) {
            DynaPoly_DeleteBgActor(gPlayState, &gPlayState->colCtx.dyna, cubeBg[i]);
            cubeBg[i] = BG_ACTOR_MAX;
        }
    if (!blocks.count) return;
    static const int faces[6][4] = { { 0, 3, 2, 1 }, { 4, 5, 6, 7 }, { 0, 4, 7, 3 }, { 1, 2, 6, 5 }, { 3, 7, 6, 2 }, { 0, 1, 5, 4 } };
    static const Vec3s normals[6] = { { 0, 0, -32767 }, { 0, 0, 32767 }, { -32767, 0, 0 }, { 32767, 0, 0 }, { 0, 32767, 0 }, { 0, -32767, 0 } };
    for (int f = 0; f < 6; f++)
        for (int t = 0; t < 2; t++) {
            auto& p = cubePolys[f * 2 + t];
            p = {};
            p.vtxData[0] = faces[f][0];
            p.vtxData[1] = faces[f][t ? 2 : 1];
            p.vtxData[2] = faces[f][t ? 3 : 2];
            p.normal = normals[f];
            p.dist = (f == 1 || f == 3 || f == 4) ? -40 : 0;
        }
    cubeCollision = {};
    cubeCollision.minBounds = { 0, 0, 0 };
    cubeCollision.maxBounds = { 40, 40, 40 };
    cubeCollision.numVertices = 8;
    cubeCollision.vtxList = cubeVertices;
    cubeCollision.numPolygons = 12;
    cubeCollision.polyList = cubePolys;
    cubeCollision.surfaceTypeList = cubeSurface;
    cubeCollision.cameraDataList = cubeCam;
    cubeCollision.cameraDataListLen = 1;
    for (uint32_t i = 0; i < blocks.count; i++) {
        auto& actor = cubeActors[i];
        auto pos = blocks.positions[i];
        if (cubeBg[i] != BG_ACTOR_MAX) {
            actor.actor.world.pos = { pos.x, pos.y, pos.z };
            continue;
        }
        actor = {};
        DynaPolyActor_Init(&actor, DPM_UNK);
        actor.actor.id = -1;
        actor.actor.scale = { 1, 1, 1 };
        actor.actor.world.pos = { pos.x, pos.y, pos.z };
        actor.actor.update = [](Actor*, PlayState*) {};
        cubeBg[i] = DynaPoly_SetBgActor(gPlayState, &gPlayState->colCtx.dyna, &actor.actor, &cubeCollision);
        actor.bgId = cubeBg[i];
    }
}
// Minecraft resolves its own block shapes for the player; hide the mirrors from that query.
struct CubeQueryGuard {
    CubeQueryGuard() {
        for (uint32_t j = 0; j < blocks.count; j++)
            if (cubeBg[j] != BG_ACTOR_MAX) func_8003EBF8(gPlayState, &gPlayState->colCtx.dyna, cubeBg[j]);
    }
    ~CubeQueryGuard() {
        for (uint32_t j = 0; j < blocks.count; j++)
            if (cubeBg[j] != BG_ACTOR_MAX) func_8003EC50(gPlayState, &gPlayState->colCtx.dyna, cubeBg[j]);
    }
};
