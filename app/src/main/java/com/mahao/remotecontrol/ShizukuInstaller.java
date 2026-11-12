package com.mahao.remotecontrol;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 把随 APK 打包的 Shizuku Manager（assets/shizuku-manager.apk）装到系统里。
 * 仍然需要用户确认安装并手动启动一次服务：普通应用无法自行获得 shell(2000) 身份。
 */
public final class ShizukuInstaller {

    public static final String PKG = "moe.shizuku.privileged.api";
    public static final String BUNDLED_VERSION = "13.6.0";

    private static final String ASSET = "shizuku-manager.apk";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private ShizukuInstaller() { }

    public static boolean isInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public static String installedVersion(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(PKG, 0);
            return pi.versionName;
        } catch (Throwable t) {
            return null;
        }
    }

    public static boolean canInstall(Context ctx) {
        try {
            return ctx.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable t) {
            return true;
        }
    }

    public static Intent unknownSourcesSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        i.setData(Uri.parse("package:" + ctx.getPackageName()));
        return i;
    }

    /** 把内置包写到缓存并拉起系统安装器。返回 null 表示已拉起，否则为错误描述。 */
    public static String install(Context ctx) {
        try {
            File apk = ShizukuApkProvider.target(ctx);
            try (InputStream in = ctx.getAssets().open(ASSET);
                 OutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            Intent view = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(ShizukuApkProvider.uri(), APK_MIME)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(view);
            return null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }
}
