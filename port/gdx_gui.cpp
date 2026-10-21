#include "gdx_gui.h"

#include <imgui.h>
#include <SDL2/SDL.h>

#include "ship/Context.h"
#include "ship/window/gui/Fonts.h"
#include "ship/window/gui/IconsFontAwesome4.h"

#include "gdx_imgui_nav.h"

#ifdef __ANDROID__
extern "C" void gdx_android_bridge_tick(void);
#endif

namespace {

ImFont* sFontStandard = nullptr;
ImFont* sFontLarge = nullptr;
ImFont* sFontMono = nullptr;

float sUiScale = 1.0f;

ImFont* LoadFontWithIcons(const std::string& path, float size) {
    ImGuiIO& io = ImGui::GetIO();
    ImFontConfig textConfig;
    textConfig.OversampleH = 2;
    textConfig.OversampleV = 2;

    ImFont* font = io.Fonts->AddFontFromFileTTF(path.c_str(), size, &textConfig);
    if (font == nullptr) {
        return nullptr;
    }

    static const ImWchar iconRanges[] = { ICON_MIN_FA, ICON_MAX_16_FA, 0 };
    ImFontConfig iconConfig;
    iconConfig.MergeMode = true;
    iconConfig.PixelSnapH = true;
    iconConfig.GlyphMinAdvanceX = size * 2.0f / 3.0f;
    io.Fonts->AddFontFromMemoryCompressedBase85TTF(fontawesome_compressed_data_base85, size * 2.0f / 3.0f,
                                                   &iconConfig, iconRanges);
    return font;
}

} // namespace

void GdxFast3dGui::ImGuiWMInit() {
    // The renderer backend is initialized after this virtual returns, so fonts added here land in
    // its first font-atlas upload.
    Fast::Fast3dGui::ImGuiWMInit();

#ifdef __ANDROID__
    // libultraship doubles the style and stretches the font bitmaps 2x on Android. Rasterize the
    // fonts at the real size instead (sharp text) and rescale the style to this screen.
    SDL_DisplayMode mode;
    if (SDL_GetCurrentDisplayMode(0, &mode) == 0 && mode.h > 0) {
        const int shortSide = mode.w < mode.h ? mode.w : mode.h;
        sUiScale = static_cast<float>(shortSide) / 540.0f;
        sUiScale = sUiScale < 1.5f ? 1.5f : (sUiScale > 3.0f ? 3.0f : sUiScale);
    } else {
        sUiScale = 2.0f;
    }
    ImGui::GetStyle().ScaleAllSizes(sUiScale / 2.0f);
    ImGui::GetStyle().ScrollbarSize *= 1.5f; // a finger-sized scrollbar
    ImGui::GetIO().FontGlobalScale = 1.0f;
#endif
    const std::string fontRoot = Ship::Context::GetPathRelativeToAppDirectory("fonts/");
    sFontStandard = LoadFontWithIcons(fontRoot + "Montserrat-Regular.ttf", 18.0f * sUiScale);
    sFontLarge = LoadFontWithIcons(fontRoot + "Montserrat-Regular.ttf", 22.0f * sUiScale);
    sFontMono = LoadFontWithIcons(fontRoot + "Inconsolata-Regular.ttf", 17.0f * sUiScale);

    // Missing loose assets must never block boot. ImGui's default font already includes Font
    // Awesome through libultraship, so it is a complete fallback.
    ImFont* fallback = ImGui::GetIO().Fonts->Fonts.empty() ? nullptr : ImGui::GetIO().Fonts->Fonts[0];
    if (sFontStandard == nullptr) {
        sFontStandard = fallback;
    }
    if (sFontLarge == nullptr) {
        sFontLarge = sFontStandard;
    }
    if (sFontMono == nullptr) {
        sFontMono = sFontStandard;
    }
    if (sFontStandard != nullptr) {
        ImGui::GetIO().FontDefault = sFontStandard;
    }
}

void GdxFast3dGui::ImGuiWMNewFrame() {
    Fast::Fast3dGui::ImGuiWMNewFrame();
    // Must land between the platform backend's gamepad poll (just above) and ImGui::NewFrame, which
    // the caller runs next: the feed both reads the backend's HasGamepad claim and queues key events
    // for this frame. See port/gdx_imgui_nav.h.
    gdx_imgui_nav_tick();
#ifdef __ANDROID__
    gdx_android_bridge_tick(); // the quick menu's queued setting reads and writes
#endif
}

float GdxUiScale() {
    return sUiScale;
}

ImFont* GdxGuiFontStandard() {
    return sFontStandard;
}

ImFont* GdxGuiFontLarge() {
    return sFontLarge;
}

ImFont* GdxGuiFontMono() {
    return sFontMono;
}
