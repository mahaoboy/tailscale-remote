package com.mahao.remotecontrol;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.regex.Pattern;

/**
 * 远程命令的安全策略，不依赖 Android API，便于单独验证。
 *
 * 默认（未解锁）只放行不会改动系统或他人应用的命令：
 *   - 永久拦截：卸载/停用其他应用、重启关机、删文件、写块设备、清应用数据
 *   - 需要本机解锁：任意 shell、授权限、改系统设置/appops、强杀应用、装非更新包
 * 解锁开关只存在手机本地设置里，远程无法打开。
 */
public final class SafetyPolicy {

    public static final String SELF_UPDATE_PATH = "/storage/emulated/0/Download/app-update.apk";

    private static final Pattern FATAL = Pattern.compile(
            "(^|[;&|\\s(])(rm|rmdir|reboot|shutdown|poweroff|halt|mkfs\\S*|dd|wipe)(\\s|$|;)"
            + "|pm\\s+(uninstall|clear|disable|disable-user|suspend|hide|uninstall-system-updates)"
            + "|cmd\\s+package\\s+(uninstall|clear)",
            Pattern.CASE_INSENSITIVE);

    private SafetyPolicy() { }

    /** 返回 null 表示放行，否则返回拒绝原因。 */
    public static String deny(String cmd, JsonObject obj, String selfPkg, boolean unlocked) {
        if (cmd == null) return null;
        switch (cmd) {
            case "uninstall": {
                String pkg = str(obj, "package");
                if (!selfPkg.equals(pkg)) {
                    return "安全策略拦截：只允许卸载本应用，拒绝卸载 " + pkg;
                }
                return null;
            }
            case "shell":
                return denyShell(str(obj, "command"), unlocked);
            case "grant":
            case "revoke":
                return unlocked ? null : "安全策略拦截：授权限需要在手机本地打开「允许危险命令」";
            case "settings":
            case "appops": {
                String mode = first(obj);
                if ("get".equals(mode) || "list".equals(mode) || "help".equals(mode) || "".equals(mode)) {
                    return null;
                }
                return unlocked ? null : "安全策略拦截：修改系统设置需要在手机本地打开「允许危险命令」";
            }
            case "am": {
                String sub = first(obj);
                if ("start".equals(sub) || "startservice".equals(sub) || "monkey".equals(sub)) return null;
                return unlocked ? null : "安全策略拦截：am " + sub + " 需要在手机本地打开「允许危险命令」";
            }
            case "install": {
                String path = str(obj, "path");
                if (SELF_UPDATE_PATH.equals(path)) return null;
                return unlocked ? null : "安全策略拦截：只允许安装更新包 app-update.apk，其他 APK 需要在手机本地解锁";
            }
            default:
                return null;
        }
    }

    public static boolean isFatallyDestructive(String command) {
        return command != null && FATAL.matcher(command).find();
    }

    private static String denyShell(String command, boolean unlocked) {
        if (isFatallyDestructive(command)) {
            return "安全策略拦截：该命令可能破坏系统或删除数据（rm/reboot/pm uninstall/pm clear/"
                    + "settings put 等），远程永久禁用";
        }
        if (!unlocked) {
            return "安全策略拦截：任意 shell 需要在手机本地打开「允许危险命令」";
        }
        return null;
    }

    private static String str(JsonObject obj, String name) {
        if (obj == null || !obj.has(name)) return "";
        JsonElement el = obj.get(name);
        return el.isJsonNull() ? "" : el.getAsString();
    }

    private static String first(JsonObject obj) {
        if (obj == null || !obj.has("args")) return "";
        JsonElement el = obj.get("args");
        if (el.isJsonArray()) {
            if (el.getAsJsonArray().size() == 0) return "";
            return el.getAsJsonArray().get(0).getAsString();
        }
        return el.isJsonNull() ? "" : el.getAsString();
    }
}
