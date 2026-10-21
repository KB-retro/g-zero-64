package org.patrullaretro.gzero64;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
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
import java.util.HashMap;
import java.util.Map;

/**
 * In-game quick menu (Back): picture and sound settings applied to the running game, then
 * Continue (green, left) and Exit (red, right). It replaces G-Diffuser's desktop menu on Android.
 * Values are read from the game in one batch before it pauses; changes reach it when it resumes.
 */
final class QuickMenuDialog {
    /** A value row: one label, n values, cycled with left/right or the arrows. */
    private interface Row {
        String[] values();
        int get();
        void set(int value);
    }

    // Game settings the menu shows: CVar name, float?, default.
    private static final String WIDESCREEN = "gEnhancements.Graphics.Widescreen";
    private static final String WIDESCREEN_UI = "gEnhancements.Graphics.WidescreenUI";
    private static final String RESOLUTION = "gInternalResolution";
    private static final String FSR = "gGZ64.Fsr";
    private static final String FSR_SHARPNESS = "gGZ64.FsrSharpness";
    private static final String FXAA = "gGZ64.Fxaa";
    private static final String MSAA = "gMSAAValue";
    private static final String TEXTURE_FILTER = "gTextureFilter";
    private static final String REMOVE_BORDERS = "gEnhancements.Graphics.RemoveBorders";
    private static final String DRAW_DISTANCE = "gEnhancements.Graphics.DrawDistance";
    private static final String FPS_COUNTER = "gz64.FpsCounter";
    private static final String MUSIC = "gEnhancements.Audio.MusicVolume";
    private static final String SFX = "gEnhancements.Audio.SfxVolume";
    private static final Object[][] SETTINGS = {
        {RESOLUTION, true, 1.0f}, {FSR, false, 0f}, {FSR_SHARPNESS, true, 0.2f}, {FXAA, false, 0f},
        {MSAA, false, 1f}, {TEXTURE_FILTER, false, 0f}, {REMOVE_BORDERS, false, 0f},
        {DRAW_DISTANCE, false, 100f}, {FPS_COUNTER, false, 0f}, {MUSIC, false, 100f}, {SFX, false, 100f},
    };

    private static final float[] SCALES = {0.5f, 0.75f, 1.0f, 1.5f, 2.0f};
    private static final float[] SHARPNESS = {1.0f, 0.5f, 0.0f}; // AMD stops: 0 = sharpest
    private static final int[] DISTANCES = {100, 150, 200};

    private final GameActivity activity;
    private final Dialog dialog;
    private final LinearLayout rows;
    private final Map<String, Float> values = new HashMap<>();

    private QuickMenuDialog(GameActivity activity) {
        this.activity = activity;
        dialog = new Dialog(activity);
        rows = new LinearLayout(activity);
    }

    static Dialog show(GameActivity activity) {
        QuickMenuDialog menu = new QuickMenuDialog(activity);
        menu.readSettings();
        activity.pauseGame();
        menu.build();
        return menu.dialog;
    }

    /** Picture format at game start: the saved choice, or by screen shape when automatic. */
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

    private void readSettings() {
        String[] names = new String[SETTINGS.length];
        boolean[] isFloat = new boolean[SETTINGS.length];
        float[] defaults = new float[SETTINGS.length];
        for (int i = 0; i < SETTINGS.length; i++) {
            names[i] = (String) SETTINGS[i][0];
            isFloat[i] = (Boolean) SETTINGS[i][1];
            defaults[i] = (Float) SETTINGS[i][2];
        }
        float[] read = defaults;
        try {
            read = GameBridge.nativeGet(names, isFloat, defaults);
        } catch (UnsatisfiedLinkError error) {
            Log.w("GZero64", "Game library not loaded; showing defaults", error);
        }
        for (int i = 0; i < names.length; i++) values.put(names[i], read[i]);
    }

    private float value(String name) {
        Float value = values.get(name);
        return value != null ? value : 0f;
    }

    private void setInt(String name, int value) {
        values.put(name, (float) value);
        GameBridge.setInt(name, value);
    }

    private void setFloat(String name, float value) {
        values.put(name, value);
        GameBridge.setFloat(name, value);
    }

    private static int nearest(float[] options, float value) {
        int best = 0;
        for (int i = 1; i < options.length; i++)
            if (Math.abs(options[i] - value) < Math.abs(options[best] - value)) best = i;
        return best;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                activity.getResources().getDisplayMetrics()));
    }

    private String[] array(int res) {
        return activity.getResources().getStringArray(res);
    }

    private void build() {
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(12));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xF2101026);
        card.setStroke(dp(1), 0xFF4A4870);
        card.setCornerRadius(dp(4));
        panel.setBackground(card);

        TextView title = new TextView(activity);
        title.setText(R.string.quick_title);
        title.setTextSize(20);
        title.setTextColor(0xFFF3F6F7);
        title.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD_ITALIC));
        panel.addView(title);

        rows.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(rows);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        addHeader(R.string.quick_section_picture);
        addRow(R.string.quick_format, new Row() {
            @Override public String[] values() { return array(R.array.quick_formats); }
            @Override public int get() { return format(activity); }
            @Override public void set(int value) {
                activity.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putInt("format", value).apply();
                applyFormat(activity, value);
            }
        });
        addRow(R.string.quick_resolution, new Row() {
            @Override public String[] values() { return array(R.array.quick_resolutions); }
            @Override public int get() { return nearest(SCALES, value(RESOLUTION)); }
            @Override public void set(int value) { setFloat(RESOLUTION, SCALES[value]); }
        });
        addRow(R.string.quick_fsr, new Row() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return value(FSR) != 0 ? 1 : 0; }
            @Override public void set(int value) { setInt(FSR, value); }
        });
        addRow(R.string.quick_fsr_sharpness, new Row() {
            @Override public String[] values() { return array(R.array.quick_sharpness); }
            @Override public int get() { return nearest(SHARPNESS, value(FSR_SHARPNESS)); }
            @Override public void set(int value) { setFloat(FSR_SHARPNESS, SHARPNESS[value]); }
        });
        addRow(R.string.quick_antialiasing, new Row() {
            @Override public String[] values() { return array(R.array.quick_antialiasing); }
            @Override public int get() {
                int msaa = Math.round(value(MSAA));
                if (msaa >= 4) return 3;
                if (msaa == 2) return 2;
                return value(FXAA) != 0 ? 1 : 0;
            }
            @Override public void set(int value) {
                setInt(FXAA, value == 1 ? 1 : 0);
                setInt(MSAA, value == 3 ? 4 : value == 2 ? 2 : 1);
            }
        });
        addRow(R.string.quick_texture_filter, new Row() {
            @Override public String[] values() { return array(R.array.quick_texture_filters); }
            @Override public int get() { return Math.max(0, Math.min(2, Math.round(value(TEXTURE_FILTER)))); }
            @Override public void set(int value) { setInt(TEXTURE_FILTER, value); }
        });
        addRow(R.string.quick_borders, new Row() {
            @Override public String[] values() { return array(R.array.quick_borders); }
            @Override public int get() { return value(REMOVE_BORDERS) != 0 ? 1 : 0; }
            @Override public void set(int value) { setInt(REMOVE_BORDERS, value); }
        });
        addRow(R.string.quick_draw_distance, new Row() {
            @Override public String[] values() { return new String[] {"100%", "150%", "200%"}; }
            @Override public int get() {
                return nearest(new float[] {DISTANCES[0], DISTANCES[1], DISTANCES[2]}, value(DRAW_DISTANCE));
            }
            @Override public void set(int value) { setInt(DRAW_DISTANCE, DISTANCES[value]); }
        });
        addRow(R.string.quick_fps, new Row() {
            @Override public String[] values() { return array(R.array.quick_off_on); }
            @Override public int get() { return value(FPS_COUNTER) != 0 ? 1 : 0; }
            @Override public void set(int value) { setInt(FPS_COUNTER, value); }
        });

        addHeader(R.string.quick_section_sound);
        addVolumeRow(R.string.quick_music, MUSIC);
        addVolumeRow(R.string.quick_sfx, SFX);

        // Continue bottom left (green), Exit bottom right (red).
        LinearLayout buttons = new LinearLayout(activity);
        buttons.setPadding(0, dp(10), 0, 0);
        Button resume = small(R.string.quick_resume, 0xFF2E7D32, 0xFF43A047);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { dialog.dismiss(); }
        });
        Button exit = small(R.string.quick_exit, 0xFFB71C1C, 0xFFE53935);
        exit.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { confirmExit(); }
        });
        buttons.addView(resume, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        buttons.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
        buttons.addView(exit, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)));
        panel.addView(buttons);

        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(panel);
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
            // Sized from the screen, so the card fits 16:9 and 4:3 alike.
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout(Math.min(dp(600), metrics.widthPixels * 92 / 100),
                    metrics.heightPixels * 92 / 100);
        }
        for (int i = 0; i < rows.getChildCount(); i++) {
            View child = rows.getChildAt(i);
            if (child.isFocusable()) {
                child.requestFocus();
                break;
            }
        }
    }

    private void addVolumeRow(int label, final String name) {
        final String[] steps = new String[11];
        for (int i = 0; i <= 10; i++) steps[i] = (i * 10) + "%";
        addRow(label, new Row() {
            @Override public String[] values() { return steps; }
            @Override public int get() { return Math.max(0, Math.min(10, Math.round(value(name) / 10f))); }
            @Override public void set(int value) { setInt(name, value * 10); }
        });
    }

    private void addHeader(int text) {
        TextView header = new TextView(activity);
        header.setText(text);
        header.setTextSize(12);
        header.setLetterSpacing(0.15f);
        header.setTextColor(0xFFFFC452);
        header.setPadding(dp(8), dp(12), 0, dp(4));
        rows.addView(header);
    }

    private void addRow(int label, final Row row) {
        final LinearLayout line = new LinearLayout(activity);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(44));
        line.setPadding(dp(8), 0, dp(4), 0);
        line.setFocusable(true);
        line.setClickable(true);
        StateListDrawable highlight = new StateListDrawable();
        highlight.addState(new int[] {android.R.attr.state_focused}, new ColorDrawable(0x40FFC452));
        highlight.addState(new int[] {android.R.attr.state_pressed}, new ColorDrawable(0x30FFC452));
        highlight.addState(new int[0], new ColorDrawable(Color.TRANSPARENT));
        line.setBackground(highlight);

        TextView name = new TextView(activity);
        name.setText(label);
        name.setTextColor(Color.WHITE);
        name.setTextSize(15);
        line.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final TextView value = new TextView(activity);
        value.setTextColor(0xFFFFD98A);
        value.setTextSize(15);
        value.setGravity(Gravity.CENTER);
        final Runnable refresh = new Runnable() {
            @Override public void run() {
                String[] values = row.values();
                value.setText(values[Math.max(0, Math.min(values.length - 1, row.get()))]);
            }
        };
        refresh.run();
        Button left = arrow("‹"), right = arrow("›");
        left.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { step(row, -1); refresh.run(); }
        });
        right.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { step(row, 1); refresh.run(); }
        });
        line.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { step(row, 1); refresh.run(); }
        });
        // Controller: up/down move between rows (default focus), left/right change the value.
        line.setOnKeyListener(new View.OnKeyListener() {
            @Override public boolean onKey(View view, int keyCode, KeyEvent event) {
                if (keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    step(row, keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                    refresh.run();
                }
                return true;
            }
        });
        line.addView(left, new LinearLayout.LayoutParams(dp(40), dp(40)));
        line.addView(value, new LinearLayout.LayoutParams(dp(130), LinearLayout.LayoutParams.WRAP_CONTENT));
        line.addView(right, new LinearLayout.LayoutParams(dp(40), dp(40)));
        rows.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private static void step(Row row, int delta) {
        int n = row.values().length;
        if (n > 0) row.set(((row.get() + delta) % n + n) % n);
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
