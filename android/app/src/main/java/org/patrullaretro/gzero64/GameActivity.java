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
    private boolean selectHeld, startHeld;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Read by the native side before anything else (port/gdx_self_exe.h).
        File appDir = RuntimeFiles.appDir(this);
        try {
            Os.setenv("GDX_APP_DIR", appDir.getAbsolutePath(), true);
            Os.setenv("GDX_EXTRACT_BIN", getApplicationInfo().nativeLibraryDir + "/libgdx_extract.so", true);
        } catch (ErrnoException exception) {
            Log.e("GZero64", "Cannot export the app folder", exception);
        }
        super.onCreate(savedInstanceState);
        restoreFullscreen();
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    // Back opens the quick menu; leaving the game is its Exit button.
    @Override public void onBackPressed() {
        showQuickMenu();
    }

    private void showQuickMenu() {
        if (isFinishing() || isDestroyed() || (quickMenu != null && quickMenu.isShowing())) return;
        selectHeld = startHeld = false;
        quickMenu = QuickMenuDialog.show(this);
        quickMenu.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override public void onDismiss(android.content.DialogInterface dialog) {
                quickMenu = null;
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

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        // Controller: Select + Start together open the quick menu.
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BUTTON_SELECT || code == KeyEvent.KEYCODE_BUTTON_START) {
            boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            if (code == KeyEvent.KEYCODE_BUTTON_SELECT) selectHeld = down;
            else startHeld = down;
            if (down && event.getRepeatCount() == 0 && selectHeld && startHeld) {
                boolean handled = super.dispatchKeyEvent(event);
                showQuickMenu();
                return handled;
            }
        }
        return super.dispatchKeyEvent(event);
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
