package com.lite.agent;
import java.lang.reflect.Method;
import java.nio.*;
/** Nested physical-pixel clipping. Existing scissor state is restored even when drawing fails. */
final class GuiClip implements AutoCloseable {
 private static Method enabled,get,scissor,enable,disable;private static boolean unavailable;
 private boolean active,was;private int[] box;
 static GuiClip begin(int x,int y,int w,int h,int physicalHeight){
  GuiClip c=new GuiClip();if(unavailable)return c;
  try{
   if(scissor==null){Class<?> g=Class.forName("org.lwjgl.opengl.GL11");enabled=g.getMethod("glIsEnabled",int.class);try{get=g.getMethod("glGetIntegerv",int.class,IntBuffer.class);}catch(NoSuchMethodException e){get=g.getMethod("glGetInteger",int.class,IntBuffer.class);}scissor=g.getMethod("glScissor",int.class,int.class,int.class,int.class);enable=g.getMethod("glEnable",int.class);disable=g.getMethod("glDisable",int.class);}
   c.was=(Boolean)enabled.invoke(null,3089);IntBuffer b=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();get.invoke(null,3088,b);c.box=new int[]{b.get(0),b.get(1),b.get(2),b.get(3)};
   int l=Math.max(0,x),bottom=Math.max(0,physicalHeight-y-h),right=x+Math.max(0,w),top=physicalHeight-y;
   if(c.was){l=Math.max(l,c.box[0]);bottom=Math.max(bottom,c.box[1]);right=Math.min(right,c.box[0]+c.box[2]);top=Math.min(top,c.box[1]+c.box[3]);}
   c.active=true;enable.invoke(null,3089);scissor.invoke(null,l,bottom,Math.max(0,right-l),Math.max(0,top-bottom));
  }catch(Throwable e){c.close();unavailable=true;Hook.logStack("GUI clipping fallback",e);}return c;
 }
 static GuiClip logical(int x,int y,int w,int h,int logicalWidth,int logicalHeight){
  try{
   Class<?> g=Class.forName("org.lwjgl.opengl.GL11");Method query;
   try{query=g.getMethod("glGetIntegerv",int.class,IntBuffer.class);}catch(NoSuchMethodException e){query=g.getMethod("glGetInteger",int.class,IntBuffer.class);}
   IntBuffer v=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asIntBuffer();query.invoke(null,2978,v);
   if(v.get(2)<=0||v.get(3)<=0)return new GuiClip();
   float sx=v.get(2)/(float)Math.max(1,logicalWidth),sy=v.get(3)/(float)Math.max(1,logicalHeight);
   int l=v.get(0)+(int)Math.floor(x*sx),top=(int)Math.floor(y*sy);
   return begin(l,top,(int)Math.ceil((x+w)*sx)+v.get(0)-l,(int)Math.ceil((y+h)*sy)-top,v.get(1)+v.get(3));
  }catch(Exception e){return new GuiClip();}
 }
 boolean active(){return active;}
 public void close(){if(!active)return;try{scissor.invoke(null,box[0],box[1],box[2],box[3]);(was?enable:disable).invoke(null,3089);}catch(Exception ignored){}active=false;}
}
