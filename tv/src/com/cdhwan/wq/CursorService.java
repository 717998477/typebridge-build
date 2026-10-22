package com.cdhwan.wq;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayDeque;

/**
 * 鼠标遥控服务（无障碍）：在电视上叠加鼠标指针，跟随焦点移动，支持点击、滚动和文字注入。
 * 所有关键步骤写日志到 /sdcard/tb_cursor.txt，便于无 adb 的设备排查。
 */
public class CursorService extends AccessibilityService {
    public static volatile CursorService inst = null;
    private static final int CUR = 44;

    private WindowManager wm;
    private ImageView cursorView;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int screenW = 1280, screenH = 720;
    private int curX = -1, curY = -1;
    private Runnable scanTask = null;
    private Runnable animTask = null;
    private Runnable watchTask = null;
    private boolean tracking = false; // 默认手动模式；用户滑动（moveRel/moveToPct）后保持手动，准星不再自动跟随焦点
    private boolean adbDragDown = false; // ADB 模式：拖动会话是否已注入 DOWN（会话式 DOWN->MOVE...->UP）

    // 移动合并：高频 mouseto 只保留最新目标，每帧在主线程执行一次
    private boolean movePending = false;
    private double pendingX = 0, pendingY = 0;

    private int evFocused = 0, evState = 0, evContent = 0;
    private int nMove = 0, nTap = 0, nScroll = 0, nKey = 0, nScan = 0, nScanNull = 0;

    static void log(String s) {
        TbLog.w(s);
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        inst = this;
        TbLog.init(this);
        log("=== CursorService connected · API=" + Build.VERSION.SDK_INT
                + " rel=" + Build.VERSION.RELEASE + " model=" + Build.MODEL);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        try { wm.getDefaultDisplay().getRealMetrics(dm); }
        catch (Exception e) { wm.getDefaultDisplay().getMetrics(dm); }
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        log("screen=" + screenW + "x" + screenH);

        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            log("rootAtConnect=" + (r == null ? "null" : r.getClassName().toString()));
            if (r != null) r.recycle();
        } catch (Throwable e) {
            log("rootAtConnectErr " + e);
        }

        cursorView = new ImageView(this);
        cursorView.setImageBitmap(cursorBitmap());
        int type = Build.VERSION.SDK_INT >= 22
                ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                CUR, CUR, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0; lp.y = 0;
        try {
            wm.addView(cursorView, lp);
            log("overlay added ok type=" + type);
        } catch (Throwable e) {
            log("overlay FAILED type=" + type + " " + stack(e));
            cursorView = null;
        }
        // 默认初始位置：屏幕右上角（不碍眼；操作后随操作移动）
        curX = Math.max(50, screenW - 50);
        curY = 50;
        moveTo(curX, curY);

        watchTask = new Runnable() {
            public void run() {
                try {
                    if (tracking && cursorView != null) scanFocus();
                    log("stats f=" + evFocused + " s=" + evState + " c=" + evContent
                            + " move=" + nMove + " tap=" + nTap + " scroll=" + nScroll + " key=" + nKey
                            + " scan=" + nScan + " scanNull=" + nScanNull);
                } catch (Throwable ignored) { }
                h.postDelayed(this, 30000);
            }
        };
        h.postDelayed(watchTask, 30000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        try {
            int t = event.getEventType();
            if (t == AccessibilityEvent.TYPE_VIEW_FOCUSED) { evFocused++; scheduleScan(); }
            else if (t == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) { evState++; scheduleScan(); }
            else if (t == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) { evContent++; scheduleScan(); }
        } catch (Throwable e) {
            log("onAccessibilityEvent err " + e);
        }
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent intent) {
        log("=== onUnbind");
        teardown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        log("=== onDestroy");
        teardown();
        super.onDestroy();
    }

    private void teardown() {
        inst = null;
        if (cursorView != null) {
            try { wm.removeView(cursorView); } catch (Exception ignored) { }
            cursorView = null;
        }

        if (scanTask != null) { h.removeCallbacks(scanTask); scanTask = null; }
        if (animTask != null) { h.removeCallbacks(animTask); animTask = null; }
        if (watchTask != null) { h.removeCallbacks(watchTask); watchTask = null; }
    }

    /** 供主界面「自检」读取状态 */
    public String state() {
        return "inst=alive API=" + Build.VERSION.SDK_INT + " model=" + Build.MODEL
                + " screen=" + screenW + "x" + screenH
                + " overlay=" + (cursorView != null ? "ok" : "FAILED")
                + " events(f/s/c)=" + evFocused + "/" + evState + "/" + evContent
                + " cmd(move/tap/scroll/key)=" + nMove + "/" + nTap + "/" + nScroll + "/" + nKey
                + " scan=" + nScan + " scanNull=" + nScanNull
                + " cursor=(" + curX + "," + curY + ")";
    }

    // ---------------- 焦点跟随 ----------------
    private void scheduleScan() {
        if (scanTask != null) h.removeCallbacks(scanTask);
        scanTask = new Runnable() { public void run() { scanFocus(); } };
        h.postDelayed(scanTask, 120);
    }

    private void scanFocus() {
        nScan++;
        if (!tracking || cursorView == null) return;
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable e) { log("getRoot err " + e); }
        if (root == null) { nScanNull++; return; }
        AccessibilityNodeInfo n = findFocused(root);
        if (n != null) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.width() > 4 && r.height() > 4) animateTo(r.centerX(), r.centerY());
            n.recycle();
        }
        root.recycle();
    }

    /** 优先真正获焦的节点；没有则返回第一个可见可交互节点 */
    private AccessibilityNodeInfo findFocused(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        AccessibilityNodeInfo fallback = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isVisibleToUser() && n.isFocused()) return n;
            if (fallback == null && n.isVisibleToUser()
                    && (n.isClickable() || n.isFocusable() || n.isEditable())) {
                Rect rb = new Rect();
                n.getBoundsInScreen(rb);
                if (rb.width() > 4 && rb.height() > 4 && rb.width() < screenW * 0.9f) fallback = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return fallback;
    }

    /** 光标坐标处最贴近的可交互节点（用于点击） */
    private AccessibilityNodeInfo findAt(AccessibilityNodeInfo root, int x, int y) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        AccessibilityNodeInfo best = null;
        int bestArea = Integer.MAX_VALUE;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (n.isVisibleToUser() && r.contains(x, y)
                    && (n.isClickable() || n.isEditable() || n.isFocusable())) {
                int area = r.width() * r.height();
                if (area < bestArea) { best = n; bestArea = area; }
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return best;
    }

    /** 获焦可编辑节点（用于文字注入） */
    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        AccessibilityNodeInfo fallback = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isEditable()) {
                if (n.isFocused() && n.isVisibleToUser()) return n;
                if (fallback == null && n.isVisibleToUser()) fallback = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return fallback;
    }

    // ---------------- 光标渲染 ----------------
    private void moveTo(int x, int y) {
        if (cursorView == null) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) cursorView.getLayoutParams();
            lp.x = x - CUR / 2;
            lp.y = y - CUR / 2;
            wm.updateViewLayout(cursorView, lp);
        } catch (Throwable e) {
            log("updateViewLayout err " + e);
        }
    }

    private void animateTo(final int tx, final int ty) {
        if (animTask != null) h.removeCallbacks(animTask);
        final int sx = curX < 0 ? tx : curX;
        final int sy = curY < 0 ? ty : curY;
        final long start = System.currentTimeMillis();
        animTask = new Runnable() {
            public void run() {
                long el = System.currentTimeMillis() - start;
                float k = el >= 120 ? 1f : el / 120f;
                k = k * k * (3 - 2 * k);
                int x = sx + (int) ((tx - sx) * k);
                int y = sy + (int) ((ty - sy) * k);
                curX = x; curY = y;
                moveTo(x, y);
                if (k < 1f) h.postDelayed(this, 16);
                else animTask = null;
            }
        };
        h.post(animTask);
    }

    private void jumpTo(int x, int y) {
        if (animTask != null) { h.removeCallbacks(animTask); animTask = null; }
        curX = x; curY = y;
        moveTo(x, y);
    }

    // ---------------- 手机指令 ----------------
    // 注意：光标窗口由主线程创建，WindowManager 的一切操作必须回到主线程
    // （否则 CalledFromWrongThreadException，这正是此前"鼠标不能动"的根因）

    /** 绝对移动：x/y 为 0-1000 归一化坐标（高频合并，主线程执行） */
    public void moveToPct(double x, double y) {
        nMove++;
        tracking = false;
        pendingX = x;
        pendingY = y;
        if (!movePending) {
            movePending = true;
            h.post(new Runnable() {
                public void run() {
                    movePending = false;
                    int px = (int) (Math.max(0, Math.min(1000, pendingX)) * screenW / 1000);
                    int py = (int) (Math.max(0, Math.min(1000, pendingY)) * screenH / 1000);
                    jumpTo(px, py);
                }
            });
        }
        if (nMove == 1 || nMove % 50 == 0) log("mouseto " + (int) x + "," + (int) y + " overlay=" + (cursorView != null));
    }

    /** 相对移动：dx/dy 归一化 0-1000（对应屏幕比例增量）。微软远程桌面风格：准星不跟手指位置
     *  必须关闭 tracking（焦点跟随），否则焦点事件会把准星自动拉回焦点处（用户反馈"准星自动跑左下角"） */
    public void moveRel(double dx, double dy) {
        nMove++;
        tracking = false;
        h.post(new Runnable() {
            public void run() {
                if (dx == 0 && dy == 0) return;
                int px = curX + (int) (dx * screenW / 1000);
                int py = curY + (int) (dy * screenH / 1000);
                px = Math.max(0, Math.min(screenW - 1, px));
                py = Math.max(0, Math.min(screenH - 1, py));
                jumpTo(px, py);
            }
        });
        if (nMove == 1 || nMove % 50 == 0) log("mouserel " + (int) dx + "," + (int) dy + " -> " + curX + "," + curY);
    }

    /** 输入模式：adb=Shizuku ADB 注入（真实连续触摸）；accessibility=无障碍手势注入 */
    private boolean adbMode() {
        try {
            return "adb".equals(getSharedPreferences("tb_settings", MODE_PRIVATE)
                    .getString("input_mode", "accessibility"));
        } catch (Throwable e) {
            return false;
        }
    }

    private boolean suReady = false, suChecked = false;
    private boolean adbUseSu() {
        if (!suChecked) { suReady = AdbShell.haveSu(); suChecked = true; }
        return suReady;
    }

    private void adbInput(String cmd) {
        // Root 直连优先（无需 Shizuku）；无 root 才走 Shizuku shell
        String out = adbUseSu() ? AdbShell.suExec(cmd) : AdbShell.exec(cmd);
        if (out != null && (out.startsWith("ERR") || out.contains("not found"))) {
            log("adb " + cmd + " -> " + out);
        }
    }

    /** 单击：优先点光标处可点元素，否则手势注入触摸（主线程执行） */
    public void tap() {
        nTap++;
        if (nTap == 1 || nTap % 20 == 0) log("tap at " + curX + "," + curY + " overlay=" + (cursorView != null));
        h.post(new Runnable() { public void run() { doTap(); } });
    }

    private void doTap() {
        if (adbMode()) {
            // 防御：若上一段长按会话的 DOWN 未结束，先补 UP 再点击，避免与悬挂触摸冲突
            if (adbDragDown) {
                adbInput("input motionevent UP " + curX + " " + curY);
                adbDragDown = false;
                adbLongDownAt = 0;
                log("adb pre-UP before tap");
            }
            adbInput("input tap " + curX + " " + curY);
            log("adb tap " + curX + "," + curY);
            return;
        }
        // 现代安卓（7.0+）：优先用"真实触摸注入"点击——和插 USB 鼠标等效，
        // 能点任意位置（图库图片、网页元素等），而不是只能点"遥控焦点能选到的控件"
        if (Build.VERSION.SDK_INT >= 24) {
            gestureTapWithFallback(curX, curY);
            return;
        }
        // 4.4：没有手势注入，退化为无障碍点击
        accessibilityTap();
    }

    /** 无障碍点击：点光标下可点击元素（含祖先），否则点焦点元素 */
    private void accessibilityTap() {
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable e) { log("getRoot err " + e); }
        if (root != null) {
            AccessibilityNodeInfo under = findAt(root, curX, curY);
            if (under != null) {
                AccessibilityNodeInfo target = clickableSelfOrAncestor(under);
                if (target != null) {
                    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    log("tap node " + target.getClassName() + " clickable=" + target.isClickable() + " ok=" + ok);
                    target.recycle();
                    if (under != target) under.recycle();
                    root.recycle();
                    return;
                }
                under.recycle();
            }
            root.recycle();
        }
        AccessibilityNodeInfo r2 = null;
        try { r2 = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (r2 != null) {
            AccessibilityNodeInfo f = findFocused(r2);
            AccessibilityNodeInfo target = f != null ? clickableSelfOrAncestor(f) : null;
            if (target != null) target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (target != null) target.recycle();
            if (f != null && f != target) f.recycle();
            r2.recycle();
        }
    }

    /** 自身可点击则返回自身，否则向上找最近的可点击祖先（中间节点顺带回收） */
    private AccessibilityNodeInfo clickableSelfOrAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            if (cur.isClickable()) return cur;
            AccessibilityNodeInfo p = cur.getParent();
            if (cur != node) cur.recycle();
            cur = p;
        }
        return null;
    }

    /** 真实触摸点击；被系统拒绝（手势能力不足）时回退到无障碍点击 */
    private void gestureTapWithFallback(final int x, final int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder gb = new GestureDescription.Builder();
            gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
            boolean sent = dispatchGesture(gb.build(), new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gd) {
                    log("gestureTap " + x + "," + y + " ok");
                }
                @Override public void onCancelled(GestureDescription gd) {
                    log("gestureTap " + x + "," + y + " cancelled -> fallback");
                    h.post(new Runnable() { public void run() { accessibilityTap(); } });
                }
            }, null);
            // 重要：dispatchGesture 返回 false 时（服务未声明 canPerformGestures 等情况）
            // 回调根本不会触发，必须在这里立即回退
            if (!sent) {
                log("gestureTap " + x + "," + y + " NOT_SENT -> fallback");
                accessibilityTap();
            }
        } catch (Throwable e) {
            log("gestureTap err " + e);
            accessibilityTap();
        }
    }

    /** 滚动：dir=up/down/left/right（主线程执行） */
    public void scroll(final String dir) {
        nScroll++;
        h.post(new Runnable() { public void run() { doScroll(dir); } });
    }

    /** 滑动开关模式：以准星为锚点，单指滑动 dx/dy 像素（1:1 跟随手指，内容动多少手指滑多少） */
    public void scrollPx(final double dx, final double dy) {
        if (dx == 0 && dy == 0) return;
        h.post(new Runnable() {
            public void run() {
                try {
                    int sx = curX, sy = curY; // 准星固定：滑动锚点
                    int ex = (int) (sx + dx), ey = (int) (sy + dy);
                    ex = Math.max(0, Math.min(screenW - 1, ex));
                    ey = Math.max(0, Math.min(screenH - 1, ey));
                    // 最小滑动距离保护：太短的手势会被系统/浏览器判定为点击（触摸 slop 阈值），
                    // 保证单次注入至少 30px 的真实滑动，才能被识别为滚动
                    double len = Math.hypot(ex - sx, ey - sy);
                    if (len < 1) return;
                    if (len < 30) {
                        double k = 30 / len;
                        ex = sx + (int) Math.round((ex - sx) * k);
                        ey = sy + (int) Math.round((ey - sy) * k);
                        ex = Math.max(0, Math.min(screenW - 1, ex));
                        ey = Math.max(0, Math.min(screenH - 1, ey));
                        if (ex == sx && ey == sy) return;
                    }
                    if (adbMode()) {
                        // ADB：DOWN→MOVE→UP 真实触摸拖动（无 fling 惯性，1:1 精确跟随）
                        adbInput("input motionevent DOWN " + sx + " " + sy);
                        adbInput("input motionevent MOVE " + ex + " " + ey);
                        adbInput("input motionevent UP " + ex + " " + ey);
                    } else if (Build.VERSION.SDK_INT >= 24) {
                        Path p = new Path();
                        p.moveTo(sx, sy);
                        p.lineTo(ex, ey);
                        GestureDescription.Builder gb = new GestureDescription.Builder();
                        gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
                        dispatchGesture(gb.build(), null, null);
                    } else {
                        return;
                    }
                    log("scrollPx dx=" + (int) dx + " dy=" + (int) dy
                            + " (" + sx + "," + sy + ")->(" + ex + "," + ey + ")");
                } catch (Throwable e) { log("scrollPx err " + e); }
            }
        });
    }

    // ---------------- 会话式滚动（滑动开关模式，v0.6.48+）：DOWN 一次→连续 MOVE→松手 UP ----------------
    // 关键：一次触摸会话不中断，浏览器才识别为连续拖动滚动（平滑）；逐段独立手势会"咔哒咔哒"跳。
    private boolean adbScrollOn = false;
    private int adbScrollSx = 0, adbScrollSy = 0, adbScrollEx = 0, adbScrollEy = 0;

    public void scrollStart() {
        h.post(new Runnable() {
            public void run() {
                try {
                    if (adbScrollOn) return;
                    adbScrollSx = curX; adbScrollSy = curY;
                    adbScrollEx = adbScrollSx; adbScrollEy = adbScrollSy;
                    if (adbMode()) {
                        adbInput("input motionevent DOWN " + adbScrollSx + " " + adbScrollSy);
                    }
                    adbScrollOn = true;
                    log("scrollStart at " + adbScrollSx + "," + adbScrollSy);
                } catch (Throwable e) { log("scrollStart err " + e); }
            }
        });
    }

    public void scrollMove(final double dx, final double dy) {
        h.post(new Runnable() {
            public void run() {
                try {
                    if (!adbScrollOn) {
                        adbScrollSx = curX; adbScrollSy = curY; adbScrollOn = true;
                    }
                    adbScrollEx = Math.max(0, Math.min(screenW - 1, (int) (adbScrollSx + dx)));
                    adbScrollEy = Math.max(0, Math.min(screenH - 1, (int) (adbScrollSy + dy)));
                    if (adbMode()) {
                        // 会话式：只发 MOVE，不中断触摸（DOWN 已由 scrollStart 注入）
                        adbInput("input motionevent MOVE " + adbScrollEx + " " + adbScrollEy);
                    } else if (Build.VERSION.SDK_INT >= 24) {
                        // 无障碍：一次性手势退化（从锚点滑到当前位置），会话式仅 ADB(Root) 可用
                        Path p = new Path();
                        p.moveTo(adbScrollSx, adbScrollSy);
                        p.lineTo(adbScrollEx, adbScrollEy);
                        GestureDescription.Builder gb = new GestureDescription.Builder();
                        gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 60));
                        dispatchGesture(gb.build(), null, null);
                    }
                    log("scrollMove -> " + adbScrollEx + "," + adbScrollEy
                            + " (anchor " + adbScrollSx + "," + adbScrollSy + ")");
                } catch (Throwable e) { log("scrollMove err " + e); }
            }
        });
    }

    public void scrollEnd() {
        h.post(new Runnable() {
            public void run() {
                try {
                    if (!adbScrollOn) return;
                    if (adbMode()) {
                        adbInput("input motionevent UP " + adbScrollEx + " " + adbScrollEy);
                    }
                    adbScrollOn = false;
                    log("scrollEnd");
                } catch (Throwable e) { log("scrollEnd err " + e); }
            }
        });
    }

    private void doScroll(String dir) {
        if (Build.VERSION.SDK_INT >= 24) {
            // 现代安卓：模拟真实手指在屏幕上滑动。
            // 关键：起点用屏幕中部大范围滑动（不依赖光标位置），多段平滑路径让系统识别为滚动/fling。
            // 之前从光标处小距离滑动，起点常落在可点击元素上被当成拖拽，所以安卓9/14都滚不动。
            boolean sent = false;
            try {
                boolean vert = "up".equals(dir) || "down".equals(dir);
                int d = ("down".equals(dir) || "right".equals(dir)) ? 1 : -1;
                float sx = screenW / 2f, sy = screenH / 2f;
                float x0 = vert ? sx : sx + d * screenW * 0.25f;
                float y0 = vert ? sy + d * screenH * 0.25f : sy;
                float x1 = vert ? sx : sx - d * screenW * 0.25f;
                float y1 = vert ? sy - d * screenH * 0.25f : sy;
                Path p = new Path();
                p.moveTo(x0, y0);
                // 多段平滑：更接近真实手指，避免被识别为长按拖拽
                for (int i = 1; i <= 8; i++) {
                    p.lineTo(x0 + (x1 - x0) * i / 8f, y0 + (y1 - y0) * i / 8f);
                }
                GestureDescription.Builder gb = new GestureDescription.Builder();
                gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 160));
                sent = dispatchGesture(gb.build(), new GestureResultCallback() {
                    @Override public void onCancelled(GestureDescription gd) {
                        log("scroll gesture cancelled");
                    }
                }, null);
                log("scrollGesture " + dir + " from " + (int) x0 + "," + (int) y0 + " to " + (int) x1 + "," + (int) y1 + " sent=" + sent);
            } catch (Throwable e) { log("scroll gesture err " + e); }
            if (!sent) {
                log("scroll gesture NOT_SENT -> accessibility fallback");
                accessibilityScroll(dir);
            }
            return;
        }
        accessibilityScroll(dir);
    }

    /** 无障碍滚动：依次尝试多个可滚动候选（无手势注入的老系统路径） */
    private void accessibilityScroll(String dir) {
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root == null) return;
        int action = ("up".equals(dir) || "left".equals(dir))
                ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
        AccessibilityNodeInfo f = findFocused(root);
        boolean done = false;
        // 候选1：焦点元素的可滚动祖先
        AccessibilityNodeInfo target = null;
        if (f != null) target = scrollableAncestor(f);
        if (target != null) {
            done = target.performAction(action);
            log("scrollNode(ancestor) " + target.getClassName() + " " + dir + " ok=" + done);
            target.recycle();
        }
        // 候选2：光标所在的可滚动容器
        if (!done) {
            AccessibilityNodeInfo t2 = firstScrollable(root);
            if (t2 != null) {
                done = t2.performAction(action);
                log("scrollNode(container) " + t2.getClassName() + " " + dir + " ok=" + done);
                t2.recycle();
            }
        }
        // 候选3：焦点元素本身
        if (!done && f != null) {
            done = f.performAction(action);
            log("scrollNode(focus) " + f.getClassName() + " " + dir + " ok=" + done);
        }
        if (f != null) f.recycle();
        root.recycle();
    }

    /** 向上找第一个可滚动祖先 */
    private AccessibilityNodeInfo scrollableAncestor(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo cur = node;
        while (true) {
            AccessibilityNodeInfo parent = cur.getParent();
            if (parent == null) break;
            if (parent.isScrollable() && parent.isVisibleToUser()) return parent;
            cur = parent;
        }
        return null;
    }

    /** BFS：优先光标所在的可滚动容器，其次任意可见可滚动容器 */
    private AccessibilityNodeInfo firstScrollable(AccessibilityNodeInfo root) {
        ArrayDeque<AccessibilityNodeInfo> q = new ArrayDeque<AccessibilityNodeInfo>();
        q.add(root);
        AccessibilityNodeInfo under = null;
        AccessibilityNodeInfo any = null;
        while (!q.isEmpty()) {
            AccessibilityNodeInfo n = q.poll();
            if (n == null) continue;
            if (n.isScrollable() && n.isVisibleToUser()) {
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                if (r.contains(curX, curY)) { under = n; break; }
                if (any == null) any = n;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo c = n.getChild(i);
                if (c != null) q.add(c);
            }
        }
        return under != null ? under : any;
    }

    /** 系统键：back/home/menu/enter（主线程执行） */
    public void key(final String k) {
        nKey++;
        h.post(new Runnable() { public void run() { doKey(k); } });
    }

    private void doKey(String k) {
        if ("back".equals(k)) performGlobalAction(GLOBAL_ACTION_BACK);
        else if ("home".equals(k)) performGlobalAction(GLOBAL_ACTION_HOME);
        else if ("menu".equals(k)) performGlobalAction(GLOBAL_ACTION_RECENTS);
        else if ("enter".equals(k)) enter();
    }

    private void enter() {
        // 回车 = 真正的"回车键"语义（不是点击光标处）：
        // 1) 焦点在输入框 -> 注入回车键（输入法需已启用）
        // 2) 焦点在可点按钮 -> 点击焦点元素（等同遥控 OK）
        // 3) 都没有 -> 才点光标处
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root != null) {
            AccessibilityNodeInfo f = findFocused(root);
            if (f != null) {
                boolean editable = f.isEditable() || (f.getClassName() != null && f.getClassName().toString().contains("EditText"));
                if (editable) {
                    log("enter editable -> ime");
                    f.recycle();
                    root.recycle();
                    if (App.ime != null) App.ime.injectEnter();
                    return;
                }
                AccessibilityNodeInfo target = clickableSelfOrAncestor(f);
                if (target != null) {
                    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    log("enter focus " + target.getClassName() + " ok=" + ok);
                    target.recycle();
                    if (f != target) f.recycle();
                    root.recycle();
                    return;
                }
                f.recycle();
            }
            // 焦点不可用：点光标处（现代安卓用真实触摸，等同鼠标左键）
            AccessibilityNodeInfo under = findAt(root, curX, curY);
            if (under != null) {
                AccessibilityNodeInfo target = clickableSelfOrAncestor(under);
                if (target != null) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    log("enter cursor " + target.getClassName());
                    target.recycle();
                    if (under != target) under.recycle();
                    root.recycle();
                    return;
                }
                under.recycle();
            }
            root.recycle();
        }
        if (App.ime != null) App.ime.injectEnter();
    }

    // ---------------- 长按 / 拖拽（模拟手指长按拖动图标） ----------------
    // 软重启教训（用户日志两次证明）：连续两次长按手势、或"长按+移动"多 stroke 手势
    // 会把桌面 Launcher 搞崩（SystemUI 软重启）。因此：
    //   longPress = 单次 600ms 长按（安全，图标浮起/弹菜单）
    //   dragMove  = 编辑模式下单次 down+move 拖动（不带长按，安全）
    //   两者分开注入、间隔 400ms 以上，桌面只受一次长按冲击。
    private int pressX = -1, pressY = -1;
    private double dragBaseX = -1, dragBaseY = -1;
    private long adbLongDownAt = 0;

    /** 长按：在准星当前位置单次按下 600ms（图标浮起/弹菜单）。单 stroke，安全不崩 */
    public void longPress() {
        h.post(new Runnable() {
            public void run() {
                if (adbMode()) {
                    // ADB：真实按住（DOWN 不抬），等系统识别长按（图标浮起）；松手 UP 结束
                    if (!adbDragDown) {
                        adbInput("input motionevent DOWN " + curX + " " + curY);
                        adbDragDown = true;
                        adbLongDownAt = SystemClock.uptimeMillis();
                        log("adb long DOWN " + curX + "," + curY + " (按住等图标浮起)");
                    }
                    return;
                }
                pressX = curX;
                pressY = curY;
                if (Build.VERSION.SDK_INT < 24) { log("longpress needs API24+, skip"); return; }
                try {
                    Path p = new Path();
                    p.moveTo(pressX, pressY);
                    GestureDescription.Builder gb = new GestureDescription.Builder();
                    gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 600));
                    boolean sent = dispatchGesture(gb.build(), null, null);
                    log("longpress " + pressX + "," + pressY + " sent=" + sent);
                } catch (Throwable e) { log("longpress err " + e); }
            }
        });
    }

    /** 拖动图标（安全版）：单次 down+move 快速滑动手势，不带长按。
     *  手势 100ms + mobile 每 100ms 发一条（等长不堆积）：系统手势执行中收到新手势会取消旧的，
     *  发送频率必须 ≤ 手势执行频率，轨迹才能覆盖全部滑动位移；100ms 手势采样 10Hz，画圈轨迹平滑。
     *  多次验证：小米桌面(HyperOS)上注入"长按+移动"多 stroke 手势必导致 Launcher 崩溃软重启
     *  （快 600+300、慢 800+800 都崩，日志为证），这是系统级限制，无法绕过。
     *  保留此单 stroke 版本仅为兼容旧页面；长按弹菜单走 longPress()（单 stroke 600ms，安全）。 */
    public void dragMove(double dx, double dy) {
        h.post(new Runnable() {
            public void run() {
                if (adbMode()) {
                    // ADB：会话式真实触摸 —— 首次 MOVE 前先 DOWN（在准星处按下），之后每次 MOVE 移动
                    if (!adbDragDown) {
                        adbInput("input motionevent DOWN " + curX + " " + curY);
                        adbDragDown = true;
                        adbLongDownAt = SystemClock.uptimeMillis();
                        log("adb drag DOWN " + curX + "," + curY);
                    }
                    curX = Math.max(0, Math.min(screenW - 1, curX + (int) (dx * screenW / 1000)));
                    curY = Math.max(0, Math.min(screenH - 1, curY + (int) (dy * screenH / 1000)));
                    moveTo(curX, curY);
                    // 长按静置期：DOWN 后 ~500ms 内不注入 MOVE，等桌面识别长按（图标浮起）再开始拖
                    if (SystemClock.uptimeMillis() - adbLongDownAt < 500) {
                        log("adb hold... 等图标浮起 (t=" + (SystemClock.uptimeMillis() - adbLongDownAt) + "ms)");
                        return;
                    }
                    adbInput("input motionevent MOVE " + curX + " " + curY);
                    log("adb drag MOVE " + curX + "," + curY);
                    return;
                }
                if (Build.VERSION.SDK_INT < 24) { log("dragmove needs API24+"); return; }
                if (pressX < 0) { pressX = curX; pressY = curY; }
                int tx = pressX + (int) (dx * screenW / 1000);
                int ty = pressY + (int) (dy * screenH / 1000);
                tx = Math.max(0, Math.min(screenW - 1, tx));
                ty = Math.max(0, Math.min(screenH - 1, ty));
                try {
                    Path p = new Path();
                    p.moveTo(pressX, pressY);
                    p.lineTo(tx, ty);
                    GestureDescription.Builder gb = new GestureDescription.Builder();
                    gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 100));
                    boolean sent = dispatchGesture(gb.build(), null, null);
                    log("dragmove " + pressX + "," + pressY + " -> " + tx + "," + ty + " sent=" + sent);
                    // 持续锚点：下次从本次终点继续，轨迹才能连成横线（松手时 mobile 发 dragend 清锚点）
                    pressX = tx;
                    pressY = ty;
                } catch (Throwable e) { log("dragmove err " + e); }
            }
        });
    }

    // ---------------- 双指捏合缩放（比例式 + 强制对称，不旋转） ----------------
    // 双指手势 = 两个手指并行移动，是应用必须正常处理的真实操作，安全不崩
    // （崩溃的是"长按+移动"串行多 stroke，与双指并行手势性质不同）
    // 缩放只认"间距比例"：mobile 发 scale（当前间距/初始间距），被控端两指始终
    // 在准星位置水平两侧对称移动、只改变间距 → 应用只能判定为纯缩放，不会旋转
    private double pinchR0 = -1;   // 初始半间距
    private double pinchCurR = -1; // 当前半间距
    private int pinchCx = -1, pinchCy = -1; // 中心 = 准星位置
    private long lastPinchDispatch = 0;      // 无障碍手势节流（防 dispatchGesture 互相覆盖）

    /** 双指捏合：scale=当前两指间距/初始间距（<1 缩小，>1 放大） */
    public void pinch(double scale) {
        h.post(new Runnable() {
            public void run() {
                // ADB(Root/su) 模式：sendevent 真实连续双指（浏览器/网页缩放必需：按下后持续 MOVE 不抬起）
                if (adbMode() && adbUseSu()) {
                    adbPinch(scale);
                    return;
                }
                // 无障碍/Shizuku 模式：dispatchGesture 双指手势注入（相册等应用可用；浏览器缩放受限）
                if (Build.VERSION.SDK_INT < 24) return;
                // 无障碍手势节流：每个 dispatchGesture 手势时长 200ms，期间新手势会排队覆盖，
                // 必须等上一个完成再发下一个（220ms），否则连续手势互相打架、缩放反而失效
                long now = System.currentTimeMillis();
                if (now - lastPinchDispatch < 220 && pinchR0 >= 0) return;
                lastPinchDispatch = now;
                // 防御：若拖动会话 DOWN 悬挂，先 UP 再缩放，避免双指与单指冲突
                if (adbMode() && adbDragDown) {
                    adbInput("input motionevent UP " + curX + " " + curY);
                    adbDragDown = false;
                    adbLongDownAt = 0;
                    log("adb pre-UP before pinch");
                }
                if (pinchR0 < 0) {
                    // 首帧：中心 = 准星当前位置（用户要求：点哪从哪缩放），初始半间距 150px=间距 300px
                    // （起手间距足够大，浏览器才把双指手势识别为缩放，放大/缩小都有效）
                    pinchCx = Math.max(250, Math.min(screenW - 250, curX));
                    pinchCy = Math.max(0, Math.min(screenH - 1, curY));
                    pinchR0 = 200;
                    pinchCurR = 200;
                }
                double r = pinchR0 * scale;
                // 边界：最小半间距 15px（防两指重叠），最大不超出屏幕
                double maxR = Math.min(pinchCx, screenW - pinchCx);
                r = Math.max(20, Math.min(r, maxR));
                if (Math.abs(r - pinchCurR) < 1) return;
                int x1 = (int) (pinchCx - pinchCurR), y1 = pinchCy;
                int x2 = (int) (pinchCx + pinchCurR), y2 = pinchCy;
                int nx1 = (int) (pinchCx - r), ny1 = pinchCy;
                int nx2 = (int) (pinchCx + r), ny2 = pinchCy;
                try {
                    Path p1 = new Path();
                    p1.moveTo(x1, y1);
                    p1.lineTo(nx1, ny1);
                    Path p2 = new Path();
                    p2.moveTo(x2, y2);
                    p2.lineTo(nx2, ny2);
                    GestureDescription.Builder gb = new GestureDescription.Builder();
                    gb.addStroke(new GestureDescription.StrokeDescription(p1, 0, 200));
                    gb.addStroke(new GestureDescription.StrokeDescription(p2, 0, 200)); // 并行双指
                    boolean sent = dispatchGesture(gb.build(), null, null);
                    log("pinch scale=" + scale + " r=" + (int) pinchCurR + "->" + (int) r
                            + " center=" + pinchCx + "," + pinchCy + " sent=" + sent);
                    pinchCurR = r;
                } catch (Throwable e) { log("pinch err " + e); }
            }
        });
    }

    /** 捏合结束：清除双指状态 */
    public void pinchEnd() {
        h.post(new Runnable() {
            public void run() {
                if (adbMode() && adbUseSu() && adbPinchOn) {
                    adbPinchEnd();
                    return;
                }
                pinchR0 = -1; pinchCurR = -1; pinchCx = -1; pinchCy = -1;
                log("pinchend");
            }
        });
    }

    // ---------------- ADB(Root) sendevent 真实连续双指缩放 ----------------
    // dispatchGesture 每帧是"按下→移动→抬起"独立手势，浏览器/网页需要"按下后持续移动不抬起"
    // 的连续触摸会话；用 sendevent 直接注入多点触摸事件（协议 B：SLOT/TRACKING_ID/X/Y），
    // 与真实手指完全一致，浏览器缩小/放大都能识别。
    private String adbPinchDev = null;
    private int adbPinchMaxX = 0, adbPinchMaxY = 0;
    private boolean adbPinchOn = false;
    private int adbPinchCx = 0, adbPinchCy = 0;
    private double adbPinchR0 = 200, adbPinchCurR = 200;
    private String adbPinchProto = "B";   // B=协议B(SLOT/TRACKING_ID)；A=协议A(BTN_TOUCH)
    private int adbPinchTrackId = 0x8000; // TRACKING_ID 从大数递增，避免 0/-1 语义混淆
    private boolean adbPinchShellOk = false, adbPinchShellChecked = false;
    private java.util.List<String> adbPinchPending = new java.util.ArrayList<String>();

    /** 注入一条 sendevent：shell 确认可用则 fire-and-forget；确认 DEAD 则收集到本帧批量后台执行（不阻塞主线程） */
    private void adbPinchCmd(String cmd) {
        if (adbPinchShellChecked && !adbPinchShellOk) {
            synchronized (adbPinchPending) { adbPinchPending.add(cmd); }
        } else {
            AdbShell.suWrite(cmd);
        }
    }

    /** 每帧末尾：把 DEAD 模式下收集的命令批量交给后台线程执行（一条 su -c 跑整帧） */
    private void adbPinchFlush() {
        final java.util.List<String> batch;
        synchronized (adbPinchPending) {
            if (adbPinchPending.size() == 0) return;
            batch = new java.util.ArrayList<String>(adbPinchPending);
            adbPinchPending.clear();
        }
        Thread t = new Thread(new Runnable() {
            public void run() { AdbShell.suChain(batch.toArray(new String[0])); }
        });
        t.setDaemon(true);
        t.start();
    }

    /** init 时验证长驻 root shell 是否真的能执行命令（后台线程，避免阻塞主线程导致 ANR） */
    private void adbPinchCheckShell() {
        if (adbPinchShellChecked) return;
        adbPinchShellChecked = true;
        final CursorService self = this;
        Thread t = new Thread(new Runnable() {
            public void run() {
                boolean ok = AdbShell.suTest();
                self.adbPinchShellOk = ok;
                log("adb pinch suShell " + (ok ? "OK" : "DEAD, fallback per-cmd suExec"));
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private int adbPinchParseMax(String s) {
        int i = s.indexOf("max");
        if (i < 0) return 0;
        String rest = s.substring(i + 3).trim();
        int j = 0;
        while (j < rest.length() && Character.isDigit(rest.charAt(j))) j++;
        try { return j > 0 ? Integer.parseInt(rest.substring(0, j)) : 0; } catch (Exception e) { return 0; }
    }

    /** 检测触摸屏设备与坐标范围（一次）：优先 /proc/bus/input/devices（可判断触摸屏），getevent 兜底 */
    private void adbPinchInitDev() {
        if (adbPinchDev != null) return;
        try {
            // 1) /proc/bus/input/devices：优先 INPUT_PROP_DIRECT(=2) 真实触摸屏，
            //    其次名称含 touch/ts 且非 uinput 虚拟设备；uinput-* 是虚拟设备，注入无效需跳过
            String out = AdbShell.suExec("cat /proc/bus/input/devices 2>&1");
            if (out != null && out.length() > 0) {
                String curName = null, curHandlers = null, curProp = null;
                String directDev = null, directName = null, nameDev = null, nameName = null;
                for (String line : out.split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("N: Name=")) curName = t.substring(8);
                    else if (t.startsWith("H: Handlers=")) curHandlers = t;
                    else if (t.startsWith("B: PROP=")) curProp = t;
                    else if (t.length() == 0) {
                        if (curName != null && curHandlers != null) {
                            boolean direct = adbPinchIsDirect(curProp);
                            boolean nameHit = !curName.toLowerCase().startsWith("uinput-")
                                    && adbPinchNameTouch(curName);
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("event\\d+").matcher(curHandlers);
                            String ev = m.find() ? "/dev/input/" + m.group() : null;
                            if (direct && directDev == null && ev != null) { directDev = ev; directName = curName; }
                            if (nameHit && nameDev == null && ev != null) { nameDev = ev; nameName = curName; }
                        }
                        curName = null; curHandlers = null; curProp = null;
                    }
                }
                log("adb pinch candidates: direct=" + (directDev == null ? "-" : directDev + "(" + directName + ")")
                        + " name=" + (nameDev == null ? "-" : nameDev + "(" + nameName + ")"));
                if (directDev != null || nameDev != null) {
                    boolean useDirect = directDev != null;
                    adbPinchDev = useDirect ? directDev : nameDev;
                    adbPinchMaxX = 32767; adbPinchMaxY = 32767;
                    log("adb pinch dev=" + adbPinchDev + " (proc/bus/input, name="
                            + (useDirect ? directName : nameName) + ")");
                    adbPinchReadRange(adbPinchDev);
                    // 实际写入一条无害 SYN 事件，确认 sendevent 能成功写进设备（成功无输出，失败有报错）
                    try {
                        String testOut = AdbShell.suExec("sendevent " + adbPinchDev + " 0 0 0 2>&1");
                        log("adb pinch sendevent write test: "
                                + (testOut == null || testOut.trim().length() == 0 ? "OK" : testOut.trim()));
                    } catch (Throwable e) { log("adb pinch write test err " + e); }
                    // 回读验证：注入一条 X 事件后 getevent 抓取，确认事件真的进了内核（区分"写不进"vs"系统不认"）
                    // 注意：必须在后台线程 + 限时执行，getevent 等不到事件会永久阻塞（之前卡死主线程的根因）
                    final String devForLoop = adbPinchDev;
                    Thread lt = new Thread(new Runnable() {
                        public void run() {
                            try {
                                String loop = AdbShell.suExec("sendevent " + devForLoop + " 3 53 100; "
                                        + "sendevent " + devForLoop + " 0 0 0; "
                                        + "timeout 2 getevent -c 2 " + devForLoop + " 2>&1");
                                log("adb pinch loopback: " + (loop == null ? "NULL" : loop.replace("\n", " | ").trim()));
                            } catch (Throwable e) { log("adb pinch loopback err " + e); }
                        }
                    });
                    lt.setDaemon(true);
                    lt.start();
                    return;
                }
            }
            // 2) 兜底：getevent -pl
            String out2 = AdbShell.suExec("getevent -pl 2>&1");
            if (out2 == null || out2.length() == 0) { log("adb pinch: getevent failed"); return; }
            String curDev = null;
            boolean mt = false;
            int mx = 0, my = 0;
            for (String line : out2.split("\n")) {
                String t = line.trim();
                if (t.startsWith("add device")) {
                    // 行如 "add device 1: /dev/input/event3"：取含 /dev/input/ 的字段
                    String[] p = t.split(" ");
                    curDev = null;
                    for (String tk : p) {
                        if (tk.startsWith("/dev/input/")) { curDev = tk; break; }
                    }
                    mt = false; mx = 0; my = 0;
                } else if (curDev != null && t.contains("ABS_MT_TRACKING_ID")) {
                    mt = true;
                } else if (mt && t.startsWith("ABS_MT_POSITION_X")) {
                    mx = adbPinchParseMax(t);
                } else if (mt && t.startsWith("ABS_MT_POSITION_Y")) {
                    my = adbPinchParseMax(t);
                }
                if (mt && mx > 0 && my > 0) {
                    adbPinchDev = curDev; adbPinchMaxX = mx; adbPinchMaxY = my;
                    break;
                }
            }
            if (adbPinchDev == null) {
                // fallback：从输出里找任意 /dev/input/eventN 路径，范围按常见 32767 假设
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("/dev/input/event\\d+").matcher(out2);
                if (m.find()) {
                    adbPinchDev = m.group();
                    adbPinchMaxX = 32767; adbPinchMaxY = 32767;
                    log("adb pinch fallback dev=" + adbPinchDev + " range=32767x32767");
                } else {
                    log("adb pinch: 无 event 设备，getevent 输出前 300 字符: "
                            + (out2.length() > 300 ? out2.substring(0, 300) : out2));
                }
            } else {
                log("adb pinch dev=" + adbPinchDev + " range=" + adbPinchMaxX + "x" + adbPinchMaxY);
            }
        } catch (Throwable e) { log("adb pinch init err " + e); }
    }

    /** 用 getevent -pl 读取指定设备的 ABS_MT_POSITION_X/Y max 与协议类型（解析失败保持 32767 假设） */
    private void adbPinchReadRange(String dev) {
        try {
            String w = AdbShell.suExec("which sendevent 2>&1");
            log("adb pinch sendevent tool: " + (w == null || w.trim().length() == 0 ? "NOT FOUND" : w.trim()));
            String out = AdbShell.suExec("getevent -pl 2>&1");
            if (out == null || out.length() == 0) return;
            String curDev = null;
            int mx = 0, my = 0;
            for (String line : out.split("\n")) {
                String t = line.trim();
                if (t.startsWith("add device")) {
                    curDev = null;
                    for (String tk : t.split(" ")) if (tk.startsWith("/dev/input/")) { curDev = tk; break; }
                    mx = 0; my = 0;
                } else if (dev.equals(curDev)) {
                    // 注意：getevent 输出中 POSITION_X/Y 在 TRACKING_ID 之前，直接按前缀匹配即可
                    if (t.startsWith("ABS_MT_POSITION_X")) mx = adbPinchParseMax(t);
                    else if (t.startsWith("ABS_MT_POSITION_Y")) my = adbPinchParseMax(t);
                    else if (t.contains("ABS_MT_SLOT")) adbPinchProto = "B";
                    else if (t.contains("ABS_MT_TRACKING_ID")) adbPinchProto = "B";
                    else if (t.contains("ABS_MT_PRESSURE") && "B".equals(adbPinchProto)) { }
                }
                if (dev.equals(curDev) && mx > 0 && my > 0) {
                    adbPinchMaxX = mx; adbPinchMaxY = my;
                    log("adb pinch range read: " + dev + " Xmax=" + mx + " Ymax=" + my + " proto=" + adbPinchProto);
                    return;
                }
            }
            log("adb pinch range not found, keep " + adbPinchMaxX + "x" + adbPinchMaxY + " proto=" + adbPinchProto);
        } catch (Throwable e) { log("adb pinch readRange err " + e); }
    }

    private boolean adbPinchIsDirect(String prop) {
        try {
            if (prop != null && prop.contains("PROP=")) {
                String v = prop.substring(prop.indexOf("PROP=") + 5).trim();
                int pv = Integer.parseInt(v, 16);
                return (pv & 2) != 0; // INPUT_PROP_DIRECT = 触摸屏
            }
        } catch (Exception e) { }
        return false;
    }

    private boolean adbPinchNameTouch(String name) {
        String n = name.toLowerCase();
        return n.contains("touch") || n.contains("ts") || n.contains("goodix")
                || n.contains("synaptics") || n.contains("ft5") || n.contains("focal")
                || n.contains("rmi") || n.contains("ist30");
    }

    private int adbPinchX(int px) { return (int) ((long) px * adbPinchMaxX / Math.max(1, screenW - 1)); }
    private int adbPinchY(int py) { return (int) ((long) py * adbPinchMaxY / Math.max(1, screenH - 1)); }

    private void adbPinch(double scale) {
        adbPinchInitDev();
        if (adbPinchDev == null) { log("adb pinch: no touch dev"); return; }
        if (adbDragDown) {
            adbInput("input motionevent UP " + curX + " " + curY);
            adbDragDown = false; adbLongDownAt = 0;
        }
        // 用常驻 root shell（suOpen 幂等）：命令写 stdin 逐条执行，高频 MOVE 不卡
        adbPinchCheckShell();
        if (!adbPinchShellOk && !AdbShell.suOpen()) { log("adb pinch: suOpen failed"); return; }
        if (!adbPinchOn) {
            // 首帧：DOWN 两指（准星两侧水平，初始半间距 150px=间距 300px，浏览器才认缩放手势）
            adbPinchCx = Math.max(250, Math.min(screenW - 250, curX));
            adbPinchCy = Math.max(0, Math.min(screenH - 1, curY));
            adbPinchR0 = 200; adbPinchCurR = 200;
            int dx1 = adbPinchX(adbPinchCx - 200), dy1 = adbPinchY(adbPinchCy);
            int dx2 = adbPinchX(adbPinchCx + 200), dy2 = adbPinchY(adbPinchCy);
            if ("A".equals(adbPinchProto)) {
                // 协议 A：BTN_TOUCH + 直接报 X/Y（无 SLOT/TRACKING_ID）
                adbPinchCmd("sendevent " + adbPinchDev + " 1 330 1");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + dx1);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + dy1);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + dx2);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + dy2);
                adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
            } else {
                // 协议 B：BTN_TOUCH + SLOT0(TRACKING/X/Y/TOUCH/TOOL/PRESSURE) + SLOT1(...) + 一次 SYN
                // （两指必须在同一个 SYN 帧内，InputReader 才会判定为"同时双指"）
                int id0 = adbPinchTrackId, id1 = adbPinchTrackId + 1;
                adbPinchTrackId += 2;
                adbPinchCmd("sendevent " + adbPinchDev + " 1 330 1");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 47 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 57 " + id0);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + dx1);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + dy1);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 48 20");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 52 20");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 55 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 58 50");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 47 1");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 57 " + id1);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + dx2);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + dy2);
                adbPinchCmd("sendevent " + adbPinchDev + " 3 48 20");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 52 20");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 55 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 58 50");
                adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
            }
            adbPinchOn = true;
            log("adb pinch sendevent DOWN dev=" + adbPinchDev + " proto=" + adbPinchProto
                    + " (" + dx1 + "," + dy1 + ")(" + dx2 + "," + dy2 + ")");
            adbPinchFlush();
            // 立即注入一帧预备 MOVE（同间距），让浏览器尽快确认双指手势，减少缩放响应延迟
            adbPinchInjectMove(200);
            // 启动后台补帧线程：从当前间距逐步追到目标间距
            synchronized (adbPinchLock) {
                adbPinchTargetR = adbPinchR0 * scale;
            }
            adbPinchStartFrames();
        } else {
            // 后续 scale 消息：只更新目标间距，补帧线程自动追赶
            double maxR = Math.min(adbPinchCx, screenW - adbPinchCx);
            double t = adbPinchR0 * scale;
            t = Math.max(20, Math.min(t, maxR));
            synchronized (adbPinchLock) {
                adbPinchTargetR = t;
                adbPinchStartFrames();
            }
        }
    }

    // ---------------- 补帧线程：25ms 一帧连续注入 MOVE，浏览器识别完整缩放 ----------------
    private final Object adbPinchLock = new Object();
    private double adbPinchTargetR = 200;
    private Thread adbPinchThread = null;

    private void adbPinchStartFrames() {
        synchronized (adbPinchLock) {
            if (adbPinchThread != null) return;
            adbPinchThread = new Thread(new Runnable() {
                public void run() {
                    try {
                        while (true) {
                            double cur, target;
                            synchronized (adbPinchLock) {
                                cur = adbPinchCurR;
                                target = adbPinchTargetR;
                            }
                            double diff = target - cur;
                            if (Math.abs(diff) < 1) {
                                synchronized (adbPinchLock) { adbPinchThread = null; }
                                return;
                            }
                            double step = Math.signum(diff) * 40; // 每帧步进 40px（反应快）
                            double next = cur + step;
                            if (Math.abs(target - next) < 40) next = target;
                            adbPinchInjectMove(next);
                            synchronized (adbPinchLock) { adbPinchCurR = next; }
                            Thread.sleep(15); // 15ms 一帧 ≈ 66Hz
                        }
                    } catch (Throwable e) {
                        synchronized (adbPinchLock) { adbPinchThread = null; }
                    }
                }
            });
            adbPinchThread.setDaemon(true);
            adbPinchThread.start();
        }
    }

    /** 注入一帧 MOVE：两指对称移动到半间距 r（同帧 SYN） */
    private void adbPinchInjectMove(double r) {
        int nx1 = (int) (adbPinchCx - r), ny1 = adbPinchCy;
        int nx2 = (int) (adbPinchCx + r), ny2 = adbPinchCy;
        if ("A".equals(adbPinchProto)) {
            adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + adbPinchX(nx1));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + adbPinchY(ny1));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + adbPinchX(nx2));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + adbPinchY(ny2));
            adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
        } else {
            // MOVE：SLOT0(X/Y) + SLOT1(X/Y) + 一次 SYN（保持同一帧双指）
            adbPinchCmd("sendevent " + adbPinchDev + " 3 47 0");
            adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + adbPinchX(nx1));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + adbPinchY(ny1));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 48 20");
            adbPinchCmd("sendevent " + adbPinchDev + " 3 58 50");
            adbPinchCmd("sendevent " + adbPinchDev + " 3 47 1");
            adbPinchCmd("sendevent " + adbPinchDev + " 3 53 " + adbPinchX(nx2));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 54 " + adbPinchY(ny2));
            adbPinchCmd("sendevent " + adbPinchDev + " 3 48 20");
            adbPinchCmd("sendevent " + adbPinchDev + " 3 58 50");
            adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
        }
        adbPinchFlush();
    }

    private void adbPinchEnd() {
        // 停补帧线程：目标置为当前值让其自然退出，最多等 120ms
        synchronized (adbPinchLock) {
            adbPinchTargetR = adbPinchCurR;
            Thread t = adbPinchThread;
            adbPinchThread = null;
            if (t != null && t.isAlive()) {
                try { t.join(120); } catch (InterruptedException ie) { }
            }
        }
        if (adbPinchDev != null && (adbPinchShellOk || AdbShell.suOpen())) {
            if ("A".equals(adbPinchProto)) {
                adbPinchCmd("sendevent " + adbPinchDev + " 1 330 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
            } else {
                // UP：BTN_TOUCH 抬起 + SLOT0(TRACKING=-1) + SLOT1(TRACKING=-1) + 一次 SYN
                adbPinchCmd("sendevent " + adbPinchDev + " 1 330 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 47 0");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 57 -1");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 47 1");
                adbPinchCmd("sendevent " + adbPinchDev + " 3 57 -1");
                adbPinchCmd("sendevent " + adbPinchDev + " 0 0 0");
            }
        }
        adbPinchOn = false;
        adbPinchR0 = 200; adbPinchCurR = 200;
        adbPinchFlush();
        log("adb pinch sendevent UP proto=" + adbPinchProto);
    }

    // ---------------- 媒体音量控制（不依赖注入模式，直接调系统 AudioManager） ----------------

    /** 音量操作：up/down/mute */
    public void volume(final String op) {
        h.post(new Runnable() {
            public void run() {
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    if (am == null) return;
                    if ("up".equals(op)) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0);
                    } else if ("down".equals(op)) {
                        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0);
                    } else if ("mute".equals(op)) {
                        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                        if (cur == 0) am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0);
                        else am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0);
                    }
                    log("volume " + op);
                } catch (Throwable e) { log("volume err " + e); }
            }
        });
    }

    /** 当前媒体音量 JSON：{"v":当前,"max":最大}（供控制页显示） */
    public String volumeJson() {
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am == null) return "{\"v\":-1,\"max\":-1}";
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int v = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            return "{\"v\":" + v + ",\"max\":" + max + "}";
        } catch (Throwable e) { return "{\"v\":-1,\"max\":-1}"; }
    }

    // ---------------- 兼容旧协议（press/dragto/dragend/dragup，新版 mobile 不再使用） ----------------
    /** 兼容：只记录锚点 */
    public void press() {
        h.post(new Runnable() {
            public void run() {
                pressX = curX;
                pressY = curY;
                dragBaseX = -1;
                dragBaseY = -1;
                log("press anchor " + pressX + "," + pressY + " (cursor)");
            }
        });
    }

    /** 兼容：连续拖拽 */
    public void dragto(double x, double y) {
        h.post(new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT < 24 || pressX < 0) return;
                double fx = Math.max(0, Math.min(1000, x));
                double fy = Math.max(0, Math.min(1000, y));
                if (dragBaseX < 0) { dragBaseX = fx; dragBaseY = fy; }
                int tx = (int) (pressX + (fx - dragBaseX) * screenW / 1000);
                int ty = (int) (pressY + (fy - dragBaseY) * screenH / 1000);
                tx = Math.max(0, Math.min(screenW - 1, tx));
                ty = Math.max(0, Math.min(screenH - 1, ty));
                try {
                    Path p = new Path();
                    p.moveTo(pressX, pressY);
                    p.lineTo(tx, ty);
                    GestureDescription.Builder gb = new GestureDescription.Builder();
                    gb.addStroke(new GestureDescription.StrokeDescription(p, 0, 200));
                    boolean sent = dispatchGesture(gb.build(), null, null);
                    log("dragto " + pressX + "," + pressY + " -> " + tx + "," + ty + " sent=" + sent);
                    pressX = tx;
                    pressY = ty;
                } catch (Throwable e) { log("dragto err " + e); }
            }
        });
    }

    /** 兼容：拖拽结束 */
    public void dragend(double x, double y) {
        h.post(new Runnable() {
            public void run() {
                if (adbMode()) {
                    if (adbDragDown) {
                        adbInput("input motionevent UP " + curX + " " + curY);
                        adbDragDown = false;
                        adbLongDownAt = 0;
                        log("adb drag UP " + curX + "," + curY);
                    }
                    return;
                }
                pressX = -1;
                pressY = -1;
                dragBaseX = -1;
                dragBaseY = -1;
                log("dragend (legacy)");
            }
        });
    }

    /** 兼容：清除长按起点 */
    public void dragup() {
        h.post(new Runnable() {
            public void run() {
                pressX = -1;
                pressY = -1;
                dragBaseX = -1;
                dragBaseY = -1;
                log("dragup (legacy)");
            }
        });
    }

    /** 向获焦输入框注入文字（API 21+），成功返回 true
     *  追加模式：先读取输入框现有内容再拼接，避免多次发送时覆盖前面的字 */
    public boolean setText(String t) {
        if (Build.VERSION.SDK_INT < 21) return false;
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Throwable ignored) { }
        if (root == null) return false;
        AccessibilityNodeInfo e = findEditable(root);
        if (e == null) { root.recycle(); return false; }
        CharSequence old = e.getText();
        String combined = (old == null ? "" : old.toString()) + t;
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, combined);
        boolean ok = e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b);
        e.recycle();
        root.recycle();
        return ok;
    }

    // ---------------- 光标图案 ----------------
    // 十字准星 + 中心圆点：命中点=图形正中心，所见即所得
    //（之前是箭头且尖端在左上角，用户把箭头身体对准目标时实际点击偏上，小控件点不中）
    private Bitmap cursorBitmap() {
        Bitmap b = Bitmap.createBitmap(CUR, CUR, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int cx = CUR / 2, cy = CUR / 2;
        Paint fill = new Paint();
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        fill.setAntiAlias(true);
        Paint stroke = new Paint();
        stroke.setColor(Color.BLACK);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(3);
        stroke.setAntiAlias(true);
        // 十字线：横线 + 竖线（带缺口，中心点让位给圆点）
        c.drawLine(cx - CUR / 2 + 3, cy, cx - 5, cy, fill);
        c.drawLine(cx + 5, cy, cx + CUR / 2 - 3, cy, fill);
        c.drawLine(cx, cy - CUR / 2 + 3, cx, cy - 5, fill);
        c.drawLine(cx, cy + 5, cx, cy + CUR / 2 - 3, fill);
        c.drawLine(cx - CUR / 2 + 3, cy, cx - 5, cy, stroke);
        c.drawLine(cx + 5, cy, cx + CUR / 2 - 3, cy, stroke);
        c.drawLine(cx, cy - CUR / 2 + 3, cx, cy - 5, stroke);
        c.drawLine(cx, cy + 5, cx, cy + CUR / 2 - 3, stroke);
        // 中心圆点（命中点指示）
        c.drawCircle(cx, cy, 4, fill);
        c.drawCircle(cx, cy, 4, stroke);
        return b;
    }

    private static String stack(Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
