package com.lite.agent;

import java.lang.reflect.*;

/** Middle-click exclusions for the existing 1.20.1 overlay and picker path. */
final class ModernEntityMarks {
    private static Method getMc,button,getEntity;
    private static Field world,player,screen,hit;
    private static Class<?> entityHit;
    private static boolean logged;
    static void poll(long window){
        try{
            if(getMc==null){
                ClassLoader loader=Ver.runtimeLoader();
                Class<?> mc=Class.forName("net.minecraft.client.Minecraft",false,loader);
                world=mc.getField("f_91073_");player=mc.getField("f_91074_");
                screen=mc.getField("f_91080_");hit=mc.getField("f_91077_");
                entityHit=Class.forName("net.minecraft.world.phys.EntityHitResult",false,loader);
                getEntity=entityHit.getMethod("m_82443_");
                button=Class.forName("org.lwjgl.glfw.GLFW",false,loader).getMethod("glfwGetMouseButton",long.class,int.class);
                getMc=mc.getMethod("m_91087_");
            }
            Object mc=getMc.invoke(null),level=world.get(mc),self=player.get(mc);
            EntityExclusions.context(level);
            boolean down=window!=0&&((Number)button.invoke(null,window,2)).intValue()!=0;
            if(!EntityExclusions.pressed(down,self==null||level==null||screen.get(mc)!=null||ModernGui.isOpen()))return;
            Object result=hit.get(mc);if(!entityHit.isInstance(result))return;
            Object entity=getEntity.invoke(result);if(entity==null||entity==self)return;
            ModernGui.status=EntityExclusions.toggle(entity)?"目标已排除，再按中键恢复":"目标已恢复";
        }catch(Throwable failure){
            if(!logged){logged=true;LiteAgent.log("Middle-click exclusions unavailable: "+failure);}
        }
    }
}
