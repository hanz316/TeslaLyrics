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
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * One-shot diagnostic scanner for the user's installed NetEase Cloud Music APK.
 * It does not modify NetEase and does not need root. The goal is to discover
 * exported components and DEX strings that may reveal a lock-screen-safe route
 * for the "随心唱" feature (deep link / command / service / broadcast).
 */
public final class NeteaseKaraokeScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private NeteaseKaraokeScanner(){}

    private static final class Hit {
        final int score;
        final String text;
        Hit(int s,String t){score=s;text=t;}
    }

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM scan error: "+t.getClass().getSimpleName());}
            finally{RUNNING.set(false);}
        },"ncm-karaoke-scan").start();
    }

    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        PackageInfo pi=pm.getPackageInfo(PKG,
                PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_PROVIDERS);
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        AppState.get().log.add("NCM scan start: v"+pi.versionName+" ("+pi.versionCode+")");

        List<Hit> hits=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        addComponents(pi,hits,seen);

        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        int dexCount=0;
        for(String p:paths)dexCount+=scanApk(p,hits,seen);

        hits.sort(Comparator.comparingInt((Hit h)->h.score).reversed().thenComparing(h->h.text));
        AppState.get().log.add("NCM scan done: dex="+dexCount+" hits="+hits.size());
        int n=Math.min(36,hits.size());
        for(int i=0;i<n;i++)AppState.get().log.add("NCM code["+(i+1)+"] "+trim(hits.get(i).text,210));
        if(n==0)AppState.get().log.add("NCM scan: no karaoke-like strings found in readable DEX");
    }

    private static void addComponents(PackageInfo pi,List<Hit> out,Set<String> seen){
        if(pi.activities!=null)for(ActivityInfo x:pi.activities)if(x!=null)addComponent("activity",x.name,x.exported,out,seen);
        if(pi.services!=null)for(ServiceInfo x:pi.services)if(x!=null)addComponent("service",x.name,x.exported,out,seen);
        if(pi.receivers!=null)for(ActivityInfo x:pi.receivers)if(x!=null)addComponent("receiver",x.name,x.exported,out,seen);
        if(pi.providers!=null)for(ProviderInfo x:pi.providers)if(x!=null)addComponent("provider",x.name,x.exported,out,seen);
    }

    private static void addComponent(String type,String name,boolean exported,List<Hit> out,Set<String> seen){
        String s=(type+" "+(exported?"EXPORTED ":"")+name).trim();
        int sc=score(s);
        if(sc>0)add(out,seen,sc+(exported?24:0),s);
    }

    private static int scanApk(String apk,List<Hit> out,Set<String> seen){
        int dex=0;
        try(ZipFile z=new ZipFile(apk)){
            java.util.Enumeration<? extends ZipEntry> en=z.entries();
            while(en.hasMoreElements()){
                ZipEntry e=en.nextElement();
                String name=e.getName();
                int ns=score(name);
                if(ns>0)add(out,seen,ns+10,"apk-entry "+name);
                if(!name.matches("classes(\\d*)\\.dex"))continue;
                long sz=e.getSize();
                if(sz<=0||sz>90L*1024L*1024L)continue;
                byte[] b=readAll(z.getInputStream(e),(int)Math.min(Integer.MAX_VALUE,sz+1024));
                scanDexStrings(name,b,out,seen);
                dex++;
            }
        }catch(Throwable t){AppState.get().log.add("NCM apk scan skip: "+t.getClass().getSimpleName());}
        return dex;
    }

    private static void scanDexStrings(String dexName,byte[] b,List<Hit> out,Set<String> seen){
        if(b==null||b.length<0x70)return;
        int count=le32(b,0x38),table=le32(b,0x3c);
        if(count<0||count>2_000_000||table<0||table+(long)count*4>b.length)return;
        for(int i=0;i<count;i++){
            int off=le32(b,table+i*4);
            if(off<=0||off>=b.length)continue;
            int p=skipUleb(b,off);
            if(p<0||p>=b.length)continue;
            int end=p;
            int max=Math.min(b.length,p+700);
            while(end<max&&b[end]!=0)end++;
            int len=end-p;
            if(len<3||len>650)continue;
            String s;
            try{s=new String(b,p,len,StandardCharsets.UTF_8);}catch(Throwable t){continue;}
            int sc=score(s);
            if(sc>0)add(out,seen,sc,dexName+": "+s);
        }
    }

    private static int score(String raw){
        if(raw==null||raw.isEmpty())return 0;
        String s=raw.toLowerCase(Locale.ROOT);
        int n=0;
        if(raw.contains("随心唱"))n+=240;
        if(raw.contains("消音"))n+=170;
        if(raw.contains("原唱"))n+=120;
        if(raw.contains("伴奏"))n+=150;
        if(raw.contains("人声"))n+=130;
        if(raw.contains("跟唱"))n+=120;
        if(raw.contains("合唱"))n+=80;
        if(s.contains("karaoke"))n+=210;
        if(s.contains("singalong")||s.contains("sing_along")||s.contains("sing-along"))n+=190;
        if(s.contains("accompaniment")||s.contains("accompany"))n+=160;
        if(s.contains("vocalremov")||s.contains("vocal_remov")||s.contains("vocal-remov"))n+=200;
        else if(s.contains("vocal"))n+=90;
        if(s.contains("ktv"))n+=120;
        if(s.contains("microphone")||s.contains("micmode")||s.contains("mic_mode"))n+=70;
        if(s.contains("orpheus://")||s.contains("music.163.com/orpheus")||s.contains("deeplink"))n+=55;
        if(s.contains("customaction")||s.contains("custom_action")||s.contains("sendcommand")||s.contains("send_command"))n+=45;
        if(s.contains("broadcast")||s.contains("service")||s.contains("activity"))n+=8;
        if(s.length()>300)n-=25;
        return n>=70?n:0;
    }

    private static void add(List<Hit> out,Set<String> seen,int score,String text){
        if(text==null)return;
        String k=text.trim();
        if(k.isEmpty()||!seen.add(k))return;
        out.add(new Hit(score,k));
    }

    private static int le32(byte[] b,int p){
        if(p<0||p+3>=b.length)return -1;
        return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);
    }

    private static int skipUleb(byte[] b,int p){
        for(int i=0;i<5;i++){
            if(p>=b.length)return -1;
            int v=b[p++]&255;
            if((v&0x80)==0)return p;
        }
        return -1;
    }

    private static byte[] readAll(InputStream in,int hint)throws Exception{
        try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream(Math.max(8192,Math.min(hint,4*1024*1024)))){
            byte[] buf=new byte[32768];int n,total=0;
            while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();
        }
    }

    private static String trim(String s,int max){return s.length()<=max?s:s.substring(0,max)+"…";}
}
