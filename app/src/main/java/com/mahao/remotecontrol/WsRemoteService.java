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
    private OkHttpClient downClient;
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

    /** 长任务的中间进度：同一 id 的多条消息，CLI 只把最后一条当作结果。 */
    public static void sendProgress(String id, String msg) {
        if (id == null || id.isEmpty()) return;
        JsonObject r = new JsonObject();
        r.addProperty("id", id);
        r.addProperty("progress", msg == null ? "" : msg);
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
            // 下载链路单独一套超时：默认 10s 读超时会让大 APK 直接 "update failed: timeout"
            downClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(60, TimeUnit.SECONDS)
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
                            + " recstep:" + (ShizukuRecovery.isRunning() ? ShizukuRecovery.currentStep() : "-")
                            + " fg:" + orDash(RemoteAccessibilityService.foregroundPackage())
                            + " built:" + buildTime();
                    StringBuilder hints = new StringBuilder();
                    if (!RemoteAccessibilityService.isReady()) {
                        hints.append("a11y off -> 点击/滑动/读屏/输入不可用，需在手机 设置-无障碍 启用「远程控制」");
                    }
                    if (!ShizukuHelper.isReady()) {
                        if (hints.length() > 0) hints.append("; ");
                        hints.append("shizuku off -> shell 能力受限，可试 recover");
                    }
                    sendResult(id, true, status, hints.length() == 0 ? null : hints.toString());
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
                    RemoteAccessibilityService.findText(findText,
                            bool(obj, "exact", false), intOf(obj, "index", 0), id);
                    break;
                }
                case "taptext": {
                    String tapText = obj.get("text").getAsString();
                    RemoteAccessibilityService.tapText(tapText,
                            bool(obj, "exact", false), intOf(obj, "index", 0), id);
                    break;
                }
                case "uis": {
                    String filter = obj.has("filter") ? obj.get("filter").getAsString() : "";
                    RemoteAccessibilityService.listUi(filter, id);
                    break;
                }
                case "uitap": {
                    int uiIndex = intOf(obj, "index", -1);
                    if (uiIndex < 0) {
                        sendResult(id, false, null, "uitap: index required (先跑 uis)");
                        break;
                    }
                    String uiFilter = obj.has("filter") ? obj.get("filter").getAsString() : "";
                    RemoteAccessibilityService.tapUiIndex(uiIndex, uiFilter, id);
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
                    runShell("shell", command, id, timeoutOf(obj, 60000));
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
                    // input text 走 InputShellCommand，中文会 NPE；有 a11y 时透明改走 ACTION_SET_TEXT
                    if ("text".equals(args[0]) && args.length > 1 && !isAscii(joinTail(args, 1))) {
                        if (RemoteAccessibilityService.isReady()) {
                            RemoteAccessibilityService.typeText(joinTail(args, 1), id);
                        } else {
                            sendResult(id, false, null,
                                    "input text: 中文需要无障碍开启，或改用 type 命令");
                        }
                        break;
                    }
                    runShell("input", "input " + Shell.quoteAll(args), id, timeoutOf(obj, 15000));
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
                    String staged = stageForInstall(path);
                    if (staged == null) {
                        sendResult(id, false, path,
                                "install: 复制到 /data/local/tmp 失败（系统 installer 读不到 /sdcard）");
                        break;
                    }
                    if (!staged.equals(path)) {
                        sendProgress(id, "已复制到 " + staged + "（系统 installer 读不到 /sdcard）");
                    }
                    runShell("install", "pm install -r " + Shell.quote(staged)
                            + " 2>&1", id, timeoutOf(obj, 180000));
                    break;
                }
                case "put":
                case "push": {
                    if (!obj.has("data") || obj.get("data").isJsonNull()) {
                        sendResult(id, false, null, "push: data(base64) required");
                        break;
                    }
                    String name = obj.has("name") ? obj.get("name").getAsString() : "upload.bin";
                    String dest = obj.has("path") ? obj.get("path").getAsString() : "";
                    pushFile(name, dest, obj.get("data").getAsString(), id);
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
                showLog("update: downloading " + apkUrl);
                sendProgress(id, "开始下载更新包");
                okhttp3.Response resp = downClient.newCall(
                    new okhttp3.Request.Builder().url(apkUrl).build()
                ).execute();
                if (!resp.isSuccessful()) {
                    sendResult(id, false, null, "download failed: HTTP " + resp.code());
                    return;
                }
                long total = resp.body().contentLength();
                Saved apk = saveToDownloads("app-update.apk", resp.body().byteStream());
                resp.close();
                if (apk == null) {
                    sendResult(id, false, null, "save apk to Downloads failed");
                    return;
                }
                String saved = apk.path;
                showLog("update: saved " + saved + " (" + total + " bytes)");

                if (Shell.hasShell()) {
                    sendProgress(id, "下载完成，准备安装（应用会短暂离线后自动恢复）");
                    String staged = stageToTmp(saved, "/data/local/tmp/app-update.apk");
                    if (staged == null) {
                        sendResult(id, false, saved, "install: 复制到 /data/local/tmp 失败");
                        return;
                    }
                    Shell.Result r = Shell.exec(
                        "pm install -r " + Shell.quote(staged) + " 2>&1", 300000);
                    String out = r.stdout + (r.stderr.isEmpty() ? "" : "\n" + r.stderr);
                    showLog("update: pm install exit=" + r.exitCode);
                    sendResult(id, r.ok() || out.contains("Success"), out,
                            "install via shizuku, exit=" + r.exitCode + ", apk=" + saved);
                    return;
                }

                // 无 Shizuku：走系统安装器
                android.content.pm.PackageManager pm = getPackageManager();
                android.net.Uri apkUri = apk.uri;
                if (pm.canRequestPackageInstalls()) {
                    android.content.Intent installIntent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    installIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(installIntent);
                    sendResult(id, true, "install triggered", "apk=" + saved);
                } else {
                    android.content.Intent settingsIntent = new android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                    settingsIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
                    settingsIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(settingsIntent);
                    sendResult(id, false, null, "需要允许安装未知来源应用");
                }
            } catch (Exception e) {
                sendResult(id, false, null, "update failed: " + e.getMessage());
                showLog("update failed: " + e.getMessage());
            }
        }).start();
    }

    private static final class Saved {
        final String path;
        final android.net.Uri uri;

        Saved(String path, android.net.Uri uri) {
            this.path = path;
            this.uri = uri;
        }
    }

    /** 写入系统下载目录（应用可写、shell 可读）；同名文件由系统加序号，不覆盖用户文件。 */
    private Saved saveToDownloads(String name, java.io.InputStream is) throws Exception {
        android.content.ContentResolver resolver = getContentResolver();
        android.net.Uri collection = android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String rel = android.os.Environment.DIRECTORY_DOWNLOADS;
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mimeFor(name));
        values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH, rel);
        android.net.Uri uri = resolver.insert(collection, values);
        if (uri == null) return null;
        String actual = name;
        try (android.database.Cursor c = resolver.query(uri,
                new String[]{android.provider.MediaStore.Downloads.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) actual = c.getString(0);
        }
        try (java.io.OutputStream os = resolver.openOutputStream(uri)) {
            if (os == null) return null;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
        return new Saved("/storage/emulated/0/" + rel + "/" + actual, uri);
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase(java.util.Locale.US);
        if (n.endsWith(".apk")) return "application/vnd.android.package-archive";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".txt") || n.endsWith(".log")) return "text/plain";
        return "application/octet-stream";
    }

    /** /sdcard 对系统 installer 不可读，先落到 shell 可读的 /data/local/tmp。 */
    private static String stageForInstall(String path) {
        if (!Shell.hasShell()) return path;
        if (!(path.startsWith("/sdcard") || path.startsWith("/storage/"))) return path;
        return stageToTmp(path, "/data/local/tmp/rc-install.apk");
    }

    private static String stageToTmp(String src, String dest) {
        if (!Shell.hasShell()) return null;
        Shell.Result r = Shell.exec("cp " + Shell.quote(src) + " " + Shell.quote(dest)
                + " && chmod 644 " + Shell.quote(dest) + " && echo RC_OK", 120000);
        if (r.stdout.contains("RC_OK")) return dest;
        int slash = dest.lastIndexOf('/');
        if (slash <= 0) return null;
        String dir = dest.substring(0, slash);
        Shell.Result r2 = Shell.exec("mkdir -p " + Shell.quote(dir) + " && cp " + Shell.quote(src)
                + " " + Shell.quote(dest) + " && chmod 644 " + Shell.quote(dest) + " && echo RC_OK", 120000);
        return r2.stdout.contains("RC_OK") ? dest : null;
    }

    private void pushFile(String name, String dest, String b64, String id) {
        new Thread(() -> {
            try {
                String safe = name.replaceAll("[^A-Za-z0-9._-]", "_");
                if (safe.isEmpty()) safe = "upload.bin";
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                sendProgress(id, "收到 " + bytes.length + " 字节，写入下载目录");
                Saved savedFile = saveToDownloads(safe, new java.io.ByteArrayInputStream(bytes));
                if (savedFile == null) {
                    sendResult(id, false, null, "push: 写下载目录失败");
                    return;
                }
                String saved = savedFile.path;
                String target = dest == null || dest.isEmpty() ? "/data/local/tmp/" + safe : dest;
                boolean allowed = target.startsWith("/data/local/tmp/")
                        || target.startsWith("/sdcard/") || target.startsWith("/storage/");
                if (!allowed) {
                    sendResult(id, false, saved, "push: 只允许写 /data/local/tmp 或 /sdcard");
                    return;
                }
                if (!Shell.hasShell()) {
                    sendResult(id, true, saved, "无 Shizuku，仅写入下载目录；目标=" + target);
                    return;
                }
                sendProgress(id, "复制到 " + target);
                String staged = stageToTmp(saved, target);
                if (staged == null) {
                    sendResult(id, false, saved, "push: 复制到 " + target + " 失败");
                } else {
                    sendResult(id, true, staged + " (" + bytes.length + " bytes)", "下载副本=" + saved);
                }
            } catch (Exception e) {
                sendResult(id, false, null, "push failed: " + e.getMessage());
            }
        }, "cmd-push").start();
    }

    private String buildTime() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.US)
                    .format(new java.util.Date(pi.lastUpdateTime));
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String orDash(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return false;
        }
        return true;
    }

    private static String joinTail(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static boolean bool(JsonObject obj, String name, boolean def) {
        return obj.has(name) && !obj.get(name).isJsonNull() ? obj.get(name).getAsBoolean() : def;
    }

    private static int intOf(JsonObject obj, String name, int def) {
        return obj.has(name) && !obj.get(name).isJsonNull() ? obj.get(name).getAsInt() : def;
    }

    private static long timeoutOf(JsonObject obj, long def) {
        return obj.has("timeoutMs") && !obj.get("timeoutMs").isJsonNull()
                ? obj.get("timeoutMs").getAsLong() : def;
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
