package com.gzero64;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * The three dumps the game needs: the F-Zero X cartridge, the translated Expansion Kit disk and
 * the 64DD IPL. They are found by size and SHA-1 in the folder the player picks, whatever their
 * names, and copied into the app folder under the names port/gdx_firstboot.cpp looks for.
 * Byte-swapped (.v64) and little-endian (.n64) cartridge dumps are converted while copying.
 */
final class GameFiles {
    static final long ROM_SIZE = 16L * 1024 * 1024;
    static final long DISK_SIZE = 64931840L;
    static final long IPL_SIZE = 4L * 1024 * 1024;

    static final String ROM_US = "baserom.us.rev0.z64";
    static final String ROM_PAL = "baserom.pal.rev0.z64";
    static final String DISK = "baserom.translated.ek.ndd";
    static final String IPL_JP = "N64DDIPLROM.n64";
    static final String IPL_US = "64DD_IPL_US_MJR.n64";

    private static final String SHA1_ROM_US = "5f658e88ffa9de23cba6986a8fd3d3a90d7b4340";
    private static final String SHA1_ROM_PAL = "af9038bcb543a2b4faaab876dbdab4663859e092";
    private static final String SHA1_ROM_JP = "a418b0151521b76691fa03f8658c8b567c69498b";
    private static final String SHA1_DISK = "fde9fa6f29a52be0144bda74caf8583c036c20ce";
    private static final String SHA1_DISK_JP = "7e8badf857f1fce8aa59307c0fd318128c44418b";
    private static final String SHA1_IPL_JP = "bf861922dcb78c316360e3e742f4f70ff63c9bc3";
    private static final String SHA1_IPL_US = "3c5b93ca231550c68693a14f03cea8d5dbd1be9e";

    /** What a folder scan found; the names are the source files, null when missing. */
    static final class Scan {
        String rom, disk, ipl;
        boolean japaneseRom, japaneseDisk;
    }

    private GameFiles() { }

    /** True when the app folder holds every input, as a dump or as the archive extracted from it. */
    static boolean installed(File dir) {
        boolean rom = new File(dir, ROM_US).isFile() || new File(dir, ROM_PAL).isFile()
            || new File(dir, "fzerox.o2r").isFile() || new File(dir, "fzerox-pal.o2r").isFile();
        boolean disk = new File(dir, DISK).isFile() || new File(dir, "media/" + DISK).isFile()
            || new File(dir, "fzerox-disk.o2r").isFile();
        boolean ipl = new File(dir, IPL_JP).isFile() || new File(dir, IPL_US).isFile()
            || new File(dir, "n64ddipl.o2r").isFile();
        return rom && disk && ipl;
    }

    /** Looks at every file of a plausible size in the picked folder and copies the ones it knows. */
    static Scan importFrom(ContentResolver resolver, Uri tree, File target) throws IOException {
        Scan scan = new Scan();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
            DocumentsContract.getTreeDocumentId(tree));
        String[] columns = {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE};
        List<String[]> files = new ArrayList<>();
        try (Cursor cursor = resolver.query(children, columns, null, null, null)) {
            if (cursor == null) throw new IOException("Cannot list the folder");
            while (cursor.moveToNext()) {
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(3))) continue;
                long size = cursor.getLong(2);
                if (size != ROM_SIZE && size != DISK_SIZE && size != IPL_SIZE) continue;
                files.add(new String[] {cursor.getString(0), cursor.getString(1), Long.toString(size)});
            }
        }
        if (!target.isDirectory() && !target.mkdirs()) throw new IOException("Cannot create " + target);
        for (String[] file : files) {
            Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, file[0]);
            long size = Long.parseLong(file[2]);
            if (size == ROM_SIZE && scan.rom == null) {
                File temporary = new File(target, ".import.tmp");
                String sha1 = copy(resolver, document, temporary, true);
                if (SHA1_ROM_US.equals(sha1) || SHA1_ROM_PAL.equals(sha1)) {
                    install(temporary, new File(target, SHA1_ROM_US.equals(sha1) ? ROM_US : ROM_PAL));
                    scan.rom = file[1];
                } else {
                    temporary.delete();
                    if (SHA1_ROM_JP.equals(sha1)) scan.japaneseRom = true;
                }
            } else if (size == DISK_SIZE && scan.disk == null) {
                File temporary = new File(target, ".import.tmp");
                String sha1 = copy(resolver, document, temporary, false);
                if (SHA1_DISK.equals(sha1)) {
                    install(temporary, new File(target, DISK));
                    scan.disk = file[1];
                } else {
                    temporary.delete();
                    if (SHA1_DISK_JP.equals(sha1)) scan.japaneseDisk = true;
                }
            } else if (size == IPL_SIZE && scan.ipl == null) {
                File temporary = new File(target, ".import.tmp");
                String sha1 = copy(resolver, document, temporary, false);
                if (SHA1_IPL_JP.equals(sha1) || SHA1_IPL_US.equals(sha1)) {
                    install(temporary, new File(target, SHA1_IPL_JP.equals(sha1) ? IPL_JP : IPL_US));
                    scan.ipl = file[1];
                } else {
                    temporary.delete();
                }
            }
        }
        return scan;
    }

    private static void install(File temporary, File destination) throws IOException {
        if (destination.exists() && !destination.delete()) throw new IOException("Cannot replace " + destination);
        if (!temporary.renameTo(destination)) throw new IOException("Cannot write " + destination);
    }

    /** Copies a document and returns the SHA-1 of what was written (after byte order fixes). */
    private static String copy(ContentResolver resolver, Uri document, File destination, boolean cartridge)
            throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException(exception);
        }
        byte[] buffer = new byte[1 << 20];
        int order = 0; // 0 big-endian (.z64), 1 byte-swapped (.v64), 2 little-endian (.n64)
        boolean first = true;
        try (InputStream input = resolver.openInputStream(document);
             OutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Cannot open " + document);
            int filled;
            while ((filled = readFully(input, buffer)) > 0) {
                if (cartridge && first && filled >= 4) {
                    if ((buffer[0] & 255) == 0x37 && (buffer[1] & 255) == 0x80) order = 1;
                    else if ((buffer[0] & 255) == 0x40 && (buffer[1] & 255) == 0x12) order = 2;
                }
                first = false;
                if (order == 1) {
                    for (int i = 0; i + 1 < filled; i += 2) {
                        byte t = buffer[i]; buffer[i] = buffer[i + 1]; buffer[i + 1] = t;
                    }
                } else if (order == 2) {
                    for (int i = 0; i + 3 < filled; i += 4) {
                        byte a = buffer[i], b = buffer[i + 1];
                        buffer[i] = buffer[i + 3]; buffer[i + 1] = buffer[i + 2];
                        buffer[i + 2] = b; buffer[i + 3] = a;
                    }
                }
                digest.update(buffer, 0, filled);
                output.write(buffer, 0, filled);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
        return hex.toString();
    }

    /** Fills the buffer unless the stream ends (chunks stay 4-byte aligned for the swaps). */
    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int total = 0;
        while (total < buffer.length) {
            int count = input.read(buffer, total, buffer.length - total);
            if (count < 0) break;
            total += count;
        }
        return total;
    }
}
