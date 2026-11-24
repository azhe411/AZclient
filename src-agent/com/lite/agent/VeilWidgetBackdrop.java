package com.lite.agent;

import java.awt.image.BufferedImage;

/** A cached nine-slice veil, fading to zero on all four edges. No frame or plate. */
final class VeilWidgetBackdrop {
    private static Object texture;
    private static final int N=64, HALF=32;
    static void draw(int x,int y,int w,int h,int feather,int color)throws Exception {
        if(w<=0||h<=0||(color>>>24)==0)return;
        if(texture==null){
            BufferedImage image=new BufferedImage(N,N,BufferedImage.TYPE_INT_ARGB);
            for(int py=0;py<N;py++)for(int px=0;px<N;px++){
                float fx=VeilBackdrop.feather(Math.min(px,N-1-px)/(float)(HALF-1));
                float fy=VeilBackdrop.feather(Math.min(py,N-1-py)/(float)(HALF-1));
                image.setRGB(px,py,(Math.round(255*fx*fy)<<24)|0xffffff);
            }
            texture=TtfFont.uploadHudInk(image,"az_widget_veil");
        }
        int f=Math.max(1,feather);
        int[] xs={x-f,x,x+w,x+w+f},ys={y-f,y,y+h,y+h+f};
        int[] uv={0,HALF-1,HALF,N},len={HALF,1,HALF};
        VeilRenderState.text();
        for(int row=0;row<3;row++)for(int col=0;col<3;col++)
            TtfFont.drawHudTexture(texture,uv[col],uv[row],len[col],len[row],
                xs[col],ys[row],xs[col+1]-xs[col],ys[row+1]-ys[row],color);
    }
}
