package com.lite.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 版本差异表 —— 1.12.2 与 1.8.9 之间**所有**不一样的地方都只写在这里。
 *
 * <h3>为什么不"移植"</h3>
 * 把工程用到的 208 个 SRG 句柄拿去和 1.8.9 的 MCP 映射逐一比对，结果是
 * <b>174 个两版本完全相同（84%）</b>，另有 8 个只是 MCP 显示名改了
 * （{@code worldObj→world}、{@code fontRendererObj→fontRenderer}、{@code expand→grow} …）
 * —— 运行时只认 SRG 名，这些名字是一致的，所以实际可复用 182 个。
 * 钩子层更是完全一致：{@code func_181560_a (FJ)V}、{@code func_78479_a (FI)V}、
 * {@code func_78757_d ()F} 在 1.8.9 里和 1.12.2 一字不差。
 *
 * 所以不需要维护两份 Hook.java。真正要处理的只有下面这 <b>22 个</b>名字，
 * 加上三处比"改名"更深的差异（见 heldItem / swingHand / hasOffhand）。
 *
 * <h3>别名为什么加在 mopt/mpriv/fopt 里</h3>
 * 那三个方法是整个工程**唯一**的反射句柄入口。翻译写在那里，22 个调用点一行都不用改，
 * 也不会漏 —— 散在各处迟早会有人新加一处忘了翻。{@code AliasCheck} 探针守着这张表
 * 和两个版本的 MCP 映射不漂移。
 *
 * <h3>怎么拿到这些名字的</h3>
 * 不是猜的：1.12.2 的 SRG 名 → 1.12.2 的 MCP 名 → 1.8.9 的同名 MCP 名 → 1.8.9 的 SRG 名，
 * 用两边官方的 {@code srg-mcp.srg} / {@code fields.csv} / {@code methods.csv} 机械推导，
 * 名字在版本间被改过（{@code getHeldItemMainhand→getHeldItem}）的那几个再手工对照归属类确认。
 * 映射文件在 {@code versions/1.8.9/}。
 */
final class Ver {

    /** MC 1.8.9。1.9 引进 EnumHand/副手，1.11 把 mainInventory 换成 NonNullList，这些是分界线。 */
    static final int V189 = 189;
    /** MC 1.12.2，即工程原本唯一支持的版本。 */
    static final int V1122 = 1122;
    /**
     * MC 1.20.1（Forge）。和上面两个不是"新一点"的关系，是**另一个体系**：
     * 运行时用 SRG(m_/f_)，但类名是 Mojang 官方名（Forge 做了 remap）；
     * 而且 1.17 换掉了 GlStateManager，绘制层是另一套 API。
     */
    static final int V1201 = 1201;

    private static volatile int current = 0;
    private static volatile ClassLoader runtimeLoader;

    /** Bind before Hook/T initialization: Forge's game module is invisible to the agent loader. */
    static synchronized void bindRuntime(Class<?>[] loaded) throws Exception {
        Class<?> minecraft = null;
        for (Class<?> c : loaded) {
            if ("net.minecraft.client.Minecraft".equals(c.getName())) {
                if (minecraft != null && minecraft.getClassLoader() != c.getClassLoader())
                    throw new IllegalStateException("发现多个 Minecraft 加载器，无法安全选择目标");
                minecraft = c;
            }
        }
        if (minecraft == null)
            throw new IllegalStateException("未找到已加载的 Forge Minecraft 主类。原版/Fabric 暂不支持；Forge 请进入主菜单后注入。");
        runtimeLoader = minecraft.getClassLoader();
        current = 0;
        if (detect() == 0)
            throw new IllegalStateException("无法识别游戏映射版本，仅支持 1.8.9、1.12.2 和 1.20.1 Forge 兼容层");
        if (is1201()) {
            String version = runtimeVersion();
            if (!"1.20.1".equals(version))
                throw new IllegalStateException("现代 Forge 版本不匹配: " + version + "，要求 1.20.1");
            minecraft.getDeclaredMethod("m_91087_"); // Verify SRG namespace, not only a class name.
        }
    }

    static ClassLoader runtimeLoader() { return runtimeLoader; }

    private static String runtimeVersion() {
        try (java.io.InputStream in = runtimeLoader.getResourceAsStream("version.json")) {
            if (in == null) return "unknown (missing version.json)";
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[1024]; int n;
            while ((n = in.read(b)) != -1) {
                if (out.size() + n > 16384) return "unknown (oversized version.json)";
                out.write(b, 0, n);
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            return m.find() ? m.group(1) : "unknown (missing version id)";
        } catch (Exception e) { return "unknown (" + e.getClass().getSimpleName() + ")"; }
    }

    private Ver() {
    }

    /**
     * 检测目标版本。
     *
     * 判据是**某个只有该版本才有的类在不在**，不是解析版本号字符串：
     * {@code net.minecraft.util.text.TextComponentString} 是 1.9 从
     * {@code net.minecraft.util.ChatComponentText} 改名来的，两个类名各自只在一个版本里存在。
     * 拿类存在性当判据是零风险的 —— 反正后面本来就要用其中之一，检测顺带完成。
     *
     * 注意不能用版本号/进程参数去猜：注入器看到的是 launcher 的启动参数，
     * 里面写的是 profile 名而不是 MC 版本，Forge/OptiFine/整合包各写各的，猜错就是静默失效。
     * 在目标 JVM 内部看实际加载的类，是唯一不会错的判据。
     */
    static synchronized int detect() {
        if (current != 0) return current;
        // 1.20.1 的判据：**官方映射里 1.17+ 才有的类**。
        // GuiGraphics 是 1.20 引进的（取代直接画在 GL 上），1.12.2/1.8.9 都没有。
        // 拿它当判据是零风险的 —— 后面绘制层本来就要用它。
        if (probe("net.minecraft.client.gui.GuiGraphics") != null) {
            current = V1201;
            return current;
        }
        boolean hasNewText = probe("net.minecraft.util.text.TextComponentString") != null;
        boolean hasOldText = probe("net.minecraft.util.ChatComponentText") != null;
        if (hasNewText) current = V1122;
        else if (hasOldText) current = V189;
        // Unknown is not 1.12.2. Do not cache a guessed version before the game loader is bound.
        return current;
    }

    static boolean is189() {
        return detect() == V189;
    }

    static boolean is1201() {
        return detect() == V1201;
    }

    /** 报给注入器/日志用。注入器读这个值显示"已检测到 X"。 */
    static String name() {
        if (is1201()) return "1.20.1";
        return is189() ? "1.8.9" : detect() == V1122 ? "1.12.2" : "unknown";
    }

    private static Class<?> probe(String n) {
        try { return Class.forName(n, false, runtimeLoader == null ? Ver.class.getClassLoader() : runtimeLoader); }
        catch (Throwable t) { return null; }
    }

    // ------------------------------------------------------------------
    // 类名表
    //
    // 这一层是补上的，而且补得很晚 —— 代价是第一次在真 1.8.9 上注入时直接
    // ClassNotFoundException: net.minecraft.util.math.AxisAlignedBB，整个 init 挂掉。
    //
    // 原因: 1.9 把 net.minecraft.util 拆出了 math 和 text 两个包。所有 util.math.* 和
    // util.text.* 的类在 1.8.9 上都在 net.minecraft.util 底下，而且有几个连名字都换了
    // (Vec3d -> Vec3, RayTraceResult -> MovingObjectPosition, ITextComponent -> IChatComponent)。
    //
    // 之前的门禁只查 func_/field_ 句柄，**一个类名都没查** —— 于是 84% 句柄同名这个漂亮数字
    // 掩盖了"类名几乎全错"这件事。门禁现在也查类名了，见 AliasCheck 的第二段。
    // ------------------------------------------------------------------
    private static final Map<String, String> CLS189 = new HashMap<String, String>();
    /** 1.8.9 上根本不存在的类。返回 null，依赖它的功能自动降级。 */
    private static final java.util.Set<String> CLS_GONE = new java.util.HashSet<String>();
    // ------------------------------------------------------------------
    // 1.20.1 的类名表。**每一条都对着官方映射核过**
    // (versions/1.20.1/mcp/client-1.20.1-20230612.114412-mappings.txt 的类行)。
    //
    // 和 1.8.9 是同一个坑、更狠的一次: 1.20.1 把 net.minecraft.entity.* 整个搬进了
    // net.minecraft.world.entity.*, client.gui 也拆过。而且这个表**之前根本不存在** ——
    // cls()/classAlias() 只分支 is189(), 1.20.1 走的是恒等, 也就是所有 1.12.2 类名
    // 会被原样拿去 Class.forName, 必然失败。
    //
    // 后果比 1.8.9 那次更重: initFields() 是总初始化, 里面任何一句抛异常,
    // **后面所有句柄都不初始化**。1.20.1 上第 1300 行的 net.minecraft.entity.Entity
    // 就会抛, 压根走不到后面。
    // ------------------------------------------------------------------
    private static final Map<String, String> CLS1201 = new HashMap<String, String>();
    /** 1.20.1 上真的不存在的类。返回 null，依赖它的功能自动降级。 */
    private static final java.util.Set<String> CLS_GONE_1201 = new java.util.HashSet<String>();

    static {
        // --- 包拆分: util.math.* -> world.phys.* / core.* ---
        CLS1201.put("net.minecraft.util.math.AxisAlignedBB", "net.minecraft.world.phys.AABB");
        CLS1201.put("net.minecraft.util.math.Vec3d", "net.minecraft.world.phys.Vec3");
        CLS1201.put("net.minecraft.util.math.Vec3i", "net.minecraft.core.Vec3i");
        CLS1201.put("net.minecraft.util.math.BlockPos", "net.minecraft.core.BlockPos");
        CLS1201.put("net.minecraft.util.math.RayTraceResult", "net.minecraft.world.phys.HitResult");
        CLS1201.put("net.minecraft.util.math.MathHelper", "net.minecraft.util.Mth");
        // --- 实体: net.minecraft.entity.* -> net.minecraft.world.entity.* ---
        CLS1201.put("net.minecraft.entity.Entity", "net.minecraft.world.entity.Entity");
        CLS1201.put("net.minecraft.entity.EntityLivingBase", "net.minecraft.world.entity.LivingEntity");
        CLS1201.put("net.minecraft.entity.player.EntityPlayer", "net.minecraft.world.entity.player.Player");
        CLS1201.put("net.minecraft.client.entity.EntityPlayerSP", "net.minecraft.client.player.LocalPlayer");
        CLS1201.put("net.minecraft.entity.passive.EntityAnimal", "net.minecraft.world.entity.animal.Animal");
        CLS1201.put("net.minecraft.entity.monster.EntityMob", "net.minecraft.world.entity.monster.Monster");
        CLS1201.put("net.minecraft.entity.player.PlayerCapabilities", "net.minecraft.world.entity.player.Abilities");
        CLS1201.put("net.minecraft.entity.player.InventoryPlayer", "net.minecraft.world.entity.player.Inventory");
        // --- 世界 ---
        CLS1201.put("net.minecraft.world.World", "net.minecraft.world.level.Level");
        CLS1201.put("net.minecraft.client.multiplayer.WorldClient", "net.minecraft.client.multiplayer.ClientLevel");
        // --- 物品 / 方块 ---
        CLS1201.put("net.minecraft.item.ItemStack", "net.minecraft.world.item.ItemStack");
        CLS1201.put("net.minecraft.item.ItemBlock", "net.minecraft.world.item.BlockItem");
        CLS1201.put("net.minecraft.block.Block", "net.minecraft.world.level.block.Block");
        CLS1201.put("net.minecraft.util.EnumHand", "net.minecraft.world.InteractionHand");
        // --- 客户端 ---
        // Minecraft 同名, 但字段几乎全变 (写进来是为了让"同名"这件事是显式的, 不是漏登记)
        CLS1201.put("net.minecraft.client.Minecraft", "net.minecraft.client.Minecraft");
        CLS1201.put("net.minecraft.client.settings.GameSettings", "net.minecraft.client.Options");
        CLS1201.put("net.minecraft.client.gui.FontRenderer", "net.minecraft.client.gui.Font");
        CLS1201.put("net.minecraft.client.gui.GuiScreen", "net.minecraft.client.gui.screens.Screen");
        CLS1201.put("net.minecraft.client.gui.GuiButton", "net.minecraft.client.gui.components.Button");
        CLS1201.put("net.minecraft.client.gui.inventory.GuiContainer",
                    "net.minecraft.client.gui.screens.inventory.AbstractContainerScreen");
        // Gui 同名, 但 fill/gradient 搬到了 GuiGraphics
        CLS1201.put("net.minecraft.client.gui.Gui", "net.minecraft.client.gui.Gui");
        CLS1201.put("net.minecraft.util.Timer", "net.minecraft.client.Timer");
        CLS1201.put("net.minecraft.inventory.Container", "net.minecraft.world.inventory.AbstractContainerMenu");
        CLS1201.put("net.minecraft.client.multiplayer.PlayerControllerMP",
                    "net.minecraft.client.multiplayer.MultiPlayerGameMode");
        CLS1201.put("net.minecraft.client.renderer.EntityRenderer", "net.minecraft.client.renderer.GameRenderer");
        CLS1201.put("net.minecraft.client.renderer.entity.RenderManager",
                    "net.minecraft.client.renderer.entity.EntityRenderDispatcher");
        CLS1201.put("net.minecraft.client.renderer.RenderGlobal", "net.minecraft.client.renderer.LevelRenderer");
        // --- 聊天 ---
        CLS1201.put("net.minecraft.util.text.ITextComponent", "net.minecraft.network.chat.Component");

        // --- 1.20.1 上真的不存在, 解析成 null, 功能降级 ---
        // GlStateManager: 1.17 被删除。矩阵栈操作搬到了 PoseStack (每帧对象) +
        //   com.mojang.blaze3d.systems.RenderSystem (部分状态)。这不是改名, 是结构性的:
        //   pushMatrix/popMatrix/ortho/loadIdentity 在 1.20 **没有静态等价物**,
        //   必须拿到当前帧的 PoseStack 才画得出来。自绘 GUI 要整体重写。
        // Render-only block animation is registered for legacy clients, not the modern overlay.
        CLS_GONE_1201.add("net.minecraft.client.renderer.ItemRenderer");
        CLS_GONE_1201.add("net.minecraft.client.renderer.entity.RenderPlayer");
        CLS_GONE_1201.add("net.minecraft.client.renderer.GlStateManager");
        // org.lwjgl.input.* 是 **LWJGL 2**。1.20 用 LWJGL 3, 这两个类整类消失,
        //   按键查询要走 com.mojang.blaze3d.platform.InputConstants。
        CLS_GONE_1201.add("org.lwjgl.input.Keyboard");
        CLS_GONE_1201.add("org.lwjgl.input.Mouse");
        // TextComponent 1.19.1 就被删了。纯文本节点现在是 contents.LiteralContents,
        //   内容抽象是 Component(接口) / MutableComponent。
        //   **1.12.2 那种"给文本类插构造器"做 NameProtect/ChatSuffix 在 1.20 无处可插** ——
        //   1.19+ 的正当落点是 ChatDecorator, 而且要注意聊天签名链。
        CLS_GONE_1201.add("net.minecraft.util.text.TextComponentString");
    }

    static {
        // --- 包拆分: util.math.* -> util.* ---
        CLS189.put("net.minecraft.util.math.AxisAlignedBB", "net.minecraft.util.AxisAlignedBB");
        CLS189.put("net.minecraft.util.math.BlockPos", "net.minecraft.util.BlockPos");
        CLS189.put("net.minecraft.util.math.Vec3i", "net.minecraft.util.Vec3i");
        // 这几个连类名都换了
        CLS189.put("net.minecraft.util.math.Vec3d", "net.minecraft.util.Vec3");
        CLS189.put("net.minecraft.util.math.RayTraceResult", "net.minecraft.util.MovingObjectPosition");
        // --- 包拆分: util.text.* -> util.* ---
        CLS189.put("net.minecraft.util.text.ITextComponent", "net.minecraft.util.IChatComponent");
        CLS189.put("net.minecraft.util.text.TextComponentString", "net.minecraft.util.ChatComponentText");
        // --- 数据包类在 1.8.9 的 MCP 里还是带编号的旧命名 ---
        CLS189.put("net.minecraft.network.play.client.CPacketPlayer",
                   "net.minecraft.network.play.client.C03PacketPlayer");
        CLS189.put("net.minecraft.network.play.client.CPacketPlayerTryUseItem",
                   "net.minecraft.network.play.client.C08PacketPlayerBlockPlacement");

        // --- 1.8.9 上不存在 (1.9 才引进)，解析成 null，功能降级 ---
        // ModernEntityMarks is called only by onFrame1201; legacy marking reads RayTraceResult.entityHit.
        CLS_GONE.add("net.minecraft.world.phys.EntityHitResult");
        CLS_GONE.add("net.minecraft.util.EnumHand");              // 副手/主手枚举
        CLS_GONE.add("net.minecraft.potion.PotionUtils");          // 1.8.9 reads ItemPotion.getEffects; Hook binds only in the other version branch
        CLS_GONE.add("net.minecraft.inventory.ClickType");        // 1.8.9 的 windowClick 用 int mode
        CLS_GONE.add("net.minecraft.client.gui.GuiGraphics");     // 1.20.1 HUD 绘制上下文
        // --- 只有 1.20.1 才有的类, 被 1.20.1 专用代码路径引用 ---
        // 这几个在 1.8.9 上同样不存在, 登记在这里是为了让 AliasCheck 的 1.8.9 类名检查
        // 知道它们是"另一个版本的名字", 而不是"打错了"。
        // (1.20.1 自己那份在 CLS1201 / CLS_GONE_1201。)
        CLS_GONE.add("net.minecraft.network.chat.contents.LiteralContents");   // 1.19.1+
        CLS_GONE.add("net.minecraft.world.entity.projectile.ProjectileUtil");  // 1.17+ 包结构
    }

    /**
     * 按版本解析类名。**所有** Class.forName 都必须走这里，包括本来用 copt 的那些 ——
     * 类名和句柄一样是版本相关的，只是在真跑起来之前没人会发现。
     *
     * 解析不到返回 null（和 copt 语义一致），依赖它的功能降级；硬性依赖用 Hook.req()。
     */
    static Class<?> cls(String mcpName) {
        if (mcpName == null) return null;
        if (is189()) {
            if (CLS_GONE.contains(mcpName)) return null;
            String t = CLS189.get(mcpName);
            if (t != null) mcpName = t;
        } else if (is1201()) {
            if (CLS_GONE_1201.contains(mcpName)) return null;
            String t = CLS1201.get(mcpName);
            if (t != null) mcpName = t;
        }
        return probe(mcpName);
    }

    /** 类名表条目数，给探针用。 */
    static int classAliasCount() {
        if (is1201()) return CLS1201.size() + CLS_GONE_1201.size();
        return CLS189.size() + CLS_GONE.size();
    }

    /** 这个类名在当前版本上有没有对应物 —— 探针用它区分"改了名"和"真没有"。 */
    static String classAlias(String mcpName) {
        if (is1201()) {
            if (CLS_GONE_1201.contains(mcpName)) return null;
            String t = CLS1201.get(mcpName);
            return t == null ? mcpName : t;
        }
        if (!is189()) return mcpName;
        if (CLS_GONE.contains(mcpName)) return null;
        String t = CLS189.get(mcpName);
        return t == null ? mcpName : t;
    }

    /** 当前版本上被明确判定不存在的类名（供探针比对）。 */
    static java.util.Set<String> goneClasses() {
        return is1201() ? CLS_GONE_1201 : CLS_GONE;
    }

    /**
     * 1.20.1 类名表里所有**目标值**（Mojang 名），供门禁豁免用。
     *
     * 为什么需要: AliasCheck 会把源码里所有 {@code "net/minecraft/..."} 字面量当成
     * "工程用到的类名" 拿去 1.8.9 的类清单里查。而 1.20.1 专用分支里写的就是 1.20.1
     * 的名字（比如 world.entity.Entity），它们在 1.8.9 上**本来就不存在** ——
     * 拿它们去查 1.8.9 是无意义的，应该按"这是另一个版本的名字"豁免。
     */
    static java.util.Set<String> classAlias1201Targets() {
        return new java.util.HashSet<String>(CLS1201.values());
    }

    /**
     * windowClick 的第 4 个参数在两个版本上**不是一种东西**：
     *   1.12.2: {@code func_187098_a(int,int,int,ClickType,EntityPlayer)} —— 传 {@code ClickType.QUICK_MOVE}
     *   1.8.9 : {@code func_78753_a   (int,int,int,int,EntityPlayer)}     —— 传 {@code 1}（mode 1 = QUICK_MOVE）
     *
     * 这是"类不存在"引出的连锁差异：ClickType 没了，签名就跟着换。
     * 光登记 CLS_GONE 不够 —— 调用点必须知道要传个 int 进去。
     */
    static boolean windowClickTakesModeInt() {
        return is189();
    }

    /**
     * Timer 那个字段在两个版本上**不是同一个量**，不只是改名。
     *
     * 1.12.2 {@code Timer.tickLength}      = 每 tick 多少**毫秒**，原版 50（= 20 TPS）；
     * 1.8.9  {@code Timer.ticksPerSecond} = 每秒多少 **tick**，原版 20。
     * 互为倒数：ticksPerSecond = 1000 / tickLength。
     *
     * 名字那半截由别名表翻（field_194149_e -> field_74282_a），单位在这里分派。
     * 之前只按 1.12.2 写：1.8.9 上字段名解析不到，句柄恒为 null，doTimer 第一行就
     * 静默 return —— 界面上开关和 0.5~3.0 档位都在，点下去什么都不发生。
     */
    static boolean timerFieldIsTicksPerSecond() {
        return is189();
    }

    // ------------------------------------------------------------------
    // 聊天文本类名：1.9 把 ChatComponentText 改名成 TextComponentString 并换了包。
    // NameProtect 和 ChatSuffix 都是插进这个类的构造器的字节码钩子，所以 LiteAgent
    // 的转换规则也必须跟着换 —— 那是唯一一个"类名本身"不一样的地方。
    // ------------------------------------------------------------------
    static String chatTextClass() {
        return is189() ? "net/minecraft/util/ChatComponentText"
                       : "net/minecraft/util/text/TextComponentString";
    }

    /**
     * doesGuiPauseGame：1.8.9 = func_73868_f，1.12.2 = func_73853_d，
     * 1.20.1 = m_7043_ (Screen.isPauseScreen)。LiteGui 要生成这个名字。
     *
     * 这里原来在 1.8.9 之外**硬编码返回字面量** "func_73853_d"，所以 1.20.1 上拿到的是错的
     * —— 而 A1201 里早就有一条 func_73853_d -> m_7043_。改成走 alias() 之后：
     * 1.8.9 -> func_73868_f，1.12.2 -> func_73853_d，1.20.1 -> m_7043_，
     * 且和表只有一个真相来源 (同样的道理见下面 heldItemMethod 的注释)。
     */
    static String guiPauseGameMethod() {
        return alias("func_73853_d");
    }

    /**
     * 玩家主手物品的方法名。
     *
     * 1.12.2: {@code getHeldItemMainhand()} = {@code func_184614_ca} ()LItemStack;
     * 1.8.9 : {@code getHeldItem()}         = {@code func_70694_bm}   ()LItemStack;
     *
     * 两个都是**无参**（带 EnumHand 的是 getHeldItem(EnumHand)，工程没用它），所以翻译
     * 就是走别名表。之所以还单列一个方法，是为了让读的人一眼看到这条差异 —— 而且
     * **不能让它在表外另写一份字符串**：那等于两个真相来源，改一处忘一处。
     */
    static String heldItemMethod() {
        return alias("func_184614_ca");
    }

    /**
     * 挥手。
     *
     * 1.12.2: {@code swingArm(EnumHand)} = func_184609_a；
     * 1.8.9 : {@code swingItem()}        = func_71038_i  （无参，1.8.9 根本没有 EnumHand）。
     *
     * 名字走别名表；**要不要带参数**是另一回事，见 swingTakesArg()。调用点是
     * {@code mSwing.invoke(player, enumMainHand)}，而 enumMainHand 在 1.8.9 上恒为 null，
     * 6 处调用全写在 {@code enumMainHand != null} 的守卫里 —— 光改名的话挥手会在 1.8.9 上
     * **静默全部失效**。所以调用点统一走 Hook.swingHand(player)。
     */
    static String swingMethod() {
        return alias("func_184609_a");
    }

    /** 挥手方法带不带参数（1.9 起带 EnumHand）。 */
    static boolean swingTakesArg() {
        return !is189();
    }

    /**
     * 鱼钩的施钓者。
     *
     * 1.12.2 是方法 {@code getAngler()} = func_190619_l；
     * 1.8.9 是**字段** {@code angler} = field_146042_b。
     *
     * 成员种类变了，所以别名表里那一条是 func_ -> field_ 的（表里唯一一条跨种类）。
     * 语义上仍然是"别名表说了 1.8.9 叫什么"，只是取的时候要用 Field 而不是 Method，
     * 由 Hook 里的 anglerOf() 按 anglerIsField() 分派。
     */
    static String anglerMethod() {
        return Ver.alias("func_190619_l");
    }

    /** angler 在 1.8.9 上是字段，取法要用 getDeclaredField。 */
    static boolean anglerIsField() {
        return is189();
    }

    /**
     * 1.8.9 没有副手，也就没有 EnumHand。
     *
     * 依赖副手的 mod 在 1.8.9 上是**不可能实现**的（不是"暂时没做"）：
     * AutoTotem 的 Offhand 模式、NoSlow 的 Offhand 模式。按约定这些功能直接不注册，
     * 不出现在界面里 —— 而不是留一个点下去什么都不发生的开关。
     */
    static boolean hasOffhand() {
        return !is189();
    }

    // ------------------------------------------------------------------
    // 别名表
    //
    // 键 = 1.12.2 的 SRG 名（也就是源码里现在写的那个），值 = 1.8.9 的 SRG 名。
    // 只有在 1.8.9 里名字**变了**的才需要出现在这里；174 个同名的一句都不用写。
    // ------------------------------------------------------------------
    private static final Map<String, String> A189 = new HashMap<String, String>();

    static {
        // --- 字段 ---
        // Entity.ridingEntity：1.12.2 field_184239_as，1.8.9 field_70154_o
        A189.put("field_184239_as", "field_70154_o");
        // EntityPlayer.activeItemStack -> 1.8.9 的 itemInUse
        A189.put("field_184627_bm", "field_71074_e");
        // Entity.fire
        A189.put("field_190534_ay", "field_70151_c");
        // EntityLivingBase.moveForward（走路方向，静默转身那条链要用）
        A189.put("field_191988_bg", "field_70701_bs");
        // Timer.tickLength -> 1.8.9 的 ticksPerSecond。
        // **单位不是一回事**，所以除了这张表还要一个单位分派，见 timerFieldIsTicksPerSecond()。
        A189.put("field_194149_e", "field_74282_a");
        A189.put("field_194147_b", "field_74281_c"); // Timer.renderPartialTicks
        A189.put("func_184144_a", "func_72945_a");   // World collision boxes

        // --- 方法 ---
        A189.put("func_184191_r", "func_142014_c");    // isOnSameTeam
        A189.put("func_184218_aH", "func_70115_ae");   // isRiding
        A189.put("func_184587_cr", "func_71039_bw");   // isHandActive -> isUsingItem
        A189.put("func_184605_cv", "func_71052_bv");   // getItemInUseCount
        A189.put("func_187098_a", "func_78753_a");     // windowClick
        A189.put("func_189697_a", "func_181563_a");    // drawSelectionBoundingBox -> 1.8.9 只有 int 颜色版
        A189.put("func_187099_a", "func_178890_a");    // processRightClickBlock -> onPlayerRightClick
        A189.put("func_73853_d", "func_73868_f");      // doesGuiPauseGame
        A189.put("func_190619_l", "field_146042_b");   // getAngler -> 1.8.9 是字段 angler

        // 这两个名字在 1.8.9 上能用, 但**不能直接喂给 mopt** —— 1.12.2 要带 EnumHand 参数,
        // 1.8.9 无参。所以表里照样登记(门禁要验目标名真的存在), 但 Hook 里用 swingMethod()/
        // heldItemMethod() + swingTakesArg() 分派着取。登记在这里也是为了防止有人在表外
        // 另写一份字符串。
        A189.put("func_184609_a", "func_71038_i");     // swingArm -> swingItem
        A189.put("func_184614_ca", "func_70694_bm");   // getHeldItemMainhand -> getHeldItem

        // --- 1.8.9 真的没有对应物（不写进表里，保持原名 -> 解析失败 -> 功能自动降级） ---
        // func_184825_o  getCooledAttackStrength   攻击冷却，1.9 才有的机制。
        //                                          1.8.9 上取 1.0f（永远算"已冷却"）是**正确**的降级，不是缺失
        // func_187441_d  glLineWidth               1.8.9 的 GlStateManager 没有这个方法。
        //                                          调用点统一走 Hook.setLineWidth —— 它对 1.8.9 回落到
        //                                          org.lwjgl.opengl.GL11.glLineWidth（本来就是同一层 GL 调用）
        // field_70093_af                          已知死句柄，1.12.2 上本来就取不到
        //
        // 注: func_190916_E/func_190920_E (getCount/setCount) **不在**表里。1.8.9 只有字段
        //     stackSize (field_77994_a)，而别名表只翻译名字、不换成员种类 —— 把方法名改成
        //     字段名会让 mopt 去 getMethod 一个不存在的名字。这两个句柄在工程里只声明未使用
        //     （mGetStackSize/mSetStackSize 全文件无调用点），所以留在 1.8.9 上为 null 即可。
        //     将来真要用，得另写按版本分派的访问器，不能塞进别名表。
    }

    // ------------------------------------------------------------------
    // 1.20.1 别名表
    //
    // 这张表**不是我手写的** —— 是 tools/Derive1201Table.java 从官方映射文件
    // (versions/1.20.1/mcp/…-mappings-merged.txt + client-…-mappings.txt) 机械推出来的。
    // 推导链: 1.12.2 SRG -> Mojang 语义 -> 1.20.1 obf -> 1.20.1 SRG。
    // 每条都过了三道自检: ① 没有两条 1.12.2 句柄映到同一个 1.20.1 句柄
    //                    (例外: 声明完全相同的两条 = 同一个成员的两套 SRG 名, 例如
    //                     func_78753_a / func_187098_a 都是 windowClick, 允许同目标)
    //                    ② 字段映字段、方法映方法 (这条抓到过一个真 bug, 见下)
    //                    ③ 方法必须按**描述符**查到 SRG, 不许退回 obf 短名
    //
    // 【bug 一】最初我用 "类#obf名" 当 key 存映射，结果 1.20 里
    // obf 名 'aG' 既是字段 f_19857_ (yRot) 又是方法 m_20098_ (getYRot())，
    // 后写的覆盖先写的 —— 于是 player/level/screen/font/yRot/xRot/onGround 等 15 个
    // 字段全被映成了方法。fopt 去 getField 必然 null，症状是"功能开着但什么都不发生"。
    // 现在按 "类#obf名#F/M" 分开存。
    //
    // 【bug 二】同一类里描述符不同的**重载**会复用同一个 obf 短名 (Minecraft 有 44 个方法
    // 都叫 'a')。按短名索引同样是谁后写谁生效 —— 所以表里有 22 条的值是错的
    // (例如 func_147108_a setScreen 曾写成 m_263196_, 那是另一个方法的名字)。
    // 症状和 bug 一 一样: 静默。现在方法按 "类#obf名#描述符" 索引 (自检3 守着),
    // 这 22 条已按重推结果改对。
    //
    // 未命中的 52 个不在这张表里，见 DEGRADE_1201 —— 那些是 1.20 上机制真的变了或没了的。
    // ------------------------------------------------------------------
    private static final Map<String, String> A1201 = new HashMap<String, String>();

    static {
        // Minecraft
        A1201.put("field_71439_g", "f_91074_");     // Minecraft.player
        A1201.put("field_71441_e", "f_91073_");     // Minecraft.level
        A1201.put("field_71462_r", "f_91080_");     // Minecraft.screen
        A1201.put("field_71443_c", "f_85359_");     // Window.width   (1.20 搬到了 Window 上)
        A1201.put("field_71440_d", "f_85360_");     // Window.height  (同上)
        A1201.put("field_71428_T", "f_90991_");     // Minecraft.timer
        A1201.put("field_71467_ac", "f_91011_");    // Minecraft.rightClickDelay
        A1201.put("field_71466_p", "f_91062_");     // Minecraft.font
        A1201.put("func_71410_x", "m_91087_");      // Minecraft.getInstance()
        A1201.put("func_147108_a", "m_91152_");     // Minecraft.setScreen(Screen)
        A1201.put("func_147121_ag", "m_91277_");    // Minecraft.startUseItem()  (原 rightClickMouse)

        // Entity —— 位置
        A1201.put("field_70165_t", "f_19825_");     // Entity.position (Vec3)
        A1201.put("field_70169_q", "f_19854_");     // Entity.xo
        A1201.put("field_70167_r", "f_19855_");     // Entity.yo
        A1201.put("field_70166_s", "f_19856_");     // Entity.zo
        A1201.put("field_70159_w", "f_19827_");     // Entity.deltaMovement (Vec3)

        // Entity —— 状态 / 朝向
        A1201.put("field_70122_E", "f_19861_");     // Entity.onGround
        A1201.put("field_70177_z", "f_19857_");     // Entity.yRot
        A1201.put("field_70125_A", "f_19858_");     // Entity.xRot
        A1201.put("field_70126_B", "f_19859_");     // Entity.yRotO
        A1201.put("field_70127_C", "f_19860_");     // Entity.xRotO
        A1201.put("field_70143_R", "f_19789_");     // Entity.fallDistance
        A1201.put("field_70173_aa", "f_19797_");    // Entity.tickCount
        A1201.put("field_70138_W", "f_19793_");     // Entity.maxUpStep

        // Entity —— 方法
        A1201.put("func_70047_e", "m_20192_");      // getEyeHeight()
        A1201.put("func_70068_e", "m_20238_");      // distanceToSqr(Vec3)
        A1201.put("func_70089_S", "m_6084_");       // isAlive()
        A1201.put("func_70067_L", "m_6087_");       // legacy picking predicate -> isPickable()
        A1201.put("func_70111_Y", "m_6143_");       // getCollisionBorderSize() -> getPickRadius()
        A1201.put("func_175149_v", "m_5833_");      // isSpectator() (now declared on Entity)
        A1201.put("func_174813_aQ", "m_20191_");    // getBoundingBox()
        A1201.put("func_174826_a", "m_20011_");     // setBoundingBox(AABB)
        A1201.put("func_70095_a", "m_20260_");      // setShiftKeyDown(boolean)
        A1201.put("func_70031_b", "m_6858_");       // setSprinting(boolean)
        A1201.put("func_70090_H", "m_20069_");      // isInWater()
        A1201.put("func_82150_aj", "m_20145_");     // isInvisible()
        A1201.put("func_184218_aH", "m_20159_");    // isPassenger()

        // LivingEntity
        A1201.put("func_110143_aJ", "m_21223_");    // getHealth()
        A1201.put("func_184614_ca", "m_21205_");    // getMainHandItem()
        A1201.put("func_184587_cr", "m_6117_");     // isUsingItem()
        A1201.put("func_184609_a", "m_6674_");      // swingArm(InteractionHand)
        A1201.put("func_70685_l", "m_142582_");     // hasLineOfSight(Entity)
        A1201.put("func_70664_aZ", "m_6135_");      // jumpFromGround()
        A1201.put("func_184605_cv", "m_21212_");    // getUseItemRemainingTicks()
        A1201.put("func_110148_a", "m_21051_");     // getAttribute(Attribute)
        A1201.put("field_70761_aq", "f_20883_");    // yBodyRot
        A1201.put("field_70733_aJ", "f_20921_");    // attackAnim (legacy swingProgress)
        A1201.put("field_70759_as", "f_20885_");    // yHeadRot
        A1201.put("field_70760_ar", "f_20884_");    // yBodyRotO
        A1201.put("field_70758_at", "f_20886_");    // yHeadRotO
        A1201.put("field_191988_bg", "f_20902_");   // zza   (原 moveForward)
        A1201.put("field_70702_br", "f_20900_");    // xxa   (原 moveStrafing)
        A1201.put("field_184627_bm", "f_20935_");   // useItem

        // Player
        A1201.put("func_71059_n", "m_5706_");       // attack(Entity)
        A1201.put("func_71024_bL", "m_36324_");     // getFoodData()
        A1201.put("field_71075_bZ", "f_36077_");    // abilities
        A1201.put("func_184825_o", "m_36403_");     // getAttackStrengthScale(float)

        // Abilities
        A1201.put("field_75100_b", "f_35935_");     // flying
        A1201.put("field_75101_c", "f_35936_");     // mayfly
        A1201.put("field_75098_d", "f_35937_");     // instabuild
        A1201.put("func_75092_a", "m_35943_");      // setFlyingSpeed(float)

        // FoodData
        A1201.put("func_75116_a", "m_38702_");      // getFoodLevel()

        // Level
        A1201.put("func_180495_p", "m_8055_");      // getBlockState(BlockPos)
        A1201.put("func_72912_H", "m_6106_");       // getLevelData()

        // AABB / 命中结果
        A1201.put("func_72314_b", "m_82377_");      // inflate(double,double,double)
        A1201.put("func_72317_d", "m_82386_");      // move(double,double,double)
        A1201.put("field_72308_g", "f_82437_");     // EntityHitResult.entity
        A1201.put("func_178782_a", "m_82425_");     // BlockHitResult.getBlockPos()
        A1201.put("field_178784_b", "f_82410_");    // BlockHitResult.direction
        A1201.put("field_72307_f", "f_82445_");     // HitResult.location
        A1201.put("field_72450_a", "f_82479_");     // Vec3.x
        A1201.put("field_72448_b", "f_82480_");     // Vec3.y
        A1201.put("field_72449_c", "f_82481_");     // Vec3.z

        // ItemStack / Item
        A1201.put("func_190916_E", "m_41613_");     // getCount()
        A1201.put("func_190920_e", "m_41764_");     // setCount(int)
        A1201.put("func_77973_b", "m_41720_");      // getItem()
        A1201.put("func_150893_a", "m_8102_");      // getDestroySpeed(ItemStack,BlockState)

        // 绘制 (1.20 的 fill 在 GuiGraphics 上，不在 Gui 上)
        A1201.put("func_73734_a", "m_280509_");     // GuiGraphics.fill(int,int,int,int,int)
        A1201.put("func_73733_a", "m_280024_");     // GuiGraphics.fillGradient(...)
        A1201.put("func_78256_a", "m_92895_");      // Font.width(String)

        // Screen
        A1201.put("field_146294_l", "f_96543_");    // Screen.width
        A1201.put("field_146295_m", "f_96544_");    // Screen.height
        A1201.put("func_73853_d", "m_7043_");       // isPauseScreen()

        // MultiPlayerGameMode / 容器
        A1201.put("func_78753_a", "m_171799_");     // handleInventoryMouseClick(...)
        A1201.put("func_78757_d", "m_105286_");     // getPickRange()
        A1201.put("func_75139_a", "m_38853_");      // AbstractContainerMenu.getSlot(int)
        A1201.put("func_75211_c", "m_7993_");       // Slot.getItem()

        // 世界数据 / 杂项
        A1201.put("func_76084_b", "m_5565_");       // PrimaryLevelData.setRaining(boolean)
        A1201.put("func_76069_a", "m_5557_");       // PrimaryLevelData.setThundering(boolean)
        A1201.put("func_71004_bE", "m_7583_");      // LocalPlayer.respawn()
        A1201.put("field_151451_c", "f_92106_");    // Options.renderDistance
        A1201.put("func_174877_v", "m_58899_");     // BlockEntity.getBlockPos()

        // ==============================================================
        // 本轮补齐 —— Hook.java 用到、原来两张表都没交代的 97 个句柄里**命中的 66 条**。
        // 每条都是"先提假设 -> 用 merged + client 两份官方映射核对 -> 命中才写进来"。
        // 剩下 31 条核对不过(类没了 / 参数表变了 / 两个候选分不开), 在 DEGRADE_1201 里。
        // 值的**形态**变了的有几处, 注释里点名了(OptionInstance / Component)。
        // ==============================================================

        // Minecraft: 1.20 改了名的字段/方法
        A1201.put("field_71474_y", "f_91066_");     // gameSettings -> options
        A1201.put("field_71476_x", "f_91077_");     // objectMouseOver -> hitResult
        A1201.put("field_71442_b", "f_91072_");     // playerController -> gameMode
        A1201.put("field_71470_ab", "f_91021_");    // debugFPS -> fps
        A1201.put("field_71429_W", "f_91078_");     // leftClickCounter -> missTime
        A1201.put("field_175616_W", "f_90994_");    // renderManager -> entityRenderDispatcher
        A1201.put("field_175622_Z", "f_91075_");    // renderViewEntity -> cameraEntity
        A1201.put("func_147114_u", "m_91403_");     // getNetHandler -> getConnection()
        A1201.put("func_147116_af", "m_202354_");   // clickMouse 的左键一半 -> startAttack()

        // Entity
        A1201.put("field_184239_as", "f_19824_");   // ridingEntity -> vehicle
        A1201.put("field_190534_ay", "f_19831_");   // fire -> remainingFireTicks
        A1201.put("field_70170_p", "f_19853_");     // worldObj -> level
        A1201.put("func_110124_au", "m_20148_");    // getUniqueID() -> getUUID()
        A1201.put("func_145782_y", "m_19879_");     // getEntityId() -> getId()
        A1201.put("func_70005_c_", "m_7755_");      // getName() —— 返回值 1.20 是 Component, 不是 String
        A1201.put("func_70051_ag", "m_20142_");     // isSprinting()
        A1201.put("func_70080_a", "m_7678_");       // setPositionAndRotation(...) -> moveTo(...)
        A1201.put("func_70093_af", "m_6144_");      // isSneaking() -> isShiftKeyDown()
        A1201.put("func_96124_cp", "m_5647_");      // getTeam()

        // LivingEntity
        A1201.put("field_70703_bu", "f_20899_");    // isJumping -> jumping
        A1201.put("field_70737_aN", "f_20916_");    // hurtTime
        A1201.put("func_110138_aP", "m_21233_");    // getMaxHealth()
        A1201.put("func_70617_f_", "m_6147_");      // isOnLadder() -> onClimbable()
        A1201.put("func_70644_a", "m_21023_");      // isPotionActive() -> hasEffect(MobEffect)
        A1201.put("func_70660_b", "m_21124_");      // getActivePotionEffect() -> getEffect(MobEffect)
        A1201.put("func_70690_d", "m_7292_");       // addPotionEffect() -> addEffect(MobEffectInstance)

        // Player / LocalPlayer / Inventory
        A1201.put("field_71071_by", "f_36093_");    // EntityPlayer.inventory
        A1201.put("field_71070_bA", "f_36096_");    // openContainer -> containerMenu
        A1201.put("field_71174_a", "f_108617_");    // EntityPlayerSP.sendQueue -> LocalPlayer.connection
        A1201.put("field_70462_a", "f_35974_");     // mainInventory -> items (NonNullList)
        A1201.put("field_70461_c", "f_35977_");     // currentItem -> selected

        // MultiPlayerGameMode (PlayerControllerMP)
        A1201.put("field_78770_f", "f_105193_");    // curBlockDamageMP -> destroyProgress
        A1201.put("field_78781_i", "f_105195_");    // blockHitDelay -> destroyDelay
        A1201.put("field_78778_j", "f_105196_");    // isHittingBlock -> isDestroying
        A1201.put("func_180512_c", "m_105283_");    // onPlayerDamageBlock() -> continueDestroyBlock()
        A1201.put("func_78764_a", "m_105223_");     // attackEntity() -> attack(Player,Entity)
        // windowClick: 1.8.9 叫 func_78753_a, 1.12.2 叫 func_187098_a —— 同一个成员的两个名字,
        // 两条**必须**映到同一个目标, 否则走另一条名字的调用点在 1.20 上解析不到。
        // (Derive1201Table 的自检1 对"声明完全相同的两条"放行, 就是这个道理。)
        A1201.put("func_187098_a", "m_171799_");    // windowClick -> handleInventoryMouseClick(...)

        // Timer / Options
        A1201.put("field_194149_e", "f_92521_");    // tickLength -> msPerTick (每 tick 毫秒, 原版 50)
        A1201.put("field_74333_Y", "f_92071_");     // gammaSetting -> Options.gamma    (OptionInstance)
        A1201.put("field_74334_X", "f_92068_");     // fovSetting   -> Options.fov     (OptionInstance)
        A1201.put("field_74336_f", "f_92080_");     // viewBobbing  -> Options.bobView (OptionInstance)
        A1201.put("field_74341_c", "f_92053_");     // mouseSensitivity -> Options.sensitivity (OptionInstance)

        // Level (雨/thunder 的衰减量声明在 Level 上, ClientLevel 继承)
        A1201.put("field_73003_n", "f_46437_");     // prevRainingStrength -> oRainLevel
        A1201.put("field_73004_o", "f_46438_");     // rainingStrength -> rainLevel
        A1201.put("func_72820_D", "m_46468_");      // getWorldTime() -> getDayTime()
        // addFreshEntity 在 1.20 声明在**接口 LevelWriter** 上 (Level/ClientLevel 都不重写),
        // 所以宿主是接口; 运行时在实例类上 getMethod 会沿接口找到这个 SRG 名。
        A1201.put("func_72838_d", "m_7967_");       // spawnEntityInWorld() -> LevelWriter.addFreshEntity()

        // AABB 六个边界
        A1201.put("field_72340_a", "f_82288_");     // minX
        A1201.put("field_72338_b", "f_82289_");     // minY
        A1201.put("field_72339_c", "f_82290_");     // minZ
        A1201.put("field_72336_d", "f_82291_");     // maxX
        A1201.put("field_72337_e", "f_82292_");     // maxY
        A1201.put("field_72334_f", "f_82293_");     // maxZ

        // BlockState / Block / Component
        A1201.put("func_177230_c", "m_60734_");     // getBlock() —— 声明在父类 BlockBehaviour$BlockStateBase
        A1201.put("func_149739_a", "m_7705_");      // getUnlocalizedName() -> getDescriptionId()
        A1201.put("func_150260_c", "getString");    // getUnformattedText() -> Component.getString()
                                                    // 这条的 1.20 名字**不是** m_ 开头: 接口 default
                                                    // 方法没被混淆, 运行时名字就叫 getString

        // ItemStack / ItemEntity
        A1201.put("func_77948_v", "m_41793_");      // isItemEnchanted() -> isEnchanted()
        A1201.put("func_82833_r", "m_41786_");      // getDisplayName() -> getHoverName() (也是 Component)
        A1201.put("func_92059_d", "m_32055_");      // EntityItem.getItem()

        // 网络: ClientPacketListener / PlayerInfo
        A1201.put("func_147297_a", "m_104955_");    // addToSendQueue() -> ClientPacketListener.send(Packet)
        A1201.put("func_175102_a", "m_104949_");    // getPlayerInfo(UUID)
        A1201.put("func_175106_d", "m_105142_");    // getPlayerInfoMap() -> getOnlinePlayers() (Map -> Collection)
        A1201.put("func_178845_a", "m_105312_");    // NetworkPlayerInfo.getGameProfile() -> PlayerInfo.getProfile()
        A1201.put("func_178853_c", "m_105330_");    // getResponseTime() -> getLatency()

        // GUI: 屏幕 / 按钮 / 容器
        A1201.put("field_146292_n", "f_96540_");    // GuiScreen.buttonList -> Screen.children
        A1201.put("field_146126_j", "f_93614_");    // GuiButton.displayString -> AbstractWidget.message (也是 Component)
        A1201.put("field_75151_b", "f_38839_");     // Container.inventorySlots -> AbstractContainerMenu.slots
    }

    /**
     * 1.20.1 上**机制本身变了或没了**的句柄。查不到就是 null，依赖它的功能自动降级。
     *
     * <p>这张表是给人和探针看的：它把"为什么这个功能在 1.20 上不工作"变成可读的一句话，
     * 而不是一个 null 反射结果。{@code Derive1201Table} 的未命中清单直接对应这里。
     *
     * <p><b>注意：这里登记的不都是"永远做不到"</b>，有几条是"要改写法"——
     * 比如位置三件套，1.12.2 是三个 double 字段，1.20 合并成了一个 Vec3，
     * 得读 {@code position.x}，不能靠改名字解决。
     *
     * <p>判据 (整张表统一, 和 tools/Derive1201Table.java 的声明规则一致)：
     * <ul>
     *   <li>进 A1201：1.20.1 上存在语义同一个成员。名字可以改、宿主类可以改名
     *       (GameSettings→Options、GuiScreen→Screen、World→Level)，字段的包装类型
     *       也可以变 (int→OptionInstance) —— 因为**反射取得到**。</li>
     *   <li>进本表：成员或宿主类不存在；搬到**另一个对象**上
     *       (Screen.setClipboard→KeyboardHandler、EntityPlayerSP.sendChatMessage→ClientPacketListener)；
     *       方法的参数表在 1.20 上完全变了 (要改写法)；两个同型候选分不开。</li>
     * </ul>
     *
     * <p>每条都是拿 versions/1.20.1/mcp/ 里两份官方映射核对后的结论, 不是猜的。
     * 核对用的是静态映射 —— 本机没有 1.20.1 客户端, **运行时行为没有验证过**。
     */
    private static final Map<String, String> DEGRADE_1201 = new HashMap<String, String>();

    static {
        DEGRADE_1201.put("field_74313_G", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_184592_cb", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_187101_a", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_77975_n", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_78766_c", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_78769_a", "Native AutoBlock belongs to legacy KA; KA remains disabled in the 1.20.1 overlay");
        DEGRADE_1201.put("func_146206_l", "Legacy InvMove GuiTextField focus check is used only by the legacy player/input path; 1.20.1 uses native Screen focus and has no InvMove port");
        DEGRADE_1201.put("func_147104_D", "Legacy AutoReconnect ServerData binding; AutoReconnect is not ported to the 1.20.1 overlay");
        DEGRADE_1201.put("func_70445_o", "Legacy inventory cursor stack; inventory automation is not ported to the modern overlay");
        DEGRADE_1201.put("func_77986_q", "Legacy numeric enchantment NBT list; modern item data uses a different binding");
        DEGRADE_1201.put("func_74745_c", "Legacy NBTTagList size; modern ListTag API is separate");
        DEGRADE_1201.put("func_150305_b", "Legacy NBTTagList compound entry; modern ListTag API is separate");
        DEGRADE_1201.put("func_74765_d", "Legacy NBTTagCompound short getter; modern CompoundTag API is separate");
        DEGRADE_1201.put("field_77879_b", "Legacy ItemArmor defense; AutoArmor is not ported to the modern overlay");
        DEGRADE_1201.put("func_150931_i", "Legacy ItemSword attack damage; AutoTool is not ported to the modern overlay");
        DEGRADE_1201.put("field_77865_bY", "Legacy ItemTool attack damage; AutoTool is not ported to the modern overlay");
        DEGRADE_1201.put("func_146103_bH", "Legacy KA entity profile verification is disabled on the 1.20.1 overlay");
        DEGRADE_1201.put("func_174806_f", "Legacy Scaffold native look-vector binding is guarded by !Ver.is1201(); Scaffold is not ported to the modern overlay");
        DEGRADE_1201.put("func_72318_a", "Legacy KA box containment is guarded by !Ver.is1201(); modern AABB has a different API");
        DEGRADE_1201.put("func_72327_a", "Legacy KA segment interception is guarded by !Ver.is1201(); modern clip returns Optional<Vec3>");
        DEGRADE_1201.put("field_74311_E", "Legacy Scaffold key input is disabled on the 1.20.1 overlay");
        DEGRADE_1201.put("field_74351_w", "Legacy forward KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_74368_y", "Legacy back KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_74370_x", "Legacy left KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_74366_z", "Legacy right KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_74314_A", "Legacy jump KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_151444_V", "Legacy sprint KeyBinding is represented by a KeyMapping/InputConstants.Key in 1.20");
        DEGRADE_1201.put("field_71158_b", "Legacy EntityPlayerSP MovementInput hook is replaced by the 1.20 LocalPlayer input pipeline");
        DEGRADE_1201.put("field_78902_a", "Legacy MovementInput moveStrafe field is absent from the 1.20 input pipeline");
        DEGRADE_1201.put("field_78900_b", "Legacy MovementInput moveForward field is absent from the 1.20 input pipeline");
        DEGRADE_1201.put("field_78899_d", "Legacy Scaffold Edge input hook is disabled on 1.20.1; modern Input has a different lifecycle");
        DEGRADE_1201.put("field_194147_b", "1.12.2 Timer render fraction; only read by the legacy Scaffold forecast");
        DEGRADE_1201.put("func_184144_a", "1.12.2 collision list for legacy Edge; the Entity movement hook is not installed on 1.20.1");
        DEGRADE_1201.put("func_72316_a", "Legacy AABB X collision clipping; 1.20 uses voxel shapes and disables this Edge path");
        DEGRADE_1201.put("func_72323_b", "Legacy AABB Y collision clipping; 1.20 uses voxel shapes and disables this Edge path");
        DEGRADE_1201.put("func_72322_c", "Legacy AABB Z collision clipping; 1.20 uses voxel shapes and disables this Edge path");
        DEGRADE_1201.put("field_75152_c", "Legacy container window id is handled through AbstractContainerMenu and modern click packets in 1.20");
        DEGRADE_1201.put("func_147447_a", "Legacy Scaffold world ray picking is disabled on the 1.20.1 overlay; BlockGetter.clip requires a ClipContext instead of Vec3,Vec3,boolean,boolean,boolean");
        DEGRADE_1201.put("field_74335_Z", "Legacy integer guiScale is an OptionInstance in 1.20; optional 1.8/1.12 visual widgets are disabled on the 1.20 overlay");
        DEGRADE_1201.put("field_77994_a", "Legacy ItemStack count field is absent in 1.20; use ItemStack#getCount");
        DEGRADE_1201.put("func_70658_aO", "Legacy armor widget reflection is disabled on the 1.20 overlay");
        DEGRADE_1201.put("func_77952_i", "Legacy ItemStack damage widget reflection is disabled on the 1.20 overlay");
        DEGRADE_1201.put("func_77958_k", "Legacy ItemStack max-damage widget reflection is disabled on the 1.20 overlay");
        DEGRADE_1201.put("func_77984_f", "Legacy ItemStack damageable widget reflection is disabled on the 1.20 overlay");
        DEGRADE_1201.put("func_187421_b", "GlStateManager texture filtering is configured by RenderSystem in 1.20; legacy renderer is unavailable");
        DEGRADE_1201.put("func_151463_i", "Legacy integer key codes changed to InputConstants.Key");
        DEGRADE_1201.put("func_74510_a", "Legacy integer key input is disabled on the 1.20.1 overlay");
        DEGRADE_1201.put("field_70163_u", "1.20 把 positionX/Y/Z 合并成 Vec3 position：改读 position.y");
        DEGRADE_1201.put("field_70161_v", "同上：改读 position.z");
        DEGRADE_1201.put("field_70128_L", "removed 字段没了，改成 isRemoved() 方法");
        DEGRADE_1201.put("field_72996_f", "Level 的实体列表改成 getEntities()，签名不同");
        DEGRADE_1201.put("field_147482_g", "blockEntities 字段没了（1.20 用 getBlockEntities()）");
        DEGRADE_1201.put("func_175623_d", "isEmptyBlock 没了，改 getBlockState(pos).isAir()");
        DEGRADE_1201.put("func_72877_b", "setDayTime 移到了 LevelData 上");
        DEGRADE_1201.put("func_177958_n", "BlockPos.getX() 实例方法没了，1.20 只有静态 getX(long)");
        DEGRADE_1201.put("func_177956_o", "同上：BlockPos.getY()");
        DEGRADE_1201.put("func_177952_p", "同上：BlockPos.getZ()");
        DEGRADE_1201.put("func_175063_a", "Font.drawShadow 没了，1.20 走 GuiGraphics.drawString");
        DEGRADE_1201.put("func_146275_d", "Screen.setClipboard 没了，1.20 在 Minecraft 上");
        DEGRADE_1201.put("func_146277_j", "Screen.getClipboard 没了，1.20 在 Minecraft 上");
        DEGRADE_1201.put("func_175279_e", "isKeyComboCtrlV 没了，1.20 快捷键体系重写");
        DEGRADE_1201.put("func_189697_a", "renderHitbox 签名全变（原来就是 1.8.9 的补丁点）");
        DEGRADE_1201.put("func_181563_a", "同上：AABB 上的 renderHitbox");
        DEGRADE_1201.put("func_111128_a", "setBaseValue 在 AttributeInstance 上，不是 Object");
        DEGRADE_1201.put("func_190619_l", "FishingHook.getOwner 变了");
        DEGRADE_1201.put("field_72313_a", "HitResult.type 字段没了，改 getType()");
        DEGRADE_1201.put("field_78730_l", "RenderManager 的 cameraX/Y/Z 搬到了 GameRenderer");
        DEGRADE_1201.put("field_78731_m", "同上");
        DEGRADE_1201.put("field_78728_n", "同上");
        DEGRADE_1201.put("field_78725_b", "Legacy RenderManager renderPosX moved into the 1.20 camera/render dispatcher pipeline");
        DEGRADE_1201.put("field_78726_c", "Legacy RenderManager renderPosY moved into the 1.20 camera/render dispatcher pipeline");
        DEGRADE_1201.put("field_78723_d", "Legacy RenderManager renderPosZ moved into the 1.20 camera/render dispatcher pipeline");
        DEGRADE_1201.put("func_184191_r", "isAlliedTo 在 1.20 有两个同参数个数重载，无法机械区分");

        // ==============================================================
        // 本轮补齐的 31 条 —— 都是 Derive1201Table 拿官方映射核对**核对不过**的,
        // 每条的理由就是工具输出的原因, 底下写成人话。
        // 三类: ① 成员/宿主类不存在 ② 搬到另一个对象上 ③ 参数表变了 (要改写法)
        // ==============================================================

        // ---- ① action 层没了: GlStateManager 整个类 1.17 就删了 (改成 RenderSystem + PoseStack) ----
        DEGRADE_1201.put("func_179094_E", "pushMatrix: GlStateManager 1.17 起整个类已删, 宿主类不存在; 调用点绑死 Class.forName(GlStateManager), 改名救不了");
        DEGRADE_1201.put("func_179121_F", "popMatrix: 同上");
        DEGRADE_1201.put("func_179109_b", "translate: 同上");
        DEGRADE_1201.put("func_179152_a", "scale: 同上");
        DEGRADE_1201.put("func_179130_a", "ortho: 同上");
        DEGRADE_1201.put("func_179128_n", "matrixMode: 同上");
        DEGRADE_1201.put("func_179096_D", "loadIdentity: 同上");
        DEGRADE_1201.put("func_179097_i", "disableDepth: 同上");
        DEGRADE_1201.put("func_179126_j", "enableDepth: 同上");
        DEGRADE_1201.put("func_179090_x", "disableTexture2D: 同上");
        DEGRADE_1201.put("func_179098_w", "enableTexture2D: 同上");
        DEGRADE_1201.put("func_179132_a", "depthMask: RenderSystem 上确实有 depthMask(boolean), 但那是**另一个类**; 调用点绑死的 cGL 就是 GlStateManager, 别名表只翻成员名不换宿主对象");
        DEGRADE_1201.put("func_187441_d", "glLineWidth: 同上 (1.20 走 RenderSystem / GL11)");

        // ---- ② 结构变了, 要改写法 ----
        DEGRADE_1201.put("field_70142_S", "lastTickPosX: 1.20 的 Entity 里 xo/yo/zo 和 xOld/yOld/zOld 都是 double 三连, 映射文件分不出哪组是渲染插值基准; 不硬塞");
        DEGRADE_1201.put("field_70137_T", "lastTickPosY: 同上");
        DEGRADE_1201.put("field_70136_U", "lastTickPosZ: 同上");
        DEGRADE_1201.put("field_70181_x", "motionY: 1.20 把 motionX/Y/Z 合并成 Vec3 deltaMovement, 那个句柄已给 motionX; 改读 deltaMovement.y");
        DEGRADE_1201.put("field_70179_y", "motionZ: 同上, 改读 deltaMovement.z");
        DEGRADE_1201.put("field_70121_D", "boundingBox 字段没了, 1.20 只有 getBoundingBox() (字段->方法, 改名解决不了)");
        // 注: 这三条原来也在表里, 但工程早就不再以字面量引用它们了 (只在注释里出现),
        // 属于过期条目 —— 留着会让门禁把"降级表"和"实际用到"对不上。
        //   field_70093_af  死句柄, 1.12.2 上本来就取不到 (真名是 func_70093_af -> isShiftKeyDown)
        //   field_74282_a   1.8.9 侧 Timer.ticksPerSecond; 1.12.2 侧同槽位叫 field_194149_e
        //                   (已在 A189 里映到 msPerTick, 见本文件 A189 那段)
        //   func_189696_b   实心框 1.20 不再由原版提供 (自己画), 句柄无对应
        DEGRADE_1201.put("field_71086_bY", "LocalPlayer 没有 timeInPortal: 1.20 同类量是 Entity.portalTime(int) / spinningEffectIntensity(float), 语义和类型都对不上, 分不开");

        // ---- ③ 参数表 / 宿主对象变了, 要改写法 ----
        DEGRADE_1201.put("func_146116_c", "GuiButton.mousePressed(Minecraft,int,int) -> AbstractWidget.mouseClicked(double,double,int): 参数表变了 (不再收 Minecraft)");
        DEGRADE_1201.put("func_178890_a", "onPlayerRightClick 6 个参数, 1.20 只剩 useItemOn(LocalPlayer,InteractionHand,BlockHitResult): 参数表全变");
        DEGRADE_1201.put("func_187099_a", "processRightClickBlock 同上");
        DEGRADE_1201.put("func_180142_b", "1.20 没有按名字取药水效果的静态方法了 (改查 BuiltInRegistries.MOB_EFFECT / MobEffect.byId)");
        DEGRADE_1201.put("func_110306_p", "Legacy TargetHUD skin portrait; KA and this target presentation are not ported to 1.20.1");
        DEGRADE_1201.put("func_110434_K", "Legacy TargetHUD texture manager path is disabled in 1.20.1; modern overlays use their own renderer");
        DEGRADE_1201.put("func_110577_a", "Legacy TargetHUD skin binding is disabled in 1.20.1; modern texture binding uses RenderSystem");
        DEGRADE_1201.put("func_152125_a", "Legacy GUI texture quads are not GuiGraphics blit; TargetHUD is legacy only");
        DEGRADE_1201.put("func_179131_c", "Legacy GlStateManager tint is not the modern shader color API; TargetHUD is legacy only");
        DEGRADE_1201.put("func_77832_l", "Legacy AutoPot reads ItemPotion effects only on 1.8.9; AutoPot is not ported to 1.20.1");
        DEGRADE_1201.put("func_185189_a", "Legacy AutoPot reads PotionUtils only on 1.12.2; AutoPot is not ported to 1.20.1");
        DEGRADE_1201.put("func_76456_a", "Legacy 1.8.9 effect integer IDs are not the modern MobEffect API");
        DEGRADE_1201.put("func_188419_a", "Legacy AutoPot effect objects are not used by the 1.20.1 overlay");
        DEGRADE_1201.put("func_76396_c", "Legacy 1.8.9 Potion#getId is not used by the 1.20.1 overlay");
        DEGRADE_1201.put("func_188409_a", "Legacy 1.12.2 Potion numeric IDs are not used by the 1.20.1 overlay");
        DEGRADE_1201.put("func_71165_d", "聊天发送搬到了另一个对象上: EntityPlayerSP.sendChatMessage -> ClientPacketListener.sendChat(String)");
        DEGRADE_1201.put("func_72900_e", "1.20 没有 Level.removeEntity(Entity) 了, 改走实体自己的 remove(RemovalReason) / discard()");
        DEGRADE_1201.put("func_175068_a", "renderWorldPass 改名成 renderLevel(F,J,PoseStack) 且描述符全变; LiteAgent 的 transformer 是按 1.12.2 描述符匹配的, 要重写");
        DEGRADE_1201.put("func_78479_a", "setupCameraTransform 在 1.20 没有独立方法了 (相机矩阵在 renderLevel 里内联); 1.20.1 的相机钩子改挂在 Camera 上, 见 LiteAgent");
        // 注: func_189696_b (实心框) 原来也在表里, 工程已不再以字面量引用它 —— 同上, 过期条目。
    }

    /** 这个句柄在 1.20.1 上为什么不可用；两版本或可用时返回 null。 */
    static String degradeReason1201(String srg) {
        if (!is1201()) return null;
        return DEGRADE_1201.get(srg);
    }

    /** 1.20.1 降级清单条目数，给探针用。 */
    static int degradeCount1201() {
        return DEGRADE_1201.size();
    }

    /**
     * 把 1.12.2 的 SRG 名翻译成当前版本的名字。
     *
     * 1.12.2 下是恒等函数（返回原值），所以对现有版本的**行为零影响** ——
     * 这一点很重要：改这个不能把已经在跑的那套弄坏。
     *
     * <h3>1.20.1 为什么也走这里</h3>
     *
     * 1.20.1 的 SRG 编号和 1.12.2 **完全是两套**（位置 X：{@code field_70165_t} vs
     * {@code f_19825_}），所以 1.12.2 的 228 个句柄里只有 12 个能靠换前缀命中。
     * 但**入口是同一个** —— 全部反射句柄都从 mopt/mpriv/fopt 走，翻译写在这里，
     * 八百多个调用点一行都不用改。
     *
     * 表是 {@code Derive1201Table} 从官方 merged 映射机械推出来的，不是我拍的；
     * 每条都过了"字段映字段、方法映方法"的自检。
     */
    static String alias(String srg) {
        if (srg == null) return srg;
        if (is189()) {
            String t = A189.get(srg);
            return t == null ? srg : t;
        }
        if (is1201()) {
            String t = A1201.get(srg);
            return t == null ? srg : t;
        }
        return srg;   // 1.12.2：恒等
    }

    /** 别名表条目数，给探针和诊断用。 */
    static int aliasCount() {
        if (is1201()) return A1201.size();
        return A189.size();
    }

    /**
     * 某些选项在 1.8.9 上**原理上做不到**，直接不出现在界面里。返回 false 的选项不会被
     * 写进模式参数的可选列表，用户看不到它。
     *
     * 判据不是"句柄取不到"（那是运行时才知道的），而是机制本身存不存在。
     * 目前只有副手一类：1.8.9 没有副手槽，AutoTotem 的 Offhand 模式无处可施。
     */
    static boolean modAvailable(String option) {
        if (is1201()) {
            // 1.20.1 的选项表仍然复用旧端配置格式。只有明确可映射的选项继续显示，
            // 其余选项在面板里直接隐藏，避免“开关亮着但运行时永远没有效果”。
            // 1.20.1 的游戏逻辑由独立的渲染/输入适配层承接，不走旧版 LWJGL2 句柄。
            return !("Offhand".equals(option)
                    || "Packet".equals(option)
                    || "Silent".equals(option));
        }
        if (!is189()) return true;
        return !"Offhand".equals(option);
    }

    /** 旧版 Hook 的字段布局与 1.20.1 的 Vec3/OptionInstance 布局不兼容。 */
    static boolean legacyFieldHookAvailable() {
        return !is1201();
    }

    /** 1.20.1 使用 GuiGraphics + GLFW 覆盖层，不再生成 GuiScreen 子类。 */
    static boolean overlayGuiAvailable() {
        return is1201();
    }
}
