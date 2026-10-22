package com.cdhwan.wq;

import android.content.Context;
import android.content.SharedPreferences;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** 全局状态：连接码、端口、服务状态、客户端数、输入法实例引用（设置持久化） */
public final class App {
    public static volatile int port = 8765;
    public static volatile String code = "cdhwan";
    public static volatile boolean serverUp = false;
    public static volatile int clients = 0;
    public static volatile TbImeService ime = null;
    private static Context ctx;
    private static final Random rnd = new Random();
    private static final String PREFS = "tb_settings";

    private App() { }

    /** 读取持久化设置（连接码 / 端口） */
    public static void load(Context c) {
        ctx = c.getApplicationContext();
        SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        code = p.getString("code", "cdhwan");
        if (!validCode(code)) code = "cdhwan";
        port = p.getInt("port", 8765);
        if (!validPort(port)) port = 8765;
    }

    /** 保存连接码（合法才生效并持久化） */
    public static void saveCode(String s) {
        if (!validCode(s)) return;
        code = s;
        if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("code", s).apply();
    }

    /** 保存端口（合法才生效并持久化） */
    public static void savePort(int p) {
        if (!validPort(p)) return;
        port = p;
        if (ctx != null) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("port", p).apply();
    }

    /** 生成 6 位随机连接码（去除易混淆字符），自动持久化 */
    public static String newCode() {
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(chars.charAt(rnd.nextInt(chars.length())));
        saveCode(sb.toString());
        return code;
    }

    /** 合法连接码：1-30 位，仅英文+数字 */
    public static boolean validCode(String s) {
        if (s == null || s.length() == 0 || s.length() > 30) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) return false;
        }
        return true;
    }

    /** 合法端口：1-65535 */
    public static boolean validPort(int p) {
        return p >= 1 && p <= 65535;
    }

    /** 本机 IPv4 地址列表（排除回环） */
    public static List<String> ips() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni == null || !ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    byte[] b = a.getAddress();
                    if (b == null || b.length != 4) continue;
                    if (a.isLoopbackAddress()) continue;
                    out.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return out;
    }
}
