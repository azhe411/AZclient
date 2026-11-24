package com.lite.agent;
import java.lang.reflect.*;import java.nio.*;import java.util.*;
/** Half-resolution separable Gaussian framebuffer pass. No world/model/movement state is read or written.
 * GL state is restored exactly; regions reuse one clean source frame, so HUD glass never blurs other HUD text. */
final class FrostPass {
 private static final Map<String,Method> methods=new HashMap<String,Method>();
 private static int program,vao,fbo,source,ping,pong,w,h;private static boolean valid,failed;
 private static int mode,delta,sampler,area,corner,opacity,glowWidth,glowStart,glowEnd;private static boolean glowFailed;
 private static Object gl(int version,String name,Object...args)throws Exception{
  String key=version+name;for(Object a:args)key+="/"+(a instanceof Integer?"i":a instanceof Float?"f":a instanceof CharSequence?"s":a instanceof IntBuffer?"ib":a==null?"bb":a.getClass().getName());
  Method m=methods.get(key);
  if(m==null){Class<?>[] types=new Class<?>[args.length];for(int i=0;i<types.length;i++)types[i]=args[i] instanceof Integer?int.class:args[i] instanceof Float?float.class:args[i] instanceof CharSequence?CharSequence.class:args[i] instanceof IntBuffer?IntBuffer.class:args[i]==null?ByteBuffer.class:args[i].getClass();
   m=Class.forName("org.lwjgl.opengl.GL"+version).getMethod(name,types);methods.put(key,m);}
  return m.invoke(null,args);
 }
 private static int n(int v,String name,Object...args)throws Exception{return ((Number)gl(v,name,args)).intValue();}
 private static int get(int key)throws Exception{return n(11,"glGetInteger",key);}
 private static IntBuffer ints(int count){return ByteBuffer.allocateDirect(Math.max(16,count)*4).order(ByteOrder.nativeOrder()).asIntBuffer();}
 private static int[] viewport()throws Exception{IntBuffer b=ints(4);try{gl(11,"glGetIntegerv",2978,b);}catch(NoSuchMethodException e){gl(11,"glGetInteger",2978,b);}return new int[]{b.get(0),b.get(1),b.get(2),b.get(3)};}
 private static int shader(int type,String src)throws Exception{int id=n(20,"glCreateShader",type);gl(20,"glShaderSource",id,src);gl(20,"glCompileShader",id);
  if(n(20,"glGetShaderi",id,35713)==0){String log=String.valueOf(gl(20,"glGetShaderInfoLog",id,8192));gl(20,"glDeleteShader",id);throw new IllegalStateException(log);}return id;}
 private static void init()throws Exception{
  if(program!=0)return;int vs=0,fs=0;
  try{
   vs=shader(35633,"#version 150\nout vec2 uv;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);uv=p;gl_Position=vec4(p*2.-1.,0.,1.);}");
   fs=shader(35632,"#version 150\nuniform sampler2D image;uniform vec2 delta;uniform int mode;uniform vec4 area;uniform float corner;uniform float opacity;uniform float glowWidth;uniform vec4 glowStart;uniform vec4 glowEnd;in vec2 uv;out vec4 frag;void main(){if(mode==0){vec3 c=texture(image,uv).rgb*.227027;c+=(texture(image,uv+delta*1.384615).rgb+texture(image,uv-delta*1.384615).rgb)*.316216;c+=(texture(image,uv+delta*3.230769).rgb+texture(image,uv-delta*3.230769).rgb)*.070270;frag=vec4(c,1.);}else{vec2 q=abs(gl_FragCoord.xy-(area.xy+area.zw)*.5)-(area.zw-area.xy)*.5+vec2(corner);float d=length(max(q,vec2(0.)))+min(max(q.x,q.y),0.)-corner;if(mode==2){if(d<=0.)discard;float z=d/max(1.,glowWidth);float a=(.64*exp(-.5*z*z/.0289)+.36*exp(-.5*z*z/.1296))*opacity*(1.-smoothstep(.78,1.,z));vec2 p=clamp((gl_FragCoord.xy-area.xy)/(area.zw-area.xy),vec2(0.),vec2(1.));vec4 c=mix(glowStart,glowEnd,p.x*.35+(1.-p.y)*.65);frag=vec4(c.rgb,a*c.a);}else{float a=(1.-smoothstep(-.8,.8,d))*opacity;if(mode==3)a=opacity*smoothstep(area.x,area.z,gl_FragCoord.x)*smoothstep(area.y,area.y+max(1.,corner),gl_FragCoord.y);if(a<=0.)discard;frag=vec4(texture(image,uv).rgb,a);}}}");
   program=n(20,"glCreateProgram");gl(20,"glAttachShader",program,vs);gl(20,"glAttachShader",program,fs);gl(20,"glLinkProgram",program);
   if(n(20,"glGetProgrami",program,35714)==0)throw new IllegalStateException(String.valueOf(gl(20,"glGetProgramInfoLog",program,8192)));
   vao=n(30,"glGenVertexArrays");fbo=n(30,"glGenFramebuffers");
   mode=n(20,"glGetUniformLocation",program,"mode");delta=n(20,"glGetUniformLocation",program,"delta");sampler=n(20,"glGetUniformLocation",program,"image");
   area=n(20,"glGetUniformLocation",program,"area");corner=n(20,"glGetUniformLocation",program,"corner");opacity=n(20,"glGetUniformLocation",program,"opacity");
   glowWidth=n(20,"glGetUniformLocation",program,"glowWidth");glowStart=n(20,"glGetUniformLocation",program,"glowStart");glowEnd=n(20,"glGetUniformLocation",program,"glowEnd");
  }finally{if(vs!=0)gl(20,"glDeleteShader",vs);if(fs!=0)gl(20,"glDeleteShader",fs);}
 }
 private static int texture(int id,int width,int height)throws Exception{if(id==0)id=n(11,"glGenTextures");gl(11,"glBindTexture",3553,id);
  gl(11,"glTexParameteri",3553,10241,9729);gl(11,"glTexParameteri",3553,10240,9729);gl(11,"glTexParameteri",3553,10242,33071);gl(11,"glTexParameteri",3553,10243,33071);
  gl(11,"glTexImage2D",3553,0,32856,width,height,0,6408,5121,null);return id;}

 /** Exterior-only rounded SDF halo. The original panel's entire interior is discarded. */
 static boolean glow(int logicalW,int logicalH,int x,int y,int width,int height,int radius,int spread,int first,int last,float strength){
  if(failed||glowFailed||logicalW<=0||logicalH<=0||width<=0||height<=0||spread<=0||strength<=0)return false;
  int oldProgram=0,oldVao=0,sr=0,dr=0,sa=0,da=0,eqR=0,eqA=0;int[] sc=null;boolean saved=false;
  int[] caps={2929,2884,3089,3042,2960,36281,32925};boolean[] enabled=new boolean[caps.length];
  try{
   oldProgram=get(35725);oldVao=get(34229);int[] vp=viewport();
   sr=get(32969);dr=get(32968);sa=get(32971);da=get(32970);eqR=get(32777);eqA=get(34877);
   IntBuffer sb=ints(4);try{gl(11,"glGetIntegerv",3088,sb);}catch(NoSuchMethodException e){gl(11,"glGetInteger",3088,sb);}sc=new int[]{sb.get(0),sb.get(1),sb.get(2),sb.get(3)};
   for(int i=0;i<caps.length;i++)enabled[i]=(Boolean)gl(11,"glIsEnabled",caps[i]);saved=true;
   init();gl(20,"glUseProgram",program);gl(30,"glBindVertexArray",vao);for(int cap:caps)gl(11,"glDisable",cap);
   gl(11,"glEnable",3042);gl(14,"glBlendFuncSeparate",770,771,1,771);gl(20,"glBlendEquationSeparate",32774,32774);
   float sx=vp[2]/(float)logicalW,sy=vp[3]/(float)logicalH;
   float l=vp[0]+x*sx,b=vp[1]+vp[3]-(y+height)*sy,right=l+width*sx,top=b+height*sy;
   gl(20,"glUniform1i",mode,2);gl(20,"glUniform4f",area,l,b,right,top);
   gl(20,"glUniform1f",corner,Math.min(radius,Math.min(width,height)/2f)*Math.min(sx,sy));
   gl(20,"glUniform1f",glowWidth,spread*Math.min(sx,sy));gl(20,"glUniform1f",opacity,Math.max(0,Math.min(1,strength)));
   gl(20,"glUniform4f",glowStart,((first>>16)&255)/255f,((first>>8)&255)/255f,(first&255)/255f,(first>>>24)/255f);
   gl(20,"glUniform4f",glowEnd,((last>>16)&255)/255f,((last>>8)&255)/255f,(last&255)/255f,(last>>>24)/255f);
   int leftClip=Math.max(vp[0],(int)Math.floor(l-spread*sx)),bottomClip=Math.max(vp[1],(int)Math.floor(b-spread*sy));
   int rightClip=Math.min(vp[0]+vp[2],(int)Math.ceil(right+spread*sx)),topClip=Math.min(vp[1]+vp[3],(int)Math.ceil(top+spread*sy));
   if(enabled[2]){leftClip=Math.max(leftClip,sc[0]);bottomClip=Math.max(bottomClip,sc[1]);rightClip=Math.min(rightClip,sc[0]+sc[2]);topClip=Math.min(topClip,sc[1]+sc[3]);}
   gl(11,"glEnable",3089);gl(11,"glScissor",leftClip,bottomClip,Math.max(0,rightClip-leftClip),Math.max(0,topClip-bottomClip));gl(11,"glDrawArrays",4,0,3);return true;
  }catch(Throwable e){glowFailed=true;Hook.logStack("Outer glow fallback",e);return false;}
  finally{if(saved)try{
   gl(20,"glUseProgram",oldProgram);gl(30,"glBindVertexArray",oldVao);gl(14,"glBlendFuncSeparate",sr,dr,sa,da);gl(20,"glBlendEquationSeparate",eqR,eqA);
   gl(11,"glScissor",sc[0],sc[1],sc[2],sc[3]);for(int i=0;i<caps.length;i++)gl(11,enabled[i]?"glEnable":"glDisable",caps[i]);
  }catch(Throwable e){Hook.log("Outer glow state restore: "+e);}}
 }
 static void beginFrame(){valid=false;}
 static boolean drawVeil(int width,int height,int left,int bottom,int fadeHeight,float blur,float alpha){return drawRegion(width,height,left,0,width,bottom,fadeHeight,blur,alpha,true);}
 static boolean draw(int logicalW,int logicalH,int x,int y,int right,int bottom,int round,float blur,float alpha){return drawRegion(logicalW,logicalH,x,y,right,bottom,round,blur,alpha,false);}
 private static boolean drawRegion(int logicalW,int logicalH,int x,int y,int right,int bottom,int round,float blur,float alpha,boolean feather){
  if(failed||logicalW<=0||logicalH<=0||right<=x||bottom<=y)return false;
  int oldProgram=0,oldVao=0,oldFbo=0,oldRead=0,active=0,tex=0,sr=0,dr=0,sa=0,da=0;int[] vp=null,sc=null;boolean saved=false;
  int[] caps={2929,2884,3089,3042,2960,36281,32925};boolean[] enabled=new boolean[caps.length];
  try{
   oldProgram=get(35725);oldVao=get(34229);oldFbo=get(36006);oldRead=get(36010);active=get(34016);vp=viewport();
   sr=get(32969);dr=get(32968);sa=get(32971);da=get(32970);IntBuffer sb=ints(4);try{gl(11,"glGetIntegerv",3088,sb);}catch(NoSuchMethodException e){gl(11,"glGetInteger",3088,sb);}sc=new int[]{sb.get(0),sb.get(1),sb.get(2),sb.get(3)};
   for(int i=0;i<caps.length;i++)enabled[i]=(Boolean)gl(11,"glIsEnabled",caps[i]);
   gl(13,"glActiveTexture",33984);tex=get(32873);saved=true;
   init();gl(20,"glUseProgram",program);gl(30,"glBindVertexArray",vao);for(int cap:caps)gl(11,"glDisable",cap);
   gl(20,"glUniform1i",sampler,0);
   int width=vp[2],height=vp[3],bw=Math.max(1,width/2),bh=Math.max(1,height/2);
   if(w!=width||h!=height){source=texture(source,width,height);ping=texture(ping,bw,bh);pong=texture(pong,bw,bh);w=width;h=height;valid=false;}
   if(!valid){
    gl(30,"glBindFramebuffer",36008,oldFbo);gl(11,"glBindTexture",3553,source);gl(11,"glCopyTexSubImage2D",3553,0,0,0,vp[0],vp[1],w,h);
    gl(30,"glBindFramebuffer",36160,fbo);gl(11,"glViewport",0,0,bw,bh);gl(20,"glUniform1i",mode,0);
    int from=source;float radius=Math.max(2,Math.min(48,blur));
    for(int pass=0;pass<6;pass++){
     int dest=(pass&1)==0?ping:pong;gl(30,"glFramebufferTexture2D",36160,36064,3553,dest,0);
     if(n(30,"glCheckFramebufferStatus",36160)!=36053)throw new IllegalStateException("incomplete blur framebuffer");
     gl(11,"glBindTexture",3553,from);gl(20,"glUniform2f",delta,(pass&1)==0?radius/(3*w):0f,(pass&1)==1?radius/(3*h):0f);
     gl(11,"glDrawArrays",4,0,3);from=dest;
    }
    valid=true;
   }
   gl(30,"glBindFramebuffer",36009,oldFbo);gl(11,"glViewport",vp[0],vp[1],vp[2],vp[3]);
   gl(11,"glEnable",3042);gl(14,"glBlendFuncSeparate",770,771,1,771);gl(11,"glBindTexture",3553,pong);gl(20,"glUniform1i",mode,feather?3:1);
   float kx=w/(float)logicalW,ky=h/(float)logicalH;float l=x*kx+vp[0],b=h-bottom*ky+vp[1],r=right*kx+vp[0],t=h-y*ky+vp[1];
   gl(20,"glUniform4f",area,l,b,r,t);gl(20,"glUniform1f",corner,round*Math.min(kx,ky));gl(20,"glUniform1f",opacity,Math.max(0,Math.min(1,alpha)));
   gl(11,"glEnable",3089);gl(11,"glScissor",Math.max(vp[0],(int)l),Math.max(vp[1],(int)b),Math.max(0,(int)(r-l+1)),Math.max(0,(int)(t-b+1)));gl(11,"glDrawArrays",4,0,3);
   return true;
  }catch(Throwable e){failed=true;Hook.logStack("Frost shader fallback",e);return false;}
  finally{if(saved)try{
   gl(30,"glBindFramebuffer",36009,oldFbo);gl(30,"glBindFramebuffer",36008,oldRead);gl(11,"glViewport",vp[0],vp[1],vp[2],vp[3]);gl(11,"glScissor",sc[0],sc[1],sc[2],sc[3]);
   gl(20,"glUseProgram",oldProgram);gl(30,"glBindVertexArray",oldVao);gl(11,"glBindTexture",3553,tex);gl(13,"glActiveTexture",active);gl(14,"glBlendFuncSeparate",sr,dr,sa,da);
   for(int i=0;i<caps.length;i++)gl(11,enabled[i]?"glEnable":"glDisable",caps[i]);
  }catch(Throwable e){Hook.log("Frost state restore: "+e);}}
 }
 static void release(){try{for(int t:new int[]{source,ping,pong})if(t!=0)gl(11,"glDeleteTextures",t);if(fbo!=0)gl(30,"glDeleteFramebuffers",fbo);if(vao!=0)gl(30,"glDeleteVertexArrays",vao);if(program!=0)gl(20,"glDeleteProgram",program);}catch(Exception ignored){}source=ping=pong=fbo=vao=program=w=h=0;valid=false;}
}
