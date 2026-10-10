package com.astra.loremotionbridge;

import android.content.Context;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import java.nio.file.*;

public final class ProrootRuntime {

    private static final String[] LIBRARIES = {
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-linker.so",
            "libproroot-bridge.so",
            "libproroot-stub-loader.so"
    };

    private static final String ROOTFS_ASSET =
            "ubuntu-base-arm64.tar";

    private ProrootRuntime() {}

    public static File rootDir(Context context) {
        return new File(context.getFilesDir(), "proroot");
    }

    public static File rootfsDir(Context context) {
        return new File(rootDir(context), "rootfs");
    }

    public static File tmpDir(Context context) {
        return new File(rootDir(context), "tmp");
    }

    public static File libDir(Context context) {
        return new File(rootDir(context), "lib");
    }

    public static File prorootExecutable(Context context) {
        return new File(libDir(context), "libproroot.so");
    }

    public static void prepare(Context context) throws Exception {
        rootDir(context).mkdirs();
        rootfsDir(context).mkdirs();
        tmpDir(context).mkdirs();
        libDir(context).mkdirs();

        extractLibraries(context);
    }

    private static void extractLibraries(Context context) throws Exception {
        ZipFile apk = new ZipFile(context.getApplicationInfo().sourceDir);

        try {
            for (String name : LIBRARIES) {
                File target = new File(libDir(context), name);

                if (target.exists() && target.length() > 0) {
                    continue;
                }

                ZipEntry entry =
                        apk.getEntry("lib/arm64-v8a/" + name);

                if (entry == null) {
                    throw new IllegalStateException(
                            "Missing APK library: " + name
                    );
                }

                try (InputStream in = apk.getInputStream(entry);
                     FileOutputStream out = new FileOutputStream(target)) {

                    byte[] buffer = new byte[8192];
                    int read;

                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                target.setReadable(true, false);
                target.setWritable(true, true);
                target.setExecutable(true, false);
            }
        } finally {
            apk.close();
        }
    }

    public static boolean checkExtracted(Context context) {
        for (String name : LIBRARIES) {
            File file = new File(libDir(context), name);

            if (!file.exists() || file.length() == 0) {
                return false;
            }
        }

        return true;
    }

    public static boolean rootfsExists(Context context) {
        File sh = new File(rootfsDir(context), "bin/sh");
        return sh.exists();
    }

    public static void extractRootfs(Context context) throws Exception {

        File rootfs = rootfsDir(context);
        File marker = new File(rootfs, ".astra_rootfs_ready");

        if (marker.exists() &&
                new File(rootfs, "bin/sh").exists()) {
            return;
        }

        File temp = new File(rootDir(context), "rootfs.tmp");

        deleteRecursive(temp);
        temp.mkdirs();

        try (InputStream raw =
                     context.getAssets().open(ROOTFS_ASSET);
             TarInputStream tar =
                     new TarInputStream(new BufferedInputStream(raw))) {

            TarEntry entry;

            while ((entry = tar.nextEntry()) != null) {

                String name = entry.name;

                while (name.startsWith("./")) {
                    name = name.substring(2);
                }

                if (name.isEmpty()) {
                    continue;
                }

                File target = new File(temp, name);

                if (!target.getCanonicalPath()
                        .startsWith(temp.getCanonicalPath() + File.separator)) {
                    throw new IOException("Unsafe tar path: " + name);
                }

                if (entry.isDirectory()) {
                    target.mkdirs();
                    continue;
                }

                if (entry.isSymbolicLink()) {
                    target.getParentFile().mkdirs();
                    Files.deleteIfExists(target.toPath());
                    Files.createSymbolicLink(
                            target.toPath(),
                            Paths.get(entry.linkName)
                    );
                    continue;
                }

                if (entry.isHardLink()) {
                    File source = new File(temp, entry.linkName);

                    if (!source.exists()) {
                        throw new IOException(
                                "Hardlink target missing: " +
                                entry.linkName
                        );
                    }

                    target.getParentFile().mkdirs();
                    copyFile(source, target);
                    continue;
                }

                target.getParentFile().mkdirs();

                try (FileOutputStream out =
                             new FileOutputStream(target)) {

                    byte[] buffer = new byte[32768];
                    int read;

                    while ((read = tar.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }

                int mode = entry.mode();
                target.setReadable((mode & 0444) != 0, false);
                target.setWritable((mode & 0222) != 0, false);
                target.setExecutable((mode & 0111) != 0, false);
            }
        }

        File sh = new File(temp, "bin/sh");

        if (!sh.exists()) {
            deleteRecursive(temp);
            throw new IOException("Ubuntu rootfs extraction incomplete: /bin/sh missing");
        }

        File old = rootfs;

        if (old.exists()) {
            deleteRecursive(old);
        }

        if (!temp.renameTo(old)) {
            throw new IOException("Cannot activate rootfs");
        }

        if (!marker.createNewFile()) {
            throw new IOException("Cannot create rootfs marker");
        }
    }

    private static void copyFile(File source, File target)
            throws IOException {

        target.getParentFile().mkdirs();

        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {

            byte[] buffer = new byte[32768];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }

        target.setExecutable(source.canExecute(), false);
    }

    private static void deleteRecursive(File file) {
        if (!file.exists()) {
            return;
        }

        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] children = file.listFiles();

            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }

        file.delete();
    }

    private static final class TarEntry {
        String name;
        String linkName;
        int type;
        long size;
        int mode;

        boolean isDirectory() {
            return type == '5' || name.endsWith("/");
        }

        boolean isSymbolicLink() {
            return type == '2';
        }

        boolean isHardLink() {
            return type == '1';
        }

        int mode() {
            return mode;
        }
    }

    private static final class TarInputStream
            extends InputStream {

        private final InputStream in;
        private long remaining;
        private long padding;

        TarInputStream(InputStream in) {
            this.in = in;
        }

        TarEntry nextEntry() throws IOException {

            skipFully(padding);
            padding = 0;

            byte[] header = new byte[512];
            int first = in.read();

            if (first == -1) {
                return null;
            }

            header[0] = (byte) first;
            readFully(header, 1, 511);

            boolean empty = true;

            for (byte b : header) {
                if (b != 0) {
                    empty = false;
                    break;
                }
            }

            if (empty) {
                return null;
            }

            TarEntry e = new TarEntry();

            e.name = string(header, 0, 100);
            e.mode = (int) number(header, 100, 8);
            e.size = number(header, 124, 12);
            e.type = header[156];

            String prefix = string(header, 345, 155);

            if (!prefix.isEmpty()) {
                e.name = prefix + "/" + e.name;
            }

            e.linkName = string(header, 157, 100);

            remaining = e.size;

            padding = (512 - (e.size % 512)) % 512;

            return e;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }

            int b = in.read();

            if (b >= 0) {
                remaining--;
            }

            return b;
        }

        @Override
        public int read(byte[] b, int off, int len)
                throws IOException {

            if (remaining == 0) {
                return -1;
            }

            int wanted = (int) Math.min(len, remaining);
            int n = in.read(b, off, wanted);

            if (n > 0) {
                remaining -= n;
            }

            return n;
        }

        private static String string(
                byte[] b, int off, int len) {

            int end = off;

            while (end < off + len && b[end] != 0) {
                end++;
            }

            return new String(
                    b,
                    off,
                    end - off,
                    java.nio.charset.StandardCharsets.UTF_8
            );
        }

        private static long number(
                byte[] b, int off, int len) {

            long value = 0;

            for (int i = off; i < off + len; i++) {
                if (b[i] == 0 || b[i] == ' ') {
                    continue;
                }

                value = (value << 3) + (b[i] - '0');
            }

            return value;
        }

        private void readFully(
                byte[] b, int off, int len)
                throws IOException {

            while (len > 0) {
                int n = in.read(b, off, len);

                if (n < 0) {
                    throw new EOFException();
                }

                off += n;
                len -= n;
            }
        }

        private void skipFully(long n)
                throws IOException {

            while (n > 0) {
                long skipped = in.skip(n);

                if (skipped <= 0) {
                    if (in.read() == -1) {
                        throw new EOFException();
                    }
                    skipped = 1;
                }

                n -= skipped;
            }
        }
    }
}
