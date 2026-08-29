package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Focused DEX cross-reference scanner for NetEase karaoke/KTV mode strings. */
public final class NeteaseKaraokeXrefScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final String[] NEEDLES={
            "audio_karaoke_ktvmode=enable",
            "audio_karaoke_ktvmode=disable",
            "audio_karaoke_ktvmode",
            "KARAOKE_KTVMODE",
            "/karaoke/onlineKtv"
    };
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private NeteaseKaraokeXrefScanner(){}

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM XREF error: "+t.getClass().getSimpleName());}
            finally{RUNNING.set(false);}
        },"ncm-karaoke-xref").start();
    }

    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        AppState.get().log.add("NCM XREF scan start");
        int dex=0,refs=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize();
                    if(sz<=0||sz>90L*1024L*1024L)continue;
                    byte[] b=readAll(z.getInputStream(e),(int)Math.min(Integer.MAX_VALUE,sz+1024));
                    Dex d=new Dex(e.getName(),b);
                    int n=d.scan();
                    if(n>0)refs+=n;
                    dex++;
                }
            }
        }
        AppState.get().log.add("NCM XREF done: dex="+dex+" refs="+refs);
    }

    private static final class Dex {
        final String name; final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,methodsN,methodsOff,classesN,classesOff;
        final Map<Integer,String> cache=new HashMap<>();
        final Map<Integer,String> targetByIndex=new HashMap<>();
        Dex(String n,byte[] data){
            name=n;b=data;
            stringsN=i32(0x38);stringsOff=i32(0x3c);
            typesN=i32(0x40);typesOff=i32(0x44);
            methodsN=i32(0x58);methodsOff=i32(0x5c);
            classesN=i32(0x60);classesOff=i32(0x64);
        }
        int scan(){
            if(b.length<0x70||!range(stringsOff,(long)stringsN*4)||!range(methodsOff,(long)methodsN*8)||!range(classesOff,(long)classesN*32))return 0;
            for(int i=0;i<stringsN;i++){
                String s=str(i);
                if(s==null)continue;
                for(String q:NEEDLES)if(s.contains(q)){targetByIndex.put(i,s);break;}
            }
            if(targetByIndex.isEmpty())return 0;
            AppState.get().log.add("NCM XREF "+name+" targets="+targetByIndex.values());
            int refs=0;
            for(int i=0;i<classesN;i++){
                int p=classesOff+i*32;
                int dataOff=i32(p+24);
                if(dataOff>0&&dataOff<b.length)refs+=scanClassData(dataOff);
            }
            return refs;
        }
        int scanClassData(int off){
            try{
                int[] p={off};
                int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}
                int refs=scanMethodList(p,dm);
                refs+=scanMethodList(p,vm);
                return refs;
            }catch(Throwable ignored){return 0;}
        }
        int scanMethodList(int[] p,int count){
            int idx=0,refs=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p); uleb(p); int codeOff=uleb(p);
                if(codeOff>0)refs+=scanCode(idx,codeOff);
            }
            return refs;
        }
        int scanCode(int methodIdx,int codeOff){
            if(!range(codeOff,16))return 0;
            int units=i32(codeOff+12);
            long bytes=(long)units*2;
            if(units<=0||!range(codeOff+16,bytes))return 0;
            int start=codeOff+16;
            Set<String> hitTargets=new LinkedHashSet<>();
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&0xff;
                if(op==0x1a&&u+1<units){
                    int si=u16(start+(u+1)*2);
                    String t=targetByIndex.get(si);if(t!=null)hitTargets.add(t);
                }else if(op==0x1b&&u+2<units){
                    int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);
                    String t=targetByIndex.get(si);if(t!=null)hitTargets.add(t);
                }
            }
            if(hitTargets.isEmpty())return 0;
            String owner=method(methodIdx);
            AppState.get().log.add("NCM XREF HIT "+name+" "+owner+" <= "+join(hitTargets,160));
            Set<String> ss=new LinkedHashSet<>(),calls=new LinkedHashSet<>();
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&0xff;
                if(op==0x1a&&u+1<units){
                    addString(ss,str(u16(start+(u+1)*2)));
                }else if(op==0x1b&&u+2<units){
                    int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);addString(ss,str(si));
                }else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    if(u+1<units){String m=method(u16(start+(u+1)*2));if(interestingCall(m))calls.add(m);}
                }
            }
            if(!ss.isEmpty())AppState.get().log.add("NCM XREF strings: "+join(ss,360));
            if(!calls.isEmpty())AppState.get().log.add("NCM XREF calls: "+join(calls,360));
            return 1;
        }
        void addString(Set<String> out,String s){
            if(s==null||s.length()<2||s.length()>180||out.size()>=18)return;
            String low=s.toLowerCase(java.util.Locale.ROOT);
            if(low.contains("karaoke")||low.contains("ktv")||low.contains("audio")||low.contains("param")||low.contains("enable")||low.contains("disable")||low.contains("player")||low.contains("orpheus")||s.contains("随心唱")||s.contains("伴奏")||s.contains("原唱")||s.contains("人声"))out.add(s);
        }
        boolean interestingCall(String s){
            if(s==null)return false;
            String x=s.toLowerCase(java.util.Locale.ROOT);
            return x.contains("param")||x.contains("audio")||x.contains("player")||x.contains("karaoke")||x.contains("ktv")||x.contains("command")||x.contains("native")||x.contains("effect")||x.contains("invoke")||x.contains("set");
        }
        String method(int idx){
            if(idx<0||idx>=methodsN)return "method#"+idx;
            int p=methodsOff+idx*8;
            int classIdx=u16(p),nameIdx=i32(p+4);
            return type(classIdx)+"->"+safe(str(nameIdx));
        }
        String type(int idx){
            if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "type#"+idx;
            return safe(str(i32(typesOff+idx*4)));
        }
        String str(int idx){
            if(idx<0||idx>=stringsN)return null;
            if(cache.containsKey(idx))return cache.get(idx);
            int p=stringsOff+idx*4;if(!range(p,4))return null;
            int off=i32(p);if(off<=0||off>=b.length)return null;
            int[] q={off};try{uleb(q);}catch(Throwable t){return null;}
            int s=q[0],e=s,max=Math.min(b.length,s+900);
            while(e<max&&b[e]!=0)e++;
            if(e<=s||e>=max)return null;
            String v;try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}
            cache.put(idx,v);return v;
        }
        int uleb(int[] pp){
            int result=0,shift=0,p=pp[0];
            for(int i=0;i<5;i++){
                if(p>=b.length)throw new IllegalArgumentException();
                int v=b[p++]&255;result|=(v&0x7f)<<shift;
                if((v&0x80)==0){pp[0]=p;return result;}shift+=7;
            }
            throw new IllegalArgumentException();
        }
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static byte[] readAll(InputStream in,int hint)throws Exception{
        try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream(Math.max(8192,Math.min(hint,4*1024*1024)))){
            byte[] buf=new byte[32768];int n,total=0;
            while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();
        }
    }
    private static String safe(String s){return s==null?"?":s;}
    private static String join(Iterable<String> xs,int max){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>=max)break;}String r=b.toString();return r.length()<=max?r:r.substring(0,max)+"…";}
}
