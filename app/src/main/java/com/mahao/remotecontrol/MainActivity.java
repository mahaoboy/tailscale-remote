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
    private TextView txtLog;

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
            txtLog = (TextView) findViewById(R.id.txtLog);

            editServerUrl.setText(prefs.getString("server_url", "wss://100.68.64.94:8765/phone"));

            btnConnect.setOnClickListener(v -> connect());
            btnDisconnect.setOnClickListener(v -> disconnect());
            updateA11yHint();

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

    @Override
    protected void onPostResume() {
        super.onPostResume();
        updateA11yHint();
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
}
