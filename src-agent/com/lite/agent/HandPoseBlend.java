package com.lite.agent;

/** Camera-relative TRS interpolation. Never blends matrix entries or gameplay state. */
final class HandPoseBlend {
    // Only soften adjacent render frames; the independent wrist clock controls the cadence.
    private static final double RESPONSE_NANOS=30000000.0;
    private Object context, item;
    private long sampled;
    private double[] pose;
    void clear(){context=item=null;sampled=0;pose=null;}
    double[] sample(Object context,Object item,double[] target,long now){
        double[] next=decompose(target);
        if(next==null){clear();return target.clone();}
        if(pose==null||this.context!=context||this.item!=item||now<sampled||now-sampled>300000000L){
            pose=next;
        }else{
            // Visual settling only: do not slow the gameplay clock or queue attacks/use.
            double alpha=-Math.expm1(-(now-sampled)/RESPONSE_NANOS);
            for(int i=0;i<6;i++)pose[i]+=(next[i]-pose[i])*alpha;
            slerp(pose,next,alpha);
        }
        this.context=context;this.item=item;sampled=now;return compose(pose);
    }
    static double[] multiply(double[] a,double[] b){
        double[] result=new double[16];
        for(int c=0;c<4;c++)for(int r=0;r<4;r++)for(int k=0;k<4;k++)result[c*4+r]+=a[k*4+r]*b[c*4+k];
        return result;
    }
    static double[] inverse(double[] m){
        double a=m[0],b=m[4],c=m[8],d=m[1],e=m[5],f=m[9],g=m[2],h=m[6],i=m[10];
        double det=a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g);
        if(!Double.isFinite(det)||Math.abs(det)<1e-12)return null;
        double[] out={(e*i-f*h)/det,(f*g-d*i)/det,(d*h-e*g)/det,0,
                (c*h-b*i)/det,(a*i-c*g)/det,(b*g-a*h)/det,0,
                (b*f-c*e)/det,(c*d-a*f)/det,(a*e-b*d)/det,0,0,0,0,1};
        for(int r=0;r<3;r++)out[12+r]=-(out[r]*m[12]+out[4+r]*m[13]+out[8+r]*m[14]);
        return out;
    }
    private static double[] decompose(double[] m){
        if(m==null||m.length!=16)return null;
        for(double n:m)if(!Double.isFinite(n))return null;
        if(Math.abs(m[3])+Math.abs(m[7])+Math.abs(m[11])+Math.abs(m[15]-1)>1e-5)return null;
        double[] p=new double[10];for(int r=0;r<3;r++)p[r]=m[12+r];
        for(int c=0;c<3;c++){
            p[3+c]=Math.sqrt(m[c*4]*m[c*4]+m[c*4+1]*m[c*4+1]+m[c*4+2]*m[c*4+2]);
            if(p[3+c]<1e-8)return null;
        }
        double a=m[0]/p[3],b=m[4]/p[4],c=m[8]/p[5],d=m[1]/p[3],e=m[5]/p[4],f=m[9]/p[5],g=m[2]/p[3],h=m[6]/p[4],i=m[10]/p[5];
        // A foreign renderer's shear/reflection is not a pose we can safely interpolate.
        if(Math.abs(a*b+d*e+g*h)+Math.abs(a*c+d*f+g*i)+Math.abs(b*c+e*f+h*i)>1e-3
                ||a*(e*i-f*h)-b*(d*i-f*g)+c*(d*h-e*g)<.999)return null;
        double s;
        if(a+e+i>0){s=Math.sqrt(a+e+i+1)*2;p[9]=s/4;p[6]=(h-f)/s;p[7]=(c-g)/s;p[8]=(d-b)/s;}
        else if(a>e&&a>i){s=Math.sqrt(1+a-e-i)*2;p[9]=(h-f)/s;p[6]=s/4;p[7]=(b+d)/s;p[8]=(c+g)/s;}
        else if(e>i){s=Math.sqrt(1+e-a-i)*2;p[9]=(c-g)/s;p[6]=(b+d)/s;p[7]=s/4;p[8]=(f+h)/s;}
        else{s=Math.sqrt(1+i-a-e)*2;p[9]=(d-b)/s;p[6]=(c+g)/s;p[7]=(f+h)/s;p[8]=s/4;}
        normalize(p);return p;
    }
    private static void normalize(double[] p){double n=0;for(int i=6;i<10;i++)n+=p[i]*p[i];n=Math.sqrt(n);for(int i=6;i<10;i++)p[i]/=n;}
    private static void slerp(double[] a,double[] b,double alpha){
        double dot=0;for(int i=6;i<10;i++)dot+=a[i]*b[i];double sign=dot<0?-1:1;dot=Math.min(1,Math.abs(dot));
        double x=1-alpha,y=alpha;
        if(dot<.9995){double angle=Math.acos(dot),sin=Math.sin(angle);x=Math.sin((1-alpha)*angle)/sin;y=Math.sin(alpha*angle)/sin;}
        for(int i=6;i<10;i++)a[i]=x*a[i]+y*sign*b[i];normalize(a);
    }
    private static double[] compose(double[] p){
        double x=p[6],y=p[7],z=p[8],w=p[9];
        return new double[]{(1-2*y*y-2*z*z)*p[3],(2*x*y+2*z*w)*p[3],(2*x*z-2*y*w)*p[3],0,
                (2*x*y-2*z*w)*p[4],(1-2*x*x-2*z*z)*p[4],(2*y*z+2*x*w)*p[4],0,
                (2*x*z+2*y*w)*p[5],(2*y*z-2*x*w)*p[5],(1-2*x*x-2*y*y)*p[5],0,p[0],p[1],p[2],1};
    }
}
