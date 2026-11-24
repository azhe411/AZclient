package com.lite.agent;

/** Small wrist compression/rebound around the native guard origin. Render transforms only. */
final class BlockHandMotion {
    private static double ease(double t){return t*t*(3-2*t);}
    static double pulse(double phase){
        if(!Double.isFinite(phase)||phase<0||phase>=1)return 0;
        if(phase<.22)return ease(phase/.22);
        if(phase<.72)return 1-ease((phase-.22)/.5);
        double s=Math.sin(Math.PI*(phase-.72)/.28);return -.10*s*s;
    }
    static double[] apply(double[] guard,double phase,int hand){
        double press=pulse(phase);if(press==0)return guard;
        double side=hand==1?-1:1,gain=hand==1?.65:1;
        double x=Math.toRadians(-12*press*gain),y=Math.toRadians(7*press*side*gain),z=Math.toRadians(8*press*side*gain);
        double cx=Math.cos(x),sx=Math.sin(x),cy=Math.cos(y),sy=Math.sin(y),cz=Math.cos(z),sz=Math.sin(z);
        double[] rx={1,0,0,0,0,cx,sx,0,0,-sx,cx,0,0,0,0,1};
        double[] ry={cy,0,-sy,0,0,1,0,0,sy,0,cy,0,0,0,0,1};
        double[] rz={cz,sz,0,0,-sz,cz,0,0,0,0,1,0,0,0,0,1};
        double[] out=HandPoseBlend.multiply(HandPoseBlend.multiply(HandPoseBlend.multiply(rz,ry),rx),guard);
        // Preserve the native wrist location: rotate basis vectors, not the camera or grip position.
        out[12]=guard[12]-.018*press*side*gain;
        out[13]=guard[13]-.028*press*gain;
        out[14]=guard[14]+.012*press*gain;
        return out;
    }
}
