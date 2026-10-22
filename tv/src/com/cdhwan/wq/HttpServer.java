package com.cdhwan.wq;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 HTTP + WebSocket 服务器（0 第三方依赖，协议与 Windows 版 TypeBridge 一致）：
 *   /m         手机控制页（HTML）
 *   /qrcode.js 页面二维码库
 *   /pair      地址 JSON（调试/备用）
 *   /ws        WebSocket：hello(code)→ready；text/sync/enter/backspace/ping
 */
public final class HttpServer {
    private static final String TAG = "TypeBridgeTV";
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static Thread thread;
    private static volatile boolean running = false;
    private static volatile ServerSocket serverSocket;
    private static int boundPort = -1;
    private static Context ctx;
    private static final Map<String, byte[]> cache = new HashMap<String, byte[]>();

    private static final Map<String, Integer> fails = Collections.synchronizedMap(new HashMap<String, Integer>());
    private static final Map<String, Long> blocked = Collections.synchronizedMap(new HashMap<String, Long>());

    private HttpServer() { }

    /** 启动/重启服务：端口未变则直接返回；端口变更时关闭旧监听并用新端口重启 */
    public static synchronized void start(Context c) {
        ctx = c.getApplicationContext();
        TbLog.init(ctx);
        if (running && boundPort == App.port) return;
        if (running) {
            running = false;
            ServerSocket old = serverSocket;
            serverSocket = null;
            if (old != null) { try { old.close(); } catch (IOException ignored) { } }
        }
        running = true;
        boundPort = App.port;
        App.serverUp = false;
        thread = new Thread(new Runnable() {
            @Override public void run() { loop(); }
        }, "tb-http");
        thread.setDaemon(true);
        thread.start();
    }

    private static void loop() {
        ServerSocket ss = null;
        try {
            ss = new ServerSocket(App.port, 16, InetAddress.getByName("0.0.0.0"));
        } catch (IOException e) {
            Log.e(TAG, "bind fail: " + e);
            App.serverUp = false;
            return;
        }
        serverSocket = ss;
        App.serverUp = true;
        while (running) {
            try {
                final Socket s = ss.accept();
                s.setTcpNoDelay(true);
                // 每连接一线程：WebSocket 长连接不能阻塞后续 HTTP 请求
                //（否则 /log、第二个手机、页面刷新都会卡住）
                Thread t = new Thread(new Runnable() {
                    @Override public void run() { handle(s); }
                }, "tb-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                // 旧监听被关闭（端口变更）或 accept 异常：本线程退出即可
                if (Thread.currentThread() != thread || !running) break;
            }
        }
    }

    private static void handle(Socket s) {
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String reqLine = readLine(in);
            if (reqLine == null) { s.close(); return; }
            String[] rp = reqLine.split(" ");
            if (rp.length < 2) { s.close(); return; }
            String method = rp[0];
            String path = rp[1];
            Map<String, String> headers = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int c = line.indexOf(':');
                if (c > 0) headers.put(line.substring(0, c).trim().toLowerCase(), line.substring(c + 1).trim());
            }
            if ("/ws".equals(path) && "GET".equals(method)) { wsSession(s, in, out, headers); return; }
            if (path.startsWith("/log")) { respond(out, 200, "text/plain; charset=utf-8", TbLog.get().getBytes("UTF-8")); s.close(); return; }
            if (path.startsWith("/api/volume")) {
                String vj = (CursorService.inst != null) ? CursorService.inst.volumeJson() : "{\"v\":-1,\"max\":-1}";
                respond(out, 200, "application/json; charset=utf-8", vj.getBytes("UTF-8"));
                s.close(); return;
            }
            // 输入模式：GET 返回 {"mode":"adb"|"accessibility"}；POST body {"mode":"adb"} 切换
            if (path.startsWith("/api/mode")) {
                if ("POST".equals(method)) {
                    String body = readBody(in, headers);
                    try {
                        JSONObject jo = new JSONObject(body);
                        String m = jo.optString("mode", "");
                        if ("adb".equals(m) || "accessibility".equals(m)) {
                            ctx.getSharedPreferences("tb_settings", Context.MODE_PRIVATE)
                                    .edit().putString("input_mode", m).apply();
                            respond(out, 200, "application/json; charset=utf-8",
                                    ("{\"mode\":\"" + m + "\"}").getBytes("UTF-8"));
                            s.close(); return;
                        }
                    } catch (Exception e) { }
                    respond(out, 400, "application/json; charset=utf-8", "{\"err\":\"bad mode\"}".getBytes("UTF-8"));
                    s.close(); return;
                }
                String cm = ctx.getSharedPreferences("tb_settings", Context.MODE_PRIVATE)
                        .getString("input_mode", "accessibility");
                respond(out, 200, "application/json; charset=utf-8",
                        ("{\"mode\":\"" + cm + "\"}").getBytes("UTF-8"));
                s.close(); return;
            }
            // 滑动开关：GET 返回 {"on":true|false}；POST body {"on":true} 切换（存偏好，刷新恢复）
            if (path.startsWith("/api/scroll")) {
                if ("POST".equals(method)) {
                    String body = readBody(in, headers);
                    try {
                        JSONObject jo = new JSONObject(body);
                        if (jo.has("on")) {
                            ctx.getSharedPreferences("tb_settings", Context.MODE_PRIVATE)
                                    .edit().putBoolean("scroll_mode", jo.optBoolean("on", false)).apply();
                            respond(out, 200, "application/json; charset=utf-8",
                                    ("{\"on\":" + jo.optBoolean("on", false) + "}").getBytes("UTF-8"));
                            s.close(); return;
                        }
                    } catch (Exception e) { }
                    respond(out, 400, "application/json; charset=utf-8", "{\"err\":\"bad body\"}".getBytes("UTF-8"));
                    s.close(); return;
                }
                boolean sm = ctx.getSharedPreferences("tb_settings", Context.MODE_PRIVATE)
                        .getBoolean("scroll_mode", false);
                respond(out, 200, "application/json; charset=utf-8",
                        ("{\"on\":" + sm + "}").getBytes("UTF-8"));
                s.close(); return;
            }
            // 日志开关：GET 返回 {"on":true|false}；POST body {"on":true} 切换
            if (path.startsWith("/api/log")) {
                if ("POST".equals(method)) {
                    String body = readBody(in, headers);
                    try {
                        JSONObject jo = new JSONObject(body);
                        if (jo.has("on")) {
                            TbLog.loggingEnabled = jo.optBoolean("on", false);
                            respond(out, 200, "application/json; charset=utf-8",
                                    ("{\"on\":" + TbLog.loggingEnabled + "}").getBytes("UTF-8"));
                            s.close(); return;
                        }
                    } catch (Exception e) { }
                    respond(out, 400, "application/json; charset=utf-8", "{\"err\":\"bad body\"}".getBytes("UTF-8"));
                    s.close(); return;
                }
                respond(out, 200, "application/json; charset=utf-8",
                        ("{\"on\":" + TbLog.loggingEnabled + "}").getBytes("UTF-8"));
                s.close(); return;
            }
            if (path.startsWith("/m")) { respond(out, 200, "text/html; charset=utf-8", asset("mobile.html")); s.close(); return; }
            if (path.startsWith("/qrcode.js")) { respond(out, 200, "application/javascript; charset=utf-8", asset("qrcode.js")); s.close(); return; }
            if (path.startsWith("/pair")) { respond(out, 200, "application/json; charset=utf-8", pairJson().getBytes("UTF-8")); s.close(); return; }
            respond(out, 404, "text/plain; charset=utf-8", "not found".getBytes("UTF-8"));
            s.close();
        } catch (IOException ignored) {
            try { s.close(); } catch (IOException ignored2) { }
        }
    }

    private static void respond(OutputStream out, int status, String ctype, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Not Found") + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Connection: close\r\n"
                + "Cache-Control: no-cache, no-store, must-revalidate\r\n"
                + "Pragma: no-cache\r\n"
                + "Expires: 0\r\n"
                + "Access-Control-Allow-Origin: *\r\n\r\n";
        out.write(head.getBytes("ISO-8859-1"));
        out.write(body);
        out.flush();
    }

    private static byte[] asset(String name) {
        byte[] b = cache.get(name);
        if (b != null) return b;
        try {
            InputStream in = ctx.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            b = out.toByteArray();
            cache.put(name, b);
        } catch (IOException e) {
            b = ("asset missing: " + name).getBytes();
        }
        return b;
    }

    private static String pairJson() {
        List<String> ips = ipList();
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ips.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"ip\":\"").append(ips.get(i))
              .append("\",\"label\":\"局域网\",\"group\":\"lan\",\"url\":\"http://")
              .append(ips.get(i)).append(":").append(App.port).append("/m?code=").append(App.code).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    private static List<String> ipList() {
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

    // ---------------- WebSocket ----------------
    private static void wsSession(Socket s, InputStream in, OutputStream out, Map<String, String> headers) {
        String key = headers.get("sec-websocket-key");
        if (key == null) { try { s.close(); } catch (IOException ignored) { } return; }
        try {
            String accept = Base64.encodeToString(sha1(key + WS_GUID), Base64.NO_WRAP);
            String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            out.write(resp.getBytes("ISO-8859-1"));
            out.flush();
        } catch (Exception e) {
            try { s.close(); } catch (IOException ignored) { }
            return;
        }

        String rip = "?";
        try {
            InetAddress ia = s.getInetAddress();
            if (ia != null) rip = ia.getHostAddress();
        } catch (Exception ignored) { }

        boolean authed = false;
        while (true) {
            Frame f;
            try {
                f = readFrame(in);
            } catch (IOException e) { break; }
            if (f == null) break;
            if (f.opcode == 8) break;                       // close
            if (f.opcode == 9) {                            // ping -> pong
                try { sendFrame(out, 10, f.payload); } catch (IOException e) { break; }
                continue;
            }
            if (f.opcode != 1) continue;                    // 仅处理文本帧
            String msg;
            try { msg = new String(f.payload, "UTF-8"); } catch (Exception e) { continue; }
            JSONObject o;
            try { o = new JSONObject(msg); } catch (Exception e) { continue; }
            String type = o.optString("type", "");

            if (!authed) {
                if (!"hello".equals(type)) { try { sendFrame(out, 1, "{\"type\":\"error\",\"msg\":\"请先配对\"}".getBytes("UTF-8")); } catch (IOException ignored) { } continue; }
                String code = o.optString("code", "");
                if (isBlocked(rip)) { try { sendFrame(out, 1, "{\"type\":\"error\",\"msg\":\"尝试次数过多，请稍后再试\"}".getBytes("UTF-8")); } catch (IOException ignored) { } continue; }
                if (!App.code.equalsIgnoreCase(code)) {
                    registerFail(rip);
                    try { sendFrame(out, 1, "{\"type\":\"error\",\"msg\":\"连接码错误\"}".getBytes("UTF-8")); } catch (IOException ignored) { }
                    continue;
                }
                clearFail(rip);
                authed = true;
                App.clients++;
                if (App.ime != null) App.ime.updateStatus();
                try { sendFrame(out, 1, "{\"type\":\"ready\"}".getBytes("UTF-8")); } catch (IOException ignored) { }
                continue;
            }

            if ("text".equals(type)) {
                String t = o.optString("text", "");
                if (t.length() > 0) {
                    boolean done = (CursorService.inst != null) && CursorService.inst.setText(t);
                    if (!done && App.ime != null) App.ime.injectText(t);
                }
            } else if ("sync".equals(type)) {
                int back = o.optInt("back", 0);
                String t = o.optString("text", "");
                if (back > 0 && App.ime != null) App.ime.injectBackspace(back);
                if (t.length() > 0) {
                    boolean done = (CursorService.inst != null) && CursorService.inst.setText(t);
                    if (!done && App.ime != null) App.ime.injectText(t);
                }
            } else if ("enter".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.key("enter");
                else if (App.ime != null) App.ime.injectEnter();
            } else if ("backspace".equals(type)) {
                int n = o.optInt("count", 1);
                if (App.ime != null) App.ime.injectBackspace(n);
            } else if ("mouseto".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.moveToPct(o.optDouble("x", -1), o.optDouble("y", -1));
            } else if ("tap".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.tap();
            } else if ("scroll".equals(type)) {
                if (CursorService.inst != null) {
                    // 滑动开关模式：带 dy/dx 的像素滚动（1:1 跟随手指）
                    if (o.has("dy")) CursorService.inst.scrollPx(o.optDouble("dx", 0), o.optDouble("dy", 0));
                    else CursorService.inst.scroll(o.optString("dir", "down"));
                }
            } else if ("scrollstart".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.scrollStart();
            } else if ("scrollmove".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.scrollMove(o.optDouble("dx", 0), o.optDouble("dy", 0));
            } else if ("scrollend".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.scrollEnd();
            } else if ("key".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.key(o.optString("key", ""));
            } else if ("press".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.press();
            } else if ("dragto".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.dragto(o.optDouble("x", -1), o.optDouble("y", -1));
            } else if ("mouserel".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.moveRel(o.optDouble("dx", 0), o.optDouble("dy", 0));
            } else if ("longpress".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.longPress();
            } else if ("dragmove".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.dragMove(o.optDouble("dx", 0), o.optDouble("dy", 0));
            } else if ("pinch".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.pinch(o.optDouble("scale", 1));
            } else if ("pinchend".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.pinchEnd();
            } else if ("volume".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.volume(o.optString("op", ""));
            } else if ("dragend".equals(type)) {
                if (CursorService.inst != null)
                    CursorService.inst.dragend(o.optDouble("x", -1), o.optDouble("y", -1));
            } else if ("dragup".equals(type)) {
                if (CursorService.inst != null) CursorService.inst.dragup();
            } else if ("ping".equals(type)) {
                try { sendFrame(out, 1, "{\"type\":\"pong\"}".getBytes("UTF-8")); } catch (IOException ignored) { }
            }
        }
        if (authed) { App.clients--; if (App.ime != null) App.ime.updateStatus(); }
        try { s.close(); } catch (IOException ignored) { }
    }

    private static class Frame {
        final int opcode;
        final byte[] payload;
        Frame(int opcode, byte[] payload) { this.opcode = opcode; this.payload = payload; }
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) throw new IOException("eof");
        int b1 = in.read();
        if (b1 < 0) throw new IOException("eof");
        int opcode = b0 & 0x0F;
        long len = b1 & 0x7F;
        boolean masked = (b1 & 0x80) != 0;
        if (len == 126) {
            byte[] x = new byte[2];
            readFully(in, x, 2);
            len = ((x[0] & 0xFF) << 8) | (x[1] & 0xFF);
        } else if (len == 127) {
            byte[] x = new byte[8];
            readFully(in, x, 8);
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | (x[i] & 0xFF);
        }
        if (len > 4 * 1024 * 1024) throw new IOException("frame too big");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask, 4);
        byte[] payload = new byte[(int) len];
        readFully(in, payload, (int) len);
        if (masked) for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        return new Frame(opcode, payload);
    }

    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(payload.length + 10);
        b.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            b.write(n);
        } else if (n < 65536) {
            b.write(126);
            b.write((n >> 8) & 0xFF);
            b.write(n & 0xFF);
        } else {
            b.write(127);
            for (int i = 7; i >= 0; i--) b.write((int) (((long) n >> (8 * i)) & 0xFF));
        }
        b.write(payload);
        out.write(b.toByteArray());
        out.flush();
    }

    private static int readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
        return off;
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
        }
        if (b.size() == 0 && c < 0) return null;
        return new String(b.toByteArray(), "ISO-8859-1");
    }

    /** 读取 POST body（按 Content-Length；没有则读到 EOF 上限 4KB） */
    private static String readBody(InputStream in, Map<String, String> headers) throws IOException {
        int len = 0;
        String cl = headers.get("content-length");
        if (cl != null) {
            try { len = Integer.parseInt(cl.trim()); } catch (Exception e) { len = 0; }
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int total = 0;
        int c;
        while (total < len && (c = in.read()) >= 0) { b.write(c); total++; }
        if (len == 0) {
            // 无 Content-Length：读到空行后按可读数据收（上限 4KB）
            while (total < 4096 && (c = in.read()) >= 0) { b.write(c); total++; }
        }
        return new String(b.toByteArray(), "UTF-8");
    }

    private static byte[] sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return md.digest(s.getBytes("ISO-8859-1"));
    }

    // ---------------- 配对防爆破（同源连错 5 次拉黑 10 分钟） ----------------
    private static boolean isBlocked(String ip) {
        Long until = blocked.get(ip);
        if (until == null) return false;
        if (System.currentTimeMillis() < until) return true;
        blocked.remove(ip);
        return false;
    }

    private static void registerFail(String ip) {
        Integer n = fails.get(ip);
        int v = (n == null ? 0 : n) + 1;
        if (v >= 5) {
            blocked.put(ip, System.currentTimeMillis() + 10 * 60 * 1000);
            fails.remove(ip);
        } else {
            fails.put(ip, v);
        }
        if (fails.size() > 2000) fails.clear();
        if (blocked.size() > 2000) blocked.clear();
    }

    private static void clearFail(String ip) {
        fails.remove(ip);
        blocked.remove(ip);
    }
}
