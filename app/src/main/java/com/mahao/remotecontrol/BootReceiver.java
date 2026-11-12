package com.mahao.remotecontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 开机 / 应用更新后自动拉起中继服务，让电脑端无需人工打开 App 就能重连。
 * 用户点过「断开」后不再自动连接（断开时会清掉保存的地址）。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences("remote", Context.MODE_PRIVATE);
        String saved = prefs.getString("server_url", "");
        if (saved == null || saved.isEmpty()) return;

        try {
            Intent service = new Intent(context, WsRemoteService.class).setAction("BOOT");
            context.startForegroundService(service);
        } catch (Throwable ignored) {
            // 前台服务被系统拒绝时，无障碍服务连接后还会再尝试一次
        }
    }
}
