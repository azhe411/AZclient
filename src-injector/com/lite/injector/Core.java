package com.lite.injector;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.*;

/**
 * 无头核心: 登录 / 会话 / 进程探测 / 注入。
 *
 * 这里原来长在 LoginFrame 和 InjectorGui 里面, 逻辑和 Swing 控件是缠在一起的 ——
 * 想换个界面就得把几百行逻辑跟着控件一起重写, 所以以前"换个皮"的成本高到不敢动。
 * 现在全搬到这里, 界面那边只剩"显示"和"点击"两件事。
 *
 * 一条硬规矩: 这个类不许 import 任何 javax.swing / java.awt。
 * 一旦破了这条, 后面又会缠回去。
 */
final class Core {

    private Core() {}

    /** 当前 EXE 内嵌的 agent 版本。旧 agent 已经在 JVM 里时，类加载器会继续复用旧 Hook，
     * 继续显示“注入完成”会让用户误以为新版 GUI 已经生效。 */
    private static final String EXPECTED_AGENT_VERSION = "AZclient 8.0.0";

    // =====================================================================
    // 会话 (记住登录)
    // =====================================================================

    /**
     * 编译期默认服务器地址。azclient-auth.txt 找不到时兜底, 免得直接掉进离线模式
     * 而用户完全不知道发生了什么。仍然优先读文件, 所以换服务器只要改那个 txt。
     */
    static final String DEFAULT_AUTH_URL = "";

    static File configDir() {
        File dir = new File(System.getProperty("user.home"), ".azclient");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    static File sessionFile() { return new File(configDir(), "session.properties"); }

    /** 读已保存的 账号+令牌; 没有或读不出来返回 null */
    static String[] loadSession() {
        File f = sessionFile();
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            String u = p.getProperty("user", "").trim().toLowerCase();
            String t = p.getProperty("token", "").trim().toLowerCase();
            if (u.isEmpty() || !t.matches("[0-9a-f]{64}")) return null;
            return new String[]{u, t};
        } catch (Throwable ignored) {
            return null;
        }
    }

    static String savedUser() {
        File f = sessionFile();
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            Properties p = new Properties();
            p.load(in);
            String u = p.getProperty("user", "").trim();
            return u.isEmpty() ? null : u;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void saveSession(String exeDir, String user, String token) {
        try (OutputStream os = new FileOutputStream(sessionFile())) {
            Properties p = new Properties();
            p.setProperty("user", user);
            p.setProperty("token", token);
            // 顺手把服务器地址也存进来: 注入进去的 agent 不知道 exe 在哪个目录,
            // 光靠搜 azclient-auth.txt 会找不到, 于是"登录能用但分享配置不能用"。
            String u = authUrl(exeDir);
            if (u != null && u.length() > 0) p.setProperty("url", u);
            p.store(os, "AZclient 登录令牌 (不是密码). 删除本文件即可忘记登录.");
        } catch (Throwable ignored) {
        }
    }

    /** 离线模式没有令牌, 只记账号名 (下次启动回填输入框) */
    static void saveUserOnly(String user) {
        try (OutputStream os = new FileOutputStream(sessionFile())) {
            Properties p = new Properties();
            p.setProperty("user", user);
            p.setProperty("token", "");
            p.store(os, "AZclient 记住的账号 (离线模式, 无令牌).");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 退出登录: 通知服务端吊销令牌, 清掉本地记录。
     * 令牌吊销是尽力而为 —— 服务端不通也得让人退出去, 否则断网就锁死在一个账号上。
     */
    static void clearSavedSession(String exeDir) {
        String[] s = loadSession();
        if (s != null) revokeToken(authUrl(exeDir), s[1]);
        sessionFile().delete();
    }

    static void revokeToken(final String url, final String token) {
        if (url == null || url.isEmpty() || token == null) return;
        Thread t = new Thread(() -> {
            try { post(url, "action=logout&token=" + token); } catch (Throwable ignored) {}
        }, "az-revoke");
        t.setDaemon(true);
        t.start();
    }

    // =====================================================================
    // 账号独占: 一个账号同时只允许一个窗口在线
    // =====================================================================

    private static FileChannel accChan;
    private static FileLock accLock;
    private static String accUser;

    /**
     * 尝试独占这个账号。拿到返回 null; 被别的窗口占着, 返回给用户看的原因。
     *
     * **为什么是本地文件锁, 而不是让服务端判断:**
     *
     *   1. 机器绑定已经把账号钉死在**一台**机器上 (服务端 machineCheck), 所以"另一个窗口"
     *      只可能是同一台机器上的另一个进程 —— 本地锁正好管住这一层, 不用改服务端。
     *   2. 锁是**进程级**的: 进程一死 (正常退出 / 崩溃 / 断电) 由操作系统自动释放。
     *      服务端方案做不到这一点 —— 它不知道谁还活着, 只能靠心跳 + 租约硬猜, 崩溃之后
     *      还得干等租约过期才能再登; 而且心跳要跟配置保存抢"每 IP 每分钟 20 次"的限流预算。
     *   3. 注入器本来就是"页面关掉就 System.exit"的 (见 WebUi.watchdog), 所以这把锁的
     *      生命周期正好等于"窗口开着且已登录" —— 关窗口 = 释放, 不需要额外钩子。
     *
     * 锁文件按账号名分: 同一台机器上开两个**不同**账号是允许的, 服务端也是这么设计的
     * (machine 列刻意没加 UNIQUE), 所以这里不能做成"整台机器只准开一个窗口"。
     *
     * 崩溃留下的空锁文件无害: 文件还在, 锁已经随进程没了, 下次 tryLock 直接成功。
     *
     * 连锁文件都建不出来 (目录只读之类) 时**放行**并记一条日志 —— 那是环境问题,
     * 不该把人挡在登录页外面, 而服务端那边对账号的校验一点都没少。
     */
    static synchronized String acquireAccount(String user) {
        String u = user == null ? "" : user.trim().toLowerCase();
        if (u.isEmpty()) return null;
        // 同一个窗口重复登录 (比如密码打错重来) 不该被自己的锁挡住
        if (u.equals(accUser) && accLock != null && accLock.isValid()) return null;
        releaseAccount();                       // 换账号: 先把旧账号放掉
        FileChannel ch = null;
        try {
            File f = new File(configDir(), "lock-" + sha256(u).substring(0, 16) + ".lck");
            ch = new RandomAccessFile(f, "rw").getChannel();
            FileLock lk = ch.tryLock();
            if (lk == null) {
                ch.close();
                log("账号 " + u + " 已经被另一个窗口占用, 拒绝登录");
                return "该账号已在另一个窗口登录 —— 请先关掉那个窗口, 或者到那边退出登录";
            }
            accChan = ch;
            accLock = lk;
            accUser = u;
            log("账号 " + u + " 独占成功 (锁 " + f.getName() + ")");
            return null;
        } catch (java.nio.channels.OverlappingFileLockException e) {
            // 同一个进程里已经有别的通道拿着这把锁。**必须当成"被占用"**, 不能落到下面
            // 那个放行分支 —— 那里是留给"目录不可写"这种环境问题的, 拿它兜住互斥失败
            // 就等于锁形同虚设, 而且失败得悄无声息。
            try { if (ch != null) ch.close(); } catch (Throwable ignored) {}
            log("账号 " + u + " 被本进程的另一个通道占着, 拒绝登录");
            return "该账号已在另一个窗口登录 —— 请先关掉那个窗口, 或者到那边退出登录";
        } catch (Throwable t) {
            try { if (ch != null) ch.close(); } catch (Throwable ignored) {}
            log("账号锁不可用, 本次放行: " + t);
            return null;
        }
    }

    /**
     * 放掉独占。退出登录时调, 让账号立刻能在别的窗口登; 不调也行 ——
     * 进程退出时操作系统会释放, 这里只是让"退出登录"不用等窗口关掉。
     */
    static synchronized void releaseAccount() {
        try { if (accLock != null && accLock.isValid()) accLock.release(); } catch (Throwable ignored) {}
        try { if (accChan != null) accChan.close(); } catch (Throwable ignored) {}
        accChan = null;
        accLock = null;
        accUser = null;
    }

    // =====================================================================
    // 验证
    // =====================================================================

    /** exe 同目录 azclient-auth.txt, 形如 url=http://.../auth */
    static String authUrl(String exeDir) {
        return "";
    }

    /** 把服务端返回的纯文本翻成给用户看的中文; OK 返回 null */
    static String describe(String resp) {
        if (resp.startsWith("OK")) return null;
        // 账号现在是自助注册的, 所以"不存在"最常见的原因是账号名打错了,
        // 而不是"还没给你开通"。别把人往找管理员那条路上引。
        if (resp.startsWith("NOUSER")) return "账号不存在 —— 检查拼写, 或者用兑换码注册一个";
        if (resp.startsWith("BADPASS")) return "密码错误";
        if (resp.startsWith("BADTOKEN")) return "登录已过期, 请重新输入密码";
        if (resp.startsWith("BAN")) return "该账号已被封禁";
        // 机器绑定。这两条必须晚于密码校验才会出现 (服务端顺序如此), 所以看到它们
        // 就说明密码是对的 —— 提示里可以直接让用户去抄机器码, 不用再防"这人在猜账号"。
        if (resp.startsWith("NOMACHINE")) return "此离线版本不使用机器绑定";
        if (resp.startsWith("BADMACHINE")) return "此离线版本不使用机器绑定";
        if (resp.startsWith("ERR:action")) return "服务器暂不支持此操作，请联系管理员更新服务";
        // 自助注册
        if (resp.startsWith("BADINVITE")) return "兑换码无效或已使用，请检查后重试";
        if (resp.startsWith("ALREADYUSED")) return "这个兑换码已用于注册，续费请使用新购买的兑换码";
        if (resp.startsWith("PERMANENT")) return "账号已是永久有效，无需续费，本次未消耗兑换码";
        if (resp.startsWith("ERR:db")) return "服务暂时无法完成兑换，请稍后用同一码重试";
        if (resp.startsWith("USERTAKEN")) return "这个账号已经被注册了";
        // 走到这里说明用户已经填了非空的码 (空的情况在 register() 里就拦了),
        // 所以服务端这个码只可能是"格式不对", 不是"没填"。别把话说反。
        if (resp.startsWith("ERR:invite")) return "兑换码格式不对 (12 位字母数字, 横线可省)";
        if (resp.startsWith("ERR:username")) return "账号只能包含字母/数字/下划线/点/横线/@, 长度 2-40 位";
        if (resp.startsWith("ERR:hash")) return "密码长度不对";
        if (resp.startsWith("ERR:nomachine")) return "此离线版本不使用机器绑定";
        if (resp.startsWith("ERR:ratelimit")) return "尝试太频繁, 请稍后再试";
        if (resp.isEmpty()) return "服务器无响应";
        return resp;
    }

    static String post(String url, String body) throws Exception {
        throw new IOException("Offline/open-source edition has no network transport");
    }

    /** Current in-memory login only; never pick credentials from another saved account. */
    static Map<String, Object> subscription(String exeDir, String user, String token, String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        if (user == null || token == null || !token.matches("[0-9a-f]{64}")) {
            out.put("error", "请先使用在线账号登录，再查看有效期或兑换续费");
            return out;
        }
        if (code != null && code.trim().isEmpty()) {
            out.put("error", "请输入兑换码");
            return out;
        }
        try {
            String url = authUrl(exeDir);
            if (url == null || url.isEmpty()) throw new Exception("离线模式不支持兑换");
            String body = "action=" + (code == null ? "account" : "redeem")
                    + "&user=" + URLEncoder.encode(user, "UTF-8")
                    + "&token=" + token + machineParam();
            if (code != null) body += "&invite=" + URLEncoder.encode(code.trim(), "UTF-8");
            String response = post(url, body);
            String error = describe(response);
            if (error != null) { out.put("error", error); return out; }
            String[] parts = response.trim().split("\\s+");
            if (parts.length != (code == null ? 3 : 5) || !"OK".equals(parts[0]))
                throw new Exception("服务器返回的订阅信息无效");
            long expires = Long.parseLong(parts[1]);
            long now = Long.parseLong(parts[code == null ? 2 : 3]);
            if (expires < 0 || expires > 8640000000000000L || now <= 0) throw new Exception("订阅时间无效");
            if (code != null) {
                int days = Integer.parseInt(parts[2]);
                if (days < 0 || days > 36500 || !("0".equals(parts[4]) || "1".equals(parts[4])))
                    throw new Exception("兑换结果无效");
                out.put("days", days);
                out.put("replayed", "1".equals(parts[4]));
            }
            out.put("ok", true);
            out.put("expiresAt", expires);
            out.put("serverNow", now);
            out.put("permanent", expires == 0);
            out.put("expired", expires > 0 && expires <= now);
        } catch (Exception e) {
            out.put("error", (code == null ? "读取有效期失败：" : "兑换结果尚未确认，请保留同一码重试：") + e.getMessage());
        }
        return out;
    }

    /** Fresh server authorization before attach, independent of the page's login state. */
    static void requireActiveSubscription(String exeDir, String user, String token) throws IOException {
        // No account or subscription gate in the offline/open-source edition.
    }

    // =====================================================================
    // 机器码 (账号绑机器)
    // =====================================================================

    private static volatile String machineCache = null;
    private static volatile boolean machineTried = false;

    /**
     * 本机机器码, 形如 {@code A1B2-C3D4-E5F6-7890}; 取不到时返回 null。
     *
     * 用途只有一个: 一个账号别在十台机器上同时用。**它不是安全边界** ——
     * 源头是注册表里的一个字符串, 会改注册表的人随时能伪造。挡的是"把 exe 和账号
     * 一起发群里", 不是挡破解。以后别拿它当防线用。
     *
     * 取 MachineGuid (HKLM\SOFTWARE\Microsoft\Cryptography): 装系统时生成一次, 之后
     * 不重装就不变。比 MAC (插个虚拟网卡就变) 和主板序列号 (不少主板直接返回
     * "To be filled by O.E.M." 这种占位串) 都稳。
     *
     * 不直接发 GUID, 而是哈希取前 16 位十六进制:
     *   1. 用户要把它念给管理员听、手抄进聊天框, 36 字符的 GUID 太长;
     *   2. 原始 GUID 泄漏没有好处 —— 它本身就是一个能被拿来伪造的值。
     *
     * 取不到就返回 **null, 不返回占位码**: 占位码会让所有人共用同一个值, 等于绑定失效
     * 而没人发现。null 会让服务端直接拒掉, 是失败关闭。
     */
    static String machineCode() {
        if (machineTried) return machineCache;
        synchronized (Core.class) {
            if (machineTried) return machineCache;
            String guid = machineGuid();
            if (guid != null) {
                try {
                    String h = sha256("AZclient/1|" + guid).toUpperCase(Locale.ROOT);
                    machineCache = h.substring(0, 4) + "-" + h.substring(4, 8)
                            + "-" + h.substring(8, 12) + "-" + h.substring(12, 16);
                } catch (Throwable ignored) { }
            }
            // 失败也只试一次 —— 界面每 700ms 刷一次状态, 不缓存就是每 700ms 开一次 reg.exe
            machineTried = true;
            return machineCache;
        }
    }

    /**
     * 读注册表里的 MachineGuid。
     *
     * reg.exe 的输出里除了 GUID 全是**本地化**文本 (中文 Windows 上连 "REG_SZ" 旁边都是中文),
     * 按行按位置去解析迟早在某个语言版本上错位。所以这里干脆不去理解它: 按 ISO-8859-1
     * 逐字节读进来 (不丢字节), 再用 GUID 的形状直接抓出来。
     */
    private static String machineGuid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ByteArrayOutputStream bo = new ByteArrayOutputStream(256);
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[1024];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            }
            p.waitFor();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    .matcher(new String(bo.toByteArray(), "ISO-8859-1"));
            return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    static String sha256(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] d = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** 结果: token 或 error, 二选一 */
    static final class Auth {
        final String token, error;
        Auth(String token, String error) { this.token = token; this.error = error; }
        boolean ok() { return error == null; }
    }

    static Auth remoteAuth(String url, String user, String hash) throws Exception {
        String resp = post(url, "action=login"
                + "&user=" + URLEncoder.encode(user, "UTF-8")
                + "&pass=" + URLEncoder.encode(hash, "UTF-8")
                + machineParam());
        String err = describe(resp);
        if (err != null) return new Auth(null, err);
        String token = resp.length() > 2 ? resp.substring(2).trim().toLowerCase() : "";
        return new Auth(token.matches("[0-9a-f]{64}") ? token : null, null);
    }

    /**
     * 机器码请求参数。
     *
     * 取不到时**照样发, 只是发空串** —— 不发就等于"老客户端", 服务端没法区分
     * "这台机器读不出机器码"和"这是个还没升级的客户端", 而这两件事该给用户完全不同的提示。
     */
    private static String machineParam() throws Exception {
        String m = machineCode();
        return "&machine=" + URLEncoder.encode(m == null ? "" : m, "UTF-8");
    }

    static File localDb() { return new File(configDir(), "accounts.properties"); }

    static boolean localAuth(String user, String hash) throws Exception {
        Properties p = new Properties();
        File db = localDb();
        if (db.isFile()) {
            try (InputStream in = new FileInputStream(db)) { p.load(in); }
        }
        String stored = p.getProperty("u." + user.toLowerCase());
        if (stored == null) throw new Exception("账号不存在, 请联系管理员开通");
        return stored.equals(hash);
    }

    /**
     * 账密登录。返回的 Auth 里 token 可能为 null (离线模式 / 老服务端不发令牌)。
     * 参数校验在这里做, 不放在界面层 —— 两个入口都得走同一套规则。
     */
    static Auth login(String exeDir, String rawUser, String pw, boolean remember) {
        String u = rawUser == null ? "" : rawUser.trim().toLowerCase();
        if (u.isEmpty() || pw == null || pw.isEmpty()) return new Auth(null, "账号和密码不能为空");
        if (u.length() < 2 || u.length() > 40) return new Auth(null, "账号长度需 2-40 位");
        if (!u.matches("^[\\p{L}\\p{N}_.@-]{2,40}$")) {
            return new Auth(null, "账号只能包含字母/数字/下划线/点/横线/@");
        }
        try {
            String hash = sha256(u + ":" + pw);
            String url = authUrl(exeDir);
            Auth r;
            if (url == null || url.isEmpty()) {
                r = localAuth(u, hash) ? new Auth(null, null) : new Auth(null, "账号或密码错误");
            } else {
                r = remoteAuth(url, u, hash);
            }
            // 抢独占放在**认证之后**: 没通过认证的请求不该占住账号, 更不该拿到
            // "已被另一个窗口占用" 这种回答 —— 那等于不用密码就能问出这个账号存不存在。
            if (r.ok()) {
                String busy = acquireAccount(u);
                if (busy != null) return new Auth(null, busy);
            }
            if (r.ok() && remember) {
                if (r.token != null) saveSession(exeDir, u, r.token);
                else saveUserOnly(u);
            }
            return r;
        } catch (Exception e) {
            String m = e.getMessage();
            return new Auth(null, m != null && m.contains("账号不存在") ? m : "连接失败: " + m);
        }
    }

    /**
     * 自助注册。成功返回带令牌的 Auth —— **注册即绑定即登录**, 一步到位。
     *
     * 返回类型和 login 一样, 因为响应格式本来就是同一个 (OK &lt;token&gt;), 错误也走
     * 同一套 describe()。两条路真正的差别只有请求体。
     *
     * 机器码在这里**必须拿得到**: 服务端要它, 拿不到就拒 (ERR:nomachine)。这是对的 ——
     * 自动绑定是"注册完就能用"的前提, 悄悄跳过它只会造出一个登不上的号, 用户以为注册失败。
     */
    static Auth register(String exeDir, String rawUser, String pw, String invite, boolean remember) {
        String u = rawUser == null ? "" : rawUser.trim().toLowerCase();
        String inv = invite == null ? "" : invite.trim().toUpperCase();
        if (u.isEmpty() || pw == null || pw.isEmpty()) return new Auth(null, "账号和密码不能为空");
        if (inv.isEmpty()) return new Auth(null, "请填写兑换码");
        if (u.length() < 2 || u.length() > 40) return new Auth(null, "账号长度需 2-40 位");
        if (!u.matches("^[\\p{L}\\p{N}_.@-]{2,40}$")) {
            return new Auth(null, "账号只能包含字母/数字/下划线/点/横线/@");
        }
        try {
            String url = authUrl(exeDir);
            if (url == null || url.isEmpty()) return new Auth(null, "离线模式无法注册");
            String hash = sha256(u + ":" + pw);
            String resp = post(url, "action=register"
                    + "&user=" + URLEncoder.encode(u, "UTF-8")
                    + "&pass=" + URLEncoder.encode(hash, "UTF-8")
                    + "&invite=" + URLEncoder.encode(inv, "UTF-8")
                    + machineParam());
            String err = describe(resp);
            if (err != null) return new Auth(null, err);
            String token = resp.length() > 2 ? resp.substring(2).trim().toLowerCase() : "";
            Auth r = new Auth(token.matches("[0-9a-f]{64}") ? token : null, null);
            // 新号的锁照拿 (它不可能被别人占着, 但"已登录就必须持有锁"这条不变式要维持住)
            String busy = acquireAccount(u);
            if (busy != null) return new Auth(null, busy);
            if (remember) {
                if (r.token != null) saveSession(exeDir, u, r.token);
                else saveUserOnly(u);
            }
            return r;
        } catch (Exception e) {
            return new Auth(null, "注册失败: " + e.getMessage());
        }
    }

    /**
     * 令牌登录 (自动登录)。成功返回 null, 失败返回给用户看的错误 */
    static String tokenLogin(String exeDir, String user, String token) {
        String url = authUrl(exeDir);
        if (url == null || url.isEmpty()) return "离线模式无法自动登录";
        try {
            String resp = post(url, "action=token&user=" + URLEncoder.encode(user, "UTF-8")
                    + "&token=" + token + machineParam());
            if (resp.startsWith("OK")) {
                // 自动登录也是登录 —— 第二个窗口的页面一加载就会走到这里。
                // 机器上两个窗口共用同一个 session.properties (也就是同一个令牌), 光看令牌
                // 分不出"是不是另一个窗口", 所以判据必须是这把进程锁。
                String busy = acquireAccount(user);
                if (busy != null) return busy;
                // 令牌有效就顺手把 session.properties 重写一遍。
                // 老版本文件里可能没有 url= 那一行, 而自动登录不会自己重写 —— 于是 agent
                // 读不到服务器地址, 分享配置就报"找不到 azclient-auth.txt"。这里一写就自愈了。
                saveSession(exeDir, user, token);
                return null;
            }
            return describe(resp);
        } catch (Throwable t) {
            return "自动登录失败: " + t.getMessage();
        }
    }

    // =====================================================================
    // 设置
    // =====================================================================

    static File settingsFile() { return new File(configDir(), "injector.properties"); }

    static Properties settings() {
        Properties p = new Properties();
        File f = settingsFile();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) { p.load(in); }
            catch (Throwable ignored) {}
        }
        if (!p.containsKey("autoInject")) p.setProperty("autoInject", "1");
        if (!p.containsKey("anim")) p.setProperty("anim", "1");
        if (!p.containsKey("logLines")) p.setProperty("logLines", "14");
        return p;
    }

    static void saveSettings(Map<String, String> kv) {
        Properties p = settings();
        for (Map.Entry<String, String> e : kv.entrySet()) p.setProperty(e.getKey(), e.getValue());
        try (OutputStream os = new FileOutputStream(settingsFile())) {
            p.store(os, "AZclient 注入器设置");
        } catch (Throwable ignored) {
        }
    }

    // =====================================================================
    // 进程探测
    // =====================================================================

    /** 一个 JVM。cmdline 拿不到 (VirtualMachineDescriptor 不暴露), 只有主类名 */
    static final class Proc {
        final String id, name;
        final boolean minecraft;
        Proc(String id, String name, boolean mc) { this.id = id; this.name = name; this.minecraft = mc; }
    }

    static boolean looksMinecraft(String name) {
        return WindowsProcesses.isGame(name);
    }

    private static boolean probeErrLogged;
    static volatile String jvmDiscoveryStatus = "尚未扫描";
    static volatile boolean jvmDiscoveryAvailable;

    static List<Proc> listProcs() {
        List<Proc> out = new ArrayList<>();
        try {
            for (VirtualMachineDescriptor v : VirtualMachine.list()) {
                String n = v.displayName();
                out.add(new Proc(v.id(), n == null || n.isEmpty() ? "(未知主类)" : n, looksMinecraft(n)));
            }
            jvmDiscoveryAvailable = !com.sun.tools.attach.spi.AttachProvider.providers().isEmpty();
            jvmDiscoveryStatus = jvmDiscoveryAvailable
                    ? "JVM 枚举返回 " + out.size() + " 项；仅枚举成功，未验证附加权限"
                    : "无 AttachProvider；当前 Java 可能缺少附加组件";
        } catch (Throwable t) {
            jvmDiscoveryAvailable = false;
            jvmDiscoveryStatus = "JVM 枚举失败：" + t.getClass().getSimpleName();
            // **不许静默吞掉**。jar 里漏了 sun/jvmstat 时这里抛
            //   InternalError: NoClassDefFoundError: sun/jvmstat/monitor/HostIdentifier
            // 吞掉之后界面上只剩一句"找不到进程", 日志里一个字都没有, 只能从 jar 里
            // 逐个包对出来。留一条日志, 只留第一次 (这个函数每 3 秒被调一次)。
            if (!probeErrLogged) {
                probeErrLogged = true;
                log("枚举 JVM 失败, 自动检测进程会失效: " + t);
            }
        }
        // Merge OS processes even when HotSpot returns an empty or incomplete list.
        // The background cache keeps slow WMI off the HTTP/UI request thread.
        for (Proc system : WindowsProcesses.snapshot()) {
            boolean found = false;
            for (int i = 0; i < out.size(); i++) {
                Proc existing = out.get(i);
                if (!existing.id.equals(system.id)) continue;
                if (!existing.minecraft && system.minecraft) out.set(i, system);
                found = true;
                break;
            }
            if (!found) out.add(system);
        }
        return out;
    }

    static Proc findMinecraft() {
        for (Proc p : listProcs()) if (p.minecraft) return p;
        return null;
    }

    // =====================================================================
    // 注入
    // =====================================================================

    static File injectorLog() {
        return new File(System.getProperty("java.io.tmpdir"), "liteclient-injector.log");
    }

    static File agentLog() {
        return new File(System.getProperty("java.io.tmpdir"), "liteclient-agent.log");
    }

    static void log(String msg) {
        try (FileWriter w = new FileWriter(injectorLog(), true)) {
            w.write(java.time.Instant.now() + " [injector] " + msg + "\n");
        } catch (Throwable ignored) {
        }
    }

    /**
     * 读文件 from 偏移之后新增的内容, 按 UTF-8 解。
     *
     * <p>和 {@link #tail} 的区别是它带"起点"。注入前记下日志长度, 注入后只读新增的部分 ——
     * 否则整份日志里上一次会话留下的 "Hook init OK" 会被当成本次的结果,
     * 一次失败的注入会被报成成功 (日志是跨会话追加的, 不轮转)。
     *
     * <p>只取末尾 64KB: 注入期间新增量很小, 但日志被别的进程写爆时不至于一次分配一大块。
     */
    static String since(File f, long from) {
        try {
            if (!f.exists()) return "";
            long len = f.length();
            if (len == 0) return "";
            if (len < from) from = 0; // Rotated logs are useful diagnostics too.
            long start = Math.max(from, len - 65536L);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 从 agent 新增日志里认出"授权被拒"并翻译成人话; 不是授权问题返回 null。
     *
     * <p>agent 那行是 {@code agentmain 拒绝: 未授权 (<Hook.licenseReason()>)}。
     * 为什么必须单独认这一种: 月卡到期的人拿到的现象和"注入坏了"**一模一样** ——
     * 都是"注入失败、游戏里没功能"。不把原因译出来, 每个到期客户都会来问你一遍。
     *
     * <p>只翻译认得的那几个, 其余把原文带出来。**猜错原因比不给原因更坏** ——
     * 一个"票据签名不对"被报成"月卡到期", 排查方向就整个歪了。
     */
    static String licenseReason(String t) {
        int i = t.lastIndexOf("未授权 (");
        if (i < 0) {
            // since() 最多往回退 64KB, 从中间截断时中文可能只剩半截, 所以再兜一次纯 ASCII 的错误码。
            if (t.contains("ERR:expired")) return "月卡已到期, 请续费后再用";
            if (t.contains("ERR:nokey")) return "服务器票据密钥没加载, 请联系管理员";
            return null;
        }
        int j = t.indexOf(')', i);
        String why = (j > 0 ? t.substring(i + "未授权 (".length(), j) : t.substring(i + "未授权 (".length())).trim();
        if (why.contains("ERR:expired") || why.contains("票据已过期")) return "月卡已到期, 请续费后再用";
        if (why.contains("ERR:nokey")) return "服务器票据密钥没加载, 请联系管理员";
        if (why.contains("ERR:nocolumn")) return "服务端配置有问题 (月卡字段没读出来), 请联系管理员";
        if (why.contains("没有登录会话")) return "注入器里没有登录会话, 请先登录再注入";
        return "授权被拒: " + why;
    }

    /** 读文件尾部 n 字节, 按 UTF-8 解 */
    static String tail(File f, int bytes) {
        try {
            if (!f.exists()) return "";
            long len = f.length();
            long start = Math.max(0, len - bytes);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                raf.seek(start);
                byte[] buf = new byte[(int) (len - start)];
                raf.readFully(buf);
                return new String(buf, "UTF-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    interface Step { void at(int pct, String text); }

    /** Attach uses a per-request receipt (nonce, PID, version), never shared log text. */
    interface AgentSession {
        void load(String path, String args) throws Exception;
        void detach() throws Exception;
    }

    static void inject(String agentPath, String pid, Step step) throws Exception {
        step.at(10, "正在附加到进程 " + pid + " ...");
        final VirtualMachine vm;
        try { vm = VirtualMachine.attach(pid); }
        catch (Exception e) {
            log("attach FAILED pid=" + pid + " -> " + e);
            throw new Exception("附加失败: " + e.getMessage() + " (游戏是否已完全启动?)", e);
        }
        injectAttached(agentPath, pid, step, new AgentSession() {
            public void load(String path, String args) throws Exception { vm.loadAgent(path, args); }
            public void detach() throws Exception { vm.detach(); }
        });
    }

    static Properties readReceipt(File file, String id, String pid) throws Exception {
        if (!file.isFile()) return null;
        Properties p = new Properties();
        try (Reader reader = new InputStreamReader(new FileInputStream(file), "UTF-8")) { p.load(reader); }
        if (!"1".equals(p.getProperty("protocol")) || !id.equals(p.getProperty("request"))
                || !pid.equals(p.getProperty("pid"))) return null;
        return p;
    }

    static void injectAttached(String agentPath, String pid, Step step, AgentSession session) throws Exception {
        java.nio.file.Path dir = null;
        try {
            step.at(45, "已附加, 正在加载 agent ...");
            String id = java.util.UUID.randomUUID().toString();
            dir = java.nio.file.Files.createTempDirectory("azclient-inject-");
            File result = dir.resolve("result.properties").toFile();
            String args = "azreceipt:" + id + ":" + pid + ":" + java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(result.getAbsolutePath().getBytes("UTF-8"));
            String loadError = null;
            try { session.load(agentPath, args); }
            catch (Exception failure) { loadError = failure.toString(); log("loadAgent reported: " + failure); }
            step.at(85, "agent 已加载，正在确认游戏内界面（1.20.1 请先进入存档或服务器）...");
            long until = System.nanoTime() + 12000000000L;
            do {
                Properties receipt = readReceipt(result, id, pid);
                if (receipt != null) {
                    String state = receipt.getProperty("state", "");
                    String version = receipt.getProperty("version", "");
                    if ("READY".equals(state) && EXPECTED_AGENT_VERSION.equals(version)) {
                        step.at(100, "注入完成! 游戏中按 RShift 呼出面板");
                        return;
                    }
                    if ("STALE".equals(state) || ("READY".equals(state) && !EXPECTED_AGENT_VERSION.equals(version)))
                        throw new Exception("游戏仍在使用旧 agent，请完全退出 Minecraft 后重新注入。要求版本: " + EXPECTED_AGENT_VERSION);
                    if ("DENIED".equals(state)) throw new Exception("授权失败: " + receipt.getProperty("reason", ""));
                    if ("ERROR".equals(state) || "NOT_READY".equals(state))
                        throw new Exception("Hook 未就绪: " + receipt.getProperty("reason", ""));
                }
                Thread.sleep(50);
            } while (System.nanoTime() < until);
            throw new Exception("未收到本次注入的进程确认，请完全退出 Minecraft 后重试。"
                    + (loadError == null ? "" : " " + loadError));
        } finally {
            try { session.detach(); } catch (Throwable e) { log("detach FAILED: " + e); }
            if (dir != null) {
                try { java.nio.file.Files.deleteIfExists(dir.resolve("result.properties"));
                    java.nio.file.Files.deleteIfExists(dir);
                } catch (Exception e) { log("receipt cleanup: " + e); }
            }
        }
    }
}
