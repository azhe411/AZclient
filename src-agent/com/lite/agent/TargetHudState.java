package com.lite.agent;

/** Presentation state only: never selects targets or changes combat timing. */
final class TargetHudState {
    Object world, target;
    String name="";
    double health, maximum, bar;
    float alpha;
    private long last;
    void clear(){world=target=null;name="";health=maximum=bar=0;alpha=0;last=0;}
    void update(Object nextWorld,Object nextTarget,String nextName,double hp,double max,long now,boolean animate,double speed){
        if(nextWorld!=world){clear();world=nextWorld;}
        if(nextWorld==null){clear();return;}
        double dt=last==0?.016:Math.max(0,Math.min(.1,(now-last)/1e9));last=now;
        if(nextTarget!=null){
            boolean changed=target!=nextTarget;target=nextTarget;name=nextName==null?"":nextName;
            maximum=Double.isFinite(max)&&max>0?max:0;
            health=Double.isFinite(hp)?Math.max(0,hp):0;
            double fraction=maximum>0?Math.min(1,health/maximum):0;
            if(changed||!animate)bar=fraction;else bar+=(fraction-bar)*(1-Math.exp(-12*dt*speed));
        }
        float wanted=nextTarget==null?0:1;
        alpha=animate?(float)(alpha+(wanted-alpha)*(1-Math.exp(-14*dt*speed))):wanted;
        if(nextTarget==null&&alpha<.005f){target=null;alpha=0;}
    }
    static double scanFraction(long now,double seconds){
        double cycle=(now/1e9)/Math.max(.25,seconds);
        return .5-.5*Math.cos((cycle-Math.floor(cycle))*Math.PI*2);
    }
    /** Clip before the ESP raster-line path; near-plane points cannot create huge stripes. */
    static float[] clip(float x0,float y0,float x1,float y1,int w,int h){
        if(!Float.isFinite(x0)||!Float.isFinite(y0)||!Float.isFinite(x1)||!Float.isFinite(y1)||w<=0||h<=0)return null;
        double dx=(double)x1-x0,dy=(double)y1-y0,lo=0,hi=1;
        double[] p={-dx,dx,-dy,dy},q={x0,w-1d-x0,y0,h-1d-y0};
        for(int i=0;i<4;i++){
            if(p[i]==0){if(q[i]<0)return null;continue;}
            double r=q[i]/p[i];if(p[i]<0)lo=Math.max(lo,r);else hi=Math.min(hi,r);
            if(lo>hi)return null;
        }
        return new float[]{(float)(x0+lo*dx),(float)(y0+lo*dy),(float)(x0+hi*dx),(float)(y0+hi*dy)};
    }
}
