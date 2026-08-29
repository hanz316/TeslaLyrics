package com.teslalyrics.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;

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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CARBIND6: safe follow-up to IPCENTRY5.
 *
 * It does two things only:
 * 1) Explicitly bind to a short allow-list of exported, unprotected NetEase services and
 *    read the returned Binder interface descriptor. It does NOT send any transact or command.
 * 2) Statically inspect those exact service classes/nested Binder classes for onBind,
 *    Stub/Proxy and media-control methods so the next probe can use a documented transaction.
 */
public final class NeteaseCarBinderProbe {
    private static final String PKG="com.netease.cloudmusic";
    private static final String[] SERVICES={
            "com.netease.cloudmusic.module.ucar.UCarService",
            "com.netease.cloudmusic.third.api.CMApiService",
            "com.netease.cloudmusic.module.hicar.HiCarHonorMediaOperateMgr",
            "com.netease.cloudmusic.hop.ContinuationService",
            "com.netease.cloudmusic.biz.watch.downloader.HuaweiSportService"
    };
    private static final AtomicBoolean STARTED=new AtomicBoolean(false);
    private static final List<ServiceConnection> CONNECTIONS=new CopyOnWriteArrayList<>();
    private NeteaseCarBinderProbe(){}

    public static void start(Context context){
        if(context==null||!STARTED.compareAndSet(false,true))return;
        final Context app=context.getApplicationContext();
        AppState.get().log.add("NCM CARBIND6 start: safe bind + exact service static trace");
        new Handler(Looper.getMainLooper()).post(()->startRuntimeBinds(app));
        new Thread(()->{
            try{staticScan(app);}catch(Throwable t){
                AppState.get().log.add("NCM CARBIND6 scan error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            }
        },"ncm-carbind6-static").start();
    }

    private static void startRuntimeBinds(Context app){
        Handler h=new Handler(Looper.getMainLooper());
        for(int i=0;i<SERVICES.length;i++){
            final String cls=SERVICES[i];
            h.postDelayed(()->bindOne(app,cls),i*900L);
        }
    }

    @SuppressWarnings("deprecation")
    private static void bindOne(Context app,String cls){
        try{
            ServiceInfo si=app.getPackageManager().getServiceInfo(new ComponentName(PKG,cls),0);
            AppState.get().log.add("NCM CARBIND6 TRY "+shortName(cls)+" exported="+si.exported+" perm="+safe(si.permission)+" process="+safe(si.processName));
            if(!si.exported){AppState.get().log.add("NCM CARBIND6 SKIP "+shortName(cls)+" not exported");return;}
            if(si.permission!=null&&!si.permission.isEmpty()){
                AppState.get().log.add("NCM CARBIND6 SKIP "+shortName(cls)+" protected="+si.permission);return;
            }
            final ServiceConnection[] holder=new ServiceConnection[1];
            ServiceConnection c=new ServiceConnection(){
                @Override public void onServiceConnected(ComponentName name,IBinder service){
                    String desc="";String local="null";String binderClass="null";boolean alive=false;
                    try{alive=service!=null&&service.isBinderAlive();}catch(Throwable ignored){}
                    try{binderClass=service==null?"null":service.getClass().getName();}catch(Throwable ignored){}
                    try{desc=service==null?"<null>":safe(service.getInterfaceDescriptor());}catch(Throwable t){desc="<"+t.getClass().getSimpleName()+">";}
                    try{
                        if(service!=null&&desc!=null&&!desc.isEmpty()&&!desc.startsWith("<")){
                            IInterface q=service.queryLocalInterface(desc);
                            local=q==null?"null":q.getClass().getName();
                        }
                    }catch(Throwable t){local="<"+t.getClass().getSimpleName()+">";}
                    AppState.get().log.add("NCM CARBIND6 CONNECTED "+shortName(cls)+" alive="+alive+" binderClass="+binderClass+" descriptor="+desc+" local="+local);
                    new Handler(Looper.getMainLooper()).postDelayed(()->safeUnbind(app,holder[0],cls),2500L);
                }
                @Override public void onServiceDisconnected(ComponentName name){AppState.get().log.add("NCM CARBIND6 DISCONNECTED "+shortName(cls));}
                @Override public void onBindingDied(ComponentName name){AppState.get().log.add("NCM CARBIND6 BINDING_DIED "+shortName(cls));safeUnbind(app,holder[0],cls);}
                @Override public void onNullBinding(ComponentName name){AppState.get().log.add("NCM CARBIND6 NULL_BINDING "+shortName(cls));safeUnbind(app,holder[0],cls);}
            };
            holder[0]=c;CONNECTIONS.add(c);
            Intent in=new Intent().setComponent(new ComponentName(PKG,cls));
            boolean ok=app.bindService(in,c,Context.BIND_AUTO_CREATE);
            AppState.get().log.add("NCM CARBIND6 bindService "+shortName(cls)+" returned="+ok);
            if(!ok){CONNECTIONS.remove(c);}
        }catch(Throwable t){
            AppState.get().log.add("NCM CARBIND6 FAIL "+shortName(cls)+" "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
        }
    }

    private static void safeUnbind(Context app,ServiceConnection c,String cls){
        if(c==null)return;
        try{app.unbindService(c);AppState.get().log.add("NCM CARBIND6 UNBOUND "+shortName(cls));}catch(Throwable ignored){}
        CONNECTIONS.remove(c);
    }

    private static void staticScan(Context c)throws Exception{
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        int dex=0,hits=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&hits<150){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long size=e.getSize();if(size<=0||size>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));
                    hits+=d.scan(150-hits);dex++;
                }
            }
        }
        AppState.get().log.add("NCM CARBIND6 done: dex="+dex+" staticHits="+hits);
    }

    private static final class Dex{
        final String name;final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff,classesN,classesOff;
        final Map<Integer,String> cache=new HashMap<>();
        Dex(String n,byte[] x){name=n;b=x;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}

        int scan(int remaining){
            if(b.length<0x70||remaining<=0||!range(classesOff,(long)classesN*32))return 0;
            int out=0;
            for(int ci=0;ci<classesN&&out<remaining;ci++){
                int cp=classesOff+ci*32;if(!range(cp,32))break;
                String cls=type(i32(cp));
                if(!ownerInteresting(cls))continue;
                String sup=type(i32(cp+8));
                AppState.get().log.add("NCM CARBIND6 CLASS "+name+" "+shorten(cls,260)+" super="+shorten(sup,180)+" ifaces="+shorten(interfaces(i32(cp+12)),260));out++;
                int data=i32(cp+24);
                if(data>0&&data<b.length&&out<remaining)out+=scanClassData(data,remaining-out);
            }
            return out;
        }

        int scanClassData(int off,int remaining){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}
                int out=scanMethods(p,dm,remaining);
                if(out<remaining)out+=scanMethods(p,vm,remaining-out);
                return out;
            }catch(Throwable t){return 0;}
        }

        int scanMethods(int[] p,int count,int remaining){
            int idx=0,out=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p);uleb(p);int code=uleb(p);
                if(out>=remaining)continue;
                String sig=method(idx);
                if(!methodInteresting(sig))continue;
                AppState.get().log.add("NCM CARBIND6 METHOD "+name+" "+shorten(sig,360));out++;
                if(code>0&&out<remaining){
                    Body z=body(code);
                    if(!z.strings.isEmpty()&&out<remaining){AppState.get().log.add("NCM CARBIND6 strings: "+join(z.strings,520));out++;}
                    if(!z.calls.isEmpty()&&out<remaining){AppState.get().log.add("NCM CARBIND6 calls: "+join(z.calls,620));out++;}
                    if(!z.nums.isEmpty()&&out<remaining){AppState.get().log.add("NCM CARBIND6 nums: "+joinNums(z.nums,220));out++;}
                }
            }
            return out;
        }

        Body body(int codeOff){
            Body r=new Body();if(!range(codeOff,16))return r;int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return r;int start=codeOff+16;
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x1a&&u+1<units){addString(r,u16(start+(u+1)*2));}
                else if(op==0x1b&&u+2<units){addString(r,u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16));}
                else if(op==0x12){int v=(cu>>12)&15;if((v&8)!=0)v|=~15;r.nums.add(v);}
                else if(op==0x13&&u+1<units){r.nums.add((int)(short)u16(start+(u+1)*2));}
                else if(op==0x14&&u+2<units){r.nums.add(u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16));}
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+1<units){String m=method(u16(start+(u+1)*2));if(callInteresting(m))r.calls.add(m);}
            }
            return r;
        }
        void addString(Body r,int idx){String s=str(idx);if(s!=null&&s.length()>0&&s.length()<500&&stringInteresting(s))r.strings.add(s);}

        String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));if(s.length()>350)break;}return s.toString();}
        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;if(cache.containsKey(idx))return cache.get(idx);int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1400);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;String v;try{v=new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}cache.put(idx,v);return v;}
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static final class Body{final Set<String> strings=new LinkedHashSet<>();final Set<String> calls=new LinkedHashSet<>();final Set<Integer> nums=new LinkedHashSet<>();}

    private static boolean ownerInteresting(String s){
        if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);
        return x.contains("module/ucar/ucarservice")||x.contains("third/api/cmapi")||x.contains("hicar/hicarhonormediaoperatemgr")||x.contains("hop/continuationservice")||x.contains("watch/downloader/huaweisportservice")
                ||((x.contains("ucar")||x.contains("cmapi")||x.contains("hicarhonor"))&&(x.contains("stub")||x.contains("proxy")||x.contains("binder")||x.contains("callback")||x.contains("service")));
    }

    private static boolean methodInteresting(String s){
        if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);
        return x.contains("->onbind(")||x.contains("->onstartcommand(")||x.contains("->ontransact(")||x.contains("->asinterface(")||x.contains("->asbinder(")||x.contains("getinterfacedescriptor")
                ||x.contains("play")||x.contains("pause")||x.contains("next")||x.contains("prev")||x.contains("seek")||x.contains("volume")||x.contains("voice")||x.contains("audio")||x.contains("command")||x.contains("action")||x.contains("media")||x.contains("track");
    }

    private static boolean callInteresting(String s){
        if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);
        return x.contains("binder")||x.contains("transact")||x.contains("asinterface")||x.contains("setmusicvoicevolume")||x.contains("setplay")||x.contains("sendmessage")||x.contains("mediacontroller")||x.contains("transportcontrols")||x.contains("play")||x.contains("pause")||x.contains("seek")||x.contains("volume")||x.contains("command");
    }

    private static boolean stringInteresting(String s){
        String x=s.toLowerCase(Locale.ROOT);
        return x.contains("action")||x.contains("interface")||x.contains("binder")||x.contains("ucar")||x.contains("cmapi")||x.contains("hicar")||x.contains("media")||x.contains("play")||x.contains("pause")||x.contains("seek")||x.contains("volume")||x.contains("voice")||x.contains("audio")||x.contains("music")||x.contains("command")||x.contains("netease")||x.contains("orpheus")||s.contains("随心唱")||s.contains("人声");
    }

    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String shortName(String s){int i=s.lastIndexOf('.');return i>=0?s.substring(i+1):s;}
    private static String safe(String s){return s==null?"":s;}
    private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
    private static String join(Iterable<String> xs,int max){StringBuilder b=new StringBuilder();for(String s:xs){if(s==null)continue;if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>=max)break;}return shorten(b.toString(),max);}
    private static String joinNums(Iterable<Integer> xs,int max){StringBuilder b=new StringBuilder();for(Integer v:xs){if(v==null||v<-1000000||v>1000000)continue;if(b.length()>0)b.append(',');b.append(v);if(b.length()>=max)break;}return shorten(b.toString(),max);}
}
