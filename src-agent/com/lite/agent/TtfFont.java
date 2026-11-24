package com.lite.agent;

import java.awt.Color;
import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 真 TTF 字体渲染 —— 替代 MC 自带的 9px 点阵字 (用户抱怨的"像素风")。
 *
 * 流程:
 *   1. 用 AWT 把系统 TTF 字形带抗锯齿画进 BufferedImage
 *   2. 像素拷进 MC 的 DynamicTexture (int[] ARGB) -> updateDynamicTexture() 上传
 *   3. TextureManager.getDynamicTextureLocation 注册, 换回 ResourceLocation
 *   4. 逐字用 Gui.drawScaledCustomSizeModalRect 贴出
 *
 * 为什么必须走 MC 自己的纹理/顶点通道, 而不是裸 GL:
 *   - GL11 immediate mode (glBegin/glVertex2f/glEnd) 在 1.12 的 shader 管线里
 *     调用成功但顶点不被消费 -> 什么都不画。这就是之前"面板透明"的根因。
 *   - 直接 glBindTexture 会让 GlStateManager 的纹理缓存与实际绑定不一致,
 *     MC 之后会跳过它认为"已绑定"的调用 -> 贴图错乱。
 *   DynamicTexture + TextureManager + drawScaledCustomSizeModalRect 全程走
 *   MC 自己的通道, 两个坑都绕开了。
 *
 * ===== 为什么图集要按 GUI 缩放光栅化 =====
 * MC 的 GUI 坐标是"逻辑单位", 屏幕上 1 逻辑单位 = guiScale 个物理像素。
 * 如果按逻辑尺寸 1:1 光栅化字形, 在 guiScale=2 时等于把 24px 的位图放大成 48px
 * -> 糊、发块、笔画断裂。这就是"字显示得乱七八糟"的根因。
 * 所以: 字形按 size*scale 光栅化进 cell*scale 的格子, 再用
 * drawScaledCustomSizeModalRect(源 texCell -> 目标 cell 逻辑单位) 贴回,
 * 在对应的 guiScale 下正好 1:1, 一个物理像素都不重采样。
 *
 * 注意不能用 drawModalRectWithCustomSizedTexture: 那个方法的源区域尺寸和绘制尺寸
 * 是同一个参数, 根本没法缩放。
 *
 * 任何一步失败 -> ready() 返回 false, 调用方自动退回 MC 原生字体, UI 不会坏。
 */
class TtfFont {

    private static final float BODY_SIZE = 17f;
    private static final int BODY_CELL = 27;
    private static final float TITLE_SIZE = 34f;
    private static final int TITLE_CELL = 52;
    private static final String TITLE_CHARS = "AZCLIENT0123456789 .-_:v";
    /** 图集边长上限, 防止缩放 4 时算出 4096 白白吃显存 */
    private static final int MAX_ATLAS = 2048;

    /**
     * 原版 MC 一行文字的高度 (FontRenderer.FONT_HEIGHT), 逻辑单位。
     * 大写占顶部 7 个, 基线以下再给小写降部 2 个 —— 所以**这一行的墨迹也是 9 个单位**,
     * 世界空间那套缩放就是按它对齐的 (见 logicalScale)。
     */
    private static final int VANILLA_LINE = 9;

    /**
     * 正文墨迹在格子里的上下边界 (相对格顶, 实测 3..22, 见 probe/InkMeasure; 跨度 20 行)。
     * 墨迹 20 / 格子 27。**改字号或抗锯齿必须重跑 InkMeasure 把这两个数改掉** ——
     * 猜出来的值会让世界空间那套缩放整档偏掉, 而且偏得看不出是哪错了。
     * (FontProbe 断言 4 会拿真图集量一遍, 对不上直接构建失败。)
     */
    private static final int BODY_INK_TOP = 3, BODY_INK_BOT = 22;

    // 给离线探针读的常量 (探针必须用真参数, 不能自己抄一份)
    static float probeBodySize() { return BODY_SIZE; }
    static int probeBodyCell() { return BODY_CELL; }
    static float probeTitleSize() { return TITLE_SIZE; }
    static int probeTitleCell() { return TITLE_CELL; }
    static String probeTitleChars() { return TITLE_CHARS; }
    static int probeInkTop() { return BODY_INK_TOP; }
    static int probeInkBot() { return BODY_INK_BOT; }
    static int probeVanillaLine() { return VANILLA_LINE; }

    /** 字体图集里一个字形的位置与步进 (包级可见: probe 要读它做离线测量) */
    static class Glyph {
        int x, y;      // 图集内的格子左上角 (栅格像素)
        float adv;     // 步进宽度 (逻辑单位, 保留小数避免逐字取整的累积漂移)
    }

    /** 一张字体图集 */
    private static class Atlas {
        Object tex;      // DynamicTexture
        Object loc;      // ResourceLocation
        int size;        // 图集边长 (栅格像素)
        int cell;        // 逻辑格子边长 (绘制用)
        int texCell;     // 栅格格子边长 = cell * scale (取样用)
        int scale;       // 光栅化时的 GUI 缩放
        boolean filtered; // 纹理已切到线性采样，避免非整数 UI 倍率出现锯齿
        Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>();
    }

    /** 图集里没有的字 (比如中文) 交回 MC 原生字体, 保证不会画成空白 */
    interface Fallback {
        int width(String s);
        /** 画一个字符, 返回新的 x */
        int drawChar(char c, int x, int y, int argb);
    }

    private static Atlas body;    // 正文第一页 (尺寸/行高基准)
    private static final List<Atlas> bodyPages = new ArrayList<Atlas>();
    private static Atlas title;   // 标题 (更大更粗)
    private static Fallback fb;
    private static boolean ready = false;
    private static boolean failed = false;
    private static int builtScale = -1;
    private static String builtCharset = "";

    private static Object texMgr;
    private static Constructor<?> ctorDT;
    private static Method mGetTexData, mUpdateTex, mGetDynLoc, mBindTex, mDrawScaled, mColor, mTexParameteri;

    static boolean ready() { return ready; }

    // Veil owns its typography and metrics; reuse only Minecraft's texture transport.
    static Object uploadHudInk(BufferedImage img,String name) throws Exception {
        if(Ver.is1201())return ModernOverlayFont.upload(img,name);
        Atlas a=new Atlas();a.size=img.getWidth();
        a.tex=ctorDT.newInstance(a.size,a.size);
        try {
            int[] px=(int[])mGetTexData.invoke(a.tex);
            img.getRGB(0,0,a.size,a.size,px,0,a.size);mUpdateTex.invoke(a.tex);
            a.loc=mGetDynLoc.invoke(texMgr,name,a.tex);return a;
        } catch(Exception e){releaseAtlas(a);throw e;}
    }
    static void releaseHudInk(Object texture){if(texture instanceof ModernOverlayFont.Atlas)ModernOverlayFont.release(texture);else if(texture!=null)releaseAtlas((Atlas)texture);}
    static void drawHudInk(Object texture,int sx,int sy,int w,int h,float x,float y,int color)throws Exception{
        if(texture instanceof ModernOverlayFont.Atlas){ModernOverlayFont.ink(texture,sx,sy,w,h,x,y,color);return;}
        drawHudTexture(texture,sx,sy,w,h,x,y,w,h,color);
    }
    static void drawHudTexture(Object texture,int sx,int sy,int w,int h,float x,float y,int dw,int dh,int color)throws Exception{
        Atlas a=(Atlas)texture;bindHudTexture(texture);
        try {
            mColor.invoke(null,((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,(color>>>24)/255f);
            mDrawScaled.invoke(null,Math.round(x),Math.round(y),(float)sx,(float)sy,w,h,dw,dh,(float)a.size,(float)a.size);
        } finally {mColor.invoke(null,1f,1f,1f,1f);}
    }
    static void bindHudTexture(Object texture)throws Exception{
        Atlas a=(Atlas)texture;mBindTex.invoke(texMgr,a.loc);
        if (!a.filtered && mTexParameteri != null) {
            mTexParameteri.invoke(null,3553,10240,9729);
            mTexParameteri.invoke(null,3553,10241,9729);
            mTexParameteri.invoke(null,3553,10242,33071);
            mTexParameteri.invoke(null,3553,10243,33071);
            a.filtered=true;
        }
    }

    /** 图集可用字形的行高 (含行距) */
    static int lineH() { return ready ? body.cell - 6 : 9; }

    /**
     * 世界空间 (Hook.pushLogicalProjection) 里用的缩放比。
     *
     * **判据是墨迹高度, 不是格子边长。** 那个投影里"原版字号"的准确含义是: 原版一行
     * 9 个单位 (FontRenderer.FONT_HEIGHT), 而这一行里**墨迹也是 9 个单位** —— 大写占
     * 顶部 7 个, 基线以下再给小写降部 2 个, 正好撑满。
     *
     * 我们的图集是按"1 单位 = 1 物理像素"烘焙的 (面板就在那个空间画, 所以是 1:1),
     * 正文格子 27 个单位、墨迹 20 个单位。不压的话那几处文字墨迹就是 20 个单位, 比原版
     * 大 2 倍多, 而且 27 像素的图集格子被拉到 27 个逻辑单位 (= GS 倍物理像素), 又大又糊。
     *
     * **不能按格子对齐 (27 -> 9)**: 我们的字体墨迹只占格子的 70%, 原版占 100%,
     * 按格子对齐会让字只剩原版的六成, 看着又小又细。按墨迹对齐才和原版一样大。
     *
     * 两个空间的比值和 GS 无关 —— 两边量的是同一套单位 —— 所以这一个系数在什么
     * guiScale 下都对。
     */
    static float logicalScale() {
        if (!ready || body == null) return 1f;
        return logicalScaleFor(body.texCell);
    }

    /**
     * logicalScale 的本体: **纯函数, 离线探针直接测的就是它** —— 探针里再抄一份公式
     * 就等于测复制品, 真代码改了它也照样通过。
     */
    static float logicalScaleFor(int texCell) {
        if (texCell <= 0) return 1f;
        return VANILLA_LINE / (texCell * inkPerCell());
    }

    /** 墨迹占格子的比例 (20/27)。两个字体各自墨迹撑满格子的程度不同, 对齐必须按这个算。 */
    private static float inkPerCell() {
        return (BODY_INK_BOT - BODY_INK_TOP + 1f) / BODY_CELL;
    }

    /** 一个字形在缩放 k 下实际占的逻辑单位边长 (blit 的目标边长, 探针也用它验算) */
    static int drawCell(int texCell, float k) { return Math.max(1, Math.round(texCell * k)); }

    /**
     * 缩放后一个字形墨迹占多少个逻辑单位 —— 这个数应该正好是 VANILLA_LINE(9)。
     * 用 drawCell 而不是 texCell*k: 贴图的目标边长是取整过的, 取整那点差也算进去。
     */
    static float inkUnits(int texCell, float k) { return drawCell(texCell, k) * inkPerCell(); }

    /**
     * 需要的图集边长 (2 的幂)。按字形数 + 格子大小 + 缩放算, 不再写死容量 ——
     * 缩放一变大格子就变大, 写死 1024 会在 guiScale=3 时放不下。
     */
    static int atlasSizeFor(int glyphs, int cell, int scale) {
        int side = (int) Math.ceil(Math.sqrt(Math.max(1, glyphs))) * cell * scale;
        int s = 256;
        while (s < side) s *= 2;
        return Math.min(s, MAX_ATLAS);
    }

    /** 给定字形数在指定缩放下能否全部放进图集 (放不下就会退回 MC 点阵字, 那就丑了) */
    static boolean fits(int glyphs, int cell, int scale) {
        int s = atlasSizeFor(glyphs, cell, scale);
        int cols = s / (cell * scale);
        return cols * cols >= glyphs;
    }

    /** 供自检用: 按缩放 4 的最坏情况检查 */
    static boolean fitsWorstCase(int glyphs) {
        for (int s = 1; s <= 4; s++) {
            int side = MAX_ATLAS / (BODY_CELL * s);
            if (side <= 0 || glyphs > side * side * 8) return false;
        }
        return true;
    }

    /**
     * 由 Hook 在每次打开面板时调用。GUI 缩放变了就重建图集 ——
     * 缩放不匹配时贴出来的字会重新采样, 又变糊。
     */
    private static String canonicalCharset(String input) {
        java.util.SortedSet<Character> chars = new java.util.TreeSet<Character>();
        if (input != null) for (char c : input.toCharArray()) chars.add(c);
        StringBuilder out = new StringBuilder(chars.size());
        for (Character c : chars) out.append(c.charValue());
        return out.toString();
    }

    /** Call only on the render thread while its GL context is current. */
    static void release() {
        for (Atlas a : bodyPages) releaseAtlas(a);
        releaseAtlas(title);
        bodyPages.clear(); body = title = null; ready = false;
    }

    private static void releaseAtlas(Atlas a) {
        if (a == null || a.tex == null) return;
        try {
            if (texMgr != null && a.loc != null)
                texMgr.getClass().getMethod("func_147645_c", a.loc.getClass()).invoke(texMgr, a.loc);
            else a.tex.getClass().getMethod("func_147631_c").invoke(a.tex);
        } catch (Throwable t) { Hook.log("TtfFont release: " + t); }
        // 1.8.9 deletes the GL id but retains the Java DynamicTexture in its map.
        // Remove only this atlas's exact key/value pair, never another owner's texture.
        if (texMgr != null) for (Class<?> c = texMgr.getClass(); c != null; c = c.getSuperclass()) {
            for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                if (!java.util.Map.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    java.util.Map<?, ?> map = (java.util.Map<?, ?>) field.get(texMgr);
                    if (map != null && a.loc != null && map.get(a.loc) == a.tex) map.remove(a.loc);
                } catch (Throwable t) { Hook.log("TtfFont unregister: " + t); }
            }
        }
        a.tex = a.loc = null;
    }

    static void ensureScale(String charset, Fallback fallback, int guiScale) {
        int s = Math.max(1, Math.min(4, guiScale));
        String requested = canonicalCharset(charset);
        if (ready && s == builtScale && requested.equals(builtCharset)) return;
        if (failed && s == builtScale && requested.equals(builtCharset)) return;
        release();
        ready = false;
        failed = false;
        builtScale = s;
        builtCharset = requested;
        init(requested, fallback, s);
    }

    static void init(String charset, Fallback fallback, int guiScale) {
        if (ready || failed) return;
        fb = fallback;
        int scale = Math.max(1, Math.min(4, guiScale));
        try {
            Class<?> cGL = Class.forName("net.minecraft.client.renderer.GlStateManager");
            mColor = cGL.getMethod("func_179131_c", float.class, float.class, float.class, float.class);
            // Minecraft 的 DynamicTexture 默认可能沿用最近邻过滤。GUI 的拖动缩放、HUD
            // 的倍率和 logicalScale 都会产生非整数目标尺寸，最近邻会把抗锯齿边缘切成锯齿。
            // 复用同一个 GlStateManager 句柄，把两级采样都固定到线性过滤。
            try {
                mTexParameteri = cGL.getMethod("func_187421_b", int.class, int.class, int.class);
            } catch (NoSuchMethodException absentOn189) {
                // TextureManager still owns the binding. Updating the bound texture's
                // sampler does not invalidate GlStateManager's binding cache on 1.8.9.
                mTexParameteri = Class.forName("org.lwjgl.opengl.GL11").getMethod(
                        "glTexParameteri", int.class, int.class, int.class);
            }

            Class<?> cMC = Class.forName("net.minecraft.client.Minecraft");
            Object mc = cMC.getMethod("func_71410_x").invoke(null);
            Object tm = cMC.getMethod("func_110434_K").invoke(mc);
            texMgr = tm;

            Class<?> cTM = Class.forName("net.minecraft.client.renderer.texture.TextureManager");
            Class<?> cRL = Class.forName("net.minecraft.util.ResourceLocation");
            Class<?> cDT = Class.forName("net.minecraft.client.renderer.texture.DynamicTexture");
            mGetDynLoc = cTM.getMethod("func_110578_a", String.class, cDT);
            mBindTex = cTM.getMethod("func_110577_a", cRL);

            // drawScaledCustomSizeModalRect: 源区域和目标尺寸分开, 这才缩放得动
            Class<?> cGui = Class.forName("net.minecraft.client.gui.Gui");
            mDrawScaled = cGui.getMethod("func_152125_a",
                    int.class, int.class, float.class, float.class,
                    int.class, int.class, int.class, int.class, float.class, float.class);

            ctorDT = cDT.getConstructor(int.class, int.class);
            mGetTexData = cDT.getMethod("func_110565_c");
            mUpdateTex = cDT.getMethod("func_110564_a");

            // 图集名字带缩放号: 换缩放时建新的, 不会和旧的互相覆盖
            // A medium-weight body face keeps small labels readable on the
            // dark glass surface without relying on a bright background.
            // Config names are runtime data.  One 2048 atlas holds 324 glyphs
            // at guiScale=4; enough for the static UI, but a pasted/synced list
            // of Chinese config names can exceed it.  Build additional pages
            // instead of falling back those last glyphs to Minecraft's pixel
            // font, which was the visible Paste/config font corruption.
            String remaining = charset;
            for (int page = 0; page < 8 && remaining.length() > 0; page++) {
                Atlas part = build("az_body_s" + scale + "_p" + page,
                        remaining, BODY_SIZE, false, BODY_CELL, scale, tm);
                if (part.glyphs.isEmpty()) break;
                bodyPages.add(part);
                StringBuilder next = new StringBuilder();
                for (int i = 0; i < remaining.length(); i++) {
                    char c = remaining.charAt(i);
                    if (!part.glyphs.containsKey(c)) next.append(c);
                }
                if (next.length() == remaining.length()) break;
                remaining = next.toString();
            }
            if (bodyPages.isEmpty()) throw new IllegalStateException("正文图集没有任何字形");
            body = bodyPages.get(0);
            title = build("az_title_s" + scale, TITLE_CHARS, TITLE_SIZE, true, TITLE_CELL, scale, tm);

            ready = true;
            int bodyGlyphs = 0;
            for (Atlas page : bodyPages) bodyGlyphs += page.glyphs.size();
            Hook.log("TtfFont OK: scale=" + scale + " body=" + bodyGlyphs
                    + "/" + charset.length() + " glyphs in " + bodyPages.size()
                    + " page(s) @" + body.size + "px, title=" + title.glyphs.size()
                    + " @" + title.size + "px");
        } catch (Throwable t) {
            release();
            failed = true;
            Hook.log("TtfFont FAILED (回退 MC 原生字体): " + t);
        }
    }

    private static Font pickFont(boolean bold) {
        String[] prefs = {"Microsoft YaHei UI", "Microsoft YaHei", "Segoe UI", "Tahoma", "Verdana", "Arial"};
        List<String> avail = Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String p : prefs) {
            if (avail.contains(p)) return new Font(p, bold ? Font.BOLD : Font.PLAIN, 12);
        }
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, 12);
    }

    /** 纯光栅化结果 (不碰 MC, 可离线测量) */
    static class Raster {
        BufferedImage img;
        Map<Character, Glyph> glyphs;
        int atlasSize, cell, texCell, scale;
    }

    /**
     * 纯光栅化, 不依赖 MC —— probe 直接调这个来测量真实渲染质量,
     * 而不是在 probe 里另写一份可能跑偏的复制品。
     */
    static Raster rasterize(String charset, float size, boolean bold, int cell, int scale) {
        float px = size * scale;
        int texCell = cell * scale;
        int atlasSize = atlasSizeFor(charset.length(), cell, scale);

        Font font = pickFont(bold).deriveFont(px);
        BufferedImage img = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        // Keep layout metrics at the original size. Supersampling changes coverage,
        // never the cell size, baseline, advance, or the caller's drawing scale.
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);
        g.setFont(font);
        g.setColor(Color.WHITE);

        FontMetrics fm = g.getFontMetrics();
        int ascent = fm.getAscent();
        int cols = atlasSize / texCell;

        // Rasterize one glyph at 2x, then area-average its alpha into the existing
        // atlas. A reusable tile bounds temporary memory even with large CJK pages.
        // Gray coverage works on translucent surfaces without LCD color fringes.
        int sampleCell = texCell * 2;
        BufferedImage tile = new BufferedImage(sampleCell, sampleCell, BufferedImage.TYPE_INT_ARGB);
        Graphics2D ink = tile.createGraphics();
        ink.setRenderingHints(g.getRenderingHints());
        ink.scale(2, 2);
        ink.setFont(font);
        ink.setColor(Color.WHITE);
        int[] samples = ((DataBufferInt) tile.getRaster().getDataBuffer()).getData();
        int[] pixels = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        // Straight-alpha GL filtering must interpolate white coverage against
        // transparent white, not black, otherwise the softened edge darkens twice.
        Arrays.fill(pixels, 0x00FFFFFF);

        Raster r = new Raster();
        r.img = img;
        r.atlasSize = atlasSize;
        r.cell = cell;
        r.texCell = texCell;
        r.scale = scale;
        r.glyphs = new HashMap<Character, Glyph>();

        int i = 0;
        for (int ci = 0; ci < charset.length(); ci++) {
            char c = charset.charAt(ci);
            if (r.glyphs.containsKey(c)) continue;
            int cx = (i % cols) * texCell;
            int cy = (i / cols) * texCell;
            if (cy + texCell > atlasSize) break;   // 图集满, 剩余交给 MC 原生字体
            // The isolated tile also prevents negative bearings (for example j)
            // from contaminating a neighbouring cell during downsampling.
            ink.setComposite(AlphaComposite.Clear);
            ink.fillRect(0, 0, texCell, texCell);
            ink.setComposite(AlphaComposite.SrcOver);
            ink.drawString(String.valueOf(c), 0, ascent);
            for (int y = 0; y < texCell; y++) {
                int source = y * 2 * sampleCell;
                int target = (cy + y) * atlasSize + cx;
                for (int x = 0; x < texCell; x++) {
                    int p = source + x * 2;
                    int alpha = ((samples[p] >>> 24) + (samples[p + 1] >>> 24)
                            + (samples[p + sampleCell] >>> 24) + (samples[p + sampleCell + 1] >>> 24) + 2) / 4;
                    pixels[target + x] = (alpha << 24) | 0x00FFFFFF;
                }
            }
            Glyph gl = new Glyph();
            gl.x = cx;
            gl.y = cy;
            // 步进换算回逻辑单位。保留小数: 每字取整会让一行字的间距逐渐跑偏
            float adv = fm.charWidth(c) / (float) scale;
            if (adv <= 0.5f) adv = size * 0.5f;
            gl.adv = adv;
            r.glyphs.put(c, gl);
            i++;
        }
        ink.dispose();
        g.dispose();
        return r;
    }

    private static Atlas build(String name, String charset, float size, boolean bold,
                               int cell, int scale, Object tm) throws Exception {
        Raster r = rasterize(charset, size, bold, cell, scale);
        if (r.glyphs.size() < charset.length()) {
            Hook.log("TtfFont: 图集 " + name + " 已满 (" + r.glyphs.size() + "/" + charset.length()
                    + "), 剩余字符交给 MC 原生字体");
        }
        Atlas a = new Atlas();
        a.size = r.atlasSize;
        a.cell = r.cell;
        a.texCell = r.texCell;
        a.scale = r.scale;
        a.glyphs = r.glyphs;

        try {
            a.tex = ctorDT.newInstance(r.atlasSize, r.atlasSize);
            int[] px2 = (int[]) mGetTexData.invoke(a.tex);
            r.img.getRGB(0, 0, r.atlasSize, r.atlasSize, px2, 0, r.atlasSize);
            mUpdateTex.invoke(a.tex);
            a.loc = mGetDynLoc.invoke(tm, name, a.tex);
        } catch (Exception failure) {
            releaseAtlas(a);
            throw failure;
        }

        return a;
    }

    /**
     * 选图集规则 —— 真实现和离线探针共用这一份, 探针不另抄。
     *
     * 返回 0=用正文图集, 1=用标题图集, -1=都没有 (交给 MC 原生字体)。
     *
     * 关键: `isTitle=false` 时**根本不看** titleGlyphs。以前这里是一句
     * `if (title.glyphs.containsKey(c)) return title;` —— 而标题图集里收着
     * "AZCLIENT0123456789 .-_:v", 于是正文里每个数字/空格/点/减号/冒号/小写 v
     * 都拿 30pt 的标题字形画, 正文只有 15pt。实测数字墨迹 11px vs 25px = 227%,
     * 空格 250%, 一行带数字的参数名能宽出 30~55px。这就是"字体大小问题 + 各种错位"。
     */
    static int pickAtlasIndex(Map<Character, Glyph> bodyGlyphs, Map<Character, Glyph> titleGlyphs,
                              char c, boolean isTitle) {
        if (isTitle && titleGlyphs != null && titleGlyphs.containsKey(c)) return 1;
        if (bodyGlyphs != null && bodyGlyphs.containsKey(c)) return 0;
        return -1;
    }

    /** 正文图集: 只用正文图集, 不许碰标题图集 */
    private static Atlas bodyAtlas(char c) {
        for (Atlas page : bodyPages) {
            if (page != null && page.glyphs.containsKey(c)) return page;
        }
        return null;
    }

    /** 标题用: 优先标题图集, 缺字回退正文图集, 都没有交给 MC 原生字体 */
    private static Atlas titleAtlas(char c) {
        Map<Character, Glyph> tg = title == null ? null : title.glyphs;
        if (tg != null && tg.containsKey(c)) return title;
        return bodyAtlas(c);
    }

    private static void blit(Atlas a, Glyph g, int x, int y, int argb, float k) throws Exception {
        mBindTex.invoke(texMgr, a.loc);
        if (!a.filtered && mTexParameteri != null) {
            // GL_TEXTURE_2D / GL_TEXTURE_MAG_FILTER / GL_TEXTURE_MIN_FILTER / GL_LINEAR
            mTexParameteri.invoke(null, 3553, 10240, 9729);
            mTexParameteri.invoke(null, 3553, 10241, 9729);
            a.filtered = true;
        }
        mColor.invoke(null,
                ((argb >> 16) & 255) / 255f,
                ((argb >> 8) & 255) / 255f,
                (argb & 255) / 255f,
                ((argb >>> 24) & 255) / 255f);
        // 源 texCell x texCell, 目标边长 = texCell * k。
        //   k = 1 (面板 / 物理像素空间): 图集正是按 S 光栅化的, 一个纹素对一个单位, 不重采样。
        //   k < 1 (逻辑单位空间, 见 logicalScale): 压回原版字号, 这时是放大采样。
        int d = drawCell(a.texCell, k);
        mDrawScaled.invoke(null, x, y, (float) g.x, (float) g.y,
                a.texCell, a.texCell, d, d, (float) a.size, (float) a.size);
    }

    /** 正文宽度 (面板 / 物理像素空间) */
    static int width(String s) { return Math.round(sumAdv(s, false, 1f)); }

    /** 标题宽度 */
    static int titleWidth(String s) { return Math.round(sumAdv(s, true, 1f)); }

    /** 正文宽度 (逻辑单位空间, 见 logicalScale) */
    static int widthLogical(String s) { return Math.round(sumAdv(s, false, logicalScale())); }

    /**
     * 累加步进宽度。
     *
     * **k 只作用在图集字形上; 缺字回退到 MC 原生字体时不能乘** —— 那本来就是原版字号,
     * 乘了会让个别非 ASCII 字符 (比如中文用户名) 缩成三分之一, 比周围的字小一圈。
     */
    private static float sumAdv(String s, boolean isTitle, float k) {
        if (!ready) return fb.width(s);
        float w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Atlas a = isTitle ? titleAtlas(c) : bodyAtlas(c);
            Glyph g = a == null ? null : a.glyphs.get(c);
            w += (g != null) ? g.adv * k : fb.width(String.valueOf(c));
        }
        return w;
    }

    /**
     * 按任意倍率画正文 (HUD 标题 / HUD 整体缩放用)。
     *
     * 和 drawLogical 的区别只是**系数来源**: 那个固定用 logicalScale() 去对齐原版字号,
     * 这个用调用方给的 k。底层 run(...) 本来就吃一个系数, 所以这里不需要第二套绘制代码 ——
     * 这也保证了"量宽度"和"画字"永远共用同一个 k (见 widthScaled)。
     */
    static void drawScaled(String s, int x, int y, int argb, float k) {
        if (!ready) return;
        try {
            run(s, x, y, argb, false, k);
        } catch (Throwable t) {
            failed = true;
            ready = false;
            Hook.log("TtfFont drawScaled 失败, 本次起回退 MC 原生字体: " + t);
        }
    }

    static void drawGradient(String s,int x,int y,int first,int last,float k){
        if(!ready)return;
        try{
            float fx=x,total=Math.max(1,sumAdv(s,false,k));
            for(int i=0;i<s.length();i++){
                char c=s.charAt(i);Atlas a=bodyAtlas(c);Glyph g=a==null?null:a.glyphs.get(c);
                float adv=g==null?fb.width(String.valueOf(c)):g.adv*k;
                int tint=VisualStyle.mix(first,last,(fx-x+adv*.5f)/total);
                if(g==null)fx=fb.drawChar(c,Math.round(fx),y,tint);
                else{blit(a,g,Math.round(fx),y,tint,k);fx+=adv;}
            }
        }catch(Throwable t){failed=true;ready=false;Hook.log("TtfFont gradient fallback: "+t);}
    }

    /** 任意倍率的正文宽度。必须和 drawScaled 用同一个 k, 否则居中/底板宽度会错位。 */
    static int widthScaled(String s, float k) { return Math.round(sumAdv(s, false, k)); }

    static void draw(String s, int x, int y, int argb) {
        if (!ready) return;
        try {
            run(s, x, y, argb, false, 1f);
        } catch (Throwable t) {
            failed = true;
            ready = false;
            Hook.log("TtfFont draw 失败, 本次起回退 MC 原生字体: " + t);
        }
    }

    /** 正文, 压到原版字号 (世界空间那几处文字用这个; 见 logicalScale) */
    static void drawLogical(String s, int x, int y, int argb) {
        if (!ready) return;
        try {
            run(s, x, y, argb, false, logicalScale());
        } catch (Throwable t) {
            failed = true;
            ready = false;
            Hook.log("TtfFont drawLogical 失败, 本次起回退 MC 原生字体: " + t);
        }
    }

    /**
     * 标题。这里刻意不画偏移阴影:
     * 阴影是 (x+1,y+1) 的"逻辑单位"偏移, 在 guiScale=2 下就是屏幕上 2 像素 ——
     * 抗锯齿文字后面叠一份半透明暗色副本, 看起来就是重影/糊成一团。
     * 面板本身是不透明深色底, 阴影本来就没什么用, 去掉反而干净。
     */
    static void drawTitle(String s, int x, int y, int argb) {
        if (!ready) return;
        try {
            run(s, x, y, argb, true, 1f);
        } catch (Throwable t) {
            failed = true;
            ready = false;
        }
    }

    private static void run(String s, int x, int y, int argb, boolean isTitle, float k) throws Exception {
        float fx = x;   // 逻辑单位的浮点推进, 只在贴图时取整
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Atlas a = isTitle ? titleAtlas(c) : bodyAtlas(c);
            Glyph g = a == null ? null : a.glyphs.get(c);
            if (g == null) {
                // 回退字形是 MC 原生的, 本来就是原版大小, 不乘 k —— fx 两边都是逻辑单位, 直接接上
                int nx = fb.drawChar(c, Math.round(fx), y, argb);
                fx = nx;
                continue;
            }
            blit(a, g, Math.round(fx), y, argb, k);
            fx += g.adv * k;
        }
    }

    /** 供 Hook 收集字符集用: 把字符串里的字符并进 charset */
    static void collect(StringBuilder sb, String s) {
        if (s == null) return;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20) continue;
            if (sb.indexOf(String.valueOf(c)) < 0) sb.append(c);
        }
    }
}
