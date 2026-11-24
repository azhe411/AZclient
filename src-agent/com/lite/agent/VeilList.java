package com.lite.agent;

import java.util.*;

/** Presentation state only. Times are monotonic nanoseconds; widths are final physical pixels. */
final class VeilList {
    static final class Item {
        final int id; final String name, suffix; final float width;
        Item(int id,String name,String suffix,float width){this.id=id;this.name=name;this.suffix=suffix;this.width=width;}
    }
    static final class Row {
        Item item; float sortedWidth, candidate, alpha, fromAlpha, y, travel, reveal;
        long candidateAt, changed, born; boolean visible, positioned;
        Row(Item item,long now){this.item=item;sortedWidth=candidate=item.width;changed=born=now;}
        float accent(long now){float t=(now-born)/1e9f;return Math.max(0,Math.min(1,(2.6f-t)/.65f))*alpha;}
    }
    private final Map<Integer,Row> states=new HashMap<Integer,Row>();
    private long previous;
    int total;
    void clear(){states.clear();previous=0;total=0;}
    static float ease(float x){ // cubic-bezier(.22,.9,.2,1), invert its x coordinate
        if(x<=0)return 0;if(x>=1)return 1;
        float lo=0,hi=1,t=x;
        for(int i=0;i<18;i++){t=(lo+hi)*.5f;float u=1-t;float px=3*u*u*t*.22f+3*u*t*t*.2f+t*t*t;if(px<x)lo=t;else hi=t;}
        float u=1-t;return 3*u*u*t*.9f+3*u*t*t+t*t*t;
    }
    List<Row> frame(List<Item> items,long now,float line,int max,boolean animate){
        if(previous>now)clear();
        float dt=previous==0?0:Math.min(.1f,(now-previous)/1e9f);previous=now;total=items.size();
        Set<Integer> live=new HashSet<Integer>();List<Row> active=new ArrayList<Row>();
        for(Item item:items){
            Row r=states.get(item.id);
            if(r==null){r=new Row(item,now);states.put(item.id,r);}
            r.item=item;
            // Width must settle beyond the 4px dead band for a full 500ms.
            if(Math.abs(item.width-r.sortedWidth)<=4){r.candidateAt=0;}
            else if(r.candidateAt==0||Math.abs(item.width-r.candidate)>1){r.candidate=item.width;r.candidateAt=now;}
            else if(now-r.candidateAt>=500000000L){r.sortedWidth=item.width;r.candidateAt=0;}
            active.add(r);
        }
        Collections.sort(active,new Comparator<Row>(){public int compare(Row a,Row b){
            int c=Float.compare(b.sortedWidth,a.sortedWidth);if(c==0)c=a.item.name.compareToIgnoreCase(b.item.name);
            return c!=0?c:Integer.compare(a.item.id,b.item.id);
        }});
        for(int i=0;i<Math.min(max,active.size());i++)live.add(active.get(i).item.id);
        for(Row r:states.values()){
            boolean target=live.contains(r.item.id);
            if(target!=r.visible){r.fromAlpha=r.alpha;r.changed=now;r.visible=target;if(target)r.born=now;}
            float t=(now-r.changed)/(r.visible?600000000f:450000000f);
            float e=animate?ease(t):1;
            r.alpha=r.fromAlpha+((r.visible?1:0)-r.fromAlpha)*e;
            r.reveal=r.visible?e:1;r.travel=r.visible?0:8*e;
        }
        for(int i=0;i<Math.min(max,active.size());i++){
            Row r=active.get(i);float target=i*line;
            if(!r.positioned||!animate){r.y=target;r.positioned=true;}
            else r.y+=(target-r.y)*(1-(float)Math.exp(-15*dt));
        }
        List<Row> result=new ArrayList<Row>();
        for(Iterator<Row> it=states.values().iterator();it.hasNext();){Row r=it.next();
            if(!r.visible&&r.alpha<=.001f){it.remove();continue;}result.add(r);
        }
        Collections.sort(result,new Comparator<Row>(){public int compare(Row a,Row b){return Float.compare(a.y,b.y);}});
        return result;
    }
}
