package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** CONTROL11: read-only reverse call trace around NetEase player-handler/SepTrack controls. */
public final class NeteaseXCall11Inspector {
    private static final String PKG="com.netease.cloudmusic";
    private static final String PLAYER_SEND="Lcom/netease/cloudmusic/service/IPlayService;->sendMessageByPlayerHandler(IIILjava/lang/Object;)V";
    private static final String SEP_SWITCH="Ljo0/f;->N(ZZ)V";
    private static final String SEP_VOLUME="Ljo0/f;->L(F)V";
    private static final String PLAY_VOL_W="Lxo0/w;->J(F)V";
    private static final String PLAY_VOL_L="Lxo0/l;->J(F)V";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final int LOG_CAP=260;

    private NeteaseXCall11Inspector(){}

    public static void scanAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);}catch(Throwable t){log("NCM XCALL11 error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-xcall11").start();
    }

    private static void scan(Context c)throws Exception{
        log("NCM XCALL11 start: reverse IPlayService/SepTrack call graph + invoke args");
        ApplicationInfo ai=c.getPackageManager().getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);

        Result r=new Result();
        // Pass 1: exact direct callers and precise call-site register snapshots.
        forEachDex(paths,(name,data)->new Dex(name,data,r,false).scan());
        log("NCM XCALL11 PASS1 directBus="+r.directBus.size()+" switchCallers="+r.switchCallers.size()+" volumeCallers="+r.volumeCallers.size());
        // Pass 2: one-hop reverse callers of every direct method found above.
        forEachDex(paths,(name,data)->new Dex(name,data,r,true).scan());
        log("NCM XCALL11 done: dex="+r.dex+" directBus="+r.directBus.size()+" switchCallers="+r.switchCallers.size()+" volumeCallers="+r.volumeCallers.size()+" up1="+r.up1+" entryLike="+r.entryLike);
    }

    private interface DexConsumer{void accept(String name,byte[] data)throws Exception;}
    private static void forEachDex(List<String> paths,DexConsumer consumer)throws Exception{
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    if(e.getSize()<=0||e.getSize()>90L*1024L*1024L)continue;
                    consumer.accept(e.getName(),readAll(z.getInputStream(e)));
                }
            }
        }
    }

    private static final class Result{
        final Set<String> directBus=new LinkedHashSet<>();
        final Set<String> switchCallers=new LinkedHashSet<>();
        final Set<String> volumeCallers=new LinkedHashSet<>();
        int logs,dex,up1,entryLike;
    }

    private static final class Dex{
        final String name;final byte[] b;final Result r;final boolean pass2;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
        Dex(String n,byte[] x,Result rr,boolean p2){
            name=n;b=x;r=rr;pass2=p2;
            stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);
            protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);
            methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);
        }

        void scan(){
            if(b.length<0x70||!range(classesOff,(long)classesN*32))return;
            if(!pass2)r.dex++;
            for(int ci=0;ci<classesN;ci++){
                int cp=classesOff+ci*32;if(!range(cp,32))break;
                String owner=type(i32(cp)),sup=type(i32(cp+8)),ifs=interfaces(i32(cp+12));
                int data=i32(cp+24);if(data<=0||data>=b.length)continue;
                scanClassData(owner,sup,ifs,data);
            }
        }

        void scanClassData(String owner,String sup,String ifs,int off){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}
                scanMethods(owner,sup,ifs,p,dm);scanMethods(owner,sup,ifs,p,vm);
            }catch(Throwable ignored){}
        }

        void scanMethods(String owner,String sup,String ifs,int[] p,int count){
            int idx=0;
            for(int i=0;i<count;i++){
                idx+=uleb(p);int access=uleb(p);int code=uleb(p);String sig=method(idx);
                if(code<=0)continue;
                Body z=body(code,(access&0x8)!=0);
                if(!pass2){
                    boolean bus=contains(z,PLAYER_SEND);
                    boolean sw=contains(z,SEP_SWITCH);
                    boolean vol=contains(z,SEP_VOLUME)||contains(z,PLAY_VOL_W)||contains(z,PLAY_VOL_L);
                    if(bus){r.directBus.add(sig);emit("NCM XCALL11 DIRECTBUS "+sig+" class="+classInfo(sup,ifs)+" strings="+join(z.strings,300));}
                    if(sw){r.switchCallers.add(sig);emit("NCM XCALL11 DIRECTSWITCH "+sig+" class="+classInfo(sup,ifs)+" strings="+join(z.strings,300));}
                    if(vol){r.volumeCallers.add(sig);emit("NCM XCALL11 DIRECTVOL "+sig+" class="+classInfo(sup,ifs)+" strings="+join(z.strings,300));}
                    if(SEP_SWITCH.equals(sig))emitSpecialSites(sig,z);
                    if(sig.equals("Lfm0/g;->sendMessageToService(IIILjava/lang/Object;)V"))emit("NCM XCALL11 BUSWRAP "+sig+" sites="+siteSummary(z,PLAYER_SEND));
                }else{
                    for(InvokeRec q:z.invokes){
                        String target=q.method;
                        if(!r.directBus.contains(target)&&!r.switchCallers.contains(target)&&!r.volumeCallers.contains(target))continue;
                        boolean entry=entryLike(owner,sup,ifs,sig,z.strings);
                        r.up1++;if(entry)r.entryLike++;
                        emit("NCM XCALL11 UP1"+(entry?" ENTRY":"")+" "+sig+" -> "+target+" class="+classInfo(sup,ifs)+" strings="+join(z.strings,360));
                        break;
                    }
                }
            }
        }

        void emitSpecialSites(String sig,Body z){
            for(CallSite s:z.sites){
                if(PLAYER_SEND.equals(s.method))emit("NCM XCALL11 BUSARGS "+sig+" @"+s.off+" regs="+regs(s.regs)+" defs="+defs(s.defs)+" pre="+shorten(s.pre,820));
                if("Lcom/netease/cloudmusic/module/player/meta/SepTrackSwitchData;-><init>(ZZ)V".equals(s.method))emit("NCM XCALL11 SWITCHOBJ "+sig+" @"+s.off+" regs="+regs(s.regs)+" defs="+defs(s.defs)+" pre="+shorten(s.pre,620));
            }
        }

        Body body(int codeOff,boolean isStatic){
            Body out=new Body();if(!range(codeOff,16))return out;
            int regN=u16(codeOff),insN=u16(codeOff+2),units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return out;
            String[] def=new String[Math.max(0,regN)];int p0=regN-insN;
            for(int i=0;i<insN&&p0+i>=0&&p0+i<def.length;i++)def[p0+i]=(isStatic?"p":"p")+i;
            int start=codeOff+16;String lastInvoke="";List<String> trace=new ArrayList<>();
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x12){int a=(cu>>8)&15,v=(cu>>12)&15;if((v&8)!=0)v|=~15;set(def,a,"const("+v+")");tr(trace,u,"v"+a+"="+v);}
                else if(op==0x13&&u+1<units){int a=(cu>>8)&255,v=(short)u16(start+(u+1)*2);set(def,a,"const("+v+")");tr(trace,u,"v"+a+"="+v);}
                else if(op==0x14&&u+2<units){int a=(cu>>8)&255,v=readI32Units(start,u+1);set(def,a,"const("+v+")");tr(trace,u,"v"+a+"="+v);}
                else if(op==0x15&&u+1<units){int a=(cu>>8)&255,v=((short)u16(start+(u+1)*2))<<16;set(def,a,"const("+v+")");tr(trace,u,"v"+a+"="+v);}
                else if(op==0x01||op==0x04||op==0x07){int a=(cu>>8)&15,bb=(cu>>12)&15;set(def,a,get(def,bb));tr(trace,u,"v"+a+"<-v"+bb);}
                else if((op==0x02||op==0x05||op==0x08)&&u+1<units){int a=(cu>>8)&255,bb=u16(start+(u+1)*2);set(def,a,get(def,bb));tr(trace,u,"v"+a+"<-v"+bb);}
                else if((op==0x03||op==0x06||op==0x09)&&u+2<units){int a=u16(start+(u+1)*2),bb=u16(start+(u+2)*2);set(def,a,get(def,bb));tr(trace,u,"v"+a+"<-v"+bb);}
                else if((op==0x0a||op==0x0b||op==0x0c)){int a=(cu>>8)&255;set(def,a,"result("+shorten(lastInvoke,150)+")");tr(trace,u,"v"+a+"=result");}
                else if(op==0x1a&&u+1<units){int a=(cu>>8)&255,sidx=u16(start+(u+1)*2);String s=str(sidx);set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);}
                else if(op==0x1b&&u+2<units){int a=(cu>>8)&255,sidx=readI32Units(start,u+1);String s=str(sidx);set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);}
                else if(op==0x22&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"new("+t+")");tr(trace,u,"v"+a+"=new "+t);}
                else if(op>=0x60&&op<=0x66&&u+1<units){int a=(cu>>8)&255;String f=field(u16(start+(u+1)*2));set(def,a,"sget("+f+")");tr(trace,u,"v"+a+"=sget "+shorten(f,130));}
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units){
                    String m=method(u16(start+(u+1)*2));int[] rr=invokeRegs(cu,op,start,u);
                    out.invokes.add(new InvokeRec(u,m,rr));
                    String[] snap=new String[rr.length];for(int k=0;k<rr.length;k++)snap[k]=get(def,rr[k]);
                    String pre=lastTrace(trace,18);out.sites.add(new CallSite(u,m,rr,snap,pre));
                    tr(trace,u,"invoke "+shorten(m,170)+" "+regs(rr));lastInvoke=m;
                }
            }
            return out;
        }

        int[] invokeRegs(int cu,int op,int start,int u){
            if(op>=0x74&&op<=0x78){int n=(cu>>8)&255,first=u16(start+(u+2)*2);int[] a=new int[n];for(int i=0;i<n;i++)a[i]=first+i;return a;}
            int n=(cu>>12)&15,g=(cu>>8)&15,x=u16(start+(u+2)*2);int[] all={x&15,(x>>4)&15,(x>>8)&15,(x>>12)&15,g};
            int[] a=new int[Math.min(n,5)];System.arraycopy(all,0,a,0,a.length);return a;
        }

        boolean contains(Body z,String target){for(InvokeRec q:z.invokes)if(target.equals(q.method))return true;return false;}
        String siteSummary(Body z,String target){for(CallSite s:z.sites)if(target.equals(s.method))return "@"+s.off+" regs="+regs(s.regs)+" defs="+defs(s.defs)+" pre="+shorten(s.pre,520);return "none";}
        String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));if(s.length()>300)break;}return s.toString();}
        String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return "field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1600);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
        int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
        int uleb(int[] pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        void emit(String s){if(r.logs>=LOG_CAP)return;r.logs++;log(s);}
    }

    private static boolean entryLike(String owner,String sup,String ifs,String sig,Set<String> strings){
        String x=(safe(owner)+" "+safe(sup)+" "+safe(ifs)+" "+safe(sig)).toLowerCase(Locale.ROOT);
        if(x.contains("service;")||x.contains("broadcastreceiver")||x.contains("mediasession")||x.contains("mediabrowser")||x.contains("binder;")||x.contains("onbind(")||x.contains("onreceive(")||x.contains("oncommand(")||x.contains("oncustomaction("))return true;
        for(String s:strings){String y=s.toLowerCase(Locale.ROOT);if(y.contains("action_")||y.contains("ucar")||y.contains("hicar")||y.contains("intent"))return true;}return false;
    }
    private static String classInfo(String sup,String ifs){return "super="+shorten(sup,120)+" ifaces="+shorten(ifs,180);}
    private static void set(String[] a,int i,String v){if(i>=0&&i<a.length)a[i]=v;}
    private static String get(String[] a,int i){return i>=0&&i<a.length&&a[i]!=null?a[i]:"?";}
    private static void tr(List<String> t,int off,String s){t.add("@"+off+" "+s);if(t.size()>40)t.remove(0);}
    private static String lastTrace(List<String> t,int n){StringBuilder b=new StringBuilder();int s=Math.max(0,t.size()-n);for(int i=s;i<t.size();i++){if(b.length()>0)b.append(" | ");b.append(t.get(i));}return b.toString();}
    private static String regs(int[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(',');b.append('v').append(a[i]);}return b.append(']').toString();}
    private static String defs(String[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(" | ");b.append(i).append('=').append(shorten(a[i],180));}return b.append(']').toString();}
    private static String join(Set<String> ss,int cap){StringBuilder b=new StringBuilder();for(String s:ss){if(b.length()>0)b.append(" | ");b.append(shorten(s,180));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static void log(String s){AppState.get().log.add(s);}
    private static String safe(String s){return s==null?"":s;}
    private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}

    private static final class Body{final Set<String> strings=new LinkedHashSet<>();final List<InvokeRec> invokes=new ArrayList<>();final List<CallSite> sites=new ArrayList<>();}
    private static final class InvokeRec{final int off;final String method;final int[] regs;InvokeRec(int o,String m,int[] r){off=o;method=m==null?"":m;regs=r;}}
    private static final class CallSite{final int off;final String method;final int[] regs;final String[] defs;final String pre;CallSite(int o,String m,int[] r,String[] d,String p){off=o;method=m;regs=r;defs=d;pre=p;}}
}
