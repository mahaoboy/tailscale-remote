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
        AccessibilityNodeInfo node = svc.getRootInActiveWindow();
        if (node != null) {
            Bundle args = new Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            if (result) ok(id); else fail(id, "performAction returned false");
            node.recycle();
        } else {
            fail(id, "no active window");
        }
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
