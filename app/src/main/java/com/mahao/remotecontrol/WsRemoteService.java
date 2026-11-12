package com.mahao.remotecontrol;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class WsRemoteService extends Service {

    private static final Gson gson = new Gson();
    private static final String PREFS = "remote";
    private static final String PREF_URL = "server_url";
    private static final String PREF_RECOVERED_BOOT = "auto_recover_boot";
    private static final long BOOT_RECOVER_WINDOW_MS = 15 * 60 * 1000L;
    private static final long RECOVER_DEADLINE_MS = 30 * 60 * 1000L;
    private static final int MAX_RECOVER_ATTEMPTS = 5;
    private static final int MAX_LOG_CHARS = 8000;
    private static final int MAX_LOG_MESSAGE = 500;

    private static volatile WebSocket ws;
    private static volatile boolean connected = false;
    private static volatile boolean manualDisconnect = false;
    private static volatile boolean reconnectScheduled = false;
    private static volatile String serverUrl;
    private OkHttpClient client;
    private PowerManager.WakeLock wakeLock;
    private static android.os.Handler mainHandler;
    private static android.widget.TextView txtLog;

    public static void setLogView(android.widget.TextView tv) {
        txtLog = tv;
    }

    private static void showLog(String msg) {
        if (msg != null && msg.length() > MAX_LOG_MESSAGE) {
            msg = msg.substring(0, MAX_LOG_MESSAGE) + "...(" + msg.length() + " chars)";
        }
        final String text = msg;
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mainHandler.post(() -> {
            if (txtLog != null) {
                String time = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date());
                txtLog.append("[" + time + "] " + text + "\n");
                CharSequence all = txtLog.getText();
                if (all.length() > MAX_LOG_CHARS) {
                    txtLog.setText(all.subSequence(all.length() - MAX_LOG_CHARS, all.length()));
                }
            }
        });
    }

    public static boolean isRunning() {
        return connected;
    }

    /** 无障碍服务连上后调用：若已保存地址且当前未连接，则恢复前台服务与连接。 */
    public static void ensureConnected() {
        android.content.Context ctx = AppContext.getAppContext();
        if (ctx == null) return;
        if (connected || manualDisconnect) return;
        String saved = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
                .getString(PREF_URL, null);
        if (saved == null || saved.isEmpty()) return;
        try {
            android.content.Intent i = new android.content.Intent(ctx, WsRemoteService.class)
                    .setAction("BOOT");
            ctx.startForegroundService(i);
        } catch (Throwable ignored) {
        }
    }

    public interface ConnListener {
        void onState(boolean connected, String detail);
    }

    private static ConnListener connListener;

    public static void setConnListener(ConnListener l) {
        connListener = l;
    }

    public static void clearConnListener(ConnListener l) {
        if (connListener == l) connListener = null;
    }

    private static void notifyState(boolean connected, String detail) {
        ConnListener l = connListener;
        if (l == null) return;
        if (mainHandler == null) {
            mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }
        mainHandler.post(() -> l.onState(connected, detail));
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
        showLog("shizuku: " + ShizukuHelper.status() + " backend: " + Shell.backend());
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
        if (intent == null || intent.getAction() == null) {
            // 系统重启服务后 intent 为 null：用上次保存的地址自动重连
            String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_URL, null);
            showLog("restart with null intent, savedUrl=" + saved);
            if (saved != null && !connected) {
                manualDisconnect = false;
                serverUrl = saved;
                startConnect(saved);
            }
            return START_STICKY;
        }
        showLog("onStartCommand: action=" + intent.getAction());
        try {
            switch (intent.getAction()) {
                case "CONNECT":
                    serverUrl = intent.getStringExtra("url");
                    manualDisconnect = false;
                    showLog("CONNECT: serverUrl=" + serverUrl);
                    if (serverUrl != null) {
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_URL, serverUrl).apply();
                        notifyState(false, "连接中...");
                        startConnect(serverUrl);
                    }
                    return START_STICKY;
                case "BOOT":
                    {
                        String saved = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_URL, null);
                        showLog("BOOT: savedUrl=" + saved);
                        if (saved != null && !saved.isEmpty()) {
                            serverUrl = saved;
                            manualDisconnect = false;
                            startForeground(1, buildNotification("正在运行"));
                            if (!connected) {
                                notifyState(false, "连接中...");
                                startConnect(saved);
                            }
                        }
                    }
                    return START_STICKY;
                case "DISCONNECT":
                    manualDisconnect = true;
                    connected = false;
                    notifyState(false, "未连接");
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_URL).apply();
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
                    manualDisconnect = false;
                    reconnectScheduled = false;
                    android.util.Log.d("WsRemote", "onOpen: " + response.protocol() + " " + response.message());
                    showLog("onOpen: " + response.protocol() + " " + response.message());
                    showLog("shizuku: " + ShizukuHelper.status() + " backend: " + Shell.backend());
                    updateNotification("已连接");
                    notifyState(true, "已连接: " + serverUrl);
                    maybeAutoRecover();
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
                    if (manualDisconnect) {
                        notifyState(false, "未连接");
                    } else {
                        notifyState(false, "连接已断开，5 秒后重连");
                        scheduleReconnect();
                    }
                }

                @Override
                public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                    connected = false;
                    String msg = t.getMessage() != null ? t.getMessage() : t.toString();
                    android.util.Log.e("WsRemote", "onFailure: " + msg + (response != null ? " httpCode=" + response.code() : ""));
                    showLog("onFailure: " + msg + (response != null ? " httpCode=" + response.code() : ""));
                    updateNotification("连接失败: " + msg);
                    if (manualDisconnect) {
                        notifyState(false, "未连接");
                    } else {
                        notifyState(false, "连接失败: " + msg);
                        scheduleReconnect();
                    }
                }
            });
            android.util.Log.d("WsRemote", "WebSocket created");
            showLog("WebSocket created");
        } catch (Throwable e) {
            android.util.Log.e("WsRemote", "startConnect failed: " + e.getMessage());
            showLog("startConnect failed: " + e.getMessage());
            e.printStackTrace();
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        final String url = serverUrl;
        if (manualDisconnect || url == null || reconnectScheduled) return;
        reconnectScheduled = true;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            reconnectScheduled = false;
            if (!connected && !manualDisconnect && url != null) {
                showLog("reconnecting...");
                notifyState(false, "正在重连...");
                startConnect(url);
            }
        }, 5000);
    }

    private void updateNotification(String text) {
        NotificationManager mgr = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (mgr != null) mgr.notify(1, buildNotification(text));
    }

    /** 重启后第一次连上服务端且 Shizuku 未运行时，自动跑一次自愈。 */
    private void maybeAutoRecover() {
        try {
            if (ShizukuHelper.isReady()) return;
            if (android.os.SystemClock.elapsedRealtime() > BOOT_RECOVER_WINDOW_MS) return;
            int bootCount = android.provider.Settings.Global.getInt(
                    getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
            if (bootCount < 0) return;
            android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
            if (prefs.getInt(PREF_RECOVERED_BOOT, -1) == bootCount) return;
            prefs.edit().putInt(PREF_RECOVERED_BOOT, bootCount).apply();
            showLog("重启后 Shizuku 未运行，开始自愈");
            scheduleRecoveryAttempt(1);
        } catch (Throwable t) {
            showLog("auto recover skipped: " + t.getMessage());
        }
    }

    private void scheduleRecoveryAttempt(int attempt) {
        long delay = attempt == 1 ? 8_000L : 60_000L;
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!connected || manualDisconnect) return;
            if (ShizukuHelper.isReady()) return;
            if (android.os.SystemClock.elapsedRealtime() > RECOVER_DEADLINE_MS) {
                showLog("自愈截止：超过开机 30 分钟仍未完成（锁屏 / 未连 Wi-Fi / 需首次配对）");
                return;
            }
            // 锁屏时静默等待解锁，不消耗尝试次数
            android.app.KeyguardManager km =
                    (android.app.KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) {
                scheduleRecoveryAttempt(attempt);
                return;
            }
            if (attempt > MAX_RECOVER_ATTEMPTS) {
                showLog("自愈放弃：多次重试后 Shizuku 仍未就绪");
                return;
            }
            showLog("自愈尝试 " + attempt + "/" + MAX_RECOVER_ATTEMPTS);
            boolean started = ShizukuRecovery.start(new ShizukuRecovery.Listener() {
                @Override
                public void onEvent(String msg) {
                    showLog("自愈: " + msg);
                }

                @Override
                public void onDone(boolean ok, String detail) {
                    showLog("自愈结束: " + (ok ? "ok" : detail));
                    if (!ok) scheduleRecoveryAttempt(attempt + 1);
                }
            });
            if (!started) showLog("自愈已在运行，跳过本轮");
        }, delay);
    }

    private void handleIncoming(String rawText) {
        try {
            JsonObject obj = gson.fromJson(rawText, JsonObject.class);
            if (obj == null || !obj.has("cmd")) return;
            String id = obj.has("id") ? obj.get("id").getAsString() : "";
            String cmd = obj.get("cmd").getAsString();

            String denied = SafetyPolicy.deny(cmd, obj, getPackageName(), isDangerUnlocked(this));
            if (denied != null) {
                showLog("安全策略拦截: " + cmd);
                sendResult(id, false, null, denied);
                return;
            }

            switch (cmd) {
                case "ping": {
                    String a11y = RemoteAccessibilityService.isReady() ? "a11y:on" : "a11y:off";
                    sendResult(id, true, "pong " + a11y + " shell:" + Shell.backend(), null);
                    break;
                }
                case "check": {
                    int bootCount = android.provider.Settings.Global.getInt(
                            getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1);
                    int recoveredBoot = getSharedPreferences(PREFS, MODE_PRIVATE)
                            .getInt(PREF_RECOVERED_BOOT, -1);
                    long uptimeMin = android.os.SystemClock.elapsedRealtime() / 60000L;
                    String autoState = (bootCount >= 0 && recoveredBoot == bootCount) ? "done"
                            : (uptimeMin > BOOT_RECOVER_WINDOW_MS / 60000L ? "skip" : "pending");
                    String status = "ws:on a11y:" + (RemoteAccessibilityService.isReady() ? "on" : "off")
                            + " " + ShizukuHelper.status() + " backend:" + Shell.backend()
                            + " ver:" + versionName()
                            + " safety:" + (isDangerUnlocked(this) ? "unlocked" : "safe")
                            + " boot:" + bootCount + " up:" + uptimeMin + "m"
                            + " autorecover:" + autoState
                            + " recstep:" + (ShizukuRecovery.isRunning() ? ShizukuRecovery.currentStep() : "-");
                    sendResult(id, true, status, null);
                    break;
                }
                case "screensize": {
                    android.view.Display d = ((android.hardware.display.DisplayManager) getSystemService(android.content.Context.DISPLAY_SERVICE)).getDisplay(android.view.Display.DEFAULT_DISPLAY);
                    if (d != null) {
                        android.graphics.Point p = new android.graphics.Point();
                        d.getRealSize(p);
                        sendResult(id, true, p.x + "x" + p.y, null);
                    } else {
                        sendResult(id, false, null, "no display");
                    }
                    break;
                }
                case "tap": {
                    int x = obj.get("x").getAsInt();
                    int y = obj.get("y").getAsInt();
                    RemoteAccessibilityService.tap(x, y, id);
                    break;
                }
                case "longtap": {
                    int lx = obj.get("x").getAsInt();
                    int ly = obj.get("y").getAsInt();
                    RemoteAccessibilityService.longTap(lx, ly, id);
                    break;
                }
                case "swipe": {
                    int x1 = obj.get("x1").getAsInt();
                    int y1 = obj.get("y1").getAsInt();
                    int x2 = obj.get("x2").getAsInt();
                    int y2 = obj.get("y2").getAsInt();
                    long dur = obj.has("duration") ? obj.get("duration").getAsLong() : 300;
                    RemoteAccessibilityService.swipe(x1, y1, x2, y2, dur, id);
                    break;
                }
                case "type": {
                    String text = obj.get("text").getAsString();
                    RemoteAccessibilityService.typeText(text, id);
                    break;
                }
                case "back":
                    RemoteAccessibilityService.backKey(id);
                    break;
                case "home":
                    RemoteAccessibilityService.homeKey(id);
                    break;
                case "findtext": {
                    String findText = obj.get("text").getAsString();
                    RemoteAccessibilityService.findText(findText, id);
                    break;
                }
                case "taptext": {
                    String tapText = obj.get("text").getAsString();
                    RemoteAccessibilityService.tapText(tapText, id);
                    break;
                }
                case "gettext":
                    RemoteAccessibilityService.getText(id);
                    break;
                case "dump":
                    RemoteAccessibilityService.dump(id);
                    break;
                case "screenshot":
                    if (Shell.hasShell()) {
                        captureScreen(id);
                    } else {
                        RemoteAccessibilityService.screenshot(id);
                    }
                    break;
                case "screencap":
                    captureScreen(id);
                    break;
                case "update": {
                    String apkUrl = obj.has("url") ? obj.get("url").getAsString() : "";
                    if (apkUrl.isEmpty()) apkUrl = "https://100.68.64.94:8080/build/app-debug.apk";
                    executeUpdate(apkUrl, id);
                    break;
                }
                case "shell": {
                    String command = obj.get("command").getAsString();
                    runShell("shell", command, id, 30000);
                    break;
                }
                case "shizuku": {
                    String action = obj.has("action") ? obj.get("action").getAsString() : "status";
                    if ("request".equals(action)) {
                        ShizukuHelper.requestPermission();
                        sendResult(id, true, "permission dialog requested", null);
                    } else {
                        sendResult(id, true, ShizukuHelper.status(), "backend=" + Shell.backend());
                    }
                    break;
                }
                case "input": {
                    String[] args = strArray(obj, "args");
                    if (args.length == 0) {
                        sendResult(id, false, null, "input: args is empty");
                        break;
                    }
                    runShell("input", "input " + Shell.quoteAll(args), id, 10000);
                    break;
                }
                case "keyevent": {
                    String code = obj.has("code") ? obj.get("code").getAsString() : null;
                    if (code == null || code.isEmpty()) {
                        sendResult(id, false, null, "keyevent: code required");
                        break;
                    }
                    runShell("keyevent", "input keyevent " + Shell.quote(code), id, 10000);
                    break;
                }
                case "grant":
                case "revoke": {
                    String pkg = obj.get("package").getAsString();
                    String perm = obj.get("permission").getAsString();
                    String verb = "grant".equals(cmd) ? "grant" : "revoke";
                    runShell(cmd, "pm " + verb + " " + Shell.quote(pkg) + " " + Shell.quote(perm) + " 2>&1", id, 15000);
                    break;
                }
                case "settings": {
                    String[] args = strArray(obj, "args");
                    if (args.length == 0) {
                        sendResult(id, false, null, "settings: args is empty");
                        break;
                    }
                    runShell("settings", "settings " + Shell.quoteAll(args) + " 2>&1", id, 15000);
                    break;
                }
                case "appops": {
                    String[] args = strArray(obj, "args");
                    if (args.length == 0) {
                        sendResult(id, false, null, "appops: args is empty");
                        break;
                    }
                    runShell("appops", "appops " + Shell.quoteAll(args) + " 2>&1", id, 15000);
                    break;
                }
                case "am": {
                    String[] args = strArray(obj, "args");
                    runShell("am", "am " + Shell.quoteAll(args) + " 2>&1", id, 30000);
                    break;
                }
                case "dumpsys": {
                    String[] args = strArray(obj, "args");
                    String command = args.length == 0 ? "dumpsys" : "dumpsys " + Shell.quoteAll(args);
                    runShell("dumpsys", command + " 2>&1", id, 30000);
                    break;
                }
                case "props": {
                    String key = obj.has("key") ? obj.get("key").getAsString() : "";
                    String command = key.isEmpty() ? "getprop" : "getprop " + Shell.quote(key);
                    runShell("props", command + " 2>&1", id, 10000);
                    break;
                }
                case "install": {
                    String path = obj.get("path").getAsString();
                    runShell("install", "pm install -r " + Shell.quote(path) + " 2>&1", id, 180000);
                    break;
                }
                case "uninstall": {
                    String pkg = obj.get("package").getAsString();
                    boolean keepData = obj.has("keepData") && obj.get("keepData").getAsBoolean();
                    String command = "pm uninstall " + (keepData ? "-k " : "") + Shell.quote(pkg) + " 2>&1";
                    runShell("uninstall", command, id, 60000);
                    break;
                }
                case "open": {
                    String pkg = obj.get("package").getAsString();
                    launchApp(pkg, id);
                    break;
                }
                case "recover": {
                    if (ShizukuHelper.isReady()) {
                        sendResult(id, true, "shizuku ready", "no recovery needed");
                        break;
                    }
                    boolean started = ShizukuRecovery.start(new ShizukuRecovery.Listener() {
                        @Override
                        public void onEvent(String msg) {
                            showLog("自愈: " + msg);
                        }

                        @Override
                        public void onDone(boolean ok, String detail) {
                            showLog("自愈结束: " + (ok ? "ok" : detail));
                            sendResult(id, ok, detail, "recovery " + (ok ? "succeeded" : "failed"));
                        }
                    });
                    if (!started) {
                        sendResult(id, false, null, "recovery already running: " + ShizukuRecovery.currentStep());
                    }
                    break;
                }
                default:
                    sendResult(id, false, null, "unknown command: " + cmd);
            }
        } catch (Exception e) {
            sendResult("", false, null, e.getMessage());
        }
    }

    /** 接受 JSON 数组或单个字符串。 */
    private static String[] strArray(JsonObject obj, String name) {
        if (!obj.has(name)) return new String[0];
        JsonElement el = obj.get(name);
        List<String> list = new ArrayList<>();
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement e : arr) list.add(e.getAsString());
        } else if (!el.isJsonNull()) {
            list.add(el.getAsString());
        }
        return list.toArray(new String[0]);
    }

    /** 危险命令开关只存在手机本地，远程命令无法修改。 */
    static boolean isDangerUnlocked(android.content.Context ctx) {
        return ctx.getSharedPreferences("remote", android.content.Context.MODE_PRIVATE)
                .getBoolean("danger_unlocked", false);
    }

    static void setDangerUnlocked(android.content.Context ctx, boolean value) {
        ctx.getSharedPreferences("remote", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("danger_unlocked", value).apply();
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "?";
        }
    }

    private void runShell(String label, String command, String id, long timeoutMs) {
        new Thread(() -> {
            if (!Shell.hasShell()) {
                showLog(label + ": shell unavailable (app uid only)");
            }
            Shell.Result r = Shell.exec(command, timeoutMs);
            String out = r.stdout + (r.stderr.isEmpty() ? "" : "\n" + r.stderr);
            if (r.timedOut) {
                sendResult(id, false, out, label + ": timeout after " + timeoutMs + "ms");
            } else {
                sendResult(id, r.exitCode == 0, out,
                        label + ": exit=" + r.exitCode + " backend=" + Shell.backend());
            }
        }, "cmd-" + label).start();
    }

    private void captureScreen(String id) {
        new Thread(() -> {
            try {
                byte[] png = Shell.execBinary("screencap -p");
                if (png == null || png.length == 0) {
                    sendResult(id, false, null, "screencap: empty output (backend=" + Shell.backend() + ")");
                    return;
                }
                String b64 = android.util.Base64.encodeToString(png, android.util.Base64.NO_WRAP);
                showLog("screencap: " + png.length + " bytes");
                sendResult(id, true, b64, "png base64, " + png.length + " bytes, backend=" + Shell.backend());
            } catch (Exception e) {
                sendResult(id, false, null, "screencap failed: " + e.getMessage());
            }
        }, "cmd-screencap").start();
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

                if (Shell.hasShell()) {
                    Shell.Result r = Shell.exec(
                        "pm install -r '/storage/emulated/0/Download/app-update.apk' 2>&1", 180000);
                    String out = r.stdout + (r.stderr.isEmpty() ? "" : "\n" + r.stderr);
                    showLog("update: pm install exit=" + r.exitCode);
                    sendResult(id, r.ok(), out, "install via shizuku, exit=" + r.exitCode);
                    return;
                }

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
        new Thread(() -> {
            try {
                android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(pkg);
                if (intent != null) {
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    sendResult(id, true, "launched: " + pkg, null);
                    return;
                }
                if (Shell.hasShell()) {
                    Shell.Result r = Shell.exec(
                        "monkey -p " + Shell.quote(pkg) + " -c android.intent.category.LAUNCHER 1 2>&1", 20000);
                    String out = r.stdout + (r.stderr.isEmpty() ? "" : "\n" + r.stderr);
                    sendResult(id, r.ok(), out, "launch via monkey, exit=" + r.exitCode);
                    return;
                }
                sendResult(id, false, null, "launch failed: no launcher intent for " + pkg);
            } catch (Exception e) {
                sendResult(id, false, null, "launch failed: " + e.getMessage());
            }
        }, "cmd-open").start();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        manualDisconnect = true;
        try {
            if (ws != null) ws.close(1000, "service destroyed");
        } catch (Throwable ignored) {
        }
        connected = false;
        ws = null;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            showLog("WakeLock released");
        }
        showLog("Service onDestroy");
    }
}
