package com.lite.agent;

import java.awt.image.BufferedImage;

/** The tint and blur share the animated content bottom and the same smoothstep
 * falloff. Two textured quads replace thousands of per-pixel rectangles. */
final class VeilBackdrop {
    private static Object texture;
    private static final int SIZE=256;
    private static long previous;
    private static float bottom;

    static float bottom(float target,long now,boolean animate){
        if(!animate||previous==0||now<previous)bottom=target;
        else bottom+=(target-bottom)*(1-(float)Math.exp(-15*Math.min(.1,(now-previous)/1e9)));
        previous=now;
        return bottom;
    }
    static void clear(){previous=0;bottom=0;}
    static float feather(float value){float t=Math.max(0,Math.min(1,value));return t*t*(3-2*t);}
    static void draw(int left,int right,int end,int fadeHeight,int color)throws Exception{
        if(right<=left||end<=0)return;
        if(texture==null){
            BufferedImage image=new BufferedImage(SIZE,SIZE,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<SIZE;y++)for(int x=0;x<SIZE;x++)
                image.setRGB(x,y,(Math.round(255*feather(x/(float)(SIZE-1))*(1-feather(y/(float)(SIZE-1))))<<24)|0xffffff);
            texture=TtfFont.uploadHudInk(image,"az_veil_backdrop");
        }
        VeilRenderState.text();
        int fade=Math.max(1,Math.min(end,fadeHeight)),start=end-fade;
        if(start>0)TtfFont.drawHudTexture(texture,0,0,SIZE,1,left,0,right-left,start,color);
        TtfFont.drawHudTexture(texture,0,0,SIZE,SIZE,left,start,right-left,fade,color);
    }
}
