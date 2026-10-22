// port/gdx_menu_bridge.cpp -- G-Zero 64: G-Diffuser's menu registry for the Android quick menu.
//
// The Android menu (QuickMenuDialog.java) draws its own rows but gets their content from here: the
// same sections, pages and controls the desktop menu registers, with their current values, ranges,
// translated labels and disable state. Changing a row goes through BridgeSet/BridgePress, which do
// what using the control in the desktop menu does, callback included. Custom-drawn blocks (tables,
// file lists, editors) have no generic form and are left out. Game thread only.
#include "gdx_menu.h"
#include "gdx_i18n.h"
#include "gdx_menu_internal.h"
#include "gdx_achievements.h"
#include "gdx_workshop.h"
#include "gz64_htc.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <string>
#include <vector>

#include "libultraship/bridge/consolevariablebridge.h"

using gdxmenu::GdxSaveCvars;

namespace {

void AppendEscaped(std::string& out, const char* text) {
    out += '"';
    for (const char* c = text != nullptr ? text : ""; *c != '\0'; ++c) {
        switch (*c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': break;
            case '\t': out += ' '; break;
            default:
                if (static_cast<unsigned char>(*c) < 0x20) {
                    out += ' ';
                } else {
                    out += *c;
                }
        }
    }
    out += '"';
}

void AppendNumber(std::string& out, double value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%.6g", std::isfinite(value) ? value : 0.0);
    out += buffer;
}

// Visible label: the part before an ImGui "##id" suffix.
std::string Label(const std::string& name) {
    const size_t id = name.find("##");
    return id == std::string::npos ? name : name.substr(0, id);
}

// Controls of the desktop menu itself, meaningless in the Android one.
bool SkipOnAndroid(const GdxUI::WidgetInfo& widget) {
    // Frame Interpolation crashes the GLES build; GameActivity also forces it off at start.
    static const char* const kNames[] = { "Menu Settings", "Menu background opacity", "Menu controller navigation",
                                          "Frame Interpolation (EXPERIMENTAL)",
                                          "Open or close this menu with F1, Escape, or Gamepad Back.",
                                          "Developer tools can be embedded in this menu or popped out into independent windows.",
                                          "A popped-out window opens on top of this menu; close the menu (F1) to use it.",
                                          "Windowing", "Multi-viewport docking",
                                          // The game text is English or Japanese only; the menu
                                          // follows the system language.
                                          "Idioma / Language" };
    for (const char* name : kNames) {
        if (widget.name == GdxTr(name)) {
            return true;
        }
    }
    return false;
}

// Settings -> Shaders draws its preset list as a custom ImGui block; the Android menu gets a plain
// combo: the built-in modes, then the presets G-Zero 64 ships in shaders/gz64 (libretro slang
// shaders chosen to run well on handhelds; hd/ ones work on the full frame, retro/ on N64 size).
constexpr const char* kShaderId = "special/shader";
constexpr const char* kShaderItems[] = { "Off", "Scanlines", "Scanlines + grille" };
struct ShaderPreset {
    const char* path; // PostPipeline value, relative to shaders/
    const char* label;
};
constexpr ShaderPreset kShaderPresets[] = {
    { "gz64/hd/sharpen-light.slangp", "HD - Light sharpening" },
    { "gz64/hd/smaa.slangp", "HD - SMAA anti-aliasing" },
    { "gz64/hd/smaa-sharpen.slangp", "HD - SMAA + sharpening" },
    { "gz64/hd/vivid-colors.slangp", "HD - Vivid colours" },
    { "gz64/retro/crt-sharp.slangp", "Retro - Sharp CRT" },
};
constexpr int kShaderBuiltins = sizeof(kShaderItems) / sizeof(kShaderItems[0]);
constexpr int kShaderChoices = kShaderBuiltins + sizeof(kShaderPresets) / sizeof(kShaderPresets[0]);

bool IsShaderPreset(const GdxUI::WidgetInfo& widget) {
    return widget.type == GdxUI::WIDGET_CUSTOM && widget.name == GdxTr("Shader preset");
}

// Index in kShaderItems + kShaderPresets, or kShaderChoices for any other preset (desktop menu).
int ShaderSelection() {
    const char* pipeline = CVarGetString("gEnhancements.Graphics.PostPipeline", "");
    const char* custom = CVarGetString("gEnhancements.Graphics.CustomShader", "");
    if (pipeline != nullptr && pipeline[0] != '\0') {
        for (int i = kShaderBuiltins; i < kShaderChoices; ++i) {
            if (std::strcmp(pipeline, kShaderPresets[i - kShaderBuiltins].path) == 0) {
                return i;
            }
        }
        return kShaderChoices;
    }
    if (custom != nullptr && custom[0] != '\0') {
        return kShaderChoices;
    }
    const int crt = CVarGetInteger("gEnhancements.Graphics.CRTShader", 0);
    return crt >= 1 && crt <= 2 ? crt : 0;
}

// ── Android layout ──────────────────────────────────────────────────────────────────────────────

struct AndroidPage {
    std::string name; // English, translated on output
    std::string key;  // pages the Java side adds its own rows to ("display", "mods", ...)
    std::string rows;
    int controls = 0;
};

struct AndroidSection {
    std::string name;
    std::deque<AndroidPage> pages; // deque: Page() hands out references that must survive growth
};

AndroidSection& Section(std::deque<AndroidSection>& layout, const std::string& name) {
    for (AndroidSection& section : layout) {
        if (section.name == name) {
            return section;
        }
    }
    layout.push_back(AndroidSection{ name, {} });
    return layout.back();
}

AndroidPage& Page(AndroidSection& section, const std::string& name, const std::string& key) {
    for (AndroidPage& page : section.pages) {
        if (page.name == name) {
            if (page.key.empty()) {
                page.key = key;
            }
            return page;
        }
    }
    section.pages.push_back(AndroidPage{ name, key, "", 0 });
    return section.pages.back();
}

AndroidPage* FindPage(AndroidSection& section, const std::string& key) {
    for (AndroidPage& page : section.pages) {
        if (page.key == key) {
            return &page;
        }
    }
    return &section.pages.front();
}

void AddRow(AndroidPage& page, const std::string& row) {
    if (row.find("\"type\":\"header\"") == std::string::npos && row.find("\"type\":\"text\"") == std::string::npos) {
        ++page.controls;
    }
    page.rows += page.rows.empty() ? "" : ",";
    page.rows += row;
}

bool Named(const GdxUI::WidgetInfo& widget, const char* const* names, size_t count) {
    for (size_t i = 0; i < count; ++i) {
        if (widget.name == GdxTr(names[i])) {
            return true;
        }
    }
    return false;
}

// Picture page of a G-Diffuser control ("display", "enhancements", "shaders"), nullptr for one
// that stays where G-Diffuser has it, "" for one dropped because the Android rows replace it.
const char* PictureTarget(const std::string& section, const std::string& page, const GdxUI::WidgetInfo& widget) {
    static const char* const kReplaced[] = {
        "MSAA",                         // part of Anti-aliasing (FXAA / MSAA)
        "Widescreen (16:9)",            // Picture format (automatic / 16:9 / 4:3) sets both
        "True widescreen HUD/UI",
        "Visual Enhancements",          // headers of pages that are now split
        "Enhancements (parity-gated)",
        "VSync",                        // the Android display decides both
        "Fullscreen",
        "Post-process shaders moved to the Shaders page.",
        "Show FPS counter overlay",     // the Java FPS counter (Display page) replaces the ImGui one
    };
    static const char* const kDisplay[] = {
        "Widescreen split-screen HUD", "Widescreen test drive", "Ultrawide (21:9+)", "Remove black borders",
        "Hide race intro curtain", "Clip off-screen course previews",
    };
    const bool picturePage = (section == "Settings" && (page == "Graphics" || page == "Shaders")) ||
                             (section == "Enhancements" && page == "Visuals") ||
                             (section == "Dev Tools" && page == "Stats");
    if (!picturePage) {
        return nullptr;
    }
    if (Named(widget, kReplaced, sizeof(kReplaced) / sizeof(kReplaced[0]))) {
        return "";
    }
    if (Named(widget, kDisplay, sizeof(kDisplay) / sizeof(kDisplay[0]))) {
        return "display";
    }
    if (page == "Stats") {
        return ""; // the FPS counter moves to Display; the rest is desktop diagnostics
    }
    return page == "Shaders" ? "shaders" : "enhancements";
}

std::string PageName(const std::string& section, const std::string& page) {
    return section == "Enhancements" && page == "Course Edit & Machine" ? "Editing" : page;
}

// Workshop -> Mods: one switch per installed .o2r pack, then Reload.
constexpr const char* kPackPrefix = "special/pack/";
constexpr const char* kReloadId = "special/reload";
std::string gReloadStatus;

void PackRows(AndroidPage& page) {
    std::string header = "{\"id\":\"\",\"type\":\"header\",\"label\":";
    AppendEscaped(header, GdxTr("Installed packs"));
    header += ",\"value\":0}";
    AddRow(page, header);
    const std::vector<GdxWorkshopPackInfo> packs = GdxWorkshopListPacks();
    if (packs.empty()) {
        std::string row = "{\"id\":\"\",\"type\":\"text\",\"label\":";
        AppendEscaped(row, GdxTr("No packs installed yet."));
        row += ",\"value\":0}";
        AddRow(page, row);
    }
    for (size_t i = 0; i < packs.size(); ++i) {
        const GdxWorkshopPackInfo& pack = packs[i];
        std::string row = "{\"id\":";
        AppendEscaped(row, (kPackPrefix + std::to_string(i)).c_str());
        row += ",\"type\":\"check\",\"label\":";
        AppendEscaped(row, (pack.name.empty() ? pack.basename : pack.name).c_str());
        std::string tip = pack.basename;
        if (pack.manifestPresent) {
            tip += "  v" + (pack.version.empty() ? std::string("?") : pack.version);
            if (!pack.author.empty()) {
                tip += "  -  " + pack.author;
            }
        }
        row += ",\"tip\":";
        AppendEscaped(row, tip.c_str());
        row += ",\"value\":";
        row += pack.disabled ? "0" : "1";
        row += '}';
        AddRow(page, row);
    }
    // The .htc pack (port/gz64_htc.cpp): its state and how many textures it has replaced so far.
    const char* htc = Gz64HtcStatus();
    if (htc[0] != '\0') {
        std::string row = "{\"id\":\"\",\"type\":\"text\",\"label\":";
        AppendEscaped(row, htc);
        row += ",\"value\":0}";
        AddRow(page, row);
    }
    std::string reload = "{\"id\":";
    AppendEscaped(reload, kReloadId);
    reload += ",\"type\":\"button\",\"label\":";
    AppendEscaped(reload, GdxTr("Reload packs"));
    reload += ",\"tip\":";
    AppendEscaped(reload, gReloadStatus.empty() ? GdxTr("Packs switched on or off apply after Reload.")
                                                : gReloadStatus.c_str());
    reload += ",\"value\":0}";
    AddRow(page, reload);
}

// Enhancements -> Achievements: a read-only line per achievement.
void AchievementRows(AndroidPage& page) {
    const uint64_t played = GdxAchievements_GetTotalPlaytimeSeconds();
    size_t unlocked = 0;
    const size_t count = GdxAchievements_Count();
    for (size_t i = 0; i < count; ++i) {
        GdxAchievementInfo info{};
        if (GdxAchievements_Get(i, &info) && info.unlocked) {
            ++unlocked;
        }
    }
    char summary[160];
    std::snprintf(summary, sizeof(summary), GdxTr("%zu of %zu unlocked  -  %llu h %02llu min played"), unlocked,
                  count, static_cast<unsigned long long>(played / 3600),
                  static_cast<unsigned long long>(played / 60 % 60));
    std::string header = "{\"id\":\"\",\"type\":\"header\",\"label\":";
    AppendEscaped(header, summary);
    header += ",\"value\":0}";
    AddRow(page, header);
    for (size_t i = 0; i < count; ++i) {
        GdxAchievementInfo info{};
        if (!GdxAchievements_Get(i, &info)) {
            continue;
        }
        std::string row = "{\"id\":\"\",\"type\":\"info\",\"label\":";
        AppendEscaped(row, info.name != nullptr ? GdxTr(info.name) : "");
        row += ",\"tip\":";
        AppendEscaped(row, info.description != nullptr ? GdxTr(info.description) : "");
        row += ",\"value\":";
        row += info.unlocked ? "1" : "0";
        row += '}';
        AddRow(page, row);
    }
}

} // namespace

std::string GdxMenu::BridgeExportJson() {
    if (!mRegistered) {
        InitElement();
    }
    for (GdxUI::DisabledInfo& info : mDisabledInfo) {
        if (info.evaluation != nullptr) {
            info.active = info.evaluation(info);
        }
    }

    // One control as a JSON row, or "" when it is hidden or has no Android form.
    auto rowJson = [this](const std::string& section, const std::string& page, size_t col, size_t index,
                          GdxUI::WidgetInfo& widget) -> std::string {
        // Same order as MenuDrawItem: hide reasons and preFunc decide existence (and
        // load pointer-backed values), then the active disable reasons.
        widget.ResetDisables();
        for (GdxUI::DisableOption reason : widget.hideWhen) {
            if (mDisabledInfo[reason].active) {
                widget.isHidden = true;
            }
        }
        if (widget.preFunc != nullptr) {
            widget.preFunc(widget);
        }
        if (widget.isHidden) {
            return std::string();
        }
        std::string reason;
        for (GdxUI::DisableOption option : widget.disableWhen) {
            if (mDisabledInfo[option].active) {
                reason += reason.empty() ? "" : " ";
                reason += mDisabledInfo[option].reason;
            }
        }
        if (reason.empty() && widget.options != nullptr && widget.options->disabled) {
            reason = " ";
        }

        const char* type = nullptr;
        double value = 0.0, min = 0.0, max = 1.0, step = 1.0;
        const char* format = "";
        bool percent = false;
        switch (widget.type) {
            case GdxUI::WIDGET_SEPARATOR_TEXT:
                type = "header";
                break;
            case GdxUI::WIDGET_TEXT:
            case GdxUI::WIDGET_TEXT_DISABLED:
                type = "text";
                break;
            case GdxUI::WIDGET_CVAR_CHECKBOX: {
                auto options = std::static_pointer_cast<UIWidgets::CheckboxOptions>(widget.options);
                type = "check";
                value = CVarGetInteger(widget.cVar, options->defaultValue ? 1 : 0) != 0 ? 1 : 0;
                break;
            }
            case GdxUI::WIDGET_CHECKBOX: {
                bool* pointer = std::get<bool*>(widget.valuePointer);
                if (pointer != nullptr) {
                    type = "check";
                    value = *pointer ? 1 : 0;
                }
                break;
            }
            case GdxUI::WIDGET_CVAR_COMBOBOX: {
                auto options = std::static_pointer_cast<UIWidgets::ComboboxOptions>(widget.options);
                type = "combo";
                value = CVarGetInteger(widget.cVar, static_cast<int32_t>(options->defaultIndex));
                break;
            }
            case GdxUI::WIDGET_COMBOBOX: {
                int32_t* pointer = std::get<int32_t*>(widget.valuePointer);
                if (pointer != nullptr) {
                    type = "combo";
                    value = *pointer;
                }
                break;
            }
            case GdxUI::WIDGET_CVAR_SLIDER_INT:
            case GdxUI::WIDGET_SLIDER_INT: {
                auto options = std::static_pointer_cast<UIWidgets::IntSliderOptions>(widget.options);
                min = options->min;
                max = options->max;
                step = options->step > 0 ? options->step : 1;
                format = options->format;
                if (widget.type == GdxUI::WIDGET_CVAR_SLIDER_INT) {
                    type = "int";
                    value = CVarGetInteger(widget.cVar, options->defaultValue);
                } else if (int32_t* pointer = std::get<int32_t*>(widget.valuePointer)) {
                    type = "int";
                    value = *pointer;
                }
                break;
            }
            case GdxUI::WIDGET_CVAR_SLIDER_FLOAT:
            case GdxUI::WIDGET_SLIDER_FLOAT: {
                auto options = std::static_pointer_cast<UIWidgets::FloatSliderOptions>(widget.options);
                min = options->min;
                max = options->max;
                step = options->step > 0 ? options->step : 0.01;
                format = options->format;
                percent = options->isPercentage;
                if (widget.type == GdxUI::WIDGET_CVAR_SLIDER_FLOAT) {
                    type = "float";
                    value = CVarGetFloat(widget.cVar, options->defaultValue);
                } else if (float* pointer = std::get<float*>(widget.valuePointer)) {
                    type = "float";
                    value = *pointer;
                }
                break;
            }
            case GdxUI::WIDGET_CVAR_RADIO_BUTTON:
                type = "radio";
                value = CVarGetInteger(widget.cVar, 0) == widget.radioValue ? 1 : 0;
                break;
            case GdxUI::WIDGET_BUTTON:
                type = "button";
                break;
            default:
                if (IsShaderPreset(widget)) {
                    type = "combo";
                    value = ShaderSelection();
                }
                break; // separators, other custom blocks, "coming soon"
        }
        if (type == nullptr || (widget.name.empty() && widget.type != GdxUI::WIDGET_BUTTON)) {
            return std::string();
        }
        const bool shader = IsShaderPreset(widget);
        std::string row = "{\"id\":";
        AppendEscaped(row, shader ? kShaderId : (section + "/" + page + "/" + std::to_string(col) + "/" +
                            std::to_string(index))
                               .c_str());
        row += ",\"type\":\"";
        row += type;
        row += "\",\"label\":";
        AppendEscaped(row, Label(widget.name).c_str());
        if (widget.options != nullptr && widget.options->tooltip != nullptr &&
            widget.options->tooltip[0] != '\0') {
            row += ",\"tip\":";
            AppendEscaped(row, widget.options->tooltip);
        }
        if (widget.note != nullptr && widget.note[0] != '\0') {
            row += ",\"note\":";
            AppendEscaped(row, widget.note);
        }
        if (!reason.empty()) {
            row += ",\"disabled\":";
            AppendEscaped(row, reason.c_str());
        }
        row += ",\"value\":";
        AppendNumber(row, value);
        if (std::string(type) == "int" || std::string(type) == "float") {
            row += ",\"min\":";
            AppendNumber(row, min);
            row += ",\"max\":";
            AppendNumber(row, max);
            row += ",\"step\":";
            AppendNumber(row, step);
            row += ",\"format\":";
            AppendEscaped(row, format);
            if (percent) {
                row += ",\"percent\":true";
            }
        }
        if (shader) {
            row += ",\"items\":[";
            for (const char* item : kShaderItems) {
                AppendEscaped(row, GdxTr(item));
                row += ',';
            }
            for (const ShaderPreset& preset : kShaderPresets) {
                AppendEscaped(row, GdxTr(preset.label));
                row += ',';
            }
            if (value == kShaderChoices) {
                AppendEscaped(row, GdxTr("Custom preset"));
            } else {
                row.pop_back();
            }
            row += "],\"tip\":";
            AppendEscaped(row, GdxTr("HD presets work on the full image; Retro ones on the console's own "
                                     "resolution. While one is on, FSR and FXAA are off."));
        } else if (std::string(type) == "combo") {
            row += ",\"items\":[";
            for (size_t i = 0; i < widget.comboItems.size(); ++i) {
                if (i > 0) {
                    row += ',';
                }
                AppendEscaped(row, widget.comboItems[i]);
            }
            row += ']';
        }
        row += '}';
        return row;
    };

    // The Android layout: G-Diffuser's pages, except that every picture setting is gathered in one
    // Picture section (display / graphics enhancements / shaders), duplicates of the Android-only
    // rows are dropped, and the custom-drawn pack and achievement lists get plain rows.
    std::deque<AndroidSection> layout;
    AndroidSection& picture = Section(layout, "Picture");
    Page(picture, "Display", "display");
    Page(picture, "Graphics enhancements", "enhancements");
    Page(picture, "Shaders", "shaders");

    for (const std::string& sectionName : mMenuOrder) {
        auto section = mMenuEntries.find(sectionName);
        if (section == mMenuEntries.end()) {
            continue;
        }
        for (const std::string& pageName : section->second.sidebarOrder) {
            auto page = section->second.sidebars.find(pageName);
            if (page == section->second.sidebars.end()) {
                continue;
            }
            // Mouse controls and the Discord status have nothing to work with on Android.
            if (sectionName == "Online" || (sectionName == "Enhancements" && pageName == "Mouse")) {
                continue;
            }
            // The input viewer overlay gives way to the touch controls, all Java (QuickMenuDialog).
            if (sectionName == "Settings" && pageName == "Input Viewer") {
                Page(Section(layout, sectionName), "Touch controls", "touch");
                continue;
            }
            const bool achievements = sectionName == "Enhancements" && pageName == "Achievements";
            const bool mods = sectionName == "Workshop" && pageName == "Mods";
            for (size_t col = 0; col < page->second.columnWidgets.size(); ++col) {
                auto& column = page->second.columnWidgets[col];
                for (size_t index = 0; index < column.size(); ++index) {
                    GdxUI::WidgetInfo& widget = column[index];
                    if (SkipOnAndroid(widget)) {
                        continue;
                    }
                    std::string row = rowJson(sectionName, pageName, col, index, widget);
                    if (row.empty()) {
                        continue;
                    }
                    const char* target = PictureTarget(sectionName, pageName, widget);
                    if (target != nullptr && target[0] == '\0') {
                        continue;
                    }
                    if (target != nullptr) {
                        AddRow(*FindPage(picture, target), row);
                    } else {
                        AddRow(Page(Section(layout, sectionName), PageName(sectionName, pageName),
                                    mods ? "mods" : ""),
                               row);
                    }
                }
            }
            if (mods) {
                PackRows(Page(Section(layout, sectionName), pageName, "mods"));
            }
            if (achievements) {
                AchievementRows(Page(Section(layout, sectionName), pageName, "achievements"));
            }
        }
    }

    std::string out = "{\"sections\":[";
    bool firstSection = true;
    for (const AndroidSection& section : layout) {
        std::string pages;
        for (const AndroidPage& page : section.pages) {
            // A page with only headers and notes, or nothing the Java side adds to, is left out.
            if (page.controls == 0 && page.key.empty()) {
                continue;
            }
            pages += pages.empty() ? "" : ",";
            pages += "{\"name\":";
            AppendEscaped(pages, GdxTr(page.name));
            pages += ",\"key\":";
            AppendEscaped(pages, page.key.c_str());
            pages += ",\"rows\":[" + page.rows + "]}";
        }
        if (pages.empty()) {
            continue;
        }
        out += firstSection ? "" : ",";
        out += "{\"name\":";
        AppendEscaped(out, GdxTr(section.name));
        out += ",\"pages\":[" + pages + "]}";
        firstSection = false;
    }
    out += "]}";
    return out;
}

static GdxUI::WidgetInfo* FindWidget(std::unordered_map<std::string, GdxUI::MainMenuEntry>& entries,
                                     const std::string& id) {
    const size_t a = id.find('/');
    const size_t b = a == std::string::npos ? a : id.find('/', a + 1);
    const size_t c = b == std::string::npos ? b : id.find('/', b + 1);
    if (c == std::string::npos) {
        return nullptr;
    }
    auto section = entries.find(id.substr(0, a));
    if (section == entries.end()) {
        return nullptr;
    }
    auto page = section->second.sidebars.find(id.substr(a + 1, b - a - 1));
    if (page == section->second.sidebars.end()) {
        return nullptr;
    }
    const size_t col = std::stoul(id.substr(b + 1, c - b - 1));
    const size_t index = std::stoul(id.substr(c + 1));
    if (col >= page->second.columnWidgets.size() || index >= page->second.columnWidgets[col].size()) {
        return nullptr;
    }
    return &page->second.columnWidgets[col][index];
}

bool GdxMenu::BridgeSet(const std::string& id, double value) {
    if (id.rfind(kPackPrefix, 0) == 0) {
        const size_t index = std::strtoul(id.c_str() + std::strlen(kPackPrefix), nullptr, 10);
        const std::vector<GdxWorkshopPackInfo> packs = GdxWorkshopListPacks();
        if (index >= packs.size()) {
            return false;
        }
        GdxWorkshopSetPackDisabled(packs[index].id.c_str(), packs[index].basename.c_str(), value != 0.0 ? 0 : 1);
        return true;
    }
    if (id == kShaderId) {
        const long selection = std::lround(value);
        if (selection < 0 || selection >= kShaderChoices) {
            return false; // "Custom preset" is only chosen from the desktop menu
        }
        const bool builtin = selection < kShaderBuiltins;
        CVarSetInteger("gEnhancements.Graphics.CRTShader", builtin ? static_cast<int32_t>(selection) : 0);
        CVarSetString("gEnhancements.Graphics.CustomShader", "");
        CVarSetString("gEnhancements.Graphics.PostPipeline",
                      builtin ? "" : kShaderPresets[selection - kShaderBuiltins].path);
        GdxSaveCvars();
        return true;
    }
    GdxUI::WidgetInfo* widget = FindWidget(mMenuEntries, id);
    if (widget == nullptr) {
        return false;
    }
    if (widget->preFunc != nullptr) {
        widget->preFunc(*widget); // pointer-backed controls load their current value first
    }
    switch (widget->type) {
        case GdxUI::WIDGET_CVAR_CHECKBOX:
            CVarSetInteger(widget->cVar, value != 0.0 ? 1 : 0);
            break;
        case GdxUI::WIDGET_CHECKBOX:
            if (bool* pointer = std::get<bool*>(widget->valuePointer)) {
                *pointer = value != 0.0;
            }
            break;
        case GdxUI::WIDGET_CVAR_COMBOBOX:
            CVarSetInteger(widget->cVar, static_cast<int32_t>(std::lround(value)));
            break;
        case GdxUI::WIDGET_COMBOBOX:
            if (int32_t* pointer = std::get<int32_t*>(widget->valuePointer)) {
                *pointer = static_cast<int32_t>(std::lround(value));
            }
            break;
        case GdxUI::WIDGET_CVAR_SLIDER_INT:
            CVarSetInteger(widget->cVar, static_cast<int32_t>(std::lround(value)));
            break;
        case GdxUI::WIDGET_SLIDER_INT:
            if (int32_t* pointer = std::get<int32_t*>(widget->valuePointer)) {
                *pointer = static_cast<int32_t>(std::lround(value));
            }
            break;
        case GdxUI::WIDGET_CVAR_SLIDER_FLOAT:
            CVarSetFloat(widget->cVar, static_cast<float>(value));
            break;
        case GdxUI::WIDGET_SLIDER_FLOAT:
            if (float* pointer = std::get<float*>(widget->valuePointer)) {
                *pointer = static_cast<float>(value);
            }
            break;
        case GdxUI::WIDGET_CVAR_RADIO_BUTTON:
            CVarSetInteger(widget->cVar, widget->radioValue);
            break;
        default:
            return false;
    }
    if (widget->callback != nullptr) {
        widget->callback(*widget);
    }
    GdxSaveCvars();
    return true;
}

bool GdxMenu::BridgePress(const std::string& id) {
    if (id == kReloadId) {
        char status[160] = "";
        GdxWorkshopReload(status, sizeof(status));
        gReloadStatus = status;
        return true;
    }
    GdxUI::WidgetInfo* widget = FindWidget(mMenuEntries, id);
    if (widget == nullptr || widget->type != GdxUI::WIDGET_BUTTON || widget->callback == nullptr) {
        return false;
    }
    widget->callback(*widget);
    GdxSaveCvars();
    return true;
}
