package com.lite.agent;

import java.lang.instrument.Instrumentation;
import java.lang.instrument.ClassFileTransformer;
import java.lang.reflect.Method;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * JVM Agent: 注入到运行中的 Minecraft 进程。
 * hook 点: EntityRenderer.updateCameraAndRender (SRG func_181560_a)——
 * 开头插入 Hook.onFrame(partialTicks), 每个 RETURN 前插入 Hook.onRender(partialTicks)。
 * 额外: 用 ASM 生成 LiteGui (GuiScreen 子类) 并 defineClass 进 LaunchClassLoader,
 * 使其方法名直接是运行时 SRG 名, 打开后 MC 原生解锁光标/捕获点击。
 */
public class LiteAgent {

    /** 嵌入式 agent 版本；注入器用它区分当前 agent 与 JVM 中残留的旧实例。 */
    private static final String AGENT_VERSION = "AZclient 8.0.0";

    private static volatile boolean injected = false;

    // ★★★★★ 二分定位用的模式串。值 = Attacher 传给 agentmain 的 args，小写。
    //
    //   真机实测（这三条是关键的对照结果）：
    //       不注入              → 进服成功
    //       agent 加载但什么都不做 → 进服成功
    //       全量注入            → 进不去服
    //   ⇒ agent 的**存在**没问题，问题在它**干的事**。
    //
    //   所以把三件事拆开单独关掉：
    //       "nt" = no-transform        照常 addURL + defineLiteGui + 注册 transformer，
    //                                  但 transform() 一律返回 null，一个字节都不改。
    //       "nu" = no-url / no-define  在 nt 基础上再跳过 addURL 和 defineLiteGui
    //                                  （即完全不碰游戏的类加载器）。
    //
    //   两个测试各给出一个二元答案：
    //       nt 能进 ⇒ 是**字节码被改了**被查到（transformer 本身和 addURL 都无辜）
    //       nt 进不去 ⇒ 是 addURL / defineLiteGui / 注册 transformer 其中之一
    //       nu 能进 ⇒ 落在 addURL 或 defineLiteGui 上
    private static volatile String MODE = "";

    /**
     * LiteGui 是否已经尝试定义过。
     *
     * <p>premain 阶段 LaunchClassLoader 还不存在, 定义不了; 等到渲染主类加载时补一次
     * (见 {@link #ensureLiteGui})。这个标志保证"补"只发生一次 —— 它在每帧都会走的
     * transformer 里被读, 不能每次都去反射一遍。
     */
    private static volatile boolean guiDone = false;


    /**
     * 送给 ASM 的 ClassVisitor/MethodVisitor API 级别。
     *
     * <p>取值取决于**装的是哪一版 ASM**, 而不是 MC 版本 —— 这一点必须说清楚, 因为它是
     * 1.20.1 的第二个硬前提 (第一个是类名/方法名全换):
     *
     * <ul>
     * <li>仓库里的 {@code lib/asm.jar} 是 <b>ASM 5.2</b>: 它的 ClassReader **读不了**
     * Java 17 的类 —— 1.20.1 的类文件是 major 61, 而 ASM 5.2 只到 Java 9,
     * 构造时直接抛 {@code IllegalArgumentException} (消息还是空的, 看不出原因)。</li>
     * <li>就算换成 ASM 9.x, 只要仍然用 {@code Opcodes.ASM5} 这个级别去访问, 一遇到
     * Java 17 类的 {@code NestHost/NestMembers} 属性就会抛
     * {@code UnsupportedOperationException: NestMember requires ASM7} ——
     * <b>读都读不进去</b>, 不是"某个补丁没生效"。</li>
     * </ul>
     *
     * <p>所以这里不写死 ASM5, 也不写死 ASM9
     * (ASM 5.2 里根本没有 ASM9 这个常量, 写死就是编译不过), 而是**探一下**:
     * ASM9 字段在不在。ASM 5.2 → 沿用 ASM5 (与改动前逐字一致); ASM 9.x → 用 ASM9,
     * 于是 Java 17 的类才真的读得进来。
     */
    static final int ASM_API = detectAsmApi();

    private static int detectAsmApi() {
        try {
            Opcodes.class.getField("ASM9");
            return 9 << 16;              // = Opcodes.ASM9, 但老 ASM 里没有这个常量
        } catch (Throwable t) {
            return Opcodes.ASM5;    // ASM 5.2: 只能这个级别, 否则构造器直接拒收
        }
    }

    // ==================================================================
    // 版本分派表 —— **所有**硬编码的 MC 类名/方法名/描述符只允许写在这里
    //
    // 为什么必须集中: transformer 的失败方式是**静默的** —— 名字对不上就一条都不匹配,
    // 不抛异常、不报错, 表现只是"功能全没有"。以前这些字符串散在 transform()/hook()/
    // 每个 patch 方法体里, 改的时候没有任何一处会提醒你"还有第二个地方写着 EntityRenderer"。
    //
    // 两列完全没有一个字面相同:
    //   1.8.9 / 1.12.2 : MCP 类名 + SRG 句柄 (func_/field_)
    //   1.20.1         : Mojang 官方类名 (Forge remap) + **另一套** SRG 编号 (m_/f_)
    //
    // 1.20.1 那一列不是凭记忆写的, 依据三条, 每条都能指出出处:
    //   ① versions/1.20.1/mcp/client-1.20.1-20230612.114412-mappings.txt   (官方名 -> obf)
    //   ② versions/1.20.1/mcp/mcp_config-...-merged.txt                    (obf -> SRG)
    //   ③ %APPDATA%\.minecraft\libraries\net\minecraft\client\1.20.1-20230612.114412\
    //        client-1.20.1-20230612.114412-srg.jar
    //      —— Forge 运行时真正加载的那份字节码 (SRG 名已 remap 好), 用 javap -p -s / -c
    //         逐条核对过方法名与描述符。本机没有可跑的 1.20.1 客户端, 但这份 jar 就是
    //         它的字节码, 所以"名字和描述符对不对"是可以静态证实的, 只有"跑起来行不行"
    //         证实不了 —— 汇报里已如实注明。
    // ==================================================================
    static final class T {

        private static final boolean V1201 = Ver.is1201();

        private T() {
        }

        // ---- 渲染主类 ----
        // 1.12.2 / 1.8.9 : net.minecraft.client.renderer.EntityRenderer
        // 1.20.1         : 改名叫 GameRenderer (mapping: "net.minecraft.client.renderer.GameRenderer -> fjq:")
        //   **注意** 1.20.1 里 EntityRenderer 这个名字**仍然存在**, 但那是另一个类
        //   (net.minecraft.client.renderer.entity.EntityRenderer = 实体渲染器基类)。
        //   所以老名字不是"找不到", 而是"指到了一个完全不相干的类", 里面当然一条钩子都匹配不上。
        static final String RENDERER = V1201
                ? "net.minecraft.client.renderer.GameRenderer"
                : "net.minecraft.client.renderer.EntityRenderer";
        static final String RENDERER_I = RENDERER.replace('.', '/');

        // ---- 原版攻击执行相位: Minecraft 客户端 tick ----
        // 1.8.9/1.12.2 clicks precede World.updateEntities(). WorldClient.tick()
        // is later: player movement and its outgoing packet have already run.
        // 1.20.1 tick() 先调用 handleKeybinds()，随后才推进 ClientLevel。
        // 渲染线程只确认模型真正显示过瞄准姿态；挥手、controller.attackEntity、
        // 冲刺中断和击退响应必须回到这个游戏 tick 相位，才能和原版移动顺序一致。
        static final String MINECRAFT = "net.minecraft.client.Minecraft";
        static final String MINECRAFT_I = MINECRAFT.replace('.', '/');
        static final String CLIENT_TICK_M = V1201 ? "m_91398_" : "func_71407_l";
        static final String ATTACK_PHASE_M = V1201 ? "m_91279_" : "func_72939_s";
        static final boolean ATTACK_PHASE_AFTER = V1201;

        // 1.20.1 HUD 绘制入口：GuiGraphics 已经是当前帧的有效绘制上下文，
        // 在它的末尾叠加面板，避免构造旧版 GuiScreen。
        static final String GUI = "net.minecraft.client.gui.Gui";
        static final String GUI_I = GUI.replace('.', '/');
        static final String GUI_M = "m_280421_";
        static final String GUI_MD = "(Lnet/minecraft/client/gui/GuiGraphics;F)V";

        // ---- 每帧钩子: onFrame(方法开头) / onRender(每个 RETURN 前) ----
        // 1.12.2 func_181560_a (FJ)V  = updateCameraAndRender(partialTicks, nanoTime)
        // 1.20.1 m_109093_   (FJZ)V  = render(partialTicks, nanoTime, renderLevel)
        //        javap -s: "public void m_109093_(float, long, boolean)" descriptor (FJZ)V
        // 两版的 partialTicks 都在局部槽 1 (this=0, float=1, long=2-3), 1.20.1 多的 boolean 在 4
        // —— 所以插入代码本身两版相同, 只有"匹配哪个方法"要分派。
        static final String RENDER_M = V1201 ? "m_109093_" : "func_181560_a";
        static final String RENDER_MD = V1201 ? "(FJZ)V" : "(FJ)V";

        // ---- 世界渲染钩子: onWorldRender ----
        // 1.12.2 func_175068_a (IFJ)V = renderWorld(pass int, partialTicks float, finishTimeNano long)
        // 1.20.1 m_109089_ (FJLcom/mojang/blaze3d/vertex/PoseStack;)V = renderLevel(partialTicks, nanoTime, poseStack)
        //        javap -s 实证 descriptor (FJLcom/mojang/blaze3d/vertex/PoseStack;)V
        // **1.20.1 没有 pass 参数**: 一帧只调一次 renderLevel, 1.12.2 的 pass 语义没有对应物,
        // 所以压常量 0 (WORLD_PASS = -1 表示"没有这个参数")。
        // 记一笔: Hook.onWorldRender 里有 `if (pass != 2) return;` 的守卫 (1.8.9 上实测主 pass==2),
        // 于是 1.20.1 上这条分支会被它自己的守卫挡掉 —— 世界坐标绘制要等 Hook 加 1.20.1 分支。
        static final String WORLD_M = V1201 ? "m_109089_" : "func_175068_a";
        static final String WORLD_MD = V1201
                ? "(FJLcom/mojang/blaze3d/vertex/PoseStack;)V" : "(IFJ)V";
        /** renderWorld 的 pass 参数槽位; -1 = 这个方法没有 pass 参数, 压常量 0。 */
        static final int WORLD_PASS = V1201 ? -1 : 1;
        /** renderWorld 的 partialTicks 槽位 (少了 pass 就前移一位)。 */
        static final int WORLD_TICKS = V1201 ? 1 : 2;

        // ---- 相机钩子 onCameraReady ----
        // 1.12.2/1.8.9 : EntityRenderer.setupCameraTransform (FI)V = func_78479_a
        // 1.20.1        : 这个方法**没了**, 相机搬到了**另一个类**
        //                 net.minecraft.client.Camera.setup
        //                 m_90575_(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V
        //                 javap -c 实证: 由 GameRenderer.m_109089_ (renderLevel) 调用, 返回之后
        //                 renderLevel 立刻拿 camera 的朝向拼 PoseStack —— 也就是
        //                 "相机已经定死、实体模型还没开始画", 与 1.12.2 那个钩子的位置语义一致。
        //                 因为换了类, transform 里多一条 CAMERA 分支 (只在 1.20.1 注册)。
        static final String CAMERA = V1201 ? "net.minecraft.client.Camera" : RENDERER;
        static final String CAMERA_I = CAMERA.replace('.', '/');
        static final String CAMERA_M = V1201 ? "m_90575_" : "func_78479_a";
        static final String CAMERA_MD = V1201
                ? "(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V" : "(FI)V";

        // ---- 攻击距离: getBlockReachDistance / getPickRange ----
        // 1.12.2 PlayerControllerMP.func_78757_d ()F
        // 1.20.1 MultiPlayerGameMode.m_105286_ ()F
        //        javap -c 实证: `GameType.m_46408_()Z ? 5.0f : 4.5f` —— 和 1.12.2 那个
        //        "返回一个常数" 的方法同形, 所以 patchReach 换方法体的做法可以照搬。
        static final String CONTROLLER = V1201
                ? "net.minecraft.client.multiplayer.MultiPlayerGameMode"
                : "net.minecraft.client.multiplayer.PlayerControllerMP";
        static final String CONTROLLER_I = CONTROLLER.replace('.', '/');
        static final String REACH_M = V1201 ? "m_105286_" : "func_78757_d";

        // ---- 静默转身的写入窗口: EntityPlayerSP.onUpdate / LocalPlayer.tick ----
        // 1.12.2 func_70071_h_ ()V
        // 1.20.1 m_8119_     ()V
        //        javap -c 实证: 第 20 条指令是
        //        invokespecial net/minecraft/client/player/AbstractClientPlayer.m_8119_:()V = super.tick()
        //        —— super 调用名与自身方法名相同, 与 1.12.2 的形状一致, 所以 patchRotation 的
        //        定位逻辑 (super 之后写、RETURN 前还原) 可以照搬, 只换名字。
        static final String PLAYER = V1201
                ? "net.minecraft.client.player.LocalPlayer"
                : "net.minecraft.client.entity.EntityPlayerSP";
        static final String PLAYER_I = PLAYER.replace('.', '/');
        static final String TICK_M = V1201 ? "m_8119_" : "func_70071_h_";
        static final String LIVING_UPDATE_M = "func_70636_d";
        static final String INPUT_UPDATE_M = "func_78898_a";

        // ---- 聊天后缀: 出站消息的改写点 ----
        // 1.12.2 EntityPlayerSP.func_71165_d (Ljava/lang/String;)V = sendChatMessage
        // 1.20.1 **那个方法没了**: LocalPlayer 里连一个 (String)V 的发送方法都不剩
        //        (javap 实证)。出站消息在 ClientPacketListener.sendChat(String) 里:
        //        net.minecraft.client.multiplayer.ClientPacketListener
        //        .m_246175_(Ljava/lang/String;)V
        //        (mapping: "void sendChat(java.lang.String) -> b")。
        //        javap -c 实证 ChatScreen 的提交路径: 不以 "/" 开头就调 m_246175_ (sendChat),
        //        以 "/" 开头的另一支调 m_246623_ (sendCommand) —— 只改前者, 不动命令。
        //        因为**换了类**, transform 里单独一条 CHAT_SEND 分支。
        //
        //  **签名安全 —— 1.20.1 上这条补丁能不能用的关键, 已逐条实证 (别改成别的地方)**:
        //  1.19 之后出站聊天走签名链 (MessageSignature / SignedMessageChain), 改内容确实可能
        //  被服务端拒收 —— 但**只看插在哪**。javap -c ClientPacketListener.m_246175_ 的指令序:
        //     25: aload_1                    ← 我们改的那个 String
        //     33: invokespecial SignedMessageBody."<init>":(String;Instant;J;LastSeenMessages)
        //     36: invokeinterface SignedMessageChain$Encoder.m_240988_(SignedMessageBody)MessageSignature
        //     48: aload_1 ... ServerboundChatPacket."<init>":(String;Instant;J;MessageSignature;...)
        //  即 **String 进方法之后才被签**, 而 patchChatSuffix 插在方法**入口** ——
        //  服务端收到的是"签在我改完的文本上"的签名, 验签照过。
        //  会真被踢的写法是"签完之后再改" (改 ServerboundChatPacket 的字段 / 改已缓存的消息),
        //  那些我们一个字都不碰。
        //  m_246175_ 的调用方只有 2 个类 (ChatScreen 与 ClientPacketListener 自己);
        //  "/命令" 走 m_246623_ (sendCommand) —— **命令不动** (命令的签名是另一套
        //  SignableCommand, 动了必炸)。
        //  已知上限: 原版聊天长度上限 256 字符, 后缀可能顶到上限 (1.12.2 同样如此, 行为一致)。
        static final String CHAT_SEND = V1201
                ? "net.minecraft.client.multiplayer.ClientPacketListener"
                : PLAYER;
        static final String CHAT_SEND_I = CHAT_SEND.replace('.', '/');
        static final String CHAT_SEND_M = V1201 ? "m_246175_" : "func_71165_d";
        static final String CHAT_SEND_MD = "(Ljava/lang/String;)V";

        // ---- 名字保护: 聊天文本节点被创建的地方 (钩 (String)V 构造器) ----
        // 1.8.9  net/minecraft/util/ChatComponentText        (Ver.chatTextClass 给的名字)
        // 1.12.2 net/minecraft/util/text/TextComponentString (同上)
        // 1.20.1 **TextComponentString / TextComponent 都不存在了**:
        //        official mapping 里搜不到 TextComponent 这个类, srg.jar 里也没有
        //        net/minecraft/network/chat/TextComponent.class。1.20.1 的纯文本节点是
        //        net.minecraft.network.chat.contents.LiteralContents
        //        (javap 实证: 是个 record, 构造器 (Ljava/lang/String;)V, 字段 f_237368_),
        //        Component.literal(String) 就是 new 一个它。
        //        仍然是 (String)V 构造器 —— 所以 patchNameProtect 一行都不用改, 只换类名。
        //
        //  **为什么 LiteralContents 就是那个槽, 而不是"找个差不多的类" (三条实证)**:
        //   ① Component.literal(String) = m_237113_ (mappings 440 行), javap -c 实证方法体是
        //      `new LiteralContents` + `.<init>:(Ljava/lang/String;)V` —— 逐字对应
        //      1.12.2 的 TextComponentString(String)。(顺带: m_237115_ 是 translatable,
        //      造的是 TranslatableContents; m_237117_ 是 keybind, 造 KeybindContents。)
        //   ② 整份 srg.jar 里**只有 4 个类**引用 LiteralContents:
        //      Component / Component$Serializer / SignBlock / 它自己 —— 没有第二个候选,
        //      不存在"选错类"的空间。
        //   ③ 进来的玩家聊天也走这条路: javap -c PlayerChatMessage 里有
        //      `invokestatic Component.m_237113_(String)`。也就是说别人发的聊天文本
        //      同样会在客户端 new 出 LiteralContents —— 钩子会响。
        //
        //  **不碰签名**: 这个钩子只改"本地 new 出来的内容对象"的入参, 是纯显示层。
        //  Component 是客户端本地构造的, 网络包 (SignedMessageBody / PlayerChatMessage /
        //  MessageSignature) 一个字段都不动, 所以既改不了服务端, 也不可能破坏验签。
        //
        //  **为什么不用 1.19+ 新出的 ChatDecorator**: 它是**服务端**的装饰钩子 ——
        //  整份 srg.jar 里只有 commands/arguments/MessageArgument、MinecraftServer、
        //  ServerGamePacketListenerImpl 引用它, 没有一个是客户端渲染路径。
        //  远程服的 ChatDecorator 跑在对面服务器上, 客户端 agent 够不着; 单人局里它改的是
        //  **广播出去的正文**(动到线上内容, 正是签名那条红线)。所以不是"更正当的落点",
        //  是错的半边。
        //
        // **这条本该由 Ver.chatTextClass() 分派**, 但它不在我的改动范围 (那张表有人在改),
        // 所以先在 LiteAgent 里兜住。汇报里已注明"该改 Ver 但没动"。
        static final String CHAT_TEXT = V1201
                ? "net/minecraft/network/chat/contents/LiteralContents"
                : Ver.chatTextClass();     // 返回的是带斜杠的内部名, 与上面一致

        // ---- 实体距离补丁: 1.20.1 的插入点识别 (按"调用了什么"定位, 不按槽位/下标) ----
        // 下面这几个都是 1.20.1 专有, **不做版本分派** —— 它们只在 1.20.1 那条分支里用,
        // 而且自检 (TransformCheck) 要在非 1.20.1 的 JVM 上直接调那条分支, 所以必须写死。
        // javap -c GameRenderer.m_109087_(F)V 实证到的方法调用 (名字都是运行时 SRG 名):
        /** GameRenderer.pick(float) —— 1.12.2 的 EntityRenderer.getMouseOver 对应的那个方法。 */
        static final String PICK_1201 = "m_109087_";
        /** MultiPlayerGameMode.getPickRange()F —— pick 里 d0 的来源 (也是 patchReach 换掉的那个)。 */
        static final String PICK_RANGE_1201 = "m_105286_";
        /** Entity.getViewVector(F)LVec3; —— 实体射线的方向。窗口从这里开始。 */
        static final String PICK_VIEWVEC_1201 = "m_20252_";
        /** ProjectileUtil.getEntityHitResult(...D)LEntityHitResult; —— 实体射线的终点判定, 插入点在这里。 */
        static final String PICK_ENTITY_1201 = "m_37287_";
        /** 1.12.2 那边 getMouseOver 的名字 (1.8.9 同名, 所以不分派)。 */
        static final String PICK_OLD = "func_78473_a";
    }

    /**
     * <b>-javaagent 入口 —— 现在的主路径。</b>
     *
     * <p>为什么必须有它, 见 {@link #installTransformer} 上面那段注释: attach 之后才注入,
     * 游戏的核心类**早就加载完了**, 而唯一能让它们立即生效的 {@code retransformClasses}
     * 会重跑反作弊自己的 transformer, 把它的原生网络解密模块搞坏 —— 进服收到第一个包就崩。
     *
     * <p>{@code -javaagent} 在**类加载之前**就挂上 transformer, 所以:
     * <ul>
     *   <li>每个游戏类在加载时就带上钩子 —— 不需要 retransform, 不需要 redefine, 不需要裸字节</li>
     *   <li>也绕开了"网易 jar 里是混淆名、getClassBytes 拿不到"这个死结</li>
     *   <li>{@code Hook} 由系统加载器解析 (jar 被 -javaagent 加进了 system search),
     *       <b>不经过 LaunchClassLoader.findClass</b>, 连反作弊那个原生类检查都碰不到</li>
     * </ul>
     *
     * <p>直接复用 agentmain 是安全的: 它里面那几步 attach 专属的副作用在 premain 阶段
     * 要么是空操作 (Launch 还没加载 → addToLaunchClassLoader 返回 null), 要么正是我们要的
     * (目标类还没加载 → 循环自然走进 "NOT LOADED yet (will transform on load)")。
     */
    public static void premain(String args, Instrumentation inst) throws Exception {
        // The game module does not exist at JVM startup. Bind it only after Minecraft loads.
        final String startupArgs = args;
        final Instrumentation startupInst = inst;
        Thread deferred = new Thread(new Runnable() { public void run() {
            try {
                long deadline = System.nanoTime() + 120000000000L;
                while (System.nanoTime() < deadline) {
                    for (Class<?> c : startupInst.getAllLoadedClasses()) {
                        if ("net.minecraft.client.Minecraft".equals(c.getName())) {
                            agentmain(startupArgs, startupInst);
                            return;
                        }
                    }
                    Thread.sleep(100);
                }
                log("premain: 等待 Forge Minecraft 主类超时，未安装任何 hook");
            } catch (Throwable e) { log("premain initialization FAILED: " + e); }
        }}, "AZclient runtime binding");
        deferred.setDaemon(true);
        deferred.start();
    }

    /**
     * premain 阶段 Launch 还没加载, 拿不到 LaunchClassLoader, 所以 LiteGui 那时定义不了。
     * 等到第一个目标类真正加载时 (那时 Launch 早就在了) 补一次。只试一次。
     */
    private static void ensureLiteGui() {
        if (guiDone) return;
        guiDone = true;
        try {
            Object lcl = startupLoader();
            if (lcl == null) {
                log("ensureLiteGui: 还是拿不到 LaunchClassLoader, LiteGui 没定义 (面板打不开)");
                return;
            }
            log("ensureLiteGui: 拿到 LaunchClassLoader, 现在定义 LiteGui");
            defineLiteGui(lcl);
        } catch (Throwable t) {
            log("ensureLiteGui FAILED: " + t);
        }
    }

    /** 反射取 net.minecraft.launchwrapper.Launch.classLoader */
    private static Object startupLoader() {
        try {
            Class<?> c = Class.forName("net.minecraft.launchwrapper.Launch");
            java.lang.reflect.Field f = c.getDeclaredField("classLoader");
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void agentmain(String args, Instrumentation inst) throws Exception {
        InjectionReceipt receipt = InjectionReceipt.parse(args);
        if (receipt != null) receipt.write("LOADING", AGENT_VERSION, "");
        try {
            runAgent(receipt == null ? args : null, inst);
            if (receipt != null) {
                String reason = Hook.licenseReason();
                if (reason != null && !reason.isEmpty()) receipt.write("DENIED", AGENT_VERSION, reason);
                else receipt.awaitReady(inst, AGENT_VERSION);
            }
        } catch (Exception failure) {
            if (receipt != null) receipt.write("ERROR", AGENT_VERSION, failure.toString());
            throw failure;
        } catch (Error failure) {
            if (receipt != null) receipt.write("ERROR", AGENT_VERSION, failure.toString());
            throw failure;
        }
    }

    private static synchronized void runAgent(String args, Instrumentation inst) throws Exception {
        // ★★★★★ "null" 模式：只证明 agent 加载了，别的什么都不做。
        //
        //   用途是**二分定位**。真机数据（22 次成功进服全部是"先进服、后注入"，
        //   唯一一次"先注入、后连接"的那次当场被 TerminateProcess 掉）指向一个结论：
        //   认证握手期间 JVM 里存在 agent = 死。但"存在 agent"和"agent 干的事"
        //   是两个不同的东西，得分开验证，否则会把方向搞错。
        //
        //   所以这一个开关问的就是那句最要紧的话：
        //       agent 只是被加载（什么都不做）→ 游戏活得下来吗？
        //     活得下来 ⇒ 问题在我们的副作用（addURL / defineClass / transformer），
        //                 可以逐步关掉排查。
        //     活不下来 ⇒ agent 的存在本身就会被 native 测出来，这条路彻底封死，
        //                 必须换一条完全不同的注入路径。
        //
        //   必须放在**最前面** —— 授权校验、defineClass、transformer 一个都不能碰。
        if (args != null && args.trim().equalsIgnoreCase("null")) {
            log("agentmain NULL 模式: agent 已加载, 不注册 transformer、不 defineClass、不改任何字节码");
            return;
        }

        // ★ 二分模式。nt = 不改字节码; nu = 连类加载器都不碰。见 MODE 字段的注释。
        MODE = (args == null) ? "" : args.trim().toLowerCase();
        if (MODE.equals("nt") || MODE.equals("nu")) {
            log("agentmain 二分模式 [" + MODE + "]: "
                    + (MODE.equals("nu") ? "跳过 addURL / defineLiteGui, 且不改字节码"
                                         : "照常 addURL + defineLiteGui, 但不改字节码"));
        }

        // 幂等: 已注入过则直接返回, 避免重复 defineClass 抛 LinkageError 导致注入器显示失败
        if (injected) {
            // 必须把 hook 的真实状态一起打出来。只打 "skip (already injected)" 的话,
            // 注入器会把这一行当成"注入成功", 于是"agent 在但 hook 没装上"这种状态
            // 被报成绿的 —— 重试一次就"成功"了, 游戏里却什么都没有。
            boolean ready;
            try { ready = com.lite.agent.Hook.isInited(); } catch (Throwable t) { ready = false; }
            log("agentmain skip (already injected), hook=" + (ready ? "READY" : "NOT-READY")
                    + " version=" + AGENT_VERSION);
            return;
        }
        // Do this before Hook's static options and T's immutable mapping table are initialized.
        Ver.bindRuntime(inst.getAllLoadedClasses());
        log("runtime loader = " + Ver.runtimeLoader() + ", version = " + Ver.name());
        // 授权校验放在**所有副作用之前** —— 这一步不通过就当场返回, 不注册 transformer,
        // 不改任何字节码, 游戏完全不受影响 (等于没注入过)。
        //
        // 位置很关键: 放在 ensure() 之后的话, 类已经被改了, 再"返回"也收不回来;
        // 而且那时 LiteGui 已经 defineClass 进游戏, 光标解锁这些副作用已经发生。
        //
        // 这是"抽 jar 自己 attach"那条路唯一的拦路石 —— 见 Hook.checkLicense 的注释
        // 关于它防得住谁、防不住谁。
        if (!com.lite.agent.Hook.checkLicense()) {
            injected = false;
            log("agentmain 拒绝: 未授权 (" + com.lite.agent.Hook.licenseReason() + ")");
            System.out.println("[AZclient] 未授权: " + com.lite.agent.Hook.licenseReason()
                    + " —— 请通过启动器登录后再注入");
            return;
        }
        injected = true;
        // 目标版本必须在这里就定下来 —— 下面 defineLiteGui 生成的 doesGuiPauseGame 名字、
        // 以及 transformer 要匹配的聊天文本类名, 都由它决定, 而这两件事都在 ensure() 之前。
        log("target MC version = " + Ver.name());
        logTargets();      // 把这次实际使用的一整套名字打进日志 —— 静默失效时只有这份日志能定位
        // 把 agent jar 自身加入 system classpath (对非 launchwrapper 环境有用)
        // 注意: 用户目录可能含中文, getFile() 返回 URL 编码形式, 必须用 toURI() 让 JDK 解码
        java.io.File agentFile = new java.io.File(
                LiteAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        log("agentmain start, self=" + agentFile.getAbsolutePath());
        inst.appendToSystemClassLoaderSearch(new JarFile(agentFile));

        // Forge / 原版客户端都用 launchwrapper 的 LaunchClassLoader 加载游戏类,
        // 它不走父类委托, 必须把 agent jar addURL 进它自己的 classpath, 否则 hook 后找不到 Hook 类。
        //
        // ★ nu 模式跳过这一步 —— 目的是把"碰过游戏的类加载器"这个变量单独摘出去。
        //   注意 nu 同时也关掉了字节码改写（见 transform()），否则注进去的代码找不到
        //   Hook 类会在渲染循环里抛 NoClassDefFoundError，那个死法跟现在要查的不是一回事。
        Object lcl = MODE.equals("nu") || Ver.is1201() ? null : addToLaunchClassLoader(agentFile);
        if (Ver.is1201()) {
            if (!MODE.equals("nu")) ModernRuntime.connect(inst, Ver.runtimeLoader());
            ModernRuntime.configureGui();
        }
        if (MODE.equals("nu")) {
            log("nu 模式: 跳过 addURL (不碰 LaunchClassLoader)");
        }

        // 生成 LiteGui 并定义进 LaunchClassLoader
        if (MODE.equals("nu")) {
            log("nu 模式: 跳过 defineLiteGui");
        } else if (Ver.is1201()) {
            guiDone = true; // Modern GUI uses GuiGraphics; no generated legacy GuiScreen.
        } else {
            defineLiteGui(lcl);
            guiDone = true;  // attach 路径上这里就定义好了; premain 上 suppress 掉重复尝试
        }

        ClassFileTransformer t = new ClassFileTransformer() {
            public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                    ProtectionDomain protectionDomain, byte[] classfileBuffer) {
                // ★ nt / nu 模式: 一律不改字节码，原样返回。
                //   这两个模式要回答的问题是"游戏是被**改字节码**弄死的，还是被 addURL/
                //   defineLiteGui/注册 transformer 弄死的"。所以这里必须一个字节都不动，
                //   连日志都不打（免得日志量本身成为变量）。
                if (MODE.equals("nt") || MODE.equals("nu")) {
                    return null;
                }

                // ★★★★★ 按类二分（为什么这么做见 MODE 字段的注释）。
                //
                //   已经确证的事实: agent 存在 / addURL / defineLiteGui / 注册 transformer
                //   全都无辜，**只有"真的改了游戏类的字节码"会死**。
                //   那么下一个问题是"哪个类被盯着"。我们一共改 6 处，按语义分两组：
                //
                //     ne = 跳过渲染主类 (EntityRenderer / 1.20.1 的 GameRenderer)。
                //          挂在上面的有每帧钩子 onFrame/onRender/onWorldRender、相机 onCameraReady、
                //          实体距离覆盖。这是外挂的经典目标，最可能被逐个核对。
                //     np = 跳过 PlayerControllerMP (方块/攻击距离) 和 EntityPlayerSP
                //          (静默转身 + 聊天后缀)。这两个直接对应"作弊"语义。
                //
                //   两个测试各给出一个二元答案:
                //       ne 能进            ⇒ 渲染主类被盯着。解法是把每帧钩子改挂到
                //                            GuiIngame.renderGameOverlay —— 同样每帧调用，
                //                            但属于 HUD 类，被盯的概率低得多。
                //       ne 进不去 / np 能进 ⇒ 是 controller/player 那一组。
                //       两个都进不去        ⇒ 不是某一类的问题，是"任何游戏类被改都会被查出来"。
                if (MODE.equals("ne") && T.RENDERER_I.equals(className)) {
                    log("ne 模式: 跳过渲染主类 " + className);
                    return null;
                }
                if (MODE.equals("np")) {
                    if (T.CONTROLLER_I.equals(className)
                            || T.PLAYER_I.equals(className)
                            || T.MINECRAFT_I.equals(className)
                            || "net/minecraft/entity/Entity".equals(className)
                            || (Ver.is1201() && T.CHAT_SEND_I.equals(className))) {
                        log("np 模式: 跳过 " + className);
                        return null;
                    }
                }
                try {
                    if (Ver.is1201()) {
                        if (loader != Ver.runtimeLoader()) return null;
                        return ModernRuntime.transform(className, classfileBuffer);
                    }
                    if (BlockAnimation.target(className)) return BlockAnimation.transform(className,classfileBuffer);
                    // 渲染主类: 每帧钩子 (onFrame/onRender/onWorldRender) + 实体距离补丁。
                    // 1.12.2/1.8.9 = EntityRenderer, 1.20.1 = GameRenderer —— 名字由 T 分派。
                    // 实体距离的覆盖挂在同一个类上 —— 两个补丁必须**串起来**走:
                    // hook() 负责 onFrame/onRender/onWorldRender 那几个钩子,
                    // patchEntityReach() 只动 pick/getMouseOver 里那句"实体搜索距离"。
                    // 改的是不同方法, 所以先后无所谓; 但两个都得调 —— 少一个就是漏一半:
                    // patchEntityReach 之前写好了却没接在这里, 症状是"Reach 开到 6 还是打不到 6 格"。
                    if (T.RENDERER_I.equals(className)) {
                        // premain 阶段定义不了 LiteGui (那时还没有 LaunchClassLoader),
                        // 到这一刻补上 —— 渲染主类加载时 Launch 早就在了。
                        // attach 路径上 guiDone 已经是 true, 这里是空操作。
                        ensureLiteGui();
                        return ReachPicker.transform(hook(className, classfileBuffer), T.PICK_OLD);
                    }
                    if (Ver.is1201() && T.GUI_I.equals(className)) {
                        return hook(className, classfileBuffer);
                    }
                    // 相机钩子: 1.12.2 在 EntityRenderer.setupCameraTransform 里 (上面那条分支
                    // 顺带处理), 1.20.1 搬到了 **另一个类** net.minecraft.client.Camera.setup ——
                    // 所以 1.20.1 上多一个转换目标。
                    if (Ver.is1201() && T.CAMERA_I.equals(className)) {
                        return hook(className, classfileBuffer);
                    }
                    // 已完成的渲染帧只生成一次性瞄准凭据；真正的原版攻击调用在
                    // Minecraft 客户端 tick 的输入/世界更新边界消费它。
                    if (T.MINECRAFT_I.equals(className)) {
                        return patchClientAttackPhase(classfileBuffer);
                    }
                    // 攻击距离: 原版拿 playerController.getBlockReachDistance() (1.20.1 叫
                    // getPickRange) 当方块距离和实体距离用 (生存固定 4.5)。把整个方法体换掉,
                    // 两样一起变长 —— 1.20.5 之后才有 entity_interaction_range 属性。
                    if (T.CONTROLLER_I.equals(className)) {
                        return null; // Preserve vanilla/Forge block reach, including creative mode.
                    }
                    // 静默转身: 出站包里带瞄准角。1.12.2 挂在 EntityPlayerSP.onUpdate,
                    // 1.20.1 是 LocalPlayer.tick (同一个语义, 名字由 T 分派)。
                    if (T.PLAYER_I.equals(className)) {
                        return patchChatSuffix(patchRotation(classfileBuffer));
                    }
                    if ("net/minecraft/entity/Entity".equals(className)) {
                        return patchScaffoldEdge(classfileBuffer);
                    }
                    // 聊天后缀: 出站消息被改写的地方。
                    // 1.12.2 和静默转身是同一个类 (EntityPlayerSP.sendChatMessage), 上面那条已经处理;
                    // 1.20.1 那个方法**没了**, 出口在 ClientPacketListener.sendChat(String) —— 换了类,
                    // 所以单独一条。用 Ver.is1201() 挡住, 保证 1.12.2 上不会被施加两次后缀。
                    if (Ver.is1201() && T.CHAT_SEND_I.equals(className)) {
                        return patchChatSuffix(classfileBuffer);
                    }
                    // 名字保护: 每个聊天文本节点被创建时改写它的文本。
                    // 钩构造器而不是 FontRenderer.drawString —— 后者每帧几百次调用, 每次做一次
                    // 字符串替换在游戏里是实打实的开销; 构造器只在消息到达时跑几次。
                    //
                    // 这个类名两版不一样: 1.8.9 ChatComponentText / 1.12.2 TextComponentString /
                    // 1.20.1 contents.LiteralContents。1.8.9 与 1.12.2 走 Ver.chatTextClass(),
                    // 1.20.1 由 T.CHAT_TEXT 兜住。
                    if (T.CHAT_TEXT.equals(className)) {
                        return patchNameProtect(classfileBuffer);
                    }
                } catch (Throwable ex) {
                    // **补丁出错必须原样放行。** transformer 里抛异常会让那个类**加载失败** ——
                    // 表现是整个游戏挂在启动/进世界, 而不是"某个功能没生效"。
                    // 这一层 try 是这次加 1.20.1 分支时补上的 (改任何一处补丁都可能踩到);
                    // 1.12.2/1.8.9 正常路径一个字节都没变, 只有"本来要炸"的情况从炸变成跳过。
                    log("transformer FAILED on " + className + " (原样放行): " + ex);
                    return null;
                }
                return null;
            }
        };

        inst.addTransformer(t, true);
        log("transformer registered");

        // ---- 用 redefineClasses 代替 retransformClasses ----
        //
        // 这是踩出来的。A/B 对照非常干净:
        //   跳过 retransform: 游戏活 15 分钟以上, hook 正常渲染 (日志里 columns: 8 列 一直在打)
        //   走 retransform:   三个类 OK 之后必崩
        // 崩点还不在我们碰过的任何类上, 而在网易的原生网络解密:
        //   Library._O00O0O0O0O0OO(native) -> nativeTransformer$CustomNettyEncryptingDecoder.decode
        //
        // 原因: retransformClasses 会把**所有已注册的 transformer 重跑一遍**, 包括反作弊自己的
        // FMLCorePlugin。它的原生解密模块被重跑一次状态就废了, 下一个服务器包进来空指针。
        //
        // redefineClasses 是**直接换字节码, 一个 transformer 都不重跑**, 正好绕开这个雷。
        // 对我们够用: redefine 只禁止增删字段/方法、改签名, 而我们的 hook 全是在已有方法体里
        // 插调用 (onFrame / blockReach / chatSuffix 那些), 完全在允许范围内。
        //
        // 原始字节从 LaunchClassLoader.getClassBytes(name, false) 拿 —— 它是个 public 方法,
        // 而且返回的正是**未经 transformation 的裸字节**, 最适合喂给 redefine。
        //
        // 开关 lite-noretransform 保留: 存在时连 redefine 也跳过, 退回已验证稳定的
        // "什么都不改" (游戏一定活, 但 hook 只对新加载的类生效)。
        java.io.File noRet = new java.io.File(
                System.getProperty("java.io.tmpdir", "."), "lite-noretransform");
        boolean skipAll = noRet.exists();
        log(skipAll
                ? "★ 开关生效: 连 redefine 都跳过 (只处理之后加载的类)"
                : "redefine 模式: 用 redefineClasses 取代 retransformClasses");

        // 目标类可能已经加载, 得让它立即生效。
        // 注意不能用 Class.forName——MC 类由 launchwrapper 的类加载器加载, agent 的系统加载器看不到。
        // 用 LinkedHashSet 而不是数组: 1.12.2 上 PLAYER 和 CHAT_SEND 是同一个类
        // (EntityPlayerSP), 去重后顺序与原来完全一致; 1.20.1 上两者是两个类, 各自进一次。
        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<String>();
        targets.add(T.RENDERER);
        if (Ver.is1201()) targets.add(T.GUI);
        targets.add(T.MINECRAFT);
        targets.add(T.CONTROLLER);
        targets.add(T.PLAYER);
        if (!Ver.is1201()) {
            targets.add("net.minecraft.entity.Entity");
            targets.add(BlockAnimation.ITEM.replace('/', '.'));
            targets.add(BlockAnimation.PLAYER.replace('/', '.'));
        }
        targets.add(T.CHAT_SEND);
        targets.add(T.CHAT_TEXT.replace('/', '.'));
        if (Ver.is1201()) targets.add(T.CAMERA);   // 1.20.1 的相机钩子在另一个类里
        if (Ver.is1201()) {
            targets.clear();
            targets.add(T.RENDERER);
            targets.add(ModernRuntime.hudTarget().replace('/', '.'));
            targets.add(ModernRuntime.KEYBOARD.replace('/', '.'));
            targets.add(ModernRuntime.LEVEL_RENDERER.replace('/', '.'));
            log("1.20.1 compatibility mode: native GUI + input + ESP; gameplay hooks not installed");
        }

        // getClassBytes 是 LaunchClassLoader 的取裸字节入口。三处坑:
        //   ① 它可能不是 public —— getMethod 只找 public, 找不到就 NoSuchMethodException
        //      (实测就是这样: getClassBytes 反射失败), 所以必须 getDeclaredMethod + 往父类走。
        //   ② 第二个参数是 needDecrypt。反编译源码 294 行喂给 runTransformers 的是
        //      getClassBytes(name, **true**) —— true 才是解密后的真字节, false 是网易加密的原始字节。
        //   ③ 真找不到就把声明的方法名打出来, 别猜。
        java.lang.reflect.Method getBytes = lcl == null ? null : findGetClassBytes(lcl.getClass());

        for (String target : targets) {
            boolean found = false;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (target.equals(c.getName())) {
                    found = true;
                    if (!skipAll) applyToLoaded(inst, t, lcl, getBytes, c, target);
                    break;
                }
            }
            if (!found) log(target + " NOT LOADED yet (will transform on load)");
        }
    }

    /**
     * 找 LaunchClassLoader 的 getClassBytes。getMethod 只认 public, 所以自己往父类走,
     * 用 getDeclaredMethod + setAccessible。找不到就说清有哪些方法, 不打哑谜。
     */
    private static java.lang.reflect.Method findGetClassBytes(Class<?> cls) {
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Class<?>[] sig : new Class<?>[][]{
                    {String.class, boolean.class}, {String.class}}) {
                try {
                    java.lang.reflect.Method m = k.getDeclaredMethod("getClassBytes", sig);
                    m.setAccessible(true);
                    log("getClassBytes 找到: " + k.getName() + " (参数 " + sig.length + " 个)");
                    return m;
                } catch (Throwable ignored) { }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Class<?> k = cls; k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Method m : k.getDeclaredMethods()) {
                String n = m.getName();
                if (n.toLowerCase().contains("class") || n.toLowerCase().contains("ytes")) {
                    sb.append(k.getSimpleName()).append('.').append(n).append(' ');
                }
            }
        }
        log("getClassBytes 找不到! 相关的已声明方法: " + (sb.length() == 0 ? "(一个都没有)" : sb.toString()));
        return null;
    }

    /**
     * 让一个**已经加载**的目标类立即生效。
     *
     * <p>走 redefineClasses: 拿裸字节 → 跑我们自己的 transformer → 直接替换。
     * 全程不触发任何其它 transformer (关键, 见上面那段注释)。
     *
     * <p>失败不致命也不静默: 只影响这一个类的 hook, 游戏照常跑。
     */
    private static void applyToLoaded(Instrumentation inst, ClassFileTransformer t,
                                      Object lcl, java.lang.reflect.Method getBytes,
                                      Class<?> c, String target) throws Exception {
        if (Ver.is1201()) {
            ModernRuntime.retransform(inst, c);
            return;
        }
        try {
            if (getBytes == null || lcl == null) {
                // 和下面 orig == null 是同一类局面, 也必须兜底而不是放弃:
                //   getBytes == null —— 只是反射没找到 getClassBytes, 但 lcl 还在,
                //                      外科 retransform 照样能跑。
                //   lcl == null      —— 不是 launchwrapper 环境, 摘不到 transformer 链,
                //                      外科函数会自己报"拿不到列表"并返回 false, 损失为零。
                log("redefine 拿不到 getClassBytes 入口 (" + target + ") —— 改用外科式 retransform 兜底");
                retransformSurgical(inst, c, target, lcl);
                return;
            }
            // 参数个数看 findGetClassBytes 找到的是哪个重载。第二个参数是 needDecrypt:
            // true = 解密后的真字节 (runTransformers 用的就是它), false = 网易加密的原始字节。
            Object[] args = getBytes.getParameterTypes().length == 2
                    ? new Object[]{target, Boolean.TRUE}
                    : new Object[]{target};
            byte[] orig = (byte[]) getBytes.invoke(lcl, args);
            if (orig == null) {
                // ★ 拿到 null = 这个类 hook 不上, 但**不是没救**。
                //
                //   局面: 类是"已加载"状态 (攻击时机稍晚必然如此), 而 getClassBytes 按
                //   反混淆名取不到字节 —— HMCL / 原版启动器的 jar 里存的是 auz 这种混淆名,
                //   我们问 net.minecraft.client.renderer.EntityRenderer 只会拿到 null。
                //   redefineClasses 要裸字节, 这条路就此堵死。
                //
                //   唯一能重新生成字节的是 retransformClasses。它会把链上**所有** transformer
                //   重跑一遍, 其中网易反作弊那个 (com.mcshield.obf.pm) 带原生网络解密模块,
                //   重跑一次状态就废了 —— 这是当初弃用它的原因。
                //
                //   所以走外科式: 先把反作弊的 transformer 摘出链外, retransform 完再装回原位。
                //   它自始至终没被重跑, 既有状态不受影响。
                //
                //   在 HMCL / 原版环境下链上根本没有 mcshield, 摘掉 0 个, 自动退化成普通
                //   retransform —— 那条路没有任何争议。
                log("redefine 拿不到裸字节 " + target + " —— 改用外科式 retransform 兜底");
                retransformSurgical(inst, c, target, lcl);
                return;
            }
            // 内部名: 我们的 transformer 用 net/minecraft/... 这种斜杠形式判断
            byte[] neu = t.transform(null, target.replace('.', '/'), c, null, orig);
            if (neu == null || neu == orig) {
                log("redefine 无事可做 " + target + " (transformer 没动它, 裸字节 " + orig.length + " B)");
                return;
            }
            inst.redefineClasses(new java.lang.instrument.ClassDefinition(c, neu));
            log("redefine " + target + " OK (" + orig.length + " -> " + neu.length + " B)");
        } catch (Throwable ex) {
            log("redefine " + target + " FAILED (该类 hook 不生效, 游戏不受影响): " + ex);
        }
    }

    /** 反射拿 LaunchClassLoader.transformers（List<IClassTransformer>）。找不到给 null。 */
    private static java.util.List<Object> launchTransformers(Object lcl) {
        if (lcl == null) return null;
        for (Class<?> k = lcl.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            try {
                java.lang.reflect.Field f = k.getDeclaredField("transformers");
                f.setAccessible(true);
                Object o = f.get(lcl);
                if (o instanceof java.util.List) {
                    @SuppressWarnings("unchecked")
                    java.util.List<Object> lst = (java.util.List<Object>) o;
                    return lst;
                }
            } catch (Throwable ignored) {
                // 继续往父类找
            }
        }
        return null;
    }

    /**
     * 外科式 retransform：**临时把反作弊自己的 transformer 从链上摘掉**再 retransform。
     *
     * <p>为什么必须摘：retransformClasses 会把链上**所有** transformer 重跑一遍，
     * com.mcshield.obf.pm 那个（带原生网络解密模块）被重跑之后就坏了 ——
     * 表现是进服后收到第一个包空指针崩在 jvm.dll。这是当初弃用 retransform 的原因。
     *
     * <p>但 retransform 是唯一能给**已经加载好的类**重新生成字节的路子：
     * redefineClasses 要裸字节，而 getClassBytes 按反混淆名取不到
     * （jar 里存的是 auz 这种名字，我们问 net.minecraft...EntityRenderer 只会拿到 null）。
     *
     * <p>所以：摘掉它 → retransform（这一轮只跑反混淆 + 我们自己的 transformer）→ **立刻装回去**。
     * 装回去之后它的既有状态不受影响，因为它自始至终没被重跑过。
     *
     * <p>真机验证前提：late attach（进服之后再 attach）本身是安全的 ——
     * 引擎日志实测「等了 126 秒…进程存活」。所以这一步只要不把它的 transformer 重跑，
     * 就不该破坏已经建立好的连接。
     */
    private static boolean retransformSurgical(Instrumentation inst, Class<?> c, String target,
                                               Object lcl) {
        java.util.List<Object> list = launchTransformers(lcl);
        if (list == null) {
            log("外科 retransform: 拿不到 LaunchClassLoader.transformers 列表, 放弃 " + target);
            return false;
        }
        java.util.List<Object> removed = new java.util.ArrayList<Object>();
        java.util.List<Integer> where = new java.util.ArrayList<Integer>();
        try {
            synchronized (list) {
                for (int i = list.size() - 1; i >= 0; i--) {
                    Object t0 = list.get(i);
                    String n = t0 == null ? "" : t0.getClass().getName().toLowerCase();
                    if (n.contains("mcshield")) {
                        removed.add(t0);
                        where.add(Integer.valueOf(i));
                        list.remove(i);
                    }
                }
                log("外科 retransform " + target + ": 链上剩 " + list.size()
                        + " 个 transformer, 临时摘掉 " + removed.size() + " 个反作弊的");
                for (Object t0 : list) {
                    log("    保留 " + (t0 == null ? "null" : t0.getClass().getName()));
                }
                inst.retransformClasses(c);
                log("外科 retransform " + target + " OK (该类 hook 已生效)");
                return true;
            }
        } catch (Throwable ex) {
            log("外科 retransform " + target + " FAILED (该类 hook 不生效, 游戏不受影响): " + ex);
            return false;
        } finally {
            synchronized (list) {
                for (int i = removed.size() - 1; i >= 0; i--) {
                    int idx = where.get(i).intValue();
                    if (idx <= list.size()) list.add(idx, removed.get(i));
                    else list.add(removed.get(i));
                }
                if (!removed.isEmpty()) {
                    log("外科 retransform: 已把 " + removed.size() + " 个反作弊 transformer 装回原位");
                }
            }
        }
    }

    /**
     * ★★★ 已废弃, 保留只为说明**为什么不能裸用**。调用点全部改走 {@link #retransformSurgical}。
     */
    @SuppressWarnings("unused")
    private static void retransform(Instrumentation inst, Class<?> c, String target) {
        try {
            inst.retransformClasses(c);
            log("retransform " + target + " OK (兜底路径)");
        } catch (Throwable ex) {
            log("retransform " + target + " FAILED (该类 hook 不生效, 游戏不受影响): " + ex);
        }
    }

    /** 反射: net.minecraft.launchwrapper.Launch.classLoader.addURL(agentJar), 返回 LaunchClassLoader 实例 */
    private static Object addToLaunchClassLoader(java.io.File agentFile) {
        try {
            Class<?> cLaunch = Class.forName("net.minecraft.launchwrapper.Launch");
            java.lang.reflect.Field f = cLaunch.getDeclaredField("classLoader");
            f.setAccessible(true);
            Object lcl = f.get(null);
            if (lcl == null) {
                log("Launch.classLoader is null");
                return null;
            }
            java.lang.reflect.Method addUrl = lcl.getClass().getMethod("addURL", java.net.URL.class);
            addUrl.invoke(lcl, agentFile.toURI().toURL());
            log("addURL OK, classLoader=" + lcl.getClass().getName());

            // ★ 这里曾经加过 predefineAgentClasses: 把 agent jar 里的类全部预定义进
            // LaunchClassLoader, 想绕开 findClass 里的原生检查。
            //
            // 实测结果: **更糟**。注入日志从"跑完全套 retransform"退化成只走到 addURL OK,
            // 也就是死在那些 defineClass 调用里 —— 一次定义 100 多个外来类, 比只加载一个
            // Hook 更容易被反作弊逮住。已删除, 别再往这条路上试。
            return lcl;
        } catch (Throwable t) {
            log("addURL FAILED: " + t);
            return null;
        }
    }

    /** 用 ASM 生成 LiteGui (extends GuiScreen, SRG 方法名), defineClass 进 LaunchClassLoader */
    private static void defineLiteGui(Object lcl) {
        try {
            // ---- 1.20.1: 明确跳过, 而且不是"暂时没做" ----
            // ① 生成出来的那个类 extends net/minecraft/client/gui/GuiScreen —— 1.20.1 里这个类
            //    不存在了 (改叫 gui/screens/Screen, 构造器还要一个 Component), defineClass 当场
            //    LinkageError。它的六个方法名 (func_73863_a/func_73864_a/func_73869_a/func_146281_b/
            //    func_73853_d/func_146274_d) 在 1.20.1 上一个都不存在 —— 1.20.1 是
            //    m_88315_(GuiGraphics,int,int,float)/m_94680_(DDI)Z/... 连描述符都换了;
            //    滚轮那条更彻底: 1.20.1 是 LWJGL3 + GLFW, 没有 org.lwjgl.input.Mouse.getDWheel,
            //    滚轮走 Screen.m_6050_(DDDD)Z。
            // ② 而且**没有地方可以 defineClass**: LiteGui 必须定义进游戏的类加载器 (1.12.2 靠
            //    launchwrapper 的 LaunchClassLoader), 而 1.20.1 的 Forge 走 ModLauncher
            //    (cpw.mods.modlauncher.TransformingClassLoader), 客户端里连 launchwrapper 都没有
            //    (srg.jar 里搜不到 net/minecraft/launchwrapper)。addToLaunchClassLoader 会失败,
            //    lcl 为 null, 下面那句日志就是"LiteGui NOT defined (no LaunchClassLoader)"。
            // ③ 更关键的是: Hook 的绘制层**没有任何 1.20.1 分支** (GlStateManager/GuiScreen/
            //    反射句柄全按 1.12.2 写, Hook.java 里 Ver.is1201() 出现 0 次)。就算硬生成一个
            //    Screen 子类, 打开也只有一块什么都不画的空屏 —— 比"打不开"更难排查。
            // 所以这里跳过并说清原因, 不生成半个用不了的类。见汇报里的已知缺口。
            if (Ver.is1201()) {
                log("LiteGui 跳过 (1.20.1): Screen/滚轮 API 全换 + 没有 LaunchClassLoader 可 defineClass"
                        + " + Hook 绘制层还没有 1.20.1 分支 —— 不是 bug, 是还没移植");
                return;
            }
            byte[] bytes = genLiteGui();
            if (lcl == null) {
                log("LiteGui NOT defined (no LaunchClassLoader)");
                return;
            }
            Method define = ClassLoader.class.getDeclaredMethod(
                    "defineClass", String.class, byte[].class, int.class, int.class);
            define.setAccessible(true);
            define.invoke(lcl, "com.lite.agent.LiteGui", bytes, 0, bytes.length);
            log("LiteGui defined OK");
        } catch (Throwable t) {
            log("LiteGui define FAILED: " + t);
        }
    }

    /**
     * 生成 LiteGui 字节码: drawScreen/mouseClicked/onGuiClosed/doesGuiPauseGame, 均为 SRG 名。
     *
     * <p><b>只对 1.8.9 / 1.12.2 有效。</b> 1.20.1 上这个类**生成出来也没用** (父类 GuiScreen 没了、
     * 六个方法名全换、滚轮 API 从 LWJGL2 换成 GLFW、而且没有 LaunchClassLoader 可以 defineClass),
     * 所以 defineLiteGui 在 1.20.1 上直接跳过 —— 原因写在那里。
     */
    public static byte[] genLiteGui() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                "com/lite/agent/LiteGui", null, "net/minecraft/client/gui/GuiScreen", null);

        // <init>
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/GuiScreen", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // drawScreen (IIF)V -> func_73863_a; 调 Hook.drawGui(II)V
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_73863_a", "(IIF)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "drawGui", "(II)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 4);
        mv.visitEnd();

        // mouseClicked (III)V -> func_73864_a; 调 Hook.guiClicked(III)V
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_73864_a", "(III)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 3);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "guiClicked", "(III)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(3, 4);
        mv.visitEnd();

        // onGuiClosed ()V -> func_146281_b; 调 Hook.guiClosed()V
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_146281_b", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "guiClosed", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();

        // Receive both press and release events while the GUI owns the keyboard.
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_146282_l", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Keyboard", "getEventKey", "()I", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Keyboard", "getEventKeyState", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Keyboard", "isRepeatEvent", "()Z", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "onGuiKeyEvent", "(IZZ)V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/GuiScreen", "func_146282_l", "()V", false);
        mv.visitInsn(Opcodes.RETURN);mv.visitMaxs(3,1);mv.visitEnd();

        // keyTyped (CI)V -> func_73869_a; 调 Hook.onKeyTyped(CI)V (用于绑定按键)
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_73869_a", "(CI)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "onKeyTyped", "(CI)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();

        // doesGuiPauseGame ()Z -> func_73853_d (1.12.2) / func_73868_f (1.8.9); return false
        //
        // **记一笔 (1.20.1 的坑, 这里够不着)**: Ver.guiPauseGameMethod() 在 1.20.1 上返回
        //   "func_73853_d" —— 那是 1.12.2 的名字, 1.20.1 上是 **m_7043_** (isPauseScreen,
        //   Ver 自己的 A1201 表里就写着 func_73853_d -> m_7043_)。也就是说这个方法在 1.20.1
        //   上给的是个**错的**名字。现在够不着它 (defineLiteGui 在 1.20.1 上提前 return 了),
        //   但 Ver 那张表改好之后, 这里会立刻变成"生成一个永远不被调用的方法"——静默失效。
        //   该改的是 Ver.guiPauseGameMethod(), 不在我的改动范围, 汇报里已注明。
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, Ver.guiPauseGameMethod(), "()Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // handleMouseInput ()V -> func_146274_d
        // 滚轮必须在鼠标事件循环里读: 面板现在是在 drawScreen 里读 Mouse.getDWheel(),
        // 而 MC 的事件循环有可能已经把它清零了, 结果就是滚不动。
        // getDWheel() 到底会不会被清零跟 LWJGL 版本有关, 所以 super 前后各读一次,
        // 交给 Hook 挑非零的那个 —— 不用赌它的语义。
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_146274_d", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "getDWheel", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 1);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/client/gui/GuiScreen", "func_146274_d", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/input/Mouse", "getDWheel", "()I", false);
        mv.visitVarInsn(Opcodes.ISTORE, 2);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitVarInsn(Opcodes.ILOAD, 2);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "guiScroll", "(II)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 3);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 测试入口: 对任意类字节码执行同样的 hook 转换 */
    static byte[] hookBytes(String internalName, byte[] bytes) {
        return hook(internalName, bytes);
    }

    /**
     * 把 PlayerControllerMP.func_78757_d ()F (getBlockReachDistance) 的方法体整个换成
     * {@code return Hook.blockReach();}。
     *
     * 1.20.1 上目标是 MultiPlayerGameMode.m_105286_ ()F (getPickRange), 名字由 T.REACH_M 分派;
     * 方法形状一样 (javap -c 实证: {@code GameType.isCreative() ? 5.0f : 4.5f}), 所以做法照搬。
     *
     * 直接换方法体而不是在原字节码里插指令, 是因为原方法有多个返回分支
     * (创造 5.0 / 生存 4.5), 逐个改容易漏; 换掉之后只有一个出口, 也好测。
     * 原方法是 public float 无参, 调用点不会因为这次替换产生栈帧问题。
     */
    static byte[] patchReach(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                if (T.REACH_M.equals(name) && "()F".equals(desc)) {
                    log("transformer: 匹配到 " + T.REACH_M + " ()F (方块/攻击距离), 已换成 Hook.blockReach()");
                    MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                    mv.visitCode();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "blockReach", "()F", false);
                    mv.visitInsn(Opcodes.FRETURN);
                    mv.visitMaxs(1, 1);
                    mv.visitEnd();
                    return null;   // 已经手写完了, 不再让 reader 覆写
                }
                return super.visitMethod(access, name, desc, signature, exceptions);
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 把 EntityPlayerSP.func_71165_d (Ljava/lang/String;)V (sendChatMessage) 的入参就地改写。
     *
     * 只在方法开头插三条指令: {@code msg = Hook.chatSuffix(msg);}
     *   ALOAD 1 / INVOKESTATIC Hook.chatSuffix / ASTORE 1
     *
     * 不动方法体其余部分 —— 原方法只是 new 一个 CPacketChatMessage 再丢进连接队列,
     * 没有任何需要在改写之后重新计算的东西。Hook.chatSuffix 内部对所有异常都兜底
     * 返回原消息, 所以最坏情况就是"没有后缀", 不会把聊天弄坏。
     *
     * 参数表签名是 (Ljava/lang/String;)V, 局部变量 1 就是 message。
     *
     * 1.20.1 上挂的**不是同一个类**: LocalPlayer 里没有 sendChatMessage 了, 出口是
     * {@code ClientPacketListener.m_246175_(Ljava/lang/String;)V} (= sendChat)。
     * 方法形状一样 (public void, 唯一参数就是消息, 槽位也是 1), 所以这个补丁本身一行没改,
     * 换的只是 transform 里的类名和 T.CHAT_SEND_M。
     */
    static byte[] patchChatSuffix(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (T.CHAT_SEND_M.equals(name) && T.CHAT_SEND_MD.equals(desc)) {
                    log("transformer: 匹配到 " + T.CHAT_SEND_M + " (聊天发送), 已插入 chatSuffix");
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "chatSuffix",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 把 TextComponentString 的构造器入参就地改写: {@code text = Hook.nameProtect(text);}
     *
     * 构造器签名是 {@code <init>(Ljava/lang/String;)V}, 局部变量 0 是 this, 1 是 text。
     * 插三条指令就够, 不动构造器其余部分。
     *
     * 注意构造器不在 SRG 映射表里 (它们始终叫 <init>), 所以匹配的是名字 + 描述符,
     * 描述符写错的话这里会静默不生效 —— TransformCheck 里有对应的加载调用测试兜底。
     */
    static byte[] patchNameProtect(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if ("<init>".equals(name) && "(Ljava/lang/String;)V".equals(desc)) {
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "nameProtect",
                                    "(Ljava/lang/String;)Ljava/lang/String;", false);
                            super.visitVarInsn(Opcodes.ASTORE, 1);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 静默转身的写入窗口 —— 挂在 EntityPlayerSP.onUpdate (func_70071_h_) 上。
     *
     * 为什么必须在这里: 一帧里 rotationYaw 被两处读, 而且都在**同一个方法**里。
     *
     *     EntityPlayerSP.onUpdate()
     *     ├── 26:  super.onUpdate()          <- 走路: WASD 方向是相对 rotationYaw 算的
     *     ├── 139: 比较 rotationYaw 与 lastReportedYaw (判断"转没转")
     *     ├── 289/343/415: new CPacketPlayer.*Rotation(rotationYaw, rotationPitch)
     *     ├── 303/357/429: sendPacket         <- 发包
     *     └── 516: lastReportedYaw = rotationYaw
     *
     * 之前是在 onRender 里写、下一帧 onFrame 还原 —— 也就是 rotationYaw 有**整整一帧**是瞄准角。
     * 而 runTick() (走路+发包) 正好落在那个窗口里, 于是"走路方向跟着瞄准走"。
     *
     * Natural Aim 与 Scaffold 的原版运动模式会在方法入口先安装本 tick 姿势，让
     * super.onUpdate()、冲刺/身体状态和随后的移动包消费同一组角；方法返回前再还原自由相机。
     * 其他只影响出站包的旋转仍可在 super.onUpdate() 之后处理。
     *
     * <h3>1.20.1</h3>
     * 方法换成 {@code LocalPlayer.tick() = m_8119_ ()V}。javap -c 实证那个方法第 20 条指令是
     * {@code invokespecial AbstractClientPlayer.m_8119_:()V} (= super.tick()), 名字与自身相同
     * —— 和 1.12.2 的形状一致, 所以下面这套"super 之后写 / RETURN 前还原"的逻辑原样可用。
     * 注意 m_8119_ 开头有一条 {@code if (!level.m_151577_(x,z)) return} 的提前返回:
     * 它落在 super.tick() 之前, 我们的 onPlayerTickEnd 会被插在那里 —— 那时 tickSaved 是 false
     * (Hook.onPlayerTickEnd 第一件事就是查它并清零), 所以是空操作, 不会把旋转角写坏。
     */
    /**
     * Put the queued KillAura action in the same client-tick phase as vanilla
     * clicks.  The renderer only certifies that the displayed model reached
     * the requested pose; it must never mutate attack/movement state itself.
     *
     * <p>Legacy: call immediately before World.updateEntities(). Vanilla's own
     * clickMouse has already run and entity movement has not advanced.
     * Modern: call immediately after handleKeybinds(), before ClientLevel tick.
     */
    static byte[] patchClientAttackPhase(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final int[] inserted = {0};
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if(!Ver.is1201()&&"func_147116_af".equals(name)&&"()V".equals(desc)) {
                    return new MethodVisitor(ASM_API,mv) {
                        @Override public void visitMethodInsn(int opcode,String owner,String called,String descriptor,boolean itf) {
                            super.visitMethodInsn(opcode,owner,called,descriptor,itf);
                            if("func_78764_a".equals(called)&&owner.equals("net/minecraft/client/multiplayer/PlayerControllerMP"))
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/Hook","onManualAttack","()V",false);
                        }
                    };
                }
                if (!T.CLIENT_TICK_M.equals(name) || !"()V".equals(desc)) return mv;
                return new MethodVisitor(ASM_API, mv) {
                    private void emitAttackPhase() {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/lite/agent/Hook", "onClientTickAttack", "()V", false);
                        inserted[0]++;
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        // Legacy clicks precede World.updateEntities(), where
                        // EntityPlayerSP performs its next movement. A completed
                        // render only queues Scaffold; consume it at this native
                        // interaction phase, before another position is produced.
                        if (!Ver.is1201() && "func_72939_s".equals(mName) && "()V".equals(mDesc))
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "onClientTickPlacement", "()V", false);
                        boolean boundary = T.ATTACK_PHASE_M.equals(mName) && "()V".equals(mDesc);
                        if (boundary && !T.ATTACK_PHASE_AFTER) emitAttackPhase();
                        super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                        if (boundary && T.ATTACK_PHASE_AFTER) emitAttackPhase();
                    }
                };
            }
        };
        cr.accept(cv, 0);
        log("transformer: " + T.CLIENT_TICK_M + " 客户端 tick 攻击相位钩子=" + inserted[0]);
        return cw.toByteArray();
    }

    /** Decide Edge sneak where vanilla checks it, after web/piston adjustments
     * and before its own support-clipping loops. All movement locals and the
     * native collision implementation remain unchanged. */
    static byte[] patchScaffoldEdge(byte[] bytes) {
        if (Ver.is1201()) return bytes;
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        final int[] hooks = {0};
        cr.accept(new ClassVisitor(ASM_API, cw) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!"func_70091_d".equals(name)) return mv;
                final int x = "(DDD)V".equals(desc) ? 1
                        : "(Lnet/minecraft/entity/MoverType;DDD)V".equals(desc) ? 2 : -1;
                if (x < 0) return mv;
                return new MethodVisitor(ASM_API, mv) {
                    boolean inserted;
                    @Override public void visitMethodInsn(int opcode, String owner, String method,
                            String type, boolean itf) {
                        super.visitMethodInsn(opcode, owner, method, type, itf);
                        if (!inserted && opcode == Opcodes.INVOKEVIRTUAL
                                && "func_70093_af".equals(method) && "()Z".equals(type)) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.DLOAD, x);
                            super.visitVarInsn(Opcodes.DLOAD, x+2);
                            super.visitVarInsn(Opcodes.DLOAD, x+4);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook",
                                    "scaffoldEdgeSneaking", "(ZLjava/lang/Object;DDD)Z", false);
                            inserted = true; hooks[0]++;
                        }
                    }
                };
            }
        }, 0);
        if (hooks[0] != 1) throw new IllegalStateException("Scaffold Edge: expected one native sneak check, got " + hooks[0]);
        return cw.toByteArray();
    }

    static byte[] patchRotation(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!Ver.is1201() && "func_70626_be".equals(name) && "()V".equals(desc)) {
                    // updateEntityActionState smooths the first-person arm using
                    // rotationYaw/Pitch. Give only these reads the free camera;
                    // keep the original recurrence and all movement work intact.
                    return new MethodVisitor(ASM_API, mv) {
                        @Override public void visitFieldInsn(int opcode, String owner,
                                                             String field, String type) {
                            super.visitFieldInsn(opcode, owner, field, type);
                            if (opcode == Opcodes.GETFIELD && "F".equals(type)) {
                                String hook = "field_70177_z".equals(field) ? "scaffoldArmCameraYaw"
                                        : "field_70125_A".equals(field) ? "scaffoldArmCameraPitch" : null;
                                if (hook != null) super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", hook, "(F)F", false);
                            }
                        }
                    };
                }
                if (!Ver.is1201() && "func_70031_b".equals(name) && "(Z)V".equals(desc)) {
                    return new MethodVisitor(ASM_API, mv) {
                        @Override public void visitCode() {
                            super.visitCode();
                            // Block activation before vanilla updates sprint
                            // attributes, jump impulse and its expiry timer.
                            super.visitVarInsn(Opcodes.ILOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook",
                                    "scaffoldSprintRequested", "(Z)Z", false);
                            super.visitVarInsn(Opcodes.ISTORE, 1);
                        }
                    };
                }
                if (!Ver.is1201() && T.LIVING_UPDATE_M.equals(name) && "()V".equals(desc)) {
                    return new MethodVisitor(ASM_API, mv) {
                        private boolean sprintBinding;
                        @Override public void visitFieldInsn(int opcode, String owner, String field, String type) {
                            super.visitFieldInsn(opcode, owner, field, type);
                            sprintBinding = opcode == Opcodes.GETFIELD && "field_151444_V".equals(field);
                        }
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                            super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                            if (sprintBinding && "func_151470_d".equals(mName) && "()Z".equals(mDesc)) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook",
                                        "sprintKeyRequested", "(Z)Z", false);
                            }
                            sprintBinding = false;
                            if (opcode == Opcodes.INVOKEVIRTUAL && T.INPUT_UPDATE_M.equals(mName)
                                    && "()V".equals(mDesc)) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "onScaffoldMovementInputUpdated", "()V", false);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "onSprintInputUpdated", "()V", false);
                            }
                        }
                    };
                }
                if (!T.TICK_M.equals(name) || !"()V".equals(desc)) return mv;
                log("transformer: 匹配到 " + T.TICK_M + " ()V (每 tick), 已插入静默转身钩子");
                final boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                return new MethodVisitor(ASM_API, mv) {
                    private final org.objectweb.asm.Label begin = new org.objectweb.asm.Label();
                    private final org.objectweb.asm.Label end = new org.objectweb.asm.Label();
                    private final org.objectweb.asm.Label cleanup = new org.objectweb.asm.Label();
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        super.visitLabel(begin);
                        // Natural Aim / Scaffold have to exist while vanilla
                        // computes this tick's movement, sprint and body state.
                        // Applying them only after super would pair a position
                        // produced from the camera yaw with a different packet
                        // yaw -- a state vanilla never emits.
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/lite/agent/Hook", "onPlayerTickStart", "()V", false);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                        // 紧跟 super.onUpdate()/super.tick() 之后 —— 走位已经算完, 接下来全是构造/发包
                        if (opcode == Opcodes.INVOKESPECIAL && T.TICK_M.equals(mName) && "()V".equals(mDesc)) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "onPlayerMoved", "()V", false);
                        }
                    }

                    @Override
                    public void visitMaxs(int maxStack, int maxLocals) {
                        // Append after existing handlers so vanilla catches keep their priority.
                        super.visitLabel(end);
                        super.visitTryCatchBlock(begin, end, cleanup, "java/lang/Throwable");
                        super.visitLabel(cleanup);
                        super.visitFrame(Opcodes.F_FULL, isStatic ? 0 : 1,
                                isStatic ? new Object[0] : new Object[]{cr.getClassName()},
                                1, new Object[]{"java/lang/Throwable"});
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook",
                                "onPlayerTickAbort", "()V", false);
                        super.visitInsn(Opcodes.ATHROW);
                        super.visitMaxs(maxStack, maxLocals);
                    }

                    @Override
                    public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            // 包都发完了, 把旋转角还回去 —— 别让它漏到走路/相机那两处
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "onPlayerTickEnd", "()V", false);
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * EntityRenderer.getMouseOver (func_78473_a) 里的**实体距离**覆盖。
     *
     * <h3>为什么不能像 patchReach 那样换方法体</h3>
     *
     * 方块距离那边直接换了 {@code func_78757_d} 的整个方法体, 因为那个方法就是
     * "返回一个常数"。getMouseOver 不行 —— 它有一百多条指令, 包含射线求交、遍历实体、
     * 各种边界判断, 换掉等于重写整个拾取逻辑。
     *
     * <h3>所以只改一个数</h3>
     *
     * 原方法里有一个局部 double 装"实体拾取距离"(槽位 8), 值来自
     * {@code playerController.getBlockReachDistance()} —— 而那个方法**已经被
     * patchReach 换成 Hook.blockReach() 了**, 也就是方块距离。原版本来就用同一个值
     * 当实体距离, 所以想单独放大实体距离, 只能在这里拦一刀。
     *
     * 做法: 在 {@code DSTORE 8} 的**紧跟其后**插
     * <pre>
     *   INVOKESTATIC Hook.entityReach ()F
     *   F2D
     *   DSTORE 8
     * </pre>
     * 即"算完原版的值, 立刻换成我们的值"。
     *
     * <h3>顺序是有讲究的 —— 插错位置等于没插</h3>
     *
     * 必须插在 DSTORE 8 **之后**。插在之前的话, 原版那句 DSTORE 8 会把我们的值
     * 覆盖掉, 表现就是"Reach 开到 6 但打不到 6 格" —— 而且没有任何报错。
     * {@code TransformCheck.checkEntityReachPatch} 就是钉这个的: 它数
     * {@code entityReach} 出现次数必须恰好 1 次, 且后面两条正好是 F2D + DSTORE 8,
     * 同时前面必须已经有过 DSTORE 8。
     *
     * <h3>槽位 8 是怎么来的</h3>
     *
     * 1.12.2 的 func_78473_a 是 (F)Lnet/minecraft/util/math/RayTraceResult;,
     * 静态槽位占用: this(0) + float partialTicks(1) +
     * {@code double d0}(2,3) + {@code double d1}(4,5) + {@code double d2}(6,7) +
     * {@code double d3}(8,9)。所以实体距离那个 double 落在 8。
     * 这个数**没变过** —— 1.8.9 和 1.12.2 的指令布局一致。
     *
     * <h3>1.20.1 完全不同 —— 走另一条路</h3>
     * 1.20.1 的 GameRenderer.pick(float) 是重写过的 (javap -c 实证): 局部槽位、指令条数、
     * 连"实体距离"存在的形式都变了 —— 它不是某个槽里的 double, 而是
     * {@code ProjectileUtil.getEntityHitResult(entity, 眼位, 射线末端, 搜索盒, 过滤, d1*d1)}
     * 的**最后一个实参**, 而且是**平方值**。按槽位/指令下标定位在 1.20.1 上必然插错,
     * 而且是静默地插错 —— 所以那条路走 {@link #patchEntityReach1201}。
     */
    static byte[] patchEntityReach(byte[] bytes) {
        if (Ver.is1201()) return patchEntityReach1201(bytes);
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!T.PICK_OLD.equals(name) || !desc.startsWith("(F)")) {
                    return mv;
                }
                log("transformer: 匹配到 getMouseOver (func_78473_a F), 已插入实体距离覆盖");
                return new MethodVisitor(ASM_API, mv) {
                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        super.visitVarInsn(opcode, var);
                        // **先让原版的 DSTORE 8 落地, 再覆盖它。**
                        if (opcode == Opcodes.DSTORE && var == 8) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "entityReach", "()F", false);
                            super.visitInsn(Opcodes.F2D);
                            super.visitVarInsn(Opcodes.DSTORE, 8);
                        }
                    }
                };
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 1.20.1 的实体距离覆盖 —— 插在 {@code GameRenderer.pick(float) = m_109087_} 里。
     *
     * <h3>为什么不能照抄 1.12.2 的 "DSTORE 8"</h3>
     * 1.20.1 那个方法体完全是另一套 (见 patchEntityReach 的注释), "槽位 8" 在那里面是两个
     * 不相干的值。所以这里**按"调用了什么"定位, 不按槽位也不按指令下标** —— 后者在 1.20.1 上
     * 不是"可能错", 是必然错, 而且错得没有任何提示。
     *
     * <h3>两处插入点, 都是现认出来的</h3>
     * <ol>
     * <li><b>实体接受阈值</b> (在 {@code ProjectileUtil.m_37287_}(...) 的调用前): 那条调用的描述符
     *     最后一位是 {@code D}, 字节码保证它前面栈顶一定压着一个 double (原版的
     *     "实体搜索距离平方")。往那儿插
     *     <pre>Hook.entityReach()F / F2D / DUP2 / DMUL / Math.max(DD)D</pre>
     *     即 {@code max(原版值, 我们的²)} —— 栈净变化 0, 不需要知道它在哪个槽。
     *     用 max 而不是直接覆盖: 原版创造模式给的是 6.0 的平方, 直接覆盖会把创造模式的
     *     攻击距离压回 4.5 (Reach 没开时 entityReach() 就是 4.5)。</li>
     * <li><b>实体搜索体积</b> (从 {@code Entity.getViewVector} 之后到上面那条调用之间):
     *     射线末端和搜索盒都是按 {@code d0}(= {@code gameMode.getPickRange()}, 已被 patchReach
     *     换成 4.5) 算的。只改阈值不改这里, 超过 4.5+1 格的实体根本进不了候选集 ——
     *     就是"Reach 开到 6 还是打不到 6 格"。所以把这段窗口里对 d0 的每一条 {@code DLOAD}
     *     换成 {@code max(d0, Hook.entityReach())}。<b>d0 的槽位也是现认的</b>: 从
     *     {@code MultiPlayerGameMode.m_105286_()F} 之后第一条 {@code DSTORE} 得到。</li>
     * </ol>
     * <b>十字线那条 {@code Entity.m_19907_}(pick) 调用和它前面那条 d0 在窗口之外, 一个字都不动</b>
     * —— 那是"Reach 只影响攻击、不影响放方块"的底线。
     *
     * <h3>认不出来就原样放行</h3>
     * 只要 pick 本体或那条 getEntityHitResult 调用没找到 (方法改名/被别的核心 mod 重写了),
     * 就返回**原始字节码**并打日志 —— 宁可"1.20.1 上实体距离没生效", 也不能往 pick 里插一条
     * 认错了的指令: 那会 VerifyError 把整个 GameRenderer 弄挂, 游戏直接进不去。
     *
     * <p>证据 (javap -c/-s 于 client-1.20.1-...-srg.jar 的 net.minecraft.client.renderer.GameRenderer):
     * {@code public void m_109087_(float) descriptor (F)V}; 方法体里
     * {@code 54: invokevirtual MultiPlayerGameMode.m_105286_:()F} → {@code 57: f2d} → {@code 58: dstore_3};
     * {@code 64: dload_3} → {@code 67: invokevirtual Entity.m_19907_:(DFZ)LHitResult;} (十字线, 不动);
     * {@code 164: invokevirtual Entity.m_20252_:(F)LVec3;} (窗口起点);
     * {@code 176/183/190/206: dload_3} (射线末端与搜索盒, 恰好 4 条);
     * {@code 233: dload 8} → {@code 235: invokestatic ProjectileUtil.m_37287_}(...D)。
     */
    static byte[] patchEntityReach1201(byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        // stat: [0]=d0 槽位 [1]=搜索体积里替换了几条 [2]=阈值替换了几处
        final int[] stat = new int[]{-1, 0, 0};
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!T.PICK_1201.equals(name) || !desc.startsWith("(F)")) return mv;
                final MethodVisitor out = mv;      // 原始委托 —— 我们自己的指令必须直接写给它, 不能走 this (会递归)
                return new MethodVisitor(ASM_API, mv) {
                    private boolean awaitD0 = false;    // 刚看到 getPickRange(), 下一条 DSTORE 就是 d0
                    private boolean inVolume = false;   // 在"十字线射线之后 / 实体判定之前"这一段里

                    @Override
                    public void visitVarInsn(int opcode, int var) {
                        if (opcode == Opcodes.DSTORE && awaitD0) {
                            stat[0] = var;
                            awaitD0 = false;
                        }
                        if (inVolume && opcode == Opcodes.DLOAD && stat[0] >= 0 && var == stat[0]) {
                            // 搜索体积: max(原版 d0, Hook.entityReach()) —— 原版值原样压回去, 净栈变化 0
                            stat[1]++;
                            out.visitVarInsn(Opcodes.DLOAD, var);
                            emitReachLinear(out);
                            return;
                        }
                        super.visitVarInsn(opcode, var);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean itf) {
                        if (opcode == Opcodes.INVOKEVIRTUAL && T.PICK_RANGE_1201.equals(mName)
                                && "()F".equals(mDesc)) {
                            awaitD0 = true;                  // d0 马上会被 DSTORE 到某个槽
                        } else if (opcode == Opcodes.INVOKEVIRTUAL && T.PICK_VIEWVEC_1201.equals(mName)) {
                            // 窗口起点 = "取实体视线方向"。这一段到"实体判定"为止, 里面每一次
                            // 读 d0 都是实体射线/搜索盒的尺度, 都要跟着实体距离走。
                            // 起点为什么不放在十字线那条 pick 调用上: 它后面紧跟的
                            // `dload_3; dstore 8` (真字节码 86/87, d1 = d0) 只是给阈值算初值,
                            // 而阈值最终由下面那条调用点上的 max 决定 —— 改它属于多余动作,
                            // 少动一条指令就少一分插错的机会。实测真字节码这段窗口里恰好 4 条 d0。
                            inVolume = true;
                        }
                        boolean isEntityHit = opcode == Opcodes.INVOKESTATIC && T.PICK_ENTITY_1201.equals(mName)
                                && mDesc.endsWith(")Lnet/minecraft/world/phys/EntityHitResult;");
                        if (isEntityHit) {
                            inVolume = false;
                            stat[2]++;
                            // 这条调用之前, 栈顶就是原版的"实体搜索距离平方"。先算我们的 (平方),
                            // 再和它取大 —— 净栈变化 0, 而且完全不关心它在哪个局部槽。
                            emitReachSquared(out);
                        }
                        super.visitMethodInsn(opcode, owner, mName, mDesc, itf);
                    }
                };
            }
        };
        cr.accept(cv, 0);

        if (stat[2] == 0) {
            // 一条都没插上 → 原样放行 (连上面可能已经改过的搜索体积一起丢掉, 不做半个补丁)
            log("patchEntityReach(1.20.1): " + T.RENDERER + "." + T.PICK_1201 + " 里没找到 "
                    + T.PICK_ENTITY_1201 + " (ProjectileUtil.getEntityHitResult) —— 插入点认不出来, "
                    + "原样放行, 1.20.1 上实体距离未覆盖"
                    + (stat[1] > 0 ? " (已丢弃 " + stat[1] + " 处搜索体积改动)" : ""));
            return bytes;
        }
        log("patchEntityReach(1.20.1): 实体距离覆盖已插入 (阈值 " + stat[2] + " 处, 搜索体积 " + stat[1]
                + " 处, d0 槽位 " + stat[0] + ")"
                + (stat[1] == 0 ? " —— 警告: 搜索体积没认出来, 实体距离仍然受 4.5+1 格的候选集限制" : ""));
        return cw.toByteArray();
    }

    /**
     * 在栈顶已有的 double (原版 d0, 线性距离) 之上取 {@code max(原版, Hook.entityReach())}。
     * 净栈变化 0 —— 用在"把某条 DLOAD 换成我们的值"的地方。
     */
    private static void emitReachLinear(MethodVisitor v) {
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "entityReach", "()F", false);
        v.visitInsn(Opcodes.F2D);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
    }

    /**
     * 在栈顶已有的 double (原版"实体搜索距离平方") 之上取
     * {@code max(原版, Hook.entityReach()²)}。净栈变化 0 —— 用在 getEntityHitResult 的调用点,
     * 那里描述符最后一位是 D, 所以栈顶必然有一个 double 可吃 (由字节码合法性保证, 不用猜槽位)。
     */
    private static void emitReachSquared(MethodVisitor v) {
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "entityReach", "()F", false);
        v.visitInsn(Opcodes.F2D);
        v.visitInsn(Opcodes.DUP2);
        v.visitInsn(Opcodes.DMUL);
        v.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(DD)D", false);
    }

    private static byte[] hook(String className, byte[] bytes) {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(ASM_API, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (Ver.is1201() && T.GUI_M.equals(name) && T.GUI_MD.equals(desc)) {
                    log("transformer: 匹配到 1.20.1 Gui." + T.GUI_M + T.GUI_MD + ", 已插入 GuiGraphics overlay");
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // this(0), GuiGraphics(1), partialTicks(2)
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitVarInsn(Opcodes.FLOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "drawGui1201",
                                        "(Ljava/lang/Object;F)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                if (T.RENDER_M.equals(name) && T.RENDER_MD.equals(desc)) {
                    log("transformer: 匹配到每帧渲染 " + T.RENDER_M + T.RENDER_MD + ", 已插入 onFrame/onRender");
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            // 参数: this(0) partialTicks float(1) nanoTime long(2,3) [1.20.1: boolean(4)]
                            // —— partialTicks 两版都在槽 1, 所以这一句两版相同
                            super.visitVarInsn(Opcodes.FLOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "onFrame", "(F)V", false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                super.visitVarInsn(Opcodes.FLOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "onRender", "(F)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }

                // renderWorld / renderLevel 的返回点 —— 世界坐标的东西 (ESP / 存储透视 /
                // 方块描边 / 追踪线) 必须在这里画。
                //
                // onRender 挂在 updateCameraAndRender/render 的返回点, 那时投影已经切成正交
                // (实测 m00=0.0047 = 2/屏幕宽), 世界坐标的框会被压成一个点 ——
                // 症状就是"绘制被调用了、不报错、屏幕上什么都没有"。
                // 1.12.2 那条路径恰好还留着透视矩阵, 所以同一份代码只在 1.8.9 上失效。
                //
                // 1.12.2 func_175068_a (IFJ)V: this(0) pass int(1) partialTicks float(2) finishTimeNano long(3,4)
                // 1.20.1 m_109089_ (FJL PoseStack;)V: this(0) partialTicks float(1) finishTimeNano long(2,3) poseStack(4)
                //   —— **1.20.1 没有 pass 参数** (一帧只调一次 renderLevel), 压常量 0。
                //   Hook.onWorldRender 里 `if (pass != 2) return;` 的守卫会因此跳过世界绘制 ——
                //   那是 Hook 自己的判断 (1.8.9 实测主 pass==2), 要移植 Hook 的人才该动它, 这里不假装。
                if (T.WORLD_M.equals(name) && T.WORLD_MD.equals(desc)) {
                    log("transformer: 匹配到 " + T.WORLD_M + T.WORLD_MD + " (世界渲染), 已插入 onWorldRender");
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (T.WORLD_PASS < 0) {
                                super.visitInsn(Opcodes.ICONST_0);
                            } else {
                                super.visitVarInsn(Opcodes.ILOAD, T.WORLD_PASS);
                            }
                            super.visitVarInsn(Opcodes.FLOAD, T.WORLD_TICKS);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "com/lite/agent/Hook", "onWorldRenderStart", "(IF)V", false);
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                if (T.WORLD_PASS < 0) {
                                    super.visitInsn(Opcodes.ICONST_0);   // 1.20.1: 没有 pass 这个参数
                                } else {
                                    super.visitVarInsn(Opcodes.ILOAD, T.WORLD_PASS);
                                }
                                super.visitVarInsn(Opcodes.FLOAD, T.WORLD_TICKS);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "onWorldRender", "(IF)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }

                // setupCameraTransform / Camera.setup 的返回点 —— 静默视角能"只动玩偶不动相机"
                // 的关键就靠这个钩子。
                //
                // 一帧里 rotationPitch 被读了三次, 但**时间点不同**:
                //     getMouseOver/pick        <- 准星
                //     setupCameraTransform     <- 相机 (内部调 orientCamera)
                //     renderEntities           <- 模型的头部俯仰
                // onRender 是在每帧渲染的 RETURN 前触发的, 那已经是**三件事全做完之后**了 ——
                // 所以在 onRender 里写俯仰只能进下一 tick 的包, 玩偶这一帧早就画完了。
                // 这就是"别的端能做到低头不改视角, 我们做不到"的真正原因。
                //
                // 相机一算完, 相机矩阵就定死了, 而模型还没开始渲染 —— 在这里写俯仰,
                // 影响到的**只有玩偶**。
                //
                // 1.12.2/1.8.9: EntityRenderer.setupCameraTransform (FI)V
                // 1.20.1:        **方法没了**, 搬到 net.minecraft.client.Camera.setup
                //                (LBlockGetter;LEntity;ZZF)V —— 换了类, 由 T.CAMERA 分派
                if (T.CAMERA_M.equals(name) && T.CAMERA_MD.equals(desc)) {
                    log("transformer: 匹配到 " + T.CAMERA_M + T.CAMERA_MD + " (" + T.CAMERA + "), 已插入 onCameraReady");
                    return new MethodVisitor(ASM_API, mv) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN) {
                                // Carry the exact camera sample into Hook.  Legacy
                                // setupCameraTransform(FI)V stores partialTicks in
                                // slot 1 and pass in slot 2; Camera.setup on 1.20.1
                                // has partialTicks in slot 5 and no pass.
                                if (T.V1201) {
                                    super.visitVarInsn(Opcodes.FLOAD, 5);
                                    super.visitInsn(Opcodes.ICONST_0);
                                } else {
                                    super.visitVarInsn(Opcodes.FLOAD, 1);
                                    super.visitVarInsn(Opcodes.ILOAD, 2);
                                }
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "com/lite/agent/Hook", "onCameraReady", "(FI)V", false);
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
                return mv;
            }
        };
        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    /**
     * 把这次实际使用的一整套类名/方法名写进日志。
     *
     * <p>为什么值得单独打: 这个 transformer 的失败方式是**静默的** —— 名字对不上就一条都不匹配,
     * 不抛异常、不报错, 用户看到的只是"功能全没有"。1.20.1 上一整列名字都换过了 (类名、方法名、
     * 连"哪个类"都换), 所以出问题时唯一能立刻定位的就是这份"我到底在找什么"的清单。
     */
    static void logTargets() {
        log("分派 renderer   = " + T.RENDERER + "  [" + T.RENDER_M + T.RENDER_MD
                + " / " + T.WORLD_M + T.WORLD_MD + "]");
        log("分派 camera     = " + T.CAMERA + "  [" + T.CAMERA_M + T.CAMERA_MD + "]");
        log("分派 clientTick = " + T.MINECRAFT + "  [" + T.CLIENT_TICK_M
                + "()V / " + (T.ATTACK_PHASE_AFTER ? "after " : "before ") + T.ATTACK_PHASE_M + "()V]");
        log("分派 controller = " + T.CONTROLLER + "  [" + T.REACH_M + "()F]");
        log("分派 player     = " + T.PLAYER + "  [" + T.TICK_M + "()V]");
        log("分派 chatSend   = " + T.CHAT_SEND + "  [" + T.CHAT_SEND_M + T.CHAT_SEND_MD + "]"
                + (Ver.is1201() ? "  —— 插在方法入口, 而签名在方法体内部才做, 故服务端验签能过"
                                : ""));
        log("分派 chatText   = " + T.CHAT_TEXT.replace('/', '.') + "  [<init>(Ljava/lang/String;)V]"
                + (Ver.is1201() ? "  —— 纯本地显示层, 不碰签名包" : ""));
        log("分派 entityReach= " + T.RENDERER + "." + (Ver.is1201() ? T.PICK_1201 : T.PICK_OLD)
                + "  (" + (Ver.is1201() ? "按方法调用模式定位插入点" : "按 DSTORE 8 定位插入点") + ")");
    }

    /** 诊断日志: 写入 %TEMP%\liteclient-agent.log (超 2MB 轮转, 防刷屏) */
    static void log(String msg) {
        try {
            java.io.File f = new java.io.File(System.getProperty("java.io.tmpdir"), "liteclient-agent.log");
            if (f.length() > 2 * 1024 * 1024) {
                java.io.File old = new java.io.File(System.getProperty("java.io.tmpdir"), "liteclient-agent.old.log");
                old.delete();
                f.renameTo(old);
            }
            java.io.FileWriter w = new java.io.FileWriter(f, true);
            w.write(java.time.Instant.now() + " [agent] " + msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }
}
