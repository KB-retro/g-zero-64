package com.gzero64;

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

    /** While open, the game holds still and keeps serving the calls below. */
    static native void nativeSetMenuOpen(boolean open);

    /** G-Diffuser's menu as JSON: sections, pages and rows with values and ranges. "" on no answer. */
    static native String nativeMenuJson();

    /** Changes one row as the desktop menu would (callback included). */
    static native boolean nativeMenuSet(String id, double value);

    /** Presses a button row. */
    static native boolean nativeMenuPress(String id);

    /** Frames drawn so far, for the FPS counter. */
    static native int nativeFrames();

    /** Touch controls: N64 buttons for port 1 and the stick (-80..80, up positive). */
    static native void nativeSetTouch(int buttons, int stickX, int stickY);

    /** First-time setup: 0 none, 1 waiting for files, 2 installing, 3 failed, 4 done. */
    static native int nativeSetupState();

    /** First-time setup progress, 0..1. */
    static native float nativeSetupProgress();

    /** A race is running (not paused, not a demo, not finished): the racing touch scheme applies. */
    static native boolean nativeInRace();

    static void setInt(String name, int value) {
        nativeSet(name, false, value);
    }

    static void setFloat(String name, float value) {
        nativeSet(name, true, value);
    }
}
