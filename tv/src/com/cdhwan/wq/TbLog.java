package com.cdhwan.wq;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 共享日志：内存环形缓冲 + 尽力写文件。
 * - 内存缓冲始终保留最近内容，供手机浏览器通过 http://电视IP:端口/log 直接查看；
 * - 文件按序尝试：应用外部目录（免权限，Android 11+ 可用）→ 内部目录 → /sdcard（老系统可用）。
 * Android 11+ 分区存储禁止直接写 /sdcard 根目录，因此必须回退到应用专属目录。
 */
public final class TbLog {
    private static final int MAX = 128 * 1024;
    private static final StringBuilder buf = new StringBuilder(MAX);
    private static volatile Context ctx = null;
    private static volatile String lastPath = "(内存缓冲)";
    /** 日志开关：开=true 记录；关=false 不记录（由主界面「日志开关」按钮控制） */
    public static volatile boolean loggingEnabled = false; // 默认关闭：只有手动打开日志开关才记录

    private TbLog() { }

    public static void init(Context c) {
        if (ctx == null && c != null) ctx = c.getApplicationContext();
    }

    public static synchronized void w(String s) {
        if (!loggingEnabled) return; // 日志开关关闭：不记录
        String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS").format(new Date()) + " " + s + "\n";
        buf.append(line);
        if (buf.length() > MAX) buf.delete(0, buf.length() - MAX);
        if (ctx == null) return;
        try {
            File f0 = new File(ctx.getExternalFilesDir(null), "tb_cursor.txt");
            File f1 = new File(ctx.getFilesDir(), "tb_cursor.txt");
            File f2 = new File("/sdcard/tb_cursor.txt");
            File[] targets = new File[]{ f0, f1, f2 };
            for (File f : targets) {
                try {
                    if (f == null) continue;
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write(line.getBytes("UTF-8"));
                    fos.close();
                    lastPath = f.getAbsolutePath();
                } catch (Exception ignored) { }
            }
        } catch (Throwable ignored) { }
    }

    public static synchronized String get() {
        return buf.toString();
    }

    public static String lastPath() { return lastPath; }
}
