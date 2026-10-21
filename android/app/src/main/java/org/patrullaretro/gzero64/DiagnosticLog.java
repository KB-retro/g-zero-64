package org.patrullaretro.gzero64;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zips the game's logs and setup state (no game data) into a file the player picked. */
final class DiagnosticLog {
    private static final long MAX_FILE = 8L * 1024 * 1024;

    private DiagnosticLog() { }

    static boolean save(Context context, Uri target) {
        File dir = RuntimeFiles.appDir(context);
        try (OutputStream raw = context.getContentResolver().openOutputStream(target, "w");
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            if (raw == null) return false;
            zip.putNextEntry(new ZipEntry("device.txt"));
            String device = "G-Zero 64 " + BuildConfig.VERSION_NAME + " (" + BuildConfig.BUILD_COMMIT + ")\n"
                + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                + " (API " + Build.VERSION.SDK_INT + "), " + Build.HARDWARE + "\n";
            zip.write(device.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    String name = file.getName();
                    boolean wanted = name.endsWith(".log") || name.endsWith(".cfg") || (name.endsWith(".txt")
                        && !name.equals("gamecontrollerdb.txt"));
                    if (!file.isFile() || !wanted || file.length() > MAX_FILE) continue;
                    zip.putNextEntry(new ZipEntry(name));
                    try (InputStream input = new FileInputStream(file)) {
                        byte[] buffer = new byte[1 << 16];
                        int count;
                        while ((count = input.read(buffer)) > 0) zip.write(buffer, 0, count);
                    }
                    zip.closeEntry();
                }
            }
            return true;
        } catch (IOException | RuntimeException exception) {
            Log.e("GZero64", "Cannot save the log", exception);
            return false;
        }
    }
}
