package com.lite.agent;
import java.util.*;
/** Presentation-only, bounded notification queue. All times are monotonic nanoseconds. */
final class NoticeStack {
 static final class Card {
  final String key; String label; boolean enabled; long start;
  float extent, alpha, travel, remaining;
  Card(String key,String label,boolean enabled){this.key=key;this.label=label;this.enabled=enabled;}
 }
 private final List<Card> active=new ArrayList<Card>();
 private final ArrayDeque<Card> pending=new ArrayDeque<Card>();
 private long nextEntry;
 synchronized void publish(String key,String label,boolean enabled){
  for(Card c:pending)if(c.key.equals(key)){c.label=label;c.enabled=enabled;return;}
  // Opposite toggles remain separate events. Only the waiting backlog is coalesced.
  if(pending.size()>=128)pending.removeFirst();pending.addLast(new Card(key,label,enabled));
 }
 synchronized void clear(){active.clear();pending.clear();nextEntry=0;}
 synchronized int pendingCount(){return pending.size();}
 synchronized List<Card> frame(long now,double duration,int capacity,boolean animated,double speed){
  speed=Math.max(.5,Math.min(2,speed));duration=Math.max(1,Math.min(8,duration));capacity=Math.max(1,Math.min(5,capacity));
  double enter=.44/speed,exit=.32/speed;
  for(Iterator<Card> it=active.iterator();it.hasNext();){Card c=it.next();double age=Math.max(0,(now-c.start)/1e9);
   if(age>=enter+duration+exit){it.remove();continue;}
   double a=Math.min(1,age/enter),z=Math.max(0,Math.min(1,(age-enter-duration)/exit));
   float open=smooth(a),close=smooth(z);c.extent=animated?open*(1-close):1;c.alpha=animated?Math.min(1,open*1.8f)*(1-close):1;
   c.travel=animated?(float)(1-back(a)+close*1.18):0;c.remaining=(float)Math.max(0,Math.min(1,(enter+duration-age)/duration));
  }
  if(active.size()<capacity&&!pending.isEmpty()&&now>=nextEntry){Card c=pending.removeFirst();c.start=now;c.extent=animated?0:1;c.alpha=animated?0:1;c.travel=animated?1:0;c.remaining=1;active.add(c);nextEntry=now+(long)(.11/speed*1e9);}
  return new ArrayList<Card>(active);
 }
 private static float smooth(double t){return (float)(t*t*(3-2*t));}
 private static double back(double t){double u=t-1;return 1+2.15*u*u*u+1.15*u*u;}
}
