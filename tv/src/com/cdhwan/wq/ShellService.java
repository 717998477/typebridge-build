package com.cdhwan.wq;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Shizuku UserService：由 Shizuku 管理器以 shell 权限启动在独立进程（:shizuku_shell）。
 * App 通过 Binder 调用 exec() 执行 shell 命令（input tap/swipe/motionevent 等），
 * 实现真实触摸注入（连续轨迹 / 画圈 / 拖动），无 root、无 USB 依赖。
 * Binder 协议：transact code 1 = exec(String cmd) -> String 输出。
 */
public class ShellService extends Service {
    private final IBinder mBinder = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws android.os.RemoteException {
            if (code == 1) {
                String cmd = data.readString();
                String out = exec(cmd);
                reply.writeString(out);
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override public IBinder onBind(Intent intent) { return mBinder; }

    private String exec(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"/system/bin/sh", "-c", cmd});
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
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
}
