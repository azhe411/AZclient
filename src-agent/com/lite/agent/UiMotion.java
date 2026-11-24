package com.lite.agent;
import java.util.HashMap;
import java.util.Map;
/** Analytic damped springs for presentation only. Values never write back to module parameters. */
final class UiMotion {
 private static final class State { double x,v; State(double x){this.x=x;} }
 private final Map<Object,State> states=new HashMap<Object,State>();
 void clear(){states.clear();}
 void seed(Object key,double value){states.put(key,new State(value));}
 double value(Object key,double target,double initial,double omega,double damping,double dt,boolean enabled){
  State s=states.get(key);if(s==null){s=new State(initial);states.put(key,s);}
  if(!enabled){s.x=target;s.v=0;return target;}
  double t=Math.max(0,Math.min(.1,dt)),y=s.x-target,v=s.v;
  if(damping>=.999){double c=v+omega*y,e=Math.exp(-omega*t);s.x=target+(y+c*t)*e;s.v=(v-omega*c*t)*e;}
  else {double a=damping*omega,b=omega*Math.sqrt(1-damping*damping),e=Math.exp(-a*t),c=Math.cos(b*t),sn=Math.sin(b*t);s.x=target+e*(y*c+(v+a*y)/b*sn);s.v=e*(v*c-(a*v+omega*omega*y)/b*sn);}
  if(Math.abs(s.x-target)<.0001&&Math.abs(s.v)<.001){s.x=target;s.v=0;}
  return s.x;
 }
 static float unit(double x){return (float)Math.max(0,Math.min(1,x));}
}
