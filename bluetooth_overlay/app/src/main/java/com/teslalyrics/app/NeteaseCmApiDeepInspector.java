package com.teslalyrics.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * CONTROL9: precise static mapping for NetEase CMAPI/UCar Binder contracts.
 *
 * This is read-only. It does not send Binder transactions.
 * It maps onTransact switch cases, logs Parcel read order, inspects CMAPI request/callback
 * classes, catalogs CMAPI handlers/getCommand strings, and checks whether any CMAPI handler
 * directly bridges into SepTrack / setMusicVoiceVolume.
 */
public final class NeteaseCmApiDeepInspector {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final int LOG_CAP=420;
    private NeteaseCmApiDeepInspector(){}

    public static void scanAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);}catch(Throwable t){log("NCM CMAPI9 error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-cmapi9").start();
    }

    private static void scan(Context c)throws Exception{
        log("NCM CMAPI9 start: transaction map + handler catalog + SepTrack bridge check");
        PackageManager pm=c.getPackageManager();
        ApplicationInfo ai=pm.getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        Counter ctr=new Counter();
        int dex=0;
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()&&ctr.logs<LOG_CAP){
                    ZipEntry e=en.nextElement();
                    if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                    long size=e.getSize();if(size<=0||size>90L*1024L*1024L)continue;
                    new Dex(e.getName(),readAll(z.getInputStream(e)),ctr).scan();
                    dex++;
                }
            }
        }
        log("NCM CMAPI9 done: dex="+dex+" classes="+ctr.classes+" methods="+ctr.methods+" handlers="+ctr.handlers+" maps="+ctr.maps+" sepBridges="+ctr.sepBridges);
    }

    private static final class Counter{
        int logs=0,classes=0,methods=0,handlers=0,maps=0,sepBridges=0;
    }

    private static final class Dex{
        final String name; final byte[] b; final Counter ctr;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
        Dex(String n,byte[] x,Counter c){
            name=n;b=x;ctr=c;
            stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);
            protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);
            methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);
        }

        void scan(){
            if(b.length<0x70||!range(classesOff,(long)classesN*32))return;
            for(int ci=0;ci<classesN&&ctr.logs<LOG_CAP;ci++){
                int cp=classesOff+ci*32;if(!range(cp,32))break;
                String cls=type(i32(cp)),sup=type(i32(cp+8)),ifs=interfaces(i32(cp+12));
                if(!focusClass(cls,sup,ifs))continue;
                ctr.classes++;
                emit("NCM CMAPI9 CLASS "+name+" "+shorten(cls,250)+" super="+shorten(sup,170)+" ifaces="+shorten(ifs,260));
                int data=i32(cp+24);if(data>0&&data<b.length)scanClassData(cls,data);
            }
        }

        boolean focusClass(String cls,String sup,String ifs){
            if(cls==null)return false;
            String x=cls.toLowerCase(Locale.ROOT);
            if(x.startsWith("lxe2/"))return true;
            if(x.startsWith("lyj0/a"))return true;
            if(x.startsWith("lcom/netease/cloudmusic/third/api/"))return true;
            if(x.contains("septrackcontroller")||x.equals("ljo0/f;")||x.equals("lxo0/w;")||x.equals("lxo0/l;")||x.equals("lcom/netease/cloudmusic/module/player/utils/j2;"))return true;
            return "Lxe2/a$a;".equals(sup)||(ifs!=null&&ifs.contains("Lxe2/a;"));
        }

        void scanClassData(String owner,int off){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                int fidx=0;
                for(int i=0;i<sf;i++){fidx+=uleb(p);uleb(p);maybeField(owner,fidx);}
                fidx=0;
                for(int i=0;i<inf;i++){fidx+=uleb(p);uleb(p);maybeField(owner,fidx);}
                scanMethods(owner,p,dm);scanMethods(owner,p,vm);
            }catch(Throwable ignored){}
        }

        void maybeField(String owner,int idx){
            if(ctr.logs>=LOG_CAP)return;
            if(owner.startsWith("Lxe2/")||owner.contains("CMApiService"))emit("NCM CMAPI9 FIELD "+field(idx));
        }

        void scanMethods(String owner,int[] p,int count){
            int idx=0;
            for(int i=0;i<count&&ctr.logs<LOG_CAP;i++){
                idx+=uleb(p);uleb(p);int code=uleb(p);
                String sig=method(idx);Body z=code>0?body(code):new Body();ctr.methods++;
                boolean xe=owner.startsWith("Lxe2/")||owner.startsWith("Lyj0/a");
                boolean handler=owner.startsWith("Lcom/netease/cloudmusic/third/api/cmapihandle/");
                boolean service=owner.contains("Lcom/netease/cloudmusic/third/api/CMApiService");
                boolean bridgeOwner=owner.contains("SepTrackController")||owner.equals("Ljo0/f;")||owner.equals("Lxo0/w;")||owner.equals("Lxo0/l;")||owner.equals("Lcom/netease/cloudmusic/module/player/utils/j2;");
                boolean commandMethod=sig.contains("->getCommand(");
                boolean relevantBody=hasRelevantString(z.strings)||hasRelevantCall(z.invokes);
                if(xe||commandMethod||(service&&relevantBody)||(handler&&relevantBody)||(bridgeOwner&&relevantBody)){
                    emit("NCM CMAPI9 METHOD "+name+" "+shorten(sig,380));
                    if(!z.strings.isEmpty())emit("NCM CMAPI9 strings: "+join(z.strings,650));
                    if(!z.invokes.isEmpty())emit("NCM CMAPI9 calls: "+joinInvokes(z.invokes,760));
                    if(!z.nums.isEmpty())emit("NCM CMAPI9 nums: "+joinInts(z.nums,220));
                }
                if(commandMethod){ctr.handlers++;emit("NCM CMAPI9 HANDLER "+shorten(owner,300)+" commandStrings="+join(z.strings,520));}
                if(owner.startsWith("Lcom/netease/cloudmusic/third/api/")&&hasSepBridge(z)){
                    ctr.sepBridges++;emit("NCM CMAPI9 SEPBRIDGE "+shorten(sig,380)+" strings="+join(z.strings,420)+" calls="+joinInvokes(z.invokes,620));
                }
                if(sig.contains("->onTransact(")&&!z.switches.isEmpty())emitMaps(sig,z);
            }
        }

        boolean hasSepBridge(Body z){
            for(String s:z.strings){String x=s.toLowerCase(Locale.ROOT);if(s.contains("随心唱")||x.contains("septrack")||x.contains("musicvoice")||x.contains("voice_balance"))return true;}
            for(InvokeRec r:z.invokes){String x=r.method.toLowerCase(Locale.ROOT);if(x.contains("setmusicvoicevolume")||x.contains("septrack")||x.contains("j2;->a1(f")||x.contains("jo0/f;->l(f"))return true;}
            return false;
        }

        void emitMaps(String sig,Body z){
            List<SwitchCase> cs=new ArrayList<>(z.switches);
            Collections.sort(cs,Comparator.comparingInt(a->a.target));
            for(int i=0;i<cs.size()&&ctr.logs<LOG_CAP;i++){
                SwitchCase sc=cs.get(i);if(sc.key<0||sc.key>64)continue;
                int end=(i+1<cs.size()?cs.get(i+1).target:Integer.MAX_VALUE);
                List<String> calls=new ArrayList<>(),parcel=new ArrayList<>();
                for(InvokeRec r:z.invokes){
                    if(r.off<sc.target||r.off>=end)continue;
                    if(r.method.startsWith("Landroid/os/Parcel;->"))parcel.add("@"+r.off+" "+shortMethod(r.method));
                    else if(!r.method.startsWith("Landroid/os/"))calls.add("@"+r.off+" "+shorten(r.method,220));
                }
                ctr.maps++;
                emit("NCM CMAPI9 MAP "+shorten(sig,230)+" code="+sc.key+" target=@"+sc.target+" calls="+joinList(calls,520)+" parcel="+joinList(parcel,620));
            }
        }

        Body body(int codeOff){
            Body r=new Body();if(!range(codeOff,16))return r;int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return r;int start=codeOff+16;
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x12){int v=(cu>>12)&15;if((v&8)!=0)v|=~15;r.nums.add(v);}
                else if(op==0x13&&u+1<units){r.nums.add((int)(short)u16(start+(u+1)*2));}
                else if(op==0x14&&u+2<units){r.nums.add(readI32Units(start,u+1));}
                else if(op==0x1a&&u+1<units){addString(r,u16(start+(u+1)*2));}
                else if(op==0x1b&&u+2<units){addString(r,readI32Units(start,u+1));}
                else if(op==0x2b&&u+2<units){parsePackedSwitch(r,start,u,readI32Units(start,u+1));}
                else if(op==0x2c&&u+2<units){parseSparseSwitch(r,start,u,readI32Units(start,u+1));}
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+1<units){
                    String m=method(u16(start+(u+1)*2));r.invokes.add(new InvokeRec(u,m));
                }
            }
            return r;
        }

        void parsePackedSwitch(Body r,int start,int switchU,int rel){
            int p=switchU+rel;if(p<0||!range(start+p*2,8)||u16(start+p*2)!=0x0100)return;
            int n=u16(start+(p+1)*2);if(n<0||n>128)return;int first=readI32Units(start,p+2);int q=p+4;
            for(int i=0;i<n&&range(start+(q+i*2)*2,4);i++){int target=switchU+readI32Units(start,q+i*2);r.switches.add(new SwitchCase(first+i,target));}
        }
        void parseSparseSwitch(Body r,int start,int switchU,int rel){
            int p=switchU+rel;if(p<0||!range(start+p*2,4)||u16(start+p*2)!=0x0200)return;
            int n=u16(start+(p+1)*2);if(n<0||n>128)return;int keys=p+2,targets=keys+n*2;
            for(int i=0;i<n;i++){if(!range(start+(keys+i*2)*2,4)||!range(start+(targets+i*2)*2,4))break;int key=readI32Units(start,keys+i*2);int target=switchU+readI32Units(start,targets+i*2);r.switches.add(new SwitchCase(key,target));}
        }

        void addString(Body r,int idx){String s=str(idx);if(s!=null&&!s.isEmpty()&&s.length()<700)r.strings.add(s);}

        boolean hasRelevantString(Set<String> ss){
            for(String s:ss){String x=s.toLowerCase(Locale.ROOT);if(s.contains("CMAPI")||s.contains("随心唱")||s.contains("人声音量")||x.contains("septrack")||x.contains("musicvoice")||x.contains("voice_balance")||x.equals("play")||x.equals("pause")||x.equals("seek")||x.contains("command"))return true;}return false;
        }
        boolean hasRelevantCall(List<InvokeRec> rs){
            for(InvokeRec r:rs){String x=r.method.toLowerCase(Locale.ROOT);if(x.contains("lxe2/")||x.contains("cmapi")||x.contains("setmusicvoicevolume")||x.contains("septrack")||x.contains("dispatchplaycommand")||x.contains("jsonobject")||x.contains("bundle"))return true;}return false;
        }

        String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));if(s.length()>380)break;}return s.toString();}
        String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return "field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
        String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
        String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
        String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
        String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+1800);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
        int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
        int uleb(int[] pp){int r=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;r|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return r;}sh+=7;}throw new IllegalArgumentException();}
        int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
        int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
        boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}

        void emit(String s){if(ctr.logs>=LOG_CAP)return;ctr.logs++;log(s);}
    }

    private static final class Body{final Set<Integer> nums=new LinkedHashSet<>();final Set<String> strings=new LinkedHashSet<>();final List<InvokeRec> invokes=new ArrayList<>();final List<SwitchCase> switches=new ArrayList<>();}
    private static final class InvokeRec{final int off;final String method;InvokeRec(int o,String m){off=o;method=m==null?"":m;}}
    private static final class SwitchCase{final int key,target;SwitchCase(int k,int t){key=k;target=t;}}

    private static String shortMethod(String m){if(m==null)return "";int a=m.indexOf("->");return a>=0?m.substring(a+2):m;}
    private static boolean hasSepWord(String s){if(s==null)return false;String x=s.toLowerCase(Locale.ROOT);return s.contains("随心唱")||s.contains("人声音量")||x.contains("septrack")||x.contains("musicvoice")||x.contains("voice_balance");}
    private static String join(Set<String> xs,int cap){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(shorten(s,240));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinInvokes(List<InvokeRec> xs,int cap){StringBuilder b=new StringBuilder();for(InvokeRec r:xs){String m=r.method;if(!(m.contains("xe2/")||m.contains("CMApi")||m.contains("cmapi")||m.contains("SepTrack")||m.contains("setMusicVoiceVolume")||m.contains("j2;->A1")||m.contains("jo0/f;->L(")||m.contains("Bundle;")||m.contains("JSONObject;")||m.contains("Parcel;")||m.contains("IBinder;")))continue;if(b.length()>0)b.append(" | ");b.append('@').append(r.off).append(' ').append(shorten(m,260));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinInts(Set<Integer> xs,int cap){StringBuilder b=new StringBuilder();for(Integer v:xs){if(b.length()>0)b.append(',');b.append(v);if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinList(List<String> xs,int cap){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static void log(String s){AppState.get().log.add(s);}
    private static String safe(String s){return s==null?"":s;}
    private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
}
