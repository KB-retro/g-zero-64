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

#include <cmath>
#include <cstdio>
#include <string>

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
    static const char* const kNames[] = { "Menu Settings", "Menu background opacity", "Menu controller navigation" };
    for (const char* name : kNames) {
        if (widget.name == GdxTr(name)) {
            return true;
        }
    }
    return false;
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

    std::string out = "{\"sections\":[";
    bool firstSection = true;
    for (const std::string& sectionName : mMenuOrder) {
        auto section = mMenuEntries.find(sectionName);
        if (section == mMenuEntries.end()) {
            continue;
        }
        std::string pages;
        bool firstPage = true;
        for (const std::string& pageName : section->second.sidebarOrder) {
            auto page = section->second.sidebars.find(pageName);
            if (page == section->second.sidebars.end()) {
                continue;
            }
            std::string rows;
            bool firstRow = true;
            int controls = 0;
            for (size_t col = 0; col < page->second.columnWidgets.size(); ++col) {
                auto& column = page->second.columnWidgets[col];
                for (size_t index = 0; index < column.size(); ++index) {
                    GdxUI::WidgetInfo& widget = column[index];
                    if (SkipOnAndroid(widget)) {
                        continue;
                    }
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
                        continue;
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
                            break; // separators, custom blocks, "coming soon"
                    }
                    if (type == nullptr || (widget.name.empty() && widget.type != GdxUI::WIDGET_BUTTON)) {
                        continue;
                    }
                    if (std::string(type) != "header" && std::string(type) != "text") {
                        ++controls;
                    }

                    std::string row = "{\"id\":";
                    AppendEscaped(row, (sectionName + "/" + pageName + "/" + std::to_string(col) + "/" +
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
                    if (std::string(type) == "combo") {
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
                    rows += firstRow ? "" : ",";
                    rows += row;
                    firstRow = false;
                }
            }
            if (controls == 0) {
                continue; // a page made only of custom blocks has nothing to show here
            }
            pages += firstPage ? "" : ",";
            pages += "{\"name\":";
            AppendEscaped(pages, GdxTr(pageName));
            pages += ",\"rows\":[" + rows + "]}";
            firstPage = false;
        }
        if (firstPage) {
            continue;
        }
        out += firstSection ? "" : ",";
        out += "{\"name\":";
        AppendEscaped(out, GdxTr(sectionName));
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
    GdxUI::WidgetInfo* widget = FindWidget(mMenuEntries, id);
    if (widget == nullptr || widget->type != GdxUI::WIDGET_BUTTON || widget->callback == nullptr) {
        return false;
    }
    widget->callback(*widget);
    GdxSaveCvars();
    return true;
}
