package com.mahao.remotecontrol;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class RemoteAccessibilityService extends AccessibilityService {

    private static volatile RemoteAccessibilityService instance;

    @Override
    protected void onServiceConnected() {
        instance = this;
        WsRemoteService.ensureConnected();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        instance = null;
    }

    public static boolean isReady() { return instance != null; }
    public static boolean isRunning() { return instance != null; }

    private static void finish(String id, boolean ok, String output, String err) {
        if (WsRemoteService.isRunning()) {
            WsRemoteService.sendResult(id, ok, output, err);
        }
    }

    private static void ok(String id) { finish(id, true, null, null); }
    private static void okOut(String id, String out) { finish(id, true, out, null); }
    private static void fail(String id, String err) { finish(id, false, null, err); }

    // ===== Gestures =====

    public static void tap(int x, int y, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 10);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        AccessibilityService.GestureResultCallback cb = new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok(id); }
            @Override public void onCancelled(GestureDescription g) { fail(id, "gesture cancelled"); }
        };
        svc.dispatchGesture(gesture, cb, null);
    }

    public static void longTap(int x, int y, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        Path path = new Path();
        path.moveTo(x, y);
        path.lineTo(x, y);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, 500);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        AccessibilityService.GestureResultCallback cb = new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok(id); }
            @Override public void onCancelled(GestureDescription g) { fail(id, "gesture cancelled"); }
        };
        svc.dispatchGesture(gesture, cb, null);
    }

    public static void swipe(int x1, int y1, int x2, int y2, long duration, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        long dur = Math.min(duration, 200);
        GestureDescription.StrokeDescription stroke = new GestureDescription.StrokeDescription(path, 0, dur);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        AccessibilityService.GestureResultCallback cb = new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { ok(id); }
            @Override public void onCancelled(GestureDescription g) { fail(id, "gesture cancelled"); }
        };
        svc.dispatchGesture(gesture, cb, null);
    }

    // ===== Text Input =====

    public static void typeText(String text, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { fail(id, "no active window"); return; }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        // 窗口根节点通常不支持 SET_TEXT，优先找当前焦点输入框，再退回第一个可编辑节点
        AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (target == null) target = findEditable(root, 0);
        boolean result = false;
        if (target != null) {
            result = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (target != root) target.recycle();
        }
        if (!result) result = root.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (result) ok(id); else fail(id, "no editable field focused");
        root.recycle();
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 20) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i), depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    // ===== Navigation =====

    public static void backKey(String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        boolean ok2 = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
        if (ok2) ok(id); else fail(id, "back action failed");
    }

    public static void homeKey(String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        boolean ok2 = svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        if (ok2) ok(id); else fail(id, "home action failed");
    }

    // ===== Screen Analysis =====

    public static void findText(String text, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { fail(id, "no active window"); return; }
        AccessibilityNodeInfo found = findNodeByText(root, text);
        if (found != null) {
            Rect r = new Rect();
            found.getBoundsInScreen(r);
            int cx = (r.left + r.right) / 2;
            int cy = (r.top + r.bottom) / 2;
            okOut(id, cx + "," + cy + " [" + r.left + "," + r.top + "," + r.right + "," + r.bottom + "]");
            found.recycle();
        } else {
            fail(id, "text not found: " + text);
        }
        root.recycle();
    }

    public static void tapText(String text, String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { fail(id, "no active window"); return; }
        AccessibilityNodeInfo found = findNodeByText(root, text);
        if (found != null) {
            boolean result = found.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (result) ok(id); else fail(id, "click failed");
            found.recycle();
        } else {
            fail(id, "text not found: " + text);
        }
        root.recycle();
    }

    public static void getText(String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { fail(id, "no active window"); return; }
        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        okOut(id, sb.toString());
        root.recycle();
    }

    public static void dump(String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        AccessibilityNodeInfo root = svc.getRootInActiveWindow();
        if (root == null) { fail(id, "no active window"); return; }
        StringBuilder sb = new StringBuilder();
        dumpNode(root, sb, 0);
        okOut(id, sb.toString());
        root.recycle();
    }

    // ===== Screenshot (API 34+) =====

    public static void screenshot(String id) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) { fail(id, "a11y service not connected"); return; }
        try {
            svc.takeScreenshot(0, svc.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        try {
                            android.hardware.HardwareBuffer hwBuffer = result.getHardwareBuffer();
                            Bitmap screenshot = android.graphics.Bitmap.wrapHardwareBuffer(hwBuffer, result.getColorSpace());
                            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    screenshot.compress(Bitmap.CompressFormat.PNG, 80, out);
    String base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.DEFAULT);
    okOut(id, base64);
                        } catch (Exception e) {
                            fail(id, "screenshot encode failed: " + e.getMessage());
                        }
                    }
                    @Override
                    public void onFailure(int errorCode) {
                        fail(id, "screenshot failed: errorCode=" + errorCode);
                    }
                });
        } catch (Throwable e) {
            fail(id, "takeScreenshot failed: " + e.getMessage());
        }
    }

    // ===== Automation primitives (used by ShizukuRecovery) =====

    private static AccessibilityNodeInfo root() {
        RemoteAccessibilityService svc = instance;
        return svc == null ? null : svc.getRootInActiveWindow();
    }

    static String readText() {
        AccessibilityNodeInfo root = root();
        if (root == null) return "";
        StringBuilder sb = new StringBuilder();
        collectText(root, sb);
        root.recycle();
        return sb.toString();
    }

    static boolean clickTextContains(String text) {
        return clickByText(text, false);
    }

    static boolean clickTextExact(String text) {
        return clickByText(text, true);
    }

    private static boolean clickByText(String text, boolean exact) {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        AccessibilityNodeInfo found = findMatch(root, text, exact, 0);
        boolean ok = found != null && clickSelfOrAncestor(found);
        if (found != null) found.recycle();
        root.recycle();
        return ok;
    }

    /** 依次尝试多个文本，命中一个就点击。 */
    static boolean clickAny(String... texts) {
        for (String text : texts) {
            if (text != null && clickTextContains(text)) return true;
        }
        return false;
    }

    /**
     * 在指定标签所在的行里找开关并点击。
     * 适配两种布局：开关与标签同级（单页开关）或需要先进子页面（本方法只处理同级）。
     */
    static boolean clickSwitchNearLabel(String label) {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        java.util.List<AccessibilityNodeInfo> labels = new java.util.ArrayList<>();
        collectMatches(root, label, labels, 0, 6);
        boolean ok = false;
        for (AccessibilityNodeInfo labelNode : labels) {
            AccessibilityNodeInfo row = labelNode;
            for (int up = 0; up < 4 && row != null && !ok; up++) {
                AccessibilityNodeInfo sw = findClass(row, "android.widget.Switch", 0);
                if (sw == null) sw = findClass(row, "android.widget.CheckBox", 0);
                if (sw != null) {
                    ok = clickSelfOrAncestor(sw);
                    sw.recycle();
                }
                if (!ok) row = row.getParent();
            }
            if (ok) break;
        }
        for (AccessibilityNodeInfo n : labels) n.recycle();
        root.recycle();
        return ok;
    }

    /** 点击当前页面第一个处于关闭状态的开关。 */
    static boolean clickFirstSwitch() {
        AccessibilityNodeInfo root = root();
        if (root == null) return false;
        java.util.List<AccessibilityNodeInfo> switches = new java.util.ArrayList<>();
        collectClass(root, "android.widget.Switch", switches, 0, 20);
        boolean ok = false;
        for (AccessibilityNodeInfo sw : switches) {
            if (!ok && !sw.isChecked()) ok = clickSelfOrAncestor(sw);
            sw.recycle();
        }
        root.recycle();
        return ok;
    }

    /** 翻一页，down=true 表示查看后面的内容（手指上滑）。 */
    static boolean scrollPage(boolean down) {
        RemoteAccessibilityService svc = instance;
        if (svc == null) return false;
        android.util.DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        int x = dm.widthPixels / 2;
        int y1 = down ? (int) (dm.heightPixels * 0.72) : (int) (dm.heightPixels * 0.28);
        int y2 = down ? (int) (dm.heightPixels * 0.30) : (int) (dm.heightPixels * 0.70);
        Path path = new Path();
        path.moveTo(x, y1);
        path.lineTo(x, y2);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 260);
        final GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] completed = {false};
        svc.dispatchGesture(gesture, new AccessibilityService.GestureResultCallback() {
            @Override public void onCompleted(GestureDescription g) { completed[0] = true; latch.countDown(); }
            @Override public void onCancelled(GestureDescription g) { latch.countDown(); }
        }, null);
        try {
            latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return completed[0];
    }

    static void pressBack() {
        RemoteAccessibilityService svc = instance;
        if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }

    static void pressHome() {
        RemoteAccessibilityService svc = instance;
        if (svc != null) svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }

    private static boolean clickSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        int guard = 0;
        while (cur != null && guard++ < 8) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            cur = cur.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private static AccessibilityNodeInfo findMatch(AccessibilityNodeInfo node, String text,
                                                   boolean exact, int depth) {
        if (node == null || depth > 20) return null;
        if (matches(node.getText(), text, exact) || matches(node.getContentDescription(), text, exact)) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findMatch(node.getChild(i), text, exact, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectMatches(AccessibilityNodeInfo node, String text,
                                       java.util.List<AccessibilityNodeInfo> out,
                                       int depth, int max) {
        if (node == null || depth > 20 || out.size() >= max) return;
        if (matches(node.getText(), text, false) || matches(node.getContentDescription(), text, false)) {
            out.add(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectMatches(node.getChild(i), text, out, depth + 1, max);
        }
    }

    private static boolean matches(CharSequence value, String text, boolean exact) {
        if (value == null) return false;
        String s = value.toString();
        return exact ? s.equals(text) : s.contains(text);
    }

    private static AccessibilityNodeInfo findClass(AccessibilityNodeInfo node, String className, int depth) {
        if (node == null || depth > 20) return null;
        if (className.equals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findClass(node.getChild(i), className, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectClass(AccessibilityNodeInfo node, String className,
                                     java.util.List<AccessibilityNodeInfo> out, int depth, int max) {
        if (node == null || depth > 20 || out.size() >= max) return;
        if (className.equals(node.getClassName())) out.add(node);
        for (int i = 0; i < node.getChildCount() && out.size() < max; i++) {
            collectClass(node.getChild(i), className, out, depth + 1, max);
        }
    }

    // ===== Helpers =====

    private static AccessibilityNodeInfo findNodeByText(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        if (root.getText() != null && root.getText().toString().contains(text)) return root;
        if (root.getContentDescription() != null && root.getContentDescription().toString().contains(text)) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = findNodeByText(root.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }

    private static void collectText(AccessibilityNodeInfo node, StringBuilder sb) {
        if (node == null) return;
        if (node.getText() != null) {
            String t = node.getText().toString().trim();
            if (!t.isEmpty()) sb.append(t).append(" ");
        }
        if (node.getContentDescription() != null) {
            String d = node.getContentDescription().toString().trim();
            if (!d.isEmpty()) sb.append(d).append(" ");
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectText(node.getChild(i), sb);
        }
    }

    private static void dumpNode(AccessibilityNodeInfo node, StringBuilder sb, int depth) {
        if (node == null) return;
        if (depth > 15) { sb.append("  ...(max depth)...\n"); return; }
        for (int i = 0; i < depth; i++) sb.append("  ");
        String cls = node.getClassName() != null ? node.getClassName().toString() : "";
        String text = node.getText() != null ? node.getText().toString() : "";
        String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
        Rect r = new Rect();
        node.getBoundsInScreen(r);
        sb.append(cls).append("[").append(r.left).append(",").append(r.top).append(",")
          .append(r.right).append(",").append(r.bottom).append("]");
        if (!text.isEmpty()) sb.append(" text=").append(text);
        if (!desc.isEmpty()) sb.append(" desc=").append(desc);
        sb.append("\n");
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNode(node.getChild(i), sb, depth + 1);
        }
    }
}
