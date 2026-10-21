// port/gdx_i18n.cpp -- see gdx_i18n.h. The Spanish table is gdx_i18n_es.inc.
#include "gdx_i18n.h"

#include <cstring>
#include <deque>
#include <mutex>
#include <string_view>
#include <unordered_map>

#include "libultraship/bridge/consolevariablebridge.h"

namespace {

struct Pair {
    const char* english;
    const char* translated;
};

const Pair kSpanish[] = {
#include "gdx_i18n_es.inc"
};

std::mutex sMutex;
std::unordered_map<std::string_view, const char*> sTable;
std::deque<std::string> sComposed; // stable storage for "translated##id" labels
bool sLoaded = false;
bool sSpanish = true;

void Load() {
    sLoaded = true;
    sSpanish = CVarGetInteger("gGZ64.Language", 0) == 0;
    if (!sSpanish) {
        return;
    }
    for (const Pair& pair : kSpanish) {
        sTable.emplace(pair.english, pair.translated);
    }
}

} // namespace

const char* GdxTr(const char* english) {
    if (english == nullptr || english[0] == '\0') {
        return english;
    }
    std::lock_guard<std::mutex> lock(sMutex);
    if (!sLoaded) {
        Load();
    }
    if (!sSpanish) {
        return english;
    }
    auto hit = sTable.find(english);
    if (hit != sTable.end()) {
        return hit->second;
    }
    // "Label##id": translate the visible part, keep the ID, remember the result.
    const char* id = std::strstr(english, "##");
    if (id != nullptr && id != english) {
        auto label = sTable.find(std::string_view(english, static_cast<size_t>(id - english)));
        if (label != sTable.end()) {
            sComposed.emplace_back(english); // the key must outlive a caller's temporary
            const std::string_view key = sComposed.back();
            sComposed.emplace_back(std::string(label->second) + id);
            const char* composed = sComposed.back().c_str();
            sTable.emplace(key, composed);
            return composed;
        }
    }
    return english;
}
