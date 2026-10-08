package com.astra.loremotionbridge;

import android.app.Application;
import android.content.Context;

public class MainApplication extends Application {
    private static Context ctx;
    @Override public void onCreate() { super.onCreate(); ctx = getApplicationContext(); }
    public static Context context() { return ctx; }
}
