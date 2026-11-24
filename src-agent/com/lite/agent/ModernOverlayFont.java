package com.lite.agent;

import java.awt.image.BufferedImage;
import java.io.*;
import java.lang.reflect.*;
import javax.imageio.ImageIO;

/** The same Exo atlas through 1.20.1 GuiGraphics; never uses fixed-function GL. */
final class ModernOverlayFont {
    private static Object graphics,manager;
    private static Method blit,color,release,register,read,filter,pose,push,pop,scale,guiScale,getWindow,getMc,flush;
    private static Constructor<?> texture;
    private static Class<?> render,gl;
    private static boolean reported;
    static final class Atlas {final Object location;final int size;Atlas(Object l,int s){location=l;size=s;}}
    private static Class<?> type(String n)throws Exception{return Class.forName(n,false,Ver.runtimeLoader());}
    private static void init()throws Exception{
        if(manager!=null)return;
        Class<?> mc=type("net.minecraft.client.Minecraft"),g=type("net.minecraft.client.gui.GuiGraphics"),
                image=type("com.mojang.blaze3d.platform.NativeImage"),dynamic=type("net.minecraft.client.renderer.texture.DynamicTexture"),
                tm=type("net.minecraft.client.renderer.texture.TextureManager"),location=type("net.minecraft.resources.ResourceLocation"),
                stack=type("com.mojang.blaze3d.vertex.PoseStack");
        getMc=mc.getMethod("m_91087_");getWindow=mc.getMethod("m_91268_");
        guiScale=type("com.mojang.blaze3d.platform.Window").getMethod("m_85449_");
        texture=dynamic.getConstructor(image);read=image.getMethod("m_85058_",InputStream.class);
        filter=dynamic.getMethod("m_117960_",boolean.class,boolean.class);
        register=tm.getMethod("m_118490_",String.class,dynamic);release=tm.getMethod("m_118513_",location);
        blit=g.getMethod("m_280163_",location,int.class,int.class,float.class,float.class,int.class,int.class,int.class,int.class);
        color=g.getMethod("m_280246_",float.class,float.class,float.class,float.class);flush=g.getMethod("m_280262_");
        pose=g.getMethod("m_280168_");push=stack.getMethod("m_85836_");pop=stack.getMethod("m_85849_");
        scale=stack.getMethod("m_85841_",float.class,float.class,float.class);
        render=type("com.mojang.blaze3d.systems.RenderSystem");gl=type("org.lwjgl.opengl.GL11");
        manager=mc.getMethod("m_91097_").invoke(getMc.invoke(null));
    }
    static Object upload(BufferedImage bitmap,String name)throws Exception{
        init();ByteArrayOutputStream bytes=new ByteArrayOutputStream();ImageIO.write(bitmap,"png",bytes);
        Object image=read.invoke(null,new ByteArrayInputStream(bytes.toByteArray()));Object tex=null;
        try{
            tex=texture.newInstance(image);filter.invoke(tex,true,false);
            Object loc=register.invoke(manager,name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_./-]","_"),tex);
            return new Atlas(loc,bitmap.getWidth());
        }catch(Exception e){if(tex!=null)tex.getClass().getMethod("close").invoke(tex);else image.getClass().getMethod("close").invoke(image);throw e;}
    }
    static void release(Object a){try{release.invoke(manager,((Atlas)a).location);}catch(Exception e){failure(e);}}
    static void textState()throws Exception{render.getMethod("enableBlend").invoke(null);render.getMethod("defaultBlendFunc").invoke(null);}
    static void ink(Object texture,int sx,int sy,int w,int h,float x,float y,int tint)throws Exception{
        Atlas a=(Atlas)texture;
        color.invoke(graphics,((tint>>16)&255)/255f,((tint>>8)&255)/255f,(tint&255)/255f,(tint>>>24)/255f);
        textState();blit.invoke(graphics,a.location,Math.round(x),Math.round(y),(float)sx,(float)sy,w,h,a.size,a.size);
    }
    private static boolean enabled(int cap)throws Exception{return (Boolean)gl.getMethod("glIsEnabled",int.class).invoke(null,cap);}
    static void draw(Object g,String value,int x,int y,int tint)throws Exception{
        init();Object stack=pose.invoke(g);boolean pushed=false;graphics=g;
        float[] saved=((float[])render.getMethod("getShaderColor").invoke(null)).clone();
        boolean blend=enabled(3042),depth=enabled(2929);
        int[] keys={32969,32968,32971,32970},factors=new int[4];for(int i=0;i<4;i++)factors[i]=(Integer)gl.getMethod("glGetInteger",int.class).invoke(null,keys[i]);
        try{
            flush.invoke(g);float density=((Number)guiScale.invoke(getWindow.invoke(getMc.invoke(null)))).floatValue();
            push.invoke(stack);pushed=true;scale.invoke(stack,1/density,1/density,1f);
            VeilFont.Face f=VeilFont.face(12*density,false);
            f.draw(OverlayFont.plain(value),x*density,y*density+f.ascent,tint,.015f*f.size,false);
        }finally{
            if(pushed)pop.invoke(stack);color.invoke(g,saved[0],saved[1],saved[2],saved[3]);
            render.getMethod("blendFuncSeparate",int.class,int.class,int.class,int.class).invoke(null,factors[0],factors[1],factors[2],factors[3]);
            render.getMethod(blend?"enableBlend":"disableBlend").invoke(null);render.getMethod(depth?"enableDepthTest":"disableDepthTest").invoke(null);graphics=null;
        }
    }
    static void failure(Exception e){if(!reported){reported=true;ModernGui.failure("Overlay font",e);}}
}
