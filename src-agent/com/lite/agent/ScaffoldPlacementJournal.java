package com.lite.agent;

import java.util.ArrayDeque;
import java.util.Iterator;

/** Read-only observations of recent local placements. A local block is never
 * treated as a server acknowledgement. This does not alter movement or retries. */
final class ScaffoldPlacementJournal {
    interface Probe { boolean isAir(int x,int y,int z)throws Exception; }
    interface Sink { void report(String message); }
    private static final int LIMIT=64, LIFETIME=100;
    private static final class Entry {
        final long tick;final int x,y,z;final String detail;boolean sawBlock;
        Entry(long tick,int x,int y,int z,boolean solid,String detail){
            this.tick=tick;this.x=x;this.y=y;this.z=z;this.sawBlock=solid;this.detail=detail;
        }
    }
    private final ArrayDeque<Entry> entries=new ArrayDeque<Entry>();
    private Object world,player;
    private long sampled=Long.MIN_VALUE;

    void clear(){entries.clear();world=player=null;sampled=Long.MIN_VALUE;}
    int size(){return entries.size();}
    private void context(Object world,Object player,long tick){
        if(this.world!=world||this.player!=player||tick<sampled)clear();
        this.world=world;this.player=player;
    }
    void record(Object world,Object player,long tick,int x,int y,int z,boolean solid,String detail){
        context(world,player,tick);
        if(world==null||player==null)return;
        if(entries.size()==LIMIT)entries.removeFirst();
        entries.addLast(new Entry(tick,x,y,z,solid,detail));
    }
    void poll(Object world,Object player,long tick,Probe probe,Sink sink)throws Exception{
        context(world,player,tick);
        if(world==null||player==null||sampled==tick)return;
        sampled=tick;
        for(Iterator<Entry> it=entries.iterator();it.hasNext();){
            Entry e=it.next();long age=tick-e.tick;
            if(age<0||age>LIFETIME){it.remove();continue;}
            boolean air=probe.isAir(e.x,e.y,e.z);
            String outcome=e.sawBlock&&air?"LOCAL_BLOCK_DISAPPEARED":
                    !e.sawBlock&&air&&age>=20?"NO_LOCAL_BLOCK_OBSERVED":null;
            if(outcome!=null){
                it.remove();
                sink.report("Scaffold placement observation: outcome="+outcome
                        +" target="+e.x+","+e.y+","+e.z+" ageTicks="+age
                        +" cause=unknown serverConfirmed=false | "+e.detail);
            }else if(!air)e.sawBlock=true;
        }
    }
}
