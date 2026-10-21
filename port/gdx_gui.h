#pragma once

#include "fast/Fast3dGui.h"

struct ImFont;

/**
 * Fast3D GUI specialization that installs the fonts used by the modern G-Diffuser menu before
 * the renderer backend uploads the ImGui font atlas.
 */
class GdxFast3dGui final : public Fast::Fast3dGui {
  public:
    using Fast3dGui::Fast3dGui;

  protected:
    void ImGuiWMInit() override;
    void ImGuiWMNewFrame() override;
};

// Size factor for the menu on this screen: 1 on desktop; on Android it follows the display
// height (2 at 1080p), so text and touch targets keep a usable physical size.
float GdxUiScale();

ImFont* GdxGuiFontStandard();
ImFont* GdxGuiFontLarge();
ImFont* GdxGuiFontMono();
