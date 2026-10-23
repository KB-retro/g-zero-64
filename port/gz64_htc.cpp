// port/gz64_htc.cpp -- G-Zero 64: hi-res texture packs in the emulators' .htc format (Android).
//
// An .htc is the texture cache GLideN64 / GlideHQ build from a hi-res pack: one gzip stream of
// {64-bit key, size, pixel format, pixels} entries. The key is the "Rice CRC" of the texture's
// bytes as the N64 holds them in RDRAM (plus the CRC of its palette for CI textures), so the pack
// works for any port that can hash the same bytes the same way.
//
// gGZ64.HiresPack (on by default) switches it on; the newest .htc found in <app>/hires (or <app>/mods) is read once
// on a background thread into <app>/cache/htc (payloads plus an index), and later boots reuse that
// cache. The renderer (android/patches/libultraship/0006-gz64-htc.patch) asks gz64_htc_find for
// every texture it is about to decode and uploads the hi-res image instead when the pack has it.
#include "gz64_htc.h"

#include <zlib.h>

#include <sys/stat.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include "libultraship/bridge/consolevariablebridge.h"
#include "gdx_i18n.h"
#include "port_log.h"

namespace {

namespace fs = std::filesystem;

// ── Pack format ─────────────────────────────────────────────────────────────────────────────────

// Entry layouts after the 64-bit key and the 32-bit width and height.
enum Layout : uint32_t {
    kLayoutGl = 1,     // GLideN64: u32 GL internal format, u16 format, u16 type, u8 hires, u32 size
    kLayoutGlNoHi = 2, // the same without the hires byte
    kLayoutGlide = 3,  // GlideHQ / Glide64: u16 Glide format, 6 x i32 lod/tiling, u8 hires, u32 size
};

struct Entry {
    uint64_t offset; // in the payload cache file
    uint32_t size;
    uint32_t width, height;
    uint32_t format;      // GL internal format or Glide format, compression flag included
    uint16_t texFormat;   // GL only
    uint16_t pixelType;   // GL only
};

constexpr uint32_t kGlCompressed = 0x80000000u;
constexpr uint32_t kGlideCompressed = 0x8000u;

// GL enums the packs use.
constexpr uint16_t kGlUnsignedByte = 0x1401, kGlRgba = 0x1908, kGlRgb = 0x1907, kGlBgra = 0x80E1,
                   kGlLuminance = 0x1909, kGlLuminanceAlpha = 0x190A, kGlAlpha = 0x1906, kGl4444 = 0x8033,
                   kGl5551 = 0x8034, kGl565 = 0x8363, kGl4444Rev = 0x8365, kGl1555Rev = 0x8366,
                   kGl8888Rev = 0x8367;

// Glide texture formats.
constexpr uint32_t kGlideAlpha8 = 0x2, kGlideIntensity8 = 0x3, kGlideAi44 = 0x4, kGlide565 = 0xA,
                   kGlide1555 = 0xB, kGlide4444 = 0xC, kGlideAi88 = 0xD, kGlide8888 = 0x12;

// Bytes per pixel of an entry, 0 when the format is not one this reader decodes (S3TC, palettes).
uint32_t BytesPerPixel(uint32_t layout, uint32_t format, uint16_t texFormat, uint16_t pixelType) {
    if (layout == kLayoutGlide) {
        switch (format & ~kGlideCompressed) {
            case kGlideAlpha8:
            case kGlideIntensity8:
            case kGlideAi44:
                return 1;
            case kGlide565:
            case kGlide1555:
            case kGlide4444:
            case kGlideAi88:
                return 2;
            case kGlide8888:
                return 4;
            default:
                return 0;
        }
    }
    switch (pixelType) {
        case kGlUnsignedByte:
            switch (texFormat) {
                case kGlRgba:
                case kGlBgra:
                    return 4;
                case kGlRgb:
                    return 3;
                case kGlLuminanceAlpha:
                    return 2;
                case kGlLuminance:
                case kGlAlpha:
                    return 1;
                default:
                    return 0;
            }
        case kGl4444:
        case kGl5551:
        case kGl565:
        case kGl4444Rev:
        case kGl1555Rev:
            return 2;
        case kGl8888Rev:
            return 4;
        default:
            return 0;
    }
}

bool Compressed(uint32_t layout, uint32_t format) {
    return layout == kLayoutGlide ? (format & kGlideCompressed) != 0 : (format & kGlCompressed) != 0;
}

uint8_t Expand(uint32_t value, int bits) {
    return static_cast<uint8_t>(bits == 8 ? value : (value * 255 + ((1u << bits) - 1) / 2) / ((1u << bits) - 1));
}

// Pixels of one entry as RGBA8.
bool ToRgba(uint32_t layout, const Entry& e, const uint8_t* src, std::vector<uint8_t>& out) {
    const uint32_t count = e.width * e.height;
    out.resize(static_cast<size_t>(count) * 4);
    uint8_t* d = out.data();
    auto u16 = [src](uint32_t i) { return static_cast<uint32_t>(src[i * 2] | (src[i * 2 + 1] << 8)); };
    auto put = [d](uint32_t i, uint32_t r, uint32_t g, uint32_t b, uint32_t a) {
        d[i * 4 + 0] = static_cast<uint8_t>(r);
        d[i * 4 + 1] = static_cast<uint8_t>(g);
        d[i * 4 + 2] = static_cast<uint8_t>(b);
        d[i * 4 + 3] = static_cast<uint8_t>(a);
    };
    if (layout == kLayoutGlide) {
        switch (e.format & ~kGlideCompressed) {
            case kGlide8888: // ARGB in a little-endian word: B, G, R, A
                for (uint32_t i = 0; i < count; ++i) put(i, src[i * 4 + 2], src[i * 4 + 1], src[i * 4], src[i * 4 + 3]);
                return true;
            case kGlide565:
                for (uint32_t i = 0; i < count; ++i) {
                    const uint32_t p = u16(i);
                    put(i, Expand(p >> 11, 5), Expand((p >> 5) & 63, 6), Expand(p & 31, 5), 255);
                }
                return true;
            case kGlide1555:
                for (uint32_t i = 0; i < count; ++i) {
                    const uint32_t p = u16(i);
                    put(i, Expand((p >> 10) & 31, 5), Expand((p >> 5) & 31, 5), Expand(p & 31, 5), (p & 0x8000) ? 255 : 0);
                }
                return true;
            case kGlide4444:
                for (uint32_t i = 0; i < count; ++i) {
                    const uint32_t p = u16(i);
                    put(i, Expand((p >> 8) & 15, 4), Expand((p >> 4) & 15, 4), Expand(p & 15, 4), Expand(p >> 12, 4));
                }
                return true;
            case kGlideAi88:
                for (uint32_t i = 0; i < count; ++i) {
                    const uint32_t p = u16(i);
                    put(i, p & 255, p & 255, p & 255, p >> 8);
                }
                return true;
            case kGlideAi44:
                for (uint32_t i = 0; i < count; ++i) {
                    const uint32_t v = Expand(src[i] & 15, 4);
                    put(i, v, v, v, Expand(src[i] >> 4, 4));
                }
                return true;
            case kGlideIntensity8:
                for (uint32_t i = 0; i < count; ++i) put(i, src[i], src[i], src[i], 255);
                return true;
            case kGlideAlpha8:
                for (uint32_t i = 0; i < count; ++i) put(i, 255, 255, 255, src[i]);
                return true;
            default:
                return false;
        }
    }
    switch (e.pixelType) {
        case kGlUnsignedByte:
            switch (e.texFormat) {
                case kGlRgba:
                    std::memcpy(d, src, out.size());
                    return true;
                case kGlBgra:
                    for (uint32_t i = 0; i < count; ++i) put(i, src[i * 4 + 2], src[i * 4 + 1], src[i * 4], src[i * 4 + 3]);
                    return true;
                case kGlRgb:
                    for (uint32_t i = 0; i < count; ++i) put(i, src[i * 3], src[i * 3 + 1], src[i * 3 + 2], 255);
                    return true;
                case kGlLuminanceAlpha:
                    for (uint32_t i = 0; i < count; ++i) put(i, src[i * 2], src[i * 2], src[i * 2], src[i * 2 + 1]);
                    return true;
                case kGlLuminance:
                    for (uint32_t i = 0; i < count; ++i) put(i, src[i], src[i], src[i], 255);
                    return true;
                case kGlAlpha:
                    for (uint32_t i = 0; i < count; ++i) put(i, 255, 255, 255, src[i]);
                    return true;
                default:
                    return false;
            }
        case kGl8888Rev: // BGRA in a little-endian word
            for (uint32_t i = 0; i < count; ++i) put(i, src[i * 4 + 2], src[i * 4 + 1], src[i * 4], src[i * 4 + 3]);
            return true;
        case kGl4444:
            for (uint32_t i = 0; i < count; ++i) {
                const uint32_t p = u16(i);
                put(i, Expand(p >> 12, 4), Expand((p >> 8) & 15, 4), Expand((p >> 4) & 15, 4), Expand(p & 15, 4));
            }
            return true;
        case kGl4444Rev:
            for (uint32_t i = 0; i < count; ++i) {
                const uint32_t p = u16(i);
                put(i, Expand(p & 15, 4), Expand((p >> 4) & 15, 4), Expand((p >> 8) & 15, 4), Expand(p >> 12, 4));
            }
            return true;
        case kGl5551:
            for (uint32_t i = 0; i < count; ++i) {
                const uint32_t p = u16(i);
                put(i, Expand(p >> 11, 5), Expand((p >> 6) & 31, 5), Expand((p >> 1) & 31, 5), (p & 1) ? 255 : 0);
            }
            return true;
        case kGl1555Rev:
            for (uint32_t i = 0; i < count; ++i) {
                const uint32_t p = u16(i);
                put(i, Expand(p & 31, 5), Expand((p >> 5) & 31, 5), Expand((p >> 10) & 31, 5), (p & 0x8000) ? 255 : 0);
            }
            return true;
        case kGl565:
            for (uint32_t i = 0; i < count; ++i) {
                const uint32_t p = u16(i);
                put(i, Expand(p >> 11, 5), Expand((p >> 5) & 63, 6), Expand(p & 31, 5), 255);
            }
            return true;
        default:
            return false;
    }
}

// ── Reading the .htc ────────────────────────────────────────────────────────────────────────────

uint32_t Le32(const uint8_t* p) {
    return p[0] | (p[1] << 8) | (p[2] << 16) | (static_cast<uint32_t>(p[3]) << 24);
}
uint16_t Le16(const uint8_t* p) {
    return static_cast<uint16_t>(p[0] | (p[1] << 8));
}
uint64_t Le64(const uint8_t* p) {
    return Le32(p) | (static_cast<uint64_t>(Le32(p + 4)) << 32);
}

uint32_t FieldsSize(uint32_t layout) {
    switch (layout) {
        case kLayoutGl:
            return 4 + 2 + 2 + 1;
        case kLayoutGlNoHi:
            return 4 + 2 + 2;
        default:
            return 2 + 6 * 4 + 1;
    }
}

// Parses the fields between the dimensions and the payload size.
void ParseFields(uint32_t layout, const uint8_t* p, Entry& e) {
    if (layout == kLayoutGlide) {
        e.format = Le16(p);
        e.texFormat = e.pixelType = 0;
    } else {
        e.format = Le32(p);
        e.texFormat = Le16(p + 4);
        e.pixelType = Le16(p + 6);
    }
}

bool Plausible(uint32_t layout, const Entry& e) {
    if (e.width == 0 || e.height == 0 || e.width > 8192 || e.height > 8192 || e.size == 0 || e.size > (64u << 20)) {
        return false;
    }
    const uint32_t bpp = BytesPerPixel(layout, e.format, e.texFormat, e.pixelType);
    if (Compressed(layout, e.format)) {
        return bpp != 0 && e.size <= e.width * e.height * bpp + 4096;
    }
    return bpp != 0 && e.size == e.width * e.height * bpp;
}

// Finds the header length and entry layout from the first bytes of the stream.
bool Detect(const std::vector<uint8_t>& probe, uint32_t& headerLen, uint32_t& layout) {
    for (uint32_t header : { 4u, 8u, 0u, 12u }) {
        for (uint32_t candidate : { kLayoutGl, kLayoutGlide, kLayoutGlNoHi }) {
            size_t at = header;
            int good = 0;
            bool bad = false;
            while (good < 6 && at + 16 + FieldsSize(candidate) + 4 <= probe.size()) {
                Entry e{};
                e.width = Le32(&probe[at + 8]);
                e.height = Le32(&probe[at + 12]);
                ParseFields(candidate, &probe[at + 16], e);
                e.size = Le32(&probe[at + 16 + FieldsSize(candidate)]);
                if (!Plausible(candidate, e)) {
                    bad = true;
                    break;
                }
                ++good;
                at += 16 + FieldsSize(candidate) + 4 + e.size;
            }
            if (!bad && good >= 1) {
                headerLen = header;
                layout = candidate;
                return true;
            }
        }
    }
    return false;
}

// ── Pack state ──────────────────────────────────────────────────────────────────────────────────

struct Pack {
    std::string name;
    uint32_t layout = 0;
    std::string payloadPath;
    std::unordered_map<uint64_t, Entry> entries;
    // Texel CRC (low 32 bits of the key) -> the one key that has it, or 0 when several do.
    std::unordered_map<uint32_t, uint64_t> byCrc;
};

void IndexByCrc(Pack& pack) {
    pack.byCrc.reserve(pack.entries.size());
    for (const auto& [key, e] : pack.entries) {
        auto [it, added] = pack.byCrc.emplace(static_cast<uint32_t>(key), key);
        if (!added && it->second != key) it->second = 0;
    }
}

enum State { kIdle, kLoading, kReady, kNoPack, kFailed };

std::mutex gMutex;
std::shared_ptr<const Pack> gPack;
std::atomic<int> gState{ kIdle };
std::atomic<uint32_t> gGeneration{ 1 };
std::atomic<uint32_t> gHits{ 0 }, gMisses{ 0 }, gShapeMismatch{ 0 }, gUsed{ 0 };
std::string gStatusName;
int gEnabled = -1;
FILE* gPayload = nullptr; // render thread only

fs::path AppDir() {
    const char* dir = std::getenv("GDX_APP_DIR");
    return dir != nullptr && dir[0] != '\0' ? fs::path(dir) : fs::current_path();
}

fs::path FindPack() {
    std::error_code ec;
    for (const char* folder : { "hires", "mods" }) {
        const fs::path dir = AppDir() / folder;
        if (!fs::is_directory(dir, ec)) {
            continue;
        }
        std::vector<fs::path> found;
        for (const auto& entry : fs::directory_iterator(dir, ec)) {
            std::string ext = entry.path().extension().string();
            std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return std::tolower(c); });
            if (entry.is_regular_file(ec) && ext == ".htc") {
                found.push_back(entry.path());
            }
        }
        if (!found.empty()) {
            // The most recently installed one.
            std::sort(found.begin(), found.end(), [&ec](const fs::path& a, const fs::path& b) {
                return fs::last_write_time(a, ec) > fs::last_write_time(b, ec);
            });
            return found.front();
        }
    }
    return {};
}

constexpr uint32_t kIndexMagic = 0x5448475A; // "ZGHT"
constexpr uint32_t kIndexVersion = 1;

struct IndexHeader {
    uint32_t magic, version, layout, count;
    uint64_t sourceSize;
    int64_t sourceTime;
};

struct IndexRecord {
    uint64_t key, offset;
    uint32_t size, width, height, format;
    uint16_t texFormat, pixelType;
};

bool LoadIndex(const fs::path& indexPath, const IndexHeader& want, Pack& pack) {
    FILE* f = std::fopen(indexPath.string().c_str(), "rb");
    if (f == nullptr) {
        return false;
    }
    IndexHeader h{};
    bool ok = std::fread(&h, sizeof(h), 1, f) == 1 && h.magic == kIndexMagic && h.version == kIndexVersion &&
              h.sourceSize == want.sourceSize && h.sourceTime == want.sourceTime;
    if (ok) {
        pack.layout = h.layout;
        pack.entries.reserve(h.count);
        IndexRecord r{};
        for (uint32_t i = 0; i < h.count && ok; ++i) {
            ok = std::fread(&r, sizeof(r), 1, f) == 1;
            pack.entries.emplace(r.key, Entry{ r.offset, r.size, r.width, r.height, r.format, r.texFormat, r.pixelType });
        }
    }
    std::fclose(f);
    return ok;
}

void Load(fs::path source) {
    auto pack = std::make_shared<Pack>();
    pack->name = source.filename().string();
    std::error_code ec;
    const fs::path cacheDir = AppDir() / "cache" / "htc";
    fs::create_directories(cacheDir, ec);
    const fs::path indexPath = cacheDir / (pack->name + ".idx");
    pack->payloadPath = (cacheDir / (pack->name + ".bin")).string();

    struct stat st {};
    IndexHeader header{ kIndexMagic, kIndexVersion, 0, 0, 0, 0 };
    if (stat(source.string().c_str(), &st) == 0) {
        header.sourceSize = static_cast<uint64_t>(st.st_size);
        header.sourceTime = static_cast<int64_t>(st.st_mtime);
    }
    if (LoadIndex(indexPath, header, *pack) && fs::exists(pack->payloadPath, ec)) {
        gdx_port_logf("[htc] %s: %zu textures (cached index)\n", pack->name.c_str(), pack->entries.size());
    } else {
        pack->entries.clear();
        gzFile gz = gzopen(source.string().c_str(), "rb");
        if (gz == nullptr) {
            gdx_port_logf("[htc] cannot open %s\n", source.string().c_str());
            gState = kFailed;
            return;
        }
        gzbuffer(gz, 1 << 20);
        std::vector<uint8_t> probe(1 << 20);
        const int probed = gzread(gz, probe.data(), static_cast<unsigned>(probe.size()));
        probe.resize(probed > 0 ? static_cast<size_t>(probed) : 0);
        uint32_t headerLen = 0, layout = 0;
        if (!Detect(probe, headerLen, layout)) {
            gdx_port_logf("[htc] %s: unknown layout (first bytes %02x %02x %02x %02x %02x %02x %02x %02x)\n",
                          pack->name.c_str(), probe.size() > 0 ? probe[0] : 0, probe.size() > 1 ? probe[1] : 0,
                          probe.size() > 2 ? probe[2] : 0, probe.size() > 3 ? probe[3] : 0,
                          probe.size() > 4 ? probe[4] : 0, probe.size() > 5 ? probe[5] : 0,
                          probe.size() > 6 ? probe[6] : 0, probe.size() > 7 ? probe[7] : 0);
            gzclose(gz);
            gState = kFailed;
            return;
        }
        pack->layout = layout;
        gzrewind(gz);
        std::vector<uint8_t> skip(headerLen + 1);
        gzread(gz, skip.data(), headerLen);

        FILE* out = std::fopen(pack->payloadPath.c_str(), "wb");
        if (out == nullptr) {
            gzclose(gz);
            gdx_port_logf("[htc] cannot write %s\n", pack->payloadPath.c_str());
            gState = kFailed;
            return;
        }
        const uint32_t fieldsSize = FieldsSize(layout);
        std::vector<uint8_t> head(16 + fieldsSize + 4);
        std::vector<uint8_t> payload;
        uint64_t offset = 0;
        uint32_t skipped = 0;
        while (gzread(gz, head.data(), static_cast<unsigned>(head.size())) == static_cast<int>(head.size())) {
            Entry e{};
            const uint64_t key = Le64(&head[0]);
            e.width = Le32(&head[8]);
            e.height = Le32(&head[12]);
            ParseFields(layout, &head[16], e);
            e.size = Le32(&head[16 + fieldsSize]);
            if (e.size == 0 || e.size > (64u << 20)) {
                break; // lost sync: keep what was read
            }
            payload.resize(e.size);
            if (gzread(gz, payload.data(), e.size) != static_cast<int>(e.size)) {
                break;
            }
            if (BytesPerPixel(layout, e.format, e.texFormat, e.pixelType) == 0 || pack->entries.count(key) != 0) {
                ++skipped;
                continue;
            }
            e.offset = offset;
            std::fwrite(payload.data(), 1, e.size, out);
            offset += e.size;
            pack->entries.emplace(key, e);
        }
        gzclose(gz);
        std::fclose(out);

        header.layout = layout;
        header.count = static_cast<uint32_t>(pack->entries.size());
        if (FILE* idx = std::fopen(indexPath.string().c_str(), "wb")) {
            std::fwrite(&header, sizeof(header), 1, idx);
            for (const auto& [key, e] : pack->entries) {
                const IndexRecord r{ key, e.offset, e.size, e.width, e.height, e.format, e.texFormat, e.pixelType };
                std::fwrite(&r, sizeof(r), 1, idx);
            }
            std::fclose(idx);
        }
        gdx_port_logf("[htc] %s: %zu textures, layout %u, header %u bytes, %u skipped (unsupported format)\n",
                      pack->name.c_str(), pack->entries.size(), layout, headerLen, skipped);
    }
    int shown = 0;
    for (const auto& [key, e] : pack->entries) {
        if (shown++ >= 8) {
            break;
        }
        gdx_port_logf("[htc]   key %016llx %ux%u fmt %x/%x/%x\n", static_cast<unsigned long long>(key), e.width,
                      e.height, e.format, e.texFormat, e.pixelType);
    }
    {
        std::lock_guard<std::mutex> lock(gMutex);
        IndexByCrc(*pack);
        gPack = pack;
    }
    gState = kReady;
    ++gGeneration;
}

// Starts the background load for the first pack found.
void StartLoad() {
    const fs::path source = FindPack();
    if (source.empty()) {
        gState = kNoPack;
        return;
    }
    gStatusName = source.filename().string();
    gState = kLoading;
    std::thread(Load, source).detach();
}

// Starts or stops the pack when the option changes. Render thread, once per frame at most.
// Results kept between lookups (render thread only): which pack entry, if any, a texture's bytes
// matched, and the last hi-res images decoded. Without them every re-decode of a texture hashed
// its bytes dozens of times and read and unpacked its replacement again.
std::unordered_map<uint64_t, uint64_t> gMatches; // content hash -> pack key, 0 = not in pack
struct Decoded {
    std::vector<uint8_t> rgba;
    uint32_t width, height;
    uint64_t lastUse;
};
std::unordered_map<uint64_t, Decoded> gDecoded;
size_t gDecodedBytes = 0;
uint64_t gUseClock = 0;
constexpr size_t kDecodedBudget = 160u << 20;
constexpr size_t kMatchesLimit = 50000;

// Drops the pack and everything derived from it; the next enable loads it again (from the index).
void Unload() {
    {
        std::lock_guard<std::mutex> lock(gMutex);
        gPack.reset();
    }
    if (gPayload != nullptr) {
        std::fclose(gPayload);
        gPayload = nullptr;
    }
    gMatches.clear();
    gDecoded.clear();
    gDecodedBytes = 0;
    gHits = gMisses = gShapeMismatch = gUsed = 0;
    gState = kIdle;
}

void Refresh() {
    const int enabled = CVarGetInteger("gGZ64.HiresPack", 1) != 0 ? 1 : 0;
    // Bumped by the quick menu when it installs an .htc: switch to it even if a pack is in use.
    static int sSerial = -1;
    const int serial = CVarGetInteger("gGZ64.HiresPackSerial", 0);
    if (serial != sSerial) {
        const bool first = sSerial == -1;
        sSerial = serial;
        if (!first && enabled == 1 && gEnabled == 1 && gState != kLoading) {
            Unload();
            ++gGeneration;
            StartLoad();
            return;
        }
    }
    if (enabled != gEnabled) {
        if (enabled == 0 && gEnabled == 1 && gState != kLoading) {
            Unload(); // also lets a deleted pack go
        }
        gEnabled = enabled;
        ++gGeneration; // the renderer re-decodes every texture with or without the pack
        if (enabled && gState != kLoading && gState != kReady) {
            StartLoad();
        }
        return;
    }
    // With no pack yet, look again every few seconds: one may have just been installed.
    static auto sLastScan = std::chrono::steady_clock::now();
    const auto now = std::chrono::steady_clock::now();
    if (enabled == 1 && gState == kNoPack && now - sLastScan >= std::chrono::seconds(3)) {
        sLastScan = now;
        StartLoad();
    }
}

// ── Rice CRC ────────────────────────────────────────────────────────────────────────────────────

// The texture bytes as an emulator's RDRAM holds them: 32-bit words in host order, so a 32-bit
// read at byte address A sees N64 bytes (A+k)^3. Reads outside [0, limit) see zero.
struct Rdram {
    const uint8_t* base;
    uint32_t align; // N64 address of base, mod 4
    uint32_t limit;
    uint32_t Read32(uint32_t offset) const {
        uint32_t value = 0;
        for (uint32_t k = 0; k < 4; ++k) {
            const int64_t index = static_cast<int64_t>((align + offset + k) ^ 3u) - align;
            const uint8_t byte = index >= 0 && index < limit ? base[index] : 0;
            value |= static_cast<uint32_t>(byte) << (8 * k);
        }
        return value;
    }
};

// RiceCRC32 / RiceCRC32_CI4 / RiceCRC32_CI8 as GLideN64 computes them. ciBits: 0 none, 4 or 8.
uint32_t RiceCrc(const Rdram& mem, uint32_t width, uint32_t height, uint32_t size, uint32_t rowStride, int ciBits,
                 uint32_t* ciMax) {
    const uint32_t bytesPerWidth = ((width << size) + 1) >> 1;
    uint32_t crc = 0, wordHash = 0, maxIndex = 0;
    uint32_t row = 0;
    for (int32_t y = static_cast<int32_t>(height) - 1; y >= 0; --y) {
        for (uint32_t pos = bytesPerWidth - 4; pos < 0x80000000u; pos -= 4) {
            const uint32_t word = mem.Read32(row + pos);
            if (ciBits == 8 && maxIndex != 255) {
                for (int s = 0; s < 32; s += 8) maxIndex = std::max(maxIndex, (word >> s) & 0xFF);
            } else if (ciBits == 4 && maxIndex != 15) {
                for (int s = 0; s < 32; s += 4) maxIndex = std::max(maxIndex, (word >> s) & 0xF);
            }
            wordHash = pos ^ word;
            crc = ((crc << 4) + ((crc >> 28) & 15)) + wordHash;
        }
        crc += static_cast<uint32_t>(y) ^ wordHash;
        row += rowStride;
    }
    if (ciMax != nullptr) {
        *ciMax = maxIndex;
    }
    return crc;
}

// Palette CRC over entries [0, count) of a 16-bit palette held as host-order values.
uint32_t PaletteCrc(const uint16_t* palette, uint32_t count, uint32_t available) {
    const uint32_t bytesPerWidth = ((count << 2) + 1) >> 1; // size 2 (16 bit)
    uint32_t crc = 0, wordHash = 0;
    for (uint32_t pos = bytesPerWidth - 4; pos < 0x80000000u; pos -= 4) {
        const uint32_t i = pos / 2;
        const uint32_t lo = i < available ? palette[i] : 0;
        const uint32_t hi = i + 1 < available ? palette[i + 1] : 0;
        const uint32_t word = lo | (hi << 16);
        wordHash = pos ^ word;
        crc = ((crc << 4) + ((crc >> 28) & 15)) + wordHash;
    }
    crc += 0u ^ wordHash;
    return crc;
}

uint64_t Checksum(const Gz64HtcQuery& q, uint32_t width, uint32_t height, uint32_t size, uint32_t rowStride,
                  const uint16_t* palette) {
    if (width == 0 || height == 0 || rowStride == 0 || width > 1024 || height > 1024) {
        return 0;
    }
    const Rdram mem{ q.addr, static_cast<uint32_t>(reinterpret_cast<uintptr_t>(q.addr) & 3u),
                     rowStride * (height - 1) + (((width << size) + 1) >> 1) };
    const bool paletted = q.siz < 2 && (q.tlut != 0 || q.fmt == 2 /* G_IM_FMT_CI */);
    if (paletted && palette != nullptr && size == q.siz) {
        uint32_t ciMax = 0;
        if (q.siz == 1) {
            const uint32_t crc = RiceCrc(mem, width, height, size, rowStride, 8, &ciMax);
            return (static_cast<uint64_t>(PaletteCrc(palette, ciMax + 1, 256)) << 32) | crc;
        }
        const uint32_t crc = RiceCrc(mem, width, height, size, rowStride, 4, &ciMax);
        const uint32_t base = (q.palette & 15) * 16;
        return (static_cast<uint64_t>(PaletteCrc(palette + base, ciMax + 1, 256 - base)) << 32) | crc;
    }
    return RiceCrc(mem, width, height, size, rowStride, 0, nullptr);
}

} // namespace

namespace {
const Entry* Search(const Gz64HtcQuery& query, const Pack& pack, uint64_t& hitKeyOut);
const uint8_t* Decode(const Pack& pack, const Entry& entry, uint64_t key, const Gz64HtcQuery& q,
                      uint32_t* outWidth, uint32_t* outHeight);
} // namespace

extern "C" int gz64_htc_active(void) {
    Refresh();
    return gEnabled == 1 && gState == kReady ? 1 : 0;
}

extern "C" uint32_t gz64_htc_generation(void) {
    Refresh();
    return gGeneration;
}

extern "C" const uint8_t* gz64_htc_find(const Gz64HtcQuery* q, uint32_t* outWidth, uint32_t* outHeight) {
    if (q == nullptr || q->addr == nullptr || gEnabled != 1 || gState != kReady) {
        return nullptr;
    }
    std::shared_ptr<const Pack> pack;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        pack = gPack;
    }
    if (pack == nullptr || pack->entries.empty()) {
        return nullptr;
    }

    // Same bytes, same shape, same palette: same answer as last time.
    uint64_t contentKey = 1469598103934665603ull;
    auto mix = [&contentKey](const uint8_t* data, size_t length) {
        for (size_t i = 0; i < length; ++i) contentKey = (contentKey ^ data[i]) * 1099511628211ull;
    };
    const uint32_t shape[] = { q->fmt, q->siz, q->tlut, q->palette, q->lineBytes, q->fullLineBytes, q->tileLineBytes,
                               q->tileWidth, q->tileHeight, q->masks, q->maskt, q->cms, q->cmt, q->width, q->height };
    mix(reinterpret_cast<const uint8_t*>(shape), sizeof(shape));
    const size_t span = std::min<size_t>(static_cast<size_t>(q->height - 1) * q->fullLineBytes + q->lineBytes, 16384);
    mix(q->addr, span);
    if (q->siz < 2 && q->palettes[0] != nullptr && q->palettes[1] != nullptr) {
        mix(q->palettes[0], 256);
        mix(q->palettes[1], 256);
    }

    const Entry* hit = nullptr;
    uint64_t hitKey = 0;
    auto known = gMatches.find(contentKey);
    if (known != gMatches.end()) {
        if (known->second == 0) {
            return nullptr;
        }
        auto it = pack->entries.find(known->second);
        if (it == pack->entries.end()) {
            return nullptr;
        }
        hit = &it->second;
        hitKey = known->second;
    } else {
        hit = Search(*q, *pack, hitKey);
        if (hit != nullptr) {
            ++gUsed;
        }
        if (gMatches.size() >= kMatchesLimit) {
            gMatches.clear();
        }
        gMatches.emplace(contentKey, hit != nullptr ? hitKey : 0);
        if (hit == nullptr) {
            return nullptr;
        }
    }
    return Decode(*pack, *hit, hitKey, *q, outWidth, outHeight);
}

namespace {

const Entry* Search(const Gz64HtcQuery& query, const Pack& pack, uint64_t& hitKeyOut) {
    const Gz64HtcQuery* q = &query;
    uint16_t palette[256];
    const uint16_t* paletteArg = nullptr;
    if (q->siz < 2 && q->palettes[0] != nullptr && q->palettes[1] != nullptr) {
        for (uint32_t i = 0; i < 256; ++i) {
            const uint8_t* p = q->palettes[i / 128] + (i % 128) * 2;
            palette[i] = static_cast<uint16_t>((p[0] << 8) | p[1]);
        }
        paletteArg = palette;
    }

    // GLideN64 sizes the hashed area from the tile for block loads and from the load for tile
    // loads; the renderer does not keep which one happened, so try the plausible shapes.
    const uint32_t tileW = q->tileWidth, tileH = q->tileHeight;
    const uint32_t maskW = q->masks != 0 ? (1u << q->masks) : tileW;
    const uint32_t maskH = q->maskt != 0 ? (1u << q->maskt) : tileH;
    const uint32_t ruleW = ((q->cms & 2) && tileW <= 256) ? std::min(maskW, tileW) : maskW;
    const uint32_t ruleH = (((q->cmt & 2) && tileH <= 256) || maskH > 256) ? std::min(maskH, tileH) : maskH;
    // Also the shapes the load itself implies: its row length in texels and its row count.
    const uint32_t lineW = (q->lineBytes << 1) >> q->siz;
    const uint32_t fullW = (q->fullLineBytes << 1) >> q->siz;
    const uint32_t loadH = q->lineBytes != 0 ? q->sizeBytes / q->lineBytes : 0;
    const uint32_t widths[] = { q->width, ruleW, tileW, maskW, lineW, fullW };
    const uint32_t heights[] = { q->height, ruleH, tileH, maskH, loadH };
    const uint32_t strides[] = { q->fullLineBytes, q->lineBytes, q->tileLineBytes, q->tileLineBytes * 2 };
    const uint32_t sizes[] = { q->siz, 2u };
    const uint64_t available = std::max<uint64_t>(
        q->sizeBytes, q->height != 0 ? static_cast<uint64_t>(q->height - 1) * q->fullLineBytes + q->lineBytes : 0);

    const Entry* hit = nullptr;
    uint64_t hitKey = 0;
    uint64_t firstKey = 0;
    const Pack* packPtr = &pack;
    uint64_t tried[256];
    uint32_t triedCount = 0;
    for (uint32_t size : sizes) {
        if (size != q->siz && q->siz >= 2) {
            continue;
        }
        for (uint32_t wi = 0; wi < 6; ++wi) {
            const uint32_t w = widths[wi];
            for (uint32_t hi = 0; hi < 5; ++hi) {
                const uint32_t h = heights[hi];
                for (uint32_t stride : strides) {
                    // The extra shapes (from index 3 on) only inside the bytes the renderer has.
                    if ((wi >= 3 || hi >= 3) && (w == 0 || h == 0 ||
                        static_cast<uint64_t>(stride) * (h - 1) + (((w << size) + 1) >> 1) > available)) {
                        continue;
                    }
                    const uint64_t key = Checksum(*q, w, h, size, stride, paletteArg);
                    if (key == 0 || std::find(tried, tried + triedCount, key) != tried + triedCount) {
                        continue;
                    }
                    if (triedCount < 256) {
                        tried[triedCount++] = key;
                    }
                    if (firstKey == 0) {
                        firstKey = key;
                    }
                    auto it = packPtr->entries.find(key);
                    if (it == packPtr->entries.end() && (key >> 32) != 0) {
                        it = packPtr->entries.find(key & 0xFFFFFFFFull);
                    }
                    if (it == packPtr->entries.end()) {
                        // Same texels, other palette CRC: accepted only when a single pack entry has
                        // those texels, since recoloured copies (palette swaps) share them.
                        auto crc = packPtr->byCrc.find(static_cast<uint32_t>(key));
                        if (crc != packPtr->byCrc.end() && crc->second != 0) {
                            it = packPtr->entries.find(crc->second);
                        }
                    }
                    if (it == packPtr->entries.end()) {
                        continue;
                    }
                    // The hi-res image has to keep the aspect of what the renderer maps.
                    if (static_cast<uint64_t>(it->second.width) * q->height !=
                        static_cast<uint64_t>(it->second.height) * q->width) {
                        ++gShapeMismatch;
                        continue;
                    }
                    hit = &it->second;
                    hitKey = it->first;
                    break;
                }
                if (hit != nullptr) break;
            }
            if (hit != nullptr) break;
        }
        if (hit != nullptr) break;
    }
    if (hit == nullptr) {
        const uint32_t misses = ++gMisses;
        if (misses <= 40) {
            gdx_port_logf("[htc] miss fmt %u siz %u %ux%u tile %ux%u line %u/%u/%u tlut %u key %016llx\n", q->fmt,
                          q->siz, q->width, q->height, tileW, tileH, q->lineBytes, q->fullLineBytes,
                          q->tileLineBytes, q->tlut, static_cast<unsigned long long>(firstKey));
        }
        return nullptr;
    }
    hitKeyOut = hitKey;
    return hit;
}

// The entry as RGBA8, at most 4x the size the renderer maps (larger ones only cost memory and
// bandwidth on a handheld), from the cache of recent decodes when possible.
const uint8_t* Decode(const Pack& pack, const Entry& entry, uint64_t key, const Gz64HtcQuery& q,
                      uint32_t* outWidth, uint32_t* outHeight) {
    uint32_t factor = 1;
    while (entry.width / (factor * 2) >= q.width * 4 && entry.height / (factor * 2) >= q.height * 4 &&
           entry.width % (factor * 2) == 0 && entry.height % (factor * 2) == 0) {
        factor *= 2;
    }
    const uint64_t cacheKey = key ^ (static_cast<uint64_t>(factor) << 58);
    auto cached = gDecoded.find(cacheKey);
    if (cached != gDecoded.end()) {
        cached->second.lastUse = ++gUseClock;
        *outWidth = cached->second.width;
        *outHeight = cached->second.height;
        return cached->second.rgba.data();
    }

    if (gPayload == nullptr) {
        gPayload = std::fopen(pack.payloadPath.c_str(), "rb");
        if (gPayload == nullptr) {
            return nullptr;
        }
    }
    static std::vector<uint8_t> raw, unpacked, rgba;
    raw.resize(entry.size);
    if (fseeko(gPayload, static_cast<off_t>(entry.offset), SEEK_SET) != 0 ||
        std::fread(raw.data(), 1, entry.size, gPayload) != entry.size) {
        return nullptr;
    }
    const uint8_t* pixels = raw.data();
    if (Compressed(pack.layout, entry.format)) {
        const uint32_t bpp = BytesPerPixel(pack.layout, entry.format, entry.texFormat, entry.pixelType);
        uLongf length = static_cast<uLongf>(entry.width) * entry.height * bpp;
        unpacked.resize(length);
        if (uncompress(unpacked.data(), &length, raw.data(), entry.size) != Z_OK ||
            length != static_cast<uLongf>(entry.width) * entry.height * bpp) {
            return nullptr;
        }
        pixels = unpacked.data();
    }
    if (!ToRgba(pack.layout, entry, pixels, rgba)) {
        return nullptr;
    }

    Decoded decoded{ {}, entry.width / factor, entry.height / factor, ++gUseClock };
    if (factor == 1) {
        decoded.rgba = rgba;
    } else {
        // Box filter: the average of each factor x factor block.
        decoded.rgba.resize(static_cast<size_t>(decoded.width) * decoded.height * 4);
        for (uint32_t y = 0; y < decoded.height; ++y) {
            for (uint32_t x = 0; x < decoded.width; ++x) {
                uint32_t sum[4] = { 0, 0, 0, 0 };
                for (uint32_t dy = 0; dy < factor; ++dy) {
                    const uint8_t* row = rgba.data() + ((static_cast<size_t>(y) * factor + dy) * entry.width + x * factor) * 4;
                    for (uint32_t dx = 0; dx < factor; ++dx) {
                        for (int c = 0; c < 4; ++c) sum[c] += row[dx * 4 + c];
                    }
                }
                uint8_t* out = decoded.rgba.data() + (static_cast<size_t>(y) * decoded.width + x) * 4;
                for (int c = 0; c < 4; ++c) out[c] = static_cast<uint8_t>(sum[c] / (factor * factor));
            }
        }
    }

    const uint32_t hits = ++gHits;
    if (hits <= 10) {
        gdx_port_logf("[htc] hit key %016llx -> %ux%u (stored %ux%u)\n", static_cast<unsigned long long>(key),
                      entry.width, entry.height, decoded.width, decoded.height);
    }
    // Make room, least recently used first.
    gDecodedBytes += decoded.rgba.size();
    while (gDecodedBytes > kDecodedBudget && !gDecoded.empty()) {
        auto oldest = gDecoded.begin();
        for (auto it = gDecoded.begin(); it != gDecoded.end(); ++it) {
            if (it->second.lastUse < oldest->second.lastUse) oldest = it;
        }
        gDecodedBytes -= oldest->second.rgba.size();
        gDecoded.erase(oldest);
    }
    Decoded& stored = gDecoded[cacheKey] = std::move(decoded);
    *outWidth = stored.width;
    *outHeight = stored.height;
    return stored.rgba.data();
}

} // namespace

const char* Gz64HtcStatus() {
    static std::string line;
    char buffer[200];
    switch (gState.load()) {
        case kLoading:
            std::snprintf(buffer, sizeof(buffer), GdxTr("%s: preparing..."), gStatusName.c_str());
            break;
        case kReady: {
            size_t count = 0;
            {
                std::lock_guard<std::mutex> lock(gMutex);
                count = gPack != nullptr ? gPack->entries.size() : 0;
            }
            std::snprintf(buffer, sizeof(buffer), GdxTr("%s: %zu HD in the pack | replaced %u, without HD %u"),
                          gStatusName.c_str(), count, gUsed.load(), gMisses.load());
            break;
        }
        case kNoPack:
            std::snprintf(buffer, sizeof(buffer), "%s", GdxTr("No HD pack: put the .htc beside the game files and pick that folder again in the launcher."));
            break;
        case kFailed:
            std::snprintf(buffer, sizeof(buffer), GdxTr("%s: could not be read (see the log)"), gStatusName.c_str());
            break;
        default:
            buffer[0] = '\0';
            break;
    }
    line = buffer;
    return line.c_str();
}
