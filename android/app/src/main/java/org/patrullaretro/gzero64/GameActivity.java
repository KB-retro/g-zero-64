package org.patrullaretro.gzero64;

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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Read by the native side before anything else (port/gdx_self_exe.h).
        File appDir = RuntimeFiles.appDir(this);
        try {
            Os.setenv("GDX_APP_DIR", appDir.getAbsolutePath(), true);
            Os.setenv("GDX_EXTRACT_BIN", getApplicationInfo().nativeLibraryDir + "/libgdx_extract.so", true);
            // gdiffuser-run.log, saved from Settings when something goes wrong.
            Os.setenv("GDX_LOG", "1", true);
            // GLES samples the CPU-drawn boot logo bottom-up, unlike desktop GL.
            Os.setenv("GDX_VI_SCANOUT_FLIP", "1", true);
        } catch (ErrnoException exception) {
            Log.e("GZero64", "Cannot export the app folder", exception);
        }
        super.onCreate(savedInstanceState);
        QuickMenuDialog.applyAutomaticFormat(this);
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
        showQuickMenu();
    }

    // SDL takes a controller's Back key as a pad button before onBackPressed sees it.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) showQuickMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showQuickMenu() {
        if (isFinishing() || isDestroyed() || (quickMenu != null && quickMenu.isShowing())) return;
        quickMenu = QuickMenuDialog.show(this);
        quickMenu.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                quickMenu = null;
                QuickMenuDialog.closed();
                restoreFullscreen();
            }
        });
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
    protected void onDestroy() {
        if (quickMenu != null) {
            quickMenu.dismiss();
            quickMenu = null;
        }
        super.onDestroy();
    }
}
