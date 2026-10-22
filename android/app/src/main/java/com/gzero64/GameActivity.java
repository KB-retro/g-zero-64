package com.gzero64;

import android.os.Bundle;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import java.io.File;
import org.libsdl.app.SDLActivity;

/** SDL hosts the game, controller input and audio. */
public final class GameActivity extends SDLActivity {
    private android.app.Dialog quickMenu;
    private TouchControlsView touchControls;
    private TouchLayoutStore touchLayoutStore;
    private View editorToolbar;
    private TouchPresetController touchPresets;
    private android.widget.TextView fpsOverlay;
    private int fpsLastFrames = -1;
    private long fpsLastTime;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Read by the native side before anything else (port/gdx_self_exe.h).
        File appDir = RuntimeFiles.appDir(this);
        try {
            Os.setenv("GDX_APP_DIR", appDir.getAbsolutePath(), true);
            Os.setenv("GDX_EXTRACT_BIN", getApplicationInfo().nativeLibraryDir + "/libgdx_extract.so", true);
            // gdiffuser-run.log, saved from Settings when something goes wrong.
            Os.setenv("GDX_LOG", "1", true);
        } catch (ErrnoException exception) {
            Log.e("GZero64", "Cannot export the app folder", exception);
        }
        super.onCreate(savedInstanceState);
        QuickMenuDialog.applyAutomaticFormat(this);
        if (mLayout != null) {
            touchLayoutStore = new TouchLayoutStore(appDir);
            touchControls = new TouchControlsView(this);
            touchControls.setLayout(touchLayoutStore.load());
            mLayout.addView(touchControls, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT));
            setFpsOverlayVisible(getSharedPreferences("game", MODE_PRIVATE).getBoolean("fps", false));
            if (!new File(appDir, "fzerox.o2r").isFile()) showInstallScreen();
        }
        restoreFullscreen();
    }

    TouchControlsView touchControls() { return touchControls; }

    // ── First-time install screen ───────────────────────────────────────────────────────────────

    private View installScreen;
    private android.widget.ProgressBar installBar;
    private android.widget.TextView installPercent;
    private long installShownAt;
    private int installDoneFrames = -1; // frames drawn when setup finished
    private float installShown;         // the bar never goes back

    /** Covers the game's setup screen with the launcher's look and an "Installing..." bar. */
    private void showInstallScreen() {
        android.widget.FrameLayout screen = new android.widget.FrameLayout(this);
        screen.setBackgroundColor(android.graphics.Color.BLACK);
        screen.setClickable(true); // nothing reaches the setup screen underneath
        android.widget.ImageView backdrop = new android.widget.ImageView(this);
        backdrop.setImageResource(R.drawable.launcher_backdrop);
        backdrop.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        backdrop.setColorFilter(0x99000000);
        screen.addView(backdrop, new android.widget.FrameLayout.LayoutParams(-1, -1));

        android.widget.LinearLayout column = new android.widget.LinearLayout(this);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER);
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        android.widget.ImageView logo = new android.widget.ImageView(this);
        logo.setImageResource(R.drawable.launcher_logo);
        logo.setAdjustViewBounds(true);
        column.addView(logo, new android.widget.LinearLayout.LayoutParams(-2, metrics.heightPixels * 30 / 100));
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(R.string.install_title);
        title.setTextColor(android.graphics.Color.WHITE);
        title.setTextSize(22);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD_ITALIC));
        title.setPadding(0, metrics.heightPixels / 30, 0, metrics.heightPixels / 60);
        title.setGravity(android.view.Gravity.CENTER);
        column.addView(title);
        installBar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        installBar.setMax(1000);
        installBar.setIndeterminate(true);
        installBar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF55BCD8));
        installBar.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFF55BCD8));
        column.addView(installBar, new android.widget.LinearLayout.LayoutParams(metrics.widthPixels * 55 / 100, -2));
        installPercent = new android.widget.TextView(this);
        installPercent.setTextColor(0xFFB8E9F5);
        installPercent.setTextSize(14);
        installPercent.setText(R.string.install_note);
        installPercent.setPadding(0, metrics.heightPixels / 60, 0, 0);
        installPercent.setGravity(android.view.Gravity.CENTER);
        column.addView(installPercent);
        screen.addView(column, new android.widget.FrameLayout.LayoutParams(-1, -1));

        installScreen = screen;
        installShownAt = android.os.SystemClock.elapsedRealtime();
        mLayout.addView(screen, new android.view.ViewGroup.LayoutParams(-1, -1));
        screen.post(installUpdate);
    }

    private final Runnable installUpdate = new Runnable() {
        @Override public void run() {
            if (installScreen == null) return;
            int state;
            float progress;
            try {
                state = GameBridge.nativeSetupState();
                progress = GameBridge.nativeSetupProgress();
            } catch (UnsatisfiedLinkError error) {
                state = 0;
                progress = 0;
            }
            long shown = android.os.SystemClock.elapsedRealtime() - installShownAt;
            // 1: the game needs files the launcher did not provide; 3: failed (the game's screen
            // offers Retry); 4: done. Otherwise keep covering it until setup starts.
            int frames = 0;
            try {
                frames = GameBridge.nativeFrames();
            } catch (UnsatisfiedLinkError ignored) {
            }
            // Done: keep covering the setup screen's last frame until the game itself has drawn a
            // second's worth of frames (loading the archives takes a moment after setup).
            if (state == 4 && installDoneFrames < 0) installDoneFrames = frames;
            boolean gameRunning = installDoneFrames >= 0 && frames - installDoneFrames > 60;
            if (state == 1 || state == 3 || gameRunning || (state == 0 && shown > 60000)
                    || (state == 4 && shown > 120000)) {
                mLayout.removeView(installScreen);
                installScreen = null;
                return;
            }
            if (state == 2 || state == 4) {
                installShown = Math.max(installShown, state == 4 ? 1f : progress);
                installBar.setIndeterminate(false);
                installBar.setProgress(Math.round(installShown * 1000));
                installPercent.setText(getString(R.string.install_percent, Math.round(installShown * 100)));
            }
            installScreen.postDelayed(this, 250);
        }
    };

    // ── FPS counter ─────────────────────────────────────────────────────────────────────────────

    private final Runnable fpsUpdate = new Runnable() {
        @Override public void run() {
            if (fpsOverlay == null) return;
            int frames;
            try {
                frames = GameBridge.nativeFrames();
            } catch (UnsatisfiedLinkError error) {
                return;
            }
            long now = android.os.SystemClock.elapsedRealtime();
            if (fpsLastFrames >= 0 && now > fpsLastTime)
                fpsOverlay.setText(Math.round((frames - fpsLastFrames) * 1000f / (now - fpsLastTime)) + " FPS");
            fpsLastFrames = frames;
            fpsLastTime = now;
            fpsOverlay.postDelayed(this, 1000);
        }
    };

    boolean fpsOverlayVisible() { return fpsOverlay != null; }

    void setFpsOverlayVisible(boolean show) {
        getSharedPreferences("game", MODE_PRIVATE).edit().putBoolean("fps", show).apply();
        if (mLayout == null) return;
        if (show && fpsOverlay == null) {
            fpsOverlay = new android.widget.TextView(this);
            fpsOverlay.setTextColor(0xFFB8E9F5);
            fpsOverlay.setTextSize(13);
            fpsOverlay.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);
            fpsOverlay.setShadowLayer(3, 1, 1, android.graphics.Color.BLACK);
            fpsOverlay.setPadding(24, 12, 24, 12);
            fpsOverlay.setText("-- FPS");
            mLayout.addView(fpsOverlay, new android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            fpsLastFrames = -1;
            fpsOverlay.post(fpsUpdate);
        } else if (!show && fpsOverlay != null) {
            fpsOverlay.removeCallbacks(fpsUpdate);
            mLayout.removeView(fpsOverlay);
            fpsOverlay = null;
        }
    }

    // ── Touch control editor (as in NFSU-X) ─────────────────────────────────────────────────────

    /** Opened from the quick menu: drag the buttons, resize the selected one, presets. */
    void openTouchEditor() {
        if (editorToolbar != null || touchControls == null) return;
        try {
            GameBridge.nativeSetMenuOpen(true); // the game holds still while editing
        } catch (UnsatisfiedLinkError ignored) {
        }
        touchControls.beginEditing();
        final android.widget.LinearLayout toolbar = new android.widget.LinearLayout(this);
        toolbar.setOrientation(android.widget.LinearLayout.VERTICAL);
        toolbar.setPadding(12, 4, 12, 4);
        toolbar.setBackgroundColor(0xEE102F40);
        android.widget.TextView title = new android.widget.TextView(this);
        title.setText(R.string.editor_hint);
        title.setPadding(8, 8, 8, 8);
        title.setOnTouchListener(new View.OnTouchListener() {
            private float offsetX, offsetY;
            @Override public boolean onTouch(View view, android.view.MotionEvent event) {
                if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
                    offsetX = event.getRawX() - toolbar.getX();
                    offsetY = event.getRawY() - toolbar.getY();
                } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_MOVE) {
                    toolbar.setX(Math.max(0, Math.min(mLayout.getWidth() - toolbar.getWidth(), event.getRawX() - offsetX)));
                    toolbar.setY(Math.max(0, Math.min(mLayout.getHeight() - toolbar.getHeight(), event.getRawY() - offsetY)));
                }
                return true;
            }
        });
        title.setTextColor(android.graphics.Color.WHITE);
        toolbar.addView(title);
        android.widget.LinearLayout actions = new android.widget.LinearLayout(this);
        toolbar.addView(actions);
        addEditorButton(actions, getString(R.string.action_save), new View.OnClickListener() {
            @Override public void onClick(View view) {
                try {
                    touchLayoutStore.save(touchControls.getLayout());
                    closeTouchEditor(true);
                } catch (java.io.IOException exception) {
                    Log.e("TouchLayout", "Cannot save layout", exception);
                    android.widget.Toast.makeText(GameActivity.this, R.string.editor_save_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            }
        });
        addEditorButton(actions, getString(R.string.editor_reset), new View.OnClickListener() {
            @Override public void onClick(View view) { touchControls.resetLayout(); }
        });
        addEditorButton(actions, getString(R.string.action_cancel), new View.OnClickListener() {
            @Override public void onClick(View view) { closeTouchEditor(false); }
        });
        final android.content.SharedPreferences editorPreferences = getSharedPreferences("touch-editor", MODE_PRIVATE);
        android.widget.CheckBox snap = new android.widget.CheckBox(this);
        snap.setText(R.string.editor_snap);
        snap.setTextColor(android.graphics.Color.WHITE);
        snap.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF55BCD8));
        snap.setChecked(editorPreferences.getBoolean("snap_to_grid", false));
        touchControls.setSnapToGrid(snap.isChecked());
        snap.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton button, boolean checked) {
                touchControls.setSnapToGrid(checked);
                editorPreferences.edit().putBoolean("snap_to_grid", checked).apply();
            }
        });
        toolbar.addView(snap);
        final android.widget.TextView sizeLabel = new android.widget.TextView(this);
        sizeLabel.setTextColor(android.graphics.Color.WHITE);
        toolbar.addView(sizeLabel);
        final android.widget.SeekBar sizeSlider = new android.widget.SeekBar(this);
        sizeSlider.setMax(Math.round((TouchLayout.MAX_SIZE - TouchLayout.MIN_SIZE) * 100));
        toolbar.addView(sizeSlider);
        final String[] names = getResources().getStringArray(R.array.touch_button_names);
        final Runnable updateSize = new Runnable() {
            @Override public void run() {
                int selected = touchControls.getSelectedButton();
                sizeSlider.setEnabled(selected >= 0);
                sizeSlider.setProgress(Math.round((touchControls.getSelectedSize() - TouchLayout.MIN_SIZE) * 100));
                sizeLabel.setText(selected < 0 ? getString(R.string.editor_select_button)
                        : names[selected] + " • " + Math.round(touchControls.getSelectedSize() * 100) + "%");
            }
        };
        touchControls.setSelectionChanged(updateSize);
        sizeSlider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar slider, int progress, boolean fromUser) {
                if (fromUser) {
                    touchControls.resizeSelected(TouchLayout.MIN_SIZE + progress / 100f);
                    updateSize.run();
                }
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar slider) { }
            @Override public void onStopTrackingTouch(android.widget.SeekBar slider) { }
        });
        updateSize.run();
        touchPresets = new TouchPresetController(this, touchControls, RuntimeFiles.appDir(this), updateSize);
        addEditorButton(actions, getString(R.string.editor_presets), new View.OnClickListener() {
            @Override public void onClick(View view) { touchPresets.show(); }
        });
        editorToolbar = toolbar;
        android.widget.RelativeLayout.LayoutParams params = new android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL);
        mLayout.addView(toolbar, params);
    }

    private void addEditorButton(android.widget.LinearLayout parent, String text, View.OnClickListener listener) {
        android.widget.Button button = new android.widget.Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        parent.addView(button);
    }

    private void closeTouchEditor(boolean save) {
        if (editorToolbar == null) return;
        mLayout.removeView(editorToolbar);
        editorToolbar = null;
        touchPresets = null;
        touchControls.setSelectionChanged(null);
        touchControls.endEditing(save);
        try {
            GameBridge.nativeSetMenuOpen(false);
        } catch (UnsatisfiedLinkError ignored) {
        }
        restoreFullscreen();
    }

    /** SDL picks the orientation from the window flags; this game is landscape only. */
    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    // Back opens the quick menu; leaving the game is its Exit button.
    @Override public void onBackPressed() {
        if (editorToolbar != null) closeTouchEditor(false);
        else showQuickMenu();
    }

    // SDL takes a controller's Back key as a pad button before onBackPressed sees it.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) onBackPressed();
            return true;
        }
        if (editorToolbar != null) return true; // the game is held while editing
        return super.dispatchKeyEvent(event);
    }

    private void showQuickMenu() {
        if (isFinishing() || isDestroyed() || (quickMenu != null && quickMenu.isShowing())) return;
        if (touchControls != null) touchControls.cancelInput();
        quickMenu = QuickMenuDialog.show(this);
        quickMenu.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                quickMenu = null;
                QuickMenuDialog.closed();
                restoreFullscreen();
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        if (touchPresets != null && touchPresets.onActivityResult(requestCode, resultCode, data)) return;
        if (requestCode == QuickMenuDialog.PICK_MODS) {
            if (resultCode == RESULT_OK) QuickMenuDialog.modsPicked(this, data);
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    /** The quick menu's Exit: the system back behaviour this activity replaced. */
    void exitGame() {
        super.onBackPressed();
        // SDL_main does not return on its own; end the separate :game process.
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    // The activity owns the system bars; SDL owns the surface and events.
    private final Runnable fullscreen = new Runnable() {
        @Override public void run() {
            if (isFinishing() || isDestroyed()) return;
            Window window = getWindow();
            window.clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.systemBars());
            }
        }
    };

    private void restoreFullscreen() {
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(fullscreen);
        decor.post(fullscreen);
    }

    @Override
    protected void onResume() {
        super.onResume();
        restoreFullscreen();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) restoreFullscreen();
    }

    @Override
    protected void onPause() {
        if (touchControls != null) touchControls.cancelInput();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (quickMenu != null) {
            quickMenu.dismiss();
            quickMenu = null;
        }
        super.onDestroy();
    }
}
