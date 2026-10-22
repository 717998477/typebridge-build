// ============================================================
// TypeBridge v0.4.1 —— 电脑端主程序
// 手机浏览器打字 → 局域网/虚拟网 WebSocket → 本程序注入电脑前台输入框
// 语法：C# 5（系统自带 csc.exe 编译，禁用 C#6+ 语法）
// 编译：build\build_stage1.bat （/target:winexe 纯托盘，无控制台窗口）
// 运行：build\TypeBridge.exe   双击即用：自动弹浏览器显示扫码二维码，
//       右下角托盘常驻（右键：链接二维码 / 退出），日志写本地文件
//
// v0.4 新增：
//   - 端口自定义：托盘「端口设置…」/ 命令行 /port:8899 / 配置文件 config.json，重启生效
//   - EasyTier 远程组网支持：检测 EasyTier 虚拟网卡，配对页列出全部网卡地址，
//     每张地址一个独立二维码；电脑手机装 EasyTier 加入同一虚拟网后，
//     即使不在同一 WiFi 也能跨公网使用（服务监听 0.0.0.0，无需改代码）
//   - 配对防爆破：同一来源 IP 连续 5 次配对失败，拉黑 10 分钟
//   - 开机自启只在首次运行时询问一次（托盘菜单可随时开关）
//   - 托盘菜单：端口设置 / 查看连接地址 / 关于（版本号+项目主页）
//   - 手机端 WS 保活 ping，降低 NAT/运营商空闲断连概率
//
// v0.4.1（修复配对窗口空白/静默退出）：
//   - 全局异常捕获：任何未处理异常写日志 + 弹窗提示，不再静默消失
//   - 配对窗口内嵌浏览器改连 http://127.0.0.1:端口/pair（避免 localhost/代理干扰），
//     8 秒未加载完成自动用系统浏览器打开，顶栏加「用浏览器打开」按钮
//   - 服务端每次生成配对页写日志；accept 循环异常自动续跑
//   - 启动时记录 OS / .NET 版本，便于排查
//
// v0.4.2（修复 .NET 4.0 兼容性崩溃）：
//   - String.Split(char) 是 .NET 4.5 才有的重载，在 .NET 4.0 机器上会导致
//     MissingMethodException 崩溃（服务端首次响应配对页时 JIT 失败、进程退出）。
//     全部改为 Split(new char[]{...})，并统一用 -sdk:4.0 编译，保证最低兼容 .NET 4.0
//
// v0.4.3（修复中文输入变成小格子·初版）：
//   - 新增注入模式框架（托盘「注入模式」切换，config 持久化）：标准 / 兼容 / 剪贴板。
//     剪贴板模式走 Ctrl+V 粘贴（字符 100% 保真，注入后自动恢复原剪贴板）。
//
// v0.4.5（自定义连接码 + 移除剪贴板模式）：
//   - 连接码从随机 4 位数字改为自定义：仅英文+数字、1-30 位，默认 cdhwan；
//     托盘「连接码设置…」修改，config.json 的 code 字段持久化，立即生效无需重启；
//     配对校验不区分大小写；手机端手动输入框同步支持字母。
//   - 移除剪贴板注入模式（托盘只剩 标准 / 智能 两种）。
//
// v0.4.6（模拟鼠标滚轮）：
//   - 手机控制页新增「滚轮」开关：开启后手指上下滑动 = 模拟鼠标滚轮，
//     注入真实滚轮事件滚动电脑前台窗口（浏览器/文档/列表），
//     上滑=向下看（+120），下滑=向上看（-120），每 60px 滚一格。
//
// v0.4.7（手机=笔记本触摸板）：
//   - 控制页新增「鼠标」标签：手机整屏变成触摸板——
//     单指滑动=移动光标、轻点=左键单击、长按=按住左键拖动、
//     双指轻点=右键、双指上下滑=滚轮（同 v0.4.6），像笔记本触摸板一样。
//
// v0.4.8（触摸板三键版）：
//   - 键盘页去掉「滚轮」开关；触摸板页底部改为 左键/滚轮/右键 三键：
//     左/右键轻点=单击、长按=锁定按住（再按解除）、滚轮键=开关滚轮模式（滑动=滚轮）。
//   - 触控板平时只移动光标；移除全部双指手势（手机浏览器双指触发自身手势会卡顿/闪退）。
//
// v0.4.9（触摸板缩放键）：
//   - 底部三键改为四键：左键/滚轮/缩放/右键；「缩放」键=开关缩放模式，
//     缩放模式下触摸板上滑=放大（Ctrl+滚轮向前）、下滑=缩小；与滚轮模式互斥。
//
// v0.4.10（缩放 5%/格 + 消息节流）：
//   - 缩放每格只注入 1/4 格（30 ≈ 5%），轻微滑动不再一下拉满；
//   - 控制端滚轮/缩放消息每 80ms 最多一条、一次最多一格，避免消息风暴导致手机浏览器卡顿闪退。
//
// 设计要点（对应方案 docs/跨屏输入工具-实施方案.md）：
//   - TcpListener 裸 Socket（坑：HttpListener 绑局域网IP要管理员，见方案8.7）
//   - 手写 RFC6455 WebSocket（SHA1 内置，零第三方库）
//   - 配对码首帧校验，未配对连接不接受任何注入指令
//   - 注入前三重校验在手机端目标不可知，此处注入当前前台（产品定义）
// ============================================================
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Net;
using Microsoft.Win32;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;

namespace TypeBridge
{
    internal static class Version
    {
        internal const string Text = "v0.4.10";
        internal const string Home = "https://gitee.com/lanyacp/TypeBridge";
    }

    // ---------------- 日志（v0.2 起写本地文件，winexe 无控制台） ----------------
    internal static class Log
    {
        private static readonly object Gate = new object();
        private static readonly string Dir =
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TypeBridge");
        private static readonly string FilePath = Path.Combine(Dir, "log.txt");

        internal static void Info(string msg)
        {
            lock (Gate)
            {
                string line = "[" + DateTime.Now.ToString("MM-dd HH:mm:ss") + "] " + msg;
                Console.WriteLine(line);
                try
                {
                    Directory.CreateDirectory(Dir);
                    FileInfo fi = new FileInfo(FilePath);
                    if (fi.Exists && fi.Length > 1024 * 1024) fi.Delete(); // 超 1MB 重开
                    using (StreamWriter w = File.AppendText(FilePath)) { w.WriteLine(line); }
                }
                catch (Exception) { }
            }
        }

        internal static void OpenFile()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                if (!File.Exists(FilePath)) File.WriteAllText(FilePath, "");
                Process.Start(FilePath);
            }
            catch (Exception) { }
        }
    }

    // ---------------- 注入引擎（阶段0已验收的同源代码） ----------------
    internal static class Inject
    {
        [StructLayout(LayoutKind.Sequential)]
        private struct KEYBDINPUT
        {
            public ushort wVk;
            public ushort wScan;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct MOUSEINPUT
        {
            public int dx;
            public int dy;
            public uint mouseData;
            public uint dwFlags;
            public uint time;
            public IntPtr dwExtraInfo;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct HARDWAREINPUT
        {
            public uint uMsg;
            public ushort wParamL;
            public ushort wParamH;
        }

        [StructLayout(LayoutKind.Explicit)]
        private struct INPUTUNION
        {
            [FieldOffset(0)] public MOUSEINPUT mi;
            [FieldOffset(0)] public KEYBDINPUT ki;
            [FieldOffset(0)] public HARDWAREINPUT hi;
        }

        [StructLayout(LayoutKind.Sequential)]
        private struct INPUT
        {
            public uint type;
            public INPUTUNION u;
        }

        private const uint INPUT_KEYBOARD = 1;
        private const uint INPUT_MOUSE = 0;
        private const uint MOUSEEVENTF_MOVE = 0x0001;
        private const uint MOUSEEVENTF_LEFTDOWN = 0x0002;
        private const uint MOUSEEVENTF_LEFTUP = 0x0004;
        private const uint MOUSEEVENTF_RIGHTDOWN = 0x0008;
        private const uint MOUSEEVENTF_RIGHTUP = 0x0010;
        private const uint MOUSEEVENTF_WHEEL = 0x0800;
        private const int WHEEL_DELTA = 120;
        private const uint KEYEVENTF_KEYUP = 0x0002;
        private const uint KEYEVENTF_UNICODE = 0x0004;
        private const ushort VK_RETURN = 0x0D;
        private const ushort VK_BACK = 0x08;
        private const ushort VK_TAB = 0x09;
        private const ushort VK_CONTROL = 0x11;
        private const uint WM_INPUTLANGCHANGEREQUEST = 0x0050;
        private const uint KLF_ACTIVATE = 0x00000001;
        private const string KLID_ZH_CN = "00000804";   // 简体中文(美式键盘)：Win8 上激活它即挂上默认中文输入法(微软拼音)

        [DllImport("user32.dll", SetLastError = true)]
        private static extern uint SendInput(uint nInputs, INPUT[] pInputs, int cbSize);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);

        [DllImport("user32.dll", CharSet = CharSet.Auto)]
        private static extern IntPtr LoadKeyboardLayout(string pwszKLID, uint Flags);

        [DllImport("user32.dll")]
        private static extern IntPtr GetKeyboardLayout(uint idThread);

        [DllImport("user32.dll")]
        private static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

        private static readonly int SizeOfInput = Marshal.SizeOf(typeof(INPUT));

        // v0.4.5：注入模式。0=标准(直接 Unicode 注入)；1=智能(注入前自动切到简体中文输入法，
        // 空闲/换窗口/退出自动恢复原布局，解决中文变格子，默认)
        internal enum InjectMode { Standard = 0, AutoLayout = 1 }

        internal static InjectMode Mode = InjectMode.AutoLayout;

        private static void AppendChar(INPUT[] arr, ref int idx, ushort code, bool keyUp)
        {
            arr[idx].type = INPUT_KEYBOARD;
            arr[idx].u.ki.wVk = 0;
            arr[idx].u.ki.wScan = code;
            arr[idx].u.ki.dwFlags = KEYEVENTF_UNICODE | (keyUp ? KEYEVENTF_KEYUP : 0);
            arr[idx].u.ki.time = 0;
            arr[idx].u.ki.dwExtraInfo = IntPtr.Zero;
            idx++;
        }

        private static INPUT[] BuildTextInputs(string text)
        {
            byte[] raw = Encoding.Unicode.GetBytes(text);
            int units = raw.Length / 2;
            INPUT[] inputs = new INPUT[units * 2];
            int idx = 0;
            for (int i = 0; i < units; i++)
            {
                ushort code = (ushort)(raw[i * 2] | (raw[i * 2 + 1] << 8));
                AppendChar(inputs, ref idx, code, false);
                AppendChar(inputs, ref idx, code, true);
            }
            return inputs;
        }

        private static INPUT[] BuildKeyInputs(ushort vk, int count)
        {
            INPUT[] inputs = new INPUT[count * 2];
            int idx = 0;
            for (int i = 0; i < count; i++)
            {
                for (int phase = 0; phase < 2; phase++)
                {
                    inputs[idx].type = INPUT_KEYBOARD;
                    inputs[idx].u.ki.wVk = vk;
                    inputs[idx].u.ki.wScan = 0;
                    inputs[idx].u.ki.dwFlags = (phase == 1 ? KEYEVENTF_KEYUP : 0);
                    inputs[idx].u.ki.time = 0;
                    inputs[idx].u.ki.dwExtraInfo = IntPtr.Zero;
                    idx++;
                }
            }
            return inputs;
        }

        private static uint SendEvents(INPUT[] inputs)
        {
            return SendInput((uint)inputs.Length, inputs, SizeOfInput);
        }

        // v0.4.5：按当前模式注入文字；返回实际发送的事件数
        internal static uint Text(string text)
        {
            if (Mode == InjectMode.AutoLayout) return AutoLayoutText(text);
            return TextUnicode(text);
        }

        // v0.4.6：模拟鼠标滚轮，滚动电脑前台窗口（浏览器/文档/列表）。
        // steps>0 = 滚轮向前（内容向下滚/看下方）；steps<0 = 滚轮向后（看上方）。一格=WHEEL_DELTA(120)。
        internal static uint Wheel(int steps)
        {
            if (steps == 0) return 0;
            if (steps > 100) steps = 100;
            if (steps < -100) steps = -100;
            INPUT[] arr = new INPUT[1];
            arr[0].type = INPUT_MOUSE;
            arr[0].u.mi.dx = 0;
            arr[0].u.mi.dy = 0;
            arr[0].u.mi.mouseData = unchecked((uint)(WHEEL_DELTA * steps)); // 负步进=二补码，SendInput 正确解释为向后滚
            arr[0].u.mi.dwFlags = MOUSEEVENTF_WHEEL;
            arr[0].u.mi.time = 0;
            arr[0].u.mi.dwExtraInfo = IntPtr.Zero;
            return SendEvents(arr);
        }

        // v0.4.7：相对移动光标（dx/dy 像素，正=右/下）。受系统鼠标加速影响，手感接近真实触摸板。
        internal static uint MouseMove(int dx, int dy)
        {
            if (dx == 0 && dy == 0) return 0;
            INPUT[] arr = new INPUT[1];
            arr[0].type = INPUT_MOUSE;
            arr[0].u.mi.dx = dx;
            arr[0].u.mi.dy = dy;
            arr[0].u.mi.mouseData = 0;
            arr[0].u.mi.dwFlags = MOUSEEVENTF_MOVE;
            arr[0].u.mi.time = 0;
            arr[0].u.mi.dwExtraInfo = IntPtr.Zero;
            return SendEvents(arr);
        }

        // v0.4.7：鼠标按键。btn="left"/"right"；down=true 按下，false 抬起。
        internal static uint MouseButton(string btn, bool down)
        {
            uint flag = btn == "right"
                ? (down ? MOUSEEVENTF_RIGHTDOWN : MOUSEEVENTF_RIGHTUP)
                : (down ? MOUSEEVENTF_LEFTDOWN : MOUSEEVENTF_LEFTUP);
            INPUT[] arr = new INPUT[1];
            arr[0].type = INPUT_MOUSE;
            arr[0].u.mi.dx = 0;
            arr[0].u.mi.dy = 0;
            arr[0].u.mi.mouseData = 0;
            arr[0].u.mi.dwFlags = flag;
            arr[0].u.mi.time = 0;
            arr[0].u.mi.dwExtraInfo = IntPtr.Zero;
            return SendEvents(arr);
        }

        // v0.4.9：Ctrl+滚轮 = 缩放（浏览器/文档/地图等）。steps>0=放大（滚轮向前），<0=缩小。
        // v0.4.10：每格只注入 1/4 格（ZOOM_DELTA=30 ≈ 5%），避免轻微滑动就拉满；Chromium 系按比例响应。
        internal static uint CtrlWheel(int steps)
        {
            if (steps == 0) return 0;
            if (steps > 20) steps = 20;
            if (steps < -20) steps = -20;
            const int ZOOM_DELTA = 30; // 120 的 1/4 ≈ 浏览器缩放 5%/格
            INPUT[] arr = new INPUT[3];
            arr[0].type = INPUT_KEYBOARD;
            arr[0].u.ki.wVk = VK_CONTROL;
            arr[0].u.ki.wScan = 0;
            arr[0].u.ki.dwFlags = 0;
            arr[0].u.ki.time = 0;
            arr[0].u.ki.dwExtraInfo = IntPtr.Zero;
            arr[1].type = INPUT_MOUSE;
            arr[1].u.mi.dx = 0;
            arr[1].u.mi.dy = 0;
            arr[1].u.mi.mouseData = unchecked((uint)(ZOOM_DELTA * steps));
            arr[1].u.mi.dwFlags = MOUSEEVENTF_WHEEL;
            arr[1].u.mi.time = 0;
            arr[1].u.mi.dwExtraInfo = IntPtr.Zero;
            arr[2].type = INPUT_KEYBOARD;
            arr[2].u.ki.wVk = VK_CONTROL;
            arr[2].u.ki.wScan = 0;
            arr[2].u.ki.dwFlags = KEYEVENTF_KEYUP;
            arr[2].u.ki.time = 0;
            arr[2].u.ki.dwExtraInfo = IntPtr.Zero;
            return SendInput(3, arr, SizeOfInput);
        }

        // v0.4.4 智能模式：部分机器在「美式键盘」等无中文输入法布局下，Unicode 注入会被改坏
        // （中文变成小格子）；切到简体中文布局（微软拼音）后注入就正常。做法：
        // 注入前把前台窗口线程布局自动切到简体中文 → 等待生效 → 注入 → 空闲 800ms /
        // 焦点切到别的窗口 / 程序退出时自动恢复原布局。用户全程无感、系统布局不留痕。
        private static long lastZhUse = long.MinValue;
        private static bool zhHeld = false;
        private static IntPtr zhHeldHwnd = IntPtr.Zero;
        private static IntPtr zhRestoreHkl = IntPtr.Zero;

        private static bool IsChineseLayout(IntPtr hkl)
        {
            if (hkl == IntPtr.Zero) return false;
            int lang = (int)((long)hkl & 0xFFFF);
            // 简体(0804)/繁体(0404)/港台(0C04)等中文语言 ID（微软拼音的 HKL 低位也是 0804）
            return lang == 0x0804 || lang == 0x0404 || lang == 0x0C04;
        }

        internal static void RestoreNow()
        {
            if (!zhHeld) return;
            try
            {
                if (zhHeldHwnd != IntPtr.Zero && zhRestoreHkl != IntPtr.Zero)
                    PostMessage(zhHeldHwnd, WM_INPUTLANGCHANGEREQUEST, IntPtr.Zero, zhRestoreHkl);
            }
            catch (Exception) { }
            zhHeld = false;
        }

        private static uint AutoLayoutText(string text)
        {
            IntPtr hwnd = GetForegroundWindow();
            IntPtr hklOld = IntPtr.Zero;
            if (hwnd != IntPtr.Zero)
            {
                try
                {
                    uint pid;
                    uint tid = GetWindowThreadProcessId(hwnd, out pid);
                    hklOld = GetKeyboardLayout(tid);
                }
                catch (Exception) { hklOld = IntPtr.Zero; }

                // 1) 之前切过中文：焦点已换到别的窗口，或空闲超 800ms → 先恢复原布局
                if (zhHeld && (hwnd != zhHeldHwnd || Environment.TickCount - lastZhUse > 800))
                {
                    RestoreNow();
                }
                // 2) 当前窗口线程不是中文布局 → 切到简体中文（微软拼音）
                if (!IsChineseLayout(hklOld))
                {
                    try
                    {
                        IntPtr hklZh = LoadKeyboardLayout(KLID_ZH_CN, KLF_ACTIVATE);
                        if (hklZh != IntPtr.Zero)
                        {
                            PostMessage(hwnd, WM_INPUTLANGCHANGEREQUEST, IntPtr.Zero, hklZh);
                            // 等待布局生效（TSF 附加需要一点时间，最多 500ms）
                            for (int w = 0; w < 10; w++)
                            {
                                Thread.Sleep(50);
                                uint pid2;
                                uint tid2 = GetWindowThreadProcessId(hwnd, out pid2);
                                if (tid2 != 0 && IsChineseLayout(GetKeyboardLayout(tid2))) break;
                            }
                            zhHeld = true;
                            zhHeldHwnd = hwnd;
                            zhRestoreHkl = hklOld;
                            Log.Info("智能注入: 已临时切换中文输入法");
                        }
                    }
                    catch (Exception ex)
                    {
                        Log.Info("智能注入: 切换中文输入法失败(" + ex.Message + ")，直接注入");
                    }
                }
                else
                {
                    zhHeld = true;
                    zhHeldHwnd = hwnd;
                    zhRestoreHkl = hklOld;
                }
            }
            uint r = TextUnicode(text);
            lastZhUse = Environment.TickCount;
            return r;
        }

        // 注入文字：\n 拆成回车键、\t 拆成 Tab 键，其余走 Unicode。
        // 返回实际发送的事件数。
        private static uint TextUnicode(string text)
        {
            uint total = 0;
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < text.Length; i++)
            {
                char ch = text[i];
                if (ch == '\r') continue;                     // \r\n 只留 \n
                if (ch == '\n')
                {
                    total += SendEvents(BuildTextInputs(buf.ToString()));
                    buf.Length = 0;
                    total += SendEvents(BuildKeyInputs(VK_RETURN, 1));
                    Thread.Sleep(5);
                }
                else if (ch == '\t')
                {
                    total += SendEvents(BuildTextInputs(buf.ToString()));
                    buf.Length = 0;
                    total += SendEvents(BuildKeyInputs(VK_TAB, 1));
                    Thread.Sleep(5);
                }
                else
                {
                    buf.Append(ch);
                    // 分批：缓冲超过 400 字符先发，防止单次数组过大
                    if (buf.Length >= 400)
                    {
                        total += SendEvents(BuildTextInputs(buf.ToString()));
                        buf.Length = 0;
                        Thread.Sleep(20);
                    }
                }
            }
            if (buf.Length > 0) total += SendEvents(BuildTextInputs(buf.ToString()));
            return total;
        }

        internal static uint Key(ushort vk, int count)
        {
            return SendEvents(BuildKeyInputs(vk, count));
        }

        internal static string ForegroundTitle()
        {
            IntPtr hwnd = GetForegroundWindow();
            if (hwnd == IntPtr.Zero) return "(未知)";
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            string proc = "unknown";
            try { proc = Process.GetProcessById((int)pid).ProcessName; }
            catch (Exception) { }
            StringBuilder sb = new StringBuilder(256);
            GetWindowText(hwnd, sb, 256);
            return proc + " - " + sb.ToString();
        }

        // 无害自检
        internal static int SelfCheck()
        {
            Console.WriteLine("TypeBridge 注入内核自检 (无注入)");
            Console.WriteLine(new string('=', 46));
            Console.WriteLine(string.Format("sizeof(INPUT) = {0} (x64 应为 40) {1}",
                SizeOfInput, SizeOfInput == 40 ? "OK" : "FAIL"));
            string sample = "跨屏输入测试 Hello 🎉";
            INPUT[] evs = BuildTextInputs(sample);
            bool codeOk = evs[0].u.ki.wScan == 0x8DE8;
            bool countOk = evs.Length == Encoding.Unicode.GetBytes(sample).Length;
            Console.WriteLine(string.Format("编码验证 '{0}': 事件数 {1} (应={2}) {3}",
                sample, evs.Length, Encoding.Unicode.GetBytes(sample).Length, countOk ? "OK" : "FAIL"));
            Console.WriteLine(string.Format("'跨'=0x{0:X4} {1}", evs[0].u.ki.wScan, codeOk ? "OK" : "FAIL"));
            Console.WriteLine(string.Format("前台窗口: {0}", ForegroundTitle()));
            Console.WriteLine((codeOk && countOk && SizeOfInput == 40) ? "[结论] 注入内核 OK" : "[结论] FAIL");
            return (codeOk && countOk && SizeOfInput == 40) ? 0 : 1;
        }
    }

    // ---------------- 全局状态 ----------------
    internal static class State
    {
        internal static bool Paused = false;
        internal static volatile int ClientCount = 0;
    }

    // ---------------- 连接码（v0.4.5：自定义，字母+数字，1-30 位，默认 cdhwan） ----------------
    internal static class Pairing
    {
        private static readonly object Gate = new object();
        private static string code = Config.DefaultCode;

        internal static string Code
        {
            get { lock (Gate) { return code; } }
        }

        internal static void SetCode(string newCode)
        {
            lock (Gate)
            {
                code = IsValidCode(newCode) ? newCode : Config.DefaultCode;
            }
        }

        // 合法连接码：1-30 位，仅英文大小写 + 数字
        internal static bool IsValidCode(string s)
        {
            if (string.IsNullOrEmpty(s)) return false;
            if (s.Length > 30) return false;
            foreach (char c in s)
            {
                bool ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
                if (!ok) return false;
            }
            return true;
        }
    }

    // ---------------- 配对防爆破（按来源 IP 记失败次数，5 次拉黑 10 分钟） ----------------
    internal static class Guard
    {
        private static readonly object Gate = new object();
        private static readonly Dictionary<string, int> Fails = new Dictionary<string, int>();
        private static readonly Dictionary<string, DateTime> Blocked = new Dictionary<string, DateTime>();

        internal static bool IsBlocked(string ip)
        {
            lock (Gate)
            {
                DateTime until;
                if (Blocked.TryGetValue(ip, out until))
                {
                    if (DateTime.Now < until) return true;
                    Blocked.Remove(ip);
                }
                return false;
            }
        }

        internal static void RegisterFail(string ip)
        {
            lock (Gate)
            {
                int n = 0;
                if (!Fails.TryGetValue(ip, out n)) n = 0;
                n++;
                if (n >= 5)
                {
                    Blocked[ip] = DateTime.Now.AddMinutes(10);
                    Fails.Remove(ip);
                }
                else Fails[ip] = n;
                if (Fails.Count > 2000 || Blocked.Count > 2000) { Fails.Clear(); Blocked.Clear(); } // 防内存膨胀
            }
        }

        internal static void Clear(string ip)
        {
            lock (Gate)
            {
                Fails.Remove(ip);
                Blocked.Remove(ip);
            }
        }
    }

    // ---------------- 配置（端口/连接码等，存 %LOCALAPPDATA%\TypeBridge\config.json） ----------------
    internal static class Config
    {
        internal const int DefaultPort = 8765;
        internal const string DefaultCode = "cdhwan";
        internal static string DirOverride = null;   // 测试用

        private static string Dir
        {
            get
            {
                if (DirOverride != null) return DirOverride;
                return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "TypeBridge");
            }
        }
        private static string FilePath { get { return Path.Combine(Dir, "config.json"); } }

        internal static int Port = DefaultPort;
        internal static bool AskAutoStartDone = false;
        internal static int InjectModeValue = 1;   // 0=标准 1=智能(自动切中文输入法)
        internal static string Code = DefaultCode;

        private static JavaScriptSerializer json = new JavaScriptSerializer();

        internal static void Load()
        {
            try
            {
                if (!File.Exists(FilePath)) return;
                string s = File.ReadAllText(FilePath);
                Dictionary<string, object> d = json.Deserialize<Dictionary<string, object>>(s);
                if (d == null) return;
                object p;
                if (d.TryGetValue("port", out p) && p != null)
                {
                    int v;
                    if (int.TryParse(Convert.ToString(p), out v) && v >= 1 && v <= 65535) Port = v;
                }
                object a;
                if (d.TryGetValue("askAutoStartDone", out a) && a != null)
                {
                    try { AskAutoStartDone = Convert.ToBoolean(a); }
                    catch (Exception) { }
                }
                object im;
                if (d.TryGetValue("injectMode", out im) && im != null)
                {
                    int v;
                    if (int.TryParse(Convert.ToString(im), out v) && v >= 0 && v <= 1) InjectModeValue = v;
                }
                object c;
                if (d.TryGetValue("code", out c) && c != null)
                {
                    string cs = Convert.ToString(c);
                    if (Pairing.IsValidCode(cs)) Code = cs;
                }
            }
            catch (Exception ex)
            {
                Log.Info("配置读取失败，使用默认值: " + ex.Message);
            }
        }

        internal static void Save()
        {
            try
            {
                Directory.CreateDirectory(Dir);
                Dictionary<string, object> d = new Dictionary<string, object>();
                d["port"] = Port;
                d["askAutoStartDone"] = AskAutoStartDone;
                d["injectMode"] = InjectModeValue;
                d["code"] = Code;
                File.WriteAllText(FilePath, json.Serialize(d));
                Log.Info("配置已保存: 端口=" + Port + " 注入模式=" + InjectModeValue + " 连接码=" + Code);
            }
            catch (Exception ex)
            {
                Log.Info("配置保存失败: " + ex.Message);
            }
        }

        // 优先级：命令行参数 > 配置文件 > 默认 8765
        // 支持 /port 8899、/port:8899、-port 8899、--port=8899
        internal static int ResolvePort(string[] args)
        {
            Load();
            int p = Port;
            if (args == null) return p;
            for (int i = 0; i < args.Length; i++)
            {
                string token = args[i].Trim();
                if (token.Length == 0) continue;
                string lower = token.ToLowerInvariant();
                while (lower.Length > 0 && (lower[0] == '/' || lower[0] == '-'))
                    lower = lower.Substring(1);
                int sep = lower.IndexOfAny(new char[] { ':', '=' });
                string key = sep >= 0 ? lower.Substring(0, sep) : lower;
                string val = sep >= 0 ? lower.Substring(sep + 1) : null;
                if (key == "port")
                {
                    if (val == null && i + 1 < args.Length) val = args[i + 1].Trim();
                    if (val != null)
                    {
                        int v;
                        if (int.TryParse(val, out v) && v >= 1 && v <= 65535) { p = v; break; }
                    }
                }
            }
            return p;
        }
    }

    // ---------------- 网卡 IP 探测 ----------------
    internal static class NetDetect
    {
        internal class AddrInfo
        {
            internal string Ip;
            internal string Label;
            internal string Group;   // easytier / lan / other
            internal bool IsEasyTier;
        }

        // EasyTier 网卡名字/描述特征（大小写不敏感）
        internal static bool IsEasyTierName(string name)
        {
            if (string.IsNullOrEmpty(name)) return false;
            return name.IndexOf("easytier", StringComparison.OrdinalIgnoreCase) >= 0;
        }

        // 返回最优局域网 IPv4：无线 > 有线 > 其他；排除环回/APIPA
        internal static string PickBestIpv4()
        {
            string best = null;
            int bestScore = -1;
            try
            {
                NetworkInterface[] nis = NetworkInterface.GetAllNetworkInterfaces();
                foreach (NetworkInterface ni in nis)
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    int score;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211) score = 3;
                    else if (ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet) score = 2;
                    else score = 1;
                    foreach (UnicastIPAddressInformation uai in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (uai.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string s = uai.Address.ToString();
                        if (s.StartsWith("169.254.") || s.StartsWith("127.")) continue;
                        if (score > bestScore)
                        {
                            bestScore = score;
                            best = s;
                        }
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Info("网卡探测失败: " + ex.Message);
            }
            return best;
        }

        // 列出全部可用 IPv4，按组排序：EasyTier(远程) → 无线/有线(局域网) → 其他
        internal static List<AddrInfo> GetAll()
        {
            List<AddrInfo> easy = new List<AddrInfo>();
            List<AddrInfo> lan = new List<AddrInfo>();
            List<AddrInfo> other = new List<AddrInfo>();
            try
            {
                NetworkInterface[] nis = NetworkInterface.GetAllNetworkInterfaces();
                foreach (NetworkInterface ni in nis)
                {
                    if (ni.OperationalStatus != OperationalStatus.Up) continue;
                    if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                    bool isEasy = IsEasyTierName(ni.Name) || IsEasyTierName(ni.Description);
                    foreach (UnicastIPAddressInformation uai in ni.GetIPProperties().UnicastAddresses)
                    {
                        if (uai.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                        string s = uai.Address.ToString();
                        if (s.StartsWith("169.254.") || s.StartsWith("127.")) continue;
                        AddrInfo a = new AddrInfo();
                        a.Ip = s;
                        a.IsEasyTier = isEasy;
                        if (isEasy)
                        {
                            a.Group = "easytier";
                            a.Label = "EasyTier 虚拟网卡";
                        }
                        else if (ni.NetworkInterfaceType == NetworkInterfaceType.Wireless80211)
                        {
                            a.Group = "lan";
                            a.Label = "无线网卡 (WiFi)";
                        }
                        else if (ni.NetworkInterfaceType == NetworkInterfaceType.Ethernet)
                        {
                            a.Group = "lan";
                            a.Label = "有线网卡";
                        }
                        else
                        {
                            a.Group = "other";
                            a.Label = "其他网卡 (" + ni.Name + ")";
                        }
                        if (isEasy) easy.Add(a);
                        else if (a.Group == "lan") lan.Add(a);
                        else other.Add(a);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Info("网卡枚举失败: " + ex.Message);
            }
            List<AddrInfo> all = new List<AddrInfo>();
            all.AddRange(easy);
            all.AddRange(lan);
            all.AddRange(other);
            return all;
        }

        internal static AddrInfo PickEasyTier()
        {
            List<AddrInfo> all = GetAll();
            foreach (AddrInfo a in all) if (a.IsEasyTier) return a;
            return null;
        }
    }

    // ---------------- 资源读取（从入口程序集读内嵌资源） ----------------
    internal static class Res
    {
        private static Func<Assembly> provider = null;

        internal static void SetProvider(Func<Assembly> p) { provider = p; }

        private static Assembly Asm
        {
            get
            {
                if (provider != null)
                {
                    Assembly a = provider();
                    if (a != null) return a;
                }
                Assembly entry = Assembly.GetEntryAssembly();
                if (entry != null) return entry;
                return Assembly.GetExecutingAssembly();
            }
        }

        internal static string Get(string name)
        {
            using (Stream s = Asm.GetManifestResourceStream("TypeBridgeRes." + name))
            {
                if (s == null) return "<h1>资源缺失: " + name + "</h1>";
                using (StreamReader r = new StreamReader(s, Encoding.UTF8))
                {
                    return r.ReadToEnd();
                }
            }
        }
    }

    // ---------------- TinyHttp：TcpListener 极简 HTTP + WS 路由 ----------------
    internal static class TinyHttp
    {
        internal static int Port = Config.DefaultPort;

        private static WsSession activeSession;
        private static readonly object SessionGate = new object();

        internal static Action ready;
        internal static Action<string> failed;

        internal static void Run(int port)
        {
            try
            {
                Port = port;
                TcpListener listener = new TcpListener(IPAddress.Any, port);
                listener.Start();
                Log.Info("HTTP/WS 服务已监听 0.0.0.0:" + port);
                Action r = ready;
                if (r != null) r();
                while (true)
                {
                    try
                    {
                        TcpClient client = listener.AcceptTcpClient();
                        ThreadPool.QueueUserWorkItem(delegate(object o) { Handle((TcpClient)o); }, client);
                    }
                    catch (Exception ex)
                    {
                        // v0.4.1：accept 偶发异常（如瞬间连接风暴）不再让服务死掉，1 秒后续跑
                        Log.Info("accept 循环异常，1 秒后继续: " + ex.Message);
                        Thread.Sleep(1000);
                    }
                }
            }
            catch (Exception ex)
            {
                Log.Info("服务异常退出: " + ex.Message);
                Action<string> f = failed;
                if (f != null) f(ex.Message);
            }
        }

        // 探测端口是否已被占用（用于单实例判断）
        internal static bool PortInUse(int port)
        {
            try
            {
                TcpClient c = new TcpClient();
                IAsyncResult ar = c.BeginConnect(IPAddress.Loopback, port, null, null);
                bool ok = ar.AsyncWaitHandle.WaitOne(300);
                bool connected = false;
                if (ok)
                {
                    try { c.EndConnect(ar); connected = c.Connected; }
                    catch (Exception) { connected = false; }
                }
                c.Close();
                return connected;
            }
            catch (Exception) { return false; }
        }

        private static void Handle(TcpClient client)
        {
            try
            {
                client.NoDelay = true;
                NetworkStream stream = client.GetStream();
                string request = ReadHeaders(stream);
                if (request == null) { client.Close(); return; }

                string firstLine = request.Split(new char[] { '\n' })[0].Trim();
                string[] parts = firstLine.Split(new char[] { ' ' });
                if (parts.Length < 2) { client.Close(); return; }
                string method = parts[0];
                string path = parts[1];

                if (path == "/ws")
                {
                    if (method != "GET") { client.Close(); return; }
                    WsSession ws = new WsSession(client, stream, request);
                    ws.Start();
                    return; // 会话自管理，不关闭 client
                }

                if (method != "GET")
                {
                    WriteResponse(stream, 405, "text/plain", "method not allowed");
                    client.Close();
                    return;
                }

                int qi = path.IndexOf('?');
                if (qi >= 0) path = path.Substring(0, qi);

                switch (path)
                {
                    case "/":
                        WriteResponse(stream, 200, "text/html; charset=utf-8", Res.Get("index.html"));
                        break;
                    case "/pair":
                        WritePair(stream);
                        break;
                    case "/m":
                        WriteResponse(stream, 200, "text/html; charset=utf-8", Res.Get("mobile.html"));
                        break;
                    case "/qrcode.js":
                        WriteResponse(stream, 200, "application/javascript; charset=utf-8", Res.Get("qrcode.js"));
                        break;
                    default:
                        WriteResponse(stream, 404, "text/plain", "not found");
                        break;
                }
                client.Close();
            }
            catch (Exception)
            {
                try { client.Close(); }
                catch (Exception) { }
            }
        }

        // 配对页：列出全部可用地址，每张地址一个二维码
        private static void WritePair(NetworkStream stream)
        {
            string code = Pairing.Code;
            List<Dictionary<string, object>> list = new List<Dictionary<string, object>>();
            foreach (NetDetect.AddrInfo a in NetDetect.GetAll())
            {
                Dictionary<string, object> d = new Dictionary<string, object>();
                d["ip"] = a.Ip;
                d["label"] = a.Label;
                d["group"] = a.Group;
                d["url"] = "http://" + a.Ip + ":" + Port + "/m?code=" + code;
                list.Add(d);
            }
            string json = new JavaScriptSerializer().Serialize(list);
            Log.Info("配对页已生成: " + list.Count + " 个地址 (端口 " + Port + ")");
            string html = Res.Get("pair.html")
                .Replace("__ADDRS__", json)
                .Replace("__CODE__", code)
                .Replace("__PORT__", Port.ToString());
            WriteResponse(stream, 200, "text/html; charset=utf-8", html);
        }

        // 字节级读取 HTTP 头（避免 StreamReader 预读吃掉 WS 升级后的数据）
        private static string ReadHeaders(NetworkStream stream)
        {
            List<byte> buf = new List<byte>();
            while (true)
            {
                int b = stream.ReadByte();
                if (b < 0) return null;
                buf.Add((byte)b);
                int n = buf.Count;
                if (n >= 4 &&
                    buf[n - 4] == 13 && buf[n - 3] == 10 &&
                    buf[n - 2] == 13 && buf[n - 1] == 10)
                {
                    break;
                }
                if (n > 64 * 1024) return null;
            }
            return Encoding.ASCII.GetString(buf.ToArray());
        }

        private static void WriteResponse(NetworkStream stream, int code, string contentType, string body)
        {
            byte[] data = Encoding.UTF8.GetBytes(body);
            string head = "HTTP/1.1 " + code + " " + ReasonOf(code) + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + data.Length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
            byte[] headBytes = Encoding.ASCII.GetBytes(head);
            stream.Write(headBytes, 0, headBytes.Length);
            stream.Write(data, 0, data.Length);
            stream.Flush();
        }

        private static string ReasonOf(int code)
        {
            if (code == 200) return "OK";
            if (code == 404) return "Not Found";
            if (code == 405) return "Method Not Allowed";
            if (code == 426) return "Upgrade Required";
            return "OK";
        }

        internal static void ReplaceActive(WsSession newer)
        {
            lock (SessionGate)
            {
                if (activeSession != null && activeSession != newer)
                {
                    Log.Info("新连接接入，断开旧连接");
                    activeSession.Kick();
                }
                activeSession = newer;
            }
        }

        internal static void RemoveActive(WsSession session)
        {
            lock (SessionGate)
            {
                if (activeSession == session) activeSession = null;
            }
        }
    }

    // ---------------- WebSocket 会话（手写 RFC6455） ----------------
    internal class WsSession
    {
        private TcpClient client;
        private NetworkStream stream;
        private bool authed;
        private volatile bool closed;
        private JavaScriptSerializer json = new JavaScriptSerializer();

        internal WsSession(TcpClient client, NetworkStream stream, string request)
        {
            this.client = client;
            this.stream = stream;
            string key = FindHeader(request, "Sec-WebSocket-Key");
            if (key == null) throw new IOException("not a websocket request");
            string accept = ComputeAccept(key);
            string resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            byte[] respBytes = Encoding.ASCII.GetBytes(resp);
            stream.Write(respBytes, 0, respBytes.Length);
            stream.Flush();
        }

        internal void Start()
        {
            TinyHttp.ReplaceActive(this);
            Thread t = new Thread(ReadLoop);
            t.IsBackground = true;
            t.Start();
        }

        internal void Kick()
        {
            closed = true;
            try { client.Close(); }
            catch (Exception) { }
        }

        private static string FindHeader(string request, string name)
        {
            string[] lines = request.Split(new char[] { '\n' });
            foreach (string line in lines)
            {
                string l = line.Trim();
                int c = l.IndexOf(':');
                if (c <= 0) continue;
                if (string.Equals(l.Substring(0, c).Trim(), name, StringComparison.OrdinalIgnoreCase))
                {
                    return l.Substring(c + 1).Trim();
                }
            }
            return null;
        }

        private static string ComputeAccept(string key)
        {
            const string magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            SHA1 sha = SHA1.Create();
            byte[] hash = sha.ComputeHash(Encoding.ASCII.GetBytes(key + magic));
            return Convert.ToBase64String(hash);
        }

        private string RemoteIp()
        {
            try
            {
                IPEndPoint ep = client.Client.RemoteEndPoint as IPEndPoint;
                if (ep != null) return ep.Address.ToString();
            }
            catch (Exception) { }
            return "?";
        }

        private void ReadLoop()
        {
            try
            {
                while (!closed)
                {
                    int opcode;
                    byte[] payload;
                    if (!ReadFrame(out opcode, out payload)) break;

                    if (opcode == 0x8) break;                    // close
                    if (opcode == 0x9)                            // ping → pong
                    {
                        SendFrame(0xA, payload);
                        continue;
                    }
                    if (opcode == 0xA) continue;                  // pong
                    if (opcode != 0x1 && opcode != 0x2) continue; // 只处理文本

                    string msg = Encoding.UTF8.GetString(payload);
                    if (!HandleMessage(msg)) break;
                }
            }
            catch (Exception) { }
            finally
            {
                closed = true;
                if (authed) State.ClientCount--;
                TinyHttp.RemoveActive(this);
                try { client.Close(); }
                catch (Exception) { }
                Log.Info("连接断开 (" + RemoteIp() + ")");
            }
        }

        // 返回 false = 连接应关闭
        private bool HandleMessage(string msg)
        {
            Dictionary<string, object> dict;
            try
            {
                dict = json.Deserialize<Dictionary<string, object>>(msg);
            }
            catch (Exception)
            {
                return false;
            }
            if (dict == null || !dict.ContainsKey("type")) return false;
            string type = dict["type"] as string;

            if (!authed)
            {
                if (type != "hello") { SendError("请先配对"); return false; }
                object codeObj;
                string code = "";
                if (dict.TryGetValue("code", out codeObj)) code = (codeObj as string) ?? "";
                string rip = RemoteIp();
                if (Guard.IsBlocked(rip))
                {
                    SendError("尝试次数过多，请 10 分钟后再试");
                    Log.Info("配对被拉黑: " + rip);
                    return false;
                }
                if (!string.Equals(code, Pairing.Code, StringComparison.OrdinalIgnoreCase))
                {
                    Guard.RegisterFail(rip);
                    SendError("配对码错误");
                    Log.Info("配对失败: 收到[" + code + "] 期望[" + Pairing.Code + "] 来自 " + rip);
                    return false;   // 配对错误直接断开（方案 4.1）
                }
                Guard.Clear(rip);
                authed = true;
                State.ClientCount++;
                SendJson(new Dictionary<string, object> { { "type", "ready" } });
                Log.Info("手机配对成功 (" + rip + ")");
                return true;
            }

            switch (type)
            {
                case "text":
                {
                    object t;
                    string text = "";
                    if (dict.TryGetValue("text", out t)) text = (t as string) ?? "";
                    if (text.Length == 0) return true;
                    if (text.Length > 10000) { SendError("单次最多1万字符"); return true; }
                    if (State.Paused) { SendError("注入已暂停"); return true; }
                    uint sent = Inject.Text(text);
                    Log.Info("注入 " + text.Length + " 字符 (" + sent + " 事件)");
                    SendAck(true, text.Length);
                    return true;
                }
                case "enter":
                {
                    if (State.Paused) return true;
                    Inject.Key(0x0D, 1);
                    SendAck(true, 0);
                    return true;
                }
                case "backspace":
                {
                    if (State.Paused) return true;
                    int count = 1;
                    object c;
                    if (dict.TryGetValue("count", out c))
                    {
                        try { count = Convert.ToInt32(c); }
                        catch (Exception) { count = 1; }
                    }
                    if (count < 1) count = 1;
                    if (count > 10000) count = 10000;
                    Inject.Key(0x08, count);
                    SendAck(true, count);
                    return true;
                }
                case "wheel":
                {
                    // v0.4.6：模拟鼠标滚轮（steps=格数，±1 起；正=内容向下滚）
                    if (State.Paused) return true;
                    int steps = 0;
                    object s;
                    if (dict.TryGetValue("steps", out s))
                    {
                        try { steps = Convert.ToInt32(s); }
                        catch (Exception) { steps = 0; }
                    }
                    if (steps == 0) return true;
                    uint sent = Inject.Wheel(steps);
                    Log.Info("滚轮 " + (steps > 0 ? "下 " : "上 ") + Math.Abs(steps) + " 格 (" + sent + " 事件)");
                    SendAck(true, Math.Abs(steps));
                    return true;
                }
                case "mmove":
                {
                    // v0.4.7：相对移动光标（触摸板单指滑动）
                    if (State.Paused) return true;
                    int mdx = 0, mdy = 0;
                    object ox, oy;
                    if (dict.TryGetValue("dx", out ox)) { try { mdx = Convert.ToInt32(ox); } catch (Exception) { mdx = 0; } }
                    if (dict.TryGetValue("dy", out oy)) { try { mdy = Convert.ToInt32(oy); } catch (Exception) { mdy = 0; } }
                    if (mdx == 0 && mdy == 0) return true;
                    if (mdx > 500) mdx = 500; if (mdx < -500) mdx = -500;
                    if (mdy > 500) mdy = 500; if (mdy < -500) mdy = -500;
                    Inject.MouseMove(mdx, mdy);
                    return true;    // 高频消息，不 ack 不记日志
                }
                case "mdn":
                    if (State.Paused) return true;
                    Inject.MouseButton("left", true);
                    return true;
                case "mup":
                    if (State.Paused) return true;
                    Inject.MouseButton("left", false);
                    return true;
                case "rdn":
                    if (State.Paused) return true;
                    Inject.MouseButton("right", true);
                    return true;
                case "rup":
                    if (State.Paused) return true;
                    Inject.MouseButton("right", false);
                    return true;
                case "zoom":
                {
                    // v0.4.9：缩放 = Ctrl+滚轮（上滑放大/下滑缩小）
                    if (State.Paused) return true;
                    int zsteps = 0;
                    object zs;
                    if (dict.TryGetValue("steps", out zs))
                    {
                        try { zsteps = Convert.ToInt32(zs); }
                        catch (Exception) { zsteps = 0; }
                    }
                    if (zsteps == 0) return true;
                    Inject.CtrlWheel(zsteps);
                    return true;
                }
                case "ping":
                    SendJson(new Dictionary<string, object> { { "type", "pong" } });
                    return true;
                case "sync":
                {
                    // 实时同步：原子完成「退格 N 次 + 输入增量文本」
                    // 客户端把输入框当作电脑上文本的镜像，算最长公共前缀求增量
                    if (State.Paused) return true;
                    object so;
                    string stext = "";
                    if (dict.TryGetValue("text", out so)) stext = (so as string) ?? "";
                    int sback = 0;
                    object sb;
                    if (dict.TryGetValue("back", out sb))
                    {
                        try { sback = Convert.ToInt32(sb); }
                        catch (Exception) { sback = 0; }
                    }
                    if (sback < 0) sback = 0;
                    if (sback > 10000) sback = 10000;
                    if (stext.Length > 10000) { SendError("单次最多1万字符"); return true; }
                    if (sback > 0) Inject.Key(0x08, sback);
                    uint ssent = 0;
                    if (stext.Length > 0) ssent = Inject.Text(stext);
                    if (sback > 0 || ssent > 0)
                    {
                        Log.Info("同步 退格" + sback + " 注入" + stext.Length + "字符");
                    }
                    SendAck(true, stext.Length);
                    return true;
                }
                default:
                    return true;    // 未知消息忽略，不断开
            }
        }

        private void SendAck(bool ok, int chars)
        {
            Dictionary<string, object> ack = new Dictionary<string, object>();
            ack["type"] = "ack";
            ack["ok"] = ok;
            ack["chars"] = chars;
            ack["fg"] = Inject.ForegroundTitle();
            SendJson(ack);
        }

        private void SendError(string msg)
        {
            Dictionary<string, object> e = new Dictionary<string, object>();
            e["type"] = "error";
            e["msg"] = msg;
            SendJson(e);
        }

        private void SendJson(Dictionary<string, object> obj)
        {
            SendFrame(0x1, Encoding.UTF8.GetBytes(json.Serialize(obj)));
        }

        private void SendFrame(int opcode, byte[] payload)
        {
            if (closed) return;
            try
            {
                List<byte> frame = new List<byte>();
                frame.Add((byte)(0x80 | opcode));              // FIN + opcode
                if (payload.Length < 126)
                {
                    frame.Add((byte)payload.Length);
                }
                else if (payload.Length < 65536)
                {
                    frame.Add(126);
                    frame.Add((byte)(payload.Length >> 8));
                    frame.Add((byte)(payload.Length & 0xFF));
                }
                else
                {
                    frame.Add(127);
                    ulong len = (ulong)payload.Length;
                    for (int i = 7; i >= 0; i--) frame.Add((byte)((len >> (i * 8)) & 0xFF));
                }
                frame.AddRange(payload);
                byte[] data = frame.ToArray();
                stream.Write(data, 0, data.Length);
                stream.Flush();
            }
            catch (Exception)
            {
                closed = true;
            }
        }

        private bool ReadFrame(out int opcode, out byte[] payload)
        {
            opcode = 0;
            payload = null;
            int b0 = stream.ReadByte();
            if (b0 < 0) return false;
            int b1 = stream.ReadByte();
            if (b1 < 0) return false;
            opcode = b0 & 0x0F;
            bool masked = (b1 & 0x80) != 0;
            ulong len = (ulong)(b1 & 0x7F);

            if (len == 126)
            {
                int hi = stream.ReadByte(), lo = stream.ReadByte();
                if (hi < 0 || lo < 0) return false;
                len = (ulong)((hi << 8) | lo);
            }
            else if (len == 127)
            {
                len = 0;
                for (int i = 0; i < 8; i++)
                {
                    int b = stream.ReadByte();
                    if (b < 0) return false;
                    len = (len << 8) | (ulong)(uint)b;
                }
            }
            if (len > 1024 * 1024) return false;               // 1MB 上限，防滥用

            byte[] mask = null;
            if (masked)
            {
                mask = new byte[4];
                for (int i = 0; i < 4; i++)
                {
                    int b = stream.ReadByte();
                    if (b < 0) return false;
                    mask[i] = (byte)b;
                }
            }

            byte[] data = new byte[len];
            int got = 0;
            while (got < (int)len)
            {
                int n = stream.Read(data, got, (int)len - got);
                if (n <= 0) return false;
                got += n;
            }
            if (masked)
            {
                for (int i = 0; i < data.Length; i++) data[i] ^= mask[i & 3];
            }
            payload = data;
            return true;
        }
    }

    // ---------------- 扫码弹窗 ----------------
    // 需求：托盘「链接二维码」不要再开浏览器（慢、占内存），改成程序内小弹窗。
    // v0.4.1：内嵌浏览器连 http://127.0.0.1 直连（避开 localhost/系统代理干扰）；
    //         8 秒未加载完成或加载异常，自动用系统默认浏览器打开配对页兜底；
    //         顶栏提供「用浏览器打开」手动按钮。
    internal class PairForm : Form
    {
        private WebBrowser browser;
        private bool shownOnce;
        private string pairUrl;
        private System.Windows.Forms.Timer loadTimer;

        internal PairForm(string pairUrl)
        {
            this.pairUrl = pairUrl;
            Text = "TypeBridge 扫码连接";
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            ClientSize = new System.Drawing.Size(380, 500);
            StartPosition = FormStartPosition.CenterScreen;
            ShowInTaskbar = true;

            Button openBrowser = new Button();
            openBrowser.Text = "用浏览器打开";
            openBrowser.SetBounds(8, 8, 130, 26);
            openBrowser.Click += delegate { FallbackOpen(); };
            Controls.Add(openBrowser);

            browser = new WebBrowser();
            browser.ScriptErrorsSuppressed = true;
            browser.SetBounds(0, 40, 380, 460);
            Controls.Add(browser);

            browser.DocumentCompleted += delegate(object s, WebBrowserDocumentCompletedEventArgs e)
            {
                try { loadTimer.Stop(); } catch (Exception) { }
                Log.Info("扫码页加载完成: " + (e.Url != null ? e.Url.ToString() : "?"));
                try
                {
                    string url = e.Url != null ? e.Url.ToString() : "";
                    if (url.IndexOf("/pair") < 0 || browser.Document == null || browser.Document.Body == null)
                    {
                        Log.Info("内嵌浏览器未正常显示配对页，改用系统浏览器打开");
                        FallbackOpen();
                    }
                }
                catch (Exception ex) { Log.Info("加载检查异常: " + ex.Message); }
            };

            // v0.4.1：8 秒没加载完（内嵌浏览器卡死/无法渲染）就切系统浏览器
            loadTimer = new System.Windows.Forms.Timer();
            loadTimer.Interval = 8000;
            loadTimer.Tick += delegate
            {
                loadTimer.Stop();
                Log.Info("扫码页 8 秒未加载完成，改用系统浏览器打开");
                FallbackOpen();
            };

            FormClosing += delegate(object s, FormClosingEventArgs e)
            {
                // 点 X 只是隐藏，程序仍在托盘常驻
                e.Cancel = true;
                Hide();
            };
        }

        private void FallbackOpen()
        {
            try { Process.Start(pairUrl); }
            catch (Exception ex) { Log.Info("打开系统浏览器失败: " + ex.Message); }
        }

        internal void ShowPair()
        {
            if (!shownOnce) { shownOnce = true; Show(); }
            else { Show(); Activate(); }
            try
            {
                browser.Navigate(pairUrl);
                loadTimer.Start();
            }
            catch (Exception ex)
            {
                Log.Info("加载扫码窗口失败: " + ex.Message);
                FallbackOpen();
            }
        }
    }

    // ---------------- 端口设置弹窗 ----------------
    internal class PortForm : Form
    {
        private TextBox input;
        internal int Port = -1;

        internal PortForm(int current)
        {
            Text = "端口设置";
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new System.Drawing.Size(320, 150);
            Font = new System.Drawing.Font("Microsoft YaHei", 9F);

            Label lbl = new Label();
            lbl.Text = "监听端口（1 - 65535），手机连接地址会同步变化：";
            lbl.SetBounds(14, 12, 292, 32);
            Controls.Add(lbl);

            input = new TextBox();
            input.Text = current.ToString();
            input.SetBounds(14, 48, 292, 26);
            Controls.Add(input);

            Button ok = new Button();
            ok.Text = "确定并重启";
            ok.SetBounds(40, 92, 110, 30);
            ok.DialogResult = DialogResult.OK;
            Controls.Add(ok);

            Button cancel = new Button();
            cancel.Text = "取消";
            cancel.SetBounds(170, 92, 110, 30);
            cancel.DialogResult = DialogResult.Cancel;
            Controls.Add(cancel);

            AcceptButton = ok;
            CancelButton = cancel;

            FormClosing += delegate(object s, FormClosingEventArgs e)
            {
                if (DialogResult == DialogResult.OK)
                {
                    int v;
                    if (!int.TryParse(input.Text.Trim(), out v) || v < 1 || v > 65535)
                    {
                        MessageBox.Show("请输入 1 - 65535 之间的端口号。", "TypeBridge",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        e.Cancel = true;
                        return;
                    }
                    Port = v;
                }
            };
        }
    }

    // ---------------- 连接码设置弹窗（v0.4.5：自定义字母+数字，1-30 位） ----------------
    internal class CodeForm : Form
    {
        private TextBox input;
        internal string Code = "";

        internal CodeForm(string current)
        {
            Text = "连接码设置";
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new System.Drawing.Size(360, 170);
            Font = new System.Drawing.Font("Microsoft YaHei", 9F);

            Label lbl = new Label();
            lbl.Text = "连接码（仅英文和数字，1 - 30 位）：\r\n改完后手机需重新扫码。";
            lbl.SetBounds(14, 10, 332, 44);
            Controls.Add(lbl);

            input = new TextBox();
            input.Text = current;
            input.MaxLength = 30;
            input.SetBounds(14, 58, 332, 26);
            Controls.Add(input);

            Button ok = new Button();
            ok.Text = "确定";
            ok.SetBounds(40, 102, 110, 30);
            ok.DialogResult = DialogResult.OK;
            Controls.Add(ok);

            Button cancel = new Button();
            cancel.Text = "取消";
            cancel.SetBounds(190, 102, 110, 30);
            cancel.DialogResult = DialogResult.Cancel;
            Controls.Add(cancel);

            AcceptButton = ok;
            CancelButton = cancel;

            FormClosing += delegate(object s, FormClosingEventArgs e)
            {
                if (DialogResult == DialogResult.OK)
                {
                    string v = (input.Text ?? "").Trim();
                    if (!Pairing.IsValidCode(v))
                    {
                        MessageBox.Show("连接码仅支持英文和数字，长度 1 - 30 位。", "TypeBridge",
                            MessageBoxButtons.OK, MessageBoxIcon.Warning);
                        e.Cancel = true;
                        return;
                    }
                    Code = v;
                }
            };
        }
    }

    // ---------------- 连接地址弹窗（列出全部网卡地址，便于远程/局域网选择） ----------------
    internal class AddressForm : Form
    {
        private TextBox box;

        internal AddressForm(int port, string code)
        {
            Text = "连接地址";
            FormBorderStyle = FormBorderStyle.FixedSingle;
            MaximizeBox = false;
            MinimizeBox = false;
            StartPosition = FormStartPosition.CenterParent;
            ClientSize = new System.Drawing.Size(460, 360);

            Label hint = new Label();
            hint.Text = "手机与电脑同一网络：用「无线/有线网卡」地址；不在同一网络：用「EasyTier 虚拟网卡」地址。";
            hint.SetBounds(14, 10, 432, 34);
            Controls.Add(hint);

            box = new TextBox();
            box.Multiline = true;
            box.ReadOnly = true;
            box.ScrollBars = ScrollBars.Vertical;
            box.SetBounds(14, 48, 432, 240);
            Controls.Add(box);

            Button copy = new Button();
            copy.Text = "复制全部地址";
            copy.SetBounds(40, 300, 140, 30);
            copy.Click += delegate
            {
                try { Clipboard.SetText(box.Text); MessageBox.Show("已复制。", "TypeBridge"); }
                catch (Exception ex) { MessageBox.Show("复制失败: " + ex.Message, "TypeBridge"); }
            };
            Controls.Add(copy);

            Button close = new Button();
            close.Text = "关闭";
            close.SetBounds(280, 300, 110, 30);
            close.DialogResult = DialogResult.Cancel;
            Controls.Add(close);

            StringBuilder sb = new StringBuilder();
            List<NetDetect.AddrInfo> addrs = NetDetect.GetAll();
            foreach (NetDetect.AddrInfo a in addrs)
            {
                sb.Append("[" + a.Label + "]  http://" + a.Ip + ":" + port + "/m?code=" + code);
                sb.AppendLine();
            }
            if (addrs.Count == 0) sb.AppendLine("未找到可用 IP。");
            sb.AppendLine();
            sb.AppendLine("提示：EasyTier 地址用于手机和电脑不在同一 WiFi 时跨公网使用，需要两端安装 EasyTier 并加入同一虚拟网络。");
            box.Text = sb.ToString();
        }
    }

    internal static class WinCompat
    {
        // 让内嵌浏览器控件使用 IE11 内核（画二维码的 canvas 需要 IE9+），只影响本程序
        internal static void EnableIe11()
        {
            try
            {
                string exe = System.IO.Path.GetFileName(
                    System.Reflection.Assembly.GetExecutingAssembly().Location);
                Microsoft.Win32.RegistryKey k = Microsoft.Win32.Registry.CurrentUser.CreateSubKey(
                    @"Software\Microsoft\Internet Explorer\Main\FeatureControl\FEATURE_BROWSER_EMULATION");
                if (k != null)
                {
                    k.SetValue(exe, 11001, Microsoft.Win32.RegistryValueKind.DWord);
                    k.Close();
                }
            }
            catch (Exception ex) { Log.Info("设置浏览器内核模式失败: " + ex.Message); }
        }
    }

    // ---------------- 开机自启（HKCU Run，免管理员） ----------------
    internal static class AutoStart
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "TypeBridge";

        private static string ExePath
        {
            get { return Application.ExecutablePath; }
        }

        internal static bool IsEnabled()
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, false))
                {
                    if (k == null) return false;
                    object v = k.GetValue(ValueName);
                    if (v == null) return false;
                    string s = (string)v;
                    // 写入时包了一层引号（兼容路径带空格），比较前剥掉
                    if (s.Length >= 2 && s[0] == '"' && s[s.Length - 1] == '"')
                        s = s.Substring(1, s.Length - 2);
                    return string.Equals(s, ExePath, StringComparison.OrdinalIgnoreCase);
                }
            }
            catch (Exception) { return false; }
        }

        // 返回 null=成功，否则为错误信息
        internal static string Set(bool on)
        {
            try
            {
                using (RegistryKey k = Registry.CurrentUser.OpenSubKey(RunKey, true))
                {
                    if (k == null) return "无法打开注册表启动项";
                    if (on) k.SetValue(ValueName, "\"" + ExePath + "\"", RegistryValueKind.String);
                    else k.DeleteValue(ValueName, false);
                }
                Log.Info("开机自启: " + (on ? "已开启" : "已关闭"));
                return null;
            }
            catch (Exception ex) { return ex.Message; }
        }
    }

    // ---------------- 托盘 ----------------
    internal class TrayApp : ApplicationContext
    {
        private NotifyIcon tray;
        private MenuItem pauseItem;
        internal NotifyIcon Tray { get { return tray; } }

        private MenuItem startupItem;
        private int port;
        private PairForm pairForm;

        internal TrayApp(int port)
        {
            this.port = port;
            ContextMenu menu = new ContextMenu();
            menu.MenuItems.Add("链接二维码", delegate { OpenPair(); });
            menu.MenuItems.Add("连接码设置…", delegate
            {
                using (CodeForm f = new CodeForm(Pairing.Code))
                {
                    if (f.ShowDialog() == DialogResult.OK)
                    {
                        Pairing.SetCode(f.Code);
                        Config.Code = Pairing.Code;
                        Config.Save();
                        try { tray.Text = "TypeBridge 连接码 " + Pairing.Code; } catch (Exception) { }
                        Log.Info("连接码已更新: " + Pairing.Code);
                        tray.ShowBalloonTip(1500, "TypeBridge", "连接码已更新: " + Pairing.Code + "，请重新扫码", ToolTipIcon.Info);
                    }
                }
            });
            pauseItem = new MenuItem("暂停注入", delegate
            {
                State.Paused = !State.Paused;
                pauseItem.Checked = State.Paused;
                Log.Info(State.Paused ? "注入已暂停" : "注入已恢复");
            });
            menu.MenuItems.Add(pauseItem);
            startupItem = new MenuItem("", delegate
            {
                bool want = !AutoStart.IsEnabled();
                string err = AutoStart.Set(want);
                if (err != null)
                {
                    MessageBox.Show("设置开机启动失败: " + err, "TypeBridge");
                    return;
                }
                RefreshStartupItem();
                tray.ShowBalloonTip(1500, "TypeBridge", want ? "已开启开机自动启动" : "已关闭开机自动启动", ToolTipIcon.Info);
            });
            RefreshStartupItem();
            menu.MenuItems.Add(startupItem);
            menu.MenuItems.Add("端口设置…", delegate
            {
                using (PortForm f = new PortForm(port))
                {
                    if (f.ShowDialog() == DialogResult.OK && f.Port > 0 && f.Port != port)
                    {
                        Config.Port = f.Port;
                        Config.Save();
                        tray.Visible = false;
                        MessageBox.Show("端口已保存为 " + f.Port + "，程序将自动重启生效。" + Environment.NewLine
                            + "如果手机正在连接，需要重新扫码。", "TypeBridge",
                            MessageBoxButtons.OK, MessageBoxIcon.Information);
                        Application.Restart();
                    }
                }
            });
            menu.MenuItems.Add("查看连接地址…", delegate
            {
                using (AddressForm f = new AddressForm(port, Pairing.Code))
                {
                    f.ShowDialog();
                }
            });
            // v0.4.4：注入模式切换（智能模式自动切中文输入法，解决中文变成小格子）
            MenuItem modeStd = new MenuItem("标准（直接注入）");
            MenuItem modeAuto = new MenuItem("智能（自动切换中文输入法，推荐）");
            MenuItem[] modeItems = new MenuItem[] { modeStd, modeAuto };
            for (int i = 0; i < modeItems.Length; i++)
            {
                int idx = i;
                modeItems[i].Checked = ((int)Inject.Mode == idx);
                modeItems[i].Click += delegate
                {
                    Inject.Mode = (Inject.InjectMode)idx;
                    Config.InjectModeValue = idx;
                    Config.Save();
                    for (int k = 0; k < modeItems.Length; k++) modeItems[k].Checked = (k == idx);
                    Log.Info("注入模式切换为: " + Program.ModeName(Inject.Mode));
                    tray.ShowBalloonTip(1500, "TypeBridge",
                        "注入模式已切换为：" + Program.ModeName(Inject.Mode), ToolTipIcon.Info);
                };
            }
            MenuItem modeMenu = new MenuItem("注入模式");
            modeMenu.MenuItems.Add(modeStd);
            modeMenu.MenuItems.Add(modeAuto);
            menu.MenuItems.Add(modeMenu);
            menu.MenuItems.Add("查看日志", delegate { Log.OpenFile(); });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("关于 " + Version.Text, delegate
            {
                MessageBox.Show(
                    "TypeBridge " + Version.Text + Environment.NewLine + Environment.NewLine
                    + "手机扫码，把文字直接送到电脑光标处。" + Environment.NewLine
                    + "支持局域网 / EasyTier 远程组网。" + Environment.NewLine + Environment.NewLine
                    + "项目主页：" + Version.Home,
                    "关于 TypeBridge", MessageBoxButtons.OK, MessageBoxIcon.Information);
            });
            menu.MenuItems.Add("-");
            menu.MenuItems.Add("退出", delegate
            {
                Inject.RestoreNow();   // v0.4.4：退出前恢复输入法布局
                tray.Visible = false;
                Environment.Exit(0);
            });

            tray = new NotifyIcon();
            tray.Icon = System.Drawing.SystemIcons.Application;
            tray.Text = "TypeBridge 连接码 " + Pairing.Code;
            tray.ContextMenu = menu;
            tray.Visible = true;
            tray.DoubleClick += delegate { OpenPair(); };
        }

        // 菜单项文字动态反映"点一下会做什么"，不靠勾选标记猜
        private void RefreshStartupItem()
        {
            bool on = AutoStart.IsEnabled();
            startupItem.Text = on ? "关闭开机启动" : "开启开机启动";
            startupItem.Checked = on;
        }

        private void OpenPair()
        {
            if (pairForm == null) { pairForm = new PairForm("http://127.0.0.1:" + port + "/pair"); }
            pairForm.ShowPair();
        }

        internal void ShowPair()
        {
            OpenPair();
        }
    }

    // ---------------- 入口 ----------------
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
#if TB_SERVER_TEST
            return RunServerTest(args);
#else
            Application.EnableVisualStyles();
            WinCompat.EnableIe11();
            Res.SetProvider(delegate { return Assembly.GetExecutingAssembly(); });

            // v0.4.1：全局异常捕获，任何未处理异常都写日志 + 弹窗，不再静默退出
            AppDomain.CurrentDomain.UnhandledException += delegate(object s, UnhandledExceptionEventArgs e)
            {
                Exception ex = e.ExceptionObject as Exception;
                Log.Info("!!! 未处理异常(进程将退出): " + (ex != null ? ex.ToString() : "未知错误"));
                try
                {
                    MessageBox.Show("TypeBridge 遇到未处理错误，即将退出。\n\n"
                        + (ex != null ? ex.Message : "未知错误")
                        + "\n\n详细信息已写入日志：%LOCALAPPDATA%\\TypeBridge\\log.txt",
                        "TypeBridge 错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                catch (Exception) { }
            };
            Application.ThreadException += delegate(object s, System.Threading.ThreadExceptionEventArgs e)
            {
                Log.Info("!!! 界面线程异常: " + (e.Exception != null ? e.Exception.ToString() : "未知"));
                try
                {
                    MessageBox.Show("TypeBridge 界面异常：" + (e.Exception != null ? e.Exception.Message : "未知")
                        + "\n\n详细信息已写入日志：%LOCALAPPDATA%\\TypeBridge\\log.txt",
                        "TypeBridge 错误", MessageBoxButtons.OK, MessageBoxIcon.Error);
                }
                catch (Exception) { }
            };
            Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);

            Log.Info("OS: " + Environment.OSVersion + "  .NET: " + Environment.Version);

            int port = Config.ResolvePort(args);
            Inject.Mode = (Inject.InjectMode)(Config.InjectModeValue & 1);
            Pairing.SetCode(Config.Code);   // v0.4.5：应用自定义连接码
            Log.Info("注入模式: " + ModeName(Inject.Mode));
            Log.Info("连接码: " + Pairing.Code + (Config.Code == Config.DefaultCode ? "（默认）" : ""));

            // 单实例：端口已被占用 = 已有一个在跑，直接复用，不再重复启动
            if (TinyHttp.PortInUse(port))
            {
                MessageBox.Show(
                    "TypeBridge 已经在运行了（右下角托盘图标里）。" + Environment.NewLine + Environment.NewLine
                    + "右键托盘图标选「链接二维码」即可弹出扫码窗口。",
                    "TypeBridge", MessageBoxButtons.OK, MessageBoxIcon.Information);
                return 0;
            }

            string ip = NetDetect.PickBestIpv4();
            NetDetect.AddrInfo easy = NetDetect.PickEasyTier();
            if (ip == null && easy == null)
            {
                MessageBox.Show("未找到可用的网络 IP，请检查电脑是否连上网络。", "TypeBridge",
                    MessageBoxButtons.OK, MessageBoxIcon.Warning);
                return 1;
            }
            string code = Pairing.Code;

            Log.Info("========== TypeBridge " + Version.Text + " 启动 ==========");
            Log.Info("监听端口: " + port);
            if (ip != null) Log.Info("局域网 IP: " + ip);
            if (easy != null) Log.Info("EasyTier 虚拟网卡: " + easy.Ip + "（远程组网可用）");
            Log.Info("配对码: " + code);
            Log.Info("手机访问地址: http://" + (ip ?? easy.Ip) + ":" + port + "/m?code=" + code);

            bool bindOk = false;
            string bindErr = null;
            TinyHttp.ready = delegate { bindOk = true; };
            TinyHttp.failed = delegate(string m) { bindErr = m; };

            Thread server = new Thread(new ThreadStart(delegate { TinyHttp.Run(port); }));
            server.IsBackground = true;
            server.Start();
            for (int i = 0; i < 25 && !bindOk && bindErr == null; i++) Thread.Sleep(100);
            if (bindErr != null)
            {
                MessageBox.Show("服务启动失败: " + bindErr + Environment.NewLine
                    + "端口 " + port + " 可能被占用，可用托盘菜单「端口设置…」换一个端口。", "TypeBridge",
                    MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }

            TrayApp app = new TrayApp(port);
            NotifyIcon trayRef = app.Tray;
            Thread tick = new Thread(new ThreadStart(delegate
            {
                int last = -1;
                while (true)
                {
                    int n = State.ClientCount;
                    if (n != last)
                    {
                        last = n;
                        string t = n > 0
                            ? "TypeBridge 手机已连接 (码 " + Pairing.Code + ")"
                            : "TypeBridge 等待扫码 (码 " + Pairing.Code + ")";
                        try { trayRef.Text = t; } catch (Exception) { }
                    }
                    Thread.Sleep(1000);
                }
            }));
            tick.IsBackground = true;
            tick.Start();

            // 服务就绪 → 自动弹出扫码窗口（程序内弹窗，不启动浏览器）
            app.ShowPair();

            // 开机自启只在首次运行时询问（v0.4：不再每次启动弹窗）
            if (!Config.AskAutoStartDone)
            {
                Config.AskAutoStartDone = true;
                Config.Save();
                if (!AutoStart.IsEnabled())
                {
                    DialogResult dr = MessageBox.Show(
                        "TypeBridge 已启动，扫码窗口已弹出。" + Environment.NewLine + Environment.NewLine
                        + "是否加入开机自动启动？（推荐，开机后无需手动打开，之后可在托盘菜单随时开关）",
                        "TypeBridge", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                    if (dr == DialogResult.Yes)
                    {
                        string err = AutoStart.Set(true);
                        if (err != null)
                            MessageBox.Show("设置开机启动失败: " + err, "TypeBridge",
                                MessageBoxButtons.OK, MessageBoxIcon.Warning);
                    }
                }
            }

            Application.Run(app);
            return 0;
#endif
        }

        internal static string ModeName(Inject.InjectMode m)
        {
            if (m == Inject.InjectMode.AutoLayout) return "智能(自动切中文输入法)";
            return "标准(直接注入)";
        }

#if TB_SERVER_TEST
        // ---------------- 测试模式（Linux/mono 下跑 HTTP/WS 与纯逻辑单测） ----------------
        private static int RunServerTest(string[] args)
        {
            bool ok = UnitChecks();
            Console.WriteLine("UNIT_CHECKS=" + (ok ? "PASS" : "FAIL"));

            int port = 18765;
            TinyHttp.ready = delegate { Console.WriteLine("SERVER_READY " + port); };
            TinyHttp.failed = delegate(string m) { Console.WriteLine("SERVER_FAIL " + m); };

            Thread server = new Thread(new ThreadStart(delegate { TinyHttp.Run(port); }));
            server.IsBackground = true;
            server.Start();
            Console.WriteLine("TEST_CODE=" + Pairing.Code);
            Thread.Sleep(60000);
            return 0;
        }

        private static bool UnitChecks()
        {
            bool ok = true;

            // 1. 命令行端口解析
            ok &= Check("port /port 8899", Config.ResolvePort(new string[] { "/port", "8899" }) == 8899);
            ok &= Check("port /port:8123", Config.ResolvePort(new string[] { "/port:8123" }) == 8123);
            ok &= Check("port --port=7777", Config.ResolvePort(new string[] { "--port=7777" }) == 7777);
            ok &= Check("port -port 1000", Config.ResolvePort(new string[] { "-port", "1000" }) == 1000);
            ok &= Check("port invalid ignored", Config.ResolvePort(new string[] { "/port", "abc" }) == Config.DefaultPort);

            // 2. 配置文件读写（隔离目录）
            string tmp = Path.Combine(Path.GetTempPath(), "tb_cfg_" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(tmp);
            Config.DirOverride = tmp;
            Config.Port = 9999;
            Config.AskAutoStartDone = true;
            Config.InjectModeValue = 1;
            Config.Code = "MyCode88";
            Config.Save();
            Config.Port = Config.DefaultPort;
            Config.AskAutoStartDone = false;
            Config.InjectModeValue = 0;
            Config.Code = Config.DefaultCode;
            Config.Load();
            ok &= Check("config roundtrip port", Config.Port == 9999);
            ok &= Check("config roundtrip askAutoStartDone", Config.AskAutoStartDone);
            ok &= Check("config roundtrip injectMode", Config.InjectModeValue == 1);
            ok &= Check("config roundtrip code", Config.Code == "MyCode88");
            Config.DirOverride = null;
            try { Directory.Delete(tmp, true); } catch (Exception) { }

            // 2b. 连接码合法性（字母+数字，1-30 位）
            ok &= Check("code 默认 cdhwan", Config.DefaultCode == "cdhwan");
            ok &= Check("code 合法: 纯英文", Pairing.IsValidCode("cdhwan"));
            ok &= Check("code 合法: 英文数字混合", Pairing.IsValidCode("AbC123xyz"));
            ok &= Check("code 合法: 30 位上限", Pairing.IsValidCode(new string('a', 30)));
            ok &= Check("code 非法: 空", !Pairing.IsValidCode(""));
            ok &= Check("code 非法: 31 位", !Pairing.IsValidCode(new string('a', 31)));
            ok &= Check("code 非法: 含中文", !Pairing.IsValidCode("你好"));
            ok &= Check("code 非法: 含符号", !Pairing.IsValidCode("abc!"));

            // 3. 配对防爆破
            Guard.Clear("1.2.3.4");
            for (int i = 0; i < 4; i++) Guard.RegisterFail("1.2.3.4");
            ok &= Check("guard not blocked at 4", !Guard.IsBlocked("1.2.3.4"));
            Guard.RegisterFail("1.2.3.4");
            ok &= Check("guard blocked at 5", Guard.IsBlocked("1.2.3.4"));
            Guard.Clear("1.2.3.4");
            ok &= Check("guard cleared", !Guard.IsBlocked("1.2.3.4"));

            // 4. EasyTier 网卡名识别
            ok &= Check("easytier name hit", NetDetect.IsEasyTierName("EasyTier"));
            ok &= Check("easytier desc hit", NetDetect.IsEasyTierName("TAP-Windows Adapter V9 (EasyTier)"));
            ok &= Check("easytier case-insensitive", NetDetect.IsEasyTierName("easytier-tap"));
            ok &= Check("non-easytier miss", !NetDetect.IsEasyTierName("Realtek PCIe GbE Family Controller"));

            // 5. JSON 序列化回环（JavaScriptSerializer）
            Dictionary<string, object> d = new Dictionary<string, object>();
            d["port"] = 1234;
            d["url"] = "http://10.126.126.2:8765/m?code=1234";
            JavaScriptSerializer ser = new JavaScriptSerializer();
            string s = ser.Serialize(d);
            Dictionary<string, object> d2 = ser.Deserialize<Dictionary<string, object>>(s);
            ok &= Check("json roundtrip", d2 != null && Convert.ToInt32(d2["port"]) == 1234
                && Convert.ToString(d2["url"]).StartsWith("http://10.126.126.2"));

            Console.WriteLine(ok ? "ALL_UNIT_OK" : "UNIT_FAIL");
            return ok;
        }

        private static bool Check(string name, bool cond)
        {
            Console.WriteLine("  [TEST] " + name + " -> " + (cond ? "PASS" : "FAIL"));
            return cond;
        }
#endif
    }
}
