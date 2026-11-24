package com.lite.agent;
/** Portable exterior contour fallback. Disjoint bands cannot brighten the panel interior. */
final class FrameGlow {
 interface Painter {void rect(int x,int y,int right,int bottom,int color)throws Exception;}
 private static double inset(int y,int width,int height,int radius,int expand){
  double yy=y+.5+expand,rr=Math.min(radius,Math.min(width,height)/2.0)+expand,hh=height+expand*2;
  if(yy<0||yy>=hh)return Double.NaN;
  double q=yy<rr?rr-yy:yy>hh-rr?yy-(hh-rr):0;
  return -expand+(q>0?rr-Math.sqrt(Math.max(0,rr*rr-q*q)):0);
 }
 static void draw(Painter p,int x,int y,int w,int h,int radius,int spread,int first,int last,float strength)throws Exception{
  if(w<=0||h<=0||spread<=0||strength<=0)return;
  int inner=0;
  for(int band=1;band<=12;band++){
   int outer=Math.round(spread*band/12f);if(outer<=inner)continue;
   float z=(inner+outer)*.5f/spread,a=(float)(.64*Math.exp(-.5*z*z/.0289)+.36*Math.exp(-.5*z*z/.1296));
   float t=Math.max(0,Math.min(1,(z-.78f)/.22f));a*=1-t*t*(3-2*t);
   for(int row=-outer;row<h+outer;row++){
    int l=(int)Math.ceil(inset(row,w,h,radius,outer)-.5),right=w-l;
    double cut=inset(row,w,h,radius,inner);float phase=Math.max(0,Math.min(1,(row+.5f)/h));
    if(Double.isNaN(cut)){
     int color=VisualStyle.mix(first,last,phase*.65f+.175f);p.rect(x+l,y+row,x+right,y+row+1,alpha(color,a*strength));
    }else{
     int leftCut=(int)Math.ceil(cut-.5),rightCut=w-leftCut;
     if(leftCut>l)p.rect(x+l,y+row,x+leftCut,y+row+1,alpha(VisualStyle.mix(first,last,phase*.65f),a*strength));
     if(right>rightCut)p.rect(x+rightCut,y+row,x+right,y+row+1,alpha(VisualStyle.mix(first,last,.35f+phase*.65f),a*strength));
    }
   }
   inner=outer;
  }
 }
 private static int alpha(int c,float a){return(Math.round((c>>>24)*Math.max(0,Math.min(1,a)))<<24)|(c&0xffffff);}
}
