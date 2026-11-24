package com.lite.agent;

/** Owns only native item use started by KA; never simulates a block packet. */
final class CombatBlock {
    interface Use {
        boolean active(Object player) throws Exception;
        Object stack(Object player, Object hand) throws Exception;
        boolean manual() throws Exception;
        void hold(boolean down) throws Exception;
        void start(Object player,Object world,Object stack,Object hand) throws Exception;
        void stop(Object player) throws Exception;
    }
    private final Use use;
    private Object player,world,stack,hand;
    private int ticks;
    private boolean releasing;
    CombatBlock(Use use){this.use=use;}
    boolean owns(Object who){return player!=null&&player==who;}
    void begin(Object who,Object level,Object item,Object which,int duration)throws Exception{
        if(player!=null||who==null||item==null||use.active(who)||use.manual())return;
        use.start(who,level,item,which);
        if(use.active(who)){player=who;world=level;stack=item;hand=which;ticks=Math.max(1,duration);use.hold(true);}
    }
    void maintain(Object who,Object level,boolean enabled,boolean tick)throws Exception{
        if(player==null)return;
        if(player!=who||world!=level){forget();return;}
        if(!use.active(player)||use.stack(player,hand)!=stack||use.manual()){forget();return;}
        if(releasing||!enabled||(tick&&--ticks<=0))release();
    }
    void release()throws Exception{
        if(player==null){use.hold(false);return;}
        releasing=true;
        // Drop the synthetic key first, but retain ownership until native use
        // actually ends. A failed stop must remain retryable on the next tick.
        use.hold(false);
        if(use.manual()||!use.active(player)||use.stack(player,hand)!=stack){forget();return;}
        use.stop(player);
        if(!use.active(player))forget();
    }
    private void forget()throws Exception{use.hold(false);player=world=stack=hand=null;ticks=0;releasing=false;}
}
