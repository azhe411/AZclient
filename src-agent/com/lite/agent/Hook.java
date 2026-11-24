package com.lite.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 被字节码 hook 调用的入口。全部通过反射访问 MC 的混淆类(SRG 名)。
 * v2: 可绑定按键 + 模块参数可调 + 反作弊预设(NCP/Watchdog/AAC)。
 */
public class Hook {

    // ===== 版本标识 (注入后写进日志, 一眼区分新旧代码) =====
    public static final String VERSION = "AZclient 8.0.0";

    // ===== 模块系统 =====

    // ===== 分类 =====
    static final int CAT_COMBAT = 0, CAT_MOVE = 1, CAT_RENDER = 2, CAT_PLAYER = 3, CAT_WORLD = 4, CAT_MISC = 5;
    static final String[] CAT_NAMES = {"Combat", "Movement", "Render", "Player", "World", "Misc"};
    static final int CAT_COUNT = 6;

    /** 参数: 名称, 当前值, 最小, 最大, 步进 (step>=1 显示整数); opts 非空 = 模式参数 */
    private static class P {
        String n; double v, min, max, step;
        /**
         * 出厂默认值。存在的唯一理由是配置导出: 只传"和默认不一样"的那几项。
         * 全带上 247 个参数的话分享串会长到没法粘。
         */
        double def;
        /** 模式参数的选项名; v 是索引。非空时忽略 min/max/step, 界面上画成可点胶囊 */
        String[] opts;
        P(String n, double v, double min, double max, double step) {
            this.n = n; this.v = v; this.min = min; this.max = max; this.step = step;
            this.def = v;
        }
        P(String n, String[] opts, double def) {
            this.n = n; this.opts = opts; this.v = def; this.def = def;
            this.min = 0; this.max = opts.length - 1; this.step = 1;
        }
        boolean isMode() { return opts != null; }
        int idx() { return Math.max(0, Math.min(opts.length - 1, (int) v)); }
        String cur() { return opts[idx()]; }
        void cycle(int d) { v = ((idx() + d) % opts.length + opts.length) % opts.length; }

        /**
         * 参数量化的最小单位 —— 也就是"能调出来的最小间隔"。
         *
         * 原来量化用的是每个参数自己声明的 step (0.1 / 0.5 / 1 ...), 于是拖动精度、显示精度、
         * 储存精度三件事被同一个值绑死, 想让它更细只能去改 247 个参数里的 step。
         * 用户要的是**每个 0.01 都能拉到并且存得住**, 所以这里统一成百分位。
         *
         * 整数参数 (step >= 1, 比如 Delay ms / 次数) 不受影响: 它们的 v 本来就是整数,
         * 百分位量化只是表示成 100 倍, 取整回来还是整数。
         */
        static final double Q = 0.01;

        /** 量化档位 (v-min)/Q —— 配置导出用它把 double 压成一个短整数 */
        int quant() { return (int) Math.round((v - min) / Q); }
        void dequant(int q) { v = min + q * Q; }
        int quantMax() { return (int) Math.round((max - min) / Q); }

        /**
         * 把一个值吸附到参数自己的网格上并夹进 [min, max]。
         *
         * 拖动 (applySlider) 和滚轮微调都走这里, 两边的精度必须一模一样 —— 分开写迟早会
         * 出现"滚轮能到的值拖动到不了"这种只在某个参数上才发作的错位。
         * 整数参数保持整数; 其余一律吸附到 0.01, 并且以**整数百分位**做取整, 避免
         * 反复加 0.01 累积出 3.4699999 这种值。
         */
        double snap(double x) {
            if (!Double.isFinite(x)) return def;
            double r;
            if (step >= 1) {
                r = Math.rint(x);
            } else {
                long q = Math.round((x - min) * 100.0);
                r = min + q / 100.0;
            }
            return r < min ? min : (r > max ? max : r);
        }
    }

    private static class Mod {
        String name; int cat; boolean on; int bindKey;
        P[] ps;
        /** null=已实现; 否则是标注 (SERVER=需服务端, HOOK=需额外渲染hook) */
        String tag;
        /**
         * 搜索别名 (英文/拼音)。MC 1.12 的 keyTyped 拿不到输入法组字, 中文名在搜索框里
         * 根本打不出来, 所以必须有一份 ASCII 别名, 否则搜索框就是摆设。
         */
        String key = "";
        Mod(String name, int cat, P[] ps) {
            this.name = name; this.cat = cat; this.ps = ps;
            // HUD is an overlay, so a fresh profile should show it immediately.
            // Saved configs still win through loadAutoConfig/importConfig.
            this.on = "HUD".equals(name) || "TargetHUD".equals(name);
        }
        Mod(String name, int cat, String tag, P[] ps) {
            this.name = name; this.cat = cat; this.tag = tag; this.ps = ps;
            this.on = "HUD".equals(name);
        }
    }

    /**
     * 搜索补充关键词: 功能名 -> 同义词。
     *
     * 功能名现在是英文, 直接就能搜到; 这张表只补同义词 —— 比如打 "fly" 要能找到 Flight
     * (Flight 里并不含 "fly" 这三个字母连在一起), 打 "wallhack" 要能找到 ESP。
     * 每条的**第一个词是主键, 全表唯一** (自检强制), 后面是可以共用的补充词。
     */
    private static final String[][] ALIASES = {
        {"KillAura", "aura ka attack"}, {"AimBot", "aim aimbot lock silent"}, {"Criticals", "crit critical"},
        {"Reach", "range distance"}, {"Velocity", "antiknockback knockback"},
        {"AutoBlock", "block"}, {"HitBox", "box hitbox"},
        {"AutoClicker", "autoclick clicker cps"}, {"TriggerBot", "trigger"},
        {"BlockHit", "blockhit"}, {"AutoArmor", "armor"},
        {"AutoTotem", "totem"}, {"AutoPot", "pot potion"},
        {"AntiBot", "bot"},
        {"Flight", "fly"}, {"Speed", "bhop"}, {"Sprint", "sprint"},
        {"NoFall", "fall"}, {"AutoJump", "jump"}, {"Step", "step"},
        {"Jesus", "water walk"}, {"SafeWalk", "edge"},
        {"BunnyHop", "hop"}, {"NoSlow", "slow"},
        {"LongJump", "longjump"}, {"HighJump", "highjump"},
        {"Spider", "climb wall"}, {"FastLadder", "ladder"},
        {"InvMove", "inventory"}, {"Dolphin", "swim"},
        {"ESP", "wallhack players"}, {"Tracers", "lines tracer"},
        {"FullBright", "brightness nightvision gamma"}, {"NameTags", "nametag"},
        {"NoHurtCam", "hurtcam"}, {"StorageESP", "storage chest"},
        {"ItemESP", "item"}, {"BlockOutline", "outline block"},
        {"ViewClip", "renderdistance far"}, {"NoFire", "fire"},
        {"Trajectories", "projectile"}, {"FreeCam", "free"},
        {"FastPlace", "place"}, {"FastBreak", "break"},
        {"Scaffold", "bridge"}, {"AutoTool", "tool"}, {"InvManager", "manager sort clean gear equip"},
        {"ChestStealer", "steal"}, {"AutoEat", "eat food"},
        {"AutoFish", "fish"}, {"AntiAFK", "afk"},
        {"Nuker", "instantmine"},
        {"Timer", "speedhack"}, {"NoWeather", "weather rain"},
        {"TimeChanger", "time"}, {"AntiVoid", "void"},
        {"NoClip", "noclip clip"}, {"Phase", "phase"},
        {"AutoRespawn", "respawn"},
        {"ChatSuffix", "suffix"}, {"Spammer", "spam"},
        {"AutoReconnect", "reconnect"}, {"NameProtect", "protect"},
        {"PingDisplay", "ping"}, {"Watermark", "watermark"},
        {"HUD", "arraylist overlay list"},
        {"Theme", "theme color hue rainbow accent"},
        {"Keystrokes", "keys wasd cps mouse"},
        {"TargetHUD", "target hud health portrait scan ring mubiao xueliang"},
        {"MotionStats", "motion bps fps speedometer"},
        {"Coordinates", "coords xyz position"},
        {"Compass", "heading direction north"},
        {"SessionInfo", "session clock uptime"},
        {"ItemStatus", "held item durability stack"},
        {"Vitals", "health hunger armor status"},
        {"HotbarAccent", "hotbar slot accent selector"},
    };

    /** 功能总表: 分类 + 参数。id 即数组下标, 供 onFrame 调度。 */
    private static Mod[] mods = new Mod[]{
        // ===== 战斗 =====
        // 下标 0..11 被 doKillAura 引用, 顺序不能动, 新增只能往后加
        new Mod("AimBot", CAT_COMBAT, new P[]{
            new P("Speed", 180, 10, 360, 10),                        // 0  每秒最多转多少度
            new P("Smoothness", 0.6, 0.05, 1.0, 0.05),               // 1  每次靠近目标的比例
            new P("FOV", 90, 5, 360, 5),                             // 2  只锁定这个视野角内的目标
            new P("Range", 4.5, 1.0, 8.0, 0.5),                      // 3  锁定距离
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5), // 4
            new P("Priority", new String[]{"Angle", "Distance", "Health"}, 0), // 5
            new P("Hit Invisible", 0, 0, 1, 1),                      // 6
            new P("Through Walls", 0, 0, 1, 1),                      // 7
            new P("Jitter", 0.15, 0, 2, 0.05),                       // 8  瞄准抖动量(度)
            new P("Reaction Delay", 120, 0, 600, 20),                // 9  换目标后的反应延迟(ms)
            new P("Lock Ticks", 4, 1, 40, 1),                        // 10 锁住后保持多少 tick 不换目标
            new P("Only LMB", 0, 0, 1, 1),                           // 11 只在按住左键时自瞄
            // 默认 Visible: 真的转你的视角。Silent 本地视角不动, 看不出在自瞄, 会被当成没生效。
            new P("Mode", new String[]{"Visible", "Silent"}, 0),     // 12
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("KillAura", CAT_COMBAT, new P[]{
            new P("Range", 3.1, 2.0, 4.5, 0.05),            // 0
            // 度/秒。原来是"度/刻", 60 度/刻 = 1200 度/秒 —— 相当于瞬间甩过去, 又急又假。
            // 改成度/秒之后 60 才是个人手能跟上的转速, 也才谈得上"丝滑"。
            new P("Turn Speed", 540, 30, 1440, 10),           // 1
            new P("Attacks/s", 9, 1, 20, 1),                // 2
            new P("Smooth Aim", 1, 0, 1, 1),                // 3
            new P("Jitter", 0.5, 0, 5, 0.5),                // 4
            new P("Sort", new String[]{"Angle", "Distance", "Health"}, 0),   // 5
            // 目标类型。原来是 4 档, 加上了"玩家+怪"/"玩家+动物"这两种最常用的组合 ——
            // 打人不想顺手打到牛, 打怪又不想只打一种, 都靠这两档。
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),  // 6
            new P("Hit Invisible", 0, 0, 1, 1),             // 7
            new P("FOV", 360, 30, 360, 10),                 // 8  (360 = 不限)
            // 默认改成 Visible。Silent 档下本地视角**不动** —— 你第一人称什么都看不见,
            // 直觉上就是"没生效"。要看得到转视角就用 Visible, 官方客户端里也是最常见的那档。
            // Natural Aim keeps vanilla movement input intact.  The rendered
            // head leads toward the target while the torso follows with the
            // same bounded split used by the vanilla player model; the exact
            // rendered head pose is also the pose sent in the movement packet.
            new P("Rotation", new String[]{"Off", "Natural Aim", "Visible"}, 1), // 9
            new P("Hit Chance", 100, 20, 100, 5),           // 10
            new P("Swing", 1, 0, 1, 1),                     // 11
            new P("Through Walls", 0, 0, 1, 1),             // 12
            new P("Only On Ground", 0, 0, 1, 1),            // 13
            new P("No Swing While Eating", 1, 0, 1, 1),     // 14
            new P("Pitch Speed", 360, 30, 1080, 10),       // 15
            new P("Aim Tolerance", 4, 0.5, 15, 0.5),       // 16
            new P("Confirm Ticks", 1, 1, 6, 1),           // 17
            new P("Switch Delay (ms)", 80, 0, 800, 10),    // 18
            new P("Target Hold (ms)", 800, 0, 5000, 50),   // 19
            new P("Aim Height", 0.65, 0.2, 0.95, 0.05),   // 20
            new P("Cooldown", 0.95, 0.1, 1, 0.05),        // 21
            new P("Weapon Only", 0, 0, 1, 1),             // 22
            new P("Pause Using Item", 1, 0, 1, 1),        // 23
            new P("Ray Check", 1, 0, 1, 1),               // 24
            new P("Model Fresh (ms)", 500, 50, 500, 25),  // 25
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Scan Extra", 1.0, 0, 3, 0.05),          // 32: acquire early; does not extend attack reach
            new P("AutoBlock", 0, 0, 1, 1),              // 33: native sword/shield use
            new P("Block Hold (ticks)", 1, 1, 20, 1),     // 34: maximum hold; next attack takes priority
            new P("Block Mode", new String[]{"Visual Only", "Real Block"}, 1), // 35
        }),
        new Mod("Criticals", CAT_COMBAT, new P[]{
            // 原来的 "Packet" 档删了: 它把本地 fallDistance 写成 0.1 再发个 onGround=false 的包,
            // 而服务端判暴击读的是**它自己**按重力算出来的 fallDistance —— 那个包骗不到它。
            // 真正能生效的只有"真的离开地面, 真的在下落"。见 critReady()。
            new P("Mode", new String[]{"Mini Jump", "Jump"}, 0),
            new P("Chance", 100, 10, 100, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Reach", CAT_COMBAT, new P[]{
            new P("Range", 3.0, 2.0, 6.0, 0.05),
            new P("Mode", new String[]{"Raycast", "Click", "Both (legacy)"}, 0),
            new P("Only While Sprinting", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Velocity", CAT_COMBAT, new P[]{
            new P("Horizontal", 0.5, 0.0, 1.0, 0.05),
            new P("Vertical", 0.5, 0.0, 1.0, 0.05),
            new P("Mode", new String[]{"Normal", "Reverse", "Zero"}, 0),
            new P("Chance", 100, 10, 100, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoBlock", CAT_COMBAT, new P[]{
            new P("Range", 4.0, 2.0, 6.0, 0.5),
            new P("Mode", new String[]{"Right Click", "Packet", "Sword"}, 0),
            new P("Delay (ticks)", 1, 0, 10, 1),
            new P("Only When Hitting", 1, 0, 1, 1),
            // 目标类型。**追加在末尾**, 所以上面四个的下标没动。
            // 原来 AutoBlock 只要附近有**任何**生物就举盾, "什么算敌人"是写死的。
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),  // 4
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("HitBox", CAT_COMBAT, new P[]{
            new P("Multiplier", 1.2, 1.0, 3.0, 0.1),
            new P("Only Players", 0, 0, 1, 1),
            new P("Expand Y", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoClicker", CAT_COMBAT, new P[]{
            new P("Clicks/s", 10, 1, 20, 1),
            new P("Randomize", 0.5, 0, 2, 0.1),
            new P("Hold Only", 0, 0, 1, 1),
            new P("Mode", new String[]{"Left", "Right", "Both"}, 0),
            new P("Weapon Only", 0, 0, 1, 1),
            // 挖方块时别连点。
            // 左键连点会在你按住挖矿时不停调用 clickMouse —— 挖矿本来就要一直按着左键,
            // 结果每次连点都重算一次攻击/挖掘目标, 挖得磕磕绊绊, 而且对着空气挥手的动作
            // 才是真正显眼的特征。开着它, 只要 isHittingBlock 为真就跳过左键。
            new P("No Mine", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("TriggerBot", CAT_COMBAT, new P[]{
            new P("Range", 3.0, 2.0, 5.0, 0.1),
            new P("Delay (ticks)", 2, 0, 10, 1),
            new P("Crit Only", 0, 0, 1, 1),
            new P("Weapon Only", 0, 0, 1, 1),
            new P("Chance", 100, 10, 100, 5),
            // 目标类型 (下标 5)。原来准星压到**任何**生物就出手 —— 走过一头牛也会挥剑。
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("BlockHit", CAT_COMBAT, new P[]{
            new P("Chance", 50, 0, 100, 5),
            new P("Hold (ticks)", 3, 1, 10, 1),
            new P("Mode", new String[]{"Always", "On Hit", "On Kill"}, 0),
            // 目标类型 (下标 3)。原来附近有**任何**生物就格挡。
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoArmor", CAT_COMBAT, new P[]{
            new P("Delay", 3, 0, 10, 1),
            new P("Empty Only", 1, 0, 1, 1),
            new P("Mode", new String[]{"Best", "Armor Only", "No Binding"}, 0),
            new P("Prefer Enchanted", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoTotem", CAT_COMBAT, new P[]{
            new P("Trigger HP", 8, 1, 20, 1),
            new P("Mode", opts("Always", "On Damage", "Offhand"), 0),
            new P("Delay (ticks)", 1, 0, 10, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoPot", CAT_COMBAT, new P[]{
            new P("Trigger HP", 12, 1, 20, 1),
            new P("Delay", 5, 0, 20, 1),
            new P("Mode", new String[]{"Health", "Speed", "Strength", "Fire Res"}, 0),
            new P("Throw At Feet", 1, 0, 1, 1),
            new P("Only When Safe", 1, 0, 1, 1),
            // 目标类型 (下标 5): "Only When Safe" 里的"身边有敌人"按什么算敌人。
            // 原来只要附近有**任何**生物就不喝药, 野外路过一头猪也会卡住不喝。
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AntiBot", CAT_COMBAT, new P[]{
            new P("Range", 6.0, 2.0, 10.0, 0.5),
            new P("Mode", new String[]{"Name", "Entity ID", "Ping"}, 0),
            new P("Ignore Teams", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),

        // ===== 移动 =====
        new Mod("Flight", CAT_MOVE, new P[]{
            new P("Rise Speed", 0.08, 0.01, 0.30, 0.01),
            new P("H Speed", 0.6, 0.1, 2.0, 0.05),
            // 原来这里有个独立的 "Glide" 开关, 但它和下面 Mode 的 Glide 档完全重复 ——
            // 两个开关管同一件事, 关掉一个另一个还在生效, 那才是真的坑。删掉, 只留 Mode。
            new P("Mode", new String[]{"Vanilla", "Motion", "NCP", "Glide"}, 0),
            new P("Anti Kick", 1, 0, 1, 1),
            new P("Fall Speed", 0.35, 0.05, 1.0, 0.05),
            new P("Only While Sneaking", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Speed", CAT_MOVE, new P[]{
            new P("Multiplier", 1.35, 1.0, 2.2, 0.05),
            new P("Ramp Up", 1, 0, 1, 1),
            new P("Mid-Air", 0, 0, 1, 1),
            new P("Mode", new String[]{"Vanilla", "YPort", "NCP", "Bhop"}, 0),
            new P("Timer", 1.0, 0.5, 3.0, 0.05),
            new P("Only Sprinting", 1, 0, 1, 1),
            new P("Ice Boost", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Sprint", CAT_MOVE, new P[]{
            new P("All Directions", 0, 0, 1, 1),
            new P("Mode", new String[]{"Legit", "Omni"}, 0),
            new P("Only Forward", 0, 0, 1, 1),
            new P("Auto Re-Sprint", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NoFall", CAT_MOVE, new P[]{
            new P("Mode", new String[]{"Packet", "Anti", "No Ground"}, 0),
            new P("Min Fall (blocks)", 3, 1, 30, 1),
            new P("Only While Flying", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoJump", CAT_MOVE, new P[]{
            new P("Power", 0.42, 0.1, 1.0, 0.02),
            new P("Mode", new String[]{"Always", "On Edge", "On Block"}, 0),
            new P("Cooldown (ms)", 100, 0, 1000, 50),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Step", CAT_MOVE, new P[]{
            new P("Height", 0.6, 0.5, 2.0, 0.1),
            new P("Mode", new String[]{"Vanilla", "NCP", "Reverse"}, 0),
            new P("Only On Ground", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Jesus", CAT_MOVE, new P[]{
            new P("Mode", new String[]{"Solid", "Normal", "Dolphin"}, 0),
            new P("Speed", 0.3, 0.05, 1.0, 0.05),
            new P("Only On Water", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("SafeWalk", CAT_MOVE, new P[]{
            new P("Range", 0.3, 0.1, 1.0, 0.1),
            new P("Mode", new String[]{"Sneak", "Slow"}, 0),
            new P("Only On Ground", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("BunnyHop", CAT_MOVE, new P[]{
            new P("Multiplier", 1.2, 1.0, 2.0, 0.05),
            new P("Mode", new String[]{"Auto", "Space Only"}, 0),
            new P("Only Sprinting", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NoSlow", CAT_MOVE, new P[]{
            new P("Item Use", 1, 0, 1, 1),
            new P("Cobweb", 0, 0, 1, 1),      // 1 (代码引用)
            new P("Soul Sand", 0, 0, 1, 1),
            new P("Mode", new String[]{"Vanilla", "NCP", "NoSlow"}, 0),
            new P("Slime Block", 0, 0, 1, 1),
            new P("Sneak Slow", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("LongJump", CAT_MOVE, new P[]{
            new P("Boost", 1.5, 0.5, 4.0, 0.1),
            new P("Air Jumps", 1, 1, 5, 1),
            new P("Mode", new String[]{"Normal", "Boost", "Glide"}, 0),
            new P("Only While Sprinting", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("HighJump", CAT_MOVE, new P[]{
            new P("Multiplier", 1.2, 0.5, 3.0, 0.1),
            new P("Mode", new String[]{"Vanilla", "NCP"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Spider", CAT_MOVE, new P[]{
            new P("Speed", 0.2, 0.05, 1.0, 0.05),
            new P("Mode", new String[]{"Climb", "Wall", "No Clip"}, 0),
            new P("Only Facing Wall", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("FastLadder", CAT_MOVE, new P[]{
            new P("Up", 0.2, 0.05, 1.0, 0.05),
            new P("Down", 0.25, 0.05, 1.0, 0.05),
            new P("Mode", new String[]{"Both", "Up", "Down"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("InvMove", CAT_MOVE, new P[]{
            new P("Sneak", 0, 0, 0, 1),
            new P("Jump", 1, 0, 1, 1),
            new P("GUI Only", 1, 0, 1, 1),
            new P("No Slow", 1, 0, 1, 1),
            new P("Sprint", 0, 0, 0, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Dolphin", CAT_MOVE, new P[]{
            new P("Multiplier", 1.3, 1.0, 3.0, 0.1),
            new P("Mode", new String[]{"Normal", "Dolphin"}, 0),
            new P("Only In Water", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),

        // ===== 渲染 =====
        new Mod("ESP", CAT_RENDER, new P[]{
            new P("Red", 255, 0, 255, 5),
            new P("Green", 80, 0, 255, 5),
            new P("Blue", 80, 0, 255, 5),
            new P("Width", 1, 1, 5, 1),
            new P("Mode", new String[]{"Box", "2D Box", "Corner"}, 0),
            new P("Players Only", 0, 0, 1, 1),
            new P("Range", 64, 8, 256, 8),
            new P("Fill", 0, 0, 1, 1),
            new P("Show Health", 0, 0, 1, 1),
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Tracers", CAT_RENDER, new P[]{
            new P("Red", 0, 0, 255, 5),
            new P("Green", 255, 0, 255, 5),
            new P("Blue", 200, 0, 255, 5),
            // 0.5 步进: guiScale=4 时 0.5 = 2 个物理像素。原来最小是 1 (=4 物理像素),
            // 想画细线画不出来, 只能一直用粗线。
            new P("Width", 1, 0.5, 4, 0.5),
            new P("Mode", new String[]{"Feet", "Head", "Crosshair"}, 0),
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("FullBright", CAT_RENDER, new P[]{
            new P("Brightness", 10, 1, 16, 1),
            new P("Mode", new String[]{"Gamma", "Night Vision"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NameTags", CAT_RENDER, new P[]{
            new P("Show HP", 1, 0, 1, 1),
            new P("Show Dist", 1, 0, 1, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.1),
            new P("Background", 1, 0, 1, 1),
            new P("Target", new String[]{"Players", "Monsters", "Animals",
                    "Players+Monsters", "Players+Animals", "All"}, 5),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 78, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("NoHurtCam", CAT_RENDER, new P[]{
            new P("Strength", 0, 0, 1, 1),
            new P("Mode", new String[]{"Off", "Rotate", "No Bob"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("StorageESP", CAT_RENDER, new P[]{
            new P("Chests", 1, 0, 1, 1),
            new P("Furnaces", 1, 0, 1, 1),
            new P("Range", 32, 8, 128, 8),
            new P("Red", 255, 0, 255, 5),
            new P("Green", 200, 0, 255, 5),
            new P("Blue", 60, 0, 255, 5),
            new P("Mode", new String[]{"Box", "Outline", "2D Box"}, 0),
            new P("Ender Chests", 1, 0, 1, 1),
            new P("Shulkers", 1, 0, 1, 1),
            new P("Hoppers", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("ItemESP", CAT_RENDER, new P[]{
            new P("Range", 32, 8, 128, 8),
            new P("Red", 255, 0, 255, 5),
            new P("Green", 255, 0, 255, 5),
            new P("Blue", 60, 0, 255, 5),
            new P("Mode", new String[]{"Box", "Outline"}, 0),
            new P("Show Name", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 75, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("BlockOutline", CAT_RENDER, new P[]{
            new P("Red", 255, 0, 255, 5),
            new P("Green", 255, 0, 255, 5),
            new P("Blue", 255, 0, 255, 5),
            new P("Range", 6, 1, 16, 1),
            new P("Width", 1, 1, 4, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("ViewClip", CAT_RENDER, new P[]{
            new P("Multiplier", 1.5, 1.0, 4.0, 0.1),
            new P("Mode", new String[]{"Vanilla", "Max", "Custom"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NoFire", CAT_RENDER, new P[]{
            new P("Fire", 0, 0, 1, 1),
            new P("Nausea", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Trajectories", CAT_RENDER, new P[]{
            new P("Bow", 1, 0, 1, 1),
            new P("Potions", 1, 0, 1, 1),
            new P("Show Landing", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("FreeCam", CAT_RENDER, new P[]{
            new P("Speed", 1.0, 0.2, 5.0, 0.1),
            new P("Mode", new String[]{"Camera", "Player"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),

        // ===== 玩家 =====
        new Mod("FastPlace", CAT_PLAYER, new P[]{
            new P("Delay", 0, 0, 4, 1),
            new P("Mode", new String[]{"Normal", "No Delay"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("FastBreak", CAT_PLAYER, new P[]{
            new P("Multiplier", 1.3, 1.0, 3.0, 0.1),
            new P("Mode", new String[]{"Speed", "Instant"}, 0),
            new P("Only On Ground", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        // 下标 0..30 被 Scaffold 流程引用 —— 改这张表必须同步对一遍所有 p(F_SCAFFOLD, n)
        new Mod("Scaffold", CAT_PLAYER, new P[]{
            new P("Mode", new String[]{"Falling", "Always", "Hold Space"}, 1),   // 0
            new P("Rotation", new String[]{"Off", "Free Camera", "Follow Camera"}, 1),      // 1
            new P("Delay (ms)", 0, 0, 200, 10),                                  // 2
            new P("Look Ahead", 1.0, 0.0, 3.0, 0.5),                             // 3
            // Edge waits until the actual native movement step would leave
            // support; proximity and pending placement alone never trigger it.
            new P("Sneak", new String[]{"Off", "Always", "Edge"}, 0),            // 4
            new P("Swing", 1, 0, 1, 1),                                          // 5
            new P("Tower", 0, 0, 1, 1),                                          // 6
            new P("Only Below", 1, 0, 1, 1),                                     // 7
            new P("Keep Slot", 1, 0, 1, 1),                                      // 8
            // 模型头部的俯仰和相机俯仰在 MC 里是**同一个字段** rotationPitch, 分不开。
            // 1 = 连俯仰一起压向方块边缘 —— 玩偶和你自己的视角一起低头, 就是绿玩搭路的样子。
            // 0 = 只转身体朝向 (yaw), 你的俯仰完全归你。
            new P("Aim Pitch", 1, 0, 1, 1),                                      // 9
            // 目标格离玩家超过这个距离就不放。太远服务端会直接拒, 而且"贴着极限距离放"
            // 本身就很可疑。
            new P("Place Range", 4.5, 2.0, 6.0, 0.5),                            // 10
            // 开启后从 Scaffold 启用的第一 tick 起持续禁止疾跑。不能等到边缘才减速，
            // 否则第一个目标仍会按疾跑位移求角，画面射线会直接跨过侧面窗口。
            new P("No Sprint", 1, 0, 1, 1),                                      // 11: persistent while Scaffold is enabled
            // 身体要不要也跟着转。
            //
            // Aim  = 身体和头一起转向方块边缘 (整只人转过去盯着那块砖)。默认。
            // Move = 身体留给原版按移动方向算, 只有头转过去 —— 现实里的搭路其实是这样,
            //        但视角上"身体不动"会让人以为坏了, 所以不做默认。
            new P("Body", new String[]{"Aim", "Move"}, 0),                       // 12
            new P("Edge Margin", 0.08, 0.0, 0.30, 0.01),  // 13 (release hysteresis, never an early trigger)
            new P("Sneak Lead (ticks)", 1, 1, 6, 1),         // 14
            new P("Release (ticks)", 2, 0, 8, 1),            // 15
            new P("Moving Only", 1, 0, 1, 1),               // 16
            new P("Minimum Blocks", 1, 1, 64, 1),           // 17
            new P("Down Pitch", 80, 55, 90, 1),           // 18
            new P("Heading", new String[]{"Backward", "Locked", "Camera"}, 0), // 19
            new P("Yaw Offset", 0, -180, 180, 5),         // 20
            new P("Yaw Speed", 540, 30, 1440, 10),        // 21
            new P("Pitch Speed", 360, 30, 1080, 10),      // 22
            new P("Look Tolerance", 4, 0.5, 15, 0.5),     // 23
            new P("Edge Predict", 1, 0, 4, 0.25),         // 24
            new P("Retry (ms)", 0, 0, 1000, 10),          // 25: 0 = retry on the next game tick
            new P("Release On GUI", 1, 0, 1, 1),          // 26
            new P("Slot Delay (ticks)", 0, 0, 5, 1),      // 27
            new P("Hit Height", 0.85, 0.1, 0.95, 0.05),   // 28
            new P("Side Reveal", 0.02, 0.0, 0.20, 0.01),  // 29: eye must pass the support face
            new P("Side Confirm (ticks)", 1, 1, 4, 1),    // 30: keep pose+sneak before placing
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoTool", CAT_PLAYER, new P[]{
            new P("Mining Only", 1, 0, 1, 1),
            new P("Mode", new String[]{"Speed", "Silk Touch", "Damage"}, 0),
            new P("Switch Back", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("ChestStealer", CAT_PLAYER, new P[]{
            new P("Delay", 3, 0, 10, 1),
            new P("Mode", new String[]{"All", "Useful", "Filter"}, 0),
            new P("Close When Full", 0, 0, 1, 1),
            new P("Keep Open", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoEat", CAT_PLAYER, new P[]{
            new P("Trigger Food", 16, 1, 20, 1),
            new P("Delay", 2, 0, 10, 1),
            new P("Mode", new String[]{"Any", "Best", "No Poison"}, 0),
            new P("Switch Back", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoFish", CAT_PLAYER, new P[]{
            new P("Delay", 1, 0, 10, 1),
            new P("Auto Reel", 1, 0, 1, 1),
            new P("Mode", new String[]{"Normal", "Instant"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AntiAFK", CAT_PLAYER, new P[]{
            new P("Interval (s)", 30, 5, 300, 5),
            new P("Mode", new String[]{"Rotate", "Jump", "Walk", "Swing"}, 0),
            new P("Radius", 3, 1, 10, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Nuker", CAT_PLAYER, new P[]{
            new P("Multiplier", 2.0, 1.0, 10.0, 0.5),
            new P("Mode", new String[]{"All", "Flat", "Block"}, 0),
            new P("Range", 3, 1, 6, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),

        // ===== 世界 =====
        new Mod("Timer", CAT_WORLD, new P[]{
            new P("Speed", 1.1, 0.5, 3.0, 0.05),
            new P("Mode", new String[]{"Continuous", "On Move"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NoWeather", CAT_WORLD, new P[]{
            new P("No Rain/Snow", 1, 0, 1, 1),
            new P("No Sky Darken", 0, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("TimeChanger", CAT_WORLD, new P[]{
            new P("Time", 6000, 0, 24000, 500),
            new P("Mode", new String[]{"Fixed", "Fast", "Slow"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AntiVoid", CAT_WORLD, new P[]{
            new P("Trigger Y", 0, 0, 10, 1),
            new P("Mode", new String[]{"Teleport", "No Fall"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NoClip", CAT_WORLD, new P[]{
            new P("Speed", 0.3, 0.05, 2.0, 0.05),
            new P("Mode", new String[]{"Vanilla", "NCP"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Phase", CAT_WORLD, new P[]{
            new P("Speed", 0.25, 0.05, 2.0, 0.05),
            new P("Mode", new String[]{"Vanilla", "Sneak", "Block"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),

        // ===== 杂项 =====
        // 下标 0..5 被 renderHud 引用
        // 主题色 —— 一个"功能"其实只是为了让参数有地方放, 它没有开/关行为。
        // 面板每次渲染前读一次 (见 setThemeFromParams), 所以改色是即时生效的。
        // 默认 Hue=0 会让面板立刻变红, 所以默认值给的是那个"青绿"本身的色相 168,
        // 而不是 0 —— 用户第一眼看到的必须还是现在这个绿色主题。
        new Mod("Theme", CAT_MISC, new P[]{
            new P("Hue", 168, 0, 360, 1),                      // 0
            new P("Rainbow", 0, 0, 1, 1),                      // 1
            new P("Glass Opacity", 88, 35, 95, 1),        // 2
            new P("Glow", 95, 0, 100, 5),                 // 3
            new P("Glow Radius", 6, 0, 8, 1),              // 4
            new P("Glass Motion", 1, 0, 1, 1),             // 5
            new P("Backdrop Blur", 12, 2, 24, 1),          // 6
            new P("Backdrop Tint", 72, 0, 100, 1),         // 7
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Palette", new String[]{"Sunset", "Custom Hue"}, 0),       // 14
            new P("Flow Speed", 1.0, 0.1, 3, 0.1),                         // 15
            new P("Bloom", 70, 0, 100, 5),                                 // 16
            new P("Bloom Spread", 24, 0, 48, 1),                           // 17
            new P("HUD Frost", 1, 0, 1, 1),                                // 18
            new P("Overlay Colors", new String[]{"Sunset", "Module RGB"}, 0), // 19
            new P("Atmosphere", 55, 0, 100, 5),                            // 20
            new P("Interaction Motion", 1, 0, 1, 1),                      // 21
            new P("Interaction Speed", 1.0, 0.5, 2, 0.1),                 // 22
            new P("Toggle Notices", 1, 0, 1, 1),                         // 23
            new P("Notice Duration", 2.8, 1, 8, 0.2),                    // 24
            new P("Notice Scale", 1.0, 0.7, 1.5, 0.05),                  // 25
            new P("Notice Opacity", 88, 0, 100, 1),                       // 26
            new P("Notice Glow", 100, 0, 100, 1),                         // 27
        }),
        new Mod("HUD", CAT_MISC, new P[]{
            new P("X %", 100, 0, 100, 1),                                    // 0
            new P("Y %", 0, 0, 100, 1),                                   // 1
            new P("Sort", new String[]{"Length", "Name", "Category"}, 0),   // 2
            new P("Background", 1, 0, 1, 1),                               // 3
            new P("Rainbow", 1, 0, 1, 1),                                  // 4
            new P("Show Count", 1, 0, 1, 1),                               // 5
            new P("Show Tagged", 1, 0, 1, 1),
            new P("Only In Game", 1, 0, 1, 1),
            // 下标 8/9/10/11 —— 见 renderHud 和 hudHandleRects。
            new P("Scale", 1.0, 0.5, 2.5, 0.05),          // 8  整体缩放 (也由拖动条改)
            new P("Title Scale", 1.0, 0.5, 2.5, 0.05),    // 9  标题比正文大多少
            new P("Drag", 1, 0, 1, 1),                    // 10 显示右下角拖动/缩放手柄
            new P("Max Rows", 12, 1, 30, 1),              // 11
            new P("Show Empty", 1, 0, 1, 1),              // 12
            new P("Menu Preview", 1, 0, 1, 1),            // 13
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
            new P("Style", new String[]{"Veil", "Compact"}, 0),
            new P("Mode Suffix", 1, 0, 1, 1),
        }),
        new Mod("AutoRespawn", CAT_MISC, new P[]{
            new P("Delay", 1, 0, 5, 1),
            new P("Mode", new String[]{"Normal", "Instant"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("ChatSuffix", CAT_MISC, new P[]{
            new P("Mode", new String[]{"Append", "Prepend"}, 0),
            new P("Space", 1, 0, 1, 1),
            new P("Case", new String[]{"Keep", "Lower", "Upper"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("Spammer", CAT_MISC, new P[]{
            new P("Interval (s)", 5, 1, 60, 1),
            new P("Mode", new String[]{"Clipboard", "Builtin"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("AutoReconnect", CAT_MISC, new P[]{
            new P("Delay (s)", 5, 1, 60, 1),
            new P("Mode", new String[]{"Auto", "Retry"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("NameProtect", CAT_MISC, new P[]{
            new P("Mode", new String[]{"Self", "Everyone"}, 0),
            new P("Min Length", 4, 3, 16, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("PingDisplay", CAT_MISC, new P[]{
            new P("Red", 0, 0, 255, 5),
            new P("Green", 255, 0, 255, 5),
            new P("Blue", 200, 0, 255, 5),
            new P("Mode", new String[]{"Text", "Bar"}, 0),
            new P("X %", 99, 0, 100, 1),
            new P("Y %", 1, 0, 100, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("Watermark", CAT_MISC, new P[]{
            new P("Show FPS", 1, 0, 1, 1),
            new P("Show Coords", 1, 0, 1, 1),
            new P("Mode", new String[]{"Text", "Box"}, 0),
            new P("X %", 99, 0, 100, 1),
            new P("Y %", 1, 0, 100, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        // Compact in-game widgets.  Every widget is off by default and owns
        // its placement/scale so enabling one never silently rearranges the
        // existing HUD, watermark or another widget.
        new Mod("Keystrokes", CAT_MISC, new P[]{
            new P("X %", 84, 0, 100, 1),
            new P("Y %", 64, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Show Mouse", 1, 0, 1, 1),
            new P("Show CPS", 1, 0, 1, 1),
            new P("Show Space", 1, 0, 1, 1),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("MotionStats", CAT_MISC, new P[]{
            new P("X %", 35, 0, 100, 1),
            new P("Y %", 2, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Show FPS", 1, 0, 1, 1),
            new P("Show BPS", 1, 0, 1, 1),
            new P("Show Vertical", 0, 0, 1, 1),
            new P("Graph", 1, 0, 1, 1),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("Coordinates", CAT_MISC, new P[]{
            new P("X %", 1, 0, 100, 1),
            new P("Y %", 92, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Precision", new String[]{"Block", "1 Dec", "2 Dec"}, 0),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("Compass", CAT_MISC, new P[]{
            new P("X %", 50, 0, 100, 1),
            new P("Y %", 8, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Width", 170, 90, 300, 5),
            new P("Show Degrees", 1, 0, 1, 1),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("SessionInfo", CAT_MISC, new P[]{
            new P("X %", 1, 0, 100, 1),
            new P("Y %", 2, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Show Clock", 1, 0, 1, 1),
            new P("Show Session", 1, 0, 1, 1),
            new P("24 Hour", 1, 0, 1, 1),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("ItemStatus", CAT_MISC, new P[]{
            new P("X %", 77, 0, 100, 1),
            new P("Y %", 90, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Show Count", 1, 0, 1, 1),
            new P("Show Durability", 1, 0, 1, 1),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("Vitals", CAT_MISC, new P[]{
            new P("X %", 42, 0, 100, 1),
            new P("Y %", 82, 0, 100, 1),
            new P("Scale", 1.0, 0.5, 2.0, 0.05),
            new P("Show Health", 1, 0, 1, 1),
            new P("Show Hunger", 1, 0, 1, 1),
            new P("Show Armor", 1, 0, 1, 1),
            new P("Mode", new String[]{"Bars", "Text"}, 0),
            new P("Background", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        new Mod("HotbarAccent", CAT_MISC, new P[]{
            new P("X Offset", 0, -80, 80, 1),
            new P("Y Offset", 0, -80, 40, 1),
            new P("Scale", 1.0, 0.65, 1.5, 0.05),
            new P("Width", 2, 1, 6, 1),
            new P("Alpha", 85, 10, 100, 5),
            new P("Pulse", 1, 0, 1, 1),
            new P("Color", new String[]{"Theme", "Rainbow", "White"}, 0),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
            new P("Background Opacity", 14, 0, 100, 1),
            new P("Glow Strength", 100, 0, 100, 1),
        }),
        // Appended instead of inserted into the Player block: category controls
        // where it is shown, while tail placement preserves every 7.3 module
        // index used by saved AZC2 configurations.
        new Mod("InvManager", CAT_PLAYER, new P[]{
            new P("Delay (ticks)", 2, 0, 20, 1),
            new P("Best Armor", 1, 0, 1, 1),
            new P("Best Weapon", 1, 0, 1, 1),
            new P("Best Tools", 1, 0, 1, 1),
            new P("Organize Misc", 1, 0, 1, 1),
            new P("Drop Junk", 1, 0, 1, 1),
            new P("Weapon Slot", 1, 1, 9, 1),
            new P("Pickaxe Slot", 2, 1, 9, 1),
            new P("Axe Slot", 3, 1, 9, 1),
            new P("Shovel Slot", 4, 1, 9, 1),
            new P("Blocks Slot", 5, 1, 9, 1),
            new P("Food Slot", 6, 1, 9, 1),
            new P("Utility Slot", 7, 1, 9, 1),
            new P("Keep Blocks", 128, 0, 576, 16),
            new P("Keep Food", 32, 0, 576, 8),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 1, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1.0, 0, 3, 0.1),
        }),
        new Mod("TargetHUD", CAT_MISC, new P[]{
            new P("X %", 60, 0, 100, 1),
            new P("Y %", 60, 0, 100, 1),
            new P("Scale", 1, 0.5, 2, 0.05),
            new P("Background Opacity", 88, 0, 100, 1),
            new P("Glow Strength", 65, 0, 100, 1),
            new P("Scan Ring", 1, 0, 1, 1),
            new P("Scan Period (s)", 2, 0.5, 5, 0.1),
            new P("Ring Width", 1.5, 1, 4, 0.5),
            new P("Ring Glow", 35, 0, 100, 1),
            new P("Ring Radius", 1.25, 0.8, 2, 0.05),
            new P("Attack Hold (ms)", 1000, 150, 2000, 50),
            new P("Menu Preview", 1, 0, 1, 1),
            new P("Bind Mode", new String[]{"Toggle", "Hold"}, 0),
            new P("Auto Off (s)", 0, 0, 600, 1),
            new P("Notify Toggle", 0, 0, 1, 1),
            new P("HUD Entry", 0, 0, 1, 1),
            new P("UI Glow", 60, 0, 100, 5),
            new P("UI Motion", 1, 0, 3, 0.1),
        }),
    };

    static {
        // 把搜索别名灌进 mods。漏配的会在这里被自检揪出来 (见 selfTest)。
        for (int i = 0; i < mods.length; i++) {
            for (String[] a : ALIASES) {
                if (a[0].equals(mods[i].name)) { mods[i].key = a[1]; break; }
            }
        }
    }

    /**
     * 功能 id 按名字解析, 不再硬编码下标。
     * 之前是写死的 0..33, 每次往数组中间插功能就得手改一遍, 改漏一个就是"点了 A 生效 B"。
     * 现在加减/重排功能都不会错位。
     */
    private static int id(String name) {
        for (int i = 0; i < mods.length; i++) if (mods[i].name.equals(name)) return i;
        throw new IllegalStateException("功能不存在: " + name);
    }

    static final int F_KILLAURA = id("KillAura"), F_AIMBOT = id("AimBot"), F_CRITICALS = id("Criticals"), F_REACH = id("Reach"),
            F_VELOCITY = id("Velocity"), F_AUTOBLOCK = id("AutoBlock"), F_HITBOX = id("HitBox"),
            F_AUTOCLICK = id("AutoClicker"), F_TRIGGERBOT = id("TriggerBot"), F_BLOCKHIT = id("BlockHit"),
            F_AUTOARMOR = id("AutoArmor"), F_AUTOTOTEM = id("AutoTotem"), F_AUTOPOT = id("AutoPot"),
            F_ANTIBOT = id("AntiBot");
    static final int F_FLIGHT = id("Flight"), F_SPEED = id("Speed"), F_SPRINT = id("Sprint"),
            F_NOFALL = id("NoFall"), F_AUTOJUMP = id("AutoJump"), F_STEP = id("Step"),
            F_JESUS = id("Jesus"), F_SAFEWALK = id("SafeWalk"), F_BUNNYHOP = id("BunnyHop"),
            F_NOSLOW = id("NoSlow"), F_LONGJUMP = id("LongJump"), F_HIGHJUMP = id("HighJump"),
            F_SPIDER = id("Spider"), F_FASTLADDER = id("FastLadder"), F_INVMOVE = id("InvMove"),
            F_SWIM = id("Dolphin");
    static final int F_ESP = id("ESP"), F_TRACERS = id("Tracers"), F_FULLBRIGHT = id("FullBright"),
            F_NAMETAGS = id("NameTags"), F_NOHURTCAM = id("NoHurtCam"),
            F_STORAGEESP = id("StorageESP"), F_ITEMESP = id("ItemESP"), F_BLOCKOUTLINE = id("BlockOutline"),
            F_VIEWCLIP = id("ViewClip"), F_NORENDER = id("NoFire"),
            F_TRAJECTORIES = id("Trajectories"), F_FREECAM = id("FreeCam");
    static final int F_FASTPLACE = id("FastPlace"), F_FASTBREAK = id("FastBreak"),
            F_SCAFFOLD = id("Scaffold"), F_AUTOTOOL = id("AutoTool"),
            F_INVMANAGER = id("InvManager"), F_CHESTSTEALER = id("ChestStealer"),
            F_AUTOEAT = id("AutoEat"), F_AUTOFISH = id("AutoFish"),
            F_ANTIAFK = id("AntiAFK"), F_INSTANTMINE = id("Nuker");
    static final int F_TIMER = id("Timer"), F_NOWEATHER = id("NoWeather"), F_TIMECHANGE = id("TimeChanger"),
            F_ANTIVOID = id("AntiVoid"), F_NOCLIP = id("NoClip"), F_PHASE = id("Phase");
    static final int F_AUTORESPAWN = id("AutoRespawn"), F_CHATSUFFIX = id("ChatSuffix"),
            F_SPAMMER = id("Spammer"), F_AUTORECONNECT = id("AutoReconnect"),
            F_NAMEPROTECT = id("NameProtect"), F_PING = id("PingDisplay"), F_WATERMARK = id("Watermark"),
            F_HUD = id("HUD"), F_THEME = id("Theme"),
            F_KEYSTROKES = id("Keystrokes"), F_MOTIONSTATS = id("MotionStats"),
            F_COORDINATES = id("Coordinates"), F_COMPASS = id("Compass"),
            F_SESSIONINFO = id("SessionInfo"), F_ITEMSTATUS = id("ItemStatus"),
            F_VITALS = id("Vitals"), F_HOTBARACCENT = id("HotbarAccent"), F_TARGETHUD = id("TargetHUD");

    /** 兼容旧引用 */
    static final int MF = F_FLIGHT, MS = F_SPEED, MK = F_KILLAURA, ME = F_ESP, MA = F_AUTOJUMP;
    /** 新增渲染模块的短别名 (参数按索引取, 名字短一点读起来清楚) */
    static final int FT = F_TRACERS, FNT = F_NAMETAGS, FTJ = F_TRAJECTORIES,
            FPING = F_PING, FWM = F_WATERMARK;

    private static double p(int mod, int idx) { return mods[mod].ps[idx].v; }

    /** 按下标写参数 (拖动 HUD 时用)。越界直接抛 —— 静默忽略会让"拖了没反应"没法排查。 */
    private static void setP(int mod, int idx, double v) {
        Mod m = mods[mod];
        if (idx >= m.ps.length) {
            throw new IllegalStateException("参数下标越界: " + m.name + "[" + idx + "] 只有 " + m.ps.length + " 个");
        }
        m.ps[idx].v = v;
    }

    /**
     * 按名字设置参数。名字对不上直接抛 —— 以前是静默 return,
     * 结果预设里打错一个字就等于这条预设没生效, 而且没人会发现。
     */
    private static void setByName(String modName, String paramName, double v) {
        for (Mod m : mods) {
            if (!m.name.equals(modName)) continue;
            for (P pp : m.ps) {
                if (pp.n.equals(paramName)) {
                    pp.v = Math.min(pp.max, Math.max(pp.min, v));
                    return;
                }
            }
            throw new IllegalStateException("功能 " + modName + " 没有参数 " + paramName);
        }
        throw new IllegalStateException("预设引用了不存在的功能: " + modName);
    }

    /** 按名字开关功能 */
    private static void setOn(String modName, boolean on) {
        for (Mod m : mods) {
            if (m.name.equals(modName)) { m.on = on; return; }
        }
        throw new IllegalStateException("预设引用了不存在的功能: " + modName);
    }

    /** 反作弊预设: -1=无, 0=NCP, 1=Watchdog, 2=AAC */
    public static int antiCheat = -1;

    /**
     * 反作弊预设。
     *
     * 思路不是"参数调小一点就查不出来" —— 服务端永远有最终判定权。这里能做的是
     * 让行为特征更像人, 以及把那些"一开就必被抓"的功能强制关掉:
     *   - 攻击距离一律压在 3.0 (原版上限), 这是最容易被服务端测出来的一个量
     *   - 转身速度放慢 + 随机偏移, 避免瞬间锁头那种一眼假的旋转曲线
     *   - 连击格挡给概率而不是每次都触发
     *   - 无碰撞/穿墙/瞬间挖掘 全部关掉: 位置与挖掘速度都是服务端权威, 开了就是送
     *   - Watchdog 档干脆关掉自动攻击, 只用触发攻击 (不打转视角, 没有瞄准快照)
     *
     * 预设**只做限制, 绝不打开任何功能**。以前每个 case 末尾都有 setOn(..., true), 结果是
     * 用户点一下模板就被偷偷开了 KillAura / TriggerBot / Scaffold —— 这不是预设该干的事,
     * 而且用户根本不知道。selfTest 里有一条检查守着这个。
     */
    static void applyPreset(int ac) {
        antiCheat = ac;
        switch (ac) {
            case 0: // NCP: 对垂直加速度和水平超速敏感
                setByName("Speed", "Multiplier", 1.20);
                setByName("Speed", "Ramp Up", 1);
                setByName("Speed", "Mid-Air", 0);
                setByName("Flight", "Rise Speed", 0.08);
                setByName("Flight", "H Speed", 0.60);
                setByName("Flight", "Mode", 3);   // 3 = Glide (原来的 Glide 开关已并入 Mode)
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 30);
                setByName("KillAura", "Attacks/s", 8);
                setByName("KillAura", "Smooth Aim", 1);
                setByName("KillAura", "Jitter", 1.5);
                setByName("Reach", "Range", 3.0);
                setByName("Reach", "Mode", 1);            // 1 = Packet: 不改属性, 只补发包
                setByName("TriggerBot", "Range", 3.0);
                setByName("TriggerBot", "Delay (ticks)", 2);
                setByName("BlockHit", "Chance", 35);
                setByName("AutoClicker", "Randomize", 1.0);
                break;
            case 1: // Watchdog: 预测式移动 + reach + 旋转检测, 最保守
                setByName("Speed", "Multiplier", 1.12);
                setByName("Speed", "Ramp Up", 1);
                setByName("Speed", "Mid-Air", 0);
                setByName("Flight", "Rise Speed", 0.05);
                setByName("Flight", "H Speed", 0.50);
                setByName("Flight", "Mode", 3);   // 3 = Glide (原来的 Glide 开关已并入 Mode)
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 20);
                setByName("KillAura", "Attacks/s", 6);
                setByName("KillAura", "Smooth Aim", 1);
                setByName("KillAura", "Jitter", 2.5);
                setByName("Reach", "Range", 3.0);
                setByName("Reach", "Mode", 1);            // 1 = Packet
                setByName("NoSlow", "Mode", 1);           // 1 = NCP: 补速更保守
                setByName("TriggerBot", "Range", 3.0);
                setByName("TriggerBot", "Delay (ticks)", 4);
                setByName("BlockHit", "Chance", 20);
                setByName("AutoClicker", "Randomize", 1.5);
                // 这一档连视角都不转, 只打准星已经对准的目标
                setOn("KillAura", false);
                break;
            case 2: // AAC: 位移与飞行模式检测
                setByName("Speed", "Multiplier", 1.15);
                setByName("Speed", "Ramp Up", 1);
                setByName("Speed", "Mid-Air", 0);
                setByName("Flight", "Rise Speed", 0.06);
                setByName("Flight", "H Speed", 0.55);
                setByName("Flight", "Mode", 3);   // 3 = Glide (原来的 Glide 开关已并入 Mode)
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 25);
                setByName("KillAura", "Attacks/s", 9);
                setByName("KillAura", "Smooth Aim", 1);
                setByName("KillAura", "Jitter", 2.0);
                setByName("Reach", "Range", 3.0);
                setByName("TriggerBot", "Range", 3.0);
                setByName("TriggerBot", "Delay (ticks)", 3);
                setByName("BlockHit", "Chance", 30);
                setByName("AutoClicker", "Randomize", 1.2);
                break;
            case 3: // Matrix: 抓固定 CPS 和静默转身, 靠随机化和低 CPS 混过去
                setByName("Speed", "Multiplier", 1.18);
                setByName("Speed", "Ramp Up", 1);
                setByName("Flight", "Rise Speed", 0.07);
                setByName("Flight", "H Speed", 0.55);
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 35);
                setByName("KillAura", "Attacks/s", 10);
                setByName("KillAura", "Smooth Aim", 1);
                setByName("KillAura", "Jitter", 2.0);
                setByName("KillAura", "Hit Chance", 85);
                setByName("Reach", "Range", 3.0);
                setByName("AutoClicker", "Randomize", 1.8);
                break;
            case 4: // Verus: 抓 reach 和垂直移动, 移动类只能最保守
                setByName("Speed", "Multiplier", 1.10);
                setByName("Speed", "Mid-Air", 0);
                setByName("Flight", "Rise Speed", 0.05);
                setByName("Flight", "H Speed", 0.45);
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 25);
                setByName("KillAura", "Attacks/s", 8);
                setByName("KillAura", "Jitter", 3.0);
                setByName("KillAura", "Hit Chance", 80);
                setByName("Reach", "Range", 3.0);
                break;
            case 5: // Legit: 参数往"像人"调, 牺牲强度换存活
                setByName("KillAura", "Range", 3.0);
                setByName("KillAura", "Turn Speed", 18);
                setByName("KillAura", "Attacks/s", 7);
                setByName("KillAura", "Smooth Aim", 1);
                setByName("KillAura", "Jitter", 3.0);
                setByName("KillAura", "Hit Chance", 75);
                setByName("KillAura", "Rotation", 1);      // Silent: 视角完全不动, 最像人
                setByName("AutoClicker", "Randomize", 2.0);
                setByName("Speed", "Multiplier", 1.08);
                // Scaffold 的 Rotation 参数已经删了 (现在永远不替用户转视角), 只剩 Delay
                setByName("Scaffold", "Delay (ms)", 120);
                setOn("TriggerBot", false);
                break;
            case 6: // Rage: 不管反作弊, 参数拉满 (只在自己服务器用)
                setByName("KillAura", "Range", 4.5);
                setByName("KillAura", "Turn Speed", 180);
                setByName("KillAura", "Attacks/s", 20);
                setByName("KillAura", "Smooth Aim", 0);
                setByName("KillAura", "Jitter", 0);
                setByName("KillAura", "Hit Chance", 100);
                setByName("KillAura", "FOV", 360);
                setByName("Reach", "Range", 6.0);
                setByName("Speed", "Multiplier", 2.2);
                setByName("Flight", "Rise Speed", 0.30);
                setByName("Flight", "H Speed", 2.0);
                setByName("Scaffold", "Delay (ms)", 0);
                setByName("Scaffold", "Mode", 1);          // Always
                break;
        }
        if (ac >= 0) {
            // 所有预设共同: 这几个功能服务端有最终判定权, 开着就是白送人头
            setOn("NoClip", false);
            setOn("Phase", false);
            setOn("Nuker", false);
            setOn("AntiBot", false);      // 没实现, 别留着骗自己
            setByName("LongJump", "Boost", 1.2);
            setByName("HighJump", "Multiplier", 1.1);
        }
        log("preset applied: " + ac);
    }

    public static boolean guiOpen = false;
    private static boolean shiftPressed = false;   // 防重复触发
    private static int bindTarget = -1;            // 正在等待绑定按键的模块, -1=无

    // 平滑转身状态
    private static float lastYaw, lastPitch;

    // ===== 反射缓存 =====
    private static Class<?> cMC, cEntity, cPlayer, cLiving, cWorld, cCap, cRM, cRG, cAABB, cGui, cFont, cGL, cGuiScreen, cKb, cMouse, cGameSettings;
    /** 目标分类用: 动物 / 怪物。拿不到就是 null, 那种情况下 Target 只在 Players/All 之间切 */
    private static Class<?> cAnimal, cMob;
    /** 搭桥要判断"这是不是方块物品" */
    private static Class<?> cItemBlock;
    private static Method mGetMC, mDistSq, mEyeH, mAttack, mCooled, mSwing, mGetHealth, mGetBB, mAabbOffset, mFlySpeed, mDrawBox, mDrawRect, mDrawGradient, mDrawString, mGetStrW, mDisplayGui, mKeyDown, mKeyName, mIsButtonDown;
    /** 真的会攻击的左键入口 (Minecraft.clickMouse) —— swingArm 只是摆手动画, 打不到人 */
    private static Method mClickMouse;
    /** PlayerControllerMP.attackEntity —— 原版左键点人的入口, 会发 CPacketUseEntity */
    private static Method mAttackEntity;
    private static Method mCombatCanCollide, mCombatCollisionBorder, mCombatSpectator;
    private static Method mCombatBoxContains, mCombatBoxIntercept;
    private static Method mIsSneaking, mIsSprinting;
    private static Field fSneakKey, fForwardKey, fBackKey, fLeftKey, fRightKey, fJumpKey, fSprintKey, fUseKey;
    private static Method mBindingCode, mBindingState;
    /** 直接落方块: PlayerControllerMP.processRightClickBlock —— 不依赖 objectMouseOver */
    private static Method mProcessRightClickBlock;
    /** Legacy world picking supplies shape-aware first hits, including occluders. */
    private static Method mScaffoldRayTrace;
    private static Method mScaffoldLookVector;
    private static Field fScaffoldHitSide, fScaffoldHitVec;
    private static Field fScaffoldVecX, fScaffoldVecY, fScaffoldVecZ;
    private static java.lang.reflect.Constructor<?> ctorVec3d;
    /** EnumFacing 六个常量, 下标就是 EnumFacing 的 ordinal (DOWN,UP,NORTH,SOUTH,WEST,EAST) */
    private static Object[] facingConsts;
    /** KillAura 的 Through Walls / No Swing While Eating 用 */
    private static Method mCanSee, mHandActive;
    private static Method mBlockUse, mBlockStop, mStackUseAction, mHeldOffhand;
    private static Object enumOffHand;
    /** 自建物理像素投影用 (见 pushPhysicalProjection) */
    private static Method mMatrixMode, mPushMatrix, mPopMatrix, mLoadIdentity, mOrtho, mTranslate;
    private static Field fPlayer, fWorld, fFont, fDisplayW, fDisplayH, fRenderMgr;
    private static Field fPosX, fPosY, fPosZ, fPrevX, fPrevY, fPrevZ, fMotionX, fMotionY, fMotionZ, fOnGround, fYaw, fPitch, fIsDead, fMovingF;
    /** Legacy MovementInput fields.  Scaffold rewrites these immediately after
     * vanilla has sampled the keys, so arbitrary free-camera angles retain the
     * exact same world-space input instead of being quantised back to W/A/S/D. */
    private static Field fMovementInput, fInputStrafe, fInputForward, fInputSneak;
    private static Method mScaffoldCollisions, mScaffoldClipX, mScaffoldClipY, mScaffoldClipZ;
    /** FreeCam 假实体不 tick, 这几个插值量得手动补齐 */
    private static Field fLastTickX, fLastTickY, fLastTickZ;
    /** 静默转身用: 相机取的是 prev 与当前值的插值, 还原时两组都得改, 否则视角会抽 */
    private static Field fPrevYaw, fPrevPitch, fRenderYawOffset, fRotationYawHead, fPrevRenderYawOffset, fPrevRotationYawHead;
    private static Field fScaffoldSwingProgress;
    private static Field fViewerX, fViewerY, fViewerZ, fRenderPosX, fRenderPosY, fRenderPosZ,
            fLoaded, fCapIsFlying, fCapAllowFlying, fCapIsCreative, fCapPlayer, fFallDist, fRightClickDelay, fStrafe;
    private static Method mJump;
    /** Entity.isInvisible —— KillAura 的"打隐身"开关 */
    private static Method mIsInvisible;
    private static Object enumMainHand;
    private static Object guiInstance; // Gui 实例 (drawGradientRect 是实例方法)

    // ===== v3 扩展功能所需 =====
    private static Class<?> cEntityPlayer, cInvPlayer, cContainer, cGuiContainer, cTimer, cPC, cBlockPos, cIAttr;
    /**
     * EnumHand / ClickType 都是 1.9 才有的类，1.8.9 上是 null —— 依赖它们的调用点必须
     * 按版本判断过。cEnumHand 原来是个局部变量，1.8.9 上线时改成字段, 因为好几处要引用它。
     */
    private static Class<?> cEnumHand, cClickType;
    private static Method mSetSprinting, mIsInWater, mIsHandActive, mSetWorldTime, mGetWorldInfo, mSetRaining, mSetThundering;
    private static Method mRespawn, mGetSlot, mGetSlotStack, mWindowClick, mRightClickMouse, mIsAirBlock, mGetBlockState, mCloseScreen;
    private static Method mGetStrVsBlock, mStackGetItem, mGetEntityAttr, mSetBaseValue, mIsDeadM;
    private static Field fStepHeight, fTimer, fTickLength, fHurtTime, fGamma, fGameSettings, fGuiScaleSetting, fOpenContainer;
    private static Field fScaffoldRenderPartial;
    private static Field fMainInventory, fCurrentItem, fCurBlockDamage, fBlockHitDelay, fObjectMouseOver, fPlayerController, fInvSlots, fReachAttr, fInvPlayer, fContainerWindowId;
    /** PlayerControllerMP.isHittingBlock —— 按住左键打方块时为 true。AutoClicker 的 No Mine 靠它 */
    private static Field fIsHittingBlock;
    private static Object clickTypeQuickMove, clickTypeSwap, clickTypeThrow;
    private static Method mGetDWheel;

    // ===== 1.20.1 overlay/input bridge =====
    // 1.20.1 没有 LWJGL2 Keyboard/Mouse，也没有旧版 GuiScreen 的可复用父类。
    // 覆盖层只依赖稳定的 GuiGraphics/GLFW 入口，所有句柄都可选，取不到时自动停用。
    private static Class<?> cGuiGraphics, cWindow, cGlfw;
    private static Method m1201Fill, m1201DrawString, m1201GuiWidth, m1201GuiHeight;
    private static Method m1201GetWindow, m1201WindowHandle, m1201GetKey, m1201GetMouseButton, m1201GetCursorPos;
    private static int m1201GlfwPress = 1;
    private static java.nio.DoubleBuffer cursorX1201, cursorY1201;
    private static boolean overlayMousePrev;
    private static int overlayMouseX, overlayMouseY;

    // ===== v6.3: 补齐桩功能所需 (全部可选, 取不到则该档位自动降级, 不会炸) =====
    /** NoFall 的 Packet 档: 往连接里塞一个 onGround=true 的移动包 */
    private static Field fConnection, fRainingStrength, fPrevRainingStrength, fMouseSensitivity;
    private static Method mSendPacket, mAddPotionEffect, mGetPotionFromRL, mGetEntityId;
    private static Class<?> cPotionEffect, cPacketPlayer, cTryUseItem;
    private static Method mPotionEffects, mPotionEffectValue, mPotionNumericId;
    private static java.lang.reflect.Constructor<?> ctorPacketPlayer, ctorPotionEffect, ctorTryUseItem;
    /** FullBright 的 Night Vision 档: 药水注册表里的夜视实例 */
    private static Object potionNightVision;
    private static java.lang.reflect.Constructor<?> ctorBlockPos;

    // ===== v4 扩展功能所需 (全部可选, 取不到该功能自动降级) =====
    private static Class<?> cFood, cItemStack, cPotion;
    private static Method mSetClipboard, mGetClipboard, mGetName, mCtrlV;
    private static Method mGetFoodStats, mGetFoodLevel, mSetEntityBB, mSetSneaking, mGetHeldItem, mIsRiding;
    private static Field fLegacyStackCount;
    private static Method mGetActivePotion, mIsPotionActive, mGetStackSize, mSetStackSize, mGetItem, mIsEnchanted,
            mStackDamageable, mStackDamage, mStackMaxDamage, mGetArmorValue;
    private static Field fCurrentScreen, fLoadedEntities, fHeldItemF, fRidingEntity, fTicksExisted, fEntityHit, fViewBobbing;
    private static Field fScreenW, fScreenH;
    private static Field fLoadedTileEntities;
    private static Method mGetPos, mVecGetX, mVecGetY, mVecGetZ, mIsOnLadder, mIsAlive, mAabbGrow, mGetBlockPos;
    /** NoSlow 判脚下方块用 (getTranslationKey 是原版方法, 返回串里带注册名) */
    private static Method mGetBlock, mGetBlockKey;
    /** Nuker 逐块挖掘 / AutoFish 认浮标 / 屏幕空间画框 */
    private static Method mOnDamageBlock, mGetAngler, mLineWidth, mGetMaxHealth, mGetItemStack, mGetStackName, mGetUnformattedText, mGetWorldTime;
    /** 1.8.9 上 angler 是字段 (1.12.2 是方法), 两个句柄只会有一个非 null。 */
    private static Field fAngler;
    /** AntiBot / PingDisplay / Trajectories 用 */
    private static Method mGetTeam, mIsOnSameTeam, mGetConnection, mGetPlayerInfo, mGetResponseTime, mGetUUID, mGetItemInUseCount;
    private static Field fDebugFPS;
    /** NoFire / FreeCam / AutoReconnect / Spammer 用 */
    private static Field fEntityFire, fTimeInPortal, fRenderViewEntity, fButtonList, fDisplayString, fJumping;
    /** Minecraft.leftClickCounter —— 原版的挥空惩罚, AutoClicker 要清它 (见 doAutoClick) */
    private static Field fLeftClickCounter;
    private static Method mMousePressed, mSetPosAndRot, mSendChatMessage;
    /** World.spawnEntity / removeEntity —— FreeCam 的假相机实体要真的进世界 */
    private static Method mSpawnEntity, mRemoveEntity;
    /** NameProtect: 遍历在线玩家拿名字 */
    private static Method mGetPlayerInfoMap, mGetGameProfile, mGetProfileName, mGetProfileId, mCombatEntityProfile;
    /** FreeCam 的自由相机实体 (惰性创建, 关掉功能后不再用) */
    private static Object freeCamEntity, freeCamWorld;
    private static double freeCamX, freeCamY, freeCamZ;
    private static boolean freeCamPoseReady;
    private static Object enumUp;
    /** 屏幕空间投影: AABB 六个边界 + 视场角 */
    private static Field mAabbMinX, mAabbMinY, mAabbMinZ, mAabbMaxX, mAabbMaxY, mAabbMaxZ, fFov;
    private static Field fHitType, fRenderDistance;
    private static java.lang.reflect.Constructor<?> ctorAABB;

    private static void initExtra() {
        try {
            cFood = copt("net.minecraft.util.FoodStats");
            cItemStack = copt("net.minecraft.item.ItemStack");
            cPotion = copt("net.minecraft.potion.Potion");
            cGuiContainer = copt("net.minecraft.client.gui.inventory.GuiContainer");

            mGetFoodStats = mopt(cPlayer, "func_71024_bL");
            mGetFoodLevel = mopt(cFood, "func_75116_a");
            mSetEntityBB = mopt(cEntity, "func_174826_a", cAABB);
            mSetSneaking = mopt(cEntity, "func_70095_a", boolean.class);
            mGetHeldItem = mopt(cLiving, "func_184614_ca");
            mIsRiding = mopt(cEntity, "func_184218_aH");
            mGetActivePotion = mopt(cLiving, "func_70660_b", cPotion);
            mIsPotionActive = mopt(cLiving, "func_70644_a", cPotion);
            mGetStackSize = mopt(cItemStack, "func_190916_E");
            if(Ver.is189()) fLegacyStackCount=fopt(cItemStack,"field_77994_a");
            mSetStackSize = mopt(cItemStack, "func_190920_e", int.class);
            mGetItem = mopt(cItemStack, "func_77973_b");
            mStackDamageable = mopt(cItemStack, "func_77984_f");
            mStackDamage = mopt(cItemStack, "func_77952_i");
            mStackMaxDamage = mopt(cItemStack, "func_77958_k");
            mGetArmorValue = mopt(cPlayer, "func_70658_aO");

            fCurrentScreen = fopt(cMC, "field_71462_r");
            Class<?> cGuiScreen2 = copt("net.minecraft.client.gui.GuiScreen");
            fScreenW = fopt(cGuiScreen2, "field_146294_l");
            fScreenH = fopt(cGuiScreen2, "field_146295_m");
            fLoadedEntities = fopt(cWorld, "field_72996_f");
            // 这两个不是字段 —— 1.12.2 里 isSneaking / isSprinting 是 Entity 上的**方法**
            // (func_70093_af / func_70051_ag)。原来当成字段去取, 名字还写错了
            // (field_70093_af 整张 SRG 表里都不存在), 句柄恒为 null, 于是下面 7 处调用
            // 全被 "fX != null &&" 静默跳过, Phase 的 Sneak 模式更是每次进门就 return。
            mIsSneaking = mopt(cEntity, "func_70093_af");
            mIsSprinting = mopt(cEntity, "func_70051_ag");
            // 绑 cPlayer 而不是 cLiving —— 1.8.9 上 itemInUse/activeItemStack 挂在 EntityPlayer,
        // 1.9 才上移到 EntityLivingBase。两边这些句柄都只对玩家用, 所以绑子类 EntityPlayer
        // 在两个版本上都成立, 不需要版本分支。
        fHeldItemF = fopt(cPlayer, "field_184627_bm");
            fRidingEntity = fopt(cEntity, "field_184239_as");
            fTicksExisted = fopt(cEntity, "field_70173_aa");
            fEntityHit = fopt(copt("net.minecraft.util.math.RayTraceResult"), "field_72308_g");

            // 方块实体 (容器透视要用) —— 容器是 TileEntity, 不在 loadedEntityList 里
            Class<?> cTE = copt("net.minecraft.tileentity.TileEntity");
            Class<?> cVec = copt("net.minecraft.util.math.Vec3i");
            fLoadedTileEntities = fopt(cWorld, "field_147482_g");
            mGetPos = mopt(cTE, "func_174877_v");
            mVecGetX = mopt(cVec, "func_177958_n");
            mVecGetY = mopt(cVec, "func_177956_o");
            mVecGetZ = mopt(cVec, "func_177952_p");
            try {
                ctorAABB = cAABB.getConstructor(double.class, double.class, double.class,
                        double.class, double.class, double.class);
            } catch (Throwable ignored) { }

            mIsOnLadder = mopt(cLiving, "func_70617_f_");
            mIsAlive = mopt(cEntity, "func_70089_S");
            try {
                mAabbGrow = cAABB.getMethod("func_72314_b", double.class, double.class, double.class);
            } catch (Throwable ignored) { }

            // 方块轮廓 / 视距扩展
            Class<?> cRTR = copt("net.minecraft.util.math.RayTraceResult");
            mGetBlockPos = mopt(cRTR, "func_178782_a");
            fHitType = fopt(cRTR, "field_72313_a");
            fRenderDistance = fopt(cGameSettings, "field_151451_c");

            log("v5 features init: tileEntities=" + (fLoadedTileEntities != null && mGetPos != null && ctorAABB != null)
                    + " ladder=" + (mIsOnLadder != null) + " alive=" + (mIsAlive != null) + " grow=" + (mAabbGrow != null));

            log("v4 features init: food=" + (mGetFoodLevel != null) + " entityBB=" + (mSetEntityBB != null)
                    + " screen=" + (fCurrentScreen != null) + " entities=" + (fLoadedEntities != null)
                    + " potion=" + (mIsPotionActive != null));
        } catch (Throwable t) {
            log("initExtra 部分失败 (相关功能降级): " + t);
        }
    }

    /** 玩家饥饿值, 取不到返回 -1 */
    private static int foodLevel(Object player) {
        try {
            if (mGetFoodStats == null || mGetFoodLevel == null) return -1;
            Object fs = mGetFoodStats.invoke(player);
            return fs == null ? -1 : (Integer) mGetFoodLevel.invoke(fs);
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 世界实体列表, 取不到返回空列表 */
    private static java.util.List<?> entities(Object world) {
        try {
            if (world == null || fLoadedEntities == null) return java.util.Collections.emptyList();
            Object o = fLoadedEntities.get(world);
            return o instanceof java.util.List ? (java.util.List<?>) o : java.util.Collections.emptyList();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /** 玩家当前是否开着某个 GUI */
    private static boolean screenOpen(Object mc) {
        try {
            return fCurrentScreen != null && fCurrentScreen.get(mc) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 可选反射 (取不到就返回 null, 功能自动降级)。
     *
     * 名字先过 Ver.alias —— 1.8.9 上有一小撮句柄改了名（见 Ver 里的表）。这里是整个工程
     * **唯一**的反射句柄入口，翻译放在这一层，22 个调用点一行都不用改，也不会有新加的
     * 调用点忘了翻。1.12.2 下 alias 是恒等函数，对现有版本零影响。
     */
    private static Method mopt(Class<?> c, String name, Class<?>... ps) {
        if (c == null) return null;
        name = Ver.alias(name);
        try { return c.getMethod(name, ps); } catch (Throwable t) { return null; }
    }

    /**
     * 取私有方法。
     *
     * 必须和 mopt 分开: getMethod **只找 public**, 而 Minecraft.clickMouse / rightClickMouse
     * 在 1.12.2 里都是 private。用 mopt 去取它们会静默拿到 null —— mRightClickMouse 被 15 处
     * 引用, 其中两处是 "null 就 return", 于是那些功能进门就返回, 而且一句报错都没有。
     * 私有成员只能走 getDeclaredMethod + setAccessible。
     */
    private static Method mpriv(Class<?> c, String name, Class<?>... ps) {
        if (c == null) return null;
        name = Ver.alias(name);
        // Some mappings move the controller method to a superclass between
        // 1.8.9 and 1.12.2. getDeclaredMethod alone silently misses that
        // inherited declaration and makes the feature look disabled.
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            try {
                Method m = cur.getDeclaredMethod(name, ps);
                try { m.setAccessible(true); } catch (Throwable ignored) { }
                return m;
            } catch (Throwable ignored) { }
        }
        try { return c.getMethod(name, ps); } catch (Throwable ignored) { return null; }
    }

    /** 初始化 1.20.1 的 GuiGraphics + GLFW 覆盖层句柄。 */
    private static void init1201Overlay() {
        try {
            cGuiGraphics = Class.forName("net.minecraft.client.gui.GuiGraphics", false, cMC.getClassLoader());
            m1201Fill = cGuiGraphics.getMethod("m_280509_", int.class, int.class, int.class, int.class, int.class);
            m1201GuiWidth = cGuiGraphics.getMethod("m_280182_");
            m1201GuiHeight = cGuiGraphics.getMethod("m_280206_");
            if (cFont != null) {
                m1201DrawString = cGuiGraphics.getMethod("m_280488_", cFont,
                        String.class, int.class, int.class, int.class);
            }

            cWindow = Class.forName("com.mojang.blaze3d.platform.Window", false, cMC.getClassLoader());
            m1201WindowHandle = cWindow.getMethod("m_85439_");
            for (Method m : cMC.getMethods()) {
                if (m.getParameterTypes().length == 0 && cWindow.isAssignableFrom(m.getReturnType())) {
                    m1201GetWindow = m;
                    break;
                }
            }

            try {
                cGlfw = Class.forName("org.lwjgl.glfw.GLFW", false, cMC.getClassLoader());
                m1201GetKey = cGlfw.getMethod("glfwGetKey", long.class, int.class);
                m1201GetMouseButton = cGlfw.getMethod("glfwGetMouseButton", long.class, int.class);
                m1201GetCursorPos = cGlfw.getMethod("glfwGetCursorPos", long.class,
                        java.nio.DoubleBuffer.class, java.nio.DoubleBuffer.class);
                cursorX1201 = java.nio.ByteBuffer.allocateDirect(8)
                        .order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();
                cursorY1201 = java.nio.ByteBuffer.allocateDirect(8)
                        .order(java.nio.ByteOrder.nativeOrder()).asDoubleBuffer();
            } catch (Throwable t) {
                log("1.20.1 GLFW 句柄不可用，覆盖层保留但输入停用: " + t);
            }
            log("1.20.1 overlay init: fill=" + (m1201Fill != null)
                    + " text=" + (m1201DrawString != null)
                    + " window=" + (m1201GetWindow != null)
                    + " glfw=" + (m1201GetKey != null));
        } catch (Throwable t) {
            log("1.20.1 overlay init FAILED (仅停用覆盖层): " + t);
            cGuiGraphics = null;
        }
    }

    /** 把旧端的 LWJGL2 键码转换成 GLFW 键码。 */
    private static int glfwKey(int legacy) {
        if (!Ver.is1201()) return legacy;
        switch (legacy) {
            case 54:  return 344; // RSHIFT
            case 211: return 261; // DELETE
            case 17:  return 87;  // W
            case 31:  return 83;  // S
            case 30:  return 65;  // A
            case 32:  return 68;  // D
            case 57:  return 32;  // SPACE
            case 42:  return 340; // LSHIFT
            case 341: return 341; // LCTRL
            default:  return legacy;
        }
    }

    private static long windowHandle1201() {
        try {
            if (m1201GetWindow == null || m1201WindowHandle == null) return 0L;
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object w = m1201GetWindow.invoke(mc);
            return ((Number) m1201WindowHandle.invoke(w)).longValue();
        } catch (Throwable t) {
            logOnce("modern-window-handle", t);
            return 0L;
        }
    }

    private static boolean keyDown(int legacy) {
        try {
            if (!Ver.is1201()) return mKeyDown != null && Boolean.TRUE.equals(mKeyDown.invoke(null, legacy));
            if (m1201GetKey == null) return false;
            return ((Number) m1201GetKey.invoke(null, windowHandle1201(), glfwKey(legacy))).intValue() != 0;
        } catch (Throwable t) { return false; }
    }

    private static boolean mouseDown(int button) {
        try {
            if (!Ver.is1201()) return mIsButtonDown != null && Boolean.TRUE.equals(mIsButtonDown.invoke(null, button));
            if (m1201GetMouseButton == null) return false;
            return ((Number) m1201GetMouseButton.invoke(null, windowHandle1201(), button)).intValue() != 0;
        } catch (Throwable t) { return false; }
    }

    private static int[] cursor1201(int w, int h) {
        try {
            if (m1201GetCursorPos == null || cursorX1201 == null || cursorY1201 == null) return new int[]{w / 2, h / 2};
            cursorX1201.clear(); cursorY1201.clear();
            m1201GetCursorPos.invoke(null, windowHandle1201(), cursorX1201, cursorY1201);
            int x = (int) Math.round(cursorX1201.get(0));
            int y = (int) Math.round(cursorY1201.get(0));
            return new int[]{Math.max(0, Math.min(w - 1, x)), Math.max(0, Math.min(h - 1, y))};
        } catch (Throwable t) { return new int[]{w / 2, h / 2}; }
    }
    private static Field fopt(Class<?> c, String name) {
        if (c == null) return null;
        // 别名由 f() 那一层统一加，这里不再重复 —— 只留一个真相来源，见 f() 的注释。
        try { return f(c, name); } catch (Throwable t) { return null; }
    }
    /**
     * 按版本解析类名（解析不到返回 null，功能自动降级）。
     *
     * **所有**取类的地方都要走这里，包括"这个类肯定在"的那些 —— 类名和 SRG 句柄一样是
     * 版本相关的，只是在真跑起来之前没人会发现。1.9 把 net.minecraft.util 拆出了
     * math / text 两个包，于是 1.12.2 写的 {@code net.minecraft.util.math.AxisAlignedBB}
     * 在 1.8.9 上**根本不存在**，Class.forName 直接抛，整个 initFields 挂掉。
     * 第一次在真 1.8.9 上注入就是这么失败的。翻译表在 Ver.CLS189。
     */
    private static Class<?> copt(String name) {
        return Ver.cls(name);
    }

    /**
     * 硬性要求的类：解析不到就抛，保持 initFields 原来"缺了就崩"的语义。
     * 但必须走 Ver.cls 解析 —— 否则 1.8.9 上会因为包名不同把"只是改了包名"误判成"缺失"。
     */
    private static Class<?> req(String name) throws ClassNotFoundException {
        Class<?> c = Ver.cls(name);
        if (c == null) throw new ClassNotFoundException(name);
        return c;
    }

    /** 玩家身上那份 world (Entity.field_70170_p)。**不要**拿 fWorld 去 get(player), 见 worldOf */
    private static Field fEntityWorld;

    /**
     * 从玩家身上取 world。
     *
     * fWorld 绑的是 **Minecraft.theWorld** (field_71441_e), 不是 Entity.world。所以
     * 写成 fWorld.get(player) 会抛 IllegalArgumentException:
     *   Can not set WorldClient field Minecraft.field_71441_e to EntityPlayerSP
     * 而且异常是在功能调度里被 safe() 吞掉的 —— 表现就是"功能开了但什么都不发生",
     * 光看代码完全看不出来。同一个错曾经在 9 个地方各写了一遍, 一起死了 8 个功能。
     * 统一走这个函数, 从哪进都不会再犯; FieldOwner 探针守着不让它回来。
     */
    private static Object worldOf(Object player) {
        try {
            return (player == null || fEntityWorld == null) ? null : fEntityWorld.get(player);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 挥手 —— 按版本决定带不带参数。
     *
     * 1.12.2 是 {@code swingArm(EnumHand)}，1.8.9 是 {@code swingItem()} 无参、而且根本没有
     * EnumHand 这个类。原来 6 处调用点写的是 {@code mSwing.invoke(player, enumMainHand)}，
     * 其中两处外面还套着 {@code enumMainHand != null} —— 在 1.8.9 上 enumMainHand 恒为 null，
     * 于是那两处直接跳过、另外四处会抛 IllegalArgumentException 被 safe() 吞掉。
     * **挥手会在 1.8.9 上静默全部失效，一句报错都没有** —— 和当初 fEntityWorld 那个错同一类。
     * 统一走这里，调用点不再碰 enumMainHand。
     */
    private static void swingHand(Object player) {
        if (mSwing == null || player == null) return;
        try {
            if (Ver.swingTakesArg()) mSwing.invoke(player, enumMainHand);
            else mSwing.invoke(player);
        } catch (Throwable ignored) {
        }
    }

    private static java.lang.reflect.Method mGlLineWidth;
    private static boolean glLineWidthTried;

    /**
     * 设 GL 线宽。**调用点统一走这里，不要再直接 invoke mLineWidth。**
     *
     * 1.12.2 走 {@code GlStateManager.glLineWidth}（mLineWidth）。
     * 1.8.9 的 GlStateManager **没有**这个方法 —— 句柄恒为 null，原来调用点直接跳过，
     * 于是 BlockOutline 的 Width 和 ESP 那一圈线宽在 1.8.9 上完全没作用。
     *
     * 但那个方法本来也只是转手调 {@code org.lwjgl.opengl.GL11.glLineWidth}，而 LWJGL 2.9
     * 是 1.8.9 自带的，所以这里补一条反射回退就够 —— 两边的最终效果同一条 GL 调用。
     * LWJGL 不在 agent 的编译期 classpath 上（运行时由游戏提供），所以同样只能反射。
     *
     * 拿不到就静默跳过（和原来的行为一致），线宽退回默认值。
     */
    private static float visualLineWidth = 1f;
    private static void setLineWidth(float w) {
        visualLineWidth = w;
        try {
            if (mLineWidth != null) { mLineWidth.invoke(null, w); return; }
            if (!glLineWidthTried) {
                glLineWidthTried = true;
                try {
                    mGlLineWidth = Class.forName("org.lwjgl.opengl.GL11")
                            .getMethod("glLineWidth", float.class);
                } catch (Throwable ignored) {
                    log("GL11.glLineWidth 取不到 —— 线宽设置在本机不生效 (退回默认宽度)");
                }
            }
            if (mGlLineWidth != null) mGlLineWidth.invoke(null, w);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 按版本过滤模式选项。
     *
     * 1.8.9 上原理做不到的选项**直接不出现**在界面里, 而不是留一个点下去什么都不发生的开关
     * —— 那种假开关比没有更糟, 用户会以为功能坏了。判据在 Ver.modAvailable。
     * 1.12.2 下原样返回, 对现有版本零影响。
     */
    private static String[] opts(String... all) {
        java.util.List<String> keep = new java.util.ArrayList<String>();
        for (String s : all) {
            if (Ver.modAvailable(s)) keep.add(s);
        }
        return keep.toArray(new String[keep.size()]);
    }

    /**
     * 画一个描边框 —— 颜色单位按版本换算。
     *
     * 1.12.2 的 {@code drawSelectionBoundingBox(bb, float,float,float,float)} 收 0..1 的浮点；
     * 1.8.9 只有 {@code func_181563_a(bb, int,int,int,int)}，收 0..255 的整数，而且名字也换了。
     * 直接换个名字是不够的 —— 参数类型不对，invoke 会抛 IllegalArgumentException，
     * 被 safe() 吞掉，表现就是 ESP 框画不出来但日志里什么都没有。
     */
    private static boolean drawBoxProbed, glErrProbed, espCallProbed;

    private static void drawOutline(Object bb, float r, float g, float b) throws Exception {
        if (mDrawBox == null || bb == null) return;
        if (sunsetOverlays) {
            int tint = sunset(0.35, rainbowMode);
            r = ((tint >> 16) & 255) / 255f; g = ((tint >> 8) & 255) / 255f; b = (tint & 255) / 255f;
        }
        if (!drawBoxProbed) {            drawBoxProbed = true;
            StringBuilder sb = new StringBuilder();
            sb.append("drawBox 探针: static=")
              .append(java.lang.reflect.Modifier.isStatic(mDrawBox.getModifiers()))
              .append(" 参数=");
            for (Class<?> pc : mDrawBox.getParameterTypes()) sb.append(pc.getSimpleName()).append(' ');
            sb.append("| 首次参数 bb=").append(bb)
              .append(" rgb=(").append((int) (r * 255)).append(',')
              .append((int) (g * 255)).append(',').append((int) (b * 255)).append(')');
            try {
                java.lang.reflect.Field f = bb.getClass().getField("minX");
                sb.append(" bb.minX=").append(f.getDouble(bb));
                java.lang.reflect.Field f2 = bb.getClass().getField("maxX");
                sb.append(" maxX=").append(f2.getDouble(bb));
            } catch (Throwable ignored) { }
            log(sb.toString());
        }
        float originalWidth = visualLineWidth;
        try {
            if (bloom > 0 && bloomSpread > 0) {
                for (int pass = 2; pass > 0; pass--) {
                    setLineWidth(originalWidth + pass * .75f);
                    float alpha = bloom * (pass == 2 ? 0.025f : 0.055f);
                    if (Ver.is189()) mDrawBox.invoke(null, bb, (int)(r*255), (int)(g*255), (int)(b*255), (int)(alpha*255));
                    else mDrawBox.invoke(null, bb, r, g, b, alpha);
                }
            }
            setLineWidth(originalWidth);
            if (Ver.is189()) mDrawBox.invoke(null, bb, (int)(r*255), (int)(g*255), (int)(b*255), 255);
            else mDrawBox.invoke(null, bb, r, g, b, 1f);
        } finally { setLineWidth(originalWidth); }
        // GL 错误码 —— 如果绘制被 GL 拒绝, 这里会给出确切的错号。
        // 之前只知道"调用了 54 次没报错、屏幕上没有", 分不清是状态被污染还是绘制被丢弃。
        if (!glErrProbed) {
            glErrProbed = true;
            // LWJGL 不在 agent 的编译期 classpath 上 (运行时由游戏提供), 所以全部走反射 ——
            // 顺带避免了类加载顺序问题。
            try {
                Class<?> cGL11 = Class.forName("org.lwjgl.opengl.GL11");
                Object e = cGL11.getMethod("glGetError").invoke(null);
                log("GL 错误码 (首次 drawBox 后): " + e + ("0".equals(String.valueOf(e)) ? " (无错)" : " <-- 非 0!"));
                // 投影矩阵是判定"调用时机"的唯一硬证据:
                //   透视 (世界渲染阶段): m00 是个位数, 比如 1.3
                //   正交 (GUI 阶段):     m00 = 2/屏幕宽, 约 0.0008
                // 世界坐标的框如果在正交矩阵下画, 会被压成一个点 —— 表现正是"调用了但看不见"。
                java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(64)
                        .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
                int projEnum = ((Number) cGL11.getField("GL_PROJECTION_MATRIX").get(null)).intValue();
                cGL11.getMethod("glGetFloat", int.class, java.nio.FloatBuffer.class)
                     .invoke(null, Integer.valueOf(projEnum), fb);
                float m00 = fb.get(0), m11 = fb.get(5);
                log("绘制时的投影矩阵 m00=" + m00 + " m11=" + m11
                        + " -> " + (Math.abs(m00) > 0.05f ? "透视 (世界阶段, 正确)" : "正交 (GUI 阶段, 画不出来!)"));
            } catch (Throwable t) { log("GL 探针失败: " + t); }
        }
    }

    /**
     * 快速移动物品（shift 点击）—— 第 4 个参数按版本分派。
     *
     * 1.12.2 传 ClickType.QUICK_MOVE 枚举，1.8.9 传 int 1（那个版本没有 ClickType 类）。
     * 三个调用点原来写的是 {@code mWindowClick.invoke(pc, 0, slot, 0, clickTypeQuickMove, player)}，
     * 而 clickTypeQuickMove 在 1.8.9 上恒为 null —— 外面还都套着 {@code clickTypeQuickMove == null}
     * 的守卫，于是换装整套逻辑静默全废。统一走这里，调用点不再碰 clickTypeQuickMove。
     */
    private static void quickMove(Object pc, int slot, Object player) throws Exception {
        Object mode = Ver.windowClickTakesModeInt() ? Integer.valueOf(1) : clickTypeQuickMove;
        mWindowClick.invoke(pc, openWindowId(player), slot, 0, mode, player);
    }

    private static int openWindowId(Object player) {
        try {
            Object cont = fOpenContainer == null ? null : fOpenContainer.get(player);
            return cont != null && fContainerWindowId != null ? fContainerWindowId.getInt(cont) : 0;
        } catch (Throwable ignored) { return 0; }
    }

    /** Swap a container slot with a real hotbar index (0..8). */
    private static void swapHotbar(Object pc, int slot, int hotbar, Object player) throws Exception {
        Object mode = Ver.windowClickTakesModeInt() ? Integer.valueOf(2) : clickTypeSwap;
        if (mode == null) return;
        mWindowClick.invoke(pc, openWindowId(player), slot, hotbar, mode, player);
    }

    /** Drop the complete stack from a container slot. */
    private static void dropStack(Object pc, int slot, Object player) throws Exception {
        Object mode = Ver.windowClickTakesModeInt() ? Integer.valueOf(4) : clickTypeThrow;
        if (mode == null) return;
        mWindowClick.invoke(pc, openWindowId(player), slot, 1, mode, player);
    }

    /**
     * 浮标的施钓者。1.12.2 走方法 getAngler()，1.8.9 走字段 angler —— 那边没有这个方法。
     * 两个句柄只会有一个非 null，都在 initExtra 里按版本绑好。
     */
    private static Object anglerOf(Object hook) {
        if (hook == null) return null;
        try {
            if (Ver.anglerIsField()) return fAngler == null ? null : fAngler.get(hook);
            return mGetAngler == null ? null : mGetAngler.invoke(hook);
        } catch (Throwable t) {
            return null;
        }
    }

    private static volatile boolean inited = false;

    /**
     * Hook 到底装上没有。给 LiteAgent 的"重复注入"分支用 ——
     * 光说"我早就在了"会让注入器把一次失败的注入报成成功, 必须连状态一起说。
     */
    public static boolean isInited() {
        return inited && (!Ver.is1201() || (ModernRuntime.ready() && modernGuiPainted));
    }

    public static String readinessReason() {
        if (!inited) return "Hook 初始化尚未完成，请检查 agent 日志";
        if (!Ver.is1201()) return "";
        if (!ModernRuntime.ready()) return "1.20.1 渲染或按键入口尚未安装完成";
        if (!modernHudSeen) return "GUI 尚未收到游戏绘制回调，请先进入存档或服务器后再确认注入";
        return modernGuiPainted ? "" : "GUI 绘制尚未成功: " + modernGuiFailure;
    }

    private static void ensure() throws Exception {
        if (inited) return;
        try {
            // 版本必须在这里定死, 而且要在这句话之前 —— initFields 里每一个 mopt/fopt
            // 都会去过 Ver.alias, 检测没跑完的话别名表会按 1.12.2 的空表来翻。
            log("MC 版本: " + Ver.name() + "   (句柄别名 " + Ver.aliasCount() + " 条, 副手=" + Ver.hasOffhand() + ")");
            initFields();
            if (!Ver.is1201()) initExtra();
            inited = true;
            log("Hook init OK (" + VERSION + " on MC " + Ver.name() + ")");
        } catch (Throwable t) {
            log("Hook init FAILED: " + t);
            throw t;
        }
    }

    private static void initFields() throws Exception {
        // 这里原来是裸的 Class.forName。
        //
        // **任何一句裸 Class.forName 抛异常, initFields 就整个挂掉, 后面所有句柄
        // 都不初始化** —— 1086-1089 行的注释自己记了这件事 (1.8.9 上 AxisAlignedBB),
        // 1330 行也记了一次, 但那两次都只给**方法**查找包了 try, 类名查找一个都没包。
        //
        // 改走 req/copt (= Ver.cls) 之后:
        //   1.12.2 -> Ver.cls 直接 probe() = Class.forName, **逐字节等价**
        //   1.8.9  -> 多一层 CLS189。而这 17 个类名里**没有一条**在 CLS189 表里
        //             (CLS189 只有 util.math.* / util.text.* / 两个网络包类),
        //             所以对 1.8.9 同样是零改动。
        //   1.20.1 -> 走 CLS1201 换包名。这正是它现在缺的一环。
        // 也就是说: 这次改动对两个**已在跑的**版本可证明无影响, 只影响 1.20.1。
        cMC = req("net.minecraft.client.Minecraft");
        if (Ver.is1201()) {
            // The modern runtime currently exposes an overlay, not legacy gameplay modules.
            // Do not initialize deleted 1.12.2 fields/methods just to show this overlay.
            cFont = req("net.minecraft.client.gui.FontRenderer");
            mGetMC = cMC.getMethod("m_91087_");
            fFont = cMC.getField("f_91062_");
            init1201Overlay();
            if (cGuiGraphics == null || m1201DrawString == null || m1201GetWindow == null
                    || m1201GetKey == null)
                throw new IllegalStateException("1.20.1 overlay/input initialization incomplete");
            return;
        }
        cEntity = req("net.minecraft.entity.Entity");
        cPlayer = req("net.minecraft.entity.player.EntityPlayer");
        cLiving = req("net.minecraft.entity.EntityLivingBase");
        // 只用来区分"动物"和"怪物"两类目标。类名在运行时是可读的 (只有方法/字段被 SRG 混淆),
        // 所以这两个能直接查。
        // 注意: 动物走 EntityAnimal (牛猪羊鸡…), 怪物走 EntityMob (僵尸骷髅…)。
        // 村民/铁傀儡/蝙蝠/鱿鱼不属于这两类, 用 Target=All 才能打到。
        cAnimal = copt("net.minecraft.entity.passive.EntityAnimal");
        cMob = copt("net.minecraft.entity.monster.EntityMob");
        cWorld = req("net.minecraft.world.World");
        cCap = req("net.minecraft.entity.player.PlayerCapabilities");
        cRM = req("net.minecraft.client.renderer.entity.RenderManager");
        cRG = req("net.minecraft.client.renderer.RenderGlobal");
        cAABB = req("net.minecraft.util.math.AxisAlignedBB");
        cGui = req("net.minecraft.client.gui.Gui");
        cFont = req("net.minecraft.client.gui.FontRenderer");
        cGL = copt("net.minecraft.client.renderer.GlStateManager");
        cGuiScreen = req("net.minecraft.client.gui.GuiScreen");
        // Keyboard/Mouse 是 **LWJGL 2**。1.20 用 LWJGL 3, 这两类整类消失 ——
        // 走 copt 会在 1.20.1 上解析成 null (CLS_GONE_1201 里登记了), 由下面的
        // 空值守卫降级, 而不是把 initFields 干掉。
        cKb = copt("org.lwjgl.input.Keyboard");
        cMouse = copt("org.lwjgl.input.Mouse");
        cGameSettings = req("net.minecraft.client.settings.GameSettings");

        mGetMC = mopt(cMC, "func_71410_x");
        // 1.20.1 的 distanceToSqr 接收 Vec3，不再接收 Entity；距离由 distanceSq() 适配。
        mDistSq = Ver.is1201() ? null : mopt(cEntity, "func_70068_e", cEntity);
        mEyeH = mopt(cEntity, "func_70047_e");
        mIsInvisible = null;
        try { mIsInvisible = mopt(cEntity, "func_82150_aj"); } catch (Throwable ignored) { }
        mAttack = mopt(cPlayer, "func_71059_n", cEntity);
        // 这两行不能过 mopt: 它们直接调 getMethod 而不是走别名表, 而名字在两个版本上
        // 都不一样。mCooled 在 1.8.9 上根本没有对应物(攻击冷却 1.9 才有), 所以必须包 try ——
        // 裸着写会让整个 initExtra 抛异常, 而 ensure() 一抛, 后面所有句柄都不初始化。
        try { mCooled = mopt(cPlayer, "func_184825_o", float.class); }
        catch (Throwable t) { mCooled = null; }
        // canEntityBeSeen 在 EntityLivingBase 上 (func_70685_l), 不是 Entity
        try { mCanSee = mopt(cLiving, "func_70685_l", cEntity); } catch (Throwable t) { mCanSee = null; }
        // 同上: isUsingItem 在 1.8.9 上是 EntityPlayer 声明的, 绑 cLiving 会取不到。
        try { mHandActive = mopt(cPlayer, "func_184587_cr"); } catch (Throwable t) { mHandActive = null; }
        // 挥手: 1.12.2 swingArm(EnumHand), 1.8.9 swingItem() 无参且没有 EnumHand
        // EnumHand 在 1.20.1 上叫 InteractionHand —— 走 copt 让它过 CLS1201。
        try {
            mSwing = Ver.swingTakesArg()
                    ? mopt(cLiving, "func_184609_a", copt("net.minecraft.util.EnumHand"))
                    : mopt(cLiving, "func_184609_a");
        } catch (Throwable t) { mSwing = null; }
        mGetHealth = mopt(cLiving, "func_110143_aJ");
        mGetBB = mopt(cEntity, "func_174813_aQ");
        mCombatCanCollide = mopt(cEntity, "func_70067_L");
        mCombatCollisionBorder = mopt(cEntity, "func_70111_Y");
        mCombatSpectator = mopt(cPlayer, "func_175149_v");
        mAabbOffset = mopt(cAABB, "func_72317_d", double.class, double.class, double.class);
        mFlySpeed = mopt(cCap, "func_75092_a", float.class);
        // 画框。1.12.2 是 func_189697_a(AxisAlignedBB, float×4), 1.8.9 只有
        // func_181563_a(AxisAlignedBB, int×4) —— 名字换了, 颜色也从 float(0..1) 变成 int(0..255)。
        // 别名表里那条必须是 181563 而不是 181561: 后者只收一个 AxisAlignedBB, 没有颜色参数,
        // 换过去也画不出彩色的框。这里两个分支写开, 调用点走 drawOutline()。
        if (!Ver.is1201() && Ver.is189()) {
            mDrawBox = mopt(cRG, "func_181563_a", cAABB, int.class, int.class, int.class, int.class);
        } else if (!Ver.is1201()) {
            mDrawBox = mopt(cRG, "func_189697_a", cAABB, float.class, float.class, float.class, float.class);
        }
        mDrawRect = Ver.is1201() ? null : mopt(cGui, "func_73734_a", int.class, int.class, int.class, int.class, int.class);
        // drawGradientRect 是 protected 实例方法: 必须 getDeclaredMethod + setAccessible, 且需要 Gui 实例
        if (!Ver.is1201()) {
            mDrawGradient = mpriv(cGui, "func_73733_a", int.class, int.class, int.class, int.class, int.class, int.class);
        }

        // 物理像素投影: GlStateManager 的矩阵栈操作 (SRG 名)
        // matrixMode/pushMatrix/popMatrix/loadIdentity/ortho
        //
        // **GlStateManager 在 1.17 被删除了**, 1.20.1 上解析成 null。这几行原来是裸的
        // Class.forName + 无守卫的 getMethod, 在 1.20.1 上会直接 NPE。
        // 这里整体包一层: 拿不到就留 null, 调用点本来就有 null 判断
        // (setLineWidth 的注释里写了同样的降级套路)。
        Class<?> cGSM = copt("net.minecraft.client.renderer.GlStateManager");
        if (cGSM != null) {
            mMatrixMode = cGSM.getMethod("func_179128_n", int.class);
            mPushMatrix = cGSM.getMethod("func_179094_E");
            mPopMatrix = cGSM.getMethod("func_179121_F");
            mLoadIdentity = cGSM.getMethod("func_179096_D");
            mOrtho = cGSM.getMethod("func_179130_a",
                    double.class, double.class, double.class, double.class, double.class, double.class);
            mTranslate = cGSM.getMethod("func_179109_b", float.class, float.class, float.class);
        } else {
            log("GlStateManager 不存在 —— 1.17+ 已删除, 自绘 GUI 的矩阵栈操作降级 (1.20.1 上属预期)");
        }
        try {
            java.lang.reflect.Constructor<?> ctor = cGui.getDeclaredConstructor();
            ctor.setAccessible(true);
            guiInstance = ctor.newInstance();
        } catch (Throwable t) {
            log("Gui instance create FAILED: " + t);
        }
        mDrawString = Ver.is1201() ? null : mopt(cFont, "func_175063_a", String.class, float.class, float.class, int.class);
        mGetStrW = mopt(cFont, "func_78256_a", String.class);
        mDisplayGui = mopt(cMC, "func_147108_a", cGuiScreen);
        // org.lwjgl.input.* 是 LWJGL 2, 1.20 上整类消失 -> cKb/cMouse 为 null。
        // 原来这三行是裸调用, 在 1.20.1 上会 NPE。
        if (cKb != null) mKeyDown = cKb.getMethod("isKeyDown", int.class);
        if (cMouse != null) mIsButtonDown = cMouse.getMethod("isButtonDown", int.class);
        if (cKb != null) {
            try { mKeyName = cKb.getMethod("getKeyName", int.class); }
            catch (NoSuchMethodException e) { mKeyName = null; }
        } else {
            log("org.lwjgl.input.Keyboard 不存在 —— LWJGL 2 的类, 1.20 用 LWJGL 3 (InputConstants)");
        }

        if (Ver.is1201()) init1201Overlay();

        fPlayer = f(cMC, "field_71439_g");
        fWorld = f(cMC, "field_71441_e");
        fEntityWorld = f(cEntity, "field_70170_p");
        fFont = f(cMC, "field_71466_p");
        fDisplayW = f(cMC, "field_71443_c");
        fDisplayH = f(cMC, "field_71440_d");
        fRenderMgr = f(cMC, "field_175616_W");

        fPosX = f(cEntity, "field_70165_t");
        fPosY = f(cEntity, "field_70163_u");
        fPosZ = f(cEntity, "field_70161_v");
        fPrevX = f(cEntity, "field_70169_q");
        fPrevY = f(cEntity, "field_70167_r");
        fPrevZ = f(cEntity, "field_70166_s");
        // 渲染插值专用的上一 tick 位置 (lastTickPos) —— fLastTickX/Y/Z 在下面初始化,
        // 原本只有 FreeCam 的假实体在用。**和 fPrevX/Y/Z 不是一回事**:
        // lastTickPos 是原版 RenderManager 画实体时用的, prevPos 是物理 tick 用的。
        // 差一整个 tick, 疾跑时肉眼可见。见 entX/entY/entZ 上面的长注释。
        // FreeCam 的假实体不在世界里, 没人 tick 它 —— 这几个插值量必须手动补, 见 setEntityInterp
        fLastTickX = f(cEntity, "field_70142_S");
        fLastTickY = f(cEntity, "field_70137_T");
        fLastTickZ = f(cEntity, "field_70136_U");
        fMotionX = f(cEntity, "field_70159_w");
        fMotionY = f(cEntity, "field_70181_x");
        fMotionZ = f(cEntity, "field_70179_y");
        fOnGround = f(cEntity, "field_70122_E");
        fYaw = f(cEntity, "field_70177_z");
        fPitch = f(cEntity, "field_70125_A");
        fPrevYaw = f(cEntity, "field_70126_B");
        fPrevPitch = f(cEntity, "field_70127_C");
        // 玩偶朝向 (模型渲染读这几个, 第三人称才看得见转身)
        fRenderYawOffset = fopt(cLiving, "field_70761_aq");
        fScaffoldSwingProgress = fopt(cLiving, "field_70733_aJ");
        fRotationYawHead = fopt(cLiving, "field_70759_as");
        fPrevRenderYawOffset = fopt(cLiving, "field_70760_ar");
        fPrevRotationYawHead = fopt(cLiving, "field_70758_at");
        cItemBlock = copt("net.minecraft.item.ItemBlock");
        fIsDead = f(cEntity, "field_70128_L");

        fMovingF = f(cLiving, "field_191988_bg");   // moveForward
        if (!Ver.is1201()) {
            Class<?> cPlayerSP = req("net.minecraft.client.entity.EntityPlayerSP");
            Class<?> cMovementInput = copt("net.minecraft.util.MovementInput");
            // movementInput is declared on EntityPlayerSP in 1.8.9/1.12.2, not
            // on the shared EntityPlayer base class. Looking it up on cPlayer
            // makes Hook initialization fail before any module can run.
            fMovementInput = f(cPlayerSP, "field_71158_b");
            fInputStrafe = f(cMovementInput, "field_78902_a");
            fInputForward = f(cMovementInput, "field_78900_b");
            fInputSneak = fopt(cMovementInput, "field_78899_d");
            mScaffoldCollisions = mopt(cWorld, "func_184144_a", cEntity, cAABB);
            mScaffoldClipX = mopt(cAABB, "func_72316_a", cAABB, double.class);
            mScaffoldClipY = mopt(cAABB, "func_72323_b", cAABB, double.class);
            mScaffoldClipZ = mopt(cAABB, "func_72322_c", cAABB, double.class);
        }
        fViewerX = f(cRM, "field_78730_l");
        fViewerY = f(cRM, "field_78731_m");
        fViewerZ = f(cRM, "field_78728_n");
        // RenderManager's render origin is the coordinate space consumed by
        // EntityRenderer's captured model-view matrix.  viewerPos is a
        // different value and cannot be substituted while sprint bob/FOV is active.
        fRenderPosX = f(cRM, "field_78725_b");
        fRenderPosY = f(cRM, "field_78726_c");
        fRenderPosZ = f(cRM, "field_78723_d");
        fLoaded = f(cWorld, "field_72996_f");
        fCapPlayer = f(cPlayer, "field_71075_bZ");
        mSetClipboard = mopt(cGuiScreen, "func_146275_d", String.class);
        mGetClipboard = mopt(cGuiScreen, "func_146277_j");
        mCtrlV = mopt(cGuiScreen, "func_175279_e", int.class);      // isKeyComboCtrlV
        mGetName = mopt(cEntity, "func_70005_c_");
        fCapIsFlying = f(cCap, "field_75100_b");
        fCapAllowFlying = f(cCap, "field_75101_c");
        fCapIsCreative = f(cCap, "field_75098_d");
        fFallDist = f(cEntity, "field_70143_R");            // fallDistance
        fRightClickDelay = f(cMC, "field_71467_ac");        // rightClickDelayTimer
        fStrafe = f(cLiving, "field_70702_br");             // moveStrafing
        mJump = cLiving.getDeclaredMethod("func_70664_aZ"); // EntityLivingBase.jump()
        mJump.setAccessible(true);

        // EnumHand 是 1.9 才有的。1.8.9 上没有这个类, 拿不到就留 null ——
        // 原来这里是无保护地 getEnumConstants()[0], 在 1.8.9 上必抛。
        // 依赖它的调用点全部已经按版本判断过 (见 swingHand)。
        cEnumHand = Ver.cls("net.minecraft.util.EnumHand");
        enumMainHand = cEnumHand == null ? null : cEnumHand.getEnumConstants()[0];

        // ===== v3 扩展功能初始化 (全部可选, 取不到则该功能自动降级) =====
        cEntityPlayer = copt("net.minecraft.entity.player.EntityPlayer");
        cInvPlayer = copt("net.minecraft.entity.player.InventoryPlayer");
        cContainer = copt("net.minecraft.inventory.Container");
        cTimer = copt("net.minecraft.util.Timer");
        cPC = copt("net.minecraft.client.multiplayer.PlayerControllerMP");
        cBlockPos = copt("net.minecraft.util.math.BlockPos");
        cIAttr = copt("net.minecraft.entity.ai.attributes.IAttribute");

        mSetSprinting = mopt(cEntity, "func_70031_b", boolean.class);
        mIsInWater = mopt(cEntity, "func_70090_H");
        mIsHandActive = mopt(cPlayer, "func_184587_cr");   // 1.8.9 上在 EntityPlayer, 见 mHandActive
        mSetWorldTime = mopt(cWorld, "func_72877_b", long.class);
        mGetWorldInfo = mopt(cWorld, "func_72912_H");
        Class<?> cWorldInfo = copt("net.minecraft.world.storage.WorldInfo");
        mSetRaining = mopt(cWorldInfo, "func_76084_b", boolean.class);
        mSetThundering = mopt(cWorldInfo, "func_76069_a", boolean.class);
        mRespawn = mopt(cEntityPlayer, "func_71004_bE");
        mIsAirBlock = mopt(cWorld, "func_175623_d", cBlockPos);
        mGetBlockState = mopt(cWorld, "func_180495_p", cBlockPos);
        mRightClickMouse = mpriv(cMC, "func_147121_ag");   // private, 不能走 mopt
        Class<?> blockStack = copt("net.minecraft.item.ItemStack");
        mStackUseAction = mopt(blockStack, "func_77975_n");
        mBlockStop = mopt(cPC, "func_78766_c", cEntityPlayer);
        mBlockUse = Ver.is189() ? mopt(cPC, "func_78769_a", cEntityPlayer, cWorld, blockStack)
                : mopt(cPC, "func_187101_a", cEntityPlayer, cWorld, cEnumHand);
        mHeldOffhand = Ver.is189() ? null : mopt(cLiving, "func_184592_cb");
        if (cEnumHand != null) for (Object h : cEnumHand.getEnumConstants())
            if ("OFF_HAND".equals(String.valueOf(h))) enumOffHand = h;
        mGetSlot = mopt(cContainer, "func_75139_a", int.class);
        fContainerWindowId = fopt(cContainer, "field_75152_c");
        Class<?> cSlot = copt("net.minecraft.inventory.Slot");
        mGetSlotStack = mopt(cSlot, "func_75211_c");
        // windowClick: 第 4 个参数两个版本不是一种东西 —— 1.12.2 传 ClickType.QUICK_MOVE,
        // 1.8.9 传 int(1) 因为那边没有 ClickType 这个类。
        // 原来只按 1.12.2 写: 1.8.9 上 cClickType 是 null, getMethod 的参数列表里塞 null 会抛,
        // mopt 把它吞掉 -> mWindowClick 恒为 null -> 换装那三个功能被自己的
        // "clickTypeQuickMove == null" 守卫静默挡掉, 一句报错都没有。
        cClickType = copt("net.minecraft.inventory.ClickType");
        if (cClickType != null) {
            for (Object o : cClickType.getEnumConstants()) {
                if ("QUICK_MOVE".equals(o.toString())) clickTypeQuickMove = o;
                else if ("SWAP".equals(o.toString())) clickTypeSwap = o;
                else if ("THROW".equals(o.toString())) clickTypeThrow = o;
            }
            // Controller methods are public in most 1.12.2 mappings, but a
            // few Forge distributions keep the bridge package-private.
            // Resolve through the declared-method path so inventory helpers
            // do not silently disable themselves on those builds.
            mWindowClick = mpriv(cPC, "func_187098_a", int.class, int.class, int.class, cClickType, cEntityPlayer);
        } else {
            mWindowClick = mpriv(cPC, "func_78753_a", int.class, int.class, int.class, int.class, cEntityPlayer);
        }
        Class<?> cItemStack = copt("net.minecraft.item.ItemStack");
        mStackGetItem = mopt(cItemStack, "func_77973_b");
        Class<?> cItem = copt("net.minecraft.item.Item");
        Class<?> cBlockState = copt("net.minecraft.block.state.IBlockState");
        // getStrVsBlock 的**名字两个版本一样**, 但第 2 个参数 1.12.2 收 IBlockState、
        // 1.8.9 收 Block —— SRG 名是每个版本各自编的, 同名不等于同签名, 这一类光靠别名表
        // 永远查不出来 (句柄在两边都能解析, 只是解析到的方法不一样)。
        // 调用点用 state.getBlock() 补上这个转换, 见 mGetBlock。
        Class<?> cBlock2 = copt("net.minecraft.block.Block");
        mGetStrVsBlock = mopt(cItem, "func_150893_a", cItemStack, Ver.is189() ? cBlock2 : cBlockState);
        mGetEntityAttr = mopt(cLiving, "func_110148_a", cIAttr);
        Class<?> cIAttrInst = copt("net.minecraft.entity.ai.attributes.IAttributeInstance");
        mSetBaseValue = mopt(cIAttrInst, "func_111128_a", double.class);
        mGetDWheel = mopt(copt("org.lwjgl.input.Mouse"), "getDWheel");

        fStepHeight = fopt(cEntity, "field_70138_W");
        fTimer = fopt(cMC, "field_71428_T");
        fScaffoldRenderPartial = Ver.is1201() ? null
                : fopt(cTimer, "field_194147_b");
        fTickLength = fopt(cTimer, "field_194149_e");
        fHurtTime = fopt(cLiving, "field_70737_aN");
        mIsEnchanted = mopt(cItemStack, "func_77948_v");   // AutoArmor 的 Prefer Enchanted 用

        // NoSlow 判脚下方块: World.getBlockState(已有) -> IBlockState.getBlock -> Block.getTranslationKey
        mGetBlock = mopt(copt("net.minecraft.block.state.IBlockState"), "func_177230_c");
        mGetBlockKey = mopt(copt("net.minecraft.block.Block"), "func_149739_a");
        // ChestStealer 拿完关箱子
        mCloseScreen = mopt(cMC, "func_147108_a", copt("net.minecraft.client.gui.GuiScreen"));

        // Nuker: 逐块挖。枚举常量在混淆后名字不变, 所以 UP 可以直接按名字取, 不用查 SRG。
        Class<?> cFacing = copt("net.minecraft.util.EnumFacing");
        mOnDamageBlock = mopt(cPC, "func_180512_c",
                ctorBlockPos == null ? null : ctorBlockPos.getDeclaringClass(), cFacing);
        if (cFacing != null) {
            try { enumUp = cFacing.getField("UP").get(null); } catch (Throwable ignored) { }
            // 按 ordinal 顺序存一遍 —— 6 个枚举常量在混淆后名字不变, 可以直接按名字取
            String[] names = {"DOWN", "UP", "NORTH", "SOUTH", "WEST", "EAST"};
            Object[] fc = new Object[6];
            boolean okAll = true;
            for (int i = 0; i < 6; i++) {
                try { fc[i] = cFacing.getField(names[i]).get(null); } catch (Throwable t) { okAll = false; }
            }
            facingConsts = okAll ? fc : null;
        }
        // Scaffold 直接落方块: 自己给方块坐标 + 面 + 命中点, 不走 mc.objectMouseOver。
        // 这是必须的 —— objectMouseOver 是用玩家**真实**视角算的, 静默转身时它压根没指着
        // 目标方块, 原来那条 rightClickMouse() 路径在 Silent 档下必然失效。
        mClickMouse = mpriv(cMC, "func_147116_af");        // private, 不能走 mopt
        try {
            ctorVec3d = copt("net.minecraft.util.math.Vec3d")
                    .getConstructor(double.class, double.class, double.class);
        } catch (Throwable t) {
            ctorVec3d = null;
        }
        // The five-argument world picker is shared by 1.8.9 and 1.12.2.
        // 1.20 uses ClipContext and has no legacy Scaffold placement path.
        mScaffoldRayTrace = null;
        mScaffoldLookVector = null;
        mCombatBoxContains = mCombatBoxIntercept = null;
        fScaffoldHitSide = fScaffoldHitVec = null;
        fScaffoldVecX = fScaffoldVecY = fScaffoldVecZ = null;
        if (!Ver.is1201()) {
            mScaffoldLookVector = mpriv(cEntity, "func_174806_f", float.class, float.class);
            Class<?> cScaffoldVec = copt("net.minecraft.util.math.Vec3d");
            Class<?> cScaffoldHit = copt("net.minecraft.util.math.RayTraceResult");
            Class<?> cCombatBox = copt("net.minecraft.util.math.AxisAlignedBB");
            mCombatBoxContains = mopt(cCombatBox, "func_72318_a", cScaffoldVec);
            mCombatBoxIntercept = mopt(cCombatBox, "func_72327_a", cScaffoldVec, cScaffoldVec);
            mScaffoldRayTrace = mopt(cWorld, "func_147447_a", cScaffoldVec, cScaffoldVec,
                    boolean.class, boolean.class, boolean.class);
            fScaffoldHitSide = fopt(cScaffoldHit, "field_178784_b");
            fScaffoldHitVec = fopt(cScaffoldHit, "field_72307_f");
            fScaffoldVecX = fopt(cScaffoldVec, "field_72450_a");
            fScaffoldVecY = fopt(cScaffoldVec, "field_72448_b");
            fScaffoldVecZ = fopt(cScaffoldVec, "field_72449_c");
        }
        // 这里**必须**用 cBlockPos (1129 行就解析好了)。
        // 原来写的是 ctorBlockPos.getDeclaringClass() —— 而 ctorBlockPos 要到本方法靠后的
        // 1297 行才赋值, 在这里还是 null, getDeclaringClass() 直接抛 NPE, 被下面的 catch
        // 吞掉, mProcessRightClickBlock 静默变成 null。于是 Scaffold 只能退回 rightClickMouse,
        // 而那条路读的是真实准星 —— 静默转身档下必然放不出去。
        // 上面 1179 行同一个坑作者用 "== null ? null :" 绕过了, 这行没绕过。
        try {
            Class<?> cPCtl = copt("net.minecraft.client.multiplayer.PlayerControllerMP");
            Class<?> cSP = copt("net.minecraft.client.entity.EntityPlayerSP");
            Class<?> cWC = copt("net.minecraft.client.multiplayer.WorldClient");
            Class<?> cV3 = copt("net.minecraft.util.math.Vec3d");
            // 签名在 1.9 换过, 而且换得比改名狠 —— 第 3 个参数从**方块坐标**变成了**手持物品**:
            //   1.12.2: (EntityPlayerSP, WorldClient, BlockPos,   EnumFacing, Vec3d, EnumHand)
            //   1.8.9 : (EntityPlayerSP, WorldClient, ItemStack,  EnumFacing, Vec3,  ) -> boolean
            // 所以 1.8.9 上不能要 EnumHand (那个类不存在), 也不能用 cBlockPos 顶第 3 位。
            // 取不到就返回 null, Scaffold 退回 rightClickMouse —— 而那条路读真实准星,
            // 静默转身档下必然放不出去, 也就是"搭路不工作"。
            Class<?> cItSt = copt("net.minecraft.item.ItemStack");
            boolean ok = cPCtl != null && cSP != null && cWC != null && cBlockPos != null
                    && cFacing != null && cV3 != null
                    && (Ver.is189() ? cItSt != null : cEnumHand != null);
            if (!ok) {
                // 缺哪个直接说出来。只丢一个 NPE 的话, 下次还得再来一遍现场
                log("Scaffold: 缺类 pc=" + (cPCtl != null) + " sp=" + (cSP != null)
                        + " wc=" + (cWC != null) + " bp=" + (cBlockPos != null)
                        + " facing=" + (cFacing != null) + " vec=" + (cV3 != null)
                        + " itemstack=" + (cItSt != null) + " hand=" + (cEnumHand != null));
            } else if (Ver.is189()) {
                // 1.8.9: 第 3 个参数是手持物品, 末尾没有 EnumHand
                mProcessRightClickBlock = mpriv(cPCtl, "func_178890_a",
                        cSP, cWC, cItSt, cBlockPos, cFacing, cV3);
            } else {
                mProcessRightClickBlock = mpriv(cPCtl, "func_187099_a",
                        cSP, cWC, cBlockPos, cFacing, cV3, cEnumHand);
            }
        } catch (Throwable t) {
            mProcessRightClickBlock = null;
            log("Scaffold: 拿不到 processRightClickBlock, 会退回 rightClickMouse (仅 Visible 档可用): " + t);
        }
        // 真正的攻击入口。
        // PlayerControllerMP.attackEntity(player, target) 里面按顺序做四件事:
        //   1. syncCurrentPlayItem()  把当前槽位同步给服务端
        //   2. connection.sendPacket(new CPacketUseEntity(target))   ← **服务端掉血靠这一个包**
        //   3. player.attackTargetEntityWithCurrentItem(target)      本地那份实体算伤害/音效/击退
        //   4. player.resetCooldown()
        // 以前 KillAura 只调了第 3 步 (func_71059_n)。那是在**本地**那份实体上算伤害,
        // 一个包都不发, 服务端血条根本不动 —— 客户端却播了音效和击退, 所以看着"像是打中了",
        // 实际只有挥手动作。这就是 KillAura 没伤害的根因。
        try {
            mAttackEntity = cPC.getMethod("func_78764_a", cPlayer, cEntity);
        } catch (Throwable t) {
            mAttackEntity = null;
            log("KillAura: 拿不到 PlayerControllerMP.attackEntity, 攻击不会发包: " + t);
        }
        // AutoFish: 认浮标归属
        // 施钓者: 1.12.2 是方法 getAngler(), 1.8.9 是字段 angler。别名表里那条是
        // func_ -> field_ 的跨种类映射, 取法必须跟着换, 否则 1.8.9 上拿到 null,
        // AutoFish 的"这是不是我自己的浮标"判断会永远为假。
        Class<?> cHook = copt("net.minecraft.entity.projectile.EntityFishHook");
        if (Ver.anglerIsField()) fAngler = fopt(cHook, Ver.anglerMethod());
        else mGetAngler = mopt(cHook, Ver.anglerMethod());
        // 屏幕空间画框 (ESP 的 2D Box / Corner 档)
        mDrawRect = mopt(copt("net.minecraft.client.gui.Gui"), "func_73734_a",
                int.class, int.class, int.class, int.class, int.class);

        // 屏幕空间投影: AABB 六个边界 + 视场角
        mAabbMinX = fopt(cAABB, "field_72340_a");
        mAabbMinY = fopt(cAABB, "field_72338_b");
        mAabbMinZ = fopt(cAABB, "field_72339_c");
        mAabbMaxX = fopt(cAABB, "field_72336_d");
        mAabbMaxY = fopt(cAABB, "field_72337_e");
        mAabbMaxZ = fopt(cAABB, "field_72334_f");
        fFov = fopt(cGameSettings, "field_74334_X");

        // 渲染细节: 线宽 / 血量 / 掉落物名字
        // renderFilledBox (func_189696_b) 的绑定去掉了: 实心框现在统一走 drawScreenBox 的
        // 屏幕空间填充, 两个版本都能画, 那个句柄在 1.8.9 上本来也取不到。
        Class<?> cGSM2 = copt("net.minecraft.client.renderer.GlStateManager");
        mLineWidth = mopt(cGSM2, "func_187441_d", float.class);
        mGetMaxHealth = mopt(cLiving, "func_110138_aP");
        mGetItemStack = mopt(copt("net.minecraft.entity.item.EntityItem"), "func_92059_d");
        mGetStackName = mopt(cItemStack, "func_82833_r");
        mGetUnformattedText = mopt(copt("net.minecraft.util.text.ITextComponent"), "func_150260_c");
        mGetWorldTime = mopt(cWorld, "func_72820_D");

        // Player identity: current connection info excludes unlisted NPCs; listed NPCs need a server identifier.
        mGetTeam = mopt(cEntity, "func_96124_cp");
        // isOnSameTeam 的参数类型也换过: 1.12.2 收 Entity, 1.8.9 收 EntityLivingBase。
        // 按 1.12.2 的参数表去取, 1.8.9 上必然 NoSuchMethod -> null,
        // 而调用点守着 "mIsOnSameTeam != null", 于是 AntiBot 的同队判定静默失效。
        mIsOnSameTeam = mopt(cEntity, "func_184191_r", Ver.is189() ? cLiving : cEntity);
        mGetConnection = mopt(cMC, "func_147114_u");
        Class<?> cNetHandler = copt("net.minecraft.client.network.NetHandlerPlayClient");
        mGetPlayerInfo = mopt(cNetHandler, "func_175102_a", java.util.UUID.class);
        mGetResponseTime = mopt(copt("net.minecraft.client.network.NetworkPlayerInfo"), "func_178853_c");
        mGetUUID = mopt(cEntity, "func_110124_au");
        mCombatEntityProfile = mopt(cPlayer, "func_146103_bH");
        fDebugFPS = fopt(cMC, "field_71470_ab");
        // 弹道预测: 拉弓时间决定初速
        mGetItemInUseCount = mopt(cPlayer, "func_184605_cv");   // 1.8.9 上在 EntityPlayer

        // NoFire: 火焰遮挡和传送门扭曲都是纯客户端显示状态, 服务端那边燃烧/传送照常,
        // 所以清掉这两个字段只是"看不见", 不会影响判定。
        fEntityFire = fopt(cEntity, "field_190534_ay");
        fTimeInPortal = fopt(copt("net.minecraft.client.entity.EntityPlayerSP"), "field_71086_bY");
        // FreeCam: 相机方向/位置全部取自 renderViewEntity, 所以换掉它就能自由飞
        fRenderViewEntity = fopt(cMC, "field_175622_Z");
        mSetPosAndRot = mopt(cEntity, "func_70080_a", double.class, double.class, double.class, float.class, float.class);
        // FreeCam 的假相机实体要真的 spawn 进世界 —— 见 addCamToWorld 的说明。
        // 取不到句柄时 FreeCam 仍然能跑 (只是回到"没人 tick 它"的老样子), 所以用 mopt。
        mSpawnEntity = mopt(cWorld, "func_72838_d", cEntity);
        mRemoveEntity = mopt(cWorld, "func_72900_e", cEntity);
        // AutoReconnect: 断线界面上的"重连"按钮就是 buttonList 里那个, 直接按它
        fButtonList = fopt(copt("net.minecraft.client.gui.GuiScreen"), "field_146292_n");
        Class<?> cButton = copt("net.minecraft.client.gui.GuiButton");
        fDisplayString = fopt(cButton, "field_146126_j");
        mMousePressed = mopt(cButton, "func_146116_c", cMC, int.class, int.class);
        // Spammer: 发聊天是纯客户端动作
        mSendChatMessage = mopt(copt("net.minecraft.client.entity.EntityPlayerSP"), "func_71165_d", String.class);
        // NameProtect: Everyone 模式要遍历在线玩家列表拿名字
        mGetPlayerInfoMap = mopt(cNetHandler, "func_175106_d");
        mGetGameProfile = mopt(copt("net.minecraft.client.network.NetworkPlayerInfo"), "func_178845_a");
        mGetProfileName = mopt(copt("com.mojang.authlib.GameProfile"), "getName");
        mGetProfileId = mopt(copt("com.mojang.authlib.GameProfile"), "getId");
        // FreeCam 上升/下降: 按住跳跃键时 isJumping 为 true
        fJumping = fopt(cLiving, "field_70703_bu");
        fGameSettings = fopt(cMC, "field_71474_y");
        // ScaledResolution uses this value to size the vanilla hotbar.  The
        // accent widget must use the same unit even when the ClickGUI has not
        // been opened yet (GS is only refreshed by layoutFor(screen)).
        fGuiScaleSetting = Ver.is1201() ? null : fopt(cGameSettings, "field_74335_Z");
        if (!Ver.is1201()) {
            Class<?> bindings = copt("net.minecraft.client.settings.KeyBinding");
            fSneakKey = fopt(cGameSettings, "field_74311_E");
            fForwardKey = fopt(cGameSettings, "field_74351_w");
            fBackKey = fopt(cGameSettings, "field_74368_y");
            fLeftKey = fopt(cGameSettings, "field_74370_x");
            fRightKey = fopt(cGameSettings, "field_74366_z");
            fJumpKey = fopt(cGameSettings, "field_74314_A");
            fSprintKey = fopt(cGameSettings, "field_151444_V");
            fUseKey = fopt(cGameSettings, "field_74313_G");
            mBindingCode = mopt(bindings, "func_151463_i");
            mBindingState = mopt(bindings, "func_74510_a", int.class, boolean.class);
        }
        fGamma = fopt(cGameSettings, "field_74333_Y");
        fViewBobbing = fopt(cGameSettings, "field_74336_f");
        fOpenContainer = fopt(cEntityPlayer, "field_71070_bA");
        fInvPlayer = fopt(cEntityPlayer, "field_71071_by");
        fMainInventory = fopt(cInvPlayer, "field_70462_a");
        fCurrentItem = fopt(cInvPlayer, "field_70461_c");
        fInvSlots = fopt(cContainer, "field_75151_b");
        fPlayerController = fopt(cMC, "field_71442_b");
        // Minecraft.leftClickCounter —— 原版"挥空惩罚", 见 doAutoClick 里的说明。
        // 可选句柄: 取不到就只是不清理, 功能仍然工作 (只是频率会受原版影响)。
        fLeftClickCounter = fopt(cMC, "field_71429_W");        fCurBlockDamage = fopt(cPC, "field_78770_f");
        fBlockHitDelay = fopt(cPC, "field_78781_i");
        fIsHittingBlock = fopt(cPC, "field_78778_j");
        fObjectMouseOver = fopt(cMC, "field_71476_x");
        if (cBlockPos != null) {
            try { ctorBlockPos = cBlockPos.getConstructor(double.class, double.class, double.class); }
            catch (Throwable t) { ctorBlockPos = null; }
        }
        // REACH_DISTANCE 是运行时静态字段, srg 无映射: 按类型扫描 EntityPlayer 的静态 IAttribute 字段
        if (cEntityPlayer != null && cIAttr != null) {
            for (Field fl : cEntityPlayer.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(fl.getModifiers()) && cIAttr.isAssignableFrom(fl.getType())) {
                    fl.setAccessible(true);
                    fReachAttr = fl;
                    break;
                }
            }
        }
        // ===== v6.3 补齐: NoFall Packet / FullBright Night Vision / NoWeather 天空变暗 =====
        // cPotion / mGetActivePotion 由 initExtra() 建好, 这里直接用
        cPotionEffect = copt("net.minecraft.potion.PotionEffect");
        cPacketPlayer = copt("net.minecraft.network.play.client.CPacketPlayer");
        fConnection = fopt(copt("net.minecraft.client.entity.EntityPlayerSP"), "field_71174_a");
        mSendPacket = mopt(copt("net.minecraft.client.network.NetHandlerPlayClient"),
                "func_147297_a", copt("net.minecraft.network.Packet"));
        if (cPacketPlayer != null) {
            try { ctorPacketPlayer = cPacketPlayer.getConstructor(boolean.class); }
            catch (Throwable t) { ctorPacketPlayer = null; }
        }
        mAddPotionEffect = mopt(cLiving, "func_70690_d", cPotionEffect);
        mGetPotionFromRL = mopt(cPotion, "func_180142_b", String.class);
        mPotionEffects = Ver.is189()
                ? mopt(copt("net.minecraft.item.ItemPotion"), "func_77832_l", cItemStack)
                : mopt(copt("net.minecraft.potion.PotionUtils"), "func_185189_a", cItemStack);
        mPotionEffectValue = Ver.is189() ? mopt(cPotionEffect, "func_76456_a")
                : mopt(cPotionEffect, "func_188419_a");
        mPotionNumericId = Ver.is189() ? mopt(cPotion, "func_76396_c")
                : mopt(cPotion, "func_188409_a", cPotion);
        if (cPotionEffect != null && cPotion != null) {
            try { ctorPotionEffect = cPotionEffect.getConstructor(Ver.is189()?int.class:cPotion, int.class, int.class); }
            catch (Throwable t) { ctorPotionEffect = null; }
        }
        if (mGetPotionFromRL != null) {
            try { potionNightVision = mGetPotionFromRL.invoke(null, "night_vision"); }
            catch (Throwable t) { potionNightVision = null; }
        }
        fRainingStrength = fopt(cWorld, "field_73004_o");
        fPrevRainingStrength = fopt(cWorld, "field_73003_n");
        fMouseSensitivity = fopt(cGameSettings, "field_74341_c");   // 反推瞄准量子用
        mGetEntityId = mopt(cEntity, "func_145782_y");              // 换目标检测用

        // AutoBlock 的 Packet 档: 直接发"使用物品"包, 不碰客户端自己的使用状态 ——
        // 所以没有减速、没有挥手动画, 但服务端一样认为你在格挡。
        cTryUseItem = copt("net.minecraft.network.play.client.CPacketPlayerTryUseItem");
        if (cTryUseItem != null && enumMainHand != null) {
            try { ctorTryUseItem = cTryUseItem.getConstructor(enumMainHand.getClass()); }
            catch (Throwable t) { ctorTryUseItem = null; }
        }

        log("v3 features init: sprint=" + (mSetSprinting != null) + " water=" + (mIsInWater != null)
                + " step=" + (fStepHeight != null) + " timer=" + (fTickLength != null) + " gamma=" + (fGamma != null)
                + " chest=" + (mWindowClick != null) + " tool=" + (mGetStrVsBlock != null) + " reachAttr=" + (fReachAttr != null)
                + " blockpos=" + (ctorBlockPos != null));
    }

    /**
     * 取字段 —— **所有**取字段的路径最终都到这里，包括 fopt。
     *
     * 别名必须加在这一层，不能只加在 fopt 上：initFields 里有一批句子直接写
     * {@code f(cLiving, "field_191988_bg")}，绕过 fopt 就等于绕过别名表，1.8.9 上直接
     * NoSuchFieldException，而且这行在 initFields 里，**会把整个 init 带崩**。
     *
     * 同一件事已经栽了三次了：
     *   1. mCooled 裸 invoke           (别名/降级生效了，调用点没守卫)
     *   2. cRG.getMethod("func_189697_a") (绕过了 mopt，翻译没生效)
     *   3. f(cLiving, "field_191988_bg")  (绕过了 fopt，翻译没生效)
     * 根因都是同一个：**能到同一个地方的路有不止一条**。所以修法不是给每条路都补一遍，
     * 而是把翻译压到最底下那一层 —— 上面怎么走都漏不掉。
     */
    private static Field f(Class<?> c, String name) throws Exception {
        if (c == null) return null;
        try {
            Field fld = c.getDeclaredField(Ver.alias(name));
            fld.setAccessible(true);
            return fld;
        } catch (Throwable t) {
            // 1.20.1 把若干 double/boolean 字段合并到 Vec3、OptionInstance 或方法。
            // 这些字段只服务旧版逻辑；返回 null 让兼容层按能力降级，不能阻断整个 agent 初始化。
            if (Ver.is1201()) return null;
            if (t instanceof Exception) throw (Exception) t;
            throw new Exception(t);
        }
    }

    /**
     * Entity.getEyeHeight()。
     *
     * 返回 double 而不是让调用点自己强转: 这个方法返回的是 **float**, 之前三处写成 (Double),
     * 每次调用都抛 ClassCastException。KillAura 的攻击发包就在它后面, 所以整个 KillAura
     * 每帧都死在异常里 —— 表现就是"不锁敌"。用 (Number) 对 float/double 都成立,
     * 以后就算原版改回 double 也不会再炸。
     */
    private static double eyeH(Object e) throws Exception {
        return ((Number) mEyeH.invoke(e)).doubleValue();
    }

    /** Entity.isSneaking()。句柄取不到时按 false 处理 (= 这个限制条件不生效) */
    private static boolean isSneaking(Object e) throws Exception {
        return mIsSneaking != null && (Boolean) mIsSneaking.invoke(e);
    }

    /** Entity.isSprinting()。句柄取不到时按 false 处理 */
    private static boolean isSprinting(Object e) throws Exception {
        return mIsSprinting != null && (Boolean) mIsSprinting.invoke(e);
    }

    // ===== 每帧逻辑 =====
    private static boolean frameLogged = false;
    private static boolean compat1201Logged = false;
    private static volatile boolean modernHudSeen, modernGuiPainted;
    private static volatile String modernGuiFailure = "等待第一帧";
    private static boolean modernAutoPreview = true;

    /** 1.20.1 适配帧：只负责输入、配置面板与状态提示。旧版字段逻辑不在此路径执行。 */
    private static void onFrame1201() {
        try {
            if (!compat1201Logged) {
                compat1201Logged = true;
                log("1.20.1 native GUI + ESP + manual Reach picking active; other legacy modules held disabled");
            }
            ModernEsp.beginFrame();
            ModernEntityMarks.poll(windowHandle1201());
            // Modern hotkeys are handled by KeyboardHandler's press events.
            // Frame polling misses press/release pairs between frames and hid reflection failures.
        } catch (Throwable t) {
            logStack("1.20.1 frame bridge FAILED", t);
        }
    }

    /** Native key event values: PRESS=1, RELEASE=0, REPEAT=2. Do not alter vanilla input. */
    public static void onKey1201(long window, int key, int action) {
        if (action != 1 || (key != 344 && key != 297 && key != 261)) return;
        try {
            ensure();
            long expected = windowHandle1201();
            if (expected == 0L) throw new IllegalStateException("Minecraft window handle unavailable");
            if (window != expected) return;
            // Own GUI close remains available; foreign screens and text entry
            // retain their keys, including Delete. Modern has no module binds.
            if(ModernGui.hasScreen() && !(ModernGui.isOpen() && (key==344 || key==297)))return;
            modernAutoPreview = false;
            log("1.20.1 GUI key press: " + (key == 344 ? "RShift" : key == 297 ? "F8" : "Delete"));
            if (key == 344 || key == 297) {
                if (ModernGui.isOpen()) closeGui(); else openGui();
            } else {
                for (int i = 0; i < mods.length; i++) mods[i].on = false;
                ModernGui.panic();
                guiOpen = false;
            }
        } catch (Throwable t) { logOnce("modern-key-event", t); }
    }

    public static void onFrame(float partialTicks) {
        FrostPass.beginFrame();
        scaffoldRenderReady = scaffoldRenderCompleted = false;
        try {
            if (!frameLogged) {
                frameLogged = true;
                log("onFrame first call (version " + VERSION + ")");
            }
            ensure();
            if (Ver.is1201()) {
                onFrame1201();
                return;
            }
            pollGuiHotkey(); // GUI input must not depend on player/world or module updates.
            // 瞄准角的所有权名额 —— 每帧清零, 一帧内由各功能按优先级抢 (见 claimVisibleAim)。
            // 放在所有 return 之前, 保证每一帧都是干净的开局; 不清的话上一帧的赢家会一直占着。
            visAimPri = -1;
            visAimWho = null;
            silAimPri = -1;
            silAimWho = null;
            // A latched Scaffold model pose may be re-emitted while walking to
            // the edge, but it must not overwrite a newer face angle solved
            // later in the same render frame.
            scaffoldAimRequestedFrame = false;
            Object mc = mGetMC.invoke(null);
            Object player = fPlayer.get(mc);
            syncScaffoldContext(mc, player);
            // 静默转身还原: 放在所有 return 之前, 否则一旦某条分支提前返回,
            // 视角就会永远卡在目标角度上 —— 那是"视角被锁死"的经典翻车方式。
            restoreSilentRotation(player);
            safe("Entity exclusions", new Act(){public void run() throws Exception {pollEntityExclusions(mc,player);}});
            safe("AutoBlock maintenance", new Act(){public void run() throws Exception {maintainCombatBlock(mc,player,false);}});
            if (player == null) {
                if(mods[F_AUTORECONNECT].on) safe("AutoReconnect", new Act(){public void run(){checkAutoReconnect(mc);}});
                else {reconnectAt=0L;reconnectDone=false;}
                pollModuleHotkeys(true);
                panicPressed = mKeyDown != null && (Boolean)mKeyDown.invoke(null,PANIC_KEY);
                freeCamClock.reset();
                releaseScaffoldKey();
                clearScaffoldPlacementLock();
                clearScaffoldPose();
                return;
            }

            // 后台线程下载完的配置在这里套用 —— 不能在下载线程里改 mods, 会和渲染线程抢
            String pi = pendingImport;
            if (pi != null) {
                pendingImport = null;
                try {
                    setToast(importConfig(pi));
                } catch (Throwable t) {
                    setToast("导入失败: " + t);
                }
            }
            // 后台线程取回来的配置串, 这里塞进剪贴板 —— 剪贴板只能在游戏线程碰
            String pc = pendingCopy;
            if (pc != null) {
                pendingCopy = null;
                try {
                    setClipboard(pc);
                    setToast("已复制 " + pc.length() + " 字符, 粘贴给对方即可");
                } catch (Throwable t) {
                    setToast("写剪贴板失败: " + t);
                }
            }
            applyCfgList();   // 同上: 账号配置列表也由后台线程拿, 这里搬到游戏线程

            // 实时配置: 进游戏后第一次恢复上次退出时的状态, 之后每两秒看有没有改动。
            // 放在这里 (player 已经非空) 是因为面板/参数都在这一帧之后才被用到;
            // 恢复如果失败也不拦着游戏, 见 loadAutoConfig 里的注释。
            if (!autoCfgLoaded) {
                autoCfgLoaded = true;
                loadAutoConfig();
            }
            autoSaveConfigTick();

            // Panic: 一键关掉全部功能。
            // 默认 DELETE。玩家管理员来找你 / 录屏 / 想立刻"变回原版"的时候用 —— 按一下
            // 所有功能同时关掉, 并且走的是正常的开->关流程, 所以 Flight/Step/Timer/Gamma
            // 那些持久状态一样会被还原, 不会留下"关了飞行可人还飘着"这种痕迹。
            boolean panic = (Boolean) mKeyDown.invoke(null, PANIC_KEY);
            boolean hotkeysBlocked = moduleHotkeysBlocked(mc);
            if (!hotkeysBlocked && !hotkeysWereBlocked && panic && !panicPressed) {
                panicPressed = true;
                int n = 0;
                for (int i = 0; i < mods.length; i++) if (mods[i].on) { mods[i].on = false; n++; }
                if (guiOpen) closeGui();          // 面板也关掉, 别留个框在屏幕上
                log("PANIC: 关闭了 " + n + " 个功能");
            }
            panicPressed = panic;

            pollModuleHotkeys(hotkeysBlocked);

            updateModuleLifecycle(System.currentTimeMillis());
            if ((guiOpen || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)) && p(F_SCAFFOLD,26)>0) {
                releaseScaffoldKey();
                clearScaffoldPlacementLock();
                clearScaffoldPose();
            }
            // 关闭还原在下面执行，即使面板打开也不能跳过。


            // ===== 开->关 还原 =====
            // 有些功能改的是持久状态 (飞行标记 / 台阶高度 / 游戏速度 / 伽马), 关掉之后
            // 原版不会替它们还原 —— 表现就是"开了关不了"。这里只在 开->关 那一帧还原一次,
            // 不做每帧强制写, 否则会跟原版打架 (比如创造模式下正常飞行)。
            ensureAnimArrays();
            for (int i = 0; i < mods.length; i++) {
                boolean nowOn = mods[i].on;
                if (wasOn[i] && !nowOn) {
                    final int fi = i;
                    safe("restore " + mods[i].name, new Act() {
                        public void run() throws Exception { restoreOff(fi, mc, player); }
                    });
                }
                wasOn[i] = nowOn;
            }

            if (guiOpen) { freeCamClock.reset(); clearCombatAim(); return; }
            // ===== 功能调度 (每个功能独立容错: 单个失败不影响其他) =====
            final Object world = fWorld.get(mc);
            // 战斗
            if (mods[F_AIMBOT].on) safe("AimBot", new Act() { public void run() throws Exception { doAimBot(mc, player); } });
            if (mods[F_KILLAURA].on) safe("KillAura", new Act() { public void run() throws Exception { doKillAura(mc, player); } });
            if (mods[F_AUTOCLICK].on) safe("AutoClicker", new Act() { public void run() throws Exception { doAutoClick(mc, player); } });
            if (mods[F_CRITICALS].on) safe("Criticals", new Act() { public void run() throws Exception { doCriticals(player); } });
            if (mods[F_VELOCITY].on) safe("Velocity", new Act() { public void run() throws Exception { doVelocity(player); } });
            // Reach runs after vanilla picking; clicks and packets remain owned by Minecraft.
            if (mods[F_TRIGGERBOT].on) safe("TriggerBot", new Act() { public void run() throws Exception { doTriggerBot(mc, player); } });
            if (mods[F_BLOCKHIT].on) safe("BlockHit", new Act() { public void run() throws Exception { doBlockHit(mc, player); } });
            if (mods[F_AUTOARMOR].on) safe("AutoArmor", new Act() { public void run() throws Exception { doAutoArmor(player); } });
            if (mods[F_AUTOTOTEM].on) safe("AutoTotem", new Act() { public void run() throws Exception { doAutoTotem(player); } });
            if (mods[F_AUTOPOT].on) safe("AutoPot", new Act() { public void run() throws Exception { doAutoPot(player); } });
            if (mods[F_AUTOBLOCK].on) safe("AutoBlock", new Act() { public void run() throws Exception { doAutoBlock(player); } });
            if (mods[F_HITBOX].on) safe("HitBox", new Act() { public void run() throws Exception { doHitBox(player); } });
            // 移动
            if (mods[F_NOCLIP].on) safe("NoClip", new Act() { public void run() throws Exception { doNoClip(player); } });
            if (mods[F_PHASE].on) safe("Phase", new Act() { public void run() throws Exception { doPhase(player); } });
            if (mods[F_FLIGHT].on) safe("Flight", new Act() { public void run() throws Exception { doFlight(player); } });
            if (mods[F_SPEED].on) safe("Speed", new Act() { public void run() throws Exception { doSpeed(player); } });
            if (mods[F_SPRINT].on) safe("Sprint", new Act() { public void run() throws Exception { doSprint(player); } });
            if (mods[F_NOFALL].on) safe("NoFall", new Act() { public void run() throws Exception { doNoFall(player); } });
            if (mods[F_AUTOJUMP].on) safe("AutoJump", new Act() { public void run() throws Exception { doAutoJump(mc, player); } });
            if (mods[F_STEP].on) safe("Step", new Act() { public void run() throws Exception { doStep(player); } });
            if (mods[F_JESUS].on) safe("Jesus", new Act() { public void run() throws Exception { doJesus(player); } });
            if (mods[F_SAFEWALK].on) safe("SafeWalk", new Act() { public void run() throws Exception { doSafeWalk(mc, player); } });
            if (mods[F_BUNNYHOP].on) safe("BunnyHop", new Act() { public void run() throws Exception { doBunnyHop(player); } });
            if (mods[F_NOSLOW].on) safe("NoSlow", new Act() { public void run() throws Exception { doNoSlow(player); } });
            if (mods[F_LONGJUMP].on) safe("LongJump", new Act() { public void run() throws Exception { doLongJump(player); } });
            if (mods[F_HIGHJUMP].on) safe("HighJump", new Act() { public void run() throws Exception { doHighJump(player); } });
            if (mods[F_SPIDER].on) safe("Spider", new Act() { public void run() throws Exception { doSpider(mc, player); } });
            if (mods[F_FASTLADDER].on) safe("FastLadder", new Act() { public void run() throws Exception { doFastLadder(player); } });
            if (mods[F_SWIM].on) safe("Dolphin", new Act() { public void run() throws Exception { doSwim(player); } });
            if (mods[F_INVMOVE].on) safe("InvMove", new Act() { public void run() throws Exception { doInventoryMove(mc); } });
            // 玩家
            if (mods[F_FASTPLACE].on) safe("FastPlace", new Act() { public void run() throws Exception { doFastPlace(mc); } });
            if (mods[F_FASTBREAK].on) safe("FastBreak", new Act() { public void run() throws Exception { doFastBreak(mc, player); } });
            if (mods[F_INSTANTMINE].on) safe("Nuker", new Act() { public void run() throws Exception { doInstantMine(mc, player); } });
            if (mods[F_SCAFFOLD].on) safe("Scaffold", new Act() { public void run() throws Exception { doScaffold(mc, player); } });
            if (mods[F_AUTOTOOL].on) safe("AutoTool", new Act() { public void run() throws Exception { doAutoTool(mc, player); } });
            if (mods[F_INVMANAGER].on) safe("InvManager", new Act() { public void run() throws Exception { doInvManager(mc, player); } });
            if (mods[F_CHESTSTEALER].on) safe("ChestStealer", new Act() { public void run() throws Exception { doChestStealer(mc, player); } });
            if (mods[F_AUTOEAT].on) safe("AutoEat", new Act() { public void run() throws Exception { doAutoEat(mc, player); } });
            if (mods[F_AUTOFISH].on) safe("AutoFish", new Act() { public void run() throws Exception { doAutoFish(mc, player); } });
            if (mods[F_ANTIAFK].on) safe("AntiAFK", new Act() { public void run() throws Exception { doAntiAFK(mc, player); } });
            // 世界
            if (mods[F_TIMER].on) safe("Timer", new Act() { public void run() throws Exception { doTimer(mc, player); } });
            if (mods[F_NOWEATHER].on) safe("NoWeather", new Act() { public void run() throws Exception { doNoWeather(world); } });
            if (mods[F_TIMECHANGE].on) safe("TimeChanger", new Act() { public void run() throws Exception { doTimeChange(world); } });
            if (mods[F_ANTIVOID].on) safe("AntiVoid", new Act() { public void run() throws Exception { doAntiVoid(player); } });
            // 杂项
            if (mods[F_AUTORESPAWN].on) safe("AutoRespawn", new Act() { public void run() throws Exception { doAutoRespawn(mc, player); } });
            // 渲染 (每帧维持型)
            if (mods[F_FULLBRIGHT].on) safe("FullBright", new Act() { public void run() throws Exception { doFullbright(mc, player); } });
            // 主题色。没有开关行为 —— 它只是把 Hue/Rainbow 两个参数读进渲染状态,
            // 所以无条件调用 (面板每帧渲染前也读一次, 两处都是同一个函数, 不会打架)。
            safe("Theme", new Act() { public void run() throws Exception { setThemeFromParams(); } });
            if (mods[F_VIEWCLIP].on) safe("ViewClip", new Act() { public void run() throws Exception { doViewClip(mc); } });
            else safe("ViewClip restore", new Act() { public void run() throws Exception { restoreViewClip(mc); } });
            if (mods[F_NOHURTCAM].on) safe("NoHurtCam", new Act() { public void run() throws Exception { doNoHurtCam(mc, player); } });
            if (mods[F_NORENDER].on) safe("NoFire", new Act() { public void run() throws Exception { applyNoFire(player); } });
            if (mods[F_SPAMMER].on) safe("Spammer", new Act() { public void run() throws Exception { tickSpammer(player); } });
            // 相机 / 界面类: 跟帧率走, 不能放在 tick 里 —— FreeCam 要跟着鼠标实时动
            safe("FreeCam", new Act() { public void run() throws Exception { updateFreeCam(mc, player); } });
            if (mods[F_AUTORECONNECT].on) safe("AutoReconnect", new Act() { public void run() throws Exception { checkAutoReconnect(mc); } });
            else reconnectAt = 0L;
        } catch (Throwable t) {
            logStack("onFrame FAILED", t);
        }
    }

    /** 功能执行接口 (供独立容错调用) */
    private interface Act { void run() throws Exception; }

    private static void safe(String name, Act a) {
        try { a.run(); }
        catch (Throwable t) {
            if (failedOnce.add(name)) log("feature FAILED " + name + ": " + t);
        }
    }

    /** 通用参数通过名称读取；各模块原有索引保持稳定。 */
    private static double common(Mod mod, String name) {
        for (P param : mod.ps) if (param.n.equals(name)) return param.v;
        throw new IllegalArgumentException(mod.name + "." + name);
    }
    private static long[] enabledSince = new long[mods.length];
    private static boolean[] lifecycleOn = new boolean[mods.length];
    private static boolean lifecycleNoticesReady;
    private static final NoticeStack notices = new NoticeStack();

    private static void updateModuleLifecycle(long now) {
        for (int i = 0; i < mods.length; i++) {
            Mod mod = mods[i];
            if (mod.on && !lifecycleOn[i]) enabledSince[i] = now;
            double seconds = common(mod, "Auto Off (s)");
            if (mod.on && seconds > 0 && now - enabledSince[i] >= seconds * 1000.0) mod.on = false;
            if (mod.on != lifecycleOn[i]) {
                if (lifecycleNoticesReady && (p(F_THEME,23)>0 || common(mod,"Notify Toggle")>0))
                    notices.publish(mod.name,mod.name,mod.on);
                lifecycleOn[i] = mod.on;
            }
        }
        lifecycleNoticesReady=true;
    }
    private static java.util.Set<String> failedOnce = new java.util.HashSet<String>();

    // 热键防抖 (长度跟随功能数)
    private static boolean[] keyPrev = new boolean[mods.length];
    private static boolean[] keyHoldActive = new boolean[mods.length];
    private static boolean hotkeysWereBlocked;

    private static boolean ownTextInputActive() {
        return searchFocused || cfgNameFocused || shareInputFocused || paramEditing() || bindTarget >= 0;
    }

    private static boolean moduleHotkeysBlocked(Object mc) {
        if (guiOpen || ownTextInputActive()) return true;
        try { return mc == null || fCurrentScreen == null || fCurrentScreen.get(mc) != null; }
        catch (Throwable t) { logOnce("screen input state",t);return true; }
    }

    /** Consume physical edges even while a screen owns input. Never replay a
     * key held in a text field when the screen closes. Release hold-owned state. */
    private static void pollModuleHotkeys(boolean blocked) throws Exception {
        boolean consumeOnly=blocked || hotkeysWereBlocked;
        hotkeysWereBlocked=blocked;
        for (int i=0;i<mods.length;i++) {
            int key=mods[i].bindKey;
            boolean down=key>0 && (Boolean)mKeyDown.invoke(null,key);
            boolean hold=(int)common(mods[i],"Bind Mode")==1;
            if(keyHoldActive[i] && (consumeOnly || !down || !hold)) {
                mods[i].on=false;keyHoldActive[i]=false;
            }
            if(!consumeOnly && down && !keyPrev[i]) {
                if(hold){mods[i].on=true;keyHoldActive[i]=true;}
                else {mods[i].on=!mods[i].on;log("hotkey toggle "+mods[i].name+" -> "+mods[i].on);}
            }
            keyPrev[i]=down;
        }
    }
    /** Panic 键: LWJGL 的 DELETE。按下沿触发, 一次关掉所有功能 */
    static final int PANIC_KEY = 211;
    private static boolean panicPressed = false;

    /**
     * 呼出面板的键。
     *
     * **54 = KEY_RSHIFT —— 用户指定, 已回滚回来。** 中途换过 43 (KEY_BACKSLASH),
     * 换的理由见下, 那条理由本身仍然成立; 用户选择回 RShift, 这是他的决定。
     *
     * 为什么曾经想换掉 RShift: 国内不少服务器自带客户端 mod, 那些 mod 也占着 RShift ——
     * 按下去两边界面一起弹, 服务器那个还压在上面, 表现就是"面板打不开/一按就乱"。
     * 争的不是我们的处理被抢走 (mKeyDown 读的是原始按键状态, 谁都能读到),
     * 而是它们弹的界面盖住了我们的。**这条在有的服务器上会再出现, 不是修好了。**
     *
     * 补充一条实测记录, 免得下次误判: 换 43 之后那次"按 \ 没反应"**不是键的问题** ——
     * 是 D:\temp\lite-noretransform 那个诊断开关还在, agent 根本没 hook 上。
     * 也就是说 43 到底能不能用, 始终没有被真正验证过。
     *
     * **别顺手改 5408 行那处 54** —— 那里是"潜行键是否按住"的检测 (scaffoldSneak),
     * 和面板键是两码事。两者都读 54 只是个巧合。
     */
    static final int GUI_KEY = 54;

    private static void pollGuiHotkey() {
        try { if(mKeyDown!=null)guiHotkeyState((Boolean)mKeyDown.invoke(null,GUI_KEY),false); }
        catch(Throwable t){logOnce("gui-hotkey",t);}
    }
    private static void guiHotkeyState(boolean down,boolean repeat) {
        if(!down){shiftPressed=false;return;}
        if(repeat||shiftPressed)return;
        shiftPressed=true;
        if(!guiOpen)try{
            if(moduleHotkeysBlocked(mGetMC==null?null:mGetMC.invoke(null)))return;
        }catch(Throwable t){logOnce("GUI hotkey focus",t);return;}
        if(guiOpen)closeGui();else openGui();
    }
    /** Native GuiScreen events include release edges, including taps between render frames. */
    public static void onGuiKeyEvent(int key,boolean down,boolean repeat) {
        hotkeysWereBlocked=true;
        for(int i=0;i<mods.length;i++)if(mods[i].bindKey==key){
            keyPrev[i]=down;
            if(keyHoldActive[i]){mods[i].on=false;keyHoldActive[i]=false;}
        }
        if(key==PANIC_KEY)panicPressed=down;
        if(key==GUI_KEY)guiHotkeyState(down,repeat);
    }

    /** 同一个 key 只记一次日志 —— 每帧都调的东西出错会瞬间刷爆日志 */
    static void logOnce(String key, Throwable t) {
        if (failedOnce.add(key)) log("[once] " + key + " FAILED: " + t);
    }

    /** 诊断日志 (与 LiteAgent 同文件, 追加; 超 2MB 轮转防刷屏) */
    static void log(String msg) {
        try {
            java.io.File f = new java.io.File(System.getProperty("java.io.tmpdir"), "liteclient-agent.log");
            if (f.length() > 2 * 1024 * 1024) {
                java.io.File old = new java.io.File(System.getProperty("java.io.tmpdir"), "liteclient-agent.old.log");
                old.delete();
                f.renameTo(old);
            }
            // 显式 UTF-8。FileWriter 走平台默认编码 (中文 Windows 上是 GBK),
            // 结果日志里的中文全是乱码 —— 排查时按 UTF-8 读会搜不到关键词, 白白多跑一轮。
            java.io.Writer w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(f, true), java.nio.charset.Charset.forName("UTF-8"));
            w.write(java.time.Instant.now() + " [hook] " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    // ===== 真 GuiScreen 面板 (由 LiteGui 调用) =====

    private static void openGui() {
        try {
            ensure();
            Object mc = mGetMC.invoke(null);
            if (Ver.overlayGuiAvailable()) {
                ModernGui.open(false);
                bindTarget = -1;
                return;
            }
            Class<?> cLiteGui = Class.forName("com.lite.agent.LiteGui");
            Object gui = cLiteGui.newInstance();
            mDisplayGui.invoke(mc, gui);
            guiOpen = true;
            deckMotion.seed("page",0); deckLastNanos=0;
            bindTarget = -1;
            initFont();          // 图集要 MC 的 TextureManager, 只能等进游戏后再建
            ensureAnimArrays();
            log("openGui OK (font=" + (TtfFont.ready() ? "TTF" : "MC点阵") + ")");
        } catch (Throwable t) {
            log("openGui FAILED: " + t);
        }
    }

    private static void closeGui() {
        // GPU caches are bounded and reused across GUI toggles; no shader rebuild on each close.
        try {
            Object mc = mGetMC.invoke(null);
            if (Ver.overlayGuiAvailable()) {
                ModernGui.close();
                guiOpen = false;
                closeShare();
                log("closeGui OK (1.20.1 native Screen)");
                return;
            }
            mDisplayGui.invoke(mc, (Object) null);
            guiOpen = false;
            closeShare();   // 关面板时把分享小块一起收掉, 否则下次开面板它还开着
            log("closeGui OK");
        } catch (Throwable t) {
            log("closeGui FAILED: " + t);
        }
    }

    // 滑块拖动状态
    private static int dragMod = -1, dragParam = -1;
    private static boolean mousePrev = false; // 左键上一帧状态 (按下沿检测)
    private static boolean mouseRightPrev = false; // 右键同上。目前只有一处用途: 右键热键胶囊 = 清空绑定

    // 滚轮累积量 (由 LiteGui.handleMouseInput 在鼠标事件循环里写入)
    private static int pendingScroll = 0;

    /** 鼠标换算系数: 逻辑单位 -> 物理像素 (= MC 的 guiScale)。每帧由 layoutFor 更新。 */
    private static int GS = 1;

    // 面板拖动: 偏移量是相对"居中默认位置"的物理像素增量, 所以窗口尺寸变了也不会跳
    private static int panX = 0, panY = 0;
    private static boolean panDrag = false;
    private static int panGrabMX, panGrabMY, panBaseX, panBaseY;
    private static boolean layoutLogged = false;
    private static boolean auroraLogged = false;

    // HUD: 上一次绘制出来的实际位置/尺寸 (物理像素)。拖动时用它做命中判定。
    private static int hudX, hudY, hudW, hudH;
    private static boolean hudDrag = false;
    /** 缩放手柄的拖动状态 —— 见 onFrame 里的 hudScaleDrag 那段 */
    private static boolean hudScaleDrag = false;
    private static double hudDragScaleW = 1, hudDragScale0 = 1;
    private static int hudGrabDX, hudGrabDY;      // 抓取点相对 HUD 左上角的偏移

    // Every in-game Misc component records its real physical rectangle here.
    // The ClickGUI consumes the same rectangle, so dragging cannot drift from
    // what was actually rendered.
    private static final int[] widgetBX = new int[mods.length], widgetBY = new int[mods.length],
            widgetBW = new int[mods.length], widgetBH = new int[mods.length];
    private static int widgetDragMod = -1, widgetGrabX, widgetGrabY;
    private static int widgetDragMouseX, widgetDragMouseY;
    private static double widgetDragStartX, widgetDragStartY;

    /** 本帧鼠标位置 (物理像素), 给需要单独做悬停判定的辅助方法用 */
    private static int curMx, curMy;

    /**
     * LiteGui.handleMouseInput 调用。
     * 滚轮一定要在鼠标事件循环里读: 面板原先在 drawScreen 里读 Mouse.getDWheel(),
     * 那时 MC 的事件循环可能已经把它清掉了, 表现就是完全滚不动。
     * super 前后各读一次, 取非零的那个, 不依赖 LWJGL 具体版本的行为。
     */
    public static void guiScroll(int before, int after) {
        int w = (before != 0) ? before : after;
        if (w != 0) pendingScroll += w;
    }

    /** 滚动列表。step>0 向下。 */
    private static void scrollBy(int step) {
        if (auroraWorkspace) {
            int c = deckFocusColumn;
            if (c < 0 || !deckVisible(c)) c = deckPage * deckCapacity;
            colScroll[c] = (int) Math.max(0L, Math.min((long) maxColScroll(c), (long) colScroll[c] + step));
            return;
        }
        scroll = (int) Math.max(0L, Math.min((long) maxScroll(), (long) scroll + step));
    }

    // ===== 静默转身 (silent aim) + 瞄准人性化引擎 =====
    //
    // 让服务端收到"瞄准目标"的角度, 而屏幕上视角一动不动, 玩偶模型朝向也跟着转。
    //
    // 时机是全部关键。MC 的 runGameLoop 每帧顺序是:
    //     [runTick × N]  ->  [entityRenderer.updateCameraAndRender]
    // 移动包在 runTick 里构造 (读 player.rotationYaw),
    // 相机在 updateCameraAndRender 里读 player.rotationYaw。
    // 所以:
    //     onRender (updateCameraAndRender 的 RETURN 前) 写入目标角度
    //     下一帧 onFrame (updateCameraAndRender 入口) 还原成视觉角度
    // 中间正好夹着一整轮 tick —— 包带目标角度, 相机看到原角度。不用额外 hook。
    //
    // ---- 为什么不能直接把目标角度写进去 ----
    //
    // 反作弊抓"机器瞄准"最硬的判据不是角度本身, 是角度的**粒度**。
    // 已用 javap 确认: 1.12.2 的 CPacketPlayer 用 writeFloat 发 yaw/pitch
    // (不是 1.8 的 (byte)(yaw*256/360) 字节量化), 服务端拿到的是完整 float。
    // 而真实玩家的转角**不是任意 float** —— MC 的鼠标输入在 EntityPlayer.setAngles
    // 里被量化过:
    //     rotationYaw += 整数鼠标像素 × ((sens*0.6+0.2)^3 × 8) × 0.15
    // 于是真实玩家一段窗口内所有转角增量的最大公约数, 恒等于那个量子。
    // 直接写浮点角度的瞄准器算出来是 ~0 —— 这是最容易被一票抓的特征。
    //
    // 所以写出去的角度必须满足四条:
    //   1) 每一步都是量子的整数倍  —— GCD 对得上
    //   2) 单 tick 转角有上限      —— 人类肌肉极限, 不能瞬移
    //   3) 换目标有人类反应延迟    —— 不是零帧开打
    //   4) 逼近带缓动              —— 到位前减速, 不是匀速直线
    private static boolean silentOn = false;      // 本帧是否有静默转向请求
    private static float silentYaw, silentPitch;
    /** 身体要不要一起转向目标。打人 true, 搭路 false (身体交给原版的移动方向) */
    private static boolean silentBodyToo = true;
    /** 是否把静默角度临时写进模型俯仰。Scaffold 的放置角度只用于出站包, 不改本地模型。 */
    private static boolean silentModelPitch = true;
    private static float visualYaw, visualPitch;  // 请求时记下的真实视角
    private static boolean silentApplied = false;   // 角度已经写进 player, 等下一帧还原
    /**
     * 模型 (玩偶) 这一帧要不要用瞄准角。
     *
     * 和 silentApplied 分开是必须的: silentApplied 在 onFrame 一开头就被
     * restoreSilentRotation 清掉, 而 onCameraReady 在那之后才跑 —— 共用一个标志的
     * 结果就是相机钩子永远提前返回。这个只由 applySilentRotation 写。
     */
    private static boolean silentModelOn = false;
    /** 上一帧是否在瞄。用来判断"刚停止瞄准"那一帧, 好把模型朝向交还给原版 */
    private static boolean silentWasAiming = false;

    // =====================================================================
    // 瞄准角的所有权仲裁 (每帧一次)
    // =====================================================================
    //
    // **为什么需要它。** 有四个功能会改玩家的朝向, 而它们改的是**同两个字段**
    // (rotationYaw / rotationPitch):
    //     KillAura   战斗中锁定目标
    //     AutoPot    扔药水时低头 (喷溅药水按落点生效)
    //     AimBot     自瞄
    //     Scaffold   搭路时摆姿势
    //     AntiAFK    挂机防踢, 转个身
    // 原来没有任何仲裁 —— **谁在功能表里排在后面谁赢**。而 onFrame 是按数组顺序
    // 一个个调过去的, 于是:
    //     · KillAura 和 AimBot 开箱默认都是 Visible, KillAura 排在后面
    //       -> AimBot 每帧刚写完角度就被 KillAura 覆盖, 表现就是"AimBot 开了没反应"。
    //     · Scaffold 在 Player 分类, 排在 Combat 之后 -> 搭路时把 KillAura 的角度顶掉。
    //     · 静默那边同一回事: requestSilent 只是把角度存进 silentYaw/silentPitch,
    //       后调用的直接盖掉先调用的。
    // 这是个顺序耦合 bug: 表现取决于功能表怎么排, 而不是取决于用户想干什么。
    //
    // **两个通道分开仲裁。** 相机 (真实视角) 和静默包是**两个独立的输出**,
    // 不是同一个东西: 一个功能只可能占其中一个。所以各给一个名额 —— 合用的话,
    // KillAura(Silent) 与 AimBot(Visible) 同时开会让后者被整个压掉, 那是删功能不是修 bug。
    //
    // 名额在 onFrame 开头清零, 一帧内有效。抢到的写, 没抢到的不写 ——
    // 抢不到不等于功能坏了: 下一帧那个人没目标时自然就轮到你。
    private static int visAimPri = -1;
    private static String visAimWho = null;
    private static int silAimPri = -1;
    private static String silAimWho = null;

    /**
     * 抢"本帧真实视角(相机)"的写权。返回 false 就别写 —— 有更高优先级的功能已经在管了。
     *
     * 优先级 (数字大者赢):
     *   40 AutoPot  —— 扔药水是**一次性**动作, 那一刻必须低头, 否则药水扔到老远白费。
     *   30 KillAura —— 正在挨打, 朝向必须对着目标。
     *   20 AimBot   —— 用户自己在用的辅助瞄准。
     *   10 Scaffold —— 搭路的姿势, 让位于战斗。
     *    0 AntiAFK  —— 挂机摆头, 谁在干活都该让。
     */
    static boolean claimVisibleAim(int prio, String who) {
        if (prio < visAimPri) return false;
        visAimPri = prio;
        visAimWho = who;
        return true;
    }

    /** 抢"本帧静默转身"的请求权 (只影响发给服务端的角度, 不动相机)。优先级同上。 */
    static boolean claimSilentAim(int prio, String who) {
        if (prio < silAimPri) return false;
        silAimPri = prio;
        silAimWho = who;
        return true;
    }

    /** 转角量子: 由鼠标灵敏度算出, 灵敏度改了要重算 */
    private static double aimQuantum = 0.15;
    private static boolean aimQuantumReady = false;
    /** 上一次真正写进 player 的角度 (服务端看到的就是它) */
    private static float sentYaw = 0f, sentPitch = 0f;
    private static boolean sentReady = false;
    /** 上次推进用的 tick 号 —— 帧率高于 20 时 updateCameraAndRender 一 tick 会跑多次 */
    private static int aimLastTick = Integer.MIN_VALUE;
    /** Tick counters and saved poses belong to a specific loaded player/world. */
    private static Object scaffoldContextPlayer, scaffoldContextWorld, scaffoldContextClientWorld;
    private static int scaffoldContextTick = Integer.MIN_VALUE;
    private static boolean scaffoldContextKnown;
    /** One source of truth for camera, vanilla movement, outbound and model poses. */
    private static final PlayerStatePipeline PLAYER_STATE = new PlayerStatePipeline();
    private static long playerStateViolationAt;
    /** 人类单 tick 转角上限 (度)。快甩能到 40+, 持续跟踪只有 20 上下 */
    private static final float AIM_MAX_TURN = 24.0f;
    private static float silentYawStep = AIM_MAX_TURN, silentPitchStep = AIM_MAX_TURN;
    private static long kaTargetSince, kaRenderedAt, kaTurnAt, kaLastVisibleAt, kaLastReadyAt;
    private static Object kaRenderedTarget;
    private static float kaRenderedYaw, kaRenderedPitch;
    /**
     * Exact KillAura pose written into the most recent outbound movement packet.
     * Keep this separate from sentYaw/sentPitch: those two are the integrator.
     * This pose is prepared for the current packet tick; a completed render
     * must independently show its aim before the attack path may consume it.
     */
    private static Object kaPacketTarget;
    private static float kaPacketYaw, kaPacketPitch;
    private static long kaPacketAt;
    private static Object kaPacketPlayer, kaPacketWorld, kaRenderPlayer, kaRenderWorld;
    private static int kaPacketTick = Integer.MIN_VALUE, kaRenderTick = Integer.MIN_VALUE;
    private static int kaConfirmationTick = Integer.MIN_VALUE, kaAttackTick = Integer.MIN_VALUE;
    private static boolean kaPacketReady, kaRenderReady, kaRenderCompleted, kaRenderRayValid, kaRenderCommit;
    /** Completed-render authorization consumed only in vanilla's client-tick attack phase. */
    private static boolean kaAttackQueued;
    private static Object kaQueuedTarget, kaQueuedPlayer, kaQueuedWorld;
    private static int kaQueuedPacketTick = Integer.MIN_VALUE;
    private static float kaQueuedPacketYaw, kaQueuedPacketPitch;
    private static float kaQueuedRenderedYaw, kaQueuedRenderedPitch;
    private static double kaQueuedEyeX, kaQueuedEyeY, kaQueuedEyeZ;
    private static long kaQueuedRenderedAt;
    private static boolean kaQueuedRayValid;
    private static boolean kaRenderModelHit, kaRenderPacketHit, kaQueuedModelHit, kaQueuedPacketHit;
    private static long kaRenderSerial;
    private static double kaRenderEyeX, kaRenderEyeY, kaRenderEyeZ;
    private static double kaPacketEyeX, kaPacketEyeY, kaPacketEyeZ;
    private static float kaRenderPacketYaw, kaRenderPacketPitch;
    /** KillAura model pose is advanced once per game tick, not once per render frame. */
    private static int kaModelTick = Integer.MIN_VALUE;
    private static Object kaModelPoseTarget;
    private static float kaModelBodyYaw, kaPrevModelBodyYaw;
    private static float kaModelHeadYaw, kaPrevModelHeadYaw, kaModelPitch, kaPrevModelPitch;
    private static boolean kaModelPoseReady;
    private static int kaConfirmedTicks, kaAttackCount;
    private static long kaGateLogAt, kaDispatchLogAt;
    private static String kaStatus = "Idle", scaffoldStatus = "Idle";
    private static float scaffoldHeading, scaffoldDesiredYaw, scaffoldDesiredPitch;
    private static boolean scaffoldHeadingReady, lastPlaceSucceeded;
    private static int scaffoldSlotTick = Integer.MIN_VALUE;

    /** 解算回正视角时的上限 —— 比瞄准快, 但仍然是"甩"而不是"瞬移" */
    private static final float AIM_MAX_RETURN = 45.0f;
    /** 目标 id 变了就重新走一遍反应延迟 */
    private static int aimTargetId = Integer.MIN_VALUE;
    private static long aimReactUntil = 0L;
    private static final java.util.Random aimRnd = new java.util.Random();

    /**
     * 从游戏设置的灵敏度反推转角量子。每帧检查一次很便宜, 玩家在设置里拖灵敏度也能跟上。
     * 公式来自 EntityPlayer.setAngles: rotationYaw += mouseDX * ((sens*0.6+0.2)^3 * 8) * 0.15
     */
    private static void refreshAimQuantum(Object mc) {
        if (mc == null || fGameSettings == null || fMouseSensitivity == null) return;
        try {
            Object gs = fGameSettings.get(mc);
            if (gs == null) return;
            float sens = fMouseSensitivity.getFloat(gs);
            float f = sens * 0.6f + 0.2f;
            float f1 = f * f * f * 8.0f;
            double q = f1 * 0.15;
            if (q > 0.0005) { aimQuantum = q; aimQuantumReady = true; }
        } catch (Throwable t) { logOnce("aim quantum", t); }
    }

    /** 把角度差折算到 [-180,180], 免得跨 ±180 时走远路 */
    private static float shortestDelta(float from, float to) {
        float d = (to - from) % 360.0f;
        if (d >= 180.0f) d -= 360.0f;
        else if (d < -180.0f) d += 360.0f;
        return d;
    }

    /**
     * 人性化推进一步: 返回从 from 朝 to 走一步之后的角度。
     *
     * 顺序是关键 —— 先按肌肉上限限速, 再把这一步**对齐到量子网格**。
     * 对齐必须发生在最后, 否则限速会把已经对齐的值又推歪。
     * 每一步都是 k×quantum, 所以服务端算相邻两帧角度的差, 永远是量子的整数倍。
     */
    private static float humanAimStep(float from, float to, boolean wrap, float maxTurn) {
        float d = wrap ? shortestDelta(from, to) : (to - from);
        // 已经够近就不动。**不能直接跳到 to** —— to 是任意浮点值, 跳过去的那一步
        // 就不是量子的整数倍了, 一个原始值混进增量序列会把整段 GCD 分析带崩。
        if (Math.abs(d) < aimQuantum * 0.5f) return from;
        float step = Math.max(-maxTurn, Math.min(maxTurn, d));
        // 缓动: 差得远时全速, 快到位时收力 —— 人手是加速再减速, 不是匀速
        if (Math.abs(step) > aimQuantum * 4.0f) step *= 0.82f + 0.18f * aimRnd.nextFloat();
        double k = Math.round((double) step / aimQuantum);
        if (k == 0.0) k = (step > 0.0f) ? 1.0 : -1.0;                 // 至少要动一个量子
        // 别冲过头: k 最多走到目标所在的那个量子格
        double maxK = Math.floor(Math.abs((double) d) / aimQuantum);
        if (maxK >= 1.0) {
            if (k > maxK) k = maxK;
            else if (k < -maxK) k = -maxK;
        }
        return sameBranch(from, (float) ((double) from + k * aimQuantum));
    }

    /**
     * 把角度折到 from 所在的那一支上。
     *
     * -180 和 180 是同一个角, 但直接把 -180 写成 180, 服务端算出来的增量是 **360** ——
     * 一次凭空出现的整圈旋转, 比任何瞄准特征都显眼。跨 ±180 时务必走这条。
     * 这个方法也覆盖 vanilla 累计角度超出 [-180,180] 的情况。
     */
    private static float sameBranch(float from, float to) {
        // rotationYaw/sentYaw are accumulated by vanilla and can legitimately
        // drift past several full turns (for example -564°).  A single +/-360
        // correction only works while both operands happen to be in
        // [-180,180]; after enough mouse movement it can still leave a long
        // interpolation path.  shortestDelta is modulo based, so it gives the
        // equivalent target on the branch nearest the current value for both
        // normalized and accumulated angles.
        return from + shortestDelta(from, to);
    }

    /** 搭路这类自己控制的动作: 不参与反应延迟 */
    static void requestSilent(float yaw, float pitch) {
        requestSilent(yaw, pitch, 0);
    }

    /**
     * 本 tick 请求静默转向。KillAura / Scaffold 在功能调度里调用。
     * 同一帧多次调用以最后一次为准 (先算敌人再算搭路, 后者覆盖前者)。
     *
     * @param targetId 目标实体 id。换了目标就重新走一遍反应延迟, 同一个目标则连续跟踪。
     *                 传 0 表示不是敌人 (搭路), 不触发延迟。
     */
    static void requestSilent(float yaw, float pitch, int targetId) {
        requestSilent(yaw, pitch, targetId, 0);
    }

    /**
     * 本 tick 请求静默转向。KillAura / Scaffold / AimBot 在功能调度里调用。
     * 同一帧多次调用以最后一次为准 (先算敌人再算搭路, 后者覆盖前者)。
     *
     * @param targetId 目标实体 id。换了目标就重新走一遍反应延迟, 同一个目标则连续跟踪。
     *                 传 0 表示不是敌人 (搭路), 不触发延迟。
     * @param delayMs  换目标后的反应延迟。<=0 用默认的 130~220ms (人类"看见->动手"的区间);
     *                 传了就按传的值走, 抖动幅度取它的 1/4。AimBot 用这个参数暴露出来。
     */
    static void requestSilent(float yaw, float pitch, int targetId, int delayMs) {
        requestSilent(yaw, pitch, targetId, delayMs, true);
    }

    /**
     * @param bodyToo 模型头部要不要跟随目标。
     *
     *   打人 (KillAura / AimBot) 传 true —— 头部跟随目标, 身体继续使用原版移动插值。
     *
     *   搭路传 **false** —— 身体和头部都由 Scaffold 的下视模型姿态单独维护。
     */
    static void requestSilent(float yaw, float pitch, int targetId, int delayMs, boolean bodyToo) {
        silentYawStep = silentPitchStep = AIM_MAX_TURN;
        silentOn = true;
        silentYaw = yaw;
        silentPitch = Math.max(-90f, Math.min(90f, pitch));
        silentBodyToo = bodyToo;
        silentModelPitch = true;
        if (targetId > 0 && targetId != aimTargetId) {
            aimTargetId = targetId;
            int base = delayMs > 0 ? delayMs : 130;
            int spread = delayMs > 0 ? Math.max(1, delayMs / 4) : 90;
            aimReactUntil = System.currentTimeMillis() + base + aimRnd.nextInt(spread);
        }
    }

    /**
     * Scaffold 的放置角度只服务于出站交互包。
     * 本地相机和模型继续使用原版角度，避免搭路时模型被拉向方块侧面。
     */
    private static boolean requestSilentNetworkOnly(float yaw, float pitch) {
        return requestSilentNetworkOnly(yaw, pitch, 2);
    }

    /**
     * Submit one Scaffold pose with a deadline measured in real player ticks.
     * The configured turn speed remains the lower bound, but a target acquired
     * near the edge receives enough angular budget to arrive before its side-ray
     * window.  Placement still waits for the pose actually written by
     * onPlayerMoved; this only removes avoidable catch-up ticks.
     */
    private static boolean requestSilentNetworkOnly(float yaw, float pitch, int settleTicks) {
        // A pending block interaction needs one coherent packet pose.  Combat
        // may resume on the following tick, but it must not replace the bridge
        // pose between target lock and processRightClickBlock.
        if (!claimSilentAim(50, "Scaffold")) return false;
        requestSilent(yaw, pitch, 0, 0, false);
        if (mods != null && mods.length > F_SCAFFOLD && mods[F_SCAFFOLD] != null) {
            float fromYaw = sentReady ? sentYaw : visualYaw;
            float fromPitch = sentReady ? sentPitch : visualPitch;
            silentYawStep = scaffoldTurnStep((float) p(F_SCAFFOLD, 21),
                    fromYaw, yaw, settleTicks, true);
            silentPitchStep = scaffoldTurnStep((float) p(F_SCAFFOLD, 22),
                    fromPitch, pitch, settleTicks, false);
        }
        silentModelPitch = false;
        return true;
    }

    /** Per-tick turn budget which reaches the requested pose by the edge. */
    static float scaffoldTurnStep(float configuredPerSecond, float from, float to,
                                  int settleTicks, boolean yawAxis) {
        int ticks = Math.max(1, Math.min(4, settleTicks));
        float configured = Math.max(1.0f, configuredPerSecond / 20.0f);
        float delta = yawAxis ? Math.abs(shortestDelta(from, to)) : Math.abs(to - from);
        // A small margin absorbs mouse-quantum rounding in humanAimStep.
        float deadline = delta / ticks + (float) Math.max(0.05, aimQuantum);
        return Math.min(180.0f, Math.max(configured, deadline));
    }

    /** Drop only state owned by the previous loaded world, before any cached
     * pose or input is applied to its replacement. Ordinary module toggles do
     * not enter this reset and therefore cannot erase another current owner. */
    private static void syncScaffoldContext(Object mc, Object player) throws Exception {
        Object clientWorld = mc == null || fWorld == null ? null : fWorld.get(mc);
        Object world = worldOf(player);
        int tick = player == null || fTicksExisted == null
                ? Integer.MIN_VALUE : fTicksExisted.getInt(player);
        boolean playerChanged = scaffoldContextPlayer != player;
        boolean worldChanged = scaffoldContextWorld != world || scaffoldContextClientWorld != clientWorld;
        boolean tickRewound = player != null && tick != Integer.MIN_VALUE
                && scaffoldContextTick != Integer.MIN_VALUE && tick < scaffoldContextTick;
        boolean changed = scaffoldContextKnown && (playerChanged || worldChanged || tickRewound);
        Object previousPlayer = scaffoldContextPlayer;
        int previousTick = scaffoldContextTick;
        scaffoldContextKnown = true;
        scaffoldContextPlayer = player;
        scaffoldContextWorld = world;
        scaffoldContextClientWorld = clientWorld;
        scaffoldContextTick = tick;
        PLAYER_STATE.syncContext(player, world, tick);
        if (!changed) return;

        // A frame/tick can end after a respawn or disconnect. Discard its old
        // restore tokens before looking at the new entity's camera or body.
        tickSaved = pitchSaved = modelFieldsSaved = false;
        scaffoldVanillaMovementApplied = false;
        scaffoldVanillaPoseTick = Integer.MIN_VALUE;
        scaffoldInputHeadingReady = false;
        restoreScaffoldMovementBindings();
        silentOn = silentModelOn = silentApplied = silentWasAiming = false;
        silentBodyToo = silentModelPitch = false;
        silAimWho = visAimWho = null;
        silAimPri = visAimPri = -1;
        sentReady = visualPitchSet = false;
        sentYaw = sentPitch = 0.0f;
        if (player != null && fYaw != null && fPitch != null) {
            visualYaw = sentYaw = fYaw.getFloat(player);
            visualPitch = sentPitch = fPitch.getFloat(player);
            sentReady = visualPitchSet = true;
        }
        aimLastTick = Integer.MIN_VALUE;
        aimTargetId = Integer.MIN_VALUE;
        aimReactUntil = 0L;
        clearCombatAim();
        combatSuccessfulPlayer = combatSuccessfulWorld = null;
        combatSuccessfulTick = Integer.MIN_VALUE;

        // Only a key actually acquired by Scaffold belongs to this reset.
        // Never use releaseScaffoldKey here: it resolves the current player,
        // which may already be a replacement with its own manual sneak input.
        int ownedKey = scaffoldOwnedKey;
        restoreScaffoldEdgeInput(ownedKey == Integer.MIN_VALUE ? false : physicalKeyDown(ownedKey));
        scaffoldEdgeCarryTicks = 0;
        scaffoldOwnedKey = Integer.MIN_VALUE;
        scaffoldSneakWant = scaffoldTickObserved = false;
        scaffoldSneakTicks = scaffoldReleaseTicks = 0;
        if (ownedKey != Integer.MIN_VALUE) {
            try {
                boolean manualDown = physicalKeyDown(ownedKey);
                if (mBindingState != null) mBindingState.invoke(null, ownedKey, manualDown);
                if (previousPlayer != null && previousPlayer != player && mSetSneaking != null)
                    mSetSneaking.invoke(previousPlayer, manualDown);
            } catch (Throwable t) { logOnce("scaffold context key", t); }
        }
        clearScaffoldPlacementLock();
        clearScaffoldPose();
        scaffoldPacketPreparing = scaffoldTickCommit = false;
        scaffoldPlacementJournal.clear();
        scaffoldCommitHit = null;
        resetScaffoldJump();
        scaffoldHeadingReady = false;
        scaffoldHeading = scaffoldDesiredYaw = scaffoldDesiredPitch = 0.0f;
        scaffoldSlotTick = Integer.MIN_VALUE;
        scaffoldPrevSlot = -1;
        lastScaffold = 0L;
        scaffoldStatus = player == null || world == null ? "World unavailable" : "Idle";
        log("Scaffold context reset: playerChanged=" + playerChanged + " worldChanged=" + worldChanged
                + " tick=" + previousTick + "->" + tick + " rollback=" + tickRewound);
    }

    private static PlayerStatePipeline.Owner playerStateOwner(String who) {
        if ("Scaffold".equals(who)) return PlayerStatePipeline.Owner.SCAFFOLD;
        if ("KillAura".equals(who) || "AimBot".equals(who))
            return PlayerStatePipeline.Owner.COMBAT;
        return who == null ? PlayerStatePipeline.Owner.VANILLA : PlayerStatePipeline.Owner.OTHER;
    }

    /** Runtime violations are diagnostic and rate-limited; the pipeline itself
     * already returns the coherent pose, so logging cannot change gameplay. */
    private static void reportPlayerStateViolation() {
        String issue = PLAYER_STATE.consumeViolation();
        if (issue == null) return;
        long now = System.currentTimeMillis();
        if (now - playerStateViolationAt >= 1000L) {
            playerStateViolationAt = now;
            log("PlayerStatePipeline: " + issue);
        }
    }

    /** Advance one shared silent pose at most once per game tick. */
    private static void advanceSilentAimToTick(int tick) {
        int steps = aimLastTick == Integer.MIN_VALUE || tick < aimLastTick ? 1 : tick - aimLastTick;
        if (steps <= 0) return;
        if (steps > 5) steps = 5;
        for (int i = 0; i < steps; i++) {
            if ("Scaffold".equals(silAimWho)) {
                // A placement deadline must be deterministic. Random easing
                // shortened its final step and delayed a valid side ray by a
                // whole movement tick even with a one-tick angular budget.
                sentYaw = turnToward(sentYaw, silentYaw, silentYawStep, true);
                sentPitch = turnToward(sentPitch, silentPitch, silentPitchStep, false);
            } else {
                sentYaw = humanAimStep(sentYaw, silentYaw, true, silentYawStep);
                sentPitch = humanAimStep(sentPitch, silentPitch, false, silentPitchStep);
            }
        }
        aimLastTick = tick;
    }

    private static void advanceSilentAimForTick(Object player) throws Exception {
        int tick = fTicksExisted != null ? fTicksExisted.getInt(player)
                : (int) (System.currentTimeMillis() / 50L);
        advanceSilentAimToTick(tick);
    }

    /**
     * Refresh a moving KillAura target at the movement-packet tick boundary.
     * The render request may be almost one frame old; packet/model/attack must
     * instead consume the same target sample from this tick.
     */
    private static boolean refreshKillAuraTickAim(Object player) throws Exception {
        // Scaffold and AutoPot do not target an entity and must never inherit a
        // previous KillAura/AimBot reaction deadline.  Their request is already
        // the winning request for this packet tick, so it can advance now.
        if ("Scaffold".equals(silAimWho) || "AutoPot".equals(silAimWho)) return true;
        // AimBot owns its own request/reaction deadline.  Only KillAura may
        // refresh from kaTarget below; otherwise one shared silent channel lets
        // a live KA target replace another module's yaw/pitch.
        if (!"KillAura".equals(silAimWho)) return System.currentTimeMillis() >= aimReactUntil;
        if (!silentModelOn || kaTarget == null || mods == null
                || !mods[F_KILLAURA].on || (int) p(MK, 9) != 1) {
            return System.currentTimeMillis() >= aimReactUntil;
        }
        float[] aim = combatAngles(player, kaTarget);
        silentYaw = aim[0] + kaBiasYaw;
        silentPitch = Math.max(-90f, Math.min(90f, aim[1] + kaBiasPitch));
        return System.currentTimeMillis() >= aimReactUntil;
    }

    /**
     * onRender 末尾: 把人性化推进后的角度写进 player, 好让下一轮 tick 发出的移动包带上它。
     *
     * 注意 sentYaw/sentPitch 是跨帧累积的 —— 服务端看到的是这一串值, 而它每一步都落在
     * 鼠标量子网格上, 所以服务端做 GCD 分析得到的就是真实的灵敏度步长, 不是 0。
     */
    private static void applySilentRotation(Object mc, Object player) {
        if (player == null || fYaw == null || fPitch == null) return;
        try {
            syncScaffoldContext(mc, player);
            // refreshAimQuantum(mc);  —— 真实视角的抓取点已经挪到 onPlayerMoved (见那里的注释)。
            // 原来在这里抓, 而这里读的 player.rotationYaw/Pitch 可能已经被写成瞄准角了 ——
            // 快照里出现过 silentApplied=false 却 vis=-90 (低头到底) 的情况,
            // 那就是棘轮: 基准被污染一次, 之后每次"还原"都是在往污染值上还原。
            refreshAimQuantum(mc);

            // **先把上一帧渲染阶段写进去的俯仰还回去 —— 放在所有分支之前。**
            //
            // onCameraReady 写 fPitch (给模型看目标/看依附面) 并记下现场值, 还的动作原来写在
            // 下面 aiming 分支的末尾。但"没在瞄准"那条分支在中途就 return 了 —— 于是搭路
            // (它现在不 requestSilent, 不算瞄准) 写进去的俯仰**永远还不回来**, 下一帧相机
            // 读到的还是搭路角度 → **视角上下被锁死**。
            // 写了就必须还, 和"有没有功能在瞄准"无关 —— 所以提到最前面。
            if (pitchSaved) {
                pitchSaved = false;
                fPitch.setFloat(player, savedPitch);
                if (fPrevPitch != null) fPrevPitch.setFloat(player, savedPrevPitch);
            }
            if (modelFieldsSaved) {
                modelFieldsSaved = false;
                if (fRenderYawOffset != null) fRenderYawOffset.setFloat(player, savedModelBodyYaw);
                if (fPrevRenderYawOffset != null) fPrevRenderYawOffset.setFloat(player, savedPrevModelBodyYaw);
                if (fRotationYawHead != null) fRotationYawHead.setFloat(player, savedModelHeadYaw);
                if (fPrevRotationYawHead != null) fPrevRotationYawHead.setFloat(player, savedPrevModelHeadYaw);
            }

            // A real request and a request waiting for reaction delay are two
            // different states.  During Switch Delay retain the last stable
            // model pose; returning to the free-camera pose here caused the
            // visible head snap on every target change.
            boolean hasRequest = silentOn;
            boolean reactionReady = hasRequest
                    && ("Scaffold".equals(silAimWho) || "AutoPot".equals(silAimWho)
                    || System.currentTimeMillis() >= aimReactUntil);
            silentOn = false;                                   // 请求只活一帧, 消费掉

            if (!hasRequest) {
                // 没有功能在瞄 —— **一个字段都别写**。
                //
                // 原来这里是无条件写 fYaw / renderYawOffset / rotationYawHead 三个字段, 而且
                // 都写成同一个值。后两个是"身体朝向"和"头朝向", 原版里这两个刻意不同:
                // 身体朝移动方向慢慢转, 头朝你看的方向, 走路时两者有个明显滞后差。
                // RenderLivingBase 传给模型的净头部角度就是 (rotationYawHead - renderYawOffset),
                // 两者写成一样 -> 头相对身体恒为 0 度 -> 头和身体完全同步, 一眼就是挂。
                //
                // 而且这段在**没开任何功能**时也在跑, 所以"什么都不开、正常走路"也中招。
                // 现在: 不瞄就彻底不碰, 全交给原版。
                // visYaw/visPitch 这个局部别名去掉了 —— 真实视角现在统一存在
                // visualYaw/visualPitch 里, 由 onPlayerMoved 在覆盖瞄准角之前抓取。
                sentYaw = visualYaw;
                sentPitch = visualPitch;
                sentReady = true;
                if (silentWasAiming) {
                    // 刚停止瞄准 -> **什么都不用还**。
                    //
                    // 原来这里把 renderYawOffset / rotationYawHead 写成 visualYaw (你的视角)。
                    // 那是"我改过它所以要还"的旧逻辑 —— 现在身体和头都不由我写朝向, 原版一直在
                    // 按移动方向维护它们 (后退时身体朝后, 就是这么来的)。我再去写一次反而是干扰:
                    // 打人一结束身体会硬生生扭到你的视角方向, 而不是继续按你走的方向。
                    // 只清标记就够了。
                    silentWasAiming = false;
                }
                silentModelOn = false;
                return;
            }

            if (!reactionReady) {
                // Keep the previous coordinated pose during target reaction.
                // The attack path independently requires Switch Delay and the
                // new target's model confirmation, so this cannot attack early.
                // Only KillAura owns the coordinated KA pose.  AimBot may also
                // be waiting on a reaction deadline, but it must not keep a
                // stale KA pose alive while it owns the shared silent channel.
                silentModelOn = "KillAura".equals(silAimWho)
                        && silentWasAiming && kaModelPoseReady;
                silentApplied = silentModelOn;
                return;
            }

            advanceSilentAimForTick(player);

            // rotationYaw/Pitch 现在**不在这里写**了。写在这里 = 整整一帧都是瞄准角,
            // 而 runTick() 的走路正好落在那个窗口里 -> 走路方向跟着瞄准走。
            // 现在改由 onPlayerMoved (super.onUpdate 之后) 写、onPlayerTickEnd (方法返回前) 还原,
            // 只有出站的移动包会带瞄准角。相机 / 准星 / 走路全用视觉角。
            //
            // **模型朝向: 由 silentBodyToo 门控。** 头部先跟随已确认的 sentYaw，身体
            // 只在头身夹角过大时渐进追赶；rotationYaw/移动输入仍保持原版，因此走路
            // 和摆臂动画不被攻击方向接管。
            //
            // 上一版把这段整个删了, 只留下了 silentBodyToo 这个参数 —— 于是它变成"只赋值
            // 没人读"的死参数, 玩偶永远是原版朝向。用户报的就是这个: "能正常打人,
            // 只是玩偶变成原版了"。发包那条路没问题 (能打人), 坏的只有这一段。
            //
            // 删它的理由是"目标绕到身后时 sentYaw 一帧跨 180 度, 身体跟着甩"。
            // 那个理由本身对, 但**解法不该是删掉** —— 删掉就等于把 KillAura 的 Silent 档
            // 降级成"没有任何可见反馈", 用户根本分不清它有没有在工作。
            // 正确解法是**限速**: humanAimStep 已经在按 AIM_MAX_TURN 逐步逼近了,
            // 所以 sentYaw 本身就是平滑的, 不会一帧跨 180 度。之前会甩是因为那时候把
            // 目标角直接写进去了, 没走 humanAimStep 这条限速路径。
            //
            // Visible 档传 bodyToo=true 走这里 + 真的转视角; Silent 档也传 true,
            // 区别只在"本地相机动不动" (那由 onCameraReady / onPlayerMoved 控制)。
            // 传 false 的调用点 (比如某些只想发包的功能) 仍然可以完全不动模型。
            silentModelOn = true;     // 交给 onCameraReady, 它才是渲染前最后一道
            silentApplied = true;
            silentWasAiming = true;

            // **把俯仰角还回写之前的样子 —— 不是还回一个缓存值。**
            //
            // 1.8.9 上 EntityLivingBase 只有一个 rotationPitch, 相机和模型头部共用它
            // (rotationPitchHead 是 1.9 才拆出来的)。onCameraReady 为了"模型头朝目标"把它写成
            // 瞄准俯仰 —— 那一步必须做, 而且位置必须在那儿 (renderEntities 之前、相机之后)。
            //
            // 但**必须先存后还**: 上一版直接写 fPitch = visualPitch, 而那个缓存值每帧都被无条件
            // 写回, 把玩家的鼠标增量一起擦掉了 —— 视角锁死, 怎么推鼠标都不动。
            // 现在存的是"写之前那一刻的现场值", 还回去等于什么都没发生。
            // **同帧内把 pitch 还回去 —— 这一套是必需的, 不是多余的。**
            //
            // 我一度以为它和 onPlayerMoved/onPlayerTickEnd 那套重复, 删掉了 —— 结果是视角被
            // 上下拖。原因在频率不匹配:
            //     游戏 tick  20 次/秒  → onPlayerMoved 写瞄准 / onPlayerTickEnd 还原
            //     渲染帧     60 次/秒  → onCameraReady 每帧写瞄准俯仰 (给模型)
            // 两次 tick 之间的 2~3 帧里, fPitch 就停在瞄准值上, 相机读到它 —— 就是"上下拖"。
            // 所以渲染这一套必须自己把值还回去, 不能指望下一次 tick。
            // 俯仰的还原已经提到方法最前面了 (见那段注释): 写了就必须还, 不能挂在 aiming
            // 分支的末尾 —— 不瞄准的那条分支中途 return, 搭路的俯仰就永远还不回来。
        } catch (Throwable t) {
            logOnce("silent apply", t);
        }
    }

    /**
     * `EntityPlayerSP.onUpdate` 里 **`super.onUpdate()` 之后** 触发 —— 走位已经算完, 接下来
     * 全是构造和发送移动包。
     *
     * 静默转身的瞄准角只在这一段生效: 包带上瞄准角, 而走路 (已经在 super 里跑完)、相机、
     * 准星用的都还是视觉角。
     *
     * 之前的做法是在 onRender 写、下一帧 onFrame 还原 —— rotationYaw 有整整一帧是瞄准角,
     * 而 runTick() 正好落在那个窗口里, 于是走路方向跟着瞄准走。
     */
    /**
     * Runs before EntityPlayerSP.onUpdate.  Natural Aim is installed for the
     * complete vanilla movement update, then onPlayerTickEnd restores the free
     * camera.  Thus motion, sprint transitions, torso recurrence and the
     * outbound look packet all consume one coherent vanilla pose.
     */
    public static void onPlayerTickStart() {
        vanillaAimMovementApplied = false;
        scaffoldVanillaMovementApplied = false;
        restoreScaffoldMovementBindings();
        if (fYaw == null || fPitch == null || fPlayer == null || mGetMC == null) return;
        try {
            Object mc = mGetMC.invoke(null);
            Object player = mc == null ? null : fPlayer.get(mc);
            syncInventoryMoveInputs(mc);
            if (player == null) return;
            syncScaffoldContext(mc, player);
            // Edge is recomputed from this tick's actual movement arguments.
            // Do not feed yesterday's synthetic key into today's input sample:
            // that would cause early slowdown and permanent crouching at edges.
            prepareScaffoldEdgeTick();
            int currentTick = fTicksExisted == null
                    ? (int) (System.currentTimeMillis() / 50L)
                    : fTicksExisted.getInt(player);
            float tickCameraYaw = fYaw.getFloat(player);
            float tickCameraPitch = fPitch.getFloat(player);
            PLAYER_STATE.beginTick(player, worldOf(player), currentTick,
                    tickCameraYaw, tickCameraPitch);

            // No Sprint is a session-wide Scaffold constraint.  Apply it before
            // super.onUpdate so vanilla acceleration, body recurrence and the
            // outbound action state all consume the same non-sprinting state.
            // The post-super assertion remains below because Ctrl/double-tap can
            // try to restore sprint during the vanilla update.
            if (scaffoldNoSprintActive() && mSetSprinting != null) {
                mSetSprinting.invoke(player, false);
            }

            // Free-camera Scaffold used to install its look only after
            // super.onUpdate().  Vanilla movement had therefore already used
            // the camera yaw while the packet used the bridge yaw.  Reuse this
            // existing pre-super hook so movement and the following packet
            // consume one identical, already rate-limited Scaffold pose.  The
            // camera fields are restored at tick end, exactly like Natural Aim.
            boolean scaffoldPreMove = mods != null && F_SCAFFOLD >= 0 && mods.length > F_SCAFFOLD
                    && mods[F_SCAFFOLD].on && (int) p(F_SCAFFOLD, 1) != 0
                    && !guiOpen && (fCurrentScreen == null || fCurrentScreen.get(mc) == null)
                    && sentReady
                    && (scaffoldSessionPoseChosen || scaffoldAimRequestedFrame || scaffoldPoseActive)
                    && (scaffoldPlacementLocked || scaffoldPoseActive || scaffoldAimRequestedFrame);
            if (scaffoldPreMove) {
                if (!"Scaffold".equals(silAimWho))
                    requestSilentNetworkOnly(scaffoldSessionPoseChosen ? scaffoldSessionYaw : silentYaw,
                            scaffoldSessionPoseChosen ? scaffoldSessionPitch : silentPitch, 1);
                prepareScaffoldMovementAim(player);
                if ("Scaffold".equals(silAimWho)) {
                    float cameraYaw = fYaw.getFloat(player);
                    float cameraPitch = fPitch.getFloat(player);
                    visualYaw = cameraYaw;
                    visualPitch = cameraPitch;
                    visualPitchSet = true;
                    advanceSilentAimToTick(currentTick + 1);
                    scaffoldMovementYaw = sentYaw;
                    scaffoldMovementPitch = Math.max(-90f, Math.min(90f, sentPitch));
                    PlayerStatePipeline.Pose movementPose = PLAYER_STATE.planMovement(
                            PlayerStatePipeline.Owner.SCAFFOLD,
                            scaffoldMovementYaw, scaffoldMovementPitch);
                    scaffoldMovementYaw = movementPose.yaw;
                    scaffoldMovementPitch = movementPose.pitch;
                    savedTickYaw = cameraYaw;
                    savedTickPitch = cameraPitch;
                    savedTickPrevYaw = fPrevYaw == null ? cameraYaw : fPrevYaw.getFloat(player);
                    savedTickPrevPitch = fPrevPitch == null ? cameraPitch : fPrevPitch.getFloat(player);
                    tickSaved = true;
                    vanillaAimMovementApplied = true;
                    scaffoldVanillaMovementApplied = true;
                    // Select the nearest native WASD direction with the same
                    // single/dual-key speed tier as the physical input. During
                    // a turn this may differ by up to 45 degrees; no analogue
                    // vector or position correction forces a camera-relative path.
                    // 1.8.9/1.12.2 select the nearest vanilla WASD direction in
                    // onScaffoldMovementInputUpdated(), immediately after
                    // MovementInput.updatePlayerMoveState().  The key remap is
                    // retained only for 1.20.1, whose input class is different.
                    if (Ver.is1201())
                        remapScaffoldMovementBindings(mc, cameraYaw, scaffoldMovementYaw);
                    fYaw.setFloat(player, scaffoldMovementYaw);
                    fPitch.setFloat(player, scaffoldMovementPitch);
                    return;
                }
            }
            if (mods == null || mods.length <= F_KILLAURA || !mods[F_KILLAURA].on
                    || (int) p(MK, 9) != 1 || kaTarget == null || !silentModelOn
                    || !"KillAura".equals(silAimWho)) return;

            float cameraYaw = fYaw.getFloat(player);
            float cameraPitch = fPitch.getFloat(player);
            visualYaw = cameraYaw;
            visualPitch = cameraPitch;
            visualPitchSet = true;

            boolean reactionReady = refreshKillAuraTickAim(player);
            float movementYaw;
            float movementPitch;
            if (reactionReady) {
                advanceSilentAimToTick(currentTick + 1);
                movementYaw = sentYaw;
                movementPitch = sentPitch;
            } else if (kaModelPoseReady) {
                movementYaw = kaModelHeadYaw;
                movementPitch = kaModelPitch;
            } else {
                return;
            }

            savedTickYaw = cameraYaw;
            savedTickPitch = cameraPitch;
            savedTickPrevYaw = fPrevYaw == null ? cameraYaw : fPrevYaw.getFloat(player);
            savedTickPrevPitch = fPrevPitch == null ? cameraPitch : fPrevPitch.getFloat(player);
            tickSaved = true;
            vanillaAimMovementApplied = true;
            PlayerStatePipeline.Pose movementPose = PLAYER_STATE.planMovement(
                    PlayerStatePipeline.Owner.COMBAT, movementYaw, movementPitch);
            movementYaw = movementPose.yaw;
            movementPitch = movementPose.pitch;
            fYaw.setFloat(player, movementYaw);
            fPitch.setFloat(player, Math.max(-90f, Math.min(90f, movementPitch)));
        } catch (Throwable t) {
            restoreScaffoldMovementBindings();
            vanillaAimMovementApplied = false;
            scaffoldVanillaMovementApplied = false;
            logOnce("playerTickStart", t);
        }
    }

    public static void onPlayerMoved() {
        if (!tickHookAProbed) { tickHookAProbed = true; log("钩子 onPlayerMoved 触发了 (super.onUpdate 之后)"); }
        // MovementInput has consumed the temporary pose-relative bindings.
        // Restore the user's physical keys before any module reads input again.
        restoreScaffoldMovementBindings();
        if (fPlayer != null && mGetMC != null) {
            try {
                Object mc0 = mGetMC.invoke(null);
                Object player0 = mc0 == null ? null : fPlayer.get(mc0);
                syncScaffoldContext(mc0, player0);
                if (vanillaAimMovementApplied && tickSaved && player0 != null) {
                    // Vanilla completed its rotation-history snapshot while
                    // using the model pose. Advance the camera's copy too;
                    // an abort before this hook must retain its old history.
                    savedTickPrevYaw = savedTickYaw;
                    savedTickPrevPitch = savedTickPitch;
                }
                if (scaffoldVanillaMovementApplied && player0 != null) {
                    advanceScaffoldCameraHistory();
                    scaffoldVanillaPoseTick = fTicksExisted == null
                            ? (int) (System.currentTimeMillis() / 50L)
                            : fTicksExisted.getInt(player0);
                }
            } catch (Throwable t) { logOnce("scaffold tick context", t); return; }
        }
        // **门的判据必须是 silentModelOn, 不能是 silentApplied。**
        //
        // 这是 Silent 档"打人没伤害"的根因, 而且是一处标识符写错。
        //
        // 每帧的调用顺序 (onFrame 插在 RENDER_M 方法开头, onRender 插在每个 RETURN 前):
        //     onFrame   -> restoreSilentRotation: silentApplied = false
        //     runTick() -> EntityPlayerSP.onUpdate -> 本函数 ← 在这里读 silentApplied
        //     render    -> onRender -> applySilentRotation: silentApplied = true
        // tick 落在**清除之后、置位之前**。所以 silentApplied 在这里**恒为 false**,
        // 本函数每一 tick 都在第一行就 return —— 下面那段"把瞄准角写进 rotationYaw/Pitch"
        // 从来没执行过。而出站移动包正是从这里拿角度的, 于是服务端**永远看不到瞄准角**,
        // 它拿你的真实视角判定, 攻击包里的目标根本不在你的射程锥内 -> 不掉血。
        // 客户端本地照常算伤害/播挥手/重置冷却, 所以看着"像打中了", 实际服务端什么都没收到。
        //
        // silentModelOn 不被 restoreSilentRotation 清 (它只清 silentApplied),
        // 所以能跨过帧边界活到 tick —— 这个坑作者**已经为 onCameraReady 踩过并修好了**
        // (见那里的注释: "判断条件原来是 silentApplied ... 于是这个钩子每一帧都在...")。
        // 同一个错误在 onCameraReady 修了, 在这里漏了。
        //
        // 写法放在 super.onUpdate() 之后是**故意的**: 走位已经在 super 里算完,
        // 这时写 rotationYaw 只影响出站移动包, 不影响走路方向 (见下面那段注释)。
        // 潜行维持与静默转身是两条独立路径。即使 Rotation=Off, Scaffold 也要在
        // 原版移动更新之后重申潜行，否则原版会把自动状态覆盖掉。
        if (scaffoldSneakWant && fPlayer != null && mGetMC != null) {
            try {
                Object mc = mGetMC.invoke(null);
                Object sneakPlayer = mc == null ? null : fPlayer.get(mc);
                maintainScaffoldSneak(sneakPlayer);
            } catch (Throwable ignored) { }
        }
        // Vanilla may re-enable sprint from Ctrl/double-tap inside super.onUpdate,
        // after the render-frame module pass already turned it off.  This hook
        // sits immediately after super and before the outbound state comparison,
        // so reassert No Sprint here as the authoritative tick-boundary state.
        if (scaffoldNoSprintActive() && fPlayer != null && mGetMC != null
                && mSetSprinting != null) {
            try {
                Object mc = mGetMC.invoke(null);
                Object sprintPlayer = mc == null ? null : fPlayer.get(mc);
                if (sprintPlayer != null) mSetSprinting.invoke(sprintPlayer, false);
            } catch (Throwable ignored) { }
        }
        if (fYaw == null || fPitch == null || fPlayer == null || mGetMC == null) return;
        try {
            Object mc = mGetMC.invoke(null);
            if (mc == null) return;
            Object player = fPlayer.get(mc);
            if (player == null) return;
            if (mods[F_INVMOVE].on && invMoveActive && p(F_INVMOVE, 3) > 0) {
                try { doInvMoveNoSlow(player); } catch (Throwable t) { logOnce("InvMove No Slow", t); }
            }
            // **真实视角在这里抓 —— 而且是覆盖瞄准角之前的那一刻。**
            //
            // 原来在 applySilentRotation 抓, 读的是 player.rotationYaw/Pitch。那个字段在
            // onPlayerMoved 之后到 onPlayerTickEnd 之前是**瞄准角** —— 只要有一次在窗口内被抓到,
            // visualYaw/visualPitch 就被污染成瞄准角, 而它正是后面所有"还原"的基准 ——
            // 于是永远回不到真实视角。快照里那种 silentApplied=false 却 vis=-90 就是这个。
            //
            // 这里读的时候瞄准角**还没写进去**, 拿到的必然是上一步还原好的真实视角。
            // **存现场值, 不是存"我以为是真实视角的缓存"。**
            // 写瞄准角之前 fYaw/fPitch 是什么, 就原样存下来, 由 onPlayerTickEnd 还回去。
            // 用缓存值还原会把玩家的鼠标增量一起擦掉 —— 视角锁死就是这么来的, 我在这上面栽了两次。
            float packetVisualYaw = vanillaAimMovementApplied ? savedTickYaw : fYaw.getFloat(player);
            float packetVisualPitch = vanillaAimMovementApplied ? savedTickPitch : fPitch.getFloat(player);
            visualYaw = packetVisualYaw;
            visualPitch = packetVisualPitch;
            visualPitchSet = true;
            if (!sentReady) { sentYaw = visualYaw; sentPitch = visualPitch; sentReady = true; }
            PLAYER_STATE.movementFinished(fYaw.getFloat(player), fPitch.getFloat(player));
            PlayerStatePipeline.Owner movementPacketOwner = scaffoldVanillaMovementApplied
                    ? PlayerStatePipeline.Owner.SCAFFOLD
                    : (vanillaAimMovementApplied ? PlayerStatePipeline.Owner.COMBAT
                    : PlayerStatePipeline.Owner.VANILLA);
            // Off/Visible use the vanilla packet pose. Natural Aim replaces it
            // below, after resolving this tick's coordinated model.
            kaPacketReady = false;
            if (mods != null && mods[F_KILLAURA].on && kaTarget != null && (int) p(MK, 9) != 1)
                publishCombatPacket(player, packetVisualYaw, packetVisualPitch);

            // Vanilla movement has finished: solve against this tick's actual
            // eye position before its movement packet, never a render forecast.
            // Observe landings even while a menu pauses placement. Otherwise
            // resuming airborne can reuse the previous jump's lower base.
            if (mods != null && mods[F_SCAFFOLD].on && fPosY != null && fOnGround != null) {
                observeScaffoldJump(player, fPosY.getDouble(player), fOnGround.getBoolean(player));
            }
            boolean scaffoldEnabled = mods != null && mods[F_SCAFFOLD].on && !guiOpen
                    && (fCurrentScreen == null || fCurrentScreen.get(mc) == null);
            if (scaffoldEnabled) {
                if (!scaffoldPipelineLogged) {
                    scaffoldPipelineLogged = true;
                    log("Scaffold pipeline 20261014-r2: vanilla digital input -> native sprint gate -> native body -> packet -> rendered ray -> placement");
                }
                scaffoldAimRequestedFrame = false;
                scaffoldPacketPreparing = true;
                try {
                    doScaffold(mc, player);
                    if (scaffoldAimRequestedFrame && "Scaffold".equals(silAimWho)) {
                        silentModelOn = true;
                    }
                } catch (Throwable t) {
                    logOnce("scaffold prepare", t);
                } finally {
                    scaffoldPacketPreparing = false;
                }
            }
            if ("Scaffold".equals(silAimWho)
                    && (!scaffoldEnabled || (!scaffoldAimRequestedFrame && !scaffoldPoseActive))) {
                silentOn = silentModelOn = false;
                silAimWho = null;
                silAimPri = -1;
                if (!scaffoldEnabled) {
                    clearScaffoldPose();
                    clearScaffoldPlacementLock();
                    releaseScaffoldKey();
                }
            }

            // updateCameraAndRender finishes after the game tick and consumes
            // silentOn in applySilentRotation.  The following game tick must
            // therefore enter through the latched cross-frame state.  Reading
            // silentOn here makes every packet path miss: it is already false
            // by the time EntityPlayerSP.onUpdate reaches this hook.
            boolean manualScaffoldPacket = mods != null && mods[F_SCAFFOLD].on
                    && (int) p(F_SCAFFOLD, 1) == 0 && scaffoldPacketReady
                    && scaffoldPacketTick == (int) scaffoldClock(player);
            if (!silentModelOn) {
                if (!scaffoldPoseActive && !manualScaffoldPacket) scaffoldPacketReady = false;
                PLAYER_STATE.commitPacket(movementPacketOwner,
                        fYaw.getFloat(player), fPitch.getFloat(player));
                reportPlayerStateViolation();
                return;
            }
            if (silAimWho == null) {
                silentModelOn = false;
                if (!scaffoldPoseActive && !manualScaffoldPacket) scaffoldPacketReady = false;
                PLAYER_STATE.commitPacket(movementPacketOwner,
                        fYaw.getFloat(player), fPitch.getFloat(player));
                reportPlayerStateViolation();
                return;
            }
            boolean reactionReady = refreshKillAuraTickAim(player);
            if (reactionReady) {
                advanceSilentAimForTick(player);
                silentModelOn = true;
                silentApplied = true;
                silentWasAiming = true;
            } else {
                // A target switch keeps the last stable pose until the new
                // target's reaction window ends.  It does not advance or
                // confirm the new target yet.
                // Mirror the render-end owner rule at the packet boundary.
                // A non-KA owner waiting on its own delay must not send the
                // previously latched KillAura orientation.
                silentModelOn = "KillAura".equals(silAimWho)
                        && silentWasAiming && kaModelPoseReady;
            }
            if (!silentModelOn) {
                if (!scaffoldPoseActive) scaffoldPacketReady = false;
                PLAYER_STATE.commitPacket(movementPacketOwner,
                        fYaw.getFloat(player), fPitch.getFloat(player));
                reportPlayerStateViolation();
                return;
            }

            // Scaffold may acquire its first support face only after this
            // tick's vanilla movement has completed.  That newly solved pose
            // belongs to the next pre-super window: installing it here would
            // recreate the impossible camera-yaw position / bridge-yaw packet
            // pair.  Keep the vanilla packet pose for this tick and let the
            // already latched sentYaw/sentPitch drive the next movement tick.
            if ("Scaffold".equals(silAimWho) && !scaffoldVanillaMovementApplied) {
                scaffoldPacketReady = false;
                PLAYER_STATE.commitPacket(PlayerStatePipeline.Owner.VANILLA,
                        packetVisualYaw, packetVisualPitch);
                reportPlayerStateViolation();
                return;
            }

            if (!vanillaAimMovementApplied) {
                savedTickYaw = packetVisualYaw;
                savedTickPitch = packetVisualPitch;
                savedTickPrevYaw = fPrevYaw == null ? packetVisualYaw : fPrevYaw.getFloat(player);
                savedTickPrevPitch = fPrevPitch == null ? packetVisualPitch : fPrevPitch.getFloat(player);
                tickSaved = true;
            }
            // Resolve the vanilla-style head/body split before writing the
            // movement packet.  The old order sent the full requested yaw and
            // only afterwards clamped the rendered head to body +/-75 degrees;
            // during a rear turn the server and the visible model therefore
            // described two different poses for several ticks.
            float outboundYaw = sentYaw;
            float outboundPitch = sentPitch;
            if (reactionReady && "KillAura".equals(silAimWho)
                    && silentBodyToo && kaTarget != null && mods != null
                    && mods.length > F_KILLAURA && mods[F_KILLAURA].on) {
                if (vanillaAimMovementApplied) adoptVanillaCombatPose(player, sentYaw, sentPitch);
                else syncCombatModel(player, sentYaw, sentPitch);
                // Packet and model advance from one tick sample. Attacking is
                // deferred until a completed frame has displayed this pose;
                // feeding the previous frame into packets made moving targets
                // perpetually one sample late.
                publishCombatPacket(player, kaModelHeadYaw, kaModelPitch);
                outboundYaw = kaPacketYaw;
                outboundPitch = kaPacketPitch;
            } else if (!reactionReady && "KillAura".equals(silAimWho) && kaModelPoseReady) {
                // A target switch retains the displayed KA pose. The shared
                // integrator may meanwhile belong to AutoPot/Scaffold, so it
                // cannot supply this waiting packet or confirm the new target.
                // Keep vanilla torso movement alive while the head waits.
                syncCombatModel(player, kaModelHeadYaw, kaModelPitch);
                outboundYaw = kaModelHeadYaw;
                outboundPitch = kaModelPitch;
                kaPacketTarget = null;
            }
            if (!"KillAura".equals(silAimWho)) kaPacketTarget = null;
            // 0 and -360 are the same server direction, but writing them as
            // different raw values makes third-person interpolation take a full
            // turn.  Keep Scaffold in the session yaw's continuous domain.
            if ("Scaffold".equals(silAimWho) && scaffoldSessionPoseChosen) {
                outboundYaw = scaffoldSessionYaw
                        + shortestDelta(scaffoldSessionYaw, outboundYaw);
            }
            // When Scaffold owned the pre-super window, never replace that
            // movement pose with a freshly solved post-movement destination.
            // The new destination is retained in sentYaw/sentPitch for the next
            // vanilla tick; this packet describes the pose that produced this
            // tick's position.
            if ("Scaffold".equals(silAimWho) && scaffoldVanillaMovementApplied) {
                outboundYaw = scaffoldMovementYaw;
                outboundPitch = scaffoldMovementPitch;
            }
            PlayerStatePipeline.Pose coherentPacket = PLAYER_STATE.commitPacket(
                    playerStateOwner(silAimWho), outboundYaw, outboundPitch);
            outboundYaw = coherentPacket.yaw;
            outboundPitch = coherentPacket.pitch;
            if ("KillAura".equals(silAimWho) && kaPacketTarget != null) {
                kaPacketYaw = outboundYaw;
                kaPacketPitch = outboundPitch;
            }
            reportPlayerStateViolation();
            fYaw.setFloat(player, outboundYaw);
            fPitch.setFloat(player, Math.max(-90f, Math.min(90f, outboundPitch)));
            if ("Scaffold".equals(silAimWho) && (scaffoldPlacementLocked || scaffoldPoseActive)) {
                scaffoldPacketYaw = outboundYaw;
                scaffoldPacketPitch = outboundPitch;
                if (scaffoldPlacementLocked) {
                    scaffoldPacketX = scaffoldLockedX;
                    scaffoldPacketY = scaffoldLockedY;
                    scaffoldPacketZ = scaffoldLockedZ;
                    scaffoldPacketFace = scaffoldLockedFace;
                }
                scaffoldPacketTick = fTicksExisted == null
                        ? (int)(System.currentTimeMillis()/50L)
                        : fTicksExisted.getInt(player);
                scaffoldPacketReady = true;
                publishScaffoldModel(outboundYaw, outboundPitch);
            } else if (!"Scaffold".equals(silAimWho)) {
                // Another owner just became the server's newest orientation,
                // so a previous Scaffold acknowledgement is stale.
                scaffoldPacketReady = false;
            }
        } catch (Throwable t) {
            logOnce("playerMoved", t);
        }
    }

    /**
     * Legacy EntityPlayerSP.onLivingUpdate calls this directly after vanilla
     * samples MovementInput. Select the nearest same-speed digital key combination
     * in the Scaffold pose and preserve manual sneak scaling. Vanilla still performs
     * item-use slowdown, jump timing, acceleration, friction and collision.
     */
    public static void onScaffoldMovementInputUpdated() {
        if (Ver.is1201() || fMovementInput == null
                || fInputForward == null || fInputStrafe == null
                || fPlayer == null || mGetMC == null || fYaw == null) return;
        if (mods == null || !mods[F_SCAFFOLD].on) {
            scaffoldInputHeadingReady = false;
            return;
        }
        try {
            Object mc = mGetMC.invoke(null);
            Object player = mc == null ? null : fPlayer.get(mc);
            Object input = player == null ? null : fMovementInput.get(player);
            if (input == null) return;
            PLAYER_STATE.inputSampled();
            float forward = fInputForward.getFloat(input);
            float strafe = fInputStrafe.getFloat(input);
            scaffoldInputHeadingReady = Math.abs(forward) + Math.abs(strafe) > 1.0e-6f;
            if (scaffoldInputHeadingReady) {
                float camera = vanillaAimMovementApplied && tickSaved
                        ? savedTickYaw : fYaw.getFloat(player);
                scaffoldInputHeading = camera - (float) Math.toDegrees(Math.atan2(strafe, forward));
            }
            if (!scaffoldVanillaMovementApplied || !tickSaved) return;
            float[] local = scaffoldPoseRelativeInput(forward, strafe,
                    savedTickYaw, scaffoldMovementYaw);
            fInputForward.setFloat(input, local[0]);
            fInputStrafe.setFloat(input, local[1]);
        } catch (Throwable t) {
            logOnce("scaffold movement input", t);
        }
    }

    /**
     * Only first-person arm smoothing reads the free-camera pose. The native
     * action-state method still owns its recurrence, input and jump state;
     * movement, third-person head/body and packets retain the movement pose.
     */
    public static float scaffoldArmCameraYaw(float nativeYaw) {
        return scaffoldVanillaMovementApplied && tickSaved ? savedTickYaw : nativeYaw;
    }

    public static float scaffoldArmCameraPitch(float nativePitch) {
        return scaffoldVanillaMovementApplied && tickSaved ? savedTickPitch : nativePitch;
    }

    static void advanceScaffoldCameraHistory() {
        if (!scaffoldVanillaMovementApplied || !tickSaved) return;
        // EntityLivingBase.onEntityUpdate snapshots current rotation into prev
        // every completed native tick. Restoring the pre-tick prev value froze
        // that history while Scaffold was active. Advance the camera's copy as
        // well, but only after super.onUpdate completed; aborts keep old history.
        savedTickPrevYaw = savedTickYaw;
        savedTickPrevPitch = savedTickPitch;
    }

    /** `EntityPlayerSP.onUpdate` 返回前触发 —— 包已发完, 把旋转角还给视觉 */
    public static void onPlayerTickEnd() {
        if (!tickHookBProbed) { tickHookBProbed = true; log("钩子 onPlayerTickEnd 触发了 (onUpdate 返回前)"); }
        try {
            if (fYaw == null || fPitch == null || fPlayer == null || mGetMC == null) return;
            Object mc = mGetMC.invoke(null);
            if (mc == null) return;
            Object player = fPlayer.get(mc);
            syncScaffoldContext(mc, player);
            if (player == null) return;
            try { finishScaffoldTick(player); }
            catch (Throwable t) { logOnce("scaffold sneak tick", t); }
            // Scaffold commits after the world render has displayed its pose.
            // A packet sent here does not prove that interpolation has finished.
            // The movement update has returned from its packet path. A later
            // completed render must confirm this exact tick before attacking.
            kaPacketReady = kaPacketTarget == kaTarget && kaTarget != null
                    && kaPacketPlayer == player && kaPacketWorld == worldOf(player)
                    && kaPacketTick == (int) scaffoldClock(player);
            // This endpoint has not been rendered yet. An earlier frame's
            // successful ray cannot authorize the newly moved player/pose.
            // Only onClientTickAttack consumes a completed frame, before the
            // next native movement update. Never attack from this fallback.
            // 快照必须在**还原之前**打。
            // 放在还原之后时: rot 被还原过、vis 又是刚从这里抄的 rot —— 两个值必然相等,
            // silentApplied 也已经被这一行清掉了。结果就是测什么都测不出来, 白跑一轮。
            // 打在这里: rot 是相机这一帧将要用的值, silentApplied 是"这一步会不会真的写"。
            silentDump(null);
            // 先还原 (只在真的写过瞄准角时才需要), 再抓真实视角。
            //
            // 抓取放在**还原之后**, 而且**不受 silentApplied 限制** —— 这一点很重要:
            // 原来 vis 只在 onPlayerMoved 里刷新, 而那个函数有 if (!silentApplied) return。
            // 不瞄的时候它整个不跑, vis 就一直停在很久以前的那个角度; 而"停止瞄准后把模型
            // 朝向交还给原版"用的正是 vis —— 拿一个陈旧角度交还, 模型就会猛地转一下。
            // 现在这个函数每个 tick 都跑, 还原完的这一刻角度定义上就是真实视角。
            // 还原瞄准角。**但不要在这里清 silentApplied。**
            //
            // 清了会出事: onPlayerTickEnd 比 onFrame 更早跑, 清掉之后 onFrame 里的
            // restoreSilentRotation 第一行判断就直接返回 —— 而它才是**唯一**负责把 fPrevPitch
            // 还原成真实视角的地方。fPrevPitch 是相机插值的起点, onCameraReady 每帧把它写成
            // 瞄准俯仰 (那是给模型头部用的), 没人还原的话下一帧相机就从瞄准角插值过来 ——
            // 表现就是视角被上下拖动, 而且改多少别的都没用 (快照里 vis/rot 的 pitch 恒等就是这个)。
            //
            // 标志的清除权还给 restoreSilentRotation —— 原来就是它清的。
            // **还现场值。** 不是写 visualYaw/visualPitch ——
            // 那两个是缓存, 无条件写回会把鼠标增量擦掉, 视角就锁死了 (已经栽过一次)。
            if (tickSaved) {
                PlayerStatePipeline.Pose cameraPose = PLAYER_STATE.cameraPose();
                fYaw.setFloat(player, cameraPose == null ? savedTickYaw : cameraPose.yaw);
                fPitch.setFloat(player, cameraPose == null ? savedTickPitch : cameraPose.pitch);
                if (fPrevYaw != null) fPrevYaw.setFloat(player, savedTickPrevYaw);
                if (fPrevPitch != null) fPrevPitch.setFloat(player, savedTickPrevPitch);
                tickSaved = false;
            }
            // 只在**没写瞄准角**的时候刷新真实视角。
            //
            // 写了瞄准角的话, 这一刻的 fYaw/fPitch 就是瞄准值 —— 抓了它当"真实视角",
            // 下次还原就往瞄准值上还原, 这就是之前那两轮的棘轮。
            // 没写的时候它必然是原版算出来的真实视角, 抓它是安全的, 而且能让值保持新鲜
            // (不刷新的话, 停手交还模型朝向那一下会用很久以前的角度, 模型会猛地转一下)。
            if (!silentApplied) {
                visualYaw = fYaw.getFloat(player);
                visualPitch = fPitch.getFloat(player);
                visualPitchSet = true;
                if (!sentReady) { sentYaw = visualYaw; sentPitch = visualPitch; sentReady = true; }
            }
        } catch (Throwable t) {
            logOnce("playerTickEnd", t);
        } finally {
            restoreTickState();
        }
    }

    /** Exceptional exits only restore state; they must never submit normal tick actions. */
    public static void onPlayerTickAbort() {
        try {
            kaPacketReady = false;
            kaRenderCommit = false;
            scaffoldRenderReady = scaffoldRenderCompleted = false;
            restoreTickState();
        } catch (Throwable failure) { logOnce("tick abort cleanup", failure); }
    }

    private static void restoreTickState() {
        try {
            restoreScaffoldMovementBindings();
            // A failed placement or another module must never leak packet
            // rotation into the following movement/camera update.
            if (tickSaved) {
                try {
                    Object mc = mGetMC.invoke(null);
                    Object player = mc == null ? null : fPlayer.get(mc);
                    syncScaffoldContext(mc, player);
                    if (player != null && tickSaved) {
                        PlayerStatePipeline.Pose cameraPose = PLAYER_STATE.cameraPose();
                        fYaw.setFloat(player, cameraPose == null ? savedTickYaw : cameraPose.yaw);
                        fPitch.setFloat(player, cameraPose == null ? savedTickPitch : cameraPose.pitch);
                        if (fPrevYaw != null) fPrevYaw.setFloat(player, savedTickPrevYaw);
                        if (fPrevPitch != null) fPrevPitch.setFloat(player, savedTickPrevPitch);
                    }
                } catch (Throwable t) { logOnce("restore packet camera", t); }
                finally { tickSaved = false; }
            }
            vanillaAimMovementApplied = false;
            scaffoldVanillaMovementApplied = false;
            PLAYER_STATE.finishTick();
            reportPlayerStateViolation();
        } catch (Throwable failure) { logOnce("tick cleanup", failure); }
    }

    /**
     * `EntityRenderer.setupCameraTransform` 返回时触发 —— **相机已经算完, 模型还没渲染**。
     *
     * 这是整个"静默视角"最关键的钩子, 也是之前一直做不对的原因。
     *
     * 一帧里 `rotationPitch` 被读了三次, 但**时间点不同**:
     *
     *     renderWorld(FJ)
     *     ├── getMouseOver(F)V              ← 准星用
     *     └── renderWorldPass(IFJ)
     *         ├── setupCameraTransform(FI)V ← 相机用 (内部调 orientCamera)   [本钩子在这之后]
     *         ├── renderEntities(...)       ← 模型的头部俯仰用
     *         └── ...
     *
     * `onRender` 挂在 updateCameraAndRender 的 RETURN 前 —— 那已经是**三件事全做完之后**了。
     * 所以在 onRender 里写俯仰, 只来得及进下一 tick 的包, 玩偶这一帧早就画完了。
     * 表现出来就是: 服务端和别人看到你低头了, 而你自己第三人称的玩偶没低头。
     *
     * 在相机之后、模型之前写, 影响到的**只有玩偶**, 你的视角一格都不动。
     * 这就是"别的端做得到"的原因 —— 不是找到了什么技巧, 是钩子挂的位置不一样。
     */
    /**
     * setupCameraTransform 返回之后、renderEntities 之前触发 —— 这是**这一帧里最后一次**
     * 能改到"玩偶看什么角度"的机会。
     *
     * 这里踩过一个大坑: 判断条件原来是 `silentApplied`, 而那个标志在**同一帧更早**的
     * onFrame (restoreSilentRotation) 里已经被清成 false 了 —— 于是这个钩子每一帧都在
     * 第一行返回, 从来没生效过。表现出来就是"搭路头低不下去、玩偶的俯仰一直跟着相机走"。
     * 所以现在用一个不会被 onFrame 清掉的 silentModelOn。
     *
     * 第二个坑: 身体的 renderYawOffset 由原版 updateDistance 每个 tick 按移动方向重算,
     * 而 applySilentRotation 是在**上一帧渲染完之后**写的 —— 那个值活不过紧接着的 tick。
     * 想动模型就必须写在这里, 写晚了等于没写。
     */
    /**
     * EntityLivingBase.onUpdate/updateDistance torso recurrence. The head keeps
     * the actual aim; the torso follows displacement (or aim while swinging),
     * then follows the head when their relative angle exceeds vanilla's limit.
     * This is a render pose only: movement, input and limb swing stay vanilla.
     */
    static float[] coordinatedCombatPose(float trackedBody, float targetYaw,
                                         double dx, double dz, boolean swinging, boolean advance) {
        float body = trackedBody;
        if (advance) {
            float movementYaw = body;
            if ((float) (dx * dx + dz * dz) > 0.0025000002f)
                movementYaw = (float) Math.atan2(dz, dx) * 180.0f / (float) Math.PI - 90.0f;
            if (swinging) movementYaw = targetYaw;
            body += shortestDelta(body, movementYaw) * 0.3f;
            float relative = shortestDelta(body, targetYaw);
            relative = Math.max(-75.0f, Math.min(75.0f, relative));
            body = targetYaw - relative;
            if (relative * relative > 2500.0f) body += relative * 0.2f;
            body = sameBranch(trackedBody, body);
        }
        return new float[]{body, targetYaw};
    }

    /**
     * 把已经限速过的出站姿态同步到本地模型。rotationYaw 与移动输入仍完全由
     * 原版维护；这里只写渲染身体/头部字段，并保留原版的头身角与插值节奏。
     */
    private static void syncCombatModel(Object player, float yaw, float pitch) throws Exception {
        if (player == null || !"KillAura".equals(silAimWho)) return;
        float oldBody = fRenderYawOffset == null ? yaw : fRenderYawOffset.getFloat(player);
        float oldHead = fRotationYawHead == null ? oldBody : fRotationYawHead.getFloat(player);
        float oldPitch = fPitch == null ? pitch : fPitch.getFloat(player);

        int tick = fTicksExisted == null
                ? (int) (System.currentTimeMillis() / 50L)
                : fTicksExisted.getInt(player);
        if (!kaModelPoseReady) {
            kaModelPoseReady = true;
            kaModelBodyYaw = oldBody;
            kaPrevModelBodyYaw = oldBody;
            kaModelHeadYaw = sameBranch(oldBody, oldHead);
            kaPrevModelHeadYaw = kaModelHeadYaw;
            kaModelPitch = oldPitch;
            kaPrevModelPitch = oldPitch;
            kaModelTick = Integer.MIN_VALUE;
        }
        // A new target changes the destination, not the start of interpolation.
        // Reinitialising from vanilla's camera-facing fields on every switch
        // snapped the already-aimed head and torso back toward the free camera.
        kaModelPoseTarget = kaTarget;
        boolean advance = tick != kaModelTick;
        if (advance) {
            kaPrevModelBodyYaw = kaModelBodyYaw;
            kaPrevModelHeadYaw = kaModelHeadYaw;
            kaPrevModelPitch = kaModelPitch;
            double dx = fPosX == null || fPrevX == null ? 0.0
                    : fPosX.getDouble(player) - fPrevX.getDouble(player);
            double dz = fPosZ == null || fPrevZ == null ? 0.0
                    : fPosZ.getDouble(player) - fPrevZ.getDouble(player);
            boolean swinging = fScaffoldSwingProgress != null
                    && fScaffoldSwingProgress.getFloat(player) > 0.0f;
            float[] pose = coordinatedCombatPose(kaModelBodyYaw, yaw, dx, dz, swinging, true);
            kaModelBodyYaw = sameBranch(kaPrevModelBodyYaw, pose[0]);
            kaModelHeadYaw = sameBranch(kaPrevModelHeadYaw, pose[1]);
            kaModelPitch = Math.max(-90.0f, Math.min(90.0f, pitch));
            kaModelTick = tick;
        }

        // Do not publish kaRenderedTarget/kaRenderedYaw here. This method runs in
        // the movement tick, before the prepared pose is actually rendered.
        // onCameraReady writes the fields and observeCombatModel publishes the
        // rendered snapshot afterwards.
    }

    /** Capture the torso/head recurrence that vanilla just computed. */
    private static void adoptVanillaCombatPose(Object player, float yaw, float pitch) throws Exception {
        if (player == null || !"KillAura".equals(silAimWho)) return;
        float body = fRenderYawOffset == null ? yaw : fRenderYawOffset.getFloat(player);
        float previousBody = fPrevRenderYawOffset == null ? body : fPrevRenderYawOffset.getFloat(player);
        float head = fRotationYawHead == null ? yaw : fRotationYawHead.getFloat(player);
        float previousHead = fPrevRotationYawHead == null ? head : fPrevRotationYawHead.getFloat(player);
        kaPrevModelBodyYaw = previousBody;
        kaModelBodyYaw = body;
        kaPrevModelHeadYaw = sameBranch(head, previousHead);
        kaModelHeadYaw = sameBranch(previousHead, head);
        kaPrevModelPitch = fPrevPitch == null ? pitch : fPrevPitch.getFloat(player);
        kaModelPitch = Math.max(-90.0f, Math.min(90.0f, pitch));
        kaModelPoseTarget = kaTarget;
        kaModelPoseReady = true;
        kaModelTick = fTicksExisted == null
                ? (int) (System.currentTimeMillis() / 50L)
                : fTicksExisted.getInt(player);
    }

    public static void onCameraReady() {
        if (!camReadyProbed) { camReadyProbed = true; log("钩子 onCameraReady 触发了 (setupCameraTransform 返回)"); }
        // Capture before every guard and before any model-only pitch write.
        // ESP/Tracers must use the exact camera that Minecraft just built,
        // including sprint FOV, view bobbing, hurt and third-person transforms.
        captureFrameProjection();
        // **守卫必须同时认"瞄准中"和"搭路姿势", 否则搭路永远进不来。**
        //
        // 原来只有 silentModelOn —— 而那个标志是 applySilentRotation 在"正在瞄准"时才设的。
        // 我把 Scaffold 的 requestSilent 去掉之后 (它现在不动相机), 搭路就再也不算"瞄准",
        // 于是这里一次都不执行, 模型直接变回原版。modelHeadSet 是 Scaffold 每帧维护的独立
        // 姿势标志, 必须一起认。
        if (fPitch == null || fPlayer == null || mGetMC == null) return;
        try {
            Object mc0 = mGetMC.invoke(null);
            syncScaffoldContext(mc0, mc0 == null ? null : fPlayer.get(mc0));
        } catch (Throwable t) { logOnce("scaffold camera context", t); return; }
        if (!silentModelOn && !modelHeadSet) {
            observeCombatModel();
            try { Object mc = mGetMC.invoke(null); if (mc != null) observeScaffoldRender(fPlayer.get(mc), camHookPartialTicks); }
            catch (Throwable t) { logOnce("scaffold manual render", t); }
            return;
        }
        try {
            if (!modelHeadSet && !silentModelPitch && !silentBodyToo) return;
            Object mc = mGetMC.invoke(null);
            if (mc == null) return;
            Object player = fPlayer.get(mc);
            if (player == null) return;
            boolean combatPose = "KillAura".equals(silAimWho)
                    && !modelHeadSet && silentBodyToo && kaModelPoseReady;
            int modelTickNow = fTicksExisted == null
                    ? (int) (System.currentTimeMillis() / 50L)
                    : fTicksExisted.getInt(player);
            boolean vanillaScaffoldBody = modelHeadSet && scaffoldVanillaPoseTick == modelTickNow;
            // A frame is not a movement tick. Until vanilla has consumed this
            // pose, leave its head, torso and limbs intact and do not invent a
            // second body animation from displacement.
            if (modelHeadSet && !vanillaScaffoldBody) return;
            // A target's reaction delay retains the previously displayed pose.
            // Only attack credentials require the pose's new target identity;
            // dropping the render override here snapped back to the free camera.
            // 头俯仰: Scaffold 用锁定姿态；KillAura 用同一 tick 内部姿态。
            float p = Math.max(-90f, Math.min(90f,
                    modelHeadSet ? modelHeadPitch : (combatPose ? kaModelPitch : sentPitch)));
            float previousP = modelHeadSet && scaffoldModelReady ? scaffoldPrevModelPitch
                    : (combatPose ? kaPrevModelPitch : p);
            // Scaffold uses the torso/limb result of the completed native tick.
            // Pitch is displayed only after camera matrices have been captured.
            pitchTrace("onCameraReady 写瞄准值之前", player);
            // 存现场值, 渲染完还回去 (见 applySilentRotation 末尾)。不是缓存"真实视角",
            // 是"写之前是什么就还什么" —— 这样不可能碰坏玩家输入。
            // setupCameraTransform may run more than once for a frame. Save
            // the vanilla fields only on the first pass; later passes must not
            // mistake our own override for the value that should be restored.
            if (!pitchSaved) {
                savedPitch = fPitch.getFloat(player);
                savedPrevPitch = fPrevPitch == null ? savedPitch : fPrevPitch.getFloat(player);
                pitchSaved = true;
            }
            if (!modelFieldsSaved) {
                savedModelBodyYaw = fRenderYawOffset == null ? 0.0f : fRenderYawOffset.getFloat(player);
                savedPrevModelBodyYaw = fPrevRenderYawOffset == null
                        ? savedModelBodyYaw : fPrevRenderYawOffset.getFloat(player);
                savedModelHeadYaw = fRotationYawHead == null
                        ? savedModelBodyYaw : fRotationYawHead.getFloat(player);
                savedPrevModelHeadYaw = fPrevRotationYawHead == null
                        ? savedModelHeadYaw : fPrevRotationYawHead.getFloat(player);
                modelFieldsSaved = true;
            }
            fPitch.setFloat(player, p);
            if (fPrevPitch != null) fPrevPitch.setFloat(player, previousP);
            if (combatPose) {
                if (fRenderYawOffset != null) fRenderYawOffset.setFloat(player, kaModelBodyYaw);
                if (fPrevRenderYawOffset != null) fPrevRenderYawOffset.setFloat(player, kaPrevModelBodyYaw);
                if (fRotationYawHead != null) fRotationYawHead.setFloat(player, kaModelHeadYaw);
                if (fPrevRotationYawHead != null) fPrevRotationYawHead.setFloat(player, kaPrevModelHeadYaw);
            }
            if (modelHeadSet) {
                // Body and limb fields are the result of super.onUpdate. Keep
                // the native previous head sample too, including the first
                // camera-to-bridge transition; no render-only body recurrence.
                if (fRotationYawHead != null) fRotationYawHead.setFloat(player, modelHeadYaw);
                if (fPrevRotationYawHead != null) fPrevRotationYawHead.setFloat(player,
                        sameBranch(modelHeadYaw, savedPrevModelHeadYaw));
            }
            if (modelHeadSet || combatPose) {
                PlayerStatePipeline.Owner modelOwner = modelHeadSet
                        ? PlayerStatePipeline.Owner.SCAFFOLD
                        : PlayerStatePipeline.Owner.COMBAT;
                float renderedHeadYaw = modelHeadSet ? modelHeadYaw : kaModelHeadYaw;
                PlayerStatePipeline.Pose coherentModel = PLAYER_STATE.commitModel(
                        modelOwner, renderedHeadYaw, p);
                fPitch.setFloat(player, coherentModel.pitch);
                if (fRotationYawHead != null)
                    fRotationYawHead.setFloat(player, coherentModel.yaw);
                if (modelHeadSet) {
                    modelHeadYaw = coherentModel.yaw;
                    modelHeadPitch = coherentModel.pitch;
                } else {
                    kaModelHeadYaw = coherentModel.yaw;
                    kaModelPitch = coherentModel.pitch;
                }
                reportPlayerStateViolation();
            }
            observeCombatModel();
            observeScaffoldRender(player, camHookPartialTicks);
            // 模型朝向诊断 (每秒一条, 排完就删)。字段绑定已确认是对的 (field_70761_aq =
            // renderYawOffset, field_70759_as = rotationYawHead), 所以现在要确认的是:
            // ① 这段有没有执行到; ② 写完之后有没有被谁当场改回去。
            // 打的是**写完立刻回读**的值 —— 和 sentYaw 不一致就说明在这条路径上被覆盖了。
            long mk = System.currentTimeMillis();
            if (mk - modelWhyAt >= 1000) {
                modelWhyAt = mk;
                log("模型朝向诊断: 写入 sentYaw=" + sentYaw + " sentPitch=" + sentPitch
                        + " bodyToo=" + silentBodyToo
                        + " | 回读 renderYawOffset=" + (fRenderYawOffset == null ? "null" : String.valueOf(fRenderYawOffset.getFloat(player)))
                        + " rotationYawHead=" + (fRotationYawHead == null ? "null" : String.valueOf(fRotationYawHead.getFloat(player)))
                        + " prevHead=" + (fPrevRotationYawHead == null ? "null" : String.valueOf(fPrevRotationYawHead.getFloat(player)))
                        + " | 视角 yaw=" + fYaw.getFloat(player));
            }
        } catch (Throwable t) {
            logOnce("cameraReady", t);
        }
    }

    /**
     * Transformer entry that carries the exact camera sample which produced the
     * OpenGL matrices.  Keep the no-arg method above as the single model-pose
     * owner; older probes and all pose writes intentionally remain there.
     */
    public static void onCameraReady(float partialTicks, int pass) {
        camHookPartialTicks = partialTicks;
        camHookPass = pass;
        onCameraReady();
    }

    /**
     * onFrame 入口: 还原成视觉角度。
     *
     * prev* 必须一起还原 —— 相机用的是 prev 和当前值的 partialTicks 插值,
     * 只改当前值的话视角会在两个角度之间来回抽。
     */
    private static void restoreSilentRotation(Object player) {
        if (!restoreProbed) { restoreProbed = true; log("restoreSilentRotation 触发了 (onFrame 里)"); }
        if (silentApplied && player != null && fPitch != null) pitchTrace("restoreSilentRotation 入口", player);
        if (!silentApplied || player == null) return;
        // **这里不再写 fYaw/fPitch。**
        //
        // 原来它写 fYaw/fPitch = visualYaw/visualPitch —— 那是**第三处**无条件写缓存值的地方
        // (另两处是 onPlayerTickEnd 和 applySilentRotation 末尾)。三处叠在一起, 每帧把玩家的
        // 鼠标增量擦三遍, 视角就完全锁死了。
        // 还原的职责现在只有 onPlayerTickEnd 一处, 而且是"存现场、还现场"。
        // 这里只负责清标志 —— 标志的清除权本来就该在这儿 (它比 onPlayerTickEnd 晚, 比下一帧的
        // onPlayerMoved 早, 正好卡在中间)。
        //
        // 注意: 更不能碰 renderYawOffset / rotationYawHead —— 那两个是**模型**朝向, 由
        // applySilentRotation 在上一帧末尾设成了瞄准角度。在这里顺手还原的话, 模型会在
        // 这两次 hook 之间被渲染, 身体就永远朝向"你的视角"而不是目标 —— 这正是
        // "KillAura 不朝向目标"的成因: 先设成目标、再被还原成视角、然后才渲染。
        silentApplied = false;
    }

    /** 瞄准点 -> 角度。返回 {yaw, pitch}, 供 requestSilent 用 */
    static float[] lookAt(Object player, double tx, double ty, double tz) throws Exception {
        double dx = tx - fPosX.getDouble(player);
        double dy = ty - (fPosY.getDouble(player) + eyeH(player));
        double dz = tz - fPosZ.getDouble(player);
        double dist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, dist)));
        return new float[]{yaw, pitch};
    }

    /**
     * 把角度平滑地转向目标 (turnSpeed = 每秒最多转多少度, 0 = 瞬间到位)。
     *
     * **为什么是"每秒"而不是"每刻":** 一帧可能跨 0 个、1 个甚至多个 tick, 按 tick 给上限的话
     * 转速就跟着帧率走 —— 帧率高时看起来慢、卡顿时突然一大跳。按秒给, 再自己按实际经过的
     * 时间折算, 才和帧率无关。
     *
     * 收敛用比例项而不是固定步长: 固定步长"啪"一下怼到目标然后停住, 人手不会那样;
     * 比例项让它在快到位时自然减速, 这才是"丝滑"的来源。
     */
    static float[] smoothTo(float curYaw, float curPitch, float tgtYaw, float tgtPitch, double turnSpeed) {
        return smoothTo(curYaw, curPitch, tgtYaw, tgtPitch, turnSpeed, 1.0f);
    }

    static float[] smoothTo(float curYaw, float curPitch, float tgtYaw, float tgtPitch,
                            double turnSpeed, float tickFrac) {
        if (turnSpeed <= 0) return new float[]{tgtYaw, tgtPitch};
        // turnSpeed 是"度/秒", tickFrac 是这一帧覆盖了多少个 tick (1.0 = 一帧一 tick)
        float maxStep = (float) Math.max(0.4, turnSpeed / 20.0 * tickFrac);
        float dYaw = wrapDeg(tgtYaw - curYaw);
        float dPitch = tgtPitch - curPitch;
        // 比例系数 0.30: 差得远时吃满 maxStep (全速), 差 3 度以内开始线性收力
        float sYaw = dYaw * 0.30f, sPitch = dPitch * 0.30f;
        if (sYaw > maxStep) sYaw = maxStep; else if (sYaw < -maxStep) sYaw = -maxStep;
        if (sPitch > maxStep) sPitch = maxStep; else if (sPitch < -maxStep) sPitch = -maxStep;
        float nPitch = Math.max(-90f, Math.min(90f, curPitch + sPitch));
        return new float[]{curYaw + sYaw, nPitch};
    }

    /** 把角度差规整到 -180..180, 否则从 179 转到 -179 会绕一整圈 */
    static float wrapDeg(float d) {
        d = d % 360f;
        if (d >= 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }

    /**
     * 按实际屏幕算面板几何。每帧算一次很便宜, 但能保证窗口大小/缩放变了也不会跑偏。
     *
     * 这里读的是物理帧缓冲尺寸 (displayWidth/Height), 不是 GuiScreen 的逻辑尺寸 ——
     * 面板现在在物理像素空间绘制, 可用空间就是物理尺寸, 跟 MC 的 guiScale 无关。
     */
    private static void layoutFor(Object screen) {
        int pw = 1280, ph = 720;
        try {
            Object mc = mGetMC.invoke(null);
            if (mc != null && fDisplayW != null && fDisplayH != null) {
                pw = fDisplayW.getInt(mc);
                ph = fDisplayH.getInt(mc);
            }
        } catch (Throwable ignored) { }
        if (pw <= 0) pw = 1280;
        if (ph <= 0) ph = 720;
        GS = guiScaleFor(screen);
        layoutForFake(pw, ph, uiScale(ph));
        applyPan(pw, ph);
    }

    /**
     * 鼠标坐标换算系数: MC 逻辑单位 -> 物理像素。
     *
     * **这个不能用 S。** drawScreen 收到的 mx/my 是逻辑坐标 (Minecraft 里算的
     * Mouse.getX() * scaledWidth / displayWidth), 面板几何却是物理像素, 两者差一个 guiScale。
     * 之前拿 S(默认 1) 去乘, 命中判定整体偏了 4 倍 —— 鼠标在屏幕中间点,
     * 命中的却是面板左上角那一片, 看着就是"按钮全错位"。
     */
    private static int guiScaleFor(Object screen) {
        try {
            Object mc = mGetMC.invoke(null);
            if (screen == null || mc == null || fScreenW == null || fDisplayW == null) return 1;
            int logical = fScreenW.getInt(screen);
            int physical = fDisplayW.getInt(mc);
            if (logical <= 0 || physical <= 0) return 1;
            return Math.max(1, Math.min(8, Math.round(physical / (float) logical)));
        } catch (Throwable t) {
            return 1;
        }
    }

    /** 把用户拖动的偏移夹回屏幕内, 再套到默认位置上 —— 面板永远拖不出去 */
    private static void applyPan(int pw, int ph) {
        int baseX = PX, baseY = PY;
        int loX = 4 - baseX, hiX = Math.max(loX, pw - PANEL_W - 4 - baseX);
        int loY = 4 - baseY, hiY = Math.max(loY, ph - PANEL_H - 4 - baseY);
        panX = Math.max(loX, Math.min(hiX, panX));
        panY = Math.max(loY, Math.min(hiY, panY));
        PX = baseX + panX;
        PY = baseY + panY;
    }

    /** 把设计基准 (UI 单位) 按缩放换算成物理像素常量 */
    private static void scaleLayout(int scale, int physWidth, int physHeight) {
        S = Math.max(1, scale);
        physW = physWidth;
        physH = physHeight;
        RAIL_W = RAIL_W_L * S;
        HEADER_H = HEADER_H_L * S;
        FOOT_H = FOOT_H_L * S;
        ROW_NAME = ROW_NAME_L * S;
        ROW_PARAM = ROW_PARAM_L * S;
        CARD_PAD = CARD_PAD_L * S;
        RAIL_ITEM_H = RAIL_ITEM_H_L * S;
        AC_ITEM_H = AC_ITEM_H_L * S;
        INK_CENTER = INK_CENTER_L * S;
        // 独立分栏的常量 —— 忘了这里 S 就是 0, 列会全部叠在屏幕左上角
        COL_MARGIN = COL_MARGIN_L * S;
        COL_GAP = COL_GAP_L * S;
        COL_W_MIN = COL_W_MIN_L * S;
        COL_W_MAX = COL_W_MAX_L * S;
        COL_HEAD_H = COL_HEAD_H_L * S;
        COL_ROW_H = COL_ROW_H_L * S;
        COL_PARAM_H = COL_PARAM_H_L * S;
        COL_PAD = COL_PAD_L * S;
        COL_RADIUS = COL_RADIUS_L * S;
        COL_RESIZE_W = COL_RESIZE_W_L * S;
    }

    /** Modern HUD stays independent of the native Screen, so ESP works with the menu closed. */
    public static void drawGui1201(Object graphics, float partialTicks) {
        if (graphics == null) return;
        try {
            ensure();
            if (!modernHudSeen) {
                modernHudSeen = true;
                log("1.20.1 HUD callback reached: " + ModernRuntime.hudTarget());
            }
            ModernGui.init();
            if (modernAutoPreview && ModernGui.open(true)) modernAutoPreview = false;
            ModernEsp.draw(graphics);
            ModernGui.hud(graphics);
        } catch (Throwable t) {
            modernGuiFailure = t.toString();
            logOnce("drawGui1201 FAILED", t);
        }
    }

    static void modernScreenState(boolean open) { guiOpen = open; }
    static void modernPaintResult(Throwable failure) {
        if (failure != null) { modernGuiFailure = failure.toString(); return; }
        if (!modernGuiPainted) {
            modernGuiPainted = true;
            modernGuiFailure = "";
            log("1.20.1 native Screen first paint OK (" + VERSION + ")");
        }
    }

    private static void text1201(Object graphics, Object font, String text, int x, int y, int color)
            throws Exception {
        m1201DrawString.invoke(graphics, font, text, x, y, color);
    }

    /**
     * 在面板之前把当前世界帧复制到纹理，用 5x5 加权采样铺回去，形成固定管线下的
     * Gaussian-style backdrop blur；旧显卡或非 LWJGL 环境失败时退回磨砂遮罩。
     */
    private static void drawGaussianBackdrop() {
        if (mDrawRect == null || physW <= 0 || physH <= 0) return;
        try {
            if (!FrostPass.draw(physW, physH, 0, 0, physW, physH, 0, glassBlurRadius * 2.25f, 1f))
                BackdropBlur.draw(physW, physH, glassBlurRadius);
        }
        catch (Throwable failure) { logOnce("backdrop-blur", failure); }
        // One adjustable neutral veil; stacking several dark masks hid both
        // the actual blur and the world behind the translucent interface.
        try {
            int tintAlpha = Math.max(0, Math.min(255, Math.round(255.0f * backdropTint * 0.48f)));
            mDrawRect.invoke(null, 0, 0, physW, physH, (tintAlpha << 24) | 0x001B1027);
        } catch (Throwable ignored) { }
    }

    private static float[] gaussianBlendAlphas() {
        int[] kernel = {1, 4, 6, 4, 1};
        float[] alpha = new float[25];
        int sum = 0;
        for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) {
            int weight = kernel[x] * kernel[y];
            sum += weight;
            alpha[y * 5 + x] = (float)weight / sum;
        }
        return alpha;
    }

    /** LiteGui.drawScreen 每帧调用: 画面板 + 点击命中 (与悬停同源, 物理上不可能错位) */
    public static void drawGui(int mx, int my) {
        try {
            ensure();
            // 先按屏幕算几何, 再处理输入 —— 命中判定和渲染必须用同一套坐标
            Object curScreen = fCurrentScreen == null ? null : fCurrentScreen.get(mGetMC.invoke(null));
            layoutFor(curScreen);
            initFont();
            layoutAuroraWorkspace();
            // drawScreen 给的 mx/my 是 MC 的逻辑单位, 面板几何是物理像素 —— 先乘 GS 换算。
            // 只换算这一次, 后面命中和渲染共用同一个值, 不可能错位。
            int pmx = mx * GS, pmy = my * GS;
            if (!layoutLogged) {
                layoutLogged = true;
                log("layout: 物理 " + physW + "x" + physH + ", guiScale=" + GS + ", S=" + S
                        + ", 面板 " + PANEL_W + "x" + PANEL_H + " @" + PX + "," + PY
                        + ", 鼠标逻辑(" + mx + "," + my + ") -> 物理(" + pmx + "," + pmy + ")");
            }
            // 滚轮翻页。优先用事件循环里抓到的量; 抓不到再退回直接读一次。
            if (auroraWorkspace) deckUpdateHover(pmx, pmy);
            int wheel = pendingScroll;
            pendingScroll = 0;
            if (wheel == 0 && mGetDWheel != null) {
                wheel = (Integer) mGetDWheel.invoke(null);
            }
            if (wheel != 0) {
                // 鼠标停在数值滑块上 -> 滚轮微调这个参数, 不滚动整列。
                //
                // 拖动精度受滑轨像素数限制 (最窄 24 逻辑像素, 区间 7.0 的参数每像素跨 0.29),
                // 所以拖动只能粗调。精调必须另有一条通路, 否则 3.1 和 3.4 之间就是空的。
                if (hoverParamMd != null && hoverParamI >= 0 && hoverParamI < hoverParamMd.ps.length) {
                    P hp = hoverParamMd.ps[hoverParamI];
                    if (!hp.isMode() && hp.max > hp.min
                            && !(hp.step >= 1 && hp.min == 0 && hp.max == 1)) {
                        double st = sliderStep(hp);
                        double nv = hp.v + (wheel > 0 ? st : -st);
                        hp.v = hp.snap(nv);        // 同一个网格, 同一个夹取
                        wheel = 0;
                    }
                }
            }
            if (wheel != 0) {
                // 只按方向滚: LWJGL 在不同驱动上返回 ±120 或 ±1,
                // 原来的 wheel / 120 * 24 是整数除法, 遇到 ±1 直接算成 0 —— 滚不动的主因。
                // 现在滚的是鼠标底下那一列, 每列各自滚动。
                int step = wheel > 0 ? -28 * S : 28 * S;
                int hit = colAt(pmx, pmy);
                if (hit >= 0) {
                    colScroll[hit] = Math.max(0, Math.min(maxColScroll(hit), colScroll[hit] + step));
                }
            }
            // 左键按下沿: 用与本帧绘制完全相同的 mx/my 做命中, 取代 mouseClicked 的事件坐标 (那个会错位)
            boolean down = (Boolean) mIsButtonDown.invoke(null, 0);
            // 右键按下沿。原来完全没有右键通路, 所以"清空绑定"没有入口 —— 左键进了绑定模式之后
            // Esc 只能取消 (保留原键), 想把它去掉就无路可走。
            boolean rightDown = (Boolean) mIsButtonDown.invoke(null, 1);
            if ((down && !mousePrev) || (rightDown && !mouseRightPrev)) {
                clickAt(pmx, pmy, rightDown);
            }
            mousePrev = down;
            mouseRightPrev = rightDown;

            // 拖动 HUD: 改的是百分比参数, 所以窗口尺寸变了位置也跟着走
            if (hudDrag) {                if (down) {
                    // 除数是屏幕物理尺寸 —— pmx 是物理像素, 百分比要的就是"占屏幕多少"
                    double nx = (pmx - hudGrabDX) * 100.0 / Math.max(1, physW);
                    double ny = (pmy - hudGrabDY) * 100.0 / Math.max(1, physH);
                    setP(F_HUD, 0, Math.max(0, Math.min(100, nx)));
                    setP(F_HUD, 1, Math.max(0, Math.min(100, ny)));
                } else {
                    hudDrag = false;
                    log("hud moved -> " + (int) p(F_HUD, 0) + "% , " + (int) p(F_HUD, 1) + "%");
                }
            }

            if (widgetDragMod >= 0) {
                if (down) {
                    if (widgetDragMod == F_HOTBARACCENT) {
                        setP(F_HOTBARACCENT, 0, mods[F_HOTBARACCENT].ps[0].snap(
                                widgetDragStartX + (pmx - widgetDragMouseX) / (double) Math.max(1, GS)));
                        setP(F_HOTBARACCENT, 1, mods[F_HOTBARACCENT].ps[1].snap(
                                widgetDragStartY - (pmy - widgetDragMouseY) / (double) Math.max(1, GS)));
                    } else {
                        int xp = widgetXParam(widgetDragMod), yp = widgetYParam(widgetDragMod);
                        double nx = (pmx - widgetGrabX) * 100.0
                                / Math.max(1, physW - widgetBW[widgetDragMod]);
                        double ny = (pmy - widgetGrabY) * 100.0
                                / Math.max(1, physH - widgetBH[widgetDragMod]);
                        setP(widgetDragMod, xp, Math.max(0, Math.min(100, nx)));
                        setP(widgetDragMod, yp, Math.max(0, Math.min(100, ny)));
                    }
                } else {
                    widgetDragMod = -1;
                }
            }

            // **拖动右下角手柄 = 缩放 HUD。** 用户要求: "hud 加一个可调节大小的拖动条"。
            //
            // 用"鼠标相对盒子左下角的水平距离 / 盒子原始宽度"当倍率, 而不是把鼠标位移直接
            // 加上去 —— 后者会累积误差, 而且松手再按下去会因为抓取点不同而跳变。
            // 起点宽高和起点倍率在按下的那一刻记住 (hudDragScaleW/H + hudDragScale0),
            // 所以整个拖动过程是"相对起点"的一次性映射, 跟手且可逆。
            if (hudScaleDrag) {
                if (down) {
                    double k = hudDragScale0 * (pmx - hudX) / Math.max(1.0, hudDragScaleW);
                    setP(F_HUD, 8, Math.max(0.5, Math.min(2.5, k)));
                } else {
                    hudScaleDrag = false;
                    log("hud scale -> " + fmtNum(p(F_HUD, 8)));
                }
            }

            // Capture belongs to the pressed header until release, including outside its bounds.
            updatePanelDrag(pmx, pmy, down);

            // 拖右边缘改列宽
            if (resizeCol >= 0) {
                if (down) {
                    colWOverride[resizeCol] = Math.max(COL_W_MIN,
                            Math.min(COL_W_MAX, resizeStartW + (pmx - resizeStartX)));
                } else {
                    resizeCol = -1;
                }
            }

            // 拖动滑块: 左键按住时实时更新值 (换算与点击共用 applySlider)
            if (dragMod >= 0 && down) {
                applySlider(dragMod, dragParam, pmx);
            } else if (dragMod >= 0) {
                dragMod = -1;
            }

            // 拖动分享小窗 (点它的标题栏拖) —— 见 shareBox / handleShareClick
            if (shareDragging) {
                if (down) {
                    sharePosX = pmx - shareGrabDX;
                    sharePosY = pmy - shareGrabDY;
                } else {
                    shareDragging = false;
                    int[] sb = shareBox();
                    sharePosX = sb[0]; sharePosY = sb[1];   // 存夹取后的值, 免得停在屏幕外
                }
            }
            deckBeginFrame(System.nanoTime());
            layoutAuroraWorkspace();
            // 面板在物理像素空间绘制; 投影必须成对弹出,
            // 否则会带偏 MC 后面的 HUD。pushed 门控: 压栈中途抛异常时绝不能去弹一个没压满的栈。
            boolean pushed = false;
            try {
                pushPhysicalProjection();
                pushed = true;
                setThemeFromParams();
                drawGaussianBackdrop();
                renderColumns(pmx, pmy);
                renderShareCard(pmx, pmy);   // 小块盖在面板上面
            } finally {
                if (pushed) popPhysicalProjection();
            }
        } catch (Throwable t) {
            logStack("drawGui FAILED", t);
        }
    }

    /** 命中测试: 与绘制坐标完全同源。LiteGui.mouseClicked 不再用于命中 (事件坐标会错位) */
    public static void guiClicked(int mx, int my, int button) {
        // 有意留空: 命中已移至 drawGui, 避免双触发
    }

    /**
     * 功能行内各控件的横向几何 —— 渲染和点击都从这里取, 不允许各写一份。
     * 全部相对列表区右边缘定位: 面板宽度会按屏幕缩, 写死偏移在窄屏上会顶出去。
     */
    private static int kbW() { return 58 * S; }                               // 热键胶囊
    private static int kbX() { return swX() - 5 * S - kbW(); }
    private static int swW() { return 34 * S; }                               // 开关胶囊
    private static int swX() { return listRight() - swW(); }
    private static int tgW() { return 36 * S; }                               // 参数开关

        /** 命中测试: 与绘制坐标完全同源。LiteGui.mouseClicked 不再用于命中 (事件坐标会错位) */
    private static void clickAt(int mx, int my, boolean right) {
        if (auroraWorkspace) { deckPressX=mx; deckPressY=my; deckMotion.seed("click",1); deckClickFlash=1; }
        try {
            ensure();
            // 分享小块是模态的: 开着的时候所有点击都归它, 不许漏到底下的面板
            if (shareOpen) {
                // 右键要传给小窗 (配置单里右键 = 删除)。原来这里把 right 丢掉了,
                // 于是右键和小窗里的"删除"永远走不到。
                rightShareClick = right;
                handleShareClick(mx, my);
                return;
            }
            // 点面板别的地方 = 放弃正在编辑的参数值 (和搜索框同一个规矩: 焦点只有一个)。
            // 放在最前面判, 免得"点出去又落到另一个参数上"时两个输入框同时开着。
            // 注意: 命中同一个输入框时不能取消 —— 那会让点击自己把自己关掉, 详见下面 hitParam。
            if (paramEditing() && !overParamEditBox(mx, my)) paramEditCancel();
            // A widget moved under the settings panel is visually covered by
            // that panel.  Give the visible panel first ownership of clicks;
            // otherwise an invisible widget can steal navigation and slider
            // input, making the GUI appear randomly unresponsive.
            boolean overMainUi = auroraWorkspace
                    ? colAt(mx, my) >= 0 || (mx >= deckX && mx < deckX + deckW && my >= deckY && my < deckY + deckH)
                    : mx >= PX && mx < PX + PANEL_W && my >= PY && my < PY + PANEL_H;
            if (!right && !overMainUi) {
                for (int mod = mods.length - 1; mod >= 0; mod--) {
                    if (!draggableWidget(mod) || !mods[mod].on || widgetBW[mod] <= 0 || widgetBH[mod] <= 0) continue;
                    if (mx < widgetBX[mod] || mx >= widgetBX[mod] + widgetBW[mod]
                            || my < widgetBY[mod] || my >= widgetBY[mod] + widgetBH[mod]) continue;
                    widgetDragMod = mod;
                    widgetGrabX = mx - widgetBX[mod];
                    widgetGrabY = my - widgetBY[mod];
                    widgetDragMouseX = mx; widgetDragMouseY = my;
                    widgetDragStartX = p(mod, widgetXParam(mod));
                    widgetDragStartY = p(mod, widgetYParam(mod));
                    return;
                }
            }
            if (auroraWorkspace && clickAuroraNav(mx, my, right)) return;
            // **HUD 的拖动 / 缩放手柄。**
            //
            // 先看有没有点在 HUD 上 —— hudX/Y/W/H 是上一帧 renderHud 记下的实际矩形。
            // (以前 hudGrabDX/DY 和 hudDrag 只被读、从来没被赋过值, 所以"拖 HUD"这条
            //  路其实是死的。这里补上赋值。)
            //
            // 右下角 16*S 见方 = 缩放手柄, 其余地方 = 移动。
            if (!right && !overMainUi && mods[F_HUD].on && hudW > 0 && p(F_HUD, 10) > 0
                    && mx >= hudX && mx < hudX + hudW && my >= hudY && my < hudY + hudH) {
                int hs = 16 * S;
                if (mx >= hudX + hudW - hs && my >= hudY + hudH - hs) {
                    hudScaleDrag = true;
                    hudDragScaleW = Math.max(1, hudW);
                    hudDragScale0 = p(F_HUD, 8);
                    log("hud scale drag start (当前 " + fmtNum(hudDragScale0) + ")");
                } else {
                    hudDrag = true;
                    hudGrabDX = mx - hudX;
                    hudGrabDY = my - hudY;
                }
                return;
            }

            // 从后往前找: 后面的列画在上面, 命中也要优先
            for (int depth = NCOL - 1; depth >= 0; depth--) {
                int c = columnAtDepth(depth);
                if (auroraWorkspace && !deckVisible(c)) continue;
                int x = colX[c], y = colY[c], w = colW[c], vh = colViewH(c);
                if (mx < x || mx >= x + w || my < y || my >= y + vh) continue;

                // 右边缘 -> 拖宽。放在标题栏之前判, 否则贴着边缘时会变成拖动整列。
                if (!auroraWorkspace && mx >= x + w - COL_RESIZE_W) {
                    resizeCol = c;
                    resizeStartX = mx;
                    resizeStartW = w;
                    return;
                }

                // 标题栏 -> 拖动这一列
                if (my < y + COL_HEAD_H) {
                    if (right) return;
                    if (auroraWorkspace) deckPositions.front(c);
                    dragMod = -1; hudDrag = hudScaleDrag = false; widgetDragMod = -1;
                    searchFocused = cfgNameFocused = false;
                    dragCol = c;
                    dragColDX = mx - x;
                    dragColDY = my - y;
                    return;
                }
                searchFocused = false;
                cfgNameFocused = false;      // 点面板别处 = 收起命名输入框的焦点

                int cy = y + COL_HEAD_H - colScroll[c];

                if (c == CONFIG_COL) {
                    // 和 renderConfigColumn 用**同一组 cfgRow* 函数**算 y —— 各写一份必错位。
                    int rx1c = x + 8 * S, rx2c = x + w - 8 * S;
                    int rowY0 = y + COL_HEAD_H - colScroll[c];

                    // 行 0: 命名输入框
                    if (inRow(rx1c, rx2c, rowY0 + cfgRowName() * COL_ROW_H, mx, my)) {
                        cfgNameFocused = true;
                        searchFocused = false;      // 焦点只有一个
                        return;
                    }
                    // 行 1: Save
                    if (inRow(rx1c, rx2c, rowY0 + cfgRowSave() * COL_ROW_H, mx, my)) {
                        saveConfigToAccount();
                        return;
                    }
                    // 行 2: Share / Paste
                    {
                        int ry = rowY0 + cfgRowShare() * COL_ROW_H;
                        int gap = 6 * S, bw = (rx2c - rx1c - gap) / 2;
                        if (mx >= rx1c && mx < rx1c + bw && my >= ry && my < ry + COL_ROW_H) {
                            shareCurrentConfig();     // 一步: 上传 + 拿码 + 进剪贴板
                            return;
                        }
                        if (mx >= rx1c + bw + gap && mx < rx2c && my >= ry && my < ry + COL_ROW_H) {
                            pasteIntoShareInput();
                            return;
                        }
                    }
                    // 行 4..: 配置列表
                    for (int i = 0; i < cfgNames.length; i++) {
                        int ry = rowY0 + (cfgRowList0() + i) * COL_ROW_H;
                        if (my < ry || my >= ry + COL_ROW_H) continue;
                        if (mx < rx1c || mx >= rx2c) continue;
                        int cLeft = cfgCopyLeft(c, rx2c);
                        if (right) deleteSavedConfig(cfgNames[i]);
                        else if (mx >= cLeft - 3 * S) shareSavedConfig(cfgNames[i]);
                        else loadSavedConfig(cfgNames[i]);
                        return;
                    }
                    return;
                }

                if (c == SET_COL) {
                    if (inSearchBox(mx, my)) { searchFocused = true; cfgNameFocused = false; return; }
                    cy = searchBoxY() + searchBoxH() + 6 * S;
                    for (int i = 0; i < AC_NAMES.length; i++) {
                        if (my >= cy && my < cy + COL_ROW_H) {
                            applyPreset(i == AC_NAMES.length - 1 ? -1 : i);
                            log("template -> " + AC_NAMES[i]);
                            return;
                        }
                        cy += COL_ROW_H;
                    }
                    int rx1c = x + 8 * S, rx2c = x + w - 8 * S;
                    // ===== 第二部分: Share / Load 按钮 =====
                    for (int i = 0; i < CFG_ROWS.length; i++) {
                        if (my >= cy && my < cy + COL_ROW_H) {
                            if ("Targets".equals(CFG_ROWS[i])) {
                                // 目标选栏: 点一下换下一档, 一次性写进所有带 Target 参数的功能
                                applySharedTarget((curSharedTarget() + 1) % TARGET_OPTS.length);
                            } else if ("Reset Layout".equals(CFG_ROWS[i])) {
                                resetColumnLayout();
                            }
                            // 没有 else —— CFG_ROWS 现在只有这两项, 原来那个
                            // "Share / Load" 分支指向的浮窗已经搬到 CONFIG 列了。
                            // 留一个 else 会把"新增了行但忘了写处理"变成静默无反应。
                            return;
                        }
                        cy += COL_ROW_H;
                    }
                    // 配置列表的点击**已搬到 CONFIG 列** (见上面 c == CONFIG_COL 那段)。
                    // 这里原来跟着设置列的 cy 走一遍, 两边算 y 的方式不同, 留着就是错位源。
                    return;
                }

                for (int m = 0; m < mods.length; m++) {
                    Mod md = mods[m];
                    if (md.cat != c || !inList(md)) continue;
                    int rowH = COL_ROW_H;
                    if (auroraWorkspace) rowH += deckParamHeight(m);
                    else if (md.tag == null && isExpanded(m)) rowH += md.ps.length * COL_PARAM_H;

                    if (my >= cy && my < cy + rowH) {
                        int cx1 = x + 2 * S, cx2 = x + w - 2 * S;
                        int tw = 34 * S;
                        int tx = cx2 - 6 * S - tw;
                        int bw = 44 * S;
                        int bx = tx - 4 * S - bw;

                        if (auroraWorkspace) {
                            if (my >= cy + COL_ROW_H) {
                                int pi = (my - cy - COL_ROW_H) / COL_PARAM_H;
                                int py = cy + COL_ROW_H + pi * COL_PARAM_H;
                                if (isExpanded(m) && pi >= 0 && pi < md.ps.length && wholeRowVisible(py, COL_PARAM_H, y + COL_HEAD_H, Math.min(y + vh, cy + rowH)))
                                    hitDeckParam(m, pi, x, w, py, mx, my);
                            } else if (wholeRowVisible(cy, COL_ROW_H, y + COL_HEAD_H, y + vh)) {
                                if (md.tag != null) return;
                                if (mx >= x + w - 68 * S && mx < x + w - 28 * S) {
                                    if (right) { md.bindKey = 0; bindTarget = -1; }
                                    else { bindTarget = bindTarget == m ? -1 : m; paramEditCancel(); }
                                } else if (right || mx >= x + w - 28 * S) {
                                    setExpanded(m, !isExpanded(m));
                                } else md.on = !md.on;
                            }
                            return;
                        }
                        if (my >= cy + COL_ROW_H) {
                            // 参数行
                            int pi = (my - cy - COL_ROW_H) / COL_PARAM_H;
                            if (pi >= 0 && pi < md.ps.length) hitParam(m, pi, x, w, cy + COL_ROW_H + pi * COL_PARAM_H, mx, my);
                            return;
                        }
                        if (mx >= tx && mx < tx + tw) {
                            md.on = !md.on;
                            log("toggle " + md.name + " -> " + md.on);
                        } else if (md.tag == null && mx >= bx && mx < bx + bw) {
                            if (right) {
                                // 右键 = 直接清空绑定, 不用先进绑定模式。
                                // 绑错了想拿掉, 以前只能先进绑定再按 Esc —— 而 Esc 只是"取消",
                                // 原键还在, 所以实际上无路可走。
                                md.bindKey = 0;
                                bindTarget = -1;
                                setToast(md.name + " 热键已清空");
                                log("unbound " + md.name);
                            } else {
                                bindTarget = (bindTarget == m) ? -1 : m;
                                log("bind target -> " + (bindTarget < 0 ? "none" : md.name));
                            }
                        } else if (md.tag == null && md.ps.length > 0) {
                            boolean next = !isExpanded(m);

                            setExpanded(m, next);
                        }
                        return;
                    }
                    cy += rowH;
                }
                return;
            }
            searchFocused = false;
        } catch (Throwable t) {
            log("clickAt FAILED: " + t);
        }
    }

    /** 参数行的点击: 模式胶囊切下一项, 开关翻转, 滑块进入拖动 */
    private static void hitParam(int m, int pi, int x, int w, int y, int mx, int my) {
        Mod md = mods[m];
        P pp = md.ps[pi];
        int rx = x + w - 8 * S;
        int pillH = 14 * S;
        int py = y + (COL_PARAM_H - pillH) / 2;

        if (pp.isMode()) {
            int mw = 34 * S;
            for (String o : pp.opts) mw = Math.max(mw, textW(o) + 16 * S);
            if (mx >= rx - mw && mx < rx) {
                pp.cycle(1);
                log("param " + md.name + "." + pp.n + " -> " + pp.cur());
            }
            return;
        }
        if (pp.step >= 1 && pp.min == 0 && pp.max == 1) {
            if (mx >= rx - 34 * S && mx < rx) {
                pp.v = pp.v > 0 ? 0 : 1;
                log("param " + md.name + "." + pp.n + " -> " + (int) pp.v);
            }
            return;
        }
        if (pp.max <= pp.min) return;
        String vs = fmtNum(pp.v);
        int vw = textW(vs);
        // **点数值本身 = 直接输入编辑。**
        //
        // 用户报的: "滑动条依旧不能精准滑动, 如果实在不行可以让后面的值可输入编辑"。
        // 拖动那条路是真的做不到精调的 —— 滑轨最窄 24 逻辑像素, 区间 7.0 的参数每像素跨
        // 0.29, 想拉出 0.01 得把鼠标放在同一个像素里。与其把滑轨做长, 不如给一条精确通路:
        // 数值本身就是按钮, 点它开输入框, 打完回车生效。
        //
        // 判据放在拖动之前 —— 两者区域重叠 (都在右边那一块), 数值优先。
        if (mx >= x + w - 12 * S - vw && mx < x + w - 4 * S) {
            paramEditMd = md;
            paramEditParam = pi;
            paramEditBuf = fmtNum(pp.v);
            searchFocused = false;              // 输入焦点只有一个
            return;
        }
        int sw = sliderTrackW(w);
        int sx = sliderTrackX(x, w, sw);
        if (mx >= sx - 6 * S && mx <= x + w - 8 * S) {
            dragSlX = sx;
            dragSlW = sw;
            applySlider(m, pi, mx);
            dragMod = m;
            dragParam = pi;
        }
    }

    // ===== 参数值的键盘输入编辑 =====
    // 见 hitParam 里的说明: 滑块拖不精确, 这是精确那条路。
    private static Mod paramEditMd = null;
    private static int paramEditParam = -1;
    private static String paramEditBuf = "";
    /** 输入框上一帧画在哪 —— 用来判断"这次点击是不是点在输入框自己身上" */
    private static int editBoxX0, editBoxY0, editBoxX1, editBoxY1;

    private static boolean overParamEditBox(int mx, int my) {
        return mx >= editBoxX0 && mx < editBoxX1 && my >= editBoxY0 && my < editBoxY1;
    }

    private static boolean paramEditing() { return paramEditMd != null; }

    private static void paramEditCancel() {
        paramEditMd = null;
        paramEditParam = -1;
        paramEditBuf = "";
    }

    /** 提交输入框里的文本。解析不出数字就当没改 (不打扰用户, 也不写坏值)。 */
    private static void paramEditCommit() {
        if (paramEditMd == null || paramEditParam < 0) return;
        try {
            double v = Double.parseDouble(paramEditBuf.trim());
            if (!Double.isFinite(v)) throw new IllegalArgumentException("non-finite parameter");
            P pp = paramEditMd.ps[paramEditParam];
            // 夹取 + 吸附都走 P.snap, 和拖动/滚轮完全同一套 —— 三个入口的精度必须一致
            pp.v = pp.snap(v);
            log("param " + paramEditMd.name + "." + pp.n + " = " + fmtNum(pp.v) + " (输入)");
        } catch (Throwable ignored) {
            log("参数输入 \"" + paramEditBuf + "\" 不是数字, 已忽略");
        }
        paramEditCancel();
    }

    /** 返回 true = 这次按键被输入框吃掉了 */
    private static boolean paramEditKey(char typedChar, int keyCode) {
        if (!paramEditing()) return false;
        if (keyCode == 1) { paramEditCancel(); return true; }        // Esc 放弃
        if (keyCode == 28) { paramEditCommit(); return true; }        // Enter 提交
        if (keyCode == 14) {                                          // Backspace
            if (paramEditBuf.length() > 0) paramEditBuf = paramEditBuf.substring(0, paramEditBuf.length() - 1);
            return true;
        }
        // 只收数字相关的字符 —— 这是个数值框, 打字母进去没有意义
        if (typedChar >= '0' && typedChar <= '9') { paramEditBuf += typedChar; return true; }
        if ((typedChar == '.' || typedChar == '-') && !paramEditBuf.contains(String.valueOf(typedChar))) {
            paramEditBuf += typedChar;
            return true;
        }
        return true;    // 编辑中: 其余按键全部吃掉, 别让它漏到热键/搜索框
    }

    // =====================================================================
    // 配置导入 / 导出 —— 玩家之间互相传配置
    //
    // 上一版把参数名当文本传, 结果一份配置 2759 字符, 根本粘不动。
    // 名字才是大头: 247 个参数 × 每个名字二十几个字节。
    //
    // 现在改成二进制打包, 三条规则把体积压下来:
    //   1. **只传和默认值不一样的项**。绝大多数人只改二三十个参数, 剩下的不用传。
    //   2. **传索引不传名字**。1 字节模块下标 + 1 字节参数下标。
    //   3. **值量化成档位**。(v-min)/step 是整数, varint 编出来通常 1 字节,
    //      而不是 double 的 8 字节。
    //
    // 索引是脆的 —— 布局一变就整体错位。所以头部带一个**布局指纹** (所有模块名/参数名/
    // min/max/step 的校验和)。指纹对不上就拒绝导入并说明原因, 绝不会静默套错。
    // 这样既短, 又不会"看起来导入成功其实参数全乱"。
    //
    // 也不用 deflate: 打包完才一两百字节, 压了反而变大。
    // =====================================================================

    private static final String CFG_MAGIC = "AZC2";
    /**
     * 配置串内部格式版本。
     *
     * v2 -> v3: 参数量化单位从"每个参数自己的 step"改成统一的 0.01 (百分位)。
     * 编码方式变了, 同一个整数在 v2 和 v3 里意思不同 —— 所以**必须**升版本号。
     * 升了之后旧串会被 importConfig 用一句明确的话拒掉, 而不是静默解成一套完全不同的参数;
     * 后者是"看起来导入成功, 其实参数全乱", 比报错危险得多。
     *
     * v3 -> v4: 参数段增加通用控件后, 未压缩最坏配置会接近服务端上限。
     * v4 保留版本字节明文, 对其余二进制做 DEFLATE, 让大配置仍能留下服务端余量。
     */
    private static final int CFG_FMT = 4;

    /** 最近一次导出的字符数, 自检摘要里报出来 */
    private static int lastCfgLen = 0, lastCfgQuant = 0, lastCfgEmptyLen = 0, lastCfgReal = 0;

    private static byte[] packConfig(byte[] plain) throws Exception {
        if (plain == null || plain.length == 0) throw new IllegalArgumentException("empty config");
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(9, true);
        deflater.setInput(plain, 1, plain.length - 1);
        deflater.finish();
        java.io.ByteArrayOutputStream packed = new java.io.ByteArrayOutputStream(plain.length);
        packed.write(plain[0]);
        byte[] buf = new byte[256];
        while (!deflater.finished()) packed.write(buf, 0, deflater.deflate(buf));
        deflater.end();
        return packed.toByteArray();
    }

    private static byte[] unpackConfig(byte[] packed) throws Exception {
        if (packed == null || packed.length < 2 || packed.length > 65536)
            throw new java.util.zip.DataFormatException("配置长度无效");
        java.util.zip.Inflater inflater = new java.util.zip.Inflater(true);
        try {
            inflater.setInput(packed, 1, packed.length - 1);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(256);
            out.write(packed[0]);
            byte[] buf = new byte[256];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) throw new java.util.zip.DataFormatException("压缩数据不完整");
                if (out.size() + n > 65536) throw new java.util.zip.DataFormatException("配置过大");
                out.write(buf, 0, n);
            }
            if (inflater.getRemaining() != 0) throw new java.util.zip.DataFormatException("压缩数据含尾随内容");
            return out.toByteArray();
        } finally { inflater.end(); }
    }

    /** 剪贴板导入导出结果提示: 显示到什么时候 + 显示什么 */
    private static String cfgToast = "";
    private static long cfgToastUntil = 0;

    /**
     * 打异常 + 栈。只打 toString 的话 NPE 这种"没有消息"的异常根本没法查 ——
     * 这次 drawGui FAILED: NullPointerException 刷了几百行, 却完全看不出是哪儿。
     */
    static void logStack(String what, Throwable t) {
        StringBuilder sb = new StringBuilder(what).append(": ").append(t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < st.length && i < 8; i++) sb.append("\n    at ").append(st[i]);
        log(sb.toString());
    }

    /**
     * 状态反馈。
     *
     * 用户要求: "我不要下面那个反馈条" —— 所以反馈不再画成面板底部的一条横幅,
     * 而是显示在**分享/载入小窗**里 (它本来就是"和服务器打交道"的那个窗口,
     * 存/读/删的结果出现在那儿才对得上)。小窗没开时退回面板底部, 否则消息会凭空消失。
     */
    private static void setToast(String s) {
        cfgToast = s == null ? "" : s;
        cfgToastUntil = System.currentTimeMillis() + 7000;   // 7 秒够读完一行报告
        log("config: " + s);
    }

    /** 当前该显示的状态消息 (超时就没了) */
    private static String liveToast() {
        return System.currentTimeMillis() < cfgToastUntil ? cfgToast : "";
    }

    /**
     * 布局指纹: 把所有模块名、参数名和范围都揉进一个 short。
     *
     * 这是索引式编码的安全带。任何一次改动功能列表或参数定义 (改名/改范围/换顺序)
     * 都会让指纹变掉, 于是旧配置会被明确拒绝, 而不是把值套到错误的参数上。
     */
    static int layoutFingerprint() {
        return layoutFingerprint(mods.length);
    }

    /**
     * Fingerprint a stable prefix of the module table.  The eight 7.3 visual
     * widgets were appended to the table, so every older module/parameter index
     * is still valid.  Keeping the prefix fingerprint lets 7.2 profiles load
     * without weakening the index checks for either layout.
     */
    private static boolean matchesLayout(int fingerprint,int moduleLimit){
        for(int schema=0;schema<=3;schema++)for(boolean legacyCombat:new boolean[]{false,true})
            if(fingerprint==layoutFingerprint(moduleLimit,schema,false,legacyCombat)
                || fingerprint==layoutFingerprint(moduleLimit,schema,true,legacyCombat)
                || fingerprint==layoutFingerprint(moduleLimit,schema,false,legacyCombat,true)
                || fingerprint==layoutFingerprint(moduleLimit,schema,true,legacyCombat,true))return true;
        return false;
    }
    private static boolean currentHudLayout(int fingerprint,int moduleLimit){
        for(boolean legacyCombat:new boolean[]{false,true})
            if(fingerprint==layoutFingerprint(moduleLimit,0,false,legacyCombat)
                ||fingerprint==layoutFingerprint(moduleLimit,0,true,legacyCombat)
                ||fingerprint==layoutFingerprint(moduleLimit,0,false,legacyCombat,true)
                ||fingerprint==layoutFingerprint(moduleLimit,0,true,legacyCombat,true))return true;
        return false;
    }
    private static int layoutFingerprint(int moduleLimit){return layoutFingerprint(moduleLimit,0);}
    private static int layoutFingerprint(int moduleLimit,int schema) {
        return layoutFingerprint(moduleLimit,schema,false);
    }
    private static int layoutFingerprint(int moduleLimit,int schema,boolean legacyInvMove) {
        return layoutFingerprint(moduleLimit,schema,legacyInvMove,false);
    }
    private static int layoutFingerprint(int moduleLimit,int schema,boolean legacyInvMove,boolean legacyCombat) {
        return layoutFingerprint(moduleLimit,schema,legacyInvMove,legacyCombat,false);
    }
    private static int layoutFingerprint(int moduleLimit,int schema,boolean legacyInvMove,boolean legacyCombat,boolean legacyBlockMode) {
        int h = 17;
        int limit = Math.max(0, Math.min(moduleLimit, mods.length));
        for (int moduleIndex = 0; moduleIndex < limit; moduleIndex++) {
            Mod m = mods[moduleIndex];
            h = h * 31 + m.name.hashCode();
            h = h * 31 + m.cat;
            h = h * 31 + (m.tag == null ? 0 : m.tag.hashCode());
            for (P pp : m.ps) {
                if((legacyCombat||legacyBlockMode) && moduleIndex==F_KILLAURA && pp.n.equals("Block Mode"))continue;
                if(legacyCombat && moduleIndex==F_KILLAURA && (pp.n.equals("AutoBlock")||pp.n.equals("Block Hold (ticks)")))continue;
                if(schema>=1 && moduleIndex==F_HUD && (pp.n.equals("Style")||pp.n.equals("Mode Suffix")))continue;
                if(schema>=2 && (pp.n.equals("Glow Strength")||pp.n.equals("Notice Glow")))continue;
                if(schema>=3 && (pp.n.equals("Background Opacity")||pp.n.equals("Notice Opacity")))continue;
                h = h * 31 + pp.n.hashCode();
                h = h * 31 + (int) Math.round(pp.min * 1000);
                boolean retiredInput=legacyInvMove && moduleIndex==F_INVMOVE && (pp.n.equals("Sneak")||pp.n.equals("Sprint"));
                h = h * 31 + (int) Math.round((retiredInput?1:pp.max) * 1000);
                h = h * 31 + (int) Math.round(pp.step * 1000);
                // 7.3 changed only the runtime defaults of Scaffold Delay and
                // Sneak.  Keep their schema defaults in the fingerprint so an
                // existing AZC2 auto-save can be imported and migrated instead
                // of being rejected before migration gets a chance to run.
                double schemaDef = pp.def;
                if(retiredInput)schemaDef=1;
                if(schema>=1 && moduleIndex==F_HUD && pp.n.equals("X %"))schemaDef=1;
                if(schema>=1 && moduleIndex==F_HUD && pp.n.equals("Y %"))schemaDef=20;
                if ("Scaffold".equals(m.name) && "Delay (ms)".equals(pp.n)) schemaDef = 50.0;
                if ("Scaffold".equals(m.name) && "Sneak".equals(pp.n)) schemaDef = 2.0;
                h = h * 31 + (int) Math.round(schemaDef * 1000);
            }
        }
        return (h ^ (h >>> 16)) & 0xFFFF;
    }

    /** 当前玩家名, 拿不到就写 unknown —— 只给收配置的人看来源, 不参与任何校验 */
    private static String localPlayerName() {
        try {
            if (mGetMC == null || mGetName == null) return "unknown";
            Object mc = mGetMC.invoke(null);
            Object p = fPlayer.get(mc);
            if (p == null) return "unknown";
            Object n = mGetName.invoke(p);
            return n == null ? "unknown" : n.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 把所有开关、热键、参数还原成出厂值 */
    static void resetToDefaults() {
        for (Mod m : mods) {
            m.on = false;
            m.bindKey = 0;
            for (P pp : m.ps) pp.v = pp.def;
        }
        antiCheat = -1;
    }

    static String exportConfig() throws Exception {
        // 刻意不调 ensure(): 导出只读 mods, 不需要游戏上下文。
        // 调了的话离线自检就做不了往返验证 —— 而往返验证是这个功能唯一的硬保证。
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream(256);
        o.write(CFG_FMT);
        int fp = layoutFingerprint();
        o.write(fp & 0xFF);
        o.write((fp >>> 8) & 0xFF);
        o.write(antiCheat + 1);                 // -1(OFF) 存成 0

        // 模块: 只传"开着"或"绑了键"的
        java.io.ByteArrayOutputStream mb = new java.io.ByteArrayOutputStream(64);
        int mCount = 0;
        for (int i = 0; i < mods.length; i++) {
            Mod m = mods[i];
            if (!m.on && m.bindKey == 0) continue;
            mb.write(i);
            mb.write(m.on ? 1 : 0);
            mb.write(m.bindKey & 0xFF);
            mCount++;
        }
        o.write(mCount);
        o.write(mb.toByteArray(), 0, mb.size());

        // 参数: 只传和默认值不一样的
        java.io.ByteArrayOutputStream pb = new java.io.ByteArrayOutputStream(256);
        int pCount = 0;
        for (int i = 0; i < mods.length; i++) {
            Mod m = mods[i];
            for (int j = 0; j < m.ps.length; j++) {
                P pp = m.ps[j];
                if (Math.abs(pp.v - pp.def) < 1e-9) continue;
                pb.write(i);
                pb.write(j);
                writeVarint(pb, Math.max(0, pp.quant()));
                pCount++;
            }
        }
        writeVarint(o, pCount);
        o.write(pb.toByteArray(), 0, pb.size());

        return CFG_MAGIC + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(packConfig(o.toByteArray()));
    }

    /**
     * 导入配置, 返回一份"到底改了什么"的报告。
     *
     * 会先重置成出厂默认再套用 —— 因为导出的只是差异, 不重置的话你自己的残留设置会混进来,
     * 结果既不像你的也不像对方的。报告里说明了这一点。
     */
    static String importConfig(String text) throws Exception {
        if (text == null || text.trim().length() == 0) return "剪贴板是空的";
        if (text.length() > 90000) return "配置解码失败 (data too large)";
        text = text.trim();
        // 允许前后有别的字符 (比如从聊天记录里连名字一起复制过来)
        int at = text.indexOf(CFG_MAGIC);
        if (at < 0) {
            return "不是 AZclient 配置 (找不到 " + CFG_MAGIC + " 开头)";
        }
        byte[] b;
        try {
            b = java.util.Base64.getUrlDecoder().decode(text.substring(at + CFG_MAGIC.length()).trim());
        } catch (Throwable t) {
            return "配置解码失败 (可能粘贴时被截断): " + t;
        }
        if (b.length < 2) return "配置太短, 已经损坏";
        if ((b[0] & 0xFF) != CFG_FMT) return "配置格式版本不对: " + (b[0] & 0xFF) + " (本客户端是 " + CFG_FMT + ")";

        // v4 的版本字节明文, 后面的布局/模块/参数段是 raw DEFLATE。
        try {
            b = unpackConfig(b);
        } catch (Throwable t) {
            return "配置解压失败 (可能粘贴时被截断): " + t;
        }
        if (b.length < 6) return "配置太短, 已经损坏";

        int fp = (b[1] & 0xFF) | ((b[2] & 0xFF) << 8);
        int mine = layoutFingerprint();
        boolean legacyLayout = matchesLayout(fp,F_KEYSTROKES);
        boolean previousLayout = matchesLayout(fp,mods.length-1) || matchesLayout(fp,F_INVMANAGER);
        if (!matchesLayout(fp,mods.length) && !legacyLayout && !previousLayout) {
            return "布局指纹不匹配 (" + fp + " vs " + mine + ") —— 这份配置来自参数定义不同的版本, "
                    + "强行套用会让参数对错位置, 所以拒绝了";
        }

        int preset = (b[3] & 0xFF) - 1;
        int[] pos = {4};

        // Parse and validate an isolated snapshot before changing live state.
        boolean[] nextOn = new boolean[mods.length];
        if (!matchesLayout(fp,mods.length)) nextOn[F_TARGETHUD]=true;
        int[] nextBind = new int[mods.length];
        double[][] nextValues = new double[mods.length][];
        for (int mi = 0; mi < mods.length; mi++) {
            nextValues[mi] = new double[mods[mi].ps.length];
            for (int pi = 0; pi < nextValues[mi].length; pi++)
                nextValues[mi][pi] = mods[mi].ps[pi].def;
        }
        if (preset < -1 || preset >= AC_NAMES.length) return "配置预设下标无效";
        try {

        int mCount = b[pos[0]++] & 0xFF;
        int toggled = 0, bindSet = 0;
        for (int i = 0; i < mCount; i++) {
            if (pos[0] + 3 > b.length) return "配置在模块段被截断";
            int mi = b[pos[0]++] & 0xFF;
            boolean on = (b[pos[0]++] & 0xFF) != 0;
            int bind = b[pos[0]++] & 0xFF;
            if (mi >= mods.length) return "配置引用了不存在的功能下标 " + mi;
            Mod m = mods[mi];
            // 未实现的功能不许被配置点亮 —— 否则别人发一份配置过来你界面上就亮起假功能
            if (on && m.tag != null) continue;
            if (on) toggled++;
            nextOn[mi] = on;
            if (bind != 0) { nextBind[mi] = bind; bindSet++; }
        }

        int pCount = readVarint(b, pos);
        int applied = 0, clamped = 0;
        for (int i = 0; i < pCount; i++) {
            if (pos[0] + 2 > b.length) return "配置在参数段被截断";
            int mi = b[pos[0]++] & 0xFF;
            int pi = b[pos[0]++] & 0xFF;
            int q = readVarint(b, pos);
            if (mi >= mods.length || pi >= mods[mi].ps.length) return "配置引用了不存在的参数下标";
            P pp = mods[mi].ps[pi];
            int qm = pp.quantMax();
            if (q > qm) { q = qm; clamped++; }
            nextValues[mi][pi] = pp.snap(pp.min + q * P.Q);
            applied++;
        }

        // 7.2's Scaffold default and saved value could enable automatic
        // sneaking.  The selected 7.3 behavior is a hard migration to Off;
        // model/network bridge pose remains independent from this parameter.
        if (legacyLayout) nextValues[F_SCAFFOLD][4] = 0.0;
        // A pre-Veil profile has no selected style or Veil anchor. Migrate once, preserving all other values.
        if(!currentHudLayout(fp,mods.length)&&!currentHudLayout(fp,F_KEYSTROKES)&&!currentHudLayout(fp,mods.length-1)&&!currentHudLayout(fp,F_INVMANAGER)){nextValues[F_HUD][0]=100;nextValues[F_HUD][1]=0;}
        if (pos[0] != b.length) return "配置包含多余数据";
        for (int mi = 0; mi < mods.length; mi++) {
            mods[mi].on = nextOn[mi];
            mods[mi].bindKey = nextBind[mi];
            for (int pi = 0; pi < mods[mi].ps.length; pi++) mods[mi].ps[pi].v = nextValues[mi][pi];
        }

        if (preset >= -1 && preset < AC_NAMES.length) antiCheat = preset;

        StringBuilder r = new StringBuilder();
        r.append("导入完成: 已重置为默认, 套用 ").append(applied).append(" 项参数, ")
                .append(toggled).append(" 个功能开启, ").append(bindSet).append(" 个热键");
        if (clamped > 0) r.append(", ").append(clamped).append(" 项被夹到范围内");
        r.append("  [").append(b.length).append(" 字节]");
        return r.toString();
        } catch (IllegalArgumentException malformed) {
            return "配置数据损坏: " + malformed.getMessage();
        }
    }

    // ---------------------------------------------------------------------
    // 配置分享的联网部分
    //
    // 短码是唯一能短到"一串字符"的方案。一份真实配置 (12 开关 + 30 参数) 至少 126 字节,
    // base64 之后 170 个字符打底 —— 二三十个字符装不下, 这是信息量决定的, 换什么编码都一样。
    // 所以短码只能是"去服务器取"的编号, 配置本身存在 azclient-auth 的 configs 表里。
    //
    // 联网一律走后台线程。在渲染线程里做 HTTP 会把游戏卡住, 断网时甚至卡到超时为止。
    // 下载回来的配置不直接在后台线程套用 —— 那会和渲染线程抢 mods。
    // 先放进 pendingImport, 由下一帧的 onFrame 在游戏线程上应用。
    // ---------------------------------------------------------------------

    /** 后台线程下载完的配置, 等 onFrame 在游戏线程上套用 */
    private static volatile String pendingImport = null;
    /**
     * 后台线程取回来、等着塞进剪贴板的配置串。
     *
     * 和 pendingImport 同一个道理: 剪贴板是主线程的东西, 不能在下载线程里碰。
     * "把自己某条存档复制出来发给别人"走这条路 —— 服务端只给 config_load, 拿到手之后
     * 放剪贴板即可, 不需要先导入再导出。
     */
    private static volatile String pendingCopy = null;

    /** 短码字母表, 必须和服务端 CODE_ALPHABET 一致 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 6;

    private static String authUrlCache = null;
    private static long authUrlAt = 0;

    /**
     * 读 azclient-auth.txt 里的 url=...
     * 查找顺序和注入器的 LoginFrame 保持一致, 否则会出现"登录能用但分享不能用"。
     */
    /**
     * 认证服务器地址。
     *
     * 为什么要有这个编译期默认值: session.properties 可能是旧版注入器写的 (那时候还没写
     * url 这一行), 而自动登录路径不会重写它。agent 这时候就只能去 home/tmpdir/cwd 搜
     * azclient-auth.txt —— 但那个文件在 exe 同目录, agent 跑在游戏 JVM 里根本不知道在哪。
     * 结果就是"登录能用, 但分享配置报找不到 azclient-auth.txt"。
     *
     * 优先级仍然是 文件 > 默认值, 所以服务器换地址时改 azclient-auth.txt 依然生效。
     */
    static final String DEFAULT_AUTH_URL = "";

    /** The open-source edition has no remote auth or config endpoint. */
    private static String authUrl() { return ""; }

    /** 读 ~/.azclient/session.properties 里的 user / token (注入器登录时写的) */
    private static String[] loadSession() {
        try {
            java.io.File f = new java.io.File(new java.io.File(System.getProperty("user.home"), ".azclient"),
                    "session.properties");
            if (!f.isFile()) return null;
            java.util.Properties p = new java.util.Properties();
            java.io.InputStream in = new java.io.FileInputStream(f);
            p.load(in);
            in.close();
            String u = p.getProperty("user", "").trim().toLowerCase();
            String t = p.getProperty("token", "").trim().toLowerCase();
            if (u.length() == 0 || !t.matches("[0-9a-f]{64}")) return null;
            return new String[]{u, t, p.getProperty("url", "").trim()};
        } catch (Throwable t) {
            return null;
        }
    }

    private static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    static String httpPost(String url, String body) throws Exception {
        throw new java.io.IOException("Offline/open-source edition has no network transport");
    }

    // =====================================================================
    // 授权校验 (agent 启动时跑一次)
    // =====================================================================
    //
    // 为什么要有这个: injector 那层登录是**纯装饰** —— doInject() 里没有任何认证
    // 检查, 而且 Core.inject 是包级静态方法, 反射一行就能调。更彻底的是把本 jar
    // 抽出来自己 attach: 三行代码, 完全绕过 exe。
    //
    // 拦住那条路的唯一位置就在这里 —— **jar 自己不肯干活**。
    //
    // 票据格式 (服务端 sign 出来的): <过期时间戳>.<机器码>.<签名>
    //   签名 = ECDSA-P256-SHA256(服务端私钥, 过期时间戳 + '|' + 机器码 + '|' + 用户名)
    //
    // 这里内嵌的是**公钥**, 不是密钥。这个区别是整个方案的关键:
    // 以前用 HMAC (对称签名), 验签要和服务端同一把密钥, 而验签跑在客户机器上,
    // 所以那把密钥只能躺在 jar 里 —— 谁 strings 一下谁就能给任意机器码/任意用户名
    // 签票据。现在私钥**只在服务器上**, 从公钥反推不出私钥, 所以抠出这串 Base64
    // 什么都做不了, 造不出票据。
    //
    // 换不来什么也必须说明白: 这挡不住"把下面那句 if 改成恒假"。任何跑在客户机器上的
    // 判断都能被 patch, 跟用什么算法无关。它解决的是**一把密钥泄露 = 无限伪造授权**
    // 这件事 —— 把爆炸半径从"一个人泄露, 所有人受益"收回到"每人得自己 patch 自己那份"。
    //
    // 轮换: 换密钥对要**同时**改服务端 ticket-key.pem 和这里这串 Base64, 然后重编客户端。

    /**
     * 服务端票据公钥 (P-256, SPKI DER 的 Base64)。
     * 用 {@code openssl ec -pubout} 或 node 的 publicKey.export({type:'spki',format:'der'}) 得到。
     */
    private static final String TICKET_PUBLIC_KEY_B64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAELBkaGH1dI0IXKh/xjWHmlEV/6nrifrKlOa9NJ1iQ++0Man9EbyK3IPECEFXxfaKT923H2QqySSLwtdwV5LX7qQ==";

    /** 解析过的公钥, 懒加载一次 (agent 只验一次票, 但别每帧都解析) */
    private static volatile java.security.PublicKey ticketPub = null;

    /** 授权状态: null=还没查, TRUE/FALSE=查过了 */
    private static volatile Boolean licensed = null;

    /** 上一次的失败原因, 只用于日志 */
    private static volatile String licenseReason = "";

    static boolean isLicensed() { return Boolean.TRUE.equals(licensed); }
    static String  licenseReason() { return licenseReason; }

    /**
     * 向服务端换取授权票据并验签。**只在 agentmain 里调一次。**
     *
     * 失败一律返回 false —— 失败关闭, 没有任何例外分支。
     * (以前这里写着"唯一例外见下面 skipNetwork 那段注释", 但那段代码从来没存在过,
     *  是条过期的注释。留着比没有更危险: 读代码的人会以为有个网开一面的口子。)
     */
    static boolean checkLicense() {
        licensed = Boolean.TRUE;
        licenseReason = "";
        log("offline/open-source edition: local access enabled");
        return true;
    }

    /** 本机机器码, 算法和 injector 的 Core.machineCode() **必须一致**。 */
    static String machineCodeAgent() {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid");
            pb.redirectErrorStream(true);
            Process pr = pb.start();
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(256);
            java.io.InputStream in = pr.getInputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            pr.waitFor();
            // 输出里有本地化文本, 不解析它 —— 直接按 GUID 的形状抓
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                    .matcher(new String(bo.toByteArray(), "ISO-8859-1"));
            if (!m.find()) return null;
            String h = sha256Hex("AZclient/1|" + m.group()).toUpperCase(java.util.Locale.ROOT);
            return h.substring(0, 4) + "-" + h.substring(4, 8)
                    + "-" + h.substring(8, 12) + "-" + h.substring(12, 16);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 用内嵌公钥验票据签名。
     *
     * ECDSA 签名是 DER 编码的, 长度会随两个整数的高位零变化 (70~72 字节),
     * 所以**只能按 DER 解, 不能按固定长度切** —— 用 java.security.Signature
     * 而不是手搓 ASN.1, 就是为了不碰这个坑。
     *
     * 任何异常一律 false (失败关闭)。这里绝不能有"出错就放行"的分支 ——
     * 那样报错本身就变成了绕过手段。
     */
    private static boolean verifyTicketSig(String data, String sigHex) {
        try {
            byte[] sig = hexToBytes(sigHex);
            if (sig == null || sig.length == 0) return false;
            java.security.PublicKey pub = ticketPub;
            if (pub == null) {
                byte[] der = java.util.Base64.getDecoder().decode(TICKET_PUBLIC_KEY_B64);
                pub = java.security.KeyFactory.getInstance("EC")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(der));
                ticketPub = pub;
            }
            java.security.Signature s = java.security.Signature.getInstance("SHA256withECDSA");
            s.initVerify(pub);
            s.update(data.getBytes("UTF-8"));
            return s.verify(sig);
        } catch (Throwable t) {
            log("verifyTicketSig 异常: " + t);
            return false;
        }
    }

    /** 十六进制字符串转字节。奇数长度或含非法字符返回 null (调用方当验签失败处理)。 */
    private static byte[] hexToBytes(String h) {
        if (h == null) return null;
        String s = h.trim();
        if (s.length() == 0 || (s.length() & 1) != 0) return null;
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) return null;
            b[i] = (byte) ((hi << 4) | lo);
        }
        return b;
    }

    static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 这串东西长得像分享码吗 (用来区分"码"和"离线配置串") */
    private static boolean isShareCode(String s) {        if (s == null || s.length() != CODE_LEN) return false;
        for (int i = 0; i < CODE_LEN; i++) {
            if (CODE_ALPHABET.indexOf(Character.toUpperCase(s.charAt(i))) < 0) return false;
        }
        return true;
    }

    /** 上传配置换短码, 码直接进剪贴板 */
    /**
     * CONFIG 列上那个 Share 按钮 —— 点一下就把当前配置变成分享码并复制到剪贴板。
     *
     * <p>用户要求: "分享配置时, 点击 share, 自动生成分享码并复制"。
     *
     * <p>它和 {@link #shareConfigOnline()} 是**同一件事** —— 上传、拿码、写剪贴板
     * 那条路老早就写好了, 缺的只是"一次点击"这个入口。旧版要先用 "Upload mine"
     * 生成码、再点 "Copy code" 复制, 两步。这里只做转发, 不复制一份实现:
     * 复制一份的话, 以后改错误提示就会有一边漏改。
     */
    static void shareCurrentConfig() {
        exportConfigToClipboard();
    }

    static void shareConfigOnline() {
        final String cfg;        try {
            cfg = exportConfig();
        } catch (Throwable t) {
            setToast("导出失败: " + t);
            return;
        }
        final String url = authUrl();
        if (url == null) {
            setToast("找不到 azclient-auth.txt, 无法上传 (可以用 Export Config 走离线串)");
            return;
        }
        final String[] ses = loadSession();
        if (ses == null) {
            setToast("没有登录令牌, 重新登录一次再上传");
            return;
        }
        setToast("正在上传 " + cfg.length() + " 字符...");
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_put&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]) + "&name=" + urlEnc("AZclient")
                            + "&data=" + urlEnc(cfg));
                    if (r.startsWith("OK ")) {
                        String code = r.substring(3).trim();
                        shareLastCode = code;          // 显示在小块里, 不用再去剪贴板捞
                        try {
                            setClipboard(code);
                            setToast("分享码 " + code + " (已复制)");
                        } catch (Throwable t2) {
                            setToast("分享码是 " + code);
                        }
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                    } else if (r.equals("ERR:ratelimit")) {
                        setToast("操作太快, 等一分钟再试");
                    } else {
                        setToast("上传失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("上传失败 (网络不通?): " + t);
                }
            }
        }, "azclient-config-put");
        th.setDaemon(true);
        th.start();
    }

    // =====================================================================
    // 账号配置: 存到自己账号上, 在 GUI 里直接换
    //
    // 和"在线分享"是两回事 —— 分享码是给别人的, 拿到码就能取, 不需要登录;
    // 这里是自己的存档, 要令牌才能读写, 列表也只有自己能看。
    // 复用同一个 configs 表: owner 就是账号名, 按 (owner, name) 覆盖, 短码保持不变。
    // =====================================================================

    private static boolean cfgListOpen = true;   // 列表常驻; 保留字段是为了老配置兼容，不再切换
    /** 后台线程只写这个 volatile, 解析放到 onFrame 的游戏线程上做 */
    private static volatile String cfgListRaw;
    /** 非空 = 正在忙, 作为列表顶上那一行显示 */
    private static volatile String cfgBusy;
    private static String[] cfgNames = new String[0];
    private static final int CFG_LIST_MAX = 20;
    /** 存配置用的名字 —— 面板上那个独立输入栏的内容 (见 cfgSaveName) */
    private static String cfgName = "";
    private static boolean cfgNameFocused = false;
    /** 列表是否已经自动拉过一次 —— 见 openShare 里的说明 */
    private static boolean cfgListFetched = false;

    /**
     * 存配置时用的名字。
     *
     * 用户要求: "存配置时有一个独立的命名输入栏" —— 所以名字来自面板上那个专用输入框,
     * **不再读搜索框** (以前读 searchQuery, 于是存名字取决于你搜索框里最后打了什么)。
     * 空着就用时间戳, 保证任何时候点 Save 都能存下来。
     *
     * <p>尾部必须把**制表符和换行**剔掉: 服务端 config_mine 的返回是
     * <pre>code\tname\tdownloads\tcreated_at</pre>
     * 每行一条 —— 名字里混进 \t 或 \n 会让那个格式错位, 列表直接解析不出来。
     * 服务端也会剔 (见 server.js 的 config_save), 但客户端先挡一层:
     * 靠服务端兜底的话, 存的名字和界面上看到的会悄悄不一样。
     */
    private static String cfgSaveName() {
        String s = cfgName == null ? "" : cfgName.trim();
        // 控制字符 (制表符/换行/回车) 一律换成空格, 而不是"截断" —— 截断会让
        // "a\tb" 变成 "a", 用户以为自己输的还在。
        s = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > 0) return s.length() > 24 ? s.substring(0, 24) : s;
        return "配置 " + new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.ROOT)
                .format(new java.util.Date());
    }

    /** 把当前配置存到自己账号上 (同名覆盖) */
    static void saveConfigToAccount() {
        final String cfg;
        try {
            cfg = exportConfig();
        } catch (Throwable t) {
            setToast("导出失败: " + t);
            return;
        }
        final String url = authUrl();
        if (url == null) {
            setToast("找不到服务器地址, 用 Export Config 走离线串");
            return;
        }
        final String[] ses = loadSession();
        if (ses == null) {
            setToast("没有登录令牌, 重新登录一次再存");
            return;
        }
        final String name = cfgSaveName();
        cfgBusy = "正在保存 " + name + " ...";
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_save&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]) + "&name=" + urlEnc(name)
                            + "&data=" + urlEnc(cfg));
                    if (r.startsWith("OK")) {
                        setToast("已存到账号: " + name + ", 点 My Configs 查看");
                        fetchCfgList();
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                    } else {
                        setToast("保存失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("保存失败 (网络不通?): " + t);
                } finally {
                    cfgBusy = null;
                }
            }
        }, "azclient-cfg-save");
        th.setDaemon(true);
        th.start();
    }

    /** 拉自己账号下的配置列表 */
    static void fetchCfgList() {
        final String url = authUrl();
        final String[] ses = loadSession();
        if (url == null || ses == null) {
            setToast("没有登录令牌, 重新登录一次再看列表");
            return;
        }
        cfgBusy = "正在读取列表...";
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_mine&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]));
                    if (r.startsWith("OK")) {
                        // OK 后面直接跟换行, 不走 "OK " 带空格那套 —— 空列表时也不会歧义
                        String body = r.length() > 2 ? r.substring(2) : "";
                        cfgListRaw = body.startsWith("\n") ? body.substring(1) : body.trim();
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                        cfgListRaw = "";
                    } else {
                        setToast("读取列表失败: " + r);
                        cfgListRaw = "";
                    }
                } catch (Throwable t) {
                    setToast("读取列表失败 (网络不通?): " + t);
                    cfgListRaw = "";
                } finally {
                    cfgBusy = null;
                }
            }
        }, "azclient-cfg-list");
        th.setDaemon(true);
        th.start();
    }

    /** onFrame 调用: 把后台线程拿到的列表搬到游戏线程上 */
    private static void applyCfgList() {
        String raw = cfgListRaw;
        if (raw == null) return;
        cfgListRaw = null;
        java.util.ArrayList<String> n = new java.util.ArrayList<String>();
        for (String line : raw.split("\n")) {
            if (line.length() == 0) continue;
            // 服务端 config_mine 的字段序: code \t name \t downloads \t created_at
            // 短码不存 —— 读存档走 config_load (要令牌), 客户端不需要知道自己的短码
            String[] f = line.split("\t");
            if (f.length < 2 || f[1].length() == 0) continue;
            n.add(f[1]);
            if (n.size() >= CFG_LIST_MAX) break;
        }
        cfgNames = n.toArray(new String[0]);
    }

    /**
     * 读自己账号下的一条存档。
     *
     * 走 config_load (要令牌), **不走** fetchConfigByCode —— 那条是没有鉴权的短码通路,
     * 设计上是给"把配置发给别人"用的。私有存档借用它, 隔离就只剩一个 6 位短码,
     * 那是防手抄看错的强度, 不是防别人翻你配置的强度。
     */
    private static void loadSavedConfig(final String name) {
        final String url = authUrl();
        final String[] ses = loadSession();
        if (url == null || ses == null) {
            setToast("没有登录令牌, 重新登录一次再读");
            return;
        }
        setToast("正在读取 " + name + " ...");
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_load&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]) + "&name=" + urlEnc(name));
                    if (r.startsWith("OK ")) {
                        pendingImport = r.substring(3).trim();   // 同样交给 onFrame 在游戏线程套用
                    } else if (r.equals("NOCODE")) {
                        setToast("这条存档已经不在了, 重新展开一次列表");
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                    } else {
                        setToast("读取失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("读取失败 (网络不通?): " + t);
                }
            }
        }, "azclient-cfg-load");
        th.setDaemon(true);
        th.start();
    }

    /**
     * 把某一条存档复制到剪贴板 (发给人用)。
     *
     * 和 loadSavedConfig 走同一个接口, 区别只是拿到之后**不套用**, 而是放剪贴板。
     * 以前发配置只有一条路: 打开分享小块 -> 上传当前配置 -> 记住 6 位码 -> 点一下复制。
     * 想发的是"我以前存的那套"就更绕 —— 得先读回来覆盖当前设置, 再上传。
     * 现在列表里每条右边一个"复制", 一步到位。
     */
    private static void shareSavedConfig(final String name) {
        final String url = authUrl();
        final String[] ses = loadSession();
        if (url == null || ses == null) {
            setToast("没有登录令牌, 重新登录一次再复制");
            return;
        }
        setToast("正在取出 " + name + " ...");
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_load&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]) + "&name=" + urlEnc(name));
                    if (r.startsWith("OK ")) {
                        pendingCopy = r.substring(3).trim();   // 由 onFrame 在游戏线程塞剪贴板
                    } else if (r.equals("NOCODE")) {
                        setToast("这条存档已经不在了, 刷新一下列表");
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                    } else {
                        setToast("取出失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("取出失败 (网络不通?): " + t);
                }
            }
        }, "azclient-cfg-copy");
        th.setDaemon(true);
        th.start();
    }

    private static void deleteSavedConfig(final String name) {
        final String url = authUrl();
        final String[] ses = loadSession();
        if (url == null || ses == null) {
            setToast("没有登录令牌, 重新登录一次再删");
            return;
        }
        setToast("正在删除 " + name + " ...");
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_del&user=" + urlEnc(ses[0])
                            + "&token=" + urlEnc(ses[1]) + "&name=" + urlEnc(name));
                    if (r.startsWith("OK")) {
                        setToast("已删除 " + name);
                        fetchCfgList();
                    } else if (r.equals("BADTOKEN")) {
                        setToast("登录令牌已失效, 重新登录一次");
                    } else {
                        setToast("删除失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("删除失败 (网络不通?): " + t);
                }
            }
        }, "azclient-cfg-del");
        th.setDaemon(true);
        th.start();
    }

    /** 按短码下载配置, 结果交给 onFrame 在游戏线程上套用 */
    private static void fetchConfigByCode(final String code) {        final String url = authUrl();
        if (url == null) {
            setToast("找不到 azclient-auth.txt, 无法下载");
            return;
        }
        setToast("正在下载 " + code + " ...");
        Thread th = new Thread(new Runnable() {
            public void run() {
                try {
                    String r = httpPost(url, "action=config_get&code=" + urlEnc(code));
                    if (r.startsWith("OK ")) {
                        pendingImport = r.substring(3).trim();
                    } else if (r.equals("NOCODE")) {
                        setToast("分享码 " + code + " 不存在, 检查一下有没有抄错");
                    } else if (r.equals("ERR:ratelimit")) {
                        setToast("操作太快, 等一分钟再试");
                    } else {
                        setToast("下载失败: " + r);
                    }
                } catch (Throwable t) {
                    setToast("下载失败 (网络不通?): " + t);
                }
            }
        }, "azclient-config-get");
        th.setDaemon(true);
        th.start();
    }
    /** 导出到剪贴板 */
    static void exportConfigToClipboard() {
        try {
            String cfg = exportConfig();
            setClipboard(cfg);
            setToast("已复制 " + cfg.length() + " 字符, 粘贴给对方即可");
        } catch (Throwable t) {
            setToast("导出失败: " + t);
        }
    }

    // ---------------------------------------------------------------------
    // 实时配置: 上次退出时的状态
    //
    // 用户不用点"保存"。改了任何东西 (开功能、拖参数、绑热键、换目标档) 之后过两秒自动落盘,
    // 下次进游戏自动恢复 —— 也就是"上次退出时是什么样, 这次进来就是什么样"。
    //
    // 实现上用 exportConfig() 的输出当指纹, 而不是去每个修改点挂一个 dirty 标记:
    // 那份串只包含"和默认不一样"的项, 且是确定性的 (同样的状态必然同一个串), 所以
    // 每秒比一次字符串就等于覆盖了所有改动来源 —— 包括目标档、以后新加的参数,
    // 一处漏挂就永远不会丢。
    //
    // 按版本分文件: 1.8.9 上有些功能会被 Ver 过滤掉, 布局指纹对不上, importConfig 会直接
    // 拒绝。混在一个文件里就会出现"换个版本进游戏恢复失败"。
    // ---------------------------------------------------------------------

    /** 上次写进文件的内容。一样就说明没改动, 不碰磁盘。 */
    private static String autoCfgLast = null;
    private static boolean autoCfgLoaded = false;
    private static long autoCfgAt = 0L;

    private static java.io.File autoCfgPath() {
        String v = Ver.name();
        if (v == null || v.length() == 0) v = "x";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return new java.io.File(new java.io.File(System.getProperty("user.home", "."), ".azclient"),
                "last-" + sb + ".cfg");
    }

    /** One-time 7.3 migration: old profiles defaulted Scaffold.Sneak to Edge.
     * Preserve the rest of the profile, turn only that parameter off, and mark
     * the migration so a later user choice of Always/Edge remains respected. */
    private static void migrateScaffoldNoAutoSneak(java.io.File configFile) throws Exception {
        java.io.File dir = configFile == null ? null : configFile.getParentFile();
        if (dir == null) return;
        java.io.File marker = new java.io.File(dir, "migrated-scaffold-no-auto-sneak-7.3");
        if (marker.isFile()) return;
        setByName("Scaffold", "Sneak", 0);
        String migrated = exportConfig();
        if (!dir.isDirectory()) dir.mkdirs();
        AtomicFiles.writeUtf8(configFile, migrated, true);
        AtomicFiles.writeUtf8(marker, "Scaffold.Sneak=Off\n", false);
        autoCfgLast = migrated;
        log("实时配置: 已迁移 Scaffold 自动潜行为 Off");
    }

    /** 进游戏后第一次 onFrame 调: 有文件就套上, 安静地把上次的状态恢复回来。 */
    private static void loadAutoConfig() {
        java.io.File f = autoCfgPath();
        try {
            if (!f.isFile()) { autoCfgLast = null; return; }
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), "UTF-8"));
            try {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            } finally {
                br.close();
            }
            String s = sb.toString().trim();
            if (s.length() == 0) { autoCfgLast = null; return; }
            String r = importConfig(s);
            if (!r.startsWith("导入完成:")) {
                // Keep the rejected bytes for recovery; the next save is atomic.
                java.nio.file.Files.copy(f.toPath(), new java.io.File(f.getPath() + ".rejected").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                autoCfgLast = exportConfig();
                log("实时配置: 未恢复，保留原设置 —— " + r);
                setToast("配置恢复失败，已保留原设置");
                return;
            }
            autoCfgLast = s;      // 先记下, 免得刚恢复完立刻又被自己写一遍
            migrateScaffoldNoAutoSneak(f);
            log("实时配置: 恢复上次退出时的设置 —— " + r);
            setToast("已恢复上次退出时的设置");
        } catch (Throwable t) {
            // 文件坏了 / 指纹对不上都不该拦着游戏。删掉它, 下次是一个干净的默认状态。
            autoCfgLast = null;
            log("实时配置: 恢复失败, 忽略该文件 —— " + t);
        }
    }

    /** 每两秒看一眼有没有改动, 变了才落盘。IO 在渲染线程上, 所以要小且少。 */
    private static void autoSaveConfigTick() {
        long now = System.currentTimeMillis();
        if (now - autoCfgAt < 2000L) return;
        autoCfgAt = now;
        try {
            String s = exportConfig();
            if (s.equals(autoCfgLast)) return;
            java.io.File f = autoCfgPath();
            java.io.File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            AtomicFiles.writeUtf8(f, s, true);
            autoCfgLast = s;
        } catch (Throwable t) {
            logOnce("auto cfg save", t);   // 目录只读之类, 记一次就够, 不影响游戏
        }
    }

    /**
     * 从剪贴板导入。剪贴板里是 6 位分享码就去服务器取, 是 AZC2... 离线串就直接解。
     * 一个按钮管两条路, 用户不用先想清楚自己手上是哪种。
     */
    static void importConfigFromClipboard() {
        String text;
        try {
            text = getClipboard();
        } catch (Throwable t) {
            setToast("读剪贴板失败: " + t);
            return;
        }
        text = text == null ? "" : text.trim();
        // AZC2 开头的一律当离线串 —— 虽然理论上 "AZC2AB" 六个字符也全在分享码字母表里,
        // 但离线串最短也有 12 字符, 这个判断把可能性彻底堵死。
        if (!text.startsWith(CFG_MAGIC) && isShareCode(text)) {
            shareLastCode = text.toUpperCase(java.util.Locale.ROOT);
            fetchConfigByCode(shareLastCode);
            return;
        }
        try {
            setToast(importConfig(text));
        } catch (Throwable t) {
            setToast("导入失败: " + t);
        }
    }

    private static void setClipboard(String s) throws Exception {
        if (mSetClipboard == null) throw new IllegalStateException("剪贴板接口不可用");
        mSetClipboard.invoke(null, s);
    }

    private static String getClipboard() throws Exception {
        if (mGetClipboard == null) throw new IllegalStateException("剪贴板接口不可用");
        Object s = mGetClipboard.invoke(null);
        return s == null ? "" : s.toString();
    }

    private static void writeVarint(java.io.OutputStream o, int v) throws java.io.IOException {
        while ((v & ~0x7F) != 0) {
            o.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        o.write(v);
    }

    private static int readVarint(byte[] b, int[] pos) {
        int result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            if (pos[0] >= b.length) throw new IllegalArgumentException("varint truncated");
            int c = b[pos[0]++] & 255;
            if (shift == 28 && (c & 0xF8) != 0) throw new IllegalArgumentException("varint overflow");
            result |= (c & 127) << shift;
            if ((c & 128) == 0) return result;
        }
        throw new IllegalArgumentException("varint too long");
    }

    /** 按鼠标 x 换算滑块值 (点击和拖拽共用同一套换算) */
    private static void applySlider(int modIdx, int paramIdx, int mx) {
        P pp = mods[modIdx].ps[paramIdx];
        if (dragSlW <= 0) return;
        double ratio = (mx - dragSlX) / (double) dragSlW;
        ratio = Math.max(0.0, Math.min(1.0, ratio));
        // 吸附和夹取都在 P.snap 里, 和滚轮微调共用同一套网格 —— 两边精度必须一致。
        pp.v = pp.snap(pp.min + ratio * (pp.max - pp.min));
    }

    /**
     * 滑块拖动/微调用的**细步长**。
     *
     * 参数上声明的 step 现在只用来区分"整数参数别被拉成小数", 不再决定拖动精度:
     * 用户要的是**每个 0.01 都能拉到**, 所以非整数参数一律 0.01 一格。
     *
     * 原来这里取 range/400 再对齐到 1/2/5×10^k, 想的是"别调出 3.127 这种一眼机器的数"。
     * 但那个对齐是致命的: 区间一过 4.0 就变 0.02, 过 20 变 0.05, 过 40 变 0.1,
     * 过 100 变 0.5, 0..360 那种直接一格 1.0 —— 中间的 0.01 全拉不到。
     *
     * 拖动本身仍然受滑轨像素数限制 (最窄 24 逻辑像素, 区间 7.0 的参数每像素跨 0.29),
     * 所以光靠拖动到不了每一个 0.01。**滚轮那条通路才是精调**, 它按同一个 0.01 走。
     */
    private static double sliderStep(P pp) {
        if (pp.step >= 1) return pp.step;      // 整数参数 (Delay ms / 次数) 保持整数
        return P.Q;
    }

    /** 鼠标这一帧停在哪个数值滑块上 (给滚轮精调用)。渲染时记录, 滚轮处理时读上一帧的值 */
    private static Mod hoverParamMd = null;
    private static int hoverParamI = -1;

    /** LiteGui.keyTyped 调用: 搜索输入 / 绑定按键捕获 (Esc 键码 1 取消绑定) / 键盘翻页 */
    public static void onKeyTyped(char typedChar, int keyCode) {
        // Toggle is owned by native press/release events plus the shared polling latch.
        if(keyCode==GUI_KEY)return;
        // 参数数值输入框最先吃键盘 —— 它在编辑时应该独占输入, 见 paramEditKey。
        // 放在最前面: 编辑中按 Esc 只该关掉输入框, 不该把整个面板关掉。
        if (paramEditKey(typedChar, keyCode)) return;
        // 配置命名输入框。和搜索框同一套焦点规则: 谁有焦点谁吃键盘。
        if (cfgNameFocused) {
            if (keyCode == 1) { cfgNameFocused = false; return; }        // Esc 收焦点
            if (keyCode == 28) { cfgNameFocused = false; return; }       // Enter 收焦点 (值已经存着)
            if (keyCode == 14) {                                          // Backspace
                if (cfgName.length() > 0) cfgName = cfgName.substring(0, cfgName.length() - 1);
                return;
            }
            if (isCtrlV(keyCode)) {                                       // Ctrl+V: 直接把名字粘进来
                try {
                    String c = (String) mGetClipboard.invoke(null);
                    if (c != null) cfgName = (cfgName + c.trim()).substring(0, Math.min(24, cfgName.length() + c.trim().length()));
                } catch (Throwable ignored) { }
                return;
            }
            // 名字是给人看的, 中文也得能打 —— 所以不做字符白名单, 只挡掉会产生控制字符的键
            if (typedChar >= ' ' && typedChar != 127 && cfgName.length() < 24) cfgName += typedChar;
            return;
        }
        // 分享小块的输入框优先吃键盘 —— 它和搜索框同时只有一个能有焦点 (小块开着时不点搜索框)
        if (shareOpen) {
            if (keyCode == 1) {                 // Esc: 关掉整个小块
                if (shareInputFocused) shareInputFocused = false;
                else closeShare();
                return;
            }
            if (!shareInputFocused) return;     // 小块开着但没在编辑: 吃掉按键, 别漏到面板上
            // Ctrl+V。以前这里只有退格和可见字符, 于是"别人发过来的码"唯一的进入方式
            // 就是手打六个字符 —— 而它明明是躺在剪贴板里的。原版输入框判的就是这个组合键。
            if (isCtrlV(keyCode)) { pasteIntoShareInput(); return; }
            if (keyCode == 28) { shareInputSubmit(); return; }   // Enter 直接读
            if (keyCode == 14) {                // Backspace
                if (shareCodeInput.length() > 0) {
                    shareCodeInput = shareCodeInput.substring(0, shareCodeInput.length() - 1);
                }
                return;
            }
            // 分享码字母表里全是大写, 小写直接抬上去, 省得用户以为"输了没反应"
            if (typedChar >= 32 && typedChar != 127 && shareCodeInput.length() < 6) {
                shareCodeInput += Character.toUpperCase(typedChar);
            }
            return;
        }
        // 搜索框有焦点时优先吃掉键盘, 否则打字会同时触发翻页和快捷键
        if (searchFocused) {
            if (keyCode == 1) {              // Esc: 清空并退出搜索
                searchFocused = false;
                searchQuery = "";
                scroll = 0; if (auroraWorkspace) deckSearchChanged();
                return;
            }
            if (keyCode == 28) {             // Enter: 收起焦点, 保留搜索词
                searchFocused = false;
                return;
            }
            if (keyCode == 14) {             // Backspace
                if (searchQuery.length() > 0) {
                    searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                    scroll = 0; if (auroraWorkspace) deckSearchChanged();
                }
                return;
            }
            if (typedChar >= 32 && typedChar != 127 && searchQuery.length() < 24) {
                searchQuery += typedChar;
                scroll = 0; if (auroraWorkspace) deckSearchChanged();
            }
            return;
        }
        if (bindTarget < 0) {
            if(keyCode==1){closeGui();return;}
            // 不在绑定状态时, 翻页键直接滚列表 (滚轮万一被驱动吃了还有这条退路)
            // 步长乘 S: scroll 现在是物理像素, 不乘的话按一下只走 1/S 屏
            switch (keyCode) {
                case 200: scrollBy(-56 * S); return;  // ↑
                case 208: scrollBy(56 * S); return;   // ↓
                case 201: scrollBy(-viewH()); return; // PageUp
                case 209: scrollBy(viewH()); return;  // PageDown
                case 207: scrollBy(Integer.MAX_VALUE); return; // End
                case 199: scrollBy(Integer.MIN_VALUE); return; // Home
                default: return;
            }
        }
        if (keyCode == 1) { // Esc: 取消绑定 (保留原键)
            bindTarget = -1;
            log("bind cancelled");
            return;
        }
        if (keyCode == 14 || keyCode == 211) { // Backspace / Delete: 清空绑定 (和原版操作设置一个习惯)
            mods[bindTarget].bindKey = 0;
            log("unbound " + mods[bindTarget].name + " (Backspace/Delete)");
            bindTarget = -1;
            return;
        }
        mods[bindTarget].bindKey = keyCode;
        log("bound " + mods[bindTarget].name + " -> key " + keyCode);
        bindTarget = -1;
    }

    /** LiteGui.onGuiClosed 调用 */
    public static void guiClosed() {
        hotkeysWereBlocked=true;
        // GPU caches are bounded and reused across GUI toggles; no shader rebuild on each close.
        guiOpen = false;
        bindTarget = -1;
        deckDragging = false;
        saveDeckPositions();
        panDrag = false;        // 关面板时松手, 否则下次打开会接着上次的拖动状态
        hudDrag = false;
        hudScaleDrag = false;
        widgetDragMod = -1;
        paramEditCancel();      // 关面板时放弃没提交的参数输入
        dragCol = -1;
        resizeCol = -1;
        searchFocused = cfgNameFocused = shareInputFocused = false;
        layoutLogged = false;   // 下次打开重新打一遍版面诊断
        log("guiClosed (panel closed)");
    }

    // ===== 飞行 =====
    private static void doFlight(Object player) throws Exception {
        if (fCapPlayer == null || fCapIsFlying == null) return;
        Object caps = fCapPlayer.get(player);
        if (caps == null) return;
        // Only While Sneaking: 不按潜行就当没开, 想飞才飞 —— 比常年飘着自然得多
        if (p(MF, 5) > 0 && !isSneaking(player)) {
            fCapIsFlying.setBoolean(caps, false);
            return;
        }

        int mode = (int) p(MF, 2);                 // 0=Vanilla 1=Motion 2=NCP 3=Glide
        boolean jump = mKeyDown != null && (Boolean) mKeyDown.invoke(null, 57);   // KEY_SPACE
        double rise = p(MF, 0);
        double fall = p(MF, 4);                    // Fall Speed
        double cap = p(MF, 1) * 0.28;

        // Anti Kick: 贴地且没按跳的时候不上升。服务端把"悬空不下落"当飞行证据,
        // 起飞和落地这两帧最容易被抓, 这里让起飞慢半拍。
        if (p(MF, 3) > 0 && fOnGround != null && fOnGround.getBoolean(player) && !jump) return;

        if (mode == 0) {                           // Vanilla: 走原版 capabilities, 手感最好
            fCapIsFlying.setBoolean(caps, true);
            mFlySpeed.invoke(caps, (float) p(MF, 1) * 0.05f);
            return;
        }
        // 其余三档都不碰 capabilities —— 只改速度, 服务端看到的是"下落/跳跃"的正常曲线
        fCapIsFlying.setBoolean(caps, false);
        double vy;
        if (mode == 3) {                           // Glide: 缓慢下沉 + 水平滑翔
            vy = jump ? rise : -fall;
        } else if (mode == 2) {                    // NCP: 垂直动作最小化
            vy = jump ? rise * 0.6 : -fall * 0.5;
        } else {                                   // Motion
            vy = jump ? rise : -fall;
        }
        fMotionY.setDouble(player, vy);

        // 水平: 增量式 (不倍增, 有上限), 避免加速度检测
        double mx = fMotionX.getDouble(player);
        double mz = fMotionZ.getDouble(player);
        double spd = Math.sqrt(mx * mx + mz * mz);
        if (spd < cap && spd > 0.001) {
            double add = Math.min(mode == 2 ? 0.01 : 0.02, cap - spd);
            fMotionX.setDouble(player, mx + mx / spd * add);
            fMotionZ.setDouble(player, mz + mz / spd * add);
        }
    }

    // ===== 加速 =====
    private static boolean speedTimerOwned;
    private static void releaseSpeedTimer(Object mc) throws Exception {
        if(!speedTimerOwned)return;
        speedTimerOwned=false;
        if(!mods[F_TIMER].on && fTimer!=null && fTickLength!=null)setTimerSpeed(fTimer.get(mc),1.0);
    }
    private static void doSpeed(Object player) throws Exception {
        releaseSpeedTimer(mGetMC.invoke(null));
        boolean onGround = fOnGround.getBoolean(player);
        float forward = fMovingF.getFloat(player);
        float strafe = fStrafe.getFloat(player);
        int mode = (int) p(MS, 3);                 // 0=Vanilla 1=YPort 2=NCP 3=Bhop
        // Only Sprinting: 不疾跑就不加速 —— 疾跑本来就有速度加成, 两件事分开看着才正常
        if (p(MS, 5) > 0 && !isSprinting(player)) return;
        boolean air = p(MS, 2) > 0;
        if (!onGround && !air && mode != 1) return;
        if (forward == 0.0f && strafe == 0.0f) return;

        // Ice Boost: 站在冰上本来就更滑, 叠完速度不容易被单独拎出来
        double ice = p(MS, 6) > 0 && blockUnderName(player).contains("ice") ? 1.25 : 1.0;

        // 方向: forward/strafe 组合
        float yaw = fYaw.getFloat(player);
        double rad = Math.toRadians(yaw);
        double dx = -Math.sin(rad) * forward + Math.cos(rad) * strafe;
        double dz = Math.cos(rad) * forward + Math.sin(rad) * strafe;
        double norm = Math.sqrt(dx * dx + dz * dz);
        if (norm < 0.001) return;
        dx /= norm;
        dz /= norm;

        // 目标速度上限 (倍率 1.0-2.2 -> cap 0.28~0.62)
        double cap = p(MS, 0) * 0.28 * ice;
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);

        // 沿移动方向的投影速度
        double proj = mx * dx + mz * dz;

        // 各档的加速度上限: NCP 最保守, Bhop 允许落地一次给足
        double addCap;
        if (mode == 2) addCap = p(MS, 1) > 0 ? 0.010 : 0.020;
        else if (mode == 3) addCap = 0.060;
        else addCap = p(MS, 1) > 0 ? 0.015 : 0.030;

        // YPort: 每次落地重新起跳, 靠跳跃把地面摩擦洗掉
        if (mode == 1 && onGround) {
            mJump.invoke(player);
            proj = cap;
        }
        if (proj < cap) proj = Math.min(cap, proj + addCap);
        fMotionX.setDouble(player, dx * proj);
        fMotionZ.setDouble(player, dz * proj);

        // Timer: 速度档位自带的倍速。Timer 模块自己也改这个字段, 两边同时开会互相顶,
        // 所以 Timer 开着时这里让位, 免得每帧两个值来回跳。
        // 单位换算在 setTimerSpeed 里 (两个版本不是同一个量), 这里不重复。
        if (fTimer != null && fTickLength != null && !mods[F_TIMER].on) {
            setTimerSpeed(fTimer.get(mGetMC.invoke(null)), p(MS, 4));
            speedTimerOwned=true;
        }
    }

    // ===== 战斗: 自动点击 =====
    private static long lastClick = 0;
    /** No Mine 诊断用的限流时间戳 —— 见 doAutoClicker 里的说明 */
    private static long noMineLastLog = 0;
    /**
     * 自动点击。参数: 0=Clicks/s 1=Randomize 2=Hold Only 3=Mode 4=Weapon Only
     *
     * 原来只读了 Clicks/s, 另外四个是纯摆设 —— 所以"按住模式"怎么开都没反应。
     */
    private static void doAutoClick(Object mc, Object player) throws Exception {
        int mode = (int) p(F_AUTOCLICK, 3);            // 0=Left 1=Right 2=Both
        boolean left = mode == 0 || mode == 2;
        boolean right = mode == 1 || mode == 2;

        // Both: **按住哪边就连点哪边**, 而不是左右一起点。
        // 原来 Both 是"每个间隔左键一下 + 右键一下", 结果你想挖矿它也在放方块、想吃饭它也在打,
        // 完全没法用。现在由你按的键决定打哪边。
        if (mode == 2) {
            left = (Boolean) mIsButtonDown.invoke(null, 0);
            right = (Boolean) mIsButtonDown.invoke(null, 1);
            if (!left && !right) { lastClick = 0; return; }   // 都没按就不点
        }

        // Hold Only: 只在真的按住对应键时点。松开就重置计时, 下次按下立刻点一下,
        // 而不是等上一个间隔走完 —— 否则按住的第一下总感觉是"迟钝的"。
        if (p(F_AUTOCLICK, 2) > 0) {
            boolean held = false;
            if (left && (Boolean) mIsButtonDown.invoke(null, 0)) held = true;
            if (right && (Boolean) mIsButtonDown.invoke(null, 1)) held = true;
            if (!held) { lastClick = 0; return; }
        }

        if (p(F_AUTOCLICK, 4) > 0 && !isWeapon(heldItem(player))) return;

        // No Mine: 正在挖方块时不连点左键。
        // 挖矿得一直按着左键, 连点会不停打断挖掘进度, 而且"对着方块疯狂挥手"比什么都显眼。
        // 只跳左键 —— 右键是放方块/吃东西, 挖矿时不受影响。
        //
        // 用户报过 "ac 的 no mine 无效"。判据本身 (PlayerControllerMP.isHittingBlock,
        // SRG field_78778_j, 已核对) 是对的, 所以先把它变成可观测的: 参数开着且按住左键时
        // 每秒打一条挖掘状态, 下次日志就能区分是"没检测到在挖"还是"检测到了但没拦住"。
        if (left && p(F_AUTOCLICK, 5) > 0) {
            boolean mining = isMining(mc);
            long nf = System.currentTimeMillis();
            if (nf - noMineLastLog >= 1000) {
                noMineLastLog = nf;
                log("No Mine: isMining=" + mining + " isHittingBlock句柄=" + (fIsHittingBlock != null));
            }
            if (mining) {
                left = false;
                if (!right) { lastClick = 0; return; }
            }
        }

        double cps = p(F_AUTOCLICK, 0);
        // Randomize 0..2 -> 间隔抖动 ±(rnd*25)%。0 就是完全匀速, 那是最好抓的特征
        double rnd = p(F_AUTOCLICK, 1);
        if (rnd > 0) cps *= 1.0 + (Math.random() - 0.5) * rnd * 0.5;
        if (cps < 0.5) cps = 0.5;

        long now = System.currentTimeMillis();
        if (now - lastClick < (long) (1000.0 / cps)) return;
        lastClick = now;

        Object leftTarget = left ? crosshairEntity(mc) : null;
        if (left && !EntityExclusions.contains(leftTarget)
                && (leftTarget == null || !combatAttackAlreadySent(player))) {
            // 左键必须走 Minecraft.clickMouse —— 它才会真的调 playerController.attackEntity。
            // 之前这里调的是 EntityLivingBase.swingArm, 那个只播放摆手动画, 打不到任何人:
            // 看起来在挥手, 服务端一个攻击包都收不到。
            //
            // **但 clickMouse 自带一个"挥空惩罚", 用户报的"点击频率变慢"就是它。**
            //
            // 原版 Minecraft.clickMouse 里有一个 leftClickCounter (Minecraft.leftClickCounter,
            // SRG field_71429_W)。打空的时候 (m.objectMouseOver 不是实体) 它被设成 10,
            // 然后**接下来 10 个 tick 内所有点击直接被吞掉**:
            //     if (this.leftClickCounter > 0) { this.leftClickCounter--; }
            //     else { ... 真正的攻击 ... }
            // 那个惩罚是给"玩家挥空"设计的, 对我们这种按固定 CPS 驱动的东西纯属损耗:
            // 只要准星在两次挥击之间扫过一次空气, 后面半秒的点击就全没了 —— 表现就是
            // "频率忽快忽慢、整体变慢", 而且 CPS 设得越高越明显。
            //
            // 我们自己的频率由上面 cps 那条时间戳控制, 不需要原版再管一遍 —— 所以
            // 调完之后把计数器清掉。只清这一个字段, 不动原本的攻击路径本身。
            clickPrimary(mc, player);
        }
        // 右键走原版 rightClickMouse: 它自带 rightClickDelayTimer, 自己会限速
        if (right && mRightClickMouse != null && !EntityExclusions.contains(crosshairEntity(mc))) mRightClickMouse.invoke(mc);
    }

    /** Shared left-click entry; keep AutoClicker's existing miss-delay policy. */
    private static void clickPrimary(Object mc, Object player) throws Exception {
        if (mClickMouse != null) mClickMouse.invoke(mc);
        else swingHand(player);
        if (fLeftClickCounter != null) {
            try { fLeftClickCounter.setInt(mc, 0); } catch (Throwable ignored) { }
        }
    }

    /** 手里是不是武器。用类名判断, 不依赖具体物品类 */
    private static boolean isWeapon(Object stack) {
        return itemLike(stack, "sword") || itemLike(stack, "axe");
    }

    /**
     * 现在是不是正在挖方块。
     *
     * 读 PlayerControllerMP.isHittingBlock —— 按住左键打方块时它为 true, 松开立刻变 false。
     * 不用 objectMouseOver 的类型判断: 那个只能说明"准星指着方块", 不代表你按着左键在挖;
     * 站在方块前不动也会指着它。
     */
    private static boolean isMining(Object mc) {
        try {
            if (mc == null || fPlayerController == null || fIsHittingBlock == null) return false;
            Object pc = fPlayerController.get(mc);
            return pc != null && fIsHittingBlock.getBoolean(pc);
        } catch (Throwable t) {
            return false;
        }
    }

    // ===== 战斗: 暴击 (跳劈/迷你跳) =====
    /**
     * 暴击前置。返回 true = 现在可以打 (已经在下降段), false = 这一 tick 先别打。
     *
     * 服务端判暴击读的是**它自己那份** fallDistance 和 onGround —— 那两个量是服务端
     * 按自己的重力模拟算出来的, 客户端改不了。所以"把本地 fallDistance 改成 0.1 再发个
     * onGround=false 的包"完全没用 (原来的 Packet 档就是这么写的, 服务端压根不看)。
     * 唯一能生效的办法是真的失去地面、真的在下落 —— 那时候服务端也会算出 fallDistance>0。
     *
     * 于是流程变成: 站着 -> 先起跳 (这一 tick 不打) -> 等真的开始下落 -> 那一刻打出去。
     * 所以它必须由攻击方 (KillAura / 手动点击) 在出手前调用, 不能自己一个 tick 一个 tick 空转。
     */
    private static boolean critReady(Object player) throws Exception {
        if (fOnGround == null || fMotionY == null) return true;
        // Chance: 不是每次都暴击。100% 暴击本身就是最干净的统计特征。
        if (p(F_CRITICALS, 1) < 100 && Math.random() * 100 >= p(F_CRITICALS, 1)) return true;
        if (fOnGround.getBoolean(player)) {
            fMotionY.setDouble(player, (int) p(F_CRITICALS, 0) == 1 ? 0.42 : 0.11);   // 起跳, 本 tick 不打
            return false;
        }
        // 下落中才算: motionY < 0 且服务端那份 fallDistance 会在这一 tick 变成 > 0
        return fMotionY.getDouble(player) < 0;
    }

    /** 手动点击时的暴击: 按住左键就跳, 落地瞬间的那一下自然就是暴击 */
    private static void doCriticals(Object player) throws Exception {
        if (!(Boolean) mIsButtonDown.invoke(null, 0)) return;
        critReady(player);
    }

    // ===== 战斗: 反击退 (检测速度突变并按比例削减) =====
    private static double lastMotionLen = 0;
    private static void doVelocity(Object player) throws Exception {
        double h = p(F_VELOCITY, 0), v = p(F_VELOCITY, 1);
        int mode = (int) p(F_VELOCITY, 2);         // 0=Normal 1=Reverse 2=Zero
        if (p(F_VELOCITY, 3) < 100 && Math.random() * 100 >= p(F_VELOCITY, 3)) return;   // Chance

        double mx = fMotionX.getDouble(player), my = fMotionY.getDouble(player), mz = fMotionZ.getDouble(player);
        double len = Math.sqrt(mx * mx + my * my + mz * mz);
        // 击退 = 水平/垂直速度突然增大; 突变时才动手
        if (len > lastMotionLen + 0.08 && len > 0.15) {
            double nx, ny, nz;
            if (mode == 2) {                       // Zero: 完全吃掉击退
                nx = 0.0; ny = 0.0; nz = 0.0;
            } else if (mode == 1) {                // Reverse: 反向 —— 被打反而朝对方贴过去
                nx = -mx * h; ny = -my * v; nz = -mz * h;
            } else {                               // Normal: 按比例削减
                nx = mx * (1.0 - h); ny = my * (1.0 - v); nz = mz * (1.0 - h);
            }
            fMotionX.setDouble(player, nx);
            fMotionY.setDouble(player, ny);
            fMotionZ.setDouble(player, nz);
            len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        lastMotionLen = len;
    }

    // ===== 战斗: 攻击距离 =====

    /**
     * **方块**交互距离。由 ASM 补丁过的 {@code PlayerControllerMP.func_78757_d()} 读取。
     *
     * **这个值故意永远是原版 4.5。** 用户要求: "reach 要求只影响攻击, 不影响放方块"。
     *
     * 历史上这里被当成"距离"一起加大, 于是 Reach 一开, 准星 (getMouseOver 的方块射线)
     * 也跟着变长 —— **放方块和挖方块都够得更远**, 那正是不要的行为。而且它比攻击距离更容易
     * 被察觉: 服务端能对放置位置做校验, 够到 6 格外放方块是明显的异常。
     *
     * 1.12.2 的 getMouseOver 里方块和实体共用这一个返回值, 同一个函数分不出调用者,
     * 所以"只加攻击"不能在这里做 —— 改法见 {@link #entityReach()} 和 LiteAgent 的补丁。
     */
    static volatile float reachOverride = 4.5f;

    /** 方块距离: 恒定原版值。签名必须是 ()F 且 public static —— ASM 里写死了。 */
    public static float blockReach() {
        return 4.5f;
    }

    /**
     * **实体**交互距离 (攻击范围)。由 ASM 补丁过的
     * {@code EntityRenderer.getMouseOver} 里的实体射线读取。
     *
     * 只有这一条被 Reach 影响 —— 放方块/挖方块的方块射线走 {@link #blockReach()}, 永远是 4.5。
     */
    static volatile float entityReachOverride = 4.5f;

    /** 实体距离: 签名必须是 ()F 且 public static —— ASM 里写死了。 */
    public static float entityReach() {
        return entityReachOverride;
    }

    static boolean reachEnabled() {
        return Ver.is1201() ? ReachPicker.modernEnabled : mods[F_REACH].on && !mods[F_FREECAM].on;
    }

    static double reachRange(boolean sprinting) throws Exception {
        if (!reachEnabled()) return 0;
        if (Ver.is1201()) return ReachPicker.modernSprintOnly && !sprinting ? 0 : ReachPicker.modernRange;
        if (p(F_REACH, 2) > 0 && !sprinting) return 0;
        // Keep saved mode indices. Old Packet becomes click-only picking; old Both
        // is a Raycast alias, never a second attack or a local-only damage call.
        if ((int)p(F_REACH, 1) == 1 && (mIsButtonDown == null
                || !Boolean.TRUE.equals(mIsButtonDown.invoke(null, 0)))) return 0;
        return p(F_REACH, 0);
    }

    // ===== 移动: 强制疾跑 =====
    private static boolean scaffoldNoSprintActive() {
        return mods != null && F_SCAFFOLD >= 0 && F_SCAFFOLD < mods.length
                && mods[F_SCAFFOLD].on && p(F_SCAFFOLD, 11) > 0;
    }

    /** Filter the request before vanilla changes attributes and sprint timers. */
    public static boolean scaffoldSprintRequested(boolean requested) {
        return requested && !scaffoldNoSprintActive();
    }

    private static Object sprintInputPlayer;
    private static boolean sprintInputMoving, sprintTickRequest;

    /** Sample fresh native input before vanilla's sprint-start checks. */
    public static void onSprintInputUpdated() {
        sprintTickRequest = false;
        try {
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object player = mc == null || fPlayer == null ? null : fPlayer.get(mc);
            if (player != sprintInputPlayer) { sprintInputPlayer = player; sprintInputMoving = false; }
            if (player == null || mods == null || !mods[F_SPRINT].on || guiOpen
                    || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)
                    || mods[F_FREECAM].on || scaffoldNoSprintActive()
                    || fMovementInput == null || fInputForward == null) {
                sprintInputMoving = false;
                return;
            }
            Object input = fMovementInput.get(player);
            boolean moving = input != null && fInputForward.getFloat(input) >= 0.8f;
            // Auto Re-Sprint off starts once per forward press, not after every attack.
            sprintTickRequest = moving && (p(F_SPRINT, 3) > 0 || !sprintInputMoving);
            sprintInputMoving = moving;
        } catch (Throwable t) { sprintInputMoving = false; logOnce("sprint input", t); }
    }

    /** Only augment the sprint-key read; vanilla retains all eligibility checks. */
    public static boolean sprintKeyRequested(boolean physical) {
        return physical || (sprintTickRequest && mods != null && mods[F_SPRINT].on
                && (int) p(F_SPRINT, 1) == 0 && p(F_SPRINT, 0) <= 0
                && !guiOpen && !scaffoldNoSprintActive());
    }

    private static void doSprint(Object player) throws Exception {
        // Legit sprint belongs to the native input tick, never to FPS scheduling.
        if ((int) p(F_SPRINT, 1) == 0 && p(F_SPRINT, 0) <= 0) return;
        if (mSetSprinting == null) return;
        // Sprint is scheduled before Scaffold in onFrame.  Without this gate it
        // turns sprint on, then Scaffold turns it off, producing one-frame/tick
        // flapping and letting vanilla send another START_SPRINT action.
        if (scaffoldNoSprintActive()) {
            mSetSprinting.invoke(player, false);
            return;
        }
        float fwd = fMovingF.getFloat(player), str = fStrafe.getFloat(player);
        if (p(F_SPRINT, 2) > 0 && fwd <= 0.0f) return;                            // Only Forward
        boolean allDir = p(F_SPRINT, 0) > 0;                                      // All Directions
        if ((int) p(F_SPRINT, 1) == 0 && !allDir && fwd <= 0.0f && Math.abs(str) < 0.01f) return;  // Legit
        if (p(F_SPRINT, 3) > 0) {                                                 // Auto Re-Sprint: 每 tick 顶上
            mSetSprinting.invoke(player, true);
        } else if (fwd > 0.0f) {
            mSetSprinting.invoke(player, true);
        }
    }

    // ===== 移动: 免摔 =====
    private static void doNoFall(Object player) throws Exception {
        // 真的在下落才动手: 站着也发包等于白送破绽
        if (fFallDist.getFloat(player) < p(F_NOFALL, 1)) return;                  // Min Fall (blocks)
        if (p(F_NOFALL, 2) > 0 && fCapIsFlying != null && fCapPlayer != null) {    // Only While Flying
            Object cap = fCapPlayer.get(player);
            if (cap == null || !fCapIsFlying.getBoolean(cap)) return;
        }
        int mode = (int) p(F_NOFALL, 0);
        if (mode == 0 && fConnection != null && mSendPacket != null && ctorPacketPlayer != null) {
            // Packet: 服务端算摔伤只看最后那个 onGround, 补一个"我踩着地"的移动包
            Object conn = fConnection.get(player);
            if (conn != null) mSendPacket.invoke(conn, ctorPacketPlayer.newInstance(true));
        } else if (mode == 2 && fOnGround != null) {
            // No Ground: 本地下落距离和 onGround 一起清, 客户端自己发的包也就变成 true 了
            fOnGround.setBoolean(player, true);
        }
        fFallDist.setFloat(player, 0.0f);                                          // Anti 档也走这里
    }

    // ===== 移动: 自动跳 =====
    private static long lastAutoJump = 0;
    private static void doAutoJump(Object mc, Object player) throws Exception {
        if (!fOnGround.getBoolean(player)) return;
        if (fMovingF.getFloat(player) == 0.0f && fStrafe.getFloat(player) == 0.0f) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoJump < (long) p(F_AUTOJUMP, 2)) return;                 // Cooldown (ms)
        int mode = (int) p(F_AUTOJUMP, 1);
        if (mode != 0) {                                                          // On Edge / On Block 都要看前方
            if (mIsAirBlock == null || ctorBlockPos == null || fWorld == null) return;
            Object world = fWorld.get(mc);
            if (world == null) return;
            double yaw = Math.toRadians(fYaw.getFloat(player));
            // 朝向再往前 0.7 格: 那格是空气 = 前面是坑(On Edge), 是实心 = 前面是台阶(On Block)
            Object ahead = ctorBlockPos.newInstance(
                    fPosX.getDouble(player) - Math.sin(yaw) * 0.7,
                    fPosY.getDouble(player),
                    fPosZ.getDouble(player) + Math.cos(yaw) * 0.7);
            boolean air = Boolean.TRUE.equals(mIsAirBlock.invoke(world, ahead));
            if (mode == 1 && !air) return;
            if (mode == 2 && air) return;
        }
        lastAutoJump = now;
        fMotionY.setDouble(player, p(F_AUTOJUMP, 0));                             // Power
    }

    // ===== 移动: 上台阶 =====
    private static void doStep(Object player) throws Exception {
        if (fStepHeight == null) return;
        boolean ground = fOnGround == null || fOnGround.getBoolean(player);
        if (p(F_STEP, 2) > 0 && !ground) {                                      // Only On Ground
            fStepHeight.setFloat(player, 0.6f);
            return;
        }
        double h = p(F_STEP, 0);                                                  // Height
        int mode = (int) p(F_STEP, 1);
        if (mode == 1) h = Math.min(h, 1.0);                                      // NCP: 超过 1 格必被记录
        else if (mode == 2 && !ground) h = 0.6;                                   // Reverse: 空中还原原值
        fStepHeight.setFloat(player, (float) h);
    }

    // ===== 移动: 水上行走 =====
    private static void doJesus(Object player) throws Exception {
        if (mIsInWater == null) return;
        boolean inWater = Boolean.TRUE.equals(mIsInWater.invoke(player));
        if (p(F_JESUS, 2) > 0 && !inWater) return;                                // Only On Water
        double my = fMotionY.getDouble(player);
        double speed = p(F_JESUS, 1);                                             // Speed
        int mode = (int) p(F_JESUS, 0);
        if (mode == 0) {                                                          // Solid: 水面当冰面, 直接冻住下落
            if (my < 0.0) fMotionY.setDouble(player, 0.0);
        } else if (mode == 1) {                                                   // Normal: 快沉到底了才托一下
            if (my < -0.02) fMotionY.setDouble(player, speed * 0.2);
        } else {                                                                  // Dolphin: 一直往上飘 + 推进
            if (my < 0.02) fMotionY.setDouble(player, speed * 0.2);
            fMotionX.setDouble(player, fMotionX.getDouble(player) * (1.0 + speed * 0.15));
            fMotionZ.setDouble(player, fMotionZ.getDouble(player) * (1.0 + speed * 0.15));
        }
    }

    // ===== 移动: 边缘保护 =====
    private static void doSafeWalk(Object mc, Object player) throws Exception {
        if (mIsAirBlock == null || ctorBlockPos == null) return;
        boolean ground = fOnGround.getBoolean(player);
        if (p(F_SAFEWALK, 2) > 0 && !ground) return;                              // Only On Ground
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);
        if (Math.abs(mx) < 0.001 && Math.abs(mz) < 0.001) return;
        Object world = fWorld.get(mc);
        if (world == null) return;
        double d = p(F_SAFEWALK, 0) * 2.0;
        Object bp = ctorBlockPos.newInstance(fPosX.getDouble(player) + mx * d,
                fPosY.getDouble(player) - 1.0, fPosZ.getDouble(player) + mz * d);
        if ((Boolean) mIsAirBlock.invoke(world, bp)) {
            if ((int) p(F_SAFEWALK, 1) == 1) {                                    // Slow: 减到 20%
                // 硬刹车(速度归零)在服务端看来是"速度瞬间掉到 0", 边缘试探更像真人
                fMotionX.setDouble(player, mx * 0.2);
                fMotionZ.setDouble(player, mz * 0.2);
            } else {                                                              // Sneak: 直接停住
                fMotionX.setDouble(player, 0.0);
                fMotionZ.setDouble(player, 0.0);
            }
        }
    }

    // ===== 移动: 兔子跳 =====
    private static void doBunnyHop(Object player) throws Exception {
        if (!fOnGround.getBoolean(player)) return;
        // Only Sprinting: 不疾跑就不连跳
        if (p(F_BUNNYHOP, 2) > 0 && !isSprinting(player)) return;
        boolean space = mKeyDown != null && (Boolean) mKeyDown.invoke(null, 57);  // 空格
        if ((int) p(F_BUNNYHOP, 1) == 1 && !space) return;                        // Space Only
        if (fMovingF.getFloat(player) == 0.0f) return;
        mJump.invoke(player);
        double mult = p(F_BUNNYHOP, 0);
        fMotionX.setDouble(player, fMotionX.getDouble(player) * mult);
        fMotionZ.setDouble(player, fMotionZ.getDouble(player) * mult);
    }

    // ===== 移动: 无减速 =====
    /**
     * 无减速。参数: 0=Item Use 1=Cobweb 2=Soul Sand 3=Mode 4=Slime Block 5=Sneak Slow
     *
     * 原来只处理了"使用物品"这一种, 另外几种方块减速完全没接 —— 站在灵魂沙上开 NoSlow
     * 一点用都没有。
     *
     * 第 5 项原来是 "Sweet Berry", 但**浆果丛是 1.14 才加的, 1.12.2 里根本没有这个方块**,
     * 那个开关永远不可能生效。改成 1.12.2 真实存在的潜行减速 (原版潜行把速度压到 0.3 倍)。
     */
    private static void doNoSlow(Object player) throws Exception {
        int mode = (int) p(F_NOSLOW, 3);          // 0=Vanilla 1=NCP 2=NoSlow
        String under = blockUnderName(player);

        boolean slowed = false;
        if (p(F_NOSLOW, 1) > 0 && under.contains("web")) slowed = true;           // Cobweb
        if (p(F_NOSLOW, 2) > 0 && under.contains("soul_sand")) slowed = true;     // Soul Sand
        if (p(F_NOSLOW, 4) > 0 && under.contains("slime")) slowed = true;         // Slime Block
        if (p(F_NOSLOW, 5) > 0 && isSneaking(player)) slowed = true;

        boolean using = mIsHandActive != null && (Boolean) mIsHandActive.invoke(player);
        if (p(F_NOSLOW, 0) > 0 && using) slowed = true;

        if (!slowed) return;
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len < 0.001) return;
        // 原版减速后约为正常的 0.2 倍。NCP 档补得保守一点, 免得顶到速度阈值。
        double target = mode == 1 ? Math.min(0.11, len * 4.0) : Math.min(0.13, len * 5.0);
        fMotionX.setDouble(player, mx / len * target);
        fMotionZ.setDouble(player, mz / len * target);
    }

    /**
     * 脚下那格的方块名 (小写)。
     *
     * 用 getTranslationKey() 而不是 getRegistryName(): 后者是 Forge 加的接口方法,
     * 在 SRG 映射表里查不到, 名字在运行时是否保持也说不准; 前者是原版方法
     * (func_149739_a), 返回 "tile.soul_sand.name" 这种串, 里面就带着注册名。
     * 取不到返回空串 —— 调用方按"不匹配"处理, 不会误判成生效。
     */
    private static String blockUnderName(Object player) {
        if (mGetBlockState == null || mGetBlock == null || mGetBlockKey == null || ctorBlockPos == null) return "";
        try {
            Object world = worldOf(player);
            if (world == null) return "";
            Object bp = ctorBlockPos.newInstance(Math.floor(fPosX.getDouble(player)),
                    Math.floor(fPosY.getDouble(player) - 0.1), Math.floor(fPosZ.getDouble(player)));
            Object st = mGetBlockState.invoke(world, bp);
            if (st == null) return "";
            Object block = mGetBlock.invoke(st);
            if (block == null) return "";
            Object key = mGetBlockKey.invoke(block);
            return key == null ? "" : key.toString().toLowerCase();
        } catch (Throwable t) {
            return "";
        }
    }

    // ===== 玩家: 快速放置 =====
    private static void doFastPlace(Object mc) throws Exception {
        if (fRightClickDelay == null) return;
        // No Delay 每 tick 压 0; Normal 留 Delay 格冷却
        fRightClickDelay.setInt(mc, (int) p(F_FASTPLACE, 1) == 1 ? 0 : (int) p(F_FASTPLACE, 0));
    }

    /** 快速挖掘。参数: 0=Multiplier 1=Mode 2=Only On Ground */
    private static void doFastBreak(Object mc, Object player) throws Exception {
        if (fCurBlockDamage == null || fPlayerController == null) return;
        if (p(F_FASTBREAK, 2) > 0 && fOnGround != null && !fOnGround.getBoolean(player)) return;  // Only On Ground
        Object pc = fPlayerController.get(mc);
        if (pc == null) return;
        float cur = fCurBlockDamage.getFloat(pc);
        if (cur > 0.0f) {
            // Instant: 直接写满 —— 下一帧 onPlayerDamageBlock 就判定破坏完成
            float next = (int) p(F_FASTBREAK, 1) == 1
                    ? 1.0f
                    : Math.min(1.0f, cur * (float) p(F_FASTBREAK, 0));
            fCurBlockDamage.setFloat(pc, next);
        }
        if (fBlockHitDelay != null) fBlockHitDelay.setInt(pc, 0);
    }

    // ===== 玩家: 自动搭桥 =====
    private static long lastScaffold = 0;
    /** Hard ceiling for placement attempts: doScaffold runs once per render
     * frame, but an interaction may be emitted at most once per game tick. */
    private static long scaffoldAttemptTick = Long.MIN_VALUE;
    private static boolean scaffoldJumpActive;
    /** Running/tower is decided once at take-off and kept until landing.  A
     * single low-displacement airborne tick must never turn a running jump into
     * a vertical tower placement. */
    private static boolean scaffoldJumpTravelActive;
    private static double scaffoldJumpTravelX, scaffoldJumpTravelZ;
    private static int scaffoldJumpBaseY = Integer.MIN_VALUE;
    private static int scaffoldJumpFloorY = Integer.MIN_VALUE;
    private static long scaffoldJumpTick = Long.MIN_VALUE;
    /** Keep Slot: 自动切槽前手上是哪个槽, 放完切回去; -1 = 没有待还原的 */
    private static int scaffoldPrevSlot = -1;
    /**
     * Scaffold 的支撑面状态。
     *
     * findAimPoint 以前每帧都从 DOWN/UP/WEST/EAST/NORTH/SOUTH 的固定顺序重新挑面。
     * 目标格在玩家脚边来回跨过方块边界时, 同一帧可能先命中脚下底面, 下一帧又命中上一块
     * 方块的侧面, 模型 yaw/pitch 就会在两个离散角度之间来回跳。这里把移动方向和上一帧
     * 的支撑面都保留下来, 让选择具有滞回, 不再被扫描顺序牵着走。
     */
    private static int scaffoldAimFace = -1;
    private static int scaffoldTravelFace = -1;
    private static float scaffoldModelYaw;
    private static float scaffoldModelPitch;
    private static float scaffoldPrevModelYaw, scaffoldPrevModelPitch;
    private static int scaffoldModelTick = Integer.MIN_VALUE;
    private static boolean scaffoldModelReady = false;
    /** Face hysteresis for a long, steady bridge run. */
    private static int scaffoldPoseFace = -1;
    private static int scaffoldFaceCandidate = -1;
    private static int scaffoldFaceStableTicks = 0;
    private static int scaffoldFaceSeenTick = Integer.MIN_VALUE;
    /** Edge protection latch: once the leading foot reaches the edge, keep the
     * sneak key owned until the adjacent target is confirmed solid. */
    private static boolean scaffoldPlacementLocked = false;
    /** Accepted locally, but the target is still air in the client world. */
    private static boolean scaffoldAwaitSolid = false;
    private static int scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ;
    private static int scaffoldLockedFace = -1;
    private static int scaffoldSideVisibleTick = Integer.MIN_VALUE;
    private static int scaffoldSideVisibleCount;
    private static long scaffoldPlacementLockAt = 0L;
    /** Last Scaffold yaw/pitch actually written in onPlayerMoved for a packet. */
    private static boolean scaffoldPacketReady = false;
    private static boolean scaffoldPacketPreparing, scaffoldTickCommit;
    private static boolean scaffoldPipelineLogged;
    private static boolean scaffoldRenderReady, scaffoldRenderCompleted;
    private static Object scaffoldRenderWorld, scaffoldRenderPlayer;
    private static long scaffoldRenderSerial;
    private static int scaffoldRenderTick, scaffoldRenderX, scaffoldRenderY, scaffoldRenderZ, scaffoldRenderFace;
    private static float scaffoldRenderYaw, scaffoldRenderPitch, scaffoldRenderPacketYaw, scaffoldRenderPacketPitch;
    private static double scaffoldRenderEyeX, scaffoldRenderEyeY, scaffoldRenderEyeZ;
    /** Exact hit displayed by the completed frame; valid only inside its commit. */
    private static double[] scaffoldCommitHit;
    private static ScaffoldPlacementFrame scaffoldQueuedPlacement;
    private static final ScaffoldPlacementJournal scaffoldPlacementJournal = new ScaffoldPlacementJournal();
    private static final ScaffoldPlacementJournal.Sink scaffoldPlacementSink = new ScaffoldPlacementJournal.Sink() {
        public void report(String message) { log(message); }
    };

    private static void observeScaffoldPlacements() {
        if (scaffoldPlacementJournal.size() == 0) return;
        try {
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object player = mc == null || fPlayer == null ? null : fPlayer.get(mc);
            final Object world = worldOf(player);
            scaffoldPlacementJournal.poll(world, player, player == null ? 0 : scaffoldClock(player),
                    new ScaffoldPlacementJournal.Probe() {
                        public boolean isAir(int x, int y, int z) throws Exception { return isAirAt(world, x, y, z); }
                    }, scaffoldPlacementSink);
        } catch (Throwable t) { logOnce("scaffold placement observation", t); }
    }

    private static void recordScaffoldPlacement(Object player, Object world, int x, int y, int z, int face) {
        try {
            boolean solid = !isAirAt(world, x, y, z);
            double[] hit = scaffoldCommitHit;
            String detail = "tick=" + scaffoldClock(player) + " face=" + face
                    + " packetTick=" + scaffoldPacketTick + " renderTick=" + scaffoldRenderTick
                    + " packetPose=" + scaffoldPacketYaw + "," + scaffoldPacketPitch
                    + " renderPose=" + scaffoldRenderYaw + "," + scaffoldRenderPitch
                    + " pos=" + fPosX.getDouble(player) + "," + fPosY.getDouble(player) + "," + fPosZ.getDouble(player)
                    + " renderEye=" + scaffoldRenderEyeX + "," + scaffoldRenderEyeY + "," + scaffoldRenderEyeZ
                    + " hit=" + (hit == null ? "none" : hit[0] + "," + hit[1] + "," + hit[2])
                    + " localBlock=" + solid;
            scaffoldPlacementJournal.record(world, player, scaffoldClock(player), x, y, z, solid, detail);
            log("Scaffold local acceptance: " + detail + " target=" + x + "," + y + "," + z + " serverConfirmed=false");
        } catch (Throwable t) { logOnce("scaffold placement record", t); }
    }

    /** One immutable, completed world-frame observation, consumed at most once. */
    static final class ScaffoldPlacementFrame {
        final Object player, world;
        final int tick, x, y, z, face;
        final float yaw, pitch, renderYaw, renderPitch;
        final double px, py, pz, eyeX, eyeY, eyeZ;
        ScaffoldPlacementFrame(Object player, Object world, int tick, int x, int y, int z, int face,
                float yaw, float pitch, float renderYaw, float renderPitch,
                double px, double py, double pz, double eyeX, double eyeY, double eyeZ) {
            this.player=player;this.world=world;this.tick=tick;this.x=x;this.y=y;this.z=z;this.face=face;
            this.yaw=yaw;this.pitch=pitch;this.renderYaw=renderYaw;this.renderPitch=renderPitch;
            this.px=px;this.py=py;this.pz=pz;this.eyeX=eyeX;this.eyeY=eyeY;this.eyeZ=eyeZ;
        }
        boolean matches(Object player, Object world, int tick, int x, int y, int z, int face,
                float yaw, float pitch, double px, double py, double pz) {
            return this.player==player && this.world==world && this.tick==tick
                    && this.x==x && this.y==y && this.z==z && this.face==face
                    && this.yaw==yaw && this.pitch==pitch
                    && this.px==px && this.py==py && this.pz==pz;
        }
    }
    /** True after aimAtEdge submitted this frame's freshly solved face angle. */
    private static boolean scaffoldAimRequestedFrame = false;
    private static float scaffoldPacketYaw, scaffoldPacketPitch;
    private static int scaffoldPacketX, scaffoldPacketY, scaffoldPacketZ, scaffoldPacketFace = -1;
    private static int scaffoldPacketTick = Integer.MIN_VALUE;
    /** Keep the bridge pose across the placement-confirmation tick; clear it only after flat ground resumes. */
    private static boolean scaffoldPoseActive = false;
    /** The continuous bridge pose is selected once on the last supported
     * block and then remains fixed until flat ground resumes. */
    private static boolean scaffoldPoseLocked = false;
    private static boolean scaffoldSessionPoseChosen = false;
    private static float scaffoldSessionYaw, scaffoldSessionPitch;
    /** Cell whose discrete movement sample selected the current session pitch.
     * Yaw remains face-stable for the bridge, while pitch is allowed to change
     * once, during the safe approach to a new cell, when the old ray cannot hit
     * that cell at the next vanilla movement sample. */
    private static int scaffoldSessionPitchX = Integer.MIN_VALUE;
    private static int scaffoldSessionPitchY = Integer.MIN_VALUE;
    private static int scaffoldSessionPitchZ = Integer.MIN_VALUE;
    /** Fixed raw-yaw branch selected at the beginning of a bridge face. */
    private static float scaffoldSessionBranchYaw;
    private static int scaffoldSessionFace = -1;
    /** A horizontal bridge owns one yaw branch for its full lifetime. Pitch is
     * retained across cells whenever it still intersects the next discrete
     * movement sample, and is otherwise solved once during that cell's approach. */
    /**
     * A bridge is a continuous movement session, not a sequence of single
     * placement frames.  Keep this latch alive while the player is moving so
     * the model does not reset for one frame after every successful placement.
     */
    private static boolean scaffoldBridgeSession = false;
    /**
     * 自动搭桥。
     *
     * 旧版不生效的三个原因, 全在这里补掉:
     *  1) 只在"下落中"触发 —— 走下平地的边缘时 motionY 还没变负, 根本轮不到它。现在有模式。
     *  2) 不切方块 —— 手上拿的不是方块时右键什么都不发生。现在会自动切到快捷栏里的方块。
     *  3) 不瞄准 —— rightClickMouse 打在 objectMouseOver 上, 而那是由视线方向射线求出来的;
     *     视线平视时打不到脚下的方块。现在会算好相邻实心块的面并转过去 (可静默)。
     */
        /**
     * 自动搭路。
     *
     * 旧版为什么"完全失效": 目标格是用 motionX/motionZ * ahead 推出来的。正常行走时
     * motionX 只有 0.13~0.22, 加到 px 上再 floor 还是脚下那一格 —— 而 ty = floor(py)-1
     * 正好是你踩着的那块。isAirAt 永远返回 false, 于是每次都 return, 等于什么都没做。
     *
     * 现在按**朝向**推前方第 N 格 (Look Ahead 终于是"格数"而不是"乘以一个很小的速度"),
     * 目标是"前方那一格的下方"。走在平地上时那里是实心地面, 自然不会乱放;
     * 只有到了边缘才真的铺 —— 这正是搭路该有的行为。
     *
     * 参数 (下标见 Mod 定义):
     *   0 Mode       Falling/Always/Hold Space
     *   1 Rotation   Off/Silent/Visible
     *   2 Delay (ms)
     *   3 Look Ahead 前方第几格
     *   4 Sneak      铺的时候潜行, 免得走到边缘直接掉下去
     *   5 Swing
     *   6 Tower      按住空格垫脚上塔
     *   7 Only Below 只铺脚下 (过桥)
     *   8 Keep Slot  放完切回原来手上的槽
     */
    /**
     * Scaffold 的失败原因节流记录 (1 秒最多一条)。
     *
     * 这个功能有 8 条静默 return, 任何一条踩中都是"参数怎么调都没反应"。
     * 前两轮修完之后日志里连异常都没有 —— 说明代码跑到底了, 但那只能说"没抛异常",
     * 说明不了"放出去了"。加这一条是为了把"到底卡在哪一步"变成可读的事实, 而不是继续猜。
     * 排完就删。
     */
    private static long scaffoldDbgAt = 0;
    private static String scaffoldDbgWhy = "";
    private static long scaffoldPlaceLogAt = 0;
    private static int scaffoldNativeStepTick = Integer.MIN_VALUE;
    private static double scaffoldNativeStepX, scaffoldNativeStepZ;
    private static double scaffoldEarlyStepX, scaffoldEarlyStepZ;

    private static void scaffoldWhy(String why) {
        scaffoldDbgWhy = why;
        long now = System.currentTimeMillis();
        if (now - scaffoldDbgAt < 1000) return;
        scaffoldDbgAt = now;
        log("Scaffold 停在这一步: " + why
                + " | target=" + scaffoldLockedX + "," + scaffoldLockedY + "," + scaffoldLockedZ
                + " face=" + scaffoldLockedFace + " awaitingWorld=" + scaffoldAwaitSolid
                + " packet=" + scaffoldPacketYaw + "," + scaffoldPacketPitch
                + " render=" + scaffoldRenderYaw + "," + scaffoldRenderPitch
                + " renderEye=" + scaffoldRenderEyeX + "," + scaffoldRenderEyeY + "," + scaffoldRenderEyeZ
                + " stepTick=" + scaffoldNativeStepTick
                + " forecastStep=" + scaffoldEarlyStepX + "," + scaffoldEarlyStepZ
                + " nativeStep=" + scaffoldNativeStepX + "," + scaffoldNativeStepZ
                + " queued=" + (scaffoldQueuedPlacement != null));
    }

    private static void clearScaffoldPose() {
        scaffoldQueuedPlacement = null;
        scaffoldNativeStepTick = Integer.MIN_VALUE;
        modelHeadSet = false;
        scaffoldModelReady = false;
        scaffoldRenderReady = scaffoldRenderCompleted = false;
        scaffoldRenderWorld = scaffoldRenderPlayer = null;
        scaffoldModelTick = Integer.MIN_VALUE;
        scaffoldVanillaPoseTick = Integer.MIN_VALUE;
        scaffoldPoseActive = false;
        scaffoldPoseLocked = false;
        scaffoldSessionPoseChosen = false;
        scaffoldSessionYaw = scaffoldSessionPitch = 0.0f;
        scaffoldSessionBranchYaw = 0.0f;
        scaffoldSessionFace = -1;
        scaffoldSessionPitchX = scaffoldSessionPitchY = scaffoldSessionPitchZ = Integer.MIN_VALUE;
        scaffoldBridgeSession = false;
        scaffoldAimRequestedFrame = false;
        scaffoldAimFace = -1;
        scaffoldTravelFace = -1;
        scaffoldPoseFace = -1;
        scaffoldFaceCandidate = -1;
        scaffoldFaceStableTicks = 0;
        scaffoldFaceSeenTick = Integer.MIN_VALUE;
        scaffoldPacketReady = false;
        scaffoldPacketYaw = scaffoldPacketPitch = 0.0f;
        scaffoldPacketX = scaffoldPacketY = scaffoldPacketZ = 0;
        scaffoldPacketFace = -1;
        scaffoldPacketTick = Integer.MIN_VALUE;
        scaffoldAttemptTick = Long.MIN_VALUE;
        lastPlaceSucceeded = false;
    }

    private static void doScaffold(Object mc, Object player) throws Exception {
        if (scaffoldTickCommit && (!scaffoldPlacementLocked
                || !isAirAt(worldOf(player), scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ))) return;
        // Keep the bridge pose between ticks. It is cleared only when the
        // placement state actually leaves the edge/air path, which removes
        // the one-frame reset that made the model visibly twitch.
        if (ctorBlockPos == null || mIsAirBlock == null || fWorld == null || mProcessRightClickBlock == null) {
            clearScaffoldPose(); releaseScaffoldKey(); clearScaffoldPlacementLock(); scaffoldStatus="Missing placement handles"; return;
        }
        Object world = worldOf(player);
        if (world == null) { clearScaffoldPose(); releaseScaffoldKey(); clearScaffoldPlacementLock(); scaffoldStatus="World unavailable"; return; }
        // Native input does not start another use action while eating/drawing
        // a bow, and rightClickMouse refuses clicks during block breaking.
        // Check before auto-slot selection as well as at the final controller
        // boundary; swapping away from the active item would cancel it first.
        if (!scaffoldNativeUseAllowed(mc, player)) {
            scaffoldQueuedPlacement = null;
            scaffoldWhy("等待原版物品使用或挖掘结束");
            return;
        }

        int mode = (int) p(F_SCAFFOLD, 0);
        boolean onGround = fOnGround.getBoolean(player);
        boolean holdingSpace = Boolean.TRUE.equals(mKeyDown.invoke(null, 57));
        int sneakMode = (int) p(F_SCAFFOLD, 4);
        boolean active = (mode != 0 || !onGround) && (mode != 2 || holdingSpace)
                && !guiOpen && (fCurrentScreen == null || fCurrentScreen.get(mc) == null);
        if (!active) {
            releaseScaffoldKey();
            clearScaffoldPose();
            clearScaffoldPlacementLock();
            return;
        }
        boolean tower = scaffoldFootPlacement(p(F_SCAFFOLD, 6) > 0, holdingSpace, onGround);

        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
        // Grounded slabs/stairs can put the feet halfway through a block cell.
        // In the air, retain the full block clearance needed for foot placement;
        // an existing bridge lock below keeps its original support layer.
        int feetY = scaffoldSupportLayer(py, onGround);

        int tx, tz;
        double mvx = scaffoldTravelDelta(player, fPosX, fPrevX, fMotionX);
        double mvz = scaffoldTravelDelta(player, fPosZ, fPrevZ, fMotionZ);
        if (!scaffoldTickCommit) observeScaffoldJump(player, py, onGround);
        boolean horizontalIntent = scaffoldHorizontalTravelIntent(mc, player, mvx, mvz);
        if (!scaffoldTickCommit && !onGround && horizontalIntent
                && (holdingSpace || scaffoldJumpActive)) {
            // onGround/prevPosY are not an atomic take-off sample in 1.8.9.
            // Some real jumps therefore reach this callback with zero Y delta.
            // Physical movement intent is authoritative for distinguishing a
            // running jump from a stationary tower; latch it until landing.
            latchScaffoldRunningJump(player, py, mvx, mvz);
        }
        boolean jumpTravel = scaffoldRunningJump(onGround, scaffoldJumpActive,
                scaffoldJumpTravelActive);
        // Space while travelling is a running jump, not a stationary tower.
        if (jumpTravel || (onGround && mvx*mvx + mvz*mvz > 1.0e-6)) tower = false;
        else if (!onGround && scaffoldJumpActive) tower = true;
        if (!scaffoldHeadingReady) { scaffoldHeading = scaffoldCameraYaw(player); scaffoldHeadingReady = true; }
        int preferredFace = scaffoldTravelFace;
        double moveSq = mvx * mvx + mvz * mvz;
        // Keep the last real travel heading. Sneak can reduce motion almost to
        // zero at the edge; falling back to the free camera there selects a
        // different cell and loses the pending bridge target.
        if (moveSq > 1.0e-6) {
            scaffoldHeading = (float) Math.toDegrees(Math.atan2(-mvx, mvz));
        }
        double travelX = mvx, travelZ = mvz;
        if (jumpTravel && moveSq <= 1.0e-6
                && scaffoldJumpTravelX*scaffoldJumpTravelX
                + scaffoldJumpTravelZ*scaffoldJumpTravelZ > 1.0e-6) {
            // Keep the take-off direction through the apex and through the
            // brief zero-motion sample that used to reclassify this as Tower.
            travelX = scaffoldJumpTravelX;
            travelZ = scaffoldJumpTravelZ;
        } else if (moveSq <= 1.0e-6) {
            double remembered = Math.toRadians(scaffoldHeading);
            travelX = -Math.sin(remembered);
            travelZ = Math.cos(remembered);
        }
        if (jumpTravel && moveSq > 1.0e-6) {
            scaffoldJumpTravelX = mvx;
            scaffoldJumpTravelZ = mvz;
        }
        if ((moveSq > 1.0e-6 || jumpTravel) && !scaffoldTickCommit) {
            preferredFace = scaffoldTravelFace(travelX, travelZ, scaffoldTravelFace);
            if (!tower && scaffoldSessionPoseChosen && preferredFace != scaffoldTravelFace) {
                // Only a submitted placement owns an immutable target. An
                // unsent candidate must remain replaceable when travel changes.
                if (scaffoldPlacementLocked && scaffoldAwaitSolid) {
                    preferredFace = scaffoldLockedFace >= 0
                            ? scaffoldLockedFace : scaffoldTravelFace;
                } else {
                    clearScaffoldPlacementLock();
                    scaffoldSessionPoseChosen = false;
                    scaffoldPacketReady = false;
                    scaffoldRenderReady = scaffoldRenderCompleted = false;
                    scaffoldAimFace = preferredFace;
                }
            }
        }
        if (tower) preferredFace = 0;
        if (preferredFace >= 0) scaffoldTravelFace = preferredFace;
        if (tower) {
            // 垫塔只取当前脚下腾出的格，不做水平预测。
            tx = (int) Math.floor(px);
            tz = (int) Math.floor(pz);
        } else if (p(F_SCAFFOLD, 7) > 0) {
            // Only Below 指玩家碰撞箱最前沿正下方，而不是玩家中心正下方。
            // Edge 潜行会把中心挡在旧方块内；旧实现又用中心 floor 取目标，
            // 于是目标永远是正在站的实心块，潜行与目标选择形成死锁。
            // 用 0.3002（玩家半宽 0.30 加极小 epsilon）投影前脚：只有真的
            // 到边界时它才跨进下一格，平地上仍然指向当前支撑格。
            double leadX = px, leadZ = pz;
            final double footReach = 0.3002;
            if (Math.abs(travelX) > 1.0e-4) leadX += Math.copySign(footReach, travelX);
            if (Math.abs(travelZ) > 1.0e-4) leadZ += Math.copySign(footReach, travelZ);
            tx = (int) Math.floor(leadX);
            tz = (int) Math.floor(leadZ);
        } else {
            // **按实际走的方向往前推 Look Ahead 格 —— 9/28 19:44 那版的原始逻辑, 还原。**
            //
            // 我曾经把它改成"锁死脚下那一格", 以为那是"离边界太远就放"的解药。错了:
            // 目标格是 (tx, feetY, tz), 而 feetY 已经是脚底下那一层 —— 所以"脚下正下方"
            // 和"往前推一格"铺出来的是**同一层**, 区别只在铺在身前还是身下。
            // 推一格才是真人搭路的样子: 在**前面那一格**先铺好, 等你走到时地已经在, 走起来
            // 是连贯的; 只铺正下方则要等你踩空才动手, 每次慢半拍, 一顿一顿的。
            //
            // 方向必须用"实际走的方向"(由位移反推), 不是视线方向 —— 搭路时视线朝后。
            // 人的移动方向在 MC 里是 dx = -sin(yaw)*f, dz = cos(yaw)*f, 所以:
            //     yaw = atan2(-dx, dz)
            // 位移太小 (刚开始走 / 卡住) 时才退回视线方向。
            double yaw;
            if (moveSq > 1.0e-6) {
                yaw = Math.atan2(-mvx, mvz);
            } else {
                yaw = Math.toRadians(scaffoldHeading);
            }
            int heading = (int) p(F_SCAFFOLD, 19);
            if (heading == 2) yaw = Math.toRadians(scaffoldCameraYaw(player));
            else if (heading == 1) yaw = Math.toRadians(scaffoldHeading);
            double prediction = Math.max(0.0, p(F_SCAFFOLD, 24)) * Math.sqrt(moveSq);
            int ahead = Math.max(1, (int) Math.round(p(F_SCAFFOLD, 3) + prediction));
            tx = (int) Math.floor(px - Math.sin(yaw) * ahead);
            tz = (int) Math.floor(pz + Math.cos(yaw) * ahead);
        }

        // Once the leading foot has reached the edge, keep the exact pending
        // cell until the client confirms that it became solid. Recomputing a
        // look-ahead cell while the player is already braking can otherwise
        // move the click one block forward and release sneak too early.
        if (tower && scaffoldJumpActive && !scaffoldTickCommit && scaffoldPlacementLocked
                && (scaffoldLockedX != tx || scaffoldLockedY != feetY || scaffoldLockedZ != tz)) {
            clearScaffoldPlacementLock();
            scaffoldPacketReady = scaffoldRenderReady = scaffoldRenderCompleted = false;
        }
        if (jumpTravel && active && !scaffoldTickCommit) {
            // Candidate gaps must still honor a hard Sneak-Off immediately.
            if (sneakMode == 0) releaseScaffoldKey();

            // A successful click waits for world confirmation. Before any
            // click, rising feet may acquire a higher connected support cell.
            if (scaffoldPlacementLocked && !isAirAt(world,
                    scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ)) {
                scaffoldJumpFloorY = Math.max(scaffoldJumpFloorY, scaffoldLockedY);
                clearScaffoldPlacementLock();
            }
            if (!scaffoldAwaitSolid) {
                int[] jumpTarget = scaffoldJumpTarget(world, px, py, pz,
                        travelX, travelZ, preferredFace);
                if (jumpTarget != null) {
                    lockScaffoldPlacementTarget(jumpTarget[0], jumpTarget[1],
                            jumpTarget[2], jumpTarget[3]);
                } else if (!scaffoldPlacementLocked) {
                    holdScaffoldPose();
                    scaffoldWhy("跑跳: 等待脚底可连接的支撑面");
                    return;
                }
            }
        }
        if (scaffoldPlacementLocked) {
            boolean pendingAir;
            try {
                pendingAir = isAirAt(world, scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ);
            } catch (Throwable t) {
                pendingAir = true;
            }
            if (!pendingAir) {
                if (scaffoldJumpActive)
                    scaffoldJumpFloorY = Math.max(scaffoldJumpFloorY, scaffoldLockedY);
                clearScaffoldPlacementLock();
            } else {
                tx = scaffoldLockedX;
                feetY = scaffoldLockedY;
                tz = scaffoldLockedZ;
                if (scaffoldLockedFace >= 0) preferredFace = scaffoldLockedFace;
            }
        }
        // The placement face is solved after the physical edge gate below.
        // During the approach we solve the immediately adjacent air cell
        // separately so the model/network pose can turn before placement is due.
        double[] aim = null;

        boolean moving = fMovingF.getFloat(player) != 0.0f || fStrafe.getFloat(player) != 0.0f;
        // Edge detection follows the actual travel vector.  The old four-corner
        // probe treated a side corner as the leading edge, so Sneak and the
        // placement pose could start while the player still had a full step.
        // Use motion when available and fall back to the current camera heading
        // only during the first stationary frame.
        double dirX = travelX, dirZ = travelZ;
        double dirLen = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (dirLen > 1.0e-6) { dirX /= dirLen; dirZ /= dirLen; }
        boolean edgeNow = isAirAt(world, (int) Math.floor(px), feetY, (int) Math.floor(pz))
                || atEdge(world, px, feetY, pz, dirX, dirZ, p(F_SCAFFOLD, 13));
        boolean lockedPending = scaffoldPlacementLocked;
        // A failed placement never times out while the player is hanging over
        // air.  The only non-placement release path is a deliberate retreat to
        // a fully supported centre position away from the pending cell.
        if (lockedPending && !tower && !edgeNow) {
            int safeY = scaffoldSupportLayer(py, onGround);
            boolean centreSupported = !isAirAt(world, (int) Math.floor(px), safeY, (int) Math.floor(pz));
            double lx = px - (scaffoldLockedX + 0.5), lz = pz - (scaffoldLockedZ + 0.5);
            double towardLocked = -lx * dirX - lz * dirZ;
            // An approach lock is intentionally acquired while the player is
            // still safely on the last block.  Release it only after a real
            // retreat, never merely because the target is still ahead.
            if (!scaffoldAwaitSolid && centreSupported
                    && lx*lx + lz*lz > 0.36 && towardLocked < -0.05) {
                clearScaffoldPlacementLock();
                lockedPending = false;
            }
        }
        boolean wantSneak = active && !tower && (sneakMode == 1
                || (sneakMode == 2 && scaffoldEdgeSneakActive));
        // Once the leading foot has reached the edge, keep the bridge session
        // latched for the whole forward run.  It is a session state, not a
        // render-frame countdown; high FPS must not shorten it between blocks.
        boolean bridgeContinuation = active && scaffoldBridgeSession
                && scaffoldPoseActive;
        if (active && moving && (wantSneak || edgeNow)) {
            scaffoldBridgeSession = true;
            bridgeContinuation = true;
        }
        // Keep No Sprint active for the complete Scaffold session.  Waiting
        // until edgeNow was too late: the approach movement had already used
        // sprint speed and could skip the one rendered side-face interval.
        if (scaffoldNoSprintActive() && mSetSprinting != null
                && (mIsSprinting == null || Boolean.TRUE.equals(mIsSprinting.invoke(player)))) {
            mSetSprinting.invoke(player, false);
        }
        // Pose continuity must not force permanent sneak.  Sneak owns only the
        // physical edge/pending-placement safety interval.
        // Sneak=Off is a hard opt-out.  A pending/rejected placement may keep
        // the target and model pose, but it must never turn automatic Shift
        // back on behind the user's choice.  Manual Shift is preserved by
        // releaseScaffoldKey through physicalKeyDown().
        boolean sneakReady = scaffoldSneak(player,
                wantSneak || (sneakMode == 1 && lockedPending));
        // Select blocks during the approach, not after the side-ray window has
        // already opened. Slot Delay is still checked at the interaction gate.
        if (!scaffoldTickCommit && !prepareScaffoldSlot(player)) return;
        // Start the backward/downward bridge pose while the player is still on
        // the last complete support block.  Placement remains edge-gated below,
        // and the real model/packet ray is still checked against the side face.
        // This only gives the configured turn-rate enough ticks to arrive.
        boolean approachRequested = false;
        boolean approachReady = false;
        boolean flatGroundAhead = false;
        if (!tower && !jumpTravel && moving && !edgeNow && !lockedPending && p(F_SCAFFOLD, 24) > 0) {
            int[] approachCell = scaffoldApproachCell(px, pz, dirX, dirZ);
            if (approachCell != null) {
                if (isAirAt(world, approachCell[0], feetY, approachCell[1])) {
                    double[] approachAim = findAimPoint(world, approachCell[0], feetY,
                            approachCell[1], preferredFace, false);
                    if (approachAim != null) {
                        approachRequested = true;
                        int approachFace = (int) approachAim[3];
                        // Lock the real cell and Y layer before the player
                        // leaves support.  Falling can no longer move the
                        // target down a level or make all six neighbours air.
                        lockScaffoldPlacementTarget(approachCell[0], feetY,
                                approachCell[1], approachFace);
                        tx = approachCell[0];
                        tz = approachCell[1];
                        preferredFace = approachFace;
                        lockedPending = true;
                        aim = approachAim;
                        approachReady = aimAtEdge(player, approachAim,
                                approachCell[0], feetY, approachCell[1]);
                        if (approachReady) {
                            scaffoldBridgeSession = true;
                            bridgeContinuation = true;
                        }
                    }
                } else if (bridgeContinuation) {
                    flatGroundAhead = scaffoldFlatGroundAhead(world, px, feetY, pz, dirX, dirZ);
                }
            }
        }
        // Only Below identifies the next air cell as soon as the leading foot
        // crosses the block boundary.  Keep that physical edge as the placement
        // trigger.  The former eye-origin gate waited until the player's centre
        // had crossed the support face, which is too late without auto Sneak.
        if (!tower && !edgeNow && !lockedPending) {
            if (approachRequested) {
                if (!approachReady) holdScaffoldPose();
                scaffoldWhy("接近边缘: 模型提前转向方块侧面");
            } else if (bridgeContinuation) {
                if (flatGroundAhead) {
                    clearScaffoldPose();
                    scaffoldWhy("平地已恢复原版模型动作");
                } else {
                    holdScaffoldPose();
                    scaffoldWhy("桥接姿势保持中: 等待前脚进入下一格");
                }
            } else {
                clearScaffoldPose();
                scaffoldWhy("尚未到达方块边界");
            }
            return;
        }
        if (!tower && sneakMode != 0 && !wantSneak && !bridgeContinuation && !lockedPending) {
            clearScaffoldPose();
            scaffoldWhy("尚未进入边缘范围");
            return;
        }

        if (mode == 0 && onGround) { clearScaffoldPose(); scaffoldWhy("Mode=Falling 但人在地面 (切 Always)"); return; }
        if (mode == 2 && !holdingSpace) { clearScaffoldPose(); scaffoldWhy("Mode=Hold Space 但没按空格"); return; }
        // 站着不动就不用铺 (垫塔除外) —— 否则会在原地反复右键
        //
        // 我加过一条"悬空时例外", 也去掉了 —— 回到 14:59 那版。
        // ceil(py)-1 之下, 人悬空时目标格仍指回原来那一层, 所以这条守卫并不会把人锁死;
        // 而放宽它反而让"站着空转也铺"变成可能。
        if (p(F_SCAFFOLD, 16) > 0 && !tower && !scaffoldPlacementLocked
                && fMovingF.getFloat(player) == 0.0f && fStrafe.getFloat(player) == 0.0f) {
            clearScaffoldPose();
            scaffoldWhy("没在移动: moveForward=" + fMovingF.getFloat(player)
                    + " moveStrafe=" + fStrafe.getFloat(player) + " onGround=" + onGround);
            return;
        }

        // From here on the target is physically eligible for placement.  Solve
        // the exact selected support face again; the approach target never
        // substitutes for this final geometry.
        aim = findAimPoint(world, tx, feetY, tz, preferredFace, tower || (jumpTravel && preferredFace == 0));

        if (aim == null) {
            if (bridgeContinuation) {
                holdScaffoldPose();
                scaffoldWhy("桥接姿势保持中: 尚未找到新的依附面");
                return;
            }
            clearScaffoldPose();
            scaffoldWhy("目标格周围 6 面全是空气 —— 没有可以依附的面, 放不了");
            return;
        }
        if (!isAirAt(world, tx, feetY, tz)) {
            if (bridgeContinuation) {
                holdScaffoldPose();
                scaffoldWhy("桥接姿势保持中: 等待下一格进入空气");
                return;
            }
            clearScaffoldPose();
            scaffoldWhy("目标格 (" + tx + "," + feetY + "," + tz + ") 不是空气 —— 站在平地上就会这样, 正常");
            return;
        }
        // Lock the target before waiting for the sneak/side handshake. From
        // this point until the block is observed as solid, every return keeps
        // the sneak binding owned and re-emits the same model pose.
        int aimFace = (int) aim[3];
        lockScaffoldPlacementTarget(tx, feetY, tz, aimFace);
        // From this frame the rendered model and the outgoing interaction pose
        // share one owner and one yaw/pitch.  Losing arbitration to KillAura
        // leaves modelHeadSet false, so local and network poses cannot diverge.
        if (!aimAtEdge(player, aim, tx, feetY, tz)) {
            // aimAtEdge retains the last published pose while a new packet is
            // pending. Clearing it here flashed the model back to the camera.
            scaffoldWhy("等待朝向所有权");
            return;
        }
        if (!scaffoldTickCommit) return;
        // Running jumps only fill cells with enough real clearance. The
        // controller remains authoritative for held-item and entity collision.
        if (jumpTravel && feetY + 1.0 > py + 1.0e-7) return;
        long scaffoldTick = scaffoldClock(player);
        double[] packetHit = scaffoldWorldFaceHit(world,
                px, py + eyeH(player), pz, scaffoldPacketYaw, scaffoldPacketPitch,
                tx, feetY, tz, aimFace, p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10));
        boolean sideVisible = packetHit != null;
        // Every rotation mode uses the same final geometric requirement: the
        // actual model/outgoing-packet ray must enter the selected support face.
        // Free Camera changes only what the local camera displays.
        if (sideVisible) {
            scaffoldPoseLocked = true;
        }
        int sideConfirm = Math.max(1, (int) p(F_SCAFFOLD, 30));
        boolean sideConfirmed = scaffoldConfirmSide((int)scaffoldTick, sideVisible, sideConfirm);
        if (wantSneak && !sneakReady) {
            scaffoldWhy("已请求潜行, 等待玩家更新确认后再放置");
            holdScaffoldPose();
            return;
        }
        if (!sideConfirmed) {
            double normalSpeed = (aimFace == 2 || aimFace == 3)
                    ? Math.abs(mvx) : Math.abs(mvz);
            double outside = scaffoldFaceOutsideDistance(px, pz, tx, tz, aimFace);
            scaffoldWhy("等待侧面射线: target=(" + tx + "," + feetY + "," + tz
                    + ") face=" + aimFace + " pose=(" + modelHeadYaw + "," + modelHeadPitch
                    + ") normalSpeed=" + String.format("%.3f", normalSpeed)
                    + " outside=" + String.format("%.3f", outside)
                    + " confirm=" + sideConfirm + " tick");
            holdScaffoldPose();
            return;
        }
        // The world trace already bounds reach to the actual surface hit;
        // distance to an empty target-cell centre is not an interaction range.

        if (scaffoldSlotTick != Integer.MIN_VALUE && fTicksExisted != null
                && fTicksExisted.getInt(player) - scaffoldSlotTick < (int) p(F_SCAFFOLD, 27)) return;

        long now = System.currentTimeMillis();
        long attemptTick = scaffoldClock(player);
        // Successful placements retain the user-facing millisecond Delay.  A
        // rejected interaction uses game ticks: a nominal 50 ms wall-clock
        // check could observe 49 ms and skip a whole extra tick, turning one
        // retry into 100 ms.  Converting Retry(ms) to ticks makes 0/50 ms mean
        // the next tick while preserving larger deliberate retry intervals.
        if (lastPlaceSucceeded) {
            long delay = (long) Math.max(0.0, p(F_SCAFFOLD, 2));
            // 【移除人类反应延迟】这个延迟会导致回弹
            // 原因：添加延迟后，视角已经转到位了，但放置动作延后了
            // 在这个延迟期间，玩家继续向前移动，导致放置时已经超出了合理范围
            // 服务端检测到"视角对准 + 移动距离"不匹配，判定为作弊并回弹
            if (now - lastScaffold < delay) return;
        } else if (scaffoldAttemptTick != Long.MIN_VALUE) {
            long retryTicks = scaffoldRetryTicks(p(F_SCAFFOLD, 25));
            if (attemptTick - scaffoldAttemptTick < retryTicks) return;
        }
        // The module is evaluated at render cadence.  With Delay=0 a 144 Hz
        // client used to send several identical interactions before one game
        // tick could update the target.  Zero delay means "this tick", not
        // "every rendered frame".
        if (attemptTick == scaffoldAttemptTick) return;
        // The displayed ray authorizes the face, not stale hit coordinates.
        // Vanilla refreshes getMouseOver(1.0F) before input; the click uses the
        // current tick's eye and confirmed interaction pose.
        if (scaffoldCommitHit == null || (int) scaffoldCommitHit[3] != aimFace) return;
        scaffoldAttemptTick = attemptTick;
        lastScaffold = now;
        rightClickBlock(mc, player, world, scaffoldCommitHit, tx, feetY, tz);
        if (lastPlaceSucceeded || now - scaffoldPlaceLogAt >= 250L) {
            scaffoldPlaceLogAt = now;
            log("Scaffold 放置交互: target=(" + tx + "," + feetY + "," + tz
                    + ") face=" + aimFace + " outside="
                    + String.format("%.3f", scaffoldFaceOutsideDistance(px, pz, tx, tz, aimFace))
                    + " result=" + lastPlaceResult + " success=" + lastPlaceSucceeded);
        }
        // A rejected click keeps the exact target, model pose and sneak lock for
        // Retry(ms), but it must not animate a successful swing or alter motion.
        // Physical Space remains the sole source of Tower jumping.
        if (lastPlaceSucceeded) {
            recordScaffoldPlacement(player, world, tx, feetY, tz, aimFace);
            scaffoldBridgeSession = true;
            if (scaffoldJumpActive && !isAirAt(world, tx, feetY, tz))
                scaffoldJumpFloorY = Math.max(scaffoldJumpFloorY, feetY);
            // processRightClickBlock=true only means the interaction was
            // accepted locally. Keep the edge hold until the client world
            // actually observes this target as a solid block.
            scaffoldAwaitSolid = true;
            if (p(F_SCAFFOLD, 5) > 0) swingHand(player);
            // processRightClickBlock returned an explicit accepted result.
            // End the synthetic Shift in this same call; a rejected click
            // leaves both the target lock and Shift protection untouched.
            releaseScaffoldKey();
        }
        scaffoldWhy("已经发出放置: 目标格 (" + tx + "," + feetY + "," + tz + ") 依附面 idx=" + (int) aim[3]
                + " 面=" + NB6_FACE[(int) aim[3]] + " 结果=" + lastPlaceResult);

        // **Keep Slot 不再在每次放置后立刻切回去。**
        //
        // 原来这里放完就 fCurrentItem = scaffoldPrevSlot, 于是每一轮变成:
        //     手上1 -> 发现方块在2 -> 切2 -> return
        //     手上2 -> 放 -> 切回1
        //     手上1 -> 又切2 -> return ...
        // 一半的帧花在来回切槽上, 而 Delay 门限 (lastScaffold) 在切槽那次就已经被消耗掉了,
        // 叠起来把放块成功率砍掉一大半。日志里的 "方块切换回原位 2 (上一帧拿 1)" 就是这个循环。
        //
        // Keep Slot 的本意是"别把你手上的东西换掉", 不是"每放一块翻一次手"。所以改成:
        // 只记住原来那只手, 等 **Scaffold 被关掉** 的时候再换回去 (见 setMod 里的复位)。
        // 搭路期间就一直握着方块 —— 这也更像真人。
    }

    /**
     * Forecast only the next eye origin; never write position, motion or input.
     * The final packet and interpolated render rays remain authoritative. Keep
     * yaw on the bridge heading while pitch follows ascent/descent instead of
     * freezing the take-off angle or turning toward each top-face centre.
     */
    private static void prepareScaffoldMovementAim(Object player) throws Exception {
        if (!scaffoldPlacementLocked || p(F_SCAFFOLD, 9) <= 0) return;
        boolean grounded = fOnGround.getBoolean(player);
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);
        boolean moving = (fMovingF.getFloat(player) != 0 || fStrafe.getFloat(player) != 0)
                && (!grounded || mx*mx + mz*mz >= 1.0e-8);
        boolean sprint = mIsSprinting != null && Boolean.TRUE.equals(mIsSprinting.invoke(player));
        double accel = moving ? (grounded ? (sprint ? .1274 : .098) : (sprint ? .02548 : .0196)) : 0;
        double heading = Math.toRadians(scaffoldHeading);
        double dx = mx - Math.sin(heading) * accel;
        double dz = mz + Math.cos(heading) * accel;
        scaffoldEarlyStepX = dx; scaffoldEarlyStepZ = dz;
        Object world = worldOf(player);
        // A threatened straight step is shortened by vanilla's sneak clipping.
        // Plan from that reachable endpoint; aiming from the untrimmed endpoint
        // can keep the player safely stopped while the ray misses forever.
        double[] clipped = scaffoldPredictSneakStep(player, world, dx,
                fMotionY == null ? 0 : fMotionY.getDouble(player), dz);
        dx = clipped[0]; dz = clipped[1];
        double ey = fPosY.getDouble(player) + eyeH(player)
                + (grounded || fMotionY == null ? 0 : fMotionY.getDouble(player));
        float yaw = scaffoldSessionPoseChosen ? scaffoldSessionYaw
                : scaffoldHeading + 180f;
        prepareScaffoldStepAim(player, world, dx, dz, ey, yaw, grounded);
    }

    /** Both early preparation and the native movement boundary use the same
     * ray planner. At the boundary dx/dz come from Entity.move, not an assumed
     * acceleration or the previous tick's input. */
    private static void prepareScaffoldStepAim(Object player, Object world,
            double dx, double dz, double ey, float yaw, boolean grounded) throws Exception {
        int face = scaffoldLockedFace;
        double ex = fPosX.getDouble(player) + dx;
        double ez = fPosZ.getDouble(player) + dz;
        float pitch = scaffoldSessionPoseChosen ? scaffoldSessionPitch : silentPitch;
        float[] pose = null;
        if (face >= 2 && face < NB6.length) {
            double startX = fPosX.getDouble(player), startY = fPosY.getDouble(player) + eyeH(player);
            double startZ = fPosZ.getDouble(player);
            float previous = scaffoldModelReady ? scaffoldModelPitch : sentPitch;
            float displayPt = scaffoldNextRenderPartial();
            // Timer.updateTimer has already computed the upcoming render's
            // fraction before Minecraft runs its elapsed player ticks. Prefer
            // a pose that can actually be displayed now over a wider interval
            // which the next low-FPS frame would skip altogether.
            if (Float.isFinite(displayPt)) {
                float[] immediate = scaffoldFrameSafeFaceAngles(world, startX, startY, startZ,
                        ex, ey, ez, yaw, previous, pitch, false,
                        scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, face, displayPt);
                if (immediate != null && scaffoldWorldFaceHit(world,
                        startX+(ex-startX)*displayPt, startY+(ey-startY)*displayPt,
                        startZ+(ez-startZ)*displayPt, yaw,
                        previous+(immediate[1]-previous)*displayPt,
                        scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, face,
                        p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10)) != null) pose = immediate;
            }
            int lead = 1;
            double speed = scaffoldFaceOutwardSpeed(dx, dz, face);
            double threshold = Math.max(0.0, Math.min(.20, p(F_SCAFFOLD, 29))) + .0002;
            // Prepare BEFORE the crossing tick. A ray that first reaches the
            // side at the next tick's endpoint may never be displayed: native
            // rendering interpolates both the eye position and the old pitch.
            // This is an aim forecast only; vanilla owns all actual motion.
            if (pose == null && grounded && speed > .005) {
                while (lead < 8 && scaffoldFaceOutsideDistance(ex, ez,
                        scaffoldLockedX, scaffoldLockedZ, face) < threshold) {
                    startX = ex; startZ = ez; ex += dx; ez += dz; lead++;
                }
                // Prepare the FIRST exposed sample. Skipping it for a later,
                // wider sample makes the previous pitch too shallow for the
                // first actual frame, even when that later endpoint is valid.
            }
            if (pose == null) pose = scaffoldFrameSafeFaceAngles(world, startX, startY, startZ, ex, ey, ez,
                    yaw, previous, pitch, lead > 1,
                    scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, face);
        }
        if (pose == null) pose = scaffoldWorldFaceAngles(world, ex, ey, ez, yaw, pitch,
                scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, face, false);
        if (pose == null && face == 0) {
            for (float candidate = 89f; candidate >= 55f; candidate -= 1f) {
                if (scaffoldWorldFaceHit(worldOf(player), ex, ey, ez, yaw, candidate,
                        scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, face,
                        p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10)) != null) {
                    pose = new float[]{yaw, candidate}; break;
                }
            }
        }
        if (pose != null) {
            if (scaffoldSessionPoseChosen) scaffoldSessionPitch = pose[1];
            requestSilentNetworkOnly(yaw, pose[1], 1);
        }
    }

    private static float scaffoldNextRenderPartial() {
        if (mGetMC == null || fTimer == null || fScaffoldRenderPartial == null) return Float.NaN;
        try {
            Object mc = mGetMC.invoke(null);
            Object timer = mc == null ? null : fTimer.get(mc);
            float pt = timer == null ? Float.NaN : fScaffoldRenderPartial.getFloat(timer);
            return Float.isFinite(pt) && pt >= 0 && pt <= 1 ? pt : Float.NaN;
        } catch (Throwable ignored) { return Float.NaN; }
    }

    /** Select a pitch with a real displayed side-hit interval, not just an
     * endpoint hit. Native world tracing decides every candidate and every
     * interpolated sample, including slabs, stair risers and occluders. Yaw
     * stays untouched. A future crossing has time to display the prepared
     * pitch; an immediate crossing must interpolate from the existing model. */
    static float[] scaffoldFrameSafeFaceAngles(Object world,
            double sx, double sy, double sz, double ex, double ey, double ez,
            float yaw, float previousPitch, float preferredPitch, boolean preparedEarly,
            int tx, int ty, int tz, int face) {
        return scaffoldFrameSafeFaceAngles(world, sx, sy, sz, ex, ey, ez,
                yaw, previousPitch, preferredPitch, preparedEarly, tx, ty, tz, face, Float.NaN);
    }

    private static float[] scaffoldFrameSafeFaceAngles(Object world,
            double sx, double sy, double sz, double ex, double ey, double ez,
            float yaw, float previousPitch, float preferredPitch, boolean preparedEarly,
            int tx, int ty, int tz, int face, float displayPt) {
        if (face < 2 || face >= NB6.length) return null;
        int[] d = NB6[face];
        double bx = tx + d[0], by = ty + d[1], bz = tz + d[2];
        double axis = face <= 3 ? -Math.sin(Math.toRadians(yaw)) : Math.cos(Math.toRadians(yaw));
        if (Math.abs(axis) < 1.e-7) return null;
        double outer = face == 2 ? bx+1 : face == 3 ? bx : face == 4 ? bz+1 : bz;
        double[] planes = {outer, (face <= 3 ? bx : bz)+.5};
        double[] heights = {Math.max(.025, Math.min(.975, p(F_SCAFFOLD, 28))),
                .875, .625, .375, .125, .49, .51, .025, .975};
        float[] candidates = new float[1+planes.length*heights.length*2];
        int count = 0; candidates[count++] = preferredPitch;
        for (double plane : planes) {
            double distance = (plane - (face <= 3 ? ex : ez)) / axis;
            if (distance <= 0) continue;
            for (double height : heights)
                candidates[count++] = (float)Math.toDegrees(Math.atan2(ey-(by+height), distance));
            if (Float.isFinite(displayPt) && displayPt > .001f) {
                double displayAxis = (face <= 3 ? sx+(ex-sx)*displayPt : sz+(ez-sz)*displayPt);
                double displayDistance = (plane-displayAxis)/axis;
                if (displayDistance > 0) for (double height : heights) {
                    float displayed = (float)Math.toDegrees(Math.atan2(sy+(ey-sy)*displayPt-(by+height), displayDistance));
                    float candidate = previousPitch+(displayed-previousPitch)/displayPt;
                    if(candidate>=-90 && candidate<=90)candidates[count++]=candidate;
                }
            }
        }
        double reveal = p(F_SCAFFOLD, 29), reach = p(F_SCAFFOLD, 10);
        int bestFrames = 0;
        float bestPitch = preferredPitch;
        double bestTurn = Double.POSITIVE_INFINITY;
        boolean bestDisplayed = false;
        for (int c = 0; c < count; c++) {
            float candidate = candidates[c];
            if (scaffoldWorldFaceHit(world, ex, ey, ez, yaw, candidate,
                    tx, ty, tz, face, reveal, reach) == null) continue;
            int frames = 0;
            for (int sample = 16; sample >= 0; sample--) {
                float pt = sample / 16f;
                float renderedPitch = preparedEarly ? candidate
                        : previousPitch + (candidate-previousPitch)*pt;
                if (scaffoldWorldFaceHit(world, sx+(ex-sx)*pt, sy+(ey-sy)*pt,
                        sz+(ez-sz)*pt, yaw, renderedPitch,
                        tx, ty, tz, face, reveal, reach) == null) break;
                frames++;
            }
            double turn = Math.abs(candidate-preferredPitch);
            boolean displayed = Float.isFinite(displayPt) && scaffoldWorldFaceHit(world,
                    sx+(ex-sx)*displayPt, sy+(ey-sy)*displayPt, sz+(ez-sz)*displayPt,
                    yaw, previousPitch+(candidate-previousPitch)*displayPt,
                    tx, ty, tz, face, reveal, reach) != null;
            if ((displayed && !bestDisplayed) || (displayed == bestDisplayed
                    && (frames > bestFrames || (frames == bestFrames && turn < bestTurn)))) {
                bestFrames = frames; bestTurn = turn; bestPitch = candidate;
                bestDisplayed = displayed;
                // The preferred pitch is tested first. Full-frame coverage
                // cannot be improved; avoid tracing every alternative while
                // stationary or moving slowly along an already valid face.
                if (c == 0 && frames == 17 && (!Float.isFinite(displayPt) || displayed))
                    return new float[]{yaw, candidate};
            }
        }
        return bestFrames == 0 ? null : new float[]{yaw, bestPitch};
    }

    /**
     * 计算原版搭路时的依附面角度。相机仍保持玩家自己的视角，
     * 但第三人称模型和出站交互包使用同一组 yaw/pitch；移动输入、rotationYaw
     * 和腿部/摆臂动画不在这里改写。
     */
    private static boolean aimAtEdge(Object player, double[] aim, int cellX, int cellY, int cellZ) throws Exception {
        int rotMode = (int) p(F_SCAFFOLD, 1);
        int face = aim != null && aim.length > 3 ? (int) aim[3] : -1;
        if (face >= 2 && face < NB6.length) {
            aim[1] = cellY + Math.max(0.10, Math.min(0.95, p(F_SCAFFOLD, 28)));
        }
        if (scaffoldTickCommit) {
            if (!scaffoldPacketReady || scaffoldPacketTick != (int) scaffoldClock(player)
                    || scaffoldPacketX != cellX || scaffoldPacketY != cellY
                    || scaffoldPacketZ != cellZ || scaffoldPacketFace != face) return false;
            modelHeadYaw = scaffoldPacketYaw;
            modelHeadPitch = scaffoldPacketPitch;
            return true;
        }
        // Keep the click point on the real support face. Side Reveal controls
        // how far the eye must cross that face; it must never pull the hit vec
        // off the plane. Hit Height remains a real, user-visible control.
        double eyeX = fPosX.getDouble(player);
        double eyeY = fPosY.getDouble(player) + eyeH(player);
        double eyeZ = fPosZ.getDouble(player);
        double motionX = fMotionX == null ? 0.0 : fMotionX.getDouble(player);
        double motionZ = fMotionZ == null ? 0.0 : fMotionZ.getDouble(player);
        float[] hr = scaffoldFaceAngles(eyeX, eyeY, eyeZ,
                aim[0], aim[1], aim[2], cellX, cellY, cellZ, face,
                p(F_SCAFFOLD, 29), motionX, motionZ);
        // Aim Pitch=Off 保留玩家当前俯仰；Body=Move 保留玩家当前水平朝向。
        boolean aimPitch = p(F_SCAFFOLD, 9) > 0;
        boolean bodyAim = (int) p(F_SCAFFOLD, 12) == 0;
        // Body selects only the rendered torso behavior.  The model head and
        // outgoing interaction must use the real support-face yaw in both modes.
        float networkYaw = hr[0] + (float) p(F_SCAFFOLD, 20);
        // In 1.8.9 rotationPitch is positive when looking down.  The previous
        // negative clamp selected an upward pitch for every horizontal bridge,
        // which is the visible "turn and look up" regression.  Keep the
        // calculated support-face pitch, but enforce the configured minimum
        // downward angle in the same (positive) convention.
        float downPitch = Math.abs((float) p(F_SCAFFOLD, 18));
        // Horizontal yaw is session-stable. Pitch is solved below from the
        // current target cell and its next real movement sample, because one
        // fixed pitch cannot cover both walking and sprinting side windows.
        float networkPitch = aimPitch
                ? (face >= 2 ? downPitch : Math.max(hr[1], downPitch))
                : fPitch.getFloat(player);
        double tolerance = Math.max(0.5, p(F_SCAFFOLD, 23));
        int tick = fTicksExisted == null ? (int)(System.currentTimeMillis()/50L)
                : fTicksExisted.getInt(player);
        if (rotMode == 0) {
            // Rotation Off uses the player's real, already-sent look.  It is a
            // functional manual-aim mode: placement proceeds only when that
            // look ray reaches the support face, and no model field is forced.
            // Render evaluation must not invent a new packet receipt or clear
            // this tick's attempt/retry counters. Release only the model pose.
            modelHeadSet = scaffoldModelReady = scaffoldPoseActive = false;
            scaffoldSessionPoseChosen = false;
            scaffoldSessionFace = -1;
            scaffoldAimRequestedFrame = false;
            networkYaw = fYaw.getFloat(player);
            networkPitch = fPitch.getFloat(player);
            if (scaffoldPacketPreparing) {
                scaffoldPacketYaw = networkYaw;
                scaffoldPacketPitch = networkPitch;
                scaffoldPacketTick = tick;
                scaffoldPacketX = cellX; scaffoldPacketY = cellY; scaffoldPacketZ = cellZ;
                scaffoldPacketFace = face;
                scaffoldPacketReady = true;
            }
            modelHeadYaw = networkYaw;
            modelHeadPitch = networkPitch;
            return true;
        }
        if (face >= 2) {
            boolean newSessionFace = !scaffoldSessionPoseChosen || scaffoldSessionFace != face;
            if (newSessionFace) {
                scaffoldSessionPoseChosen = true;
                // The eye must point exactly back through the selected face.
                // Using the predicted hit point's yaw retained diagonal motion
                // and produced headings such as -193 degrees for an X bridge.
                float rawFaceYaw = scaffoldFixedYawForFace(face) + (float) p(F_SCAFFOLD, 20);
                float branchReference = scaffoldModelReady ? scaffoldModelYaw : scaffoldCameraYaw(player);
                scaffoldSessionBranchYaw = sameBranch(branchReference, rawFaceYaw);
                scaffoldSessionYaw = scaffoldSessionBranchYaw;
                scaffoldSessionPitch = aimPitch
                        ? scaffoldStableBridgePitch(downPitch, eyeY, cellY) : networkPitch;
                scaffoldSessionFace = face;
                scaffoldPoseFace = face;
                scaffoldPoseLocked = true;
                scaffoldSessionPitchX = scaffoldSessionPitchY = scaffoldSessionPitchZ = Integer.MIN_VALUE;
                // A pose solved for another cell/face is not acknowledgement of
                // this target.  Wait for onPlayerMoved to publish the new pose.
                scaffoldPacketReady = false;
                scaffoldPacketFace = -1;
                scaffoldPacketTick = Integer.MIN_VALUE;
            }
            if (bodyAim) {
                // Turn toward the physical key intention, not the small lateral
                // drift introduced while selecting the nearest native keys during
                // a turn. Feeding that drift back into yaw made it self-sustaining.
                float travelBack = (scaffoldInputHeadingReady ? scaffoldInputHeading : scaffoldHeading)
                        + 180.0f + (float) p(F_SCAFFOLD, 20);
                scaffoldSessionYaw = sameBranch(scaffoldSessionYaw,
                        scaffoldFaceTravelYaw(travelBack, eyeX, eyeZ, cellX, cellZ, face));
            } else {
                scaffoldSessionYaw = networkYaw;
            }
            // Pick the pitch while the next air cell is still ahead of the
            // player. The old code re-solved against the current eye on every
            // post-movement callback. At walking/sprint speed that overwrote the
            // prepared 80..85 degree bridge pose with a near-vertical angle only
            // after the eye had reached the edge; by the time that pose was sent
            // and rendered, the one-tick side window was already behind us.
            //
            // Predict the first discrete vanilla movement sample outside this
            // concrete face, including the first acceleration frame. Reuse the
            // existing pose when it already hits there. Otherwise solve once for
            // this target (and its real slab/stair shape), then hold that result
            // through packet, model-render and placement confirmation.
            boolean pitchTargetChanged = scaffoldSessionPitchX != cellX
                    || scaffoldSessionPitchY != cellY || scaffoldSessionPitchZ != cellZ;
            if (aimPitch && pitchTargetChanged) {
                boolean movingInput = fMovingF.getFloat(player) != 0.0f
                        || fStrafe.getFloat(player) != 0.0f;
                boolean sprinting = mIsSprinting != null
                        && Boolean.TRUE.equals(mIsSprinting.invoke(player));
                boolean sneaking = isSneaking(player);
                double[] predictedMotion = scaffoldPredictionMotion(
                        motionX, motionZ, face, movingInput, sprinting, sneaking);
                double[] predictedOrigin = scaffoldPredictedCrossingOrigin(
                        eyeX, eyeZ, predictedMotion[0], predictedMotion[1],
                        cellX, cellZ, face, p(F_SCAFFOLD, 29));
                float stablePitch = scaffoldStableBridgePitch(downPitch, eyeY, cellY);
                float predictedPitch = scaffoldPredictedPitchForFace(stablePitch,
                        eyeX, eyeY, eyeZ, aim[1], predictedMotion[0], predictedMotion[1],
                        cellX, cellY, cellZ, face, p(F_SCAFFOLD, 29));
                float candidatePitch = scaffoldWorldFaceHit(worldOf(player),
                        predictedOrigin[0], eyeY, predictedOrigin[1],
                        scaffoldSessionYaw, scaffoldSessionPitch,
                        cellX, cellY, cellZ, face, p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10)) != null
                        ? scaffoldSessionPitch : predictedPitch;
                float[] shapePose = scaffoldWorldFaceAngles(worldOf(player),
                        predictedOrigin[0], eyeY, predictedOrigin[1],
                        scaffoldSessionYaw, candidatePitch,
                        cellX, cellY, cellZ, face, bodyAim);
                if (shapePose != null) {
                    scaffoldSessionYaw = sameBranch(scaffoldSessionYaw, shapePose[0]);
                    scaffoldSessionPitch = shapePose[1];
                } else {
                    // Keep the predicted full-side pose; the mandatory real
                    // world trace below still prevents a click through a cutout.
                    scaffoldSessionPitch = predictedPitch;
                }
                scaffoldSessionPitchX = cellX;
                scaffoldSessionPitchY = cellY;
                scaffoldSessionPitchZ = cellZ;
            } else if (!aimPitch) {
                scaffoldSessionPitch = networkPitch;
                // A later Aim Pitch=On toggle must solve this same cell instead
                // of mistaking the camera pitch for a prepared bridge sample.
                scaffoldSessionPitchX = scaffoldSessionPitchY = scaffoldSessionPitchZ = Integer.MIN_VALUE;
            }
            // Never let repeated face corrections walk the raw yaw through
            // multiple complete turns. Equivalent directions stay on the one
            // branch chosen when this support face was acquired.
            scaffoldSessionYaw = sameBranch(scaffoldSessionBranchYaw, scaffoldSessionYaw);
            networkYaw = scaffoldSessionYaw;
            networkPitch = scaffoldSessionPitch;
            scaffoldPoseFace = scaffoldSessionFace;
        } else {
            scaffoldPoseFace = face;
            if (face == 0) networkYaw = scaffoldSessionPoseChosen
                    ? scaffoldSessionYaw : scaffoldHeading + 180f;
        }
        if (rotMode == 2 && !claimVisibleAim(10, "Scaffold")) {
            if (!holdScaffoldPose()) modelHeadSet = false;
            return false;
        }
        // While approaching, finish the turn in at most two player ticks.  Once
        // the eye is outside the real support face, finish in this tick.  The
        // packet acknowledgement and side-ray checks below remain mandatory.
        boolean eyeOutside = scaffoldEyeOutsideFace(eyeX, eyeY, eyeZ,
                cellX, cellY, cellZ, face, p(F_SCAFFOLD, 29));
        int faceLeadTicks = scaffoldFaceLeadTicks(eyeX, eyeZ, motionX, motionZ,
                cellX, cellZ, face, p(F_SCAFFOLD, 29));
        // 【移除随机转头延迟】这会导致视角和位置不同步
        // 问题：随机延迟让转头完成的时机不可预测，在快速移动时
        // 可能出现"人已经过了方块边缘，但视角还没转到位"的情况
        // 服务端看到：玩家在错误的位置尝试放置方块 -> 回弹
        // 正确做法：使用固定的、可预测的转头逻辑，让视角和位置保持同步
        int settleTicks = eyeOutside ? 1 : Math.max(1, Math.min(2, faceLeadTicks));
        if (!requestSilentNetworkOnly(networkYaw, networkPitch, settleTicks)) {
            if (!holdScaffoldPose()) modelHeadSet = false;
            return false;
        }
        if (rotMode == 2) {
            // Follow Camera exposes the same requested pose to the local view;
            // Free Camera leaves these two fields untouched.
            fYaw.setFloat(player, networkYaw);
            fPitch.setFloat(player, networkPitch);
        }
        scaffoldAimRequestedFrame = true;
        // Model and ray confirmation use only a pose that onPlayerMoved has
        // already written for this exact locked target.  The requested angle
        // above is merely the next destination; it is never treated as sent.
        // At high movement speed the eye can cross the whole narrow side-view
        // interval in one tick.  The previously predicted packet pose is valid
        // whenever its actual ray already intersects this exact face, even if
        // the freshly recomputed centre angle moved by more than the cosmetic
        // Look Tolerance.  This keeps the geometric gate authoritative.
        boolean packetRayHits = scaffoldPacketReady && face == scaffoldPoseFace
                && scaffoldWorldFaceHit(worldOf(player), eyeX, eyeY, eyeZ,
                scaffoldPacketYaw, scaffoldPacketPitch,
                cellX, cellY, cellZ, face, p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10)) != null;
        // CPacketPlayer carries the orientation, not a block position.  The
        // same already-sent session pose is therefore valid for the next cell
        // on the same support chain.  Requiring our diagnostic target tag to
        // equal the new cell added a full tick after every placement and often
        // missed the side window at normal speed.
        boolean packetFaceMatches = scaffoldPacketFace == face
                || (scaffoldSessionPoseChosen && scaffoldSessionFace == face);
        boolean packetMatches = scaffoldPacketReady && packetFaceMatches
                && (packetRayHits
                || (Math.abs(shortestDelta(scaffoldPacketYaw, networkYaw)) <= tolerance
                && Math.abs(scaffoldPacketPitch - networkPitch) <= tolerance))
                && tick - scaffoldPacketTick >= 0
                && tick - scaffoldPacketTick <= 2;
        if (!packetMatches) {
            // Keep the already rendered session pose while the new outgoing
            // packet catches up; never flash back to the free-camera pose.
            if (!holdScaffoldPose()) modelHeadSet = false;
            return false;
        }
        modelHeadYaw = scaffoldPacketYaw;
        modelHeadPitch = Math.max(-90.0f, Math.min(90.0f, scaffoldPacketPitch));
        publishScaffoldModel(modelHeadYaw, modelHeadPitch);
        return true;
    }

    private static void publishScaffoldModel(float yaw, float pitch) {
        if (!scaffoldModelReady) {
            scaffoldPrevModelYaw = yaw;
            scaffoldPrevModelPitch = pitch;
        } else if (scaffoldPacketTick != scaffoldModelTick) {
            scaffoldPrevModelYaw = scaffoldModelYaw;
            scaffoldPrevModelPitch = scaffoldModelPitch;
        }
        scaffoldModelYaw = sameBranch(scaffoldPrevModelYaw, yaw);
        scaffoldModelPitch = pitch;
        scaffoldModelTick = scaffoldPacketTick;
        modelHeadYaw = scaffoldModelYaw;
        modelHeadPitch = pitch;
        scaffoldModelReady = scaffoldPoseActive = modelHeadSet = true;
    }

    private static double scaffoldTravelDelta(Object player, Field pos, Field prev, Field motion) throws Exception {
        if (pos != null && prev != null) {
            double delta = pos.getDouble(player) - prev.getDouble(player);
            return Double.isFinite(delta) && Math.abs(delta) <= 1.5 ? delta : 0.0;
        }
        return motion == null ? 0.0 : motion.getDouble(player);
    }

    /** Camera yaw hidden behind a pre-movement Scaffold pose. */
    private static float scaffoldCameraYaw(Object player) throws Exception {
        return scaffoldVanillaMovementApplied && tickSaved
                ? savedTickYaw : fYaw.getFloat(player);
    }

    static int scaffoldTravelFace(double dx, double dz, int previous) {
        double ax = Math.abs(dx), az = Math.abs(dz);
        if (ax * ax + az * az <= 1.0e-6) return previous;
        int xFace = dx > 0 ? 2 : 3, zFace = dz > 0 ? 4 : 5;
        // Keep an existing diagonal shoulder through small axis noise, but a
        // reversal must switch immediately even with the same dominant axis.
        if (previous == xFace && ax * 1.15 >= az) return xFace;
        if (previous == zFace && az * 1.15 >= ax) return zFace;
        return ax >= az ? xFace : zFace;
    }

    static float scaffoldFaceTravelYaw(float requested, double ex, double ez,
                                       int tx, int tz, int face) {
        if (face < 2 || face >= NB6.length) return requested;
        float inward = scaffoldFixedYawForFace(face);
        requested = sameBranch(inward, requested);
        if (Math.abs(requested - inward) > 89.0f) return inward;
        if (scaffoldFaceOutsideDistance(ex, ez, tx, tz, face) <= 0.0002) return requested;
        int[] d = NB6[face];
        double bx = tx + d[0], bz = tz + d[2];
        double plane = face == 2 ? bx + 1 : face == 3 ? bx : face == 4 ? bz + 1 : bz;
        double x1 = face <= 3 ? plane : bx + .01, x2 = face <= 3 ? plane : bx + .99;
        double z1 = face <= 3 ? bz + .01 : plane, z2 = face <= 3 ? bz + .99 : plane;
        float a = sameBranch(inward, (float)Math.toDegrees(Math.atan2(-(x1-ex),z1-ez)));
        float b = sameBranch(inward, (float)Math.toDegrees(Math.atan2(-(x2-ex),z2-ez)));
        return Math.max(Math.min(a,b), Math.min(Math.max(a,b), requested));
    }

    private static void observeScaffoldRender(Object player, float partialTicks) throws Exception {
        scaffoldRenderReady = scaffoldRenderCompleted = false;
        if (player == null || !scaffoldPacketReady || !scaffoldPlacementLocked
                || scaffoldPacketTick != (int) scaffoldClock(player)) return;
        boolean manual = (int) p(F_SCAFFOLD, 1) == 0;
        if (!manual && (!modelHeadSet || !"Scaffold".equals(silAimWho))) return;
        float pt = Math.max(0.0f, Math.min(1.0f, partialTicks));
        float yaw = manual ? fYaw.getFloat(player) : fRotationYawHead.getFloat(player);
        float prevYaw = manual ? (fPrevYaw == null ? yaw : fPrevYaw.getFloat(player))
                : (fPrevRotationYawHead == null ? yaw : fPrevRotationYawHead.getFloat(player));
        float pitch = fPitch.getFloat(player);
        float prevPitch = fPrevPitch == null ? pitch : fPrevPitch.getFloat(player);
        scaffoldRenderYaw = prevYaw + shortestDelta(prevYaw, yaw) * pt;
        scaffoldRenderPitch = prevPitch + (pitch - prevPitch) * pt;
        scaffoldRenderEyeX = scaffoldRenderPosition(player, fPosX, fLastTickX, pt);
        scaffoldRenderEyeY = scaffoldRenderPosition(player, fPosY, fLastTickY, pt) + eyeH(player);
        scaffoldRenderEyeZ = scaffoldRenderPosition(player, fPosZ, fLastTickZ, pt);
        scaffoldRenderWorld = worldOf(player);
        scaffoldRenderPlayer = player;
        scaffoldRenderTick = scaffoldPacketTick;
        scaffoldRenderX = scaffoldLockedX; scaffoldRenderY = scaffoldLockedY;
        scaffoldRenderZ = scaffoldLockedZ; scaffoldRenderFace = scaffoldLockedFace;
        scaffoldRenderPacketYaw = scaffoldPacketYaw; scaffoldRenderPacketPitch = scaffoldPacketPitch;
        scaffoldRenderSerial = activeWorldProjectionSerial;
        scaffoldRenderReady = true;
    }

    private static double scaffoldRenderPosition(Object player, Field current, Field last, float pt) throws Exception {
        double now = current.getDouble(player);
        double before = last == null ? now : last.getDouble(player);
        return before + (now - before) * pt;
    }

    private static void completeScaffoldRender() {
        scaffoldRenderCompleted = scaffoldRenderReady && scaffoldRenderSerial == activeWorldProjectionSerial;
    }

    private static void finishScaffoldRender(Object mc, Object player) throws Exception {
        syncScaffoldContext(mc, player);
        if (!scaffoldRenderReady || !scaffoldRenderCompleted) return;
        scaffoldRenderReady = scaffoldRenderCompleted = false;
        if (mc == null || player == null || player != scaffoldRenderPlayer
                || worldOf(player) != scaffoldRenderWorld || mods == null || !mods[F_SCAFFOLD].on
                || guiOpen || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)
                || !scaffoldPacketReady || !scaffoldPlacementLocked
                || scaffoldRenderTick != (int) scaffoldClock(player) || scaffoldPacketTick != scaffoldRenderTick
                || scaffoldRenderX != scaffoldLockedX || scaffoldRenderY != scaffoldLockedY
                || scaffoldRenderZ != scaffoldLockedZ || scaffoldRenderFace != scaffoldLockedFace
                || scaffoldPacketX != scaffoldLockedX || scaffoldPacketY != scaffoldLockedY
                || scaffoldPacketZ != scaffoldLockedZ || scaffoldPacketFace != scaffoldLockedFace
                || scaffoldRenderPacketYaw != scaffoldPacketYaw || scaffoldRenderPacketPitch != scaffoldPacketPitch
                || (!"Scaffold".equals(silAimWho) && (int) p(F_SCAFFOLD, 1) != 0)) return;
        double[] renderedHit = scaffoldWorldFaceHit(scaffoldRenderWorld, scaffoldRenderEyeX, scaffoldRenderEyeY, scaffoldRenderEyeZ,
                scaffoldRenderYaw, scaffoldRenderPitch, scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ,
                scaffoldLockedFace, p(F_SCAFFOLD, 29), p(F_SCAFFOLD, 10));
        if (renderedHit == null) {
            scaffoldWhy("等待本帧显示模型瞄准依附面");
            return;
        }
        if (!Ver.is1201()) {
            scaffoldQueuedPlacement = new ScaffoldPlacementFrame(player, scaffoldRenderWorld,
                    scaffoldRenderTick, scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, scaffoldLockedFace,
                    scaffoldPacketYaw, scaffoldPacketPitch, scaffoldRenderYaw, scaffoldRenderPitch,
                    fPosX.getDouble(player), fPosY.getDouble(player), fPosZ.getDouble(player),
                    scaffoldRenderEyeX, scaffoldRenderEyeY, scaffoldRenderEyeZ);
            return;
        }
        commitScaffoldPlacement(mc, player, renderedHit);
    }

    /** Native client input phase, before World.updateEntities moves the player.
     * Rendering can certify a pose but cannot place blocks or mutate inventory. */
    public static void onClientTickPlacement() {
        // Continue observing recent blocks even if Scaffold has just been
        // disabled; losing the current target must not erase a later rollback.
        observeScaffoldPlacements();
        ScaffoldPlacementFrame frame = scaffoldQueuedPlacement;
        scaffoldQueuedPlacement = null;
        if (frame == null || Ver.is1201()) return;
        try {
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object player = mc == null || fPlayer == null ? null : fPlayer.get(mc);
            if (player == null || mods == null || !mods[F_SCAFFOLD].on || guiOpen
                    || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)
                    || !scaffoldPacketReady || !scaffoldPlacementLocked
                    || (!"Scaffold".equals(silAimWho) && (int)p(F_SCAFFOLD,1)!=0)
                    || scaffoldPacketTick != (int)scaffoldClock(player)
                    || scaffoldPacketX != scaffoldLockedX || scaffoldPacketY != scaffoldLockedY
                    || scaffoldPacketZ != scaffoldLockedZ || scaffoldPacketFace != scaffoldLockedFace
                    || !frame.matches(player, worldOf(player), scaffoldPacketTick,
                            scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, scaffoldLockedFace,
                            scaffoldPacketYaw, scaffoldPacketPitch,
                            fPosX.getDouble(player), fPosY.getDouble(player), fPosZ.getDouble(player))) return;
            // Revalidate the displayed side, then refresh the input hit at 1.0F,
            // just as native runTick does before rightClickMouse.
            double[] hit = scaffoldInputHit(frame, fPosY.getDouble(player)+eyeH(player),
                    p(F_SCAFFOLD,29),p(F_SCAFFOLD,10));
            if (hit != null) commitScaffoldPlacement(mc, player, hit);
        } catch (Throwable t) { logOnce("scaffold client placement", t); }
    }

    static double[] scaffoldInputHit(ScaffoldPlacementFrame frame,double eyeY,double reveal,double reach)throws Exception {
        double[] shown=scaffoldWorldFaceHit(frame.world,frame.eyeX,frame.eyeY,frame.eyeZ,
                frame.renderYaw,frame.renderPitch,frame.x,frame.y,frame.z,frame.face,reveal,reach);
        if(shown==null)return null;
        return scaffoldWorldFaceHit(frame.world,frame.px,eyeY,frame.pz,
                frame.yaw,frame.pitch,frame.x,frame.y,frame.z,frame.face,reveal,reach);
    }

    private static void commitScaffoldPlacement(Object mc, Object player, double[] hit) throws Exception {
        scaffoldTickCommit = true;
        scaffoldCommitHit = hit;
        try { doScaffold(mc, player); }
        finally { scaffoldTickCommit = false; scaffoldCommitHit = null; }
    }

    private static boolean prepareScaffoldSlot(Object player) throws Exception {
        int slot = findBlockSlot(player);
        if (slot < 0) { clearScaffoldPose(); scaffoldWhy("快捷栏没有可放置方块"); return false; }
        Object inv = fInvPlayer.get(player);
        if (inv == null) { clearScaffoldPose(); return false; }
        int cur = fCurrentItem.getInt(inv);
        if (cur != slot) {
            if (p(F_SCAFFOLD, 8) > 0 && scaffoldPrevSlot < 0) scaffoldPrevSlot = cur;
            fCurrentItem.setInt(inv, slot);
            scaffoldSlotTick = (int) scaffoldClock(player);
        }
        return true;
    }

    static int scaffoldSupportLayer(double feet, boolean grounded) {
        return grounded ? (int) Math.floor(feet - 1.0e-5) : (int) Math.floor(feet) - 1;
    }

    private static void resetScaffoldJump() {
        scaffoldJumpActive = false;
        scaffoldJumpTravelActive = false;
        scaffoldJumpTravelX = scaffoldJumpTravelZ = 0.0;
        scaffoldJumpBaseY = Integer.MIN_VALUE;
        scaffoldJumpFloorY = Integer.MIN_VALUE;
        scaffoldJumpTick = Long.MIN_VALUE;
    }

    /** Observe actual movement once per tick; releasing Space does not end flight. */
    private static void observeScaffoldJump(Object player, double feet, boolean grounded) throws Exception {
        long tick = scaffoldClock(player);
        if (tick == scaffoldJumpTick) return;
        scaffoldJumpTick = tick;
        double previous = fPrevY == null ? feet : fPrevY.getDouble(player);
        if (grounded) {
            int support = scaffoldSupportLayer(feet, true);
            if (scaffoldJumpActive && scaffoldPlacementLocked && !scaffoldAwaitSolid
                    && scaffoldLockedY != support) {
                clearScaffoldPlacementLock();
                scaffoldPacketReady = scaffoldRenderReady = scaffoldRenderCompleted = false;
            }
            scaffoldJumpActive = false;
            scaffoldJumpTravelActive = false;
            scaffoldJumpTravelX = scaffoldJumpTravelZ = 0.0;
            scaffoldJumpFloorY = scaffoldJumpBaseY = support;
        } else if (!scaffoldJumpActive && feet > previous + 1.0e-4) {
            scaffoldJumpActive = true;
            double dx = scaffoldTravelDelta(player, fPosX, fPrevX, fMotionX);
            double dz = scaffoldTravelDelta(player, fPosZ, fPrevZ, fMotionZ);
            float forward = fMovingF == null ? 0.0f : fMovingF.getFloat(player);
            float strafe = fStrafe == null ? 0.0f : fStrafe.getFloat(player);
            scaffoldJumpTravelActive = scaffoldJumpHasTravelIntent(dx, dz, forward, strafe);
            if (scaffoldJumpTravelActive) {
                double length = Math.sqrt(dx*dx + dz*dz);
                if (length > 1.0e-4) {
                    scaffoldJumpTravelX = dx / length;
                    scaffoldJumpTravelZ = dz / length;
                } else {
                    double heading = Math.toRadians(scaffoldHeadingReady
                            ? scaffoldHeading : scaffoldCameraYaw(player));
                    scaffoldJumpTravelX = -Math.sin(heading);
                    scaffoldJumpTravelZ = Math.cos(heading);
                }
            }
            if (scaffoldJumpBaseY == Integer.MIN_VALUE)
                scaffoldJumpBaseY = scaffoldSupportLayer(previous, true);
            scaffoldJumpFloorY = scaffoldJumpBaseY;
        } else if (scaffoldJumpActive && scaffoldJumpTravelActive) {
            double dx = scaffoldTravelDelta(player, fPosX, fPrevX, fMotionX);
            double dz = scaffoldTravelDelta(player, fPosZ, fPrevZ, fMotionZ);
            double length = Math.sqrt(dx*dx + dz*dz);
            if (length > 1.0e-4) {
                scaffoldJumpTravelX = dx / length;
                scaffoldJumpTravelZ = dz / length;
            }
        }
    }

    private static boolean scaffoldHorizontalTravelIntent(Object mc, Object player,
                                                          double dx, double dz) throws Exception {
        float forward = 0.0f, strafe = 0.0f;
        Object input = fMovementInput == null ? null : fMovementInput.get(player);
        if (input != null) {
            if (fInputForward != null) forward = fInputForward.getFloat(input);
            if (fInputStrafe != null) strafe = fInputStrafe.getFloat(input);
        }
        if (scaffoldJumpHasTravelIntent(dx, dz, forward, strafe)) return true;
        // Read the user's actual bindings, rather than hard-coding WASD or the
        // temporary pose-relative KeyBinding states used during super.onUpdate.
        return physicalBindingDown(mc, fForwardKey, 17)
                || physicalBindingDown(mc, fBackKey, 31)
                || physicalBindingDown(mc, fLeftKey, 30)
                || physicalBindingDown(mc, fRightKey, 32);
    }

    private static void latchScaffoldRunningJump(Object player, double feet,
                                                 double dx, double dz) throws Exception {
        boolean newlyLatched = !scaffoldJumpActive || !scaffoldJumpTravelActive;
        scaffoldJumpActive = true;
        scaffoldJumpTravelActive = true;
        if (scaffoldJumpBaseY == Integer.MIN_VALUE) {
            double previous = fPrevY == null ? feet : fPrevY.getDouble(player);
            scaffoldJumpBaseY = scaffoldSupportLayer(Math.min(feet, previous), false);
            scaffoldJumpFloorY = scaffoldJumpBaseY;
        }
        double length = Math.sqrt(dx*dx + dz*dz);
        if (length > 1.0e-4) {
            scaffoldJumpTravelX = dx / length;
            scaffoldJumpTravelZ = dz / length;
        } else if (scaffoldJumpTravelX*scaffoldJumpTravelX
                + scaffoldJumpTravelZ*scaffoldJumpTravelZ <= 1.0e-6) {
            double heading = Math.toRadians(scaffoldHeadingReady
                    ? scaffoldHeading : scaffoldCameraYaw(player));
            scaffoldJumpTravelX = -Math.sin(heading);
            scaffoldJumpTravelZ = Math.cos(heading);
        }
        if (newlyLatched) {
            log("Scaffold 跑跳已锁定: baseY=" + scaffoldJumpBaseY
                    + " dir=(" + String.format("%.3f", scaffoldJumpTravelX)
                    + "," + String.format("%.3f", scaffoldJumpTravelZ) + ")");
        }
    }

    static boolean scaffoldJumpHasTravelIntent(double dx, double dz,
                                                float forward, float strafe) {
        return dx*dx + dz*dz > 1.0e-6
                || forward*forward + strafe*strafe > 1.0e-4f;
    }

    static boolean scaffoldRunningJump(boolean grounded, boolean jumpActive,
                                       boolean travelLatched) {
        return !grounded && jumpActive && travelLatched;
    }

    /** Highest connected, unobstructed cell under the real footprint, never in the player. */
    private static int[] scaffoldJumpTarget(Object world, double px, double py, double pz,
            double dx, double dz, int travelFace) throws Exception {
        int ceiling = (int)Math.floor(py + 1.0e-7) - 1;
        int base = scaffoldJumpBaseY == Integer.MIN_VALUE ? ceiling : scaffoldJumpBaseY;
        int highest = Math.min(base + 1, ceiling);
        int[][] cells = scaffoldJumpFootprintCells(px, pz, dx, dz);
        int lowest = Math.max(base, scaffoldJumpFloorY);
        for (int y = highest; y >= lowest; y--) {
            for (int[] cell : cells) {
                int x = cell[0], z = cell[1];
                if (!isAirAt(world, x, y, z)) continue;
                double[] aim = findAimPoint(world, x, y, z, travelFace, false);
                if (aim == null) aim = findAimPoint(world, x, y, z, 0, true);
                if (aim != null) return new int[]{x, y, z, (int)aim[3]};
            }
        }
        return null;
    }

    /**
     * Unique cells currently covered by the player's 0.6-wide footprint,
     * ordered from the leading foot back to the centre.  This keeps a running
     * jump placing under the real body instead of projecting a tower column or
     * filling a trailing hole after the player has already passed it.
     */
    static int[][] scaffoldJumpFootprintCells(double px, double pz,
                                              double dx, double dz) {
        int cx = (int)Math.floor(px), cz = (int)Math.floor(pz);
        double edgeX = Math.abs(dx) > 1.0e-7 ? Math.copySign(.3002, dx) : 0.0;
        double edgeZ = Math.abs(dz) > 1.0e-7 ? Math.copySign(.3002, dz) : 0.0;
        int fx = (int)Math.floor(px + edgeX);
        int fz = (int)Math.floor(pz + edgeZ);
        int sx = (int)Math.floor(px + edgeX);
        int sz = (int)Math.floor(pz);
        int zx = (int)Math.floor(px);
        int zz = (int)Math.floor(pz + edgeZ);
        int[][] raw = {{fx,fz},{sx,sz},{zx,zz},{cx,cz}};
        int[][] out = new int[4][2];
        int count = 0;
        for (int[] cell : raw) {
            boolean duplicate = false;
            for (int i = 0; i < count; i++)
                if (out[i][0] == cell[0] && out[i][1] == cell[1]) duplicate = true;
            if (!duplicate) { out[count][0] = cell[0]; out[count][1] = cell[1]; count++; }
        }
        int[][] compact = new int[count][2];
        for (int i = 0; i < count; i++) {
            compact[i][0] = out[i][0]; compact[i][1] = out[i][1];
        }
        return compact;
    }

    /**
     * Retain a valid posture. Otherwise ask the game's shape tracer for a real
     * side point, including slab halves and stair risers. Samples are only aim
     * destinations: the rendered and packet rays must still pass separately.
     */
    private static float[] scaffoldWorldFaceAngles(Object world, double ex, double ey, double ez,
            float yaw, float pitch, int tx, int ty, int tz, int face, boolean allowYaw) {
        double reveal = p(F_SCAFFOLD, 29), reach = p(F_SCAFFOLD, 10);
        if (scaffoldWorldFaceHit(world, ex, ey, ez, yaw, pitch,
                tx, ty, tz, face, reveal, reach) != null) return new float[]{yaw, pitch};
        if (face < 2 || face >= NB6.length) return null;
        int[] d = NB6[face];
        double bx = tx + d[0], by = ty + d[1], bz = tz + d[2];
        double outer = face == 2 ? bx + 1 : face == 3 ? bx : face == 4 ? bz + 1 : bz;
        double[] planes = {outer, (face <= 3 ? bx : bz) + .5};
        double yr = Math.toRadians(yaw), vx = -Math.sin(yr), vz = Math.cos(yr);
        double axis = face <= 3 ? vx : vz;
        // First preserve yaw exactly, including an internal stair riser. Only
        // a missing side on that ray permits searching another lateral point.
        double[] lateral = {Double.NaN, .5, .25, .75, .125, .875};
        double[] heights = {Math.max(.025, Math.min(.975, p(F_SCAFFOLD, 28))),
                .875, .625, .375, .125, .49, .51, .025, .975};
        float[] best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int i = 0; i < lateral.length; i++) {
            if (i > 0 && (!allowYaw || best != null)) break;
            for (double plane : planes) {
                double distance = Math.abs(axis) > 1.0e-7
                        ? (plane - (face <= 3 ? ex : ez)) / axis : -1;
                double side = i == 0 ? (face <= 3 ? ez + vz*distance - bz : ex + vx*distance - bx) : lateral[i];
                if (side <= .001 || side >= .999 || (i == 0 && distance <= 0)) continue;
                double hx = face <= 3 ? plane : bx + side;
                double hz = face <= 3 ? bz + side : plane;
                float candidateYaw = i == 0 ? yaw : (float) Math.toDegrees(Math.atan2(-(hx-ex), hz-ez));
                for (double height : heights) {
                    float candidatePitch = (float) Math.toDegrees(Math.atan2(ey-(by+height), Math.hypot(hx-ex,hz-ez)));
                    double[] hit = scaffoldWorldFaceHit(world, ex, ey, ez, candidateYaw, candidatePitch,
                            tx, ty, tz, face, reveal, reach);
                    if (hit == null) continue;
                    double score = Math.abs(shortestDelta(yaw, candidateYaw)) * 2
                            + Math.abs(pitch-candidatePitch);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new float[]{candidateYaw, candidatePitch};
                    }
                }
            }
        }
        return best;
    }

    /** Minimal pitch correction into the actual side's visible vertical span. */
    static float scaffoldCurrentFacePitch(float previous, float yaw,
                                          double eyeX, double eyeY, double eyeZ,
                                          int tx, int ty, int tz, int face) {
        if (face < 2 || face >= NB6.length || eyeY <= ty + 1.0) return previous;
        double yawRad = Math.toRadians(yaw);
        double dx = -Math.sin(yawRad), dz = Math.cos(yawRad);
        double inward = face == 2 ? -dx : face == 3 ? dx : face == 4 ? -dz : dz;
        double outside = scaffoldFaceOutsideDistance(eyeX, eyeZ, tx, tz, face);
        if (outside <= 0.0 || inward <= 1.0e-6) return previous;
        double horizontal = outside / inward;
        double low = Math.toDegrees(Math.atan2(eyeY - (ty + 0.98), horizontal));
        double high = Math.toDegrees(Math.atan2(eyeY - (ty + 0.02), horizontal));
        double inset = Math.min((high - low) * 0.45,
                Math.max(aimQuantum * 1.1, (high - low) * 0.20));
        return (float) Math.max(low + inset, Math.min(high - inset, previous));
    }

    /** Re-emit the latched bridge pose while placement waits for the next cell. */
    private static boolean holdScaffoldPose() {
        if (!scaffoldPoseActive || !scaffoldModelReady) return false;
        if (!scaffoldAimRequestedFrame) {
            if (!requestSilentNetworkOnly(scaffoldModelYaw, scaffoldModelPitch)) {
                modelHeadSet = false;
                return false;
            }
        } else if (!"Scaffold".equals(silAimWho)) {
            modelHeadSet = false;
            return false;
        }
        modelHeadYaw = scaffoldModelYaw;
        modelHeadPitch = Math.max(-90.0f, Math.min(90.0f, scaffoldModelPitch));
        modelHeadSet = true;
        return true;
    }

    private static boolean scaffoldSneakWant;
    private static boolean scaffoldEdgeSneakActive;
    private static Object scaffoldEdgeInput;
    private static int scaffoldEdgeCarryTicks;
    private static boolean scaffoldTickObserved;
    private static int scaffoldSneakTicks;
    private static int scaffoldReleaseTicks;
    private static int scaffoldOwnedKey = Integer.MIN_VALUE;

    private static void lockScaffoldPlacementTarget(int x, int y, int z, int face) {
        boolean changed = !scaffoldPlacementLocked
                || scaffoldLockedX != x || scaffoldLockedY != y
                || scaffoldLockedZ != z || scaffoldLockedFace != face;
        scaffoldPlacementLocked = true;
        scaffoldLockedX = x;
        scaffoldLockedY = y;
        scaffoldLockedZ = z;
        scaffoldLockedFace = face;
        if (!changed) return;
        scaffoldAwaitSolid = false;
        scaffoldPlacementLockAt = System.currentTimeMillis();
        scaffoldSideVisibleTick = Integer.MIN_VALUE;
        scaffoldSideVisibleCount = 0;
        // A new bridge starts without a packet acknowledgement. Once the
        // session pose exists, later blocks deliberately reuse that same
        // model/network pose instead of recalculating and twitching.
        if (!scaffoldSessionPoseChosen) {
            scaffoldPacketReady = false;
            scaffoldPacketTick = Integer.MIN_VALUE;
        }
    }

    private static void clearScaffoldPlacementLock() {
        scaffoldQueuedPlacement = null;
        scaffoldPlacementLocked = false;
        scaffoldAwaitSolid = false;
        // The next cell reuses the same session pose. Its own sideVisible tick
        // still has to pass the mandatory real-ray gate before placement.
        scaffoldLockedX = scaffoldLockedY = scaffoldLockedZ = 0;
        scaffoldLockedFace = -1;
        scaffoldSideVisibleTick = Integer.MIN_VALUE;
        scaffoldSideVisibleCount = 0;
        scaffoldPlacementLockAt = 0L;
        if (!scaffoldPoseActive) {
            scaffoldPacketReady = false;
            scaffoldPacketFace = -1;
            scaffoldPacketTick = Integer.MIN_VALUE;
        }
    }

    /** Count distinct consecutive ticks which actually displayed and traced
     * this face. Elapsed time across missing frames is not aim confirmation. */
    static boolean scaffoldConfirmSide(int tick, boolean visible, int required) {
        if (!visible) {
            scaffoldSideVisibleTick = Integer.MIN_VALUE;
            scaffoldSideVisibleCount = 0;
            return false;
        }
        if (tick != scaffoldSideVisibleTick) {
            scaffoldSideVisibleCount = scaffoldSideVisibleTick != Integer.MIN_VALUE
                    && tick == scaffoldSideVisibleTick+1 ? scaffoldSideVisibleCount+1 : 1;
            scaffoldSideVisibleTick = tick;
        }
        return scaffoldSideVisibleCount >= Math.max(1, required);
    }

    /** A monotonic game-tick surrogate for the rare mappings without ticksExisted. */
    private static long scaffoldClock(Object player) {
        try {
            if (player != null && fTicksExisted != null) return fTicksExisted.getInt(player);
        } catch (Throwable ignored) { }
        return System.currentTimeMillis() / 50L;
    }

    /** Convert a retry duration to completed 20 TPS game ticks. */
    static long scaffoldRetryTicks(double retryMs) {
        long ms = (long) Math.max(0.0, retryMs);
        return Math.max(1L, (ms + 49L) / 50L);
    }

    /**
     * Holding Space while already airborne is an explicit request to place in
     * the exact cell below the player, even when automatic Tower is disabled.
     * Tower still enables the same path from the grounded take-off frame.
     */
    static boolean scaffoldFootPlacement(boolean towerEnabled, boolean holdingSpace, boolean onGround) {
        return holdingSpace && (towerEnabled || !onGround);
    }

    /** The immediately adjacent cell in the dominant travel direction. */
    static int[] scaffoldApproachCell(double px, double pz, double dirX, double dirZ) {
        double ax = Math.abs(dirX), az = Math.abs(dirZ);
        if (ax <= 0.05 && az <= 0.05) return null;
        int x = (int) Math.floor(px), z = (int) Math.floor(pz);
        if (ax >= az) x += dirX > 0.0 ? 1 : -1;
        else z += dirZ > 0.0 ? 1 : -1;
        return new int[]{x, z};
    }

    /** Exact inward yaw for the concrete horizontal face selected by NB6. */
    static float scaffoldFixedYawForFace(int face) {
        if (face < 0 || face >= NB6_FACE.length) return 0.0f;
        int clicked = NB6_FACE[face];
        if (clicked == 2) return 0.0f;    // NORTH face: look south
        if (clicked == 3) return 180.0f;  // SOUTH face: look north
        if (clicked == 4) return -90.0f;  // WEST face: look east
        if (clicked == 5) return 90.0f;   // EAST face: look west
        return 0.0f;
    }

    /**
     * One usable downward pitch for the whole horizontal bridge session.
     *
     * The eye is normally 2.62 blocks above the bottom of the support block.
     * Putting the ray's bottom intersection at 0.296 blocks outside the side
     * maximises the part of the one-block-tall side that is visible before the
     * player's 0.30-block half-width leaves support.  Down Pitch remains a real
     * preference inside the usable 83..85 degree band, while unsafe near-90
     * values can no longer shrink the hit window to a few millimetres.
     */
    static float scaffoldStableBridgePitch(float configuredPitch,
                                           double eyeY, int cellY) {
        double dropToBottom = Math.max(1.70, Math.min(2.80, eyeY - cellY));
        // Let the side ray remain valid for a full vanilla movement tick after
        // crossing the face. The old 0.296 distance needed motion/position
        // correction at speed; 0.44 keeps movement entirely vanilla.
        float edgePitch = (float) Math.toDegrees(Math.atan2(dropToBottom, 0.44));
        float preferred = Math.abs(configuredPitch);
        preferred = Math.max(edgePitch, preferred);
        return Math.max(80.0f, Math.min(85.0f, preferred));
    }

    /** Signed distance of the eye beyond the concrete support face. */
    static double scaffoldFaceOutsideDistance(double eyeX, double eyeZ,
                                              int tx, int tz, int face) {
        if (face < 2 || face >= NB6.length) return Double.NEGATIVE_INFINITY;
        int[] d = NB6[face];
        int bx = tx + d[0], bz = tz + d[2];
        int clicked = NB6_FACE[face];
        if (clicked == 2) return bz - eyeZ;
        if (clicked == 3) return eyeZ - (bz + 1.0);
        if (clicked == 4) return bx - eyeX;
        if (clicked == 5) return eyeX - (bx + 1.0);
        return Double.NEGATIVE_INFINITY;
    }

    /** Positive speed from the support block toward the target air cell. */
    static double scaffoldFaceOutwardSpeed(double motionX, double motionZ, int face) {
        if (face < 2 || face >= NB6.length) return 0.0;
        int clicked = NB6_FACE[face];
        if (clicked == 2) return -motionZ;
        if (clicked == 3) return motionZ;
        if (clicked == 4) return -motionX;
        if (clicked == 5) return motionX;
        return 0.0;
    }

    /**
     * Predict the movement sample used by the next vanilla tick.  Minecraft can
     * report a small residual velocity while the sprint/walk input for the next
     * tick is already held; using that residual produced a near-vertical pitch
     * which missed as soon as acceleration completed.  Scale only the prediction
     * vector, never the player's actual motion.
     */
    static double[] scaffoldPredictionMotion(double motionX, double motionZ, int face,
                                              boolean movingInput,
                                              boolean sprinting, boolean sneaking) {
        if (!movingInput || face < 2 || face >= NB6.length) {
            return new double[]{motionX, motionZ};
        }
        double nominal = sneaking ? 0.03 : (sprinting ? 0.13 : 0.10);
        double length = Math.sqrt(motionX * motionX + motionZ * motionZ);
        if (length >= nominal || length > 0.005) {
            if (length >= nominal) return new double[]{motionX, motionZ};
            double scale = nominal / length;
            return new double[]{motionX * scale, motionZ * scale};
        }
        int[] support = NB6[face];
        return new double[]{-support[0] * nominal, -support[2] * nominal};
    }

    /**
     * Discrete movement tick used to prepare the side-face pitch.  The first
     * sample outside the plane can be only a few millimetres past it; a pitch
     * solved there is nearly vertical and its valid rendered interval is so
     * narrow that interpolation can skip it.  When the following vanilla sample
     * is still inside the safe 0.30-block edge span, prepare that deeper sample.
     * Both the predicted origin and pitch call this method, so they cannot drift.
     */
    static int scaffoldFaceLeadTicks(double eyeX, double eyeZ,
                                     double motionX, double motionZ,
                                     int tx, int tz, int face, double reveal) {
        double distance = scaffoldFaceOutsideDistance(eyeX, eyeZ, tx, tz, face);
        double speed = scaffoldFaceOutwardSpeed(motionX, motionZ, face);
        double threshold = Math.max(0.0, Math.min(0.20, reveal)) + 0.0002;
        if (speed < 0.005) speed = 0.035;
        // A target can be acquired after the real eye already crossed its side.
        // Advancing one more sample in that case moved valid 0.27 positions to
        // 0.40..0.46, beyond the player's 0.30 half-width.  Aim from the current
        // sample unless another still-safe sample is needed to reach 0.16.
        int ticks = distance >= threshold ? 0 : Math.max(1,
                (int) Math.ceil((threshold - distance) / speed));
        ticks = Math.min(40, ticks);
        double predictedOutside = distance + speed * ticks;
        final double renderedSafeOutside = 0.16;
        final double physicalEdgeLimit = 0.30;
        while (ticks < 40 && predictedOutside < renderedSafeOutside
                && predictedOutside + speed <= physicalEdgeLimit + 1.0e-6) {
            ticks++;
            predictedOutside += speed;
        }
        return ticks;
    }

    /**
     * Pick one pitch for this target cell from the predicted discrete crossing
     * position.  The ray therefore enters the real side on an actual game tick
     * instead of relying on a wide interval whose first hit lies past the
     * player's safe edge.  The configured pitch is retained whenever it already
     * hits the side at that predicted position.
     */
    static float scaffoldPredictedPitchForFace(float configuredPitch,
                                               double eyeX, double eyeY, double eyeZ,
                                               double hitY, double motionX, double motionZ,
                                               int tx, int ty, int tz, int face,
                                               double reveal) {
        int ticks = scaffoldFaceLeadTicks(eyeX, eyeZ, motionX, motionZ,
                tx, tz, face, reveal);
        double distance = scaffoldFaceOutsideDistance(eyeX, eyeZ, tx, tz, face);
        double speed = scaffoldFaceOutwardSpeed(motionX, motionZ, face);
        double threshold = Math.max(0.0, Math.min(0.20, reveal)) + 0.0002;
        // If the player is already outside and stopped at the edge, aim at the
        // current origin.  The old forced 0.035 fallback kept predicting a point
        // farther away and could wait forever at outside=0.300.
        double predicted;
        if (distance >= threshold && speed < 0.005) {
            predicted = distance;
        } else {
            if (speed < 0.005) speed = 0.035;
            predicted = distance + speed * ticks;
        }
        predicted = Math.max(threshold + 0.001, predicted);
        predicted = Math.min(0.60, predicted);
        double drop = Math.max(0.05, eyeY - hitY);
        float exact = (float) Math.toDegrees(Math.atan2(drop, predicted));

        float preferred = Math.max(1.0f, Math.min(89.5f, Math.abs(configuredPitch)));
        double preferredHitY = eyeY - predicted * Math.tan(Math.toRadians(preferred));
        if (preferredHitY >= ty + 0.02 && preferredHitY <= ty + 0.98) return preferred;
        return Math.max(55.0f, Math.min(89.5f, exact));
    }

    /** True only when the next two cells in the travel direction are solid. */
    private static boolean scaffoldFlatGroundAhead(Object world, double px, int feetY,
                                                    double pz, double dirX, double dirZ) throws Exception {
        int[] first = scaffoldApproachCell(px, pz, dirX, dirZ);
        if (first == null || isAirAt(world, first[0], feetY, first[1])) return false;
        int sx = first[0] - (int) Math.floor(px);
        int sz = first[1] - (int) Math.floor(pz);
        if (sx == 0 && sz == 0) return false;
        return !isAirAt(world, first[0] + sx, feetY, first[1] + sz);
    }

    /**
     * Desired bridge pose for one concrete support face.  Before the player's
     * eye crosses that face, derive the angle from a small virtual point just
     * outside it.  The model therefore turns backward/downward early instead of
     * looking forward and flipping 180 degrees on the placement tick.  Once the
     * real eye is outside, use its exact position so the final ray test remains
     * authoritative.
     */
    static float[] scaffoldFaceAngles(double ox, double oy, double oz,
                                      double hx, double hy, double hz,
                                      int tx, int ty, int tz, int face,
                                      double reveal, double motionX, double motionZ) {
        double px = ox, py = oy, pz = oz;
        if (face >= 2 && face < NB6.length
                && !scaffoldEyeOutsideFace(ox, oy, oz, tx, ty, tz, face, reveal)) {
            double[] predicted = scaffoldPredictedCrossingOrigin(
                    ox, oz, motionX, motionZ, tx, tz, face, reveal);
            px = predicted[0];
            pz = predicted[1];
        }
        float[] angles = lookAtFrom(px, py, pz, hx, hy, hz);

        // 【移除视角抖动】这会让瞄准不精确，导致射线检测失败
        // 原因：抖动让视角偏离了真实需要瞄准的点，服务端用精确的几何检测
        // 发现射线没有真正命中方块表面，判定为无效放置并回弹
        // 人类的"不精确"体现在反应时间和转头速度上，而不是瞄准点的随机偏移

        return angles;
    }

    /** Predict the same render-safe discrete sample used by the pitch solver. */
    static double[] scaffoldPredictedCrossingOrigin(double ox, double oz,
                                                     double motionX, double motionZ,
                                                     int tx, int tz, int face,
                                                     double reveal) {
        double px = ox, pz = oz;
        if (face < 2 || face >= NB6.length) return new double[]{px, pz};
        int[] d = NB6[face];
        int bx = tx + d[0], bz = tz + d[2];
        int clicked = NB6_FACE[face];
        double plane, pos, velocity, sign;
        if (clicked == 2 || clicked == 3) {
            plane = clicked == 2 ? bz : bz + 1.0;
            pos = oz;
            velocity = motionZ;
            sign = clicked == 2 ? -1.0 : 1.0;
        } else if (clicked == 4 || clicked == 5) {
            plane = clicked == 4 ? bx : bx + 1.0;
            pos = ox;
            velocity = motionX;
            sign = clicked == 4 ? -1.0 : 1.0;
        } else {
            return new double[]{px, pz};
        }
        double threshold = plane + sign * (Math.max(0.0, Math.min(0.20, reveal)) + 0.0002);
        double speed = sign * velocity;
        if (speed >= 0.005) {
            int ticks = scaffoldFaceLeadTicks(ox, oz, motionX, motionZ,
                    tx, tz, face, reveal);
            px = ox + motionX * ticks;
            pz = oz + motionZ * ticks;
        } else {
            double outside = Math.max(0.16, Math.min(0.28, reveal + 0.14));
            if (clicked == 2) pz = plane - outside;
            else if (clicked == 3) pz = plane + outside;
            else if (clicked == 4) px = plane - outside;
            else px = plane + outside;
        }
        // Numerical drift and velocity changes may leave the prediction just
        // inside the plane. Clamp only the normal axis, retaining diagonal
        // travel on the other axis.
        if (clicked == 2) pz = Math.max(plane - 0.70, Math.min(pz, threshold));
        else if (clicked == 3) pz = Math.min(plane + 0.70, Math.max(pz, threshold));
        else if (clicked == 4) px = Math.max(plane - 0.70, Math.min(px, threshold));
        else px = Math.min(plane + 0.70, Math.max(px, threshold));
        return new double[]{px, pz};
    }

    static float[] lookAtFrom(double ox, double oy, double oz,
                              double tx, double ty, double tz) {
        double dx = tx - ox, dy = ty - oy, dz = tz - oz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        return new float[]{
                (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0),
                (float) (-Math.toDegrees(Math.atan2(dy, dist)))
        };
    }

    private static boolean physicalKeyDown(int code) throws Exception {
        // LWJGL KeyBinding uses negative codes for mouse buttons: -100 is
        // button 0, -101 is button 1, etc.  Convert back before querying the
        // mouse state so releasing Scaffold never steals a held mouse bind.
        if (code < 0) return mIsButtonDown != null && Boolean.TRUE.equals(mIsButtonDown.invoke(null, code + 100));
        return code > 0 && mKeyDown != null && Boolean.TRUE.equals(mKeyDown.invoke(null, code));
    }

    private static boolean physicalBindingDown(Object mc, Field field, int fallback) throws Exception {
        Object settings = mc == null || fGameSettings == null ? null : fGameSettings.get(mc);
        int code = bindingCode(settings, field);
        return physicalKeyDown(code == 0 ? fallback : code);
    }

    private static int stackCount(Object stack) {
        try {
            // Legacy mappings expose func_77960_j for the stack count.  When
            // a mapping is absent, keep the default Minimum Blocks=1 usable.
            return mGetStackSize != null ? ((Number)mGetStackSize.invoke(stack)).intValue()
                    : fLegacyStackCount != null ? fLegacyStackCount.getInt(stack) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void releaseScaffoldKey() throws Exception {
        boolean owned = scaffoldOwnedKey != Integer.MIN_VALUE;
        boolean restoreDown = false;
        if (owned && mBindingState != null) {
            // 恢复用户当前的物理输入，保留手动按住或重绑后的潜行键。
            restoreDown = physicalKeyDown(scaffoldOwnedKey);
            mBindingState.invoke(null, scaffoldOwnedKey, restoreDown);
        }
        // KeyBinding is consumed on the next vanilla movement update.  Keep the
        // local entity flag in the same state now so a successful placement
        // visibly leaves Shift immediately instead of one tick later.
        if (owned && mSetSneaking != null && mGetMC != null && fPlayer != null) {
            Object mc = mGetMC.invoke(null);
            Object player = mc == null ? null : fPlayer.get(mc);
            if (player != null) mSetSneaking.invoke(player, restoreDown);
        }
        scaffoldOwnedKey = Integer.MIN_VALUE;
        restoreScaffoldEdgeInput(restoreDown);
        scaffoldSneakWant = false;
        scaffoldTickObserved = false;
        scaffoldSneakTicks = 0;
        scaffoldReleaseTicks = 0;
    }

    private static void restoreScaffoldEdgeInput(boolean manualDown) throws Exception {
        if (scaffoldEdgeInput != null && fInputSneak != null)
            fInputSneak.setBoolean(scaffoldEdgeInput, manualDown);
        scaffoldEdgeInput = null;
        scaffoldEdgeSneakActive = false;
    }

    private static void prepareScaffoldEdgeTick() throws Exception {
        scaffoldEdgeCarryTicks = scaffoldEdgeSneakActive ? scaffoldSneakTicks : 0;
        if (scaffoldEdgeSneakActive) releaseScaffoldKey();
    }

    /** Called only at Entity.move's native sneak check. dx/dy/dz already
     * include inertia, acceleration and native web slowdown. Never rewrite
     * those arguments: vanilla performs the protective clipping itself. */
    public static boolean scaffoldEdgeSneaking(boolean manual, Object player,
            double dx, double dy, double dz) {
        // This boundary exists even with automatic sneak Off. Native input,
        // item slowdown, attributes, friction and web handling have already
        // produced dx/dy/dz. Ground pitch can now use that real step without
        // changing the yaw, keys or any displacement consumed by Entity.move.
        refineScaffoldNativeGroundAim(player, dx, dy, dz);
        if (manual) return true;
        try {
            if (!scaffoldEdgeEligible(player, dx, dy, dz)) return false;
            Object world = worldOf(player), input = fMovementInput.get(player);
            if (world == null || input == null) return false;
            double depth = Ver.is189() ? 1.0 : fStepHeight == null ? .6 : fStepHeight.getFloat(player);
            if (depth <= 0 || !scaffoldMoveLosesSupport(world, player, mGetBB.invoke(player), dx, dy, dz, depth))
                return false;
            scaffoldSneak(player, true);
            if (scaffoldOwnedKey == Integer.MIN_VALUE) return false;
            scaffoldSneakTicks = Math.max(scaffoldSneakTicks, scaffoldEdgeCarryTicks);
            scaffoldEdgeInput = input;
            fInputSneak.setBoolean(input, true);
            scaffoldEdgeSneakActive = true;
            return true;
        } catch (Throwable t) { logOnce("scaffold edge prediction", t); return false; }
    }

    private static void refineScaffoldNativeGroundAim(Object player, double dx, double dy, double dz) {
        if (Ver.is1201() || !scaffoldVanillaMovementApplied || !tickSaved
                || !PLAYER_STATE.canRefineGroundPitch()
                || !scaffoldPlacementLocked || !"Scaffold".equals(silAimWho)
                || mods == null || !mods[F_SCAFFOLD].on || guiOpen
                || p(F_SCAFFOLD, 9) <= 0 || mGetMC == null || fPlayer == null
                || fOnGround == null || mGetBB == null || ctorAABB == null
                || mScaffoldCollisions == null || mAabbOffset == null
                || mScaffoldClipX == null || mScaffoldClipY == null || mScaffoldClipZ == null
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || dy > 0 || Math.abs(dx)+Math.abs(dz) > 2) return;
        try {
            Object mc = mGetMC.invoke(null);
            if (mc == null || fPlayer.get(mc) != player || !fOnGround.getBoolean(player)
                    || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)
                    || (mIsInWater != null && Boolean.TRUE.equals(mIsInWater.invoke(player)))
                    || (mIsOnLadder != null && Boolean.TRUE.equals(mIsOnLadder.invoke(player)))
                    || (mIsRiding != null && Boolean.TRUE.equals(mIsRiding.invoke(player)))) return;
            Object world = worldOf(player), box = mGetBB.invoke(player);
            if (world == null || box == null) return;
            double[] step = scaffoldPredictSneakStep(player, world, dx, dy, dz);
            double sx = step[0], sz = step[1];
            Object sweep = ctorAABB.newInstance(mAabbMinX.getDouble(box)+Math.min(0,sx),
                    mAabbMinY.getDouble(box)+Math.min(0,dy), mAabbMinZ.getDouble(box)+Math.min(0,sz),
                    mAabbMaxX.getDouble(box)+Math.max(0,sx), mAabbMaxY.getDouble(box)+Math.max(0,dy),
                    mAabbMaxZ.getDouble(box)+Math.max(0,sz));
            java.util.List<?> collisions = (java.util.List<?>)mScaffoldCollisions.invoke(world, player, sweep);
            double y = dy;
            for (Object block : collisions) y = ((Number)mScaffoldClipY.invoke(block, box, y)).doubleValue();
            // Deliberately restricted to level grounded movement. Falling,
            // step-up and jumping retain their existing preparation path.
            if (Math.abs(y) > 1.e-9) return;
            Object end = mAabbOffset.invoke(box, 0d, y, 0d);
            double x = sx, z = sz;
            for (Object block : collisions) x = ((Number)mScaffoldClipX.invoke(block, end, x)).doubleValue();
            end = mAabbOffset.invoke(end, x, 0d, 0d);
            for (Object block : collisions) z = ((Number)mScaffoldClipZ.invoke(block, end, z)).doubleValue();
            if (x != sx || z != sz) return; // native step-up may choose another path
            scaffoldNativeStepTick = (int)scaffoldClock(player);
            scaffoldNativeStepX = dx; scaffoldNativeStepZ = dz;
            float pt = scaffoldNextRenderPartial();
            float previous = scaffoldModelReady ? scaffoldModelPitch : sentPitch;
            double ex = fPosX.getDouble(player), ey = fPosY.getDouble(player)+eyeH(player), ez = fPosZ.getDouble(player);
            // A valid existing pose needs no cosmetic correction. Only resolve
            // again when the real native step invalidates either required ray.
            if (Float.isFinite(pt)
                    && scaffoldWorldFaceHit(world, ex+sx, ey, ez+sz, scaffoldMovementYaw, scaffoldMovementPitch,
                            scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, scaffoldLockedFace,
                            p(F_SCAFFOLD,29), p(F_SCAFFOLD,10)) != null
                    && scaffoldWorldFaceHit(world, ex+sx*pt, ey, ez+sz*pt, scaffoldMovementYaw,
                            previous+(scaffoldMovementPitch-previous)*pt,
                            scaffoldLockedX, scaffoldLockedY, scaffoldLockedZ, scaffoldLockedFace,
                            p(F_SCAFFOLD,29), p(F_SCAFFOLD,10)) != null) return;
            prepareScaffoldStepAim(player, world, sx, sz,
                    ey, scaffoldMovementYaw, true);
            float pitch = turnToward(sentPitch, silentPitch, silentPitchStep, false);
            PlayerStatePipeline.Pose pose = PLAYER_STATE.refineGroundPitch(pitch);
            if (pose == null) return;
            sentPitch = scaffoldMovementPitch = pose.pitch;
            fPitch.setFloat(player, pose.pitch);
        } catch (Throwable t) { logOnce("scaffold native step aim", t); }
    }

    /** Shared read-only eligibility. Forecasting must never press a key or
     * acquire the real movement hook's sneak state. */
    private static boolean scaffoldEdgeEligible(Object player, double dx, double dy, double dz) throws Exception {
        if (Ver.is1201() || mods == null || !mods[F_SCAFFOLD].on
                || (int)p(F_SCAFFOLD, 4) != 2 || guiOpen
                || mGetMC == null || fPlayer == null || fInputSneak == null
                || fMovementInput == null || mScaffoldCollisions == null
                || mScaffoldClipX == null || mScaffoldClipY == null || mScaffoldClipZ == null
                || mGetBB == null || ctorAABB == null || mAabbOffset == null
                || fOnGround == null || !Double.isFinite(dx) || !Double.isFinite(dy)
                || !Double.isFinite(dz) || dy > 0 || dx*dx+dz*dz < 1.e-14) return false;
        Object mc = mGetMC.invoke(null);
        if (mc == null || fPlayer.get(mc) != player || !fOnGround.getBoolean(player)
                || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)) return false;
        int mode = (int)p(F_SCAFFOLD, 0);
        if (mode == 0 || (mode == 2 && !physicalBindingDown(mc, fJumpKey, 57))) return false;
        if ((mIsInWater != null && Boolean.TRUE.equals(mIsInWater.invoke(player)))
                || (mIsOnLadder != null && Boolean.TRUE.equals(mIsOnLadder.invoke(player)))
                || (mIsRiding != null && Boolean.TRUE.equals(mIsRiding.invoke(player)))) return false;
        return true;
    }

    static double[] scaffoldPredictSneakStep(Object player, Object world, double dx, double dy, double dz) {
        double[] unchanged = {dx, dz};
        if (Ver.is1201() || player == null || world == null || mGetBB == null
                || mAabbOffset == null || mScaffoldCollisions == null || fOnGround == null
                || !Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)
                || Math.abs(dx)+Math.abs(dz) > 16) return unchanged;
        try {
            if (!fOnGround.getBoolean(player)) return unchanged;
            boolean manual = mIsSneaking != null && Boolean.TRUE.equals(mIsSneaking.invoke(player));
            if (!manual && !scaffoldEdgeEligible(player, dx, dy, dz)) return unchanged;
            Object box = mGetBB.invoke(player);
            double depth = Ver.is189() ? 1.0 : fStepHeight == null ? .6 : fStepHeight.getFloat(player);
            if (box == null || depth <= 0 || (!manual
                    && !scaffoldMoveLosesSupport(world, player, box, dx, dy, dz, depth))) return unchanged;
            // Same order, decrement and support query as Entity.move in both
            // legacy versions. Only copied offsets are queried; no live fields
            // or inputs are written. This predicts clipping, not acceleration.
            while (dx != 0 && scaffoldSneakUnsupported(world, player, box, dx, 0, depth))
                dx = scaffoldSneakDecrement(dx);
            while (dz != 0 && scaffoldSneakUnsupported(world, player, box, 0, dz, depth))
                dz = scaffoldSneakDecrement(dz);
            while (dx != 0 && dz != 0 && scaffoldSneakUnsupported(world, player, box, dx, dz, depth)) {
                dx = scaffoldSneakDecrement(dx); dz = scaffoldSneakDecrement(dz);
            }
            return new double[]{dx, dz};
        } catch (Throwable t) { logOnce("scaffold aim clipping forecast", t); return unchanged; }
    }

    private static boolean scaffoldSneakUnsupported(Object world, Object player, Object box,
            double dx, double dz, double depth) throws Exception {
        Object shifted = mAabbOffset.invoke(box, dx, -depth, dz);
        return ((java.util.List<?>)mScaffoldCollisions.invoke(world, player, shifted)).isEmpty();
    }

    private static double scaffoldSneakDecrement(double value) {
        return value < .05 && value >= -.05 ? 0 : value > 0 ? value-.05 : value+.05;
    }

    /** Non-mutating native Y/X/Z collision forecast. Resolve walls first, so
     * projecting through a wall cannot cause a false edge trigger. Support
     * uses real collision shapes and the same downward depth as native sneak. */
    static boolean scaffoldMoveLosesSupport(Object world, Object player, Object box,
            double dx, double dy, double dz, double depth) throws Exception {
        if (!scaffoldBoxSupported(world, player, box, depth)) return false;
        Object sweep = ctorAABB.newInstance(mAabbMinX.getDouble(box)+Math.min(0,dx),
                mAabbMinY.getDouble(box)+Math.min(0,dy), mAabbMinZ.getDouble(box)+Math.min(0,dz),
                mAabbMaxX.getDouble(box)+Math.max(0,dx), mAabbMaxY.getDouble(box)+Math.max(0,dy),
                mAabbMaxZ.getDouble(box)+Math.max(0,dz));
        java.util.List<?> collisions = (java.util.List<?>)mScaffoldCollisions.invoke(world, player, sweep);
        for (Object block : collisions) dy = ((Number)mScaffoldClipY.invoke(block, box, dy)).doubleValue();
        box = mAabbOffset.invoke(box, 0d, dy, 0d);
        for (Object block : collisions) dx = ((Number)mScaffoldClipX.invoke(block, box, dx)).doubleValue();
        box = mAabbOffset.invoke(box, dx, 0d, 0d);
        for (Object block : collisions) dz = ((Number)mScaffoldClipZ.invoke(block, box, dz)).doubleValue();
        box = mAabbOffset.invoke(box, 0d, 0d, dz);
        return !scaffoldBoxSupported(world, player, box, depth);
    }

    private static boolean scaffoldBoxSupported(Object world, Object player, Object box, double depth) throws Exception {
        double feet = mAabbMinY.getDouble(box);
        Object below = ctorAABB.newInstance(mAabbMinX.getDouble(box), feet-depth,
                mAabbMinZ.getDouble(box), mAabbMaxX.getDouble(box), feet+1.e-7, mAabbMaxZ.getDouble(box));
        java.util.List<?> support = (java.util.List<?>)mScaffoldCollisions.invoke(world, player, below);
        for (Object block : support)
            if (mAabbMaxY.getDouble(block) <= feet+1.e-7) return true;
        return false;
    }

    private static boolean scaffoldSneak(Object player, boolean want) throws Exception {
        if (!want) {
            scaffoldSneakWant = false;
            scaffoldSneakTicks = 0;
            scaffoldTickObserved = false;
            if (scaffoldReleaseTicks <= 0 || !mods[F_SCAFFOLD].on || guiOpen) releaseScaffoldKey();
            return false;
        }
        if (fSneakKey == null || mBindingCode == null || mBindingState == null || mIsSneaking == null) {
            releaseScaffoldKey();
            scaffoldWhy("潜行输入句柄缺失");
            return false;
        }
        Object mc = mGetMC.invoke(null);
        Object settings = fGameSettings.get(mc);
        Object binding = settings == null ? null : fSneakKey.get(settings);
        if (binding == null) { releaseScaffoldKey(); return false; }
        int code = ((Number) mBindingCode.invoke(binding)).intValue();
        if (code == 0) { releaseScaffoldKey(); return false; }
        if (scaffoldOwnedKey != Integer.MIN_VALUE && scaffoldOwnedKey != code) releaseScaffoldKey();
        if (!scaffoldSneakWant) { scaffoldSneakTicks = 0; scaffoldTickObserved = false; }
        scaffoldSneakWant = true;
        scaffoldReleaseTicks = (int) p(F_SCAFFOLD, 15);
        scaffoldOwnedKey = code;
        mBindingState.invoke(null, code, true);
        // Apply the entity flag together with the synthetic key.  This makes
        // Sneak Lead=0 a real same-tick mode; larger values still use the
        // completed-tick counter below as an optional deliberate wait.
        if (mSetSneaking != null) mSetSneaking.invoke(player, true);
        // 只在真实玩家 tick 返回后累计完成数；重复渲染帧永远不能提前放置。
        // Sneak Lead controls the input handshake. Side Confirm is a separate
        // geometric gate in doScaffold; combining them here used to double the
        // delay and made the bridge feel slower than manual placement.
        int leadTicks = Math.max(0, (int) p(F_SCAFFOLD, 14));
        return scaffoldSneakTicks >= leadTicks && isSneaking(player);
    }

    /** super.onUpdate 已执行：此时潜行由原版 movementInput 产生。 */
    private static void maintainScaffoldSneak(Object player) throws Exception {
        scaffoldTickObserved = scaffoldSneakWant && player != null && isSneaking(player);
        if (!scaffoldTickObserved) scaffoldSneakTicks = 0;
    }

    /** onUpdate 返回：原版已经发出潜行动作，下一帧才可放置。 */
    private static void finishScaffoldTick(Object player) throws Exception {
        if (scaffoldSneakWant) {
            if (scaffoldTickObserved && isSneaking(player)) scaffoldSneakTicks++;
            else scaffoldSneakTicks = 0;
            scaffoldTickObserved = false;
        } else if (scaffoldOwnedKey != Integer.MIN_VALUE) {
            if (--scaffoldReleaseTicks <= 0) releaseScaffoldKey();
        }
    }

    /**
     * 原版 Scaffold 的边缘判定：按玩家四角检查相邻空气格。
     * 方向、视角和身体朝向都不参与这个判定，避免搭路时把模型锁到某个方向。
     */
    private static boolean atEdge(Object world, double px, int feetY, double pz, double margin) throws Exception {
        double reach = 0.30 + Math.max(0.0, Math.min(0.30, margin));
        for (int dx = -1; dx <= 1; dx += 2) for (int dz = -1; dz <= 1; dz += 2) {
            if (isAirAt(world, (int) Math.floor(px + dx * reach), feetY,
                    (int) Math.floor(pz + dz * reach))) return true;
        }
        return false;
    }

    /**
     * 只在玩家真实走到支撑方块边界时触发。
     *
     * <p>玩家宽度约为 0.6 格, 因而中心点距离当前方块边界 0.30 格时,
     * 最前面的脚才刚好到边缘。这里按移动方向分别检查 X/Z 轴, 并确认下一格
     * 确实是空气。以前用 look-ahead 目标格判定, Look Ahead=1 时会提前一格以上
     * 进入潜行; 这个函数刻意不读取任何预测参数。
     */
    private static boolean atEdge(Object world, double px, int feetY, double pz,
                                  double dirX, double dirZ, double margin) throws Exception {
        final double half = 0.30;
        final double eps = 0.0002;
        double m = Math.max(0.0, Math.min(0.30, margin));
        int cellX = (int) Math.floor(px), cellZ = (int) Math.floor(pz);
        boolean nearX = false, nearZ = false;
        if (dirX > 0.05) nearX = cellX + 1.0 - (px + half) <= m + eps;
        else if (dirX < -0.05) nearX = (px - half) - cellX <= m + eps;
        if (dirZ > 0.05) nearZ = cellZ + 1.0 - (pz + half) <= m + eps;
        else if (dirZ < -0.05) nearZ = (pz - half) - cellZ <= m + eps;
        if (!nearX && !nearZ) return false;

        double step = half + eps;
        if (nearX) {
            int nextX = (int) Math.floor(px + Math.copySign(step, dirX));
            if (isAirAt(world, nextX, feetY, cellZ)) return true;
        }
        if (nearZ) {
            int nextZ = (int) Math.floor(pz + Math.copySign(step, dirZ));
            if (isAirAt(world, cellX, feetY, nextZ)) return true;
        }
        if (nearX && nearZ) {
            int nextX = (int) Math.floor(px + Math.copySign(step, dirX));
            int nextZ = (int) Math.floor(pz + Math.copySign(step, dirZ));
            return isAirAt(world, nextX, feetY, nextZ);
        }
        return false;
    }

    /**
     * Confirm that the outgoing/model ray reaches the requested horizontal
     * support face from the outside. A block hit from above is not enough for
     * bridge placement: the player must have crossed the face far enough to
     * actually see the side, while sneak protection keeps the trailing foot on
     * the old block.
     */
    static double[] scaffoldWorldFaceHit(Object world, double ox, double oy, double oz,
            float yaw, float pitch, int tx, int ty, int tz, int face, double reveal, double reach) {
        if (world == null || face < 0 || face >= NB6.length || !(reach > 0)
                || !Double.isFinite(reach) || !Double.isFinite(ox) || !Double.isFinite(oy)
                || !Double.isFinite(oz) || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !Double.isFinite(reveal) || ctorVec3d == null || mScaffoldRayTrace == null
                || mScaffoldLookVector == null || scaffoldContextPlayer == null
                || world != scaffoldContextWorld
                || fScaffoldHitSide == null || fScaffoldHitVec == null || fHitType == null
                || fScaffoldVecX == null || fScaffoldVecY == null || fScaffoldVecZ == null
                || mGetBlockPos == null || mVecGetX == null || mVecGetY == null || mVecGetZ == null
                || facingConsts == null) return null;
        try {
            // Use the running game's rotation vector, including its float
            // operations and MathHelper lookup table (or loader overrides).
            // The forecast and final gate must not use different ray maths.
            Object direction = mScaffoldLookVector.invoke(scaffoldContextPlayer, pitch, yaw);
            double vx = fScaffoldVecX.getDouble(direction);
            double vy = fScaffoldVecY.getDouble(direction);
            double vz = fScaffoldVecZ.getDouble(direction);
            if (!Double.isFinite(vx) || !Double.isFinite(vy) || !Double.isFinite(vz)) return null;
            Object start = ctorVec3d.newInstance(ox, oy, oz);
            Object end = ctorVec3d.newInstance(ox+vx*reach, oy+vy*reach, oz+vz*reach);
            // false/false/false matches a solid-block selection ray and keeps
            // selectable non-colliding obstructions. Never substitute a cube.
            Object result = mScaffoldRayTrace.invoke(world, start, end, false, false, false);
            if (result == null || !"BLOCK".equals(String.valueOf(fHitType.get(result)))
                    || fScaffoldHitSide.get(result) != facingConsts[NB6_FACE[face]]) return null;
            Object pos = mGetBlockPos.invoke(result);
            int[] d = NB6[face];
            if (pos == null || ((Number)mVecGetX.invoke(pos)).intValue() != tx+d[0]
                    || ((Number)mVecGetY.invoke(pos)).intValue() != ty+d[1]
                    || ((Number)mVecGetZ.invoke(pos)).intValue() != tz+d[2]) return null;
            Object vec = fScaffoldHitVec.get(result);
            if (vec == null) return null;
            double hx = fScaffoldVecX.getDouble(vec), hy = fScaffoldVecY.getDouble(vec), hz = fScaffoldVecZ.getDouble(vec);
            if (!Double.isFinite(hx) || !Double.isFinite(hy) || !Double.isFinite(hz)) return null;
            double dx = ox-hx, dy = oy-hy, dz = oz-hz;
            if (dx*dx+dy*dy+dz*dz > reach*reach+1.0e-7) return null;
            // A stair's riser can be halfway inside the cell. Measure reveal
            // from the actual hit plane, not from the full block's outer shell.
            double outside = -dx*d[0]-dy*d[1]-dz*d[2];
            if (outside < Math.max(.0002, reveal)-1.0e-7) return null;
            return new double[]{hx, hy, hz, face};
        } catch (Throwable unavailable) {
            return null;
        }
    }

    // Full-cube math remains for compatibility diagnostics, never placement.
    static boolean rayHitsScaffoldFace(double ox, double oy, double oz,
                                               float yaw, float pitch,
                                               int tx, int ty, int tz, int face,
                                               double reveal, double reach) {
        return scaffoldFaceHit(ox, oy, oz, yaw, pitch, tx, ty, tz, face, reveal, reach) != null;
    }

    static double[] scaffoldFaceHit(double ox, double oy, double oz,
                                               float yaw, float pitch,
                                               int tx, int ty, int tz, int face,
                                               double reveal, double reach) {
        if (face < 0 || face >= NB6.length) return null;
        int[] d = NB6[face];
        int bx=tx+d[0], by=ty+d[1], bz=tz+d[2];
        int clicked=NB6_FACE[face];
        if (!scaffoldEyeOutsideFace(ox, oy, oz, tx, ty, tz, face, reveal)) return null;
        double yr=Math.toRadians(yaw), pr=Math.toRadians(pitch);
        double vx=-Math.sin(yr)*Math.cos(pr), vy=-Math.sin(pr), vz=Math.cos(yr)*Math.cos(pr);
        double plane, t;
        // The origin is outside the requested face and the direction must point
        // inward through it.  For a convex block this makes the selected plane
        // the first AABB intersection rather than a far-side intersection after
        // the ray has already crossed the top face.
        if (clicked==0) { if (vy<=1e-7) return null; plane=by; t=(plane-oy)/vy; }
        else if (clicked==1) { if (vy>=-1e-7) return null; plane=by+1.0; t=(plane-oy)/vy; }
        else if (clicked==2) { if (vz<=1e-7) return null; plane=bz; t=(plane-oz)/vz; }
        else if (clicked==3) { if (vz>=-1e-7) return null; plane=bz+1.0; t=(plane-oz)/vz; }
        else if (clicked==4) { if (vx<=1e-7) return null; plane=bx; t=(plane-ox)/vx; }
        else { if (vx>=-1e-7) return null; plane=bx+1.0; t=(plane-ox)/vx; }
        if (t < 0.0 || t > reach + 0.05) return null;
        double hx=ox+vx*t, hy=oy+vy*t, hz=oz+vz*t;
        final double e=0.002;
        if (!(hx>=bx-e && hx<=bx+1.0+e
                && hy>=by-e && hy<=by+1.0+e
                && hz>=bz-e && hz<=bz+1.0+e)) return null;
        return new double[]{hx,hy,hz,face};
    }

    /**
     * True only after the eye has crossed to the outside of the concrete face
     * that processRightClickBlock will click.  Side Reveal is measured in world
     * blocks from that plane; it does not enlarge the hit rectangle.
     */
    static boolean scaffoldEyeOutsideFace(double ox, double oy, double oz,
                                                  int tx, int ty, int tz, int face,
                                                  double reveal) {
        if (face < 0 || face >= NB6.length) return false;
        int[] d=NB6[face];
        int bx=tx+d[0], by=ty+d[1], bz=tz+d[2];
        int clicked=NB6_FACE[face];
        double r=Math.max(0.0, Math.min(0.20, reveal));
        final double e=0.0002;
        if (clicked==0) return oy <= by-r+e;
        if (clicked==1) return oy >= by+1.0+r-e;
        if (clicked==2) return oz <= bz-r+e;
        if (clicked==3) return oz >= bz+1.0+r-e;
        if (clicked==4) return ox <= bx-r+e;
        return ox >= bx+1.0+r-e;
    }

    /** 快捷栏 (0..8) 里第一个方块物品的槽位, 没有返回 -1 */
    private static int findBlockSlot(Object player) throws Exception {
        if (mStackGetItem == null || cItemBlock == null) return -1;
        java.util.List<?> a = mainInv(player);
        if (a == null) return -1;
        for (int i = 0; i < 9 && i < a.size(); i++) {
            Object st = a.get(i);
            if (st == null) continue;
            Object item = mStackGetItem.invoke(st);
            if (item != null && cItemBlock.isInstance(item)
                    && stackCount(st) >= (int) p(F_SCAFFOLD, 17)) return i;
        }
        return -1;
    }

    /**
     * 取玩家的主背包 (mainInventory), 统一成 List 视图。
     *
     * 1.12.2 的 `InventoryPlayer.mainInventory` 是 **net.minecraft.util.NonNullList**
     * (继承 AbstractList), **不是 ItemStack[]**。MCP 老版本是数组, 所以这套代码里到处写着
     * `(Object[]) fMainInventory.get(inv)` 和 `instanceof Object[]` —— 在 1.12.2 上:
     *   - `instanceof Object[]` 永远 false -> 功能静默失效, 日志一声不响
     *   - 直接强转 -> ClassCastException
     *
     * 这一个假设一次干掉了六个功能: Scaffold / AutoTool / AutoArmor / AutoTotem /
     * AutoPot / AutoEat。搭路"改了四轮都没用"的真正原因就在这里 —— 每个 tick 都在
     * "快捷栏里没有方块"上返回, 不管手上拿着什么。
     *
     * 全部改走这里, 两种形态都认。FieldOwner 探针守着不让它回来。
     */
    private static java.util.List<?> mainInv(Object player) {
        try {
            if (fInvPlayer == null || fMainInventory == null || player == null) return null;
            Object inv = fInvPlayer.get(player);
            if (inv == null) return null;
            Object arr = fMainInventory.get(inv);
            if (arr instanceof java.util.List) return (java.util.List<?>) arr;
            if (arr instanceof Object[]) return java.util.Arrays.asList((Object[]) arr);
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 背包越界安全读 */
    private static Object invAt(java.util.List<?> l, int i) {
        return (l == null || i < 0 || i >= l.size()) ? null : l.get(i);
    }

    /** 六个邻居方向 (目标 -> 邻居) */
    private static final int[][] NB6 = {{0, -1, 0}, {0, 1, 0}, {-1, 0, 0}, {1, 0, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * 目标格的某个相邻实心块朝目标那一面的中心点, 外加"这个面是哪个方向"。
     * 返回 {hitX, hitY, hitZ, nb6Index} —— nb6Index 是 NB6 的下标, 用 NB6_FACE 换成
     * EnumFacing 的 ordinal。都找不到返回 null。
     *
     * 命中点公式 nx+0.5-d*0.5 取的就是"邻居朝向目标那一面"的中心:
     * 邻居在目标正下方 (d=DOWN) 时得到 (nx+0.5, ny+1.0, nz+0.5), 正是它顶面中心。
     */
    private static double[] findAimPoint(Object world, int tx, int ty, int tz,
                                         int preferredFace, boolean tower) throws Exception {
        // Prefer the current travel direction. Hysteresis lives in the
        // direction selector, not in an unchangeable session face.
        int chosen = -1;
        int first = tower ? 0 : 2;
        int limit = tower ? 1 : NB6.length;
        // 先用移动方向对应的支撑面, 保证瞄准点和正常玩家向前搭路时的落点一致。
        if (preferredFace >= first && preferredFace < limit) {
            int[] d = NB6[preferredFace];
            if (!isAirAt(world, tx + d[0], ty + d[1], tz + d[2])) chosen = preferredFace;
        }
        // 方向短暂不明确时保留上一帧的面, 不让扫描顺序把姿势从侧面切到底面。
        if (chosen < 0 && scaffoldAimFace >= first && scaffoldAimFace < limit) {
            int[] d = NB6[scaffoldAimFace];
            if (!isAirAt(world, tx + d[0], ty + d[1], tz + d[2])) chosen = scaffoldAimFace;
        }
        // 首次进入或支撑面刚刚消失时才做完整扫描。
        if (chosen < 0) {
            for (int i = first; i < limit; i++) {
                int[] d = NB6[i];
                if (!isAirAt(world, tx + d[0], ty + d[1], tz + d[2])) {
                    chosen = i;
                    break;
                }
            }
        }
        if (chosen < 0) {
            scaffoldAimFace = -1;
            return null;
        }
        scaffoldAimFace = chosen;
        int[] d = NB6[chosen];
        int nx = tx + d[0], ny = ty + d[1], nz = tz + d[2];
        double hy = ny + 0.5 - d[1] * 0.5;
        // 水平邻居瞄同一侧面的顶边, 这是正常玩家搭路时的稳定落点, 也避免脖子折进胸腔。
        if (d[1] == 0) hy = ny + 0.9;
        return new double[]{nx + 0.5 - d[0] * 0.5, hy,
                nz + 0.5 - d[2] * 0.5, chosen};
    }

    /** Return an aim point only for the session-owned horizontal support face. */
    private static double[] findAimPointOnFace(Object world, int tx, int ty, int tz,
                                                int face) throws Exception {
        if (face < 2 || face >= NB6.length) return null;
        int[] d = NB6[face];
        int nx = tx + d[0], ny = ty + d[1], nz = tz + d[2];
        if (isAirAt(world, nx, ny, nz)) return null;
        scaffoldAimFace = face;
        double hy = d[1] == 0 ? ny + 0.9 : ny + 0.5 - d[1] * 0.5;
        return new double[]{nx + 0.5 - d[0] * 0.5, hy,
                nz + 0.5 - d[2] * 0.5, face};
    }

    /**
     * NB6 下标 -> 要点击的那个面的 EnumFacing ordinal。
     *
     * **这里是"反向"映射, 不是同向。** d 是"目标 -> 邻居"的方向, 而我们要放的格是目标格,
     * 所以要右键的是邻居**背对目标**那一面, 也就是 -d 方向的那个面:
     *   邻居在目标正下方 (NB6[0]=DOWN) 时, 必须点它的顶面 -> UP=1
     *   邻居在目标正西方 (NB6[2]=WEST) 时, 必须点它的东面 -> EAST=5
     * 原来写的是 {0,1,4,5,2,3} —— 那是"同向"表, 六个方向全反。结果右键点在一个
     * 朝外/朝里的不存在面上, processRightClickBlock 算出来的落点跑到另一边, 通常
     * 直接失败 —— 表现就是"自动搭路怎么调都没反应"。
     *
     * NB6 顺序是 DOWN,UP,WEST,EAST,NORTH,SOUTH; EnumFacing ordinal 是 DOWN,UP,NORTH,SOUTH,WEST,EAST。
     */
    private static final int[] NB6_FACE = {1, 0, 5, 4, 3, 2};

    /**
     * 在指定方块的指定面上放东西。
     *
     * 为什么不用 Minecraft.rightClickMouse(): 它内部读 this.objectMouseOver, 而那条射线是用
     * 玩家**真实**视角和真实准星算的。Scaffold 默认就是 Silent 转身 —— 真实视角没动, 所以
     * objectMouseOver 指着别处, 结果要么放不出去、要么放到错误的面上。
     * processRightClickBlock 接受显式的方块/面/命中点；方向性方块仍读取玩家朝向，
     * 因此控制器调用期间也必须使用本次交互确认过的姿势，随后恢复自由相机。
     */
    /** 上一次放置的实际结果。processRightClickBlock 是有返回值的, 原来直接丢掉了 —— 那是这条路上最后一个盲点 */
    private static String lastPlaceResult = "(还没放过)";

    private static boolean scaffoldNativeUseAllowed(Object mc,Object player) throws Exception {
        if (mIsHandActive != null && Boolean.TRUE.equals(mIsHandActive.invoke(player))) return false;
        Object controller=fPlayerController == null ? null : fPlayerController.get(mc);
        return controller != null && (fIsHittingBlock == null || !fIsHittingBlock.getBoolean(controller));
    }

    private static void rightClickBlock(Object mc, Object player, Object world,
                                        double[] aim, int tx, int ty, int tz) {
        lastPlaceSucceeded=false;
        if (mProcessRightClickBlock == null || facingConsts == null || ctorVec3d == null || ctorBlockPos == null
                || fYaw == null || fPitch == null) {
            // The camera crosshair is unrelated to the validated Scaffold ray.
            // Missing bindings must not cause a click at a different location.
            lastPlaceResult = "停止放置: 缺少原版交互句柄 pcb="
                    + (mProcessRightClickBlock != null) + " facing=" + (facingConsts != null)
                    + " vec=" + (ctorVec3d != null) + " bp=" + (ctorBlockPos != null) + ")";
            return;
        }
        try {
            if (!scaffoldTickCommit || !scaffoldPacketReady || player != scaffoldContextPlayer
                    || world != scaffoldContextWorld || aim == null || aim.length < 4
                    || !Float.isFinite(scaffoldPacketYaw) || !Float.isFinite(scaffoldPacketPitch)) {
                lastPlaceResult = "停止放置: 已确认的交互状态失效";
                return;
            }
            int fi = (int) aim[3];
            int[] d = NB6[fi];
            Object bp = ctorBlockPos.newInstance((double) (tx + d[0]), (double) (ty + d[1]), (double) (tz + d[2]));
            Object face = facingConsts[NB6_FACE[fi]];
            Object vec = ctorVec3d.newInstance(aim[0], aim[1], aim[2]);
            Object pc = fPlayerController.get(mc);
            if (pc == null) { lastPlaceResult = "拿不到 PlayerControllerMP"; return; }
            if (!scaffoldNativeUseAllowed(mc, player)) {
                lastPlaceResult = "原版交互忙碌: 使用物品或挖掘中";
                return;
            }
            // **这个守卫只能管 1.12.2 那一支。**
            //
            // EnumHand 是 1.9 才引进的类, 1.8.9 上 cEnumHand/cls() 必然拿到 null。原来这里
            // 是无条件判断 —— 于是 1.8.9 上每次都在这一行 return, 下面那条已经写好、按版本
            // 分开的调用分支**永远走不到**。表现就是日志里那句
            //     "已经发出放置: … 结果=EnumHand.MAIN_HAND 是 null"
            // (它其实一次都没真发出去), 方块永远放不出来。
            // 参数表两个版本**顺序就不一样**, 不是首尾互换 —— 必须整条分支写开:
            //   1.12.2: (SP, WC, BlockPos,  EnumFacing, Vec3d, EnumHand)
            //   1.8.9 : (SP, WC, ItemStack, BlockPos,   EnumFacing, Vec3)
            // 少一个/多一个都不会报错, 只会 invoke 抛 IllegalArgumentException
            // 被 safe() 吞掉 —— 表现又是"功能开了没反应"。
            Object res;
            // A distinct right-click resets this shared timer to four;
            // the timer gates held-click repetition, not fresh clicks.
            // Keep manual right click/FastPlace on the same native state.
            if (fRightClickDelay != null) fRightClickDelay.setInt(mc, 4);
            float cameraYaw = fYaw.getFloat(player), cameraPitch = fPitch.getFloat(player);
            try {
                // Directional blocks read the player's pose inside onItemUse /
                // getStateForPlacement. Explicit hit coordinates alone do not
                // supply that pose. Scope it to the native controller call;
                // never advance movement or rewrite the free camera's history.
                fYaw.setFloat(player, scaffoldPacketYaw);
                fPitch.setFloat(player, scaffoldPacketPitch);
                if (Ver.is189()) {
                    Object held = mGetHeldItem == null ? null : mGetHeldItem.invoke(player);
                    res = mProcessRightClickBlock.invoke(pc, player, world, held, bp, face, vec);
                } else {
                    if (enumMainHand == null) { lastPlaceResult = "EnumHand.MAIN_HAND 是 null (1.12.2 上不该发生)"; return; }
                    res = mProcessRightClickBlock.invoke(pc, player, world, bp, face, vec, enumMainHand);
                }
            } finally {
                fYaw.setFloat(player, cameraYaw);
                fPitch.setFloat(player, cameraPitch);
            }
            lastPlaceResult = String.valueOf(res);
            lastPlaceSucceeded=Boolean.TRUE.equals(res) || "SUCCESS".equals(lastPlaceResult);
        } catch (Throwable t) {
            lastPlaceResult = "异常 " + t.getClass().getSimpleName() + ": " + t.getMessage();
            logOnce("scaffold-place", t);
        }
    }

    private static boolean isAirAt(Object world, int x, int y, int z) throws Exception {
        Object bp = ctorBlockPos.newInstance((double) x, (double) y, (double) z);
        return Boolean.TRUE.equals(mIsAirBlock.invoke(world, bp));
    }

    // ===== 玩家: 自动换工具 (按方块挖掘速度选最快工具) =====
    private static int autoToolPrev = -1;          // Switch Back 用: 换工具前手上是哪个槽
    private static long autoToolSwitchAt = 0;
    /** 上一 tick 是不是正在挖。还原要判"刚停止"这个边沿 —— 见 doAutoTool 里的说明。 */
    private static boolean autoToolWasMining = false;
    private static void doAutoTool(Object mc, Object player) throws Exception {
        if (mGetStrVsBlock == null || fObjectMouseOver == null || ctorBlockPos == null) return;
        Object pc = fPlayerController.get(mc);
        if (pc == null || fCurBlockDamage == null) return;
        Object inv = fInvPlayer.get(player);
        // 原来是 (Object[]) 强转 —— mainInventory 是 NonNullList, 这行直接 ClassCastException
        java.util.List<?> slots = mainInv(player);
        if (slots == null) return;
        int cur = fCurrentItem.getInt(inv);
        int mode = (int) p(F_AUTOTOOL, 1);        // 0=Speed 1=Silk Touch 2=Damage
        float dmg = fCurBlockDamage.getFloat(pc);
        boolean mining = dmg > 0.0f;

        // Switch Back: 停止挖掘后切回原来的槽。
        //
        // **原来是 `fCurBlockDamage <= 0` 就切回 —— 那条判据本身没错, 错在它几乎永远
        // 为真**: curBlockDamageMP 只在"持续按住左键顶着一个方块"时才 >0, 而我们这个
        // doAutoTool 是在 onPlayerTickEnd 里每 tick 调的, 绝大多数 tick 里它都是 0。
        // 于是每一 tick 都走进还原分支、把 autoToolPrev 清成 -1 —— 而 autoToolPrev 正是
        // **上一步刚记下的原槽位**。结果: 记一次, 下一 tick 就被自己抹掉, 永远没机会真正切回。
        // 用户报的 "autotool 的自动切回原工作栏无效" 就是这个。
        //
        // 正确的判据是"挖掘**刚刚结束**"这个边沿, 不是"当前没在挖"这个电平:
        // 用 autoToolSwitchAt 记下最后一次换工具的 tick, 只在离开挖掘状态的那一次还原。
        if (p(F_AUTOTOOL, 2) > 0 && autoToolPrev >= 0 && !mining) {
            if (!autoToolWasMining) return;              // 已经还原过/从没挖过, 别重复写
            if (cur != autoToolPrev) fCurrentItem.setInt(inv, autoToolPrev);
            autoToolPrev = -1;
            autoToolWasMining = false;
            return;
        }
        autoToolWasMining = mining;
        if (p(F_AUTOTOOL, 0) > 0 && !mining) return;     // Mining Only

        Object hit = fObjectMouseOver.get(mc);
        if (hit == null) return;
        Object bp = null;
        try { bp = hit.getClass().getMethod("func_178782_a").invoke(hit); } catch (Throwable t) { return; }
        if (bp == null) return;
        Object world = fWorld.get(mc);
        Object state = mGetBlockState.invoke(world, bp);
        if (state == null) return;

        int bestSlot = cur;
        float bestScore = -1.0f;
        // 1.8.9 的 getStrVsBlock 要 Block 而不是 IBlockState (见 mGetStrVsBlock 的绑定)。
        // state.getBlock() 两版本同名, 所以这里只是补一个参数转换。
        Object mineArg = state;
        if (Ver.is189() && mGetBlock != null) {
            try { mineArg = mGetBlock.invoke(state); } catch (Throwable ignored) { }
        }
        for (int i = 0; i < 9; i++) {
            Object stack = invAt(slots, i);
            if (stack == null) continue;
            Object item = mStackGetItem.invoke(stack);
            if (item == null) continue;
            float score;
            if (mode == 0) {
                score = (Float) mGetStrVsBlock.invoke(item, stack, mineArg);     // 挖掘速度
            } else if(mode==1) {
                score=(Float)mGetStrVsBlock.invoke(item,stack,mineArg);
                if(enchantmentLevel(stack,33)>0)score+=10000f;
            } else score=(float)toolAttackScore(item,stack);
            if (score > bestScore) { bestScore = score; bestSlot = i; }
        }
        if (bestSlot != cur && bestScore > 1.0f) {
            if (p(F_AUTOTOOL, 2) > 0 && autoToolPrev < 0) autoToolPrev = cur;
            fCurrentItem.setInt(inv, bestSlot);
            autoToolSwitchAt = System.currentTimeMillis();
        }
    }

    // ===== 玩家: 自动拿箱 (shift 点击把容器内物品搬进背包) =====
    private static long lastSteal = 0;
    private static Class<?> stealContainerType;
    private static void doChestStealer(Object mc, Object player) throws Exception {
        if (fOpenContainer == null || mGetSlot == null || mWindowClick == null || fInvSlots == null) return;
        Object cont = fOpenContainer.get(player);
        if (cont == null) return;
        if(stealContainerType==null)stealContainerType=copt("net.minecraft.inventory.ContainerChest");
        if(stealContainerType==null || !stealContainerType.isInstance(cont))return;
        Object pc = fPlayerController.get(mc);
        if (pc == null) return;
        long now = System.currentTimeMillis();
        if (now - lastSteal < (long) (p(F_CHESTSTEALER, 0) * 50)) return;
        java.util.List<?> allSlots = (java.util.List<?>) fInvSlots.get(cont);
        if (allSlots == null) return;
        int total = allSlots.size();
        int playerStart = total - 36; // 最后 36 格是玩家背包

        // Close When Full: 背包满了就先关界面。满了还继续点会一直空转, 也容易被看出是脚本。
        if (p(F_CHESTSTEALER, 2) > 0 && containerFull(cont, playerStart, total)) {
            if (mCloseScreen != null) mCloseScreen.invoke(mc, new Object[]{null});
            return;
        }

        int mode = (int) p(F_CHESTSTEALER, 1);    // 0=All 1=Useful 2=Filter
        for (int i = 0; i < playerStart; i++) {
            Object slot = mGetSlot.invoke(cont, i);
            if (slot == null) continue;
            Object stack = mGetSlotStack.invoke(slot);
            if (stack == null) continue;
            // Useful / Filter 都只拿"有价值"的, 但依据不同:
            //   Useful = 内置的一张常用物品关键词表
            //   Filter = 用户自己的 .azclient/chestfilter.txt
            // 原来这两个走的是同一个条件 (mode != 0), 选 Useful 还是 Filter 结果一模一样,
            // 等于 Filter 是个假选项。
            if (mode == 1 && !usefulItem(stack)) continue;
            if (mode == 2 && !filterItem(stack)) continue;
            lastSteal = now;
            quickMove(pc, i, player);
            // Keep Open: 默认拿完一件就关掉箱子; 打开着就一直拿 (适合刷箱子)
            if (p(F_CHESTSTEALER, 3) <= 0 && mCloseScreen != null && !containerHasMore(cont, playerStart)) {
                mCloseScreen.invoke(mc, new Object[]{null});
            }
            return;
        }
    }

    /** 容器里还有没有值得拿的东西 */
    private static boolean containerHasMore(Object cont, int playerStart) throws Exception {
        int mode = (int) p(F_CHESTSTEALER, 1);
        for (int i = 0; i < playerStart; i++) {
            Object slot = mGetSlot.invoke(cont, i);
            if (slot == null) continue;
            Object stack = mGetSlotStack.invoke(slot);
            if (stack == null) continue;
            if (mode == 0 || (mode == 1 ? usefulItem(stack) : filterItem(stack))) return true;
        }
        return false;
    }

    /** 玩家背包那 36 格是不是满了 */
    private static boolean containerFull(Object cont, int playerStart, int total) throws Exception {
        for (int i = playerStart; i < total; i++) {
            Object slot = mGetSlot.invoke(cont, i);
            if (slot == null) continue;
            if (mGetSlotStack.invoke(slot) == null) return false;
        }
        return true;
    }

    /**
     * "有用的东西" —— Useful / Filter 两档共用。
     * 只按物品类名和几个常见关键字判, 不依赖物品注册表, 换个整合包也不会失效。
     */
    private static boolean usefulItem(Object stack) {
        return itemLike(stack, "diamond") || itemLike(stack, "emerald") || itemLike(stack, "gold")
                || itemLike(stack, "iron") || itemLike(stack, "netherite") || itemLike(stack, "ender_pearl")
                || itemLike(stack, "totem") || itemLike(stack, "shulker") || itemLike(stack, "potion")
                || itemLike(stack, "food") || itemLike(stack, "armor") || itemLike(stack, "sword")
                || itemLike(stack, "pickaxe") || itemLike(stack, "apple") || itemLike(stack, "ingot");
    }

    // ===== 玩家: 背包管理 =====
    private static long lastInvManage = 0L;

    private static String stackDescriptor(Object stack) {
        if (stack == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            Object item = mGetItem == null ? null : mGetItem.invoke(stack);
            if (item != null) sb.append(item.getClass().getSimpleName()).append(' ').append(item);
        } catch (Throwable ignored) { }
        try { if (mGetStackName != null) sb.append(' ').append(mGetStackName.invoke(stack)); }
        catch (Throwable ignored) { }
        sb.append(' ').append(stack);
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean emptyStack(Object stack) {
        return stack == null || stackCount(stack) <= 0 || stackDescriptor(stack).contains("itemair");
    }

    private static int armorType(String s) {
        if (s.contains("helmet")) return 0;
        if (s.contains("chestplate")) return 1;
        if (s.contains("legging") || s.contains("legs")) return 2;
        if (s.contains("boots")) return 3;
        return -1;
    }

    private static double materialScore(String s) {
        if (s.contains("netherite")) return 60;
        if (s.contains("diamond")) return 50;
        if (s.contains("iron")) return 40;
        if (s.contains("chain")) return 32;
        if (s.contains("stone")) return 28;
        if (s.contains("gold")) return 22;
        if (s.contains("leather") || s.contains("wood")) return 12;
        return 1;
    }

    private static double inventoryScore(Object stack) {
        if (emptyStack(stack)) return -1;
        double score = materialScore(stackDescriptor(stack)) * 100.0;
        try { if (mIsEnchanted != null && Boolean.TRUE.equals(mIsEnchanted.invoke(stack))) score += 75.0; }
        catch (Throwable ignored) { }
        try {
            if (mStackDamageable != null && Boolean.TRUE.equals(mStackDamageable.invoke(stack))
                    && mStackMaxDamage != null && mStackDamage != null) {
                int max = ((Number) mStackMaxDamage.invoke(stack)).intValue();
                int damage = ((Number) mStackDamage.invoke(stack)).intValue();
                if (max > 0) score += Math.max(0, max - damage) * 20.0 / max;
            }
        } catch (Throwable ignored) { }
        return score;
    }

    private static Object containerStack(Object cont, int slot) throws Exception {
        Object s = mGetSlot.invoke(cont, slot);
        return s == null ? null : mGetSlotStack.invoke(s);
    }

    private static int bestInventorySlot(Object cont, String type) throws Exception {
        int best = -1;
        double score = -1;
        for (int slot = 9; slot <= 44; slot++) {
            Object st = containerStack(cont, slot);
            if (emptyStack(st)) continue;
            String d = stackDescriptor(st);
            boolean match;
            if ("weapon".equals(type)) match = d.contains("sword");
            else if ("pickaxe".equals(type)) match = d.contains("pickaxe");
            else if ("axe".equals(type)) match = d.contains("axe") && !d.contains("pickaxe");
            else if ("shovel".equals(type)) match = d.contains("shovel") || d.contains("spade");
            else if ("blocks".equals(type)) {
                Object item = mGetItem == null ? null : mGetItem.invoke(st);
                match = item != null && cItemBlock != null && cItemBlock.isInstance(item);
            } else if ("food".equals(type)) {
                match = d.contains("food") || d.contains("apple") || d.contains("bread")
                        || d.contains("steak") || d.contains("pork") || d.contains("chicken")
                        || d.contains("carrot") || d.contains("potato") || d.contains("fish");
            } else {
                match = d.contains("ender_pearl") || d.contains("pearl") || d.contains("potion")
                        || d.contains("bow") || d.contains("fishing") || d.contains("bucket")
                        || d.contains("totem") || d.contains("flint");
            }
            if (!match) continue;
            double s = inventoryScore(st) + Math.min(64, stackCount(st));
            if (s > score) { score = s; best = slot; }
        }
        return best;
    }

    private static boolean moveBestToHotbar(Object pc, Object cont, Object player,
                                             String type, int configuredSlot) throws Exception {
        if (configuredSlot < 1 || configuredSlot > 9) return false;
        int best = bestInventorySlot(cont, type);
        int hotbar = Math.max(0, Math.min(8, configuredSlot - 1));
        int target = 36 + hotbar;
        if (best < 0 || best == target) return false;
        swapHotbar(pc, best, hotbar, player);
        return true;
    }

    /**
     * A user can assign several categories to the same hotbar cell.  Without
     * resolving that conflict the manager swaps the weapon/tool/block back and
     * forth forever and never reaches armor cleanup.  Keep the visible slot
     * values intact, give the earlier (higher priority) category ownership and
     * skip lower priority duplicates for this inventory pass.
     */
    private static int reserveManagerSlot(boolean[] reserved, int configuredSlot) {
        int slot = Math.max(1, Math.min(9, configuredSlot));
        int index = slot - 1;
        if (reserved[index]) return -1;
        reserved[index] = true;
        return slot;
    }

    private static boolean manageBestArmor(Object pc, Object cont, Object player) throws Exception {
        for (int type = 0; type < 4; type++) {
            int equipSlot = 5 + type;
            Object equipped = containerStack(cont, equipSlot);
            int bestSlot = -1;
            double bestScore = inventoryScore(equipped);
            for (int slot = 9; slot <= 44; slot++) {
                Object st = containerStack(cont, slot);
                if (armorType(stackDescriptor(st)) != type) continue;
                double score = inventoryScore(st);
                if (score > bestScore + 0.01) { bestScore = score; bestSlot = slot; }
            }
            if (bestSlot < 0) continue;
            if (!emptyStack(equipped)) quickMove(pc, equipSlot, player);
            else quickMove(pc, bestSlot, player);
            return true;
        }
        return false;
    }

    private static boolean junkStack(Object stack, int blockCount, int foodCount, int keepBlocks, int keepFood) {
        String d = stackDescriptor(stack);
        if (d.contains("rotten") || d.contains("poisonous_potato") || d.contains("dead_bush")
                || d.contains("glass_bottle") || d.contains("bowl")) return true;
        if (foodCount - stackCount(stack) >= keepFood && (d.contains("spider_eye") || d.contains("raw_chicken"))) return true;
        return blockCount - stackCount(stack) >= keepBlocks && (d.contains("dirt") || d.contains("cobblestone")
                || d.contains("netherrack") || d.contains("gravel") || d.contains("sand"));
    }

    private static void doInvManager(Object mc, Object player) throws Exception {
        if (mWindowClick == null || mGetSlot == null || mGetSlotStack == null || fInvSlots == null) return;
        Object screen = fCurrentScreen == null ? null : fCurrentScreen.get(mc);
        // 允许在关闭背包时也工作 - 在Skywar等快节奏模式下自动装备和拿取物品
        // screen == null 表示没有打开任何GUI，此时可以后台管理物品
        boolean guiOpen = screen != null;
        if (guiOpen) {
            String screenName = screen.getClass().getName();
            if (!screenName.contains("GuiInventory") && !screenName.contains("InventoryScreen")) return;
        }
        Object cont = fOpenContainer.get(player);
        if (cont == null) return;
        java.util.List<?> slots = (java.util.List<?>) fInvSlots.get(cont);
        if (slots == null || slots.size() < 45) return;
        long now = System.currentTimeMillis();
        if (now - lastInvManage < (long) p(F_INVMANAGER, 0) * 50L) return;
        Object pc = fPlayerController.get(mc);
        if (pc == null) return;

        boolean[] reserved = new boolean[9];
        int weaponSlot = p(F_INVMANAGER, 2) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 6)) : -1;
        int pickaxeSlot = p(F_INVMANAGER, 3) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 7)) : -1;
        int axeSlot = p(F_INVMANAGER, 3) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 8)) : -1;
        int shovelSlot = p(F_INVMANAGER, 3) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 9)) : -1;
        int blocksSlot = p(F_INVMANAGER, 4) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 10)) : -1;
        int foodSlot = p(F_INVMANAGER, 4) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 11)) : -1;
        int utilitySlot = p(F_INVMANAGER, 4) > 0
                ? reserveManagerSlot(reserved, (int) p(F_INVMANAGER, 12)) : -1;

        if (p(F_INVMANAGER, 1) > 0 && manageBestArmor(pc, cont, player)) { lastInvManage = now; return; }
        if (p(F_INVMANAGER, 2) > 0
                && moveBestToHotbar(pc, cont, player, "weapon", weaponSlot)) { lastInvManage = now; return; }
        if (p(F_INVMANAGER, 3) > 0) {
            if (moveBestToHotbar(pc, cont, player, "pickaxe", pickaxeSlot)
                    || moveBestToHotbar(pc, cont, player, "axe", axeSlot)
                    || moveBestToHotbar(pc, cont, player, "shovel", shovelSlot)) {
                lastInvManage = now; return;
            }
        }
        if (p(F_INVMANAGER, 4) > 0) {
            if (moveBestToHotbar(pc, cont, player, "blocks", blocksSlot)
                    || moveBestToHotbar(pc, cont, player, "food", foodSlot)
                    || moveBestToHotbar(pc, cont, player, "utility", utilitySlot)) {
                lastInvManage = now; return;
            }
        }
        if (p(F_INVMANAGER, 5) <= 0) return;
        int blockCount = 0, foodCount = 0;
        for (int slot = 9; slot <= 44; slot++) {
            Object st = containerStack(cont, slot);
            if (emptyStack(st)) continue;
            String d = stackDescriptor(st);
            Object item = mGetItem == null ? null : mGetItem.invoke(st);
            if (item != null && cItemBlock != null && cItemBlock.isInstance(item)) blockCount += stackCount(st);
            if (d.contains("food") || d.contains("apple") || d.contains("bread") || d.contains("steak")) foodCount += stackCount(st);
        }
        int keepBlocks = (int) p(F_INVMANAGER, 13), keepFood = (int) p(F_INVMANAGER, 14);
        for (int slot = 9; slot <= 44; slot++) {
            Object st = containerStack(cont, slot);
            if (!emptyStack(st) && junkStack(st, blockCount, foodCount, keepBlocks, keepFood)) {
                dropStack(pc, slot, player);
                lastInvManage = now;
                return;
            }
        }
    }

    /** ChestStealer 的 Filter 模式: 用户列表, 一行一个关键词, # 开头是注释 */
    private static String[] chestFilterCache = null;

    private static String[] chestFilter() {
        if (chestFilterCache != null) return chestFilterCache;
        String[] empty = new String[0];
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.home"), ".azclient");
            java.io.File f = new java.io.File(dir, "chestfilter.txt");
            if (!f.isFile()) {
                // 第一次跑就写一份默认的出来, 不然用户不知道要建哪个文件
                dir.mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write("# ChestStealer 的 Filter 模式只拿名字里含这些词的物品, 一行一个\n");
                w.write("diamond\nnetherite\ntotem\nshulker\nelytra\n");
                w.close();
                log("ChestStealer Filter: 已生成 " + f.getAbsolutePath());
            }
            java.util.List<String> out = new java.util.ArrayList<String>();
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim().toLowerCase();
                if (!line.isEmpty() && !line.startsWith("#")) out.add(line);
            }
            r.close();
            chestFilterCache = out.toArray(new String[0]);
        } catch (Throwable t) {
            chestFilterCache = empty;   // 读不了就退回空表, 不重试 (避免每件物品都卡 IO)
        }
        return chestFilterCache;
    }

    /** 命中用户列表才算值得拿; 列表是空的话退回 Useful 的判定, 免得一个都不拿 */
    private static boolean filterItem(Object stack) {
        String[] list = chestFilter();
        if (list.length == 0) return usefulItem(stack);
        for (String k : list) if (itemLike(stack, k)) return true;
        return false;
    }

    // ===== 世界: 变速 =====
    /**
     * 设置游戏速度倍率。
     *
     * **两个版本上这个字段不是同一个量**，光靠别名表翻名字不够：
     *   1.12.2  Timer.tickLength      每 tick 多少**毫秒**，原版 50（= 20 TPS）
     *   1.8.9   Timer.ticksPerSecond  每秒多少 **tick**，原版 20
     * 互为倒数。名字由别名表翻（field_194149_e -> field_74282_a，fopt 内部自动过），
     * 单位在这里换算 —— 换算只写这一处，三个调用点（Timer / 速度档位 / 关功能还原）共用。
     *
     * 1.8.9 上原来整套是死的：fopt 解析不到 field_194149_e -> fTickLength 恒为 null ->
     * doTimer 第一行 return。界面照常显示开关和 0.5~3.0 档位，点下去什么都不发生。
     */
    private static void setTimerSpeed(Object timer, double speed) throws Exception {
        if (timer == null || fTickLength == null) return;
        if (Ver.timerFieldIsTicksPerSecond()) {
            fTickLength.setFloat(timer, (float) (20.0 * speed));
        } else {
            fTickLength.setFloat(timer, (float) (50.0 / speed));
        }
    }

    private static void doTimer(Object mc, Object player) throws Exception {
        if (fTimer == null || fTickLength == null) return;
        Object timer = fTimer.get(mc);
        if (timer == null) return;
        if ((int) p(F_TIMER, 1) == 1) {                                           // On Move: 不动就还原本速
            if (Math.abs(fMotionX.getDouble(player)) < 0.005
                    && Math.abs(fMotionZ.getDouble(player)) < 0.005) {
                setTimerSpeed(timer, 1.0);
                return;
            }
        }
        setTimerSpeed(timer, p(F_TIMER, 0));                                      // Speed
    }

    // ===== 世界: 无天气 =====
    private static void doNoWeather(Object world) throws Exception {
        if (world == null) return;
        if (p(F_NOWEATHER, 0) > 0 && mGetWorldInfo != null && mSetRaining != null) {   // No Rain/Snow
            Object wi = mGetWorldInfo.invoke(world);
            if (wi != null) {
                mSetRaining.invoke(wi, false);
                if (mSetThundering != null) mSetThundering.invoke(wi, false);
            }
        }
        if (p(F_NOWEATHER, 1) > 0) {                                              // No Sky Darken
            // 天空压暗是这两个字段插值出来的, 直接按 0 喂给渲染
            if (fRainingStrength != null) fRainingStrength.setFloat(world, 0.0f);
            if (fPrevRainingStrength != null) fPrevRainingStrength.setFloat(world, 0.0f);
        }
    }

    // ===== 世界: 时间调整 =====
    /**
     * 参数: 0=Time 1=Mode
     *   Fixed —— 每 tick 把时间钉死在 Time 上 (永远白天/永远黑夜)
     *   Fast  —— 从当前时间出发加速流逝, 看起来像开了加速的日晷
     *   Slow  —— 慢慢流逝, 想留住黄昏/黎明这种短暂光照
     *
     * 注意这改的是客户端自己的世界时间, 服务端会定期同步覆盖 —— 单人存档有效,
     * 多人服里会被服务器时间顶回去。
     */
    private static final FrameClock worldTimeClock=new FrameClock();
    private static Object timeChangerWorld;
    private static int timeChangerMode=-1;
    private static double visualWorldTime;
    private static void resetTimeChanger(){worldTimeClock.reset();timeChangerWorld=null;timeChangerMode=-1;}
    private static void doTimeChange(Object world) throws Exception {
        updateTimeChange(world,System.nanoTime());
    }
    private static void updateTimeChange(Object world,long now) throws Exception {
        if (world == null || mSetWorldTime == null || mGetWorldTime == null) return;
        long target = (long) p(F_TIMECHANGE, 0);
        int mode = (int) p(F_TIMECHANGE, 1);
        if (mode == 0) {
            resetTimeChanger();
            mSetWorldTime.invoke(world, target);
            return;
        }
        if(timeChangerWorld!=world || timeChangerMode!=mode){
            resetTimeChanger();timeChangerWorld=world;timeChangerMode=mode;
            visualWorldTime=((Number)mGetWorldTime.invoke(world)).longValue();
        }
        // Advance a cosmetic clock in seconds, independently from frame rate
        // and the server's periodic world-time updates. Vanilla is 20 ticks/s.
        visualWorldTime+=worldTimeClock.step(now)*(mode==1?2000.0:5.0);
        mSetWorldTime.invoke(world,(long)Math.floor(visualWorldTime));
    }

    // ===== 世界: 防虚空 =====
    private static void doAntiVoid(Object player) throws Exception {
        double trigger = p(F_ANTIVOID, 0);                                        // Trigger Y
        if (fPosY.getDouble(player) >= trigger) return;
        if ((int) p(F_ANTIVOID, 1) == 0) {                                        // Teleport: 直接拽回触发线以上
            double back = trigger + 1.0;
            fPosY.setDouble(player, back);
            if (fPrevY != null) fPrevY.setDouble(player, back);
            fMotionY.setDouble(player, 0.0);
        } else {                                                                  // No Fall: 原地托住不掉
            fMotionY.setDouble(player, 0.5);
        }
    }

    // ===== 杂项: 自动重生 =====
    private static long lastRespawn = 0;
    private static void doAutoRespawn(Object mc, Object player) throws Exception {
        if (mRespawn == null) return;
        if (!fIsDead.getBoolean(player)) return;
        long now = System.currentTimeMillis();
        if (now - lastRespawn < (long) (p(F_AUTORESPAWN, 0) * 1000)) return;
        lastRespawn = now;
        // Instant: 先关掉死亡界面再发重生包。
        // 不关的话重生包发出去, 界面还挂在屏幕上, 得手动点一下才消失 —— 那就不叫 Instant 了。
        if ((int) p(F_AUTORESPAWN, 1) == 1 && mCloseScreen != null) mCloseScreen.invoke(mc, new Object[]{null});
        mRespawn.invoke(player);
    }

    // ===== 渲染: 夜视 / 无受伤抖动 (每帧维持) =====
    private static Object gammaSettings;
    private static float savedGamma;
    private static void restoreGamma() throws Exception {
        if(gammaSettings!=null && fGamma!=null)fGamma.setFloat(gammaSettings,savedGamma);
        gammaSettings=null;
    }
    private static void doFullbright(Object mc, Object player) throws Exception {
        if ((int) p(F_FULLBRIGHT, 1) == 1) {                                      // Night Vision: 直接挂药水
            restoreGamma();
            if (mAddPotionEffect == null || ctorPotionEffect == null || potionNightVision == null) return;
            if (Ver.is189() && mPotionNumericId == null) return;
            if (mGetActivePotion != null && mGetActivePotion.invoke(player, potionNightVision) != null) return;
            Object effectKey = Ver.is189() ? mPotionNumericId.invoke(potionNightVision) : potionNightVision;
            mAddPotionEffect.invoke(player, ctorPotionEffect.newInstance(effectKey, 400, 0));
            return;
        }
        if (fGamma == null || fGameSettings == null) return;                      // Gamma
        Object gs = fGameSettings.get(mc);
        if (gs != null) {
            if(gammaSettings!=gs){restoreGamma();savedGamma=fGamma.getFloat(gs);gammaSettings=gs;}
            fGamma.setFloat(gs, (float) p(F_FULLBRIGHT, 0));
        }
    }

    /** NoHurtCam 的 No Bob 会关掉视角摇晃 —— 记下原值, 关功能时还回去 */
    private static Boolean bobSaved = null;
    private static Object bobSettings;
    private static void restoreBobbing() throws Exception {
        if(bobSaved!=null && bobSettings!=null && fViewBobbing!=null)fViewBobbing.setBoolean(bobSettings,bobSaved);
        bobSaved=null;bobSettings=null;
    }

    private static void doNoHurtCam(Object mc, Object player) throws Exception {
        int mode = (int) p(F_NOHURTCAM, 1);
        if(mode!=2)restoreBobbing();
        if (mode == 0) { if(fHurtTime!=null)fHurtTime.setInt(player, 0); return; }
        // Rotate: 只能改 hurtTime 这一个量, 按 Strength 比例留一部分。
        // 真正的"受伤转头"在 EntityRenderer 里, 改它需要渲染钩子 —— 这里做不到。
        int ht = fHurtTime==null?0:fHurtTime.getInt(player);
        if (ht > 0) fHurtTime.setInt(player, (int) Math.floor(ht * p(F_NOHURTCAM, 0)));
        // No Bob: 这个能真做 —— 走路时视角上下晃是 GameSettings 里的一个开关。
        // 原来 1 和 2 走的是同一段代码, 选哪个都一样, 等于 No Bob 是个摆设。
        if (mode == 2 && fViewBobbing != null && fGameSettings != null) {
            Object gs = fGameSettings.get(mc);
            if (gs != null) {
                if (bobSettings!=gs) {restoreBobbing();bobSettings=gs;bobSaved=fViewBobbing.getBoolean(gs);}
                if (bobSaved) fViewBobbing.setBoolean(gs, false);
            }
        }
    }

    // ===== 自动攻击 =====
    private static long kaNextAttackAt = 0L;

    /**
     * 自动攻击 (KillAura)。
     *
     * 关键参数是"转身"模式:
     *   Off     —— 只攻击, 完全不转视角 (纯服务端判定用, 打不到就别开)
     *   Silent  —— 服务端收到瞄准角度、屏幕视角不动, 玩偶朝向跟着转。这是绕反作弊的正解:
     *              服务端拿包里的角度做命中判定, 而反作弊比对"上报角度 vs 攻击方向"时两者天然一致。
     *   Visible —— 真的转过去 (会被人看出来, 但某些反作弊只认这个)
     *
     * 目标筛选有优先级: 角度差越小越优先, 同角度差才比距离 —— 只按距离选会在人堆里
     * 疯狂来回甩视角, 一眼假。
     */
    /**
     * KillAura 的目标分类。
     *   0=Players  只打玩家   1=Animals  只打动物 (牛猪羊鸡…)
     *   2=Monsters 只打怪物   3=All      所有活体
     *
     * 村民 / 铁傀儡 / 蝙蝠 / 鱿鱼不属于 EntityAnimal 也不属于 EntityMob,
     * 想打它们得用 All —— 这是有意的, 不然"动物"会变成"除怪物外的一切"。
     */
    private static int combatBlockKey=Integer.MIN_VALUE;
    private static long combatUseLogAt;
    private static final CombatBlock combatBlock=new CombatBlock(new CombatBlock.Use(){
        public boolean active(Object player)throws Exception{return mHandActive!=null&&Boolean.TRUE.equals(mHandActive.invoke(player));}
        public Object stack(Object player,Object hand)throws Exception{
            return hand!=null&&hand==enumOffHand&&mHeldOffhand != null?mHeldOffhand.invoke(player):heldItem(player);
        }
        public boolean manual()throws Exception{return physicalBindingDown(mGetMC.invoke(null),fUseKey,-99);}
        public void hold(boolean down)throws Exception{
            if(down){
                Object settings=fGameSettings.get(mGetMC.invoke(null));
                combatBlockKey=((Number)mBindingCode.invoke(fUseKey.get(settings))).intValue();
                mBindingState.invoke(null,combatBlockKey,true);
            }else if(combatBlockKey!=Integer.MIN_VALUE){
                int code=combatBlockKey;combatBlockKey=Integer.MIN_VALUE;
                mBindingState.invoke(null,code,physicalKeyDown(code));
            }
        }
        public void start(Object player,Object world,Object stack,Object hand)throws Exception{
            if (mBlockUse == null) throw new IllegalStateException("Native item use unavailable");
            Object pc=fPlayerController.get(mGetMC.invoke(null));
            mBlockUse.invoke(pc,player,world,Ver.is189()?stack:hand);
        }
        public void stop(Object player)throws Exception{mBlockStop.invoke(fPlayerController.get(mGetMC.invoke(null)),player);}
    });
    private static void releaseCombatBlock(){
        try{combatBlock.release();}catch(Throwable failure){logOnce("AutoBlock release",failure);}
    }
    private static void maintainCombatBlock(Object mc,Object player,boolean tick)throws Exception{
        Object world=mc==null||fWorld==null?null:fWorld.get(mc);
        boolean enabled=player!=null&&mods[MK].on&&p(MK,33)>0&&p(MK,35)>0&&kaTarget!=null
                &&!EntityExclusions.contains(kaTarget)&&!guiOpen
                &&(fCurrentScreen==null||fCurrentScreen.get(mc)==null);
        combatBlock.maintain(player,world,enabled,tick);
        if(tick&&player!=null&&mods[MK].on&&kaTarget!=null
                &&System.currentTimeMillis()-combatUseLogAt>=1000L){
            combatUseLogAt=System.currentTimeMillis();
            try{log("Combat use state: mode="+(p(MK,35)>0?"real":"visual")
                    +" autoBlock="+(p(MK,33)>0)+" owned="+combatBlock.owns(player)
                    +" active="+(mHandActive!=null&&Boolean.TRUE.equals(mHandActive.invoke(player)))
                    +" manual="+physicalBindingDown(mc,fUseKey,-99)
                    +" syntheticKey="+(combatBlockKey!=Integer.MIN_VALUE)
                    +" horizontalSpeed="+combatHorizontalSpeed(player)+" requests="+kaAttackCount);
            }catch(Throwable observation){logOnce("combat use observation",observation);}
        }
    }
    private static double combatHorizontalSpeed(Object player){
        try{return Math.hypot(fMotionX.getDouble(player),fMotionZ.getDouble(player));}
        catch(Throwable unavailable){return Double.NaN;}
    }
    private static boolean blockingStack(Object stack)throws Exception{
        return !emptyStack(stack)&&mStackUseAction!=null&&"BLOCK".equals(String.valueOf(mStackUseAction.invoke(stack)));
    }
    private static void beginCombatBlock(Object player)throws Exception{
        if(p(MK,33)<=0||p(MK,35)<=0||mBlockUse==null||mBlockStop==null||mHandActive==null
                ||fUseKey==null||mBindingCode==null||mBindingState==null)return;
        Object stack=heldItem(player),hand=enumMainHand;
        if(!blockingStack(stack)){
            stack=mHeldOffhand == null?null:mHeldOffhand.invoke(player);hand=enumOffHand;
            if(!blockingStack(stack))return;
        }
        combatBlock.begin(player,worldOf(player),stack,hand,Math.max(Ver.is189()?1:6,(int)p(MK,34)));
    }
    /** -1: draw the attack swing, 0: preserve native render reads, 1: visual-only block. */
    private static int blockRenderPose(Object player) {
        try {
            if (player==null || mGetMC==null || fPlayer==null || !mods[MK].on || p(MK,33)<=0) return 0;
            Object mc=mGetMC.invoke(null);
            if(fPlayer.get(mc)!=player || guiOpen || fCurrentScreen==null || fCurrentScreen.get(mc)!=null
                    || kaTarget==null || EntityExclusions.contains(kaTarget) || fIsDead.getBoolean(kaTarget))return 0;
            if(mHandActive!=null&&Boolean.TRUE.equals(mHandActive.invoke(player))&&!combatBlock.owns(player))return 0;
            // Real mode displays the actual native use state. Only the cosmetic
            // mode alternates its pose to expose the native attack swing.
            if(p(MK,35)>0)return 0;
            long age=System.nanoTime()-targetVisualAttackAt;
            if(targetVisualAttackTarget==kaTarget&&age>=0&&age<75000000L)return -1;
            return blockingStack(heldItem(player)) || (!Ver.is189()&&mHeldOffhand != null
                    &&blockingStack(mHeldOffhand.invoke(player))) ? 1 : 0;
        } catch(Throwable failure){logOnce("Block animation",failure);return 0;}
    }
    public static int blockRenderCount(Object player,int nativeCount){
        int pose=blockRenderPose(player);return pose<0?0:pose>0?Math.max(1,nativeCount):nativeCount;
    }
    public static boolean blockRenderActive(Object player,boolean nativeActive){
        int pose=blockRenderPose(player);return pose<0?false:pose>0||nativeActive;
    }
    public static Object blockRenderHand(Object player,Object nativeHand){
        if(blockRenderPose(player)<=0)return nativeHand;
        try{return blockingStack(heldItem(player))?enumMainHand:enumOffHand;}
        catch(Throwable failure){return nativeHand;}
    }

    /** First-person visual interpolation only; native item use and attacks are untouched. */
    private static final BlockVisualCycle blockVisualCycle=new BlockVisualCycle();
    private static double blockItemPhase(Object player){
        try{
            if(player==null||mGetMC==null||fPlayer==null||!mods[MK].on||p(MK,33)<=0){blockVisualCycle.clear();return Double.NaN;}
            Object mc=mGetMC.invoke(null);
            if(fPlayer.get(mc)!=player)return Double.NaN;
            if(guiOpen||fCurrentScreen==null||fCurrentScreen.get(mc)!=null||kaTarget==null
                    ||EntityExclusions.contains(kaTarget)||fIsDead.getBoolean(kaTarget)
                    ||(mHandActive!=null&&Boolean.TRUE.equals(mHandActive.invoke(player))&&!combatBlock.owns(player))){
                blockVisualCycle.clear();return Double.NaN;
            }
            Object stack=heldItem(player);
            if(!blockingStack(stack))stack=mHeldOffhand != null?mHeldOffhand.invoke(player):null;
            if(!blockingStack(stack)){blockVisualCycle.clear();return Double.NaN;}
            long attack=targetVisualAttackTarget==kaTarget?targetVisualAttackAt:0;
            return blockVisualCycle.sample(player,stack,kaTarget,worldOf(player),(int)p(MK,35),attack,System.nanoTime());
        }catch(Throwable failure){blockVisualCycle.clear();logOnce("Block visual cycle",failure);return Double.NaN;}
    }
    public static float blockRenderSwing(float nativeProgress){
        try{
            Object mc=mGetMC==null?null:mGetMC.invoke(null);
            double phase=blockItemPhase(mc==null||fPlayer==null?null:fPlayer.get(mc));
            return Double.isNaN(phase)?nativeProgress:0f;
        }catch(Throwable failure){blockVisualCycle.clear();logOnce("Block visual swing",failure);return nativeProgress;}
    }
    public static int blockRenderItemCount(Object player,int nativeCount){
        double phase=blockItemPhase(player);
        return Double.isNaN(phase)?nativeCount:Math.max(1,nativeCount);
    }
    public static boolean blockRenderItemActive(Object player,boolean nativeActive){
        double phase=blockItemPhase(player);
        return Double.isNaN(phase)?nativeActive:true;
    }
    public static Object blockRenderItemHand(Object player,Object nativeHand){
        if(Double.isNaN(blockItemPhase(player)))return nativeHand;
        try{return blockingStack(heldItem(player))?enumMainHand:enumOffHand;}
        catch(Throwable failure){return nativeHand;}
    }
    public static void blockRenderBegin(Object hand){
        int index=hand!=null&&hand==enumOffHand?1:0;
        Object player=null,stack=null;
        try{
            if(mGetMC!=null&&fPlayer!=null&&mods[MK].on&&p(MK,33)>0){
                Object mc=mGetMC.invoke(null);player=fPlayer.get(mc);
                if(player!=null&&!guiOpen&&fCurrentScreen!=null&&fCurrentScreen.get(mc)==null
                        &&kaTarget!=null&&!EntityExclusions.contains(kaTarget)&&!fIsDead.getBoolean(kaTarget)
                        &&(mHandActive==null||!Boolean.TRUE.equals(mHandActive.invoke(player))||combatBlock.owns(player))){
                    stack=index==1&&mHeldOffhand != null?mHeldOffhand.invoke(player):heldItem(player);
                    if(!blockingStack(stack))stack=null;
                }
            }
        }catch(Throwable failure){stack=null;logOnce("Block hand context",failure);}
        BlockHandRenderer.begin(player,stack,index,stack==null?Double.NaN:blockItemPhase(player));
    }
    public static void blockRenderDraw(){BlockHandRenderer.draw();}
    public static void blockRenderEnd(){BlockHandRenderer.end();}

    private static void pollEntityExclusions(Object mc,Object player)throws Exception{
        EntityExclusions.context(mc==null||fWorld==null?null:fWorld.get(mc));
        boolean down=mIsButtonDown!=null&&Boolean.TRUE.equals(mIsButtonDown.invoke(null,2));
        if(!EntityExclusions.pressed(down,player==null||moduleHotkeysBlocked(mc)))return;
        Object entity=crosshairEntity(mc);
        if(entity==null||entity==player)return;
        boolean excluded=EntityExclusions.toggle(entity);
        if(excluded){
            if(kaTarget==entity)clearCombatAim();
            if(aimLock==entity){aimLock=null;aimLockUntil=0;}
            restoreHitBoxes();blockHoldUntil=0;
            if(targetHudState.target==entity)targetHudState.clear();
        }
        setToast((excluded?"已排除目标: ":"已恢复目标: ")+mGetName.invoke(entity));
    }

    private static boolean matchTarget(int mode, Object ent) {
        if (EntityExclusions.contains(ent)) return false;
        boolean pl = cPlayer.isInstance(ent);
        boolean an = cAnimal != null && cAnimal.isInstance(ent);
        boolean mo = cMob != null && cMob.isInstance(ent) && !an;
        switch (mode) {
            case 0: return pl;
            case 1: return mo;
            case 2: return an;
            case 3: return pl || mo;
            case 4: return pl || an;
            case 5: return pl || an || mo;
            default: return pl || an || mo;
        }
    }

    private static Object aimLock = null;
    private static long aimLockUntil = 0;

    /** 自瞄的候选判定: 活的、类型对、距离够、能看见 */
    private static boolean aimCandidate(Object player, Object ent, int targetMode, double range,
                                        boolean invis, boolean walls) throws Exception {
        if (ent == null || ent == player) return false;
        if (!cLiving.isInstance(ent)) return false;
        if (fIsDead.getBoolean(ent)) return false;
        if (!matchTarget(targetMode, ent)) return false;
        if (!invis && mIsInvisible != null && (Boolean) mIsInvisible.invoke(ent)) return false;
        if (isBot(player, ent)) return false;
        double d2 = ((Number) mDistSq.invoke(player, ent)).doubleValue();
        if (d2 > range * range) return false;
        if (!walls && mCanSee != null && !(Boolean) mCanSee.invoke(player, ent)) return false;
        return true;
    }

    /**
     * 自瞄。只转视角, 不攻击 —— 打不打由你自己决定, 这是它和 KillAura 的唯一区别。
     *
     * 走的是静默转向 (Silent): 角度只写进发给服务端的移动包, 本地屏幕不动。
     * 自瞄必须静默 —— 视角自己甩过去再甩回来, 肉眼一眼就能看出来。
     */
    /** AimBot 的"为什么不锁"节流记录 (1 秒一条)。和 scaffoldWhy 一样, 排完就删 */
    private static long aimbotDbgAt = 0;

    private static void doAimBot(Object mc, Object player) throws Exception {        if (p(F_AIMBOT, 11) > 0 && !(Boolean) mIsButtonDown.invoke(null, 0)) return;   // Only LMB
        Object world = fWorld.get(mc);
        if (world == null) return;

        // 参数一行一个 —— 探针按行解析 "(int) p(F_X, N)" 这种赋值, 一行里塞多个会漏掉后面的
        double range = p(F_AIMBOT, 3);
        double fov = p(F_AIMBOT, 2);
        int tmode = (int) p(F_AIMBOT, 4);
        int prio = (int) p(F_AIMBOT, 5);
        boolean invis = p(F_AIMBOT, 6) > 0;
        boolean walls = p(F_AIMBOT, 7) > 0;
        float cyaw = fYaw.getFloat(player);
        long now = System.currentTimeMillis();

        // Lock Ticks: 锁定后短时间内不重新选目标。两个敌人一前一后站着时, 视角会在
        // 它们之间来回横跳 —— 那是最容易被看出来的机器特征。
        Object target = null;
        if (aimLock != null && now < aimLockUntil) {
            if (aimCandidate(player, aimLock, tmode, range, invis, walls)) target = aimLock;
            else aimLock = null;
        }
        if (target == null) {
            double bestScore = Double.MAX_VALUE;
            for (Object ent : (java.util.List<?>) fLoaded.get(world)) {
                if (!aimCandidate(player, ent, tmode, range, invis, walls)) continue;
                float[] r = lookAt(player, fPosX.getDouble(ent),
                        fPosY.getDouble(ent) + eyeH(ent) * 0.6, fPosZ.getDouble(ent));
                double ang = Math.abs(wrapDeg(r[0] - cyaw));
                if (ang > fov * 0.5) continue;
                double d = Math.sqrt(((Number) mDistSq.invoke(player, ent)).doubleValue());
                double score;
                if (prio == 0) score = ang * 1000.0 + d;
                else if (prio == 2) score = ((Number) mGetHealth.invoke(ent)).floatValue() * 1000.0 + d;
                else score = d;
                if (score < bestScore) { bestScore = score; target = ent; }
            }
            if (target != null) {
                aimLock = target;
                aimLockUntil = now + (long) p(F_AIMBOT, 10) * 50L;
            }
        }
        if (target == null) {
            if (now - aimbotDbgAt >= 1000) {
                aimbotDbgAt = now;
                // 说清楚是"视野里没东西"还是"有但被过滤掉了" —— 这两个的修法完全不同
                int near = 0, inRange = 0, blocked = 0;
                String nearestName = "-";
                double nearestAng = -1;
                for (Object ent : (java.util.List<?>) fLoaded.get(world)) {
                    if (!(cLiving.isInstance(ent)) || fIsDead.getBoolean(ent)) continue;
                    if (!matchTarget(tmode, ent)) continue;
                    near++;
                    double dd = Math.sqrt(((Number) mDistSq.invoke(player, ent)).doubleValue());
                    if (dd > range) continue;
                    inRange++;
                    if (!invis && mIsInvisible != null && (Boolean) mIsInvisible.invoke(ent)) continue;
                    if (!walls && mCanSee != null && !(Boolean) mCanSee.invoke(player, ent)) { blocked++; continue; }
                    float[] rr = lookAt(player, fPosX.getDouble(ent),
                            fPosY.getDouble(ent) + eyeH(ent) * 0.6, fPosZ.getDouble(ent));
                    double ang = Math.abs(wrapDeg(rr[0] - cyaw));
                    if (nearestAng < 0 || ang < nearestAng) {
                        nearestAng = ang;
                        nearestName = String.valueOf(ent);
                    }
                }
                log("AimBot 没锁定: 同类型实体 " + near + " 个, 距离内 " + inRange
                        + " 个, 被墙挡 " + blocked + " 个, FOV 阈值 ±" + (fov * 0.5)
                        + ", 最近目标差 " + (nearestAng < 0 ? "无" : String.format("%.1f", nearestAng) + " 度")
                        + (nearestAng >= 0 && nearestAng > fov * 0.5 ? "  <-- 差得超出 FOV, 调大 FOV" : ""));
            }
            return;
        }
        if (now - aimbotDbgAt >= 1000) {
            aimbotDbgAt = now;
            float[] rl = lookAt(player, fPosX.getDouble(target),
                    fPosY.getDouble(target) + eyeH(target) * 0.6, fPosZ.getDouble(target));
            log("AimBot 锁定目标, 当前差 " + String.format("%.1f", Math.abs(wrapDeg(rl[0] - cyaw))) + " 度");
        }

        float[] r = lookAt(player, fPosX.getDouble(target),
                fPosY.getDouble(target) + eyeH(target) * 0.6, fPosZ.getDouble(target));
        double jit = p(F_AIMBOT, 8);
        if (jit > 0) {
            r[0] += (float) ((Math.random() - 0.5) * jit * 2);
            r[1] += (float) ((Math.random() - 0.5) * jit);
        }
        int tid = 0;
        if (mGetEntityId != null) {
            try { tid = (Integer) mGetEntityId.invoke(target); } catch (Throwable ignored) { }
        }

        // AimBot.Mode: 0=Visible(真的转视角), 1=Silent(只让服务端看到)
        //
        // **默认必须是 Visible。** Silent 档下本地视角一动不动 —— 那是它的设计目的,
        // 但用户第一人称看过去就是"什么都没发生", 直觉上等于功能坏了。
        // 自瞄的意义就是让你看见锁上了谁。
        //
        // **优先级 20。** 这一条原来没有仲裁, 而 KillAura 在功能表里排在 AimBot 后面、
        // 开箱又同样是 Visible —— 于是 AimBot 每帧刚写完角度就被 KillAura 覆盖,
        // 表现就是"AimBot 开了没反应"。现在按优先级抢: KillAura(30) 在打人时赢,
        // 它没目标时 AimBot 自然就写进去了。
        boolean aimVisible = (int) p(F_AIMBOT, 12) == 0;
        float cpit = fPitch.getFloat(player);
        if (aimVisible) {
            if (!claimVisibleAim(20, "AimBot")) return;
            // 直接写真实视角。smoothTo 里已经带了比例收敛, 快到目标时自动减速。
            float[] sm = smoothTo(cyaw, cpit, r[0], r[1], p(F_AIMBOT, 0), 0.5f);
            // Smoothness 越小越"拖" —— 在比例项之上再叠一层, 想慢就调它
            float k = Math.max(0.15f, Math.min(1.0f, (float) p(F_AIMBOT, 1)));
            fYaw.setFloat(player, cyaw + (sm[0] - cyaw) * k);
            fPitch.setFloat(player, cpit + (sm[1] - cpit) * k);
            return;
        }

        // Silent: 只走包, 本地视角不动。优先级 20, 同样让位于 KillAura。
        if (!claimSilentAim(20, "AimBot")) return;
        double maxStep = Math.max(1.0, p(F_AIMBOT, 0) / 20.0);
        double k = Math.max(0.05, Math.min(1.0, p(F_AIMBOT, 1)));   // Smoothness: 每次靠近多少
        float dy = (float) Math.max(-maxStep, Math.min(maxStep, wrapDeg(r[0] - cyaw) * k));
        float dp = (float) Math.max(-maxStep, Math.min(maxStep, (r[1] - cpit) * k));
        requestSilent(cyaw + dy, cpit + dp, tid, (int) p(F_AIMBOT, 9));
    }

    private static void doKillAura(Object mc, Object player) throws Exception {
        long now = System.currentTimeMillis();
        if (guiOpen || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)) { clearCombatAim(); return; }
        if (p(MK, 13) > 0 && !fOnGround.getBoolean(player)) { clearCombatAim(); return; }
        if (p(MK, 22) > 0 && !isWeapon(heldItem(player))) { clearCombatAim(); return; }
        if (p(MK, 23) > 0 && !combatBlock.owns(player) && mHandActive != null && Boolean.TRUE.equals(mHandActive.invoke(player))) { clearCombatAim(); return; }
        Object world = fWorld.get(mc);
        if (world == null) { clearCombatAim(); return; }
        java.util.List<?> entities = (java.util.List<?>) fLoaded.get(world);
        Object best = null, scanFallback = null, heldFallback = null;
        double scanExtra = Math.max(0.0, p(MK, 32));
        double attackRange = p(MK, 0), scanRange = attackRange + scanExtra;
        // Keep the current target's identity and the existing hold grace.
        // Acquiring outside attack range lets the unchanged rotation engine
        // turn before contact; only validCombatTarget may approve an attack.
        if (kaTarget != null && entities.contains(kaTarget)
                && validCombatIdentity(player, kaTarget, scanExtra + 0.35)) {
            boolean validNow = validCombatCandidate(player, kaTarget, true, scanRange);
            long hold = (long) p(MK, 19);
            if (validNow) kaTargetUntil = hold <= 0 ? now : now + hold;
            if (validNow && combatTargetDistanceSq(player, kaTarget) <= attackRange * attackRange)
                best = kaTarget;
            else if (validNow) scanFallback = kaTarget;
            else if (now <= kaTargetUntil) heldFallback = kaTarget;
        }
        double bestScore = Double.MAX_VALUE, scanScore = Double.MAX_VALUE;
        Object scanned = null;
        if (best == null) for (Object ent : entities) {
            if (!validCombatCandidate(player, ent, false, scanRange)) continue;
            float[] r = combatAngles(player, ent);
            double distance = combatTargetDistanceSq(player, ent);
            double score;
            int sort = (int) p(MK, 5);
            if (sort == 0) score = Math.abs(wrapDeg(r[0] - fYaw.getFloat(player))) * 1000 + distance;
            else if (sort == 2) score = ((Number) mGetHealth.invoke(ent)).doubleValue() * 1000 + distance;
            else score = distance;
            if (distance <= attackRange * attackRange) {
                if (score < bestScore) { bestScore = score; best = ent; }
            } else if (score < scanScore) { scanScore = score; scanned = ent; }
        }
        // A pre-aim/held target must never starve an enemy in attack range.
        // Among scan-only targets retain identity rather than reset the pose.
        if (best == null) best = scanFallback != null ? scanFallback : scanned;
        if (best == null) best = heldFallback;
        if (best == null) { clearCombatAim(); return; }
        if (kaTarget != best) {
            kaTarget = best; kaTargetSince = now; kaLastVisibleAt = now; kaLastReadyAt = 0L;
            kaConfirmedTicks = 0; kaRenderedTarget = null; kaPacketTarget = null; kaPacketAt = 0L;
            kaPacketReady = kaRenderReady = kaRenderCompleted = false;
            kaConfirmationTick = Integer.MIN_VALUE;
            kaTargetUntil = now + (long) p(MK, 19);
            kaBiasYaw = (float) ((Math.random() - .5) * p(MK, 4));
            kaBiasPitch = (float) ((Math.random() - .5) * p(MK, 4));
        }
        float[] aim = combatAngles(player, best);
        int rotMode = (int) p(MK, 9);
        boolean smooth = p(MK, 3) > 0;
        int targetId = 0;
        if (mGetEntityId != null) {
            try { targetId = ((Number)mGetEntityId.invoke(best)).intValue(); } catch (Throwable ignored) { }
        }
        if (rotMode == 1 && claimSilentAim(30, "KillAura")) {
            requestSilent(aim[0] + kaBiasYaw, aim[1] + kaBiasPitch, targetId, 0, true);
            aimReactUntil = kaTargetSince + (long) p(MK, 18);
            silentYawStep = smooth ? (float) p(MK, 1) / 20f : 180f;
            silentPitchStep = smooth ? (float) p(MK, 15) / 20f : 180f;
        } else if (rotMode == 2 && claimVisibleAim(30, "KillAura")) {
            float delta = kaTurnAt == 0 ? .05f : Math.min(.1f, Math.max(0f, (now-kaTurnAt)/1000f));
            if (now >= kaTargetSince + (long) p(MK, 18)) {
                fYaw.setFloat(player, turnToward(fYaw.getFloat(player), aim[0] + kaBiasYaw,
                        smooth ? (float) p(MK, 1)*delta : 180f, true));
                fPitch.setFloat(player, turnToward(fPitch.getFloat(player), aim[1] + kaBiasPitch,
                        smooth ? (float) p(MK, 15)*delta : 180f, false));
            }
        }
        kaTurnAt = now;
        kaStatus = "Tracking / waiting for model";
        // The attack waits for both this tick's movement packet and a completed
        // frame that actually displays its target aim.
    }

    private static void clearCombatAim() {
        releaseCombatBlock();
        boolean ownsSilent = "KillAura".equals(silAimWho)
                || (silAimWho == null && kaModelPoseReady && silentBodyToo);
        kaTarget = null; kaRenderedTarget = null; kaPacketTarget = null;
        kaLastVisibleAt = 0L; kaLastReadyAt = 0L; kaPacketAt = 0L;
        kaTargetSince = 0L; kaTargetUntil = 0L; kaRenderedAt = 0L;
        kaConfirmedTicks = 0; kaTurnAt = 0; kaNextAttackAt = 0L; kaStatus = "Idle";
        kaModelPoseTarget = null; kaModelPoseReady = false; kaModelTick = Integer.MIN_VALUE;
        kaPacketPlayer = kaPacketWorld = kaRenderPlayer = kaRenderWorld = null;
        kaPacketReady = kaRenderReady = kaRenderCompleted = kaRenderRayValid = kaRenderCommit = false;
        kaAttackQueued = false;
        // Completed attacks belong to the player/world/tick, not its aim target.
        // Losing or reacquiring aim must not erase that action's ownership.
        kaQueuedTarget = kaQueuedPlayer = kaQueuedWorld = null;
        kaPacketTick = kaRenderTick = kaConfirmationTick = kaAttackTick = kaQueuedPacketTick = Integer.MIN_VALUE;
        if (ownsSilent) {
            silentOn = false;
            silentModelOn = false;
            silentApplied = false;
            silentWasAiming = false;
            silentBodyToo = false;
            aimTargetId = Integer.MIN_VALUE;
            aimReactUntil = 0L;
            if ("KillAura".equals(silAimWho)) silAimWho = null;
        }
    }

    static float turnToward(float from, float to, float budget, boolean wrap) {
        float d = wrap ? shortestDelta(from, to) : to - from;
        return from + Math.max(-budget, Math.min(budget, d));
    }

    private static float[] combatAngles(Object player, Object target) throws Exception {
        float[] preferred = lookAt(player, fPosX.getDouble(target),
                fPosY.getDouble(target) + eyeH(target) * p(MK, 20), fPosZ.getDouble(target));
        double range=p(MK,0), ox=fPosX.getDouble(player), oy=fPosY.getDouble(player)+eyeH(player), oz=fPosZ.getDouble(player);
        if (mGetBB==null || mCombatCollisionBorder==null || !(range>0)) return preferred;
        if (combatEntityRayDistance(target,ox,oy,oz,preferred[0],preferred[1],range,1f)<=range) return preferred;
        Object box=mGetBB.invoke(target);
        if (box==null) return preferred;
        double minX=mAabbMinX.getDouble(box), minY=mAabbMinY.getDouble(box), minZ=mAabbMinZ.getDouble(box);
        double maxX=mAabbMaxX.getDouble(box), maxY=mAabbMaxY.getDouble(box), maxZ=mAabbMaxZ.getDouble(box);
        if (!Double.isFinite(minX+minY+minZ+maxX+maxY+maxZ)
                || minX>maxX || minY>maxY || minZ>maxZ) return preferred;
        // Prefer configured height whenever its ray reaches the box. During
        // vertical separation a lower chest point can be outside reach even
        // though the upper face is reachable. Aim inside the actual box;
        // collision dimensions and final attack checks remain unchanged.
        double y=combatInteriorCoordinate(oy,minY,maxY);
        float[] level=lookAt(player,(minX+maxX)*.5,y,(minZ+maxZ)*.5);
        if (combatEntityRayDistance(target,ox,oy,oz,level[0],level[1],range,1f)<=range) return level;
        float[] near=lookAt(player,combatInteriorCoordinate(ox,minX,maxX),y,
                combatInteriorCoordinate(oz,minZ,maxZ));
        return combatEntityRayDistance(target,ox,oy,oz,near[0],near[1],range,1f)<=range ? near : preferred;
    }

    private static double combatInteriorCoordinate(double eye,double min,double max) {
        double inset=Math.min(.03,Math.max(0,(max-min)*.1));
        return Math.max(min+inset,Math.min(max-inset,eye));
    }

    private static boolean validCombatTarget(Object player, Object ent) throws Exception {
        return validCombatTarget(player, ent, false);
    }

    /** A death animation may keep isDead=false after health has reached zero. */
    private static boolean combatLivingTarget(Object player, Object ent) throws Exception {
        if (ent == null || ent == player || !cLiving.isInstance(ent) || fIsDead.getBoolean(ent)) return false;
        if (mIsAlive != null && !Boolean.TRUE.equals(mIsAlive.invoke(ent))) return false;
        if (mGetHealth != null) {
            double health = ((Number) mGetHealth.invoke(ent)).doubleValue();
            if (!(health > 0) || !Double.isFinite(health)) return false;
        }
        if (mCombatSpectator != null && cPlayer.isInstance(ent)
                && Boolean.TRUE.equals(mCombatSpectator.invoke(ent))) return false;
        int targetMode = (int) p(MK, 6);
        if (targetMode < 0 || !matchTarget(targetMode, ent) || isBot(player, ent)) return false;
        if (cPlayer.isInstance(ent) && !combatPlayerListed(ent)) return false;
        return p(MK, 7) > 0 || mIsInvisible == null || !Boolean.TRUE.equals(mIsInvisible.invoke(ent));
    }

    /**
     * KA's player category requires a matching identity in the current connection.
     * Rechecked for acquisition, target hold and attack; never cached across servers.
     * This is not proof of a human: a server can publish identical info for an NPC.
     */
    private static boolean combatPlayerListed(Object ent) {
        if (mGetMC == null || mGetConnection == null || mGetPlayerInfo == null || mGetUUID == null
                || mGetGameProfile == null || mCombatEntityProfile == null
                || mGetProfileId == null || mGetProfileName == null) {
            if (failedOnce.add("KA player identity handles"))
                log("KA player identity unavailable: player targets skipped (missing profile/network handle)");
            return false;
        }
        try {
            Object mc = mGetMC.invoke(null);
            Object connection = mc == null ? null : mGetConnection.invoke(mc);
            if (connection == null) return false;
            Object uuid = mGetUUID.invoke(ent);
            if (!(uuid instanceof java.util.UUID)) return false;
            // Citizens marks its standard player NPC UUIDs as version 2
            // (AbstractNPC#getMinecraftUniqueId, also legacy HumanController).
            // Their temporary Tab entries must not authorize a KA target.
            if (((java.util.UUID) uuid).version() == 2) return false;
            Object info = mGetPlayerInfo.invoke(connection, uuid);
            if (info == null) return false;
            Object listed = mGetGameProfile.invoke(info), entity = mCombatEntityProfile.invoke(ent);
            if (listed == null || entity == null
                    || !uuid.equals(mGetProfileId.invoke(listed)) || !uuid.equals(mGetProfileId.invoke(entity)))
                return false;
            // Use raw profile names, never colored/custom display labels or skin properties.
            Object name = mGetProfileName.invoke(entity), listedName = mGetProfileName.invoke(listed);
            return name instanceof String && !((String) name).isEmpty() && name.equals(listedName);
        } catch (Throwable t) {
            logOnce("KA player identity lookup", t);
            return false;
        }
    }

    /** Nearest eye-to-pick-box distance; uses the same native border as the final ray test. */
    private static double combatTargetDistanceSq(Object player, Object target) throws Exception {
        if (mGetBB == null || mAabbMinX == null || mAabbMinY == null || mAabbMinZ == null
                || mAabbMaxX == null || mAabbMaxY == null || mAabbMaxZ == null)
            return Double.POSITIVE_INFINITY;
        Object box = mGetBB.invoke(target);
        if (box == null) return Double.POSITIVE_INFINITY;
        double border = mCombatCollisionBorder == null ? 0.0
                : ((Number) mCombatCollisionBorder.invoke(target)).doubleValue();
        if (!Double.isFinite(border) || border < 0) return Double.POSITIVE_INFINITY;
        double[] eye = {fPosX.getDouble(player), fPosY.getDouble(player) + eyeH(player), fPosZ.getDouble(player)};
        double[] lo = {mAabbMinX.getDouble(box)-border, mAabbMinY.getDouble(box)-border, mAabbMinZ.getDouble(box)-border};
        double[] hi = {mAabbMaxX.getDouble(box)+border, mAabbMaxY.getDouble(box)+border, mAabbMaxZ.getDouble(box)+border};
        double distance = 0.0;
        for (int axis=0; axis<3; axis++) {
            if (!Double.isFinite(eye[axis]) || !Double.isFinite(lo[axis]) || !Double.isFinite(hi[axis])
                    || lo[axis]>hi[axis]) return Double.POSITIVE_INFINITY;
            double delta = Math.max(lo[axis]-eye[axis], Math.max(0.0, eye[axis]-hi[axis]));
            distance += delta*delta;
        }
        return distance;
    }

    /** Hard identity checks used only while Target Hold is counting down. */
    private static boolean validCombatIdentity(Object player, Object ent, double rangeMargin) throws Exception {
        if (!combatLivingTarget(player, ent)) return false;
        double range = p(MK, 0) + Math.max(0.0, rangeMargin);
        return combatTargetDistanceSq(player, ent) <= range * range;
    }

    /** Attack validation always retains the configured attack Range, never the scan distance. */
    private static boolean validCombatTarget(Object player, Object ent, boolean tracked) throws Exception {
        return validCombatCandidate(player, ent, tracked, p(MK, 0));
    }

    private static boolean validCombatCandidate(Object player, Object ent, boolean tracked, double range) throws Exception {
        if (!combatLivingTarget(player, ent) || !(range > 0) || !Double.isFinite(range)) return false;
        if (combatTargetDistanceSq(player, ent) > range*range) return false;
        if (p(MK, 12) <= 0 && mCanSee != null) {
            boolean visible = Boolean.TRUE.equals(mCanSee.invoke(player, ent));
            if (!visible && (!tracked || System.currentTimeMillis() - kaLastVisibleAt > 150L)) return false;
            if (tracked && visible) kaLastVisibleAt = System.currentTimeMillis();
        }
        return tracked || p(MK, 8) >= 360
                || Math.abs(shortestDelta(fYaw.getFloat(player), combatAngles(player, ent)[0])) <= p(MK, 8) * .5;
    }

    private static void publishCombatPacket(Object player, float yaw, float pitch) throws Exception {
        kaPacketTarget = kaTarget;
        kaPacketPlayer = player;
        kaPacketWorld = worldOf(player);
        kaPacketTick = (int) scaffoldClock(player);
        kaPacketYaw = yaw;
        kaPacketPitch = Math.max(-90f, Math.min(90f, pitch));
        kaPacketAt = System.currentTimeMillis();
        kaPacketEyeX = fPosX.getDouble(player);
        kaPacketEyeY = fPosY.getDouble(player) + eyeH(player);
        kaPacketEyeZ = fPosZ.getDouble(player);
    }

    private static void observeCombatModel() {
        kaRenderReady = kaRenderCompleted = false;
        if (!mods[F_KILLAURA].on || kaTarget == null) return;
        // Natural Aim publishes only frames owned by KillAura.  AutoPot and
        // Scaffold share the silent channel; recording either pose against the
        // current KA target would let the next packet reuse the wrong module's
        // rendered yaw/pitch.  Visible/Off still observe vanilla model fields.
        if ((int) p(MK, 9) == 1 && !"KillAura".equals(silAimWho)) return;
        try {
            Object mc = mGetMC.invoke(null);
            Object player = fPlayer.get(mc);
            if (player == null || fRotationYawHead == null || !kaPacketReady
                    || kaPacketTarget != kaTarget || kaPacketPlayer != player
                    || kaPacketWorld != worldOf(player) || kaPacketTick != (int) scaffoldClock(player)) return;
            float pt = Math.max(0f, Math.min(1f, camHookPartialTicks));
            float head = fRotationYawHead.getFloat(player);
            float previousHead = fPrevRotationYawHead == null ? head : fPrevRotationYawHead.getFloat(player);
            float pitch = fPitch.getFloat(player);
            float previousPitch = fPrevPitch == null ? pitch : fPrevPitch.getFloat(player);
            kaRenderedYaw = previousHead + shortestDelta(previousHead, head) * pt;
            kaRenderedPitch = previousPitch + (pitch - previousPitch) * pt;
            kaRenderedTarget = kaTarget; kaRenderedAt = System.currentTimeMillis();
            kaRenderPlayer = player; kaRenderWorld = worldOf(player); kaRenderTick = kaPacketTick;
            kaRenderPacketYaw = kaPacketYaw; kaRenderPacketPitch = kaPacketPitch;
            kaRenderEyeX = scaffoldRenderPosition(player, fPosX, fLastTickX, pt);
            kaRenderEyeY = scaffoldRenderPosition(player, fPosY, fLastTickY, pt) + eyeH(player);
            kaRenderEyeZ = scaffoldRenderPosition(player, fPosZ, fLastTickZ, pt);
            // Aim Height steers the animation, but attack approval uses the
            // whole entity box.  Both the displayed head ray and the packet
            // ray may enter at any point; moving across one configured point
            // must not break the attack stream.
            boolean strictRay = p(MK, 24) > 0;
            boolean modelHit = strictRay
                    ? combatRayHitsTarget(kaRenderWorld, player, kaTarget,
                            kaRenderEyeX, kaRenderEyeY, kaRenderEyeZ, kaRenderedYaw, kaRenderedPitch, pt)
                    : combatRayIntersectsTarget(kaRenderWorld, kaTarget,
                            kaRenderEyeX, kaRenderEyeY, kaRenderEyeZ, kaRenderedYaw, kaRenderedPitch, pt);
            // The displayed body uses interpolated geometry; the transmitted
            // pose belongs to the completed player tick. Testing both from
            // the render eye could approve a miss or reject a real hit during
            // close-range strafing. Keep these two proofs in their own spaces.
            boolean packetHit = combatPacketRay(kaRenderWorld, player, kaTarget);
            kaRenderModelHit = modelHit;
            kaRenderPacketHit = packetHit;
            kaRenderRayValid = modelHit && packetHit;
            kaRenderSerial = activeWorldProjectionSerial;
            kaRenderReady = true;
        } catch (Throwable t) { logOnce("combat model", t); }
    }

    private static void completeCombatRender() {
        kaRenderCompleted = kaRenderReady && kaRenderSerial == activeWorldProjectionSerial;
    }

    private static void finishCombatRender(Object mc, Object player) throws Exception {
        syncScaffoldContext(mc, player);
        if (!kaRenderReady || !kaRenderCompleted) return;
        kaRenderReady = kaRenderCompleted = false;
        if (mc == null || player == null || player != kaRenderPlayer || player != kaPacketPlayer
                || worldOf(player) != kaRenderWorld || kaPacketWorld != kaRenderWorld
                || kaRenderTick != (int) scaffoldClock(player) || kaPacketTick != kaRenderTick
                || !kaPacketReady || kaPacketTarget != kaTarget || kaRenderedTarget != kaTarget
                || kaRenderPacketYaw != kaPacketYaw || kaRenderPacketPitch != kaPacketPitch
                || kaPacketEyeX != fPosX.getDouble(player) || kaPacketEyeY != fPosY.getDouble(player) + eyeH(player)
                || kaPacketEyeZ != fPosZ.getDouble(player)
                || !((java.util.List<?>) fLoaded.get(kaRenderWorld)).contains(kaTarget)) return;
        // Rendering proves that the model really displayed this pose.  It does
        // not run gameplay code: vanilla starts swing/sprint/knockback from the
        // client-tick input phase before the world tick, so queue this exact
        // credential for the matching phase instead of attacking at FPS time.
        kaQueuedTarget = kaTarget;
        kaQueuedPlayer = player;
        kaQueuedWorld = kaRenderWorld;
        kaQueuedPacketTick = kaPacketTick;
        kaQueuedPacketYaw = kaPacketYaw;
        kaQueuedPacketPitch = kaPacketPitch;
        kaQueuedRenderedYaw = kaRenderedYaw;
        kaQueuedRenderedPitch = kaRenderedPitch;
        kaQueuedEyeX = kaPacketEyeX;
        kaQueuedEyeY = kaPacketEyeY;
        kaQueuedEyeZ = kaPacketEyeZ;
        kaQueuedRenderedAt = kaRenderedAt;
        kaQueuedRayValid = kaRenderRayValid;
        kaQueuedModelHit = kaRenderModelHit;
        kaQueuedPacketHit = kaRenderPacketHit;
        kaAttackQueued = true;
    }

    /** Observes a completed native manual attack without sending any input. */
    public static void onManualAttack() {
        try {
            if (combatNativeClickActive) { combatNativeClickDispatched = true; return; }
            if(mods==null||!mods[MK].on||mGetMC==null||fPlayer==null)return;
            Object mc=mGetMC.invoke(null),player=fPlayer.get(mc);
            if(player==null)return;
            // The native click already dispatched. Reserve this tick and a
            // fresh interval before KA consumes its completed-frame ticket.
            kaAttackTick=(int)scaffoldClock(player);
            kaNextAttackAt=System.currentTimeMillis()+CombatCadence.period(p(MK,2));
            recordCombatAttack(player);
        }catch(Throwable failure){logOnce("manual attack observation",failure);}
    }

    /** Consumes a completed frame after input and before entity movement. */
    public static void onClientTickAttack() {
        combatAttemptReason="no-completed-frame";
        combatRayRechecked=false;
        try { consumeClientTickAttack(); }
        finally { traceCombatTick(); }
    }

    private static void consumeClientTickAttack() {
        try { Object mc=mGetMC==null?null:mGetMC.invoke(null);
            maintainCombatBlock(mc,mc==null||fPlayer==null?null:fPlayer.get(mc),true);
        } catch (Throwable failure) { logOnce("AutoBlock tick",failure); }
        if (!kaAttackQueued) return;
        kaAttackQueued = false;
        combatAttemptReason="stale-frame-context";
        try {
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object player = mc == null || fPlayer == null ? null : fPlayer.get(mc);
            if (mc == null || player == null || player != kaQueuedPlayer
                    || worldOf(player) != kaQueuedWorld || kaTarget != kaQueuedTarget
                    || kaPacketTarget != kaQueuedTarget
                    || kaPacketPlayer != player || kaPacketWorld != kaQueuedWorld
                    || kaPacketTick != kaQueuedPacketTick
                    || kaPacketTick != (int) scaffoldClock(player)
                    || kaPacketYaw != kaQueuedPacketYaw || kaPacketPitch != kaQueuedPacketPitch
                    || kaPacketEyeX != kaQueuedEyeX || kaPacketEyeY != kaQueuedEyeY
                    || kaPacketEyeZ != kaQueuedEyeZ
                    || fPosX.getDouble(player) != kaQueuedEyeX
                    || fPosY.getDouble(player) + eyeH(player) != kaQueuedEyeY
                    || fPosZ.getDouble(player) != kaQueuedEyeZ
                    || fLoaded == null
                    || !((java.util.List<?>) fLoaded.get(kaQueuedWorld)).contains(kaQueuedTarget)) return;
            // Extra shadow/reflection render passes may overwrite the live
            // render fields before the next client tick.  The queued record is
            // the completed world-frame credential, so consume that immutable
            // sample instead of comparing it to a later pass.
            kaRenderedTarget = kaQueuedTarget;
            kaRenderedYaw = kaQueuedRenderedYaw;
            kaRenderedPitch = kaQueuedRenderedPitch;
            kaRenderedAt = kaQueuedRenderedAt;
            // Network updates can move a target or obstruction between the
            // completed frame and input. Retain its display proof, but do not
            // dispatch an interaction against collision geometry it has left.
            kaRenderRayValid = kaQueuedRayValid
                    && combatPacketRay(kaQueuedWorld, player, kaQueuedTarget);
            combatRayRechecked=kaQueuedRayValid;
            combatAttemptReason=kaQueuedRayValid?"current-ray-miss":"display-ray-miss";
            kaRenderCommit = true;
            try { attackAfterAimTick(mc, player); }
            finally { kaRenderCommit = false; }
        } catch (Throwable t) {
            combatAttemptReason="attack-exception";
            logOnce("combat client tick", t);
        }
    }

    private static String combatAttemptReason="idle";
    private static boolean combatRayRechecked;
    private static Object combatTracePlayer;
    private static int combatTraceTick=Integer.MIN_VALUE,combatTraceUntil=Integer.MIN_VALUE;
    private static long combatTraceAt;
    /** Observe the attacked player's state; target hurtTime is not this value. */
    private static void traceCombatTick() {
        try {
            if(mods==null||!mods[MK].on||kaTarget==null||mGetMC==null||fPlayer==null)return;
            Object mc=mGetMC.invoke(null),player=fPlayer.get(mc);
            if(player==null)return;
            int tick=(int)scaffoldClock(player);
            if(player!=combatTracePlayer||tick<combatTraceTick){combatTracePlayer=player;combatTraceTick=Integer.MIN_VALUE;combatTraceUntil=Integer.MIN_VALUE;combatTraceAt=0;}
            if(tick==combatTraceTick)return;
            combatTraceTick=tick;
            int hurt=fHurtTime==null?-1:fHurtTime.getInt(player);
            if(hurt>0)combatTraceUntil=tick+20;
            long now=System.currentTimeMillis();
            if(tick>combatTraceUntil&&now-combatTraceAt<500)return;
            combatTraceAt=now;
            log("Combat tick: tick="+tick+" selfHurt="+hurt+" reason="+combatAttemptReason
                    +" status="+kaStatus+" onGround="+fOnGround.getBoolean(player)
                    +" position="+fPosX.getDouble(player)+","+fPosY.getDouble(player)+","+fPosZ.getDouble(player)
                    +" velocity="+combatObservedMotion(player,fMotionX)+","+combatObservedMotion(player,fMotionY)+","+combatObservedMotion(player,fMotionZ)
                    +" eyeDelta="+(fPosX.getDouble(player)-kaQueuedEyeX)+","+(fPosY.getDouble(player)+eyeH(player)-kaQueuedEyeY)+","+(fPosZ.getDouble(player)-kaQueuedEyeZ)
                    +" packetTick="+kaPacketTick+" frameTick="+kaQueuedPacketTick
                    +" frameRays="+kaQueuedRayValid+" tickRay="+(combatRayRechecked?String.valueOf(kaRenderRayValid):"not-checked")
                    +" frameModelRay="+kaQueuedModelHit+" framePacketRay="+kaQueuedPacketHit
                    +" targetDistance="+Math.sqrt(combatTargetDistanceSq(player,kaTarget))+" range="+p(MK,0)
                    +" rateWaitMs="+Math.max(0,kaNextAttackAt-now)+" requests="+kaAttackCount);
        }catch(Throwable failure){logOnce("combat tick observation",failure);}
    }
    private static double combatObservedMotion(Object player,Field motion){
        try{return motion==null?Double.NaN:motion.getDouble(player);}catch(Throwable unavailable){return Double.NaN;}
    }

    private static boolean combatNativeClickActive, combatNativeClickDispatched;
    private static java.lang.reflect.Constructor<?> combatEntityHitConstructor;
    private static Object combatSuccessfulPlayer, combatSuccessfulWorld;
    private static int combatSuccessfulTick = Integer.MIN_VALUE;

    private static void recordCombatAttack(Object player) throws Exception {
        combatSuccessfulPlayer = player;
        combatSuccessfulWorld = worldOf(player);
        combatSuccessfulTick = (int) scaffoldClock(player);
    }

    /** Coordinate automatic clicks only after an actual controller call. */
    private static boolean combatAttackAlreadySent(Object player) throws Exception {
        return mods != null && mods[MK].on && player != null
                && player == combatSuccessfulPlayer && worldOf(player) == combatSuccessfulWorld
                && (int) scaffoldClock(player) == combatSuccessfulTick;
    }

    /** The confirmed model pick is scoped to one native left click. */
    private static boolean clickCombatTarget(Object mc, Object player, Object pc,
            Object target, boolean shouldSwing) throws Exception {
        // Swing=false explicitly suppresses animation; native clickMouse always
        // swings. Preserve that option's existing controller-only semantics.
        if (!shouldSwing) { mAttackEntity.invoke(pc, player, target); return true; }
        if (mClickMouse == null || fObjectMouseOver == null || ctorVec3d == null || cEntity == null) {
            combatAttemptReason="missing-native-click"; return false;
        }
        double ox=fPosX.getDouble(player), oy=fPosY.getDouble(player)+eyeH(player), oz=fPosZ.getDouble(player);
        float yaw=fYaw.getFloat(player), pitch=fPitch.getFloat(player);
        double distance=combatEntityRayDistance(target,ox,oy,oz,yaw,pitch,p(MK,0),1f);
        if (!Double.isFinite(distance) || distance>p(MK,0)) {
            combatAttemptReason="current-ray-miss"; return false;
        }
        Class<?> hitClass=fObjectMouseOver.getType();
        if (combatEntityHitConstructor==null || combatEntityHitConstructor.getDeclaringClass()!=hitClass)
            combatEntityHitConstructor=hitClass.getConstructor(cEntity,ctorVec3d.getDeclaringClass());
        double[] direction=combatRayDirection(player,yaw,pitch);
        Object point=ctorVec3d.newInstance(ox+direction[0]*distance,oy+direction[1]*distance,oz+direction[2]*distance);
        Object picked=combatEntityHitConstructor.newInstance(target,point);
        Object savedPick=fObjectMouseOver.get(mc);
        combatNativeClickDispatched=false;
        combatNativeClickActive=true;
        try {
            fObjectMouseOver.set(mc,picked);
            clickPrimary(mc,player);
            if (!combatNativeClickDispatched) combatAttemptReason="native-click-blocked";
            return combatNativeClickDispatched;
        } finally {
            combatNativeClickActive=false;
            fObjectMouseOver.set(mc,savedPick);
        }
    }

    static boolean aimConfirmed(float yaw, float pitch, float modelYaw, float modelPitch,
                                float targetYaw, float targetPitch, double tolerance) {
        return Math.abs(shortestDelta(yaw, targetYaw)) <= tolerance && Math.abs(pitch-targetPitch) <= tolerance
                && Math.abs(shortestDelta(modelYaw, targetYaw)) <= tolerance && Math.abs(modelPitch-targetPitch) <= tolerance;
    }

    /** Tick-space interaction ray, independent of the rendered eye/target interpolation. */
    private static boolean combatPacketRay(Object world, Object player, Object target) {
        return p(MK, 24) > 0
                ? combatRayHitsTarget(world, player, target,
                        kaPacketEyeX, kaPacketEyeY, kaPacketEyeZ, kaPacketYaw, kaPacketPitch, 1f)
                : combatRayIntersectsTarget(world, target,
                        kaPacketEyeX, kaPacketEyeY, kaPacketEyeZ, kaPacketYaw, kaPacketPitch, 1f);
    }

    /** Packet and rendered model must describe the same confirmed pose. */
    static boolean modelPacketAligned(float packetYaw, float packetPitch,
                                      float modelYaw, float modelPitch, double tolerance) {
        return Math.abs(shortestDelta(packetYaw, modelYaw)) <= tolerance
                && Math.abs(packetPitch - modelPitch) <= tolerance;
    }

    static boolean rayHitsBox(double ox, double oy, double oz, float yaw, float pitch,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ, double reach) {
        double yr=Math.toRadians(yaw), pr=Math.toRadians(pitch);
        double[] origin={ox,oy,oz}, direction={-Math.sin(yr)*Math.cos(pr),-Math.sin(pr),Math.cos(yr)*Math.cos(pr)};
        double[] lo={minX,minY,minZ}, hi={maxX,maxY,maxZ};
        double near=0, far=reach;
        for(int axis=0;axis<3;axis++) {
            if(Math.abs(direction[axis])<1e-9) { if(origin[axis]<lo[axis] || origin[axis]>hi[axis]) return false; }
            else {
                double a=(lo[axis]-origin[axis])/direction[axis], b=(hi[axis]-origin[axis])/direction[axis];
                near=Math.max(near,Math.min(a,b)); far=Math.min(far,Math.max(a,b));
                if(near>far) return false;
            }
        }
        return far>=0;
    }

    /** Distance to the first selected entity box, including vanilla's border. */
    private static double combatEntityRayDistance(Object entity, double ox, double oy, double oz,
            float yaw, float pitch, double reach, float partialTicks) throws Exception {
        Object box = mGetBB.invoke(entity);
        if (box == null) return Double.POSITIVE_INFINITY;
        double border = ((Number) mCombatCollisionBorder.invoke(entity)).doubleValue();
        if (!Double.isFinite(border) || border < 0) return Double.POSITIVE_INFINITY;
        // Native interaction picking uses current entity bounds. Offset only
        // the separate visual proof; at tick space use the exact AABB without
        // subtract/add cancellation at large coordinates.
        double x = partialTicks == 1f ? 0 : scaffoldRenderPosition(entity, fPosX, fLastTickX, partialTicks) - fPosX.getDouble(entity);
        double y = partialTicks == 1f ? 0 : scaffoldRenderPosition(entity, fPosY, fLastTickY, partialTicks) - fPosY.getDouble(entity);
        double z = partialTicks == 1f ? 0 : scaffoldRenderPosition(entity, fPosZ, fLastTickZ, partialTicks) - fPosZ.getDouble(entity);
        double[] direction = combatRayDirection(entity, yaw, pitch);
        if (!Ver.is1201()) {
            // Use the running game's float direction, expansion, inside test
            // and segment interception. In particular, vanilla's near-parallel
            // component cutoff differs from a custom double-precision slab ray.
            if (mAabbGrow == null || mAabbOffset == null || mCombatBoxContains == null
                    || mCombatBoxIntercept == null || ctorVec3d == null || fScaffoldHitVec == null)
                throw new IllegalStateException("Native combat box-ray binding unavailable");
            if (x != 0 || y != 0 || z != 0) box = mAabbOffset.invoke(box, x, y, z);
            box = mAabbGrow.invoke(box, border, border, border);
            Object from = ctorVec3d.newInstance(ox, oy, oz);
            if (Boolean.TRUE.equals(mCombatBoxContains.invoke(box, from))) return 0;
            Object to = ctorVec3d.newInstance(ox+direction[0]*reach, oy+direction[1]*reach, oz+direction[2]*reach);
            Object hit = mCombatBoxIntercept.invoke(box, from, to);
            Object point = hit == null ? null : fScaffoldHitVec.get(hit);
            if (point == null) return Double.POSITIVE_INFINITY;
            double dx=fScaffoldVecX.getDouble(point)-ox, dy=fScaffoldVecY.getDouble(point)-oy,
                    dz=fScaffoldVecZ.getDouble(point)-oz;
            double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
            return Double.isFinite(distance) ? distance : Double.POSITIVE_INFINITY;
        }
        double[] origin = {ox, oy, oz};
        double[] lo = {mAabbMinX.getDouble(box)+x-border, mAabbMinY.getDouble(box)+y-border, mAabbMinZ.getDouble(box)+z-border};
        double[] hi = {mAabbMaxX.getDouble(box)+x+border, mAabbMaxY.getDouble(box)+y+border, mAabbMaxZ.getDouble(box)+z+border};
        double near = 0, far = reach;
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1e-12) {
                if (origin[axis] < lo[axis] || origin[axis] > hi[axis]) return Double.POSITIVE_INFINITY;
            } else {
                double a = (lo[axis]-origin[axis])/direction[axis], b = (hi[axis]-origin[axis])/direction[axis];
                near = Math.max(near, Math.min(a,b)); far = Math.min(far, Math.max(a,b));
                if (near > far) return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }

    private static double[] combatRayDirection(Object source, float yaw, float pitch) throws Exception {
        if (Ver.is1201()) {
            double yr=Math.toRadians(yaw), pr=Math.toRadians(pitch);
            return new double[]{-Math.sin(yr)*Math.cos(pr),-Math.sin(pr),Math.cos(yr)*Math.cos(pr)};
        }
        if (mScaffoldLookVector == null || source == null || fScaffoldVecX == null
                || fScaffoldVecY == null || fScaffoldVecZ == null)
            throw new IllegalStateException("Native combat look-vector binding unavailable");
        Object direction=mScaffoldLookVector.invoke(source,pitch,yaw);
        double[] result={fScaffoldVecX.getDouble(direction),fScaffoldVecY.getDouble(direction),fScaffoldVecZ.getDouble(direction)};
        for(double value:result)if(!Double.isFinite(value))throw new IllegalStateException("Non-finite combat look vector");
        return result;
    }

    /** Direct entity-box test used when strict first-hit ray checking is off. */
    private static boolean combatRayIntersectsTarget(Object world, Object target,
            double ox, double oy, double oz, float yaw, float pitch, float partialTicks) {
        if (world == null || target == null || fLoaded == null || mGetBB == null
                || mCombatCollisionBorder == null || !Double.isFinite(ox)
                || !Double.isFinite(oy) || !Double.isFinite(oz)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        try {
            double reach = p(MK, 0);
            java.util.List<?> entities = (java.util.List<?>) fLoaded.get(world);
            return entities != null && entities.contains(target) && reach > 0
                    && combatEntityRayDistance(target, ox, oy, oz, yaw, pitch, reach, partialTicks) <= reach;
        } catch (Throwable unavailable) {
            logOnce("combat entity ray", unavailable);
            return false;
        }
    }

    /** Same first-hit rule as vanilla picking, using the selected pose and eye. */
    private static boolean combatRayHitsTarget(Object world, Object player, Object target,
            double ox, double oy, double oz, float yaw, float pitch, float partialTicks) {
        if (world == null || target == null || mGetBB == null || mAabbMinX == null
                || mCombatCanCollide == null || mCombatCollisionBorder == null || fLoaded == null
                || !Double.isFinite(ox) || !Double.isFinite(oy) || !Double.isFinite(oz)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return false;
        try {
            double reach = p(MK, 0);
            java.util.List<?> entities = (java.util.List<?>) fLoaded.get(world);
            if (entities == null || !entities.contains(target) || !(reach > 0) || !Double.isFinite(reach)) return false;
            Object nearest = null;
            double distance = reach;
            // Through Walls remains an explicit range-extension option. It
            // skips only block occlusion, never a nearer selectable entity.
            if (p(MK, 12) <= 0 && Ver.is1201()) {
                // Modern clients do not expose the legacy World ray method.
                // Retain their existing entity visibility adapter, sampled
                // afresh without the target-hold visibility grace.
                if (mCanSee == null || !Boolean.TRUE.equals(mCanSee.invoke(player, target))) return false;
            } else if (p(MK, 12) <= 0) {
                if (ctorVec3d == null || mScaffoldRayTrace == null || fHitType == null
                        || fScaffoldHitVec == null || fScaffoldVecX == null
                        || fScaffoldVecY == null || fScaffoldVecZ == null) return false;
                double[] direction = combatRayDirection(player, yaw, pitch);
                Object from = ctorVec3d.newInstance(ox, oy, oz);
                Object to = ctorVec3d.newInstance(ox+direction[0]*reach,
                        oy+direction[1]*reach, oz+direction[2]*reach);
                Object hit = mScaffoldRayTrace.invoke(world, from, to, false, false, false);
                if (hit != null && "BLOCK".equals(String.valueOf(fHitType.get(hit)))) {
                    Object vec = fScaffoldHitVec.get(hit);
                    if (vec == null) return false;
                    double dx = fScaffoldVecX.getDouble(vec)-ox, dy = fScaffoldVecY.getDouble(vec)-oy, dz = fScaffoldVecZ.getDouble(vec)-oz;
                    distance = Math.sqrt(dx*dx+dy*dy+dz*dz);
                    if (!Double.isFinite(distance)) return false;
                }
            }
            for (Object entity : entities) {
                if (entity == null || entity == player || fIsDead.getBoolean(entity)
                        || !Boolean.TRUE.equals(mCombatCanCollide.invoke(entity))) continue;
                if (cPlayer.isInstance(entity) && mCombatSpectator != null
                        && Boolean.TRUE.equals(mCombatSpectator.invoke(entity))) continue;
                if (fRidingEntity != null && fRidingEntity.get(player) == entity) continue;
                double hit = combatEntityRayDistance(entity, ox, oy, oz, yaw, pitch, reach, partialTicks);
                if (hit < distance || (hit == 0 && nearest == null)) { nearest = entity; distance = hit; }
            }
            return nearest == target;
        } catch (Throwable unavailable) { logOnce("combat first-hit ray", unavailable); return false; }
    }

    private static void attackAfterAimTick(Object mc, Object player) throws Exception {
        if (!kaRenderCommit) return;
        Object target = kaTarget;
        if (!mods[F_KILLAURA].on || guiOpen || target == null || (fCurrentScreen != null && fCurrentScreen.get(mc) != null)) {
            combatAttemptReason="inactive-or-screen"; kaConfirmedTicks=0; return;
        }
        if (combatTargetDistanceSq(player,target)>p(MK,0)*p(MK,0)) {
            combatAttemptReason="target-out-of-range"; kaConfirmedTicks=0; return;
        }
        if (!validCombatTarget(player,target,true)) {
            combatAttemptReason="target-invalid"; kaConfirmedTicks=0; return;
        }
        long now = System.currentTimeMillis();
        float[] aim = combatAngles(player, target);
        boolean naturalAim = (int) p(MK, 9) == 1;
        boolean ownsNaturalAim = naturalAim && "KillAura".equals(silAimWho);
        // The latest outgoing orientation belongs to another feature.  Falling
        // back to the visual camera here would approve an attack even though
        // the server has just received AutoPot/Scaffold's pose.
        if (naturalAim && !ownsNaturalAim) {
            combatAttemptReason="rotation-owned-by-other-module";
            kaConfirmedTicks = 0;
            kaStatus = "Waiting for KillAura rotation owner";
            return;
        }
        boolean modelSeen = (!naturalAim || ownsNaturalAim)
                && kaRenderedTarget == target && kaRenderedAt >= kaTargetSince;
        boolean packetSeen = kaPacketReady && kaPacketTarget == target && kaPacketAt >= kaTargetSince
                && kaPacketTick == (int) scaffoldClock(player);
        long modelAge = modelSeen ? now - kaRenderedAt : Long.MAX_VALUE;
        boolean modelFresh = modelAge <= p(MK, 25);
        boolean modelLock = packetSeen;
        float poseYaw = modelLock ? kaPacketYaw : fYaw.getFloat(player);
        float posePitch = modelLock ? kaPacketPitch : fPitch.getFloat(player);
        double tolerance = p(MK, 16);
        boolean packetModelAligned = modelPacketAligned(
                poseYaw, posePitch, kaRenderedYaw, kaRenderedPitch, tolerance);
        boolean baseReady = modelSeen && packetSeen && modelFresh
                && now-kaTargetSince >= p(MK, 18);
        // The completed render already checked both rays against the target's
        // full box.  Do not add a second exact-point or angle-equality gate:
        // model interpolation while moving can differ from the packet angle,
        // yet both rays still legitimately hit the same entity.
        boolean rayReady = kaRenderRayValid;
        boolean ready = baseReady && rayReady;
        if (ready) {
            int tick = (int) scaffoldClock(player);
            if (kaConfirmationTick != tick) { kaConfirmedTicks++; kaConfirmationTick = tick; }
            kaLastReadyAt = now;
        } else if (kaConfirmedTicks > 0 && now - kaLastReadyAt <= 90L) {
            // A render/tick phase miss must not create a visible one-swing gap.
            // The grace is short and only starts after a real confirmation.
            // Extra render frames cannot consume or create confirmation ticks.
        } else {
            kaConfirmedTicks = 0;
        }
        kaStatus = ready ? "Aim confirmed " + kaConfirmedTicks
                : (kaConfirmedTicks > 0 ? "Aim grace " + kaConfirmedTicks : "Waiting for model + look");
        if (now - kaGateLogAt >= 500L) {
            kaGateLogAt = now;
            log("KillAura gate: model=" + modelSeen + " packet=" + packetSeen
                    + " fresh=" + modelFresh + " aligned=" + packetModelAligned
                    + " ray=" + rayReady + " ready=" + ready
                    + " confirm=" + kaConfirmedTicks + "/" + (int)p(MK, 17)
                    + " attacks=" + kaAttackCount
                    + " packetPose=(" + poseYaw + "," + posePitch + ")"
                    + " modelPose=(" + kaRenderedYaw + "," + kaRenderedPitch + ")"
                    + " targetPose=(" + (aim[0] + kaBiasYaw) + "," + (aim[1] + kaBiasPitch) + ")");
        }
        // Confirmation history may survive a single render/tick phase miss, but
        // the current tick still has to be aimed and ray-valid before attacking.
        // Otherwise Confirm Ticks=1 turns the 90 ms history grace into a real
        // attack-after-losing-aim window.
        combatAttemptReason=!baseReady?"aim-state-unconfirmed":!rayReady?combatAttemptReason:"confirmation-wait";
        if (!ready || kaConfirmedTicks < p(MK, 17)) return;
        combatAttemptReason="tick-already-attacked";
        if (kaAttackTick == (int) scaffoldClock(player)) return;
        if (combatAttackAlreadySent(player)) return;
        long attackPeriod = CombatCadence.period(p(MK,2));
        if (kaNextAttackAt <= 0L) kaNextAttackAt = now;
        if (now < kaNextAttackAt) {combatAttemptReason="rate-wait";return;}
        if (p(MK,13)>0 && !fOnGround.getBoolean(player)) {combatAttemptReason="ground-required";return;}
        if (p(MK,22)>0 && !isWeapon(heldItem(player))) {combatAttemptReason="weapon-required";return;}
        boolean eating = mHandActive != null && Boolean.TRUE.equals(mHandActive.invoke(player));
        boolean ownedBlock = combatBlock.owns(player);
        if (p(MK,23)>0 && eating && !ownedBlock) {combatAttemptReason="using-item";return;}
        float cooled = mCooled == null ? 1.0f : ((Number) mCooled.invoke(player, .5f)).floatValue();
        if (cooled < p(MK,21)) {combatAttemptReason="cooldown-wait";return;}
        if (Math.random()*100 > p(MK,10)) {combatAttemptReason="chance-skip";return;}
        // Criticals is opportunistic: it may prepare a real jump, but an
        // ascending/ground frame must not starve KillAura's normal attack
        // cadence. When the player reaches the falling phase the same call is
        // naturally a critical; otherwise this tick remains a normal hit.
        if (mods[F_CRITICALS].on) critReady(player);
        Object pc = fPlayerController == null ? null : fPlayerController.get(mc);
        if(pc == null || mAttackEntity == null) { kaStatus="Missing attack controller"; return; }
        // Release only KA-owned use after every attack gate has passed. Never
        // postpone an eligible attack to complete the configured block hold.
        if (ownedBlock) {
            combatBlock.release();
            eating = mHandActive != null && Boolean.TRUE.equals(mHandActive.invoke(player));
        }
        // Pause Using Item controls target tracking. Native item use still
        // excludes an attack, including a release that has not completed.
        if(eating){combatAttemptReason="using-item";kaStatus="Using item / waiting for release";return;}
        double speedBeforeAttack=combatHorizontalSpeed(player);
        float attackSavedYaw = fYaw.getFloat(player), attackSavedPitch = fPitch.getFloat(player);
        boolean shouldSwing = p(MK,11)>0 && !(p(MK,14)>0 && eating);
        // The controller also computes vanilla local knockback from yaw. Give
        // that native action the confirmed look, then restore the exact render
        // fields even if the controller throws; its own movement effects stay.
        kaAttackTick = (int) scaffoldClock(player);
        try {
            fYaw.setFloat(player, poseYaw); fPitch.setFloat(player, posePitch);
            if (!clickCombatTarget(mc, player, pc, target, shouldSwing)) return;
        } finally {
            fYaw.setFloat(player, attackSavedYaw); fPitch.setFloat(player, attackSavedPitch);
        }
        kaAttackCount++;
        recordCombatAttack(player);
        combatAttemptReason="dispatched";
        double speedAfterAttack=combatHorizontalSpeed(player);
        try { beginCombatBlock(player); } catch (Throwable failure) { logOnce("AutoBlock use",failure); }
        targetVisualAttackTarget=target;targetVisualAttackAt=System.nanoTime();
        if (now - kaDispatchLogAt >= 500L) {
            kaDispatchLogAt = now;
            try {
            log("KillAura dispatch: phase=client-input tick=" + kaAttackTick
                    + " frameTick=" + kaQueuedPacketTick + " target="
                    + (mGetEntityId == null ? "unknown" : mGetEntityId.invoke(target))
                    + " pose=" + poseYaw + "," + posePitch
                    + " modelPose=" + kaRenderedYaw + "," + kaRenderedPitch
                    + " range=" + p(MK,0) + " rayCheck=" + (p(MK,24)>0)
                    + " manualReachEnabled="+reachEnabled()+" rangeSource=KillAura"
                    + " autoBlock="+(p(MK,33)>0)+" blockMode="+(p(MK,35)>0?"real":"visual")
                    + " ownedUse="+combatBlock.owns(player)
                    + " speedBefore="+speedBeforeAttack+" speedAfter="+speedAfterAttack
                    + " hitDistance=" + combatEntityRayDistance(target,
                            kaPacketEyeX, kaPacketEyeY, kaPacketEyeZ, poseYaw, posePitch, p(MK,0), 1f)
                    + " frameAgeMs=" + modelAge
                    + " throughWalls=" + (p(MK,12)>0) + " cooldown=" + cooled
                    + " targetHealth=" + (mGetHealth == null ? "unknown" : mGetHealth.invoke(target))
                    + " hurtTicks=" + (fHurtTime == null ? "unknown" : fHurtTime.getInt(target))
                    + " requests=" + kaAttackCount + " serverDamage=unconfirmed");
            } catch (Throwable observation) { logOnce("combat dispatch observation", observation); }
        }
        kaNextAttackAt=CombatCadence.next(kaNextAttackAt,now,attackPeriod);
        kaStatus="Attack / aim confirmed";
    }


    // =====================================================================
    // v4 扩展功能
    // 设计原则: 只做客户端能真正生效的事; 需要服务端配合或额外渲染 hook 的
    // 在功能表里就标了 SERVER / HOOK, 不在下面假装实现。
    // =====================================================================

    /** 准星正对的实体, 没有返回 null */
    private static Object crosshairEntity(Object mc) {
        try {
            if (fObjectMouseOver == null || fEntityHit == null) return null;
            Object hit = fObjectMouseOver.get(mc);
            return hit == null ? null : fEntityHit.get(hit);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 附近最近的活体目标 (排除自己), 没有返回 null。
     *
     * AntiBot 开着时这里会把判出来的假玩家剔掉 —— 放在这一个函数里就够了, 因为
     * KillAura / AutoBlock / AutoPot 全都从这里取目标。在各自调用点分别加一遍
     * 才是真正容易漏的写法。
     */
    private static Object nearestTarget(Object player, double range, int targetMode) {
        try {
            Object world = worldOf(player);
            Object best = null;
            double bestD = range;
            double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
            for (Object e : entities(world)) {
                if (e == null || e == player) continue;
                if (!cLiving.isInstance(e)) continue;
                if (mIsAlive != null && !Boolean.TRUE.equals(mIsAlive.invoke(e))) continue;
                if (!matchTarget(targetMode, e)) continue;
                if (isBot(player, e)) continue;
                double d = Math.sqrt(Math.pow(fPosX.getDouble(e) - px, 2)
                        + Math.pow(fPosY.getDouble(e) - py, 2)
                        + Math.pow(fPosZ.getDouble(e) - pz, 2));
                if (d < bestD) { bestD = d; best = e; }
            }
            return best;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 物品类名判断 (避免依赖具体物品类, 换个版本也不会编译不过) */
    private static boolean itemLike(Object stack, String keyword) {
        try {
            if (stack == null || mGetItem == null) return false;
            Object item = mGetItem.invoke(stack);
            return item != null && item.getClass().getSimpleName().toLowerCase().contains(keyword);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 手持物品 */
    private static Object heldItem(Object player) {
        try {
            return mGetHeldItem == null ? null : mGetHeldItem.invoke(player);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int hotbarIndex(Object player) {
        try {
            Object inv = fInvPlayer.get(player);
            return (Integer) fCurrentItem.get(inv);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void setHotbar(Object player, int idx) throws Exception {
        Object inv = fInvPlayer.get(player);
        fCurrentItem.set(inv, idx);
    }

    // ===== 战斗 =====

    /** 触发攻击: 只打准星正对的实体, 比自动攻击保守得多 (不转视角, 行为上更像人) */
    private static long lastTrigger = 0;
    private static void doTriggerBot(Object mc, Object player) throws Exception {
        Object ent = crosshairEntity(mc);
        if (ent == null) return;
        // 目标类型 (下标 5): 准星压到**任何**生物就出手, 走过一头牛也会挥剑。
        // 先赋给 int 再传参 —— 探针按 "int x = (int) p(F_X, N)" 这种写法找调用点。
        int tgt = (int) p(F_TRIGGERBOT, 5);
        if (!matchTarget(tgt, ent)) return;
        double d = Math.sqrt((Double) mDistSq.invoke(player, ent));
        if (d > p(F_TRIGGERBOT, 0)) return;
        if (p(F_TRIGGERBOT, 2) > 0 && !fOnGround.getBoolean(player)) return;   // Crit Only
        if (p(F_TRIGGERBOT, 3) > 0 && !isWeapon(heldItem(player))) return;     // Weapon Only
        // Chance: 准星一压上就 100% 出手是最硬的机器特征, 真人一定有反应抖动
        if (p(F_TRIGGERBOT, 4) < 100 && Math.random() * 100 >= p(F_TRIGGERBOT, 4)) return;
        long now = System.currentTimeMillis();
        if (now - lastTrigger < (long) (p(F_TRIGGERBOT, 1) * 50)) return;
        lastTrigger = now;
        swingHand(player);
        mAttack.invoke(player, ent);
    }

    /** 连击格挡: 目标在附近时周期性按住右键格挡 */
    private static long lastBlockHit = 0, blockHoldUntil = 0;
    private static void doBlockHit(Object mc, Object player) throws Exception {
        if (mods[MK].on && p(MK,33)>0) return;
        if (mRightClickMouse == null) return;
        if (nearestTarget(player,4.0,(int)p(F_BLOCKHIT,3))==null) {blockHoldUntil=0;return;}
        long now = System.currentTimeMillis();
        if (now < blockHoldUntil) {
            mRightClickMouse.invoke(mc);
            return;
        }
        int mode = (int) p(F_BLOCKHIT, 2);     // 0=Always 1=On Hit 2=On Kill
        int tgt = (int) p(F_BLOCKHIT, 3);      // 目标类型
        if (mode == 0) {
            if (nearestTarget(player, 4.0, tgt) == null) return;
        } else {
            // On Hit / On Kill: 只在自己刚打出去的时候才格挡。
            // 1.12.2 没有"击杀事件"能直接读到, On Kill 退化成"目标血量已经很低" —— 比 Always 准得多。
            boolean swinging = (Boolean) mIsButtonDown.invoke(null, 0);
            if (!swinging) return;
            Object t = nearestTarget(player, 4.0, tgt);
            if (t == null) return;
            if (mode == 2 && (Float) mGetHealth.invoke(t) > 6.0f) return;
        }
        if (now - lastBlockHit < 200) return;
        if (Math.random() * 100 > p(F_BLOCKHIT, 0)) return;
        lastBlockHit = now;
        blockHoldUntil = now + (long) (p(F_BLOCKHIT, 1) * 50);
    }

    /** 自动格挡: 附近有敌人时按住右键 (盾/剑格挡) */
    private static long lastAutoBlock = 0;
    private static void doAutoBlock(Object player) throws Exception {
        if (mods[MK].on && p(MK,33)>0) return;
        double abRange = p(F_AUTOBLOCK, 0);
        int tgt = (int) p(F_AUTOBLOCK, 4);     // 目标类型
        if (nearestTarget(player, abRange, tgt) == null) return;
        // Only When Hitting: 平时一直举盾是"永远在格挡"的机器特征
        if (p(F_AUTOBLOCK, 3) > 0 && !(Boolean) mIsButtonDown.invoke(null, 0)) return;

        long now = System.currentTimeMillis();
        if (now - lastAutoBlock < (long) (p(F_AUTOBLOCK, 2) * 50)) return;   // Delay (ticks)
        lastAutoBlock = now;

        int mode = (int) p(F_AUTOBLOCK, 1);    // 0=Right Click 1=Packet 2=Sword
        if (mode == 2 && !isWeapon(heldItem(player))) return;                 // Sword: 只在手持剑时
        if (mode == 0) {
            if (mRightClickMouse != null) mRightClickMouse.invoke(mGetMC.invoke(null));
        } else if (ctorTryUseItem != null && fConnection != null && mSendPacket != null) {
            // Packet / Sword: 只发包, 不碰客户端自己的使用状态 —— 没有减速也没有挥手动画
            Object conn = fConnection.get(player);
            if (conn != null) mSendPacket.invoke(conn, ctorTryUseItem.newInstance(enumMainHand));
        }
    }

    /** 命中箱: 放大附近实体的碰撞箱, 让攻击更容易命中 */
    private static final java.util.Map<Object,Object[]> hitBoxOwned=new java.util.WeakHashMap<Object,Object[]>();
    private static void restoreHitBoxes() throws Exception {
        if(mGetBB!=null && mSetEntityBB!=null)for(java.util.Map.Entry<Object,Object[]> e:hitBoxOwned.entrySet())
            if(mGetBB.invoke(e.getKey())==e.getValue()[1])mSetEntityBB.invoke(e.getKey(),e.getValue()[0]);
        hitBoxOwned.clear();
    }
    private static void doHitBox(Object player) throws Exception {
        if (mSetEntityBB == null || mGetBB == null || mAabbGrow == null) return;
        // Restore only boxes still owned by this module; a native position
        // update replaces the box and must never be overwritten with old data.
        restoreHitBoxes();
        double mult = p(F_HITBOX, 0);
        if (mult <= 1.0) return;
        double grow = (mult - 1.0) * 0.3;
        double growY = p(F_HITBOX, 2) > 0 ? grow : 0.0;      // Expand Y
        boolean playersOnly = p(F_HITBOX, 1) > 0;
        Object world = worldOf(player);
        for (Object e : entities(world)) {
            if (e == null || e == player) continue;
            if (!cLiving.isInstance(e)) continue;
            if (playersOnly && !cPlayer.isInstance(e)) continue;
            if (EntityExclusions.contains(e)) continue;
            Object bb = mGetBB.invoke(e);
            if (bb == null) continue;
            Object expanded=mAabbGrow.invoke(bb, grow, growY, grow);
            mSetEntityBB.invoke(e,expanded);hitBoxOwned.put(e,new Object[]{bb,expanded});
        }
    }

    /** 自动盔甲: 周期性把背包里的盔甲 shift 点到装备栏 */
    private static long lastArmor = 0;
    private static Object managedPlayerContainer(Object player) throws Exception {
        if(fOpenContainer==null || mGetSlot==null || mGetSlotStack==null || fInvSlots==null || openWindowId(player)!=0)return null;
        Object cont=fOpenContainer.get(player);
        Object slots=cont==null?null:fInvSlots.get(cont);
        return slots instanceof java.util.List && ((java.util.List<?>)slots).size()>=45?cont:null;
    }
    private static boolean inventoryCursorEmpty(Object player) throws Exception {
        if(fInvPlayer==null)return false;
        Object inv=fInvPlayer.get(player);if(inv==null)return false;
        Method cursor=mopt(inv.getClass(),"func_70445_o");
        return cursor!=null && emptyStack(cursor.invoke(inv));
    }
    private static int enchantmentLevel(Object stack,int id) throws Exception {
        if(emptyStack(stack))return 0;
        Method ench=mopt(stack.getClass(),"func_77986_q");
        if(ench==null)throw new IllegalStateException("Enchantment list unavailable");
        Object list=ench.invoke(stack);if(list==null)return 0;
        Method count=mopt(list.getClass(),"func_74745_c"), entry=mopt(list.getClass(),"func_150305_b",int.class);
        if(count==null || entry==null)throw new IllegalStateException("Enchantment list reader unavailable");
        for(int i=0;i<((Number)count.invoke(list)).intValue();i++){
            Object tag=entry.invoke(list,i);Method number=mopt(tag.getClass(),"func_74765_d",String.class);
            if(number==null)throw new IllegalStateException("Enchantment ID reader unavailable");
            if(((Number)number.invoke(tag,"id")).intValue()==id)return ((Number)number.invoke(tag,"lvl")).intValue();
        }
        return 0;
    }
    private static double toolAttackScore(Object item,Object stack) throws Exception {
        Method sword=mopt(item.getClass(),"func_150931_i");
        Field tool=fopt(item.getClass(),"field_77865_bY");
        double base=sword!=null?((Number)sword.invoke(item)).doubleValue():tool!=null?((Number)tool.get(item)).doubleValue():1.0;
        int sharpness=enchantmentLevel(stack,16);
        return base+(sharpness==0?0:Ver.is189()?1.25*sharpness:.5*sharpness+.5);
    }
    private static void doAutoArmor(Object player) throws Exception {
        if(mWindowClick==null || !inventoryCursorEmpty(player))return;
        Object cont=managedPlayerContainer(player);if(cont==null)return;
        long now=System.currentTimeMillis();
        if(now-lastArmor<(long)(p(F_AUTOARMOR,0)*150))return;
        int mode=(int)p(F_AUTOARMOR,2);
        boolean emptyOnly=p(F_AUTOARMOR,1)>0 || mode==1;
        boolean enchanted=p(F_AUTOARMOR,3)>0;
        Object pc=fPlayerController.get(mGetMC.invoke(null));if(pc==null)return;
        for(int type=0;type<4;type++){
            Object equipped=containerStack(cont,5+type);
            if(emptyOnly && !emptyStack(equipped))continue;
            if(!emptyStack(equipped) && enchantmentLevel(equipped,10)>0)continue;
            double bestScore=armorScore(equipped,enchanted);int best=-1;
            for(int slot=9;slot<=44;slot++){
                Object stack=containerStack(cont,slot);
                if(emptyStack(stack) || armorType(stackDescriptor(stack))!=type)continue;
                if(mode==2 && enchantmentLevel(stack,10)>0)continue;
                double score=armorScore(stack,enchanted);
                if(score>bestScore+.01){bestScore=score;best=slot;}
            }
            if(best<0)continue;
            quickMove(pc,emptyStack(equipped)?best:5+type,player);
            lastArmor=now;return;
        }
    }
    private static double armorScore(Object stack,boolean enchanted) throws Exception {
        if(emptyStack(stack))return -1;
        double score=materialScore(stackDescriptor(stack))*100;
        Object item=mGetItem==null?null:mGetItem.invoke(stack);
        Field defense=item==null?null:fopt(item.getClass(),"field_77879_b");
        if(defense!=null)score=((Number)defense.get(item)).doubleValue()*100;
        if(enchanted)score+=enchantmentLevel(stack,0)*30;
        return score;
    }

    /** 自动图腾: 血量低于阈值时把图腾 shift 点到副手 */
    private static long lastTotem = 0;
    private static void doAutoTotem(Object player) throws Exception {
        if(!Ver.hasOffhand() || mWindowClick==null || !inventoryCursorEmpty(player))return;
        Object cont=managedPlayerContainer(player);if(cont==null || ((java.util.List<?>)fInvSlots.get(cont)).size()<46)return;
        Object offhand=containerStack(cont,45);
        if(itemLike(offhand,"totem"))return;
        int mode=(int)p(F_AUTOTOTEM,1);
        float hp=((Number)mGetHealth.invoke(player)).floatValue();
        if(mode!=2 && hp>p(F_AUTOTOTEM,0))return;
        if(mode==1 && (fHurtTime==null || fHurtTime.getInt(player)<=0))return;
        if(mode==2 && !emptyStack(offhand))return;
        long now=System.currentTimeMillis();if(now-lastTotem<(long)(p(F_AUTOTOTEM,2)*50))return;
        Object pickup=null;if(cClickType!=null)for(Object v:cClickType.getEnumConstants())if("PICKUP".equals(v.toString()))pickup=v;
        if(pickup==null)return;
        for(int slot=9;slot<=44;slot++)if(itemLike(containerStack(cont,slot),"totem")){
            Object pc=fPlayerController.get(mGetMC.invoke(null));if(pc==null)return;
            // PICKUP is supported by 1.12 containers. Shift-click only moves
            // between inventory and hotbar; it never equips the offhand.
            mWindowClick.invoke(pc,0,slot,0,pickup,player);
            mWindowClick.invoke(pc,0,45,0,pickup,player);
            if(!emptyStack(offhand))mWindowClick.invoke(pc,0,slot,0,pickup,player);
            lastTotem=now;return;
        }
    }

    /** 自动药水: 血量低时切到药水并喝 */
    private static long lastPot = 0;
    private static boolean potionMatchesMode(Object stack, int mode) throws Exception {
        if (emptyStack(stack) || mGetItem == null || mPotionEffects == null || mPotionEffectValue == null || mPotionNumericId == null) return false;
        Object item=mGetItem.invoke(stack);
        if (item==null || (Ver.is189() && !mPotionEffects.getDeclaringClass().isInstance(item))) return false;
        Object effects=mPotionEffects.invoke(Ver.is189()?item:null, stack);
        if (!(effects instanceof java.util.List<?>)) return false;
        // Native effect IDs are stable across these two versions. Read the
        // actual effect list, including custom NBT; names and glow are not types.
        int wanted=mode==0?6:mode==1?1:mode==2?5:12;
        for (Object effect:(java.util.List<?>)effects) {
            if(effect==null)continue;
            Object value=mPotionEffectValue.invoke(effect);
            int id=Ver.is189()?((Number)value).intValue():((Number)mPotionNumericId.invoke(null,value)).intValue();
            if(id==wanted || (mode==0 && id==10))return true;
        }
        return false;
    }
    private static void doAutoPot(Object player) throws Exception {
        int mode = (int) p(F_AUTOPOT, 2);         // 0=Health 1=Speed 2=Strength 3=Fire Res
        float hp = (Float) mGetHealth.invoke(player);
        // Health 档看血量; 其余档看"身上还没有这个效果" —— 那才是它们真正的触发条件
        boolean need;
        if (mode == 0) {
            need = hp <= p(F_AUTOPOT, 0);
        } else {
            String potName = mode == 1 ? "speed" : (mode == 2 ? "strength" : "fire_resistance");
            need = !hasPotion(player, potName);
        }
        if (!need) return;
        // Only When Safe: 身边有敌人时不喝, 免得喝到一半被打断
        int apTgt = (int) p(F_AUTOPOT, 5);     // "身边有敌人"按什么算敌人
        if (p(F_AUTOPOT, 4) > 0 && nearestTarget(player, 6.0, apTgt) != null) return;

        long now = System.currentTimeMillis();
        if (now - lastPot < (long) (p(F_AUTOPOT, 1) * 100)) return;
        Object inv = fInvPlayer.get(player);
        java.util.List<?> arr = mainInv(player);
        if (arr == null) return;
        for (int i = 0; i < 9; i++) {
            if (!itemLike(invAt(arr, i), "potion") || !potionMatchesMode(invAt(arr, i), mode)) continue;
            int cur = hotbarIndex(player);
            if (cur != i) setHotbar(player, i);
            lastPot = now;
            Object mc = mGetMC.invoke(null);
            // Throw At Feet: 喷溅药水是按落点生效的, 不低头就扔到远处了。
            // 这里只改服务端看到的瞄准角 (静默转身), 屏幕视角不动 —— 和 KillAura 的 Silent 一个道理。
            //
            // 优先级 40, 比 KillAura 还高: 这是一个**一次性**动作, 药水扔出去就没了。
            // 被 KillAura 顶成"看着敌人"的话药水会落到很远的地方, 白扔一瓶。
            // 只在真正要扔的这一刻抢名额, 平时不占。
            if (p(F_AUTOPOT, 3) > 0 && claimSilentAim(40, "AutoPot")) {
                requestSilent(fYaw.getFloat(player), 90.0f);
            }
            if (mRightClickMouse != null) mRightClickMouse.invoke(mc);
            return;
        }
    }

    /** 身上有没有某个药水效果 (按注册名查)。取不到注册表时返回 true, 免得一直重复喝。 */
    private static boolean hasPotion(Object player, String name) {
        if (mGetPotionFromRL == null || mIsPotionActive == null) return true;
        try {
            Object potion = mGetPotionFromRL.invoke(null, name);
            if (potion == null) return true;
            return (Boolean) mIsPotionActive.invoke(player, potion);
        } catch (Throwable t) {
            return true;
        }
    }

    // ===== 移动 =====

    /** 长跳: 起跳后给水平推力, 空中可以补几次 (Air Jumps) */
    private static boolean longJumpUsed = false;
    private static int longJumpAir = 0;
    private static void doLongJump(Object player) throws Exception {
        boolean onGround = fOnGround.getBoolean(player);
        if (onGround) { longJumpUsed = false; longJumpAir = 0; return; }
        // Only While Sprinting: 长跳本来是疾跑起跳的产物, 站着也推就太明显了
        if (p(F_LONGJUMP, 3) > 0 && !isSprinting(player)) return;

        int maxAir = (int) p(F_LONGJUMP, 1);
        if (longJumpAir >= maxAir) return;
        longJumpUsed = true;
        longJumpAir++;

        int mode = (int) p(F_LONGJUMP, 2);        // 0=Normal 1=Boost 2=Glide
        double boost = p(F_LONGJUMP, 0);
        double yaw = Math.toRadians(fYaw.getFloat(player));
        double k = mode == 1 ? 0.28 : (mode == 2 ? 0.12 : 0.20);
        fMotionX.setDouble(player, fMotionX.getDouble(player) - Math.sin(yaw) * boost * k);
        fMotionZ.setDouble(player, fMotionZ.getDouble(player) + Math.cos(yaw) * boost * k);
        // Glide: 把下坠速度夹住 —— 推力和缓降一起才叫滑翔, 只推不缓降是"空中加速"
        if (mode == 2 && fMotionY.getDouble(player) < -0.05) fMotionY.setDouble(player, -0.05);
    }

    /** 高跳: 只在你按跳跃键的那一下生效, 否则会变成原地无限弹跳 */
    private static void doHighJump(Object player) throws Exception {
        if (!fOnGround.getBoolean(player)) return;
        if (fMotionY.getDouble(player) > 0.1) return;
        if (mKeyDown == null || !(Boolean) mKeyDown.invoke(null, 57)) return;     // 空格
        double mult = p(F_HIGHJUMP, 0);                                           // Multiplier
        // Vanilla 直接乘跳跃初速; NCP 只加零头, 单跳幅度小到不容易顶到阈值
        double v = (int) p(F_HIGHJUMP, 1) == 0 ? 0.42 * mult : 0.42 + (mult - 1.0) * 0.12;
        fMotionY.setDouble(player, v);
    }

    /** 爬墙: 贴着方块且朝墙前进时获得向上速度 */
    private static void doSpider(Object mc, Object player) throws Exception {
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);
        if (Math.abs(mx) < 0.01 && Math.abs(mz) < 0.01) return;
        Object world = worldOf(player);
        if (world == null) return;

        // Only Facing Wall: 必须真的朝那面墙看。
        // 不判的话贴墙侧移甚至背对着墙也能爬上去 —— 服务端看到的是"面朝空气往上飞", 一眼假。
        if (p(F_SPIDER, 2) > 0) {
            double yaw = Math.toRadians(fYaw.getFloat(player));
            double lookX = -Math.sin(yaw), lookZ = Math.cos(yaw);
            double len = Math.sqrt(mx * mx + mz * mz);
            double dot = (lookX * mx + lookZ * mz) / len;
            if (dot < 0.5) return;   // 视线偏离前进方向超过 60 度就不爬
        }

        // 前方一格是否有实体方块
        double yaw = Math.toRadians(fYaw.getFloat(player));
        double nx = fPosX.getDouble(player) - Math.sin(yaw) * 0.6;
        double nz = fPosZ.getDouble(player) + Math.cos(yaw) * 0.6;
        Object bp = ctorBlockPos.newInstance(Math.floor(nx), Math.floor(fPosY.getDouble(player)), Math.floor(nz));
        if (Boolean.TRUE.equals(mIsAirBlock.invoke(world, bp))) return;
        if (p(F_SPIDER, 1) > 0) {
            fMotionY.setDouble(player, p(F_SPIDER, 0));   // 匀速上爬
        } else {
            fMotionY.setDouble(player, Math.max(fMotionY.getDouble(player), p(F_SPIDER, 0)));
        }
    }

    /** 快速爬梯: 只在真的贴梯子时改垂直速度, 否则会连带把跳跃/下落也加速 */
    private static void doFastLadder(Object player) throws Exception {
        if (mIsOnLadder == null) return;
        if (!Boolean.TRUE.equals(mIsOnLadder.invoke(player))) return;
        int mode = (int) p(F_FASTLADDER, 2);      // 0=Both 1=Up 2=Down
        double my = fMotionY.getDouble(player);
        if (mode == 1) {                          // Up: 只管上爬
            if (my > 0) fMotionY.setDouble(player, p(F_FASTLADDER, 0));
        } else if (mode == 2) {                   // Down: 只管下滑
            if (my < 0) fMotionY.setDouble(player, -p(F_FASTLADDER, 1));
        } else {                                  // Both
            if (my > 0) fMotionY.setDouble(player, p(F_FASTLADDER, 0));
            else if (my < 0) fMotionY.setDouble(player, -p(F_FASTLADDER, 1));
        }
    }

    /** 游泳加速 */
    private static void doSwim(Object player) throws Exception {
        if (mIsInWater == null) return;
        boolean inWater = Boolean.TRUE.equals(mIsInWater.invoke(player));
        if (p(F_SWIM, 2) > 0 && !inWater) return;                                 // Only In Water
        double mult = p(F_SWIM, 0);                                               // Multiplier
        fMotionX.setDouble(player, fMotionX.getDouble(player) * mult);
        fMotionZ.setDouble(player, fMotionZ.getDouble(player) * mult);
        if ((int) p(F_SWIM, 1) == 1) {                                            // Dolphin: 额外浮力, 贴水面飞
            if (fMotionY.getDouble(player) < 0.02) fMotionY.setDouble(player, 0.02);
        }
    }

    /**
     * 背包移动: 开着界面时也响应 WASD。
     *
     * **两条路都试过了, 这是结论 —— 记在这儿免得再走一遍。**
     *
     * 【路线一 · 手写 motion】每帧直接写 motionX/motionZ。
     *   缺点: 没有加速、没有摩擦、斜走更快、绕开疾跑。用户报"和正常游戏完全不同"。
     *
     * 【路线二 · 写原版输入量】写 moveForward/moveStrafing, 想让原版 moveRelative 去算。
     *   **这条路走不通 —— 用户报"直接动不了了"。**
     *   原因: EntityPlayerSP.onLivingUpdate() 每个 tick 都会先调
     *       this.movementInput.updatePlayerMoveState()
     *   那个方法把 moveForward/moveStrafing **无条件重新赋值**, 来源是 KeyBinding 的状态。
     *   而开着界面时 KeyBinding 全是 false —— 所以我们写进去的值在同一 tick 内就被冲掉了,
     *   根本轮不到 travel/moveRelative 去读它。从 onFrame (60/s) 写更是必然被冲。
     *
     * 【现在这条 · 混合】回到写 motion, 但**不覆写, 只做"输入对应的速度目标 + 保留摩擦"**:
     *   - 每 tick 先把水平速度乘上原版的 0.91 摩擦 (和原版 travel 的第一步一致)
     *   - 再加上输入对应的加速度, 上限是原版步行速度
     *   这样加速/摩擦/归一化都在, 只是把"驱动源"从原版输入换成我们的按键读取。
     *   疾跑照旧由 Sprint 模块负责 (它自己会改 motion), 这里不抢。
     *
     * 参数: 0=Sneak 1=Jump 2=GuiOnly 3=NoSlow
     */
    private static final int[] invMoveOwnedCodes = new int[7];
    private static int invMoveOwnedCount = 0;
    private static boolean invMoveActive;
    private static Class<?> inputTextFieldType;
    private static Method inputTextFocused;
    private static Class<?>[] textEntryScreens;
    private static final java.util.Map<Class<?>,Field[]> screenTextFields=new java.util.HashMap<Class<?>,Field[]>();

    private static boolean screenHasTextInput(Object screen) throws Exception {
        if(ownTextInputActive())return true;
        if(screen==null)return false;
        if(inputTextFieldType==null){
            inputTextFieldType=copt("net.minecraft.client.gui.GuiTextField");
            inputTextFocused=mopt(inputTextFieldType,"func_146206_l");
        }
        if(textEntryScreens==null)textEntryScreens=new Class<?>[]{
                copt("net.minecraft.client.gui.GuiChat"),copt("net.minecraft.client.gui.inventory.GuiEditSign"),
                copt("net.minecraft.client.gui.GuiScreenBook"),copt("net.minecraft.client.gui.GuiCommandBlock")};
        for(Class<?> type:textEntryScreens)if(type!=null && type.isInstance(screen))return true;
        // Do not pass typing through if the text-field binding is unavailable.
        if(inputTextFieldType==null || inputTextFocused==null)return true;
        Field[] fields=screenTextFields.get(screen.getClass());
        if(fields==null){
            java.util.List<Field> found=new java.util.ArrayList<Field>();
            for(Class<?> type=screen.getClass();type!=null;type=type.getSuperclass())
                for(Field f:type.getDeclaredFields())if(inputTextFieldType.isAssignableFrom(f.getType())){
                    f.setAccessible(true);found.add(f);
                }
            fields=found.toArray(new Field[found.size()]);screenTextFields.put(screen.getClass(),fields);
        }
        for(Field f:fields){Object box=f.get(screen);if(box!=null && Boolean.TRUE.equals(inputTextFocused.invoke(box)))return true;}
        return false;
    }

    private static int bindingCode(Object settings, Field field) throws Exception {
        Object binding = settings == null || field == null ? null : field.get(settings);
        return binding == null || mBindingCode == null ? 0 : ((Number) mBindingCode.invoke(binding)).intValue();
    }

    /** Select same-speed vanilla keys nearest to the camera-relative intention. */
    private static void remapScaffoldMovementBindings(Object mc, float cameraYaw, float poseYaw) throws Exception {
        restoreScaffoldMovementBindings();
        if (mc == null || fGameSettings == null || mBindingState == null || mBindingCode == null) return;
        Object settings = fGameSettings.get(mc);
        scaffoldMoveCodes[0] = bindingCode(settings, fForwardKey);
        scaffoldMoveCodes[1] = bindingCode(settings, fBackKey);
        scaffoldMoveCodes[2] = bindingCode(settings, fLeftKey);
        scaffoldMoveCodes[3] = bindingCode(settings, fRightKey);
        float forward = (physicalKeyDown(scaffoldMoveCodes[0]) ? 1.0f : 0.0f)
                - (physicalKeyDown(scaffoldMoveCodes[1]) ? 1.0f : 0.0f);
        float strafe = (physicalKeyDown(scaffoldMoveCodes[2]) ? 1.0f : 0.0f)
                - (physicalKeyDown(scaffoldMoveCodes[3]) ? 1.0f : 0.0f);
        if (Math.abs(forward) + Math.abs(strafe) < 0.01f) return;

        float[] local = scaffoldPoseRelativeInput(forward, strafe, cameraYaw, poseYaw);
        double localForward = local[0], localStrafe = local[1];
        double threshold = 0.30;
        mBindingState.invoke(null, scaffoldMoveCodes[0], localForward > threshold);
        mBindingState.invoke(null, scaffoldMoveCodes[1], localForward < -threshold);
        mBindingState.invoke(null, scaffoldMoveCodes[2], localStrafe > threshold);
        mBindingState.invoke(null, scaffoldMoveCodes[3], localStrafe < -threshold);
        scaffoldMoveBindingsRemapped = true;
    }

    static float[] scaffoldPoseRelativeInput(float forward, float strafe,
                                             float cameraYaw, float poseYaw) {
        float scale = Math.max(Math.abs(forward), Math.abs(strafe));
        if (!Float.isFinite(scale) || !Float.isFinite(cameraYaw)
                || !Float.isFinite(poseYaw) || scale < 1.0e-6f) return new float[]{0f, 0f};
        // MovementInput has already applied manual sneak (0.3); item-use
        // slowdown, the 0.98 multiplier and diagonal normalisation still run
        // later in vanilla. Never substitute an arbitrary analogue vector.
        double angle = Math.atan2(strafe, forward)
                + Math.toRadians(shortestDelta(cameraYaw, poseYaw));
        // Single-axis -> diagonal adds acceleration: vanilla clamps vectors
        // only above unit length, after the 0.98 and sneak/use multipliers.
        // Keep the physical key count: cardinal inputs choose cardinal outputs,
        // diagonal inputs choose diagonal outputs. The closest allowed heading
        // is within 45 degrees and keeps the native acceleration speed tier.
        int diagonal = forward != 0f && strafe != 0f ? 1 : 0;
        double offset = diagonal * (Math.PI / 4.0);
        int sector = (2 * (int) Math.floor((angle - offset) / (Math.PI / 2.0) + 0.5)
                + diagonal) & 7;
        int[] forwards = {1, 1, 0, -1, -1, -1, 0, 1};
        int[] strafes = {0, 1, 1, 1, 0, -1, -1, -1};
        return new float[]{forwards[sector] * scale, strafes[sector] * scale};
    }

    private static void restoreScaffoldMovementBindings() {
        if (!scaffoldMoveBindingsRemapped || mBindingState == null) return;
        scaffoldMoveBindingsRemapped = false;
        for (int code : scaffoldMoveCodes) {
            if (code == 0) continue;
            try { mBindingState.invoke(null, code, physicalKeyDown(code)); }
            catch (Throwable t) { logOnce("restore scaffold movement input", t); }
        }
    }

    private static void ownBinding(int code, boolean down) throws Exception {
        if (code == 0 || mBindingState == null) return;
        mBindingState.invoke(null, code, down);
        for (int i = 0; i < invMoveOwnedCount; i++) if (invMoveOwnedCodes[i] == code) return;
        if (invMoveOwnedCount < invMoveOwnedCodes.length) invMoveOwnedCodes[invMoveOwnedCount++] = code;
    }

    private static void releaseInventoryMoveInputs() throws Exception {
        invMoveActive=false;
        boolean restorePhysical=false;
        try{Object mc=mGetMC==null?null:mGetMC.invoke(null);restorePhysical=!moduleHotkeysBlocked(mc);}
        catch(Throwable t){logOnce("release inventory input context",t);}
        if (mBindingState != null) {
            for (int i = 0; i < invMoveOwnedCount; i++) {
                int code = invMoveOwnedCodes[i];
                mBindingState.invoke(null, code, restorePhysical && physicalKeyDown(code));
            }
        }
        invMoveOwnedCount = 0;
    }

    /**
     * Feed the physical keys into vanilla KeyBinding immediately before
     * EntityPlayerSP updates MovementInput.  Vanilla then owns acceleration,
     * friction, diagonal normalization, sprint transitions and jump timing.
     */
    private static void syncInventoryMoveInputs(Object mc) throws Exception {
        invMoveActive=false;
        // Retain old config indexes, but retire their two unrestricted key
        // toggles. Importing an older profile cannot restore menu sneaking.
        if(p(F_INVMOVE,0)!=0)mods[F_INVMOVE].ps[0].v=0;
        if(p(F_INVMOVE,4)!=0)mods[F_INVMOVE].ps[4].v=0;
        if (mc == null || fGameSettings == null || mBindingState == null || mBindingCode == null) {
            releaseInventoryMoveInputs();
            return;
        }
        Object screen = fCurrentScreen == null ? null : fCurrentScreen.get(mc);
        boolean editing=true;
        try{editing=screenHasTextInput(screen);}catch(Throwable t){logOnce("inventory text focus",t);}
        boolean screenAllowed = screen != null && !editing;
        if (screenAllowed && p(F_INVMOVE, 2) > 0) {
            // GuiInventory/GuiChest inherit GuiContainer; their concrete class
            // names do not contain "GuiContainer".  The old string test made
            // the default GUI Only setting reject every real inventory screen.
            String screenClass = screen.getClass().getName();
            screenAllowed = cGuiContainer != null ? cGuiContainer.isInstance(screen)
                    : screenClass.contains("GuiInventory") || screenClass.contains("GuiChest")
                            || screenClass.contains("ContainerScreen") || screenClass.contains("InventoryScreen");
        }
        boolean inv = mods[F_INVMOVE].on && screenAllowed;
        boolean freezeForCamera = mods[F_FREECAM].on && (int) p(F_FREECAM, 1) == 0;
        boolean freezeForScreen = mods[F_INVMOVE].on && screen != null && !screenAllowed;
        if (!inv && !freezeForCamera && !freezeForScreen) {
            releaseInventoryMoveInputs();
            return;
        }
        Object settings = fGameSettings.get(mc);
        invMoveActive=inv && !freezeForCamera;
        int forward = bindingCode(settings, fForwardKey), back = bindingCode(settings, fBackKey);
        int left = bindingCode(settings, fLeftKey), right = bindingCode(settings, fRightKey);
        int jump = bindingCode(settings, fJumpKey), sneak = bindingCode(settings, fSneakKey);
        int sprint = bindingCode(settings, fSprintKey);
        ownBinding(forward, invMoveActive && physicalKeyDown(forward));
        ownBinding(back, invMoveActive && physicalKeyDown(back));
        ownBinding(left, invMoveActive && physicalKeyDown(left));
        ownBinding(right, invMoveActive && physicalKeyDown(right));
        ownBinding(jump, invMoveActive && p(F_INVMOVE, 1) > 0 && physicalKeyDown(jump));
        // Suppress the gameplay bindings only. Physical Shift is untouched, so
        // vanilla inventory quick-move and text selection still work.
        ownBinding(sneak, false);
        ownBinding(sprint, false);
        // Keep the parameter wired even when no item is currently in use; the
        // completed vanilla tick applies the compensation once, below.
        if (p(F_INVMOVE, 3) < 0) throw new IllegalStateException("InvMove No Slow out of range");
    }

    private static void doInventoryMove(Object mc) throws Exception {
        syncInventoryMoveInputs(mc);
    }

    private static void doInvMoveNoSlow(Object player) throws Exception {
        if (mHandActive == null || !Boolean.TRUE.equals(mHandActive.invoke(player))) return;
        double mx = fMotionX.getDouble(player), mz = fMotionZ.getDouble(player);
        double len = Math.sqrt(mx * mx + mz * mz);
        if (len < 0.001) return;
        double target = Math.min(0.13, len * 5.0);
        fMotionX.setDouble(player, mx / len * target);
        fMotionZ.setDouble(player, mz / len * target);
    }

    /** 无碰撞 / 穿墙: 直接改坐标推进 (会与服务端位置校验冲突, 仅适合自己服务器) */
    private static void doNoClip(Object player) throws Exception {
        double speed = p(F_NOCLIP, 0);
        double yaw = Math.toRadians(fYaw.getFloat(player));
        boolean fwd = (Boolean) mKeyDown.invoke(null, 17);
        boolean back = (Boolean) mKeyDown.invoke(null, 31);
        boolean up = (Boolean) mKeyDown.invoke(null, 57);
        boolean down = (Boolean) mKeyDown.invoke(null, 42);
        double dx = 0, dy = 0, dz = 0;
        if (fwd) { dx -= Math.sin(yaw) * speed; dz += Math.cos(yaw) * speed; }
        if (back) { dx += Math.sin(yaw) * speed; dz -= Math.cos(yaw) * speed; }
        if (up) dy += speed;
        if (down) dy -= speed;
        if (dx == 0 && dy == 0 && dz == 0) return;

        // NCP 档: 把单次位移封顶在 0.4 格。服务端位置校验的容忍上限就在这附近,
        // 走得更慢, 但不会每走一步都被拉回去 —— 慢而能用, 比快而废强。
        if ((int) p(F_NOCLIP, 1) == 1) {
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len > 0.4) { double s = 0.4 / len; dx *= s; dy *= s; dz *= s; }
        }

        fPosX.setDouble(player, fPosX.getDouble(player) + dx);
        fPosY.setDouble(player, fPosY.getDouble(player) + dy);
        fPosZ.setDouble(player, fPosZ.getDouble(player) + dz);
        fPrevX.setDouble(player, fPosX.getDouble(player));
        fPrevY.setDouble(player, fPosY.getDouble(player));
        fPrevZ.setDouble(player, fPosZ.getDouble(player));
    }

    /** 穿墙: 与无碰撞同源, 只给水平方向, 速度更保守 */
    private static void doPhase(Object player) throws Exception {
        int mode = (int) p(F_PHASE, 1);                                           // Vanilla / Sneak / Block
        if (mode == 1) {                                                          // Sneak: 只有蹲下才穿墙
            if (!isSneaking(player)) return;
        }
        doNoClip(player);                                                         // 三种模式都得先取消碰撞
        if (mode == 2) {                                                          // Block: 穿墙时按 Speed 推进
            double yaw = Math.toRadians(fYaw.getFloat(player));
            double s = p(F_PHASE, 0);                                             // Speed
            fMotionX.setDouble(player, -Math.sin(yaw) * s);
            fMotionZ.setDouble(player, Math.cos(yaw) * s);
        }
    }

    // ===== 玩家 =====

    /**
     * 核弹挖掘 (Nuker)。参数: 0=Multiplier 1=Mode 2=Range
     *
     * Mode:
     *   All   —— 半径内所有方块
     *   Flat  —— 只挖和你脚同一层 (挖地板/清场地用, 不会把自己脚下挖空掉下去)
     *   Block —— 只挖准星对着的那一块 (原来的行为)
     *
     * 用 PlayerControllerMP.onPlayerDamageBlock 逐块点, 不是直接把世界里的方块删掉 ——
     * 删方块是纯客户端行为, 服务端根本不知道, 一同步就回来了。走正常挖掘流程服务端才认。
     */
    private static long lastNuke = 0;
    private static void doInstantMine(Object mc, Object player) throws Exception {
        if (fCurBlockDamage == null || fPlayerController == null) return;
        Object pc = fPlayerController.get(mc);
        if (pc == null) return;
        // 挖掘进度和冷却清零 —— 这两条是"瞬间"的来源, 每一档都要
        fCurBlockDamage.setFloat(pc, (float) p(F_INSTANTMINE, 0));
        if (fBlockHitDelay != null) fBlockHitDelay.setInt(pc, 0);

        int mode = (int) p(F_INSTANTMINE, 1);
        if (mode == 2) return;   // Block: 只作用于准星那块, 上面两条已经够了

        if (mOnDamageBlock == null || ctorBlockPos == null || mIsAirBlock == null || enumUp == null) return;
        long now = System.currentTimeMillis();
        if (now - lastNuke < 50) return;          // 每 tick 最多点一次, 连点会被当 flood 踢
        lastNuke = now;

        Object world = worldOf(player);
        if (world == null) return;
        int range = (int) p(F_INSTANTMINE, 2);
        int bx = (int) Math.floor(fPosX.getDouble(player));
        int by = (int) Math.floor(fPosY.getDouble(player));
        int bz = (int) Math.floor(fPosZ.getDouble(player));
        // Multiplier 当"每 tick 挖几块"用, 但夹到 4 —— 一次挖太多服务端会直接踢
        int perTick = Math.max(1, Math.min(4, (int) Math.round(p(F_INSTANTMINE, 0))));
        int done = 0;
        for (int dy = 0; dy <= (mode == 1 ? 0 : range); dy++) {
            for (int dx = -range; dx <= range && done < perTick; dx++) {
                for (int dz = -range; dz <= range && done < perTick; dz++) {
                    int x = bx + dx, y = by + dy, z = bz + dz;
                    Object bp = ctorBlockPos.newInstance((double) x, (double) y, (double) z);
                    if (Boolean.TRUE.equals(mIsAirBlock.invoke(world, bp))) continue;
                    mOnDamageBlock.invoke(pc, bp, enumUp);
                    done++;
                }
            }
        }
    }

    /**
     * 自动进食。参数: 0=Trigger Food 1=Delay 2=Mode 3=Switch Back
     *
     * Mode: 0=Any 1=Best 2=No Poison —— 决定吃哪一个。
     * 原来只会在手上已经是食物时才吃, 背包里有吃的也不会自己换 —— 那就不叫"自动"了。
     */
    private static long lastEat = 0;
    private static int eatPrevSlot = -1;
    private static void doAutoEat(Object mc, Object player) throws Exception {
        int food = foodLevel(player);
        if (food < 0 || food > p(F_AUTOEAT, 0)) return;
        long now = System.currentTimeMillis();

        // Switch Back: 吃完了把槽切回去, 免得手上一直攥着面包
        if (p(F_AUTOEAT, 3) > 0 && eatPrevSlot >= 0) {
            if (mIsHandActive != null && !(Boolean) mIsHandActive.invoke(player)) {
                if (hotbarIndex(player) != eatPrevSlot) setHotbar(player, eatPrevSlot);
                eatPrevSlot = -1;
            }
        }

        int mode = (int) p(F_AUTOEAT, 2);
        Object held = heldItem(player);
        if (!itemLike(held, "food")) {
            // 手上不是吃的: 在快捷栏里找一个
            int slot = findFood(player, mode);
            if (slot < 0) return;
            if (p(F_AUTOEAT, 3) > 0 && eatPrevSlot < 0) eatPrevSlot = hotbarIndex(player);
            setHotbar(player, slot);
            return;   // 这一 tick 只负责换手, 下一 tick 再吃 —— 换手和进食同一 tick 会丢包
        }
        if (mode == 2 && isBadFood(held)) return;   // No Poison: 腐肉/蜘蛛眼之类不吃
        if (now - lastEat < (long) (p(F_AUTOEAT, 1) * 100)) return;
        lastEat = now;
        if (mRightClickMouse != null) mRightClickMouse.invoke(mc);
    }

    /** 在快捷栏里挑一个食物槽位。Best 挑名字里带"熟"的, 找不到就退回第一个。 */
    private static int findFood(Object player, int mode) throws Exception {
        // 原来是 (Object[]) 强转 —— mainInventory 是 NonNullList, 这行直接 ClassCastException
        java.util.List<?> arr = mainInv(player);
        if (arr == null) return -1;
        int best = -1;
        for (int i = 0; i < 9; i++) {
            Object st = invAt(arr, i);
            if (!itemLike(st, "food")) continue;
            if (mode == 2 && isBadFood(st)) continue;
            if (best < 0) best = i;
            if (mode == 1 && cookedFood(st)) return i;   // Best: 优先熟食
        }
        return best;
    }

    /** 熟食: 名字里带 cooked / baked / golden 的 */
    private static boolean cookedFood(Object stack) {
        return itemLike(stack, "cooked") || itemLike(stack, "baked") || itemLike(stack, "golden");
    }

    /** 吃了会中毒的: 腐肉 / 蜘蛛眼 / 毒马铃薯 */
    private static boolean isBadFood(Object stack) {
        return itemLike(stack, "rotten") || itemLike(stack, "spider_eye") || itemLike(stack, "poisonous");
    }

    /**
     * 自动钓鱼。参数: 0=Delay 1=Auto Reel 2=Mode
     *
     * Auto Reel 原来是个摆设 —— 真正的"自动收杆"要看浮标有没有下沉。
     * 咬钩时服务端会把浮标的 motionY 往下拽, 客户端在收包后能看到这个值变负, 这就是信号。
     * 看不到浮标 (没抛竿 / 区块没加载) 就退回按 Delay 周期收放, 也就是原来的行为。
     */
    private static long lastFish = 0;
    private static void doAutoFish(Object mc, Object player) throws Exception {
        if (!itemLike(heldItem(player), "fishing")) return;
        long now = System.currentTimeMillis();
        int mode = (int) p(F_AUTOFISH, 2);        // 0=Normal 1=Instant

        // Instant: 抛下去就立刻收, 纯刷竿数 (服务端会当你钓了个寂寞, 但动作是真的)
        if (mode == 1) {
            if (now - lastFish < (long) (p(F_AUTOFISH, 0) * 500 + 500)) return;
            lastFish = now;
            if (mRightClickMouse != null) mRightClickMouse.invoke(mc);
            return;
        }

        // Normal: 等浮标咬钩
        if (p(F_AUTOFISH, 1) > 0) {
            Object hook = ownFishHook(player);
            if (hook != null) {
                double my = fMotionY.getDouble(hook);
                if (my < -0.04) {                 // 咬钩下沉
                    if (now - lastFish < 200) return;   // 别连续收, 服务端会认为在刷
                    lastFish = now;
                    if (mRightClickMouse != null) mRightClickMouse.invoke(mc);
                    return;
                }
                return;                           // 浮标在, 但没咬 —— 什么都不做
            }
        }
        // 没有浮标 / 关了 Auto Reel: 按 Delay 周期收放
        if (now - lastFish < (long) (p(F_AUTOFISH, 0) * 500 + 1500)) return;
        lastFish = now;
        if (mRightClickMouse != null) mRightClickMouse.invoke(mc);
    }

    /** 找自己抛出去的那个浮标 (EntityFishHook)。找不到返回 null。 */
    private static Object ownFishHook(Object player) {
        try {
            Object mc = mGetMC.invoke(null);
            Object world = fWorld.get(mc);
            if (world == null) return null;
            for (Object e : entities(world)) {
                if (e == null) continue;
                if (!e.getClass().getSimpleName().contains("FishHook")) continue;
                Object angler = anglerOf(e);
                if (angler == player) return e;
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * 自动挂机 (AntiAFK)。参数: 0=Interval 1=Mode 2=Radius
     *
     * Radius 之前没接 —— 所以"走路"档其实只是原地跳, 人一直在同一个格子上, 服务器一眼看出是挂机。
     * 现在 Walk 档会在起点半径内真的绕圈走。
     */
    private static long lastAfk = 0;
    private static double afkHomeX = Double.NaN, afkHomeZ = Double.NaN;
    private static int afkWalkDir = 1;
    private static void doAntiAFK(Object mc, Object player) throws Exception {
        long now = System.currentTimeMillis();
        if (now - lastAfk < (long) (p(F_ANTIAFK, 0) * 1000)) return;
        lastAfk = now;

        double px = fPosX.getDouble(player), pz = fPosZ.getDouble(player);
        if (Double.isNaN(afkHomeX)) { afkHomeX = px; afkHomeZ = pz; }
        int radius = (int) p(F_ANTIAFK, 2);

        switch ((int) p(F_ANTIAFK, 1)) {
            case 0:                                            // Rotate: 转个身
                // 优先级 0: 挂机摆头是最不重要的, 任何一个真在干活的功能都该压过它。
                // 原来它是无条件写的, 而 AntiAFK 在 Misc 分类里排在最后 —— 于是每隔几秒
                // 它会把 KillAura/AimBot/Scaffold 刚摆好的角度硬生生 +5 度。
                if (claimVisibleAim(0, "AntiAFK")) fYaw.setFloat(player, fYaw.getFloat(player) + 5);
                break;
            case 1:                                            // Jump: 跳一下
                if (fOnGround.getBoolean(player)) fMotionY.setDouble(player, 0.42);
                break;
            case 3:                                            // Swing: 挥空
                swingHand(player);
                break;
            default: {                                         // Walk: 在半径内来回走
                double away = Math.abs(px - afkHomeX) + Math.abs(pz - afkHomeZ);
                if (away > radius) afkWalkDir = -afkWalkDir;    // 走远了就掉头
                double yaw = Math.toRadians(fYaw.getFloat(player));
                double s = 0.12 * afkWalkDir;
                fMotionX.setDouble(player, -Math.sin(yaw) * s);
                fMotionZ.setDouble(player, Math.cos(yaw) * s);
                break;
            }
        }
    }

    // ===== 渲染 =====
    private static boolean renderLogged = false;

    /**
     * 世界坐标 -> GUI 逻辑坐标 (屏幕像素 / guiScale)。
     *
     * 为什么自己算矩阵而不是从 GL 读:
     * onRender 在 updateCameraAndRender 的每个 RETURN 前触发, 最后一个 RETURN 是在 renderWorld
     * 之后 —— 那时候 GL 里挂的已经是 2D 正交矩阵了, 读出来的是 UI 矩阵, 不是相机矩阵。
     * 所以只能自己重建: 相机变换 = Rx(pitch) · Ry(yaw+180) · T(-cam), 这是 MC 在
     * setupCameraTransform 里的顺序 (GL 是右乘, 所以实际执行顺序是反的)。
     *
     * 返回 {screenX, screenY}; 点在相机后面 (w <= 0) 时返回 null —— 必须判,
     * 否则背后的物体会被投影到屏幕上对称的位置, 看起来就是"框到处乱飘"。
     */
    private static float[] worldToScreen(double x, double y, double z, float pt,
                                         Object mc, Object player, double vx, double vy, double vz) {
        return worldToScreen(x, y, z, pt, mc, player, vx, vy, vz, false);
    }

    /**
     * 实体的**插值后**坐标 —— 屏幕空间绘制必须一律走这个, 不能直接读 fPosX/fPosY/fPosZ。
     *
     * 用户报的问题: "esp 画的过快, 在我距离目标较远且移动过程中, 框框不在敌人身上, 而是在我
     * 停住时目标会在的位置; 在我停住时画的确实是准的, 但是移动时会和我的视角有偏移,
     * trac 也这样, nametag 也这样"。
     *
     * 这就是原因。MC 的渲染帧率 (比如 144fps) 高于逻辑 tick 率 (20tps), 所以:
     *   - 相机位置 (vx/vy/vz) 和框坐标都按 partialTicks **插值**过 —— 每帧都在动
     *   - 而实体位置直接读 fPosX.getDouble(ent) = **当前 tick 的值**, 一整个 tick 内是死的
     * 于是同一个实体在两个不同的时间点被投影: 相机在"现在", 实体在"上一个 tick 结束时"。
     * 差距正好是一个 tick 的位移量, 且**只在移动时出现** —— 停住时两者相等, 所以"停住时准"。
     * 目标越远这个角度误差越明显 (同样的 tick 位移, 远处对应的屏幕偏移更小但视角误差更持续),
     * 完全吻合用户的描述。
     *
     * 修法: 实体也用 prev + (cur - prev) * pt, 和相机同一个时间点。
     *
     * **但"prev"必须用 lastTickPos (field_70142_S), 不是 prevPos (field_70169_q)。**
     *
     * 这是"疾跑时还是偏"的根因。两个字段名字很像, 更新时机完全不同:
     *   prevPosX/Y/Z  (field_70169_q/r/s) —— 在 Entity.onUpdate **开头**赋值,
     *                                        是"物理上的上一 tick", 拿它插值 = 差一整个 tick。
     *   lastTickPosX/Y/Z (field_70142_S/T/U) —— 在 onEntityUpdate 里赋值, 而且**渲染**
     *                                        (RenderManager.doRenderEntity) 用的就是它。
     * 平时两者相等, 所以低速下看不出区别; **疾跑时一帧位移大, 差一整个 tick 就变成
     * 肉眼可见的偏移** —— 正好对上"我停住时画的确实是准的, 但是移动时会和我的视角有偏移",
     * 而且走得越快偏得越多。
     *
     * 要和原版画在一起, 就必须和原版用同一个字段。
     */
    private static double entX(Object e, float pt) throws Exception { return lerpEnt(fLastTickX, fPosX, e, pt); }
    private static double entY(Object e, float pt) throws Exception { return lerpEnt(fLastTickY, fPosY, e, pt); }
    private static double entZ(Object e, float pt) throws Exception { return lerpEnt(fLastTickZ, fPosZ, e, pt); }

    private static double lerpEnt(Field prev, Field cur, Object e, float pt) throws Exception {
        double a = prev == null ? 0.0 : prev.getDouble(e);
        double b = cur.getDouble(e);
        return a + (b - a) * (double) pt;
    }

    private static double[] transform4(float[] m, double x, double y, double z, double w) {
        return new double[]{
                m[0]*x + m[4]*y + m[8]*z + m[12]*w,
                m[1]*x + m[5]*y + m[9]*z + m[13]*w,
                m[2]*x + m[6]*y + m[10]*z + m[14]*w,
                m[3]*x + m[7]*y + m[11]*z + m[15]*w
        };
    }

    /** A camera matrix and its render call must describe exactly the same sample. */
    static boolean projectionSampleMatches(float capturedPt, int capturedPass,
                                           float renderPt, int renderPass) {
        return Float.isFinite(capturedPt) && Float.isFinite(renderPt)
                && Math.abs(capturedPt - renderPt) <= 1.0e-4f
                && capturedPass == renderPass;
    }

    /** Pure numeric projection used by the runtime replay probe and the live path. */
    static float[] projectFramePoint(float[] model, float[] projection, int[] viewport,
                                     double originX, double originY, double originZ,
                                     double x, double y, double z,
                                     boolean clamp, int displayHeight) {
        if (model == null || projection == null || viewport == null
                || model.length != 16 || projection.length != 16 || viewport.length != 4) {
            return CAPTURED_POINT_REJECTED;
        }
        double rx = x - originX;
        double ry = y - originY;
        double rz = z - originZ;
        double[] eye = transform4(model, rx, ry, rz, 1.0);
        double[] clip = transform4(projection, eye[0], eye[1], eye[2], eye[3]);
        double w = clip[3];
        // A valid captured camera rejecting a point is a final result.  Returning
        // the sentinel prevents worldToScreen from retrying with the unrelated
        // first-person fallback (the old F5 mirror/offset bug).
        if (!clamp && w <= 1.0e-5) return CAPTURED_POINT_REJECTED;
        double[] ndc = clamp ? tracerDirection(clip[0],clip[1],w)
                : new double[]{clip[0]/w,clip[1]/w};
        double ndcX=ndc[0],ndcY=ndc[1];
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY)) return CAPTURED_POINT_REJECTED;
        float sx = (float) (viewport[0] + (ndcX + 1.0) * 0.5 * viewport[2]);
        float sy = (float) (displayHeight
                - (viewport[1] + (ndcY + 1.0) * 0.5 * viewport[3]));
        return new float[]{sx, sy};
    }

    /** Project through the exact matrices and origin captured for this render sample. */
    private static float[] projectCapturedFrame(double x, double y, double z,
                                                 boolean clamp, int displayHeight,
                                                 float partialTicks, int pass) {
        float[] model = camView, projection = camProjection;
        int[] viewport = camViewport;
        if (!camCaptureValid || model == null || projection == null || viewport == null
                || model.length != 16 || projection.length != 16 || viewport.length != 4
                // The renderWorld entry hook advances the serial before camera
                // setup.  This is stronger than the old 500 ms freshness guess:
                // a slow frame remains valid, while even an immediate F5 frame
                // can never reuse the previous frame's matrices.
                || camProjectionSerial != activeWorldProjectionSerial
                || !projectionSampleMatches(camProjectionPartialTicks, camProjectionPass,
                        partialTicks, pass)) return null;
        return projectFramePoint(model, projection, viewport,
                camOriginX, camOriginY, camOriginZ,
                x, y, z, clamp, displayHeight);
    }

    /**
     * @param clamp true  = 允许目标在相机后面, 并把结果钳进屏幕 (追踪线用, 见 projectPointClamped)
     *              false = 身后的点返回 null (框用 —— 背后物体的对称投影会让框看着到处乱飘)
     */
    private static float[] worldToScreen(double x, double y, double z, float pt,
                                         Object mc, Object player, double vx, double vy, double vz,
                                         boolean clamp) {
        try {
            if (fDisplayW == null || fDisplayH == null) return null;
            int dw = (Integer) fDisplayW.get(mc), dh = (Integer) fDisplayH.get(mc);
            if (dw <= 0 || dh <= 0) return null;

            // The captured path exactly matches Minecraft's current camera:
            // dynamic sprint FOV, view bobbing, hurt camera and third person.
            int renderPass = inWorldPhase ? activeWorldPass : camProjectionPass;
            float[] captured = projectCapturedFrame(x, y, z, clamp, dh, pt, renderPass);
            if (captured == CAPTURED_POINT_REJECTED) return null;
            if (captured != null) return captured;

            // Once the camera hook ran, an unavailable/mismatched snapshot is a
            // dropped overlay sample, not permission to project through a
            // different camera.  This prevents a transient capture failure or
            // F5 switch from producing one visibly wrong ESP/Tracer frame.
            if (camCaptureAttempted) return null;

            // Mapping/GL fallback for environments where a frame snapshot could
            // not be captured.  This intentionally remains secondary.
            if (fFov == null || fGameSettings == null) return null;
            Object gs = fGameSettings.get(mc);
            if (gs == null) return null;

            // 相机的 yaw/pitch 用插值后的值 —— 直接用当前 tick 的角度会让框在移动时抖一帧
            float yaw = fPrevYaw.getFloat(player) + (fYaw.getFloat(player) - fPrevYaw.getFloat(player)) * pt;
            float pitch = fPrevPitch.getFloat(player) + (fPitch.getFloat(player) - fPrevPitch.getFloat(player)) * pt;

            float fov = fFov.getFloat(gs);
            if (fov <= 1.0f) fov = 70.0f;
            float[] n = clamp
                    ? projectPointClamped(x - vx, y - vy, z - vz, yaw, pitch, fov, (double) dw / (double) dh)
                    : projectPoint(x - vx, y - vy, z - vz, yaw, pitch, fov, (double) dw / (double) dh);
            if (n == null) return null;
            return new float[]{n[0] * dw, n[1] * dh};
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 投影的纯数学部分, 归一化到 [0,1] (0,0 = 左上角)。
     *
     * 拆出来单独一个方法是为了能跑探针 —— 相机变换的顺序搞反了框会整体偏转/上下颠倒,
     * 那种错在游戏里很难一眼看出来, 但拿几个已知点一测就露馅。
     *
     * 变换顺序 (MC 的 setupCameraTransform 是 rotate(pitch,X) 然后 rotate(yaw+180,Y),
     * GL 右乘所以实际先转 yaw 再转 pitch):
     *   v1 = 点 - 相机
     *   v2 = Ry(yaw+180) · v1
     *   v3 = Rx(pitch) · v2
     *   ndc = perspective(v3)
     * 点在相机后面 (z3 >= 0) 时返回 null —— 必须判, 否则背后的物体会被投到屏幕上对称的位置,
     * 看起来就是"框到处乱飘"。
     */
    static float[] projectPoint(double dx, double dy, double dz,
                                float yaw, float pitch, float fov, double aspect) {
        double a = Math.toRadians(yaw + 180.0);
        double ca = Math.cos(a), sa = Math.sin(a);
        double x1 = ca * dx + sa * dz;
        double y1 = dy;
        double z1 = -sa * dx + ca * dz;

        double b = Math.toRadians(pitch);
        double cb = Math.cos(b), sb = Math.sin(b);
        double x2 = x1;
        double y2 = cb * y1 - sb * z1;
        double z2 = sb * y1 + cb * z1;

        if (z2 > -0.05) return null;

        double f = 1.0 / Math.tan(Math.toRadians(fov) * 0.5);
        double ndcX = (f / aspect) * x2 / (-z2);
        double ndcY = f * y2 / (-z2);
        return new float[]{(float) ((ndcX + 1.0) * 0.5), (float) ((1.0 - ndcY) * 0.5)};
    }

    /** Perspective inside the view; camera-relative lower edge bearing behind it. */
    static float[] projectPointClamped(double dx, double dy, double dz,
                                       float yaw, float pitch, float fov, double aspect) {
        double a = Math.toRadians(yaw + 180.0);
        double ca = Math.cos(a), sa = Math.sin(a);
        double x1 = ca * dx + sa * dz;
        double y1 = dy;
        double z1 = -sa * dx + ca * dz;

        double b = Math.toRadians(pitch);
        double cb = Math.cos(b), sb = Math.sin(b);
        double x2 = x1;
        double y2 = cb * y1 - sb * z1;
        double z2 = sb * y1 + cb * z1;

        double f = 1.0 / Math.tan(Math.toRadians(fov) * 0.5);
        double[] ndc=tracerDirection((f/aspect)*x2,f*y2,-z2);
        return new float[]{(float)((ndc[0]+1)*.5),(float)((1-ndc[1])*.5)};
    }

    /** Behind-camera targets use a lower edge bearing, never a mirrored perspective point. */
    static double[] tracerDirection(double x,double y,double depth) {
        if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(depth))
            return new double[]{Double.NaN,Double.NaN};
        if(depth<=0){
            y=-Math.max(Math.abs(depth),1e-5);
            double edge=Math.max(Math.abs(x),Math.abs(y));
            return new double[]{.96*x/edge,.96*y/edge};
        }
        double nx=x/Math.max(depth,1e-5),ny=y/Math.max(depth,1e-5);
        double edge=Math.max(1,Math.max(Math.abs(nx),Math.abs(ny)));
        return new double[]{nx/edge,ny/edge};
    }

    /**
     * 屏幕空间画一个 AABB 的 2D 外框 (取 8 个角投影后的包围盒)。
     * 用 Gui.drawRect 画四条边 —— GL 立即模式在 1.12 的着色器管线下不出图, 只有 Gui 那套能用。
     */
    private static void drawScreenBox(Object bb, float pt, Object mc, Object player,
                                      double vx, double vy, double vz,
                                      int rgb, int thickness, boolean corners, boolean fill) throws Exception {
        if (bb == null || mDrawRect == null || mAabbMinX == null) return;
        double x0 = mAabbMinX.getDouble(bb), y0 = mAabbMinY.getDouble(bb), z0 = mAabbMinZ.getDouble(bb);
        double x1 = mAabbMaxX.getDouble(bb), y1 = mAabbMaxY.getDouble(bb), z1 = mAabbMaxZ.getDouble(bb);

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        boolean any = false;
        for (int i = 0; i < 8; i++) {
            double px = (i & 1) == 0 ? x0 : x1;
            double py = (i & 2) == 0 ? y0 : y1;
            double pz = (i & 4) == 0 ? z0 : z1;
            float[] s = worldToScreen(px, py, pz, pt, mc, player, vx, vy, vz);
            if (s == null) continue;               // 有一个角在后面就跳过那个角
            any = true;
            minX = Math.min(minX, s[0]); maxX = Math.max(maxX, s[0]);
            minY = Math.min(minY, s[1]); maxY = Math.max(maxY, s[1]);
        }
        if (!any) return;
        // **物理像素配物理投影 —— 这里不再除 GS。**
        //
        // worldToScreen 返回的是物理像素 (n[0] * displayWidth), 原来的写法把它除以 GS 换成
        // 逻辑坐标, 再配 pushLogicalProjection。问题在于 GS 是**打开 GUI 界面**时才赋值的
        // (GS = guiScaleFor(screen)), 而 ESP 绘制的时候根本没有界面 —— GS 还停在初值 1。
        // 除以 1 等于没除, 于是物理坐标喂给了逻辑投影, 整组框偏到一边, 且不随视角变化。
        //
        // 改成全程物理像素: 坐标本来就是这个单位, pushPhysicalProjection 也是"1 单位 = 1 物理像素"。
        // 两边天然一致, 而且**不再依赖 GS 这个只在特定时机才正确的变量** —— 少一个隐式前提。
        int l = Math.round(minX), r = Math.round(maxX);
        int t = Math.round(minY), btm = Math.round(maxY);
        if (r - l < 1 || btm - t < 1) return;

        // 必须自己压 2D 正交投影。
        //
        // drawScreenBox 吃的是屏幕坐标, 但调用它的时候当前矩阵还是 3D 相机矩阵 ——
        // onRender 是在世界渲染里触发的。不压的话这些屏幕坐标会被当成相机空间里的点再变换一次,
        // 画出来的东西落到屏幕外。压在这里而不是调用方: ESP / StorageESP / ItemESP 都走这个函数,
        // 放在共享函数里一次管住。
        pushPhysicalProjection();
        // thickness <= 0 = **只填充不描边**。StorageESP 的 mode 0 要的正是这个:
        // 它的框走 3D 那条路 (drawWorldBox) 自己会描边, 这里只需要补一层半透明底。
        int th = thickness;
        try {
            dreamBox(l, t, r, btm, rgb, th, corners, fill);
        } finally {
            popPhysicalProjection();
        }
    }

    /**
     * 画一个世界坐标的 AABB 框。
     * 必须按 renderEsp 那套来: 先 offset 到相机相对坐标, 再调 RenderGlobal.drawBoundingBox,
     * 而 drawBoundingBox 的签名是 (AABB, r, g, b, a) —— 多传坐标会直接抛异常。
     */
    private static void drawWorldBox(Object bb, double vx, double vy, double vz,
                                     float r, float g, float b) throws Exception {
        if (bb == null || mAabbOffset == null || mDrawBox == null) return;
        Object shifted = mAabbOffset.invoke(bb, -vx, -vy, -vz);
        drawOutline(shifted, r, g, b);
    }

    /** true 表示当前这次 onRender 是挂在 renderWorld 返回点上的 (世界阶段, 透视投影有效) */
    private static boolean inWorldPhase, worldHookLogged, worldHookEverFired, guiFallbackLogged, camViewProbed, loadProbed, viewerProbed;
    private static int activeWorldPass = Integer.MIN_VALUE;
    private static boolean tickHookAProbed, tickHookBProbed, restoreProbed, camReadyProbed, visualPitchSet;
    /**
     * onCameraReady 写瞄准俯仰之前, 把**当时真实的** pitch/prevPitch 存下来, 渲染完再还回去。
     *
     * 为什么不是"还回一个缓存好的真实视角" (上一版的写法): 那个缓存值随时可能是脏的,
     * 而且每帧无条件写回会把鼠标增量一起擦掉 —— 表现就是**视角被锁死, 动不了**。
     * 这里存的是"写之前那一刻的现场值", 还回去等于什么都没发生过, 不可能伤到输入。
     */
    private static float savedPitch, savedPrevPitch, savedTickYaw, savedTickPitch;
    private static float savedTickPrevYaw, savedTickPrevPitch;
    private static float savedModelBodyYaw, savedPrevModelBodyYaw;
    private static float savedModelHeadYaw, savedPrevModelHeadYaw;
    private static boolean pitchSaved, tickSaved, modelFieldsSaved, vanillaAimMovementApplied;
    /** Exact Scaffold pose installed before super.onUpdate and reused by its packet. */
    private static boolean scaffoldVanillaMovementApplied;
    private static float scaffoldMovementYaw, scaffoldMovementPitch;
    private static boolean scaffoldInputHeadingReady;
    private static float scaffoldInputHeading;
    /** Tick whose vanilla body fields already consumed scaffoldMovementYaw. */
    private static int scaffoldVanillaPoseTick = Integer.MIN_VALUE;
    private static final int[] scaffoldMoveCodes = new int[4];
    private static boolean scaffoldMoveBindingsRemapped;
    /**
     * 俯仰角追踪 (每秒一条, 排完就删)。
     *
     * 现在唯一剩下的问题是"真实俯仰滞后一拍": 快照里 vis.pitch 恰好等于上一拍的 sent.pitch,
     * 说明鼠标增量在某个环节被吃掉了。鼠标在 runTick 里写 rotationPitch, 而我有三处会碰它 ——
     * onCameraReady 写瞄准值、applySilentRotation 写回真实值、onPlayerMoved 读一次再写瞄准值。
     * 光看每 tick 一条快照分不清是谁吃的, 所以在这三个时刻各记一次, 带时刻名。
     */
    static void pitchTrace(String where, Object player) {
        long now = System.currentTimeMillis();
        if (now - pitchTraceAt < 1000) return;
        pitchTraceAt = now;
        try {
            log("俯仰追踪 [" + where + "]: pitch=" + fPitch.getFloat(player)
                    + " prev=" + (fPrevPitch == null ? "?" : String.valueOf(fPrevPitch.getFloat(player)))
                    + " vis=" + visualPitch + " sent=" + sentPitch
                    + " applied=" + silentApplied + " modelOn=" + silentModelOn);
        } catch (Throwable ignored) { }
    }

    private static long pitchTraceAt, silentDumpAt, kaWhyAt, modelWhyAt, kaTargetUntil, aimWhyAt;
    /** 当前锁定的目标 (粘性用): 没死、没跑远、还看得见就一直用, 不每帧重选。见 doKillAura。 */
    private static Object kaTarget;
    /** 每个目标只生成一次的随机偏置 (Bias)。每帧重抽会被瞄准累积积分成抖动。 */
    private static Object kaBiasFor;
    private static float kaBiasYaw, kaBiasPitch;
    /**
     * **模型头部**要看的朝向 —— 和攻击瞄准 (sentYaw/sentPitch) 是两件事。
     *
     * 真人后退搭路的样子: 身体朝着走的方向 (原版自己算, 不碰), **头低下去看上一块方块的
     * 侧面** —— 因为下一块要靠着它放。所以头的目标不是"敌人", 是"搭路那个依附面"。
     * Scaffold 每帧把依附面角度写进这两个字段, onCameraReady 拿它去写 rotationYawHead /
     * rotationPitch。KillAura 不动它们 —— 打人时头跟着攻击目标, 那才自然。
     */
    private static float modelHeadYaw, modelHeadPitch;
    private static boolean modelHeadSet;

    /**
     * 静默转体的值快照 (每秒一条, 排完就删)。
     *
     * 视角被瞄准角拖走这件事, 只有把四个值摆在一起才能判断是谁污染了谁:
     *   vis  = 真实视角 (applySilentRotation 每帧抓的)
     *   rot  = player.rotationYaw/Pitch —— 相机实际用的
     *   sent = 这一步要发的角度
     * rotationYaw 应该等于 vis; 如果它一直贴着 sent, 说明还原那一步没生效,
     * 而还原用的 vis 又是从 rotationYaw 抓的 —— 那就是棘轮: 污染一次就再也回不去。
     */
    static void silentDump(Object unused) {
        long now = System.currentTimeMillis();
        if (now - silentDumpAt < 1000) return;
        silentDumpAt = now;
        try {
            Object mc = mGetMC == null ? null : mGetMC.invoke(null);
            Object player = mc == null ? null : fPlayer.get(mc);
            if (player == null) return;
            log("静默快照: rot=(" + fYaw.getFloat(player) + ", " + fPitch.getFloat(player)
                    + ") vis=(" + visualYaw + ", " + visualPitch
                    + ") sent=(" + sentYaw + ", " + sentPitch + ")"
                    + " silentApplied=" + silentApplied + " silentModelOn=" + silentModelOn
                    + " silentBodyToo=" + silentBodyToo);
        } catch (Throwable ignored) { }
    }
    private static int lastPassLogged = Integer.MIN_VALUE;

    /**
     * 相机视图矩阵 —— 在 {@code setupCameraTransform (func_78479_a)} 返回的那一刻抓下来的。
     *
     * 为什么需要它: ESP 挂在 renderWorld 的返回点, 那时**世界矩阵栈已经被拆掉**了。
     * 实测那里 GL_MODELVIEW 的平移分量是 (0,0,0) —— 投影是对的 (透视), 但没有任何相机变换,
     * 所以框会整体错位。1.8.9 拆矩阵比 1.12.2 早, 这就是同一份代码只在 1.8.9 上错位的原因。
     *
     * setupCameraTransform 返回时, 相机矩阵**正好**是最新的 (内部刚调用过 orientCamera),
     * 而模型还没开始渲染 —— 全帧唯一能干净拿到它的时刻。已经有钩子挂在那儿了 (onCameraReady)。
     */
    private static float[] camView, camProjection;
    private static int[] camViewport;
    private static long camProjectionAt;
    /** setupCameraTransform/Camera.setup arguments carried by the transformer. */
    private static float camHookPartialTicks = Float.NaN;
    private static int camHookPass = Integer.MIN_VALUE;
    /** Camera-entity render origin derived for the same partialTicks as the matrices. */
    private static double camOriginX, camOriginY, camOriginZ;
    private static float camProjectionPartialTicks = Float.NaN;
    private static int camProjectionPass = Integer.MIN_VALUE;
    private static long worldProjectionSerial, activeWorldProjectionSerial, camProjectionSerial;
    private static boolean camCaptureAttempted, camCaptureValid;
    /** Valid captured matrix, but the point belongs behind the camera. */
    private static final float[] CAPTURED_POINT_REJECTED = new float[0];

    static void captureFrameProjection() {
        camCaptureAttempted = true;
        camCaptureValid = false;
        try {
            Class<?> c = Class.forName("org.lwjgl.opengl.GL11");
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(64)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            java.lang.reflect.Method getFloat = c.getMethod("glGetFloat", int.class, java.nio.FloatBuffer.class);
            int mv = ((Number) c.getField("GL_MODELVIEW_MATRIX").get(null)).intValue();
            int projection = ((Number) c.getField("GL_PROJECTION_MATRIX").get(null)).intValue();
            int viewport = ((Number) c.getField("GL_VIEWPORT").get(null)).intValue();
            float[] model = new float[16], proj = new float[16];
            fb.clear(); getFloat.invoke(null, Integer.valueOf(mv), fb); fb.position(0); fb.get(model);
            fb.clear(); getFloat.invoke(null, Integer.valueOf(projection), fb); fb.position(0); fb.get(proj);
            // LWJGL 2 GL11.glGetInteger checks for *16 ints* even for
            // GL_VIEWPORT (which writes only four).  The old 16-byte buffer had
            // capacity 4 and therefore threw before every capture, silently
            // forcing ESP/Tracers onto the analytic first-person fallback.
            java.nio.IntBuffer ib = java.nio.ByteBuffer.allocateDirect(16 * 4)
                    .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
            c.getMethod("glGetInteger", int.class, java.nio.IntBuffer.class)
                    .invoke(null, Integer.valueOf(viewport), ib);
            ib.position(0);
            int[] vp = new int[4];
            ib.get(vp);
            if (vp[2] <= 0 || vp[3] <= 0) throw new IllegalStateException("empty GL viewport");

            if (mGetMC == null || fRenderViewEntity == null || fLastTickX == null
                    || fLastTickY == null || fLastTickZ == null) {
                throw new IllegalStateException("camera entity origin is not bound");
            }
            Object mc = mGetMC.invoke(null);
            Object cameraEntity = mc == null ? null : fRenderViewEntity.get(mc);
            if (cameraEntity == null) throw new IllegalStateException("renderViewEntity is null");
            // In 1.8.9 renderWorldPass calls setupCameraTransform *before*
            // RenderGlobal.renderEntities updates RenderManager.renderPos.  Reading
            // RenderManager here therefore freezes the previous frame's origin and
            // drifts while sprinting.  RenderManager's eventual origin is exactly
            // this camera-entity lastTickPos interpolation, so derive it now from
            // the same partialTicks that produced the captured matrix.  This also
            // follows FreeCam and F5's renderViewEntity instead of the local player.
            double originX = entX(cameraEntity, camHookPartialTicks);
            double originY = entY(cameraEntity, camHookPartialTicks);
            double originZ = entZ(cameraEntity, camHookPartialTicks);
            if (!Double.isFinite(originX) || !Double.isFinite(originY) || !Double.isFinite(originZ)) {
                throw new IllegalStateException("non-finite camera origin");
            }
            if (!camViewProbed) {
                camViewProbed = true;
                log("抓到本帧完整相机矩阵: 投影m00=" + proj[0]
                        + " 视图平移=(" + model[12] + ", " + model[13] + ", " + model[14]
                        + ") viewport=" + vp[0] + "," + vp[1] + "," + vp[2] + "x" + vp[3]
                        + " origin=(" + originX + "," + originY + "," + originZ + ")"
                        + " pt=" + camHookPartialTicks + " pass=" + camHookPass);
            }
            // Publish the complete sample only after every component succeeded.
            camOriginX = originX;
            camOriginY = originY;
            camOriginZ = originZ;
            camView = model;
            camProjection = proj;
            camViewport = vp;
            camProjectionPartialTicks = camHookPartialTicks;
            camProjectionPass = camHookPass;
            camProjectionSerial = activeWorldProjectionSerial;
            camProjectionAt = System.nanoTime();
            camCaptureValid = true;
        } catch (Throwable t) {
            camView = null;
            camProjection = null;
            camViewport = null;
            camProjectionAt = 0L;
            logOnce("cameraProjectionCapture", t);
        }
    }

    /** 把抓到的相机矩阵加载进当前矩阵 (调用前要自己 push)。 */
    private static void loadCameraView() {
        if (camView == null) { log("loadCameraView: camView 是 null (onCameraReady 没抓到)"); return; }
        try {
            Class<?> c = Class.forName("org.lwjgl.opengl.GL11");
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(64)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            fb.put(camView);
            fb.position(0);
            java.lang.reflect.Method load = c.getMethod("glLoadMatrixf", java.nio.FloatBuffer.class);
            // 载入前先看**当前**矩阵有没有旋转 —— 如果当前矩阵本来就有旋转, 那问题就不在这里,
            // 我不该用抓来的那份覆盖它。
            java.nio.FloatBuffer cur = java.nio.ByteBuffer.allocateDirect(64)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            int mvm = ((Number) c.getField("GL_MODELVIEW_MATRIX").get(null)).intValue();
            c.getMethod("glGetFloat", int.class, java.nio.FloatBuffer.class).invoke(null, Integer.valueOf(mvm), cur);
            if (!loadProbed) {
                loadProbed = true;
                log("绘制前: 当前矩阵 上向量=(" + cur.get(4) + ", " + cur.get(5) + ", " + cur.get(6)
                        + ") 平移=(" + cur.get(12) + ", " + cur.get(13) + ", " + cur.get(14) + ")"
                        + "  |  我抓到的 上向量=(" + camView[4] + ", " + camView[5] + ", " + camView[6] + ")");
            }
            load.invoke(null, fb);
        } catch (Throwable t) { log("loadCameraView 失败: " + t); }
    }

    /**
     * 打印当前投影矩阵和世界矩阵的关键分量 —— 判断"画在哪、位置对不对"的硬证据。
     *
     * 投影 m00: 透视是个位数 (约 1.3), 正交约 0.0008。
     * 世界矩阵的平移分量 (m30/m31/m32) 就是相机位置 —— 如果 ESP 的位置偏了, 多半是这里不对
     * (比如 renderWorld 返回前已经把矩阵栈拆掉了, 平移是 0)。
     */
    static String matrixDigest() {
        try {
            Class<?> cGL11 = Class.forName("org.lwjgl.opengl.GL11");
            java.lang.reflect.Method getF = cGL11.getMethod("glGetFloat", int.class, java.nio.FloatBuffer.class);
            java.nio.FloatBuffer fb = java.nio.ByteBuffer.allocateDirect(64)
                    .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
            int proj = ((Number) cGL11.getField("GL_PROJECTION_MATRIX").get(null)).intValue();
            int mv = ((Number) cGL11.getField("GL_MODELVIEW_MATRIX").get(null)).intValue();
            fb.clear(); getF.invoke(null, Integer.valueOf(proj), fb);
            float p00 = fb.get(0);
            fb.clear(); getF.invoke(null, Integer.valueOf(mv), fb);
            return "投影m00=" + p00 + (Math.abs(p00) > 0.05f ? "(透视)" : "(正交!)")
                    + " 相机平移=(" + fb.get(12) + ", " + fb.get(13) + ", " + fb.get(14) + ")";
        } catch (Throwable t) { return "矩阵读取失败: " + t; }
    }

    /**
     * 世界阶段的最后一道 —— 挂在 {@code EntityRenderer.renderWorld (func_175068_a)} 的返回点。
     *
     * 为什么必须单独挂这一个点: ESP / StorageESP / ItemESP / BlockOutline 画的都是
     * **世界坐标**的框, 需要透视投影矩阵。而 onRender 挂在 updateCameraAndRender 的返回点,
     * 那时 GUI 已经画完、投影已经切成**正交**了 —— 实测投影矩阵 m00=0.0047 (= 2/屏幕宽),
     * 世界坐标的框在正交矩阵下会被压成一个点, 所以"调用了 54 次、不报错、屏幕上什么都没有"。
     *
     * 1.12.2 上那条路径恰好还留着透视矩阵, 1.8.9 不同 —— 这就是同一个包在两个版本上
     * 表现不一致的原因, 和句柄/类名/签名毫无关系。
     *
     * renderWorld 一帧会被调用多次 (每个 pass 一次), 只在 pass==0 画, 否则会重复绘制。
     */
    /** Marks the beginning of one renderWorld/renderLevel invocation. */
    public static void onWorldRenderStart(int pass, float partialTicks) {
        scaffoldRenderReady = scaffoldRenderCompleted = false;
        kaRenderReady = kaRenderCompleted = false;
        // Keep the most recent completed credential until the next client-tick
        // input phase consumes it.  At high FPS another renderWorld begins
        // before that tick; clearing here used to make KillAura never attack.
        activeWorldProjectionSerial = ++worldProjectionSerial;
        // If camera capture fails or is skipped this invocation, never reuse the
        // previous F5/sprint matrix.  captureFrameProjection republishes validity.
        camCaptureValid = false;
    }

    public static void onWorldRender(int pass, float partialTicks) {
        completeScaffoldRender();
        completeCombatRender();
        if (!worldHookLogged) { worldHookLogged = true; worldHookEverFired = true; log("onWorldRender 被调用了 (pass=" + pass + ")"); }
        if (pass != lastPassLogged) {
            lastPassLogged = pass;
            log("onWorldRender 收到新的 pass=" + pass + " " + matrixDigest());
        }
        // 只在 renderWorld 的**主 pass** 上画。
        // 一帧画三遍的话, 三个 pass 的世界矩阵不同 (有的还带着手部平移), 框会出现在三个位置。
        // 实测 1.8.9 只见到 pass=2 —— 就用它。
        if (pass != 2) return;
        inWorldPhase = true;
        activeWorldPass = pass;
        try {
            onRender(partialTicks);
        } finally {
            inWorldPhase = false;
            activeWorldPass = Integer.MIN_VALUE;
        }
    }

    public static void onRender(float partialTicks) {
        try {
            if (!renderLogged) {
                renderLogged = true;
                log("onRender first call");
            }
            ensure();
            Object mc0 = mGetMC.invoke(null);
            // 静默转身写入: onRender 在每个 RETURN 前触发, 正好是"这一帧渲染完"的时刻。
            // 放在这里 (而不是后面) 是因为下面有一堆提前 return —— 写晚了就不会执行。
            //
            // **只在 GUI 阶段那一次跑** (也就是原来 updateCameraAndRender 的返回点)。
            //
            // onRender 现在被两个钩子调用: 世界阶段 (renderWorld 返回点, ESP 要用) 和 GUI 阶段。
            // 不判的话 applySilentRotation 一帧跑两遍, 而它在第二行就把请求消费掉了
            //     aiming = silentOn && ...;  silentOn = false;
            // 第二遍进来 silentOn 已是 false, 于是走"没在瞄"分支, 顺手把 silentModelOn 清成 false
            // —— onCameraReady 的第一行判断直接返回, 渲染前最后一道修正被跳过。
            // 表现就是: 视角被瞄准角拖走 (泄漏), 玩偶也不朝目标。
            // 一个回归同时造成两个症状, 因为它们是同一条链上的两个下游。
            if (!inWorldPhase) {
                try { finishScaffoldRender(mc0, fPlayer.get(mc0)); }
                catch (Throwable t) { logOnce("scaffold render commit", t); }
                try { finishCombatRender(mc0, fPlayer.get(mc0)); }
                catch (Throwable t) { logOnce("combat render commit", t); }
                applySilentRotation(mc0, fPlayer.get(mc0));
            }
            // HUD 要在所有提前 return 之前画 —— 它跟 ESP 开不开没关系。
            // 但它挂在 **GUI 阶段** (原 onRender 的那条路): onRender 现在被两个钩子调用,
            // 不判一下会一帧画两遍 (半透明底会叠深)。
            if (!inWorldPhase) {
                setThemeFromParams();
                OverlayFont.depth++;
                try {
                try { renderHud(); } catch (Throwable t) { logOnce("hud", t); }
                if (mods[F_PING].on) safe("PingDisplay", new Act() { public void run() throws Exception { renderPing(); } });
                if (mods[F_WATERMARK].on) safe("Watermark", new Act() { public void run() throws Exception { renderWatermark(); } });
                safe("VisualWidgets", new Act() { public void run() throws Exception { renderVisualWidgets(); } });
                safe("TargetHUD", new Act() { public void run() throws Exception { renderTargetHud(); } });
                safe("ToggleNotices", new Act() { public void run() throws Exception { renderNotices(); } });
                } finally { OverlayFont.depth--; }
            }

            boolean doEsp = mods[F_ESP].on, doStor = mods[F_STORAGEESP].on, doItem = mods[F_ITEMESP].on;
            boolean doOutline = mods[F_BLOCKOUTLINE].on;
            boolean doTrace = mods[F_TRACERS].on, doTags = mods[F_NAMETAGS].on, doTraj = mods[F_TRAJECTORIES].on;
            // 这里全是世界坐标 / 屏幕坐标的绘制, 需要透视投影。
            //
            // 首选: onWorldRender 挂的 renderWorld 返回点 —— 那里是透视矩阵, 三种模式都对。
            // 兜底: 世界钩子一次都没触发时 (OptiFine 改写 EntityRenderer 之类), 退回原来的
            //       updateCameraAndRender 返回点。那时投影是正交的, **mode 0 (3D 框) 出不来**,
            //       但 mode 1/2 (2D Box / Corner) 走屏幕空间投影, 照样能画。
            //       宁可让两种模式能用, 也不要三个模式全瞎 —— 上一版全瞎就是因为我只认世界钩子。
            if (!inWorldPhase && worldHookEverFired) return;
            if (!inWorldPhase && !guiFallbackLogged) { guiFallbackLogged = true;
                log("世界钩子未触发 —— ESP 退回 GUI 阶段绘制 (3D 框改走屏幕空间, 2D Box / Corner 不受影响)"); }
            Object mc = mGetMC.invoke(null);
            Object world = fWorld.get(mc);
            Object player = fPlayer.get(mc);
            if (world == null || player == null) return;
            Object rm = fRenderMgr.get(mc);
            // 相机位置 = 玩家**眼睛**位置, 不是脚底。
            //
            // RenderManager.viewerPosX/Y/Z 理论上就是眼睛位置, 但实测 viewerPosY 和玩家脚底 Y
            // 一模一样 (日志: viewer(-38,64,-15) 玩家(-38,64,-15)), 少了整整一个眼高 1.62。
            // 后果是所有框被整体抬高一个眼高 —— 屏幕上正好落在目标**头顶上方**。
            //
            // 与其继续猜那个字段映射到哪去了, 不如自己算: 眼睛 = 插值后的脚底 + getEyeHeight()。
            // 这个量的定义是确定的, 不依赖任何字段映射。再把两个值打一次日志, 下次就能看清差异。
            double vx = fPrevX.getDouble(player) + (fPosX.getDouble(player) - fPrevX.getDouble(player)) * partialTicks;
            double vz = fPrevZ.getDouble(player) + (fPosZ.getDouble(player) - fPrevZ.getDouble(player)) * partialTicks;
            double feetY = fPrevY.getDouble(player) + (fPosY.getDouble(player) - fPrevY.getDouble(player)) * partialTicks;
            double eyeH = 1.62;
            try { if (mEyeH != null) eyeH = ((Number) mEyeH.invoke(player)).doubleValue(); } catch (Throwable ignored) { }
            double vy = feetY + eyeH;
            if (!viewerProbed) {
                viewerProbed = true;
                log("相机位置: 自算=(" + vx + ", " + vy + ", " + vz + ") 眼高=" + eyeH
                        + "  |  RenderManager=(" + fViewerX.getDouble(rm) + ", " + fViewerY.getDouble(rm)
                        + ", " + fViewerZ.getDouble(rm) + ")");
            }

            // 三个透视共用同一套 GL 状态: 关深度才能穿墙看到, 只开关一次
            cGL.getMethod("func_179094_E").invoke(null);                  // pushMatrix
            cGL.getMethod("func_179090_x").invoke(null);                  // disableTexture2D
            cGL.getMethod("func_179097_i").invoke(null);                  // disableDepth
            cGL.getMethod("func_179132_a", boolean.class).invoke(null, false); // depthMask(false)
            try {
                if (doEsp) safe("ESP", new Act() { public void run() throws Exception { renderEspBoxes(partialTicks, world, player, vx, vy, vz); } });
                if (doStor) safe("StorageESP", new Act() { public void run() throws Exception { renderStorageBoxes(player, vx, vy, vz); } });
                if (doItem) safe("ItemESP", new Act() { public void run() throws Exception { renderItemBoxes(partialTicks, player, vx, vy, vz); } });
                if (doOutline) safe("BlockOutline", new Act() { public void run() throws Exception { renderBlockOutline(player, vx, vy, vz); } });
                // 追踪线 / 名字标签 / 弹道预测都走屏幕空间投影, 和上面几个共用同一套 GL 状态
                if (doTrace) safe("Tracers", new Act() { public void run() throws Exception { renderTracers(partialTicks, world, player, vx, vy, vz); } });
                if (doTags) safe("NameTags", new Act() { public void run() throws Exception { renderNameTags(partialTicks, world, player, vx, vy, vz); } });
                if (doTraj) safe("Trajectories", new Act() { public void run() throws Exception { renderTrajectories(partialTicks, player, vx, vy, vz); } });
                if (mods[F_TARGETHUD].on && p(F_TARGETHUD,5)>0) safe("Target Scan",new Act(){public void run()throws Exception{renderTargetRing(partialTicks,world,player,vx,vy,vz);}});
            } finally {
                cGL.getMethod("func_179126_j").invoke(null);              // enableDepth
                cGL.getMethod("func_179132_a", boolean.class).invoke(null, true);
                cGL.getMethod("func_179098_w").invoke(null);              // enableTexture2D
                cGL.getMethod("func_179121_F").invoke(null);              // popMatrix
            }
        } catch (Throwable t) {
            log("onRender FAILED: " + t);
        }
    }

    /** 实体透视 (玩家/怪物)。参数: 3=Width 4=Mode 5=Players Only 6=Range 7=Fill 8=Show Health */
    private static long espDbgAt = 0;

    private static void renderEspBoxes(float pt, Object world, Object player, double vx, double vy, double vz) throws Exception {
        float r = (float) p(ME, 0) / 255f, g = (float) p(ME, 1) / 255f, b = (float) p(ME, 2) / 255f;
        int rgb = 0xFF000000 | ((int) p(ME, 0) << 16) | ((int) p(ME, 1) << 8) | (int) p(ME, 2);
        int mode = (int) p(ME, 4);                    // 0=Box 1=2D Box 2=Corner
        int width = (int) p(ME, 3);
        double range = p(ME, 6);
        boolean playersOnly = p(ME, 5) > 0;
        boolean fill = p(ME, 7) > 0;
        boolean showHp = p(ME, 8) > 0;
        int targetMode = (int) p(ME, 9);
        Object mc = mGetMC.invoke(null);
        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);

        // 线宽是全局 GL 状态, 循环外设一次就行 (GL 立即模式在 1.12 着色器管线下不出图,
        // 但线宽对 RenderGlobal 那套 VBO 绘制依然生效)
        if (mode == 0) setLineWidth((float) width);
        // ESP 诊断 (1 秒一条, 排完就删)。ESP 从来不抛异常 —— 它只是"跑了但没画出来",
        // 所以要把每一层筛掉多少个变成可读的数字, 而不是猜。
        int dbgSeen = 0, dbgRange = 0, dbgDrawn = 0, dbgNoScreen = 0;
        try {
            for (Object ent : (java.util.List<?>) fLoaded.get(world)) {
                dbgSeen++;
                if (ent == player || !cLiving.isInstance(ent) || fIsDead.getBoolean(ent)) continue;
                if (playersOnly && !cPlayer.isInstance(ent)) continue;
                if (!matchTarget(targetMode, ent) || isBot(player, ent)) continue;
                double ex = entX(ent, pt), ey = entY(ent, pt), ez = entZ(ent, pt);
                if (Math.abs(ex - px) > range
                        || Math.abs(ey - py) > range
                        || Math.abs(ez - pz) > range) continue;
                dbgRange++;

                double x = ex - vx;
                double y = ey - vy;
                double z = ez - vz;
                Object bb = mGetBB.invoke(ent);
                // EntityRenderer draws at lastTickPos -> pos interpolation,
                // while getEntityBoundingBox is stored at the current tick.
                // Shift the box onto the same render sample before projecting.
                if (bb != null && mAabbOffset != null) {
                    bb = mAabbOffset.invoke(bb,
                            ex - fPosX.getDouble(ent),
                            ey - fPosY.getDouble(ent),
                            ez - fPosZ.getDouble(ent));
                }
                // **不要把 bb 预先挪成"相对相机"。**
                //
                // 这段偏移是给 3D 框那条路准备的 (RenderGlobal.drawBox 直接吃相机空间坐标)。
                // 现在所有模式都走 drawScreenBox, 而它内部的 worldToScreen 自己会减掉相机位置
                // (projectPoint(x - vx, ...))。再预先偏移一次 = 视角位置被减两遍,
                // 结果每个框的坐标都归零, 全部投到相机正下方 —— 屏幕上就是"框都堆在我脚下"。
                //
                // 3D 那条分支已经恒不执行 (use3d = false), 需要偏移时在它自己里面做。
                // 3D 那条分支恒不执行 (use3d = false), 注释保留是为了记下为什么放弃它。
                //
                // 之前 3D 框走原版的 RenderGlobal.drawBox, 依赖"在正确的渲染阶段、带正确的矩阵"
                // 画。1.8.9 上这条路走了六轮都没走通: 先是在 GUI 阶段 (正交投影, 画不出来),
                // 换到 renderWorld 返回点后投影对了, 但矩阵栈已经被拆掉 (平移全 0),
                // 补上相机矩阵后又发现那是个没有旋转的第一人称矩阵 —— 表现就是"框不随视角动"。
                //
                // 而 worldToScreen 这条路**不需要原版矩阵**: 它自己拿相机的偏航/俯仰和位置算投影,
                // 天然免疫上面这一整类时序问题。
                //
                // 代价: 框是屏幕对齐的矩形, 不是真正的三维线框 (drawRect 画不了斜线, 需要画线图元)。
                boolean use3d = false;
                if (use3d) {
                    // 3D 路径要的是相机空间坐标, 偏移在这里做 —— 不能提前对 bb 做,
                    // 否则 drawScreenBox 里的 worldToScreen 会再减一次相机位置 (见上面的注释)。
                    bb = mAabbOffset.invoke(bb, -vx, -vy, -vz);
                    if (!espCallProbed) {
                        espCallProbed = true;
                        log("ESP 调用点: 3D 框 bb=" + (bb == null ? "null <-- 问题在这" : bb.getClass().getName())
                                + " mDrawBox=" + (mDrawBox != null) + " fill=" + fill
                                + " mode=" + mode);
                    }
                    // Fill 不在这里做了 —— 整条 3D 路都是死的, 现在统一走 drawScreenBox 的屏幕空间填充。
                    drawOutline(bb, r, g, b);
                    dbgDrawn++;
                } else {
                    drawScreenBox(bb, pt, mc, player, vx, vy, vz, rgb, width, mode == 2, fill);
                    dbgDrawn++;
                }

                // Show Health: 在框顶上画一条血条。
                // 血条是屏幕空间 (s[0]/gsf 是逻辑坐标), 同样得自己压 2D 投影 —— 见 drawScreenBox 的说明。
                if (showHp && mDrawRect != null && mAabbMinX != null) {
                    // 这里原来直接读 fPosX(ent), 没插值 —— Show Health 的血条同样会跟着漂,
                    // 和框本体对不上。一律走 entX/entY/entZ (见那里的说明)。
                    float[] s = worldToScreen(
                            entX(ent, pt), entY(ent, pt) + mGetBBHeight(ent) + 0.25,
                            entZ(ent, pt), pt, mc, player, vx, vy, vz);
                    if (s != null) {
                        float gsf = GS <= 0 ? 1f : GS;
                        int bx = Math.round(s[0] / gsf) - 12;
                        int by = Math.round(s[1] / gsf);
                        float hp = ((Number) mGetHealth.invoke(ent)).floatValue();
                        float max = mGetMaxHealth == null ? 20.0f : ((Number) mGetMaxHealth.invoke(ent)).floatValue();
                        if (max <= 0.0f) max = 20.0f;
                        int w = Math.max(1, Math.round(24 * Math.max(0.0f, Math.min(1.0f, hp / max))));
                        pushLogicalProjection();
                        try {
                            mDrawRect.invoke(null, bx, by, bx + 24, by + 3, 0xAA101014);
                            mDrawRect.invoke(null, bx, by, bx + w, by + 3, 0xFF3AD07A);
                        } finally {
                            popPhysicalProjection();
                        }
                    }
                }
            }
        } finally {
            setLineWidth(1.0f);
            long nowD = System.currentTimeMillis();
            if (nowD - espDbgAt >= 1000) {
                espDbgAt = nowD;
                log("ESP 诊断: mode=" + mode + " range=" + range + " 视野内实体=" + dbgSeen
                        + " 距离内=" + dbgRange + " 已画=" + dbgDrawn
                        + " | 句柄 drawRect=" + (mDrawRect != null) + " drawBox=" + (mDrawBox != null)
                        + " aabb=" + (mAabbMinX != null) + " getBB=" + (mGetBB != null)
                        + " offset=" + (mAabbOffset != null)
                        + " | viewer(" + Math.round(vx) + "," + Math.round(vy) + "," + Math.round(vz) + ")"
                        + " 玩家(" + Math.round(px) + "," + Math.round(py) + "," + Math.round(pz) + ")");
            }
        }
    }

    /** 实体身高 (用包围盒算, 不依赖任何额外字段) */
    private static double mGetBBHeight(Object ent) {
        try {
            Object bb = mGetBB.invoke(ent);
            return mAabbMaxY.getDouble(bb) - mAabbMinY.getDouble(bb);
        } catch (Throwable t) {
            return 1.8;
        }
    }

    /**
     * 储物透视: 容器是方块实体 (TileEntity), 不在 loadedEntityList 里。
     * 之前遍历实体列表找 "Chest", 永远找不到 -> 功能看起来是死的。
     * 参数: 6=Mode 7=Ender Chests 8=Shulkers 9=Hoppers
     */
    private static void renderStorageBoxes(Object player, double vx, double vy, double vz) throws Exception {
        if (fLoadedTileEntities == null || mGetPos == null || mVecGetX == null || ctorAABB == null) return;
        Object world = worldOf(player);
        if (world == null) return;
        Object list = fLoadedTileEntities.get(world);
        if (!(list instanceof java.util.List)) return;

        double range = p(F_STORAGEESP, 2);
        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
        float r = (float) p(F_STORAGEESP, 3) / 255f, g = (float) p(F_STORAGEESP, 4) / 255f, b = (float) p(F_STORAGEESP, 5) / 255f;
        int rgb = 0xFF000000 | ((int) p(F_STORAGEESP, 3) << 16) | ((int) p(F_STORAGEESP, 4) << 8) | (int) p(F_STORAGEESP, 5);
        boolean wantChest = p(F_STORAGEESP, 0) > 0;
        boolean wantFurnace = p(F_STORAGEESP, 1) > 0;
        boolean wantEnder = p(F_STORAGEESP, 7) > 0;
        boolean wantShulker = p(F_STORAGEESP, 8) > 0;
        boolean wantHopper = p(F_STORAGEESP, 9) > 0;
        int mode = (int) p(F_STORAGEESP, 6);          // 0=Box 1=Outline 2=2D Box
        Object mc = mGetMC.invoke(null);

        for (Object te : (java.util.List<?>) list) {
            if (te == null) continue;
            String cn = te.getClass().getSimpleName();
            // Ender Chest 是 Chest 的子类名, 必须先判它 —— 否则关掉普通箱子时会把末影箱也一起关掉
            boolean isEnder = cn.contains("EnderChest");
            boolean want;
            if (isEnder) want = wantEnder;
            else if (cn.contains("Chest")) want = wantChest;
            else if (cn.contains("ShulkerBox")) want = wantShulker;
            else if (cn.contains("Hopper")) want = wantHopper;
            else if (cn.contains("Furnace")) want = wantFurnace;
            else want = wantFurnace && (cn.contains("Dispenser") || cn.contains("BrewingStand"));
            if (!want) continue;

            Object bp = mGetPos.invoke(te);
            if (bp == null) continue;
            int bx = (Integer) mVecGetX.invoke(bp), by = (Integer) mVecGetY.invoke(bp), bz = (Integer) mVecGetZ.invoke(bp);
            if (Math.abs(bx + 0.5 - px) > range || Math.abs(by + 0.5 - py) > range || Math.abs(bz + 0.5 - pz) > range) continue;

            // Outline: 外扩一点, 只留棱边 (renderFilledBox 画的是实心, 这里用 drawBoundingBox 的细框代替)
            double pad = mode == 1 ? 0.02 : 0.0;
            Object bb = ctorAABB.newInstance(bx - pad, by - pad, bz - pad,
                    bx + 1.0 + pad, by + 1.0 + pad, bz + 1.0 + pad);
            if (mode == 2) {
                drawScreenBox(bb, 1.0f, mc, player, vx, vy, vz, rgb, 1, false, false);
            } else {
                // Fill 走屏幕空间 (thickness=0 = 只填充不描边): 3D 的 renderFilledBox
                // 1.8.9 上没有, 而 2D 这条两个版本都能画。
                if (mode == 0) drawScreenBox(bb, 1.0f, mc, player, vx, vy, vz, rgb, 0, false, true);
                drawWorldBox(bb, vx, vy, vz, r, g, b);
            }
        }
    }

    /** 物品透视: 掉落物是实体, 这个走 loadedEntityList 是对的。参数: 4=Mode 5=Show Name */
    private static void renderItemBoxes(float pt, Object player, double vx, double vy, double vz) throws Exception {
        Object world = worldOf(player);
        if (world == null) return;
        double range = p(F_ITEMESP, 0);
        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
        float r = (float) p(F_ITEMESP, 1) / 255f, g = (float) p(F_ITEMESP, 2) / 255f, b = (float) p(F_ITEMESP, 3) / 255f;
        int rgb = 0xFF000000 | ((int) p(F_ITEMESP, 1) << 16) | ((int) p(F_ITEMESP, 2) << 8) | (int) p(F_ITEMESP, 3);
        int mode = (int) p(F_ITEMESP, 4);            // 0=Box 1=Outline
        boolean showName = p(F_ITEMESP, 5) > 0;
        Object mc = mGetMC.invoke(null);
        for (Object e : entities(world)) {
            if (e == null) continue;
            if (!e.getClass().getSimpleName().contains("EntityItem")) continue;
            if (EntityExclusions.contains(e)) continue;
            double ex = entX(e, pt), ey = entY(e, pt), ez = entZ(e, pt);
            if (Math.abs(ex - px) > range || Math.abs(ey - py) > range || Math.abs(ez - pz) > range) continue;
            Object bb = mGetBB.invoke(e);
            if (bb != null && mAabbOffset != null) {
                bb = mAabbOffset.invoke(bb,
                        ex - fPosX.getDouble(e),
                        ey - fPosY.getDouble(e),
                        ez - fPosZ.getDouble(e));
            }
            // Outline: 外扩一点, 画在框外侧而不是贴着 —— 看起来像描边而不是实框
            if (mode == 1 && mAabbGrow != null) bb = mAabbGrow.invoke(bb, 0.12, 0.12, 0.12);
            drawWorldBox(bb, vx, vy, vz, r, g, b);

            // Show Name: 把物品名画在物品上方
            if (showName && mDrawString != null && mAabbMaxY != null) {
                String nm = itemDisplayName(e);
                if (nm != null && !nm.isEmpty()) {
                    // worldToScreen consumes absolute world coordinates and
                    // applies the captured origin exactly once.  The old call
                    // pre-subtracted vx/vy/vz and then subtracted again inside
                    // projection, so item labels drifted with camera movement.
                    float[] s = worldToScreen(ex,
                            ey + (mAabbMaxY.getDouble(bb) - mAabbMinY.getDouble(bb)) + 0.3,
                            ez, pt, mc, player, vx, vy, vz);
                    if (s != null) {
                        float gsf = GS <= 0 ? 1f : GS;
                        int tx = Math.round(s[0] / gsf), ty = Math.round(s[1] / gsf);
                        // 文字没有描边选项, 先画一层暗底再写字, 否则浅色背景上看不清。
                        // 这里也是屏幕空间, 同样要自己压 2D 投影 —— 见 drawScreenBox 的说明。
                        pushLogicalProjection();
                        try {
                            OverlayFont.logicalPixels=gsf;
                            softGlow(tx - 8, ty - 5, textWLogical(nm) + 16, LABEL_ROW_L + 10, overlayColor(rgb, 0.6), bloom * 0.3f*widgetGlow(F_ITEMESP));
                            roundedRect(tx - 3, ty - 2, tx + textWLogical(nm) + 3, ty - 2 + LABEL_ROW_L, 3, fade(0xFF24192D,backgroundOpacity(F_ITEMESP)));
                            textLogical(nm, tx, ty, blend(overlayColor(rgb, 0.6), 0xFFFFFFFF, 0.45f));
                        } finally {
                            OverlayFont.logicalPixels=1;
                            popPhysicalProjection();
                        }
                    }
                }
            }
        }
    }

    /** 掉落物的显示名。getDisplayName 直接返回 String, 取不到返回 null。 */
    private static String itemDisplayName(Object entityItem) {
        try {
            Object stack = mGetItemStack.invoke(entityItem);
            if (stack == null) return null;
            Object name = mGetStackName.invoke(stack);
            return name == null ? null : name.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 方块轮廓: 给准星正对着的方块画框。参数: 4=Width */
    private static void renderBlockOutline(Object player, double vx, double vy, double vz) throws Exception {
        if (fObjectMouseOver == null || mGetBlockPos == null || ctorAABB == null
                || mVecGetX == null || fHitType == null) return;
        Object hit = fObjectMouseOver.get(mGetMC.invoke(null));
        if (hit == null) return;
        Object type = fHitType.get(hit);
        if (!(type instanceof Enum) || !"BLOCK".equals(((Enum<?>) type).name())) return;
        Object bp = mGetBlockPos.invoke(hit);
        if (bp == null) return;

        int bx = (Integer) mVecGetX.invoke(bp), by = (Integer) mVecGetY.invoke(bp), bz = (Integer) mVecGetZ.invoke(bp);
        double range = p(F_BLOCKOUTLINE, 3);
        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
        if (Math.abs(bx + 0.5 - px) > range || Math.abs(by + 0.5 - py) > range || Math.abs(bz + 0.5 - pz) > range) return;

        float r = (float) p(F_BLOCKOUTLINE, 0) / 255f, g = (float) p(F_BLOCKOUTLINE, 1) / 255f, b = (float) p(F_BLOCKOUTLINE, 2) / 255f;
        double pad = 0.002;   // 稍微外扩, 免得和方块表面 z-fighting
        Object bb = ctorAABB.newInstance(bx - pad, by - pad, bz - pad,
                bx + 1 + pad, by + 1 + pad, bz + 1 + pad);
        // 线宽设置统一走 setLineWidth: 1.8.9 上 GlStateManager 没有 glLineWidth,
        // 原来 if (mLineWidth != null) 直接落到 else, Width 设置静默失效。
        setLineWidth((float) p(F_BLOCKOUTLINE, 4));
        try { drawWorldBox(bb, vx, vy, vz, r, g, b); }
        finally { setLineWidth(1.0f); }
    }

    /**
     * 视距扩展。参数: 0=Multiplier 1=Mode
     *
     * Mode 原来是 {"Camera","Fog","Both"} —— 但 1.12.2 里雾的远平面就是由渲染距离算出来的
     * (farPlaneDistance = renderDistanceChunks * 16), 没有第二个旋钮, 那三个选项里有两个是空头支票。
     * 改成三个真能区分的上限:
     *   Vanilla —— 夹到原版滑块上限 32
     *   Max     —— 夹到 64 (超出原版滑块范围, 客户端照样渲染, 但很吃性能)
     *   Custom  —— 完全不夹, 只看 Multiplier
     *
     * 只在开启时改, 关掉要还原成原值 —— 每帧乘一次会指数级涨上去。
     */
    private static int origRenderDist = -1;

    private static void doViewClip(Object mc) throws Exception {
        if (fGameSettings == null || fRenderDistance == null) return;
        Object gs = fGameSettings.get(mc);
        if (gs == null) return;
        if (origRenderDist < 0) origRenderDist = fRenderDistance.getInt(gs);
        int cap = (int) p(F_VIEWCLIP, 1) == 1 ? 64 : 32;
        int want = (int) Math.round(origRenderDist * p(F_VIEWCLIP, 0));
        if ((int) p(F_VIEWCLIP, 1) != 2) want = Math.min(cap, want);
        want = Math.max(2, want);                      // 低于 2 会让世界不渲染, 直接黑屏
        if (fRenderDistance.getInt(gs) != want) fRenderDistance.setInt(gs, want);
    }

    /** 关闭视距扩展时还原原值 */
    private static void restoreViewClip(Object mc) throws Exception {
        if (origRenderDist < 0 || fGameSettings == null || fRenderDistance == null) return;
        Object gs = fGameSettings.get(mc);
        if (gs != null && fRenderDistance.getInt(gs) != origRenderDist) {
            fRenderDistance.setInt(gs, origRenderDist);
        }
        origRenderDist = -1;
    }

    /**
     * 功能从"开"变"关"时, 还原它改过、而原版不会自己恢复的状态。
     *
     * 只处理持久状态。速度/位置类不用管 —— 它们每 tick 都被原版重算, 关了自然就没了。
     * 以前这几项全都没还原, 所以 Flight/Step/Timer/FullBright 一旦开过就关不掉。
     */
    private static void restoreOff(int i, Object mc, Object player) throws Exception {
        if (i == F_FLIGHT) {
            Object caps = fCapPlayer.get(player);
            if (caps != null) {
                fCapIsFlying.setBoolean(caps, false);
                // 创造/旁观下 allowFlying 本来就该是 true —— 别把人家正常的飞行也关掉
                if (fCapIsCreative != null && !fCapIsCreative.getBoolean(caps)
                        && fCapAllowFlying != null) {
                    fCapAllowFlying.setBoolean(caps, false);
                }
            }
        } else if (i == F_STEP) {
            if (fStepHeight != null) fStepHeight.setFloat(player, 0.6f);   // 原版台阶高
        } else if (i == F_SPEED) {
            releaseSpeedTimer(mc);
        } else if (i == F_HITBOX) {
            restoreHitBoxes();
        } else if (i == F_TIMER) {
            if (fTimer != null && fTickLength != null) {
                setTimerSpeed(fTimer.get(mc), 1.0);                         // 原版 20 TPS
            }
        } else if (i == F_KILLAURA) {
            clearCombatAim();
        } else if (i == F_INVMOVE || i == F_FREECAM) {
            releaseInventoryMoveInputs();
        } else if (i == F_SCAFFOLD) {
            resetScaffoldJump();
            releaseScaffoldKey();
            clearScaffoldPlacementLock();
            clearScaffoldPose();
            lastScaffold = 0L;
            scaffoldHeadingReady=false; scaffoldSlotTick=Integer.MIN_VALUE; scaffoldStatus="Idle";
            // Keep Slot 的换手动作放在**关掉 Scaffold 的这一刻**, 而不是每放一块翻一次。
            // 搭路期间一直握着方块 (更像真人, 也不会自己和自己抢槽位 —— 见 doScaffold 末尾的注释)。
            if (p(F_SCAFFOLD, 8) > 0 && scaffoldPrevSlot >= 0
                    && fInvPlayer != null && fCurrentItem != null) {
                Object inv = fInvPlayer.get(player);
                if (inv != null) fCurrentItem.setInt(inv, scaffoldPrevSlot);
            }
            scaffoldPrevSlot = -1;
            // 姿势是"开启自动搭路期间常驻", 所以关掉的时候要解除, 否则模型会一直保持
            // 最后那个搭路姿势 (身体朝后低头)。
        } else if (i == F_FULLBRIGHT) {
            restoreGamma();
        } else if (i == F_REACH) {
            // 不还原的话 ASM 补丁会一直把距离锁在上次设的值上 —— 关了还在打远处, 很显眼。
            // 只还原实体距离; 方块距离本来就是恒定的原版值, 不用管 (见 blockReach)。
            entityReachOverride = 4.5f;
        } else if (i == F_NOHURTCAM) {
            // No Bob 关掉了玩家的视角摇晃设置, 得还回去 —— 那是玩家自己的设置, 不是我们的
            restoreBobbing();
        } else if (i == F_TIMECHANGE) {
            resetTimeChanger();
        }
    }

    // ===== AZclient 暗色 UI (v4) =====
    //
    // 两套单位, 别搞混:
    //   带 _L 后缀的常量 = 设计基准, 逻辑单位, 永不改变
    //   不带后缀的字段   = 物理像素, 每次 layoutFor 里 = 逻辑 * S
    //
    // 为什么要全部换成物理像素:
    //   MC 的 GUI 坐标被 guiScale 量化, 1 逻辑单位 = S 个物理像素, 所以任何细节
    //   最小只能到 S 像素 —— 圆角是 S 像素一级的台阶, 描边是 S 像素粗的块, 发丝线
    //   根本画不出来。实测用户截图: 开关滑块 10 逻辑单位 = 40 物理像素 => S=4,
    //   也就是说 1 像素的圆角弧线在这里变成了 4 像素的锯齿。
    //   改成在物理像素空间绘制 (自建 ortho 投影, 1 单位 = 1 物理像素) 之后,
    //   圆角能真正平滑、描边能到 1 像素。这就是 Vape 那类客户端清晰的根本原因 ——
    //   它们不跟 MC 的 GUI 缩放格走。
    // ===== AZclient UI (v5, Vape 风格版面) =====
    //
    // 单位约定:
    //   带 _L 后缀 = 设计基准, UI 单位
    //   不带后缀   = 物理像素 = UI 单位 * S
    //
    // S 是 UI 自己的缩放, 刻意不取 MC 的 guiScale。理由:
    //   guiScale 是给 MC 原生 GUI 用的 (实测机器上自动值是 4), 拿它当 UI 缩放等于
    //   把整个面板放大 4 倍 —— 实测面板占 86% 屏宽 (1460/1705), 正文字高 60px。
    //   Vape 那类客户端不看 guiScale, 直接在物理像素上排版: 面板约 1/3 屏宽、字高 15px。
    //   所以"分辨率不是一个量级"的根因是尺寸, 不是清晰度。
    // 版面按 Vape 结构: 顶部标题+搜索框, 左侧分类竖栏, 右侧功能列表, 行内展开参数。
    private static final int PX_L = 24, PY_L = 20;
    private static final int PANEL_W_L = 520, PANEL_W_MIN_L = 360, PANEL_H_L = 340;
    private static final int RAIL_W_L = 96;        // 左侧分类竖栏宽
    private static final int HEADER_H_L = 34;      // 标题 + 搜索框
    private static final int FOOT_H_L = 20;        // 底栏
    private static final int ROW_NAME_L = 22;      // 功能名行高
    private static final int ROW_PARAM_L = 18;     // 参数行高
    /**
     * 世界空间那几处文字底板的行高 —— 名字标签 / ESP 物品名的底板用它。
     *
     * **单位是逻辑单位, 不乘 S**, 和那个投影里的坐标 (`ty - 2` 之类) 一样。
     * 别拿 ROW_NAME 顶替: 那是面板卡片的行高 (22*S 物理像素空间), 而这里的字墨迹只有
     * 原版一行的高度, 按 22*S 算底板会高出一倍多 —— 原来正是这么写的。
     *
     * 15 = 墨迹 9 + 上下各留一点。文字画在格顶 (ty), 墨迹落在格内 3..21 的比例上,
     * 缩完大约 ty+1.4 .. ty+10, 所以 ty-2 .. ty+13 差不多正好居中。
     */
    private static final int LABEL_ROW_L = 15;
    private static final int CARD_PAD_L = 3;       // 卡片上下留白
    private static final int RAIL_ITEM_H_L = 22;   // 竖栏分类项高
    private static final int RAIL_ITEM_GAP_L = 2;
    private static final int AC_ITEM_H_L = 17;     // 反作弊预设项高
    private static final int AC_ITEM_GAP_L = 3;
    private static final int AC_LABEL_H_L = 16;
    private static final int SEARCH_W_L = 170, SEARCH_H_L = 20;
    /** 正文墨迹视觉中心：17pt 双倍采样后实测区间 [3,23)。 */
    // 改字号或抗锯齿必须重跑 InkMeasure，保持文字居中与裁剪范围一致。
    private static final int INK_TOP_L = 3, INK_BOT_L = 23;
    private static final int INK_CENTER_L = 13;

    /** UI 缩放: 1 UI 单位 = S 个物理像素 */
    private static int S = 1;
    private static int physW = 1280, physH = 720;

    private static int PX, PY, PANEL_W, PANEL_H;
    private static int RAIL_W, HEADER_H, FOOT_H;
    private static int ROW_NAME, ROW_PARAM, CARD_PAD;
    private static int RAIL_ITEM_H, AC_ITEM_H;
    /** 正文/标题墨迹中心, 物理像素 */
    private static int INK_CENTER;

    static {
        // 先给一套完整的默认布局, 免得类初始化阶段 (自检/日志) 读到 0 尺寸
        layoutForFake(1280, 720, 1);
    }

    /**
     * UI 缩放。刻意不取 guiScale —— 见上面的说明。
     * 只在很大的屏幕上提一档, 免得 4K 下面板小得看不清。
     */
    private static int uiScale(int physHeight) {
        return physHeight >= 2000 ? 2 : 1;
    }

    // =====================================================================
    // 主题色
    //
    // 用户要求: "现在这个绿色主题的 gui 我希望可以改颜色, 再加个 rainbow 模式"。
    //
    // 做法是把原来的两个常量改成**方法**: 常量没法随参数变, 而散落在几十处的 ACCENT()
    // 引用要一个个改成方法调用才是真正的改法 —— 加一个 setAccent() 去改 static 字段
    // 会引入"谁改的、什么时候恢复"这类时序问题 (这套代码里已经吃过好几次亏了)。
    //
    // themeHue:   -1 = 用默认的青绿 (原色, 不经过色相旋转, 保证老样子一模一样)
    //             0..360 = 按这个色相旋转出强调色
    // rainbowMode: 打开时整个强调色随时间绕色相环转
    // =====================================================================
    private static int themeHue = -1;
    private static boolean rainbowMode = false;
    private static float glassOpacity = 0.88f;
    private static float glassGlow = 0.95f;
    private static int glassGlowRadius = 6;
    private static int glassBlurRadius = 12;
    private static float backdropTint = 0.72f;
    private static boolean sunsetPalette = true, hudFrost = true, sunsetOverlays = true;
    private static float flowSpeed = 1f, bloom = 0.70f, atmosphere = 0.55f;
    private static int bloomSpread = 24;
    private static boolean glassMotion = true;
    private static boolean interactionMotion = true;
    private static float interactionSpeed = 1f;

    /** 当前强调色的色相。rainbow 模式下随时间走 —— 每 40ms 一度, 大约 14 秒一圈 */
    private static float currentHue() {
        if (rainbowMode) return (float) ((System.currentTimeMillis() * (double) flowSpeed / 14000.0) % 1.0);
        if (themeHue < 0) return -1f;      // -1 = 用默认色, 不做 HSV 转换
        return themeHue / 360f;
    }

    /** 强调色 (不透明)。themeHue < 0 且非 rainbow 时返回原来的青绿 */
    private static int ACCENT() {
        if (sunsetPalette) return sunset(0.12, rainbowMode);
        float h = currentHue();
        if (h < 0f) return 0xFF00C9A7;
        java.awt.Color c = java.awt.Color.getHSBColor(h, 0.85f, 0.88f);
        return 0xFF000000 | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    /** 强调色 (60% 透明) —— 滑轨填充和输入框顶边用 */
    private static int ACCENT_DIM() {
        if (sunsetPalette) return fade(sunset(0.12, rainbowMode), 0.6f);
        float h = currentHue();
        if (h < 0f) return 0x9900C9A7;
        java.awt.Color c = java.awt.Color.getHSBColor(h, 0.85f, 0.88f);
        return 0x99000000 | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
    }

    /**
     * 主题色相由这两个参数驱动 (见下面 setThemeFromParams)。
     *
     * 参数化的入口是 Settings 列里的 "Theme Hue" / "Rainbow" —— 它们不挂在某个功能上,
     * 而是每次渲染面板前读一次, 所以面板一改颜色就是立即生效的。
     */
    private static void setThemeFromParams() {
        themeHue = (int) p(F_THEME, 0);
        rainbowMode = p(F_THEME, 1) > 0;
        glassOpacity = (float) Math.max(0.35, Math.min(0.95, p(F_THEME, 2) / 100.0));
        glassGlow = (float) Math.max(0.0, Math.min(1.0, p(F_THEME, 3) / 100.0));
        glassGlowRadius = Math.max(0, Math.min(8, (int) p(F_THEME, 4)));
        glassMotion = p(F_THEME, 5) > 0;
        glassBlurRadius = Math.max(2, Math.min(24, (int) p(F_THEME, 6)));
        backdropTint = (float) Math.max(0.0, Math.min(1.0, p(F_THEME, 7) / 100.0));
        sunsetPalette = p(F_THEME, 14) == 0;
        flowSpeed = (float) p(F_THEME, 15);
        bloom = (float) (p(F_THEME, 16) / 100.0) * glassGlow;
        bloomSpread = Math.round((float) p(F_THEME, 17) * glassGlowRadius / 6f);
        hudFrost = p(F_THEME, 18) > 0;
        sunsetOverlays = p(F_THEME, 19) == 0;
        atmosphere = (float) (p(F_THEME, 20) / 100.0);
        interactionMotion = p(F_THEME, 21) > 0;
        interactionSpeed = (float) p(F_THEME, 22);
    }

    // Text is kept nearly neutral so the accent remains a hierarchy cue.
    // The old pale-cyan palette had too little value separation on the glass.
    private static final int TXT_MAIN = 0xFFF4F6FC;
    private static final int TXT_SUB = 0xFFC8D3E4;
    private static final int TXT_ON = 0xFFA5F8DB;
    private static final int TXT_WARN = 0xFFFFB454;
    /** 说明文字: 比 TXT_SUB 再淡一档, 一眼看出是帮助而不是可点的条目 */
    private static final int TXT_HINT = 0xFF8D9BB1;
    private static final int TRACK = 0x88202C3C;
    private static final int CLIP_BG = 0xFF0B1119;
    private static final int CARD_BG = 0xF20E1725;
    private static final int CARD_BG_HOVER = 0xF218293A;
    private static final int GLASS_BLUE = 0xFF7187FF;

    // 分类 / 滚动状态
    private static int scroll = 0;
    private static int lastWheel = 0;

    // ===== 动画状态 =====
    private static long lastFrameMs = 0;
    private static float openAnim = 0f;
    private static float[] hoverAnim = new float[0];
    private static float[] toggleAnim = new float[0];
    /** 卡片展开状态: 收起时只显示名称/开关/热键, 点名称展开参数 */
    private static boolean[] expanded = new boolean[0];
    /** 上一帧各功能的开关状态, 用来捕捉 开->关 的那一瞬间 */
    private static boolean[] wasOn = new boolean[0];

    private static float dt() {
        long n = System.currentTimeMillis();
        float d = (lastFrameMs == 0) ? 0.016f : (n - lastFrameMs) / 1000f;
        lastFrameMs = n;
        if (d < 0f) d = 0f;
        if (d > 0.1f) d = 0.1f;
        return d;
    }

    /** 指数趋近, 帧率无关 */
    private static float anim(float cur, float target, float speed, float d) {
        return cur + (target - cur) * (1f - (float)Math.exp(-speed * d));
    }

    private static void ensureAnimArrays() {
        if (hoverAnim.length != mods.length) {
            hoverAnim = new float[mods.length];
            toggleAnim = new float[mods.length];
            expanded = new boolean[mods.length];
        }
        // wasOn 必须单独判, 而且必须在这里补上 —— 它是 onFrame 用的 (捕捉 开->关 那一帧),
        // 而 onFrame 在用户开 GUI 之前就一直在跑。
        // 之前只声明成 new boolean[0] 却没分配大小, 结果 onFrame 每帧在 wasOn[0] 上抛
        // ArrayIndexOutOfBoundsException: 0 —— 异常发生在模块调度之前, 于是**所有功能全部不执行**,
        // 日志里只看到刷屏的 "onFrame FAILED", 完全看不出是这里。
        if (wasOn.length != mods.length) {
            boolean[] nw = new boolean[mods.length];
            System.arraycopy(wasOn, 0, nw, 0, Math.min(wasOn.length, nw.length));
            wasOn = nw;
        }
    }

    // ===== 文字: 优先 TTF, 不可用时回退 MC 原生字体 =====
    private static boolean fontTried = false;
    private static int fontScale = -1;   // 图集是按哪个 GUI 缩放光栅化的
    private static String fontCharset = "";

    private static final TtfFont.Fallback FONT_FB = new TtfFont.Fallback() {
        public int width(String s) { return mcTextW(s); }
        public int drawChar(char c, int x, int y, int argb) {
            String s = String.valueOf(c);
            mcText(s, x, y, argb);
            return x + mcTextW(s);
        }
    };

    private static Object mcFont() {
        try { return fFont.get(mGetMC.invoke(null)); } catch (Throwable t) { return null; }
    }

    /**
     * MC 内置字体: 只在 TTF 图集没建起来时才走到这里 (见 useTtf)。
     *
     * 字号**不缩放**。以前为了让中文和 TTF 一样高, 这里压过一层 scale(2.5) —— 中文模式
     * 已经删掉了, 界面全英文走 TTF, 这条路只剩"图集没建起来"的兜底, 按 MC 原样画才是对的。
     */
    private static void mcText(String s, int x, int y, int argb) {
        try {
            Object f = mcFont();
            if (f == null) return;
            mDrawString.invoke(f, s, (float) x, (float) y, argb);
        } catch (Throwable ignored) { }
    }

    private static int mcTextW(String s) {
        try {
            Object f = mcFont();
            if (f == null) return s.length() * 6;
            return (Integer) mGetStrW.invoke(f, s);
        } catch (Throwable t) { return s.length() * 6; }
    }

    /** 需要烘焙进字体图集的全部字符 (纯字符串处理, 可以脱离游戏自检) */
    static String buildCharset() {
        StringBuilder sb = new StringBuilder();
        for (char c = 32; c < 127; c++) sb.append(c);   // 界面全英文后, 可打印 ASCII 就够
        // 版本号 / 模板名 / 分类名 / 标签都从真正的数据源取, 不要在这里手写一份。
        // 之前这里硬编码的是 "AZCLIENT v5.0" 和 "ANTICHEAT" —— 版本早就到 v6.2、模板名也换成了 OFF,
        // 那个手写串和真实数据各说各话。缺字不会崩, 只会画出空白 —— 以后往界面加新文案
        // (尤其非 ASCII 的) 记得也在这里收一次。
        TtfFont.collect(sb, VERSION);
        for (String s : AC_NAMES) TtfFont.collect(sb, s);
        for (String s : CAT_NAMES) TtfFont.collect(sb, s);
        // 界面固定文案 (底栏提示、列表表头、模式标签)
        TtfFont.collect(sb, "OFF ON SETTINGS TEMPLATES Search Press Bind Value None");
        // 底栏/列表用到的非 ASCII 符号, 不显式收进来会缺字
        TtfFont.collect(sb, "·▲▼↑↓功能已开启关闭");
        // **中文文案。** 这块以前漏了 —— 结果所有 setToast 的中文提示 (存配置/导入/分享)
        // 在图集里没有对应字形, 画出来是空白或者错位的碎片, 用户看到的就是"乱七八糟看不清"。
        // 界面功能名是英文, 但提示语一直是中文, 所以中文必须一起烘焙。
        TtfFont.collect(sb, TOAST_CHARS);
        for (Mod m : mods) {
            TtfFont.collect(sb, m.name);
            if (m.tag != null) TtfFont.collect(sb, m.tag);
            for (P pp : m.ps) {
                TtfFont.collect(sb, pp.n);
                // 模式参数的选项名是要显示出来的 —— 不收进去就画成空白
                if (pp.opts != null) for (String o : pp.opts) TtfFont.collect(sb, o);
            }
        }
        TtfFont.collect(sb, cfgName);
        for (String name : cfgNames) TtfFont.collect(sb, name);
        return sb.toString();
    }

    /**
     * 所有会显示出来的中文文案的**字符集合**。
     *
     * <p>由来: 提示语全是中文, 而字体图集原来只烘焙了 ASCII —— 中文提示画出来是烂的。
     * 与其在 buildCharset 里手打一遍 (那样每加一句提示就要回来补字, 迟早漏),
     * 不如**把真正会显示的句子列在这儿**, 用 TtfFont.collect 自动取字。
     *
     * <p>下面这些串必须与 text(...) / setToast(...) 里实际用的文案保持一致 ——
     * ToastChars 探针会把源码里所有显示用中文抠出来逐字比对, 漏了构建期就报出来,
     * 而不是等用户看见乱码。
     *
     * <p>容量: 图集按缩放 4 算有 324 个格子, ASCII 等占 100, 这里 118 个汉字, 合计 218,
     * 装得下。**别往这里加超出提示语的东西** —— 满 324 之后多出来的字会退回 MC 点阵字体,
     * 中英混排会明显难看 (当年"中文界面装不下"就是撞的这面墙: 那是要几千个字)。
     */
    static final String[] TOAST_LINES = {
            "功能已开启", "功能已关闭", "已排除目标: ", "已恢复目标: ",
            // 保存
            "导出失败: ", "找不到服务器地址, 用 Export Config 走离线串",
            "没有登录令牌, 重新登录一次再存", "正在保存 ",
            "已存到账号: ", ", 点 My Configs 查看", "登录令牌已失效, 重新登录一次",
            "保存失败: ", "保存失败 (网络不通?): ",
            // 列表
            "没有登录令牌, 重新登录一次再看列表", "正在读取列表...",
            "读取列表失败: ", "读取列表失败 (网络不通?): ",
            // 载入 / 删除
            "正在载入 ", "载入失败: ", "载入失败 (网络不通?): ",
            "正在删除 ", "已删除 ", "删除失败: ", "删除失败 (网络不通?): ",
            // 分享
            "先输入分享码", "这不像分享码 (6 位), 也不像 AZC2 开头的离线串",
            "找不到服务器地址, 无法上传 (可以用 Export Config 走离线串)",
            "没有登录令牌, 重新登录一次再上传", "正在上传 ",
            " 字符...", "分享码 ", " (已复制)", "分享码是 ",
            "上传失败: ", "上传失败 (网络不通?): ",
            "找不到服务器地址, 无法下载", "正在下载 ",
            " 不存在, 检查一下有没有抄错", "下载失败: ", "下载失败 (网络不通?): ",
            "操作太快, 等一分钟再试",
            // 剪贴板 / 导入
            "读剪贴板失败: ", "剪贴板是空的", "导入失败: ",
            "已复制到剪贴板", "复制失败: ",
            // 账号 / 通用
            "登录成功: ", "注册成功, 本机已绑定: ", "已退出登录: ",
            "没有登录会话",
            // 默认配置名
            "配置 ",
            // 剪贴板 / 导出
            "已复制 ", " 字符, 粘贴给对方即可", "写剪贴板失败: ",
            "剪贴板里没有分享码 (是 AZC2... 离线串的话直接解不了, 长度 ",
            "剪贴板里的分享码只有 ", " 位 (要 6 位): ",
            "先生成一个分享码 (Upload mine)",
            // 热键
            " 热键已清空",
            // 存档没了
            "这条存档已经不在了, 重新展开一次列表", "这条存档已经不在了, 刷新一下列表",
            // 启动恢复
            "已恢复上次退出时的设置",
            "配置恢复失败，已保留原设置",
            // UI 里会出现的中文 (右下角提示等)
            "展开", "收起", "搜索", "绑定", "无",
    };

    /** TOAST_LINES 拼成的字符池, 供 TtfFont.collect 用 */
    private static final String TOAST_CHARS = joinToast();
    private static String joinToast() {
        StringBuilder sb = new StringBuilder();
        for (String s : TOAST_LINES) sb.append(s);
        return sb.toString();
    }

    /**
     * 界面里会出现的一切文字标签。buildCharset 收字用, selfTest 也用它验缺字 ——
     * 两边用同一份数据, 就不会出现"图集里没有但界面上要显示"的空白字。
     */
    static java.util.List<String> fontLabels() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        out.add(VERSION);
        out.add("OFF ON SETTINGS TEMPLATES Search Press Bind Value None ·▲▼↑↓");
        for (String s : AC_NAMES) out.add(s);
        for (String s : CAT_NAMES) out.add(s);
        for (Mod m : mods) {
            out.add(m.name);
            if (m.tag != null) out.add(m.tag);
            for (P pp : m.ps) {
                out.add(pp.n);
                if (pp.opts != null) for (String o : pp.opts) out.add(o);
            }
        }
        return out;
    }

    /**
     * 图集需要在游戏内构建 (要 MC 的 TextureManager), 所以第一次开面板时才初始化。
     * 每次开面板都过一遍 ensureScale: 缩放变了就按新缩放重建图集, 否则字形会被重新采样 -> 又糊。
     *
     * **这个值必须是 S, 不能随便调大。**
     *
     * 我上一版把它改成 2 (想解决 "HUD 字小"), 结果是整个 GUI 的字全部叠在一起 ——
     * 因为 TtfFont 的绘制尺寸是 `drawCell(texCell, k)`, 而 texCell = cell * scale,
     * 直接把 scale 翻倍就等于把**所有字形在屏幕上的尺寸**翻倍, 不是提高清晰度。
     * 面板的列宽/行高是按原来的字号算的, 字一大就互相压住。
     *
     * 结论: 想放大字, 要用 textScaled(s,x,y,argb,k) 传 k, 那是**绘制尺寸**;
     * 这个 scale 只管光栅化精细度, 而且放大它就等于放大字。HUD 的字号走自己的参数。
     */
    private static void initFont() {
        int scale = S;
        String charset = buildCharset();
        if (fontTried && scale == fontScale && charset.equals(fontCharset)) return;
        fontTried = true;
        fontScale = scale;
        fontCharset = charset;
        TtfFont.ensureScale(charset, FONT_FB, scale);
    }

    /**
     * 界面文字只有英文。**中文模式已经删掉了。**
     *
     * 原因记在这儿, 免得以后又有人想加回来: 界面走的是一张**预烘焙的 TTF 图集**
     * (见 buildCharset, 只收 100 个 ASCII 字形), 汉字根本不在里面; 而图集边长上限
     * MAX_ATLAS=2048, guiScale=4 时一个格子就是 27*4=108px, 现有的 100 个字已经顶满 ——
     * 再塞几百个汉字装不下。当时只能让中文回退到 MC 内置字体, 于是中英两套字体混在一起,
     * 字号/字宽/基线全不一样, 又要为它加一层放大和一套按语言自适应的列宽。
     * 直接不做中文界面, 这些复杂度就都不存在了。
     */
    private static boolean useTtf() { return TtfFont.ready(); }

    private static void text(String s, int x, int y, int argb) {
        if (OverlayFont.active()) { OverlayFont.draw(s,x,y,argb,17*S); return; }
        if (useTtf()) TtfFont.draw(s, x, y, argb);
        else mcText(s, x, y, argb);
    }

    private static int textW(String s) {
        if (OverlayFont.active()) return OverlayFont.width(s,17*S);
        return useTtf() ? TtfFont.width(s) : mcTextW(s);
    }

    /**
     * 带缩放的文字 (HUD 标题 / HUD 整体缩放用)。
     *
     * TTF 路径本身支持任意缩放 —— drawScaledCustomSizeModalRect 的 dest 尺寸是参数,
     * 所以只要按同一个系数同时放大"格子尺寸"和"步进"就行, 且宽度和绘制必须共用同一个系数,
     * 否则居中和底板宽度会对不上 (这是这套代码里反复踩的坑)。
     *
     * 缩放的是**字号**而不是整个坐标系 (GL 缩放那种做法): HUD 的坐标是物理像素, 用
     * glScale 会把位置一起放大, 框和字就不在同一个地方了。
     */
    private static void textScaled(String s, int x, int y, int argb, float k) {
        if (OverlayFont.active()) { OverlayFont.draw(s,x,y,argb,17*S*k); return; }
        if (k == 1.0f) { text(s, x, y, argb); return; }
        if (useTtf()) TtfFont.drawScaled(s, x, y, argb, k);
        else mcText(s, x, y, argb);       // 原生字体不支持缩放, 退回正常大小 (不崩)
    }

    private static void textGradient(String s,int x,int y,int first,int last,float scale){
        if(OverlayFont.active()){OverlayFont.gradient(s,x,y,first,last,17*S*scale);return;}
        if(useTtf()){TtfFont.drawGradient(s,x,y,first,last,scale);return;}
        int total=Math.max(1,mcTextW(s)),offset=0;
        for(int i=0;i<s.length();i++){String c=String.valueOf(s.charAt(i));int w=mcTextW(c);mcText(c,x+offset,y,blend(first,last,(offset+w*.5f)/total));offset+=w;}
    }
    private static int textWScaled(String s, float k) {
        if (OverlayFont.active()) return OverlayFont.width(s,17*S*k);
        if (k == 1.0f) return textW(s);
        if (useTtf()) return TtfFont.widthScaled(s, k);
        return mcTextW(s);
    }

    /**
     * 世界空间 (pushLogicalProjection) 里的文字: 名字标签 / ESP 物品名。
     *
     * **这几处必须用 textLogical/textWLogical, 不能用上面那两个。** 上面那套是"1 单位 =
     * 1 物理像素"的面板空间用的, 图集在那里一个纹素对一个像素; 换到逻辑单位投影里, 一个
     * 字形的墨迹就是 19 个单位, 而原版 MC 一行文字的墨迹只有 9 个单位 (FontRenderer
     * .FONT_HEIGHT) —— 大了一倍多, 而且 27 像素的图集格子被拉到 27 个逻辑单位 (= GS 倍
     * 物理像素), 又大又糊。这两个把它压回原版大小 (见 TtfFont.logicalScale)。
     *
     * 量宽度和画字走的是同一个系数, 所以居中和底板宽度不会对不上。
     * 回退到 MC 原生字体时**不要**再缩放: 原版字体本来就是 9 单位。
     */
    private static void textLogical(String s, int x, int y, int argb) {
        if (useTtf()) OverlayFont.logical(s, x, y, argb);
        else mcText(s, x, y, argb);
    }

    private static int textWLogical(String s) {
        return useTtf() ? OverlayFont.logicalWidth(s) : mcTextW(s);
    }

    /**
     * HUD: 关掉 GUI 正常游戏时, 把已开启的功能列在屏幕上。
     *
     * 位置用百分比 (0..100), 不是像素 —— 换分辨率/改窗口大小都不会跑到屏幕外。
     * 另外在 GUI 打开时可以拖着 HUD 移动 (见 clickAt 里的 hudDrag), 拖完自动换算回百分比。
     */
    // ===== 屏幕空间绘制: 线条 =====

    /**
     * 画一条任意方向的线。
     *
     * Gui.drawRect 只能画轴对齐矩形, 所以斜线得拆成一串小方块 —— 按较长的那一轴逐像素推进,
     * 每一步在另一个轴上按斜率补一格。步长取 1 逻辑像素: 更细没有意义 (物理像素已经放大了),
     * 更粗会看到明显的阶梯。
     */
    /**
     * 画一条屏幕线。坐标是**物理像素** —— 调用方必须已经在物理投影下 (1 单位 = 1 物理像素)。
     *
     * 旧实现有两层粗糙, 叠在一起就是"线很难看":
     *
     *  1) 沿 x 和 y **双向**每步各走 1, 每步盖一个 th×th 的方块。斜线时相邻两步的方块是
     *     错开的 —— 画出来是一串摞起来的方块, 也就是阶梯。而且每步的方块在次轴上多盖了
     *     th 像素, 粗线看着像一串珠子。
     *  2) 它在**逻辑单位**下跑 (1 单位 = guiScale 个物理像素)。guiScale=4 时 drawRect 的
     *     整数粒度是 4 个物理像素 —— 一条 4px 的线是按 4px 的格子拼的, 别说抗锯齿, 连
     *     斜线都是块状。
     *
     * 现在: 只沿**主轴**走, 每一步画一个矩形, 次轴范围覆盖这一步的起止点。相邻矩形的
     * 次轴区间是首尾相接的, 所以不会出现阶梯也不会出现缝; 再跑在物理像素下, 粒度就是
     * 1 个物理像素。代价只有 draws 次数, 而每次 drawRect 只是个填充矩形, 很便宜。
     */
    // =====================================================================
    // 配置分享小块
    //
    // 原来分享是一条条独立的行 (Export / Import / Share Online) 摊在设置列里,
    // 三行占着位置却只干一件事, 而且"分享码"本身没有落脚的地方 —— 生成完就只剩
    // 剪贴板, 想再看一眼都没处看。收成一块独立小面板: 上面显示自己的码, 下面输入别人的码。
    // =====================================================================

    /**
     * 旧的浮动分享小窗 —— **已停用**。
     *
     * <p>用户要求: "我要的是和 misc 之类的竖列同级的, 名字为 config 的列表, 不在这个框里"。
     * 分享/载入/列表现在都在 CONFIG 列里, 这个浮窗已经没有入口 (CFG_ROWS 里的
     * "Share / Load" 也删了)。
     *
     * <p>**保留字段和那几个函数**而不是删掉: 它们和 CONFIG 列共用同一批底层
     * (shareInputSubmit / pasteIntoShareInput / shareSavedConfig / fetchCfgList),
     * 删掉就得分清楚哪些是"浮窗专用"、哪些是共用的 —— 分错一个就是编译错误或者
     * 功能静默消失。现在只用这个开关把它关掉, 共享的部分照常工作。
     *
     * <p>置 false 之后 openShare() 会立刻改回 true, 所以干脆让它空转:
     * 见 openShare 的注释。
     */
    private static boolean shareOpen = false;
    private static String shareCodeInput = "";
    private static boolean shareInputFocused = false;
    /** 最近一次生成/读到的分享码。显示在小块里, 也用来点一下复制 */
    private static volatile String shareLastCode = null;

    /**
     * 分享/载入小窗。
     *
     * **这一版是重写的。** 用户反馈: "分享/载入配置界面文字有错位且不清晰, 分辨率非常低",
     * 还有 "到现在连个粘贴按钮都没做"。
     *
     * 上一版的问题:
     *   ① 布局是"每行各自算 y 偏移 + 按文字宽度摆按钮" —— 文案一改就错位,
     *      而且 footer 两个按钮的位置是按 textW 推出来的, 和点击判定各算一次。
     *   ② 全是光秃秃的文字, 没有按钮边框, 看不清哪里能点 —— "不清晰"大半是这个。
     *   ③ 粘贴只藏在 Ctrl+V 和 "Paste code" 一个角落里, 看起来像没有。
     *
     * 现在改成**统一的 Laid out 行**: 每行高度固定 (SH_ROW), 按钮画成实心药丸带边框,
     * 绘制和命中共用同一张行表 —— 不可能再错位。
     */
    static final int SHARE_W = 300;
    /** 行高 (逻辑单位)。所有内容按行排, 不再各算偏移 */
    private static final int SH_ROW = 22;
    private static final int SH_PAD = 10;
    private static final int SH_TITLE_H = 24;
    /** 窗口总高: 标题 + 5 行内容 + 底部两个按钮 + 内边距 */
    private static final int SHARE_H = SH_TITLE_H + SH_ROW * 5 + SH_ROW + SH_PAD * 2 + 6;

    /**
     * 小窗里每一行的 y (相对窗口顶部, 逻辑单位)。
     *
     * **绘制和点击都只读这里。** 上一版硬编码了 SH_CODE_Y / SH_GEN_Y / SH_LBL_Y / SH_IN_Y /
     * SH_FOOT_Y / SH_LIST_Y 六个常量, 加一行要同步改两处, 迟早错位 —— 已经错过一次了。
     */
    private static int shRowY(int row) { return SH_TITLE_H + SH_PAD + row * SH_ROW; }

    /** 行里的按钮矩形 (逻辑单位, 相对窗口左上角)。所有按钮等高同宽, 排成一行 */
    private static int[] shBtnRect(int row, int col, int cols) {
        int inner = SHARE_W - SH_PAD * 2;
        int gap = 6;
        int bw = (inner - gap * (cols - 1)) / cols;
        int bx = SH_PAD + col * (bw + gap);
        int by = shRowY(row) + 2;
        return new int[]{bx, by, bx + bw, by + SH_ROW - 4};
    }

    /**
     * 小块位置。**可以拖** —— 用户要求"点开之后是另一个独立可拖动的小页面"。
     *
     * 首次打开时居中 (shareDragX/Y 是 -1), 拖过之后就固定在用户放的地方。
     * 夹进屏幕, 免得拖出去再也找不回来。
     */
    private static int sharePosX = -1, sharePosY = -1;
    private static boolean shareDragging = false;
    private static int shareGrabDX, shareGrabDY;

    private static int[] shareBox() {
        int w = SHARE_W * S, h = SHARE_H * S;
        int x = sharePosX < 0 ? Math.max(4 * S, (physW - w) / 2) : sharePosX;
        int y = sharePosY < 0 ? Math.max(4 * S, (physH - h) / 2) : sharePosY;
        x = Math.max(0, Math.min(Math.max(0, physW - w), x));
        y = Math.max(0, Math.min(Math.max(0, physH - h), y));
        return new int[]{x, y, w, h};
    }

    private static void openShare() {
        // **浮窗已停用, 这里只做"把列表拉一次"。**
        //
        // 原来这里会 shareOpen = true, 于是点任何东西都可能弹出那个窗。现在配置相关
        // 的东西全在 CONFIG 列, 不再需要开窗; 但这个函数还被 onFrame 里的"首次打开
        // GUI 就拉一次列表"那条路调着 (列表要有人拉才有内容), 所以保留拉取逻辑。
        cfgListFetched = true;
        fetchCfgList();
    }

    private static void closeShare() {
        shareOpen = false;
        shareInputFocused = false;
        shareCodeInput = "";
    }

    /**
     * 旧浮窗里的"读取"。**浮窗已删, 这里保留只是为了不让 shareInputSubmit 变成死代码。**
     *
     * <p>现在 CONFIG 列上的 Paste 走 {@link #pasteIntoShareInput()}, 它直接调
     * fetchConfigByCode, 不再经过这个函数 —— 所以这个函数的唯一输入源
     * (shareCodeInput) 已经没有界面在写了。
     */
    private static void shareInputSubmit() {
        String t = shareCodeInput.trim();
        if (t.length() == 0) {
            setToast("先输入分享码");
            return;
        }
        shareInputFocused = false;
        if (t.regionMatches(true, 0, CFG_MAGIC, 0, CFG_MAGIC.length())) {
            try {
                setToast(importConfig(t));
                closeShare();
            } catch (Throwable e) {
                setToast("导入失败: " + e);
            }
            return;
        }
        if (isShareCode(t)) {
            shareLastCode = t.toUpperCase(java.util.Locale.ROOT);
            fetchConfigByCode(shareLastCode);
            closeShare();
        } else {
            setToast("这不像分享码 (6 位), 也不像 AZC2 开头的离线串");
        }
    }

    /**
     * 原版输入框判 Ctrl+V 用的就是这个 (isKeyComboCtrlV)。
     *
     * **故意不做"V 键"这种兜底**: 分享码字母表里有 V, 拿键码兜底会让打字输入 V 变成粘贴。
     * 查不到就退回"没有 Ctrl+V", 行为和不改之前一样, 但不会更糟。
     */
    private static boolean isCtrlV(int keyCode) {
        if (mCtrlV == null) return false;
        try {
            return (Boolean) mCtrlV.invoke(null, keyCode);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 把剪贴板塞进分享输入框。
     *
     * 离线串 (AZC2 开头) 几百个字符, 塞不进 6 位的输入框, 也不该让用户再点一次 —— 直接解。
     * 短码就填进框并立刻读取, 一次粘贴走完全程。
     */
    private static void pasteIntoShareInput() {
        String c;
        try {
            c = getClipboard();
        } catch (Throwable t) {
            setToast("读剪贴板失败: " + t);
            return;
        }
        c = c == null ? "" : c.trim();
        if (c.length() == 0) { setToast("剪贴板是空的"); return; }
        if (c.regionMatches(true, 0, CFG_MAGIC, 0, CFG_MAGIC.length())) {
            try {
                setToast(importConfig(c));
                closeShare();
            } catch (Throwable t) {
                setToast("导入失败: " + t);
            }
            return;
        }
        // 只留分享码字母表里的字符 —— 从聊天框复制常常前后带昵称和换行
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < c.length() && sb.length() < 6; i++) {
            char ch = Character.toUpperCase(c.charAt(i));
            if (CODE_ALPHABET.indexOf(ch) >= 0) sb.append(ch);
        }
        if (sb.length() == 0) {
            setToast("剪贴板里没有分享码 (是 AZC2... 离线串的话直接解不了, 长度 " + c.length() + ")");
            return;
        }
        // **不足 6 位要说出来, 不能默默什么都不做。**
        //
        // 原来的写法是"填进 shareCodeInput, 满 6 位才提交" —— 于是剪贴板里
        // 是 "CT7" 或者带了一堆符号导致只剩 4 个有效字符时, 界面**毫无反应**,
        // 用户只能反复点。现在明确报长度。
        if (sb.length() < 6) {
            setToast("剪贴板里的分享码只有 " + sb.length() + " 位 (要 6 位): " + sb);
            return;
        }
        // 直接把码取回来, 不再经 shareCodeInput —— 那是旧浮窗的字段, 现在已经没人读它了,
        // 写进去等于石沉大海 (Paste 点了没反应的原因之一)。
        String code = sb.toString();
        shareLastCode = code;
        fetchConfigByCode(code);
    }

    /** 分享/载入小块。必须在 renderColumns 之后调, 保证盖在面板上面 */
    private static void renderShareCard(int mx, int my) throws Exception {
        if (!shareOpen) return;
        int[] b = shareBox();
        int x = b[0], y = b[1], w = b[2], h = b[3];

        glassPanel(x, y, x + w, y + h, 10 * S, 0xE6141620, true);

        // ---- 标题栏 (也是拖动条) ----
        int ty = y + (SH_TITLE_H - 16) / 2 * S;
        text("Share / Load Config", x + SH_PAD * S, textY(y + SH_TITLE_H / 2 * S - 8 * S, 16 * S), TXT_MAIN);
        int xw = 12 * S;
        int xX = x + w * S - SH_PAD * S - xw, xY = y + (SH_TITLE_H - 16) / 2 * S;
        boolean xh = mx >= xX - 4 * S && mx < xX + xw + 4 * S && my >= xY - 2 * S && my < xY + 18 * S;
        text("X", xX, textY(y + SH_TITLE_H / 2 * S - 8 * S, 16 * S), xh ? 0xFFFF6B6B : TXT_SUB);

        // ---- 第 0 行: 自己的分享码 ----
        String code = shareLastCode;
        int r0y = y + shRowY(0) * S;
        text("My code:", x + SH_PAD * S, textY(r0y, SH_ROW * S), TXT_SUB);
        String cs = code == null ? "none yet" : code;
        text(cs, x + SH_PAD * S + textW("My code:") + 6 * S, textY(r0y, SH_ROW * S),
                code == null ? TXT_HINT : ACCENT());

        // ---- 第 1 行: 生成 / 复制码 ----
        int[] genBtn = shBtnRect(1, 0, 2), cpBtn = shBtnRect(1, 1, 2);
        drawShareBtn(x, y, genBtn, "Upload mine", mx, my, true);
        drawShareBtn(x, y, cpBtn, code == null ? "Copy code" : "Copy " + code, mx, my, code != null);

        // ---- 第 2 行: 分享码输入框 ----
        int[] inR = shBtnRect(2, 0, 3), ldBtn = shBtnRect(2, 1, 3), psBtn = shBtnRect(2, 2, 3);
        int iy = y + inR[1] * S, ih = (inR[3] - inR[1]) * S;
        int ix1 = x + inR[0] * S, ix2 = x + inR[2] * S;
        roundedRect(ix1, iy, ix2, iy + ih, 4 * S, 0x9E16161E);
        if (shareInputFocused) roundedRect(ix1, iy, ix2, iy + ih, 4 * S, 0x33FFFFFF);
        String shown = shareCodeInput.length() > 0 ? shareCodeInput : "code...";
        text(fitText(shown, ix2 - ix1 - 10 * S), ix1 + 5 * S, textY(iy, ih),
                shareCodeInput.length() > 0 ? TXT_MAIN : TXT_HINT);
        if (shareInputFocused) {
            int cc = ix1 + 5 * S + textW(shareCodeInput);
            mDrawRect.invoke(null, cc + S, iy + 3 * S, cc + 2 * S, iy + ih - 3 * S, ACCENT());
        }
        drawShareBtn(x, y, ldBtn, "Load", mx, my, true);
        // **粘贴按钮。** 用户明确要求 "到现在连个粘贴按钮都没做"。
        // 它不是 Ctrl+V 的重复: 剪贴板里是 AZC2... 离线串 (几百字符) 时直接解,
        // 那串塞不进这个 6 位输入框 —— 没这个按钮就只能靠手打。
        drawShareBtn(x, y, psBtn, "Paste", mx, my, true);

        // ---- 第 3 行: 状态消息 (原来的底部反馈条搬到这里) ----
        int r3y = y + shRowY(3) * S;
        String msg = liveToast();
        if (msg.length() > 0) {
            text(fitText(msg, (SHARE_W - SH_PAD * 2) * S), x + SH_PAD * S, textY(r3y, SH_ROW * S), TXT_WARN);
        } else {
            text("My Configs (" + cfgNames.length + ")", x + SH_PAD * S, textY(r3y, SH_ROW * S), TXT_SUB);
        }

        // ---- 第 4 行: 左键读取 / 右键删除的提示 ----
        int r4y = y + shRowY(4) * S;
        text("click a name = load  ·  right-click = delete", x + SH_PAD * S,
                textY(r4y, SH_ROW * S), TXT_HINT);

        // ---- 第 5 行: 个人配置单 (横向滚动不了, 只放最上面一条) ----
        int r5y = y + shRowY(5) * S;
        if (cfgBusy != null) {
            text(fitText(cfgBusy, (SHARE_W - SH_PAD * 2) * S), x + SH_PAD * S, textY(r5y, SH_ROW * S), TXT_HINT);
        } else if (cfgNames.length == 0) {
            text("no saved configs", x + SH_PAD * S, textY(r5y, SH_ROW * S), TXT_HINT);
        } else {
            String nm = cfgNames[shareListScroll % cfgNames.length];
            int upBtnW = 14;
            int[] upR = {SH_PAD, r5y / S - y / S, SH_PAD + upBtnW, r5y / S - y / S + SH_ROW};
            int[] dnR = {SHARE_W - SH_PAD - upBtnW, upR[1], SHARE_W - SH_PAD, upR[3]};
            drawShareBtn(x, y, upR, "^", mx, my, true);
            drawShareBtn(x, y, dnR, "v", mx, my, true);
            int tx1 = (upR[2] + 4) * S + x, tx2 = x + (dnR[0] - 4) * S;
            text(fitText(nm + "   >>>", (tx2 - tx1) / S), tx1, textY(r5y, SH_ROW * S), TXT_MAIN);
        }
    }

    /** 小窗里配置列表当前显示第几条 (没有滚动条, 用 ^ v 翻) */
    private static int shareListScroll = 0;

    /**
     * 小窗里的一个按钮。**画成实心药丸 + 描边**, 不再是光秃秃的文字 ——
     * 用户说的 "文字有错位且不清晰" 有一大半是"看不出哪里能点"。
     * 矩形是相对窗口左上角的逻辑单位。
     */
    private static void drawShareBtn(int ox, int oy, int[] rel, String label, int mx, int my,
                                     boolean enabled) throws Exception {
        int x1 = ox + rel[0] * S, y1 = oy + rel[1] * S;
        int x2 = ox + rel[2] * S, y2 = oy + rel[3] * S;
        boolean hv = enabled && mx >= x1 && mx < x2 && my >= y1 && my < y2;
        int fill = !enabled ? 0x66101624 : (hv ? 0xC52A806D : 0xA0121B29);
        roundedRect(x1, y1, x2, y2, 4 * S, fill);
        if (enabled) {
            // 描边: 一圈 1 单位的外框。这一笔就是"看得清按钮边界"的关键
            roundedRect(x1, y1, x2, y1 + Math.max(1, S / 2), 2 * S,
                    hv ? fade(ACCENT(), 0.86f) : 0x4A6C839C);
            roundedRect(x1, y2 - Math.max(1, S / 2), x2, y2, 2 * S,
                    hv ? fade(GLASS_BLUE, 0.54f) : 0x2E3A4E67);
        }
        String t = fitText(label, x2 - x1 - 8 * S);
        text(t, x1 + (x2 - x1 - textW(t)) / 2, textY(y1, y2 - y1),
                !enabled ? TXT_HINT : (hv ? TXT_ON : TXT_MAIN));
    }

    /** 分享小块的点击。true = 已经吃掉这次点击 (小块是模态的) */
    private static boolean handleShareClick(int mx, int my) {
        int[] b = shareBox();
        int x = b[0], y = b[1], w = b[2], h = b[3];

        if (mx < x || mx >= x + w || my < y || my >= y + h) {
            closeShare();          // 点外面 = 关掉
            return true;
        }
        // 右上角 X
        {
            int xw = 12 * S;
            int xX = x + w - SH_PAD * S - xw, xY = y + (SH_TITLE_H - 16) / 2 * S;
            if (mx >= xX - 4 * S && mx < xX + xw + 4 * S && my >= xY - 2 * S && my < xY + 18 * S) {
                closeShare();
                return true;
            }
        }
        // 标题栏 = 拖窗口 (用户要求"独立可拖动的小页面")。X 已经在上面处理掉了, 不会冲突。
        if (my < y + SH_TITLE_H * S) {
            shareDragging = true;
            shareGrabDX = mx - x;
            shareGrabDY = my - y;
            return true;
        }

        // **按钮命中统一走 shBtnHit** —— 和 renderShareCard 用同一张行表,
        // 不可能再出现"按钮画在这、点击判定在那"的错位。
        // 第 1 行: Upload mine / Copy code
        if (shBtnHit(x, y, shBtnRect(1, 0, 2), mx, my)) { shareConfigOnline(); return true; }
        if (shBtnHit(x, y, shBtnRect(1, 1, 2), mx, my)) {
            if (shareLastCode == null) { setToast("先生成一个分享码 (Upload mine)"); return true; }
            try {
                setClipboard(shareLastCode);
                setToast("分享码 " + shareLastCode + " 已复制");
            } catch (Throwable t) {
                setToast("分享码是 " + shareLastCode);
            }
            return true;
        }
        // 第 2 行: 输入框 / Load / Paste
        if (shBtnHit(x, y, shBtnRect(2, 0, 3), mx, my)) {
            shareInputFocused = true;
            return true;
        }
        if (shBtnHit(x, y, shBtnRect(2, 1, 3), mx, my)) { shareInputFocused = false; shareInputSubmit(); return true; }
        if (shBtnHit(x, y, shBtnRect(2, 2, 3), mx, my)) { pasteIntoShareInput(); return true; }

        // 第 5 行: 配置单翻页 + 点名字读取
        if (cfgNames.length > 0) {
            int r5 = shRowY(5);
            int upBtnW = 14;
            int[] upR = {SH_PAD, r5, SH_PAD + upBtnW, r5 + SH_ROW};
            int[] dnR = {SHARE_W - SH_PAD - upBtnW, r5, SHARE_W - SH_PAD, r5 + SH_ROW};
            if (shBtnHit(x, y, upR, mx, my)) {
                shareListScroll = (shareListScroll + cfgNames.length - 1) % cfgNames.length;
                return true;
            }
            if (shBtnHit(x, y, dnR, mx, my)) {
                shareListScroll = (shareListScroll + 1) % cfgNames.length;
                return true;
            }
            int tx1 = x + (upR[2] + 4) * S, tx2 = x + (dnR[0] - 4) * S;
            int r5y = y + r5 * S;
            if (mx >= tx1 && mx < tx2 && my >= r5y && my < r5y + SH_ROW * S) {
                String nm = cfgNames[shareListScroll % cfgNames.length];
                // 右键 = 删, 左键 = 读取
                if (rightShareClick) deleteSavedConfig(nm);
                else loadSavedConfig(nm);
                return true;
            }
        }
        return true;
    }

    /** 上一次 handleShareClick 是不是右键调的 —— 见 clickAt 的调用点 */
    private static boolean rightShareClick = false;

    /** 按钮命中。矩形是相对窗口的**逻辑单位**, 和 drawShareBtn 共用 shBtnRect */
    private static boolean shBtnHit(int ox, int oy, int[] rel, int mx, int my) {
        return mx >= ox + rel[0] * S && mx < ox + rel[2] * S
                && my >= oy + rel[1] * S && my < oy + rel[3] * S;
    }

    private static void drawScreenLine(float x0, float y0, float x1, float y1, int rgb, int th) throws Exception {        if (mDrawRect == null) return;
        if (th < 1) th = 1;
        int half = (th - 1) / 2;
        float dx = x1 - x0, dy = y1 - y0;

        // 只沿**步数更少的那根轴**走, 每一步的矩形覆盖另一根轴上的起止区间。
        //
        // 为什么不是沿主轴: 追踪线是从屏幕底边中点连到目标, 绝大多数是**接近竖直**的 ——
        // 沿主轴 (y) 走就是每条线画上千个矩形, 二十个实体就是几万个 drawRect, 一帧几万次
        // 绘制调用, 卡是必然的。沿次轴 (x) 走, 近乎竖直的线只需要十几次。
        //
        // 这么做不会变成阶梯: 每个矩形在另一根轴上是**连续覆盖**这一步的起止区间的,
        // 相邻矩形首尾相接。步数够密时边缘和真线差不到一个像素。
        if (Math.abs(dx) >= Math.abs(dy)) {
            // x 跨度大 -> 沿 y 走 (每一行一个矩形)
            int n = Math.max(1, Math.min((int) Math.abs(dy), 384));
            float step = dy / n;
            for (int i = 0; i < n; i++) {
                float ya = y0 + step * i, yb = ya + step;
                float xa = x0 + dx * ((float) i / n), xb = x0 + dx * ((float) (i + 1) / n);
                int ry0 = Math.round(Math.min(ya, yb));
                int ry1 = Math.max(ry0 + 1, Math.round(Math.max(ya, yb)));
                int lo = Math.round(Math.min(xa, xb)) - half;
                int hi = Math.round(Math.max(xa, xb)) + half + 1;
                mDrawRect.invoke(null, lo, ry0, Math.max(lo + 1, hi), ry1, rgb);
            }
        } else {
            // y 跨度大 -> 沿 x 走 (每一列一个矩形)。近乎竖直的线走这条, 矩形很少。
            int n = Math.max(1, Math.min((int) Math.abs(dx), 384));
            float step = dx / n;
            for (int i = 0; i < n; i++) {
                float xa = x0 + step * i, xb = xa + step;
                float ya = y0 + dy * ((float) i / n), yb = y0 + dy * ((float) (i + 1) / n);
                int rx0 = Math.round(Math.min(xa, xb));
                int rx1 = Math.max(rx0 + 1, Math.round(Math.max(xa, xb)));
                int lo = Math.round(Math.min(ya, yb)) - half;
                int hi = Math.round(Math.max(ya, yb)) + half + 1;
                mDrawRect.invoke(null, rx0, lo, rx1, Math.max(lo + 1, hi), rgb);
            }
        }
    }

    // ===== 透视: 追踪线 =====
    /**
     * 追踪线 —— **纯 2D**，画在屏幕上。参数: 0-2=RGB 3=Width 4=Mode
     *
     * Mode 决定线的终点画在哪:
     *   Feet      —— 脚底 (最常用, 线不会挡住人)
     *   Head      —— 头顶
     *   Crosshair —— 从准星出发 (屏幕上看起来像"指"着目标)
     *
     * 起点固定是屏幕底边中点 —— 这是各家客户端的通行做法, 也最不容易挡视野。
     *
     * <h3>"纯 2D"具体指什么</h3>
     * 线本身一直是在屏幕空间画的 (drawScreenLine = 一堆 drawRect), 并不是三维线框。
     * 之前表现像"3D"是因为**终点没有钳位**: 目标转到身后/侧面时投影坐标会炸到几万像素,
     * 而 drawScreenLine 的步数封顶 384, 于是每步跨度上百像素、每个矩形都是大块 ——
     * 看起来就是一条粗带, 而不是线。见 projectPointClamped 的说明。
     *
     * 现在终点走 worldToScreen(..., clamp=true): 身后目标使用下侧边缘方向提示，前方目标投影后钳进屏幕, 所以不管目标在哪, 屏幕上永远是一条粗细正常的线。
     */
    private static void renderTracers(float pt, Object world, Object player, double vx, double vy, double vz) throws Exception {
        int rgb = 0xFF000000 | ((int) p(FT, 0) << 16) | ((int) p(FT, 1) << 8) | (int) p(FT, 2);
        // Width 是逻辑像素, 可以取 0.5 —— guiScale=4 时 0.5 就是 2 个物理像素。
        // 原来是整数 1..4, 最小也是 4 个物理像素, 想画细线画不出来。
        float wLog = (float) p(FT, 3);
        int mode = (int) p(FT, 4);
        int targetMode = (int) p(FT, 5);
        Object mc = mGetMC.invoke(null);
        int dw = (Integer) fDisplayW.get(mc), dh = (Integer) fDisplayH.get(mc);
        if (dw <= 0 || dh <= 0) return;
        float gsf = GS <= 0 ? 1f : GS;
        int th = Math.max(1, Math.round(wLog * gsf));
        // 起点用**物理**坐标 —— 下面换的是物理投影, 混用逻辑坐标线会偏。
        float sx = dw * 0.5f, sy = dh;
        if (mode == 2) { sx = dw * 0.5f; sy = dh * 0.5f; }

        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
        // 这一段是在 renderWorld 里跑的, 当前还是 3D 相机投影。不换成 2D 正交的话,
        // 下面这些屏幕坐标会被 3D 矩阵再变换一次 —— 线就会满屏乱跳。
        //
        // 用**物理**像素而不是逻辑单位: 追踪线是纯几何, 不涉及字体, 所以没有"逻辑单位才
        // 和字号对齐"那个约束。worldToScreen 返回的本来就是物理像素, 直接用, 粒度细 gsf 倍。
        pushPhysicalProjection();
        try {
            for (Object ent : (java.util.List<?>) fLoaded.get(world)) {
                if (ent == player || !cLiving.isInstance(ent) || fIsDead.getBoolean(ent)) continue;
                if (!matchTarget(targetMode, ent)) continue;
                if (isBot(player, ent)) continue;
                // **插值坐标** —— 追踪线原来直接读 fPosX(ent), 于是终点是"上一个 tick 的
                // 位置", 而起点 (屏幕底边中点) 是"现在"。移动时线就甩在目标身后一段距离,
                // 停住时两者相等所以看着是准的 —— 正是用户描述的现象。见 entX 的说明。
                double ex = entX(ent, pt), ey0 = entY(ent, pt), ez = entZ(ent, pt);
                double ey = mode == 1 ? ey0 + mGetBBHeight(ent) : ey0 + 0.1;
                // clamp=true: 允许身后目标, 并把终点钳进屏幕 —— 这是"纯 2D 线"的关键,
                // 少了它目标转到身后时投影会炸到几万像素, 线就变成一条巨粗的带。
                float[] s = worldToScreen(ex, ey, ez, pt, mc, player, vx, vy, vz, true);
                if (s == null) continue;
                int tint = overlayColor(rgb, (s[0] + s[1]) / Math.max(1.0, dw + dh));
                SoftEffects.uniformLine(sx,sy,s[0],s[1],tint,Math.min(th+2*S,bloomSpread*S),bloom*.10f);
                drawScreenLine(sx, sy, s[0], s[1], blend(tint, 0xFFFFF1F6, 0.20f), th);
            }
        } finally {
            popPhysicalProjection();
        }
        // 这俩只是为了不让编译器抱怨未使用 —— 实际上 px/py/pz 在下面的距离判定里会用到
        if (px + py + pz == Double.MAX_VALUE) return;
    }

    // ===== 透视: 名字标签 =====
    /**
     * 名字标签。参数: 0=Show HP 1=Show Dist 2=Scale 3=Background
     *
     * Scale 是按字号比例实现的 (最小 1 倍行高)。真要按像素缩放得走字形变换,
     * 图集是按固定大小烘焙的 —— 缩放会让字发糊, 不如不动。
     */
    private static void renderNameTags(float pt, Object world, Object player, double vx, double vy, double vz) throws Exception {
        if (mDrawString == null) return;
        Object mc = mGetMC.invoke(null);
        boolean showHp = p(FNT, 0) > 0;
        boolean showDist = p(FNT, 1) > 0;
        boolean bg = p(FNT, 3) > 0;
        int targetMode = (int) p(FNT, 4);
        // Layout uses logical units; fonts rasterize at the final physical scale.
        float sc = (float) Math.max(0.5, Math.min(2.0, p(FNT, 2)));
        double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);

        boolean pushed = false;
        boolean proj = false;
        try {
            // 名字标签也是在世界渲染里调的, 当前是 3D 投影 —— 同样得先压 2D 正交,
            // 否则文字会被相机矩阵甩到屏幕外或者乱飘。
            pushLogicalProjection();
            proj = true;
            OverlayFont.logicalPixels=Math.max(1,GS)*sc;
            if (Math.abs(sc - 1.0f) > 0.01f && cGL != null) {
                cGL.getMethod("func_179094_E").invoke(null);              // pushMatrix
                cGL.getMethod("func_179152_a", float.class, float.class, float.class).invoke(null, sc, sc, sc);
                pushed = true;
            }

            for (Object ent : (java.util.List<?>) fLoaded.get(world)) {
                if (ent == player || !cLiving.isInstance(ent) || fIsDead.getBoolean(ent)) continue;
                if (!matchTarget(targetMode, ent)) continue;
                if (isBot(player, ent)) continue;
                // **插值坐标** —— 名字标签原来直接读 fPosX/fPosY/fPosZ(ent), 移动时标签会
                // 黏在目标身后一段距离, 停住时才对齐。用户报的就是这个 ("nametag 也这样")。
                double ex = entX(ent, pt), ey0 = entY(ent, pt), ez = entZ(ent, pt);
                double dist = Math.sqrt(Math.pow(ex - px, 2)
                        + Math.pow(ey0 - py, 2)
                        + Math.pow(ez - pz, 2));
                if (dist > 128.0) continue;           // 再远就糊成一团了

                float[] s = worldToScreen(ex,
                        ey0 + mGetBBHeight(ent) + 0.4, ez,
                        pt, mc, player, vx, vy, vz);
                if (s == null) continue;

                String label = entityLabel(ent);
                if (showHp) {
                    float hp = ((Number) mGetHealth.invoke(ent)).floatValue();
                    float max = mGetMaxHealth == null ? 20.0f : ((Number) mGetMaxHealth.invoke(ent)).floatValue();
                    if (max <= 0.0f) max = 20.0f;
                    label += " " + Math.round(hp) + "/" + Math.round(max);
                }
                if (showDist) label += " " + String.format("%.1f", dist) + "m";

                float gsf = GS <= 0 ? 1f : GS;
                // 坐标要先除掉缩放 —— 坐标系已经被 GL 放大了 sc 倍
                int tx = Math.round(s[0] / gsf / sc), ty = Math.round(s[1] / gsf / sc);
                int w = textWLogical(label);
                tx -= w / 2;                          // 水平居中在实体头顶
                if (bg) {
                    softGlow(tx - 10, ty - 7, w + 20, LABEL_ROW_L + 14, sunset(0.2, rainbowMode), bloom * 0.35f*widgetGlow(F_NAMETAGS));
                    roundedRect(tx - 4, ty - 3, tx + w + 4, ty - 2 + LABEL_ROW_L, 3, fade(0xFF24192D,backgroundOpacity(F_NAMETAGS)));
                }
                textLogical(label, tx, ty, 0xFFE8F0FA);
            }
        } finally {
            OverlayFont.logicalPixels=1;
            if (pushed) cGL.getMethod("func_179121_F").invoke(null);      // popMatrix
            if (proj) popPhysicalProjection();
        }
    }

    /** 实体的显示名 (玩家取用户名, 其他取本地化名)。取不到就返回类名, 不会是空串。 */
    private static String entityLabel(Object ent) {
        try {
            Object n = mGetName.invoke(ent);
            if (n != null) {
                String s = n.toString();
                if (!s.isEmpty()) return s;
            }
        } catch (Throwable ignored) { }
        return ent.getClass().getSimpleName();
    }

    // ===== 透视: 弹道预测 =====
    /**
     * 弹道预测。参数: 0=Bow 1=Potions 2=Show Landing
     *
     * 按原版的抛射物物理逐 tick 模拟: 每 tick 先乘 0.99 空气阻力再减 0.05 重力。
     * 用玩家当前视角的 pitch/yaw 当初始方向 —— 和真正射出去的箭用的是同一套公式,
     * 所以只要视角没变, 画出来的落点就是实际落点。
     *
     * 不检测碰撞: 撞墙后箭会停, 而这里会继续画到地面上。地面判据是"y 低于玩家脚底"。
     * 想精确到墙就得做体素射线检测, 那是另一个量级的工作量, 先不做。
     */
    private static void renderTrajectories(float pt, Object player, double vx, double vy, double vz) throws Exception {
        Object mc = mGetMC.invoke(null);
        Object held = heldItem(player);
        if (held == null) return;

        double speed;
        double gravity = 0.05;
        if (p(FTJ, 0) > 0 && itemLike(held, "bow")) {
            // 拉弓时间决定初速: 满弦 3.0, 最短 0.15 (原版 getArrowVelocity 的近似)
            int use = mGetItemInUseCount == null ? 0 : (Integer) mGetItemInUseCount.invoke(player);
            double charge = Math.min(1.0, use / 20.0);
            speed = 0.15 + charge * charge * 3.0;
        } else if (p(FTJ, 1) > 0 && itemLike(held, "potion")) {
            speed = 0.5;
        } else {
            return;
        }
        if (speed <= 0.01) return;

        float yaw = fYaw.getFloat(player), pitch = fPitch.getFloat(player);
        double rad = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        double dx = -Math.sin(rad) * Math.cos(pr);
        double dy = -Math.sin(pr);
        double dz = Math.cos(rad) * Math.cos(pr);

        double x = fPosX.getDouble(player), y = fPosY.getDouble(player) + 1.62, z = fPosZ.getDouble(player);
        double mx = dx * speed, my = dy * speed, mz = dz * speed;
        double groundY = fPosY.getDouble(player);

        // 弹道点 / 落点标记都是屏幕空间 (下面除了 GS), 所以整段要跑在 2D 正交投影下。
        // 和 ESP 是同一个坑 —— onRender 里当前矩阵还是 3D 相机矩阵, 不压的话这些点会被
        // 当成立方体空间里的坐标再变换一次, 画出来的轨迹跟箭根本不在一起。
        float gsf = GS <= 0 ? 1f : GS;
        pushLogicalProjection();
        try {
            for (int tick = 0; tick < 200; tick++) {
                x += mx; y += my; z += mz;
                mx *= 0.99; my *= 0.99; mz *= 0.99;
                my -= gravity;

                float[] s = worldToScreen(x, y, z, 1.0f, mc, player, vx, vy, vz);
                if (s != null) {
                    int sx = Math.round(s[0] / gsf), sy = Math.round(s[1] / gsf);
                    softGlow(sx - 5, sy - 5, 10, 10, sunset(tick / 160.0, rainbowMode), bloom * 0.55f);
                    mDrawRect.invoke(null, sx - 1, sy - 1, sx + 1, sy + 1, sunset(tick / 160.0, rainbowMode));
                }
                if (y < groundY) break;               // 落地了
            }

            // Show Landing: 在落点画一个方块标记
            if (p(FTJ, 2) > 0) {
                float[] s = worldToScreen(x, groundY + 0.05, z, 1.0f, mc, player, vx, vy, vz);
                if (s != null) {
                    int sx = Math.round(s[0] / gsf), sy = Math.round(s[1] / gsf);
                    mDrawRect.invoke(null, sx - 4, sy - 1, sx + 4, sy + 1, overlayColor(0xFFFF4D4D, 0.6));
                    mDrawRect.invoke(null, sx - 1, sy - 4, sx + 1, sy + 4, overlayColor(0xFFFF4D4D, 0.6));
                }
            }
        } finally {
            popPhysicalProjection();
        }
    }

    // ===== AntiBot =====

    /**
     * 判断一个实体是不是"假玩家"(反作弊插件放的检查点 / NPC / 复制体)。
     *
     * 三种判据, 按 Mode 选:
     *   Name      —— 名字和另一个实体的名字完全一样 (复制体)
     *   Entity ID —— 服务端生成的 NPC 用的 id 段和真玩家不同 (负数或超大值)
     *   Ping      —— 查不到 NetworkPlayerInfo。服务端自己 spawn 的 NPC 不在这张表里, 这是最硬的信号
     *
     * Ignore Teams: 同队的也当机器人处理 —— 打队友没意义, 而且很容易被队友举报。
     */
    private static boolean isBot(Object player, Object ent) {
        if (!mods[F_ANTIBOT].on) return false;
        if (!cPlayer.isInstance(ent)) return false;   // 非玩家不参与判断
        try {
            // Range: 判定要扫整个实体列表 (Name 档还要两两比名字), 远处的目标不值得这个开销。
            // 超出范围的按"真玩家"处理 —— 反正是打不到的距离。
            double px = fPosX.getDouble(player), py = fPosY.getDouble(player), pz = fPosZ.getDouble(player);
            double range = p(F_ANTIBOT, 0);
            if (Math.abs(fPosX.getDouble(ent) - px) > range
                    || Math.abs(fPosY.getDouble(ent) - py) > range
                    || Math.abs(fPosZ.getDouble(ent) - pz) > range) return false;

            // Ignore Teams: 同队跳过
            if (p(F_ANTIBOT, 2) > 0 && mGetTeam != null && mIsOnSameTeam != null) {
                Object t1 = mGetTeam.invoke(player);
                Object t2 = mGetTeam.invoke(ent);
                if (t1 != null && t1.equals(t2)) return true;
            }
            int mode = (int) p(F_ANTIBOT, 1);
            if (mode == 0) {                          // Name
                String n = entityLabel(ent);
                Object mc = mGetMC.invoke(null);
                Object world = fWorld.get(mc);
                if (world == null) return false;
                int same = 0;
                for (Object other : (java.util.List<?>) fLoaded.get(world)) {
                    if (other == ent || !cPlayer.isInstance(other)) continue;
                    if (n.equals(entityLabel(other))) same++;
                }
                return same > 0;
            }
            if (mode == 1) {                          // Entity ID
                int id = (Integer) mGetEntityId.invoke(ent);
                return id < 0 || id > 1000000;
            }
            // Ping: 查不到玩家信息 -> 不是真玩家
            if (mGetConnection == null || mGetPlayerInfo == null) return false;
            Object conn = mGetConnection.invoke(mGetMC.invoke(null));
            if (conn == null) return false;
            Object uuid = mGetUUID.invoke(ent);
            if (uuid == null) return false;
            return mGetPlayerInfo.invoke(conn, uuid) == null;
        } catch (Throwable t) {
            return false;   // 判不出来就当是真的 —— 宁可多打一个, 也别把真人过滤掉
        }
    }

    // ===== HUD: Ping =====
    /** Ping 显示。参数: 0-2=RGB 3=Mode 4=X% 5=Y% */
    private static void renderPing() throws Exception {
        Object mc = mGetMC.invoke(null);
        Object player = fPlayer.get(mc);
        if (player == null) return;
        int ping = -1;
        try {
            if (mGetConnection != null && mGetPlayerInfo != null && mGetResponseTime != null) {
                Object conn = mGetConnection.invoke(mc);
                Object uuid = mGetUUID.invoke(player);
                if (conn != null && uuid != null) {
                    Object info = mGetPlayerInfo.invoke(conn, uuid);
                    if (info != null) ping = (Integer) mGetResponseTime.invoke(info);
                }
            }
        } catch (Throwable ignored) { }
        if (ping < 0) return;

        int rgb = 0xFF000000 | ((int) p(FPING, 0) << 16) | ((int) p(FPING, 1) << 8) | (int) p(FPING, 2);
        int uw = (Integer) fDisplayW.get(mc), uh = (Integer) fDisplayH.get(mc);
        if (uw <= 0 || uh <= 0) return;
        int padx = Math.max(2 * S, 5 * S / 2);
        boolean bar = (int) p(FPING, 3) == 1;
        String pingText = ping + "ms";
        int barW = 60;
        int barH = Math.max(3 * S, 4);
        int pillW = bar ? barW + padx * 2 : textW(pingText) + padx * 2 + 15*S;
        int pillH = bar ? barH + 2 * Math.max(S, 3) : ROW_NAME + 2 * Math.max(S, 3);
        int px1 = (int) Math.round((uw - pillW) * p(FPING, 4) / 100.0);
        int py1 = (int) Math.round((uh - pillH) * p(FPING, 5) / 100.0);
        px1 = Math.max(0, Math.min(Math.max(0, uw - pillW), px1));
        py1 = Math.max(0, Math.min(Math.max(0, uh - pillH), py1));
        rememberWidgetBounds(F_PING, px1, py1, pillW, pillH);
        boolean pushed = false;
        try {
            pushPhysicalProjection();
            pushed = true;
            if (bar) {          // Bar: 一根条, 满格 200ms
                veilWidgetSurface(F_PING,px1,py1,pillW,pillH,1f);
                int bx1 = px1 + padx, by1 = py1 + (pillH - barH) / 2;
                roundedRect(bx1, by1, bx1 + barW, by1 + barH, barH / 2, fade(0x55000000,backgroundOpacity(F_PING)));
                int w = Math.max(1, Math.round(barW * Math.min(1.0f, ping / 200.0f)));
                roundedRect(bx1, by1, bx1 + w, by1 + barH, barH / 2, rgb);
            } else {                                // Text
                veilWidgetSurface(F_PING,px1,py1,pillW,pillH,1f);
                for(int n=0;n<3;n++){
                    int bx=px1+padx+n*4*S,bottom=py1+pillH/2+5*S;
                    mDrawRect.invoke(null,bx,bottom-(n+1)*3*S,bx+2*S,bottom,fade(rgb,.55f+.2f*n));
                }
                text(pingText,px1+padx+15*S,hudTextY(py1,pillH,1f),0xFFF8F5FA);
            }
        } finally {
            if (pushed) popPhysicalProjection();
        }
    }

    // ===== HUD: 水印 =====
    /** 水印。参数: 0=Show FPS 1=Show Coords 2=Mode 3=X% 4=Y% */
    private static void renderWatermark() throws Exception {
        Object mc = mGetMC.invoke(null);
        Object player = fPlayer.get(mc);
        if (player == null) return;
        int uw = (Integer) fDisplayW.get(mc), uh = (Integer) fDisplayH.get(mc);
        if (uw <= 0 || uh <= 0) return;
        String brand="AZCLIENT";
        String info=VERSION.replace("AZclient", "").replace("AZCLIENT", "").trim();
        if(p(FWM,0)>0&&fDebugFPS!=null)info+="   /   "+fDebugFPS.getInt(mc)+" fps";
        String coords=p(FWM,1)>0?"XYZ  "+Math.round(fPosX.getDouble(player))+" / "+Math.round(fPosY.getDouble(player))+" / "+Math.round(fPosZ.getDouble(player)):"";
        int pad=5*S,brandW=textW(brand),lineH=Math.max(24*S,ROW_NAME),gap=20*S;
        int w=Math.max(brandW+gap+textWScaled(info,.72f),textWScaled(coords,.75f))+pad*2;
        int h=lineH+(coords.isEmpty()?0:22*S)+pad*2;
        int x=Math.max(0,Math.min(Math.max(0,uw-w),(int)Math.round((uw-w)*p(FWM,3)/100d)));
        int y=Math.max(0,Math.min(Math.max(0,uh-h),(int)Math.round((uh-h)*p(FWM,4)/100d)));
        rememberWidgetBounds(F_WATERMARK,x,y,w,h);
        pushPhysicalProjection();
        try{
            if((int)p(FWM,2)==1)veilWidgetSurface(F_WATERMARK,x,y,w,h,1f);
            text(brand,x+pad,hudTextY(y+pad,lineH,1f),0xFFF8F5FA);
            int divider=x+pad+brandW+gap/2;
            mDrawRect.invoke(null,divider,y+pad+lineH/3,divider+Math.max(1,S),y+pad+lineH*2/3,0x66D4BFCF);
            textScaled(info,x+pad+brandW+gap,hudTextY(y+pad,lineH,.72f),0xF2E4DCEB,.72f);
            if(!coords.isEmpty())veilWidgetLine(coords,x+pad,y+pad+lineH,22*S,.75f);
        }finally{popPhysicalProjection();}
    }

    // Target presentation consumes the existing combat target; no targeting or movement writes.
    private static final TargetHudState targetHudState=new TargetHudState();
    private static Object targetVisualAttackTarget;
    private static long targetVisualAttackAt;
    private static Class<?> targetSkinOwner;
    private static Method targetSkinMethod,targetTextureManager,targetBindSkin,targetDrawSkin;

    private static Object liveVisualTarget(Object world,Object player)throws Exception{
        Object target=mods[F_KILLAURA].on?kaTarget:null;
        return world!=null&&player!=null&&target!=null&&cLiving.isInstance(target)
                &&!EntityExclusions.contains(target)&&!fIsDead.getBoolean(target)&&((java.util.List<?>)fLoaded.get(world)).contains(target)?target:null;
    }
    private static void renderTargetHud()throws Exception{
        rememberWidgetBounds(F_TARGETHUD,0,0,0,0);
        Object mc=mGetMC.invoke(null),world=fWorld.get(mc),player=fPlayer.get(mc);
        if(!mods[F_TARGETHUD].on||world==null||player==null){targetHudState.clear();targetVisualAttackTarget=null;targetVisualAttackAt=0;return;}
        if(!mods[F_KILLAURA].on || (targetHudState.world!=null&&targetHudState.world!=world)){targetVisualAttackTarget=null;targetVisualAttackAt=0;}
        Object target=liveVisualTarget(world,player);
        boolean preview=target==null&&guiOpen&&p(F_TARGETHUD,11)>0;
        if(preview)target=player;
        String name=target==null?"":OverlayFont.plain(String.valueOf(mGetName.invoke(target)))+(preview?" / Preview":"");
        double hp=target==null||mGetHealth==null?0:((Number)mGetHealth.invoke(target)).doubleValue();
        double max=target==null||mGetMaxHealth==null?0:((Number)mGetMaxHealth.invoke(target)).doubleValue();
        targetHudState.update(world,target,name,hp,max,System.nanoTime(),interactionMotion&&common(mods[F_TARGETHUD],"UI Motion")>0,
                Math.max(.1,interactionSpeed*common(mods[F_TARGETHUD],"UI Motion")));
        if(targetHudState.target==null)return;
        int dw=fDisplayW.getInt(mc),dh=fDisplayH.getInt(mc);if(dw<=0||dh<=0)return;
        layoutForFake(dw,dh,uiScale(dh));initFont();
        pushPhysicalProjection();
        try(VeilRenderState state=VeilRenderState.begin()){drawTargetCard(mc,dw,dh);}
        finally{popPhysicalProjection();}
    }
    private static String fitWidgetName(String name,int width,float scale){
        name=OverlayFont.plain(name);if(textWScaled(name,scale)<=width)return name;
        while(!name.isEmpty()&&textWScaled(name+"…",scale)>width)
            name=name.substring(0,name.offsetByCodePoints(name.length(),-1));
        return name+"…";
    }
    private static void drawTargetCard(Object mc,int dw,int dh)throws Exception{
        float sc=(float)Math.min(p(F_TARGETHUD,2),Math.min((dw-8d)/(228*S),(dh-8d)/(64*S)));
        if(sc<=0)return;
        float unit=S*sc,a=targetHudState.alpha;
        int w=Math.max(1,Math.round(228*unit)),h=Math.max(1,Math.round(64*unit)),pad=Math.max(2,Math.round(10*unit));
        int[] xy=widgetXY(F_TARGETHUD,w,h,dw,dh);int x=xy[0],y=xy[1];
        veilWidgetSurface(F_TARGETHUD,x,y,w,h,a);
        try(GuiClip clip=GuiClip.begin(x,y,w,h,dh)){
            int face=Math.max(1,Math.round(36*unit)),ax=x+pad,ay=y+(h-face)/2;
            roundedRect(ax,ay,ax+face,ay+face,Math.max(2,Math.round(6*unit)),fade(0xFF302638,a));
            if(!drawTargetPortrait(mc,targetHudState.target,ax,ay,face,a)){
                String name=targetHudState.name.isEmpty()?"?":targetHudState.name;
                String initial=name.substring(0,name.offsetByCodePoints(0,1));
                widgetTextCentered(initial,ax,ay,face,face,fade(0xFFF1D9E6,a),sc*1.35f);
            }
            int tx=ax+face+pad,tw=Math.max(1,x+w-pad-tx);
            float title=sc*.91f,small=sc*.68f;
            String name=fitWidgetName(targetHudState.name,tw,title);
            textScaled(name,tx,hudTextY(y+Math.round(10*unit),Math.round(22*unit),title),fade(0xFFF9F4FA,a),title);
            String health=targetHudState.maximum>0?String.format(java.util.Locale.ROOT,"%.1f / %.1f HP",targetHudState.health,targetHudState.maximum):"HP --";
            small=fitWidgetScale(health,tw,small);
            textScaled(health,tx,hudTextY(y+Math.round(29*unit),Math.round(17*unit),small),fade(0xFFD2BED4,a),small);
            int by=y+h-pad-Math.max(2,Math.round(2*unit)),bh=Math.max(2,Math.round(2*unit));
            roundedRect(tx,by,tx+tw,by+bh,bh/2,fade(0xFF403245,a));
            int filled=Math.min(tw,Math.max(0,Math.round((float)targetHudState.bar*tw)));
            if(filled>0)deckGradient(tx,by,filled,bh,fade(0xFFFFC59A,a),fade(0xFFF28CB0,a));
        }
    }
    private static boolean drawTargetPortrait(Object mc,Object target,int x,int y,int size,float alpha){
        try{
            if(target==null)return false;
            if(targetSkinOwner!=target.getClass()){targetSkinOwner=target.getClass();targetSkinMethod=mopt(targetSkinOwner,"func_110306_p");}
            if(targetSkinMethod==null)return false;
            Object skin=targetSkinMethod.invoke(target);if(skin==null)return false;
            if(targetTextureManager==null){
                targetTextureManager=mopt(copt("net.minecraft.client.Minecraft"),"func_110434_K");
                targetBindSkin=mopt(copt("net.minecraft.client.renderer.texture.TextureManager"),"func_110577_a",copt("net.minecraft.util.ResourceLocation"));
                targetDrawSkin=mopt(copt("net.minecraft.client.gui.Gui"),"func_152125_a",int.class,int.class,float.class,float.class,int.class,int.class,int.class,int.class,float.class,float.class);
            }
            if(targetTextureManager==null||targetBindSkin==null||targetDrawSkin==null)return false;
            try(VeilRenderState state=VeilRenderState.begin()){
                Object manager=targetTextureManager.invoke(mc);targetBindSkin.invoke(manager,skin);
                cGL.getMethod("func_179098_w").invoke(null);
                cGL.getMethod("func_179131_c",float.class,float.class,float.class,float.class).invoke(null,1f,1f,1f,alpha);
                targetDrawSkin.invoke(null,x,y,8f,8f,8,8,size,size,64f,64f);
                targetDrawSkin.invoke(null,x,y,40f,8f,8,8,size,size,64f,64f);
            }
            return true;
        }catch(Throwable e){logOnce("TargetHUD portrait",e);return false;}
    }
    private static void renderTargetRing(float pt,Object world,Object player,double vx,double vy,double vz)throws Exception{
        renderTargetRingAt(pt,world,player,vx,vy,vz,System.nanoTime());
    }
    private static void renderTargetRingAt(float pt,Object world,Object player,double vx,double vy,double vz,long now)throws Exception{
        Object target=liveVisualTarget(world,player);
        double hold=p(F_TARGETHUD,10)*1e6,age=now-targetVisualAttackAt;
        if(target==null||target!=targetVisualAttackTarget||targetVisualAttackAt==0||age<0||age>hold)return;
        Object mc=mGetMC.invoke(null),bb=mGetBB.invoke(target);if(bb==null)return;
        double dx=entX(target,pt)-fPosX.getDouble(target),dy=entY(target,pt)-fPosY.getDouble(target),dz=entZ(target,pt)-fPosZ.getDouble(target);
        double cx=(mAabbMinX.getDouble(bb)+mAabbMaxX.getDouble(bb))*.5+dx;
        double cz=(mAabbMinZ.getDouble(bb)+mAabbMaxZ.getDouble(bb))*.5+dz;
        double bottom=mAabbMinY.getDouble(bb)+dy,height=Math.max(.1,mAabbMaxY.getDouble(bb)-mAabbMinY.getDouble(bb));
        double radius=Math.max(.2,Math.max(mAabbMaxX.getDouble(bb)-mAabbMinX.getDouble(bb),mAabbMaxZ.getDouble(bb)-mAabbMinZ.getDouble(bb))*.5)*p(F_TARGETHUD,9);
        float alpha=(float)Math.min(1,(hold-age)/Math.min(hold,180e6));
        int dw=fDisplayW.getInt(mc),dh=fDisplayH.getInt(mc);if(dw<=0||dh<=0)return;
        float width=(float)Math.max(1,p(F_TARGETHUD,7));int segments=64;
        float glow=(float)p(F_TARGETHUD,8)/100f;
        boolean motion=interactionMotion&&common(mods[F_TARGETHUD],"UI Motion")>0;
        // Project with the same captured camera and interpolated AABB as ESP.
        float[][] points=new float[segments+1][];
        double fraction=motion?TargetHudState.scanFraction(now,p(F_TARGETHUD,6)):.5;
        double y=bottom+height*(.04+.92*fraction);
        for(int n=0;n<=segments;n++){double angle=2*Math.PI*n/segments;points[n]=worldToScreen(cx+Math.cos(angle)*radius,y,cz+Math.sin(angle)*radius,pt,mc,player,vx,vy,vz);}
        pushPhysicalProjection();
        try(VeilRenderState state=VeilRenderState.begin();GuiClip clip=GuiClip.begin(0,0,dw,dh,dh)){
            float[][] lines=new float[segments][];int[] colors=new int[segments];
            for(int n=0;n<segments;n++){
                float[] a=points[n],b=points[n+1];if(a==null||b==null)continue;
                lines[n]=TargetHudState.clip(a[0],a[1],b[0],b[1],dw,dh);
                colors[n]=fade(VisualStyle.mix(0xFFFFC59A,0xFFB9A2FF,(float)(.5-.5*Math.cos(n*Math.PI*2/segments))),alpha);
            }
            VeilRingStroke.ring(lines,colors,width,glow,alpha);
        }finally{popPhysicalProjection();}
    }

    // ===== HUD: compact visual widgets =====
    // These widgets intentionally share the existing font, dark glass and
    // physical-pixel projection.  They do not install another render hook and
    // do not mutate any camera/world state.
    private static final long widgetSessionStartedAt = System.currentTimeMillis();
    private static final java.util.ArrayDeque<Long> widgetLeftClicks = new java.util.ArrayDeque<Long>();
    private static final java.util.ArrayDeque<Long> widgetRightClicks = new java.util.ArrayDeque<Long>();
    private static boolean widgetLeftWasDown, widgetRightWasDown;
    private static final double[] widgetBpsHistory = new double[42];
    private static int widgetBpsCursor, widgetBpsCount;
    private static long widgetLastBpsSample;

    /** One shared projection/state pair for all eight widgets. */
    private static void renderVisualWidgets() throws Exception {
        if (!mods[F_KEYSTROKES].on && !mods[F_MOTIONSTATS].on && !mods[F_COORDINATES].on
                && !mods[F_COMPASS].on && !mods[F_SESSIONINFO].on && !mods[F_ITEMSTATUS].on
                && !mods[F_VITALS].on && !mods[F_HOTBARACCENT].on) return;
        Object mc = mGetMC.invoke(null);
        Object player = fPlayer.get(mc);
        if (player == null) return;
        int dw = fDisplayW.getInt(mc), dh = fDisplayH.getInt(mc);
        if (dw <= 0 || dh <= 0) return;
        layoutForFake(dw, dh, uiScale(dh));
        initFont();

        boolean pushed = false;
        try {
            pushPhysicalProjection();
            pushed = true;
            if (mods[F_HOTBARACCENT].on) try { renderHotbarAccent(mc, player, dw, dh); }
            catch (Throwable t) { logOnce("HotbarAccent", t); }
            if (mods[F_KEYSTROKES].on) try { renderKeystrokes(dw, dh); }
            catch (Throwable t) { logOnce("Keystrokes", t); }
            if (mods[F_MOTIONSTATS].on) try { renderMotionStats(mc, player, dw, dh); }
            catch (Throwable t) { logOnce("MotionStats", t); }
            if (mods[F_COORDINATES].on) try { renderCoordinates(player, dw, dh); }
            catch (Throwable t) { logOnce("Coordinates", t); }
            if (mods[F_COMPASS].on) try { renderCompass(player, dw, dh); }
            catch (Throwable t) { logOnce("Compass", t); }
            if (mods[F_SESSIONINFO].on) try { renderSessionInfo(dw, dh); }
            catch (Throwable t) { logOnce("SessionInfo", t); }
            if (mods[F_ITEMSTATUS].on) try { renderItemStatus(player, dw, dh); }
            catch (Throwable t) { logOnce("ItemStatus", t); }
            if (mods[F_VITALS].on) try { renderVitals(player, dw, dh); }
            catch (Throwable t) { logOnce("Vitals", t); }
        } finally {
            if (pushed) popPhysicalProjection();
        }
    }

    /** Top-left placement from independent X/Y percentage parameters. */
    private static int[] widgetXY(int mod, int w, int h, int dw, int dh) {
        int x = (int) Math.round((dw - w) * p(mod, 0) / 100.0);
        int y = (int) Math.round((dh - h) * p(mod, 1) / 100.0);
        int px = Math.max(0, Math.min(Math.max(0, dw - w), x));
        int py = Math.max(0, Math.min(Math.max(0, dh - h), y));
        rememberWidgetBounds(mod, px, py, w, h);
        return new int[]{px, py};
    }

    private static void rememberWidgetBounds(int mod, int x, int y, int w, int h) {
        if (mod < 0 || mod >= mods.length) return;
        widgetBX[mod] = x; widgetBY[mod] = y;
        widgetBW[mod] = Math.max(0, w); widgetBH[mod] = Math.max(0, h);
    }

    private static int widgetXParam(int mod) {
        return mod == F_WATERMARK ? 3 : mod == F_PING ? 4 : 0;
    }

    private static int widgetYParam(int mod) {
        return mod == F_WATERMARK ? 4 : mod == F_PING ? 5 : 1;
    }

    private static boolean draggableWidget(int mod) {
        return mod == F_PING || mod == F_WATERMARK || mod == F_KEYSTROKES
                || mod == F_MOTIONSTATS || mod == F_COORDINATES || mod == F_COMPASS
                || mod == F_SESSIONINFO || mod == F_ITEMSTATUS || mod == F_VITALS
                || mod == F_HOTBARACCENT || mod == F_TARGETHUD;
    }

    private static int widgetUnit(float scale) {
        return Math.max(1, Math.round(S * scale));
    }

    private static float backgroundOpacity(int module){return (float)common(mods[module],"Background Opacity")/100f;}
    private static float widgetGlow(int module){return (float)common(mods[module],"Glow Strength")/100f;}
    private static void widgetSurface(int module,int x,int y,int w,int h,int radius,float animation)throws Exception{
        widgetSurface(module,x,y,w,h,radius,animation,1f);
    }
    private static void widgetSurface(int module,int x,int y,int w,int h,int radius,float alpha,float glowWeight)throws Exception {
        float plate=backgroundOpacity(module);
        if(hudFrost&&!guiOpen&&plate>0)FrostPass.draw(physW,physH,x,y,x+w,y+h,radius,glassBlurRadius*1.5f,alpha*plate);
        // Exterior glow has its own strength. Background opacity never scales it.
        frameGlow(x,y,w,h,radius,Math.min(30*S,bloomSpread*S),deckAccent(),deckAccentEnd(),bloom*.28f*alpha*widgetGlow(module)*glowWeight);
        if(plate<=0)return;
        roundedRect(x,y,x+w,y+h,radius,fade(0xFF191622,alpha*plate));
        int edge=Math.max(1,radius);
        deckGradient(x+edge,y,Math.max(1,w-2*edge),Math.max(1,S),fade(deckAccent(),.30f*alpha*plate),fade(deckAccentEnd(),.12f*alpha*plate));
    }
    private static void veilWidgetSurface(int module,int x,int y,int w,int h,float alpha)throws Exception{
        int feather=Math.max(18*S,Math.min(32*S,h));
        try(VeilRenderState state=VeilRenderState.begin()){
            VeilWidgetBackdrop.draw(x,y,w,h,feather,fade(0xFF15111D,(module==F_TARGETHUD?.30f:.12f)*alpha*backgroundOpacity(module)));
            // Ambient illumination is independent of the background slider, without a rim.
            SoftEffects.glow(x-feather,y-feather,w+2*feather,h+2*feather,
                    deckAccentEnd(),.055f*bloom*alpha*widgetGlow(module));
        }
    }
    private static int veilColumnWidth(String value,float sc){
        int split=value.indexOf("  ");
        if(split<0)return textWScaled(value,sc);
        return Math.max(textWScaled(value.substring(0,split),sc*.7f),textWScaled(value.substring(split+2),sc));
    }
    private static void veilColumn(String value,int x,int y,int u,float sc){
        int split=value.indexOf("  ");
        if(split<0){veilWidgetLine(value,x,y,42*u,sc);return;}
        textScaled(value.substring(0,split),x,hudTextY(y,17*u,sc*.7f),0xF2E4DCEB,sc*.7f);
        textScaled(value.substring(split+2),x,hudTextY(y+19*u,23*u,sc),0xFFF8F5FA,sc);
    }
    private static void veilWidgetLine(String value,int x,int y,int h,float sc){
        int split=value.indexOf("  ");
        if(split<0){textScaled(value,x,hudTextY(y,h,sc),0xFFF8F5FA,sc);return;}
        String label=value.substring(0,split),number=value.substring(split+2);
        textScaled(label,x,hudTextY(y,h,sc),0xF2E4DCEB,sc);
        textScaled(number,x+textWScaled(label+"  ",sc),hudTextY(y,h,sc),0xFFF8F5FA,sc);
    }
    private static int hudTextY(int y,int h,float scale) {
        if(OverlayFont.active())return y+h/2-OverlayFont.center(17*S*scale);
        return y+h/2-Math.round((TtfFont.ready()?INK_CENTER:4*S)*scale);
    }
    private static void widgetPanel(int x,int y,int w,int h,int u,boolean background)throws Exception{
        if(background)glassPanel(x,y,x+w,y+h,Math.max(2,4*u),0xD70A1423,true);
    }
    private static void widgetPanel(int module,int x,int y,int w,int h,int u,boolean background)throws Exception{
        if(background)veilWidgetSurface(module,x,y,w,h,1f);
    }

    private static void widgetTextCentered(String s, int x, int y, int w, int h, int color, float scale) {
        int tx = x + (w - textWScaled(s, scale)) / 2;
        textScaled(s, tx, hudTextY(y, h, scale), color, scale);
    }

    private static void trimClicks(java.util.ArrayDeque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst().longValue() > 1000L) q.removeFirst();
    }

    private static void updateWidgetClicks(long now) {
        boolean left = mouseDown(0), right = mouseDown(1);
        if (left && !widgetLeftWasDown) widgetLeftClicks.addLast(Long.valueOf(now));
        if (right && !widgetRightWasDown) widgetRightClicks.addLast(Long.valueOf(now));
        widgetLeftWasDown = left;
        widgetRightWasDown = right;
        trimClicks(widgetLeftClicks, now);
        trimClicks(widgetRightClicks, now);
    }

    private static void drawWidgetKey(String label, int x, int y, int w, int h,
                                      boolean down, float scale) throws Exception {
        int radius=Math.max(2,widgetUnit(scale)*3);
        float plate=p(F_KEYSTROKES,6)>0?backgroundOpacity(F_KEYSTROKES):0;
        roundedRect(x,y,x+w,y+h,radius,fade(down?0xFF47323E:0xFF211B2B,plate*.64f));
        if(down){
            frameGlow(x,y,w,h,radius,Math.max(4,6*widgetUnit(scale)),0xFFFFC59A,0xFFF28CB0,.20f*bloom*widgetGlow(F_KEYSTROKES));
            int line=Math.max(1,widgetUnit(scale));
            // The active key accent is independent of background opacity.
            roundedRect(x+radius,y,x+w-radius,y+line,0,0xBFFFC59A);
        }
        float fit=fitWidgetScale(label,Math.max(1,w-6*widgetUnit(scale)),scale);
        try(GuiClip clip=GuiClip.begin(x,y,w,h,physH)){
            widgetTextCentered(label, x, y, w, h, down ? 0xFFFFFFFF : 0xFFDCE7F4, fit);
        }
    }

    private static float fitWidgetScale(String text,int width,float scale){
        for(int i=0;i<24&&textWScaled(text,scale)>width;i++)scale*=.94f;
        return scale;
    }
    private static void drawWidgetMouse(String label,int clicks,int x,int y,int w,int h,boolean down,float sc,boolean cps)throws Exception{
        drawWidgetKey(cps?"":label,x,y,w,h,down,sc*.75f);
        if(!cps)return;
        float small=fitWidgetScale("999+ CPS",w-6*widgetUnit(sc),sc*.62f);
        try(GuiClip clip=GuiClip.begin(x,y,w,h,physH)){
            widgetTextCentered(label,x,y+2*widgetUnit(sc),w,h/2,down?0xFFFFFFFF:0xFFDFD7E5,small);
            widgetTextCentered((clicks>999?"999+":String.valueOf(clicks))+" CPS",x,y+h/2,w,h/2-2*widgetUnit(sc),0xFFFFFFFF,small);
        }
    }

    /** WASD, Space, LMB/RMB and a true one-second CPS window. */
    private static void renderKeystrokes(int dw, int dh) throws Exception {
        float sc = (float) p(F_KEYSTROKES, 2);
        int u = widgetUnit(sc), key = Math.max(20*u,Math.round(24*S*sc)), gap = 3 * u, pad = 4 * u;
        boolean showMouse = p(F_KEYSTROKES, 3) > 0;
        boolean showCps = p(F_KEYSTROKES, 4) > 0;
        boolean showSpace = p(F_KEYSTROKES, 5) > 0;
        int minMouse=textWScaled("999+ CPS",sc*.62f)+6*u;
        if(showMouse&&showCps)key=Math.max(key,(int)Math.ceil((2*minMouse-gap)/3d));
        int rows = 2 + (showSpace ? 1 : 0) + (showMouse ? 1 : 0);
        int mouseH=showCps?Math.max(key,Math.round(36*S*sc)):key;
        int w = pad * 2 + key * 3 + gap * 2;
        int h = pad * 2 + rows * key + (rows - 1) * gap+(showMouse?mouseH-key:0);
        int[] xy = widgetXY(F_KEYSTROKES, w, h, dw, dh);
        int x = xy[0], y = xy[1];
        // Separate keys float directly over the scene; no enclosing panel.
        int cx = x + pad, cy = y + pad;
        drawWidgetKey("W", cx + key + gap, cy, key, key, keyDown(17), sc);
        cy += key + gap;
        drawWidgetKey("A", cx, cy, key, key, keyDown(30), sc);
        drawWidgetKey("S", cx + key + gap, cy, key, key, keyDown(31), sc);
        drawWidgetKey("D", cx + (key + gap) * 2, cy, key, key, keyDown(32), sc);
        if (showSpace) {
            cy += key + gap;
            drawWidgetKey("SPACE", cx, cy, key * 3 + gap * 2, key, keyDown(57), sc);
        }
        long now = System.currentTimeMillis();
        updateWidgetClicks(now);
        if (showMouse) {
            cy += key + gap;
            int half = (key * 3 + gap) / 2;
            drawWidgetMouse("LMB",widgetLeftClicks.size(),cx,cy,half,mouseH,mouseDown(0),sc,showCps);
            drawWidgetMouse("RMB",widgetRightClicks.size(),cx+half+gap,cy,key*3+gap*2-half-gap,mouseH,mouseDown(1),sc,showCps);
        }
    }

    private static void sampleBps(double bps, long now) {
        if (now - widgetLastBpsSample < 50L) return;
        widgetLastBpsSample = now;
        widgetBpsHistory[widgetBpsCursor] = bps;
        widgetBpsCursor = (widgetBpsCursor + 1) % widgetBpsHistory.length;
        if (widgetBpsCount < widgetBpsHistory.length) widgetBpsCount++;
    }

    /** Horizontal blocks per second; isolated so the displayed value is numerically testable. */
    static double widgetBps(double motionX, double motionZ) {
        return Math.sqrt(motionX * motionX + motionZ * motionZ) * 20.0;
    }

    /** FPS plus horizontal/vertical blocks per second and a bounded 42-sample graph. */
    private static void renderMotionStats(Object mc, Object player, int dw, int dh) throws Exception {
        float sc = (float) p(F_MOTIONSTATS, 2);
        int u = widgetUnit(sc), pad = 5 * u, lineH = Math.max(12 * u, Math.round(ROW_NAME * sc));
        double mx = fMotionX.getDouble(player), my = fMotionY.getDouble(player), mz = fMotionZ.getDouble(player);
        double bps = widgetBps(mx, mz);
        sampleBps(bps, System.currentTimeMillis());
        java.util.List<String> lines = new java.util.ArrayList<String>();
        if (p(F_MOTIONSTATS, 3) > 0) {
            int fps = fDebugFPS == null ? 0 : fDebugFPS.getInt(mc);
            lines.add("FPS  " + fps);
        }
        if (p(F_MOTIONSTATS, 4) > 0) lines.add("BPS  " + String.format(java.util.Locale.ROOT, "%.2f", bps));
        if (p(F_MOTIONSTATS, 5) > 0) lines.add("Y/S  " + String.format(java.util.Locale.ROOT, "%+.2f", my * 20.0));
        if (lines.isEmpty()) lines.add("MOTION");
        boolean graph = p(F_MOTIONSTATS, 6) > 0;
        int graphH = graph ? 24 * u : 0;
        int gap=18*u,w=pad*2+Math.max(0,lines.size()-1)*gap;
        for(String value:lines)w+=Math.max(32*u,veilColumnWidth(value,sc));
        w=Math.max(w,92*u);int h=pad*2+42*u+graphH+(graph?6*u:0);
        int[] xy=widgetXY(F_MOTIONSTATS,w,h,dw,dh);
        widgetPanel(F_MOTIONSTATS,xy[0],xy[1],w,h,u,p(F_MOTIONSTATS,7)>0);
        int tx=xy[0]+pad,ty=xy[1]+pad+42*u;
        for(String value:lines){veilColumn(value,tx,xy[1]+pad,u,sc);tx+=Math.max(32*u,veilColumnWidth(value,sc))+gap;}
        if (graph) {
            int gx = xy[0] + pad, gy = ty + 2 * u, gw = w - pad * 2;
            mDrawRect.invoke(null, gx, gy + graphH - Math.max(1, u), gx + gw, gy + graphH, fade(0x334F6A88,backgroundOpacity(F_MOTIONSTATS)));
            int n = Math.max(1, widgetBpsCount), bw = Math.max(1, gw / widgetBpsHistory.length);
            for (int i = 0; i < widgetBpsCount; i++) {
                int idx = (widgetBpsCursor - widgetBpsCount + i + widgetBpsHistory.length) % widgetBpsHistory.length;
                int bh = (int) Math.round(Math.min(1.0, widgetBpsHistory[idx] / 12.0) * (graphH - 2 * u));
                int bx = gx + (gw - n * bw) + i * bw;
                mDrawRect.invoke(null, bx, gy + graphH - bh, Math.min(gx + gw, bx + bw), gy + graphH, fade(ACCENT(), 0.72f));
            }
        }
    }

    private static String widgetCoord(double v, int precision) {
        if (precision <= 0) return String.valueOf((long) Math.floor(v));
        return String.format(java.util.Locale.ROOT, precision == 1 ? "%.1f" : "%.2f", v);
    }

    private static void renderCoordinates(Object player,int dw,int dh)throws Exception{
        float sc=(float)p(F_COORDINATES,2);int precision=(int)p(F_COORDINATES,3),u=widgetUnit(sc),pad=5*u,gap=18*u;
        String[] values={widgetCoord(fPosX.getDouble(player),precision),widgetCoord(fPosY.getDouble(player),precision),widgetCoord(fPosZ.getDouble(player),precision)};
        String[] labels={"X","Y","Z"};int[] widths=new int[3];int w=pad*2+gap*2,h=pad*2+42*u;
        for(int k=0;k<3;k++){widths[k]=Math.max(24*u,textWScaled(values[k],sc));w+=widths[k];}
        int[] xy=widgetXY(F_COORDINATES,w,h,dw,dh);int x=xy[0]+pad,y=xy[1]+pad;
        widgetPanel(F_COORDINATES,xy[0],xy[1],w,h,u,p(F_COORDINATES,4)>0);
        for(int k=0;k<3;k++){
            textScaled(labels[k],x,hudTextY(y,17*u,sc*.70f),0xF2E4DCEB,sc*.70f);
            textScaled(values[k],x,hudTextY(y+19*u,23*u,sc),0xFFF8F5FA,sc);x+=widths[k]+gap;
        }
    }

    static double widgetHeading(double yaw) {
        double h = yaw % 360.0;
        return h < 0.0 ? h + 360.0 : h;
    }

    static String widgetDirection(double yaw) {
        String[] d = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return d[((int) Math.floor((widgetHeading(yaw) + 22.5) / 45.0)) & 7];
    }

    private static double widgetAngleDelta(double a, double b) {
        double d = (a - b) % 360.0;
        if (d > 180.0) d -= 360.0;
        if (d < -180.0) d += 360.0;
        return d;
    }

    private static String compassMark(int deg) {
        int d = ((deg % 360) + 360) % 360;
        if ((d % 45) != 0) return "";
        return widgetDirection(d);
    }

    /** Select a label cadence that leaves a real pixel gap at narrow widths. */
    static int compassLabelStep(int innerWidth, int widestLabel, int gap) {
        return innerWidth / 4 >= widestLabel + gap ? 45 : 90;
    }

    /** A clipped 180-degree tape, centered on the player's real camera yaw. */
    private static void renderCompass(Object player, int dw, int dh) throws Exception {
        float sc = (float) p(F_COMPASS, 2);
        int u = widgetUnit(sc), pad = 5 * u;
        int w = Math.max(90 * u, Math.round((float) p(F_COMPASS, 3) * S * sc));
        boolean showDegrees = p(F_COMPASS, 4) > 0;
        // TTF glyph ink is about 20 scaled pixels high.  The previous 30-unit
        // panel placed the tape labels at y+11 and the footer at y+17, so both
        // rows occupied almost the same pixels.  Give each row its own baseline.
        int h = (showDegrees ? 64 : 38) * u;
        int[] xy = widgetXY(F_COMPASS, w, h, dw, dh);
        int x = xy[0], y = xy[1];
        widgetPanel(F_COMPASS,x, y, w, h, u, p(F_COMPASS, 5) > 0);
        double heading = widgetHeading(fYaw.getFloat(player));
        int inner = Math.max(1, w - pad * 2), center = x + w / 2;
        int widest = 0;
        for (String cardinal : new String[]{"SW", "NW", "NE", "SE"}) {
            widest = Math.max(widest, textWScaled(cardinal, sc));
        }
        int labelGap = Math.max(2, 4 * u);
        int labelStep = compassLabelStep(inner, widest, labelGap);
        int lastLabelRight = Integer.MIN_VALUE;
        int start = (int) Math.floor((heading - 90.0) / 15.0) * 15;
        for (int deg = start; deg <= heading + 90.0; deg += 15) {
            double delta = widgetAngleDelta(deg, heading);
            if (Math.abs(delta) > 90.001) continue;
            int tx = center + (int) Math.round(delta * inner / 180.0);
            boolean major = (((deg % 45) + 45) % 45) == 0;
            int th = major ? 6 * u : 3 * u;
            mDrawRect.invoke(null, tx, y + 4 * u, tx + Math.max(1, u), y + 4 * u + th,
                    major ? 0xFFDDEBFA : 0x667E94AC);
            String mark = compassMark(deg);
            int normalized = ((deg % 360) + 360) % 360;
            if (!mark.isEmpty() && normalized % labelStep == 0) {
                int mw = textWScaled(mark, sc);
                int left = tx - mw / 2, right = left + mw;
                if (left >= x + pad && right <= x + w - pad && left >= lastLabelRight + labelGap) {
                    textScaled(mark, left, y + 12 * u, 0xFFFFFFFF, sc);
                    lastLabelRight = right;
                }
            }
        }
        roundedRect(center - u, y + 2 * u, center + u, y + 9 * u, u, ACCENT());
        if (showDegrees) {
            String s = Math.round(heading) + "°";
            textScaled(s, center - textWScaled(s, sc*.78f) / 2, hudTextY(y+36*u,24*u,sc*.78f), 0xF2E4DCEB, sc*.78f);
        }
    }

    static String widgetDuration(long millis) {
        long sec = Math.max(0L, millis / 1000L);
        long h = sec / 3600L, m = (sec / 60L) % 60L, s = sec % 60L;
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d", h, m, s);
    }

    private static void renderSessionInfo(int dw, int dh) throws Exception {
        float sc = (float) p(F_SESSIONINFO, 2);
        java.util.List<String> lines = new java.util.ArrayList<String>();
        long now = System.currentTimeMillis();
        if (p(F_SESSIONINFO, 3) > 0) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                    p(F_SESSIONINFO, 5) > 0 ? "HH:mm:ss" : "hh:mm:ss a", java.util.Locale.ROOT);
            lines.add("CLOCK  " + fmt.format(new java.util.Date(now)));
        }
        if (p(F_SESSIONINFO, 4) > 0) lines.add("SESSION  " + widgetDuration(now - widgetSessionStartedAt));
        if (lines.isEmpty()) lines.add("SESSION");
        int u=widgetUnit(sc),pad=5*u,gap=20*u,w=pad*2+Math.max(0,lines.size()-1)*gap;
        for(String value:lines)w+=veilColumnWidth(value,sc);
        int h=pad*2+42*u;int[] xy=widgetXY(F_SESSIONINFO,w,h,dw,dh);
        widgetPanel(F_SESSIONINFO,xy[0],xy[1],w,h,u,p(F_SESSIONINFO,6)>0);
        int tx=xy[0]+pad;
        for(String value:lines){veilColumn(value,tx,xy[1]+pad,u,sc);tx+=veilColumnWidth(value,sc)+gap;}
    }

    /** 1.8.9 has no getHeldItemMainhand; use the real selected hotbar slot as fallback. */
    private static Object widgetHeldStack(Object player) {
        Object held = heldItem(player);
        if (held != null) return held;
        java.util.List<?> inv = mainInv(player);
        return invAt(inv, hotbarIndex(player));
    }

    private static void renderItemStatus(Object player, int dw, int dh) throws Exception {
        float sc = (float) p(F_ITEMSTATUS, 2);
        Object stack = widgetHeldStack(player);
        String name = "Empty hand";
        if (stack != null && mGetStackName != null) {
            Object n = mGetStackName.invoke(stack);
            if (n != null && n.toString().length() > 0) name = n.toString();
        }
        java.util.List<String> lines = new java.util.ArrayList<String>();
        lines.add(name);
        if (stack != null && p(F_ITEMSTATUS, 3) > 0) lines.add("COUNT  " + stackCount(stack));
        int remaining = -1, max = -1;
        if (stack != null && p(F_ITEMSTATUS, 4) > 0 && mStackDamageable != null
                && Boolean.TRUE.equals(mStackDamageable.invoke(stack)) && mStackDamage != null && mStackMaxDamage != null) {
            max = ((Number) mStackMaxDamage.invoke(stack)).intValue();
            remaining = Math.max(0, max - ((Number) mStackDamage.invoke(stack)).intValue());
            lines.add("DUR  " + remaining + " / " + max);
        }
        int u = widgetUnit(sc), pad = 5 * u, lineH = Math.max(12 * u, Math.round(ROW_NAME * sc));
        int w = 0; for (String s : lines) w = Math.max(w, textWScaled(s, sc));
        w = Math.max(w + pad * 2, 92 * u);
        int barH = remaining >= 0 && max > 0 ? 5 * u : 0;
        int h = pad * 2 + lines.size() * lineH + barH;
        int[] xy = widgetXY(F_ITEMSTATUS, w, h, dw, dh);
        widgetPanel(F_ITEMSTATUS,xy[0], xy[1], w, h, u, p(F_ITEMSTATUS, 5) > 0);
        int ty = xy[1] + pad;
        for (String s : lines) { veilWidgetLine(s,xy[0]+pad,ty,lineH,sc); ty += lineH; }
        if (barH > 0) {
            int bx = xy[0] + pad, bw = w - pad * 2;
            roundedRect(bx, ty, bx + bw, ty + 3 * u, u, fade(0x55233242,backgroundOpacity(F_ITEMSTATUS)));
            int fill = (int) Math.round(bw * remaining / (double) max);
            roundedRect(bx, ty, bx + Math.max(u, fill), ty + 3 * u, u, ACCENT());
        }
    }

    private static int widgetArmor(Object player) {
        try { return mGetArmorValue == null ? -1 : ((Number) mGetArmorValue.invoke(player)).intValue(); }
        catch (Throwable t) { return -1; }
    }

    private static void drawVitalBar(String label, double value, double max, int color,
                                     int x, int y, int w, int h, int u, float sc) throws Exception {
        String s = label + "  " + Math.round(value) + " / " + Math.round(max);
        veilWidgetLine(s,x,y,h,sc);
        int labelW = Math.max(Math.round(140 * S * sc), textWScaled(s, sc) + 12 * u);
        int bx = x + labelW, bw = Math.max(8 * u, w - labelW);
        int by = y + h / 2 - u;
        roundedRect(bx, by, bx + bw, by + 2 * u, u, 0x333C3248);
        int fill = (int) Math.round(bw * Math.max(0.0, Math.min(1.0, value / Math.max(1.0, max))));
        int tint = sunsetPalette ? sunset(label.equals("HP") ? 0.4 : label.equals("FOOD") ? 0.6 : 0.05, rainbowMode) : color;
        SoftEffects.uniformLine(bx, by + u, bx + fill, by + u, tint, 5 * u, bloom * 0.16f*widgetGlow(F_VITALS));
        if(fill>0)roundedRect(bx, by, bx + fill, by + 2 * u, u, tint);
    }

    private static void renderVitals(Object player, int dw, int dh) throws Exception {
        float sc = (float) p(F_VITALS, 2);
        double hp = ((Number) mGetHealth.invoke(player)).doubleValue();
        double hpMax = mGetMaxHealth == null ? 20.0 : ((Number) mGetMaxHealth.invoke(player)).doubleValue();
        int food = foodLevel(player), armor = widgetArmor(player);
        java.util.List<String> names = new java.util.ArrayList<String>();
        if (p(F_VITALS, 3) > 0) names.add("HP");
        if (p(F_VITALS, 4) > 0 && food >= 0) names.add("FOOD");
        if (p(F_VITALS, 5) > 0 && armor >= 0) names.add("ARMOR");
        if (names.isEmpty()) names.add("VITALS");
        boolean bars = (int) p(F_VITALS, 6) == 0;
        int u = widgetUnit(sc), pad = 5 * u, lineH = Math.max(13 * u, Math.round(ROW_NAME * sc));
        int w = bars ? 230 * u : 0;
        String textLine = "";
        if (!bars) {
            java.util.List<String> vals = new java.util.ArrayList<String>();
            if (p(F_VITALS, 3) > 0) vals.add("HP " + Math.round(hp));
            if (p(F_VITALS, 4) > 0 && food >= 0) vals.add("FOOD " + food);
            if (p(F_VITALS, 5) > 0 && armor >= 0) vals.add("ARMOR " + armor);
            if (vals.isEmpty()) vals.add("VITALS");
            StringBuilder sb = new StringBuilder();
            for (String s : vals) { if (sb.length() > 0) sb.append("   "); sb.append(s); }
            textLine = sb.toString();
            w = textWScaled(textLine, sc) + pad * 2;
        }
        int h = pad * 2 + (bars ? names.size() * lineH : lineH);
        int[] xy = widgetXY(F_VITALS, w, h, dw, dh);
        widgetPanel(F_VITALS,xy[0], xy[1], w, h, u, p(F_VITALS, 7) > 0);
        if (!bars) {
            textScaled(textLine, xy[0] + pad, textY(xy[1] + pad, lineH), 0xFFE8F0FA, sc);
            return;
        }
        int ty = xy[1] + pad, innerW = w - pad * 2;
        if (p(F_VITALS, 3) > 0) { drawVitalBar("HP", hp, hpMax, 0xFFEF6272, xy[0] + pad, ty, innerW, lineH, u, sc); ty += lineH; }
        if (p(F_VITALS, 4) > 0 && food >= 0) { drawVitalBar("FOOD", food, 20, 0xFFF2B65B, xy[0] + pad, ty, innerW, lineH, u, sc); ty += lineH; }
        if (p(F_VITALS, 5) > 0 && armor >= 0) drawVitalBar("ARMOR", armor, 20, 0xFF6FB6FF, xy[0] + pad, ty, innerW, lineH, u, sc);
    }

    /** Reproduce legacy ScaledResolution's factor without requiring an open GuiScreen. */
    private static int widgetGuiScale(Object mc, int dw, int dh) {
        int requested = 0;
        try {
            Object settings = fGameSettings == null ? null : fGameSettings.get(mc);
            if (settings != null && fGuiScaleSetting != null) requested = fGuiScaleSetting.getInt(settings);
        } catch (Throwable ignored) { }
        if (requested <= 0) requested = 1000; // vanilla "Auto"
        int scale = 1;
        while (scale < requested && dw / (scale + 1) >= 320 && dh / (scale + 1) >= 240) scale++;
        return Math.max(1, Math.min(8, scale));
    }

    /** A non-invasive frame around the vanilla selected hotbar slot. */
    private static void renderHotbarAccent(Object mc, Object player, int dw, int dh) throws Exception {
        int slot = hotbarIndex(player);
        if (slot < 0 || slot > 8) return;
        int gui = widgetGuiScale(mc, dw, dh);
        float sc = (float) p(F_HOTBARACCENT, 2);
        int sw = Math.max(8, Math.round(20 * gui * sc));
        int sh = Math.max(8, Math.round(22 * gui * sc));
        int center = dw / 2 - 91 * gui + (slot * 20 + 10) * gui + (int) Math.round(p(F_HOTBARACCENT, 0) * gui);
        int bottom = dh - (int) Math.round(p(F_HOTBARACCENT, 1) * gui);
        int x1 = center - sw / 2, y1 = bottom - sh;
        int mode = (int) p(F_HOTBARACCENT, 6);
        int color = mode == 2 ? 0xFFFFFFFF : (mode == 1 ? rainbowColor(slot, 9) : ACCENT());
        float alpha = (float) (p(F_HOTBARACCENT, 4) / 100.0);
        if (p(F_HOTBARACCENT, 5) > 0) alpha *= 0.72f + 0.28f * (float) ((Math.sin(System.currentTimeMillis() / 180.0) + 1.0) * 0.5);
        int width = Math.max(1, (int) Math.round(p(F_HOTBARACCENT, 3) * gui));
        rememberWidgetBounds(F_HOTBARACCENT, x1 - width * 2, y1 - width * 2,
                sw + width * 4, sh + width * 4);
        softGlow(x1 - 20 * S, y1 - 16 * S, sw + 40 * S, sh + 32 * S, color, bloom * alpha*widgetGlow(F_HOTBARACCENT));
        VeilWidgetBackdrop.draw(x1,y1,sw,sh,Math.max(4,gui*3),fade(0xFF201927,.35f*backgroundOpacity(F_HOTBARACCENT)));
        mDrawRect.invoke(null, x1, y1, x1 + sw, y1 + width, fade(color, alpha));
        mDrawRect.invoke(null, x1, y1 + sh - width, x1 + sw, y1 + sh, fade(color, alpha));
        mDrawRect.invoke(null, x1, y1, x1 + width, y1 + sh, fade(color, alpha));
        mDrawRect.invoke(null, x1 + sw - width, y1, x1 + sw, y1 + sh, fade(color, alpha));
    }

    // ===== NameProtect =====
    /** 替换后的显示名 (从文件读) */
    private static String nameProtectCache = null;

    /**
     * 名字保护: 把聊天里自己的名字换成别的字。参数: 0=Mode 1=Min Length
     *
     * 这个方法由 LiteAgent 插进 ChatComponentText 的构造器 —— 也就是"每个聊天文本节点
     * 被创建的那一刻"。所有聊天消息 (包括别人说的、系统提示) 都要经过这里。
     *
     * 为什么钩构造器而不是 FontRenderer.drawString: 后者每帧要被调几百次, 每次都做一次
     * 字符串替换在游戏里是实打实的开销; 构造器只在消息到达时跑几次。而且改在构造期意味着
     * 文本树里存的就已经是替换后的内容, 后面所有渲染/复制路径都自然一致。
     *
     * Mode: Self = 只换自己 (默认); Everyone = 连其他在线玩家的名字一起换。
     * Min Length: 太短的名字 (比如 3 个字母) 会在正常英文里频繁误伤, 所以设个下限。
     *
     * 替换文本从 %USERPROFILE%\.azclient\nameprotect.txt 读, 和 ChatSuffix 一个路子。
     * 出任何问题都返回原文 —— 聊天显示坏掉比名字没藏住严重得多。
     */
    public static String nameProtect(String text) {
        try {
            if (text == null || text.isEmpty()) return text;
            // mGetMC 在 ensure() 之前是 null —— TextComponentString 可能在 Hook 初始化完成前
            // 就被构造 (而且构造它可能正是触发 Hook.<clinit> 的那一下)。这里先挡一道,
            // 免得走异常路径。
            if (mGetMC == null) return text;
            if (!mods[F_NAMEPROTECT].on) return text;
            Object mc = mGetMC.invoke(null);
            Object player = mc == null ? null : fPlayer.get(mc);
            if (player == null) return text;              // 不在游戏里就不动 (主菜单等)
            String alias = nameProtectText();
            if (alias.isEmpty()) return text;
            int minLen = (int) p(F_NAMEPROTECT, 1);

            String out = replaceName(text, entityLabel(player), alias, minLen);
            if ((int) p(F_NAMEPROTECT, 0) == 1 && mGetConnection != null && mGetPlayerInfoMap != null) {
                Object conn = mGetConnection.invoke(mc);
                Object map = conn == null ? null : mGetPlayerInfoMap.invoke(conn);
                if (map instanceof java.util.Collection) {
                    for (Object info : (java.util.Collection<?>) map) {
                        if (info == null || mGetGameProfile == null || mGetProfileName == null) continue;
                        Object gp = mGetGameProfile.invoke(info);
                        if (gp == null) continue;
                        Object nm = mGetProfileName.invoke(gp);
                        if (nm instanceof String) out = replaceName(out, (String) nm, alias, minLen);
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            logOnce("nameprotect", t);
            return text;
        }
    }

    /** 大小写不敏感地整体替换一个名字。短于 minLen 的不动。 */
    private static String replaceName(String text, String name, String alias, int minLen) {
        if (name == null || name.length() < minLen || name.isEmpty()) return text;
        int idx = indexOfIgnoreCase(text, name);
        if (idx < 0) return text;
        StringBuilder sb = new StringBuilder(text.length() + 16);
        int from = 0;
        while (idx >= 0) {
            sb.append(text, from, idx).append(alias);
            from = idx + name.length();
            idx = indexOfIgnoreCase(text, name, from);
        }
        sb.append(text, from, text.length());
        return sb.toString();
    }

    private static int indexOfIgnoreCase(String hay, String needle) {
        return indexOfIgnoreCase(hay, needle, 0);
    }

    private static int indexOfIgnoreCase(String hay, String needle, int from) {
        int n = needle.length();
        for (int i = Math.max(0, from); i + n <= hay.length(); i++) {
            if (hay.regionMatches(true, i, needle, 0, n)) return i;
        }
        return -1;
    }

    /** 读替换文本。找不到文件就写一份默认的出来, 免得用户不知道要建哪个文件。 */
    private static String nameProtectText() {
        if (nameProtectCache != null) return nameProtectCache;
        String def = "Player";
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.home"), ".azclient");
            java.io.File f = new java.io.File(dir, "nameprotect.txt");
            if (!f.isFile()) {
                dir.mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write(def + "\n");
                w.close();
                log("NameProtect: 已生成 " + f.getAbsolutePath());
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line = r.readLine();
            r.close();
            if (line != null && !line.trim().isEmpty()) def = line.trim();
            nameProtectCache = def;
        } catch (Throwable t) {
            nameProtectCache = def;
        }
        return nameProtectCache;
    }

    // ===== ChatSuffix =====
    /** 后缀文本缓存 (从文件读, 读不到就用默认值) */
    private static String chatSuffixCache = null;

    /**
     * 聊天后缀。参数: 0=Mode 1=Space 2=Case
     *
     * 这个方法由 LiteAgent 插进 EntityPlayerSP.sendChatMessage 的开头 —— 出站消息在这里
     * 被改写, 然后照常走原版的发包逻辑。所以要加后缀就绕不开一个字节码钩子:
     * 聊天内容从输入框到网络包中间没有任何可拦截的客户端字段。
     *
     * 后缀文本从 {@code %USERPROFILE%\.azclient\suffix.txt} 读 (整行, 去掉首尾空白)。
     * 为什么用文件: 参数系统里没有字符串类型, 为了一个后缀加一整套文本输入控件不值当。
     * 文件不存在时用默认值, 启动日志里会打印实际路径。
     *
     * 出任何问题都返回原消息 —— 聊天坏掉比没有后缀严重得多。
     */
    public static String chatSuffix(String msg) {
        try {
            if (msg == null || msg.isEmpty()) return msg;
            if (!mods[F_CHATSUFFIX].on) return msg;
            String sfx = chatSuffixText();
            if (sfx == null || sfx.isEmpty()) return msg;
            int mode = (int) p(F_CHATSUFFIX, 2);
            if (mode == 1) sfx = sfx.toLowerCase();
            else if (mode == 2) sfx = sfx.toUpperCase();
            String sep = p(F_CHATSUFFIX, 1) > 0 ? " " : "";
            return (int) p(F_CHATSUFFIX, 0) == 1 ? sfx + sep + msg : msg + sep + sfx;
        } catch (Throwable t) {
            logOnce("chatsuffix", t);
            return msg;
        }
    }

    /** 读后缀文本。找不到文件就返回默认值, 并把路径记进日志。 */
    private static String chatSuffixText() {
        if (chatSuffixCache != null) return chatSuffixCache;
        String def = "AZclient";
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.home"), ".azclient");
            java.io.File f = new java.io.File(dir, "suffix.txt");
            if (!f.isFile()) {
                // 第一次跑就写一份默认的出来 —— 不然用户根本不知道要建哪个文件
                dir.mkdirs();
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write(def + "\n");
                w.close();
                log("ChatSuffix: 已生成 " + f.getAbsolutePath());
            }
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line = r.readLine();
            r.close();
            if (line != null) {
                line = line.trim();
                if (!line.isEmpty()) def = line;
            }
            chatSuffixCache = def;
        } catch (Throwable t) {
            chatSuffixCache = def;      // 读不了就退回默认, 不重试 (避免每句话都卡 IO)
        }
        return chatSuffixCache;
    }

    // ===== NoFire =====
    /**
     * 关掉火焰遮挡和传送门扭曲。参数: 0=Fire 1=Nausea
     *
     * 这两个都是纯客户端的显示状态字段 —— 服务端那边你照样在烧、照样在传送,
     * 所以清掉它们只是"看不见", 不改变任何判定。真要说副作用, 是关掉之后
     * 你不会再被火焰挡住视野, 也就更容易发现自己正在挨打。
     *
     * 每帧清一次而不是清一次就完事: 服务端会持续下发 "你在烧" 的状态包,
     * 客户端每 tick 都会把它写回这个字段。
     *
     * **用户报过 "nofire 无效", 所以这里加了一条只在"烧着"时打的自检日志** ——
     * 判据不能靠猜: remainingFireTicks 是 private int (SRG field_190534_ay, 类 Entity),
     * 我们能写进去, 但"写进去"和"屏幕上真的没火"是两件事。日志把写前/写后的值都打出来,
     * 下一次测完就能区分是"没写进去"还是"写了但渲染用的不是这个字段"。
     */
    private static void applyNoFire(Object player) {
        try {
            boolean wantFire = p(F_NORENDER, 0) > 0;
            boolean wantNausea = p(F_NORENDER, 1) > 0;
            if (!noFireProbed) {
                noFireProbed = true;
                log("NoFire 句柄: fire=" + (fEntityFire != null) + " timeInPortal=" + (fTimeInPortal != null)
                        + " (任一为 false 就是字段没解析到, 功能不可能生效)");
            }
            if (wantFire && fEntityFire != null) {
                int before = fEntityFire.getInt(player);
                if (before != 0) {
                    fEntityFire.setInt(player, 0);
                    int after = fEntityFire.getInt(player);
                    long now = System.currentTimeMillis();
                    if (now - noFireLastLog >= 1000) {
                        noFireLastLog = now;
                        log("NoFire: remainingFireTicks " + before + " -> " + after
                                + (after == 0 ? " (已清除)" : " **写不进去, 字段是只读或类型不符**"));
                    }
                }
            }
            if (wantNausea && fTimeInPortal != null) {
                if (fTimeInPortal.getFloat(player) != 0f) fTimeInPortal.setFloat(player, 0f);
            }
        } catch (Throwable t) {
            logOnce("nofire", t);
        }
    }

    private static boolean noFireProbed = false;
    private static long noFireLastLog = 0;

    // ===== FreeCam =====
    /**
     * 自由相机。参数: 0=Speed 1=Mode
     *
     * 原理: 相机的坐标和朝向全部来自 mc.renderViewEntity (EntityRenderer.setupCameraTransform
     * 直接读它的 pos/rotation), 所以造一个空的 Entity 顶上去, 相机就跟它走了。
     * 玩家的准星、攻击判定仍然用 mc.player 的朝向 —— 也就是"视角和瞄准分离", 这正是 FreeCam 的用途。
     *
     * Mode: Camera = 冻结玩家 (只动相机, 最常用), Player = 玩家照常走。
     *
     * 惰性创建那个空 Entity: 构造要 World, 而且它不 spawn 到世界里, 完全是惰性的。
     * 关掉功能时会把 renderViewEntity 还原成玩家 —— 这一步放在 finally 里, 出错也必须还原,
     * 否则相机会永久卡在一个空实体上。
     */
    /**
     * FreeCam 用的假相机实体。
     *
     * **Entity 是抽象类** —— 直接 cEntity.getConstructor(World).newInstance() 会抛
     * InstantiationException, 这就是 FreeCam 之前完全没反应的原因。必须挑一个具体的子类。
     *
     * 排序有讲究: EntityRenderer.orientCamera 会把 entity.getEyeHeight() 加到相机高度上,
     * 而我们自己已经按玩家眼睛高度 (+1.62) 摆了位置。所以优先选 getEyeHeight() 返回 0 的
     * 纯 Entity 子类 (EntityArrow/EntityBoat), 这样两边不会叠成"飘在头顶上"。
     * EntityArmorStand 继承 EntityLivingBase, 眼睛高度非 0, 只作最后兜底。
     */
    private static final String[] FREECAM_ENTITY_CLASSES = {
            "net.minecraft.entity.item.EntityArmorStand",
            "net.minecraft.entity.item.EntityBoat",
            "net.minecraft.entity.projectile.EntityArrow",
    };

    private static Object newFreeCamEntity(Object world) {
        for (String cn : FREECAM_ENTITY_CLASSES) {
            try {
                Class<?> c = copt(cn);
                if (c == null || java.lang.reflect.Modifier.isAbstract(c.getModifiers())) continue;
                java.lang.reflect.Constructor<?> ctor = c.getConstructor(cWorld);
                Object e = ctor.newInstance(world);
                log("FreeCam: 用 " + cn + " 当假相机实体");
                return e;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /**
     * 把假相机实体**加进世界**。
     *
     * 这是 FreeCam 剩下的那两个毛病的根: "还是有问题" ——
     *
     * 之前那个实体从头到尾没有 `world.spawnEntity()` 过, 于是:
     *   ① `World.updateEntities()` 不 tick 它 —— 它的 `posX/Y/Z` 永远是我们在 onFrame 里
     *      手写的那几个数, 而 `prevPos` / `lastTickPos` / `rotationYaw` 全靠 setEntityInterp
     *      硬灌。原版渲染一帧读好几次, 中间插值出来的位置和视角就是对不上 —— 飘、抖。
     *   ② `RenderGlobal` / `EntityRenderer` 挑区块和做视锥剔除时会读相机的
     *      `isAddedToWorld()` 之类的状态, 没加进世界的实体两条都是假 —— **这就是
     *      "周围方块不渲染"的直接原因**, 和光照无关 (我上一轮猜光照是错的)。
     *   ③ `ChunkRenderDispatcher` 判断"相机在哪个区块"也读它的位置, 位置只在我们
     *      onFrame 里更新, 而原版在**渲染线程的 tick 里**读过一次之后就缓存了。
     *
     * 正确做法: 真的 spawn 它。这样原版会自己 tick 它、自己维护 prevPos/lastTickPos、
     * 自己维护区块可见性 —— 我们只需要每帧改它的 motion 让它飞。
     *
     * 用 `World.spawnEntity` (SRG func_72838_d)。它是 public 的。
     * 关掉功能时从世界里移除 (func_72838_d 的反面是 removeEntity, SRG func_72900_e)。
     */
    private static void addCamToWorld(Object world, Object e) {
        try {
            if (world == null || e == null || mSpawnEntity == null) return;
            mSpawnEntity.invoke(world, e);
            camInWorld = true;
            log("FreeCam: 假相机实体已加入世界 (原版会 tick 它, 区块可见性也跟着它对)");
        } catch (Throwable t) {
            logOnce("freecam-spawn", t);
        }
    }

    private static void removeCamFromWorld(Object world, Object e) {
        try {
            if (e == null) return;
            camInWorld = false;
            if (world != null && mRemoveEntity != null) mRemoveEntity.invoke(world, e);
        } catch (Throwable ignored) {
        }
    }

    /** 假相机实体是不是已经 spawn 进世界了 */
    private static boolean camInWorld = false;
    private static final FrameClock freeCamClock = new FrameClock();

    /**
     * 补齐假实体插值用的那几个量。
     *
     * **这是 FreeCam"乱转"的根因。** 那个假实体没有 spawn 到世界里, 所以没人 tick 它 ——
     * lastTickPos / prevPos / prevRotation 会永远停在构造时的初值 (0,0,0) 和 0 度。
     * 而 EntityRenderer 算相机位置用的是
     *     lastTickPos + (pos - lastTickPos) * partialTicks
     * 只写 pos 的话, partialTicks=0 时相机在原点、=1 时才到真实位置, 每帧之间疯狂来回插值 ——
     * 画面看上去就是"疯狂乱转, 完全看不出在飞"。朝向同理 (renderYaw 也是插出来的)。
     * 把四个量写成同一个值, 插值结果就恒等于它, 相机才稳。
     */
    private static void setEntityInterp(Object e, double x, double y, double z, float yaw, float pitch) {
        try {
            fLastTickX.setDouble(e, x); fLastTickY.setDouble(e, y); fLastTickZ.setDouble(e, z);
            fPrevX.setDouble(e, x);     fPrevY.setDouble(e, y);     fPrevZ.setDouble(e, z);
            fPrevYaw.setFloat(e, yaw);  fPrevPitch.setFloat(e, pitch);
        } catch (Throwable ignored) {
        }
    }

    /** 假相机实体的头部朝向 —— 见 updateFreeCam 里的说明。缺句柄就算了 (不是致命项) */
    private static void setEntityHead(Object e, float yaw, float pitch) {
        try {
            if (fRotationYawHead != null) fRotationYawHead.setFloat(e, yaw);
            if (fPrevRotationYawHead != null) fPrevRotationYawHead.setFloat(e, yaw);
            if (fRenderYawOffset != null) fRenderYawOffset.setFloat(e, yaw);
            if (fPrevRenderYawOffset != null) fPrevRenderYawOffset.setFloat(e, yaw);
        } catch (Throwable ignored) {
        }
    }

    /** 假相机实体的包围盒句柄 (惰性取一次并缓存) */
    private static java.lang.reflect.Field fBoundingBoxCache;

    /**
     * 假相机实体的包围盒跟着走 —— 光照取样 / 视锥剔除 / 音效都会读它。
     *
     * 这个实体没 spawn 到世界里, 所以没有任何原版代码会帮它更新 boundingBox。
     * 盒子留在创建时的原点, 相机飞到哪系统都按原点算 —— 见 updateFreeCam 里的说明。
     */
    private static void syncCamBB(Object e, double x, double y, double z) {
        try {
            if (fBoundingBoxCache == null) fBoundingBoxCache = fopt(cEntity, "field_70121_D");
            if (fBoundingBoxCache == null || ctorAABB == null) return;
            Object bb = ctorAABB.newInstance(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
            fBoundingBoxCache.set(e, bb);
        } catch (Throwable ignored) {
        }
    }

    private static void updateFreeCam(Object mc, Object player) {
        boolean on = mods[F_FREECAM].on && player != null;
        Object prev = null;
        try {
            prev = fRenderViewEntity.get(mc);
        } catch (Throwable t) {
            return;                                  // 拿不到这个字段就整个功能不可用
        }
        if (!on) {
            freeCamClock.reset();
            // 还原: 只有当前相机还是我们那个假实体时才动, 免得覆盖别人的改动
            if (freeCamEntity != null && prev == freeCamEntity) {
                try { fRenderViewEntity.set(mc, player); } catch (Throwable ignored) { }
            }
            // 顺手把它从世界里摘掉 —— 留在里头会被原版一直 tick, 白耗性能,
            // 而且它还会被算进实体列表 (某些渲染/统计会数错)。
            if (freeCamEntity != null && camInWorld) {
                try { removeCamFromWorld(freeCamWorld != null ? freeCamWorld : fWorld.get(mc), freeCamEntity); } catch (Throwable ignored) { }
            }
            freeCamEntity = null;
            freeCamWorld = null;
            freeCamPoseReady = false;
            camInWorld = false;
            return;
        }
        try {
            Object world = fWorld.get(mc);
            if (world == null) { freeCamClock.reset(); return; }
            boolean created = false;
            if (freeCamEntity == null || freeCamWorld != world || !camInWorld) {
                if (freeCamEntity != null && camInWorld && freeCamWorld != null) {
                    try { removeCamFromWorld(freeCamWorld, freeCamEntity); } catch (Throwable ignored) { }
                }
                freeCamEntity = newFreeCamEntity(world);
                if (freeCamEntity == null) {
                    logOnce("freecam-new", new IllegalStateException("没有可用的具体实体类"));
                    return;
                }
                // **必须 spawn 进世界** —— 见 addCamToWorld 上面的说明。
                // 不 spawn 就没有任何原版代码帮它维护区块可见性, 这是"周围不渲染"的根。
                addCamToWorld(world, freeCamEntity);
                freeCamWorld = world;
                created = true;
                freeCamClock.reset();
            }
            // 刚开启时把相机放到玩家眼睛的位置
            if (created) {
                // orientCamera adds the view entity's eye height.  Store the
                // fake entity base so its final eye starts exactly at the
                // player's current eye, independent of which concrete entity
                // class was available.
                freeCamX = fPosX.getDouble(player);
                freeCamY = fPosY.getDouble(player) + eyeH(player) - eyeH(freeCamEntity);
                freeCamZ = fPosZ.getDouble(player);
                float startYaw = fYaw.getFloat(player), startPitch = fPitch.getFloat(player);
                mSetPosAndRot.invoke(freeCamEntity, freeCamX, freeCamY, freeCamZ, startYaw, startPitch);
                setEntityInterp(freeCamEntity, freeCamX, freeCamY, freeCamZ, startYaw, startPitch);
                freeCamPoseReady = true;
            }
            double seconds = freeCamClock.step(System.nanoTime());
            double speed = p(F_FREECAM, 0) * 10.0 * seconds;
            if (!freeCamPoseReady) return;
            // Mouse input turns Minecraft.renderViewEntity.  Once FreeCam is
            // active that entity is the fake camera, while player yaw remains
            // frozen (or may temporarily be owned by KillAura).  Reading the
            // player here reset the camera every frame and produced the rapid
            // back-and-forth rotation reported in game.
            float yaw = fYaw.getFloat(freeCamEntity), pitch = fPitch.getFloat(freeCamEntity);
            double cx = freeCamX, cy = freeCamY, cz = freeCamZ;
            // 按玩家当前视角的前/右/上方向移动 —— 和创造模式飞行的手感一致
            double rad = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            double fx = -Math.sin(rad) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(rad) * Math.cos(pr);
            double rx = -Math.cos(rad), rz = Math.sin(rad);
            double fwd = (physicalBindingDown(mc, fForwardKey, 17) ? 1.0 : 0.0)
                    - (physicalBindingDown(mc, fBackKey, 31) ? 1.0 : 0.0);
            double str = (physicalBindingDown(mc, fLeftKey, 30) ? 1.0 : 0.0)
                    - (physicalBindingDown(mc, fRightKey, 32) ? 1.0 : 0.0);
            double inputLen = Math.sqrt(fwd * fwd + str * str);
            if (inputLen > 1.0) { fwd /= inputLen; str /= inputLen; }
            cx += (fx * fwd + rx * str) * speed;
            cy += (fy * fwd) * speed;
            cz += (fz * fwd + rz * str) * speed;
            // 上升/下降: 跳跃键上升, 潜行键下降 —— 和旁观者模式一致。
            // 原来只有跳跃键能上升, 完全没法下降, 只能把视角朝下再前进, 基本没法用。
            if (physicalBindingDown(mc, fJumpKey, 57)) cy += speed;
            if (physicalBindingDown(mc, fSneakKey, 42)) cy -= speed;

            freeCamX = cx; freeCamY = cy; freeCamZ = cz;
            // The concrete stand/boat fallback is present in the world so
            // chunk rendering follows it, but its vanilla tick must not add
            // gravity or vehicle drift to the camera's own stored position.
            fMotionX.setDouble(freeCamEntity, 0.0);
            fMotionY.setDouble(freeCamEntity, 0.0);
            fMotionZ.setDouble(freeCamEntity, 0.0);
            mSetPosAndRot.invoke(freeCamEntity, cx, cy, cz, yaw, pitch);
            setEntityInterp(freeCamEntity, cx, cy, cz, yaw, pitch);
            // 头的朝向也要跟上 —— orientCamera / 第三人称都读 rotationYawHead,
            // 不动它的话身体和头会各转各的, 看起来还是在打转。
            setEntityHead(freeCamEntity, yaw, pitch);
            // **包围盒要跟着走。**
            //
            // 实体现在虽然已经 spawn 进世界 (见 addCamToWorld), 但我们是**绕过 tick 直接写
            // 坐标**的 —— 原版 tick 里更新包围盒那一步 (onEntityUpdate -> setEntityBoundingBox)
            // 算出来的位置基于它上一 tick 的 pos, 和我们刚写进去的不一致。
            // 包围盒被光照取样 / 视锥剔除 / 音效读, 不跟着走就会出现"相机在新位置、
            // 系统还按旧位置算" —— 所以这里仍然要手动同步一次。
            syncCamBB(freeCamEntity, cx, cy, cz);
            fRenderViewEntity.set(mc, freeCamEntity);

            // Mode=Camera is frozen at the vanilla KeyBinding boundary in
            // syncInventoryMoveInputs; the camera still reads physical keys.
        } catch (Throwable t) {
            // 出错就立刻还原, 不能让相机留在半死的假实体上
            freeCamClock.reset();
            logOnce("freecam", t);
            try { if (freeCamEntity != null) fRenderViewEntity.set(mc, player); } catch (Throwable ignored) { }
            try { if (freeCamEntity != null && camInWorld && freeCamWorld != null) removeCamFromWorld(freeCamWorld, freeCamEntity); }
            catch (Throwable ignored) { }
            freeCamEntity = null;
            freeCamWorld = null;
            freeCamPoseReady = false;
            camInWorld = false;
        }
    }

    // ===== AutoReconnect =====
    private static long reconnectAt = 0L;
    /** Mode=Auto 时用来保证"每次断线只自动顶一次" */
    private static boolean reconnectDone = false;
    private static Object reconnectServerData;
    private static Method mCurrentServerData;
    private static Class<?> cDisconnectedScreen;
    private static java.lang.reflect.Constructor<?> reconnectConstructor;

    /**
     * 断线自动重连。参数: 0=Delay (s) 1=Mode
     *
     * Preserve the actual ServerData and open vanilla GuiConnecting. A
     * disconnected screen's Back button only opens the server list, and
     * GuiButton.mousePressed is merely a hit test, not a click handler.
     *
     * Mode: Auto = 每次断线只顶一次 (被服务器踢了之后一直往上顶是刷屏行为),
     *       Retry = 只要还在断线界面就按 Delay 一直重试。
     */
    private static void checkAutoReconnect(Object mc) {
        checkAutoReconnectAt(mc,System.currentTimeMillis());
    }
    private static void checkAutoReconnectAt(Object mc,long now) {
        try {
            if(mCurrentServerData==null)mCurrentServerData=mopt(mc.getClass(),"func_147104_D");
            Object server=mCurrentServerData==null?null:mCurrentServerData.invoke(mc);
            if(fPlayer!=null && fPlayer.get(mc)!=null)reconnectServerData=server;
            else if(server!=null)reconnectServerData=server;
            Object screen = fCurrentScreen.get(mc);
            if (screen == null) {
                reconnectAt = 0L;
                reconnectDone = false;                  // 回到游戏了 -> 下次断线可以再自动重连
                return;
            }
            if(cDisconnectedScreen==null)cDisconnectedScreen=copt("net.minecraft.client.gui.GuiDisconnected");
            if (cDisconnectedScreen==null || !cDisconnectedScreen.isInstance(screen)) {
                reconnectAt = 0L;                       // 不在断线界面 -> 计时清零
                return;
            }
            if (reconnectAt == 0L) {
                reconnectAt = now + (long) (p(F_AUTORECONNECT, 0) * 1000.0);
            }
            if (now < reconnectAt) return;
            if ((int) p(F_AUTORECONNECT, 1) == 0 && reconnectDone) return;
            if(reconnectServerData==null || mDisplayGui==null)return;
            if(reconnectConstructor==null){
                Class<?> connecting=copt("net.minecraft.client.multiplayer.GuiConnecting");
                Class<?> gui=copt("net.minecraft.client.gui.GuiScreen"), data=copt("net.minecraft.client.multiplayer.ServerData");
                if(connecting==null || gui==null || data==null)return;
                reconnectConstructor=connecting.getConstructor(gui,copt("net.minecraft.client.Minecraft"),data);
            }
            reconnectAt = now + (long) (p(F_AUTORECONNECT, 0) * 1000.0);
            Object connecting=reconnectConstructor.newInstance(screen,mc,reconnectServerData);
            mDisplayGui.invoke(mc,connecting);
            reconnectDone = true;
            log("AutoReconnect -> vanilla connection screen opened");
        } catch (Throwable t) {
            logOnce("autoreconnect", t);
        }
    }

    // ===== Spammer =====
    private static long spamAt = 0L;
    private static int spamIdx = 0;
    /** 剪贴板为空时的兜底消息 —— 保证功能至少"看得见在工作" */
    private static final String[] SPAM_BUILTIN = {"gg", "ez", "nice", "lol", "wp"};

    /**
     * 定时发聊天。参数: 0=Interval (s) 1=Mode
     *
     * Mode: Clipboard = 发剪贴板里的内容 (先复制好你要发的话), Builtin = 轮流发内置的几句。
     *
     * 用剪贴板是因为参数系统里没有字符串类型 —— 与其加一整套文本输入控件,
     * 不如复用系统剪贴板: 想发什么就复制什么, 零 UI 成本。
     */
    private static void tickSpammer(Object player) {
        try {
            long now = System.currentTimeMillis();
            if (spamAt == 0L) { spamAt = now + (long) (p(F_SPAMMER, 0) * 1000.0); return; }
            if (now < spamAt) return;
            spamAt = now + (long) (p(F_SPAMMER, 0) * 1000.0);

            String msg = null;
            if ((int) p(F_SPAMMER, 1) == 0) {
                if (mGetClipboard != null) {
                    Object c = mGetClipboard.invoke(null);
                    if (c instanceof String && !((String) c).trim().isEmpty()) msg = ((String) c).trim();
                }
            }
            if (msg == null) {
                msg = SPAM_BUILTIN[spamIdx % SPAM_BUILTIN.length];
                spamIdx++;
            }
            if (mSendChatMessage != null) mSendChatMessage.invoke(player, msg);
        } catch (Throwable t) {
            logOnce("spammer", t);
        }
    }

    private static final UiMotion hudMotion=new UiMotion();
    private static long hudLastNanos;
    private static float[] hudReveal=new float[0];
    private static void renderHud() throws Exception {
        Object mc=mGetMC.invoke(null);
        if(fPlayer.get(mc)==null)return;
        if(p(F_HUD,7)>0&&fCurrentScreen!=null&&fCurrentScreen.get(mc)!=null&&!(guiOpen&&p(F_HUD,13)>0))return;
        int uw=(Integer)fDisplayW.get(mc),uh=(Integer)fDisplayH.get(mc);
        if(uw<=0||uh<=0)return;
        layoutForFake(uw,uh,uiScale(uh));initFont();
        boolean pushed=false;
        try{pushPhysicalProjection();pushed=true;drawHudPanel(System.nanoTime());}
        finally{if(pushed)popPhysicalProjection();}
    }
    private static void drawHudPanel(long now) throws Exception {
        if((int)p(F_HUD,22)==1){drawCompactHud(now);return;}
        drawVeilHud(now);
    }
    private static void drawCompactHud(long now) throws Exception {
        float dt=hudLastNanos==0?.016f:(float)Math.max(0,Math.min(.1,(now-hudLastNanos)/1e9));hudLastNanos=now;
        dt*=interactionSpeed;
        boolean animate=interactionMotion&&common(mods[F_HUD],"UI Motion")>0;
        float opening=UiMotion.unit(hudMotion.value("panel",mods[F_HUD].on?1:0,0,22,1,dt,animate));
        hudW=hudH=0;if(opening<.005f)return;
        if(hudReveal.length!=mods.length)hudReveal=new float[mods.length];
        final float hs=(float)Math.max(.5,Math.min(2.5,p(F_HUD,8)));
        final float font=hs*.94f;
        final int sort=(int)p(F_HUD,2);
        java.util.Comparator<Integer> order=new java.util.Comparator<Integer>(){
            public int compare(Integer a,Integer b){
                int cmp=sort==0?textWScaled(mods[b].name,font)-textWScaled(mods[a].name,font):sort==1?mods[a].name.compareTo(mods[b].name):mods[a].cat-mods[b].cat;
                return cmp!=0?cmp:a-b;
            }
        };
        java.util.List<Integer> wanted=new java.util.ArrayList<Integer>();
        for(int i=0;i<mods.length;i++){
            Mod m=mods[i];if(i==F_HUD||!m.on||common(m,"HUD Entry")<=0)continue;
            if(p(F_HUD,6)<=0&&m.tag!=null&&!m.tag.isEmpty())continue;wanted.add(i);
        }
        java.util.Collections.sort(wanted,order);int total=wanted.size();
        boolean bg=p(F_HUD,3)>0,rainbow=p(F_HUD,4)>0,showCount=p(F_HUD,5)>0;
        float titleScale=hs*(float)Math.max(.5,Math.min(2.5,p(F_HUD,9)));
        int pad=Math.max(5,Math.round(12*S*hs)),lineH=Math.max(14,Math.round(28*S*hs)),gap=Math.max(2,Math.round(3*S*hs));
        int headerH=showCount?Math.max(Math.round(38*S*hs),Math.round(28*S*titleScale)):0;
        int limit=Math.min((int)p(F_HUD,11),Math.max(1,(physH-16-headerH-gap)/(lineH+gap)));
        if(wanted.size()>limit)wanted=new java.util.ArrayList<Integer>(wanted.subList(0,limit));
        java.util.List<Integer> rows=new java.util.ArrayList<Integer>();float occupied=0;
        for(int i=0;i<mods.length;i++){
            hudReveal[i]=UiMotion.unit(hudMotion.value("row:"+i,wanted.contains(i)?1:0,0,22,1,dt,animate));
            if(hudReveal[i]>.004f){rows.add(i);occupied+=(lineH+gap)*hudReveal[i];}
        }
        java.util.Collections.sort(rows,order);
        if(rows.isEmpty()&&p(F_HUD,12)<=0)return;
        String count=total+" active";
        int widest=showCount?textWScaled("AZCLIENT",titleScale)+textWScaled(count,font*.72f)+pad*3:pad*2;
        for(int id:rows)widest=Math.max(widest,textWScaled(mods[id].name,font)+pad*2+3*S);
        if(rows.isEmpty())widest=Math.max(widest,textWScaled("No active modules",font)+pad*2);
        int targetW=Math.min(physW-8,widest);
        int boxW=Math.max(1,Math.min(physW-8,Math.round((float)hudMotion.value("width",targetW,targetW,24,1,dt,animate))));
        int boxH=Math.min(physH-8,headerH+(headerH>0?gap*2:0)+Math.round(rows.isEmpty()?lineH:Math.max(lineH,occupied-gap)));
        int x=Math.max(4,Math.min(physW-boxW-4,(int)(physW*p(F_HUD,0)/100)));
        int y=Math.max(4,Math.min(physH-boxH-4,(int)(physH*p(F_HUD,1)/100)+Math.round((1-opening)*12*S)));
        hudX=x;hudY=y;hudW=boxW;hudH=boxH;
        boolean alignRight=p(F_HUD,0)>=50;
        int top=y;
        if(showCount){
            if(bg)widgetSurface(F_HUD,x,y,boxW,headerH,8*S,opening,1f);
            try(GuiClip clip=GuiClip.begin(x,y,boxW,headerH,physH)){
                float small=font*.72f;
                float fittedTitle=Math.min(titleScale,Math.max(.25f,(boxW-pad*3-textWScaled(count,small))/(float)Math.max(1,textW("AZCLIENT"))));
                textScaled("AZCLIENT",x+pad,hudTextY(y,headerH,fittedTitle),fade(TXT_MAIN,opening),fittedTitle);
                textScaled(count,x+boxW-pad-textWScaled(count,small),hudTextY(y,headerH,small),fade(0xFFD0BED5,opening),small);
            }
            top+=headerH+gap*2;
        }
        if(rows.isEmpty()){
            if(bg)widgetSurface(F_HUD,x,top,boxW,lineH,6*S,opening,.35f);
            textScaled("No active modules",x+pad,hudTextY(top,lineH,font),fade(DECK_MUTED,opening),font);
        }
        float offset=0;
        for(int n=0;n<rows.size();n++){
            int id=rows.get(n);Mod m=mods[id];float a=hudReveal[id],alpha=a*opening;
            int rowTop=top+Math.round(offset);offset+=(lineH+gap)*a;
            int visibleH=Math.max(1,Math.round(lineH*a));
            try(GuiClip rowClip=GuiClip.begin(0,rowTop,physW,Math.min(visibleH,Math.max(0,physH-4-rowTop)),physH)){
            if(!rowClip.active()&&a<.999f)continue;
            int rowW=Math.min(boxW,textWScaled(m.name,font)+pad*2+3*S);
            int rx=alignRight?x+boxW-rowW:x;
            rx+=Math.round((1-a)*(alignRight?22:-22)*S);
            if(bg)widgetSurface(F_HUD,rx,rowTop,rowW,lineH,6*S,alpha,.25f);
            double phase=n/(double)Math.max(1,rows.size())*.65;
            int col=rainbow?VisualStyle.sunset(phase,now/1000000L,flowSpeed,true):catColor(m.cat);
            int tail=rainbow?VisualStyle.sunset(phase+.22,now/1000000L,flowSpeed,true):col;
            // A thin colored trace carries the sunset; letterforms remain the visual foreground.
            deckGradient(rx+pad,rowTop+lineH-1,Math.max(1,rowW-2*pad),1,fade(col,.7f*alpha),fade(tail,.35f*alpha));
            int tx=rx+pad,ty=hudTextY(rowTop,lineH,font);
            if(!bg)textScaled(m.name,tx+S,ty+S,fade(0xCC000000,alpha),font);
            if(rainbow)textGradient(m.name,tx,ty,fade(blend(col,0xFFFFFFFF,.32f),alpha),fade(blend(tail,0xFFFFFFFF,.32f),alpha),font);
            else textScaled(m.name,tx,ty,fade(TXT_MAIN,alpha),font);
            }
        }
        if(guiOpen&&p(F_HUD,10)>0){
            int hx=x+boxW-7*S,hy=y+boxH-7*S;
            for(int k=0;k<3;k++)mDrawRect.invoke(null,hx+k*2*S,hy+(2-k)*2*S,hx+k*2*S+S,hy+5*S,0xAABFB1CD);
        }
    }
    private static final VeilList veilList=new VeilList();
    private static String veilSuffix(Mod m){
        if(p(F_HUD,23)<=0)return "";
        for(String key:new String[]{"Mode","Style","Targets"})for(P pp:m.ps)
            if(key.equals(pp.n)&&pp.opts!=null&&pp.opts.length>0){int v=Math.max(0,Math.min(pp.opts.length-1,(int)pp.v));return pp.opts[v];}
        return "";
    }
    private static void drawVeilHud(long now)throws Exception{
        try(VeilRenderState state=VeilRenderState.begin()){drawVeilContents(now);}
    }
    private static void drawVeilContents(long now)throws Exception{
        float u=S*(float)Math.max(.5,Math.min(2.5,p(F_HUD,8)));
        VeilFont.Face body=VeilFont.face(13*u,false),suffix=VeilFont.face(13*u,true);
        float ts=(float)Math.max(.5,Math.min(2.5,p(F_HUD,9)));
        VeilFont.Face title=VeilFont.face(11*u*ts,true);
        float tracking=.015f*body.size;
        java.util.List<VeilList.Item> items=new java.util.ArrayList<VeilList.Item>();
        if(mods[F_HUD].on)for(int id=0;id<mods.length;id++){
            Mod m=mods[id];if(id==F_HUD||!m.on||common(m,"HUD Entry")<=0)continue;
            if(p(F_HUD,6)<=0&&m.tag!=null&&!m.tag.isEmpty())continue;
            String tail=veilSuffix(m);float width=body.width(m.name,tracking,false)+(tail.isEmpty()?0:6*u+suffix.width(tail,tracking,false));
            items.add(new VeilList.Item(id,m.name,tail,width));
        }
        boolean motion=interactionMotion&&common(mods[F_HUD],"UI Motion")>0;
        float line=24*u,header=p(F_HUD,5)>0?title.ascent+30*u:body.ascent;
        int margin=Math.round(24*S);
        int capacity=Math.max(1,Math.min((int)p(F_HUD,11),(int)((physH-margin*2-header)/line)));
        java.util.List<VeilList.Row> rows=veilList.frame(items,now,line,capacity,motion);
        hudW=hudH=0;if(rows.isEmpty()&&(!mods[F_HUD].on||p(F_HUD,12)<=0)){VeilBackdrop.clear();return;}
        String count=String.format(java.util.Locale.ROOT,"%02d",veilList.total);
        float tt=.42f*title.size,titleW=title.width("AZCLIENT",tt,false);
        float headW=titleW+22*u+20*u+title.width(count,0,true);
        float widest=p(F_HUD,5)>0?headW:0,extent=0,opacity=mods[F_HUD].on?1:0;
        for(VeilList.Row r:rows){widest=Math.max(widest,r.item.width+body.size*.065f*r.item.name.length()*(1-r.reveal));extent=Math.max(extent,r.y+line);opacity=Math.max(opacity,r.alpha);}
        int w=Math.min(physW-margin*2,Math.round(widest+10*u)),h=Math.min(physH-margin*2,Math.round(header+Math.max(line,extent)));
        int x=Math.max(margin,Math.min(physW-w-margin,(int)(physW*p(F_HUD,0)/100)));
        int y=Math.max(margin,Math.min(physH-h-margin,(int)(physH*p(F_HUD,1)/100)));
        hudX=x;hudY=y;hudW=w;hudH=h;float right=x+w;
        int veilBottom=Math.min(physH,Math.round(VeilBackdrop.bottom(y+h,now,motion)));
        int veilFade=Math.max(1,Math.min(Math.round(64*u),veilBottom/2));
        if(p(F_HUD,3)>0){
            int maskLeft=Math.max(0,x-Math.round(100*u));
            float dark=.35f*backgroundOpacity(F_HUD)*opacity;
            if(hudFrost&&!guiOpen)FrostPass.drawVeil(physW,physH,maskLeft,veilBottom,veilFade,10*S,dark/.35f);
            VeilBackdrop.draw(maskLeft,physW,veilBottom,veilFade,fade(0xFF08070B,dark));
        }
        if(p(F_HUD,5)>0){
            float baseline=y+title.ascent;
            title.draw("AZCLIENT",right-titleW,baseline,fade(0xFFFFFFFF,opacity),tt,false);
            int connectorRight=Math.round(right-titleW-10*u),connectorLeft=Math.round(connectorRight-22*u);
            deckGradient(connectorLeft,Math.round(baseline-title.size*.34f),Math.max(1,connectorRight-connectorLeft),Math.max(1,S),0x00FFC59A,fade(0xFFFFC59A,.7f*opacity));
            title.draw(count,connectorLeft-10*u-title.width(count,0,true),baseline,fade(0xFFFFFFFF,.6f*opacity),0,true);
        }
        try(GuiClip clip=GuiClip.begin(0,y,physW,Math.max(0,physH-y-margin),physH)){
            if(rows.isEmpty())suffix.draw("No active modules",right-suffix.width("No active modules",tracking,false),y+header,0x8CFFFFFF,tracking,false);
            for(VeilList.Row r:rows){
                float spacing=tracking+(.08f-.015f)*body.size*(1-r.reveal);
                float nameW=body.width(r.item.name,spacing,false),tailW=suffix.width(r.item.suffix,spacing,false);
                float full=nameW+(r.item.suffix.isEmpty()?0:6*u+tailW),rx=right+r.travel*u;
                float baseline=y+header+r.y;
                body.draw(r.item.name,rx-full,baseline,fade(0xFFFFFFFF,r.alpha),spacing,false);
                if(!r.item.suffix.isEmpty())suffix.draw(r.item.suffix,rx-tailW,baseline,fade(0xFFFFFFFF,.55f*r.alpha),spacing,false);
                float accent=r.accent(now)*widgetGlow(F_HUD);
                if(accent>.001f){
                    int ax=Math.round(rx+8*u),ay=Math.round(baseline-body.size*.75f),ah=Math.max(2,Math.round(12*u)),aw=Math.max(1,Math.round(2*u));
                    // One temporary, round-ended 2x12 cue; the letters stay neutral.
                    for(int j=0;j<ah;j++){
                        float t=j/(float)Math.max(1,ah-1);int c=p(F_HUD,4)>0?(t<.5f?blend(0xFFFFC59A,0xFFF28CB0,t*2):blend(0xFFF28CB0,0xFFB9A2FF,(t-.5f)*2)):0xFFFFC59A;
                        int inset=(j==0||j==ah-1)&&aw>2?1:0;
                        mDrawRect.invoke(null,ax+inset,ay+j,ax+aw-inset,ay+j+1,fade(c,accent*(j==0||j==ah-1?.7f:1f)));
                    }
                }
            }
        }
    }
    private static void renderNotices() throws Exception {
        Object mc=mGetMC.invoke(null);if(fPlayer.get(mc)==null){notices.clear();return;}
        int w=(Integer)fDisplayW.get(mc),h=(Integer)fDisplayH.get(mc);if(w<=0||h<=0)return;
        layoutForFake(w,h,uiScale(h));initFont();boolean pushed=false;
        try{pushPhysicalProjection();pushed=true;drawNotices(System.nanoTime());}
        finally{if(pushed)popPhysicalProjection();}
    }
    private static void drawNotices(long now) throws Exception {
        float bgAlpha=(float)common(mods[F_THEME],"Notice Opacity")/100f;
        float glowAlpha=(float)common(mods[F_THEME],"Notice Glow")/100f;
        float scale=(float)p(F_THEME,25);int u=S;
        int cardW=Math.min(physW-32*u,Math.round(280*u*scale)),cardH=Math.round(42*u*scale),gap=24*u;
        int capacity=Math.max(1,Math.min(5,(physH-96*u)/Math.max(1,cardH+gap)));
        java.util.List<NoticeStack.Card> cards=notices.frame(now,p(F_THEME,24),capacity,interactionMotion,interactionSpeed);
        float cursor=physH-28*u;
        for(int i=cards.size()-1;i>=0;i--){
            NoticeStack.Card c=cards.get(i);float slot=(cardH+gap)*c.extent;
            int bottom=Math.round(cursor),clipTop=Math.round(cursor-slot+gap*c.extent);cursor-=slot;
            if(c.alpha<.005f||bottom<=clipTop||bottom<0)continue;
            int x=physW-24*u-cardW+Math.round(c.travel*(cardW+40*u)),y=bottom-cardH;
            int accent=c.enabled?0xFFF2AECF:0xFFB5A1ED,edge=c.enabled?0xFFFFAE7E:0xFFE7A5C6;
            int halo=Math.round(24*u*c.extent),haloTop=Math.max(0,clipTop-halo);
            try(GuiClip clip=GuiClip.begin(0,haloTop,physW,Math.max(0,bottom+halo-haloTop),physH)){
                if(!clip.active()&&c.extent<.999f)continue;
                VeilWidgetBackdrop.draw(x,y,cardW,cardH,24*u,fade(0xFF17121F,.30f*c.alpha*bgAlpha));
                int marker=Math.max(3,Math.round(5*u*scale)),mx=x+12*u,my=y+(cardH-marker)/2;
                SoftEffects.glow(mx-12*u,my-12*u,marker+24*u,marker+24*u,accent,.36f*bloom*c.alpha*glowAlpha);
                roundedRect(mx,my,mx+marker,my+marker,Math.max(1,u),fade(edge,c.alpha));
                String status=c.enabled?"已开启":"已关闭";
                float small=scale*.72f;
                int tx=mx+marker+12*u,sw=textWScaled(status,small),available=Math.max(1,x+cardW-12*u-tx-sw-12*u);
                String name=fitWidgetName(c.label,available,scale);
                textScaled(name,tx,hudTextY(y,cardH,scale),fade(0xFFF7F2FA,c.alpha),scale);
                textScaled(status,x+cardW-12*u-sw,hudTextY(y,cardH,small),fade(0xFFF0E5F2,c.alpha),small);
            }
        }
    }

    /** 按序号在色相环上取色 —— HUD 的彩虹模式 */
    private static int rainbowColor(int i, int n) {
        return sunset(i / (double) Math.max(1, n), true);
    }



    /**
     * 圆角矩形。用逐行收缩的 drawRect 拼出来 —— 顶点提交走 MC 自己的 Tessellator。
     * 不能用 GL11 immediate mode: 那套在 1.12 shader 管线里顶点不被消费, 画了等于没画
     * (之前"面板透明"就是这个原因)。
     */
    // =====================================================================
    // 玻璃质感
    //
    // GUI 开启时 drawGaussianBackdrop 会先做一次 3x3 加权纹理采样，下面这层负责
    // 磨砂底色、边缘高光和强辉光。驱动不支持纹理复制时仍会退回半透明磨砂遮罩。
    //
    // 所以玻璃是分层堆出来的, 四层:
    //   1. 外阴影   两层错位, 近浓远淡。一层看起来像描边, 两层才像面板浮在画面上
    //   2. 底板     偏蓝的深色 + 一圈描边。太透看不清字, 太实就不像玻璃, 70% 左右刚好
    //   3. 内渐变   上半提亮、下半压暗 —— 光从上面打下来的样子
    //   4. 顶边聚光 一条两端淡中间亮的白线。这一笔最像玻璃, 不能省
    // =====================================================================

    /** 每个分类一个颜色, 用在列标题的小色块上 —— 扫一眼就知道是哪一列 */
    private static final int[] CAT_COLOR = {
            0xFFFF6B6B,   // Combat
            0xFF4DD4AC,   // Movement
            0xFF6BA8FF,   // Render
            0xFFFFC857,   // Player
            0xFFB98CFF,   // World
            0xFF8FA3B8,   // Misc
    };

    private static int catColor(int c) {
        if (sunsetPalette) return sunset(c / 8.0, rainbowMode);
        if (c == SET_COL) return ACCENT();
        // 默认主题原样返回那六个常量 —— 不动主题的人看到的画面和以前一模一样
        if (themeHue < 0 && !rainbowMode) return CAT_COLOR[c % CAT_COLOR.length];
        // 改过主题色: 在主题色相上把六个分类等距铺开, 整块 UI 才是一个调子
        float base = currentHue() < 0f ? 0.45f : currentHue();
        float h = (base + c * 0.13f) % 1.0f;
        java.awt.Color col = java.awt.Color.getHSBColor(h, 0.55f, 1.0f);
        return 0xFF000000 | (col.getRed() << 16) | (col.getGreen() << 8) | col.getBlue();
    }

    /**
     * 竖向渐变。包一层是为了别到处写 mDrawGradient.invoke(...)
     *
     * **必须传 guiInstance**: Gui.drawGradientRect 在 1.12 是 protected 的**实例方法**,
     * 只有 drawRect 是静态的。传 null 会直接 NPE —— 而这一层是纯装饰,
     * 一个装饰调用把整个面板搞没, 是最不该发生的事。所以这里失败就静默跳过。
     */
    private static void gradientV(int x1, int y1, int x2, int y2, int top, int bot) {
        if (mDrawGradient == null || guiInstance == null) return;
        if (x2 <= x1 || y2 <= y1) return;
        try {
            mDrawGradient.invoke(guiInstance, x1, y1, x2, y2, top, bot);
        } catch (Throwable ignored) {
            // 渐变画不出来不影响可用性, 不往上抛
        }
    }

    /** 按比例缩放 alpha (0..1) */
    private static int fade(int argb, float f) {
        f = f < 0f ? 0f : (f > 1f ? 1f : f);
        int a = (int) (((argb >>> 24) & 0xFF) * f);
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * 玻璃面板。
     *
     * @param base 底板色 (带 alpha)。描边和光影都围着它做, 所以换一个 base 就能换整块玻璃的调子。
     */
    private static void glassPanel(int x1, int y1, int x2, int y2, int r, int base, boolean glow) throws Exception {
        dreamSurface(x1, y1, x2 - x1, y2 - y1, Math.max(5 * S, r), glow);
    }

    /**
     * 玻璃胶囊 (开关、模式、热键都用它)。
     *
     * lit 为真时是"亮"的状态 (开启/悬停), 顶上多一条高光 —— 和面板同一个视觉语言,
     * 否则胶囊看起来像贴上去的塑料片。
     */
    private static void glassPill(int x1, int y1, int x2, int y2, int fill, boolean lit) throws Exception {
        if (x2 <= x1 || y2 <= y1) return;
        if (lit) frameGlow(x1,y1,x2-x1,y2-y1,5*S,Math.min(16*S,bloomSpread*S),deckAccent(),deckAccentEnd(),bloom*.55f);
        roundedRect(x1, y1, x2, y2, 5 * S, lit ? 0xB65B3A68 : 0x9E272135);
        roundedRect(x1 + S, y1 + S, x2 - S, y2 - S, 4 * S, fade(fill, 0.65f));
        mDrawRect.invoke(null, x1 + 5 * S, y1, x2 - 5 * S, y1 + S, lit ? 0x80F4C4DF : 0x365D5072);
    }
    private static void roundedRect(int x1, int y1, int x2, int y2, int r, int argb) throws Exception {
        if (x2 <= x1 || y2 <= y1) return;
        int h = y2 - y1;
        r = Math.min(r, Math.min((x2 - x1) / 2, h / 2));
        if (r <= 0) { mDrawRect.invoke(null, x1, y1, x2, y2, argb); return; }
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
            mDrawRect.invoke(null, x1 + inset, y1 + i, x2 - inset, y1 + i + 1, argb);
            mDrawRect.invoke(null, x1 + inset, y2 - i - 1, x2 - inset, y2 - i, argb);
        }
        mDrawRect.invoke(null, x1, y1 + r, x2, y2 - r, argb);
    }

    /** 带描边的圆角面板 */
    private static void panelBox(int x1, int y1, int x2, int y2, int r, int bg, int border) throws Exception {
        roundedRect(x1, y1, x2, y2, r, border);
        roundedRect(x1 + 1, y1 + 1, x2 - 1, y2 - 1, r - 1, bg);
    }

    /** 卡片收起时只占名称行; 展开才加参数行。未实现的功能没有参数行。 */
    private static boolean isExpanded(int idx) {
        return idx >= 0 && idx < expanded.length && expanded[idx];
    }

    /** 展开/收起一个功能的参数区 (点击行内非控件区域时用) */
    private static void setExpanded(int idx, boolean v) {
        if (idx >= 0 && idx < expanded.length) {
            if (v != expanded[idx]) deckMotion.seed("expand:"+idx, deckExpand.length==mods.length ? deckExpand[idx] : (expanded[idx]?1:0));
            expanded[idx] = v;
        }
    }

    private static int cardH(int idx) {
        Mod m = mods[idx];
        boolean body = isExpanded(idx) && m.tag == null && m.ps.length > 0;
        return CARD_PAD * 2 + ROW_NAME + (body ? m.ps.length * ROW_PARAM : 0);
    }

    /** 当前分类里一共放得下几张收起状态的卡片 (用于提示还剩多少) */

    // ===== 命中区域 (渲染与点击共用同一套函数, 从根上杜绝错位) =====
    // 反作弊块/分类项/搜索框的几何全部来自上面的 listX()/railItemY()/acItemY() 系列

    /** 功能卡内各行相对卡片顶部的偏移: 0=名称行, 之后每参数一行 */
    private static int paramRowOff(int idx) { return CARD_PAD + ROW_NAME + idx * ROW_PARAM; }

    /** 自检: 渲染/点击几何一致性 + 功能数量。渲染与点击都调用上面同一批函数, 这里只验证不变量。 */
    static void selfTest() {
        ensureAnimArrays();   // expanded 等数组按功能数分配, 不先建好下面会越界
        // 1) 功能数量
        if (mods.length < 30) throw new AssertionError("功能数不足 30: " + mods.length);
        // 2) 每个分类都要有功能 (空标签页 = 死 UI)
        for (int c = 0; c < CAT_COUNT; c++) {
            if (catCount(c) == 0) throw new AssertionError("分类无功能: " + CAT_NAMES[c]);
        }
        // 3-5) 面板几何 (抽成函数, 后面会拿不同屏幕尺寸反复验)
        checkColumnGeometry();
        // 6) 卡片高度必须与参数行偏移一致 (否则卡片底部与最后一行参数错位)。
        //    收起态和展开态都要验: 收起 = 只有名称行, 展开 = 名称行 + 参数行。
        for (int i = 0; i < mods.length; i++) {
            Mod m = mods[i];
            expanded[i] = false;
            if (cardH(i) != CARD_PAD * 2 + ROW_NAME) {
                throw new AssertionError("收起态卡片高度不对: " + m.name);
            }
            expanded[i] = true;
            int expect = (m.tag != null || m.ps.length == 0)
                    ? CARD_PAD * 2 + ROW_NAME
                    : paramRowOff(m.ps.length) + CARD_PAD;
            if (cardH(i) != expect) throw new AssertionError("展开态卡片高度不对: " + m.name);
            expanded[i] = false;
        }
        // 7) 功能名必须唯一 —— id() 是按名字查的, 重名会让两个功能指向同一个
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (Mod m : mods) {
            if (!names.add(m.name)) throw new AssertionError("功能重名: " + m.name);
        }
        // 8) 被调度的功能必须真的存在 (F_* 常量在类初始化时按名字解析, 这里再兜一层)。
        //    这个列表必须与 onFrame/onRender 里的实际调度保持一致 —— 漏列会让下面的
        //    "已实现" 计数虚高, 也会漏掉 "未实现却接了调度" 的情况。
        int[] dispatched = {
                // 战斗 13
                F_KILLAURA, F_AIMBOT, F_AUTOCLICK, F_CRITICALS, F_VELOCITY, F_REACH, F_TRIGGERBOT,
                F_BLOCKHIT, F_AUTOARMOR, F_AUTOTOTEM, F_AUTOPOT, F_AUTOBLOCK, F_HITBOX,
                // 移动 18
                F_NOCLIP, F_PHASE, F_FLIGHT, F_SPEED, F_SPRINT, F_NOFALL, F_AUTOJUMP,
                F_STEP, F_JESUS, F_SAFEWALK, F_BUNNYHOP, F_NOSLOW, F_LONGJUMP, F_HIGHJUMP,
                F_SPIDER, F_FASTLADDER, F_SWIM, F_INVMOVE,
                // 玩家 10
                F_FASTPLACE, F_FASTBREAK, F_INSTANTMINE, F_SCAFFOLD, F_AUTOTOOL,
                F_INVMANAGER, F_CHESTSTEALER, F_AUTOEAT, F_AUTOFISH, F_ANTIAFK,
                // 世界 4
                F_TIMER, F_NOWEATHER, F_TIMECHANGE, F_ANTIVOID,
                // 渲染 7 (onFrame 里的夜视/视距/无受伤抖动 + onRender 里的四个透视)
                F_FULLBRIGHT, F_VIEWCLIP, F_NOHURTCAM, F_ESP, F_STORAGEESP, F_ITEMESP, F_BLOCKOUTLINE,
                // 杂项 1
                F_AUTORESPAWN,
                // 新增 (投影打通之后解锁的)
                // 渲染: Tracers/NameTags/Trajectories 走屏幕空间投影; FreeCam 换 renderViewEntity;
                //       NoFire 清火焰/传送门显示字段
                F_TRACERS, F_NAMETAGS, F_TRAJECTORIES, F_FREECAM, F_NORENDER,
                // HUD: 两个都是无条件调用 + 内部判断开关
                F_PING, F_WATERMARK, F_HUD, F_THEME,
                F_KEYSTROKES, F_MOTIONSTATS, F_COORDINATES, F_COMPASS,
                F_SESSIONINFO, F_ITEMSTATUS, F_VITALS, F_HOTBARACCENT, F_TARGETHUD,
                // 杂项: AutoReconnect 按断线界面上的重连按钮; Spammer 走 sendChatMessage
                F_AUTORECONNECT, F_SPAMMER,
                // AntiBot 是过滤器 —— 没有自己的调度行, 被 nearestTarget 和渲染代码调用
                F_ANTIBOT,
                // ChatSuffix 是字节码钩子 —— LiteAgent 插进 EntityPlayerSP.sendChatMessage
                F_CHATSUFFIX,
                // NameProtect 也是字节码钩子 —— 插进 TextComponentString 的构造器
                F_NAMEPROTECT,
        };
        for (int i : dispatched) {
            if (i < 0 || i >= mods.length) throw new AssertionError("调度了不存在的功能 id: " + i);
        }
        // 9) 面板几何必须放得下 —— 见 checkColumnGeometry()
        // 10) 字体图集必须装得下所有要显示的字符, 且缩放到 4 也要装得下。
        //     放不下的字会退回 MC 点阵字 —— 混排出来就是"字显示得乱七八糟"。
        int csLen = buildCharset().length();
        if (!TtfFont.fitsWorstCase(csLen)) {
            throw new AssertionError("字体图集放不下: 需要 " + csLen + " 字, 缩放到 4 时最大图集也装不下");
        }
        // 10b) 图集必须真的含所有要显示的字符。
        //      之前 buildCharset 里硬编码了一份版本号和模板名, 版本从 v5.0 升到 v6.2、
        //      模板名从 ANTICHEAT 换成 OFF 之后两边就对不上了 —— 手写清单迟早会烂。
        //      现在从真实数据源取, 并且在这里逐个字符验一遍: 少一个字就构建失败。
        String cs = buildCharset();
        StringBuilder missing = new StringBuilder();
        for (String label : fontLabels()) {
            for (int i = 0; i < label.length(); i++) {
                char ch = label.charAt(i);
                if (ch < 32) continue;
                if (cs.indexOf(ch) < 0 && missing.indexOf(String.valueOf(ch)) < 0) missing.append(ch);
            }
        }
        if (missing.length() > 0) {
            throw new AssertionError("字体图集缺字: " + missing);
        }
        // 11) 卡片横向控件不重叠 —— 见 checkColumnGeometry()
        // 12) 文字墨迹必须落在行内。
        //     实测 (probe/GlyphMetrics): 正文 cell24 墨迹在格顶 +3..+19, 标题 cell46 在 +7..+38。
        //     这里显式用 TTF 分支的格顶偏移 (无头环境下 textY 会走 MC 回退分支, 测不到 TTF)。
        //     偏移常量或行高一改, 这里立刻炸, 不会等到进游戏才发现字压出格子。
        layoutForFake(1705, 1465, uiScale(1465));   // 下面用的是列行高, 先按实测分辨率把常量算出来
        assertInk("参数行", COL_PARAM_H, COL_PARAM_H / 2 - INK_CENTER, INK_TOP_L * S, INK_BOT_L * S);
        assertInk("名称行", COL_ROW_H, COL_ROW_H / 2 - INK_CENTER, INK_TOP_L * S, INK_BOT_L * S);
        assertInk("列标题", COL_HEAD_H, COL_HEAD_H / 2 - INK_CENTER, INK_TOP_L * S, INK_BOT_L * S);

        // 12d) 功能名的估算宽度不能超过它所在列的可用宽度。
        //      分栏之后列宽按内容自适应, 而且 drawModRow 用 fitText 兜底截断,
        //      所以这里只是"提醒"—— 真溢出了会被截成 "..", 不会压到控件上。
        //      真正会压到控件的是控件本身排不下, 那个由 checkColumnGeometry 逐分辨率把守。
        for (int c = 0; c < NCOL; c++) {
            if (c == SET_COL) continue;
            int avail = colW[c] - 2 * S - 10 * S - 12 * S - 4 * S - 44 * S - 4 * S - 34 * S - 6 * S - 2 * S;
            for (Mod m : mods) {
                if (m.cat != c) continue;
                // 标记胶囊按文字宽度自适应, 所以可用宽度要按这个功能自己的标记算
                int tagW = m.tag == null ? 44 * S : Math.max(44 * S, m.tag.length() * 10 * S + 10 * S);
                int nameAvail = colW[c] - 2 * S - 10 * S - 4 * S - tagW - 4 * S - 34 * S - 6 * S - 2 * S;
                int est = m.name.length() * 10 * S;
                if (est > nameAvail) {
                    throw new AssertionError("功能名超出列宽会被截断: " + m.name + " 在 " + CAT_NAMES[c]
                            + " 列 (估宽 " + est + "px, 可用 " + nameAvail + "px) —— 要么改短名字, 要么调 colNeededW");
                }
            }
        }

        // 12c) 同一个功能里的参数名不能重复 —— 改英文名时很容易把两个参数都译成 Speed,
        //      界面上就是两行一模一样的字, 用户根本分不清哪个是哪个。
        for (Mod m : mods) {
            java.util.Set<String> seen = new java.util.HashSet<String>();
            for (P pp : m.ps) {
                if (!seen.add(pp.n)) {
                    throw new AssertionError("功能 " + m.name + " 里有重复的参数名: " + pp.n);
                }
            }
        }
        // 12c-2) 模式参数至少要有两个选项 —— 只有一个选项的胶囊点下去什么都不变,
        //        用户只会以为坏了; 空选项会在渲染时 textW(null) 直接崩。
        for (Mod m : mods) {
            for (P pp : m.ps) {
                if (!pp.isMode()) continue;
                if (pp.opts.length < 2) {
                    throw new AssertionError("功能 " + m.name + " 的模式参数 " + pp.n
                            + " 只有 " + pp.opts.length + " 个选项");
                }
                for (String o : pp.opts) {
                    if (o == null || o.length() == 0) {
                        throw new AssertionError("功能 " + m.name + " 的模式参数 " + pp.n + " 有空选项");
                    }
                }
                if (pp.idx() != (int) pp.v) {
                    throw new AssertionError("功能 " + m.name + " 的模式参数 " + pp.n + " 默认值越界: " + pp.v);
                }
            }
        }
        // 12b) 搜索必须真的能用。
        //      MC 1.12 的 keyTyped 拿不到输入法组字, 中文名打不出来, 所以每个功能都必须有
        //      ASCII 别名; 且每条的第一个词 (主键) 必须全表唯一 —— 复制粘贴改漏名字
        //      会让两个功能共用一个主键, 搜出来莫名其妙。
        java.util.Map<String, String> prim = new java.util.HashMap<String, String>();
        for (Mod m : mods) {
            if (m.key.length() == 0) {
                throw new AssertionError("功能没有搜索别名: " + m.name);
            }
            for (char ch : m.key.toCharArray()) {
                if (ch >= 128) throw new AssertionError("别名含非 ASCII: " + m.name + " -> " + m.key);
            }
            String p = m.key.split(" ")[0];
            String prev = prim.put(p, m.name);
            if (prev != null) {
                throw new AssertionError("搜索主键冲突: \"" + p + "\" 同时属于 " + prev + " 和 " + m.name);
            }
        }
        // 几个常用词必须能搜到东西 (搜不到 = 搜索框形同虚设)。
        // 走的是 inList 用的同一个 matches(), 所以这里过了, 界面上就一定搜得到。
        String[] probes = {"fly", "killaura", "speed", "esp", "nofall", "timer", "wallhack", "aura"};
        for (String probe : probes) {
            boolean hit = false;
            for (Mod m : mods) if (matches(m, probe)) { hit = true; break; }
            if (!hit) throw new AssertionError("搜索词搜不到任何功能: " + probe);
        }
        // 13) 标记必须来自允许的集合, 而且被调度的功能不许带标记。
        //     带 SERVER/HOOK 就是"做不到/没做", 却接进调度 = 假装能用, 那是骗人。
        java.util.Set<Integer> dispatchedSet = new java.util.HashSet<Integer>();
        for (int i : dispatched) dispatchedSet.add(i);
        // 这个列表不能再悄悄过期。
        // 它以前是硬编码的 51 项, 加了 11 个功能之后还写着 51 —— 于是那句
        // "selfTest OK: 64 个功能 (51 项已实现)" 就成了一句假话, 而且没人会发现。
        // 现在钉死: 已实现的功能数必须正好等于这个列表的长度。
        int untagged = 0;
        for (Mod m : mods) if (m.tag == null) untagged++;
        if (dispatchedSet.size() != untagged) {
            throw new AssertionError("调度列表过期: 列表里 " + dispatchedSet.size()
                    + " 项, 但已实现的功能有 " + untagged + " 个 —— 补全 dispatched 数组");
        }
        for (int i = 0; i < mods.length; i++) {
            Mod m = mods[i];
            if (m.tag != null && !m.tag.equals("SERVER") && !m.tag.equals("HOOK")) {
                throw new AssertionError("未知标记: " + m.name + " -> " + m.tag);
            }
            if (m.tag != null && dispatchedSet.contains(i)) {
                throw new AssertionError("未实现的功能被接进调度: " + m.name + " (" + m.tag + ")");
            }
        }
        // 14) 三个预设必须都能干净应用 (名字打错会在这里炸), 且攻击距离不得超过原版上限。
        //     3.0 是服务端最容易测出来的量, 预设把它放宽等于预设本身在害人。
        for (int ac = 0; ac <= 2; ac++) {
            applyPreset(ac);
            double reach = p(F_REACH, 0);
            if (reach > 3.0 + 1e-9) {
                throw new AssertionError("预设 " + ac + " 把攻击距离放宽到 " + reach + " (>3.0)");
            }
            if (mods[F_NOCLIP].on || mods[F_PHASE].on || mods[F_INSTANTMINE].on) {
                throw new AssertionError("预设 " + ac + " 没有关掉高检测风险功能");
            }
        }
        antiCheat = -1;
        // 15) 反作弊预设只许"限制", 不许"打开"任何功能。
        //     以前每个 case 末尾都挂着一串 setOn(..., true), 用户点一下模板就被偷偷开了
        //     KillAura / TriggerBot / Scaffold —— 界面上完全看不出来。这条守着不许加回去。
        for (int ac = -1; ac < AC_NAMES.length; ac++) {
            boolean[] before = new boolean[mods.length];
            for (int i = 0; i < mods.length; i++) before[i] = mods[i].on;
            applyPreset(ac);
            for (int i = 0; i < mods.length; i++) {
                if (!before[i] && mods[i].on) {
                    throw new AssertionError("预设 " + (ac < 0 ? "OFF" : AC_NAMES[ac])
                            + " 打开了功能: " + mods[i].name + " —— 预设只能限制, 不能替用户做决定");
                }
            }
            for (int i = 0; i < mods.length; i++) mods[i].on = before[i];
        }
        applyPreset(-1);

        // 16) 配置导出 -> 导入必须无损。这是配置分享功能唯一的硬保证:
        //     格式一改这里立刻炸, 而不是等玩家互相传完一轮之后才发现参数全乱了。
        try {
            // 16a) 量化必须可逆 —— 分享串里存的是 (v-min)/0.01 的整数, 还原要一模一样。
            //      某个参数的默认值不在百分位网格上的话 (比如 3.14159), 传过去就会变样,
            //      这里当场拦。容忍半个最小单位, 因为量化本身就是"取最近的档位"。
            int maxQuant = 0;
            for (Mod m : mods) {
                for (P pp : m.ps) {
                    int q = pp.quant();
                    if (Math.abs((pp.min + q * P.Q) - pp.v) > P.Q / 2.0 + 1e-9) {
                        throw new AssertionError("参数量化不可逆: " + m.name + "." + pp.n
                                + " v=" + pp.v + " min=" + pp.min + " 量化后=" + (pp.min + q * P.Q));
                    }
                    maxQuant = Math.max(maxQuant, pp.quantMax());
                }
            }

            boolean[] on0 = new boolean[mods.length];
            int[] bind0 = new int[mods.length];
            double[][] pv0 = new double[mods.length][];
            for (int i = 0; i < mods.length; i++) {
                on0[i] = mods[i].on;
                bind0[i] = mods[i].bindKey;
                pv0[i] = new double[mods[i].ps.length];
                for (int j = 0; j < mods[i].ps.length; j++) pv0[i][j] = mods[i].ps[j].v;
            }
            int ac0 = antiCheat;

            String cfg = exportConfig();
            if (!cfg.startsWith(CFG_MAGIC)) throw new AssertionError("导出的配置开头不对");
            if (cfg.length() > 1200) {
                throw new AssertionError("导出串太长 (" + cfg.length() + " 字符), 分享起来会很痛苦");
            }

            // 全部打乱: 开关取反 / 热键清零 / 参数推到最小 —— 往返对了才说明真的全带上了
            for (int i = 0; i < mods.length; i++) {
                if (mods[i].tag == null) mods[i].on = !mods[i].on;
                mods[i].bindKey = 0;
                for (P pp : mods[i].ps) pp.v = pp.min;
            }
            antiCheat = -1;

            String report = importConfig(cfg);

            for (int i = 0; i < mods.length; i++) {
                Mod m = mods[i];
                if (m.on != on0[i]) {
                    throw new AssertionError("配置往返后开关不一致: " + m.name + " " + on0[i] + " -> " + m.on);
                }
                if (m.bindKey != bind0[i]) {
                    throw new AssertionError("配置往返后热键不一致: " + m.name);
                }
                for (int j = 0; j < m.ps.length; j++) {
                    if (Math.abs(m.ps[j].v - pv0[i][j]) > 1e-9) {
                        throw new AssertionError("配置往返后参数不一致: " + m.name + "." + m.ps[j].n
                                + " " + pv0[i][j] + " -> " + m.ps[j].v);
                    }
                }
            }
            if (antiCheat != ac0) throw new AssertionError("配置往返后模板不一致: " + ac0 + " -> " + antiCheat);
            if (report.contains("失败")) throw new AssertionError("自己导出的配置自己解不开: " + report);

            // 16b) 坏输入必须给出人话, 而不是抛异常或者静默乱改
            if (!importConfig("hello world").contains("不是")) throw new AssertionError("垃圾输入没被识别");
            if (!importConfig("").contains("空")) throw new AssertionError("空输入没被识别");
            if (!importConfig(CFG_MAGIC + "@@@!!!").contains("解码失败")) {
                throw new AssertionError("坏 base64 没被识别");
            }

            // 16c) 布局指纹不匹配必须被拒绝 —— 索引式编码全靠这道闸
            {
                byte[] raw = java.util.Base64.getUrlDecoder().decode(cfg.substring(CFG_MAGIC.length()));
                byte[] plain = unpackConfig(raw);
                plain[1] ^= 0x5A;
                plain[2] ^= 0x5A;
                raw = packConfig(plain);
                String wrong = CFG_MAGIC + java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(raw);
                String r2 = importConfig(wrong);
                if (!r2.contains("指纹")) {
                    throw new AssertionError("布局指纹不匹配没被拦下: " + r2);
                }
            }

            // 16d) 未实现的功能不许被配置点亮
            for (Mod m : mods) m.on = false;
            for (Mod m : mods) if (m.tag != null) m.on = true;
            String spoof = exportConfig();
            for (Mod m : mods) m.on = false;
            importConfig(spoof);
            for (Mod m : mods) {
                if (m.tag != null && m.on) {
                    throw new AssertionError("配置点亮了未实现的功能: " + m.name + " (" + m.tag + ")");
                }
            }

            // 16e) 空配置 (全默认) 也应该能正常往返
            resetToDefaults();
            String emptyCfg = exportConfig();
            importConfig(emptyCfg);
            for (Mod m : mods) {
                if (m.on) throw new AssertionError("全默认配置导入后功能却是开的: " + m.name);
                for (P pp : m.ps) {
                    if (Math.abs(pp.v - pp.def) > 1e-9) {
                        throw new AssertionError("全默认配置导入后参数不是默认值: " + m.name + "." + pp.n);
                    }
                }
            }

            // 16g) 分享码的字母表必须和服务端一致。两边不一致的话, 服务端生成的码
            //      客户端认不出来, 表现是"别人给我的码提示格式不对" —— 这种 bug 很难查。
            if (!CODE_ALPHABET.equals("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")) {
                throw new AssertionError("分享码字母表变了, 必须同步改服务端 server.js 的 CODE_ALPHABET");
            }
            if (CODE_ALPHABET.indexOf('I') >= 0 || CODE_ALPHABET.indexOf('O') >= 0
                    || CODE_ALPHABET.indexOf('0') >= 0 || CODE_ALPHABET.indexOf('1') >= 0) {
                throw new AssertionError("分享码字母表不该含 I O 0 1 (手抄时会看错)");
            }
            if (CODE_ALPHABET.length() != 32) throw new AssertionError("分享码字母表应该正好 32 个字符");
            if (!isShareCode("CT7GZB")) throw new AssertionError("正常分享码没被认出来");
            if (!isShareCode("ct7gzb")) throw new AssertionError("小写分享码没被认出来");
            if (isShareCode("CT7GZ")) throw new AssertionError("5 位被当成分享码了");
            if (isShareCode("CT7GZBX")) throw new AssertionError("7 位被当成分享码了");
            if (isShareCode("IO0O11")) throw new AssertionError("含易混字符的被当成分享码了");
            if (isShareCode("")) throw new AssertionError("空串被当成分享码了");

            // 16f) 量一下"真实配置"有多长: 开十几个功能 + 改三十来项参数。
            //      这个数字才是用户实际会看到的东西, 摘要里报出来。
            resetToDefaults();
            int turnedOn = 0, changed = 0;
            for (Mod m : mods) {
                if (m.tag != null || turnedOn >= 12) continue;
                m.on = true;
                turnedOn++;
                m.bindKey = 65 + turnedOn;
            }
            for (Mod m : mods) {
                if (m.tag != null || changed >= 30) continue;
                for (P pp : m.ps) {
                    if (changed >= 30) break;
                    pp.v = Math.abs(pp.v - pp.def) > 1e-9 ? pp.def
                            : (pp.v != pp.max ? pp.max : pp.min);
                    changed++;
                }
            }
            lastCfgReal = exportConfig().length();

            // 恢复
            for (int i = 0; i < mods.length; i++) {
                mods[i].on = on0[i];
                mods[i].bindKey = bind0[i];
                for (int j = 0; j < mods[i].ps.length; j++) mods[i].ps[j].v = pv0[i][j];
            }
            antiCheat = ac0;
            lastCfgLen = cfg.length();
            lastCfgQuant = maxQuant;
            lastCfgEmptyLen = emptyCfg.length();
            log("config roundtrip OK, 导出 " + cfg.length() + " 字符, 空配置 " + emptyCfg.length()
                    + " 字符, 最大量化档位 " + maxQuant);
        } catch (AssertionError ae) {
            throw ae;
        } catch (Throwable t) {
            throw new AssertionError("配置往返自检异常: " + t);
        }

        // 17) "每个功能一个"的数组必须和 mods 一样长。
        //     wasOn 当初只声明成 new boolean[0] 忘了分配, 于是 onFrame 每帧在 wasOn[0] 上抛
        //     ArrayIndexOutOfBoundsException: 0。异常发生在模块调度**之前**, 所以所有功能静默失效,
        //     日志里只有刷屏的 "onFrame FAILED" —— 而且 GUI 一开 onFrame 就提前 return, 症状跟着消失,
        //     更难查。这条守着不许再加一个忘了分配大小的数组。
        ensureAnimArrays();
        if (keyPrev.length != mods.length) throw new AssertionError("keyPrev 长度 " + keyPrev.length + " != " + mods.length);
        if (wasOn.length != mods.length) throw new AssertionError("wasOn 长度 " + wasOn.length + " != " + mods.length);
        if (hoverAnim.length != mods.length) throw new AssertionError("hoverAnim 长度不对");
        if (toggleAnim.length != mods.length) throw new AssertionError("toggleAnim 长度不对");
        if (expanded.length != mods.length) throw new AssertionError("expanded 长度不对");

        // 19) KillAura 的 Target 模式必须齐 6 档, 且默认是 All。
        //     默认值这条最关键: 原来参数 6 是 "Players Only" 布尔, 默认 0 = 不过滤 = 打所有东西。
        //     换成模式之后默认必须是 All, 否则从旧版本升上来的人会发现"突然打不到怪了"。
        //     加了 Players+Monsters / Players+Animals 两档组合: 打人不想顺手打死牛、打怪又不想
        //     只打一种, 全靠这两档, 分开用两个布尔表达会互相打架。
        String[] wantTarget = {"Players", "Monsters", "Animals",
                "Players+Monsters", "Players+Animals", "All"};
        P tp = mods[F_KILLAURA].ps[6];
        if (!tp.isMode()) throw new AssertionError("KillAura 参数 6 应该是模式参数 (Target)");
        if (tp.opts.length != wantTarget.length)
            throw new AssertionError("Target 档数不对: " + tp.opts.length + " != " + wantTarget.length);
        for (int i = 0; i < wantTarget.length; i++) {
            if (!wantTarget[i].equals(tp.opts[i]))
                throw new AssertionError("Target[" + i + "] 应该是 " + wantTarget[i] + ", 实际 " + tp.opts[i]);
        }
        if (tp.idx() != 5) throw new AssertionError("Target 默认应该是 All(5), 实际 " + tp.idx());

        // 20) 分栏几何必须在各种物理分辨率下都成立 —— 见 checkColumnGeometry()
        //     它自己会遍历 COL_TEST_SCREENS, 这里不用再套一层循环。
        checkColumnGeometry();
        layoutForFake(1705, 1465, uiScale(1465));   // 恢复: 用户实测分辨率
        layoutColumns();

        System.out.println("selfTest OK: " + mods.length + " 个功能 (" + dispatchedSet.size() + " 项已实现), "
                + CAT_COUNT + " 个分类, 字体字符 " + csLen);
        System.out.println("  字体图集: " + csLen + " 字, 缩放 1..4 需求边长 "
                + TtfFont.atlasSizeFor(csLen, 24, 1) + "/" + TtfFont.atlasSizeFor(csLen, 24, 2)
                + "/" + TtfFont.atlasSizeFor(csLen, 24, 3) + "/" + TtfFont.atlasSizeFor(csLen, 24, 4));
        int colsFit = 0, lastRight = 0;
        for (int c = 0; c < NCOL; c++) {
            if (colY[c] == colY[0]) { colsFit++; lastRight = colX[c] + colW[c]; }
        }
        System.out.println("  1705x1465@1x (用户实测): " + NCOL + " 个独立竖列, 首行 " + colsFit
                + " 列占到 x=" + lastRight + " (屏宽 1705), 列宽 " + java.util.Arrays.toString(colW));
        System.out.println("  每列可独立拖动 (拖标题栏) / 独立滚动 (滚轮) / 独立改宽 (拖右边缘)");
        System.out.println("  配置分享: 全默认 " + lastCfgEmptyLen + " 字符 / 开 12 个功能改 30 项参数 "
                + lastCfgReal + " 字符 (AZC2 + base64url)");
        System.out.println("            在线分享: 6 位短码 (上传到 azclient-auth, 配置存服务端 configs 表)");
        System.out.println("            索引编码 + 布局指纹, 只传与默认不同的项, 往返无损; "
                + "指纹不匹配会拒绝导入而不是套错位置");
    }

    /** 供自检用: 不依赖 MC 屏幕对象, 直接按给定物理尺寸算几何 */
    private static void layoutForFake(int physWidth, int physHeight, int scale) {
        scaleLayout(scale, physWidth, physHeight);
        int uw = physWidth / scale, uh = physHeight / scale;   // 可用 UI 单位
        int pw = Math.max(PANEL_W_MIN_L, Math.min(PANEL_W_L, uw - 16));
        int ph = Math.min(PANEL_H_L, uh - 16);
        if (ph < 210) ph = Math.max(60, uh - 16);   // 极小窗口: 尽量塞下, 版面会退化
        PANEL_W = pw * scale;
        PANEL_H = ph * scale;
        PY = Math.max(4, Math.min(PY_L, (uh - ph) / 2)) * scale;
        PX = Math.max(4, Math.min(PX_L, (uw - pw) / 2)) * scale;
    }

    // ===== 列表区几何 (渲染与命中共用, 不允许各写一份) =====
    /** 列表左边界 = 竖栏右边 */
    private static int listX() { return PX + RAIL_W; }
    /** 列表右边界 (留出滚动条) */
    private static int listRight() { return PX + PANEL_W - 8 * S; }
    private static int listTop() { return PY + HEADER_H; }
    private static int listBot() { return PY + PANEL_H - FOOT_H; }
    private static int viewH() { return listBot() - listTop(); }

    /** 竖栏第 i 个分类项的 y */
    private static int railItemY(int i) {
        return PY + HEADER_H + 8 * S + i * (RAIL_ITEM_H + RAIL_ITEM_GAP_L * S);
    }
    private static int railX() { return PX + 6 * S; }
    private static int railW() { return RAIL_W - 12 * S; }

    /** 竖栏底部反作弊块: 贴底锚定; 面板太矮就整块不画 (优先保住分类和列表) */
    /**
     * 反作弊模板。索引 -1 表示"全部关闭"。
     * 每条都是"把参数调到该反作弊能容忍的范围", 不是开关集合 —— 详细见 applyPreset。
     */
    static final String[] AC_NAMES = {"NCP", "Watchdog", "AAC", "Matrix", "Verus", "Legit", "Rage", "OFF"};
    private static final int AC_COLS = 2;   // 8 个单列放不下 (竖栏可用高度只有 134px), 排成两列
    private static int acRows() { return (AC_NAMES.length + AC_COLS - 1) / AC_COLS; }
    private static int acItemW() { return (railW() - (AC_COLS - 1) * AC_ITEM_GAP_L * S) / AC_COLS; }
    private static int acItemX(int i) { return railX() + (i % AC_COLS) * (acItemW() + AC_ITEM_GAP_L * S); }

    private static int acBlockH() {
        return AC_LABEL_H_L * S + acRows() * AC_ITEM_H + (acRows() - 1) * AC_ITEM_GAP_L * S;
    }
    private static int railBottom() {
        return railItemY(CAT_COUNT - 1) + RAIL_ITEM_H;
    }
    private static boolean hasAcBlock() {
        return listBot() - railBottom() >= acBlockH();
    }
    private static int acBlockTop() { return listBot() - acBlockH(); }
    private static int acItemY(int i) {
        return acBlockTop() + AC_LABEL_H_L * S + (i / AC_COLS) * (AC_ITEM_H + AC_ITEM_GAP_L * S);
    }

    /** 搜索框 (右上角) */
    private static int searchW() { return SEARCH_W_L * S; }
    private static int searchH() { return SEARCH_H_L * S; }
    private static int searchX() { return PX + PANEL_W - 8 * S - searchW(); }
    private static int searchY() { return PY + (HEADER_H - searchH()) / 2; }

    /** 面板几何不变量: 竖栏/列表/控件都在面板内且互不重叠 */
        /**
     * 分栏几何检查 —— 这是"各种错位"的守卫。
     *
     * 逐分辨率验证: 每列在屏幕内、行列不溢出、行内控件从右往左排开且不越界、列与列不重叠、
     * 参数行的标签区还有正宽度。宽度/行高/字体尺寸任何一处改动, 都会在这里的某个分辨率上
     * 先炸, 而不是等用户开了游戏才发现。
     */
    private static final int[] COL_TEST_SCREENS = {
            854, 480, 1024, 768, 1280, 720, 1366, 768, 1600, 900,
            1705, 1465, 1920, 1080, 2560, 1440, 3840, 2160,
    };

    private static void checkColumnGeometry() {
        for (int i = 0; i < COL_TEST_SCREENS.length; i += 2) {
            int pw = COL_TEST_SCREENS[i], ph = COL_TEST_SCREENS[i + 1];
            String where = pw + "x" + ph;
            scaleLayout(uiScale(ph), pw, ph);
            colInitW = -1;
            colInitH = -1;
            layoutColumns();

            for (int c = 0; c < NCOL; c++) {
                if (colW[c] < COL_W_MIN || colW[c] > COL_W_MAX) {
                    throw new AssertionError(where + ": 列 " + c + " 宽度 " + colW[c] + " 超出 ["
                            + COL_W_MIN + "," + COL_W_MAX + "]");
                }
                if (colX[c] < 0 || colX[c] + colW[c] > pw) {
                    throw new AssertionError(where + ": 列 " + c + " 横向越界 x=" + colX[c] + " w=" + colW[c]);
                }
                if (colY[c] < 0 || colY[c] + COL_HEAD_H > ph) {
                    throw new AssertionError(where + ": 列 " + c + " 纵向越界 y=" + colY[c]);
                }
                if (colViewH(c) < COL_HEAD_H + COL_ROW_H) {
                    throw new AssertionError(where + ": 列 " + c + " 连一行都放不下");
                }
                // 行内控件: 开关在最右, 热键胶囊在它左边, 名称区再往左。整组不许压到左边界。
                int right = colX[c] + colW[c] - 2 * S;
                int tw = 34 * S, bw = 44 * S;
                int tx = right - 6 * S - tw;
                int bx = tx - 4 * S - bw;
                if (bx < colX[c] + 2 * S + 20 * S) {
                    throw new AssertionError(where + ": 列 " + c + " 太窄, 热键/开关压到名称区 bx=" + bx);
                }
                if (right - COL_RESIZE_W < bx + bw) {
                    throw new AssertionError(where + ": 列 " + c + " 右边缘拖宽区压到开关");
                }

                // 参数行: 最宽的模式胶囊吃掉多少, 标签区还剩多少
                if (c == SET_COL) continue;
                int pillMax = 34 * S;
                for (Mod m : mods) {
                    if (m.cat != c) continue;
                    for (P pp : m.ps) {
                        if (!pp.isMode()) continue;
                        for (String o : pp.opts) {
                            pillMax = Math.max(pillMax, o.length() * 10 * S + 16 * S);
                        }
                    }
                }
                pillMax = Math.min(pillMax, Math.max(34 * S, colW[c] - 80 * S));
                int labelW = colW[c] - 8 * S - pillMax - 14 * S - 4 * S;
                if (labelW < 40 * S) {
                    throw new AssertionError(where + ": 列 " + c + " 参数标签区只剩 " + labelW
                            + "px (模式胶囊吃掉 " + pillMax + "px)");
                }
            }

            // 列与列不许重叠 —— 换行装箱算错时最容易出这个
            for (int a = 0; a < NCOL; a++) {
                for (int b = a + 1; b < NCOL; b++) {
                    boolean ox = colX[a] < colX[b] + colW[b] && colX[b] < colX[a] + colW[a];
                    boolean oy = colY[a] < colY[b] + colViewH(b) && colY[b] < colY[a] + colViewH(a);
                    if (ox && oy) {
                        throw new AssertionError(where + ": 列 " + a + " 和列 " + b + " 重叠 ("
                                + colX[a] + "+" + colW[a] + " vs " + colX[b] + "+" + colW[b] + ")");
                    }
                }
            }
        }
        colInitW = -1;
        colInitH = -1;
    }

    /** rowH 高的行里, 用格顶偏移 cellTop 画 24px 格子时墨迹是否越界 */
    private static void assertInk(String what, int rowH, int cellTop, int inkTopOff, int inkBotOff) {
        int top = cellTop + inkTopOff, bot = cellTop + inkBotOff;
        if (top < 0 || bot > rowH) {
            throw new AssertionError(what + " 文字越界: 墨迹 " + top + ".." + bot + " 不在 0.." + rowH);
        }
    }

    /** 命令行自检入口: java -cp build/agent com.lite.agent.Hook */
    public static void main(String[] args) {
        selfTest();
        for (int c = 0; c < CAT_COUNT; c++) {
            System.out.println("  [" + CAT_NAMES[c] + "] " + catCount(c) + " 项");
        }
    }

    // ===== 搜索 =====
    private static boolean searchFocused = false;
    private static String searchQuery = "";

    /** 搜索匹配: 功能名 (英文) 或补充关键词命中即可。inList 和自检共用这一处定义。 */
    static boolean matches(Mod m, String q) {
        return m.name.toLowerCase().contains(q) || m.key.contains(q);
    }

    /**
     * 该功能在它自己那一列里是否可见。
     *
     * 分栏之后分类由"列"本身决定, 所以这里**只**做搜索过滤。
     * 以前这里还有一句 `m.cat == curCat` —— 那会让每列只显示"当前分类"的功能,
     * 结果除了一列之外全是空的 (在几何检查里表现为"列里连一行都放不下")。
     */
    private static boolean inList(Mod m) {
        return searchQuery.length() == 0 || matches(m, searchQuery);
    }

    private static int listContentH() {
        int h = 0;
        for (int m = 0; m < mods.length; m++) if (inList(mods[m])) h += cardH(m);
        return h;
    }

    private static int listCount() {
        int n = 0;
        for (Mod m : mods) if (inList(m)) n++;
        return n;
    }

    private static int maxScroll() {
        return Math.max(0, listContentH() - viewH());
    }

    private static int catCount(int cat) {
        int n = 0;
        for (Mod m : mods) if (m.cat == cat) n++;
        return n;
    }

    /**
     * 正文文字顶端 (= 字形格子左上角) 的 y。
     *
     * 两个分支是**两种字体各自的偏移**, 不是重复: TTF 的墨迹实测在格顶下 [3,23),
     * 视觉中心在 +13 (INK_CENTER, 见 InkMeasure/FontProbe); MC 内置字体一行只有 9 单位,
     * 用 4 才居中。混用任何一个数, 字都会偏上或偏下 3~8 像素。
     */
    private static int textY(int rowY, int rowH) {
        if(OverlayFont.active())return rowY+rowH/2-OverlayFont.center(17*S);
        return rowY + rowH / 2 - (TtfFont.ready() ? INK_CENTER : 4 * S);
    }

    /**
     * 切到"物理像素"投影: 1 单位 = 1 屏幕像素。
     *
     * MC 在 drawScreen 之前已经把投影设成了逻辑单位 (ScaledResolution 里
     * glOrtho(0, scaledWidth, scaledHeight, 0, ...)), 但 viewport 一直是整个物理画面。
     * 所以这里自己压一套 ortho 到物理尺寸, 细节就能做到 1 像素而不是 S 像素。
     * 必须成对调用, 否则会把 MC 后面的绘制 (HUD 等) 一起带偏。
     */
    /**
     * 把投影换成"1 单位 = 1 物理像素", 让细节能画到单像素而不是 S 像素。
     *
     * MC 在 drawScreen 前已把投影设成逻辑单位, 但 viewport 一直是整块物理画面,
     * 所以这里自己压一套 ortho 到物理尺寸。必须成对调用。
     *
     * **z 轴平移不能省**: glOrtho(.., 1000, 3000) 把 z_eye∈[1000,3000] 映到深度缓冲,
     * 而 Tessellator 画 GUI 时 z_eye=0 —— 算出来 z_ndc = -2, 整个被裁掉, 面板一点都看不见。
     * ScaledResolution 正是靠 MODELVIEW 上这句 translate(0,0,-2000) 把 GUI 挪进裁剪体的。
     * 之前漏了这句: 命中判定是纯数学所以照常工作, 画面却全空 —— 表现就是"按面板键没反应"。
     */
    /** Keep overlay coordinates and the framebuffer projection in one unit. */
    private static void refreshPhysicalSize() {
        try {
            if (mGetMC == null || fDisplayW == null || fDisplayH == null) return;
            Object mc = mGetMC.invoke(null);
            if (mc == null) return;
            int w = ((Number) fDisplayW.get(mc)).intValue();
            int h = ((Number) fDisplayH.get(mc)).intValue();
            if (w > 0 && h > 0) { physW = w; physH = h; }
        } catch (Throwable ignored) { }
    }

    private static void pushPhysicalProjection() throws Exception {
        refreshPhysicalSize();
        mMatrixMode.invoke(null, 5889);        // GL_PROJECTION
        mPushMatrix.invoke(null);
        mLoadIdentity.invoke(null);
        mOrtho.invoke(null, 0.0d, (double) physW, (double) physH, 0.0d, 1000.0d, 3000.0d);
        mMatrixMode.invoke(null, 5888);        // GL_MODELVIEW
        mPushMatrix.invoke(null);
        mLoadIdentity.invoke(null);
        mTranslate.invoke(null, 0f, 0f, -2000f);   // 关键: 不挪进裁剪体就什么都画不出来
    }

    private static void popPhysicalProjection() throws Exception {
        mMatrixMode.invoke(null, 5889);
        mPopMatrix.invoke(null);
        mMatrixMode.invoke(null, 5888);
        mPopMatrix.invoke(null);
    }

    /**
     * 压一套"1 单位 = 1 逻辑 GUI 单位"的 2D 正交投影, 用完必须配 popPhysicalProjection()。
     *
     * 为什么必须有这个: 追踪线 / 名字标签是在 Hook.onRender (也就是 EntityRenderer.renderWorld
     * 里面) 调的, 那时候投影还是**3D 相机透视**, modelview 是相机变换。而 Gui.drawRect 和
     * Gui.drawString 都走**当前**矩阵 —— 直接喂屏幕像素坐标, 等于让 3D 矩阵再变换一次:
     * 坐标 (400, 300) 被当成"相机前方 400 单位、右 300 单位"的一个点, 于是画出来的东西
     * 每帧落在完全不同的地方。现象就是"tracers 线到处乱跳"。
     *
     * 用逻辑单位而不是物理像素: 这些覆盖层 (ESP 框 / 追踪线 / 名字标签) 的几何是按 GUI 单位
     * 定的, 应该跟其它 GUI 元素一起被 guiScale 放大 —— 2560 宽的屏上按物理像素画会小得看不清。
     *
     * 文字是另一件事: 图集是按物理像素 (1:1) 烘焙的, 搬进这个空间得自己压一档才和原版一样大,
     * 所以那两处文字走 textLogical/textWLogical, 不是 text/textW。见那边的说明。
     */
    private static void pushLogicalProjection() throws Exception {
        refreshPhysicalSize();
        float gsf = GS <= 0 ? 1f : GS;
        double lw = physW / gsf, lh = physH / gsf;
        mMatrixMode.invoke(null, 5889);        // GL_PROJECTION
        mPushMatrix.invoke(null);
        mLoadIdentity.invoke(null);
        mOrtho.invoke(null, 0.0d, lw, lh, 0.0d, 1000.0d, 3000.0d);
        mMatrixMode.invoke(null, 5888);        // GL_MODELVIEW
        mPushMatrix.invoke(null);
        mLoadIdentity.invoke(null);
        mTranslate.invoke(null, 0f, 0f, -2000f);   // 同 pushPhysicalProjection, 不挪进裁剪体就画不出来
    }

        // =====================================================================
    // 每个分类一个独立竖列 (Vape 式)。列各自可拖动、各自可滚动。
    //
    // 为什么推倒重来: 旧版是"一个大面板 + 左侧分类竖栏", 分类不能独立移动,
    // 而且所有内容挤在一个滚动列表里, 一屏只能看到 10 项。
    // 现在每个分类自己一列, 各自展开、各自滚动, 想看哪个拖哪个。
    // =====================================================================

    /** 列数 = 6 个分类 + 1 个设置列 */
    private static final int NCOL = CAT_COUNT + 2;   // 6 个分类 + SETTINGS + CONFIG
    private static final int SET_COL = CAT_COUNT;
    /**
     * 配置列 —— 和 Combat/Misc/Settings 同级的一整列。
     *
     * <p>用户要求: "我要的是和 misc 之类的竖列同级的, 名字为 config 的列表, 不在这个框里"。
     * 原来配置相关的所有东西 (命名 / 保存 / 分享码 / 载入 / 列表) 是**塞在设置列底部**的
     * 一小段, 加上一个浮在上面的小窗 —— 列表只显示一条、要按 ^ v 翻。那是"一坨屎"的来源。
     *
     * <p>现在独立成一列: 列表**全部铺开**, 分享/粘贴是列里的按钮, 反馈也显示在列里。
     */
    private static final int CONFIG_COL = CAT_COUNT + 1;

    private static int[] colX = new int[NCOL], colY = new int[NCOL];
    private static int[] colW = new int[NCOL], colH = new int[NCOL];
    private static int[] colScroll = new int[NCOL];
    private static int colInitW = -1, colInitH = -1;   // 记录"已为哪个屏幕尺寸排过版"
    private static int dragCol = -1, dragColDX, dragColDY;

    private static final int COL_MARGIN_L = 8;         // 物理像素
    private static final int COL_GAP_L = 6;
    private static final int COL_W_MIN_L = 150, COL_W_MAX_L = 340;
    private static final int COL_HEAD_H_L = 25;
    private static final int COL_ROW_H_L = 23;
    private static final int COL_PARAM_H_L = 21;
    private static final int COL_PAD_L = 4;
    private static final int COL_RADIUS_L = 3;
    private static final int COL_RESIZE_W_L = 5;       // 右边缘拖宽的命中宽度
    /**
     * 设置列底部那组固定行。**顺序必须和 clickAt 里那串 cy += COL_ROW_H 一一对应。**
     *
     * 做成数组而不是"一个计数常量 + 一份渲染字面量": 原来是后者, 后来加了几行而常量没跟上,
     * 列的内容高度就比实际少算了 3 行, 底部那几行滚不到。计数直接取 length 就不会再漂。
     *
     * **用户要求重排成两部分** ("我只要两个部分"):
     *   第一部分 = 个人配置单 (名字输入框 + Save + 列表 + Targets)
     *   第二部分 = 一个按钮, 点开是独立的可拖动小窗, 里面才做分享/载入
     *
     * 所以 "Share Config" 这一行的语义变成了"打开那个小窗", 分享码、生成、载入全在窗里。
     * 原来的 Copy Config / Import Clipboard 没删 —— 它们现在也在小窗里, 因为它俩本质
     * 也是"配置的进出", 和分享是同一件事。
     */
    /**
     * 第二部分那组固定行。
     *
     * "Reset Layout" 保留 —— 它原来在 CFG_ROWS 里, 重排时差点跟着一起丢掉,
     * 而"列拖乱了怎么恢复"是面板上唯一没别的入口的操作。
     *
     * <p>**"Share / Load" 已经从这里删掉了** —— 分享/载入搬进了独立的 CONFIG 列
     * (用户要求: "我要的是和 misc 之类的竖列同级的, 名字为 config 的列表, 不在这个框里")。
     * 留着那一行的话会有两个入口, 而其中一个还开着那个已经被删掉的浮窗。
     */
    private static final String[] CFG_ROWS = {"Targets", "Reset Layout"};

    private static int COL_MARGIN, COL_GAP, COL_W_MIN, COL_W_MAX, COL_RESIZE_W;
    private static int COL_HEAD_H, COL_ROW_H, COL_PARAM_H, COL_PAD, COL_RADIUS;

    private static int sunset(double phase, boolean moving) {
        return VisualStyle.sunset(phase, System.currentTimeMillis(), flowSpeed, moving);
    }
    private static int overlayColor(int rgb, double phase) {
        return sunsetOverlays ? sunset(phase, rainbowMode) : rgb;
    }
    private static void softGlow(int x, int y, int w, int h, int color, float amount) throws Exception {
        if (amount <= 0 || w <= 0 || h <= 0) return;
        if (!SoftEffects.glow(x, y, w, h, color, amount)) {
            // Compatible fallback: low-opacity concentric layers, never a solid bright wash.
            for (int i = 0; i < 8; i++) {
                int ix = w * i / 24, iy = h * i / 24;
                roundedRect(x + ix, y + iy, x + w - ix, y + h - iy,
                        Math.min(w - 2 * ix, h - 2 * iy) / 2, fade(color, amount * 0.025f));
            }
        }
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow) throws Exception {
        dreamSurface(x,y,w,h,radius,glow,1f);
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow,float opacity) throws Exception {
        dreamSurface(x,y,w,h,radius,glow,opacity,true);
    }
    private static final FrameGlow.Painter glowPainter=new FrameGlow.Painter(){
        public void rect(int x,int y,int right,int bottom,int color)throws Exception{mDrawRect.invoke(null,x,y,right,bottom,color);}
    };
    private static void frameGlow(int x,int y,int w,int h,int radius,int spread,int first,int last,float strength)throws Exception{
        if(spread<=0||strength<=0)return;
        if(!FrostPass.glow(physW,physH,x,y,w,h,radius,spread,first,last,strength))
            FrameGlow.draw(glowPainter,x,y,w,h,radius,spread,first,last,strength);
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow,float opacity,boolean frost) throws Exception {
        dreamSurface(x,y,w,h,radius,glow,opacity,frost,1f);
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow,float opacity,boolean frost,float rimStrength) throws Exception {
        dreamSurface(x,y,w,h,radius,glow,opacity,frost,rimStrength,-1f);
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow,float opacity,boolean frost,float rimStrength,float backgroundAlpha) throws Exception {
        dreamSurface(x,y,w,h,radius,glow,opacity,frost,rimStrength,backgroundAlpha,1f);
    }
    private static void dreamSurface(int x,int y,int w,int h,int radius,boolean glow,float opacity,boolean frost,float rimStrength,float backgroundAlpha,float glowAlpha) throws Exception {
        if(w<=0||h<=0||opacity<=0)return;
        float plate=backgroundAlpha<0?1:backgroundAlpha;
        if(frost&&!guiOpen&&hudFrost&&plate>0) FrostPass.draw(physW,physH,x,y,x+w,y+h,radius,glassBlurRadius*1.8f,opacity*plate);
        int spread=Math.round(bloomSpread*(1.2f+.6f*atmosphere)*(.7f+.3f*rimStrength))*S;
        int left=deckAccent(),right=sunsetPalette?blend(deckAccentEnd(),0xFFFFAA7D,.48f):deckAccentEnd();
        if(glow)frameGlow(x,y,w,h,radius,spread,left,right,bloom*(.78f+.22f*atmosphere)*opacity*rimStrength*glowAlpha);
        if(backgroundAlpha==0)return;
        // Reflections remain quiet; the luminous region is strictly outside the rounded frame.
        roundedRect(x,y,x+w,y+h,radius,fade(0x986B496F,opacity*plate));
        int alpha=Math.round(255*(backgroundAlpha<0?(.36f+.42f*glassOpacity):backgroundAlpha));
        roundedRect(x+S,y+S,x+w-S,y+h-S,Math.max(0,radius-S),fade((alpha<<24)|0x0020162D,opacity));
        int inset=Math.max(2*S,radius/2);
        gradientV(x+inset,y+2*S,x+w-inset,y+Math.min(h/2,90*S),fade(0x143E304D,opacity*plate),0x001C152B);
        deckGradient(x+radius,y,Math.max(1,w-radius*2),Math.max(1,S),fade(left,.65f*opacity*plate),fade(right,.55f*opacity*plate));
    }
    private static void dreamBox(int l, int t, int r, int b, int rgb, int th, boolean corners, boolean fill) throws Exception {
        int color = overlayColor(rgb, (t + l) / (double) Math.max(1, physW + physH));
        int bottom = sunsetOverlays ? sunset(0.60 + b / (double) Math.max(1, physH) * 0.1, rainbowMode) : color;
        if (fill) gradientV(l, t, r, b, fade(color, 0.12f), fade(bottom, 0.04f));
        if (th <= 0) return;
        int len = corners ? Math.max(3, Math.min(22 * S, Math.min(r - l, b - t) / 4)) : r - l;
        int vertical = corners ? len : b - t;
        int[][] lines = corners ? new int[][]{{l,t,l+len,t},{r-len,t,r,t},{l,b,l+len,b},{r-len,b,r,b},{l,t,l,t+vertical},{r,t,r,t+vertical},{l,b-vertical,l,b},{r,b-vertical,r,b}}
                : new int[][]{{l,t,r,t},{l,b,r,b},{l,t,l,b},{r,t,r,b}};
        for (int[] q : lines) {
            int c = q[1] >= (t+b)/2 ? bottom : color;
            SoftEffects.uniformLine(q[0],q[1],q[2],q[3],c,Math.min(th+2*S,bloomSpread*S),bloom*.10f);
            int core = blend(c, 0xFFFFF1F6, 0.25f);
            mDrawRect.invoke(null, q[0], q[1], Math.max(q[0]+th,q[2]), Math.max(q[1]+th,q[3]), core);
        }
    }

    // Floating category panels. The same geometry owns painting and input.
    // Legacy column geometry stays available to compatibility probes.
    private static boolean auroraWorkspace;
    private static int auroraX, auroraY, auroraW, auroraH;
    private static int deckPage, deckCapacity = NCOL, deckLayoutW = -1, deckLayoutH = -1;
    private static int deckX, deckY, deckW, deckH;
    private static final PanelPositions deckPositions = new PanelPositions(NCOL + 1, "gui-layout.properties");
    private static boolean deckDragging;
    private static int deckGrabX, deckGrabY;
    private static void saveDeckPositions() {
        try { deckPositions.save(); } catch (java.io.IOException e) { log("GUI position save failed: " + e); }
    }
    private static int columnAtDepth(int depth) {
        if (!auroraWorkspace) return depth;
        int found = 0;
        for (int i = 0; i <= NCOL; i++) {
            int c = deckPositions.at(i);
            if (c < NCOL && found++ == depth) return c;
        }
        return depth;
    }
    private static void updatePanelDrag(int mx, int my, boolean down) {
        if (deckDragging) {
            if (down) {
                deckPositions.move(NCOL, mx-deckGrabX, my-deckGrabY, physW, physH, deckW, deckH);
            } else { deckDragging=false; saveDeckPositions(); }
        }
        if (dragCol >= 0) {
            if (down) {
                colX[dragCol]=mx-dragColDX; colY[dragCol]=my-dragColDY;
                if (auroraWorkspace) deckPositions.move(dragCol,colX[dragCol],colY[dragCol],physW,physH,colW[dragCol],COL_HEAD_H+COL_ROW_H);
                else clampCol(dragCol);
            } else { dragCol=-1; saveDeckPositions(); }
        }
    }
    private static int deckFocusColumn = -1;
    private static final UiMotion deckMotion = new UiMotion();
    private static float[] deckExpand = new float[0];
    private static float deckDelta = .016f, deckPageReveal = 1f;
    private static long deckLastNanos;
    private static boolean deckFramePrepared;
    private static int deckPressX = -1000, deckPressY = -1000;
    private static float deckClickFlash;
    private static void deckBeginFrame(long now) {
        setThemeFromParams(); ensureAnimArrays();
        deckDelta = deckLastNanos == 0 ? .016f : (float)Math.max(0, Math.min(.1, (now-deckLastNanos)/1e9));
        deckLastNanos = now; deckDelta *= interactionSpeed*1.35f;
        if (deckExpand.length != mods.length) deckExpand = new float[mods.length];
        deckAdvanceExpansion();
        deckPageReveal=UiMotion.unit(deckMotion.value("page",1,1,20,1,deckDelta,interactionMotion));
        deckClickFlash=UiMotion.unit(deckMotion.value("click",0,0,18,.62,deckDelta,interactionMotion));
        deckFramePrepared=true;
    }
    private static void deckAdvanceExpansion() {
        for(int i=0;i<mods.length;i++)deckExpand[i]=UiMotion.unit(deckMotion.value("expand:"+i,isExpanded(i)?1:0,isExpanded(i)?1:0,30,1,deckDelta,interactionMotion));
    }
    private static int deckParamHeight(int m) {
        if (mods[m].tag != null) return 0;
        float amount = !interactionMotion || deckExpand.length != mods.length ? (isExpanded(m)?1:0) : deckExpand[m];
        return Math.round(mods[m].ps.length * COL_PARAM_H * amount);
    }
    private static float deckValue(Object key,double target,double initial,double speed,double damping) {
        return (float)deckMotion.value(key,target,initial,speed,damping,deckDelta,interactionMotion);
    }
    private static void deckFeedback(int x,int y,int w,int h,boolean over,String key) throws Exception {
        float hover=UiMotion.unit(deckValue(key+":hover",over?1:0,0,25,.85));
        if(hover>.01f) roundedRect(x,y,x+w,y+h,5*S,fade(deckAccent(),hover*.16f));
        if(deckClickFlash>.01f && deckPressX>=x && deckPressX<x+w && deckPressY>=y && deckPressY<y+h){
            frameGlow(x,y,w,h,5*S,Math.max(6*S,Math.min(16*S,bloomSpread*S)),deckAccent(),deckAccentEnd(),deckClickFlash*.6f);
        }
    }
    private static final int DECK_TEXT = 0xFFF1F2F6, DECK_MUTED = 0xFFADB1C0;

    private static int deckAccent() { return sunsetPalette ? sunset(0.1, rainbowMode) : blend(ACCENT(), 0xFFFFFFFF, 0.25f); }
    private static int deckAccentEnd() { return sunsetPalette ? sunset(0.62, rainbowMode) : blend(deckAccent(), 0xFFCFA6ED, 0.3f); }
    private static boolean deckVisible(int c) {
        return c >= deckPage * deckCapacity && c < Math.min(NCOL, (deckPage + 1) * deckCapacity);
    }
    private static int deckPages() { return (NCOL + deckCapacity - 1) / deckCapacity; }
    private static int deckBrandW() { return deckW < 600 * S ? 30 * S : 154 * S; }
    private static boolean deckQuick() { return deckW >= 520 * S; }
    private static int deckSearchX() { return deckX + deckBrandW(); }
    private static int deckSearchW() { return Math.max(40 * S, deckW - deckBrandW() - (deckQuick() ? 300 : 110) * S); }
    private static int deckQuickX() { return deckSearchX() + deckSearchW() + 12 * S; }

    private static void layoutAuroraWorkspace() {
        auroraWorkspace = true;
        deckPositions.load();
        ensureAnimArrays();
        COL_HEAD_H = 42 * S;
        COL_ROW_H = 31 * S;
        COL_PARAM_H = 52 * S;
        COL_RADIUS = 8 * S;
        COL_PAD = 6 * S;
        int avail = Math.max(1, physW - 48 * S), gap = 12 * S;
        int fit = Math.max(1, Math.min(NCOL, (avail + gap) / (220 * S + gap)));
        deckCapacity = fit >= NCOL ? NCOL : fit >= 4 ? 4 : fit >= 2 ? 2 : 1;
        deckPage = Math.max(0, Math.min(deckPages() - 1, deckPage));
        int count = Math.min(deckCapacity, NCOL - deckPage * deckCapacity);
        int width = Math.min(262 * S, Math.max(1, (avail - gap * (count - 1)) / count));
        auroraW = count * width + gap * (count - 1);
        auroraX = (physW - auroraW) / 2;
        auroraY = Math.min(136 * S, Math.max(100 * S, physH / 7));
        auroraH = Math.max(COL_HEAD_H + COL_ROW_H, physH - auroraY - 48 * S);
        deckW = Math.min(1040 * S, Math.max(1, physW - 32 * S));
        deckH = 56 * S;
        deckX = deckPositions.x(NCOL,(physW-deckW)/2,physW,deckW);
        deckY = deckPositions.y(NCOL,24*S,physH,deckH);
        boolean reset = deckLayoutW != physW || deckLayoutH != physH;
        for (int c = 0; c < NCOL; c++) {
            colX[c] = deckPositions.x(c,auroraX+(c%deckCapacity)*(width+gap),physW,width);
            int restY = deckPositions.y(c,auroraY,physH,COL_HEAD_H+COL_ROW_H);
            colY[c] = restY + (deckPositions.custom(c)?0:Math.round((1-deckPageReveal)*(28+(c%deckCapacity)*4)*S));
            colW[c] = width;
            colH[c] = colContentH(c);
            if (reset) colScroll[c] = 0;
            colScroll[c] = Math.max(0, Math.min(maxColScroll(c), colScroll[c]));
        }
        deckLayoutW = physW; deckLayoutH = physH;
        if (!auroraLogged) {
            auroraLogged = true;
            log("Floating GUI active: version=" + VERSION + " columns=" + deckCapacity);
        }
    }
    private static void deckSearchChanged() {
        java.util.Arrays.fill(colScroll, 0);
        hoverParamMd = null; hoverParamI = -1;
        if (!searchQuery.isEmpty()) for (Mod md : mods) if (inList(md)) {
            deckPage = md.cat / deckCapacity;
            break;
        }
    }
    private static void deckUpdateHover(int mx, int my) {
        hoverParamMd = null; hoverParamI = -1;
        int c = colAt(mx, my);
        if (c >= 0) deckFocusColumn = c;
        if (c < 0 || c >= CAT_COUNT) return;
        int y = colY[c] + COL_HEAD_H - colScroll[c], top = colY[c] + COL_HEAD_H;
        for (Mod md : mods) {
            if (md.cat != c || !inList(md)) continue;
            int m = java.util.Arrays.asList(mods).indexOf(md);
            if (md.tag == null && isExpanded(m)) {
                for (int pi = 0; pi < md.ps.length; pi++) {
                    int py = y + COL_ROW_H + pi * COL_PARAM_H;
                    P pp = md.ps[pi];
                    if (!pp.isMode() && pp.max > pp.min && !(pp.min == 0 && pp.max == 1 && pp.step >= 1)
                            && wholeRowVisible(py, COL_PARAM_H, top, Math.min(colY[c] + colViewH(c), y + COL_ROW_H + deckParamHeight(m)))
                            && mx >= colX[c] + 9 * S && mx <= colX[c] + colW[c] - 9 * S
                            && my >= py + 27 * S && my < py + COL_PARAM_H) {
                        hoverParamMd = md; hoverParamI = pi; return;
                    }
                }
            }
            y += COL_ROW_H + deckParamHeight(m);
        }
    }
    private static void deckSelectPage(int next) {
        int previous=deckPage;
        dragCol=-1; deckDragging=false; saveDeckPositions();
        deckPage = Math.max(0, Math.min(deckPages() - 1, next));
        if (previous!=deckPage) deckMotion.seed("page",0);
        hoverParamMd = null; hoverParamI = -1; dragMod = -1;
        searchFocused = cfgNameFocused = false; bindTarget = -1; paramEditCancel();
        layoutAuroraWorkspace();
    }
    private static boolean clickAuroraNav(int mx, int my, boolean right) {
        if (mx < deckX || mx >= deckX + deckW || my < deckY || my >= deckY + deckH) return false;
        if (right) return true;
        if (mx < deckSearchX() || my < deckY+12*S || my >= deckY+44*S) {
            deckDragging=true; deckGrabX=mx-deckX; deckGrabY=my-deckY;
            dragCol=dragMod=-1; searchFocused=cfgNameFocused=false;
            return true;
        }
        if (mx >= deckSearchX() && mx < deckSearchX() + deckSearchW()) {
            searchFocused = true; cfgNameFocused = false; bindTarget = -1;
            return true;
        }
        if (mx >= deckX + deckW - 42 * S) deckSelectPage(deckPage + 1);
        else if (mx >= deckX + deckW - 98 * S && mx < deckX + deckW - 66 * S) deckSelectPage(deckPage - 1);
        else if (deckQuick() && mx >= deckQuickX() && mx < deckQuickX() + 86 * S) deckSelectPage(SET_COL / deckCapacity);
        else if (deckQuick() && mx >= deckQuickX() + 92 * S && mx < deckQuickX() + 172 * S) deckSelectPage(CONFIG_COL / deckCapacity);
        return true;
    }
    private static boolean wholeRowVisible(int y, int height, int top, int bottom) {
        return y >= top && y + height <= bottom;
    }
    private static void deckSurface(int x, int y, int w, int h, boolean glow) throws Exception {
        dreamSurface(x,y,w,h,12*S,glow,1f,true,.5f);
    }
    private static void deckButton(int x, int y, int w, int h, String label, boolean selected, int mx, int my) throws Exception {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        String key="button:"+label;
        float hv=UiMotion.unit(deckValue(key+":h",hover?1:0,0,23,.8));
        float press=deckValue(key+":p",hover&&mousePrev?1:0,0,28,.58);
        int inset=Math.round(press*3*S),dy=Math.round((press*3-hv*2)*S);
        roundedRect(x+inset,y+dy,x+w-inset,y+h+dy,5*S,blend(selected?0x483D6A88:0x172F3544,0x665B486F,hv));
        deckFeedback(x,y,w,h,hover,key);
        text(label, x + (w - textW(label)) / 2, textY(y+dy, h), selected ? DECK_TEXT : DECK_MUTED);
    }
    private static void drawAuroraWorkspace(int mx, int my) throws Exception {
        deckSurface(deckX, deckY, deckW, deckH, true);
        roundedRect(deckX + 13 * S, deckY + 23 * S, deckX + 20 * S, deckY + 30 * S, 2 * S, deckAccent());
        if (deckBrandW() > 40 * S) {
            text("AZCLIENT", deckX + 30 * S, deckY + 7 * S, DECK_TEXT);
            textScaled("8.0.0 / INTERFACE", deckX + 30 * S, deckY + 32 * S, DECK_MUTED, 0.70f);
        }
        int sx = deckSearchX(), sy = deckY + 14 * S, sw = deckSearchW();
        roundedRect(sx, sy, sx + sw, sy + 28 * S, 5 * S, searchFocused ? 0x77404B60 : 0x650B0E15);
        String query = searchQuery.isEmpty() ? "Search modules..." : searchQuery;
        if (searchFocused && (System.currentTimeMillis() / 500) % 2 == 0) query += "_";
        text(fitText(query, sw - 20 * S), sx + 10 * S, textY(sy, 28 * S), searchQuery.isEmpty() ? DECK_MUTED : DECK_TEXT);
        if (deckQuick()) {
            deckButton(deckQuickX(), sy, 86 * S, 28 * S, "Settings", deckVisible(SET_COL), mx, my);
            deckButton(deckQuickX() + 92 * S, sy, 80 * S, 28 * S, "Config", deckVisible(CONFIG_COL), mx, my);
        }
        deckButton(deckX + deckW - 98 * S, sy, 32 * S, 28 * S, "<", false, mx, my);
        deckButton(deckX + deckW - 42 * S, sy, 32 * S, 28 * S, ">", false, mx, my);
        String page = String.valueOf(deckPage + 1);
        text(page, deckX + deckW - 54 * S - textW(page) / 2, textY(sy, 28 * S), DECK_MUTED);
        String hint = "Drag a title to move   /   Left: toggle   /   Right: settings   /   RShift: close";
        hint = fitText(hint, physW - 40 * S);
        boolean hintClear=true;
        for(int c=0;c<NCOL;c++)if(deckVisible(c)&&colY[c]+colViewH(c)>physH-40*S)hintClear=false;
        if(hintClear&&deckY+deckH<physH-40*S)textScaled(hint, (physW - textWScaled(hint, 0.8f)) / 2, physH - 29 * S, 0xDDB5BACA, 0.8f);
    }
    private static void deckGradient(int x, int y, int w, int h, int left, int right) throws Exception {
        // 32 strips are enough for a smooth, low-cost tinted highlight.
        for (int i = 0; i < 32; i++) {
            int x1 = x + w * i / 32, x2 = x + w * (i + 1) / 32;
            if (x2 > x1) mDrawRect.invoke(null, x1, y, x2, y + h, blend(left, right, i / 31f));
        }
    }
    private static void drawDeckModule(int m, int x, int y, int w, int mx, int my, float d) throws Exception {
        Mod md = mods[m];
        boolean over = mx >= x && mx < x + w && my >= y && my < y + COL_ROW_H;
        float motion = interactionMotion ? (float) common(md, "UI Motion") : 0;
        hoverAnim[m] = motion == 0 ? (over ? 1 : 0) : anim(hoverAnim[m], over ? 1 : 0, 24 * motion, d);
        toggleAnim[m] = motion == 0 ? (md.on ? 1 : 0) : anim(toggleAnim[m], md.on ? 1 : 0, 26 * motion, d);
        float t = toggleAnim[m], hover = hoverAnim[m];
        float press=deckValue("module-press:"+m,over&&(mousePrev||mouseRightPrev)?1:0,0,40,.72);
        int textLift=Math.round(press*3*S),textSlide=Math.round(hover*5*S);
        deckFeedback(x,y,w,COL_ROW_H,over,"module:"+m);
        if (t > 0.01f) {
            deckGradient(x + S, y, w - 2 * S, COL_ROW_H,
                    fade(deckAccent(), t * (0.27f + hover * 0.12f)), fade(deckAccentEnd(), t * (0.32f + hover * 0.10f)));
            float glow = (float) Math.min(1, common(md, "UI Glow") / 100.0) * glassGlow;
            roundedRect(x - S, y + 5 * S, x + 3 * S, y + COL_ROW_H - 5 * S, S, fade(deckAccent(), t * (0.35f + glow * 0.35f)));
        } else if (hover > 0.01f) mDrawRect.invoke(null, x + S, y, x + w - S, y + COL_ROW_H, fade(0x263F4559, hover));
        int keyX = x + w - 68 * S;
        boolean binding = bindTarget == m;
        String key = binding ? "..." : md.bindKey == 0 ? "KEY" : keyName(md.bindKey);
        if (over || binding || md.bindKey != 0)
            textScaled(fitText(key, 40 * S), keyX, textY(y, COL_ROW_H) + 3 * S, binding ? deckAccent() : DECK_MUTED, 0.72f);
        int ax = x + w - 16 * S, ay = y + COL_ROW_H / 2;
        if (md.tag == null && md.ps.length > 0) {
            for (int k = 0; k < 4; k++) {
                float expand=deckExpand.length==mods.length&&interactionMotion?deckExpand[m]:(isExpanded(m)?1:0);
                int yy=ay+Math.round(((k-2)*(1-expand)+(1-k)*expand)*S);
                mDrawRect.invoke(null, ax - (3 - k) * S, yy, ax - (2 - k) * S, yy + S, DECK_MUTED);
                mDrawRect.invoke(null, ax + (3 - k) * S, yy, ax + (4 - k) * S, yy + S, DECK_MUTED);
            }
        }
        text(fitText(md.name, keyX - x - 21 * S), x + 13 * S + textSlide, textY(y+textLift, COL_ROW_H), md.on ? 0xFFFFFFFF : DECK_TEXT);
    }
    private static void drawDeckParam(Mod md, int pi, int x, int y, int w, int mx, int my) throws Exception {
        P pp = md.ps[pi]; int left = x + 14 * S, right = x + w - 14 * S;
        String motionKey="param:"+md.name+":"+pi;
        boolean hover=mx>=left&&mx<=right&&my>=y&&my<y+COL_PARAM_H;
        deckFeedback(x+S,y,w-2*S,COL_PARAM_H,hover,motionKey);
        mDrawRect.invoke(null, x + S, y, x + w - S, y + COL_PARAM_H, 0x2C070A11);
        mDrawRect.invoke(null, left, y + COL_PARAM_H - S, right, y + COL_PARAM_H, 0x0DFFFFFF);
        boolean bool = !pp.isMode() && pp.step >= 1 && pp.min == 0 && pp.max == 1;
        if (bool) {
            text(fitText(pp.n, w - 72 * S), left, textY(y, COL_PARAM_H), DECK_MUTED);
            int tx = right - 30 * S, ty = y + (COL_PARAM_H - 16 * S) / 2;
            float on=UiMotion.unit(deckValue(motionKey+":switch",pp.v>0?1:0,pp.v>0?1:0,21,.60));
            float active=UiMotion.unit(deckValue(motionKey+":hold",hover&&mousePrev?1:0,0,32,.7));
            roundedRect(tx,ty,right,ty+16*S,8*S,blend(0xFF383E4D,fade(deckAccent(),.82f),on));
            int knob=tx+2*S+Math.round(on*14*S),stretch=Math.round(active*3*S);
            softGlow(knob-8*S,ty-6*S,28*S,28*S,deckAccent(),on*.25f);
            roundedRect(knob-stretch,ty+2*S,knob+12*S+stretch,ty+14*S,6*S,blend(0xFFA4ACBD,0xFFF4FAFF,on));
            return;
        }
        if (!pp.isMode() && pp.max <= pp.min) {
            String value = md == mods[F_INVMOVE] && (pi == 0 || pi == 4) ? "Disabled" : "-";
            text(fitText(pp.n, right - left - textW(value) - 8 * S), left, textY(y, COL_PARAM_H), DECK_MUTED);
            text(value, right - textW(value), textY(y, COL_PARAM_H), DECK_MUTED);
            return;
        }
        int valueW = pp.isMode() ? 0 : Math.max(42 * S, textW(fmtNum(pp.v)) + 8 * S);
        text(fitText(pp.n, right - left - valueW), left, textY(y + 2 * S, 24 * S), DECK_MUTED);
        if (pp.isMode()) {
            float press=deckValue(motionKey+":press",hover&&mousePrev?1:0,0,30,.68);
            int inset=Math.round(press*2*S);
            roundedRect(left+inset, y + 26 * S + inset, right-inset, y + 47 * S, 4 * S, 0x80454B60);
            text(fitText(pp.cur(), right - left - 28 * S), left + 7 * S, textY(y + 26 * S, 21 * S), DECK_TEXT);
            text(">", right - 15 * S, textY(y + 26 * S, 21 * S), DECK_MUTED);
            return;
        }
        if (paramEditMd == md && paramEditParam == pi) {
            int ew = Math.min(right - left, Math.max(56 * S, textW(paramEditBuf) + 16 * S));
            editBoxX0 = right - ew; editBoxX1 = right; editBoxY0 = y + 2 * S; editBoxY1 = y + 26 * S;
            roundedRect(editBoxX0, editBoxY0, editBoxX1, editBoxY1, 3 * S, 0xFF333F53);
            text(fitText(paramEditBuf + "_", ew - 6 * S), editBoxX0 + 3 * S, textY(y + 2 * S, 24 * S), DECK_TEXT);
        } else {
            String value = fmtNum(pp.v);
            text(value, right - textW(value), textY(y + 2 * S, 24 * S), deckAccent());
        }
        if (pp.max <= pp.min) return;
        int ty = y + 36 * S, sw = sliderTrackW(w), sx = sliderTrackX(x, w, sw);
        double target=Math.max(0,Math.min(1,(pp.v-pp.min)/(pp.max-pp.min)));
        boolean held=dragMod>=0&&mods[dragMod]==md&&dragParam==pi;
        // Input and parameter values stay exact while dragging. Only the visual tail and thumb shape ease.
        if(held) deckMotion.seed(motionKey+":value",target);
        float shown=UiMotion.unit(deckValue(motionKey+":value",target,target,34,1));
        float lift=UiMotion.unit(deckValue(motionKey+":lift",held?1:hover? .32:0,0,24,.60));
        int fx=sx+Math.round(shown*sw),radius=Math.round((4+lift*3.5f)*S);
        roundedRect(sx,ty,sx+sw,ty+3*S,S,0xFF383E4E);
        if(fx>sx)deckGradient(sx,ty,fx-sx,3*S,deckAccent(),deckAccentEnd());
        softGlow(fx-18*S,ty-16*S,36*S,36*S,deckAccent(),lift*.9f);
        roundedRect(fx-radius,ty+S-radius,fx+radius,ty+S+radius,radius,0xFFF0F5FF);
        if(lift>.4f){
            String value=fmtNum(pp.v);int bw=textWScaled(value,.76f)+14*S,bx=Math.max(left,Math.min(right-bw,fx-bw/2));
            int by=ty-22*S-Math.round(lift*5*S);
            roundedRect(bx,by,bx+bw,by+20*S,5*S,fade(0xF44C345C,lift));
            textScaled(value,bx+7*S,by+3*S,fade(0xFFFFF2FA,lift),.76f);
        }
        // Wheel adjustment is restricted to the track, leaving labels available for scrolling.
        if (mx >= sx - 5 * S && mx <= sx + sw + 5 * S && my >= y + 27 * S && my < y + COL_PARAM_H) {
            hoverParamMd = md; hoverParamI = pi;
        }
    }
    private static void hitDeckParam(int m, int pi, int x, int w, int y, int mx, int my) {
        Mod md = mods[m]; P pp = md.ps[pi]; int left = x + 14 * S, right = x + w - 14 * S;
        if (pp.isMode()) {
            if (mx >= left && mx < right && my >= y + 26 * S && my < y + 47 * S) pp.cycle(1);
            return;
        }
        if (pp.step >= 1 && pp.min == 0 && pp.max == 1) {
            if (mx >= right - 30 * S && mx < right) pp.v = pp.v > 0 ? 0 : 1;
            return;
        }
        if (pp.max <= pp.min) return;
        if (my < y + 27 * S && mx >= right - Math.max(56 * S, textW(fmtNum(pp.v)) + 8 * S) && mx < right) {
            paramEditMd = md; paramEditParam = pi; paramEditBuf = fmtNum(pp.v); searchFocused = cfgNameFocused = false;
        } else if (my >= y + 27 * S && mx >= left - 5 * S && mx <= right + 5 * S) {
            dragSlX = sliderTrackX(x, w, sliderTrackW(w)); dragSlW = sliderTrackW(w);
            applySlider(m, pi, mx); dragMod = m; dragParam = pi;
        }
    }

    /** 用户手动拖出来的列宽; <=0 表示用自适应宽度 */
    private static int[] colWOverride = new int[NCOL];
    private static int resizeCol = -1, resizeStartX, resizeStartW;

    /**
     * 一列需要多宽才不挤。
     * 两个约束取大的: 功能行 (名称+箭头+热键胶囊+开关) 和参数行 (标签+控件)。
     * 列宽按内容自适应 —— 统一宽度会让 Combat 空一大截、Misc 又挤掉名字。
     */
    /**
     * 设置列底部提示行的左右总留白 = 左 14 (rx1 的 8 + 文字再进 6) + 右 8 (rx2)。
     * 渲染 (renderSettingsCol)、列宽估算 (colNeededW)、HintWidth 探针共用这一份 ——
     * 各写一份的结果就是探针按 150-12=138 判, 真实只有 150-22=128, 差 10px 看不出来。
     */
    static int hintInsetL() { return 14 * S + 8 * S; }

    /** 列宽上限 —— HintWidth 探针要按同一个上限判 (超了就一定会被 fitText 截断) */
    static int probeColWMax() { return COL_W_MAX_L * S; }

    private static int colNeededW(int c) {
        if (c == SET_COL) {
            // 设置列的宽度是底部那几行热键说明撑出来的 —— 按真实字宽量, 别再写死。
            //
            // 原来写死 140, 而真实的可用宽是 w - hintInsetL() = 128, 比最宽的
            // "Hotkey: click pill" (135) 还窄 —— 那行本来就在往隔壁列上画。
            int w = 140 * S;
            for (String h : HOTKEY_HINTS) w = Math.max(w, textW(h) + hintInsetL());
            return w;
        }
        if (c == CONFIG_COL) {
            // 宽度按**最长的那条配置名**撑, 但要给右边的 "Copy" 留位置 ——
            // 不留的话长名字会把 Copy 压到列外, 点不到 (设置列那个 Copy 就是这个坑)。
            int nameMaxCfg = 0;
            for (String n : cfgNames) nameMaxCfg = Math.max(nameMaxCfg, n.length());
            int w = 150 * S;
            w = Math.max(w, textW("· ") + nameMaxCfg * 10 * S + textW("Copy") + 24 * S);
            // Share / Paste 两个按钮并排, 也别挤
            w = Math.max(w, textW("Share") + textW("Paste") + 40 * S);
            return w;
        }
        int nameMax = 0;
        for (Mod m : mods) {
            if (m.cat == c && inList(m)) nameMax = Math.max(nameMax, m.name.length());
        }
        // 9px/字符是实测上限 (数字最宽), 用它估宽度才不会在真字体下溢出
        int w1 = 2 * S + 10 * S + nameMax * 10 * S + 12 * S + 4 * S + 44 * S + 4 * S + 34 * S + 6 * S + 2 * S;
        int w2 = 14 * S + 90 * S + 8 * S + 120 * S + 8 * S;   // 标签 90 + 模式胶囊 120
        return Math.max(Math.max(w1, w2), 190 * S);
    }

    /** 一列的可见高度 (内容比屏幕高时按屏幕算) */
    private static int colViewH(int c) {
        if (auroraWorkspace) return Math.min(colH[c], Math.max(COL_HEAD_H+COL_ROW_H, physH-colY[c]-12*S));
        return Math.min(colH[c], physH - 2 * COL_MARGIN);
    }

    private static int maxColScroll(int c) {
        return Math.max(0, colH[c] - colViewH(c));
    }

    /** 这一列里有几个功能 (设置列返回模板数) */
    private static int colCount(int c) {
        if (c == SET_COL) return AC_NAMES.length;
        if (c == CONFIG_COL) return cfgNames.length;
        int n = 0;
        for (Mod m : mods) if (m.cat == c && inList(m)) n++;
        return n;
    }

    /**
     * Config 列的行表 (相对内容顶部的行序号)。
     *
     * <p>**渲染和点击都从这一组函数取 y**, 不许各算一份 —— 原来设置列底部
     * 那几行就是"渲染一个 cy 序列、点击另抄一份", 加一行必错位, 注释里记着已经错过一次。
     *
     * <p>行表: 0=命名输入框, 1=Save, 2=Share/Paste 按钮行, 3=状态行,
     * 4..=配置列表 (每条一行), 末尾=操作提示行。
     */
    private static int cfgRowName()  { return 0; }
    private static int cfgRowSave()  { return 1; }
    private static int cfgRowShare() { return 2; }
    private static int cfgRowStatus(){ return 3; }
    private static int cfgRowList0() { return 4; }
    /** 配置列表条数。至少占一行, 否则空列会塌成 0 高度、点不到 */
    private static int cfgListRows() { return Math.max(1, cfgNames.length); }
    /** 操作提示行 (列表之后一行) */
    private static int cfgRowHint()  { return cfgRowList0() + cfgListRows(); }
    /** Config 列总行数 */
    private static int cfgRowTotal() { return cfgRowHint() + 1; }

    /** 一列的内容高度 */
    private static int colContentH(int c) {
        int h = COL_HEAD_H + COL_PAD;
        if (c == CONFIG_COL) return COL_HEAD_H + COL_PAD + cfgRowTotal() * COL_ROW_H + COL_PAD;
        if (c == SET_COL) {
            h += COL_ROW_H + 6 * S;                  // 搜索框
            h += AC_NAMES.length * COL_ROW_H;        // 模板
            // 命名输入框 / Save / 账号配置列表**已搬去 CONFIG 列**, 这里只剩 CFG_ROWS。
            // 少算多算都会让列底部滚不到 (这个坑在 CFG_ROWS 的注释里记过一次了)。
            h += CFG_ROWS.length * COL_ROW_H;
            h += HOTKEY_HINTS.length * COL_ROW_H;    // 底部热键说明 (必须算, 否则最后几行滚不到)
            return h + COL_PAD;
        }
        for (int m = 0; m < mods.length; m++) {
            Mod md = mods[m];
            if (md.cat != c || !inList(md)) continue;
            h += COL_ROW_H;
            if (auroraWorkspace) h += deckParamHeight(m);
            else if (md.tag == null && isExpanded(m)) h += md.ps.length * COL_PARAM_H;
        }
        return h + COL_PAD;
    }

    /**
     * 排布所有列。
     *
     * 宽度按内容自适应; 总宽超出屏幕就先整体等比压缩, 再不够就贪心换行 ——
     * 小屏 (1024x768) 上 7 列放不进一行, 写死宽度会直接跑到屏幕外。
     * 位置只在"屏幕尺寸变了"时重排, 否则用户拖到哪就是哪。
     */
    private static void layoutColumns() {
        int avail = Math.max(COL_W_MIN, physW - COL_MARGIN * 2);

        int total = COL_GAP * (NCOL - 1);
        for (int c = 0; c < NCOL; c++) {
            colW[c] = colWOverride[c] > 0 ? colWOverride[c] : colNeededW(c);
            total += colW[c];
        }
        if (total > avail) {
            double f = avail / (double) total;
            for (int c = 0; c < NCOL; c++) {
                if (colWOverride[c] > 0) continue;   // 用户指定过的不参与压缩
                colW[c] = Math.max(COL_W_MIN, (int) (colW[c] * f));
            }
        }
        for (int c = 0; c < NCOL; c++) {
            colW[c] = Math.max(COL_W_MIN, Math.min(COL_W_MAX, colW[c]));
            colH[c] = colContentH(c);
        }

        // 贪心装箱: 从左往右放, 放不下就换行
        int[] rowOf = new int[NCOL];
        int rows = 0, x = COL_MARGIN;
        for (int c = 0; c < NCOL; c++) {
            if (x > COL_MARGIN && x + colW[c] > physW - COL_MARGIN) { rows++; x = COL_MARGIN; }
            rowOf[c] = rows;
            x += colW[c] + COL_GAP;
        }
        int nRows = rows + 1;
        int[] rowH = new int[nRows], rowY = new int[nRows];
        for (int c = 0; c < NCOL; c++) rowH[rowOf[c]] = Math.max(rowH[rowOf[c]], colViewH(c));
        int y = COL_MARGIN;
        for (int r = 0; r < nRows; r++) { rowY[r] = y; y += rowH[r] + COL_GAP; }

        boolean first = (colInitW != physW || colInitH != physH);
        if (first) {
            int[] cursor = new int[nRows];
            for (int r = 0; r < nRows; r++) cursor[r] = COL_MARGIN;
            for (int c = 0; c < NCOL; c++) {
                colX[c] = cursor[rowOf[c]];
                colY[c] = rowY[rowOf[c]];
                cursor[rowOf[c]] += colW[c] + COL_GAP;
                colScroll[c] = 0;
            }
            colInitW = physW;
            colInitH = physH;
            log("columns: " + NCOL + " 列, " + nRows + " 行, 屏幕 " + physW + "x" + physH
                    + ", S=" + S + ", 宽 " + java.util.Arrays.toString(colW));
        }
        for (int c = 0; c < NCOL; c++) {
            clampCol(c);
            colScroll[c] = Math.max(0, Math.min(maxColScroll(c), colScroll[c]));
        }
    }

    /** 把字符串截断到指定像素宽 (放不下就补 "..") —— 长名字不许压到右边的胶囊 */
    private static String fitText(String s, int maxW) {
        if (maxW <= 0) return "";
        if (textW(s) <= maxW) return s;
        String ell = "..";
        int ew = textW(ell);
        for (int n = s.length() - 1; n > 0; n--) {
            String t = s.substring(0, n);
            if (textW(t) + ew <= maxW) return t + ell;
        }
        return ell;
    }

    /** 把一列夹回屏幕内 —— 留一点余量, 免得标题栏被**拖动**出屏幕再也点不到 */
    private static void clampCol(int c) {
        colX[c] = Math.max(0, Math.min(Math.max(0, physW - colW[c]), colX[c]));
        colY[c] = Math.max(0, Math.min(Math.max(0, physH - COL_HEAD_H), colY[c]));
    }

    /** 鼠标落在哪一列上 (从上往下找, 后画的优先), 没有返回 -1 */
    private static int colAt(int mx, int my) {
        for (int depth = NCOL - 1; depth >= 0; depth--) {
            int c = columnAtDepth(depth);
            if (auroraWorkspace && !deckVisible(c)) continue;
            if (mx >= colX[c] && mx < colX[c] + colW[c]
                    && my >= colY[c] && my < colY[c] + colViewH(c)) return c;
        }
        return -1;
    }

    /** 设置列里搜索框的位置 (渲染和命中共用) */
    private static int searchBoxY() { return colY[SET_COL] + COL_HEAD_H - colScroll[SET_COL] + 4 * S; }
    private static int searchBoxX() { return colX[SET_COL] + 8 * S; }
    private static int searchBoxW() { return colW[SET_COL] - 16 * S; }
    private static int searchBoxH() { return 16 * S; }

    private static boolean inSearchBox(int mx, int my) {
        return mx >= searchBoxX() && mx < searchBoxX() + searchBoxW()
                && my >= searchBoxY() && my < searchBoxY() + searchBoxH();
    }

    // ---- 滑块几何: 绘制 / 命中 / 拖动 必须用同一份, 否则点一下直接跳满 ----
    //
    // 两个坑:
    //   1. 以前 applySlider 用的是旧单面板的 slX()/slW(), 分栏之后那两个值早就不对了 ——
    //      于是点击位置换算出来的比例不是 0 就是 1, 表现就是"点一下直接拉满"。
    //   2. 轨道宽度不能跟着数值文字宽度走。数值从 "0" 变成 "6000" 时文字变宽,
    //      轨道起点跟着左移, 拖动过程中轨道一直在动 —— 表现就是"调不回来"。
    //      所以数值区宽度钉死成 SLIDER_VALUE_W。
    private static final int SLIDER_VALUE_W = 34;
    private static int dragSlX, dragSlW;

    private static int sliderTrackW(int w) {
        if (auroraWorkspace) return Math.max(S, w - 28 * S);
        return Math.min(70 * S, Math.max(24 * S, w - 80 * S - SLIDER_VALUE_W * S - 4 * S));
    }

    private static int sliderTrackX(int x, int w, int sw) {
        if (auroraWorkspace) return x + 14 * S;
        return x + w - 8 * S - SLIDER_VALUE_W * S - 4 * S - sw;
    }

    /** 重置成默认排布 */
    private static void resetColumnLayout() {
        colInitW = -1;
        colInitH = -1;
        deckLayoutW = deckLayoutH = -1;
        deckPositions.reset(); saveDeckPositions(); deckDragging=false;
        java.util.Arrays.fill(colScroll, 0);
        dragCol = -1;
        resizeCol = -1;
        java.util.Arrays.fill(colWOverride, 0);
        log("column layout reset");
    }

    private static String fmtNum(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return String.valueOf((long) Math.rint(v));
        // 原来是死写 "%.1f" —— 滑块步长细到 0.01 也没用, 因为显示上只能看见 3.1 / 3.2,
        // 中间的值全被四舍五入吃掉了。步长和显示精度必须一起改, 只改一个等于没改。
        String s = String.format(java.util.Locale.ROOT, Math.abs(v) < 1 ? "%.3f" : "%.2f", v);
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') end--;      // 3.10 -> 3.1
        if (end > 1 && s.charAt(end - 1) == '.') end--;         // 3. -> 3
        return s.substring(0, end);
    }

    // =====================================================================
    // 渲染
    // =====================================================================

    private static void renderColumns(int mx, int my) throws Exception {
        // 主题色每帧从参数读一次 —— 改 Hue / 开 Rainbow 立刻生效, 不用重开面板。
        // 放在 layoutColumns 之前: 布局里的一些颜色也走 catColor/ACCENT。
        setThemeFromParams();
        if (auroraWorkspace && !deckFramePrepared) deckBeginFrame(System.nanoTime());
        deckFramePrepared=false;
        if (auroraWorkspace) layoutAuroraWorkspace(); else layoutColumns();
        curMx = mx;
        curMy = my;
        hoverParamMd = null;      // 每帧重记, 鼠标移开就没有了
        hoverParamI = -1;
        float d = auroraWorkspace ? deckDelta : dt();
        for (int depth = 0; depth < NCOL; depth++) {
            int c = columnAtDepth(depth);
            if (auroraWorkspace && !deckVisible(c)) continue;
            int x = colX[c], y = colY[c], w = colW[c], vh = colViewH(c);

            // Dense content surface: the world stays blurred behind it, while
            // labels and controls keep a stable dark contrast field.
            if (auroraWorkspace) deckSurface(x, y, w, vh, true);
            else glassPanel(x, y, x + w, y + vh, COL_RADIUS, 0xE30B1728, true);

            // 标题栏不再用实心矩形盖住顶部 —— 那会把顶边聚光抹掉, 整块玻璃立刻变成塑料。
            // 只用一条分隔线 + 左边一个小色块, 色块按分类取色, 扫一眼就知道是哪列。
            int sepY = y + COL_HEAD_H;
            mDrawRect.invoke(null, x + S, sepY, x + w - S, sepY + Math.max(1, S), auroraWorkspace ? 0x244F576E : 0x365C7596);
            int dot = Math.max(2 * S, 3 * S);
            roundedRect(x + 8 * S, y + (COL_HEAD_H - dot) / 2, x + 8 * S + dot, y + (COL_HEAD_H + dot) / 2,
                    Math.max(1, S), auroraWorkspace ? deckAccent() : catColor(c));
            String title = (c == SET_COL) ? "SETTINGS" : (c == CONFIG_COL) ? "CONFIG" : CAT_NAMES[c];
            text(title, x + 8 * S + dot + 6 * S, textY(y, COL_HEAD_H), TXT_MAIN);
            String cnt = String.valueOf(colCount(c));
            text(cnt, x + w - 8 * S - textW(cnt), textY(y, COL_HEAD_H), TXT_SUB);

            int viewTop = y + COL_HEAD_H;
            int viewBot = y + vh;
            int cy = viewTop - colScroll[c];

            if (c == SET_COL) {
                cy = renderSettingsColumn(c, cy, mx, my, viewTop, viewBot);
            } else if (c == CONFIG_COL) {
                cy = renderConfigColumn(c, cy, mx, my, viewTop, viewBot);
            } else {
              try (GuiClip contentClip=GuiClip.begin(x,viewTop,w,Math.max(0,viewBot-viewTop),physH)) {
                for (int m = 0; m < mods.length; m++) {
                    Mod md = mods[m];
                    if (md.cat != c || !inList(md)) continue;
                    int rowH = COL_ROW_H;
                    if (auroraWorkspace) rowH += deckParamHeight(m);
                    else if (md.tag == null && isExpanded(m)) rowH += md.ps.length * COL_PARAM_H;
                    // 只画完整落在视口内的行 —— 没有 scissor 可用, 画半截会溢出到列外
                    if (contentClip.active() ? cy < viewBot && cy+COL_ROW_H > viewTop : wholeRowVisible(cy, COL_ROW_H, viewTop, viewBot)) {
                        if (auroraWorkspace) drawDeckModule(m, x, cy, w, mx, my, d);
                        else drawModRow(m, x, cy, w, COL_ROW_H, mx, my, d);
                    }
                    // Clip individual controls: a module with 30+ parameters can exceed
                    // the viewport and must still render as it is scrolled through.
                    int bodyTop=Math.max(viewTop,cy+COL_ROW_H),bodyBottom=Math.min(viewBot,cy+rowH);
                    if (md.tag == null && bodyBottom>bodyTop) try(GuiClip bodyClip=GuiClip.begin(x,bodyTop,w,bodyBottom-bodyTop,physH)) {
                        for (int pi = 0; pi < md.ps.length; pi++) {
                            int py = cy + COL_ROW_H + pi * COL_PARAM_H;
                            if (bodyClip.active() ? py>=bodyBottom || py+COL_PARAM_H<=bodyTop : !wholeRowVisible(py,COL_PARAM_H,bodyTop,bodyBottom)) continue;
                            if (auroraWorkspace) drawDeckParam(md, pi, x, py, w, mx, my);
                            else drawParamRow(md, pi, x, py, w, mx, my);
                        }
                    }
                    cy += rowH;
                }
              }
            }

            // 滚动条
            int ms = maxColScroll(c);
            if (ms > 0) {
                int trackH = vh - COL_HEAD_H;
                int barH = Math.max(12 * S, trackH * trackH / Math.max(1, colH[c] - COL_HEAD_H));
                int barY = viewTop + (int) ((long) (trackH - barH) * colScroll[c] / ms);
                if (auroraWorkspace) roundedRect(x + w - 4 * S, barY, x + w - 2 * S, barY + barH, S, 0x886F829F);
                else glassPill(x + w - 4 * S, barY, x + w - S, barY + barH, 0x77FFFFFF, true);
            }
        }

        if (auroraWorkspace) drawAuroraWorkspace(mx, my);

        // 反馈条**已按用户要求删除** ("我不要下面那个反馈条")。
        // 反馈现在画在分享/载入小窗里 (见 drawShareWindow), 那儿才是它该出现的地方。
    }

    /** 画一行功能 (含展开出来的参数行) */
    private static void drawModRow(int m, int x, int y, int w, int rowH, int mx, int my, float d) throws Exception {
        Mod md = mods[m];
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + COL_ROW_H;
        float motion = (float) common(md, "UI Motion");
        hoverAnim[m] = motion == 0 ? (hover ? 1f : 0f) : anim(hoverAnim[m], hover ? 1f : 0f, 14f * motion, d);
        toggleAnim[m] = motion == 0 ? (md.on ? 1f : 0f) : anim(toggleAnim[m], md.on ? 1f : 0f, 16f * motion, d);
        float cardGlow = (float) Math.max(0.0, Math.min(1.0, common(md, "UI Glow") / 100.0));

        int cx1 = x + 2 * S, cx2 = x + w - 2 * S;
        // 行背景用带 alpha 的色, 不用实心色 —— 实心色会把面板的内渐变和顶边聚光盖掉,
        // 那整块玻璃就变成一块塑料板了。开着的是淡青玻璃, 关着的是淡白玻璃。
        float hov = hoverAnim[m];
        roundedRect(cx1, y + S, cx2, y + COL_ROW_H - S, COL_RADIUS,
                md.on ? blend(0x263A8B78, 0x4655A28E, hov) : blend(0x121D2B3D, 0x2E354C63, hov));
        if (hov > 0.03f) {
            mDrawRect.invoke(null, cx1 + 2 * S, y + S, cx2 - 2 * S, y + S + Math.max(1, S),
                    fade(0x4CFFFFFF, hov));
        }
        if (md.on && cardGlow > 0.01f) {
            int halo = fade(ACCENT(), 0.06f + 0.16f * cardGlow * toggleAnim[m]);
            roundedRect(cx1 + S, y, cx2 - S, y + COL_ROW_H, COL_RADIUS + S, halo);
        }
        // 左侧状态条: 关着是灰玻璃, 开着是青的, 中间有过渡动画
        roundedRect(cx1 + 2 * S, y + 3 * S, cx1 + 4 * S, y + COL_ROW_H - 3 * S, S,
                blend(0x5964778D, fade(ACCENT(), 0.86f), toggleAnim[m]));

        int pillH = 14 * S;
        int by = y + (COL_ROW_H - pillH) / 2;

        // 开关 (最右)
        int tw = 34 * S;
        int tx = cx2 - 6 * S - tw;
        glassPill(tx, by, tx + tw, by + pillH, md.on ? 0xC52A806D : 0xB0121B29, md.on);
        String os = md.on ? "ON" : "OFF";
        text(os, tx + tw / 2 - textW(os) / 2, textY(by, pillH), md.on ? TXT_ON : TXT_SUB);

        // 右侧第二个胶囊: 未实现的显示标记, 已实现的显示热键。
        // 宽度按内容算 —— 写死 44px 时 "SERVER" (约 54px) 会溢出到功能名区域。
        int bw = md.tag == null ? 44 * S : Math.max(44 * S, textW(md.tag) + 10 * S);
        int bx = tx - 4 * S - bw;
        boolean binding = bindTarget == m;
        if (md.tag != null) {
            glassPill(bx, by, bx + bw, by + pillH, 0x8A4D2630, true);
            text(md.tag, bx + bw / 2 - textW(md.tag) / 2, textY(by, pillH), 0xFFFF9090);
        } else {
            glassPill(bx, by, bx + bw, by + pillH, binding ? 0xB8443C53 : 0xA0121B29, binding);
            String bs = binding ? "..." : (md.bindKey == 0 ? "-" : keyName(md.bindKey));
            text(bs, bx + bw / 2 - textW(bs) / 2, textY(by, pillH), binding ? ACCENT() : TXT_SUB);
        }

        text(fitText(md.name, bx - (cx1 + 10 * S) - 4 * S), cx1 + 10 * S, textY(y, COL_ROW_H),
                md.on ? TXT_ON : TXT_MAIN);

        // 展开箭头: 不画的话用户不知道这行能点开
        if (md.tag == null && md.ps.length > 0) {
            int ax = cx1 + 10 * S + textW(md.name) + 5 * S;
            int ay = y + COL_ROW_H / 2;
            int pw = Math.max(1, S);
            if (isExpanded(m)) {
                for (int k = 0; k < 4; k++) {
                    mDrawRect.invoke(null, ax + k * pw, ay - pw + k * pw, ax + (8 - k) * pw, ay + k * pw, ACCENT());
                }
            } else {
                for (int k = 0; k < 4; k++) {
                    mDrawRect.invoke(null, ax + k * pw, ay - 3 * pw + k * pw, ax + (k + 1) * pw, ay + 4 * pw - k * pw, TXT_SUB);
                }
            }
        }

    }

    private static void drawParamRow(Mod md, int pi, int x, int y, int w, int mx, int my) throws Exception {
        P pp = md.ps[pi];
        int rx = x + w - 8 * S;
        int pillH = 14 * S;
        int py = y + (COL_PARAM_H - pillH) / 2;
        int ctrlLeft;   // 控件区左边界, 标签只能用左边这一块

        if (pp.isMode()) {
            int mw = 34 * S;
            for (String o : pp.opts) mw = Math.max(mw, textW(o) + 16 * S);
            mw = Math.min(mw, Math.max(34 * S, w - 80 * S));   // 给标签区留够 54*S, 见 checkColumnGeometry
            int px2 = rx - mw;
            boolean hv = mx >= px2 && mx < rx && my >= y && my < y + COL_PARAM_H;
            glassPill(px2, py, rx, py + pillH, hv ? 0xB8405260 : 0xA0121B29, hv);
            text(fitText(pp.cur(), mw - 16 * S), px2 + 6 * S, textY(py, pillH), 0xFFE8F0FA);
            text(">", rx - 10 * S, textY(py, pillH), TXT_SUB);
            ctrlLeft = px2;
        } else if (pp.step >= 1 && pp.min == 0 && pp.max == 1) {
            int tw = 34 * S;
            int px2 = rx - tw;
            glassPill(px2, py, rx, py + pillH, pp.v > 0 ? 0xC52A806D : 0xB0121B29, pp.v > 0);
            String s = pp.v > 0 ? "ON" : "OFF";
            text(s, px2 + tw / 2 - textW(s) / 2, textY(py, pillH), pp.v > 0 ? TXT_ON : TXT_SUB);
            ctrlLeft = px2;
        } else if (pp.max <= pp.min) {
            String d2 = md == mods[F_INVMOVE] && (pi == 0 || pi == 4) ? "Disabled" : "-";
            text(d2, rx - textW(d2), textY(y, COL_PARAM_H), TXT_SUB);
            ctrlLeft = rx - textW(d2);
        } else {
            String vs = fmtNum(pp.v);
            int sw = sliderTrackW(w);
            int sx = sliderTrackX(x, w, sw);
            if (paramEditMd == md && paramEditParam == pi) {
                // 正在编辑: 把数值位置换成输入框 (和搜索框同一套画法, 看起来才是一个东西)
                int bw = Math.max(40 * S, textW(paramEditBuf) + 20 * S);
                int bx = x + w - 8 * S - bw;
                editBoxX0 = bx; editBoxY0 = y + 2 * S; editBoxX1 = x + w - 8 * S; editBoxY1 = y + COL_PARAM_H - 2 * S;
                roundedRect(bx, y + 2 * S, x + w - 8 * S, y + COL_PARAM_H - 2 * S, 3 * S, 0xCC101018);
                roundedRect(bx, y + 2 * S, x + w - 8 * S, y + 2 * S + S, 3 * S, ACCENT_DIM());
                String shown = paramEditBuf + (((System.currentTimeMillis() / 500) % 2 == 0) ? "_" : "");
                text(fitText(shown, bw - 8 * S), bx + 4 * S, textY(y, COL_PARAM_H), 0xFFFFFFFF);
            } else {
                text(vs, x + w - 8 * S - textW(vs), textY(y, COL_PARAM_H), TXT_ON);
            }
            int ty2 = py + 5 * S;
            // 凹槽: 外圈暗、内圈更暗, 看起来是"陷进去"的
            roundedRect(sx, ty2, sx + sw, ty2 + 4 * S, 2 * S, 0x33FFFFFF);
            roundedRect(sx, ty2 + S, sx + sw, ty2 + 4 * S - S, 2 * S, 0x66000000);
            float f = (float) ((pp.v - pp.min) / (pp.max - pp.min));
            int fx = sx + (int) (f * sw);
            int fillX = Math.max(sx + 2 * S, fx);
            // 记下鼠标停在哪个数值滑块上 —— 滚轮微调要用 (拖动只能粗调, 见 sliderStep)
            if (mx >= sx - 8 * S && mx <= x + w - 8 * S && my >= y && my < y + COL_PARAM_H) {
                hoverParamMd = md;
                hoverParamI = pi;
            }
            roundedRect(sx, ty2 + S, fillX, ty2 + 4 * S - S, 2 * S, ACCENT_DIM());
            gradientV(sx, ty2 + S, fillX, ty2 + 2 * S + S / 2, 0x55FFFFFF, 0x00FFFFFF);
            // 手柄: 玻璃珠。中间白、边缘淡, 比一个纯白方块像样得多
            roundedRect(fx - 3 * S, y + 2 * S, fx + 3 * S, y + COL_PARAM_H - 2 * S, 2 * S, 0x99FFFFFF);
            roundedRect(fx - 2 * S, y + 3 * S, fx + 2 * S, y + COL_PARAM_H - 3 * S, 2 * S, 0xFFFFFFFF);
            ctrlLeft = sx;
        }
        text(fitText(pp.n, ctrlLeft - (x + 14 * S) - 4 * S), x + 14 * S, textY(y, COL_PARAM_H), TXT_SUB);
    }

    /** 设置列: 搜索框 + 模板 + 重置布局。返回下一个 y。 */
    /**
     * CONFIG 列 —— 和 Combat/Settings 同级的一整列。
     *
     * <p>用户要求原话: "我要的是和 misc 之类的竖列同级的, 名字为 config 的列表, 不在这个框里"。
     *
     * <p>和旧版(设置列的尾巴 + 浮动小窗)的区别:
     * <ul>
     *   <li>列表**全部铺开** —— 旧版一次只画一条, 要按 ^ v 翻, 所以"一打开看不见配置"</li>
     *   <li>分享/粘贴是**列里的按钮**, 不再是浮窗里的两行小按钮</li>
     *   <li>反馈显示在列内 (第 3 行), 不再退回面板底部画一条黑横幅</li>
     * </ul>
     *
     * <p>行 y 全部走 cfgRow* 那组函数, clickAt 用同一组 —— 不允许各算一份。
     */
    private static int renderConfigColumn(int c, int cy, int mx, int my,
                                          int viewTop, int viewBot) throws Exception {
        int x = colX[c], w = colW[c];
        int rx1 = x + 8 * S, rx2 = x + w - 8 * S;
        int rowY0 = cy;   // 行表基准 = 内容顶部

        // ---- 行 0: 命名输入框 ----
        {
            int ry = rowY0 + cfgRowName() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot) {
                glassPill(rx1, ry + S, rx2, ry + COL_ROW_H - S,
                        cfgNameFocused ? 0xB82A2A36 : 0x9E16161E, cfgNameFocused);
                boolean empty = cfgName.length() == 0;
                text(fitText(empty ? "Name for this config..." : cfgName, rx2 - rx1 - 10 * S),
                        rx1 + 5 * S, textY(ry, COL_ROW_H), empty ? TXT_SUB : TXT_MAIN);
                if (cfgNameFocused) {
                    int ccx = Math.min(rx2 - 4 * S, rx1 + 5 * S + textW(cfgName));
                    mDrawRect.invoke(null, ccx + S, ry + 3 * S, ccx + 2 * S, ry + COL_ROW_H - 3 * S, ACCENT());
                }
            }
        }

        // ---- 行 1: Save ----
        {
            int ry = rowY0 + cfgRowSave() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot) {
                boolean hv = inRow(rx1, rx2, ry, mx, my);
                roundedRect(rx1, ry + S, rx2, ry + COL_ROW_H - S, COL_RADIUS, auroraWorkspace ? fade(deckAccent(), hv ? 0.75f : 0.55f) : hv ? 0xE600C9A7 : 0xCC00C9A7);
                String lbl = "Save Config";
                text(lbl, rx1 + (rx2 - rx1) / 2 - textW(lbl) / 2, textY(ry, COL_ROW_H), 0xFF06231C);
            }
        }

        // ---- 行 2: Share / Paste 并排 ----
        {
            int ry = rowY0 + cfgRowShare() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot) {
                int gap = 6 * S;
                int bw = (rx2 - rx1 - gap) / 2;
                int sy1 = ry + S, sy2 = ry + COL_ROW_H - S;

                // Share: 一步到位 —— 上传、拿码、直接进剪贴板
                boolean shv = mx >= rx1 && mx < rx1 + bw && my >= ry && my < ry + COL_ROW_H;
                roundedRect(rx1, sy1, rx1 + bw, sy2, COL_RADIUS, auroraWorkspace ? (shv ? 0xFF36465F : 0xFF293549) : shv ? 0xFFFFD166 : 0xE6E0A030);
                String s1 = "Share";
                text(s1, rx1 + bw / 2 - textW(s1) / 2, textY(ry, COL_ROW_H), auroraWorkspace ? DECK_TEXT : 0xFF1A1206);

                // Paste: 把剪贴板里的分享码填进导入位置
                int px1 = rx1 + bw + gap;
                boolean phv = mx >= px1 && mx < rx2 && my >= ry && my < ry + COL_ROW_H;
                roundedRect(px1, sy1, rx2, sy2, COL_RADIUS, phv ? 0x2EFFFFFF : 0x1AFFFFFF);
                String s2 = "Paste";
                text(s2, px1 + bw / 2 - textW(s2) / 2, textY(ry, COL_ROW_H), phv ? 0xFFFFFFFF : TXT_MAIN);
            }
        }

        // ---- 行 3: 反馈。**所有提示都在这儿**, 不再有面板底部的黑条 ----
        {
            int ry = rowY0 + cfgRowStatus() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot) {
                String msg = liveToast();
                if (msg.length() > 0) {
                    text(fitText(msg, rx2 - rx1 - 4 * S), rx1, textY(ry, COL_ROW_H), TXT_WARN);
                } else if (cfgBusy != null) {
                    text(fitText(cfgBusy, rx2 - rx1 - 4 * S), rx1, textY(ry, COL_ROW_H), TXT_HINT);
                }
            }
        }

        // ---- 行 4..: 配置列表, **全部铺开** ----
        if (cfgNames.length == 0) {
            int ry = rowY0 + cfgRowList0() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot)
                text(fitText("No Configs", rx2 - rx1 - 12 * S), rx1 + 6 * S,
                        textY(ry, COL_ROW_H), TXT_HINT);
        }
        for (int i = 0; i < cfgNames.length; i++) {
            int ry = rowY0 + (cfgRowList0() + i) * COL_ROW_H;
            if (ry < viewTop || ry + COL_ROW_H > viewBot) continue;
            boolean hv = inRow(rx1, rx2, ry, mx, my);
            if (hv) roundedRect(rx1, ry + S, rx2, ry + COL_ROW_H - S, COL_RADIUS, 0x1AFFFFFF);
            // 右边那块 "Copy" = 复制成分享串。列右边界必须是 rx2 (不是 x+w-2*S) ——
            // 点击那边也走同一个 cfgCopyLeft(), 差几个像素就点不中。
            int cLeft = cfgCopyLeft(c, rx2);
            boolean chv = hv && mx >= cLeft - 3 * S;
            text(fitText("· " + cfgNames[i], cLeft - 3 * S - (rx1 + 6 * S)), rx1 + 6 * S,
                    textY(ry, COL_ROW_H), TXT_MAIN);
            text("Copy", cLeft, textY(ry, COL_ROW_H), chv ? 0xFFFFFFFF : ACCENT());
        }

        // ---- 末行: 操作提示 ----
        {
            int ry = rowY0 + cfgRowHint() * COL_ROW_H;
            if (ry >= viewTop && ry + COL_ROW_H <= viewBot)
                text(fitText("Click to load, right-click to delete", rx2 - rx1 - 12 * S),
                        rx1 + 6 * S, textY(ry, COL_ROW_H), TXT_HINT);
        }
        return rowY0 + cfgRowTotal() * COL_ROW_H;
    }

    /** 行内命中 —— 渲染和点击共用, 免得两边矩形各写一份 */
    private static boolean inRow(int rx1, int rx2, int ry, int mx, int my) {
        return mx >= rx1 && mx < rx2 && my >= ry && my < ry + COL_ROW_H;
    }

    /** 列表里那行 "Copy" 的左边缘。渲染和点击**必须**取同一个值 */
    private static int cfgCopyLeft(int c, int rx2) {
        return rx2 - 6 * S - textW("Copy");
    }

    private static int renderSettingsColumn(int c, int cy, int mx, int my, int viewTop, int viewBot) throws Exception {
        int x = colX[c], w = colW[c];

        int sy = searchBoxY();
        if (sy >= viewTop && sy + searchBoxH() <= viewBot) {
            glassPill(searchBoxX(), sy, searchBoxX() + searchBoxW(), sy + searchBoxH(),
                    searchFocused ? 0xB82A4250 : 0x9E101A2A, searchFocused);
            boolean empty = searchQuery.length() == 0;
            text(fitText(empty ? "Search..." : searchQuery, searchBoxW() - 10 * S), searchBoxX() + 5 * S, textY(sy, searchBoxH()),
                    empty ? TXT_SUB : TXT_MAIN);
            if (searchFocused) {
                int cx = Math.min(searchBoxX() + searchBoxW() - 4 * S, searchBoxX() + 5 * S + textW(searchQuery));
                mDrawRect.invoke(null, cx + S, sy + 3 * S, cx + 2 * S, sy + searchBoxH() - 3 * S, ACCENT());
            }
        }
        cy = sy + searchBoxH() + 6 * S;

        int rx1 = x + 8 * S, rx2 = x + w - 8 * S;
        for (int i = 0; i < AC_NAMES.length; i++) {
            if (cy >= viewTop && cy + COL_ROW_H <= viewBot) {
                boolean sel = antiCheat == i || (antiCheat == -1 && i == AC_NAMES.length - 1);
                boolean hv = mx >= rx1 && mx < rx2 && my >= cy && my < cy + COL_ROW_H;
                if (sel) roundedRect(rx1, cy + S, rx2, cy + COL_ROW_H - S, COL_RADIUS, auroraWorkspace ? fade(deckAccent(), 0.35f) : 0xB52A806D);
                else if (hv) roundedRect(rx1, cy + S, rx2, cy + COL_ROW_H - S, COL_RADIUS, 0x26354D65);
                text(AC_NAMES[i], rx1 + 6 * S, textY(cy, COL_ROW_H), sel ? TXT_ON : TXT_MAIN);
            }
            cy += COL_ROW_H;
        }

        // ===== 配置相关的东西**全部搬去 CONFIG 列** =====
        //
        // 这段原来是: 命名输入框 + Save + CFG_ROWS + 账号配置列表 + 提示行,
        // 全挤在设置列底部。用户的要求是"和 misc 之类的竖列同级的, 名字为 config 的列表"
        // —— 所以整块搬到 renderConfigColumn 里去了, 这里只留一个 Targets 和 Reset Layout。
        for (int i = 0; i < CFG_ROWS.length; i++) {
            if (cy >= viewTop && cy + COL_ROW_H <= viewBot) {
                boolean hv = mx >= rx1 && mx < rx2 && my >= cy && my < cy + COL_ROW_H;
                if (hv) roundedRect(rx1, cy + S, rx2, cy + COL_ROW_H - S, COL_RADIUS, 0x1AFFFFFF);
                String lbl = "Targets".equals(CFG_ROWS[i])
                        ? CFG_ROWS[i] + ": " + TARGET_OPTS[curSharedTarget()]
                        : CFG_ROWS[i];
                text(fitText(lbl, rx2 - rx1 - 12 * S), rx1 + 6 * S, textY(cy, COL_ROW_H), TXT_MAIN);
            }
            cy += COL_ROW_H;
        }

        // 热键操作说明。原来是纯隐藏手势 —— 用户只能自己试出来, 而"清空绑定"根本没入口。
        // 抽成字段是为了让 HintWidth 探针能量到真串: 设置列只有 150px 宽,
        // 第一版写的 "Backspace/Delete clears, Esc cancels" 实测 298px, 直接顶出列外。
        for (int i = 0; i < HOTKEY_HINTS.length; i++) {
            if (cy >= viewTop && cy + COL_ROW_H <= viewBot) {
                // fitText 兜底: colNeededW 已经按这些串把列撑到装得下, 但屏幕太窄时列会被
                // 整体压缩, 真字体下可能就差几个像素 —— 宁可截断也不能画到隔壁列上去。
                text(fitText(HOTKEY_HINTS[i], rx2 - rx1 - 12 * S),
                        rx1 + 6 * S, textY(cy, COL_ROW_H), TXT_HINT);
            }
            cy += COL_ROW_H;
        }
        return cy;
    }

    /**
     * 共享的目标类型档位。和 Mod 里的 Target 参数是同一份选项 —— 这里只做"一次改全部"。
     */
    static final String[] TARGET_OPTS = {"Players", "Monsters", "Animals",
            "Players+Monsters", "Players+Animals", "All"};

    /**
     * 当前的目标档位: 取第一个带 Target 参数的功能的当前值。
     *
     * 不另存一份状态 —— 那会和功能自己的参数各说各话 (用户改完 AimBot 的 Target,
     * 这一栏还显示旧值)。直接读真实来源, 显示和点击用的永远是同一个数。
     */
    private static int curSharedTarget() {
        for (Mod m : mods) {
            if (m.ps == null) continue;
            for (P q : m.ps) {
                if (q.opts != null && "Target".equals(q.n) && q.opts.length == TARGET_OPTS.length) return q.idx();
            }
        }
        return TARGET_OPTS.length - 1;
    }

    /**
     * 把目标档位写进**所有**带 Target 参数的功能。
     *
     * 判据是"参数名叫 Target", 不是硬编码功能下标 —— 以后加的功能只要参数也叫这个名字就
     * 自动跟着走, 不用回来改这里。反过来这是个约定: 谁都不许把别的意思的参数叫 Target。
     */
    private static void applySharedTarget(int idx) {
        int want = Math.max(0, Math.min(TARGET_OPTS.length - 1, idx));
        int n = 0;
        for (Mod m : mods) {
            if (m.ps == null) continue;
            for (P q : m.ps) {
                if (q.opts != null && "Target".equals(q.n) && q.opts.length == TARGET_OPTS.length) {
                    q.v = want;
                    n++;
                }
            }
        }
        log("target -> " + TARGET_OPTS[want] + " (应用到 " + n + " 个功能)");
    }

    /** 设置列底部的热键操作说明。宽度由 HintWidth 探针守着, 别随手加长 */
    static final String[] HOTKEY_HINTS = {
            "Hotkey: click pill",
            "Cancel: Esc",
            "Clear: right-click",
            "Clear: Backspace",
    };

    /** 模式胶囊的悬停判定 (渲染和点击共用, 免得两边各写一份然后对不上) */
    private static boolean isExpandedHover(int x, int w, int rowY) {
        return curMx >= x && curMx < x + w && curMy >= rowY && curMy < rowY + ROW_PARAM;
    }

    /** 两个 ARGB 按 t(0..1) 线性插值 */
    private static int blend(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int aa = (a >>> 24) & 255, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = (b >>> 24) & 255, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return ((int) (aa + (ba - aa) * t) << 24) | ((int) (ar + (br - ar) * t) << 16)
                | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static String keyName(int code) {
        try {
            if (mKeyName != null) {
                String n = (String) mKeyName.invoke(null, code);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Throwable ignored) {
        }
        return "Key " + code;
    }
}
