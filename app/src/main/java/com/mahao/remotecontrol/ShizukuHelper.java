package com.mahao.remotecontrol;

import android.content.pm.PackageManager;

import rikka.shizuku.Shizuku;

public final class ShizukuHelper {

    private static final int REQUEST_CODE = 4211;
    private static volatile boolean binderAlive = false;

    private ShizukuHelper() { }

    public static void init() {
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> binderAlive = true);
            Shizuku.addBinderDeadListener(() -> binderAlive = false);
        } catch (Throwable ignored) {
        }
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isReady() {
        return isBinderAlive() && hasPermission();
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    public static int requestCode() {
        return REQUEST_CODE;
    }

    public static String status() {
        if (!isBinderAlive()) return "shizuku:off(service not running)";
        if (!hasPermission()) return "shizuku:off(permission denied)";
        return "shizuku:on";
    }
}
