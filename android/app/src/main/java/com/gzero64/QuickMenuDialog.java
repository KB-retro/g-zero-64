package com.gzero64;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * In-game quick menu (Back). Left: pages grouped by section; right: the page's rows; bottom: the
 * focused row's explanation, Continue (green, left) and Exit (red, right).
 *
 * The first page is G-Zero 64's own picture settings (format, FSR, anti-aliasing). Every other page
 * comes from G-Diffuser's menu through GameBridge.nativeMenuJson(), so its options, ranges, labels
 * and disable rules are the game's own, and changing a row runs the game's own code for it. The game
 * holds still while the menu is open (GameBridge.nativeSetMenuOpen).
 */
final class QuickMenuDialog {
    /** A row of the G-Zero 64 page: one label, n values, cycled with left/right or the arrows. */
    private interface Choice {
        String[] values();
        int get();
        void set(int value);
    }

    // G-Zero 64's own rows (Picture section): CVar name, float?, default.
    private static final String WIDESCREEN = "gEnhancements.Graphics.Widescreen";
    private static final String WIDESCREEN_UI = "gEnhancements.Graphics.WidescreenUI";
    private static final String FSR = "gGZ64.Fsr";
    private static final String FSR_SHARPNESS = "gGZ64.FsrSharpness";
    private static final String FXAA = "gGZ64.Fxaa";
    private static final String MSAA = "gMSAAValue";
    private static final String XBRZ = "gGZ64.Xbrz";
    private static final String HIRES_PACK = "gGZ64.HiresPack";
    // Picture effects (Effects page), applied by the renderer's G-Zero 64 pass.
    private static final String[] EFFECTS = {
        "gGZ64.Bloom", "gGZ64.Hdr", "gGZ64.Vibrance", "gGZ64.Style", "gGZ64.Vignette", "gGZ64.SpeedBlur", "gGZ64.Cas"
    };
    private static final int EFFECT_STYLE = 3;
    private static final float[] LEVELS = {0f, 0.35f, 0.65f, 1f};
    // Profiles in EFFECTS order: light, modern, neon, cinema (style indexes R.array.quick_effect_styles).
    private static final float[][] PROFILES = {
        {0f, 0f, 0f, 0f, 0f, 0f, 0f},
        {0.35f, 0.35f, 0.35f, 0f, 0f, 0f, 0.35f},
        {0.65f, 0.65f, 0.35f, 0f, 0.35f, 0.35f, 0.65f},
        {1f, 0.65f, 0.65f, 4f, 0.35f, 0.65f, 0.35f},
        {0.35f, 0.65f, 0f, 1f, 0.65f, 0f, 0.35f},
    };
    private static final Object[][] SETTINGS = {
        {FSR, false, 0f}, {FSR_SHARPNESS, true, 0.2f}, {FXAA, false, 0f}, {MSAA, false, 1f}, {XBRZ, false, 0f}, {HIRES_PACK, false, 0f},
        {"gGZ64.Bloom", true, 0f}, {"gGZ64.Hdr", true, 0f}, {"gGZ64.Vibrance", true, 0f}, {"gGZ64.Style", false, 0f},
        {"gGZ64.Vignette", true, 0f}, {"gGZ64.SpeedBlur", true, 0f}, {"gGZ64.Cas", true, 0f},
    };
    private static final int[] XBRZ_FACTORS = {0, 2, 3, 4};
    static final int PICK_MODS = 6401;
    private static final String RELOAD_PACKS = "special/reload";
    /** The open menu, for the mod picker's result. */
    private static QuickMenuDialog current;
    private static final float[] SHARPNESS = {1.0f, 0.5f, 0.0f}; // AMD stops: 0 = sharpest

    private final GameActivity activity;
    private final Dialog dialog;
    private final Handler ui = new Handler(Looper.getMainLooper());
    // One thread for the app's lifetime: the menu opens and closes many times.
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Float> values = new HashMap<>();
    private final LinearLayout nav;
    private final LinearLayout rows;
    private final ScrollView rowScroll;
    private final TextView pageTitle;
    private final TextView footer;
    private final List<String> pageNames = new ArrayList<>();
    private final List<JSONObject> pages = new ArrayList<>(); // null entry: game not loaded, own rows only
    private int currentPage;
    private boolean busy;

    private QuickMenuDialog(GameActivity activity) {
        this.activity = activity;
        dialog = new Dialog(activity);
        nav = new LinearLayout(activity);
        rows = new LinearLayout(activity);
        rowScroll = new ScrollView(activity);
        pageTitle = new TextView(activity);
        footer = new TextView(activity);
    }

    static Dialog show(GameActivity activity) {
        QuickMenuDialog menu = new QuickMenuDialog(activity);
        try {
            GameBridge.nativeSetMenuOpen(true);
        } catch (UnsatisfiedLinkError error) {
            Log.w("GZero64", "Game library not loaded", error);
        }
        current = menu;
        menu.build();
        menu.load(0, 0);
        return menu.dialog;
    }

    // ── Picture format (also applied at game start) ─────────────────────────────────────────────

    static void applyAutomaticFormat(GameActivity activity) {
        applyFormat(activity, format(activity));
    }

    private static int format(Context context) {
        return context.getSharedPreferences("game", Context.MODE_PRIVATE).getInt("format", 0);
    }

    /** 0 automatic (16:9 unless the screen is narrower than 3:2), 1 widescreen, 2 classic 4:3. */
    private static void applyFormat(GameActivity activity, int format) {
        boolean wide;
        if (format == 0) {
            android.graphics.Rect bounds = activity.getWindowManager().getMaximumWindowMetrics().getBounds();
            float ratio = (float) Math.max(bounds.width(), bounds.height())
                / Math.max(1, Math.min(bounds.width(), bounds.height()));
            wide = ratio >= 1.45f;
        } else {
            wide = format == 1;
        }
        try {
            GameBridge.setInt(WIDESCREEN, wide ? 1 : 0);
            GameBridge.setInt(WIDESCREEN_UI, wide ? 1 : 0);
        } catch (UnsatisfiedLinkError error) {
            Log.w("GZero64", "Game library not loaded; picture format not applied", error);
        }
    }

    // ── Data ────────────────────────────────────────────────────────────────────────────────────

    /** Reads everything from the game off the UI thread, then shows page `page` focused at `row`. */
    private void load(final int page, final int row) {
        busy = true;
        worker.execute(new Runnable() {
            @Override public void run() {
                String json = "";
                float[] read = null;
                String[] names = new String[SETTINGS.length];
                boolean[] isFloat = new boolean[SETTINGS.length];
                float[] defaults = new float[SETTINGS.length];
                for (int i = 0; i < SETTINGS.length; i++) {
                    names[i] = (String) SETTINGS[i][0];
                    isFloat[i] = (Boolean) SETTINGS[i][1];
                    defaults[i] = (Float) SETTINGS[i][2];
                }
                try {
                    read = GameBridge.nativeGet(names, isFloat, defaults);
                    json = GameBridge.nativeMenuJson();
                } catch (UnsatisfiedLinkError error) {
                    Log.w("GZero64", "Game library not loaded", error);
                }
                final float[] settings = read != null ? read : defaults;
                final String menu = json;
                ui.post(new Runnable() {
                    @Override public void run() {
                        for (int i = 0; i < names.length; i++) values.put(names[i], settings[i]);
                        parse(menu);
                        busy = false;
                        if (dialog.isShowing()) showPage(Math.min(page, pages.size() - 1), row);
                    }
                });
            }
        });
    }

    private void parse(String json) {
        pageNames.clear();
        pages.clear();
        nav.removeAllViews();
        if (json == null || json.isEmpty()) {
            addNavHeader("G-ZERO 64");
            pageNames.add(activity.getString(R.string.quick_page_gzero));
            pages.add(null);
            addNavItem(0);
            return;
        }
        try {
            JSONArray sections = new JSONObject(json).getJSONArray("sections");
            for (int s = 0; s < sections.length(); s++) {
                JSONObject section = sections.getJSONObject(s);
                addNavHeader(section.getString("name").toUpperCase(Locale.ROOT));
                JSONArray sectionPages = section.getJSONArray("pages");
                for (int p = 0; p < sectionPages.length(); p++) {
                    JSONObject page = sectionPages.getJSONObject(p);
                    pageNames.add(page.getString("name"));
                    pages.add(page);
                    addNavItem(pages.size() - 1);
                }
            }
        } catch (JSONException exception) {
            Log.e("GZero64", "Bad menu JSON", exception);
        }
    }

    private float value(String name) {
        Float value = values.get(name);
        return value != null ? value : 0f;
    }

    private static int nearest(float[] options, float value) {
        int best = 0;
        for (int i = 1; i < options.length; i++)
            if (Math.abs(options[i] - value) < Math.abs(options[best] - value)) best = i;
        return best;
    }

    // ── Layout ──────────────────────────────────────────────────────────────────────────────────

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    private void build() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF5101026);
        card.setStroke(dp(1), 0xFF4A4870);
        card.setCornerRadius(dp(4));
        panel.setBackground(card);

        TextView title = new TextView(activity);
        title.setText(R.string.quick_title);
        title.setTextSize(18);
        title.setTextColor(0xFFF3F6F7);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        panel.addView(title);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setPadding(0, dp(6), 0, dp(6));
        nav.setOrientation(LinearLayout.VERTICAL);
        ScrollView navScroll = new ScrollView(activity);
        navScroll.addView(nav);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int navWidth = Math.max(dp(150), Math.min(dp(230), metrics.widthPixels * 28 / 100));
        body.addView(navScroll, new LinearLayout.LayoutParams(navWidth, LinearLayout.LayoutParams.MATCH_PARENT));
        View divider = new View(activity);
        divider.setBackgroundColor(0xFF4A4870);
        body.addView(divider, new LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), 0, 0, 0);
        pageTitle.setTextSize(16);
        pageTitle.setTextColor(0xFFFFC452);
        pageTitle.setTypeface(Typeface.DEFAULT_BOLD);
        pageTitle.setPadding(dp(8), 0, 0, dp(4));
        right.addView(pageTitle);
        rows.setOrientation(LinearLayout.VERTICAL);
        rowScroll.addView(rows);
        right.addView(rowScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        body.addView(right, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        panel.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        footer.setTextSize(12);
        footer.setTextColor(0xFFA9B0C8);
        footer.setMaxLines(3);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        footer.setMinLines(2);
        footer.setPadding(dp(4), dp(4), dp(4), dp(4));
        panel.addView(footer);

        // Continue bottom left (green), Exit bottom right (red).
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setGravity(Gravity.CENTER_VERTICAL);
        Button resume = small(R.string.quick_resume, 0xFF2E7D32, 0xFF43A047);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        Button exit = small(R.string.quick_exit, 0xFFB71C1C, 0xFFE53935);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmExit(); }
        });
        TextView hint = new TextView(activity);
        hint.setText(R.string.quick_pages_hint);
        hint.setTextSize(11);
        hint.setTextColor(0xFF7F86A3);
        hint.setGravity(Gravity.CENTER);
        buttons.addView(resume, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        buttons.addView(hint, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        buttons.addView(exit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        panel.addView(buttons);

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            @Override public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
                    if (!busy && !pages.isEmpty()) {
                        int next = currentPage + (keyCode == KeyEvent.KEYCODE_BUTTON_R1 ? 1 : -1);
                        showPage((next + pages.size()) % pages.size(), 0);
                    }
                    return true;
                }
                return false;
            }
        });
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        dialog.show();
        if (window != null) {
            android.view.WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(android.view.WindowInsets.Type.systemBars());
            }
            // Sized from the screen, so it fits 16:9 and 4:3 alike.
            window.setLayout(metrics.widthPixels * 96 / 100, metrics.heightPixels * 94 / 100);
        }
        pageTitle.setText(R.string.quick_loading);
    }

    /** Called by GameActivity's dismiss listener: the game runs again. */
    static void closed() {
        current = null;
        try {
            GameBridge.nativeSetMenuOpen(false);
        } catch (UnsatisfiedLinkError ignored) {
        }
    }

    private void addNavHeader(String text) {
        TextView header = new TextView(activity);
        header.setText(text);
        header.setTextSize(11);
        header.setLetterSpacing(0.12f);
        header.setTextColor(0xFFFFC452);
        header.setPadding(dp(8), dp(10), dp(4), dp(2));
        nav.addView(header);
    }

    private void addNavItem(final int page) {
        TextView item = new TextView(activity);
        item.setText(pageNames.get(page));
        item.setTextSize(14);
        item.setTextColor(Color.WHITE);
        item.setPadding(dp(14), dp(8), dp(6), dp(8));
        item.setFocusable(true);
        item.setClickable(true);
        item.setTag(page);
        item.setBackground(highlight());
        item.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { if (!busy) showPage(page, 0); }
        });
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus && !busy && page != currentPage) showPage(page, -1);
            }
        });
        nav.addView(item);
    }

    private StateListDrawable highlight() {
        StateListDrawable highlight = new StateListDrawable();
        highlight.addState(new int[] {android.R.attr.state_selected}, new ColorDrawable(0x55FFC452));
        highlight.addState(new int[] {android.R.attr.state_focused}, new ColorDrawable(0x40FFC452));
        highlight.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(0x30FFC452));
        highlight.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        return highlight;
    }

    /** focusRow: -1 keeps the focus where it is (navigating the page list). */
    private void showPage(int page, int focusRow) {
        if (page < 0) return;
        currentPage = page;
        for (int i = 0; i < nav.getChildCount(); i++) {
            View child = nav.getChildAt(i);
            if (child.getTag() instanceof Integer) child.setSelected((Integer) child.getTag() == page);
        }
        pageTitle.setText(pageNames.get(page));
        rows.removeAllViews();
        footer.setText("");
        JSONObject data = pages.get(page);
        if (data == null) {
            addFormatRow();
            addScalingRows();
        } else {
            switch (data.optString("key")) {
                case "display": addFormatRow(); addFpsRow(); break;
                case "touch": addTouchRows(); break;
                case "effects": addEffectRows(); break;
                case "enhancements": addScalingRows(); break;
                case "mods": addInstallRow(); break;
                default: break;
            }
            try {
                JSONArray list = data.getJSONArray("rows");
                for (int i = 0; i < list.length(); i++) addGameRow(list.getJSONObject(i));
            } catch (JSONException exception) {
                Log.e("GZero64", "Bad page JSON", exception);
            }
        }
        if (focusRow >= 0) {
            int focusable = 0;
            for (int i = 0; i < rows.getChildCount(); i++) {
                View child = rows.getChildAt(i);
                if (!child.isFocusable()) continue;
                if (focusable++ == focusRow || i == rows.getChildCount() - 1) {
                    child.requestFocus();
                    break;
                }
            }
        }
    }

    /** Position of a row among the page's focusable rows, to restore the focus after a reload. */
    private int focusIndex(View row) {
        int index = 0;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child == row) return index;
            if (child.isFocusable()) index++;
        }
        return 0;
    }

    // ── G-Zero 64 page ──────────────────────────────────────────────────────────────────────────

    private String[] array(int res) {
        return activity.getResources().getStringArray(res);
    }

    private void addFormatRow() {
        addChoice(R.string.quick_format, R.string.quick_format_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_formats); }
            @Override public int get() { return format(activity); }
            @Override public void set(int value) {
                activity.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putInt("format", value).apply();
                applyFormat(activity, value);
            }
        });
    }

    /** FSR and anti-aliasing (G-Zero 64's own passes), at the top of Graphics enhancements. */
    private void addScalingRows() {
        addChoice(R.string.quick_xbrz, R.string.quick_xbrz_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_xbrz); }
            @Override public int get() {
                int factor = Math.round(value(XBRZ));
                for (int i = 0; i < XBRZ_FACTORS.length; i++) if (XBRZ_FACTORS[i] == factor) return i;
                return 0;
            }
            @Override public void set(int value) { setSetting(XBRZ, false, XBRZ_FACTORS[value]); }
        });
        addChoice(R.string.quick_fsr, R.string.quick_fsr_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return value(FSR) != 0 ? 1 : 0; }
            @Override public void set(int value) { setSetting(FSR, false, value); }
        });
        addChoice(R.string.quick_fsr_sharpness, R.string.quick_fsr_sharpness_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_sharpness); }
            @Override public int get() { return nearest(SHARPNESS, value(FSR_SHARPNESS)); }
            @Override public void set(int value) { setSetting(FSR_SHARPNESS, true, SHARPNESS[value]); }
        });
        addChoice(R.string.quick_antialiasing, R.string.quick_antialiasing_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_antialiasing); }
            @Override public int get() {
                int msaa = Math.round(value(MSAA));
                if (msaa >= 4) return 3;
                if (msaa == 2) return 2;
                return value(FXAA) != 0 ? 1 : 0;
            }
            @Override public void set(int value) {
                setSetting(FXAA, false, value == 1 ? 1 : 0);
                setSetting(MSAA, false, value == 3 ? 4 : value == 2 ? 2 : 1);
            }
        });
    }

    // ── Installing packs ────────────────────────────────────────────────────────────────────────

    private void addFpsRow() {
        addChoice(R.string.quick_fps, 0, new Choice() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return activity.fpsOverlayVisible() ? 1 : 0; }
            @Override public void set(int value) { activity.setFpsOverlayVisible(value == 1); }
        });
    }

    // ── Picture effects ─────────────────────────────────────────────────────────────────────────

    private int currentProfile() {
        for (int p = 0; p < PROFILES.length; p++) {
            boolean same = true;
            for (int i = 0; i < EFFECTS.length && same; i++) same = Math.abs(value(EFFECTS[i]) - PROFILES[p][i]) < 0.01f;
            if (same) return p;
        }
        return PROFILES.length; // custom
    }

    private void addEffectRows() {
        addChoice(R.string.quick_effects_profile, R.string.quick_effects_profile_tip, new Choice() {
            @Override public String[] values() {
                // "Custom" is only shown, never chosen: it is whatever the rows below add up to.
                String[] all = array(R.array.quick_effect_profiles);
                return currentProfile() < PROFILES.length ? java.util.Arrays.copyOf(all, PROFILES.length) : all;
            }
            @Override public int get() { return currentProfile(); }
            @Override public void set(int value) {
                int profile = value % PROFILES.length; // "Custom" only shows what is set
                for (int i = 0; i < EFFECTS.length; i++) {
                    setSetting(EFFECTS[i], i != EFFECT_STYLE, PROFILES[profile][i]);
                }
                showPage(currentPage, 0); // the rows below follow the profile
            }
        });
        int[] labels = {R.string.quick_effect_bloom, R.string.quick_effect_hdr, R.string.quick_effect_vibrance, 0,
                        R.string.quick_effect_vignette, R.string.quick_effect_speed, R.string.quick_effect_cas};
        int[] tips = {R.string.quick_effect_bloom_tip, R.string.quick_effect_hdr_tip, R.string.quick_effect_vibrance_tip, 0,
                      R.string.quick_effect_vignette_tip, R.string.quick_effect_speed_tip, R.string.quick_effect_cas_tip};
        for (int i = 0; i < EFFECTS.length; i++) {
            final String name = EFFECTS[i];
            if (i == EFFECT_STYLE) {
                addChoice(R.string.quick_effect_style, R.string.quick_effect_style_tip, new Choice() {
                    @Override public String[] values() { return array(R.array.quick_effect_styles); }
                    @Override public int get() { return Math.round(value(name)); }
                    @Override public void set(int value) { setSetting(name, false, value); refreshProfile(); }
                });
                continue;
            }
            addChoice(labels[i], tips[i], new Choice() {
                @Override public String[] values() { return array(R.array.quick_effect_levels); }
                @Override public int get() { return nearest(LEVELS, value(name)); }
                @Override public void set(int value) { setSetting(name, true, LEVELS[value]); refreshProfile(); }
            });
        }
    }

    /** The profile row shows "Custom" once a single effect changes. */
    private void refreshProfile() {
        final int focus = focusIndex(rows.findFocus() != null ? rowOf(rows.findFocus()) : null);
        ui.post(new Runnable() {
            @Override public void run() { showPage(currentPage, focus); }
        });
    }

    // ── Touch controls ──────────────────────────────────────────────────────────────────────────

    private void addTouchRows() {
        final TouchControlsView controls = activity.touchControls();
        if (controls == null) return;
        addChoice(R.string.quick_touch_mode, R.string.quick_touch_mode_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_touch_modes); }
            @Override public int get() { return controls.getMode(); }
            @Override public void set(int value) { controls.setMode(value); }
        });
        Button edit = new Button(activity);
        edit.setText(R.string.quick_touch_editor);
        edit.setAllCaps(false);
        edit.setOnFocusChangeListener(footerOnFocus(activity.getString(R.string.quick_touch_editor_tip)));
        edit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                dialog.dismiss();
                activity.openTouchEditor();
            }
        });
        rows.addView(edit);
    }

    // ── Installed packs ─────────────────────────────────────────────────────────────────────────

    /** Installed packs: .o2r in mods/, .htc in hires/, music in mods/music/custom/. */
    private static List<File> installedPacks(Context context) {
        File appDir = RuntimeFiles.appDir(context);
        List<File> files = new ArrayList<>();
        for (String folder : new String[] {"mods", "hires", "mods/music/custom"}) {
            File[] list = new File(appDir, folder).listFiles();
            if (list == null) continue;
            java.util.Arrays.sort(list);
            for (File file : list) {
                String lower = file.getName().toLowerCase(Locale.ROOT);
                if (file.isFile() && (lower.endsWith(".o2r") || lower.endsWith(".htc") || lower.endsWith(".ogg")
                        || lower.endsWith(".flac") || lower.endsWith(".wav"))) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    private void deletePacks() {
        final List<File> files = installedPacks(activity);
        if (files.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(R.string.quick_delete_none)
                    .setPositiveButton(R.string.action_close, null).show();
            return;
        }
        final String[] names = new String[files.size()];
        for (int i = 0; i < names.length; i++) names[i] = files.get(i).getName();
        final boolean[] chosen = new boolean[names.length];
        new AlertDialog.Builder(activity).setTitle(R.string.quick_delete_mods)
                .setMultiChoiceItems(names, chosen, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override public void onClick(DialogInterface d, int which, boolean checked) { chosen[which] = checked; }
                })
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        final List<File> doomed = new ArrayList<>();
                        for (int i = 0; i < chosen.length; i++) if (chosen[i]) doomed.add(files.get(i));
                        if (!doomed.isEmpty()) confirmDelete(doomed);
                    }
                })
                .setNegativeButton(R.string.quick_cancel, null).show();
    }

    private void confirmDelete(final List<File> doomed) {
        new AlertDialog.Builder(activity)
                .setMessage(activity.getResources().getQuantityString(R.plurals.quick_delete_confirm, doomed.size(), doomed.size()))
                .setPositiveButton(R.string.action_delete, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        boolean htc = false;
                        int deleted = 0;
                        File cache = new File(RuntimeFiles.appDir(activity), "cache/htc");
                        for (File file : doomed) {
                            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".htc")) {
                                htc = true;
                                new File(cache, file.getName() + ".idx").delete();
                                new File(cache, file.getName() + ".bin").delete();
                            }
                            if (file.delete()) deleted++;
                        }
                        if (htc) setSetting(HIRES_PACK, false, 0); // drops the pack it was using
                        final String message = activity.getString(R.string.quick_deleted, deleted);
                        worker.execute(new Runnable() {
                            @Override public void run() {
                                try {
                                    GameBridge.nativeMenuPress(RELOAD_PACKS);
                                } catch (UnsatisfiedLinkError ignored) {
                                }
                                final String json = readMenu();
                                ui.post(new Runnable() {
                                    @Override public void run() {
                                        if (!dialog.isShowing()) return;
                                        if (!json.isEmpty()) {
                                            parse(json);
                                            showPage(Math.min(currentPage, pages.size() - 1), 0);
                                        }
                                        footer.setText(message);
                                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                                    }
                                });
                            }
                        });
                    }
                })
                .setNegativeButton(R.string.quick_cancel, null).show();
    }

    private void addInstallRow() {
        Button install = new Button(activity);
        install.setText(R.string.quick_install_mods);
        install.setAllCaps(false);
        install.setOnFocusChangeListener(footerOnFocus(activity.getString(R.string.quick_install_mods_tip)));
        install.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    activity.startActivityForResult(pick, PICK_MODS);
                } catch (android.content.ActivityNotFoundException exception) {
                    footer.setText(R.string.quick_install_failed);
                }
            }
        });
        rows.addView(install);
        Button delete = new Button(activity);
        delete.setText(R.string.quick_delete_mods);
        delete.setAllCaps(false);
        delete.setOnFocusChangeListener(footerOnFocus(activity.getString(R.string.quick_delete_mods_tip)));
        delete.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { deletePacks(); }
        });
        rows.addView(delete);
        addChoice(R.string.quick_hires_pack, R.string.quick_hires_pack_tip, new Choice() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return value(HIRES_PACK) != 0 ? 1 : 0; }
            @Override public void set(int value) { setSetting(HIRES_PACK, false, value); }
        });
    }

    /** GameActivity's result for PICK_MODS: copies the packs into mods/ and reloads them. */
    static void modsPicked(final GameActivity activity, Intent data) {
        final List<Uri> uris = new ArrayList<>();
        if (data != null && data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data != null && data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        if (current != null) current.footer.setText(R.string.quick_installing);
        // A visible copy: big packs (.htc) take a while, and the menu itself shows nothing meanwhile.
        final Handler main = new Handler(Looper.getMainLooper());
        final android.widget.ProgressBar bar =
                new android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        final TextView status = new TextView(activity);
        status.setPadding(0, 0, 0, 16);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 8);
        box.addView(status);
        box.addView(bar);
        final AlertDialog progress = new AlertDialog.Builder(activity).setTitle(R.string.quick_installing)
                .setView(box).setCancelable(false).show();
        worker.execute(new Runnable() {
            @Override public void run() {
                int installed = 0, skipped = 0;
                final StringBuilder names = new StringBuilder();
                File appDir = RuntimeFiles.appDir(activity);
                for (Uri uri : uris) {
                    String name = displayName(activity, uri);
                    String lower = name.toLowerCase(Locale.ROOT);
                    File dir;
                    if (lower.endsWith(".o2r")) {
                        dir = new File(appDir, "mods");
                    } else if (lower.endsWith(".htc")) {
                        dir = new File(appDir, "hires");
                    } else if (lower.endsWith(".ogg") || lower.endsWith(".flac") || lower.endsWith(".wav")) {
                        dir = new File(appDir, "mods/music/custom");
                    } else {
                        skipped++;
                        continue;
                    }
                    if (!dir.isDirectory() && !dir.mkdirs()) {
                        skipped++;
                        continue;
                    }
                    final long size = fileSize(activity, uri);
                    final String shown = name;
                    main.post(new Runnable() {
                        @Override public void run() {
                            status.setText(shown);
                            bar.setIndeterminate(size <= 0);
                            bar.setProgress(0);
                        }
                    });
                    try (InputStream in = activity.getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(new File(dir, name))) {
                        if (in == null) throw new IOException("no stream");
                        byte[] buffer = new byte[1 << 16];
                        long copied = 0;
                        long lastUpdate = 0;
                        for (int n; (n = in.read(buffer)) > 0; ) {
                            out.write(buffer, 0, n);
                            copied += n;
                            long now = android.os.SystemClock.elapsedRealtime();
                            if (size > 0 && now - lastUpdate > 100) {
                                lastUpdate = now;
                                final int permille = (int) Math.min(1000, copied * 1000 / size);
                                main.post(new Runnable() {
                                    @Override public void run() { bar.setProgress(permille); }
                                });
                            }
                        }
                        installed++;
                        names.append("\n• ").append(name);
                    } catch (IOException exception) {
                        Log.e("GZero64", "Cannot install " + name, exception);
                        skipped++;
                    }
                }
                // The game thread may still be held by the picker's pause: retry for a few seconds.
                for (int attempt = 0; installed > 0 && attempt < 10; attempt++) {
                    try {
                        if (GameBridge.nativeMenuPress(RELOAD_PACKS)) break;
                        Thread.sleep(500);
                    } catch (UnsatisfiedLinkError | InterruptedException ignored) {
                        break;
                    }
                }
                final String message = activity.getString(R.string.quick_installed, installed)
                        + (skipped > 0 ? "  " + activity.getString(R.string.quick_install_skipped, skipped) : "");
                final String menuJson = readMenu();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override public void run() {
                        QuickMenuDialog menu = current;
                        if (menu == null || !menu.dialog.isShowing()) return;
                        if (!menuJson.isEmpty()) {
                            menu.parse(menuJson);
                            menu.showPage(Math.min(menu.currentPage, menu.pages.size() - 1), 0);
                        }
                        menu.footer.setText(message);
                    }
                });
                final String report = message + names;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        progress.dismiss();
                        // A clear answer: what was installed and what was not.
                        new AlertDialog.Builder(activity).setTitle(R.string.quick_install_mods_done)
                                .setMessage(report).setPositiveButton(R.string.action_close, null).show();
                    }
                });
            }
        });
    }

    private static String readMenu() {
        try {
            return GameBridge.nativeMenuJson();
        } catch (UnsatisfiedLinkError error) {
            return "";
        }
    }

    private static long fileSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
        } catch (RuntimeException ignored) {
        }
        return -1;
    }

    private static String displayName(Context context, Uri uri) {
        String name = null;
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException ignored) {
        }
        if (name == null) name = uri.getLastPathSegment() != null ? uri.getLastPathSegment() : "pack.o2r";
        return new File(name).getName(); // no folders from the provider's name
    }

    private void setSetting(String name, boolean isFloat, float value) {
        values.put(name, value);
        GameBridge.nativeSet(name, isFloat, value);
    }

    private void addChoice(int label, final int tip, final Choice choice) {
        final TextView value = new TextView(activity);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                String[] values = choice.values();
                value.setText(values[Math.max(0, Math.min(values.length - 1, choice.get()))]);
            }
        };
        refresh.run();
        addValueRow(activity.getString(label), tip != 0 ? activity.getString(tip) : "", null, value, true,
            new Stepper() {
                @Override public void step(int delta) {
                    int n = choice.values().length;
                    choice.set(((choice.get() + delta) % n + n) % n);
                    refresh.run();
                }
            });
    }

    // ── G-Diffuser rows ─────────────────────────────────────────────────────────────────────────

    private interface Stepper {
        void step(int delta);
    }

    private void addGameRow(final JSONObject row) throws JSONException {
        final String type = row.getString("type");
        final String label = row.getString("label");
        final String tip = row.optString("tip", "");
        final String note = row.optString("note", "");
        final String disabled = row.optString("disabled", "");
        final boolean enabled = disabled.isEmpty();
        final String describe = enabled ? tip : disabled.trim().isEmpty() ? tip : disabled;
        switch (type) {
            case "header": {
                TextView header = new TextView(activity);
                header.setText(label);
                header.setTextSize(12);
                header.setLetterSpacing(0.1f);
                header.setTextColor(0xFFFFC452);
                header.setPadding(dp(8), dp(12), 0, dp(2));
                rows.addView(header);
                return;
            }
            case "info": {
                // Read-only line (achievements): name, and a mark on the right.
                LinearLayout line = new LinearLayout(activity);
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.setMinimumHeight(dp(40));
                line.setPadding(dp(8), 0, dp(12), 0);
                line.setFocusable(true);
                line.setBackground(highlight());
                line.setOnFocusChangeListener(footerOnFocus(describe));
                final boolean done = row.optDouble("value", 0) != 0;
                line.setAlpha(done ? 1f : 0.55f);
                TextView name = new TextView(activity);
                name.setText(label);
                name.setTextColor(Color.WHITE);
                name.setTextSize(15);
                line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                TextView mark = new TextView(activity);
                mark.setText(done ? "✓" : "—");
                mark.setTextSize(16);
                mark.setTextColor(done ? 0xFF66D17A : 0xFF8E92AA);
                line.addView(mark);
                rows.addView(line);
                return;
            }
            case "text": {
                TextView text = new TextView(activity);
                text.setText(label);
                text.setTextSize(12);
                text.setTextColor(0xFF8E92AA);
                text.setPadding(dp(8), dp(4), dp(8), dp(4));
                rows.addView(text);
                return;
            }
            case "button": {
                Button button = new Button(activity);
                button.setText(label);
                button.setAllCaps(false);
                button.setEnabled(enabled);
                button.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(final View view) {
                        final int focus = focusIndex(view);
                        apply(new Runnable() {
                            @Override public void run() {
                                GameBridge.nativeMenuPress(row.optString("id"));
                            }
                        }, focus);
                    }
                });
                button.setOnFocusChangeListener(footerOnFocus(describe));
                rows.addView(button);
                return;
            }
            default:
                break;
        }
        final double current = row.optDouble("value", 0);
        final TextView value = new TextView(activity);
        Stepper stepper;
        if ("check".equals(type) || "radio".equals(type)) {
            String[] onOff = array(R.array.quick_off_on);
            value.setText("radio".equals(type) ? (current != 0 ? "●" : "○") : onOff[current != 0 ? 1 : 0]);
            stepper = new Stepper() {
                @Override public void step(int delta) {
                    double next = "radio".equals(type) ? 1 : (current != 0 ? 0 : 1);
                    set(row, next);
                }
            };
        } else if ("combo".equals(type)) {
            final JSONArray items = row.getJSONArray("items");
            final int count = Math.max(1, items.length());
            int index = (int) Math.max(0, Math.min(count - 1, Math.round(current)));
            value.setText(items.optString(index, String.valueOf(index)));
            stepper = new Stepper() {
                @Override public void step(int delta) {
                    set(row, ((Math.round(current) + delta) % count + count) % count);
                }
            };
        } else {
            final double min = row.optDouble("min", 0), max = row.optDouble("max", 1);
            double step = row.optDouble("step", 1);
            // Sliders with fine steps would take hundreds of presses: about 20 steps end to end.
            if ((max - min) / step > 40) step = niceStep((max - min) / 20);
            final double inc = step;
            value.setText(formatValue(row, current));
            stepper = new Stepper() {
                @Override public void step(int delta) {
                    double next = Math.max(min, Math.min(max, current + delta * inc));
                    if ("int".equals(type)) next = Math.round(next);
                    set(row, next);
                }
            };
        }
        addValueRow(label, describe, note, value, enabled, stepper);
    }

    private static double niceStep(double raw) {
        double magnitude = Math.pow(10, Math.floor(Math.log10(raw)));
        double[] nice = {1, 2, 5, 10};
        for (double n : nice) if (raw <= n * magnitude) return n * magnitude;
        return 10 * magnitude;
    }

    /** The game's printf format, with UIWidgets' percentage display (value x 100). */
    private static String formatValue(JSONObject row, double value) {
        if (row.optBoolean("percent")) return Math.round(value * 100) + "%";
        String format = row.optString("format", "");
        boolean isInt = "int".equals(row.optString("type"));
        if (format.isEmpty()) format = isInt ? "%d" : "%.2f";
        try {
            return isInt ? String.format(Locale.ROOT, format, Math.round(value))
                         : String.format(Locale.ROOT, format, value);
        } catch (RuntimeException exception) {
            return isInt ? String.valueOf(Math.round(value)) : String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private void set(final JSONObject row, final double value) {
        final String id = row.optString("id");
        apply(new Runnable() {
            @Override public void run() { GameBridge.nativeMenuSet(id, value); }
        }, focusIndex(rows.findFocus() != null ? rowOf(rows.findFocus()) : null));
    }

    private View rowOf(View view) {
        while (view != null && view.getParent() != rows) {
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return view;
    }

    /** Runs a change on the game, then reloads the page so values and disabled rows are current. */
    private void apply(final Runnable change, final int focus) {
        if (busy) return;
        busy = true;
        final int page = currentPage;
        final int scroll = rowScroll.getScrollY();
        worker.execute(new Runnable() {
            @Override public void run() {
                try {
                    change.run();
                } catch (UnsatisfiedLinkError error) {
                    Log.w("GZero64", "Game library not loaded", error);
                }
                final String json;
                String read = "";
                try {
                    read = GameBridge.nativeMenuJson();
                } catch (UnsatisfiedLinkError ignored) {
                }
                json = read;
                ui.post(new Runnable() {
                    @Override public void run() {
                        busy = false;
                        if (!dialog.isShowing() || json.isEmpty()) return;
                        parse(json);
                        showPage(Math.min(page, pages.size() - 1), focus);
                        rowScroll.scrollTo(0, scroll);
                    }
                });
            }
        });
    }

    private View.OnFocusChangeListener footerOnFocus(final String text) {
        return new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View view, boolean hasFocus) {
                if (hasFocus) footer.setText(text);
            }
        };
    }

    private void addValueRow(String label, String describe, String note, final TextView value, boolean enabled,
                             final Stepper stepper) {
        final LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(44));
        line.setPadding(dp(8), 0, dp(4), 0);
        line.setFocusable(true);
        line.setClickable(true);
        line.setBackground(highlight());
        line.setAlpha(enabled ? 1f : 0.45f);
        line.setOnFocusChangeListener(footerOnFocus(describe));

        TextView name = new TextView(activity);
        name.setText(note == null || note.isEmpty() ? label : label + "  " + note);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        value.setTextColor(0xFFFFD98A);
        value.setTextSize(15);
        value.setGravity(Gravity.CENTER);
        value.setSingleLine(true);
        value.setEllipsize(TextUtils.TruncateAt.END);
        Button left = arrow("‹"), right = arrow("›");
        if (enabled) {
            left.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { line.requestFocus(); stepper.step(-1); }
            });
            right.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { line.requestFocus(); stepper.step(1); }
            });
            line.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { stepper.step(1); }
            });
        }
        // Controller: up/down move between rows, left/right change the value.
        line.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN && line.getAlpha() == 1f) {
                    stepper.step(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;
            }
        });
        line.addView(left, new LinearLayout.LayoutParams(dp(40), dp(40)));
        line.addView(value, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));
        line.addView(right, new LinearLayout.LayoutParams(dp(40), dp(40)));
        rows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private Button arrow(String symbol) {
        Button button = new Button(activity);
        button.setText(symbol);
        button.setTextSize(20);
        button.setPadding(0, 0, 0, 0);
        button.setFocusable(false); // the row keeps the controller focus
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setTextColor(0xFFF3F6F7);
        return button;
    }

    /** Compact coloured button; lighter with controller focus or when pressed. */
    private Button small(int res, int color, int focused) {
        Button button = new Button(activity);
        button.setText(res);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setTextSize(14);
        button.setMinWidth(dp(120));
        button.setMinimumWidth(dp(120));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(16), 0, dp(16), 0);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[] {android.R.attr.state_focused}, shape(focused, Color.WHITE));
        background.addState(new int[] {android.R.attr.state_pressed}, shape(focused, focused));
        background.addState(new int[0], shape(color, color));
        button.setBackground(background);
        button.setStateListAnimator(null);
        return button;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(2), stroke);
        shape.setCornerRadius(dp(4));
        return shape;
    }

    private void confirmExit() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.quick_exit)
                .setMessage(R.string.quick_exit_confirm)
                .setPositiveButton(R.string.quick_exit, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface confirmation, int which) {
                        dialog.dismiss();
                        activity.exitGame();
                    }
                })
                .setNegativeButton(R.string.quick_cancel, null)
                .show();
    }
}
