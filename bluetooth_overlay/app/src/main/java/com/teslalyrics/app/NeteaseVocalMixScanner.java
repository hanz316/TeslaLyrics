package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Focused static scanner for NetEase Cloud Music's actual vocal/original/accompaniment
 * mix controls. Unlike the Xiaomi KTV vendor-parameter probe, this looks for generic
 * sing-mode/player code that is more likely to be used on non-Xiaomi phones too.
 */
public final class NeteaseVocalMixScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private static final int MAX_LOG_HITS=70;
    private NeteaseVocalMixScanner(){}

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM MIXSCAN error: "+t.getClass().getSimpleName());}
            finally{RUNNING.set(false);}
        },"ncm-vocal-mix-scan").start();
    }

    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        AppState.get().log.add("NCM MIXSCAN start: vocal/original/accompany mix");
        int dex=0,hits=0,methodNames=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&hits<MAX_LOG_HITS){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize();
                    if(sz<=0||sz>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));
                    int[] r=d.scan(MAX_LOG_HITS-hits);
                    hits+=r[0]; methodNames+=r[1]; dex++;
                }
            }
        }
        AppState.get().log.add("NCM MIXSCAN done: dex="+dex+" xrefs="+hits+" namedMethods="+methodNames);
    }

    private static final class Dex {
        final String name; final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,methodsN,methodsOff,classesN,classesOff;
        final Map<Integer,String> cache=new HashMap<>();
        final Map<Integer,String> targets=new HashMap<>();
        Dex(String n,byte[] data){
            name=n;b=data;
            stringsN=i32(0x38);stringsOff=i32(0x3c);
            typesN=i32(0x40);typesOff=i32(0x44);
            methodsN=i32(0x58);methodsOff=i32(0x5c);
            classesN=i32(0x60);classesOff=i32(0x64);
        }

        int[] scan(int remaining){
            if(b.length<0x70||!range(stringsOff,(long)stringsN*4)||!range(methodsOff,(long)methodsN*8)||!range(classesOff,(long)classesN*32))return new int[]{0,0};
            for(int i=0;i<stringsN;i++){
                String s=str(i);
                if(isTargetString(s))targets.put(i,s);
            }
            int named=logInterestingMethodNames(Math.min(20,remaining));
            if(targets.isEmpty()||remaining<=0)return new int[]{0,named};
            int refs=0;
            for(int i=0;i<classesN&&refs<remaining;i++){
                int p=classesOff+i*32;
                int dataOff=i32(p+24);
                if(dataOff>0&&dataOff<b.length)refs+=scanClassData(dataOff,remaining-refs);
            }
            return new int[]{refs,named};
        }

        int logInterestingMethodNames(int max){
            int out=0;
            for(int i=0;i<methodsN&&out<max;i++){
                String sig=method(i);
                if(isInterestingMethodName(sig)){
                    AppState.get().log.add("NCM MIXSCAN METHOD "+name+" "+shorten(sig,300));
                    out++;
                }
            }
            return out;
        }

        int scanClassData(int off,int remaining){
            try{
                int[] p={off};
                int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}
                int refs=scanMethodList(p,dm,remaining);
                if(refs<remaining)refs+=scanMethodList(p,vm,remaining-refs);
                return refs;
            }catch(Throwable ignored){return 0;}
        }

        int scanMethodList(int[] p,int count,int remaining){
            int idx=0,refs=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p); uleb(p); int codeOff=uleb(p);
                if(codeOff>0&&refs<remaining)refs+=scanCode(idx,codeOff);
            }
            return refs;
        }

        int scanCode(int methodIdx,int codeOff){
            if(!range(codeOff,16))return 0;
            int units=i32(codeOff+12);
            if(units<=0||!range(codeOff+16,(long)units*2))return 0;
            int start=codeOff+16;
            Set<String> hit=new LinkedHashSet<>();
            Set<Integer> nums=new LinkedHashSet<>();
            Set<String> calls=new LinkedHashSet<>();
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&0xff;
                if(op==0x1a&&u+1<units){
                    int si=u16(start+(u+1)*2);String t=targets.get(si);if(t!=null)hit.add(t);
                }else if(op==0x1b&&u+2<units){
                    int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);String t=targets.get(si);if(t!=null)hit.add(t);
                }else if(op==0x12){
                    int lit=(cu>>12)&0xf;if((lit&8)!=0)lit|=~0xf;nums.add(lit);
                }else if(op==0x13&&u+1<units){
                    nums.add((int)(short)u16(start+(u+1)*2));
                }else if(op==0x14&&u+2<units){
                    nums.add(u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16));
                }else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    if(u+1<units){String m=method(u16(start+(u+1)*2));if(isInterestingCall(m))calls.add(m);}
                }
            }
            if(hit.isEmpty())return 0;
            String owner=method(methodIdx);
            AppState.get().log.add("NCM MIXSCAN HIT "+name+" "+shorten(owner,240));
            AppState.get().log.add("NCM MIXSCAN strings: "+joinStrings(hit,360));
            if(!nums.isEmpty())AppState.get().log.add("NCM MIXSCAN nums: "+joinNums(nums,260));
            if(!calls.isEmpty())AppState.get().log.add("NCM MIXSCAN calls: "+joinStrings(calls,420));
            return 1;
        }

        String method(int idx){
            if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;
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
            int s=q[0],e=s,max=Math.min(b.length,s+900);while(e<max&&b[e]!=0)e++;
            if(e<=s||e>=max)return null;
            String v;try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}
            cache.put(idx,v);return v;
        }
        int uleb(int[] pp){
            int r=0,shift=0,p=pp[0];
            for(int i=0;i<5;i++){
                if(p>=b.length)throw new IllegalArgumentException();
                int v=b[p++]&255;r|=(v&127)<<shift;
                if((v&128)==0){pp[0]=p;return r;}shift+=7;
            }
            throw new IllegalArgumentException();
        }
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static boolean isTargetString(String s){
        if(s==null||s.length()<2||s.length()>220)return false;
        if(s.contains("人声")||s.contains("原唱")||s.contains("伴奏")||s.contains("随心唱"))return true;
        String x=s.toLowerCase(Locale.ROOT);
        boolean voice=x.contains("vocal")||x.contains("accompany")||x.contains("accompaniment")||x.contains("original")||x.contains("singmode")||x.contains("sing_mode")||x.contains("voice")||x.contains("separate")||x.contains("stem");
        boolean control=x.contains("volume")||x.contains("ratio")||x.contains("level")||x.contains("gain")||x.contains("mix")||x.contains("percent")||x.contains("balance")||x.contains("strength")||x.contains("progress");
        return voice&&control;
    }

    private static boolean isInterestingMethodName(String s){
        if(s==null)return false;
        String x=s.toLowerCase(Locale.ROOT);
        boolean voice=x.contains("vocal")||x.contains("accompany")||x.contains("original")||x.contains("singmode")||x.contains("sing_mode")||x.contains("stem");
        boolean control=x.contains("volume")||x.contains("ratio")||x.contains("level")||x.contains("gain")||x.contains("mix")||x.contains("percent")||x.contains("balance")||x.contains("strength");
        return voice&&control;
    }

    private static boolean isInterestingCall(String s){
        if(s==null)return false;
        String x=s.toLowerCase(Locale.ROOT);
        return x.contains("volume")||x.contains("ratio")||x.contains("gain")||x.contains("mix")||x.contains("level")||x.contains("vocal")||x.contains("accompany")||x.contains("original")||x.contains("sing")||x.contains("audio")||x.contains("player")||x.contains("effect")||x.contains("separate")||x.contains("stem");
    }

    private static byte[] readAll(InputStream in)throws Exception{
        try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){
            byte[] buf=new byte[32768];int n,total=0;
            while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();
        }
    }
    private static String safe(String s){return s==null?"?":s;}
    private static String shorten(String s,int n){if(s==null)return "";return s.length()<=n?s:s.substring(0,n)+"…";}
    private static String joinStrings(Iterable<String> xs,int max){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>=max)break;}return shorten(b.toString(),max);}
    private static String joinNums(Iterable<Integer> xs,int max){StringBuilder b=new StringBuilder();for(Integer v:xs){if(v!=null&&v>=-10000&&v<=100000){if(b.length()>0)b.append(',');b.append(v);}if(b.length()>=max)break;}return shorten(b.toString(),max);}
}