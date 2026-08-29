package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Bundle;
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
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** BRIDGE14 is static/read-only. It never sends Broadcast/Binder/MediaSession/SepTrack commands. */
public class DetectorBridge14Activity extends Activity {
    private static final String BUILD = "BRIDGE14";
    private static final String NETEASE = "com.netease.cloudmusic";
    private static final LogBook LOG = new LogBook(9000);
    private TextView output;
    private Button scan;
    private final AtomicBoolean ticker = new AtomicBoolean(false);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14); root.setPadding(p,p,p,p);
        TextView title = new TextView(this);
        title.setText("Tesla Lyrics Detector · " + BUILD + "\n只读：只找 102/103 的跨 App 入口");
        title.setTextSize(18f);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        scan = new Button(this); scan.setText("一键扫描最后的控制桥");
        root.addView(scan,new LinearLayout.LayoutParams(-1,-2));
        Button export = new Button(this); export.setText("导出完整 TXT");
        root.addView(export,new LinearLayout.LayoutParams(-1,-2));
        ScrollView sv = new ScrollView(this);
        output = new TextView(this); output.setTextSize(10.5f); output.setTextIsSelectable(true);
        sv.addView(output); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);
        LOG.add("BUILD " + BUILD);
        LOG.add("READONLY: no broadcast send, no bind/transact, no MediaSession command, no SepTrack write");
        refresh();
        scan.setOnClickListener(v -> runScan());
        export.setOnClickListener(v -> {
            String x=exportTxt(); Toast.makeText(this,x==null?"导出失败":"已导出: "+x,Toast.LENGTH_LONG).show();
        });
    }

    private void runScan(){
        if(!Bridge.RUNNING.compareAndSet(false,true)){Toast.makeText(this,"扫描正在运行",Toast.LENGTH_SHORT).show();return;}
        scan.setEnabled(false); LOG.add("B14 START: broadcast wrapper + receiver registration + PlayController.call + generic PlayerHandler forwarders + exported entries");
        startTicker();
        new Thread(() -> {
            try { Bridge.scan(this,LOG); }
            catch(Throwable t){ LOG.add("B14 ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage())); }
            finally {
                Bridge.RUNNING.set(false); String x=exportTxt(); LOG.add("B14 AUTO_EXPORT "+safe(x));
                runOnUiThread(() -> {scan.setEnabled(true);refresh();});
            }
        },"netease-bridge14").start();
    }

    private void startTicker(){if(!ticker.compareAndSet(false,true))return;output.post(new Runnable(){@Override public void run(){refresh();if(Bridge.RUNNING.get())output.postDelayed(this,1000);else ticker.set(false);}});}
    private String exportTxt(){
        String name="NetEase-"+BUILD+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: "+BUILD+"\nTime: "+new Date()+"\n\n"+LOG.text();
        try{
            ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(u==null)return null;
            try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)return null;os.write(body.getBytes(StandardCharsets.UTF_8));}
            return "Downloads/TeslaLyricsDetector/"+name;
        }catch(Throwable t){LOG.add("EXPORT ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));return null;}
    }
    private void refresh(){output.setText(LOG.text());}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    static String safe(String s){return s==null?"":s;}

    static final class LogBook{
        final int cap; final Deque<String> q=new ArrayDeque<>();
        LogBook(int c){cap=c;}
        synchronized void add(String s){String t=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s;q.addLast(t);while(q.size()>cap)q.removeFirst();}
        synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}
    }

    static final class Bridge{
        static final AtomicBoolean RUNNING=new AtomicBoolean(false);
        static final String BUS="Lcom/netease/cloudmusic/service/IPlayService;->sendMessageByPlayerHandler(IIILjava/lang/Object;)V";
        static final String QD_F="Lqd/a;->f(Ljava/lang/Object;Landroid/content/Intent;Ljava/lang/String;)V";
        static final String PLAY_CALL_IMPL="Lcom/netease/cloudmusic/service/PlayService$1;->call(IIILcom/netease/cloudmusic/aidl/PlayControllCallbackObject;)V";
        static final String PLAY_CALL_IFACE="Lcom/netease/cloudmusic/aidl/d;->call(IIILcom/netease/cloudmusic/aidl/PlayControllCallbackObject;)V";
        static final String RX="Lfp0/r$g$a;";
        static final String RX_ONRECEIVE="Lfp0/r$g$a;->onReceive(Landroid/content/Context;Landroid/content/Intent;)V";
        static final String RX_ACTION="BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK";
        static final String CALLBACK_PARAM="Lcom/netease/cloudmusic/aidl/MediaSessionCallbackParam;";
        static LogBook log;

        static void scan(Context c,LogBook l)throws Exception{
            log=l; PackageManager pm=c.getPackageManager();
            PackageInfo pi=pm.getPackageInfo(NETEASE,PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_ACTIVITIES|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS);
            ApplicationInfo ai=pi.applicationInfo;
            log.add("PKG version="+pi.versionName+" code="+pi.getLongVersionCode()+" source="+(ai==null?"":safe(ai.sourceDir)));
            Map<String,Comp> comps=components(pi);
            for(Map.Entry<String,Comp> e:comps.entrySet()){Comp x=e.getValue();if(x.exported)log.add("EXPORTED "+x.kind+" "+e.getKey()+" perm="+safe(x.permission)+" process="+safe(x.process));}
            try{
                Intent q=new Intent(RX_ACTION);q.setPackage(NETEASE);
                List<ResolveInfo> rr=pm.queryBroadcastReceivers(q,PackageManager.MATCH_DISABLED_COMPONENTS);
                log.add("ACTION_QUERY action="+RX_ACTION+" manifestReceivers="+(rr==null?0:rr.size()));
                if(rr!=null)for(ResolveInfo r:rr)if(r.activityInfo!=null)log.add("ACTION_QUERY receiver="+r.activityInfo.name+" exported="+r.activityInfo.exported+" perm="+safe(r.activityInfo.permission));
            }catch(Throwable t){log.add("ACTION_QUERY ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            List<String> paths=new ArrayList<>();if(ai!=null&&ai.sourceDir!=null)paths.add(ai.sourceDir);if(ai!=null&&ai.splitSourceDirs!=null)Collections.addAll(paths,ai.splitSourceDirs);
            Result out=new Result(comps);for(String apk:paths)scanApk(apk,out);emit(out);
            log.add("B14 DONE dex="+out.dex+" methods="+out.methods+" exact="+out.exact.size()+" busCallers="+out.bus.size()+" bridgeCallers="+out.bridge.size()+" rxRefs="+out.rxRefs.size()+" registerRefs="+out.registerRefs.size()+" sendRefs="+out.sendRefs.size()+" playCallers="+out.playCallers.size()+" exportedHits="+out.exportedHits.size());
        }

        static Map<String,Comp> components(PackageInfo pi){
            Map<String,Comp> m=new LinkedHashMap<>();
            if(pi.services!=null)for(ServiceInfo x:pi.services)put(m,x,"service",x.permission);
            if(pi.receivers!=null)for(ActivityInfo x:pi.receivers)put(m,x,"receiver",x.permission);
            if(pi.activities!=null)for(ActivityInfo x:pi.activities)put(m,x,"activity",x.permission);
            if(pi.providers!=null)for(ProviderInfo x:pi.providers)put(m,x,"provider",x.readPermission!=null?x.readPermission:x.writePermission);
            return m;
        }
        static void put(Map<String,Comp> m,ComponentInfo x,String kind,String perm){if(x==null||x.name==null)return;m.put(desc(x.name),new Comp(kind,x.exported,perm,x.processName));}
        static String desc(String n){if(n.startsWith("."))n=NETEASE+n;return"L"+n.replace('.','/')+";";}
        static void scanApk(String apk,Result r)throws Exception{try(ZipFile z=new ZipFile(apk)){java.util.Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;if(e.getSize()<=0||e.getSize()>96L*1024*1024)continue;r.dex++;new Dex(readAll(z.getInputStream(e)),r).scan();}}}
        static void emit(Result r){
            log.add("===== B14 EXACT METHODS =====");for(String s:r.exact)log.add(s);
            log.add("===== B14 ALL PLAYER BUS CALLERS =====");for(String s:r.bus)log.add(s);
            log.add("===== B14 BROADCAST BRIDGE CALLERS =====");for(String s:r.bridge)log.add(s);
            log.add("===== B14 RECEIVER / REGISTRATION REFS =====");for(String s:r.rxRefs)log.add(s);for(String s:r.registerRefs)log.add(s);
            log.add("===== B14 SEND / PLAYCONTROLLER CALLERS =====");for(String s:r.sendRefs)log.add(s);for(String s:r.playCallers)log.add(s);
            log.add("===== B14 EXPORTED COMPONENT ROUTE HITS =====");for(String s:r.exportedHits)log.add(s);
        }

        static final class Comp{final String kind;final boolean exported;final String permission,process;Comp(String k,boolean e,String p,String pr){kind=k;exported=e;permission=p;process=pr;}}
        static final class Result{
            final Map<String,Comp> comps;int dex,methods;
            final List<String> exact=new ArrayList<>(),bus=new ArrayList<>(),bridge=new ArrayList<>(),rxRefs=new ArrayList<>(),registerRefs=new ArrayList<>(),sendRefs=new ArrayList<>(),playCallers=new ArrayList<>(),exportedHits=new ArrayList<>();
            Result(Map<String,Comp> c){comps=c;}
        }
        static final class Call{final int off;final String m;final int[] regs;final String[] defs;Call(int o,String x,int[] r,String[] d){off=o;m=x;regs=r;defs=d;}}
        static final class Event{final int off;final String s;Event(int o,String x){off=o;s=x;}}
        static final class Body{final Set<String> strings=new LinkedHashSet<>();final Set<Integer> nums=new LinkedHashSet<>();final List<Call> calls=new ArrayList<>();final List<Event> events=new ArrayList<>();}

        static final class Dex{
            final byte[] b;final Result r;final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
            Dex(byte[] x,Result rr){b=x;r=rr;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}
            void scan(){if(b.length<0x70||!range(classesOff,(long)classesN*32))return;for(int i=0;i<classesN;i++){int cp=classesOff+i*32;if(!range(cp,32))break;String owner=type(i32(cp)),sup=type(i32(cp+8)),ifs=interfaces(i32(cp+12));int data=i32(cp+24);if(data>0&&data<b.length)scanClass(owner,sup,ifs,data);}}
            void scanClass(String owner,String sup,String ifs,int off){try{int[] p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}scanMethods(owner,sup,ifs,p,dm);scanMethods(owner,sup,ifs,p,vm);}catch(Throwable ignored){}}
            void scanMethods(String owner,String sup,String ifs,int[] p,int n){int idx=0;for(int i=0;i<n;i++){idx+=uleb(p);int access=uleb(p),code=uleb(p);String sig=method(idx);r.methods++;if(code<=0)continue;Body z=body(code,(access&8)!=0);inspect(owner,sup,ifs,sig,z);}}
            void inspect(String owner,String sup,String ifs,String sig,Body z){
                boolean exact=sig.equals(QD_F)||sig.equals(PLAY_CALL_IMPL)||sig.equals(RX_ONRECEIVE)||owner.equals("Lfp0/r;")&&containsIgnore(z.strings,RX_ACTION);
                if(exact)r.exact.add("EXACT14 "+sig+" owner="+owner+" super="+sup+" ifaces="+ifs+" strings="+shorten(join(z.strings),1200)+" nums="+nums(z.nums)+" calls="+shorten(callNames(z),5000)+" events="+events(z.events,260,14000));
                boolean hasRx=owner.equals(RX)||sig.contains(RX)||containsEvent(z.events,RX)||containsCall(z,RX);
                if(hasRx)r.rxRefs.add("RXREF14 "+sig+" owner="+owner+" strings="+shorten(join(z.strings),700)+" calls="+shorten(callNames(z),4500)+" events="+events(z.events,180,10000));
                for(Call c:z.calls){
                    if(c.m.equals(BUS))r.bus.add("BUS14 "+sig+" owner="+owner+" @"+c.off+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),500)+" events="+window(z.events,c.off,45));
                    if(c.m.equals(QD_F))r.bridge.add("BRIDGECALL14 "+sig+" owner="+owner+" @"+c.off+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),800)+" events="+window(z.events,c.off,55));
                    if(c.m.equals(PLAY_CALL_IFACE)||c.m.equals(PLAY_CALL_IMPL))r.playCallers.add("PLAYCALL14 "+sig+" owner="+owner+" @"+c.off+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),700)+" events="+window(z.events,c.off,55));
                    String lm=c.m.toLowerCase(Locale.ROOT);
                    if(lm.contains("registerreceiver"))r.registerRefs.add("REGISTER14 "+sig+" owner="+owner+" @"+c.off+" call="+c.m+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),1000)+" events="+window(z.events,c.off,70));
                    if(lm.contains("sendbroadcast")||lm.contains("localbroadcastmanager;->sendbroadcast")||lm.contains("broadcastmanager;->"))r.sendRefs.add("SEND14 "+sig+" owner="+owner+" @"+c.off+" call="+c.m+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),1000)+" events="+window(z.events,c.off,70));
                }
                if(containsIgnore(z.strings,RX_ACTION)&&!sig.equals(QD_F))r.bridge.add("ACTION14 "+sig+" owner="+owner+" strings="+shorten(join(z.strings),1000)+" calls="+shorten(callNames(z),5000)+" events="+events(z.events,200,11000));
                Comp cp=r.comps.get(owner);if(cp!=null&&cp.exported&&interestingEntry(sig,z))r.exportedHits.add("EXTERNAL14 kind="+cp.kind+" owner="+owner+" perm="+safe(cp.permission)+" method="+sig+" strings="+shorten(join(z.strings),900)+" calls="+shorten(callNames(z),5000)+" events="+events(z.events,160,10000));
            }
            boolean interestingEntry(String sig,Body z){String s=sig.toLowerCase(Locale.ROOT);if(s.contains("->onbind(")||s.contains("->onreceive(")||s.contains("->onstartcommand(")||s.contains("->oncommand(")||s.contains("->oncustomaction(")||s.contains("->onhandleintent("))return true;if(containsIgnore(z.strings,RX_ACTION)||containsIgnore(z.strings,"callbackName")||containsIgnore(z.strings,"MediaSessionCallbackParam"))return true;for(Call c:z.calls)if(c.m.equals(BUS)||c.m.equals(QD_F)||c.m.equals(PLAY_CALL_IFACE))return true;return false;}

            Body body(int codeOff,boolean isStatic){
                Body out=new Body();if(!range(codeOff,16))return out;int regN=u16(codeOff),insN=u16(codeOff+2),units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return out;String[] def=new String[Math.max(regN,0)];int p0=regN-insN;for(int i=0;i<insN&&p0+i>=0&&p0+i<def.length;i++)def[p0+i]="p"+i;int start=codeOff+16;String last="";
                for(int u=0;u<units;u++){
                    int cu=u16(start+u*2),op=cu&255;
                    if(op==0x12){int a=(cu>>8)&15,v=(cu>>12)&15;if((v&8)!=0)v|=~15;set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const v"+a+"="+constDesc(v)));}
                    else if(op==0x13&&u+1<units){int a=(cu>>8)&255,v=(short)u16(start+(u+1)*2);set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const16 v"+a+"="+constDesc(v)));}
                    else if(op==0x14&&u+2<units){int a=(cu>>8)&255,v=readI32Units(start,u+1);set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const32 v"+a+"="+constDesc(v)));}
                    else if(op==0x15&&u+1<units){int a=(cu>>8)&255,v=((short)u16(start+(u+1)*2))<<16;set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"constHigh16 v"+a+"="+constDesc(v)));}
                    else if(op==0x01||op==0x04||op==0x07){int a=(cu>>8)&15,bb=(cu>>12)&15;set(def,a,get(def,bb));out.events.add(new Event(u,"move v"+a+" <- v"+bb+"="+get(def,bb)));}
                    else if((op==0x02||op==0x05||op==0x08)&&u+1<units){int a=(cu>>8)&255,bb=u16(start+(u+1)*2);set(def,a,get(def,bb));out.events.add(new Event(u,"move/from16 v"+a+" <- v"+bb+"="+get(def,bb)));}
                    else if((op==0x03||op==0x06||op==0x09)&&u+2<units){int a=u16(start+(u+1)*2),bb=u16(start+(u+2)*2);set(def,a,get(def,bb));out.events.add(new Event(u,"move/16 v"+a+" <- v"+bb+"="+get(def,bb)));}
                    else if(op==0x0a||op==0x0b||op==0x0c){int a=(cu>>8)&255;set(def,a,"result("+shorten(last,150)+")");out.events.add(new Event(u,"move-result v"+a+"="+get(def,a)));}
                    else if(op==0x1a&&u+1<units){int a=(cu>>8)&255;String s=str(u16(start+(u+1)*2));set(def,a,"str("+shorten(s,100)+")");if(s!=null)out.strings.add(s);out.events.add(new Event(u,"string v"+a+"="+shorten(s,130)));}
                    else if(op==0x1b&&u+2<units){int a=(cu>>8)&255;String s=str(readI32Units(start,u+1));set(def,a,"str("+shorten(s,100)+")");if(s!=null)out.strings.add(s);out.events.add(new Event(u,"string/jumbo v"+a+"="+shorten(s,130)));}
                    else if(op==0x1f&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"cast("+t+","+get(def,a)+")");out.events.add(new Event(u,"check-cast v"+a+" -> "+t));}
                    else if(op==0x22&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"new("+t+")");out.events.add(new Event(u,"new v"+a+" "+t));}
                    else if(op>=0x52&&op<=0x58&&u+1<units){int a=(cu>>8)&15,bb=(cu>>12)&15;String f=field(u16(start+(u+1)*2));set(def,a,"iget("+f+")");out.events.add(new Event(u,"iget v"+a+" <- "+f+" obj=v"+bb));}
                    else if(op>=0x59&&op<=0x5f&&u+1<units){int a=(cu>>8)&15,bb=(cu>>12)&15;String f=field(u16(start+(u+1)*2));out.events.add(new Event(u,"iput "+f+" <- v"+a+"="+get(def,a)+" obj=v"+bb));}
                    else if(op>=0x60&&op<=0x66&&u+1<units){int a=(cu>>8)&255;String f=field(u16(start+(u+1)*2));set(def,a,"sget("+f+")");out.events.add(new Event(u,"sget v"+a+" <- "+f));}
                    else if(op>=0x67&&op<=0x6d&&u+1<units){int a=(cu>>8)&255;String f=field(u16(start+(u+1)*2));out.events.add(new Event(u,"sput "+f+" <- v"+a+"="+get(def,a)));}
                    else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units){String m=method(u16(start+(u+1)*2));int[] rr=invokeRegs(cu,op,start,u);String[] snap=new String[rr.length];for(int k=0;k<rr.length;k++)snap[k]=get(def,rr[k]);out.calls.add(new Call(u,m,rr,snap));out.events.add(new Event(u,"invoke "+m+" regs="+regs(rr)+" defs="+defs(snap)));last=m;}
                }
                return out;
            }
            int[] invokeRegs(int cu,int op,int start,int u){if(op>=0x74&&op<=0x78){int n=(cu>>8)&255,first=u16(start+(u+2)*2);int[] a=new int[n];for(int i=0;i<n;i++)a[i]=first+i;return a;}int n=(cu>>12)&15,g=(cu>>8)&15,x=u16(start+(u+2)*2);int[] all={x&15,(x>>4)&15,(x>>8)&15,(x>>12)&15,g};int[] a=new int[Math.min(n,5)];System.arraycopy(all,0,a,0,a.length);return a;}
            String interfaces(int off){if(off<=0||!range(off,4))return"";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));}return s.toString();}
            String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return"field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
            String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return"method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
            String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return"(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
            String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return"?";return safe(str(i32(typesOff+idx*4)));}
            String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+4000);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
            int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
            int uleb(int[] pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}
            int i32(int p){if(!range(p,4))return-1;return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
            int u16(int p){if(!range(p,2))return-1;return(b[p]&255)|((b[p+1]&255)<<8);}
            boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        }

        static boolean containsIgnore(Set<String> s,String needle){String n=needle.toLowerCase(Locale.ROOT);for(String x:s)if(x!=null&&x.toLowerCase(Locale.ROOT).contains(n))return true;return false;}
        static boolean containsCall(Body z,String needle){for(Call c:z.calls)if(c.m.contains(needle))return true;return false;}
        static boolean containsEvent(List<Event> es,String needle){for(Event e:es)if(e.s.contains(needle))return true;return false;}
        static String callNames(Body z){StringBuilder b=new StringBuilder();for(Call c:z.calls){if(b.length()>0)b.append(" | ");b.append('@').append(c.off).append(' ').append(c.m).append(' ').append(defs(c.defs));}return b.toString();}
        static String events(List<Event> es,int max,int chars){StringBuilder b=new StringBuilder();int n=0;for(Event e:es){if(n++>=max)break;if(b.length()>0)b.append(" || ");b.append('@').append(e.off).append(' ').append(e.s);if(b.length()>chars)break;}return shorten(b.toString(),chars);}
        static String window(List<Event> es,int target,int radius){StringBuilder b=new StringBuilder();int n=0;for(Event e:es){if(e.off<target-radius||e.off>target+radius)continue;if(n++>100)break;if(b.length()>0)b.append(" || ");b.append('@').append(e.off).append(' ').append(e.s);}return shorten(b.toString(),9000);}
        static String join(Set<String> s){StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(" | ");b.append(x);}return b.toString();}
        static String nums(Set<Integer> ns){StringBuilder b=new StringBuilder();for(int v:ns){if(b.length()>0)b.append(',');b.append(v);float f=Float.intBitsToFloat(v);if(Float.isFinite(f)&&Math.abs(f)>=0.00001f&&Math.abs(f)<=1000f)b.append("(f=").append(f).append(')');}return b.toString();}
        static String regs(int[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(',');b.append('v').append(a[i]);}return b.append(']').toString();}
        static String defs(String[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(" | ");b.append(i).append('=').append(shorten(a[i],260));}return b.append(']').toString();}
        static String constDesc(int v){float f=Float.intBitsToFloat(v);if(Float.isFinite(f)&&Math.abs(f)>=0.00001f&&Math.abs(f)<=1000f)return"const("+v+",floatBits="+f+")";return"const("+v+")";}
        static void set(String[] a,int i,String v){if(i>=0&&i<a.length)a[i]=v;}
        static String get(String[] a,int i){return i>=0&&i<a.length&&a[i]!=null?a[i]:"?";}
        static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
        static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] q=new byte[32768];int n,total=0;while((n=x.read(q))>0){total+=n;if(total>96*1024*1024)throw new IllegalStateException("dex too large");o.write(q,0,n);}return o.toByteArray();}}
    }
}
