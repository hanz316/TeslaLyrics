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

/** Focused DEX tracer for NetEase "随心唱" / separated vocal-volume control. */
public final class NeteaseSingTraceScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private static final int MAX_CALLERS=70;
    private NeteaseSingTraceScanner(){}

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM SINGTRACE error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-sing-trace").start();
    }

    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        AppState.get().log.add("NCM SINGTRACE start: setMusicVoiceVolume/audioSepTrackVoiceVolume/随心唱");
        int dex=0,targets=0,callers=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&callers<MAX_CALLERS){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize();
                    if(sz<=0||sz>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));
                    int[] r=d.scan(MAX_CALLERS-callers);
                    targets+=r[0];callers+=r[1];dex++;
                }
            }
        }
        AppState.get().log.add("NCM SINGTRACE done: dex="+dex+" targets="+targets+" callers="+callers);
    }

    private static final class Dex {
        final String name; final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff,classesN,classesOff;
        final Map<Integer,String> strCache=new HashMap<>();
        final Set<Integer> targetMethods=new LinkedHashSet<>();
        final Set<Integer> targetStrings=new LinkedHashSet<>();
        Dex(String n,byte[] data){
            name=n;b=data;
            stringsN=i32(0x38);stringsOff=i32(0x3c);
            typesN=i32(0x40);typesOff=i32(0x44);
            protosN=i32(0x48);protosOff=i32(0x4c);
            methodsN=i32(0x58);methodsOff=i32(0x5c);
            classesN=i32(0x60);classesOff=i32(0x64);
        }

        int[] scan(int remaining){
            if(b.length<0x70||!range(stringsOff,(long)stringsN*4)||!range(methodsOff,(long)methodsN*8)||!range(classesOff,(long)classesN*32))return new int[]{0,0};
            for(int i=0;i<stringsN;i++)if(isTargetString(str(i)))targetStrings.add(i);
            for(int i=0;i<methodsN;i++)if(isTargetMethod(i))targetMethods.add(i);
            if(targetMethods.isEmpty()&&targetStrings.isEmpty())return new int[]{0,0};

            for(Integer mi:targetMethods)AppState.get().log.add("NCM SINGTRACE TARGET "+name+" #"+mi+" "+method(mi));

            int callers=0;
            for(int i=0;i<classesN&&callers<remaining;i++){
                int p=classesOff+i*32;int dataOff=i32(p+24);
                if(dataOff>0&&dataOff<b.length)callers+=scanClassData(dataOff,remaining-callers);
            }
            return new int[]{targetMethods.size(),callers};
        }

        int scanClassData(int off,int remaining){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}
                int n=scanMethodList(p,dm,remaining);
                if(n<remaining)n+=scanMethodList(p,vm,remaining-n);
                return n;
            }catch(Throwable ignored){return 0;}
        }

        int scanMethodList(int[] p,int count,int remaining){
            int idx=0,out=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p);uleb(p);int codeOff=uleb(p);
                if(codeOff>0&&out<remaining)out+=scanCode(idx,codeOff);
            }
            return out;
        }

        int scanCode(int ownerIdx,int codeOff){
            if(!range(codeOff,16))return 0;
            int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return 0;
            int start=codeOff+16;
            Set<Integer> calledTargets=new LinkedHashSet<>();
            Set<String> strings=new LinkedHashSet<>();
            Set<Integer> nums=new LinkedHashSet<>();
            Set<String> calls=new LinkedHashSet<>();
            boolean hasTargetString=false;

            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&0xff;
                if(op==0x1a&&u+1<units){
                    int si=u16(start+(u+1)*2);if(targetStrings.contains(si))hasTargetString=true;addInterestingString(strings,str(si));
                }else if(op==0x1b&&u+2<units){
                    int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);if(targetStrings.contains(si))hasTargetString=true;addInterestingString(strings,str(si));
                }else if(op==0x12){
                    int lit=(cu>>12)&15;if((lit&8)!=0)lit|=~15;nums.add(lit);
                }else if(op==0x13&&u+1<units){
                    nums.add((int)(short)u16(start+(u+1)*2));
                }else if(op==0x14&&u+2<units){
                    nums.add(u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16));
                }else if((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78)){
                    if(u+1<units){
                        int mi=u16(start+(u+1)*2);
                        if(targetMethods.contains(mi))calledTargets.add(mi);
                        String ms=method(mi);if(isInterestingCall(ms))calls.add(ms);
                    }
                }
            }

            boolean isTarget=targetMethods.contains(ownerIdx);
            if(!isTarget&&calledTargets.isEmpty()&&!hasTargetString)return 0;
            AppState.get().log.add("NCM SINGTRACE "+(isTarget?"BODY":"CALLER")+" "+name+" "+method(ownerIdx));
            if(!calledTargets.isEmpty()){
                Set<String> ts=new LinkedHashSet<>();for(Integer x:calledTargets)ts.add(method(x));
                AppState.get().log.add("NCM SINGTRACE invokes: "+join(ts,520));
            }
            if(!strings.isEmpty())AppState.get().log.add("NCM SINGTRACE strings: "+join(strings,520));
            if(!nums.isEmpty())AppState.get().log.add("NCM SINGTRACE nums: "+joinNums(nums,320));
            if(!calls.isEmpty())AppState.get().log.add("NCM SINGTRACE calls: "+join(calls,600));
            return 1;
        }

        boolean isTargetMethod(int idx){
            String m=method(idx);String x=m.toLowerCase(Locale.ROOT);
            return x.contains("->setmusicvoicevolume(")
                    ||x.contains("module/player/utils/j2;->a1(")
                    ||x.contains("module/player/utils/j2;->d0(")
                    ||x.contains("lxo0/d;->q(")
                    ||x.contains("audioseptracksettingdemoactivity$b;->onprogresschanged(")
                    ||x.contains("lxp0/f$y$a;->invokesuspend(");
        }

        String method(int idx){
            if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;
            int p=methodsOff+idx*8;
            int classIdx=u16(p),protoIdx=u16(p+2),nameIdx=i32(p+4);
            return type(classIdx)+"->"+safe(str(nameIdx))+proto(protoIdx);
        }

        String proto(int idx){
            if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";
            int p=protosOff+idx*12;int ret=i32(p+4),params=i32(p+8);
            StringBuilder s=new StringBuilder("(");
            if(params>0&&range(params,4)){
                int n=i32(params);int q=params+4;
                for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));
            }
            return s.append(')').append(type(ret)).toString();
        }

        String type(int idx){
            if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";
            return safe(str(i32(typesOff+idx*4)));
        }
        String str(int idx){
            if(idx<0||idx>=stringsN)return null;if(strCache.containsKey(idx))return strCache.get(idx);
            int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;
            int[] q={off};try{uleb(q);}catch(Throwable t){return null;}
            int s=q[0],e=s,max=Math.min(b.length,s+1000);while(e<max&&b[e]!=0)e++;
            if(e<=s||e>=max)return null;String v;
            try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}
            strCache.put(idx,v);return v;
        }
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static boolean isTargetString(String s){
        if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);
        return s.contains("随心唱")||s.contains("人声音量")||x.contains("audioseptrackvoicevolume")||x.contains("septrackvoicevolume")||x.contains("setmusicvoicevolume")||x.contains("voice_balance_config_2430722");
    }
    private static void addInterestingString(Set<String> out,String s){
        if(s==null||s.length()<2||s.length()>240||out.size()>=24)return;String x=s.toLowerCase(Locale.ROOT);
        if(isTargetString(s)||x.contains("audio")||x.contains("voice")||x.contains("vocal")||x.contains("septrack")||x.contains("sing")||x.contains("volume")||x.contains("balance")||x.contains("progress")||x.contains("player")||s.contains("人声")||s.contains("伴奏")||s.contains("原唱"))out.add(s);
    }
    private static boolean isInterestingCall(String s){
        if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);
        return x.contains("musicvoice")||x.contains("septrack")||x.contains("voicevolume")||x.contains("audioai")||x.contains("progress")||x.contains("volume")||x.contains("player/utils/j2")||x.contains("player")||x.contains("datasource");
    }
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String safe(String s){return s==null?"?":s;}
    private static String join(Iterable<String> xs,int max){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>=max)break;}String r=b.toString();return r.length()<=max?r:r.substring(0,max)+"…";}
    private static String joinNums(Iterable<Integer> xs,int max){StringBuilder b=new StringBuilder();for(Integer v:xs){if(v!=null&&v>=-100000&&v<=100000){if(b.length()>0)b.append(',');b.append(v);}if(b.length()>=max)break;}String r=b.toString();return r.length()<=max?r:r.substring(0,max)+"…";}
}
