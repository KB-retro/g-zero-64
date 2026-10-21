package com.gzero64;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The files desktop releases ship beside the executable (engine archive, fonts, shaders, extraction
 * recipes, controller database) travel as APK assets under runtime/ and are copied into the app
 * folder once per build.
 */
final class RuntimeFiles {
    private static final String ROOT = "runtime";
    private static final String STAMP = ".runtime-build";

    private RuntimeFiles() { }

    /** The folder the game treats as its install and data directory. */
    static File appDir(Context context) {
        File external = context.getExternalFilesDir(null);
        return external != null ? external : context.getFilesDir();
    }

    static void install(Context context) throws IOException {
        File dir = appDir(context);
        File stamp = new File(dir, STAMP);
        String build = BuildConfig.VERSION_CODE + "-" + BuildConfig.BUILD_COMMIT_FULL;
        if (build.equals(read(stamp))) return;
        copyTree(context.getAssets(), ROOT, dir);
        try (OutputStream output = new FileOutputStream(stamp)) {
            output.write(build.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyTree(AssetManager assets, String path, File destination) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs())
                throw new IOException("Cannot create " + parent);
            File temporary = new File(destination.getPath() + ".tmp");
            try (InputStream input = assets.open(path); OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[1 << 16];
                int count;
                while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
            }
            if (!temporary.renameTo(destination)) throw new IOException("Cannot write " + destination);
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) throw new IOException("Cannot create " + destination);
        for (String child : children) copyTree(assets, path + "/" + child, new File(destination, child));
    }

    private static String read(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            byte[] data = new byte[(int) Math.min(file.length(), 256)];
            int count = input.read(data);
            return count <= 0 ? "" : new String(data, 0, count, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }
}
