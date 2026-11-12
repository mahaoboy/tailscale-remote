package com.mahao.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WsRemoteService extends Service {

    private static final Gson gson = new Gson();
    private static volatile WebSocket ws;
    private static volatile boolean connected = false;
    private String serverUrl;
    private OkHttpClient client;
    private PowerManager.WakeLock wakeLock;
    private static android.os.Handler mainHandler;
    private static android.widget.TextView txtLog;

    public static void setLogView(android.widget.TextView tv) {
        txtLog = tv;
    }

    private static void showLog(String msg) {
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mainHandler.post(() -> {
            if (txtLog != null) {
                String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                String line = "[" + time + "] " + msg + "\n";
                txtLog.append(line);
            }
        });
    }

    public static boolean isRunning() {
        return connected;
    }

    public static void sendRaw(String text) {
        WebSocket w = ws;
        if (w != null && connected) {
            w.send(text);
        }
    }

    public static void sendJson(String json) {
        sendRaw(json);
    }

    public static void sendResult(String id, boolean ok, String output, String extra) {
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("ok", ok);
        if (output != null) r.addProperty("output", output);
        if (extra != null) r.addProperty("extra", extra);
        sendRaw(gson.toJson(r));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        showLog("Service onCreate");
        try {
            // 信任所有证书（debug build）
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                    public void checkClientTrusted(java.security.cert.X509Certificate[] c, String s) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] c, String s) {}
                }
            };
            javax.net.ssl.SSLContext sslCtx = javax.net.ssl.SSLContext.getInstance("TLS");
            sslCtx.init(null, trustAll, new java.security.SecureRandom());
            javax.net.ssl.SSLSocketFactory sslFactory = sslCtx.getSocketFactory();

            client = new OkHttpClient.Builder()
                .pingInterval(60, TimeUnit.SECONDS)
                .sslSocketFactory(sslFactory, (javax.net.ssl.X509TrustManager) trustAll[0])
                .hostnameVerifier((hostname, session) -> true)
                .build();
            showLog("OkHttpClient initialized (SSL trust-all, ping 60s)");
        } catch (Throwable e) {
            android.util.Log.e("WsRemote", "OkHttpClient init failed: " + e.getMessage());
            showLog("ERROR: OkHttpClient init failed: " + e.getMessage());
            e.printStackTrace();
        }
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WsRemote:Network");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire(8 * 60 * 60 * 1000L); // 8 hours
            showLog("WakeLock acquired (8h)");
        } catch (Throwable e) {
            showLog("WakeLock failed: " + e.getMessage());
        }
        try {
            startForeground(1, buildNotification("正在运行"));
            showLog("startForeground OK");
        } catch (Throwable e) {
            showLog("startForeground failed: " + e.getMessage());
            try {
                Notification fallback = new Notification.Builder(this)
                    .setContentTitle("远程控制")
                    .setContentText("正在运行")
                    .setSmallIcon(android.R.drawable.ic_menu_share)
                    .setOngoing(true)
                    .build();
                startForeground(1, fallback);
                showLog("startForeground fallback OK");
            } catch (Throwable e2) {
                android.util.Log.e("WsRemote", "startForeground fallback failed: " + e2.getMessage());
                showLog("ERROR: startForeground fallback failed: " + e2.getMessage());
            }
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        String channelId = "remote_channel";
        if (mgr.getNotificationChannel(channelId) == null) {
            mgr.createNotificationChannel(new NotificationChannel(channelId, "远程控制", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, channelId)
                .setContentTitle("远程控制已启动")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        showLog("onStartCommand: action=" + intent.getAction());
        try {
            switch (intent.getAction()) {
                case "CONNECT":
                    serverUrl = intent.getStringExtra("url");
                    showLog("CONNECT: serverUrl=" + serverUrl);
                    if (serverUrl != null) startConnect(serverUrl);
                    return START_STICKY;
                case "DISCONNECT":
                    connected = false;
                    if (ws != null) ws.close(1000, "user");
                    NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                    if (mgr != null) mgr.cancel(1);
                    stopSelf();
                    return START_NOT_STICKY;
            }
        } catch (Throwable e) {
            android.util.Log.e("WsRemote", "onStartCommand error: " + e.getMessage());
            showLog("ERROR: onStartCommand: " + e.getMessage());
            sendResult("", false, null, e.getMessage());
        }
        return START_STICKY;
    }

    private void startConnect(String url) {
        android.util.Log.d("WsRemote", "startConnect: url=" + url);
        showLog("startConnect: url=" + url);
        if (client == null) {
            android.util.Log.e("WsRemote", "client is null, cannot connect");
            showLog("ERROR: client is null, cannot connect");
            return;
        }
        try {
            Request request = new Request.Builder().url(url).build();
            android.util.Log.d("WsRemote", "Request built: " + request.url());
            showLog("Request built: " + request.url());
            ws = client.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket webSocket, Response response) {
                    connected = true;
                    android.util.Log.d("WsRemote", "onOpen: " + response.protocol() + " " + response.message());
                    showLog("onOpen: " + response.protocol() + " " + response.message());
                    updateNotification("已连接");
                }

                @Override
                public void onMessage(WebSocket webSocket, String text) {
                    android.util.Log.d("WsRemote", "onMessage(text): " + text);
                    showLog("onMessage(text): " + text);
                    handleIncoming(text);
                }

                @Override
                public void onMessage(WebSocket webSocket, ByteString bytes) {
                    android.util.Log.d("WsRemote", "onMessage(bytes): " + bytes.size() + " bytes");
                    showLog("onMessage(bytes): " + bytes.size() + " bytes");
                    handleIncoming(bytes.utf8());
                }

                @Override
                public void onClosing(WebSocket webSocket, int code, String reason) {
                    android.util.Log.d("WsRemote", "onClosing: code=" + code + " reason=" + reason);
                    showLog("onClosing: code=" + code + " reason=" + reason);
                    webSocket.close(1000, null);
                }

                @Override
                public void onClosed(WebSocket webSocket, int code, String reason) {
                    connected = false;
                    android.util.Log.d("WsRemote", "onClosed: code=" + code + " reason=" + reason);
                    showLog("onClosed: code=" + code + " reason=" + reason);
                    updateNotification("连接断开");
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    connected = false;
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    android.util.Log.e("WsRemote", "onFailure: " + msg + (response != null ? " httpCode=" + response.code() : ""));
                    showLog("onFailure: " + msg + (response != null ? " httpCode=" + response.code() : ""));
                    updateNotification("连接失败: " + msg);
                    // Auto reconnect after 5s
                    if (serverUrl != null) {
                        final String url2 = serverUrl;
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            if (!connected && url2 != null) startConnect(url2);
                        }, 5000);
                    }
                }
            });
            android.util.Log.d("WsRemote", "WebSocket created");
            showLog("WebSocket created");
        } catch (Throwable e) {
            android.util.Log.e("WsRemote", "startConnect failed: " + e.getMessage());
            showLog("startConnect failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateNotification(String text) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr != null) mgr.notify(1, buildNotification(text));
    }

    private void handleIncoming(String rawText) {
        try {
            JsonObject obj = gson.fromJson(rawText, JsonObject.class);
            if (obj == null || !obj.has("cmd")) return;
            String id = obj.has("id") ? obj.get("id").getAsString() : "";
            String cmd = obj.get("cmd").getAsString();
            String data = obj.toString();

            switch (cmd) {
                case "ping":
                    String a11y = RemoteAccessibilityService.isReady() ? "a11y:on" : "a11y:off";
                    sendResult(id, true, "pong " + a11y, null);
                    break;
                case "check":
                    String status = "ws:on a11y:" + (RemoteAccessibilityService.isReady() ? "on" : "off");
                    sendResult(id, true, status, null);
                    break;
                case "screensize":
                    android.view.Display d = ((android.hardware.display.DisplayManager) getSystemService(android.content.Context.DISPLAY_SERVICE)).getDisplay(android.view.Display.DEFAULT_DISPLAY);
                    if (d != null) {
                        android.graphics.Point p = new android.graphics.Point();
                        d.getRealSize(p);
                        sendResult(id, true, p.x + "x" + p.y, null);
                    } else {
                        sendResult(id, false, null, "no display");
                    }
                    break;
                case "tap":
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    RemoteAccessibilityService.tap(x, y, id);
                    break;
                case "longtap":
                    int lx = obj.get("x").getAsInt();
                    int ly = obj.get("y").getAsInt();
                    RemoteAccessibilityService.longTap(lx, ly, id);
                    break;
                case "swipe":
                    int x1 = obj.get("x1").getAsInt();
                    int y1 = obj.get("y1").getAsInt();
                    int x2 = obj.get("x2").getAsInt();
                    int y2 = obj.get("y2").getAsInt();
                    long dur = obj.has("duration") ? obj.get("duration").getAsLong() : 300;
                    RemoteAccessibilityService.swipe(x1, y1, x2, y2, dur, id);
                    break;
                case "type":
                    String text2 = obj.get("text").getAsString();
                    RemoteAccessibilityService.typeText(text2, id);
                    break;
                case "back":
                    RemoteAccessibilityService.backKey(id);
                    break;
                case "home":
                    RemoteAccessibilityService.homeKey(id);
                    break;
                case "findtext":
                    String findText = obj.get("text").getAsString();
                    RemoteAccessibilityService.findText(findText, id);
                    break;
                case "taptext":
                    String tapText = obj.get("text").getAsString();
                    RemoteAccessibilityService.tapText(tapText, id);
                    break;
                case "gettext":
                    RemoteAccessibilityService.getText(id);
                    break;
                case "dump":
                    RemoteAccessibilityService.dump(id);
                    break;
                case "screenshot":
                    RemoteAccessibilityService.screenshot(id);
                    break;
                case "update":
                    String apkUrl = obj.has("url") ? obj.get("url").getAsString() : "";
                    if (apkUrl.isEmpty()) apkUrl = "http://100.68.64.94:8080/app-debug.apk";
                    executeUpdate(apkUrl, id);
                    break;
                case "shell":
                    String command = obj.get("command").getAsString();
                    executeShell(command, id);
                    break;
                case "open":
                    String pkg = obj.get("package").getAsString();
                    launchApp(pkg, id);
                    break;
                default:
                    sendResult(id, false, null, "unknown command: " + cmd);
            }
        } catch (Exception e) {
            sendResult("", false, null, e.getMessage());
        }
    }

    private void executeShell(String command, String id) {
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = new ProcessBuilder("/system/bin/sh", "-c", command)
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while ((line = r.readLine()) != null) out.append(line).append("\n");
                p.waitFor();
                sendResult(id, true, out.toString(), null);
            } catch (Exception e) {
                sendResult(id, false, null, e.getMessage());
            }
        }).start();
    }

    private void executeUpdate(String apkUrl, String id) {
        new Thread(() -> {
            try {
                // Download APK using OkHttp
                showLog("update: downloading " + apkUrl);
                okhttp3.Response resp = client.newCall(
                    new okhttp3.Request.Builder().url(apkUrl).build()
                ).execute();
                if (!resp.isSuccessful()) {
                    sendResult(id, false, null, "download failed: HTTP " + resp.code());
                    return;
                }
                java.io.InputStream is = resp.body().byteStream();

                // Save to MediaStore Downloads (API 29+)
                android.content.ContentResolver resolver = getContentResolver();
                android.content.ContentValues values = new android.content.ContentValues();
                values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, "app-update.apk");
                values.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
                values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS);
                android.net.Uri uri = resolver.insert(
                    android.provider.MediaStore.Downloads.getContentUri(
                        android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values);
                if (uri == null) {
                    sendResult(id, false, null, "MediaStore insert failed");
                    return;
                }
                java.io.OutputStream os = resolver.openOutputStream(uri);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                os.close();
                is.close();
                showLog("update: APK saved, triggering install");

                // Check install permission
                android.content.pm.PackageManager pm = getPackageManager();
                if (pm.canRequestPackageInstalls()) {
                    // Trigger install
                    android.content.Intent installIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    installIntent.setDataAndType(uri, "application/vnd.android.package-archive");
                    installIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    installIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(installIntent);
                    sendResult(id, true, "install triggered", null);
                } else {
                    // Request permission
                    android.content.Intent settingsIntent = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    settingsIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settingsIntent);
                    sendResult(id, false, null, "need to grant install permission in Settings");
                }
            } catch (Exception e) {
                sendResult(id, false, null, "update failed: " + e.getMessage());
                showLog("update failed: " + e.getMessage());
            }
        }).start();
    }

    private void launchApp(String pkg, String id) {
        try {
            // Try launcher intent
            android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                // Try settings app
                intent = new android.content.Intent(android.provider.Settings.ACTION_SETTINGS);
                pkg = "settings";
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            sendResult(id, true, "launched: " + pkg, null);
        } catch (Exception e) {
            sendResult(id, false, null, "launch failed: " + e.getMessage());
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            showLog("WakeLock released");
        }
        showLog("Service onDestroy");
    }
}
