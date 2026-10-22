package com.gzero64;

import android.util.AtomicFile;
import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** The same private game directory contains the log and this atomic XML file. */
final class TouchLayoutStore {
    private final AtomicFile file;
    private final String[] ids;

    TouchLayoutStore(File directory, String filename, String[] ids) {
        file = new AtomicFile(new File(directory, filename));
        this.ids = ids;
    }

    TouchLayout loadChecked() throws IOException {
        try (FileInputStream input = file.openRead()) {
            return TouchLayout.read(input, ids);
        }
    }

    void delete() throws IOException {
        file.delete();
        if (file.getBaseFile().exists() || new File(file.getBaseFile() + ".bak").exists())
            throw new IOException("Cannot delete preset");
    }

    TouchLayout load() {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists()) {
            return new TouchLayout(ids);
        }
        try {
            return loadChecked();
        } catch (IOException exception) {
            Log.w("TouchLayout", "Cannot load layout; using defaults", exception);
            return new TouchLayout(ids);
        }
    }

    void save(TouchLayout layout) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            layout.write(output);
            file.finishWrite(output);
        } catch (IOException exception) {
            if (output != null) file.failWrite(output);
            throw exception;
        }
    }
}
