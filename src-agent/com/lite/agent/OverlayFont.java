package com.lite.agent;

/** In-game overlay typography. GUI retains its own TtfFont and metrics. */
final class OverlayFont {
    static int depth;
    static float logicalPixels=1;
    private static boolean reported;
    static boolean active(){return depth>0&&TtfFont.ready();}
    static String plain(String s){return s==null?"":s.replaceAll("\u00a7[0-9a-fk-orA-FK-OR]","");}
    static VeilFont.Face face(float pixels)throws Exception{return VeilFont.face(Math.max(6,pixels),false);}
    static int width(String s,float pixels){
        try{return (int)Math.ceil(VeilFont.measure(plain(s),Math.max(6,pixels),pixels*.015f));}
        catch(Exception e){failure(e);return TtfFont.width(s);}
    }
    static int center(float pixels){
        try{return VeilFont.center(Math.max(6,pixels));}
        catch(Exception e){failure(e);return Math.round(pixels*.65f);}
    }
    static void draw(String s,float x,float y,int color,float pixels){
        try(VeilRenderState state=VeilRenderState.begin()){
            VeilFont.Face f=face(pixels);f.draw(plain(s),x,y+f.ascent,color,pixels*.015f,false);
        }catch(Exception e){failure(e);TtfFont.draw(s,Math.round(x),Math.round(y),color);}
    }
    static void gradient(String s,float x,float y,int first,int last,float pixels){
        s=plain(s);
        try(VeilRenderState state=VeilRenderState.begin()){
            VeilFont.Face f=face(pixels);float total=Math.max(1,f.width(s,pixels*.015f,false)),pen=0;
            for(int i=0;i<s.length();i++){
                String c=s.substring(i,i+1);float w=f.width(c,0,false);
                f.draw(c,x+pen,y+f.ascent,VisualStyle.mix(first,last,(pen+w*.5f)/total),0,false);pen+=w+pixels*.015f;
            }
        }catch(Exception e){failure(e);draw(s,x,y,first,pixels);}
    }
    static int logicalWidth(String s){return (int)Math.ceil(width(s,12*logicalPixels)/logicalPixels);}
    static void logical(String s,int x,int y,int color){
        Class<?> gl=null;boolean pushed=false;
        try{
            gl=Class.forName("net.minecraft.client.renderer.GlStateManager");
            gl.getMethod("func_179094_E").invoke(null);pushed=true;
            gl.getMethod("func_179152_a",float.class,float.class,float.class).invoke(null,1/logicalPixels,1/logicalPixels,1f);
            VeilFont.Face f=face(12*logicalPixels);
            // World-label y is an ink-top coordinate, unlike widget cell y.
            draw(s,x*logicalPixels,y*logicalPixels+f.capHeight-f.ascent,color,f.size);
        }catch(Exception e){failure(e);}
        finally{if(pushed)try{gl.getMethod("func_179121_F").invoke(null);}catch(Exception e){failure(e);}}
    }
    private static void failure(Exception e){if(!reported){reported=true;LiteAgent.log("Overlay font fallback: "+e);}}
}
