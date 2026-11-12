package com.mahao.remotecontrol;

import android.app.AlertDialog;
import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.graphics.Color;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

public class MainActivity extends android.app.Activity {

    private EditText editServerUrl;
    private Button btnConnect;
    private Button btnDisconnect;
    private TextView txtStatus;
    private TextView txtA11yHint;
    private TextView txtShizukuHint;
    private Button btnShizuku;
    private android.widget.CheckBox chkDanger;
    private TextView txtLog;

    private final rikka.shizuku.Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> updateShizukuUi();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);
            SharedPreferences prefs = getSharedPreferences("remote", MODE_PRIVATE);

            editServerUrl = (EditText) findViewById(R.id.editServerUrl);
            btnConnect = (Button) findViewById(R.id.btnConnect);
            btnDisconnect = (Button) findViewById(R.id.btnDisconnect);
            txtStatus = (TextView) findViewById(R.id.txtStatus);
            txtA11yHint = (TextView) findViewById(R.id.txtA11yHint);
            txtShizukuHint = (TextView) findViewById(R.id.txtShizukuHint);
            btnShizuku = (Button) findViewById(R.id.btnShizuku);
            chkDanger = (android.widget.CheckBox) findViewById(R.id.chkDanger);
            txtLog = (TextView) findViewById(R.id.txtLog);

            editServerUrl.setText(prefs.getString("server_url", "wss://100.68.64.94:8765/phone"));

            btnConnect.setOnClickListener(v -> connect());
            btnDisconnect.setOnClickListener(v -> disconnect());
            btnShizuku.setOnClickListener(v -> onShizukuButton());
            txtShizukuHint.setOnClickListener(v -> onShizukuButton());

            chkDanger.setChecked(WsRemoteService.isDangerUnlocked(this));
            chkDanger.setOnCheckedChangeListener((b, checked) -> {
                WsRemoteService.setDangerUnlocked(this, checked);
                showLog(checked ? "已开启危险命令（远程可执行 shell）" : "已恢复安全模式（shell 需再次解锁）");
            });

            updateA11yHint();
            updateShizukuUi();

            try {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
            } catch (Throwable ignored) {
            }

            WsRemoteService.setLogView(txtLog);
            showLog("APP 启动");

            if (Build.VERSION.SDK_INT >= 33) {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
                }
            }
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            new AlertDialog.Builder(this)
                .setTitle("启动错误")
                .setMessage(sw.toString())
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager c = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    c.setPrimaryClip(android.content.ClipData.newPlainText("err", sw.toString()));
                })
                .setNegativeButton("关闭", null)
                .show();
        }
    }

    private void connect() {
        try {
            String url = editServerUrl.getText().toString().trim();
            if (url.isEmpty()) return;
            getSharedPreferences("remote", MODE_PRIVATE).edit().putString("server_url", url).apply();
            showLog("发送连接请求: " + url);
            Intent i = new Intent(this, WsRemoteService.class);
            i.setAction("CONNECT");
            i.putExtra("url", url);
            startForegroundService(i);
            btnConnect.setEnabled(false);
            btnDisconnect.setEnabled(true);
            txtStatus.setText("连接中...");
        } catch (Exception e) {
            java.io.StringWriter sw = new java.io.StringWriter();
            e.printStackTrace(new java.io.PrintWriter(sw));
            showLog("连接异常: " + e.getMessage());
            new AlertDialog.Builder(this)
                .setTitle("连接错误")
                .setMessage(sw.toString())
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager c = (android.content.ClipboardManager)
                        getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    c.setPrimaryClip(android.content.ClipData.newPlainText("err", sw.toString()));
                })
                .setNegativeButton("关闭", null)
                .show();
        }
    }

    private void disconnect() {
        showLog("发送断开请求");
        Intent i = new Intent(this, WsRemoteService.class);
        i.setAction("DISCONNECT");
        startService(i);
        btnConnect.setEnabled(true);
        btnDisconnect.setEnabled(false);
        txtStatus.setText("未连接");
    }

    private void showLog(String msg) {
        if (txtLog != null) {
            String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
            txtLog.append("[" + time + "] " + msg + "\n");
        }
    }

    private final WsRemoteService.ConnListener connListener = (connected, detail) -> {
        btnConnect.setEnabled(!connected);
        btnDisconnect.setEnabled(connected);
        txtStatus.setText(detail);
    };

    @Override
    protected void onStart() {
        super.onStart();
        WsRemoteService.setConnListener(connListener);
    }

    @Override
    protected void onStop() {
        WsRemoteService.clearConnListener(connListener);
        super.onStop();
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        updateA11yHint();
        updateShizukuUi();
        boolean running = WsRemoteService.isRunning();
        btnConnect.setEnabled(!running);
        btnDisconnect.setEnabled(running);
        txtStatus.setText(running ? "已连接: " + getSharedPreferences("remote", MODE_PRIVATE).getString("server_url", "") : "未连接");
    }

    private void updateA11yHint() {
        boolean enabled = RemoteAccessibilityService.isRunning();
        if (enabled) {
            txtA11yHint.setText("已启用");
            txtA11yHint.setTextColor(0xFF3DC000);
        } else {
            txtA11yHint.setText("未启用");
            txtA11yHint.setTextColor(0xFFCC0000);
        }
    }

    private void updateShizukuUi() {
        if (txtShizukuHint == null || btnShizuku == null) return;
        boolean installed = ShizukuInstaller.isInstalled(this);
        if (!ShizukuHelper.isBinderAlive()) {
            txtShizukuHint.setText(installed
                    ? "Shizuku 未运行：shell 能力不可用（服务未启动）"
                    : "Shizuku 未安装：shell 能力不可用（可一键内置安装）");
            txtShizukuHint.setTextColor(0xFFCC0000);
            btnShizuku.setText(installed ? "启动 Shizuku" : "安装并启用 Shizuku");
            btnShizuku.setEnabled(true);
        } else if (!ShizukuHelper.hasPermission()) {
            txtShizukuHint.setText("Shizuku 已运行，但本应用未获得授权");
            txtShizukuHint.setTextColor(0xFFCC8800);
            btnShizuku.setText("授权 Shizuku");
            btnShizuku.setEnabled(true);
        } else {
            txtShizukuHint.setText("Shizuku 已授权：shell 能力可用");
            txtShizukuHint.setTextColor(0xFF3DC000);
            btnShizuku.setText("已授权");
            btnShizuku.setEnabled(false);
        }
    }

    private void onShizukuButton() {
        if (ShizukuHelper.isReady()) {
            showLog("Shizuku 已授权，无需重复启用");
            return;
        }
        if (ShizukuHelper.isBinderAlive()) {
            ShizukuHelper.requestPermission();
            showLog("已请求 Shizuku 授权");
            return;
        }
        startActivity(new Intent(this, ShizukuOnboardActivity.class));
    }

    @Override
    protected void onDestroy() {
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
