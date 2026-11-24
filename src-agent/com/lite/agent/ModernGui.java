package com.lite.agent;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.objectweb.asm.*;

/** A real 1.20.1 Screen. Minecraft owns focus, cursor capture and scaled mouse coordinates. */
public final class ModernGui {
    private ModernGui() {}
    private static Class<?> screenClass, generated;
    private static Method getMc, setScreen, fill, drawText, width, height;
    private static Field currentScreen, font;
    private static Object previousScreen;
    private static final Set<String> reportedErrors=new HashSet<String>();
    private static int tab, scroll, x, y, w, h, contentX;
    private static final List<Hit> hits = new ArrayList<Hit>();
    private static Hit activeSlider;
    private static final PanelPositions placement=new PanelPositions(1,"gui-modern-layout.properties");
    private static boolean moving;
    private static double grabX,grabY;
    private static int viewportW,viewportH;
    private static void savePlacement(){try{placement.save();}catch(IOException e){failure("save window position",e);}}
    private static boolean loaded;
    private static final UiMotion motion=new UiMotion();
    private static long lastFrame;
    private static float frameDelta=.016f, contentReveal=1;
    private static final NoticeStack notices=new NoticeStack();
    private static final UiMotion hudMotion=new UiMotion();
    private static long hudFrame;
    static boolean hud = true;
    static int hudOpacity=88,noticeOpacity=88;
    static int hudGlow=100,noticeGlow=100;
    static String status = "";
    static final int TEXT=0xFFF0F3FA, MUTED=0xFFABB8CA, ACCENT=0xFFEDADD5;
    private static final class Hit {
        final int x,y,w,h,id;
        Hit(int x,int y,int w,int h,int id){this.x=x;this.y=y;this.w=w;this.h=h;this.id=id;}
        boolean contains(double a,double b){return a>=x && a<x+w && b>=y && b<y+h;}
    }
    static void init() throws Exception {
        if (generated != null) return;
        ClassLoader game=Ver.runtimeLoader();
        Class<?> mc=Class.forName("net.minecraft.client.Minecraft",false,game);
        screenClass=Class.forName("net.minecraft.client.gui.screens.Screen",false,game);
        Class<?> graphics=Class.forName("net.minecraft.client.gui.GuiGraphics",false,game);
        Class<?> fontClass=Class.forName("net.minecraft.client.gui.Font",false,game);
        getMc=mc.getMethod("m_91087_");setScreen=mc.getMethod("m_91152_",screenClass);
        currentScreen=mc.getField("f_91080_");font=mc.getField("f_91062_");
        fill=graphics.getMethod("m_280509_",int.class,int.class,int.class,int.class,int.class);
        drawText=graphics.getMethod("m_280488_",fontClass,String.class,int.class,int.class,int.class);
        width=graphics.getMethod("m_280182_");height=graphics.getMethod("m_280206_");
        generated=new ScreenLoader(game).define(screenBytes());
        load();
    }
    private static final class ScreenLoader extends ClassLoader {
        ScreenLoader(ClassLoader game){super(game);}
        Class<?> define(byte[] bytes){return defineClass("com.lite.ui.AzScreen1201",bytes,0,bytes.length);}
    }
    static byte[] screenBytes() {
        String base="net/minecraft/client/gui/screens/Screen", owner="com/lite/ui/AzScreen1201";
        ClassWriter out=new ClassWriter(ClassWriter.COMPUTE_MAXS);
        out.visit(Opcodes.V1_8,Opcodes.ACC_PUBLIC|Opcodes.ACC_FINAL,owner,null,base,null);
        MethodVisitor ctor=out.visitMethod(Opcodes.ACC_PUBLIC,"<init>","()V",null,null);
        ctor.visitCode();ctor.visitVarInsn(Opcodes.ALOAD,0);ctor.visitLdcInsn("AZclient");
        ctor.visitMethodInsn(Opcodes.INVOKESTATIC,"net/minecraft/network/chat/Component","m_237113_",
                "(Ljava/lang/String;)Lnet/minecraft/network/chat/MutableComponent;",true);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL,base,"<init>","(Lnet/minecraft/network/chat/Component;)V",false);
        ctor.visitInsn(Opcodes.RETURN);ctor.visitMaxs(0,0);ctor.visitEnd();
        forward(out,"m_88315_","(Lnet/minecraft/client/gui/GuiGraphics;IIF)V","render",
                "(Ljava/lang/Object;IIF)V",new int[]{Opcodes.ALOAD,Opcodes.ILOAD,Opcodes.ILOAD,Opcodes.FLOAD},new int[]{1,2,3,4},Opcodes.RETURN);
        forward(out,"m_6375_","(DDI)Z","click","(DDI)Z",new int[]{Opcodes.DLOAD,Opcodes.DLOAD,Opcodes.ILOAD},new int[]{1,3,5},Opcodes.IRETURN);
        forward(out,"m_7979_","(DDIDD)Z","drag","(DDIDD)Z",new int[]{Opcodes.DLOAD,Opcodes.DLOAD,Opcodes.ILOAD,Opcodes.DLOAD,Opcodes.DLOAD},new int[]{1,3,5,6,8},Opcodes.IRETURN);
        forward(out,"m_6348_","(DDI)Z","release","(DDI)Z",new int[]{Opcodes.DLOAD,Opcodes.DLOAD,Opcodes.ILOAD},new int[]{1,3,5},Opcodes.IRETURN);
        forward(out,"m_6050_","(DDD)Z","scroll","(DDD)Z",new int[]{Opcodes.DLOAD,Opcodes.DLOAD,Opcodes.DLOAD},new int[]{1,3,5},Opcodes.IRETURN);
        forward(out,"m_7933_","(III)Z","key","(III)Z",new int[]{Opcodes.ILOAD,Opcodes.ILOAD,Opcodes.ILOAD},new int[]{1,2,3},Opcodes.IRETURN);
        forward(out,"m_7379_","()V","close","()V",new int[0],new int[0],Opcodes.RETURN);
        forward(out,"m_7861_","()V","removed","()V",new int[0],new int[0],Opcodes.RETURN);
        MethodVisitor pause=out.visitMethod(Opcodes.ACC_PUBLIC,"m_7043_","()Z",null,null);
        pause.visitCode();pause.visitInsn(Opcodes.ICONST_0);pause.visitInsn(Opcodes.IRETURN);pause.visitMaxs(0,0);pause.visitEnd();
        out.visitEnd();return out.toByteArray();
    }
    private static void forward(ClassWriter out,String name,String desc,String callback,String callbackDesc,int[] ops,int[] slots,int ret){
        MethodVisitor v=out.visitMethod(Opcodes.ACC_PUBLIC,name,desc,null,null);v.visitCode();
        for(int i=0;i<ops.length;i++)v.visitVarInsn(ops[i],slots[i]);
        v.visitMethodInsn(Opcodes.INVOKESTATIC,"com/lite/agent/ModernGui",callback,callbackDesc,false);
        v.visitInsn(ret);v.visitMaxs(0,0);v.visitEnd();
    }
    static boolean isOpen() {
        try {return generated!=null && generated.isInstance(currentScreen.get(getMc.invoke(null)));}
        catch(Exception e){return false;}
    }
    static boolean hasScreen()throws Exception {init();return currentScreen.get(getMc.invoke(null))!=null;}
    static boolean open(boolean automatic) throws Exception {
        init();Object mc=getMc.invoke(null), current=currentScreen.get(mc);
        if(isOpen())return true;
        if(automatic && current!=null)return false; // Do not steal a chat/container/loading screen.
        previousScreen=current;Object next=generated.getConstructor().newInstance();
        setScreen.invoke(mc,next);Hook.modernScreenState(true);
        LiteAgent.log("1.20.1 native Screen opened; mouse focus owned by Minecraft");
        return true;
    }
    public static void close() {
        try {
            moving=false;savePlacement();
            finishSlider();
            if(isOpen()){Object restore=previousScreen;previousScreen=null;setScreen.invoke(getMc.invoke(null),restore);}
            Hook.modernScreenState(false);hits.clear();
        } catch(Throwable e){failure("close",e);}
    }
    public static void removed(){moving=false;savePlacement();finishSlider();Hook.modernScreenState(false);hits.clear();}
    public static boolean key(int key,int scan,int modifiers){
        if(key==256)close();
        // RShift/F8 are toggled once by KeyboardHandler; don't toggle a second time here.
        return true;
    }
    static void panic(){ModernEsp.enabled=false;ReachPicker.modernEnabled=false;close();save();}
    public static boolean click(double mx,double my,int button){
        if(button!=0 || !isOpen())return false;
        finishSlider();
        if(mx>=x&&mx<x+w-38&&my>=y&&my<y+46){moving=true;grabX=mx-x;grabY=my-y;return true;}
        for(Hit hit:new ArrayList<Hit>(hits))if(hit.contains(mx,my)){
            int id=hit.id; motion.seed("click:"+id,1);
            if(id>=30&&id<=33){activeSlider=hit;applySlider(mx);return true;}
            if(id<4){if(tab!=id)motion.seed("content",0);tab=id;scroll=0;hits.clear();return true;}
            if(id==99){close();return true;}
            switch(id){
                case 10: ModernEsp.enabled=!ModernEsp.enabled;notices.publish("ESP","ESP",ModernEsp.enabled);break;
                case 11: ModernEsp.players=!ModernEsp.players;break;
                case 12: ModernEsp.creatures=!ModernEsp.creatures;break;
                case 13: ModernEsp.invisible=!ModernEsp.invisible;break;
                case 14: ModernEsp.range=Math.max(16,ModernEsp.range-16);break;
                case 15: ModernEsp.range=Math.min(256,ModernEsp.range+16);break;
                case 16: ModernEsp.thickness=ModernEsp.thickness==1?2:1;break;
                case 17: hud=!hud;notices.publish("HUD","HUD",hud);break;
                case 18: ReachPicker.modernEnabled=!ReachPicker.modernEnabled;notices.publish("Reach","Reach",ReachPicker.modernEnabled);break;
                case 19: ReachPicker.modernRange=Math.max(2,Math.round((ReachPicker.modernRange-.1)*10)/10.0);break;
                case 20: ReachPicker.modernRange=Math.min(6,Math.round((ReachPicker.modernRange+.1)*10)/10.0);break;
                case 21: ReachPicker.modernSprintOnly=!ReachPicker.modernSprintOnly;break;
            }
            save();return true;
        }
        return false;
    }
    private static void applySlider(double mx){
        if(activeSlider==null)return;
        int value=(int)Math.round(100*Math.max(0,Math.min(1,(mx-activeSlider.x)/Math.max(1,activeSlider.w-1))));
        switch(activeSlider.id){case 30:hudOpacity=value;break;case 31:hudGlow=value;break;case 32:noticeOpacity=value;break;case 33:noticeGlow=value;break;}
    }
    private static void finishSlider(){if(activeSlider!=null){activeSlider=null;save();}}
    public static boolean drag(double mx,double my,int button,double dx,double dy){
        if(button==0&&moving&&isOpen()){
            placement.move(0,(int)Math.round(mx-grabX),(int)Math.round(my-grabY),viewportW,viewportH,w,h);
            return true;
        }
        if(button!=0||activeSlider==null||!isOpen())return false;applySlider(mx);return true;
    }
    public static boolean release(double mx,double my,int button){
        if(button==0&&moving){drag(mx,my,button,0,0);moving=false;savePlacement();return true;}
        if(button!=0||activeSlider==null)return false;applySlider(mx);finishSlider();return true;
    }
    public static boolean scroll(double mx,double my,double delta){
        if(mx<contentX||mx>=x+w||my<y+48||my>=y+h-30)return false;
        finishSlider();int total=tab==0?7:tab==1?8:tab==2?5:6, visible=Math.max(1,(h-88)/27);
        scroll=Math.max(0,Math.min(Math.max(0,total-visible),scroll-(int)Math.signum(delta)));return true;
    }
    public static void render(Object g,int mx,int my,float partial) {
        try{
            init();int sw=((Number)width.invoke(g)).intValue(),sh=((Number)height.invoke(g)).intValue();
            w=Math.min(500,Math.max(1,sw-16));h=Math.min(324,Math.max(1,sh-16));
            viewportW=sw;viewportH=sh;placement.load();
            x=placement.x(0,(sw-w)/2,sw,w);y=placement.y(0,(sh-h)/2,sh,h);contentX=x+Math.min(124,w/3);
            long now=System.nanoTime();frameDelta=lastFrame==0?.016f:(float)Math.max(0,Math.min(.1,(now-lastFrame)/1e9));lastFrame=now;
            contentReveal=UiMotion.unit(motion.value("content",1,1,20,1,frameDelta,true));
            hits.clear();
            FrostPass.draw(sw,sh,0,0,sw,sh,0,34,1f);
            rect(g,0,0,sw,sh,0x4A211429);
            surface(g,x,y,w,h,true,1f,.5f);
            rect(g,x+1,y+1,contentX,y+h-1,0x501D162A);
            text(g,"A Z C L I E N T",x+12,y+16,TEXT);
            text(g,"1.20.1 / 8.0.0",x+12,y+31,MUTED);
            button(g,x+10,y+60,contentX-x-20,25,0,"ESP",tab==0,mx,my);
            button(g,x+10,y+91,contentX-x-20,25,1,"界面",tab==1,mx,my);
            button(g,x+10,y+122,contentX-x-20,25,2,"兼容状态",tab==2,mx,my);
            button(g,x+10,y+153,contentX-x-20,25,3,"Reach",tab==3,mx,my);
            button(g,x+w-32,y+9,23,22,99,"X",false,mx,my);
            text(g,tab==0?"实体显示":tab==1?"界面与输入":tab==2?"1.20.1 功能状态":"Reach / 实体选取",contentX+16,y+18,TEXT);
            text(g,tab==0?"相机同步投影 / 每帧插值":tab==1?"使用 Minecraft 原生焦点":tab==2?"仅显示已接通的能力":"手动左键 / 保留方块遮挡",contentX+16,y+33,MUTED);
            int row=0;
            if(tab==0){
                row(g,row++,"ESP",ModernEsp.enabled?"开启":"关闭",10,mx,my);
                row(g,row++,"玩家",ModernEsp.players?"显示":"隐藏",11,mx,my);
                row(g,row++,"其他生物",ModernEsp.creatures?"显示":"隐藏",12,mx,my);
                row(g,row++,"隐形实体",ModernEsp.invisible?"显示":"隐藏",13,mx,my);
                int ry=rowY(row++);
                if(visible(ry)){text(g,"距离  "+ModernEsp.range+" m",contentX+16,ry+9,TEXT);
                    button(g,x+w-72,ry+2,24,22,14,"-",false,mx,my);
                    button(g,x+w-42,ry+2,24,22,15,"+",false,mx,my);}
                row(g,row++,"线宽",ModernEsp.thickness+" px",16,mx,my);
                row(g,row++,"状态 HUD",hud?"显示":"隐藏",17,mx,my);
            }else if(tab==1){
                row(g,row++,"状态 HUD",hud?"显示":"隐藏",17,mx,my);
                sliderRow(g,row++,"HUD 背景",hudOpacity,30);
                sliderRow(g,row++,"HUD 柔光",hudGlow,31);
                sliderRow(g,row++,"通知背景",noticeOpacity,32);
                sliderRow(g,row++,"通知柔光",noticeGlow,33);
                info(g,row++,"RShift / F8：打开或关闭");
                info(g,row++,"Esc：关闭；Delete 仅在游戏内停用功能");
                info(g,row++,"点击切换参数，滚轮查看其余选项");
            }else if(tab==2){
                info(g,row++,"GUI / 鼠标 / 按键：已接通");
                info(g,row++,"ESP：独立 1.20.1 渲染");
                info(g,row++,"Reach：已接通原版左键选取");
                info(g,row++,"KA / Scaffold：未移植");
                info(g,row++,"其余旧版模块：未移植");
            }else{
                row(g,row++,"Reach",ReachPicker.modernEnabled?"开启":"关闭",18,mx,my);
                int ry=rowY(row++);
                if(visible(ry)){text(g,"距离  "+String.format(java.util.Locale.ROOT,"%.1f",ReachPicker.modernRange)+" m",contentX+16,ry+9,TEXT);
                    button(g,x+w-72,ry+2,24,22,19,"-",false,mx,my);
                    button(g,x+w-42,ry+2,24,22,20,"+",false,mx,my);}
                row(g,row++,"仅疾跑时",ReachPicker.modernSprintOnly?"开启":"关闭",21,mx,my);
                info(g,row++,"准星须指向实体，左键攻击");
                info(g,row++,"不改变放置 / 挖掘距离");
                info(g,row++,ReachPicker.error.isEmpty()?"命中仍由服务器验证":"Reach 初始化失败，请查看日志");
            }
            rect(g,contentX,y+h-30,x+w,y+h,0xD521192F);
            text(g,status.isEmpty()?"RShift / F8 开关  ·  Esc 返回":status,contentX+12,y+h-18,MUTED);
            drawNotices(g,System.nanoTime());
            Hook.modernPaintResult(null);
        }catch(Throwable e){Hook.modernPaintResult(e);failure("render",e);}
    }
    private static int rowY(int row){return y+56+(row-scroll)*27+Math.round((1-contentReveal)*18);}
    private static boolean visible(int ry){return ry>=y+56 && ry+25<=y+h-32;}
    private static void row(Object g,int row,String label,String value,int id,int mx,int my)throws Exception{
        int ry=rowY(row);if(!visible(ry))return;
        rect(g,contentX+10,ry,x+w-10,ry+25,0x502B233A);
        text(g,label,contentX+17,ry+9,TEXT);
        button(g,x+w-79,ry+2,60,21,id,value,false,mx,my);
    }
    private static void sliderRow(Object g,int row,String label,int value,int id)throws Exception{
        int ry=rowY(row);if(!visible(ry))return;
        int left=contentX+17,right=x+w-22,knob=left+Math.round((right-left)*value/100f);
        text(g,label,left,ry+2,TEXT);text(g,value+"%",right-27,ry+2,ACCENT);
        hits.add(new Hit(left,ry+13,right-left+1,12,id));
        round(g,left,ry+18,right-left,3,1,0x90625470);
        round(g,left,ry+18,Math.max(1,knob-left),3,1,ACCENT);
        round(g,knob-3,ry+15,6,9,3,TEXT);
    }
    private static void info(Object g,int row,String message)throws Exception{int ry=rowY(row);if(visible(ry))text(g,message,contentX+16,ry+9,MUTED);}
    private static void button(Object g,int bx,int by,int bw,int bh,int id,String label,boolean selected,int mx,int my)throws Exception{
        Hit hit=new Hit(bx,by,bw,bh,id);hits.add(hit);
        float hover=UiMotion.unit(motion.value("hover:"+id,hit.contains(mx,my)?1:0,0,25,.8,frameDelta,true));
        float pulse=(float)motion.value("click:"+id,0,0,24,.65,frameDelta,true);
        int inset=Math.round(pulse*3),dy=Math.round(pulse*3-hover*2);
        round(g,bx+inset,by+dy,bw-inset*2,bh,4,VisualStyle.mix(selected?0x985F416B:0x503E324E,0xB180587E,hover));
        text(g,label,bx+7+inset,by+(bh-8)/2+dy,selected?TEXT:ACCENT);
    }
    static int sunset(double phase){return VisualStyle.sunset(phase,System.currentTimeMillis(),1,true);}
    static void round(Object g,int x,int y,int w,int h,int radius,int color)throws Exception{
        int r=Math.min(radius,Math.min(w,h)/2);if(r<=0){rect(g,x,y,x+w,y+h,color);return;}
        for(int i=0;i<r;i++){double d=r-i-.5;int inset=r-(int)Math.round(Math.sqrt(r*r-d*d));rect(g,x+inset,y+i,x+w-inset,y+i+1,color);rect(g,x+inset,y+h-i-1,x+w-inset,y+h-i,color);}
        rect(g,x,y+r,x+w,y+h-r,color);
    }
    static void surface(Object g,int x,int y,int w,int h,boolean strong)throws Exception{surface(g,x,y,w,h,strong,1);}
    static int fade(int color,float a){return (Math.round((color>>>24)*Math.max(0,Math.min(1,a)))<<24)|(color&0xFFFFFF);}
    static void frameGlow(final Object g,int x,int y,int w,int h,int radius,int spread,int first,int last,float strength)throws Exception{
        if(!FrostPass.glow(width(g),height(g),x,y,w,h,radius,spread,first,last,strength))
            FrameGlow.draw(new FrameGlow.Painter(){public void rect(int l,int t,int r,int b,int c)throws Exception{ModernGui.rect(g,l,t,r,b,c);}},x,y,w,h,radius,spread,first,last,strength);
    }
    static void surface(Object g,int x,int y,int w,int h,boolean strong,float opacity)throws Exception{surface(g,x,y,w,h,strong,opacity,1f);}
    static void surface(Object g,int x,int y,int w,int h,boolean strong,float opacity,float rimStrength)throws Exception{surface(g,x,y,w,h,strong,opacity,rimStrength,-1f);}
    static void surface(Object g,int x,int y,int w,int h,boolean strong,float opacity,float rimStrength,float backgroundAlpha)throws Exception{
        surface(g,x,y,w,h,strong,opacity,rimStrength,backgroundAlpha,1f);
    }
    static void surface(Object g,int x,int y,int w,int h,boolean strong,float opacity,float rimStrength,float backgroundAlpha,float glowAlpha)throws Exception{
        float plate=backgroundAlpha<0?1:backgroundAlpha;
        if(strong)frameGlow(g,x,y,w,h,8,Math.round(28*(.7f+.3f*rimStrength)),sunset(.1),0xFFFFAF80,.7f*opacity*rimStrength*glowAlpha);
        if(backgroundAlpha==0)return;
        round(g,x,y,w,h,8,fade(0x986B496F,opacity*plate));round(g,x+1,y+1,w-2,h-2,7,fade(backgroundAlpha<0?0xCC20162D:((Math.round(backgroundAlpha*255)<<24)|0x20162D),opacity));
        for(int i=0;i<14;i++)rect(g,x+7,y+2+i,x+w-7,y+3+i,fade(0x103E304D,opacity*plate*(1-i/14f)));
        int a=sunset(0),b=0xFFFFAF80;for(int i=0;i<24;i++)rect(g,x+8+(w-16)*i/24,y,x+8+(w-16)*(i+1)/24,y+1,fade(VisualStyle.mix(a,b,i/23f),opacity*.65f*plate));
    }
    static void rect(Object g,int x,int y,int r,int b,int color)throws Exception{fill.invoke(g,x,y,r,b,color);}
    static void text(Object g,String s,int x,int y,int color)throws Exception{drawText.invoke(g,font.get(getMc.invoke(null)),s,x,y,color);}
    private static void overlayText(Object g,String s,int x,int y,int color)throws Exception{
        try{ModernOverlayFont.draw(g,s,x,y,color);}catch(Exception e){ModernOverlayFont.failure(e);text(g,s,x,y,color);}
    }
    static int width(Object g)throws Exception{return ((Number)width.invoke(g)).intValue();}
    static int height(Object g)throws Exception{return ((Number)height.invoke(g)).intValue();}
    private static void hudPlate(Object g,int x,int y,int w,int h,float show,float weight)throws Exception{
        FrostPass.draw(width(g),height(g),x,y,x+w,y+h,7,24,show*hudOpacity/100f);
        frameGlow(g,x,y,w,h,7,24,sunset(.1),0xFFFFAF80,.2f*show*hudGlow/100f*weight);
        round(g,x,y,w,h,7,fade(0xFF191622,show*hudOpacity/100f));
    }
    static void hud(Object g)throws Exception{
        init();long now=System.nanoTime();double dt=hudFrame==0?.016:Math.max(0,Math.min(.1,(now-hudFrame)/1e9));hudFrame=now;
        float show=UiMotion.unit(hudMotion.value("show",hud?1:0,0,18,.8,dt,true));
        float esp=UiMotion.unit(hudMotion.value("esp",ModernEsp.enabled?1:0,ModernEsp.enabled?1:0,18,1,dt,true));
        if(!isOpen()){
            if(show>.005f){int py=8+Math.round((1-show)*12);
                hudPlate(g,8,py,156,27,show,1);
                overlayText(g,"AZCLIENT",18,py+7,fade(TEXT,show));overlayText(g,"8.0.0",116,py+7,fade(MUTED,show));
                int next=py+32;
                if(esp>.005f){hudPlate(g,8,next,102,22,show*esp,.35f);
                    overlayText(g,"ESP  ·  "+ModernEsp.drawn+" 实体",18,next+4,fade(ACCENT,show*esp));next+=Math.round(26*esp);}
                if(ReachPicker.modernEnabled){hudPlate(g,8,next,72,22,show,.35f);overlayText(g,"Reach",18,next+4,fade(ACCENT,show));}
                else if(esp<=.005f){hudPlate(g,8,next,112,22,show,.35f);overlayText(g,"无已开启功能",18,next+4,fade(MUTED,show));}
            }
            drawNotices(g,now);
        }
    }
    private static void drawNotices(Object g,long now)throws Exception{
        int sw=width(g),sh=height(g),cw=Math.min(216,sw-24),ch=48,gap=18;
        List<NoticeStack.Card> cards=notices.frame(now,2.8,Math.max(1,Math.min(5,(sh-50)/(ch+gap))),true,1);
        float cursor=sh-18;
        for(int i=cards.size()-1;i>=0;i--){NoticeStack.Card c=cards.get(i);float slot=(ch+gap)*c.extent;
            int bottom=Math.round(cursor),clipTop=Math.round(cursor-slot+gap*c.extent);cursor-=slot;
            if(c.alpha<.005f||bottom<=clipTop)continue;
            int px=sw-14-cw+Math.round(c.travel*(cw+30)),py=bottom-ch;
            int color=c.enabled?0xFFFFB280:ACCENT,halo=Math.round(gap*.5f*c.extent),haloTop=Math.max(0,clipTop-halo);
            try(GuiClip glowClip=GuiClip.logical(0,haloTop,sw,Math.max(0,bottom+halo-haloTop),sw,sh)){
                if(glowClip.active()||c.extent>=.999f)frameGlow(g,px,py,cw,ch,8,24,ACCENT,color,c.alpha*noticeGlow/100f*(.22f+.15f*(1-c.extent)));
            }
            try(GuiClip clip=GuiClip.logical(0,Math.max(0,clipTop),sw,Math.max(0,bottom-Math.max(0,clipTop)),sw,sh)){
                if(!clip.active()&&c.extent<.999f)continue;
                round(g,px,py,cw,ch,8,fade(0xFF191622,c.alpha*noticeOpacity/100f));
                round(g,px+10,py+13,22,22,7,fade(color,c.alpha*.2f*noticeOpacity/100f));
                overlayText(g,c.enabled?"+":"-",px+18,py+17,fade(color,c.alpha));
                overlayText(g,c.label,px+42,py+9,fade(TEXT,c.alpha));
                overlayText(g,c.enabled?"功能已开启":"功能已关闭",px+42,py+25,fade(MUTED,c.alpha));
                rect(g,px+12,py+ch-4,px+12+Math.round((cw-24)*c.remaining),py+ch-3,fade(color,c.alpha));
            }
        }
    }
    private static File settings(){return new File(new File(System.getProperty("user.home","."),".azclient"),"modern-1201.properties");}
    private static void load(){
        if(loaded)return;loaded=true;Properties p=new Properties();
        if(!settings().isFile())return;
        try(Reader r=new InputStreamReader(new FileInputStream(settings()),"UTF-8")){
            p.load(r);hud=Boolean.parseBoolean(p.getProperty("hud","true"));
            hudOpacity=readOpacity(p,"hudOpacity");noticeOpacity=readOpacity(p,"noticeOpacity");
            hudGlow=readPercent(p,"hudGlow",100);noticeGlow=readPercent(p,"noticeGlow",100);
            ModernEsp.players=Boolean.parseBoolean(p.getProperty("players","true"));
            ModernEsp.creatures=Boolean.parseBoolean(p.getProperty("creatures","true"));
            ModernEsp.invisible=Boolean.parseBoolean(p.getProperty("invisible","false"));
            ModernEsp.range=Math.max(16,Math.min(256,Integer.parseInt(p.getProperty("range","96"))));
            ModernEsp.thickness=Math.max(1,Math.min(2,Integer.parseInt(p.getProperty("thickness","1"))));
            double reach=Double.parseDouble(p.getProperty("reachRange","3"));
            ReachPicker.modernRange=Double.isFinite(reach)?Math.max(2,Math.min(6,reach)):3;
            ReachPicker.modernSprintOnly=Boolean.parseBoolean(p.getProperty("reachSprintOnly","false"));
            // Never enable an overlay automatically in a newly attached game.
        }catch(Exception e){failure("load preferences",e);}
    }
    private static int readOpacity(Properties p,String name){return readPercent(p,name,88);}
    private static int readPercent(Properties p,String name,int fallback){try{return Math.max(0,Math.min(100,Integer.parseInt(p.getProperty(name,String.valueOf(fallback)))));}catch(NumberFormatException e){return fallback;}}
    private static void save(){
        try{AtomicFiles.writeUtf8(settings(),"hud="+hud+"\nhudOpacity="+hudOpacity+"\nnoticeOpacity="+noticeOpacity+"\nhudGlow="+hudGlow+"\nnoticeGlow="+noticeGlow+"\nplayers="+ModernEsp.players+"\ncreatures="+ModernEsp.creatures+
                "\ninvisible="+ModernEsp.invisible+"\nrange="+ModernEsp.range+"\nthickness="+ModernEsp.thickness+
                "\nreachRange="+ReachPicker.modernRange+"\nreachSprintOnly="+ReachPicker.modernSprintOnly+"\n",true);status="设置已保存";}
        catch(Exception e){status="设置保存失败，请查看日志";failure("save preferences",e);}
    }
    static void failure(String action,Throwable e){
        while(e instanceof InvocationTargetException && ((InvocationTargetException)e).getCause()!=null)e=e.getCause();
        String key=action+": "+e;
        if(!reportedErrors.add(key))return;
        StringWriter trace=new StringWriter();e.printStackTrace(new PrintWriter(trace));
        LiteAgent.log("modern "+action+" failed: "+trace);
    }
}
