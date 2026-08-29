package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

/** BRIDGE18 is static/read-only. It never binds, transacts, broadcasts, or sends media/SepTrack commands. */
public class DetectorBridge18Activity extends Activity {
    private static final String BUILD="BRIDGE18";
    private static final String NETEASE="com.netease.cloudmusic";
    private static final LogBook LOG=new LogBook(3500);
    private static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    private TextView output;
    private Button scan;

    @Override public void onCreate(android.os.Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);int p=dp(14);root.setPadding(p,p,p,p);
        TextView title=new TextView(this);title.setText("Tesla Lyrics Detector · "+BUILD+"\n只读：PlayController Binder 获取路线终检");title.setTextSize(18f);root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView hint=new TextView(this);hint.setText("不播放、不绑定、不发命令。只查 fj.a / yp0.b / m6$g / SubPlayController / SmartDeviceBrowerActivity 的真实服务目标和 exported 状态。");root.addView(hint,new LinearLayout.LayoutParams(-1,-2));
        scan=new Button(this);scan.setText("一键终检 Binder 获取路线");root.addView(scan,new LinearLayout.LayoutParams(-1,-2));
        Button export=new Button(this);export.setText("导出完整 TXT");root.addView(export,new LinearLayout.LayoutParams(-1,-2));
        ScrollView sv=new ScrollView(this);output=new TextView(this);output.setTextSize(10.5f);output.setTextIsSelectable(true);sv.addView(output);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));setContentView(root);
        LOG.add("BUILD "+BUILD);LOG.add("READONLY: no bindService, no Binder transact, no Broadcast send, no MediaSession command, no SepTrack write");refresh();
        scan.setOnClickListener(v->runScan());export.setOnClickListener(v->{String x=exportTxt();Toast.makeText(this,x==null?"导出失败":"已导出: "+x,Toast.LENGTH_LONG).show();});
    }

    private void runScan(){
        if(!RUNNING.compareAndSet(false,true)){Toast.makeText(this,"扫描正在运行",Toast.LENGTH_SHORT).show();return;}
        scan.setEnabled(false);LOG.add("B18 START");
        new Thread(()->{try{Inspector.run(this,LOG);}catch(Throwable t){LOG.add("B18 ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}finally{RUNNING.set(false);String x=exportTxt();LOG.add("B18 AUTO_EXPORT "+safe(x));runOnUiThread(()->{scan.setEnabled(true);refresh();});}},"netease-bridge18").start();
        output.post(new Runnable(){@Override public void run(){refresh();if(RUNNING.get())output.postDelayed(this,1000);}});
    }

    private String exportTxt(){
        String name="NetEase-"+BUILD+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: "+BUILD+"\nTime: "+new Date()+"\n\n"+LOG.text();
        try{ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(u==null)return null;try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)return null;os.write(body.getBytes(StandardCharsets.UTF_8));}return "Downloads/TeslaLyricsDetector/"+name;}catch(Throwable t){LOG.add("EXPORT ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));return null;}
    }
    private void refresh(){output.setText(LOG.text());}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    static String safe(String s){return s==null?"":s;}

    static final class LogBook{
        final int cap;final Deque<String> q=new ArrayDeque<>();LogBook(int c){cap=c;}
        synchronized void add(String s){q.addLast(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);while(q.size()>cap)q.removeFirst();}
        synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}
    }

    static final class Inspector{
        static final String FJ="Lcom/netease/cloudmusic/utils/fj;";
        static final String BASE="Lyp0/b;";
        static final String M6="Lcom/netease/cloudmusic/utils/m6;";
        static final String M6G="Lcom/netease/cloudmusic/utils/m6$g;";
        static final String SUB="Lcom/netease/cloudmusic/module/player/rpc/subprocess/SubPlayController;";
        static final String SUBC="Lcom/netease/cloudmusic/module/player/rpc/subprocess/SubPlayController$c;";
        static final String SMART="Lcom/netease/cloudmusic/activity/SmartDeviceBrowerActivity;";
        static final String SMARTB="Lcom/netease/cloudmusic/activity/SmartDeviceBrowerActivity$b;";
        static final String AIDL_STUB="Lcom/netease/cloudmusic/aidl/d$a;";
        static final String PS="Lcom/netease/cloudmusic/service/PlayService;";
        static final String MPS="Lcom/netease/cloudmusic/service/MainProcessPlayService;";
        static final String[] REFLECT={"com.netease.cloudmusic.utils.fj","yp0.b","com.netease.cloudmusic.utils.m6$g","com.netease.cloudmusic.module.player.rpc.subprocess.SubPlayController","com.netease.cloudmusic.module.player.rpc.subprocess.SubPlayController$c","com.netease.cloudmusic.activity.SmartDeviceBrowerActivity","com.netease.cloudmusic.activity.SmartDeviceBrowerActivity$b"};
        static LogBook log;

        static void run(Context c,LogBook l)throws Exception{
            log=l;PackageManager pm=c.getPackageManager();PackageInfo pi=pm.getPackageInfo(NETEASE,PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.MATCH_DISABLED_COMPONENTS);ApplicationInfo ai=pi.applicationInfo;
            log.add("PKG version="+pi.versionName+" code="+pi.getLongVersionCode()+" source="+(ai==null?"":safe(ai.sourceDir)));
            dumpComponents(pi);
            List<String> paths=new ArrayList<>();if(ai!=null&&ai.sourceDir!=null)paths.add(ai.sourceDir);if(ai!=null&&ai.splitSourceDirs!=null)Collections.addAll(paths,ai.splitSourceDirs);
            reflect(c,paths);
            Result r=new Result();for(String apk:paths)scanApk(apk,r);
            log.add("===== B18 FOCUS CLASS META =====");for(String s:r.meta)log.add(s);
            log.add("===== B18 FOCUS METHOD BODIES =====");for(String s:r.focus)log.add(s);
            log.add("===== B18 BINDER / SERVICE ACQUISITION CALLERS =====");for(String s:r.callers)log.add(s);
            log.add("B18 DONE dex="+r.dex+" methods="+r.methods+" meta="+r.meta.size()+" focus="+r.focus.size()+" callers="+r.callers.size());
        }

        static void dumpComponents(PackageInfo pi){
            String[] want={"com.netease.cloudmusic.service.PlayService","com.netease.cloudmusic.service.MainProcessPlayService","com.netease.cloudmusic.module.ucar.UCarService","com.netease.cloudmusic.third.api.CMApiService","com.netease.cloudmusic.activity.SmartDeviceBrowerActivity"};
            for(String n:want){boolean found=false;if(pi.services!=null)for(ServiceInfo s:pi.services)if(s!=null&&n.equals(s.name)){log.add(comp("SERVICE",s,s.permission));found=true;}if(pi.activities!=null)for(ActivityInfo a:pi.activities)if(a!=null&&n.equals(a.name)){log.add(comp("ACTIVITY",a,a.permission));found=true;}if(!found)log.add("COMP18 NOT_FOUND "+n);}
        }
        static String comp(String kind,ComponentInfo x,String perm){return"COMP18 "+kind+" "+x.name+" exported="+x.exported+" enabled="+x.enabled+" perm="+safe(perm)+" process="+safe(x.processName);}

        static void reflect(Context c,List<String> paths){
            try{StringBuilder cp=new StringBuilder();for(String p:paths){if(cp.length()>0)cp.append(java.io.File.pathSeparator);cp.append(p);}java.io.File opt=new java.io.File(c.getCodeCacheDir(),"ncm18");opt.mkdirs();DexClassLoader cl=new DexClassLoader(cp.toString(),opt.getAbsolutePath(),null,c.getClassLoader());
                for(String n:REFLECT)try{Class<?> k=Class.forName(n,false,cl);StringBuilder h=new StringBuilder("REFLECT18 ").append(n).append(" super=").append(k.getSuperclass()==null?"":k.getSuperclass().getName()).append(" interfaces=");for(Class<?> i:k.getInterfaces())h.append(i.getName()).append(',');h.append(" modifiers=").append(Modifier.toString(k.getModifiers()));log.add(h.toString());int nf=0;for(Field f:k.getDeclaredFields()){if(nf++>=40)break;log.add("REFLECT18 FIELD "+n+" "+Modifier.toString(f.getModifiers())+" "+f.getType().getName()+" "+f.getName());}int nc=0;for(Constructor<?> x:k.getDeclaredConstructors()){if(nc++>=20)break;log.add("REFLECT18 CTOR "+n+" "+x.toGenericString());}int nm=0;for(Method m:k.getDeclaredMethods()){if(nm++>=100)break;log.add("REFLECT18 METHOD "+n+" "+m.toGenericString());}}catch(Throwable t){log.add("REFLECT18 FAIL "+n+" "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            }catch(Throwable t){log.add("REFLECT18 LOADER FAIL "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
        }

        static void scanApk(String apk,Result r)throws Exception{try(ZipFile z=new ZipFile(apk)){java.util.Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;if(e.getSize()<=0||e.getSize()>96L*1024*1024)continue;r.dex++;new Dex(readAll(z.getInputStream(e)),r).scan();}}}

        static final class Result{int dex,methods;final List<String>meta=new ArrayList<>(),focus=new ArrayList<>(),callers=new ArrayList<>();}
        static final class Body{final Set<String>strings=new LinkedHashSet<>(),types=new LinkedHashSet<>(),fields=new LinkedHashSet<>();final List<String>calls=new ArrayList<>();}

        static final class Dex{
            final byte[]b;final Result r;final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
            Dex(byte[]x,Result rr){b=x;r=rr;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}
            void scan(){if(b.length<0x70||!range(classesOff,(long)classesN*32))return;for(int i=0;i<classesN;i++){int p=classesOff+i*32;if(!range(p,32))break;String owner=type(i32(p)),sup=type(i32(p+8)),ifs=interfaces(i32(p+12));int access=i32(p+4),data=i32(p+24);if(isFocus(owner))r.meta.add("META18 owner="+owner+" super="+sup+" ifaces="+ifs+" access=0x"+Integer.toHexString(access));if(data>0&&data<b.length)scanClass(owner,sup,ifs,data);}}
            void scanClass(String owner,String sup,String ifs,int off){try{int[]p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}scanMethods(owner,sup,ifs,p,dm);scanMethods(owner,sup,ifs,p,vm);}catch(Throwable ignored){}}
            void scanMethods(String owner,String sup,String ifs,int[]p,int count){int idx=0;for(int i=0;i<count;i++){idx+=uleb(p);int access=uleb(p),code=uleb(p);String sig=method(idx);r.methods++;if(code<=0)continue;Body z=body(code);inspect(owner,sup,ifs,sig,access,z);}}
            void inspect(String owner,String sup,String ifs,String sig,int access,Body z){
                if(isFocus(owner))r.focus.add(line("FOCUS18",sig,owner,sup,ifs,access,z));
                boolean fj=hasCall(z,FJ+"->a()Ljava/lang/Class;");
                boolean base=hasOwnerCall(z,BASE);
                boolean as=hasOwnerCall(z,AIDL_STUB)&&containsCallText(z,"Landroid/os/IBinder;");
                boolean bind=containsCallText(z,"->bindService(")||containsCallText(z,"->bindServiceAsUser(")||containsCallText(z,"->bindIsolatedService(");
                boolean ps=z.types.contains(PS)||z.types.contains(MPS)||contains(z.strings,"PlayService")||containsFields(z.fields,"PlayService");
                boolean intentClass=containsCallText(z,"Landroid/content/Intent;-><init>(Landroid/content/Context;Ljava/lang/Class;)V");
                if(fj||as||(base&&(bind||intentClass||ps))||(bind&&ps)||(intentClass&&ps))r.callers.add(line("ROUTE18",sig,owner,sup,ifs,access,z));
            }
            String line(String tag,String sig,String owner,String sup,String ifs,int access,Body z){return tag+" "+sig+" owner="+owner+" super="+sup+" ifaces="+ifs+" access=0x"+Integer.toHexString(access)+" strings="+shorten(join(z.strings),1200)+" types="+shorten(join(z.types),1200)+" fields="+shorten(join(z.fields),1800)+" calls="+shorten(joinList(z.calls),8000);}
            Body body(int codeOff){Body o=new Body();if(!range(codeOff,16))return o;int units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return o;int start=codeOff+16;for(int u=0;u<units;u++){int cu=u16(start+u*2),op=cu&255;
                    if(op==0x1a&&u+1<units){String s=str(u16(start+(u+1)*2));if(s!=null)o.strings.add(s);}else if(op==0x1b&&u+2<units){String s=str(readI32Units(start,u+1));if(s!=null)o.strings.add(s);}else if((op==0x1c||op==0x1f||op==0x22)&&u+1<units){o.types.add(type(u16(start+(u+1)*2)));}else if(op>=0x52&&op<=0x6d&&u+1<units){o.fields.add(field(u16(start+(u+1)*2)));}else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units){o.calls.add("@"+u+" "+method(u16(start+(u+1)*2)));}}
                return o;}
            String interfaces(int off){if(off<=0||!range(off,4))return"";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));}return s.toString();}
            String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return"field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
            String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return"method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
            String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return"(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
            String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return"?";return safe(str(i32(typesOff+idx*4)));}
            String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[]q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+3500);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
            int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
            int uleb(int[]pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}
            int i32(int p){if(!range(p,4))return-1;return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
            int u16(int p){if(!range(p,2))return-1;return(b[p]&255)|((b[p+1]&255)<<8);}
            boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        }

        static boolean isFocus(String s){return FJ.equals(s)||BASE.equals(s)||M6.equals(s)||M6G.equals(s)||SUB.equals(s)||SUBC.equals(s)||SMART.equals(s)||SMARTB.equals(s);}
        static boolean contains(Set<String>s,String n){String q=n.toLowerCase(Locale.ROOT);for(String x:s)if(x!=null&&x.toLowerCase(Locale.ROOT).contains(q))return true;return false;}
        static boolean containsFields(Set<String>s,String n){String q=n.toLowerCase(Locale.ROOT);for(String x:s)if(x!=null&&x.toLowerCase(Locale.ROOT).contains(q))return true;return false;}
        static boolean hasCall(Body z,String exact){for(String c:z.calls)if(c.endsWith(" "+exact))return true;return false;}
        static boolean hasOwnerCall(Body z,String owner){for(String c:z.calls)if(c.contains(" "+owner+"->"))return true;return false;}
        static boolean containsCallText(Body z,String needle){for(String c:z.calls)if(c.contains(needle))return true;return false;}
        static String join(Set<String>s){StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(" | ");b.append(x);}return b.toString();}
        static String joinList(List<String>s){StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(" | ");b.append(x);}return b.toString();}
        static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
        static byte[]readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>96*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    }
}
