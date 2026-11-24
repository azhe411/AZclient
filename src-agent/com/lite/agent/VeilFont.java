package com.lite.agent;

import java.awt.*;
import java.awt.font.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;

/** Bundled Exo 2 native italic, grayscale coverage at physical pixel size, one metric baseline per face.
 * Shadow is preblurred in the atlas; the white glyph itself is never blurred or sharpened. */
final class VeilFont {
    private static Font regular,light;
    private static final LinkedHashMap<String,Face> cache=new LinkedHashMap<String,Face>(8,.75f,true);
    // Different widget scales are a simultaneous working set, not stale entries.
    // Twelve faces made a 13-face frame evict and rasterize its entire set forever.
    private static final int MAX_FACES=64;
    private static final long MAX_ATLAS_BYTES=128L*1024*1024;
    private static final LinkedHashMap<Float,Metrics> metrics=new LinkedHashMap<Float,Metrics>(16,.75f,true);
    private static final FontRenderContext METRIC_CONTEXT=new FontRenderContext(null,true,true);
    private static final class Metrics {
        final Font font;final float ascent,capHeight;
        final Map<Character,Float> advances=new HashMap<Character,Float>();
        Metrics(Font font){this.font=font;ascent=font.getLineMetrics("Ag0123456789",METRIC_CONTEXT).getAscent();
            capHeight=(float)font.createGlyphVector(METRIC_CONTEXT,"H").getVisualBounds().getHeight();}
        float advance(char c){
            Float a=advances.get(c);if(a!=null)return a;
            a=fallback(font,c).createGlyphVector(METRIC_CONTEXT,new char[]{c}).getGlyphMetrics(0).getAdvanceX();
            if(advances.size()>=512)advances.clear();advances.put(c,a);return a;
        }
    }
    private static void fonts()throws Exception{
        if(regular==null){regular=load("Exo2-MediumItalic.ttf");light=load("Exo2-Italic.ttf");}
    }
    private static Metrics metrics(float pixels)throws Exception{
        pixels=Math.round(pixels*4)/4f;Metrics m=metrics.get(pixels);if(m!=null)return m;
        fonts();m=new Metrics(regular.deriveFont(pixels));metrics.put(pixels,m);
        while(metrics.size()>128){Iterator<Metrics> it=metrics.values().iterator();it.next();it.remove();}
        return m;
    }
    // Measuring/truncating labels must never allocate a bitmap, upload a texture,
    // evict a face, or synchronize with GL. Only actual drawing needs an atlas.
    static float measure(String text,float pixels,float tracking)throws Exception{
        Metrics m=metrics(pixels);float x=0;
        for(int i=0;i<text.length();i++){x+=m.advance(text.charAt(i));if(i+1<text.length())x+=tracking;}
        return x;
    }
    static int center(float pixels)throws Exception{Metrics m=metrics(pixels);return Math.round(m.ascent-m.capHeight*.5f);}
    private static int atlasSide(float pixels,int characters){
        int cell=(int)Math.ceil(pixels*1.7f)+Math.max(10,Math.round(pixels*.77f))*2;
        int columns=(int)Math.ceil(Math.sqrt(characters*2));
        // DynamicTexture accepts NPOT sizes. Do not turn a ~1100px working
        // atlas into 2048px and exhaust the cache with transparent padding.
        return Math.max(256,((columns*cell+63)/64)*64);
    }
    static final class Glyph {int x,y,w,h,bearingX,bearingY,shadowX,shadowY;float advance;Object texture;}
    static final class Face {
        final float size,ascent,capHeight;final Map<Character,Glyph> glyphs;final Object texture;final Font font;final long bytes;
        final LinkedHashMap<Integer,Face> pages=new LinkedHashMap<Integer,Face>(8,.75f,true);
        Face(Font font,float ascent,Map<Character,Glyph> glyphs,Object texture,int side){this.bytes=4L*side*side;this.font=font;this.size=font.getSize2D();this.ascent=ascent;this.glyphs=glyphs;this.texture=texture;
            this.capHeight=(float)font.createGlyphVector(new FontRenderContext(null,true,true),"H").getVisualBounds().getHeight();}
        Glyph glyph(char c){
            Glyph g=glyphs.get(c);if(g!=null)return g;
            int block=c/16;
            Face page=pages.get(block);
            if(page==null)try{
                StringBuilder chars=new StringBuilder();for(int i=block*16;i<block*16+16;i++)chars.append((char)i);
                page=build(font,"extra_"+font.getFontName()+"_"+size+"_"+block,chars.toString());pages.put(block,page);
                while(pages.size()>8){Iterator<Face> it=pages.values().iterator();Face old=it.next();it.remove();old.release();}
            }catch(Exception e){throw new IllegalStateException("Overlay glyph atlas",e);}
            return page.glyphs.get(c);
        }
        void release(){TtfFont.releaseHudInk(texture);for(Face page:pages.values())page.release();pages.clear();}
        float width(String text,float tracking,boolean tabular){
            float x=0;for(int i=0;i<text.length();i++){Glyph g=glyph(text.charAt(i));if(g!=null)x+=advance(g,text.charAt(i),tabular);if(i+1<text.length())x+=tracking;}return x;
        }
        float advance(Glyph g,char c,boolean tabular){return tabular&&c>='0'&&c<='9'?glyphs.get('0').advance:g.advance;}
        void draw(String text,float x,float baseline,int color,float tracking,boolean tabular)throws Exception {
            VeilRenderState.text();
            // All shadows first: neighbouring glyphs cannot paint shadows over crisp foreground ink.
            for(int pass=0;pass<2;pass++){
                float pen=x;
                for(int i=0;i<text.length();i++){
                    char c=text.charAt(i);Glyph g=glyph(c);if(g==null)continue;
                    float advance=advance(g,c,tabular),offset=tabular?(advance-g.advance)*.5f:0;
                    int ink=pass==0?Math.round((color>>>24)*.28f)<<24:color;
                    TtfFont.drawHudInk(g.texture,pass==0?g.shadowX:g.x,pass==0?g.shadowY:g.y,g.w,g.h,pen+offset+g.bearingX,baseline+g.bearingY+(pass==0?1:0),ink);
                    pen+=advance+tracking;
                }
            }
        }
    }
    static Face face(float pixels,boolean thin)throws Exception {
        // Raster at the requested physical size, never magnify a fixed 13px texture.
        pixels=Math.round(pixels*4)/4f;String key=pixels+":"+thin;
        Face f=cache.get(key);if(f!=null)return f;
        // Evict before allocation. Keep the two most recent faces because Veil
        // borrows body, suffix and title together. A count-only limit retained
        // several 4096px atlases during DPI/scale changes.
        int side=atlasSide(pixels,pixels>70?18:95);
        long used=0;for(Face old:cache.values())used+=old.bytes;
        while(cache.size()>2&&(cache.size()>=MAX_FACES||used+4L*side*side>MAX_ATLAS_BYTES)){
            Iterator<Face> it=cache.values().iterator();Face old=it.next();it.remove();used-=old.bytes;old.release();
        }
        // Approved B specimen: Medium Italic (500) names, Italic (400) suffix/title.
        fonts();
        f=build((thin?light:regular).deriveFont(pixels),key);cache.put(key,f);
        while(cache.size()>MAX_FACES){Iterator<Face> it=cache.values().iterator();Face old=it.next();it.remove();old.release();}
        return f;
    }
    private static Font load(String name)throws Exception {
        InputStream in=VeilFont.class.getResourceAsStream("/azclient/fonts/"+name);
        if(in==null)throw new IOException("Missing bundled HUD font: "+name);
        try{return Font.createFont(Font.TRUETYPE_FONT,in);}finally{in.close();}
    }
    private static Face build(Font font,String key)throws Exception {
        String chars=font.getSize2D()>70?"AZCLIENT0123456789":"";
        if(chars.length()==0)for(int c=32;c<=126;c++)chars+=(char)c;
        return build(font,key,chars);
    }
    private static Font fallback(Font primary,char c){
        if(primary.canDisplay(c))return primary;
        Font chinese=new Font("Microsoft YaHei",Font.PLAIN,1).deriveFont(primary.getSize2D());
        return chinese.canDisplay(c)?chinese:new Font("Dialog",Font.PLAIN,1).deriveFont(primary.getSize2D());
    }
    private static Face build(Font font,String key,String chars)throws Exception {
        FontRenderContext frc=new FontRenderContext(null,true,true);
        float size=font.getSize2D();int pad=Math.max(10,Math.round(size*.77f));
        int cell=(int)Math.ceil(size*1.7f)+pad*2,side=atlasSide(size,chars.length());
        BufferedImage atlas=new BufferedImage(side,side,BufferedImage.TYPE_INT_ARGB);
        int[] dest=((DataBufferInt)atlas.getRaster().getDataBuffer()).getData();Arrays.fill(dest,0x00FFFFFF);
        Map<Character,Glyph> glyphs=new HashMap<Character,Glyph>();int cols=side/cell;
        float ascent=font.getLineMetrics("Ag0123456789",frc).getAscent();
        for(int ci=0;ci<chars.length();ci++){
            int c=chars.charAt(ci);
            Font selected=fallback(font,(char)c);
            GlyphVector vector=selected.createGlyphVector(frc,new char[]{(char)c});Rectangle bounds=vector.getPixelBounds(frc,0,0);
            Glyph g=new Glyph();int index=ci*2;
            g.x=index%cols*cell;g.y=index/cols*cell;
            // Keep each shadow in the following tile, even across atlas row boundaries.
            int shadowX=(index+1)%cols*cell,shadowY=(index+1)/cols*cell;
            g.w=cell;g.h=cell;g.bearingX=bounds.x-pad;g.bearingY=bounds.y-pad;g.advance=vector.getGlyphMetrics(0).getAdvanceX();
            BufferedImage sample=new BufferedImage(cell*2,cell*2,BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg=sample.createGraphics();gg.scale(2,2);gg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,RenderingHints.VALUE_FRACTIONALMETRICS_ON);gg.setColor(Color.WHITE);gg.setFont(font);
            gg.drawGlyphVector(vector,pad-bounds.x,pad-bounds.y);gg.dispose();
            int[] src=((DataBufferInt)sample.getRaster().getDataBuffer()).getData();float[] alpha=new float[cell*cell];
            for(int y=0;y<cell;y++)for(int x=0;x<cell;x++){int p=y*4*cell+x*2;int a=((src[p]>>>24)+(src[p+1]>>>24)+(src[p+cell*2]>>>24)+(src[p+cell*2+1]>>>24)+2)/4;
                alpha[y*cell+x]=a;dest[(g.y+y)*side+g.x+x]=(a<<24)|0xFFFFFF;}
            float[] blur=blur(alpha,cell,Math.max(1.8f,size*.14f));
            // Coverage-normalize only the separate soft shadow: Light strokes otherwise
            // lose almost all of the requested 28% support after Gaussian convolution.
            // Foreground coverage remains the original grayscale supersampled bitmap.
            float peak=0;for(float value:blur)peak=Math.max(peak,value);
            if(peak>0)for(int k=0;k<blur.length;k++)blur[k]*=255f/peak;
            for(int y=0;y<cell;y++)for(int x=0;x<cell;x++)dest[(shadowY+y)*side+shadowX+x]=(Math.round(blur[y*cell+x])<<24)|0xFFFFFF;
            // Source shadow can occupy another column; store it in a matching glyph record.
            g.shadowX=shadowX;g.shadowY=shadowY;glyphs.put((char)c,g);
        }
        Object texture=TtfFont.uploadHudInk(atlas,"az_veil_"+key);
        for(Glyph g:glyphs.values())g.texture=texture;
        return new Face(font,ascent,glyphs,texture,side);
    }
    private static float[] blur(float[] a,int side,float sigma){
        int radius=(int)Math.ceil(sigma*3);float[] weights=new float[radius*2+1];float sum=0;
        for(int k=-radius;k<=radius;k++){float v=(float)Math.exp(-k*k/(2*sigma*sigma));weights[k+radius]=v;sum+=v;}
        for(int k=0;k<weights.length;k++)weights[k]/=sum;
        float[] temp=new float[a.length],out=new float[a.length];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++)for(int k=-radius;k<=radius;k++)if(x+k>=0&&x+k<side)temp[y*side+x]+=a[y*side+x+k]*weights[k+radius];
        for(int y=0;y<side;y++)for(int x=0;x<side;x++)for(int k=-radius;k<=radius;k++)if(y+k>=0&&y+k<side)out[y*side+x]+=temp[(y+k)*side+x]*weights[k+radius];
        return out;
    }
}
