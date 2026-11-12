package com.mahao.remotecontrol;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.util.Locale;

public class ShizukuOnboardActivity extends android.app.Activity {

    private TextView txtStep1Title;
    private TextView txtStep1Desc;
    private TextView txtStep2Title;
    private TextView txtStep2Desc;
    private TextView txtStep3Title;
    private TextView txtStep3Desc;
    private TextView txtStatus;
    private Button btnInstall;
    private Button btnOpen;
    private Button btnDebug;
    private Button btnGrant;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoRequested = false;
    private String notice;
    private long noticeUntil;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            render();
            handler.postDelayed(this, 1000);
        }
    };

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> render();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_shizuku_onboard);

        txtStep1Title = findViewById(R.id.txtStep1Title);
        txtStep1Desc = findViewById(R.id.txtStep1Desc);
        txtStep2Title = findViewById(R.id.txtStep2Title);
        txtStep2Desc = findViewById(R.id.txtStep2Desc);
        txtStep3Title = findViewById(R.id.txtStep3Title);
        txtStep3Desc = findViewById(R.id.txtStep3Desc);
        txtStatus = findViewById(R.id.txtOnboardStatus);
        btnInstall = findViewById(R.id.btnInstall);
        btnOpen = findViewById(R.id.btnOpen);
        btnDebug = findViewById(R.id.btnDebug);
        btnGrant = findViewById(R.id.btnGrant);

        btnInstall.setOnClickListener(v -> onInstall());
        btnOpen.setOnClickListener(v -> onOpenShizuku());
        btnDebug.setOnClickListener(v -> onOpenDebugSettings());
        btnGrant.setOnClickListener(v -> {
            ShizukuHelper.requestPermission();
            render();
        });
        ((Button) findViewById(R.id.btnDone)).setOnClickListener(v -> finish());

        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(tick);
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(permissionListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private void onInstall() {
        if (!ShizukuInstaller.canInstall(this)) {
            notice("请先允许「远程控制」安装未知来源应用，再返回本页继续");
            try {
                startActivity(ShizukuInstaller.unknownSourcesSettings(this));
            } catch (Throwable t) {
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
                } catch (Throwable ignored) {
                }
            }
            return;
        }
        String error = ShizukuInstaller.install(this);
        notice(error == null
                ? "已打开系统安装界面，点「安装」确认"
                : "内置安装失败：" + error);
        render();
    }

    private void onOpenShizuku() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ShizukuInstaller.PKG);
        if (launch == null) {
            notice("Shizuku 尚未安装，请先完成第 1 步");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void onOpenDebugSettings() {
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Throwable t) {
            notice("无法打开开发者选项：" + t.getMessage());
        }
    }

    private void render() {
        boolean installed = ShizukuInstaller.isInstalled(this);
        boolean alive = ShizukuHelper.isBinderAlive();
        boolean granted = ShizukuHelper.hasPermission();

        String installedVersion = installed ? ShizukuInstaller.installedVersion(this) : null;
        String versionText = installedVersion == null
                ? ShizukuInstaller.BUNDLED_VERSION
                : installedVersion;

        step(txtStep1Title, txtStep1Desc, btnInstall, installed,
                "1. 安装 Shizuku",
                installed
                        ? "已安装 " + versionText
                        : "从本应用内置的安装包安装 Shizuku " + ShizukuInstaller.BUNDLED_VERSION + "，无需联网下载",
                installed ? "已安装" : "安装内置 Shizuku");

        step(txtStep2Title, txtStep2Desc, btnOpen, alive,
                "2. 启动 Shizuku 服务",
                alive
                        ? "服务已运行"
                        : !installed
                                ? "请先完成第 1 步"
                                : "打开 Shizuku，按首页提示启动服务。Android 11+ 选「通过无线调试启动」，按提示配对一次即可",
                alive ? "服务运行中" : "打开 Shizuku");
        btnDebug.setEnabled(installed && !alive);
        btnDebug.setVisibility(!installed || alive ? View.GONE : View.VISIBLE);

        step(txtStep3Title, txtStep3Desc, btnGrant, granted,
                "3. 授权本应用",
                granted
                        ? "已授权，shell 能力可用"
                        : !alive
                                ? "服务启动后会自动弹出授权请求"
                                : "Shizuku 会弹出授权框，请选择「允许」",
                granted ? "已授权" : "授权本应用");
        btnGrant.setEnabled(alive && !granted);

        if (alive && !granted && !autoRequested) {
            autoRequested = true;
            ShizukuHelper.requestPermission();
        }

        if (SystemClock.elapsedRealtime() < noticeUntil) {
            txtStatus.setText(notice);
        } else if (granted) {
            txtStatus.setText("shell 能力已启用：pm / input / dumpsys / 安装应用均可使用");
        } else if (alive) {
            txtStatus.setText("Shizuku 服务已运行，等待授权…");
        } else if (installed) {
            txtStatus.setText("下一步：打开 Shizuku 启动服务");
        } else {
            txtStatus.setText("下一步：安装内置 Shizuku");
        }
    }

    private void notice(String text) {
        notice = text;
        noticeUntil = SystemClock.elapsedRealtime() + 5000;
        txtStatus.setText(text);
    }

    private void step(TextView title, TextView desc, Button button,
                      boolean done, String titleText, String descText, String buttonText) {
        title.setText(String.format(Locale.US, "%s %s", done ? "✔" : "○", titleText));
        title.setTextColor(done ? 0xFF3DC000 : 0xFF222222);
        desc.setText(descText);
        button.setText(buttonText);
        button.setEnabled(!done);
    }
}
