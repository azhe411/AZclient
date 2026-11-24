package com.lite.agent;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
/** Per-attempt confirmation; game logs are diagnostics, never a success channel. */
final class InjectionReceipt {
    final File file; final String id, pid;
    private InjectionReceipt(File f, String i, String p) { file=f; id=i; pid=p; }
    static InjectionReceipt parse(String args) throws Exception {
        if (args == null || !args.startsWith("azreceipt:")) return null;
        String[] parts=args.split(":",4);
        if(parts.length!=4 || !parts[1].matches("[0-9a-f-]{36}") || !parts[2].matches("[0-9]+"))
            throw new IllegalArgumentException("Invalid injection receipt request");
        String current=ManagementFactory.getRuntimeMXBean().getName().split("@",2)[0];
        if(!current.equals(parts[2])) throw new IllegalArgumentException("Injection receipt PID mismatch");
        File f=new File(new String(Base64.getUrlDecoder().decode(parts[3]), StandardCharsets.UTF_8));
        if(!f.isAbsolute() || !"result.properties".equals(f.getName())) throw new IllegalArgumentException("Invalid receipt path");
        return new InjectionReceipt(f,parts[1],current);
    }
    void write(String state,String version,String reason) {
        try {
            Properties p=new Properties();p.setProperty("protocol","1");p.setProperty("request",id);
            p.setProperty("pid",pid);p.setProperty("state",state);p.setProperty("version",version);
            p.setProperty("reason",reason==null?"":reason);
            StringWriter out=new StringWriter();p.store(out,"AZclient injection result");
            AtomicFiles.writeUtf8(file,out.toString(),false);
        } catch(Exception e) { LiteAgent.log("receipt write FAILED: "+e); }
    }
    void awaitReady(final Instrumentation inst,final String expected) {
        Thread waiter=new Thread(new Runnable(){public void run(){
            try {
                long until=System.nanoTime()+10000000000L;
                String reason="Hook initialization timed out";
                do {
                    // LaunchWrapper can define Hook in a child loader. Observe the
                    // class actually loaded there instead of assuming the agent's copy.
                    for(Class<?> c:inst.getAllLoadedClasses()) if(c.getName().equals("com.lite.agent.Hook")) {
                        if(Boolean.TRUE.equals(c.getMethod("isInited").invoke(null))) {
                            java.lang.reflect.Field f=c.getDeclaredField("VERSION");f.setAccessible(true);
                            String actual=String.valueOf(f.get(null));
                            write(actual.equals(expected)?"READY":"STALE",actual,"");return;
                        }
                        try { reason=String.valueOf(c.getMethod("readinessReason").invoke(null)); }
                        catch(NoSuchMethodException ignored) { }
                    }
                    Thread.sleep(50);
                }while(System.nanoTime()<until);
                write("NOT_READY",expected,reason);
            }catch(Throwable e){write("ERROR",expected,e.toString());}
        }},"AZclient injection confirmation");
        waiter.setDaemon(true);waiter.start();
    }
}
