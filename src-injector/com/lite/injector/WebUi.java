package com.lite.injector;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * 界面宿主: 一个只监听 127.0.0.1 的 HTTP 服务, 页面从 jar 里读出来发过去。
 *
 * 为什么不是 Swing: 想要真模糊、真排版、60fps 的缓动, 这些 Swing 给不了。
 * 为什么用内置 HttpServer 而不是自己拉一个 WebView: JDK 自带, 零依赖, 而且
 * 现有 exe 一行都不用改 —— 它本来就是把 jar 解压出来跑 javaw。
 *
 * 安全: 绑定 127.0.0.1 + 每次启动随机一个 token, 页面所有 API 调用都要带。
 * 不然本机任何进程都能调"注入"这个接口。
 */
final class WebUi {

    /** 每次启动随机一个, 页面所有 API 调用都要带。包内可见是为了让 InjectorSmoke 能打接口。 */
    static final String TOKEN = randomToken();

    /** 页面资源放在 jar 的 /web/ 下 */
    private static final String[] STATIC = {"/index.html", "/app.css", "/app.js"};

    private static String randomToken() {
        StringBuilder sb = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 24; i++) sb.append("0123456789abcdef".charAt(r.nextInt(16)));
        return sb.toString();
    }

    // =====================================================================
    // 运行时状态 (页面轮询这个)
    // =====================================================================

    static final class St {
        volatile String exeDir = ".";
        volatile String agentPath = "lite-agent.jar";
        volatile String user;                    // 已登录账号, null = 未登录
        volatile String sessionToken;            // Kept in memory even when remember-login is off.
        volatile String mode = "offline";
        volatile String authUrl = "";
        volatile boolean autoInject = true;
        volatile boolean anim = true;
        volatile int logKeep = 14;

        volatile boolean busy;
        volatile int pct;
        volatile String step = "";
        volatile String error;
        volatile String targetPid;
        volatile String targetName;
        volatile String operationPid;           // Actual PID of the latest injection, separate from auto-discovery.
        volatile boolean scanned;
        volatile long lastInjectAt;
        volatile String lastResult;
        volatile long startedAt = System.currentTimeMillis();

        final ArrayDeque<String> lines = new ArrayDeque<>();
        volatile int lineSeq;

        void say(String s) {
            String t = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
            synchronized (lines) {
                lines.addLast(t + "  " + s);
                while (lines.size() > Math.max(4, logKeep)) lines.removeFirst();
            }
            lineSeq++;
        }
    }

    static final St S = new St();

    // =====================================================================
    // 启动
    // =====================================================================

    static String start(String agentPath, String exeDir, boolean openWindow) throws IOException {
        S.agentPath = agentPath;
        S.exeDir = exeDir;
        S.authUrl = "";
        S.mode = "offline";
        S.user = "local";
        S.sessionToken = "offline";

        Properties p = Core.settings();
        S.autoInject = "1".equals(p.getProperty("autoInject"));
        S.anim = "1".equals(p.getProperty("anim"));
        try { S.logKeep = Integer.parseInt(p.getProperty("logLines", "14")); } catch (Throwable ignored) {}
        S.logKeep = Math.max(4, Math.min(40, S.logKeep));

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.setExecutor(Executors.newFixedThreadPool(4));
        srv.createContext("/", WebUi::handle);
        srv.start();

        int port = srv.getAddress().getPort();
        String url = "http://127.0.0.1:" + port + "/?t=" + TOKEN;
        // 启动时把环境交代清楚。这几行不是凑数 —— 注入失败时, 用户第一个想问的
        // 就是"用的哪个 JVM / agent 在哪", 少问一轮是一轮。
        // **但认证服务器地址不往界面上写**: 一行日志就够把地址扒出来, 而那台机器上
        // 是账号库和配置库。要排障让它去 ~/.azclient/azclient-auth.txt 里看。
        S.say("AZclient 注入器就绪");
        File af = new File(agentPath);
        S.say("agent: " + af.getName() + (af.isFile() ? "  (" + (af.length() / 1024) + " KB)" : "  [不存在]"));
        S.say("JVM " + System.getProperty("java.version", "?") + " / " + System.getProperty("os.name", "?"));
        S.say("登录模式: " + ("online".equals(S.mode) ? "在线验证" : "离线"));
        S.say("配置目录: " + Core.configDir().getAbsolutePath());
        S.say("日志: " + Core.injectorLog().getAbsolutePath());
        S.say("页面: http://127.0.0.1:" + port + "/");
        if (Core.loadSession() != null) S.say("发现已保存的登录, 正在免密登录...");
        S.say("开始监视 Java 进程 (每 3 秒)");

        if (openWindow) {
            // 走的是哪条路要记下来 —— 用户说"打不开"的时候, 这一行是唯一能分清
            // "装的是 Edge 还是 360" / "这台机器上根本没有浏览器" 的地方。
            // 必须落盘: S.say 只进内存里那个环形缓冲 (见上面的 lines), 用户报问题
            // 的时候早被顶掉了, 等于没记。
            String how = Browser.open(url);
            String line = how == null
                    ? "界面打不开: 这台机器上没有可用的浏览器 (装个 Edge 或 Chrome 再运行)"
                    : "界面已通过 " + how + " 打开";
            S.say(line);
            Core.log(line);
        }
        watchProcs();
        watchdog(url);
        return url;
    }

    /** 页面每 700ms 轮询一次; 超过 20 秒没有任何请求说明窗口关掉了, 自己退出 */
    /**
     * 退出条件。以前是"20 秒没请求就当窗口关了", 这条判据是错的 —— 窗口被游戏盖住时
     * 浏览器会节流定时器, 页面还开着但心跳会断, 于是注入器在用户打游戏的时候自己退出。
     * 现在主判据是页面的 pagehide 信标 (见 app.js), 轮询只当一个很宽松的兜底。
     */
    private static void watchdog(String url) {
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis();
            boolean retried = false;
            while (true) {
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
                long now = System.currentTimeMillis();
                if (lastHit == 0) {
                    // 一直没有页面连上来。除了"用户还没打开", 还有一种情况需要区分:
                    // 浏览器进程起来了但界面根本没出现 (精简版 Windows 把 Edge 删剩一个
                    // 空壳时就是这样), 那时 open() 会报告成功。这里换默认浏览器再试一次,
                    // 而不是让用户对着什么都没有的桌面干等三分钟。
                    // 20 秒是留给冷启动的余量 —— 太短会在慢机器上开出两个窗口。
                    if (!retried && now - t0 > 20000) {
                        retried = true;
                        Browser.retryDefault(url);
                    }
                    if (now - t0 > 180000) {
                        // 落盘: 注入器是 javaw 起的, 没有控制台, 上面那种 println 谁都看不见。
                        // 客户说"注入器自己没了"的时候, 这行是日志里唯一的原因。
                        Core.log("3 分钟内没有任何页面连上来, 自退 (浏览器没开成, 或者根本没浏览器)");
                        System.exit(0);
                    }
                    continue;
                }
                if (byeAt != 0 && now - byeAt > 3000) {
                    System.out.println("页面已关闭, 退出");
                    System.exit(0);
                }
                // 兜底: 页面崩了 / 信标丢了, 5 分钟也该收工
                if (now - lastHit > 300000) {
                    System.out.println("页面长时间无响应, 退出 (reqs=" + REQS.get() + ")");
                    System.exit(0);
                }
            }
        }, "az-watchdog");
        t.setDaemon(true);
        t.start();
    }

    /** 页面已发出关闭信号的时间; 0 表示没有。任何后续请求都会把它清掉。 */
    private static volatile long byeAt;

    private static final java.util.concurrent.atomic.AtomicInteger REQS =
            new java.util.concurrent.atomic.AtomicInteger();

    private static volatile long lastHit;

    // =====================================================================
    // 路由
    // =====================================================================

    private static void handle(HttpExchange ex) throws IOException {
        REQS.incrementAndGet();
        String path = ex.getRequestURI().getPath();
        Map<String, String> q = query(ex.getRequestURI().getQuery());
        Map<String, String> form = "POST".equals(ex.getRequestMethod()) ? form(ex) : q;

        try {
            if (path.equals("/") || path.equals("/index.html")) {
                sendStatic(ex, "/web/index.html", "text/html; charset=utf-8");
                return;
            }
            for (String s : STATIC) {
                if (path.equals(s)) {
                    String ct = s.endsWith(".css") ? "text/css; charset=utf-8"
                            : s.endsWith(".js") ? "application/javascript; charset=utf-8"
                            : "text/plain; charset=utf-8";
                    sendStatic(ex, "/web" + s, ct);
                    return;
                }
            }
            // 窗口图标。Edge 的 --app 窗口拿页面的 favicon 当任务栏图标, 这里以前直接回
            // 204 —— 于是窗口顶着浏览器的默认图标, 和 exe 的图标对不上。不能走 token 校验,
            // 浏览器拿 favicon 时不带参数。
            if (path.equals("/favicon.ico")) {
                sendStatic(ex, "/web/favicon.ico", "image/x-icon");
                return;
            }

            if (path.startsWith("/api/")) {
                // 页面首次加载也要能过: token 在 URL 里
                String tk = first(form.get("t"), q.get("t"));
                if (!TOKEN.equals(tk)) { send(ex, 403, "{\"ok\":false,\"error\":\"bad token\"}"); return; }
                lastHit = System.currentTimeMillis();
                if (!"bye".equals(path.substring(5))) byeAt = 0;   // 有活动就取消待退出
                api(ex, path.substring(5), form);
                return;
            }
            send(ex, 404, "not found");
        } catch (Throwable t) {
            try { send(ex, 500, "{\"ok\":false,\"error\":" + json(String.valueOf(t)) + "}"); }
            catch (Throwable ignored) {}
        } finally {
            ex.close();
        }
    }

    private static void api(HttpExchange ex, String what, Map<String, String> f) throws Exception {
        // 每个请求都算一次心跳
        switch (what) {
            case "state": send(ex, 200, json(state())); return;
            case "scan": doScan(true); send(ex, 200, json(ok())); return;
            case "inject": doInject(f.get("pid")); send(ex, 200, json(ok())); return;
            case "login": send(ex, 200, json(doLogin(f))); return;
            case "register": send(ex, 200, json(doRegister(f))); return;
            case "autologin": send(ex, 200, json(doAutoLogin())); return;
            case "logout": doLogout(); send(ex, 200, json(ok())); return;
            case "account": send(ex, 200, json(doSubscription(null))); return;
            case "redeem": send(ex, 200, json(doSubscription(f.get("invite") == null ? "" : f.get("invite")))); return;
            case "diag": send(ex, 200, json(diag())); return;
            case "logs": send(ex, 200, json(logs(f.get("which")))); return;
            case "settings": doSettings(f); send(ex, 200, json(ok())); return;
            // 页面要关了。不当场退出 —— 先记下时间, 3 秒内如果有新请求进来就取消,
            // 这样用户按 F5 刷新不会把注入器一起带走。
            case "bye": send(ex, 200, json(ok())); byeAt = System.currentTimeMillis(); return;
            default: send(ex, 404, "{\"ok\":false,\"error\":\"unknown api\"}");
        }
    }

    // =====================================================================
    // API 实现
    // =====================================================================

    private static Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }

    private static synchronized Map<String, Object> doLogin(Map<String, String> f) {
        boolean remember = !"0".equals(f.get("remember"));
        Core.Auth r = Core.login(S.exeDir, f.get("user"), f.get("pass"), remember);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!r.ok()) {
            out.put("ok", false);
            out.put("error", r.error);
            return out;
        }
        S.user = (f.get("user") == null ? "" : f.get("user").trim().toLowerCase());
        S.sessionToken = r.token;
        S.say("登录成功: " + S.user);
        out.put("ok", true);
        out.put("user", S.user);
        return out;
    }

    /**
     * 自助注册。和 doLogin 几乎一样 —— 因为注册成功后本来就是登录状态
     * (服务端建号时就把机器码绑好了, 并直接发了令牌)。
     */
    private static synchronized Map<String, Object> doRegister(Map<String, String> f) {
        boolean remember = !"0".equals(f.get("remember"));
        Core.Auth r = Core.register(S.exeDir, f.get("user"), f.get("pass"), f.get("invite"), remember);
        Map<String, Object> out = new LinkedHashMap<>();
        if (!r.ok()) {
            out.put("ok", false);
            out.put("error", r.error);
            return out;
        }
        S.user = (f.get("user") == null ? "" : f.get("user").trim().toLowerCase());
        S.sessionToken = r.token;
        S.say("注册成功, 本机已绑定: " + S.user);
        out.put("ok", true);
        out.put("user", S.user);
        return out;
    }

    private static synchronized Map<String, Object> doAutoLogin() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (S.user != null) { out.put("ok", true); out.put("user", S.user); return out; }
        String[] s = Core.loadSession();
        if (s == null) {
            out.put("ok", false);
            out.put("error", "没有保存的登录");
            // 离线模式下仍然回填账号名
            String u = Core.savedUser();
            if (u != null) out.put("user", u);
            return out;
        }
        if (S.authUrl == null || S.authUrl.isEmpty()) {
            out.put("ok", false);
            out.put("error", "离线模式无法自动登录");
            out.put("user", s[0]);
            return out;
        }
        String err = Core.tokenLogin(S.exeDir, s[0], s[1]);
        if (err != null) {
            // 令牌失效就清掉, 否则每次启动都白跑一趟
            Core.sessionFile().delete();
            out.put("ok", false);
            out.put("error", err);
            out.put("user", s[0]);
            return out;
        }
        S.user = s[0];
        S.sessionToken = s[1];
        S.say("令牌自动登录成功: " + s[0]);
        out.put("ok", true);
        out.put("user", s[0]);
        return out;
    }

    private static synchronized void doLogout() {
        if (S.sessionToken != null) Core.revokeToken(Core.authUrl(S.exeDir), S.sessionToken);
        Core.clearSavedSession(S.exeDir);
        // 账号独占立刻放掉, 不等窗口关掉 —— "退出登录" 之后这个号就该能在别的窗口登。
        // 不手动放也行 (进程退出时 OS 会放), 但那要等到窗口关了。
        Core.releaseAccount();
        S.say("已退出登录: " + (S.user == null ? "-" : S.user));
        S.user = null;
        S.sessionToken = null;
        S.targetPid = null;
        S.targetName = null;
        S.scanned = false;
        S.operationPid = null;
        S.lastResult = null;
        S.pct = 0;
        S.step = "";
        S.error = null;
    }

    private static synchronized Map<String, Object> doSubscription(String code) {
        Map<String, Object> out = Core.subscription(S.exeDir, S.user, S.sessionToken, code);
        if (code != null && Boolean.TRUE.equals(out.get("ok")) && !Boolean.TRUE.equals(out.get("replayed"))) {
            S.say("账号兑换成功，有效期已更新");
        }
        return out;
    }

    private static void doSettings(Map<String, String> f) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (f.containsKey("autoInject")) {
            S.autoInject = !"0".equals(f.get("autoInject"));
            kv.put("autoInject", S.autoInject ? "1" : "0");
        }
        if (f.containsKey("anim")) {
            S.anim = !"0".equals(f.get("anim"));
            kv.put("anim", S.anim ? "1" : "0");
        }
        if (f.containsKey("logLines")) {
            try { S.logKeep = Math.max(4, Math.min(40, Integer.parseInt(f.get("logLines")))); } catch (Throwable ignored) {}
            kv.put("logLines", String.valueOf(S.logKeep));
        }
        Core.saveSettings(kv);
        S.say("设置已保存");
    }

    private static void doScan(boolean verbose) {
        Core.Proc p = Core.findMinecraft();
        S.scanned = true;
        if (p != null) {
            boolean fresh = !p.id.equals(S.targetPid);
            S.targetPid = p.id;
            S.targetName = p.name;
            if (fresh && verbose) S.say("找到目标: PID " + p.id + "  " + shortName(p.name));
            // 这里以前会自动注入。去掉了 —— 自动注入会让"注入"按钮失去意义,
            // 而且游戏一启动就注入的话, 用户还没准备好 (没进世界) 就已经注完了。
            // 现在只负责**检测并显示**目标, 注不注由用户按按钮决定。
            return;
        }
        if (S.targetPid != null) {
            if (verbose) S.say("目标进程已退出");
            S.targetPid = null;
            S.targetName = null;
            S.lastResult = null;
            return;
        }
        if (verbose) S.say("未找到 Minecraft 进程 (已列出 " + Core.listProcs().size() + " 个 JVM)");
    }

    /**
     * 后台盯着进程。游戏是启动器拉起来的, 用户不会记得回来点"重新检测",
     * 所以这里 3 秒扫一次, 一发现目标就报出来 (开了自动注入就直接注)。
     */
    private static void watchProcs() {
        Thread t = new Thread(() -> {
            boolean first = true;
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                try {
                    if (!S.busy) doScan(first);
                    first = false;
                } catch (Throwable ignored) {
                }
            }
        }, "az-scan");
        t.setDaemon(true);
        t.start();
    }

    private static String shortName(String s) {
        if (s == null) return "";
        int i = s.lastIndexOf('.');
        return i >= 0 && i < s.length() - 1 ? s.substring(i + 1) : s;
    }

    /**
     * 「检测到游戏就自动注入」的判据。
     *
     * 单独拎出来是因为它漏过一次, 而且漏得完全无声: 令牌免密登录那条路径当时忘了给
     * S.user 赋值, 于是这个条件恒假 —— 自动注入永远不触发, 界面上一切正常, 只有用户
     * 觉得"说好的自动呢"。抽成纯函数才测得动 (见 InjectorSmoke)。
     */
    static boolean shouldAutoInject(boolean autoInject, String user, boolean busy, String lastResult) {
        // 永远不自动注入 —— 注入只由界面上的按钮触发。
        // 函数留着是为了让那条路径的语义有个明确的落点, 而不是散落在调用处。
        return false;
    }

    private static void doInject(String pid) {
        if (S.busy) return;
        final String target = (pid != null && !pid.isEmpty()) ? pid : S.targetPid;
        if (target == null) {
            S.error = "没有可注入的目标进程";
            S.say(S.error);
            return;
        }
        S.operationPid = target;
        S.busy = true;
        S.pct = 0;
        S.error = null;
        S.lastResult = null;
        S.lastInjectAt = System.currentTimeMillis();
        S.say("开始注入 PID " + target);
        Thread t = new Thread(() -> {
            try {
                S.step = "正在加载本地组件 ...";
                Core.inject(S.agentPath, target, (pct, text) -> {
                    S.pct = pct;
                    S.step = text;
                    S.say(text);
                });
                S.lastResult = S.step;
            } catch (Throwable e) {
                S.error = String.valueOf(e.getMessage());
                S.lastResult = "注入失败: " + S.error;
                S.say("注入失败: " + S.error);
            } finally {
                S.busy = false;
            }
        }, "az-inject");
        t.setDaemon(true);
        t.start();
    }

    private static Map<String, Object> state() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("loggedIn", true);
        m.put("user", "本地模式");
        m.put("mode", S.mode);
        // 机器码。本地算出来的 (注册表 MachineGuid 的哈希), 不是服务端发的 ——
        // 所以它必须在这里露出去, 登录页才有东西可以给用户抄。
        // 取不到就给空串, 前端据此显示"读不到", 而不是显示一个假的。
        String mach = Core.machineCode();
        m.put("machine", mach == null ? "" : mach);
        // authUrl **不往外发**。前端已经不显示它了, 再留在接口里等于谁开一下
        // devtools 就能看见账号库所在那台机器 —— 而它在这个页面上没有任何用途。
        m.put("autoInject", S.autoInject);
        m.put("anim", S.anim);
        m.put("logKeep", S.logKeep);
        m.put("savedUser", "");
        m.put("hasToken", false);

        Map<String, Object> inj = new LinkedHashMap<>();
        inj.put("busy", S.busy);
        inj.put("pct", S.pct);
        inj.put("step", S.step);
        inj.put("error", S.error == null ? "" : S.error);
        inj.put("result", S.lastResult == null ? "" : S.lastResult);
        inj.put("pid", S.targetPid == null ? "" : S.targetPid);
        inj.put("target", S.targetName == null ? "" : S.targetName);
        inj.put("operationPid", S.operationPid == null ? "" : S.operationPid);
        inj.put("scanned", S.scanned);
        inj.put("uptime", (System.currentTimeMillis() - S.startedAt) / 1000);
        m.put("inject", inj);

        List<Object> ls = new ArrayList<>();
        synchronized (S.lines) { for (String s : S.lines) ls.add(s); }
        m.put("lines", ls);

        m.put("procs", procList());
        m.put("stats", stats());
        return m;
    }

    private static List<Object> procList() {
        List<Object> out = new ArrayList<>();
        for (Core.Proc p : Core.listProcs()) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("pid", p.id);
            o.put("name", p.name);
            o.put("mc", p.minecraft);
            out.add(o);
        }
        return out;
    }

    /** 仪表盘的数据。全是真量出来的, 编的迟早会被当真。 */
    private static Map<String, Object> stats() {
        Map<String, Object> s = new LinkedHashMap<>();
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        s.put("jvmUsed", used);
        s.put("jvmMax", rt.maxMemory());
        long[] pm = physMem();
        s.put("physUsed", pm == null ? -1 : pm[0]);
        s.put("physTotal", pm == null ? -1 : pm[1]);
        s.put("cpu", cpuLoad());
        s.put("threads", Thread.activeCount());
        s.put("ping", pingMs);
        return s;
    }

    private static Class<?> osBean;
    private static java.lang.reflect.Method mCpu, mTot, mFree;

    static {
        try {
            osBean = Class.forName("com.sun.management.OperatingSystemMXBean");
            mCpu = osBean.getMethod("getSystemCpuLoad");
            mTot = osBean.getMethod("getTotalPhysicalMemorySize");
            mFree = osBean.getMethod("getFreePhysicalMemorySize");
        } catch (Throwable ignored) {
        }
    }

    /** 反射而不是直接强转: 精简 JRE 上没有 com.sun.management, 那段代码在请求线程上, 抛出去就是 500 */
    private static float cpuLoad() {
        if (mCpu == null) return -1;
        try {
            Object b = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            double v = (Double) mCpu.invoke(b);
            return v < 0 ? -1 : (float) v;
        } catch (Throwable t) { return -1; }
    }

    private static long[] physMem() {
        if (mTot == null || mFree == null) return null;
        try {
            Object b = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            long tot = (Long) mTot.invoke(b), free = (Long) mFree.invoke(b);
            return tot <= 0 ? null : new long[]{tot - free, tot};
        } catch (Throwable t) { return null; }
    }

    static volatile int pingMs = -1;

    /** 后台真打一次服务器, 结果缓存。绝不放在请求线程上 */
    static void pingAsync(final String url) {
        if (url == null || url.isEmpty()) return;
        Thread t = new Thread(() -> {
            try {
                java.net.URL u = new java.net.URL(url);
                int port = u.getPort() > 0 ? u.getPort() : 80;
                long t0 = System.nanoTime();
                java.net.Socket s = new java.net.Socket();
                s.connect(new java.net.InetSocketAddress(u.getHost(), port), 1500);
                s.close();
                pingMs = (int) ((System.nanoTime() - t0) / 1000000L);
            } catch (Throwable e) { pingMs = -2; }
        }, "az-ping");
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================
    // 诊断 / 日志 / 关于
    // =====================================================================

    private static Map<String, Object> diag() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();

        items.add(check("Java 运行时", System.getProperty("java.version", "?")
                + "  " + System.getProperty("java.vendor", ""), true));
        items.add(check("操作系统", System.getProperty("os.name", "?") + " "
                + System.getProperty("os.arch", "?"), true));
        items.add(check("Java 路径", System.getProperty("java.home", "?"), true));
        items.add(check("Java 位数", System.getProperty("sun.arch.data.model", "?"), true));
        File perfRoot = new File(System.getProperty("java.io.tmpdir", "."));
        items.add(check("临时目录", perfRoot.getAbsolutePath() + "；可读=" + perfRoot.canRead(),
                perfRoot.isDirectory() && perfRoot.canRead()));

        File agent = new File(S.agentPath);
        boolean agentOk = agent.isFile() && agent.length() > 0;
        items.add(check("agent 包", agentOk
                ? agent.getName() + "  " + (agent.length() / 1024) + " KB" : "找不到 " + S.agentPath, agentOk));

        int n = 0;
        try { n = Core.listProcs().size(); } catch (Throwable ignored) { }
        items.add(check("JVM 枚举", Core.jvmDiscoveryStatus, Core.jvmDiscoveryAvailable));
        items.add(check("Windows 枚举", WindowsProcesses.status, WindowsProcesses.available));
        items.add(check("合并进程数", n + "；系统可见不等于有附加权限", n > 0));

        boolean logOk;
        try {
            Core.log("--- diag ---");
            logOk = Core.injectorLog().canWrite();
        } catch (Throwable t) { logOk = false; }
        items.add(check("日志可写", Core.injectorLog().getAbsolutePath(), logOk));

        int p = pingMs;
        items.add(check("服务器连通", p == -1 ? "尚未测量" : p == -2 ? "连接失败" : p + " ms",
                p >= 0 || p == -1));

        boolean sess = Core.sessionFile().isFile();
        items.add(check("会话文件", sess ? Core.sessionFile().getAbsolutePath() : "未保存登录", true));

        boolean hook = Core.tail(Core.agentLog(), 4096).contains("Hook init OK");
        // pass 必须是 hook 本身。写死成 true 的话这一项恒绿: 面板上写着"游戏内未注入",
        // 旁边却是通过的标记, 于是"注入了但 hook 没挂上"这种状态永远没人发现。
        // 另外这个判断只扫日志尾部 4KB —— 日志涨过 4KB 后它会退化成误报"未注入",
        // 所以只能当"最近一次有没有成功"看, 不能当"从来没成功过"看。
        items.add(check("Hook 状态", hook ? "游戏内已注入" : "游戏内未注入", hook));

        out.put("ok", true);
        out.put("items", items);
        return out;
    }

    private static Map<String, Object> check(String name, String value, boolean pass) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("pass", pass);
        return m;
    }

    private static Map<String, Object> logs(String which) {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean agent = "agent".equals(which);
        File f = agent ? Core.agentLog() : Core.injectorLog();
        String text = f.isFile() ? Core.tail(f, 20000) : "";
        out.put("ok", true);
        out.put("which", agent ? "agent" : "injector");
        out.put("path", f.getAbsolutePath());
        out.put("exists", f.isFile());
        out.put("size", f.isFile() ? f.length() : 0);
        out.put("text", text);
        return out;
    }

    // =====================================================================
    // HTTP 小工具
    // =====================================================================

    private static String first(String a, String b) { return a != null ? a : b; }

    private static Map<String, String> query(String q) {
        Map<String, String> m = new LinkedHashMap<>();
        if (q == null) return m;
        for (String kv : q.split("&")) {
            int i = kv.indexOf('=');
            if (i < 0) continue;
            try {
                m.put(dec(kv.substring(0, i)), dec(kv.substring(i + 1)));
            } catch (Throwable ignored) {}
        }
        return m;
    }

    private static Map<String, String> form(HttpExchange ex) {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            InputStream in = ex.getRequestBody();
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return query(new String(bo.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            return new LinkedHashMap<>();
        }
    }

    private static String dec(String s) throws Exception { return URLDecoder.decode(s, "UTF-8"); }

    private static void sendStatic(HttpExchange ex, String res, String ct) throws IOException {
        InputStream in = WebUi.class.getResourceAsStream(res);
        if (in == null) { send(ex, 404, "missing resource " + res); return; }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        byte[] b = bo.toByteArray();
        ex.getResponseHeaders().add("Content-Type", ct);
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
    }

    // =====================================================================
    // JSON
    // =====================================================================

    static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
        if (o == null) { sb.append("null"); return; }
        if (o instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (o instanceof Collection) {
            sb.append('[');
            boolean first = true;
            for (Object v : (Collection<?>) o) {
                if (!first) sb.append(',');
                first = false;
                write(sb, v);
            }
            sb.append(']');
            return;
        }
        if (o instanceof Number || o instanceof Boolean) { sb.append(o); return; }
        str(sb, String.valueOf(o));
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20 || c == 0x2028 || c == 0x2029) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
