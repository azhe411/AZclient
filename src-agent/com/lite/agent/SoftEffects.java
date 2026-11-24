package com.lite.agent;
import java.lang.reflect.*;
/** One cached Gaussian texture; submitted through Minecraft's textured GUI quad path. */
final class SoftEffects {
 private static Object manager,location,texture;private static Method bind,draw,color,push,pop,translate,rotate;private static boolean failed;
 private static final int SIZE=96;
 private static void init()throws Exception{
  if(location!=null)return;
  Class<?> mc=Class.forName("net.minecraft.client.Minecraft"),tm=Class.forName("net.minecraft.client.renderer.texture.TextureManager"),
   dt=Class.forName("net.minecraft.client.renderer.texture.DynamicTexture"),rl=Class.forName("net.minecraft.util.ResourceLocation"),gl=Class.forName("net.minecraft.client.renderer.GlStateManager");
  manager=mc.getMethod("func_110434_K").invoke(mc.getMethod("func_71410_x").invoke(null));
  texture=dt.getConstructor(int.class,int.class).newInstance(SIZE,SIZE);int[] pixels=(int[])dt.getMethod("func_110565_c").invoke(texture);
  for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++){
   float dx=(x+0.5f-SIZE/2f)/(SIZE/2f),dy=(y+0.5f-SIZE/2f)/(SIZE/2f);
   int a=Math.round(255*VisualStyle.gaussian((float)Math.sqrt(dx*dx+dy*dy),0.29f));pixels[y*SIZE+x]=(a<<24)|0xFFFFFF;
  }
  dt.getMethod("func_110564_a").invoke(texture);location=tm.getMethod("func_110578_a",String.class,dt).invoke(manager,"az_sunset_glow",texture);
  bind=tm.getMethod("func_110577_a",rl);
  try{bind.invoke(manager,location);Method filter=Class.forName("org.lwjgl.opengl.GL11").getMethod("glTexParameteri",int.class,int.class,int.class);filter.invoke(null,3553,10240,9729);filter.invoke(null,3553,10241,9729);}catch(ReflectiveOperationException ignored){}
  color=gl.getMethod("func_179131_c",float.class,float.class,float.class,float.class);
  draw=Class.forName("net.minecraft.client.gui.Gui").getMethod("func_152125_a",int.class,int.class,float.class,float.class,int.class,int.class,int.class,int.class,float.class,float.class);
  push=gl.getMethod("func_179094_E");pop=gl.getMethod("func_179121_F");translate=gl.getMethod("func_179109_b",float.class,float.class,float.class);rotate=gl.getMethod("func_179114_b",float.class,float.class,float.class,float.class);
 }
 static boolean glow(int x,int y,int w,int h,int argb,float strength){
  if(failed||w<=0||h<=0||strength<=0)return false;
  try{init();bind.invoke(manager,location);color.invoke(null,((argb>>16)&255)/255f,((argb>>8)&255)/255f,(argb&255)/255f,Math.min(1,strength)*((argb>>>24)/255f));
   draw.invoke(null,x,y,0f,0f,SIZE,SIZE,w,h,(float)SIZE,(float)SIZE);return true;
  }catch(Throwable e){failed=true;Hook.log("Gaussian glow fallback: "+e);return false;}
  finally{try{if(color!=null)color.invoke(null,1f,1f,1f,1f);}catch(Exception ignored){}}
 }
 static void line(float x0,float y0,float x1,float y1,int argb,int radius,float strength){
  if(failed||radius<=0||strength<=0)return;boolean saved=false;
  try{init();push.invoke(null);saved=true;translate.invoke(null,x0,y0,0f);rotate.invoke(null,(float)Math.toDegrees(Math.atan2(y1-y0,x1-x0)),0f,0f,1f);
   int len=Math.max(1,Math.round((float)Math.hypot(x1-x0,y1-y0)));glow(-radius,-radius,len+2*radius,2*radius,argb,strength);
  }catch(Throwable e){failed=true;Hook.log("Gaussian line fallback: "+e);}finally{try{if(saved)pop.invoke(null);}catch(Exception ignored){}}
 }
 /** Sample the center strip: brightness varies across the line, never along its length. */
 static void uniformLine(float x0,float y0,float x1,float y1,int argb,int radius,float strength){
  if(failed||radius<=0||strength<=0)return;boolean saved=false;
  try{init();push.invoke(null);saved=true;translate.invoke(null,x0,y0,0f);rotate.invoke(null,(float)Math.toDegrees(Math.atan2(y1-y0,x1-x0)),0f,0f,1f);
   int len=Math.max(1,Math.round((float)Math.hypot(x1-x0,y1-y0)));bind.invoke(manager,location);
   color.invoke(null,((argb>>16)&255)/255f,((argb>>8)&255)/255f,(argb&255)/255f,Math.min(1,strength)*((argb>>>24)/255f));
   draw.invoke(null,0,-radius,SIZE/2f-1,0f,2,SIZE,len,2*radius,(float)SIZE,(float)SIZE);
  }catch(Throwable e){failed=true;Hook.log("Uniform glow fallback: "+e);}
  finally{try{if(color!=null)color.invoke(null,1f,1f,1f,1f);if(saved)pop.invoke(null);}catch(Exception ignored){}}
 }
 static void release(){
  try{if(location!=null)manager.getClass().getMethod("func_147645_c",location.getClass()).invoke(manager,location);}catch(Exception ignored){}
  // 1.8.9 retains deleted dynamic textures. Remove only this resource's exact entry.
  if(manager!=null)for(Class<?> c=manager.getClass();c!=null;c=c.getSuperclass())for(Field f:c.getDeclaredFields()){
   if(!java.util.Map.class.isAssignableFrom(f.getType()))continue;
   try{f.setAccessible(true);java.util.Map<?,?> map=(java.util.Map<?,?>)f.get(manager);if(map!=null&&location!=null&&map.get(location)==texture)map.remove(location);}catch(Exception ignored){}
  }
  location=texture=null;
 }
}
