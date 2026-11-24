package com.lite.agent;

import java.lang.reflect.*;
import java.util.*;

/** Read-only entity overlay. Uses the exact world-render matrices, not player yaw or a guessed FOV. */
public final class ModernEsp {
    private ModernEsp() {}
    static boolean enabled, players=true, creatures=true, invisible;
    static int range=96, thickness=1, drawn;
    private static Method getMc, entities, cameraPosition, poseLast, poseMatrix, matrixGet;
    private static Method alive, hidden, bounds, ex, ey, ez;
    private static Field level, localPlayer, oldX, oldY, oldZ;
    private static Field[] vec, box;
    private static Class<?> playerClass, livingClass;
    private static Object capturedWorld;
    private static List<double[]> rectangles=Collections.emptyList();
    private static boolean initialized, errorLogged;
    static void beginFrame(){rectangles=Collections.emptyList();capturedWorld=null;drawn=0;}
    private static Class<?> type(String name)throws ClassNotFoundException{return Class.forName(name,false,Ver.runtimeLoader());}
    private static void init()throws Exception{
        if(initialized)return;
        Class<?> mc=type("net.minecraft.client.Minecraft");
        getMc=mc.getMethod("m_91087_");level=mc.getField("f_91073_");localPlayer=mc.getField("f_91074_");
        entities=type("net.minecraft.client.multiplayer.ClientLevel").getMethod("m_104735_");
        cameraPosition=type("net.minecraft.client.Camera").getMethod("m_90583_");
        poseLast=type("com.mojang.blaze3d.vertex.PoseStack").getMethod("m_85850_");
        poseMatrix=type("com.mojang.blaze3d.vertex.PoseStack$Pose").getMethod("m_252922_");
        matrixGet=type("org.joml.Matrix4f").getMethod("get",float[].class);
        Class<?> entity=type("net.minecraft.world.entity.Entity");
        alive=entity.getMethod("m_6084_");hidden=entity.getMethod("m_20145_");bounds=entity.getMethod("m_20191_");
        ex=entity.getMethod("m_20185_");ey=entity.getMethod("m_20186_");ez=entity.getMethod("m_20189_");
        // LevelRenderer interpolates xOld/yOld/zOld, not xo/yo/zo used by some physics code.
        oldX=entity.getField("f_19790_");oldY=entity.getField("f_19791_");oldZ=entity.getField("f_19792_");
        vec=fields(type("net.minecraft.world.phys.Vec3"),"f_82479_","f_82480_","f_82481_");
        box=fields(type("net.minecraft.world.phys.AABB"),"f_82288_","f_82289_","f_82290_","f_82291_","f_82292_","f_82293_");
        playerClass=type("net.minecraft.world.entity.player.Player");livingClass=type("net.minecraft.world.entity.LivingEntity");
        initialized=true;
    }
    private static Field[] fields(Class<?> c,String...names)throws Exception{
        Field[] f=new Field[names.length];for(int i=0;i<f.length;i++)f[i]=c.getField(names[i]);return f;
    }
    /** Called at LevelRenderer.renderLevel entry, after vanilla camera, bobbing and FOV setup. */
    public static void capture(Object pose,float partial,Object camera,Object projection){
        if(!enabled)return;
        try{
            init();Object mc=getMc.invoke(null),world=level.get(mc),self=localPlayer.get(mc);
            if(world==null||self==null)return;
            float[] view=new float[16],proj=new float[16];
            matrixGet.invoke(poseMatrix.invoke(poseLast.invoke(pose)),(Object)view);
            matrixGet.invoke(projection,(Object)proj);
            double[] transform=multiply(proj,view);
            Object position=cameraPosition.invoke(camera);
            double cx=vec[0].getDouble(position),cy=vec[1].getDouble(position),cz=vec[2].getDouble(position);
            double p=Math.max(0,Math.min(1,partial)),limit=(double)range*range;
            List<double[]> next=new ArrayList<double[]>();
            for(Object entity:(Iterable<?>)entities.invoke(world)){
                if(entity==self||!livingClass.isInstance(entity)||!(Boolean)alive.invoke(entity))continue;
                if(EntityExclusions.contains(entity))continue;
                if(playerClass.isInstance(entity)?!players:!creatures)continue;
                if(!invisible&&(Boolean)hidden.invoke(entity))continue;
                double nx=((Number)ex.invoke(entity)).doubleValue(),ny=((Number)ey.invoke(entity)).doubleValue(),nz=((Number)ez.invoke(entity)).doubleValue();
                double ix=lerp(oldX.getDouble(entity),nx,p),iy=lerp(oldY.getDouble(entity),ny,p),iz=lerp(oldZ.getDouble(entity),nz,p);
                if(sq(ix-cx)+sq(iy-cy)+sq(iz-cz)>limit)continue;
                Object aabb=bounds.invoke(entity);double[] b=new double[6];
                for(int i=0;i<6;i++)b[i]=box[i].getDouble(aabb)+(i%3==0?ix-nx-cx:i%3==1?iy-ny-cy:iz-nz-cz);
                double[] rect=project(transform,b);
                if(rect!=null)next.add(rect);
            }
            capturedWorld=world;rectangles=next;
        }catch(Throwable e){
            beginFrame();
            if(!errorLogged){errorLogged=true;ModernGui.failure("ESP projection",e);}
            ModernGui.status="ESP 渲染失败，请查看日志";
        }
    }
    static void draw(Object graphics)throws Exception{
        drawn=0;if(!enabled||capturedWorld==null)return;
        if(level.get(getMc.invoke(null))!=capturedWorld){beginFrame();return;}
        int width=ModernGui.width(graphics),height=ModernGui.height(graphics),t=thickness;
        for(double[] r:rectangles){
            int l=(int)Math.floor(r[0]*width),top=(int)Math.floor(r[1]*height);
            int right=(int)Math.ceil(r[2]*width),bottom=(int)Math.ceil(r[3]*height);
            if(right-l<2||bottom-top<2)continue;
            int tint=ModernGui.sunset(r[1]*.4);
            for(int spread=2;spread>0;spread--)outline(graphics,l-spread,top-spread,right+spread,bottom+spread,t,((spread==2?4:9)<<24)|(tint&0xFFFFFF));
            outline(graphics,l,top,right,bottom,t,VisualStyle.mix(tint,0xFFFFF4F8,.28f));drawn++;
        }
    }
    private static void outline(Object g,int l,int top,int r,int b,int t,int color)throws Exception{
        ModernGui.rect(g,l,top,r,Math.min(b,top+t),color);ModernGui.rect(g,l,Math.max(top,b-t),r,b,color);
        ModernGui.rect(g,l,top,Math.min(r,l+t),b,color);ModernGui.rect(g,Math.max(l,r-t),top,r,b,color);
    }
    static double lerp(double old,double now,double p){return old+(now-old)*p;}
    private static double sq(double a){return a*a;}
    static double[] multiply(float[] a,float[] b){
        double[] out=new double[16];
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)for(int k=0;k<4;k++)out[c*4+r]+=a[k*4+r]*b[c*4+k];
        return out;
    }
    /** Clip all box edges to the near plane before the perspective divide. Coordinates are normalized to GUI size. */
    static double[] project(double[] m,double[] box){
        double[][] corners=new double[8][4];
        for(int i=0;i<8;i++){
            double x=box[(i&1)==0?0:3],y=box[(i&2)==0?1:4],z=box[(i&4)==0?2:5];
            for(int r=0;r<4;r++)corners[i][r]=m[r]*x+m[4+r]*y+m[8+r]*z+m[12+r];
        }
        double[] ext={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        for(int i=0;i<8;i++)for(int bit=1;bit<=4;bit<<=1)if((i&bit)==0){
            double[] a=corners[i].clone(),b=corners[i|bit].clone();
            if(!clip(a,b,2) || !clip(a,b,3))continue;
            add(ext,a);add(ext,b);
        }
        if(!Double.isFinite(ext[0])||ext[2]<-1||ext[0]>1||ext[3]<-1||ext[1]>1)return null;
        return new double[]{Math.max(0,(ext[0]+1)*.5),Math.max(0,(1-ext[3])*.5),
                Math.min(1,(ext[2]+1)*.5),Math.min(1,(1-ext[1])*.5)};
    }
    private static boolean clip(double[] a,double[] b,int plane){
        double da=plane==2?a[2]+a[3]:a[3]-1e-6,db=plane==2?b[2]+b[3]:b[3]-1e-6;
        if(da<0&&db<0)return false;
        if(da<0||db<0){double t=da/(da-db);double[] clipped=new double[4];
            for(int i=0;i<4;i++)clipped[i]=a[i]+t*(b[i]-a[i]);
            System.arraycopy(clipped,0,da<0?a:b,0,4);}
        return true;
    }
    private static void add(double[] e,double[] p){
        double x=p[0]/p[3],y=p[1]/p[3];if(!Double.isFinite(x)||!Double.isFinite(y))return;
        e[0]=Math.min(e[0],x);e[1]=Math.min(e[1],y);e[2]=Math.max(e[2],x);e[3]=Math.max(e[3],y);
    }
}
