package com.lite.agent;

import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Ring-only antialiased ribbons. Float transforms preserve joins and real stroke width. */
final class VeilRingStroke {
    private static final Map<Integer,Object> textures=new HashMap<Integer,Object>();
    private static Method push,pop,translate,rotate,scale;
    private static Method begin,end,ink,uv,vertex,pushCurrent,popCurrent;
    private static Object gaussian;
    private static Object texture(float width)throws Exception{
        int key=Math.round(Math.max(1,Math.min(6,width))*2);width=key*.5f;
        int height=(int)Math.ceil(width+2);Object texture=textures.get(key);
        if(texture!=null)return texture;
        BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<64;y++){
            float distance=Math.abs((y+.5f)/64f-.5f)*height;
            int rgba=(Math.round(255*(1-VeilBackdrop.feather(distance-width*.5f+.5f)))<<24)|0xffffff;
            for(int x=0;x<64;x++)image.setRGB(x,y,rgba);
        }
        texture=TtfFont.uploadHudInk(image,"az_ring_stroke_"+key);textures.put(key,texture);return texture;
    }
    /** Three texture submissions per ring, rather than four per segment.
     * Vertices are already in physical screen space: no per-segment matrix work. */
    static void ring(float[][] lines,int[] colors,float width,float glow,float alpha)throws Exception{
        if(begin==null){
            Class<?> gl=Class.forName("org.lwjgl.opengl.GL11");
            begin=gl.getMethod("glBegin",int.class);end=gl.getMethod("glEnd");
            ink=gl.getMethod("glColor4f",float.class,float.class,float.class,float.class);
            uv=gl.getMethod("glTexCoord2f",float.class,float.class);vertex=gl.getMethod("glVertex2f",float.class,float.class);
            pushCurrent=gl.getMethod("glPushAttrib",int.class);popCurrent=gl.getMethod("glPopAttrib");
        }
        Object under=texture(width+2),core=texture(width);
        if(glow>0&&gaussian==null){
            BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<64;y++){
                float d=(y+.5f-32)/32f;
                int rgba=(Math.round(255*VisualStyle.gaussian(Math.abs(d),.29f))<<24)|0xffffff;
                for(int x=0;x<64;x++)image.setRGB(x,y,rgba);
            }
            gaussian=TtfFont.uploadHudInk(image,"az_ring_glow_strip");
        }
        // Raw vertex color is restored without invalidating Minecraft's color cache.
        pushCurrent.invoke(null,1);
        try{
            batch(lines,colors,under,(float)Math.ceil(width+4),0,alpha);
            if(glow>0)batch(lines,colors,gaussian,width,1,glow);
            batch(lines,colors,core,(float)Math.ceil(width+2),2,1);
        }finally{popCurrent.invoke(null);}
    }
    private static void batch(float[][] lines,int[] colors,Object texture,float height,int pass,float strength)throws Exception{
        VeilRenderState.text();TtfFont.bindHudTexture(texture);begin.invoke(null,7);
        try{
            for(int n=0;n<lines.length;n++){
                float[] line=lines[n];if(line==null)continue;
                if(pass==1){quad(line,colors[n],2*Math.round(height+6),strength*.80f);quad(line,colors[n],2*Math.round(height+2),strength*.45f);}
                else quad(line,pass==0?0xff241c31:colors[n],height,pass==0?strength*.38f:strength);
            }
        }finally{end.invoke(null);}
    }
    private static void quad(float[] line,int color,float height,float strength)throws Exception{
        float dx=line[2]-line[0],dy=line[3]-line[1];double len=Math.hypot(dx,dy);if(len<.001)return;
        float nx=(float)(-dy/len*height*.5),ny=(float)(dx/len*height*.5);
        ink.invoke(null,((color>>16)&255)/255f,((color>>8)&255)/255f,(color&255)/255f,((color>>>24)/255f)*Math.min(1,strength));
        // Same winding as Minecraft Gui quads, including when face culling is on.
        corner(line[0]+nx,line[1]+ny,1);corner(line[2]+nx,line[3]+ny,1);
        corner(line[2]-nx,line[3]-ny,0);corner(line[0]-nx,line[1]-ny,0);
    }
    private static void corner(float x,float y,float v)throws Exception{uv.invoke(null,.5f,v);vertex.invoke(null,x,y);}
    static void line(float x0,float y0,float x1,float y1,int color,float width)throws Exception{
        double length=Math.hypot(x1-x0,y1-y0);if(length<.001)return;
        if(push==null){
            Class<?> gl=Class.forName("net.minecraft.client.renderer.GlStateManager");
            push=gl.getMethod("func_179094_E");pop=gl.getMethod("func_179121_F");
            translate=gl.getMethod("func_179109_b",float.class,float.class,float.class);
            rotate=gl.getMethod("func_179114_b",float.class,float.class,float.class,float.class);
            scale=gl.getMethod("func_179152_a",float.class,float.class,float.class);
        }
        int key=Math.round(Math.max(1,Math.min(6,width))*2);width=key*.5f;
        int height=(int)Math.ceil(width+2);Object texture=texture(width);
        push.invoke(null);
        try{
            translate.invoke(null,x0,y0,0f);
            rotate.invoke(null,(float)Math.toDegrees(Math.atan2(y1-y0,x1-x0)),0f,0f,1f);
            int pixels=Math.max(1,(int)Math.ceil(length));
            scale.invoke(null,(float)(length/pixels),1f,1f);
            translate.invoke(null,0f,-height*.5f,0f);
            VeilRenderState.text();
            TtfFont.drawHudTexture(texture,31,0,1,64,0,0,pixels,height,color);
        }finally{pop.invoke(null);}
    }
}
