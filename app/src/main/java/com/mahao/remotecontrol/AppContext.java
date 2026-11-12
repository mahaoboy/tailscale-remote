package com.mahao.remotecontrol;

import android.app.Application;
import android.content.Context;

public class AppContext extends Application {
    private static AppContext instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler());
    }

    public static Context getAppContext() {
        return instance;
    }
}
