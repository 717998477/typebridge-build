package com.cdhwan.wq;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.SharedPreferences;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.Intent;
import android.os.IBinder;
import rikka.shizuku.Shizuku;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Build;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 主界面：显示连接码 / 手机访问地址 / 二维码 / 输入法启用指引 */
public class MainActivity extends Activity {
    private TextView statusTv, codeTv, ipTv;
    private ImageView qrView;
    private Button logToggle, modeBtn;
    private android.os.Handler uiRefresh = new android.os.Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        CrashLog.install(this);
        App.load(this);
        HttpServer.start(this);
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        // 最近任务隐藏开关开启时，每次回到前台都重新应用（防止任务被重新加入最近列表）
        if (getSharedPreferences("tb_settings", MODE_PRIVATE).getBoolean("hide_recents", false)) {
            applyHideRecents(true, false);
        }
    }

    private void buildUi() {
        // 整体可滚动：内容超出屏幕时遥控可继续下按，二维码不再被截断
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Color.parseColor("#0F1B2D"));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(56));

        // 正文不再显示副标题（标题栏显示应用名"CDH远程输入法"）
        statusTv = new TextView(this);
        statusTv.setTextColor(Color.parseColor("#9BB6D8"));
        statusTv.setTextSize(14);
        statusTv.setGravity(Gravity.CENTER);
        root.addView(statusTv, lp());

        TextView codeLbl = new TextView(this);
        codeLbl.setText("连接码");
        codeLbl.setTextColor(Color.parseColor("#9BB6D8"));
        codeLbl.setTextSize(13);
        codeLbl.setGravity(Gravity.CENTER);
        root.addView(codeLbl, lp());

        codeTv = new TextView(this);
        codeTv.setTextColor(Color.parseColor("#4FC3F7"));
        codeTv.setTextSize(46);
        codeTv.setGravity(Gravity.CENTER);
        codeTv.setTypeface(Typeface.MONOSPACE);
        root.addView(codeTv, lp());

        // 电视遥控上下键导航：所有按钮纵向排布，每个按钮独占一行
        Button regen = new Button(this);
        regen.setText("重新生成连接码");
        regen.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                App.newCode();
                refresh();
            }
        });
        root.addView(regen, lp());

        Button setCode = new Button(this);
        setCode.setText("连接码设置…");
        setCode.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showCodeDialog(); }
        });
        root.addView(setCode, lp());

        Button setPort = new Button(this);
        setPort.setText("端口设置…");
        setPort.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showPortDialog(); }
        });
        root.addView(setPort, lp());

        Button enable = new Button(this);
        enable.setText("启用 / 切换输入法");
        enable.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });
        root.addView(enable, lp());

        Button cursorBtn = new Button(this);
        cursorBtn.setText("开启CDH远程输入法（无障碍）");
        cursorBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            }
        });
        root.addView(cursorBtn, lp());

        // 日志开关：打开才记录日志，关闭不记录（默认关闭，按钮文本按实际状态显示）
        logToggle = new Button(this);
        logToggle.setText(TbLog.loggingEnabled
                ? "日志开关：记录中（点此关闭）" : "日志开关：已关闭（点此开启）");
        logToggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                TbLog.init(MainActivity.this);
                TbLog.loggingEnabled = !TbLog.loggingEnabled;
                if (TbLog.loggingEnabled) {
                    // 开启时顺带写一条自检快照，方便查看当前状态
                    StringBuilder sb = new StringBuilder();
                    sb.append("--- log switch ON, selftest ").append(System.currentTimeMillis()).append(" ---\n");
                    sb.append("API=").append(android.os.Build.VERSION.SDK_INT)
                            .append(" model=").append(android.os.Build.MODEL).append("\n");
                    sb.append("cursorService=").append(CursorService.inst != null ? "alive" : "NULL（无障碍未生效）").append("\n");
                    if (CursorService.inst != null) sb.append(CursorService.inst.state()).append("\n");
                    TbLog.w(sb.toString());
                    logToggle.setText("日志开关：记录中（点此关闭）");
                    Toast.makeText(MainActivity.this,
                            "日志记录已开启\n手机浏览器打开 http://IP:" + App.port + "/log 查看全部日志",
                            Toast.LENGTH_SHORT).show();
                } else {
                    logToggle.setText("日志开关：已关闭（点此开启）");
                    Toast.makeText(MainActivity.this, "日志记录已关闭", Toast.LENGTH_SHORT).show();
                }
            }
        });
        root.addView(logToggle, lp());

        // 最近任务列表隐藏开关（Android 5.0+ 才支持从最近任务动态移除）
        final SharedPreferences prefs = getSharedPreferences("tb_settings", MODE_PRIVATE);
        final Button hideRecents = new Button(this);
        final boolean hideNow = prefs.getBoolean("hide_recents", false);
        hideRecents.setText(hideNow ? "最近任务隐藏：开（点此关闭）" : "最近任务隐藏：关（点此开启）");
        hideRecents.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                boolean now = !prefs.getBoolean("hide_recents", false);
                prefs.edit().putBoolean("hide_recents", now).apply();
                applyHideRecents(now, true);
                hideRecents.setText(now ? "最近任务隐藏：开（点此关闭）" : "最近任务隐藏：关（点此开启）");
            }
        });
        root.addView(hideRecents, lp());

        // 输入模式开关：无障碍 / ADB(Shizuku)
        modeBtn = new Button(this);
        final String[] mode = { prefs.getString("input_mode", "accessibility") };
        modeBtn.setText(mode[0].equals("adb")
                ? "输入模式：ADB(Shizuku)（点此切无障碍）"
                : "输入模式：无障碍（点此切 ADB）");
        modeBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (mode[0].equals("adb")) {
                    // 切回无障碍
                    mode[0] = "accessibility";
                    prefs.edit().putString("input_mode", "accessibility").apply();
                    modeBtn.setText("输入模式：无障碍（点此切 ADB）");
                    Toast.makeText(MainActivity.this, "已切换：无障碍注入模式", Toast.LENGTH_SHORT).show();
                    return;
                }
                // 切到 ADB 注入模式：Root 直连（su）优先，无 root 才走 Shizuku
                String chan = "Shizuku";
                try {
                    if (AdbShell.haveSu()) {
                        chan = "Root直连(su)";
                    } else if (Shizuku.pingBinder()) {
                        if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            Shizuku.requestPermission(0);
                            Toast.makeText(MainActivity.this,
                                    "请在 Shizuku 授权弹窗中允许「远程输入法」使用权限",
                                    Toast.LENGTH_LONG).show();
                            return; // 授权回调里再切
                        }
                        bindShellService();
                    } else {
                        Toast.makeText(MainActivity.this,
                                "未检测到可用通道：设备无 root 且未安装/启动 Shizuku（需无线调试配对一次）",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                } catch (Throwable e) {
                    Toast.makeText(MainActivity.this, "ADB 通道检查失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                    return;
                }
                mode[0] = "adb";
                prefs.edit().putString("input_mode", "adb").apply();
                modeBtn.setText("输入模式：ADB(" + chan + ")（点此切无障碍）");
                Toast.makeText(MainActivity.this, "已切换：ADB 注入模式（" + chan + "）", Toast.LENGTH_SHORT).show();
            }
        });
        root.addView(modeBtn, lp());

        // Shizuku 授权结果回调：授权成功后自动绑定并切换到 ADB 模式
        try {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        bindShellService();
                        prefs.edit().putString("input_mode", "adb").apply();
                        modeBtn.setText("输入模式：ADB(Shizuku)（点此切无障碍）");
                        Toast.makeText(MainActivity.this, "Shizuku 已授权，已切换 ADB 模式", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (Throwable ignored) { }

        ipTv = new TextView(this);
        ipTv.setTextColor(Color.WHITE);
        ipTv.setTextSize(15);
        ipTv.setGravity(Gravity.CENTER);
        ipTv.setFocusable(true);   // 遥控可聚焦，ScrollView 随之自动滚动到可见
        root.addView(ipTv, lp());

        qrView = new ImageView(this);
        qrView.setFocusable(true); // 遥控可聚焦，ScrollView 自动滚出完整二维码
        root.addView(qrView, lp(dp(340), dp(340)));

        TextView hint = new TextView(this);
        hint.setText("使用方法：\n1. 手机浏览器打开上方任一地址，输入连接码；\n2. 鼠标：先到系统「无障碍」里开启「CDH远程输入法」，手机控制页切到「鼠标遥控」标签即可移动/点击/滚动；\n3. 键盘：在电视输入框里把输入法切到「CDH远程输入法」（或直接点中输入框），手机「键盘输入」标签打字即可。\n\n提示：连接码与端口可点上方按钮自定义（连接码 1-30 位英文/数字）。");
        hint.setTextColor(Color.parseColor("#9BB6D8"));
        hint.setTextSize(13);
        root.addView(hint, lp());

        sv.addView(root);
        setContentView(sv);

        // 网页端可能通过 /api/log、/api/mode 改了状态：定时刷新按钮显示，与网页保持同步
        uiRefresh.postDelayed(refreshRunnable, 1000);
    }

    /** 网页端/主界面双向同步：按实际状态刷新日志、模式按钮文本 */
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshUi();
            uiRefresh.postDelayed(this, 2000);
        }
    };

    private void refreshUi() {
        try {
            if (logToggle != null) {
                String want = TbLog.loggingEnabled ? "日志开关：记录中（点此关闭）" : "日志开关：已关闭（点此开启）";
                if (!want.equals(logToggle.getText().toString())) logToggle.setText(want);
            }
            if (modeBtn != null) {
                String m = getSharedPreferences("tb_settings", MODE_PRIVATE).getString("input_mode", "accessibility");
                String cur = modeBtn.getText().toString();
                boolean curAdb = cur.contains("输入模式：ADB");
                if (m.equals("adb") && !curAdb) {
                    modeBtn.setText("输入模式：ADB(Shizuku)（点此切无障碍）");
                } else if (!m.equals("adb") && curAdb) {
                    modeBtn.setText("输入模式：无障碍（点此切 ADB）");
                }
            }
        } catch (Throwable ignored) { }
    }

    /** dp -> px */
    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /** 连接码设置对话框（1-30 位，仅英文+数字） */
    private void showCodeDialog() {
        final EditText et = new EditText(this);
        et.setText(App.code);
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        et.setFocusableInTouchMode(true);
        et.selectAll();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置连接码")
                .setMessage("1-30 位，仅英文和数字")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        String s = et.getText().toString().trim();
                        if (!App.validCode(s)) {
                            Toast.makeText(MainActivity.this, "连接码需 1-30 位英文/数字", Toast.LENGTH_LONG).show();
                            return;
                        }
                        App.saveCode(s);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                et.requestFocus();
                if (d instanceof AlertDialog)
                    ((AlertDialog) d).getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        d.show();
    }

    /** 端口设置对话框（1-65535） */
    private void showPortDialog() {
        final EditText et = new EditText(this);
        et.setText(String.valueOf(App.port));
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_NUMBER);
        et.setFocusableInTouchMode(true);
        et.selectAll();
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("设置端口")
                .setMessage("1-65535，修改后服务自动重启")
                .setView(et)
                .setPositiveButton("确定", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        int p;
                        try { p = Integer.parseInt(et.getText().toString().trim()); }
                        catch (NumberFormatException e) { p = 0; }
                        if (!App.validPort(p)) {
                            Toast.makeText(MainActivity.this, "端口需在 1-65535 之间", Toast.LENGTH_LONG).show();
                            return;
                        }
                        App.savePort(p);
                        HttpServer.start(MainActivity.this);
                        refresh();
                    }
                })
                .setNegativeButton("取消", null)
                .create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface d) {
                et.requestFocus();
                if (d instanceof AlertDialog)
                    ((AlertDialog) d).getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        });
        d.show();
    }

    private LinearLayout.LayoutParams lp() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    /** 绑定 Shizuku ShellService：之后 ADB 模式的注入命令走 AdbShell.exec */
    private void bindShellService() {
        try {
            if (AdbShell.ready()) return;
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(
                    new ComponentName(this, ShellService.class))
                    .daemon(true)
                    .processNameSuffix("shizuku_shell")
                    .debuggable(false)
                    .version(1);
            Shizuku.bindUserService(args, new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    AdbShell.binder = service;
                    Toast.makeText(MainActivity.this, "Shizuku shell 已连接（ADB 注入就绪）", Toast.LENGTH_SHORT).show();
                }
                @Override public void onServiceDisconnected(ComponentName name) {
                    AdbShell.binder = null;
                }
            });
        } catch (Throwable e) {
            Toast.makeText(this, "绑定 Shizuku 失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** 最近任务列表隐藏/恢复：遍历本应用的所有 task 设置 excludeFromRecents */
    private void applyHideRecents(boolean hide, boolean showToast) {
        if (Build.VERSION.SDK_INT < 21) {
            if (showToast) Toast.makeText(this, "最近任务隐藏仅支持 Android 5.0+", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            java.util.List<ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks != null) {
                for (ActivityManager.AppTask t : tasks) t.setExcludeFromRecents(hide);
            }
            if (showToast) Toast.makeText(this, hide ? "已从最近任务隐藏" : "已恢复显示在最近任务", Toast.LENGTH_SHORT).show();
        } catch (Throwable e) {
            if (showToast) Toast.makeText(this, "设置失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refresh() {
        try {
            HttpServer.start(this);
            List<String> ips = App.ips();
            codeTv.setText(App.code);
            statusTv.setText((App.serverUp ? "服务运行中 · 端口 " + App.port : "服务启动失败（端口被占用？）")
                    + " · 鼠标：" + (CursorService.inst != null ? "已开启" : "未开启"));
            StringBuilder sb = new StringBuilder("手机访问地址（任一地址）：");
            if (ips.isEmpty()) {
                sb.append("\n（未获取到 IP，请检查网络连接）");
            } else {
                for (String ip : ips)
                    sb.append("\nhttp://").append(ip).append(":").append(App.port).append("/m?code=").append(App.code);
            }
            ipTv.setText(sb.toString());
            if (!ips.isEmpty()) qrView.setImageBitmap(Qr.qr("http://" + ips.get(0) + ":" + App.port + "/m?code=" + App.code, 512));
            else qrView.setImageBitmap(null);
        } catch (Throwable t) {
            // 刷新异常不崩应用：显示提示 + 日志落盘
            CrashLog.dump(this, Thread.currentThread(), t);
            statusTv.setText("界面刷新异常：" + t);
        }
    }
}
