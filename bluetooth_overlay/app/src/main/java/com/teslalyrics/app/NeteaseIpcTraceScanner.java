package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Finds an externally reachable IPC path into NetEase's player process. */
public final class NeteaseIpcTraceScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private NeteaseIpcTraceScanner(){}

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM IPCTRACE error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-ipc-trace").start();
    }

    @SuppressWarnings("deprecation")
    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        AppState.get().log.add("NCM IPCTRACE start: exported IPC + IPlayService");
        PackageInfo pi=pm.getPackageInfo(PKG,PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS);
        int exported=0;
        if(pi.services!=null)for(ServiceInfo s:pi.services){
            if(s.exported&&interesting(s.name,s.permission)){
                AppState.get().log.add("NCM IPCTRACE SERVICE exported name="+s.name+" perm="+safe(s.permission)+" process="+safe(s.processName));exported++;
            }
        }
        if(pi.receivers!=null)for(ActivityInfo r:pi.receivers){
            if(r.exported&&interesting(r.name,r.permission)){
                AppState.get().log.add("NCM IPCTRACE RECEIVER exported name="+r.name+" perm="+safe(r.permission)+" process="+safe(r.processName));exported++;
            }
        }
        if(pi.activities!=null)for(ActivityInfo a:pi.activities){
            if(a.exported&&interesting(a.name,a.permission)){
                AppState.get().log.add("NCM IPCTRACE ACTIVITY exported name="+a.name+" perm="+safe(a.permission)+" process="+safe(a.processName));exported++;
            }
        }
        if(pi.providers!=null)for(ProviderInfo p:pi.providers){
            if(p.exported&&interesting(p.name,p.authority)){
                AppState.get().log.add("NCM IPCTRACE PROVIDER exported name="+p.name+" auth="+safe(p.authority)+" rperm="+safe(p.readPermission)+" wperm="+safe(p.writePermission));exported++;
            }
        }

        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();if(ai.sourceDir!=null)paths.add(ai.sourceDir);if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        int dex=0,methods=0,strings=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize();if(sz<=0||sz>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));int[] r=d.scan();methods+=r[0];strings+=r[1];dex++;
                }
            }
        }
        AppState.get().log.add("NCM IPCTRACE done: exportedRelevant="+exported+" dex="+dex+" binderMethods="+methods+" routeStrings="+strings);
    }

    private static boolean interesting(String a,String b){String x=(safe(a)+" "+safe(b)).toLowerCase(Locale.ROOT);return x.contains("play")||x.contains("music")||x.contains("audio")||x.contains("media")||x.contains("voice")||x.contains("sing")||x.contains("sep")||x.contains("orpheus")||x.contains("cloudmusic")||x.contains("command");}

    private static final class Dex{
        final String name;final byte[] b;final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff;final Map<Integer,String> cache=new HashMap<>();
        Dex(String n,byte[] x){name=n;b=x;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);methodsN=i32(0x58);methodsOff=i32(0x5c);}
        int[] scan(){if(b.length<0x70||!range(methodsOff,(long)methodsN*8))return new int[]{0,0};int mc=0,sc=0;
            for(int i=0;i<methodsN&&mc<120;i++){String m=method(i);String x=m.toLowerCase(Locale.ROOT);if((x.contains("iplayservice")||x.contains("playservice$stub")||x.contains("playservice$proxy"))&&(x.contains("voice")||x.contains("audio")||x.contains("music")||x.contains("player")||x.contains("command")||x.contains("sep")||x.contains("track")||x.contains("volume")||x.contains("mode")||x.contains("current"))){AppState.get().log.add("NCM IPCTRACE BINDER "+name+" #"+i+" "+m);mc++;}}
            for(int i=0;i<stringsN&&sc<80;i++){String s=str(i);if(routeString(s)){AppState.get().log.add("NCM IPCTRACE ROUTE "+name+" "+shorten(s,340));sc++;}}
            return new int[]{mc,sc};}
        boolean routeString(String s){if(s==null||s.length()<4||s.length()>500)return false;String x=s.toLowerCase(Locale.ROOT);boolean route=x.contains("orpheus://")||x.contains("intent://")||x.contains("content://")||x.contains("broadcast")||x.contains("action_")||x.contains(".action.");boolean sing=x.contains("septrack")||x.contains("musicvoice")||x.contains("audiosep")||x.contains("sing")||x.contains("karaoke")||s.contains("随心唱")||s.contains("人声");return route&&sing;}
        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;if(cache.containsKey(idx))return cache.get(idx);int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1200);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;String v;try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}cache.put(idx,v);return v;}
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String safe(String s){return s==null?"":s;}private static String shorten(String s,int n){return s.length()<=n?s:s.substring(0,n)+"…";}
}
