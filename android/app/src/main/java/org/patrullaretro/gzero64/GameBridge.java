package org.patrullaretro.gzero64;

/**
 * Game settings for the quick menu (port/gdx_android_bridge.cpp). Reads are answered by the game
 * thread within a frame; writes are queued and applied on its next frame, so a write made while the
 * game is paused takes effect when it resumes.
 */
final class GameBridge {
    private GameBridge() { }

    /** One value per name; isFloat picks the setting's type, defaults cover unset or no answer. */
    static native float[] nativeGet(String[] names, boolean[] isFloat, float[] defaults);

    static native void nativeSet(String name, boolean isFloat, float value);

    static void setInt(String name, int value) {
        nativeSet(name, false, value);
    }

    static void setFloat(String name, float value) {
        nativeSet(name, true, value);
    }
}
