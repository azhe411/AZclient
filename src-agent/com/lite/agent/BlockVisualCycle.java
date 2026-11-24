package com.lite.agent;

/** A render clock, not an attack queue. New attacks never restart an active cycle. */
final class BlockVisualCycle {
    // Estimated cadence of the supplied clip; always keep the native guard pose.
    static final long DURATION=300000000L;
    private Object player,item,target,world;
    private int mode;
    private long start,lastSample;
    private boolean running;
    void clear(){player=item=target=world=null;start=lastSample=0;running=false;}
    double sample(Object who,Object held,Object victim,Object scene,int selection,long attack,long now){
        if(who==null||held==null||victim==null||scene==null){clear();return -1;}
        if(player!=who||item!=held||target!=victim||world!=scene||mode!=selection||now<lastSample){
            clear();player=who;item=held;target=victim;world=scene;mode=selection;
        }
        lastSample=now;
        boolean recent=attack>0&&attack<=now&&now-attack<=250000000L;
        if(running&&now-start>=DURATION){
            if(recent&&attack>start)start+=((now-start)/DURATION)*DURATION;
            else running=false;
        }
        if(!running&&recent){running=true;start=now;}
        return running?(now-start)/(double)DURATION:-1;
    }
}
