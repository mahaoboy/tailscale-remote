package com.mahao.remotecontrol;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

public class CrashHandler implements Thread.UncaughtExceptionHandler {

    private static final String TAG = "CrashHandler";

    @Override
    public void uncaughtException(Thread thread, Throwable throwable) {
        Log.e(TAG, "Uncaught exception in thread: " + thread.getName(), throwable);

        StringWriter sw = new StringWriter();
        throwable.printStackTrace(new PrintWriter(sw));
        String stackTrace = sw.toString();

        // Save to file
        try {
            java.io.File dir = android.os.Environment.getExternalStorageDirectory();
            java.io.File crashFile = new java.io.File(dir, "crash_" + System.currentTimeMillis() + ".txt");
            java.io.FileWriter fw = new java.io.FileWriter(crashFile);
            fw.write(stackTrace);
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save crash log", e);
        }

        // Show dialog on main thread
        try {
            final Context context = AppContext.getAppContext();
            if (context != null) {
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            AlertDialog.Builder builder = new AlertDialog.Builder(context);
                            builder.setTitle("应用崩溃 - 错误信息");
                            builder.setMessage(stackTrace);
                            builder.setPositiveButton("复制", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    try {
                                        android.content.ClipboardManager clip = (android.content.ClipboardManager)
                                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                                        android.content.ClipData data = android.content.ClipData.newPlainText("crash", stackTrace);
                                        clip.setPrimaryClip(data);
                                        dialog.dismiss();
                                    } catch (Exception e) {}
                                }
                            });
                            builder.setNegativeButton("关闭", null);
                            builder.create().show();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to show dialog", e);
                        }
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to show crash dialog", e);
        }
    }
}
