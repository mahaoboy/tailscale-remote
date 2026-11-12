package com.mahao.remotecontrol;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 非 root 下重启手机后的 Shizuku 自愈：开 VWi-Fi -> 开无线调试 -> 打开 Shizuku 点「启动」。
 *
 * 前提是设备已解锁且能连上 Wi-Fi；首次配对仍需手动完成（Shizuku 的配对依赖通知交互）。
 * 整个流程只做开关与点击，不修改任何系统设置值。
 */
public final class ShizukuRecovery {

    public interface Listener {
        void onEvent(String msg);
        void onDone(boolean ok, String detail);
    }

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final long TOTAL_BUDGET_MS = 150_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile String currentStep = "";

    private ShizukuRecovery() { }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    public static String currentStep() {
        return currentStep;
    }

    /** 返回 false 表示已有一轮自愈在进行。 */
    public static boolean start(Listener listener) {
        if (!RUNNING.compareAndSet(false, true)) return false;
        new Thread(() -> {
            boolean ok;
            String detail;
            try {
                detail = run(listener, SystemClock.elapsedRealtime() + TOTAL_BUDGET_MS);
            } catch (Throwable t) {
                detail = "fail: 自愈异常 " + t;
            }
            ok = ShizukuHelper.isReady();
            if (ok && (detail == null || !detail.startsWith("fail"))) {
                detail = "shizuku ready";
            }
            RUNNING.set(false);
            currentStep = "";
            if (listener != null) listener.onDone(ok, detail);
        }, "shizuku-recovery").start();
        return true;
    }

    private static String run(Listener l, long deadline) throws InterruptedException {
        Context ctx = AppContext.getAppContext();
        if (ctx == null) return "fail: 无应用上下文";
        if (ShizukuHelper.isReady()) return "already running";
        if (!RemoteAccessibilityService.isReady()) return "fail: 无障碍服务未连接";

        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        if (km != null && km.isKeyguardLocked()) {
            return "fail: 设备处于锁屏状态，解锁后重试";
        }

        if (wifiConnected(ctx)) {
            step(l, "Wi-Fi 已连接");
        } else {
            step(l, "启用 Wi-Fi");
            if (!enableWifi(ctx, deadline)) {
                return "fail: Wi-Fi 未连接，无线调试无法开启（需要手机连上可用 Wi-Fi）";
            }
            step(l, "Wi-Fi 已连接");
        }

        if (wirelessDebuggingOn(ctx)) {
            step(l, "无线调试已开启");
        } else {
            step(l, "打开「无线调试」");
            if (!enableWirelessDebugging(ctx, deadline)) {
                return "fail: 未能打开「无线调试」开关";
            }
            step(l, "无线调试已开启");
        }

        step(l, "打开 Shizuku 并点击「启动」");
        String started = startShizuku(ctx, deadline);
        if (started != null && ShizukuHelper.isReady()) started = null;
        if (started != null) return started;

        step(l, "等待 Shizuku 服务就绪");
        if (!waitReady(deadline)) return "fail: Shizuku 在超时时间内未就绪";

        sleep(500);
        RemoteAccessibilityService.pressHome();
        return "shizuku ready";
    }

    // ===== Wi-Fi =====

    private static boolean wifiConnected(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network network = cm.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean enableWifi(Context ctx, long deadline) throws InterruptedException {
        launch(ctx, new Intent(Settings.ACTION_WIFI_SETTINGS));
        sleep(1500);
        RemoteAccessibilityService.clickFirstSwitch();
        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 25_000L);
        while (SystemClock.elapsedRealtime() < until) {
            if (wifiConnected(ctx)) {
                RemoteAccessibilityService.pressBack();
                sleep(600);
                return true;
            }
            sleep(1000);
        }
        RemoteAccessibilityService.pressBack();
        sleep(400);
        return false;
    }

    // ===== 无线调试 =====

    private static boolean wirelessDebuggingOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), "adb_wifi_enabled", 0) == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean enableWirelessDebugging(Context ctx, long deadline) throws InterruptedException {
        launch(ctx, new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        sleep(1500);
        dismissConfirmDialogs();

        boolean found = scrollUntilVisible("无线调试", 8);
        if (!found) {
            // 有些 ROM 开发者选项会记住上次滚动位置，先回到顶部再找一遍
            for (int i = 0; i < 10 && !found; i++) {
                RemoteAccessibilityService.scrollPage(false);
                sleep(400);
                found = RemoteAccessibilityService.readText().contains("无线调试");
            }
        }
        if (!found) {
            RemoteAccessibilityService.pressBack();
            return false;
        }

        boolean clicked = RemoteAccessibilityService.clickSwitchNearLabel("无线调试");
        if (!clicked) {
            // 布局不同：点这一行进入子页面，再点子页面里的开关
            RemoteAccessibilityService.clickTextContains("无线调试");
            sleep(1200);
            dismissConfirmDialogs();
            clicked = RemoteAccessibilityService.clickFirstSwitch();
        }
        if (!clicked) {
            RemoteAccessibilityService.pressBack();
            return false;
        }

        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 20_000L);
        while (SystemClock.elapsedRealtime() < until) {
            if (wirelessDebuggingOn(ctx)) {
                RemoteAccessibilityService.pressBack();
                sleep(600);
                return true;
            }
            dismissConfirmDialogs();
            sleep(800);
        }
        RemoteAccessibilityService.pressBack();
        sleep(400);
        return false;
    }

    /** 部分 ROM 打开开关时会弹「确定 / 允许」确认框。 */
    private static void dismissConfirmDialogs() throws InterruptedException {
        String text = RemoteAccessibilityService.readText();
        if (text.contains("想要打开") || text.contains("想要启动")) {
            if (!RemoteAccessibilityService.clickTextExact("仅本次允许")) {
                RemoteAccessibilityService.clickTextExact("允许");
            }
            sleep(600);
            return;
        }
        // 只在出现明确确认按钮时点，避免误触其它页面元素
        if (text.contains("确定") && (text.contains("无线调试") || text.contains("调试"))) {
            RemoteAccessibilityService.clickTextExact("确定");
            sleep(500);
            return;
        }
        if (text.contains("允许") && text.contains("调试")) {
            RemoteAccessibilityService.clickTextExact("允许");
            sleep(500);
        }
    }

    private static boolean scrollUntilVisible(String text, int maxSwipes) throws InterruptedException {
        for (int i = 0; i < maxSwipes; i++) {
            if (RemoteAccessibilityService.readText().contains(text)) return true;
            RemoteAccessibilityService.scrollPage(true);
            sleep(500);
        }
        return RemoteAccessibilityService.readText().contains(text);
    }

    // ===== Shizuku =====

    private static String startShizuku(Context ctx, long deadline) throws InterruptedException {
        Intent intent = ctx.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
        if (intent == null) return "fail: 未找到 Shizuku 应用";
        launch(ctx, intent);
        sleep(1800);
        dismissConfirmDialogs();
        sleep(600);

        // 等到 Shizuku 主界面出现（未运行状态）
        for (int i = 0; i < 8; i++) {
            String text = RemoteAccessibilityService.readText();
            if (text.contains("Shizuku未运行") || text.contains("Shizuku 未运行")
                    || text.contains("正在运行")) {
                break;
            }
            sleep(500);
        }
        if (ShizukuHelper.isReady()) return null;

        if (!RemoteAccessibilityService.clickTextExact("启动")) {
            RemoteAccessibilityService.scrollPage(true);
            sleep(500);
            if (!RemoteAccessibilityService.clickTextExact("启动")) {
                return "fail: 未找到 Shizuku 的「启动」按钮";
            }
        }

        long until = Math.min(deadline, SystemClock.elapsedRealtime() + 30_000L);
        while (SystemClock.elapsedRealtime() < until) {
            if (ShizukuHelper.isReady()) return null;
            String text = RemoteAccessibilityService.readText();
            if (text.contains("请先进行配对步骤")
                    || text.contains("Please go through the pairing step first")) {
                return "fail: 需要先在 Shizuku 内完成一次「配对」（首次使用需手动，之后重启可自动）";
            }
            sleep(1000);
        }
        return "fail: 点击「启动」后 Shizuku 未就绪";
    }

    private static boolean waitReady(long deadline) throws InterruptedException {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (ShizukuHelper.isReady()) return true;
            sleep(700);
        }
        return ShizukuHelper.isReady();
    }

    // ===== helpers =====

    private static void launch(Context ctx, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(intent);
        } catch (Throwable ignored) {
        }
    }

    private static void step(Listener l, String msg) {
        currentStep = msg;
        if (l != null) l.onEvent(msg);
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }
}
