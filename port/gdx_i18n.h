// port/gdx_i18n.h -- menu translation for G-Zero 64.
//
// GdxTr() maps an English menu string, exactly as written in the source, to the active language.
// Spanish is the default; gGZ64.Language = 1 keeps English. The language is read once, so a change
// applies on restart. Strings without a translation come back unchanged, and an ImGui ID suffix
// ("Label##id") is kept while the label part is translated.
#pragma once

#include <string>

const char* GdxTr(const char* english);
inline const char* GdxTr(const std::string& english) {
    return GdxTr(english.c_str());
}
