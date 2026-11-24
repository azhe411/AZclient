package com.lite.agent;

import java.lang.reflect.Method;
import java.nio.*;

/** Smooths only the item transform between native push/pop; camera motion stays immediate. */
final class BlockHandRenderer {
    private static final HandPoseBlend[] blends={new HandPoseBlend(),new HandPoseBlend()};
    private static final FloatBuffer buffer=ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private static Method get,load,mode;
    private static double[] inverseBase,base;
    private static Object player,item;
    private static int hand;
    private static boolean active;
    private static double phase=Double.NaN;
    private static boolean ready()throws Exception{
        if(get==null){
            ClassLoader loader=Ver.runtimeLoader();
            Class<?> gl=Class.forName("org.lwjgl.opengl.GL11",false,loader==null?BlockHandRenderer.class.getClassLoader():loader);
            get=gl.getMethod("glGetFloat",int.class,FloatBuffer.class);
            load=gl.getMethod("glLoadMatrix",FloatBuffer.class);
            mode=gl.getMethod("glGetInteger",int.class);
        }
        return ((Number)mode.invoke(null,2976)).intValue()==5888; // MATRIX_MODE, MODELVIEW
    }
    private static double[] read()throws Exception{
        buffer.clear();get.invoke(null,2982,buffer);double[] result=new double[16];
        for(int i=0;i<16;i++)result[i]=buffer.get(i);return result;
    }
    static void begin(Object who,Object stack,int which){
        begin(who,stack,which,Double.NaN);
    }
    static void begin(Object who,Object stack,int which,double motionPhase){
        phase=motionPhase;
        active=false;hand=which==1?1:0;
        try{
            if(who==null||stack==null){blends[hand].clear();return;}
            if(!ready()){blends[hand].clear();return;}
            base=read();inverseBase=HandPoseBlend.inverse(base);
            player=who;item=stack;active=inverseBase!=null;
        }catch(Throwable failure){blends[hand].clear();Hook.logOnce("Block hand begin",failure);}
    }
    static void draw(){
        if(!active)return;
        active=false;
        try{
            if(!ready())return;
            double[] local=HandPoseBlend.multiply(inverseBase,read());
            local=BlockHandMotion.apply(local,phase,hand);
            double[] smooth=blends[hand].sample(player,item,local,System.nanoTime());
            double[] result=HandPoseBlend.multiply(base,smooth);
            buffer.clear();for(double n:result)buffer.put((float)n);buffer.flip();load.invoke(null,buffer);
        }catch(Throwable failure){blends[hand].clear();Hook.logOnce("Block hand draw",failure);}
    }
    static void end(){active=false;player=item=null;base=inverseBase=null;phase=Double.NaN;}
}
