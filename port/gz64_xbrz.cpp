// port/gz64_xbrz.cpp -- G-Zero 64: xBRZ upscaling of the game's decoded textures (Android).
//
// Called by the renderer (android/patches/libultraship/0005-gz64-xbrz.patch) for every texture it
// decodes from the game's own data, while gGZ64.Xbrz holds a factor of 2-4. Textures are cached
// after decoding, so each one is scaled once. The source is padded with its own wrapped edges so
// tiling textures (roads, walls) stay seamless.
#include <algorithm>
#include <cstdint>
#include <vector>

#include "third_party/xbrz/xbrz.h"

namespace {

// Large images (backgrounds, framebuffer copies) gain little and cost the most.
constexpr uint32_t kMaxSourcePixels = 128 * 128;
constexpr uint32_t kMaxOutputSide = 2048;
constexpr int kPad = 2; // rows/columns xBRZ reads around each pixel

// RGBA bytes <-> xBRZ's 0xAARRGGBB.
uint32_t ToArgb(const uint8_t* p) {
    return (uint32_t(p[3]) << 24) | (uint32_t(p[0]) << 16) | (uint32_t(p[1]) << 8) | p[2];
}

} // namespace

extern "C" const uint8_t* gz64_xbrz_scale(const uint8_t* rgba32, uint32_t width, uint32_t height, int factor,
                                          uint32_t* outWidth, uint32_t* outHeight) {
    if (rgba32 == nullptr || factor < 2 || factor > 6 || width < 2 || height < 2 ||
        width * height > kMaxSourcePixels || width * factor > kMaxOutputSide || height * factor > kMaxOutputSide) {
        return nullptr;
    }
    // Renderer thread only, so one set of buffers serves every call.
    static std::vector<uint32_t> source;
    static std::vector<uint32_t> scaled;
    static std::vector<uint8_t> result;

    const int paddedW = static_cast<int>(width) + 2 * kPad;
    const int paddedH = static_cast<int>(height) + 2 * kPad;
    source.resize(static_cast<size_t>(paddedW) * paddedH);
    for (int y = 0; y < paddedH; ++y) {
        const uint32_t sy = static_cast<uint32_t>(y - kPad + static_cast<int>(height)) % height;
        for (int x = 0; x < paddedW; ++x) {
            const uint32_t sx = static_cast<uint32_t>(x - kPad + static_cast<int>(width)) % width;
            source[static_cast<size_t>(y) * paddedW + x] = ToArgb(rgba32 + (static_cast<size_t>(sy) * width + sx) * 4);
        }
    }

    const int scaledW = paddedW * factor;
    scaled.resize(static_cast<size_t>(scaledW) * paddedH * factor);
    xbrz::scale(static_cast<size_t>(factor), source.data(), scaled.data(), paddedW, paddedH, xbrz::ColorFormat::ARGB);

    const uint32_t w = width * factor;
    const uint32_t h = height * factor;
    result.resize(static_cast<size_t>(w) * h * 4);
    const int offset = kPad * factor;
    for (uint32_t y = 0; y < h; ++y) {
        const uint32_t* row = scaled.data() + static_cast<size_t>(y + offset) * scaledW + offset;
        uint8_t* out = result.data() + static_cast<size_t>(y) * w * 4;
        for (uint32_t x = 0; x < w; ++x) {
            const uint32_t argb = row[x];
            out[x * 4 + 0] = static_cast<uint8_t>(argb >> 16);
            out[x * 4 + 1] = static_cast<uint8_t>(argb >> 8);
            out[x * 4 + 2] = static_cast<uint8_t>(argb);
            out[x * 4 + 3] = static_cast<uint8_t>(argb >> 24);
        }
    }
    *outWidth = w;
    *outHeight = h;
    return result.data();
}
