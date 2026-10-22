package com.cdhwan.wq;

import android.os.IBinder;
import android.os.Parcel;

/**
 * Shizuku shell 通道：MainActivity 负责绑定 ShellService（binder 存入这里），
 * CursorService 的 ADB 注入模式通过 exec() 执行 shell 命令。
 */
public final class AdbShell {
    /** ShellService 的 binder，由 MainActivity 绑定后写入 */
    public static volatile IBinder binder;

    private AdbShell() { }

    public static boolean ready() {
        return binder != null;
    }

    /** 执行一条 shell 命令（如 "input tap 100 200"），返回输出；失败返回 null */
    public static String exec(String cmd) {
        IBinder b = binder;
        if (b == null) return null;
        try {
            Parcel d = Parcel.obtain();
            Parcel r = Parcel.obtain();
            d.writeString(cmd);
            boolean ok = b.transact(1, d, r, 0);
            String out = r.readString();
            d.recycle();
            r.recycle();
            return ok ? out : null;
        } catch (Throwable e) {
            return "ERR " + e;
        }
    }

    // ---------------- Root 直连（su）：root 设备无需 Shizuku ----------------

    /** 检测设备是否有可用的 su（root） */
    public static boolean haveSu() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/su", "-c", "id"});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            String line = br.readLine();
            p.waitFor();
            return line != null && line.contains("uid=0");
        } catch (Throwable e) {
            return false;
        }
    }

    /** 以 root 执行一条 shell 命令，返回输出；失败返回 ERR... */
    public static String suExec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/su", "-c", cmd});
            java.io.BufferedReader br = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            p.waitFor();
            return sb.toString();
        } catch (Throwable e) {
            return "ERR " + e;
        }
    }

    /** 以 root 连续执行多条 shell 命令（合并为一条 su -c "cmd1; cmd2; ..."，减少进程开销） */
    public static String suChain(String... cmds) {
        StringBuilder sb = new StringBuilder();
        for (String c : cmds) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(c);
        }
        return suExec(sb.toString());
    }

    // ---------------- 长驻 root shell（供高频注入：sendevent 多点触摸等） ----------------
    // 每条 su -c 都要起一个新进程（~100ms），高频 MOVE 跟不上；改为常驻一个 root shell，
    // 命令直接写 stdin 逐条执行，接近零开销，帧率可到几十 Hz，浏览器才能识别连续缩放。

    private static Process suProc = null;
    private static java.io.BufferedWriter suIn = null;

    /** 打开（或复用）常驻 root shell：多方式尝试（exec sh / sh / 裸 su）+ 存活检测 */
    public static synchronized boolean suOpen() {
        if (suProc != null) {
            try { suProc.exitValue(); suProc = null; suIn = null; }
            catch (IllegalThreadStateException e) { return true; } // 仍存活
        }
        String[][] tries = {
                {"/system/bin/su", "-c", "exec sh"},
                {"/system/bin/su", "-c", "sh"},
                {"/system/bin/su"}
        };
        for (String[] argv : tries) {
            try {
                Process p = Runtime.getRuntime().exec(argv);
                Thread.sleep(150);
                try { p.exitValue(); p.destroy(); continue; } // 立即退出：此方式不可用
                catch (IllegalThreadStateException e) {
                    suProc = p;
                    suIn = new java.io.BufferedWriter(new java.io.OutputStreamWriter(p.getOutputStream(), "UTF-8"));
                    return true;
                }
            } catch (Throwable e) { }
        }
        suProc = null; suIn = null;
        return false;
    }

    /** 验证常驻 shell 是否真的在执行命令：写入 echo 并轮询读回（限时 800ms） */
    public static synchronized boolean suTest() {
        if (!suOpen()) return false;
        try {
            suIn.write("echo TBSHELLOK\n");
            suIn.flush();
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 800) {
                if (suProc.getInputStream().available() > 0) {
                    byte[] b = new byte[64];
                    int n = suProc.getInputStream().read(b);
                    return n > 0;
                }
                Thread.sleep(50);
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    /** 向常驻 root shell 写一条命令（不等待，fire-and-forget；高频注入用） */
    public static void suWrite(String cmd) {
        try {
            java.io.BufferedWriter w = suIn;
            if (w == null) return;
            w.write(cmd);
            w.write('\n');
            w.flush();
        } catch (Throwable ignored) { }
    }
}
