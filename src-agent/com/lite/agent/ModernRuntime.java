package com.lite.agent;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import org.objectweb.asm.*;

/** Modern Forge GUI, entity rendering and manual Reach picking; no player movement transformer. */
final class ModernRuntime {
    private ModernRuntime() { }
    private static final java.util.Set<String> prepared = java.util.Collections.newSetFromMap(
            new java.util.concurrent.ConcurrentHashMap<String, Boolean>());
    private static volatile String hudTarget;
    static final String LEVEL_RENDERER = "net/minecraft/client/renderer/LevelRenderer";
    static final String KEYBOARD = "net/minecraft/client/KeyboardHandler";

    /** ForgeGui overrides Gui.render without calling super; use the real instance's implementation. */
    static void configureGui() throws Exception {
        ClassLoader loader = Ver.runtimeLoader();
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
        Object mc = minecraft.getMethod("m_91087_").invoke(null);
        Object gui = minecraft.getField("f_91065_").get(mc);
        if (gui == null) throw new IllegalStateException("Minecraft HUD instance is not available");
        Class<?> graphics = Class.forName("net.minecraft.client.gui.GuiGraphics", false, loader);
        Method render = gui.getClass().getMethod("m_280421_", graphics, float.class);
        hudTarget = render.getDeclaringClass().getName().replace('.', '/');
        LiteAgent.log("modern HUD implementation = " + hudTarget + ", instance=" + gui.getClass().getName());
    }

    static String hudTarget() {
        if (hudTarget == null) throw new IllegalStateException("Modern HUD has not been selected");
        return hudTarget;
    }

    static boolean ready() {
        return prepared.contains("net/minecraft/client/renderer/GameRenderer")
                && hudTarget != null && prepared.contains(hudTarget) && prepared.contains(KEYBOARD) && prepared.contains(LEVEL_RENDERER);
    }

    static boolean target(String name) {
        return "net/minecraft/client/renderer/GameRenderer".equals(name)
                || KEYBOARD.equals(name) || LEVEL_RENDERER.equals(name) || (hudTarget != null && hudTarget.equals(name));
    }

    static void connect(Instrumentation inst, ClassLoader game) throws Exception {
        if (game == null) throw new IllegalStateException("Missing game class loader");
        try {
            if (Class.forName(Hook.class.getName(), false, game) == Hook.class) return;
            throw new IllegalStateException("游戏已载入另一份 Hook，请完全退出游戏后重新注入");
        } catch (ClassNotFoundException expected) { }
        Class<?> moduleLoader = game.getClass();
        while (moduleLoader != null && !"cpw.mods.cl.ModuleClassLoader".equals(moduleLoader.getName()))
            moduleLoader = moduleLoader.getSuperclass();
        if (moduleLoader == null)
            throw new IllegalStateException("不支持的现代游戏加载器: " + game.getClass().getName());

        // Open only this loader package to our module, using Java 9 API reflectively for Java 8 builds.
        Class<?> module = Class.forName("java.lang.Module");
        Method getModule = Class.class.getMethod("getModule");
        Object owner = getModule.invoke(moduleLoader), ours = getModule.invoke(ModernRuntime.class);
        Instrumentation.class.getMethod("redefineModule", module, java.util.Set.class,
                java.util.Map.class, java.util.Map.class, java.util.Set.class, java.util.Map.class)
                .invoke(inst, owner, Collections.emptySet(), Collections.emptyMap(),
                        Collections.singletonMap("cpw.mods.cl", Collections.singleton(ours)),
                        Collections.emptySet(), Collections.emptyMap());
        Field fallback = moduleLoader.getDeclaredField("fallbackClassLoader");
        fallback.setAccessible(true);
        ClassLoader previous = (ClassLoader) fallback.get(game);
        ClassLoader bridge = new CallbackLoader(previous);
        Method setFallback = moduleLoader.getMethod("setFallbackClassLoader", ClassLoader.class);
        setFallback.invoke(game, bridge);
        try {
            if (Class.forName(Hook.class.getName(), false, game) != Hook.class)
                throw new IllegalStateException("Hook class identity mismatch");
        } catch (Throwable failure) {
            setFallback.invoke(game, previous);
            throw new IllegalStateException("游戏加载器无法访问回调类", failure);
        }
        LiteAgent.log("modern callback bridge ready: " + game.getClass().getName()
                + ", preserved fallback=" + previous);
    }

    private static final class CallbackLoader extends ClassLoader {
        private final ClassLoader previous;
        CallbackLoader(ClassLoader previous) { super(null); this.previous = previous; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            // All unrelated lookups retain the original Forge fallback behavior.
            if (name.startsWith("com.lite.agent."))
                return Class.forName(name, false, ModernRuntime.class.getClassLoader());
            return previous == null ? Class.forName(name, false, null) : previous.loadClass(name);
        }
        @Override public java.net.URL getResource(String name) {
            return previous == null ? null : previous.getResource(name);
        }
        @Override public java.util.Enumeration<java.net.URL> getResources(String name) throws java.io.IOException {
            return previous == null ? Collections.enumeration(Collections.<java.net.URL>emptyList())
                    : previous.getResources(name);
        }
    }

    static void retransform(Instrumentation inst, Class<?> type) throws Exception {
        if (!inst.isRetransformClassesSupported() || !inst.isModifiableClass(type))
            throw new IllegalStateException("JVM 不支持重转换: " + type.getName());
        String name = type.getName().replace('.', '/');
        prepared.remove(name);
        inst.retransformClasses(type);
        if (!prepared.contains(name))
            throw new IllegalStateException("1.20.1 hook 未应用: " + type.getName() + "，请查看 agent 日志并重启游戏");
        LiteAgent.log("modern retransform OK: " + type.getName());
    }

    static byte[] transform(final String name, byte[] original) {
        if (!target(name)) return null;
        final boolean renderer = name.endsWith("/GameRenderer");
        final boolean keyboard = KEYBOARD.equals(name);
        final boolean level = LEVEL_RENDERER.equals(name);
        final int[] matched = {0};
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(LiteAgent.ASM_API, writer) {
            @Override public MethodVisitor visitMethod(int access, String method, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, method, desc, signature, exceptions);
                if (!(level ? "m_109599_".equals(method) && ("(Lcom/mojang/blaze3d/vertex/PoseStack;FJZLnet/minecraft/client/Camera;"
                        + "Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/renderer/LightTexture;Lorg/joml/Matrix4f;)V").equals(desc)
                        : keyboard ? "m_90893_".equals(method) && "(JIIII)V".equals(desc)
                        : renderer ? "m_109093_".equals(method) && "(FJZ)V".equals(desc)
                        : "m_280421_".equals(method)
                        && "(Lnet/minecraft/client/gui/GuiGraphics;F)V".equals(desc))) return mv;
                matched[0]++;
                return new MethodVisitor(LiteAgent.ASM_API, mv) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (level) {
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.FLOAD, 2);
                            super.visitVarInsn(Opcodes.ALOAD, 6);
                            super.visitVarInsn(Opcodes.ALOAD, 9);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/ModernEsp", "capture",
                                    "(Ljava/lang/Object;FLjava/lang/Object;Ljava/lang/Object;)V", false);
                        } else if (renderer) {
                            super.visitVarInsn(Opcodes.FLOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "onFrame", "(F)V", false);
                        } else if (keyboard) {
                            super.visitVarInsn(Opcodes.LLOAD, 1);
                            super.visitVarInsn(Opcodes.ILOAD, 3);
                            super.visitVarInsn(Opcodes.ILOAD, 5);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "onKey1201", "(JII)V", false);
                        }
                    }
                    @Override public void visitInsn(int op) {
                        if (!renderer && !keyboard && !level && op == Opcodes.RETURN) {
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitVarInsn(Opcodes.FLOAD, 2);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "com/lite/agent/Hook", "drawGui1201",
                                    "(Ljava/lang/Object;F)V", false);
                        }
                        super.visitInsn(op);
                    }
                };
            }
        }, 0);
        if (matched[0] != 1) throw new IllegalStateException("1.20.1 render mapping mismatch: " + name);
        byte[] result = writer.toByteArray();
        if (renderer) result = ReachPicker.transform(result, "m_109087_");
        prepared.add(name);
        return result;
    }
}
