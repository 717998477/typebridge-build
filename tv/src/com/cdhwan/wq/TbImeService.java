package com.cdhwan.wq;

import android.graphics.Bitmap;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.KeyEvent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * CDH远程输入法：本身不弹键盘，接收手机发来的文字/按键，
 * 直接写入当前输入框（commitText / deleteSurroundingText）。
 * 状态条显示：就绪提示 + 手机访问网址 + 二维码。
 */
public class TbImeService extends InputMethodService {
    private LinearLayout view;
    private TextView status;
    private TextView urlTv;
    private ImageView qrView;

    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
        App.ime = this;
        HttpServer.start(this);
    }

    @Override
    public View onCreateInputView() {
        view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        view.setBackgroundColor(0xFFF2F5F9);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xFF222222);
        status.setGravity(Gravity.CENTER);
        view.addView(status, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        urlTv = new TextView(this);
        urlTv.setTextSize(13);
        urlTv.setTextColor(0xFF185FA5);
        urlTv.setGravity(Gravity.CENTER);
        urlTv.setPadding(0, dp(6), 0, dp(4));
        view.addView(urlTv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        qrView = new ImageView(this);
        view.addView(qrView, new LinearLayout.LayoutParams(dp(180), dp(180)));

        updateStatus();
        return view;
    }

    @Override
    public boolean onEvaluateInputViewShown() {
        return true;   // TV 上始终显示状态条（即使用户未点输入框也保持可见）
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // 不弹软键盘，仅保留状态条
        updateStatus();
    }

    /**
     * 状态条刷新：就绪/已连接文案 + 网址 + 二维码。
     * 可能被 HTTP 服务线程调用（手机连接/断开时），所有 UI 更新必须切回主线程。
     */
    public void updateStatus() {
        if (view == null) return;
        String ip = null;
        List<String> ips = App.ips();
        if (!ips.isEmpty()) ip = ips.get(0);

        final int clients = App.clients;
        final String code = App.code;
        final int port = App.port;
        final String host = ip;
        final Bitmap b = (ip == null) ? null : Qr.qr("http://" + ip + ":" + port + "/m?code=" + code, 360);

        final Handler main = new Handler(Looper.getMainLooper());
        main.post(new Runnable() {
            @Override public void run() {
                if (view == null) return;
                if (clients > 0) {
                    status.setText("CDH · 手机已连接（" + clients + " 台）· 发送文字将直接进入当前输入框");
                } else {
                    status.setText("CDH远程输入法已就绪，请用手机浏览器打开网址或扫描二维码");
                }
                if (host == null) {
                    urlTv.setText("（未获取到 IP，请检查网络连接）");
                    qrView.setVisibility(View.GONE);
                } else {
                    urlTv.setText("http://" + host + ":" + port + "/m?code=" + code);
                    qrView.setImageBitmap(b);
                    qrView.setVisibility(b == null ? View.GONE : View.VISIBLE);
                }
            }
        });
    }

    public void injectText(String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.commitText(text, 1);
    }

    public void injectEnter() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
        ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
    }

    public void injectBackspace(int count) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || count <= 0) return;
        ic.deleteSurroundingText(count, 0);
    }

    /** dp -> px */
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
