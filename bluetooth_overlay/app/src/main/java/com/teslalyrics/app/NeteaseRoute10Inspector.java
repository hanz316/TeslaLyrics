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

/** CONTROL10: narrow read-only DEX trace for CMAPI contract + SepTrack message route. */
public final class NeteaseRoute10Inspector {
    private static final String PKG="com.netease.cloudmusic";
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private static final int LOG_CAP=260;
    private NeteaseRoute10Inspector(){}

    public static void scanAsync(Context context){
        if(context==null||!RUNNING.compareAndSet(false,true))return;
        Context app=context.getApplicationContext();
        new Thread(()->{
            try{scan(app);}catch(Throwable t){log("NCM ROUTE10 error: "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally{RUNNING.set(false);}
        },"ncm-route10").start();
    }

    private static void scan(Context c)throws Exception{
        log("NCM ROUTE10 start: CMAPI-first + SepTrack message bus trace");
        ApplicationInfo ai=c.getPackageManager().getApplicationInfo(PKG,0);
        List<String> paths=new ArrayList<>();
        if(ai.sourceDir!=null)paths.add(ai.sourceDir);
        if(ai.splitSourceDirs!=null)for(String p:ai.splitSourceDirs)if(p!=null)paths.add(p);
        Counter ctr=new Counter();
        for(String apk:paths){
            try(ZipFile z=new ZipFile(apk)){
                List<ZipEntry> entries=new ArrayList<>();
                java.util.Enumeration<? extends ZipEntry> en=z.entries();
                while(en.hasMoreElements()){
                    ZipEntry e=en.nextElement();
                    if(e.getName().matches("classes(\\d*)\\.dex")&&e.getSize()>0&&e.getSize()<=90L*1024L*1024L)entries.add(e);
                }
                Collections.sort(entries,Comparator.comparingInt(NeteaseRoute10Inspector::priority));
                for(ZipEntry e:entries){
                    if(ctr.logs>=LOG_CAP)break;
                    new Dex(e.getName(),readAll(z.getInputStream(e)),ctr).scan();
                    ctr.dex++;
                }
            }
        }
        log("NCM ROUTE10 done: dex="+ctr.dex+" maps="+ctr.maps+" handlers="+ctr.handlers+" cmapiMethods="+ctr.cmapiMethods+" sepCallers="+ctr.sepCallers+" busMethods="+ctr.busMethods);
    }

    private static int priority(ZipEntry e){
        String n=e.getName();
        if("classes3.dex".equals(n))return 0;
        if("classes20.dex".equals(n))return 1;
        if("classes21.dex".equals(n))return 2;
        return 10;
    }

    private static final class Counter{int logs,dex,maps,handlers,cmapiMethods,sepCallers,busMethods;}

    private static final class Dex{
        final String name;final byte[] b;final Counter ctr;
        final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,methodsN,methodsOff,classesN,classesOff;
        Dex(String n,byte[] x,Counter c){name=n;b=x;ctr=c;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}

        void scan(){
            if(b.length<0x70||!range(classesOff,(long)classesN*32))return;
            for(int ci=0;ci<classesN&&ctr.logs<LOG_CAP;ci++){
                int cp=classesOff+ci*32;if(!range(cp,32))break;
                String owner=type(i32(cp));int data=i32(cp+24);if(data<=0||data>=b.length)continue;
                boolean focused=isFocusedOwner(owner);
                scanClassData(owner,data,focused);
            }
        }

        boolean isFocusedOwner(String o){
            if(o==null)return false;
            return o.startsWith("Lxe2/")||o.startsWith("Lue2/")||o.startsWith("Lcom/netease/cloudmusic/third/api/")||o.equals("Ljo0/f;")||o.equals("Lfm0/g;");
        }

        void scanClassData(String owner,int off,boolean focused){
            try{
                int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);
                for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}scanMethods(owner,p,dm,focused);scanMethods(owner,p,vm,focused);
            }catch(Throwable ignored){}
        }

        void scanMethods(String owner,int[] p,int count,boolean focused){
            int idx=0;
            for(int i=0;i<count&&ctr.logs<LOG_CAP;i++){
                idx+=uleb(p);uleb(p);int code=uleb(p);String sig=method(idx);
                Body z=code>0?body(code):new Body();
                boolean callerN=containsInvoke(z,"Ljo0/f;->N(ZZ)V");
                if(callerN){
                    ctr.sepCallers++;
                    emit("NCM ROUTE10 SEPCALLER "+shorten(sig,360)+" strings="+join(z.strings,420)+" calls="+joinInvokes(z.invokes,620)+" nums="+joinInts(z.nums,160));
                }
                if(!focused)continue;

                if(sig.contains("->onTransact(")&&!z.switches.isEmpty())emitMaps(sig,z);

                if(sig.contains("->getCommand(")){
                    ctr.handlers++;
                    emit("NCM ROUTE10 HANDLER "+shorten(owner,300)+" commandStrings="+join(z.strings,520));
                }

                if(owner.equals("Ljo0/f;")&&sig.contains("->N(ZZ)V")){
                    emit("NCM ROUTE10 SEPMSG "+shorten(sig,340)+" strings="+join(z.strings,360)+" calls="+joinInvokesAll(z.invokes,720)+" nums="+joinInts(z.nums,180));
                }

                if(owner.equals("Lfm0/g;")&&(sig.toLowerCase(Locale.ROOT).contains("sendmessagetoservice")||hasString(z,"sendMessageToService"))){
                    ctr.busMethods++;
                    emit("NCM ROUTE10 BUS "+shorten(sig,360)+" strings="+join(z.strings,360)+" calls="+joinInvokesAll(z.invokes,720)+" nums="+joinInts(z.nums,180));
                }

                if(owner.startsWith("Lxe2/")||owner.startsWith("Lue2/")||owner.startsWith("Lcom/netease/cloudmusic/third/api/")){
                    boolean important=sig.contains("CMApiService")||sig.contains("->J0(")||sig.contains("->p1(")||sig.contains("->t0(")||sig.contains("->z3(")||sig.contains("dispatchPlayCommand")||sig.contains("getCommand")||hasRelevant(z);
                    if(important){ctr.cmapiMethods++;emit("NCM ROUTE10 CMAPI "+shorten(sig,380)+" strings="+join(z.strings,420)+" calls="+joinInvokes(z.invokes,650)+" nums="+joinInts(z.nums,150));}
                }
            }
        }

        boolean hasRelevant(Body z){
            for(String s:z.strings){String x=s.toLowerCase(Locale.ROOT);if(x.contains("cmapi")||x.contains("command")||x.contains("play")||x.contains("pause")||x.contains("seek")||x.contains("septrack")||x.contains("musicvoice"))return true;}
            for(InvokeRec r:z.invokes){String x=r.method.toLowerCase(Locale.ROOT);if(x.contains("cmapihandle")||x.contains("dispatchplaycommand")||x.contains("septrack")||x.contains("setmusicvoicevolume"))return true;}
            return false;
        }

        void emitMaps(String sig,Body z){
            List<SwitchCase> cs=new ArrayList<>(z.switches);Collections.sort(cs,Comparator.comparingInt(a->a.target));
            for(int i=0;i<cs.size()&&ctr.logs<LOG_CAP;i++){
                SwitchCase sc=cs.get(i);if(sc.key<1||sc.key>32)continue;int end=i+1<cs.size()?cs.get(i+1).target:Integer.MAX_VALUE;
                List<String> calls=new ArrayList<>(),parcel=new ArrayList<>();
                for(InvokeRec r:z.invokes){if(r.off<sc.target||r.off>=end)continue;if(r.method.startsWith("Landroid/os/Parcel;->"))parcel.add("@"+r.off+" "+shortMethod(r.method));else if(!r.method.startsWith("Landroid/os/"))calls.add("@"+r.off+" "+shorten(r.method,220));}
                ctr.maps++;emit("NCM ROUTE10 MAP "+shorten(sig,230)+" code="+sc.key+" calls="+joinList(calls,540)+" parcel="+joinList(parcel,620));
            }
        }

        Body body(int codeOff){
            Body r=new Body();if(!range(codeOff,16))return r;int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return r;int start=codeOff+16;
            for(int u=0;u<units;u++){
                int cu=u16(start+u*2),op=cu&255;
                if(op==0x12){int v=(cu>>12)&15;if((v&8)!=0)v|=~15;r.nums.add(v);}else if(op==0x13&&u+1<units)r.nums.add((int)(short)u16(start+(u+1)*2));else if(op==0x14&&u+2<units)r.nums.add(readI32Units(start,u+1));
                else if(op==0x1a&&u+1<units)addString(r,u16(start+(u+1)*2));else if(op==0x1b&&u+2<units)addString(r,readI32Units(start,u+1));
                else if(op==0x2b&&u+2<units)parsePackedSwitch(r,start,u,readI32Units(start,u+1));else if(op==0x2c&&u+2<units)parseSparseSwitch(r,start,u,readI32Units(start,u+1));
                else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+1<units)r.invokes.add(new InvokeRec(u,method(u16(start+(u+1)*2))));
            }
            return r;
        }

        void parsePackedSwitch(Body r,int start,int switchU,int rel){int p=switchU+rel;if(p<0||!range(start+p*2,8)||u16(start+p*2)!=0x0100)return;int n=u16(start+(p+1)*2);if(n<0||n>128)return;int first=readI32Units(start,p+2),q=p+4;for(int i=0;i<n&&range(start+(q+i*2)*2,4);i++)r.switches.add(new SwitchCase(first+i,switchU+readI32Units(start,q+i*2)));}
        void parseSparseSwitch(Body r,int start,int switchU,int rel){int p=switchU+rel;if(p<0||!range(start+p*2,4)||u16(start+p*2)!=0x0200)return;int n=u16(start+(p+1)*2);if(n<0||n>128)return;int keys=p+2,targets=keys+n*2;for(int i=0;i<n;i++){if(!range(start+(keys+i*2)*2,4)||!range(start+(targets+i*2)*2,4))break;r.switches.add(new SwitchCase(readI32Units(start,keys+i*2),switchU+readI32Units(start,targets+i*2)));}}
        void addString(Body r,int idx){String s=str(idx);if(s!=null&&!s.isEmpty()&&s.length()<800)r.strings.add(s);}
        boolean containsInvoke(Body z,String m){for(InvokeRec r:z.invokes)if(m.equals(r.method))return true;return false;}
        boolean hasString(Body z,String needle){for(String s:z.strings)if(s.contains(needle))return true;return false;}

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

    private static String join(Set<String> xs,int cap){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(shorten(s,220));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinInvokes(List<InvokeRec> xs,int cap){StringBuilder b=new StringBuilder();for(InvokeRec r:xs){String x=r.method.toLowerCase(Locale.ROOT);if(!(x.contains("xe2/")||x.contains("ue2/")||x.contains("cmapi")||x.contains("cmapihandle")||x.contains("septrack")||x.contains("setmusicvoicevolume")||x.contains("sendmessagetoservice")||x.contains("bundle")||x.contains("jsonobject")))continue;if(b.length()>0)b.append(" | ");b.append('@').append(r.off).append(' ').append(shorten(r.method,240));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinInvokesAll(List<InvokeRec> xs,int cap){StringBuilder b=new StringBuilder();for(InvokeRec r:xs){if(b.length()>0)b.append(" | ");b.append('@').append(r.off).append(' ').append(shorten(r.method,230));if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinInts(Set<Integer> xs,int cap){StringBuilder b=new StringBuilder();for(Integer v:xs){if(b.length()>0)b.append(',');b.append(v);if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String joinList(List<String> xs,int cap){StringBuilder b=new StringBuilder();for(String s:xs){if(b.length()>0)b.append(" | ");b.append(s);if(b.length()>cap)break;}return shorten(b.toString(),cap);}
    private static String shortMethod(String m){if(m==null)return "";int a=m.indexOf("->");return a>=0?m.substring(a+2):m;}
    private static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>90*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    private static void log(String s){AppState.get().log.add(s);}
    private static String safe(String s){return s==null?"":s;}
    private static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
}
