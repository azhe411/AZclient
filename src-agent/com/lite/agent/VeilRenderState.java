package com.lite.agent;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Legacy HUD compositing scope. Use Minecraft's setters so its cached state
 * agrees with OpenGL both during the pass and after an early return/failure. */
final class VeilRenderState implements AutoCloseable {
    private static Method isEnabled,getInt,getFloat,alphaOn,alphaOff,blendOn,blendOff,
            depthOn,depthOff,textureOn,textureOff,blendFunc,color,bind;
    private final boolean alpha,blend,depth,texture;
    private final int srcRgb,dstRgb,srcAlpha,dstAlpha,binding;
    private final float r,g,b,a;
    private boolean closed;

    private static void init() throws Exception {
        if(isEnabled!=null)return;
        Class<?> gl=Class.forName("org.lwjgl.opengl.GL11");
        Class<?> mc=Class.forName("net.minecraft.client.renderer.GlStateManager");
        alphaOn=mc.getMethod("func_179141_d");alphaOff=mc.getMethod("func_179118_c");
        blendOn=mc.getMethod("func_179147_l");blendOff=mc.getMethod("func_179084_k");
        depthOn=mc.getMethod("func_179126_j");depthOff=mc.getMethod("func_179097_i");
        textureOn=mc.getMethod("func_179098_w");textureOff=mc.getMethod("func_179090_x");
        blendFunc=mc.getMethod("func_179120_a",int.class,int.class,int.class,int.class);
        color=mc.getMethod("func_179131_c",float.class,float.class,float.class,float.class);
        bind=mc.getMethod("func_179144_i",int.class);
        getInt=gl.getMethod("glGetInteger",int.class);
        try{getFloat=gl.getMethod("glGetFloatv",int.class,FloatBuffer.class);}
        catch(NoSuchMethodException lwjgl2){getFloat=gl.getMethod("glGetFloat",int.class,FloatBuffer.class);}
        isEnabled=gl.getMethod("glIsEnabled",int.class);
    }
    private static boolean enabled(int cap)throws Exception{return (Boolean)isEnabled.invoke(null,cap);}
    private static int integer(int key)throws Exception{return (Integer)getInt.invoke(null,key);}
    static VeilRenderState begin() throws Exception {init();return new VeilRenderState();}
    private VeilRenderState() throws Exception {
        alpha=enabled(3008);blend=enabled(3042);depth=enabled(2929);texture=enabled(3553);
        srcRgb=integer(32969);dstRgb=integer(32968);srcAlpha=integer(32971);dstAlpha=integer(32970);binding=integer(32873);
        FloatBuffer rgba=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
        getFloat.invoke(null,2816,rgba);r=rgba.get(0);g=rgba.get(1);b=rgba.get(2);a=rgba.get(3);
        try{alphaOff.invoke(null);depthOff.invoke(null);textureOn.invoke(null);text();}
        catch(Exception e){close();throw e;}
    }
    /** Gui.drawRect disables blending. Re-establish it for each string, including
     * the count after its connector and rows after an animated arrival cue. */
    static void text() throws Exception {
        if(Ver.is1201()){ModernOverlayFont.textState();return;}
        init();alphaOff.invoke(null);blendOn.invoke(null);blendFunc.invoke(null,770,771,1,0);
    }
    public void close() throws Exception {
        if(closed)return;closed=true;
        bind.invoke(null,binding);color.invoke(null,r,g,b,a);
        blendFunc.invoke(null,srcRgb,dstRgb,srcAlpha,dstAlpha);
        (texture?textureOn:textureOff).invoke(null);(depth?depthOn:depthOff).invoke(null);
        (blend?blendOn:blendOff).invoke(null);(alpha?alphaOn:alphaOff).invoke(null);
    }
}
