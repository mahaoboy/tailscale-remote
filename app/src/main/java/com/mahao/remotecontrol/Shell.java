package com.mahao.remotecontrol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import rikka.shizuku.Shizuku;

/**
 * 统一的命令执行层：Shizuku 就绪时以 shell(uid 2000) 身份执行，否则退回应用自身 uid。
 * 应用自身 uid 下 pm grant / settings put / screencap 等命令会失败，因此调用方应先用
 * hasShell() 判断能力，或在返回结果里区分 backend。
 */
public final class Shell {

    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;
        public final boolean timedOut;

        Result(int exitCode, String stdout, String stderr, boolean timedOut) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    private Shell() { }

    public static boolean hasShell() {
        return ShizukuHelper.isReady();
    }

    public static String backend() {
        return hasShell() ? "shizuku" : "app";
    }

    public static Result exec(String command, long timeoutMs) {
        Process p = null;
        try {
            p = start(command);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            Thread tOut = pump(p.getInputStream(), out);
            Thread tErr = pump(p.getErrorStream(), err);

            final Process proc = p;
            final int[] code = {Integer.MIN_VALUE};
            Thread waiter = new Thread(() -> {
                try {
                    code[0] = proc.waitFor();
                } catch (Throwable ignored) {
                }
            }, "shell-wait");
            waiter.setDaemon(true);
            waiter.start();
            waiter.join(timeoutMs);

            boolean done = !waiter.isAlive() && code[0] != Integer.MIN_VALUE;
            if (!done) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
                waiter.join(1000);
            }
            tOut.join(800);
            tErr.join(800);

            return new Result(done ? code[0] : -1, out.toString("UTF-8"), err.toString("UTF-8"), !done);
        } catch (Throwable e) {
            return new Result(-1, "", describe(e), false);
        } finally {
            if (p != null) {
                try {
                    if (p.isAlive()) p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static byte[] execBinary(String command) throws IOException {
        Process p = start(command);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread tErr = pump(p.getErrorStream(), new ByteArrayOutputStream());
        try {
            InputStream in = p.getInputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 32 * 1024 * 1024) {
                    throw new IOException("output exceeds 32MB");
                }
            }
            p.waitFor();
            tErr.join(500);
            return out.toByteArray();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } finally {
            try {
                p.destroy();
            } catch (Throwable ignored) {
            }
        }
    }

    public static String quote(String s) {
        if (s == null) return "''";
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static String quoteAll(String[] args) {
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(quote(a));
        }
        return sb.toString();
    }

    private static Process start(String command) throws IOException {
        if (hasShell()) {
            try {
                return Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
            } catch (Throwable ignored) {
                // binder 刚断或权限被撤销：退回应用 uid，让调用方从结果里看到真实退出码
            }
        }
        return new ProcessBuilder("/system/bin/sh", "-c", command).start();
    }

    private static Thread pump(InputStream in, ByteArrayOutputStream out) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (IOException ignored) {
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static String describe(Throwable e) {
        String msg = e.getMessage();
        return e.getClass().getSimpleName() + (msg == null ? "" : ": " + msg);
    }
}
