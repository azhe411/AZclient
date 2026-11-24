package com.lite.agent;

/**
 * 字节码自检:
 *  1) hook 转换确实把 Hook.onFrame/onRender 插进了 func_181560_a
 *  2) genLiteGui 生成的 GuiScreen 子类方法齐全 —— 尤其是 handleMouseInput。
 *     漏了它滚轮就彻底没反应, 而这种缺失在源码里完全看不出来 (方法名是 SRG 字符串)。
 */
public class TransformCheck {

    // 假的目标类: 模拟 EntityRenderer 上被挂钩子的那两个方法
    public static class FakeRenderer extends FakeRendererBase {
        public FakeMovementInput movementInput = new FakeMovementInput();
        public void func_181560_a(float pt, long nano) {
            System.out.println("fake render");
        }

        // setupCameraTransform —— 静默视角"只动玩偶不动相机"全靠这个钩子
        private void func_78479_a(float pt, int pass) {
            System.out.println("fake camera");
        }

        // 模拟 EntityPlayerSP.onUpdate: super.onUpdate() 之后才是构造/发包
        public void func_70071_h_() {
            super.func_70071_h_();
            System.out.println("fake tick");
            if (System.nanoTime() > 0) return;
        }

        public void func_70031_b(boolean sprint) { System.out.println(sprint); }

        public void func_70636_d() {
            movementInput.func_78898_a();
            System.out.println("fake living update");
        }
    }

    public static class FakeMovementInput {
        public void func_78898_a() { }
    }

    // 假的父类, 让 super.onUpdate() 编译成 INVOKESPECIAL func_70071_h_
    public static class FakeRendererBase {
        public void func_70071_h_() {
        }
    }

    public static void main(String[] args) throws Exception {
        boolean ok = checkHook() && checkClientAttackPhaseHook() && checkRotationHook() && checkGui() && checkGlStateManager() && checkProjection() && checkReach()
                && checkEntityReachPatch()
                && checkEntityReachPatch1201()
                && check1201RealJar()
                && checkChatSuffix() && checkNameProtect();
        System.out.println(ok ? "CHECK PASSED" : "CHECK FAILED");
        if (!ok) System.exit(1);
    }

    // 假的 PlayerControllerMP: 只要那个 SRG 方法名和描述符对得上就行
    public static class FakeController {
        public float func_78757_d() {
            return 4.5F;   // 原版生存距离
        }
    }

    public static class FakeWorldClient {
        public void func_72939_s() { System.out.println("fake entity movement"); }
        public void func_72835_b() { System.out.println("fake world tick"); }
    }

    public static class FakeMinecraft {
        public FakeWorldClient world = new FakeWorldClient();
        public void func_71407_l() {
            System.out.println("fake input handled");
            world.func_72939_s();
            world.func_72835_b();
            System.out.println("fake tick complete");
        }
    }

    /** Distinguish entity movement from the later block-world tick. */
    static boolean checkClientAttackPhaseHook() throws Exception {
        java.io.InputStream is = FakeMinecraft.class.getResourceAsStream("TransformCheck$FakeMinecraft.class");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        final java.util.List<String> calls = new java.util.ArrayList<String>();
        byte[] out = LiteAgent.patchClientAttackPhase(bos.toByteArray());
        new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!"func_71407_l".equals(name) || !"()V".equals(desc)) return null;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        calls.add(owner + "." + nm + d);
                    }
                };
            }
        }, 0);
        int hook = calls.indexOf("com/lite/agent/Hook.onClientTickAttack()V");
        int world = -1, entities = -1;
        for (int i = 0; i < calls.size(); i++) {
            if (calls.get(i).contains(".func_72835_b()V")) world = i;
            if (calls.get(i).contains(".func_72939_s()V")) entities = i;
        }
        boolean once = hook >= 0 && calls.lastIndexOf("com/lite/agent/Hook.onClientTickAttack()V") == hook;
        boolean ordered = once && entities == hook + 1 && world > entities;
        System.out.println("[attack-phase] hook=" + hook + " entities=" + entities + " worldTick=" + world
                + " beforeEntityMovement=" + ordered);
        return ordered;
    }

    /**
     * 距离补丁的加载测试。**两个补丁都要测, 而且要测出"互不影响"** —— 这正是用户报的点。
     *
     * 用户要求: "reach 要求只影响攻击, 不影响放方块"。
     *
     *   1) func_78757_d (方块距离) 必须**恒定 4.5**, 无论 Reach 开多大。
     *      它同时喂方块射线 (放/挖方块) 和原版的实体射线, 所以它一旦被 Reach 带大,
     *      放方块就会够得更远 —— 就是用户不要的那个行为。
     *   2) getMouseOver 里的实体搜索距离 (局部变量 8) 必须等于 entityReach。
     *
     * 这里**真的把补丁后的字节码加载起来调一次**, 而不是数指令 —— 因为这两个补丁都有
     * 容易静默出错的点: 方法体没换干净、描述符写错、局部变量槽位猜错。源码里都看不出来,
     * 直接调用一次就能全暴露: 返回值不对就是没接上。
     */
    static boolean checkReach() throws Exception {
        byte[] in;
        try (java.io.InputStream is = FakeController.class.getResourceAsStream("TransformCheck$FakeController.class")) {
            if (is == null) { System.out.println("[reach] 跳过 (拿不到假类)"); return true; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            in = bos.toByteArray();
        }
        final byte[] out = LiteAgent.patchReach(in);
        final String cn = "com.lite.agent.TransformCheck$FakeController";
        // 匿名 ClassLoader 里的 defineClass 是 protected, 只能从子类内部调 —— 套一层 public 方法
        ClassLoader cl = new ClassLoader(TransformCheck.class.getClassLoader()) {
            public Class<?> def() { return defineClass(cn, out, 0, out.length); }
        };
        java.lang.reflect.Method defM = cl.getClass().getDeclaredMethod("def");
        defM.setAccessible(true);
        Class<?> patched = (Class<?>) defM.invoke(cl);

        float beforeEnt = Hook.entityReachOverride;
        // 故意把实体距离设成一个"明显不对"的值, 然后要求方块距离**丝毫不受影响**
        Hook.entityReachOverride = 9.5f;
        float gotBlock = (Float) patched.getMethod("func_78757_d").invoke(patched.newInstance());
        Hook.entityReachOverride = beforeEnt;

        boolean blockOk = Math.abs(gotBlock - 4.5f) < 1e-6f;
        System.out.println("[reach] Reach 开到 9.5 时, 方块距离 getBlockReachDistance() = " + gotBlock
                + " (必须是原版 4.5) -> " + (blockOk ? "OK" : "方块距离被 Reach 带跑了"));

        // entityReach() 本身也得对 —— 它是 getMouseOver 补丁的取值来源
        float before = Hook.entityReachOverride;
        Hook.entityReachOverride = 7.25f;
        float gotEnt = Hook.entityReach();
        boolean entOk = Math.abs(gotEnt - 7.25f) < 1e-6f;
        Hook.entityReachOverride = before;
        System.out.println("[reach] entityReach() = " + gotEnt + " (期望 7.25) -> "
                + (entOk ? "OK" : "取值没接上"));

        return blockOk && entOk;
    }

    /**
     * getMouseOver 实体距离补丁: 确认它真的把那三条指令插进去了, 且插在 dstore 8 之后。
     *
     * 这个补丁没法像上面那样"加载起来调一次" —— 假类里没法复刻原版那一百多条指令。
     * 所以退一步查字节码本身: 数 INVOKESTATIC Hook.entityReach 出现了几次 (必须恰好 1 次,
     * 否则要么没插上、要么插到了别的 dstore 8 上), 并确认它紧跟着 F2D + DSTORE 8。
     */
    static boolean checkEntityReachPatch() throws Exception {
        // 造一个和原版同形的最小方法: 先算出 d8, 再 dstore 8。
        // 局部变量表要和真的一致 (d3 在槽 3, d8 在槽 8), 否则测不出槽位猜错的情况。
        String sig = "(F)V";
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_6, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "FakeER", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "func_78473_a", sig, null, null);
        mv.visitCode();
        mv.visitInsn(org.objectweb.asm.Opcodes.DCONST_0);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, 3);     // d3 = 方块距离槽位
        mv.visitInsn(org.objectweb.asm.Opcodes.DCONST_1);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, 8);     // d8 = 实体距离槽位 ← 插点
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 8);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP2);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(2, 10);
        mv.visitEnd();
        cw.visitEnd();

        byte[] out = LiteAgent.patchEntityReach(cw.toByteArray());

        int calls = 0, at = -1;
        org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(out);
        org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
        cr.accept(node, 0);
        for (org.objectweb.asm.tree.MethodNode m : node.methods) {
            org.objectweb.asm.tree.InsnList ins = m.instructions;
            for (int i = 0; i < ins.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
                if (a.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                        && a instanceof org.objectweb.asm.tree.MethodInsnNode
                        && "entityReach".equals(((org.objectweb.asm.tree.MethodInsnNode) a).name)) {
                    calls++;
                    at = i;
                }
            }
            if (calls == 1 && at >= 0) {
                // 后面两条必须正好是 F2D 和 DSTORE 8
                org.objectweb.asm.tree.AbstractInsnNode n1 = ins.get(at + 1);
                org.objectweb.asm.tree.AbstractInsnNode n2 = ins.get(at + 2);
                boolean tailOk = n1.getOpcode() == org.objectweb.asm.Opcodes.F2D
                        && n2.getOpcode() == org.objectweb.asm.Opcodes.DSTORE
                        && ((org.objectweb.asm.tree.VarInsnNode) n2).var == 8;
                boolean beforeOk = false;
                for (int i = 0; i < at; i++) {
                    org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
                    if (a.getOpcode() == org.objectweb.asm.Opcodes.DSTORE
                            && ((org.objectweb.asm.tree.VarInsnNode) a).var == 8) beforeOk = true;
                }
                if (!tailOk) {
                    System.out.println("[entityreach] 插入点后面不是 F2D + DSTORE 8, 补丁写错了");
                    return false;
                }
                if (!beforeOk) {
                    System.out.println("[entityreach] 插入点在 dstore 8 **之前** —— 我们的值会被原版覆盖掉");
                    return false;
                }
                System.out.println("[entityreach] 实体距离覆盖插在 dstore 8 之后, 且恰好一次 -> OK");
                return true;
            }
        }
        System.out.println("[entityreach] entityReach 调用出现 " + calls + " 次 (必须恰好 1 次) -> 补丁没生效");
        return false;
    }

    /**
     * 聊天后缀的字节码钩子。
     *
     * 和 checkReach 同样的思路: 真的把补丁后的字节码加载起来调一次。这个补丁要往
     * 方法开头插 ALOAD 1 / INVOKESTATIC / ASTORE 1 三条指令, 描述符写错的话
     * 编译期完全看不出来, 只会在用户打字发消息那一刻抛 NoSuchMethodError ——
     * 而且那时候聊天已经废了。
     *
     * 假类把收到的字符串存下来, 所以"后缀有没有真的改写入参"一看就知道。
     * 后缀文本和开关都直接写 Hook 的字段, 不碰用户目录下那个真实文件 (测试要无副作用)。
     */
    static boolean checkChatSuffix() throws Exception {
        byte[] in;
        try (java.io.InputStream is = FakePlayerSP.class.getResourceAsStream("TransformCheck$FakePlayerSP.class")) {
            if (is == null) { System.out.println("[chatsuffix] 跳过 (拿不到假类)"); return true; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            in = bos.toByteArray();
        }
        final byte[] out = LiteAgent.patchChatSuffix(in);
        final String cn = "com.lite.agent.TransformCheck$FakePlayerSP";
        ClassLoader cl = new ClassLoader(TransformCheck.class.getClassLoader()) {
            public Class<?> def() { return defineClass(cn, out, 0, out.length); }
        };
        java.lang.reflect.Method defM = cl.getClass().getDeclaredMethod("def");
        defM.setAccessible(true);
        Class<?> patched = (Class<?>) defM.invoke(cl);
        Object inst = patched.newInstance();
        java.lang.reflect.Method send = patched.getMethod("func_71165_d", String.class);
        java.lang.reflect.Field sent = patched.getField("sent");

        // 备份现场
        java.lang.reflect.Field fCache = Hook.class.getDeclaredField("chatSuffixCache");
        fCache.setAccessible(true);
        Object oldCache = fCache.get(null);
        Class<?> cMod = Class.forName("com.lite.agent.Hook$Mod");
        java.lang.reflect.Field fMods = Hook.class.getDeclaredField("mods");
        fMods.setAccessible(true);
        Object[] mods = (Object[]) fMods.get(null);
        java.lang.reflect.Field fOn = cMod.getDeclaredField("on");
        fOn.setAccessible(true);
        Object chatMod = mods[Hook.F_CHATSUFFIX];
        boolean oldOn = fOn.getBoolean(chatMod);

        boolean ok = true;
        try {
            fCache.set(null, "TESTSUF");
            fOn.setBoolean(chatMod, true);
            send.invoke(inst, "hello");
            String got = (String) sent.get(inst);
            boolean a = "hello TESTSUF".equals(got);
            System.out.println("[chatsuffix] Append 模式: \"hello\" -> \"" + got + "\" -> " + (a ? "OK" : "不对"));
            ok &= a;

            // 关掉功能必须原样放行 —— 这是"最坏情况不能弄坏聊天"的那条底线
            fOn.setBoolean(chatMod, false);
            send.invoke(inst, "hello");
            String got2 = (String) sent.get(inst);
            boolean b = "hello".equals(got2);
            System.out.println("[chatsuffix] 关闭时原样放行: \"" + got2 + "\" -> " + (b ? "OK" : "被改写了"));
            ok &= b;
        } finally {
            fCache.set(null, oldCache);
            fOn.setBoolean(chatMod, oldOn);
        }
        return ok;
    }

    /**
     * 名字保护的字节码钩子 —— 钩的是 TextComponentString 的**构造器**。
     *
     * 构造器不在 SRG 映射表里 (永远叫 <init>), 所以名字/描述符全靠手写。写错了不会编译报错,
     * 只会在游戏里静默失效 —— 名字照旧显示出来, 而用户以为功能开着。
     * 这里真的加载补丁后的字节码 new 一个出来, 看构造出来的文本有没有被改写。
     */
    static boolean checkNameProtect() throws Exception {
        byte[] in;
        try (java.io.InputStream is = FakeTextComponent.class.getResourceAsStream("TransformCheck$FakeTextComponent.class")) {
            if (is == null) { System.out.println("[nameprotect] 跳过 (拿不到假类)"); return true; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            in = bos.toByteArray();
        }
        final byte[] out = LiteAgent.patchNameProtect(in);
        final String cn = "com.lite.agent.TransformCheck$FakeTextComponent";
        ClassLoader cl = new ClassLoader(TransformCheck.class.getClassLoader()) {
            public Class<?> def() { return defineClass(cn, out, 0, out.length); }
        };
        java.lang.reflect.Method defM = cl.getClass().getDeclaredMethod("def");
        defM.setAccessible(true);
        Class<?> patched = (Class<?>) defM.invoke(cl);
        java.lang.reflect.Constructor<?> ctor = patched.getConstructor(String.class);
        java.lang.reflect.Field fText = patched.getField("text");

        // 备份现场
        java.lang.reflect.Field fCache = Hook.class.getDeclaredField("nameProtectCache");
        fCache.setAccessible(true);
        Object oldCache = fCache.get(null);
        Class<?> cMod = Class.forName("com.lite.agent.Hook$Mod");
        java.lang.reflect.Field fMods = Hook.class.getDeclaredField("mods");
        fMods.setAccessible(true);
        Object[] mods = (Object[]) fMods.get(null);
        java.lang.reflect.Field fOn = cMod.getDeclaredField("on");
        fOn.setAccessible(true);
        Object npMod = mods[Hook.F_NAMEPROTECT];
        boolean oldOn = fOn.getBoolean(npMod);

        boolean ok = true;
        try {
            fCache.set(null, "HIDDEN");
            fOn.setBoolean(npMod, true);
            // 这里没有 MC 实例, Hook.nameProtect 会直接返回原文 —— 正好验证那条"不在游戏里就不动"
            // 的兜底路径: 构造器补丁必须原样放行, 不能把文本弄成 null 或空。
            Object o = ctor.newInstance("hello world");
            String got = (String) fText.get(o);
            boolean a = "hello world".equals(got);
            System.out.println("[nameprotect] 无 MC 时原样放行: \"" + got + "\" -> " + (a ? "OK" : "被改坏了"));
            ok &= a;

            // 顺带验证替换逻辑本身 (纯函数, 不依赖 MC)。
            // replaceName 是 private —— getMethod 找不到, 得用 getDeclaredMethod + setAccessible。
            java.lang.reflect.Method repM = Hook.class.getDeclaredMethod(
                    "replaceName", String.class, String.class, String.class, int.class);
            repM.setAccessible(true);
            String rep = (String) repM.invoke(null, "hi Steve and sTeVe", "Steve", "HIDDEN", 4);
            boolean b = "hi HIDDEN and HIDDEN".equals(rep);
            System.out.println("[nameprotect] 大小写不敏感替换: \"" + rep + "\" -> " + (b ? "OK" : "不对"));
            ok &= b;

            // 短名字必须不动 (Min Length 的语义)
            String rep2 = (String) repM.invoke(null, "hi Al and Al", "Al", "HIDDEN", 4);
            boolean c = "hi Al and Al".equals(rep2);
            System.out.println("[nameprotect] 短于下限不动: \"" + rep2 + "\" -> " + (c ? "OK" : "误伤了"));
            ok &= c;
        } finally {
            fCache.set(null, oldCache);
            fOn.setBoolean(npMod, oldOn);
        }
        return ok;
    }

    /** 假的 TextComponentString: 只保留 (String) 构造器和 text 字段 */
    public static class FakeTextComponent {
        public String text;
        public FakeTextComponent(String text) { this.text = text; }
    }

    /** 假的 EntityPlayerSP: 把收到的消息存下来, 好观察入参有没有被改写 */
    public static class FakePlayerSP {
        public String sent = null;
        public void func_71165_d(String msg) { this.sent = msg; }
    }

    /**
     * Hook 是靠字符串反射调 GlStateManager 的 (agent 编译时 classpath 里没有 MC), 所以
     * SRG 名字写错不会有编译错误, 只会在用户机器上静默失效。这里对着 MCP 的 SRG 映射
     * 表核一遍: 每个被反射的 SRG 名必须存在, 且描述符完全一致。
     *
     * 找到映射表就检查, 找不到就跳过 (别的机器上没有 gradle 缓存不该导致构建失败)。
     */
    static boolean checkGlStateManager() {
        java.io.File srg = new java.io.File(System.getProperty("user.home"),
                ".gradle/caches/minecraft/de/oceanlabs/mcp/mcp_stable/39/1.12.2/srgs/srg-mcp.srg");
        if (!srg.isFile()) {
            System.out.println("[glsm] 跳过 (找不到 srg-mcp.srg, 非本机构建)");
            return true;
        }
        // 与 Hook.pushPhysicalProjection 里反射的名字/描述符必须逐字一致。
        // func_179109_b = translate(FFF): 缺了它 MODELVIEW 就没把 GUI 挪进裁剪体,
        // 整个面板会被深度裁掉 —— 表现是"按面板键没反应", 而日志里一条异常都没有。
        String[][] want = {
            {"func_179128_n", "(I)V"},
            {"func_179094_E", "()V"},
            {"func_179121_F", "()V"},
            {"func_179096_D", "()V"},
            {"func_179130_a", "(DDDDDD)V"},
            {"func_179109_b", "(FFF)V"},
        };
        String all;
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(srg.toPath());
            all = new String(raw, "UTF-8");
        } catch (Exception e) {
            System.out.println("[glsm] 跳过 (读取失败: " + e + ")");
            return true;
        }
        boolean ok = true;
        for (String[] w : want) {
            String needle = "GlStateManager/" + w[0] + " " + w[1];
            boolean found = all.contains(needle);
            System.out.println("[glsm] " + w[0] + w[1] + " -> " + (found ? "OK" : "缺失"));
            if (!found) ok = false;
        }
        return ok;
    }

    /**
     * 投影函数必须在字节码里真的把 GUI 挪进裁剪体。
     *
     * 为什么单独查这个: 整个面板是靠反射调 GlStateManager 画的, 源码里既没有 MC 的类型,
     * 也没有任何编译期检查。上一版就是漏了 MODELVIEW 上的 translate(0,0,-2000),
     * 结果 glOrtho(..,1000,3000) 把 z_eye=0 映成 z_ndc=-2, 整个面板被裁掉 ——
     * 日志里一条异常都没有 (命中判定是纯数学, 照常工作), 表现只是"按面板键没反应"。
     *
     * 所以这里数 pushPhysicalProjection 里 Method.invoke 的调用次数。正常是 8 次:
     * matrixMode/push/loadIdentity/ortho/matrixMode/push/loadIdentity/translate。
     * 少一次就是有人把某步删了 —— 直接炸, 而不是等用户发现面板空了。
     */
    static boolean checkProjection() throws Exception {
        byte[] b;
        try (java.io.InputStream is = Hook.class.getResourceAsStream("Hook.class")) {
            if (is == null) { System.out.println("[proj] 跳过 (拿不到 Hook.class)"); return true; }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            b = bos.toByteArray();
        }
        final int[] invokes = {0};
        final boolean[] seen = {false};
        new org.objectweb.asm.ClassReader(b).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!"pushPhysicalProjection".equals(name)) return null;
                seen[0] = true;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        if ("java/lang/reflect/Method".equals(owner) && "invoke".equals(nm)) invokes[0]++;
                    }
                };
            }
        }, 0);
        if (!seen[0]) {
            System.out.println("[proj] 找不到 pushPhysicalProjection");
            return false;
        }
        boolean ok = invokes[0] >= 8;
        System.out.println("[proj] pushPhysicalProjection 反射调用 " + invokes[0] + " 次 (需 >=8, 含 translate)"
                + " -> " + (ok ? "OK" : "缺少矩阵步骤"));
        // 顺带确认 translate 的 SRG 名确实被解析过 (常量池里必须有)
        boolean hasName = new String(b, "ISO-8859-1").contains("func_179109_b");
        System.out.println("[proj] 解析了 func_179109_b(translate) -> " + (hasName ? "OK" : "缺失"));
        return ok && hasName;
    }

    static boolean checkHook() throws Exception {        java.io.InputStream is = FakeRenderer.class.getResourceAsStream("TransformCheck$FakeRenderer.class");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        byte[] out = LiteAgent.hookBytes(new FakeRenderer().getClass().getName().replace('.', '/'), bos.toByteArray());
        String s = new String(out, "ISO-8859-1");
        boolean frameOk = s.contains("onFrame");
        boolean renderOk = s.contains("onRender");
        boolean cameraOk = s.contains("onCameraReady");
        boolean kept = s.contains("fake render");
        boolean kept2 = s.contains("fake camera");
        System.out.println("[hook] onFrame=" + frameOk + " onRender=" + renderOk
                + " onCameraReady=" + cameraOk + " 原方法体保留=" + kept + "/" + kept2);
        if (!cameraOk) {
            System.out.println("[hook] !! onCameraReady 没插进 func_78479_a —— 玩偶低头会跟着把相机一起带下去");
        }
        return frameOk && renderOk && cameraOk && kept && kept2;
    }

    /**
     * 静默转身的写入窗口: 方法入口先装入瞄准角，super.onUpdate() 之后发布发包姿态，
     * 方法返回前还原自由相机。这样原版移动、疾跑和身体状态与发包朝向属于同一姿态。
     *
     * 这个检查是防回归的关键 —— 一旦 onPlayerMoved 没插上, rotationYaw 就会退回
     * "整帧都是瞄准角"的老样子, 症状是**走路方向跟着瞄准走**。那个 bug 从外面看
     * 完全不像旋转系统的问题, 所以必须在这里挡住。
     */
    static boolean checkRotationHook() throws Exception {
        java.io.InputStream is = FakeRenderer.class.getResourceAsStream("TransformCheck$FakeRenderer.class");
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        byte[] out = LiteAgent.patchRotation(bos.toByteArray());
        final java.util.List<String> calls = new java.util.ArrayList<String>();
        new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!LiteAgent.T.TICK_M.equals(name) || !"()V".equals(desc)) return null;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        calls.add(owner + "." + nm + d + "#" + op);
                    }
                };
            }
        }, 0);
        String s = new String(out, "ISO-8859-1");
        int start = -1, parent = -1, movedAt = -1, endAt = -1;
        for (int i = 0; i < calls.size(); i++) {
            String c = calls.get(i);
            if (c.startsWith("com/lite/agent/Hook.onPlayerTickStart()V")) start = i;
            if (c.startsWith("com/lite/agent/Hook.onPlayerMoved()V")) movedAt = i;
            if (c.startsWith("com/lite/agent/Hook.onPlayerTickEnd()V")) endAt = i;
            if (c.contains("." + LiteAgent.T.TICK_M + "()V#" + org.objectweb.asm.Opcodes.INVOKESPECIAL)) parent = i;
        }
        boolean moved = movedAt >= 0;
        boolean end = endAt >= 0;
        boolean kept = s.contains("fake tick");
        boolean ordered = start >= 0 && start < parent && parent < movedAt && movedAt < endAt;
        System.out.println("[rot] start=" + start + " super=" + parent + " moved=" + movedAt
                + " end=" + endAt + " 顺序正确=" + ordered + " 原方法体保留=" + kept);
        if (!moved) {
            System.out.println("[rot] !! onPlayerMoved 没插进 super.onUpdate() 之后 —— "
                    + "rotationYaw 会整帧停在瞄准角, 走路方向会跟着瞄准走");
        }
        final java.util.List<String> livingCalls = new java.util.ArrayList<String>();
        new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!LiteAgent.T.LIVING_UPDATE_M.equals(name) || !"()V".equals(desc)) return null;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        livingCalls.add(owner + "." + nm + d);
                    }
                };
            }
        }, 0);
        int input = -1, exact = -1;
        for (int i = 0; i < livingCalls.size(); i++) {
            String c = livingCalls.get(i);
            if (c.contains("." + LiteAgent.T.INPUT_UPDATE_M + "()V")) input = i;
            if (c.equals("com/lite/agent/Hook.onScaffoldMovementInputUpdated()V")) exact = i;
        }
        boolean inputOrdered = input >= 0 && exact == input + 1;
        System.out.println("[rot] movementInput=" + input + " digitalInput=" + exact
                + " 原版八方向输入钩子顺序正确=" + inputOrdered);
        final int[] gates = {0};
        new org.objectweb.asm.ClassReader(out).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                if (!"func_70031_b".equals(name) || !"(Z)V".equals(desc)) return null;
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        if (owner.equals("com/lite/agent/Hook") && nm.equals("scaffoldSprintRequested")
                                && d.equals("(Z)Z")) gates[0]++;
                    }
                };
            }
        }, 0);
        System.out.println("[rot] native sprint request gates=" + gates[0]);
        return moved && end && ordered && kept && inputOrdered && gates[0] == 1;
    }

    /** 解析 genLiteGui 生成的字节码, 校验每个必需方法都在, 且滚轮确实接到了 LWJGL */
    static boolean checkGui() throws Exception {
        byte[] b = LiteAgent.genLiteGui();
        final java.util.Map<String, String> methods = new java.util.HashMap<String, String>();
        final java.util.Set<String> calls = new java.util.HashSet<String>();

        new org.objectweb.asm.ClassReader(b).accept(new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM5) {
            public org.objectweb.asm.MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                methods.put(name, desc);
                return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM5) {
                    public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        calls.add(owner + "." + nm);
                    }
                };
            }
        }, 0);

        // 每个都必须存在, 描述符也要对 —— 名字对但描述符错会在游戏里 NoSuchMethodError
        String[][] need = {
                {"func_73863_a", "(IIF)V"},    // drawScreen
                {"func_73864_a", "(III)V"},    // mouseClicked
                {"func_73869_a", "(CI)V"},     // keyTyped
                {"func_146281_b", "()V"},      // onGuiClosed
                {"func_73853_d", "()Z"},       // doesGuiPauseGame
                {"func_146274_d", "()V"},      // handleMouseInput <- 滚轮靠它
        };
        boolean ok = true;
        for (String[] m : need) {
            String got = methods.get(m[0]);
            boolean hit = m[1].equals(got);
            if (!hit) ok = false;
            System.out.println("[gui] " + m[0] + m[1] + " -> " + (hit ? "OK" : "缺失/描述符不符: " + got));
        }
        boolean wheel = calls.contains("org/lwjgl/input/Mouse.getDWheel");
        boolean scroll = calls.contains("com/lite/agent/Hook.guiScroll");
        boolean superCall = calls.contains("net/minecraft/client/gui/GuiScreen.func_146274_d");
        System.out.println("[gui] handleMouseInput 读滚轮=" + wheel + " 回传 Hook=" + scroll + " 调用super=" + superCall);
        return ok && wheel && scroll && superCall;
    }

    // ==================================================================
    // 1.20.1 的两项自检
    //
    // 为什么必须自检: 1.20.1 的实体距离补丁是**按"调用了什么"定位插入点**的
    // (1.12.2 那套"DSTORE 8"在 1.20.1 上必然插错), 而"模式没认出来"的表现是
    // **静默的** —— 不报错, 只是 Reach 在白名单里点了却没效果。这种事只有跑一次能发现。
    //
    // 本机没有能跑的 1.20.1 客户端, 所以这里做到两件事:
    //   ① 合成一个与真 pick() 同形的类, 把"插哪儿 / 不插哪儿"钉死 (任何机器上都能跑);
    //   ② 如果本机有 1.20.1 的 SRG 客户端 jar (Forge 的 gradle 缓存), 直接拿**真的
    //      GameRenderer.class** 过一遍补丁, 看模式认不认得出来 —— 这是最接近"真机"的证据。
    //      没有那个 jar 就跳过 (别的机器上没有 gradle 缓存, 不该因此让构建失败)。
    // ==================================================================

    /** 假 GameRenderer.pick 的字节码, 形状照抄真 1.20.1 GameRenderer.m_109087_(F)V 的关键指令。 */
    private static byte[] fakePick1201(String entityHitCallName) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(org.objectweb.asm.Opcodes.V1_8, org.objectweb.asm.Opcodes.ACC_PUBLIC,
                "FakeGR1201", null, "java/lang/Object", null);
        org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                org.objectweb.asm.Opcodes.ACC_PUBLIC, "m_109087_", "(F)V", null, null);
        mv.visitCode();
        // d0 = gameMode.getPickRange()
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/client/multiplayer/MultiPlayerGameMode", "m_105286_", "()F", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.F2D);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, 3);            // d0 -> 槽 3 (真字节码 58: dstore_3)
        // 十字线: entity.pick(d0, partialTicks, false) —— **必须一动不动**
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 3);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.FLOAD, 1);
        mv.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/Entity", "m_19907_",
                "(DFZ)Lnet/minecraft/world/phys/HitResult;", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        // 十字线之后紧跟的 `d1 = d0` (真字节码 86/87): 只是给阈值算初值, **也必须一动不动**
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 3);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, 8);
        // 实体搜索体积: getViewVector (窗口起点, 真字节码 164) + 4 条 d0 (真字节码 176/183/190/206)
        mv.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 1);
        mv.visitInsn(org.objectweb.asm.Opcodes.FCONST_1);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/world/entity/Entity", "m_20252_",
                "(F)Lnet/minecraft/world/phys/Vec3;", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        for (int i = 0; i < 4; i++) {
            mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 3);
            mv.visitInsn(org.objectweb.asm.Opcodes.POP2);
        }
        // 原版阈值: d1 = d1 * d1 (槽 8) —— 这两条 DLOAD 8 与下面那条阈值**都不许动**
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 8);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 8);
        mv.visitInsn(org.objectweb.asm.Opcodes.DMUL);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DSTORE, 8);
        for (int i = 0; i < 5; i++) mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 8);              // 真字节码 233: 栈顶 = 实体搜索距离²
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESTATIC,
                "net/minecraft/world/entity/projectile/ProjectileUtil", entityHitCallName,
                "(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;"
                        + "Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;"
                        + "Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        // 判定之后**还有**一条 DLOAD 8 (真字节码 320): 也必须一动不动
        mv.visitVarInsn(org.objectweb.asm.Opcodes.DLOAD, 8);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP2);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(8, 20);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** 数一个类里 Hook.entityReach 调用出现在第几条指令 (返回 -1 表示没有)。 */
    private static java.util.List<Integer> reachCalls(org.objectweb.asm.tree.InsnList ins) {
        java.util.List<Integer> at = new java.util.ArrayList<Integer>();
        for (int i = 0; i < ins.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
            if (a.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && a instanceof org.objectweb.asm.tree.MethodInsnNode
                    && "entityReach".equals(((org.objectweb.asm.tree.MethodInsnNode) a).name)) {
                at.add(i);
            }
        }
        return at;
    }

    private static org.objectweb.asm.tree.MethodNode firstMethod(byte[] bytes, String name) {
        org.objectweb.asm.tree.ClassNode n = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(n, 0);
        for (org.objectweb.asm.tree.MethodNode m : n.methods) {
            if (name == null || name.equals(m.name)) return m;
        }
        return null;
    }

    /** 找一条方法调用在指令表里的下标 (按名字+描述符后缀)。 */
    private static int callAt(org.objectweb.asm.tree.InsnList ins, String name, String descEnd) {
        for (int i = 0; i < ins.size(); i++) {
            org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
            if (a instanceof org.objectweb.asm.tree.MethodInsnNode) {
                org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) a;
                if (mi.name.equals(name) && (descEnd == null || mi.desc.endsWith(descEnd))) return i;
            }
        }
        return -1;
    }

    /**
     * 1.20.1 实体距离补丁的定位逻辑: 合成类上验证四件事。
     *
     *  ① 阈值: getEntityHitResult 调用之前恰好插了 entityReach→F2D→DUP2→DMUL→Math.max(DD)D;
     *  ② 搜索体积: 窗口里那 4 条 d0 每条都变成 max(原版 d0, entityReach());
     *  ③ 十字线 (Entity.pick 的那条 d0) 和窗口外那两条 DLOAD 8 **一个都没动**;
     *  ④ 反例: 把 getEntityHitResult 改名 → 补丁必须**原样返回**, 一个字节都不改
     *     (认不出插入点时宁可不动; 插错会 VerifyError 把 GameRenderer 弄挂)。
     */
    static boolean checkEntityReachPatch1201() throws Exception {
        byte[] in = fakePick1201("m_37287_");
        byte[] out = LiteAgent.patchEntityReach1201(in);
        org.objectweb.asm.tree.MethodNode m = firstMethod(out, "m_109087_");
        if (m == null) { System.out.println("[reach1201] 补丁后类里找不到 m_109087_ -> FAIL"); return false; }
        org.objectweb.asm.tree.InsnList ins = m.instructions;

        int hitCall = callAt(ins, "m_37287_", "Lnet/minecraft/world/phys/EntityHitResult;");
        int blockPick = callAt(ins, "m_19907_", null);
        if (hitCall < 0 || blockPick < 0) { System.out.println("[reach1201] 找不到关键调用 -> FAIL"); return false; }

        java.util.List<Integer> calls = reachCalls(ins);
        // ① 阈值
        boolean thrOk = false;
        if (hitCall >= 5) {
            org.objectweb.asm.tree.AbstractInsnNode a0 = ins.get(hitCall - 5);
            org.objectweb.asm.tree.AbstractInsnNode a1 = ins.get(hitCall - 4);
            org.objectweb.asm.tree.AbstractInsnNode a2 = ins.get(hitCall - 3);
            org.objectweb.asm.tree.AbstractInsnNode a3 = ins.get(hitCall - 2);
            org.objectweb.asm.tree.AbstractInsnNode a4 = ins.get(hitCall - 1);
            org.objectweb.asm.tree.AbstractInsnNode a5 = ins.get(hitCall - 6);
            boolean head = a0.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && "entityReach".equals(((org.objectweb.asm.tree.MethodInsnNode) a0).name);
            boolean body = a1.getOpcode() == org.objectweb.asm.Opcodes.F2D
                    && a2.getOpcode() == org.objectweb.asm.Opcodes.DUP2
                    && a3.getOpcode() == org.objectweb.asm.Opcodes.DMUL
                    && a4.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && "max".equals(((org.objectweb.asm.tree.MethodInsnNode) a4).name);
            // 前一条必须是原版的 DLOAD 8 (原版值还在栈上, 我们跟它取大)
            boolean keepVanilla = a5 != null && a5.getOpcode() == org.objectweb.asm.Opcodes.DLOAD
                    && ((org.objectweb.asm.tree.VarInsnNode) a5).var == 8;
            thrOk = head && body && keepVanilla;
        }
        // ② 搜索体积: 窗口 (十字线调用之后, 实体判定之前) 里 4 条 d0 全被替换
        int vol = 0;
        for (int i = blockPick + 1; i < hitCall; i++) {
            org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
            if (a.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && "entityReach".equals(((org.objectweb.asm.tree.MethodInsnNode) a).name)) {
                org.objectweb.asm.tree.AbstractInsnNode prev = ins.get(i - 1);
                if (prev.getOpcode() == org.objectweb.asm.Opcodes.DLOAD
                        && ((org.objectweb.asm.tree.VarInsnNode) prev).var == 3) vol++;
            }
        }
        // ③ 十字线不动: 窗口之前的 entityReach 必须 0 个, 且 Entity.pick 前面那条还是裸 DLOAD 3
        int beforePick = 0;
        for (int i = 0; i < blockPick; i++) {
            org.objectweb.asm.tree.AbstractInsnNode a = ins.get(i);
            if (a.getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && "entityReach".equals(((org.objectweb.asm.tree.MethodInsnNode) a).name)) beforePick++;
        }
        org.objectweb.asm.tree.AbstractInsnNode pre = ins.get(blockPick - 3);
        boolean crosshairOk = beforePick == 0 && pre.getOpcode() == org.objectweb.asm.Opcodes.DLOAD
                && ((org.objectweb.asm.tree.VarInsnNode) pre).var == 3
                && ins.get(blockPick - 2).getOpcode() == org.objectweb.asm.Opcodes.FLOAD
                && ins.get(blockPick - 1).getOpcode() == org.objectweb.asm.Opcodes.ICONST_0;
        // 判定之后那条 DLOAD 8 + POP2 必须原样 (说明我们只动栈顶那个实参, 没动局部变量)
        org.objectweb.asm.tree.AbstractInsnNode post = ins.get(hitCall + 2);
        boolean postOk = post != null && post.getOpcode() == org.objectweb.asm.Opcodes.DLOAD
                && ((org.objectweb.asm.tree.VarInsnNode) post).var == 8
                && ins.get(hitCall + 3).getOpcode() == org.objectweb.asm.Opcodes.POP2;
        // ④ 反例
        byte[] negIn = fakePick1201("m_999999_");
        byte[] negOut = LiteAgent.patchEntityReach1201(negIn);
        boolean bailOk = java.util.Arrays.equals(negIn, negOut);

        boolean ok = thrOk && vol == 4 && calls.size() == 5 && crosshairOk && postOk && bailOk;
        System.out.println("[reach1201] 阈值序列=" + thrOk + " 搜索体积替换=" + vol + "/4 共"
                + calls.size() + "次entityReach" + " 十字线不动=" + crosshairOk
                + " 判定后局部不动=" + postOk + " 认不出就原样放行=" + bailOk
                + " -> " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    /**
     * 拿**真的** 1.20.1 GameRenderer.class 过一遍补丁 —— 最接近真机的静态证据。
     *
     * jar 在 Forge 的 maven 缓存里 (构建 1.20.1 工装时下的), 没有就跳过:
     * 别的机器上没这份缓存, 不该让构建失败。
     */
    static boolean check1201RealJar() {
        java.io.File jar = find1201Jar();
        if (jar == null) {
            System.out.println("[reach1201real] 跳过 (本机没有 1.20.1 的 SRG 客户端 jar)");
            return true;
        }
        try {
            java.util.jar.JarFile jf = new java.util.jar.JarFile(jar);
            java.util.jar.JarEntry e = jf.getJarEntry("net/minecraft/client/renderer/GameRenderer.class");
            if (e == null) {
                System.out.println("[reach1201real] " + jar.getName() + " 里没有 GameRenderer -> 跳过");
                jf.close();
                return true;
            }
            java.io.InputStream is = jf.getInputStream(e);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            jf.close();
            byte[] in = bos.toByteArray();

            // 先确认本机的 ASM 读不读得了这个类 —— 1.20.1 的类文件是 Java 17 (major 61),
            // 而仓库里的 lib/asm.jar 是 ASM 5.2 (只到 Java 9)。这不是这次补丁的问题,
            // 但是 1.20.1 能不能生效的**硬前提**, 所以打成显眼的跳过而不是失败。
            int major = ((in[6] & 0xff) << 8) | (in[7] & 0xff);
            try {
                new org.objectweb.asm.ClassReader(in);
            } catch (IllegalArgumentException tooOldAsm) {
                System.out.println("[reach1201real] 跳过: class file major " + major
                        + " (Java " + (major - 44) + "), 本机 lib/asm.jar 读不了 ——"
                        + " 1.20.1 必须把 ASM 换成 9.x (换成 9.x 后本项会自动跑起来)");
                return true;
            }

            // 真字节码里 StackMapTable 的帧数: 补丁不许把它弄丢 (丢了就是 VerifyError, 游戏进不去)
            org.objectweb.asm.tree.MethodNode inPick = firstMethod(in, "m_109087_");
            if (inPick == null) { System.out.println("[reach1201real] 真类里没有 m_109087_ -> FAIL"); return false; }
            int inFrames = countFrames(inPick);

            byte[] out = LiteAgent.patchEntityReach1201(in);
            if (java.util.Arrays.equals(in, out)) {
                System.out.println("[reach1201real] 真 GameRenderer 上**一条都没插上** —— 模式没认出来 -> FAIL");
                return false;
            }
            org.objectweb.asm.tree.MethodNode outPick = firstMethod(out, "m_109087_");
            int calls = reachCalls(outPick.instructions).size();
            int outFrames = countFrames(outPick);
            int hitCall = callAt(outPick.instructions, "m_37287_", "Lnet/minecraft/world/phys/EntityHitResult;");
            boolean thrOk = hitCall > 0
                    && outPick.instructions.get(hitCall - 1).getOpcode() == org.objectweb.asm.Opcodes.INVOKESTATIC
                    && "max".equals(((org.objectweb.asm.tree.MethodInsnNode) outPick.instructions.get(hitCall - 1)).name);
            // 补丁后的字节码再过一次 ASM 的完整数据流分析 (COMPUTE_FRAMES), 看栈有没有被我插坏。
            // 只当"栈是否平衡"的探针用 (帧本身不采纳), 而且先拿原版跑一遍作对照,
            // 原版都跑不过才不算数 —— 免得把 ASM 自身的限制当成我的 bug。
            boolean controlFramesOk = framesProbeOk(in);
            boolean patchedFramesOk = framesProbeOk(out);
            boolean ok = calls == 5 && outFrames == inFrames && thrOk
                    && (!controlFramesOk || patchedFramesOk);
            System.out.println("[reach1201real] " + jar.getName() + ": entityReach 插入=" + calls
                    + "/5 帧数 " + inFrames + "->" + outFrames + " 阈值前是Math.max=" + thrOk
                    + " 数据流复检(原版=" + controlFramesOk + "/补丁后=" + patchedFramesOk + ")"
                    + " -> " + (ok ? "OK" : "FAIL"));
            return ok && checkChat1201(jar);
        } catch (Throwable t) {
            String msg = String.valueOf(t.getMessage());
            // ASM 太旧抛的 IllegalArgumentException 消息可能是空的 (ASM 5.2 就是空消息),
            // 所以主判据是上面那段"先试着解析一次"; 这里只兜住其它读文件类的异常。
            if (t instanceof IllegalArgumentException && msg.contains("class file major version")) {
                System.out.println("[reach1201real] 跳过: 本机 lib/asm.jar 读不了 Java 17 的类 ("
                        + msg + ") —— 1.20.1 必须换 ASM 9.x, 见汇报里的阻塞项");
                return true;
            }
            System.out.println("[reach1201real] 读 " + jar + " 出错: " + t);
            return false;
        }
    }

    /**
     * 1.20.1 聊天那两件事的**实证复核** —— 拿真 srg.jar 的字节码当判据, 不是照注释相信。
     *
     * <p>为什么值得单独验: 1.19 之后聊天多了签名链, "改聊天内容"这件事从"随便改"变成
     * "改错地方就把消息搞废, 而且可能被封"。所以这两条必须钉死:
     * <ol>
     * <li><b>名字保护 (NameProtect)</b>: 1.20.1 没有 TextComponent —— 我们钩的是
     *     {@code LiteralContents}. 这里验三件事: 它的 {@code (String)V} 构造器在;
     *     {@code Component.m_237113_} (Component.literal) 的方法体里确实 new 它 (所以
     *     1.12.2 的"钩文本类构造器"在 1.20.1 有等价物, 不是硬凑一个类);
     *     {@code PlayerChatMessage} 里确实调了 {@code Component.m_237113_} (所以**进来的**
     *     玩家聊天也在客户端 new LiteralContents, 钩子会响)。</li>
     * <li><b>聊天后缀 (ChatSuffix)</b>: 出站文本在 {@code ClientPacketListener.m_246175_} 里
     *     被签 ({@code SignedMessageChain$Encoder.m_240988_}); 这里验"签名那条调用**排在**
     *     第一次读入参之后" —— 也就是说 patchChatSuffix 插在方法入口时, 服务端拿到的签名
     *     是签在我改完的文本上的, 验签能过。要是哪天 Mojang 把签名挪到调用方 (签完之后才进
     *     这个方法), 这条断言就会红 —— 那正是"改了内容会被服务端拒收"的临界点, 必须响。</li>
     * </ol>
     */
    static boolean checkChat1201(java.io.File jar) throws Exception {
        byte[] comp = readEntry(jar, "net/minecraft/network/chat/Component.class");
        byte[] lit = readEntry(jar, "net/minecraft/network/chat/contents/LiteralContents.class");
        byte[] pcm = readEntry(jar, "net/minecraft/network/chat/PlayerChatMessage.class");
        byte[] cpl = readEntry(jar, "net/minecraft/client/multiplayer/ClientPacketListener.class");
        if (comp == null || lit == null || pcm == null || cpl == null) {
            System.out.println("[chat1201] 跳过 (jar 里少类)");
            return true;
        }
        // ① LiteralContents 有 (String)V 构造器
        boolean ctor = false;
        for (org.objectweb.asm.tree.MethodNode m : methods(lit)) {
            if ("<init>".equals(m.name) && "(Ljava/lang/String;)V".equals(m.desc)) ctor = true;
        }
        // ② Component.literal (m_237113_) 的方法体 new 出 LiteralContents
        boolean literalFactory = false;
        for (org.objectweb.asm.tree.MethodNode m : methods(comp)) {
            if (!"m_237113_".equals(m.name)) continue;
            boolean isNew = false, isCtor = false;
            for (int i = 0; i < m.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode a = m.instructions.get(i);
                if (a.getOpcode() == org.objectweb.asm.Opcodes.NEW
                        && ((org.objectweb.asm.tree.TypeInsnNode) a).desc
                            .endsWith("network/chat/contents/LiteralContents")) isNew = true;
                if (a instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) a;
                    if ("<init>".equals(mi.name) && "(Ljava/lang/String;)V".equals(mi.desc)
                            && mi.owner.endsWith("LiteralContents")) isCtor = true;
                }
            }
            if (isNew && isCtor) literalFactory = true;
        }
        // ③ 进来的玩家聊天也走 Component.literal (→ LiteralContents)。
        //    注意描述符是 (Ljava/lang/String;)LMutableComponent; —— 不能按"以 Ljava/lang/String; 结尾"匹配,
        //    那是方法**参数**的写法, 返回值在后面。
        boolean incoming = false;
        for (org.objectweb.asm.tree.MethodNode m : methods(pcm)) {
            for (int i = 0; i < m.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode a = m.instructions.get(i);
                if (a instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) a;
                    if ("m_237113_".equals(mi.name) && mi.owner.endsWith("network/chat/Component")) {
                        incoming = true;
                    }
                }
            }
        }
        // ④ 出站: 签名排在"第一次读入参 (ALOAD 1)"之后
        boolean signAfterEntry = false;
        for (org.objectweb.asm.tree.MethodNode m : methods(cpl)) {
            if (!"m_246175_".equals(m.name) || !"(Ljava/lang/String;)V".equals(m.desc)) continue;
            int firstArgUse = -1, signAt = -1;
            for (int i = 0; i < m.instructions.size(); i++) {
                org.objectweb.asm.tree.AbstractInsnNode a = m.instructions.get(i);
                if (firstArgUse < 0 && a.getOpcode() == org.objectweb.asm.Opcodes.ALOAD
                        && ((org.objectweb.asm.tree.VarInsnNode) a).var == 1) firstArgUse = i;
                if (a instanceof org.objectweb.asm.tree.MethodInsnNode) {
                    org.objectweb.asm.tree.MethodInsnNode mi = (org.objectweb.asm.tree.MethodInsnNode) a;
                    if (("m_240988_".equals(mi.name) || mi.name.contains("240988"))
                            && mi.owner.contains("SignedMessageChain")) signAt = i;
                }
            }
            signAfterEntry = firstArgUse >= 0 && signAt > firstArgUse;
        }
        boolean ok = ctor && literalFactory && incoming && signAfterEntry;
        System.out.println("[chat1201real] LiteralContents 有(String)V构造器=" + ctor
                + " Component.literal 造它=" + literalFactory
                + " 入站聊天走它=" + incoming
                + " 出站签名在入口之后=" + signAfterEntry + " -> " + (ok ? "OK" : "FAIL"));
        return ok;
    }

    private static java.util.List<org.objectweb.asm.tree.MethodNode> methods(byte[] bytes) {
        org.objectweb.asm.tree.ClassNode n = new org.objectweb.asm.tree.ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(n, 0);
        return n.methods;
    }

    private static byte[] readEntry(java.io.File jar, String path) throws Exception {
        java.util.jar.JarFile jf = new java.util.jar.JarFile(jar);
        try {
            java.util.jar.JarEntry e = jf.getJarEntry(path);
            if (e == null) return null;
            java.io.InputStream is = jf.getInputStream(e);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            is.close();
            return bos.toByteArray();
        } finally {
            jf.close();
        }
    }

    private static int countFrames(org.objectweb.asm.tree.MethodNode m) {
        int c = 0;
        for (int i = 0; i < m.instructions.size(); i++) {
            if (m.instructions.get(i) instanceof org.objectweb.asm.tree.FrameNode) c++;
        }
        return c;
    }

    /** COMPUTE_FRAMES 只当栈平衡探针: 类加载器换成"什么都返回 java/lang/Object", 不去加载 MC 类。 */
    private static boolean framesProbeOk(byte[] bytes) {
        try {
            org.objectweb.asm.ClassReader cr = new org.objectweb.asm.ClassReader(bytes);
            org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(cr, org.objectweb.asm.ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(String a, String b) {
                    return "java/lang/Object";
                }
            };
            cr.accept(cw, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static java.io.File find1201Jar() {
        String[] roots = {System.getenv("APPDATA"), System.getProperty("user.home")};
        for (String r : roots) {
            if (r == null) continue;
            java.io.File f = new java.io.File(r, ".minecraft/libraries/net/minecraft/client/"
                    + "1.20.1-20230612.114412/client-1.20.1-20230612.114412-srg.jar");
            if (f.isFile()) return f;
        }
        return null;
    }
}
