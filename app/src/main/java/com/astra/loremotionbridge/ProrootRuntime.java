package com.astra.loremotionbridge;

import android.content.Context;
import java.io.File;

public final class ProrootRuntime {

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

    public static boolean checkLibraries(Context context) {
        String[] names = {
                "libproroot.so",
                "libproroot-runtime.so",
                "libproroot-linker.so",
                "libproroot-bridge.so",
                "libproroot-stub-loader.so"
        };

        File libDir = new File(context.getApplicationInfo().nativeLibraryDir);

        for (String name : names) {
            if (!new File(libDir, name).exists()) {
                return false;
            }
        }

        return true;
    }

    public static void prepare(Context context) {
        rootDir(context).mkdirs();
        rootfsDir(context).mkdirs();
        tmpDir(context).mkdirs();
    }
}
