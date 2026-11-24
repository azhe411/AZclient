package com.lite.injector;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.io.File;
import java.net.URI;

/**
 * 把界面窗口开起来。
 *
 * 用 Edge/Chrome 的 --app 模式: 没有地址栏、没有标签页, 看起来就是一个普通应用窗口。
 * 独立 user-data-dir 是为了不碰用户自己的浏览器配置, 也避免"浏览器正在运行"时
 * --app 参数被忽略、结果开成普通标签页。
 *
 * 这个文件是全项目唯一碰 java.awt 的地方 (算什么尺寸、开什么窗口), 逻辑那边不许沾。
 */
final class Browser {

    private Browser() {}

    private static final String[] CANDIDATES = {
            "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
    };

    static File profileDir() {
        File d = new File(Core.configDir(), "webprofile");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private static String find() {
        // 用户自己指定浏览器 (便携版、装在怪地方)。设了就**只认它** —— 人家明确告诉
        // 我们浏览器在哪, 再自作主张去猜别的更糟; 路径不对就返回 null, 让 open()
        // 退到系统默认浏览器那条路。
        String forced = System.getProperty("azclient.browser");
        if (forced != null && !forced.isEmpty()) {
            File f = new File(forced);
            return f.isFile() ? f.getAbsolutePath() : null;
        }
        for (String p : CANDIDATES) if (new File(p).isFile()) return p;
        // App Paths 注册表兜底 (装在非默认位置时)
        for (String exe : new String[]{"msedge.exe", "chrome.exe"}) {
            for (String root : new String[]{
                    "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\",
                    "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\App Paths\\"}) {
                try {
                    Process p = new ProcessBuilder("reg", "query", root + exe, "/ve").start();
                    java.io.BufferedReader br = new java.io.BufferedReader(
                            new java.io.InputStreamReader(p.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) {
                        int i = line.indexOf("REG_SZ");
                        if (i > 0) {
                            String path = line.substring(i + 6).trim();
                            if (new File(path).isFile()) return path;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        return null;
    }

    /**
     * 把界面开起来。返回一句话说明走的是哪条路 (给日志用); 一条都不通返回 null。
     *
     * 三级兜底, 顺序是有讲究的:
     *   1. Edge / Chrome 的 --app 模式 —— 体验最好: 没有地址栏和标签页, 像个应用
     *   2. 系统默认浏览器           —— 覆盖一切装在非标准位置的浏览器: Firefox、
     *      360、QQ 浏览器, 以及精简版 Windows 上剩下什么就用什么
     *   3. 消息框                   —— 真的一个浏览器都没有时, 至少让用户知道
     *
     * 第 2 步里 Desktop 排在 rundll32 前面, 因为**它会报错**: ShellExecute 失败时抛
     * IOException, 我们能据此判断该不该往下走; 而 rundll32 无论如何都返回 0, 问不出结果。
     */
    static String open(String url) {
        String exe = find();
        if (exe != null) {
            try {
                // 800x660 是算出来的下限, 不是随便挑的: 去掉窗口边框后网页可用约
                // 786x623, 减去页头 59 和状态条 31, 卡片区剩 497px。注册模式那张卡
                // 要 482px (登录 383, 左栏 443), 所以高度再矮就出滚动条 —— 600 高
                // 只剩 424px, 溢 58px。宽度也到边了: 两张卡 310+392 加 16 的间距和
                // 36 的内边距是 758, 786 只多 28px。
                // 改了这里就要重新量一次, 或者干脆把 .gate 的滚动当成兜底。
                int[] wh = {800, 660};
                int sw = 1920, sh = 1080;
                try {
                    java.awt.Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
                    sw = d.width;
                    sh = d.height;
                } catch (Throwable ignored) {}
                int x = Math.max(0, (sw - wh[0]) / 2);
                int y = Math.max(0, (sh - wh[1]) / 3);

                new ProcessBuilder(exe,
                        "--app=" + url,
                        "--user-data-dir=" + profileDir().getAbsolutePath(),
                        "--no-first-run", "--no-default-browser-check",
                        "--disable-features=Translate,msEdgeIdentityFeatures",
                        // 这三个是必须的, 不是调优: 注入器窗口迟早会被游戏盖住, 而
                        // Chromium 默认会把被遮挡页面的定时器节流到每分钟一次 ——
                        // 那样页面看上去还在, 心跳却断了, 守护线程会把注入器当成
                        // "窗口已关"给收掉。用户开游戏十分钟后回来发现问题, 已经晚了。
                        "--disable-background-timer-throttling",
                        "--disable-backgrounding-occluded-windows",
                        "--disable-renderer-backgrounding",
                        "--window-size=" + wh[0] + "," + wh[1],
                        "--window-position=" + x + "," + y).start();
                return "应用窗口 (" + new File(exe).getName() + ")";
            } catch (Throwable ignored) {
            }
        }
        String how = viaDefault(url);
        if (how != null) return how;
        complain(url);
        return null;
    }

    /**
     * 用系统默认浏览器打开。成功返回说明文字, 都不通返回 null。
     *
     * Desktop 排在 rundll32 前面是因为**它会报错** —— ShellExecute 失败时抛 IOException,
     * 我们能据此判断该不该往下走; 而 rundll32 无论如何都返回 0, 问不出结果。
     * rundll32 留作最后一招, 好处是**不依赖 AWT** —— 上面那条要先把 AWT 初始化起来,
     * 而这台机器上 AWT 能不能起来是另一回事。
     */
    private static String viaDefault(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
                return "默认浏览器";
            }
        } catch (Throwable ignored) {
        }
        try {
            new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            return "默认浏览器 (shell)";
        } catch (Throwable ignored) {
        }
        return null;
    }

    /**
     * 守护线程用: 页面迟迟没连上来, 说明第一次虽然"启动成功"但界面根本没出现。
     *
     * 最典型的原因是精简版 Windows 上被删剩空壳的 msedge.exe —— 启动即退出, 什么都不开,
     * 而 open() 那边只看得见"进程起来了", 于是报告成功。那里没法当场判断 (Chromium 的
     * 启动进程在复用自己的 profile 时也会立刻退出, 拿退出码判断会误伤), 但页面连没连上
     * 是确定的事实。所以换默认浏览器再试一次, 而不是让用户干等三分钟。
     */
    static void retryDefault(String url) {
        String how = viaDefault(url);
        // 打到 Core.log 而不是 System.out: 注入器是 javaw 起的, 没有控制台,
        // println 出去的字谁都看不见。
        if (how == null) complain(url);
        else Core.log("页面一直没连上 (第一次启动的浏览器没把界面打开), 已改用 " + how + " 重新打开");
    }

    /**
     * 这台机器上一个浏览器都没有。这是**唯一**还通知得到用户的通道。
     *
     * 不能只往 stderr 打一行 —— 启动器是 winexe, 没有控制台, 那句话没人看得见。
     * 用户看到的现象是"双击了, 什么都没发生", 而那个现象和崩溃、没装 Java、
     * 被杀软删掉一模一样, 是最难查的一类反馈。
     */
    private static boolean complained = false;

    // 不是 private: 这是最后一道兜底, 而它恰恰是最没法在真机上复现的一条路径
    // (总不能让用户把浏览器卸了来测)。开放给 probe 直接调, 至少能验证它真的会
    // 落文件、真的会弹框。
    /** 一个浏览器都没有时给用户看的话。抽成纯函数是为了能断言它 —— 见 BrowserProbe。 */
    static String noBrowserMessage(String url) {
        return "没找到可用的浏览器, 界面开不起来。\n\n"
                + "装一个 Edge 或 Chrome 再运行即可 —— 任意浏览器都行, 装完不用做任何设置。\n\n"
                + "如果本来就有浏览器, 也可以手动打开这个地址:\n" + url;
    }

    static void complain(String url) {
        if (complained) return;        // 只烦用户一次: 主路径失败和守护线程重试失败都会走到这里
        complained = true;
        String msg = noBrowserMessage(url);
        // 先落一个文件。万一消息框也弹不出来, 至少还留下痕迹, 不至于彻底无声。
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(
                    new java.io.FileOutputStream(new File(Core.configDir(), "no-browser.txt")), true);
            w.println(msg);
            w.close();
        } catch (Throwable ignored) {}
        try {
            javax.swing.JOptionPane.showMessageDialog(null, msg, "AZclient",
                    javax.swing.JOptionPane.ERROR_MESSAGE);
        } catch (Throwable ignored) {
            // AWT 起不来, 消息框都弹不出来。文件已经写了, 到此为止。
        }
    }
}
