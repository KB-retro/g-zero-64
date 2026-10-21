// port/gz64_htc.h -- G-Zero 64: hi-res texture packs in the emulators' .htc format (Android).
#pragma once

#include <cstdint>

// What the renderer knows about a texture it is about to decode. Keep in sync with the copy in
// android/patches/libultraship/0006-gz64-htc.patch.
struct Gz64HtcQuery {
    const uint8_t* addr;          // first texel, N64 byte order
    uint32_t fmt, siz;            // tile format / size (G_IM_FMT_*, G_IM_SIZ_*)
    uint32_t tlut;                // other-mode TLUT enabled
    uint32_t palette;             // tile palette (CI4)
    const uint8_t* palettes[2];   // TLUT halves, 128 big-endian entries each
    uint32_t sizeBytes;           // loaded bytes
    uint32_t lineBytes;           // loaded bytes per row
    uint32_t fullLineBytes;       // bytes per row of the source image
    uint32_t tileLineBytes;       // tile line (TMEM words * 8)
    uint32_t tileWidth, tileHeight;
    uint32_t masks, maskt, cms, cmt;
    uint32_t width, height;       // what the decoder would upload
};

extern "C" {
// True while the option is on and a pack is (being) loaded.
int gz64_htc_active(void);
// Bumped when a pack finishes loading or the option changes: the renderer then drops its cache.
uint32_t gz64_htc_generation(void);
// The hi-res replacement as RGBA8, or nullptr.
const uint8_t* gz64_htc_find(const Gz64HtcQuery* query, uint32_t* outWidth, uint32_t* outHeight);
}

// One line for the menu: pack name, state, textures found so far.
const char* Gz64HtcStatus();
