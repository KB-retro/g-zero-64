package io.github.kbretro.gzero64;

import android.content.Context;

/** Launcher preferences kept outside the game's own configuration. */
final class LauncherSettings {
    private LauncherSettings() { }

    static boolean autoStart(Context context) {
        return context.getSharedPreferences("game", Context.MODE_PRIVATE).getBoolean("auto_start", false);
    }

    static void setAutoStart(Context context, boolean value) {
        context.getSharedPreferences("game", Context.MODE_PRIVATE).edit().putBoolean("auto_start", value).apply();
    }
}
