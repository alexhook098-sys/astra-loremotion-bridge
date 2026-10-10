package com.astra.loremotionbridge;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ProrootRuntime {

    private static final String[] LIBRARIES = {
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-linker.so",
            "libproroot-bridge.so",
            "libproroot-stub-loader.so"
    };

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
}
