package com.cdhwan.wq;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * 全局崩溃日志：任何线程异常都会把堆栈写到
 *   /sdcard/tb_crash.txt 与 应用外部目录（免权限）
 * 方便老设备（如 Android 4.4）无 adb 时也能回传日志。
 */
public final class CrashLog {
    private CrashLog() { }

    /** 注册全局异常处理器（幂等） */
    public static synchronized void install(Context c) {
        final Context ctx = c.getApplicationContext();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                dump(ctx, t, e);
                if (prev != null) prev.uncaughtException(t, e);   // 交给系统收尾（进程正常结束）
                else android.os.Process.killProcess(android.os.Process.myPid());
            }
        });
    }

    static void dump(Context ctx, Thread t, Throwable e) {
        try {
            StringWriter sw = new StringWriter();
            e.printStackTrace(new PrintWriter(sw));
            String text = "=== " + System.currentTimeMillis() + " thread=" + t.getName() + " ===\n" + sw.toString() + "\n";
            Log.e("TypeBridgeTV", text);
            File[] targets = new File[]{
                    new File("/sdcard/tb_crash.txt"),
                    new File(ctx.getExternalFilesDir(null), "tb_crash.txt")
            };
            for (File f : targets) {
                try {
                    if (f == null) continue;
                    FileOutputStream fos = new FileOutputStream(f, true);
                    fos.write(text.getBytes("UTF-8"));
                    fos.close();
                } catch (Exception ignored) { }
            }
        } catch (Throwable ignored) { }
    }
}
