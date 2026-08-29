package com.teslalyrics.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;

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

/** Focused follow-up after SINGTRACE3/IPCTRACE4. */
public final class NeteaseExternalEntryScanner {
    private static final String PKG="com.netease.cloudmusic";
    private static final String PLAY_SERVICE="com.netease.cloudmusic.service.PlayService";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final AtomicBoolean DONE=new AtomicBoolean(false);
    private NeteaseExternalEntryScanner(){}

    public static void scanAsync(Context context){
        if(context==null||DONE.get()||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);DONE.set(true);}catch(Throwable t){AppState.get().log.add("NCM ENTRY5 error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-entry5").start();
    }

    @SuppressWarnings("deprecation")
    private static void scan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        AppState.get().log.add("NCM ENTRY5 start: PlayService/onBind/real Binder/MediaSession command");

        try{
            ServiceInfo s=pm.getServiceInfo(new ComponentName(PKG,PLAY_SERVICE),0);
            AppState.get().log.add("NCM ENTRY5 PlayService exported="+s.exported+" enabled="+s.enabled+" perm="+safe(s.permission)+" process="+safe(s.processName));
        }catch(Throwable t){AppState.get().log.add("NCM ENTRY5 PlayService lookup="+t.getClass().getSimpleName());}

        PackageInfo pi=pm.getPackageInfo(PKG,PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS);
        int ext=0;
        if(pi.services!=null)for(ServiceInfo s:pi.services){
            String x=(safe(s.name)+" "+safe(s.permission)).toLowerCase(Locale.ROOT);
            if(s.exported&&(x.contains("play")||x.contains("audio")||x.contains("media")||x.contains("music")||x.contains("car")||x.contains("command"))){
                AppState.get().log.add("NCM ENTRY5 EXTSVC name="+s.name+" perm="+safe(s.permission)+" process="+safe(s.processName));
                if(++ext>=24)break;
            }
        }
        int rx=0;
        if(pi.receivers!=null)for(ActivityInfo r:pi.receivers){
            String x=(safe(r.name)+" "+safe(r.permission)).toLowerCase(Locale.ROOT);
            if(r.exported&&(x.contains("play")||x.contains("audio")||x.contains("media")||x.contains("music")||x.contains("car")||x.contains("command"))){
                AppState.get().log.add("NCM ENTRY5 EXTRX name="+r.name+" perm="+safe(r.permission)+" process="+safe(r.processName));
                if(++rx>=24)break;
            }
        }

        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        int dex=0,hits=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&hits<90){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long sz=e.getSize();if(sz<=0||sz>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));
                    hits+=d.scan(90-hits);dex++;
                }
            }
        }
        AppState.get().log.add("NCM ENTRY5 done: dex="+dex+" hits="+hits+" exportedServices="+ext+" exportedReceivers="+rx);
    }

    private static final class Dex{
        final String name;final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff,classesN,classesOff;
        final Map<Integer,String> cache=new HashMap<>();
        final Set<Integer> interestingStrings=new LinkedHashSet<>();
        Dex(String n,byte[] x){name=n;b=x;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}

        int scan(int remaining){
            if(b.length<0x70||remaining<=0)return 0;
            int out=0;
            for(int i=0;i<stringsN;i++){String s=str(i);if(isInterestingString(s))interestingStrings.add(i);}

            // Identify real Binder classes related to PlayService/IPlayService.
            for(int ci=0;ci<classesN&&out<remaining;ci++){
                int p=classesOff+ci*32;if(!range(p,32))break;
                String cls=type(i32(p));String sup=type(i32(p+8));
                if(cls!=null&&(cls.contains("PlayService")||cls.contains("IPlayService"))){
                    if("Landroid/os/Binder;".equals(sup)||implementsType(i32(p+12),"Landroid/os/IInterface;")||implementsType(i32(p+12),"Landroid/os/IBinder;")){
                        AppState.get().log.add("NCM ENTRY5 BINDERCLASS "+name+" cls="+cls+" super="+sup+" ifaces="+interfaces(i32(p+12)));out++;
                    }
                }
            }

            // Methods and bodies around PlayService binding + MediaSession command + sep-track command strings.
            for(int ci=0;ci<classesN&&out<remaining;ci++){
                int p=classesOff+ci*32;if(!range(p,32))break;
                int data=i32(p+24);if(data>0&&data<b.length)out+=scanClassData(data,remaining-out);
            }
            return out;
        }

        int scanClassData(int off,int remaining){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}int out=scanMethods(p,dm,remaining);if(out<remaining)out+=scanMethods(p,vm,remaining-out);return out;
            }catch(Throwable t){return 0;}
        }

        int scanMethods(int[] p,int count,int remaining){
            int idx=0,out=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p);uleb(p);int code=uleb(p);
                if(out>=remaining)continue;
                String sig=method(idx);String lo=sig.toLowerCase(Locale.ROOT);
                boolean named=(sig.contains("Lcom/netease/cloudmusic/service/PlayService;")&&(lo.contains("onbind")||lo.contains("onstartcommand")||lo.contains("onhandleintent")||lo.contains("oncommand")))
                        ||(lo.contains("mediasession")&&(lo.contains("oncommand")||lo.contains("oncustomaction")||lo.contains("sendcommand")))
                        ||(lo.contains("media")&&lo.contains("callback")&&(lo.contains("oncommand")||lo.contains("oncustomaction")));
                Body body=code>0?body(code):new Body();
                boolean bodyInteresting=!body.strings.isEmpty();
                if(named||bodyInteresting){
                    AppState.get().log.add("NCM ENTRY5 METHOD "+name+" "+shorten(sig,300));out++;
                    if(!body.strings.isEmpty()&&out<remaining){AppState.get().log.add("NCM ENTRY5 strings: "+join(body.strings,420));out++;}
                    if(!body.calls.isEmpty()&&out<remaining){AppState.get().log.add("NCM ENTRY5 calls: "+join(body.calls,500));out++;}
                }
            }
            return out;
        }

        Body body(int codeOff){
            Body r=new Body();if(!range(codeOff,16))return r;int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return r;int start=codeOff+16;
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x1a&&u+1<units){int si=u16(start+(u+1)*2);if(interestingStrings.contains(si))r.strings.add(str(si));}
                else if(op==0x1b&&u+2<units){int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);if(interestingStrings.contains(si))r.strings.add(str(si));}
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+1<units){String m=method(u16(start+(u+1)*2));if(isInterestingCall(m))r.calls.add(m);}
            }
            return r;
        }

        boolean implementsType(int off,String target){if(off<=0||!range(off,4))return false;int n=i32(off),q=off+4;for(int i=0;i<n&&range(q+i*2,2);i++)if(target.equals(type(u16(q+i*2))))return true;return false;}
        String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));if(s.length()>300)break;}return s.toString();}

        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;if(cache.containsKey(idx))return cache.get(idx);int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1200);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;String v;try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}cache.put(idx,v);return v;}
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static final class Body{final Set<String> strings=new LinkedHashSet<>();final Set<String> calls=new LinkedHashSet<>();}
    private static boolean isInterestingString(String s){if(s==null||s.length()<3||s.length()>500)return false;String x=s.toLowerCase(Locale.ROOT);return s.contains("随心唱")||s.contains("人声")||x.contains("septrack")||x.contains("audioseptrack")||x.contains("musicvoice")||x.contains("voice_balance")||x.contains("karaoke")||(x.contains("command")&&(x.contains("audio")||x.contains("player")||x.contains("music")||x.contains("voice")));}
    private static boolean isInterestingCall(String s){if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);return x.contains("setmusicvoicevolume")||x.contains("septrack")||x.contains("audiosep")||x.contains("sendcommand")||x.contains("oncommand")||x.contains("oncustomaction")||x.contains("binder")||x.contains("getplayback")||x.contains("setplayresource")||x.contains("sendmessagebyplayerhandler")||x.contains("sendmessagetoclient");}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String join(Iterable<String> xs,int max){StringBuilder b=new StringBuilder();for(String s:xs){if(s==null)continue;if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>=max)break;}return shorten(b.toString(),max);}private static String safe(String s){return s==null?"":s;}private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
}
