// Frame transport to Minecraft: colour, the camera it was rendered with, and depth.
// Called only on SDL's GL render thread, just before the swap. Included by the bridge.
//
// Layout (little endian): 64-byte header {seqlock, magic, width, height, pid, camera},
// then bottom-up RGBA8 pixels, then one float per pixel of depth. Depth is already in
// Minecraft's convention (reversed: 1 at its near plane, 0 at infinity), so Minecraft
// can write it straight into its depth buffer and its blocks are hidden behind
// Zelda's walls and ground. Colour, depth and camera share one publication lock.
#include <vector>

namespace {
constexpr uint32_t FRAME_MAGIC = 0x46524D32;
constexpr int FRAME_HEADER = 64, FRAME_MAX_WIDTH = 2560, FRAME_MAX_HEIGHT = 1440;
constexpr float MINECRAFT_NEAR = 0.05f;

// Zelda's window depth (0 near, 1 far, perspective) to Minecraft's reversed depth.
void convertDepth(float* depth, size_t count, float zeldaNear, float zeldaFar, float minecraftFar) {
    // 1/distance in blocks is linear in window depth: (1-d)/near + d/far, at 40 units per block.
    const float base = composite::SCALE / zeldaNear, slope = composite::SCALE / zeldaFar - base;
    const float scale = MINECRAFT_NEAR * minecraftFar / (minecraftFar - MINECRAFT_NEAR);
    const float offset = MINECRAFT_NEAR / (minecraftFar - MINECRAFT_NEAR);
    for (size_t i = 0; i < count; i++) {
        float d = depth[i];
        // Untouched pixels are sky: infinitely far, so every block shows in front of them.
        depth[i] = d >= 0.9999999f ? 0.0f : std::clamp(scale * (base + d * slope) - offset, 0.0f, 1.0f);
    }
}
} // namespace

extern "C" void CompositeExportFrame(SDL_Window* win) {
    const char* base = std::getenv("COMPOSITE_FRAME");
    if (!base) return;
    constexpr size_t capacity = FRAME_HEADER + (size_t)FRAME_MAX_WIDTH * FRAME_MAX_HEIGHT * 8;
    static uint8_t* mem = nullptr;
    static bool failed = false;
    static std::vector<uint8_t> pixels;
    static std::vector<float> depth;
    if (failed) return;
    if (!mem) {
        int fd = open(base, O_RDWR | O_CREAT | O_NOFOLLOW, 0600);
        struct stat st {};
        if (fd < 0 || fstat(fd, &st) || st.st_uid != getuid() || !S_ISREG(st.st_mode) || flock(fd, LOCK_EX | LOCK_NB) ||
            ftruncate(fd, capacity)) {
            if (fd >= 0) close(fd);
            failed = true;
            return;
        }
        auto p = mmap(nullptr, capacity, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        if (p == MAP_FAILED) {
            close(fd);
            failed = true;
            return;
        }
        mem = (uint8_t*)p;
        std::memset(mem, 0, FRAME_HEADER);
    }
    int w, h;
    SDL_GL_GetDrawableSize(win, &w, &h);
    if (w <= 0 || h <= 0 || w > FRAME_MAX_WIDTH || h > FRAME_MAX_HEIGHT) return;
    // GLX defers default backbuffer allocation across window resizes until a swap.
    // Reading the stale drawable can fault in NVIDIA's driver. Present once per
    // size change, then capture the next fully rendered frame at that size.
    static int drawableWidth = 0, drawableHeight = 0;
    if (w != drawableWidth || h != drawableHeight) {
        SDL_GL_SwapWindow(win);
        drawableWidth = w;
        drawableHeight = h;
        return;
    }
    using Read = void (*)(int, int, int, int, unsigned, unsigned, void*);
    using Get = void (*)(unsigned, int*);
    using Bind = void (*)(unsigned, unsigned);
    using Pixel = void (*)(unsigned, int);
    static auto read = (Read)SDL_GL_GetProcAddress("glReadPixels");
    static auto get = (Get)SDL_GL_GetProcAddress("glGetIntegerv");
    static auto bind = (Bind)SDL_GL_GetProcAddress("glBindFramebuffer");
    static auto pixel = (Pixel)SDL_GL_GetProcAddress("glPixelStorei");
    if (!read || !get || !bind || !pixel) return;
    constexpr unsigned READ_FRAMEBUFFER_BINDING = 0x8CAA, READ_FRAMEBUFFER = 0x8CA8, PACK_ALIGNMENT = 0x0D05,
                       RGBA = 0x1908, UNSIGNED_BYTE = 0x1401, DEPTH_COMPONENT = 0x1902, FLOAT = 0x1406;
    const size_t count = (size_t)w * h;
    int oldFramebuffer = 0, oldAlignment = 0;
    get(READ_FRAMEBUFFER_BINDING, &oldFramebuffer);
    get(PACK_ALIGNMENT, &oldAlignment);
    bind(READ_FRAMEBUFFER, 0);
    pixel(PACK_ALIGNMENT, 1);
    pixels.resize(count * 4);
    depth.resize(count);
    read(0, 0, w, h, RGBA, UNSIGNED_BYTE, pixels.data());
    read(0, 0, w, h, DEPTH_COMPONENT, FLOAT, depth.data());
    bind(READ_FRAMEBUFFER, oldFramebuffer);
    pixel(PACK_ALIGNMENT, oldAlignment);

    // Minecraft publishes its far plane; until it has, mark everything as far away.
    float minecraftFar = 0;
    if (shared) std::memcpy(&minecraftFar, shared + 60, 4);
    if (std::isfinite(minecraftFar) && minecraftFar > 1 && renderedNear > 0 && renderedFar > renderedNear)
        convertDepth(depth.data(), count, renderedNear, renderedFar, minecraftFar);
    else
        std::fill(depth.begin(), depth.end(), 0.0f);

    auto seq = composite::acquire(mem);
    composite::release(mem, seq + 1);
    __atomic_thread_fence(__ATOMIC_SEQ_CST);
    uint32_t header[] = { FRAME_MAGIC, (uint32_t)w, (uint32_t)h, (uint32_t)getpid() };
    std::memcpy(mem + 4, header, sizeof(header));
    std::memcpy(mem + 20, &renderedCamera, sizeof(renderedCamera));
    std::memcpy(mem + FRAME_HEADER, pixels.data(), count * 4);
    std::memcpy(mem + FRAME_HEADER + count * 4, depth.data(), count * 4);
    composite::release(mem, seq + 2);
}
extern "C" int CompositeHideNativeHud() { return shared != nullptr; }
