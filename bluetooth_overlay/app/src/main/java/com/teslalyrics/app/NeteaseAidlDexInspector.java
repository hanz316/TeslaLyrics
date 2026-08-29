package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** CONTROL8: static DEX inspector for the two confirmed NetEase Binder contracts. */
public final class NeteaseAidlDexInspector {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private NeteaseAidlDexInspector(){}

    public static void scanAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);}catch(Throwable t){AppState.get().log.add("NCM AIDL8 error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-aidl8").start();
    }

    private static void scan(Context c)throws Exception{
        AppState.get().log.add("NCM AIDL8 start: raw DEX Binder contract trace");
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        int dex=0,classes=0,methods=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long size=e.getSize(); if(size<=0||size>90L*1024L*1024L)continue;
                    Dex d=new Dex(e.getName(),readAll(z.getInputStream(e)));
                    int[] r=d.scan(); classes+=r[0]; methods+=r[1]; dex++;
                }
            }
        }
        AppState.get().log.add("NCM AIDL8 done: dex="+dex+" classes="+classes+" methods="+methods);
    }

    private static final class Dex{
        final String name; final byte[] b;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff,classesN,classesOff;
        Dex(String n,byte[] x){name=n;b=x;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}

        int[] scan(){
            int classes=0,methods=0;
            if(b.length<0x70||!range(classesOff,(long)classesN*32))return new int[]{0,0};
            for(int ci=0;ci<classesN;ci++){
                int cp=classesOff+ci*32;if(!range(cp,32))break;
                String cls=type(i32(cp));
                if(!targetClass(cls))continue;
                classes++;
                AppState.get().log.add("NCM AIDL8 CLASS "+name+" "+cls+" super="+type(i32(cp+8))+" ifaces="+interfaces(i32(cp+12)));
                int data=i32(cp+24); if(data>0&&data<b.length)methods+=scanClassData(data);
            }
            return new int[]{classes,methods};
        }

        boolean targetClass(String cls){
            if(cls==null)return false;
            return cls.startsWith("Lxe2/a") || cls.startsWith("Lyj0/a");
        }

        int scanClassData(int off){
            try{
                int[] p={off}; int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);} int n=scanMethods(p,dm); n+=scanMethods(p,vm); return n;
            }catch(Throwable t){return 0;}
        }

        int scanMethods(int[] p,int count){
            int idx=0,out=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p); uleb(p); int code=uleb(p);
                String sig=method(idx); Body body=code>0?body(code):new Body();
                AppState.get().log.add("NCM AIDL8 METHOD "+name+" "+sig); out++;
                if(body.transact){
                    AppState.get().log.add("NCM AIDL8 TXMETHOD "+sig+" nums="+joinInts(body.nums)+" parcel="+join(body.parcelCalls));
                } else if(!body.parcelCalls.isEmpty()||!body.strings.isEmpty()){
                    AppState.get().log.add("NCM AIDL8 BODY "+sig+" nums="+joinInts(body.nums)+" strings="+join(body.strings)+" calls="+join(body.parcelCalls));
                }
            }
            return out;
        }

        Body body(int codeOff){
            Body r=new Body(); if(!range(codeOff,16))return r; int units=i32(codeOff+12); if(units<=0||!range(codeOff+16,(long)units*2))return r; int start=codeOff+16;
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x12){int v=(cu>>12)&15;if((v&8)!=0)v|=~15;r.nums.add(v);} 
                else if(op==0x13&&u+1<units){r.nums.add((int)(short)u16(start+(u+1)*2));}
                else if(op==0x14&&u+2<units){r.nums.add(u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16));}
                else if(op==0x1a&&u+1<units){String s=str(u16(start+(u+1)*2)); if(interestingString(s))r.strings.add(s);} 
                else if(op==0x1b&&u+2<units){int si=u16(start+(u+1)*2)|(u16(start+(u+2)*2)<<16);String s=str(si);if(interestingString(s))r.strings.add(s);} 
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+1<units){
                    String m=method(u16(start+(u+1)*2));
                    if(m.contains("Landroid/os/IBinder;->transact("))r.transact=true;
                    if(m.contains("Landroid/os/Parcel;->")||m.contains("Landroid/os/IBinder;->transact(")||m.contains("->asBinder()"))r.parcelCalls.add(m);
                }
            }
            return r;
        }

        boolean interestingString(String s){
            if(s==null)return false;
            return s.contains("ICMApi")||s.contains("UCarMediaSessionController")||s.contains("CMAPI")||s.contains("ucar")||s.contains("play")||s.contains("pause")||s.contains("seek")||s.contains("voice")||s.contains("SepTrack");
        }

        String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));}return s.toString();}
        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1400);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
    }

    private static final class Body{boolean transact=false;final Set<Integer> nums=new LinkedHashSet<>();final Set<String> strings=new LinkedHashSet<>();final Set<String> parcelCalls=new LinkedHashSet<>();}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static String safe(String s){return s==null?"":s;}
    private static String join(Set<String> xs){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>550)break;}return b.toString();}
    private static String joinInts(Set<Integer> xs){StringBuilder b=new StringBuilder();for(Integer v:xs){if(b.length()>0)b.append(',');b.append(v);if(b.length()>180)break;}return b.toString();}
}
