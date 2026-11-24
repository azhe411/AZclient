package com.lite.agent;
/** Shared sunset palette. Time is explicit so animation and pause can be verified offline. */
final class VisualStyle {
 private static final int[] SUNSET={0xFFB396F4,0xFFEB9CD0,0xFFFF989B,0xFFFFB280,0xFFD58BBD};
 static int mix(int a,int b,float t){t=Math.max(0,Math.min(1,t));int c=0;for(int s=0;s<=24;s+=8)c|=((int)(((a>>>s)&255)+(((b>>>s)&255)-((a>>>s)&255))*t))<<s;return c;}
 static int sunset(double position,long millis,double speed,boolean moving){
  double phase=position+(moving?millis*speed/14000.0:0);phase-=Math.floor(phase);
  double v=phase*SUNSET.length;int i=(int)v;float f=(float)(v-i);f=f*f*(3-2*f);
  return mix(SUNSET[i],SUNSET[(i+1)%SUNSET.length],f);
 }
 static float gaussian(float distance,float sigma){return (float)Math.exp(-distance*distance/(2*sigma*sigma));}
}
