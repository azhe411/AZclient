package com.lite.agent;

import java.io.*;
import java.util.*;

/** Presentation-only placement. Persist free-space ratios, never gameplay configuration. */
final class PanelPositions {
    private final double[] xs, ys;
    private final int[] order;
    private final File file;
    private boolean loaded, dirty;
    PanelPositions(int count, String name) {
        xs=new double[count]; ys=new double[count]; order=new int[count];
        Arrays.fill(xs,Double.NaN); Arrays.fill(ys,Double.NaN);
        for(int i=0;i<count;i++)order[i]=i;
        file=new File(new File(System.getProperty("user.home","."),".azclient"),name);
    }
    void load() {
        if(loaded)return; loaded=true;
        if(!file.isFile())return;
        Properties p=new Properties();
        try(Reader r=new InputStreamReader(new FileInputStream(file),"UTF-8")) {
            p.load(r);
            for(int i=0;i<xs.length;i++) {
                double a=number(p.getProperty(i+".x")),b=number(p.getProperty(i+".y"));
                if(Double.isFinite(a)&&Double.isFinite(b)){xs[i]=a;ys[i]=b;}
            }
            String[] saved=p.getProperty("order","").split(",");
            if(saved.length==order.length){
                int[] next=new int[order.length];boolean[] seen=new boolean[order.length];boolean valid=true;
                for(int i=0;i<next.length;i++)try{int id=Integer.parseInt(saved[i]);if(id<0||id>=next.length||seen[id]){valid=false;break;}next[i]=id;seen[id]=true;}catch(NumberFormatException e){valid=false;break;}
                if(valid)System.arraycopy(next,0,order,0,order.length);
            }
        } catch(IOException ignored) { /* Missing or interrupted preferences keep default positions. */ }
          catch(IllegalArgumentException ignored) { /* Malformed property escapes keep safe defaults. */ }
    }
    private static double number(String s) {
        try {double d=Double.parseDouble(s);return Double.isFinite(d)?Math.max(0,Math.min(1,d)):Double.NaN;}
        catch(Exception e){return Double.NaN;}
    }
    boolean custom(int id){return Double.isFinite(xs[id]);}
    int x(int id,int fallback,int viewport,int width){return custom(id)?4+(int)Math.round(xs[id]*Math.max(0,viewport-width-8)):fallback;}
    int y(int id,int fallback,int viewport,int minHeight){return custom(id)?4+(int)Math.round(ys[id]*Math.max(0,viewport-minHeight-8)):fallback;}
    void move(int id,int x,int y,int viewportW,int viewportH,int width,int minHeight) {
        xs[id]=Math.max(0,Math.min(1,(x-4d)/Math.max(1,viewportW-width-8)));
        ys[id]=Math.max(0,Math.min(1,(y-4d)/Math.max(1,viewportH-minHeight-8)));dirty=true;
    }
    int at(int depth){return order[depth];}
    void front(int id){int at=0;while(order[at]!=id)at++;for(int i=at;i<order.length-1;i++)order[i]=order[i+1];order[order.length-1]=id;dirty=true;}
    void reset(){Arrays.fill(xs,Double.NaN);Arrays.fill(ys,Double.NaN);for(int i=0;i<order.length;i++)order[i]=i;dirty=true;}
    void save() throws IOException {
        if(!dirty)return;
        StringBuilder b=new StringBuilder("# AZclient GUI positions; independent of module settings\n");
        b.append("order=");for(int i=0;i<order.length;i++){if(i>0)b.append(',');b.append(order[i]);}b.append('\n');
        for(int i=0;i<xs.length;i++)if(custom(i))b.append(i).append(".x=").append(xs[i]).append('\n').append(i).append(".y=").append(ys[i]).append('\n');
        AtomicFiles.writeUtf8(file,b.toString(),true);dirty=false;
    }
}
