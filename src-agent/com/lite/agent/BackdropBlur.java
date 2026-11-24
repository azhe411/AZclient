package com.lite.agent;
import java.lang.reflect.Method;
/** Cached separable five-tap backdrop convolution on the render thread. */
final class BackdropBlur {
 private static Class<?> gl;
 private static Method gen,bind,param,copyImage,copySub,enable,disable,blend,begin,end,color,uv,vertex,push,pop,delete,env;
 private static int texture,width,height;
 private static void init() throws Exception {
  if(gl!=null)return;Class<?> c=Class.forName("org.lwjgl.opengl.GL11");
  gen=c.getMethod("glGenTextures");bind=c.getMethod("glBindTexture",int.class,int.class);
  param=c.getMethod("glTexParameteri",int.class,int.class,int.class);
  copyImage=c.getMethod("glCopyTexImage2D",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
  copySub=c.getMethod("glCopyTexSubImage2D",int.class,int.class,int.class,int.class,int.class,int.class,int.class,int.class);
  enable=c.getMethod("glEnable",int.class);disable=c.getMethod("glDisable",int.class);blend=c.getMethod("glBlendFunc",int.class,int.class);
  begin=c.getMethod("glBegin",int.class);end=c.getMethod("glEnd");color=c.getMethod("glColor4f",float.class,float.class,float.class,float.class);
  uv=c.getMethod("glTexCoord2f",float.class,float.class);vertex=c.getMethod("glVertex2f",float.class,float.class);
  push=c.getMethod("glPushAttrib",int.class);pop=c.getMethod("glPopAttrib");delete=c.getMethod("glDeleteTextures",int.class);
  env=c.getMethod("glTexEnvi",int.class,int.class,int.class);gl=c;
 }
 static float[] alphas(){float[] w={1,4,6,4,1},a=new float[5];float sum=0;for(int i=0;i<5;i++){sum+=w[i];a[i]=w[i]/sum;}return a;}
 static void draw(int w,int h,float radius)throws Exception{
  if(w<=0||h<=0)return;init();boolean saved=false;
  try{
   push.invoke(null,0x000FFFFF);saved=true;
   if(texture==0)texture=((Number)gen.invoke(null)).intValue();bind.invoke(null,3553,texture);
   param.invoke(null,3553,10241,9729);param.invoke(null,3553,10240,9729);param.invoke(null,3553,10242,33071);param.invoke(null,3553,10243,33071);
   if(width!=w||height!=h){copyImage.invoke(null,3553,0,6407,0,0,w,h,0);width=w;height=h;}
   else copySub.invoke(null,3553,0,0,0,0,0,w,h);
   env.invoke(null,8960,8704,8448);enable.invoke(null,3553);enable.invoke(null,3042);
   for(int cap:new int[]{2929,2884,3008,3089,2912,2896})disable.invoke(null,cap);
   blend.invoke(null,770,771);float blur=Math.max(2f,Math.min(24f,radius));
   pass(w,h,blur,true);copySub.invoke(null,3553,0,0,0,0,0,w,h);pass(w,h,blur,false);
  }catch(Exception failure){release();throw failure;}finally{if(saved)pop.invoke(null);}
 }
 private static void pass(int w,int h,float radius,boolean horizontal)throws Exception{
  float[] a=alphas();boolean drawing=false;
  try{begin.invoke(null,7);drawing=true;
   for(int i=0;i<5;i++){
    float delta=(i-2)*radius*.5f,du=horizontal?delta/w:0,dv=horizontal?0:delta/h;
    color.invoke(null,1f,1f,1f,a[i]);uv.invoke(null,du,1f+dv);vertex.invoke(null,0f,0f);
    uv.invoke(null,1f+du,1f+dv);vertex.invoke(null,(float)w,0f);
    uv.invoke(null,1f+du,dv);vertex.invoke(null,(float)w,(float)h);uv.invoke(null,du,dv);vertex.invoke(null,0f,(float)h);
   }
  }finally{if(drawing)end.invoke(null);}
 }
 static void release(){try{if(texture!=0&&delete!=null)delete.invoke(null,texture);}catch(Exception e){Hook.log("backdrop release: "+e);}texture=width=height=0;}
}
