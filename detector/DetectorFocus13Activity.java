package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
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

/** FULLSCAN13 is read-only: no unknown Binder transact and no media/SepTrack writes. */
public class DetectorFocus13Activity extends Activity {
    private static final String BUILD = "FULLSCAN13";
    private static final String NETEASE = "com.netease.cloudmusic";
    private static final LogBook LOG = new LogBook(12000);
    private TextView output;
    private Button scan;
    private final AtomicBoolean ticker = new AtomicBoolean(false);

    public static class SessionAccessService extends android.service.notification.NotificationListenerService {}

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad,pad,pad,pad);
        TextView title = new TextView(this);
        title.setText("Tesla Lyrics Detector · " + BUILD + "\n只读专项扫描：AIDL / 102-103 Handler / 正式滑杆");
        title.setTextSize(18f);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        scan = new Button(this);
        scan.setText("一键专项扫描网易云");
        root.addView(scan,new LinearLayout.LayoutParams(-1,-2));
        Button media = new Button(this);
        media.setText("打开媒体读取权限（建议开启）");
        root.addView(media,new LinearLayout.LayoutParams(-1,-2));
        Button export = new Button(this);
        export.setText("导出完整 TXT");
        root.addView(export,new LinearLayout.LayoutParams(-1,-2));
        ScrollView sv = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(10.5f);
        output.setTextIsSelectable(true);
        sv.addView(output);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);
        LOG.add("BUILD " + BUILD);
        LOG.add("READONLY: no unknown Binder transact, no MediaSession command, no SepTrack write");
        refresh();
        scan.setOnClickListener(v -> runScan());
        media.setOnClickListener(v -> {
            try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
            catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        export.setOnClickListener(v -> {
            String p = exportTxt();
            Toast.makeText(this,p==null?"导出失败":"已导出: "+p,Toast.LENGTH_LONG).show();
        });
    }

    private void runScan() {
        if (!Focus.RUNNING.compareAndSet(false,true)) { Toast.makeText(this,"扫描正在运行",Toast.LENGTH_SHORT).show(); return; }
        scan.setEnabled(false);
        LOG.add("F13 START: PlayController AIDL + Handler cases 102/103 + VolumeView field mapping + bind/media entry search");
        startTicker();
        new Thread(() -> {
            try { dumpPackageAndServices(); dumpLiveMedia(); Focus.scan(this,LOG); }
            catch (Throwable t) { LOG.add("F13 ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage())); }
            finally {
                Focus.RUNNING.set(false);
                String p = exportTxt();
                LOG.add("F13 AUTO_EXPORT " + safe(p));
                runOnUiThread(() -> { scan.setEnabled(true); refresh(); });
            }
        },"netease-focus13").start();
    }

    private void startTicker() {
        if (!ticker.compareAndSet(false,true)) return;
        output.post(new Runnable(){@Override public void run(){ refresh(); if(Focus.RUNNING.get()) output.postDelayed(this,1000); else ticker.set(false); }});
    }

    private void dumpPackageAndServices() {
        try {
            PackageManager pm=getPackageManager();
            PackageInfo pi=pm.getPackageInfo(NETEASE,PackageManager.GET_SERVICES|PackageManager.GET_META_DATA|PackageManager.MATCH_DISABLED_COMPONENTS);
            ApplicationInfo ai=pi.applicationInfo;
            LOG.add("PKG version="+pi.versionName+" code="+pi.getLongVersionCode()+" source="+(ai==null?"":ai.sourceDir));
            if(pi.services!=null) for(ServiceInfo s:pi.services) if(s!=null) LOG.add("SERVICE name="+s.name+" exported="+s.exported+" perm="+safe(s.permission)+" process="+safe(s.processName));
        } catch(Throwable t){ LOG.add("PKG ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage())); }
    }

    private void dumpLiveMedia() {
        try {
            ComponentName access=new ComponentName(this,SessionAccessService.class);
            String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");
            boolean ok=enabled!=null&&enabled.contains(getPackageName());
            LOG.add("LIVE MEDIA permission="+ok);
            if(!ok){ LOG.add("LIVE MEDIA skipped"); return; }
            MediaSessionManager mm=(MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
            int count=0;
            for(MediaController mc:mm.getActiveSessions(access)){
                if(!NETEASE.equals(mc.getPackageName()))continue;
                count++;
                MediaMetadata md=mc.getMetadata(); PlaybackState ps=mc.getPlaybackState();
                LOG.add("LIVE MEDIA state="+(ps==null?"null":ps.getState())+" title="+(md==null?"":safe(md.getString(MediaMetadata.METADATA_KEY_TITLE))));
                if(ps!=null){ StringBuilder b=new StringBuilder(); for(PlaybackState.CustomAction ca:ps.getCustomActions()){ if(b.length()>0)b.append(" | "); b.append(ca.getName()).append('=').append(ca.getAction()); } LOG.add("LIVE CUSTOM_ACTIONS "+b); }
            }
            LOG.add("LIVE MEDIA sessions="+count);
        } catch(Throwable t){LOG.add("LIVE MEDIA ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
    }

    private String exportTxt(){
        String name="NetEase-"+BUILD+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: "+BUILD+"\nTime: "+new Date()+"\n\n"+LOG.text();
        try{
            ContentValues cv=new ContentValues(); cv.put(MediaStore.Downloads.DISPLAY_NAME,name); cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain"); cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv); if(u==null)return null;
            try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)return null;os.write(body.getBytes(StandardCharsets.UTF_8));}
            return "Downloads/TeslaLyricsDetector/"+name;
        }catch(Throwable t){LOG.add("EXPORT ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));return null;}
    }

    private void refresh(){output.setText(LOG.text());}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    static String safe(String s){return s==null?"":s;}

    static final class LogBook{
        final int cap; final Deque<String> q=new ArrayDeque<>(); LogBook(int c){cap=c;}
        synchronized void add(String s){String t=new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s;q.addLast(t);while(q.size()>cap)q.removeFirst();}
        synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}
    }

    static final class Focus {
        static final AtomicBoolean RUNNING=new AtomicBoolean(false);
        static final String BUS="Lcom/netease/cloudmusic/service/IPlayService;->sendMessageByPlayerHandler(IIILjava/lang/Object;)V";
        static final String AIDL_IFACE="Lcom/netease/cloudmusic/aidl/d;";
        static final String AIDL_STUB="Lcom/netease/cloudmusic/aidl/d$a;";
        static final String AIDL_PROXY="Lcom/netease/cloudmusic/aidl/d$a$a;";
        static final String AIDL_DESC="com.netease.cloudmusic.aidl.PlayController";
        static final String MSCB="Lcom/netease/cloudmusic/aidl/MediaSessionCallbackParam;";
        static final String HANDLER="Lcom/netease/cloudmusic/service/PlayService$PlayerHandler;->handleMessageInner(Landroid/os/Message;)V";
        static final String VOLVIEW="Lcom/netease/cloudmusic/ui/plugin/sep/track/VolumeView;";
        static LogBook log;

        static void scan(Context c,LogBook l)throws Exception{
            log=l; PackageManager pm=c.getPackageManager(); ApplicationInfo ai=pm.getApplicationInfo(NETEASE,0);
            List<String> paths=new ArrayList<>(); if(ai.sourceDir!=null)paths.add(ai.sourceDir); if(ai.splitSourceDirs!=null)Collections.addAll(paths,ai.splitSourceDirs);
            Map<String,ServiceInfo> services=serviceMap(pm); Result r=new Result(services);
            for(String apk:paths) scanApk(apk,r); emit(r);
            log.add("F13 DONE dex="+r.dex+" methods="+r.methods+" aidlMethods="+r.aidlMethods.size()+" aidlTx="+r.aidlTx.size()+" bus102="+r.bus102.size()+" bus103="+r.bus103.size()+" handlerCases="+r.handlerCases.size()+" volumeMethods="+r.volume.size()+" bindCandidates="+r.binds.size()+" callbackUsers="+r.callbackUsers.size());
        }

        static Map<String,ServiceInfo> serviceMap(PackageManager pm){Map<String,ServiceInfo> out=new LinkedHashMap<>();try{PackageInfo pi=pm.getPackageInfo(NETEASE,PackageManager.GET_SERVICES|PackageManager.MATCH_DISABLED_COMPONENTS);if(pi.services!=null)for(ServiceInfo s:pi.services)if(s!=null)out.put(desc(s.name),s);}catch(Throwable ignored){}return out;}
        static String desc(String n){if(n==null)return"";if(n.startsWith("."))n=NETEASE+n;return"L"+n.replace('.','/')+";";}
        static void scanApk(String apk,Result r)throws Exception{try(ZipFile z=new ZipFile(apk)){java.util.Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;if(e.getSize()<=0||e.getSize()>96L*1024*1024)continue;r.dex++;new Dex(e.getName(),readAll(z.getInputStream(e)),r).scan();}}}

        static void emit(Result r){
            log.add("===== F13 AIDL PLAYCONTROLLER ====="); for(String s:r.aidlMethods)log.add(s);for(String s:r.aidlTx)log.add(s);for(String s:r.aidlDescriptor)log.add(s);for(String s:r.aidlImpl)log.add(s);for(String s:r.callbackUsers)log.add(s);
            log.add("===== F13 PLAYER BUS 102/103 ====="); for(String s:r.bus102)log.add(s);for(String s:r.bus103)log.add(s);for(String s:r.handlerCases)log.add(s);
            log.add("===== F13 PRODUCTION VOLUMEVIEW ====="); for(String s:r.volume)log.add(s);
            log.add("===== F13 SERVICE / MEDIA ENTRY ====="); for(String s:r.binds)log.add(s);for(String s:r.media)log.add(s);
        }

        static final class Result{
            final Map<String,ServiceInfo> services;int dex,methods;
            final List<String> aidlMethods=new ArrayList<>(),aidlTx=new ArrayList<>(),aidlDescriptor=new ArrayList<>(),aidlImpl=new ArrayList<>(),callbackUsers=new ArrayList<>();
            final List<String> bus102=new ArrayList<>(),bus103=new ArrayList<>(),handlerCases=new ArrayList<>(),volume=new ArrayList<>(),binds=new ArrayList<>(),media=new ArrayList<>();
            Result(Map<String,ServiceInfo>s){services=s;}
        }
        static final class Event{final int off;final String d;Event(int o,String x){off=o;d=x;}}
        static final class SwitchCase{final int key,target;SwitchCase(int k,int t){key=k;target=t;}}
        static final class Call{final int off;final String m;final int[] regs;final String[] defs;Call(int o,String mm,int[] rr,String[] dd){off=o;m=mm;regs=rr;defs=dd;}}
        static final class Body{final Set<String> strings=new LinkedHashSet<>();final Set<Integer> nums=new LinkedHashSet<>();final List<Call> calls=new ArrayList<>();final List<Event> events=new ArrayList<>();final List<SwitchCase> switches=new ArrayList<>();}

        static final class Dex{
            final String name;final byte[]b;final Result r;final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
            Dex(String n,byte[]x,Result rr){name=n;b=x;r=rr;stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);}
            void scan(){if(b.length<0x70||!range(classesOff,(long)classesN*32))return;for(int ci=0;ci<classesN;ci++){int cp=classesOff+ci*32;if(!range(cp,32))break;String owner=type(i32(cp)),sup=type(i32(cp+8)),ifs=interfaces(i32(cp+12));int data=i32(cp+24);if(data>0&&data<b.length)scanClass(owner,sup,ifs,data);}}
            void scanClass(String owner,String sup,String ifs,int off){try{int[]p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}scanMethods(owner,sup,ifs,p,dm);scanMethods(owner,sup,ifs,p,vm);}catch(Throwable ignored){}}
            void scanMethods(String owner,String sup,String ifs,int[]p,int count){int idx=0;for(int i=0;i<count;i++){idx+=uleb(p);int access=uleb(p),code=uleb(p);String sig=method(idx);r.methods++;if(owner.equals(AIDL_IFACE))r.aidlMethods.add("AIDL13 IFACE "+sig+" access=0x"+Integer.toHexString(access));if(code<=0)continue;Body z=body(code,(access&8)!=0);inspect(owner,sup,ifs,sig,z);}}
            void inspect(String owner,String sup,String ifs,String sig,Body z){
                boolean aidlFamily=owner.equals(AIDL_IFACE)||owner.equals(AIDL_STUB)||owner.equals(AIDL_PROXY)||owner.startsWith("Lcom/netease/cloudmusic/aidl/");
                boolean descHit=z.strings.contains(AIDL_DESC); boolean impl=ifs.contains(AIDL_IFACE)||sup.equals(AIDL_STUB);
                if(impl && !owner.equals(AIDL_PROXY))r.aidlImpl.add("AIDL13 IMPL owner="+owner+" super="+sup+" ifaces="+ifs+" method="+sig);
                if(aidlFamily && owner.equals(AIDL_PROXY)) for(Call c:z.calls)if(c.m.equals("Landroid/os/IBinder;->transact(ILandroid/os/Parcel;Landroid/os/Parcel;I)Z")) r.aidlTx.add("AIDL13 TX "+sig+" @"+c.off+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" strings="+shorten(join(z.strings),300));
                if(descHit)r.aidlDescriptor.add("AIDL13 DESCRIPTOR "+sig+" owner="+owner+" class=super="+sup+" ifaces="+ifs+" calls="+shorten(callNames(z),900)+" nums="+nums(z.nums));
                if(usesCallback(z)||sig.contains("MediaSessionCallbackParam"))r.callbackUsers.add("AIDL13 CALLBACK_USER "+sig+" owner="+owner+" strings="+shorten(join(z.strings),280)+" calls="+shorten(callNames(z),700));
                for(Call c:z.calls)if(c.m.equals(BUS)&&c.regs.length>=5){String what=c.defs.length>1?c.defs[1]:"?";String line="BUS13 "+sig+" @"+c.off+" regs="+regs(c.regs)+" defs="+defs(c.defs)+" owner="+owner;if(what.contains("const(102"))r.bus102.add(line);else if(what.contains("const(103"))r.bus103.add(line);}
                if(sig.equals(HANDLER)){
                    for(SwitchCase sc:z.switches)if(sc.key==102||sc.key==103)r.handlerCases.add("HANDLER13 CASE what="+sc.key+" target=@"+sc.target+" events="+window(z.events,sc.target,80));
                    r.handlerCases.add("HANDLER13 SUMMARY switches="+switches(z.switches)+" sepEvents="+filterEvents(z.events,new String[]{"SepTrack","MusicVoice","VoiceVolume","setMusicVoiceVolume","check-cast"},120));
                }
                if(owner.equals(VOLVIEW)&&isVolumeFocus(sig))r.volume.add("VOLUME13 "+sig+" nums="+nums(z.nums)+" events="+window(z.events,0,260));
                if(r.services.containsKey(owner)&&sig.contains("->onBind(Landroid/content/Intent;)Landroid/os/IBinder;")){ServiceInfo si=r.services.get(owner);r.binds.add("BIND13 "+sig+" exported="+si.exported+" perm="+safe(si.permission)+" strings="+shorten(join(z.strings),500)+" calls="+shorten(callNames(z),1000)+" events="+filterEvents(z.events,new String[]{"AIDL","Binder","asBinder","action","PlayController","UCar","HiCar"},120));}
                if(owner.contains("UCarService")||owner.contains("HiCarHonorMediaOperateMgr")||owner.contains("CMApiService")||owner.contains("ContinuationService")){if(sig.contains("onBind")||descHit||containsIgnore(z.strings,"action")||containsCall(z,"asBinder"))r.binds.add("CARBIND13 "+sig+" owner="+owner+" strings="+shorten(join(z.strings),520)+" calls="+shorten(callNames(z),900));}
                String low=(owner+" "+sup+" "+ifs+" "+sig+" "+join(z.strings)).toLowerCase(Locale.ROOT);if(low.contains("oncustomaction(")||low.contains("oncommand(")||low.contains("mediasessioncallback")||(low.contains("media")&&(low.contains("septrack")||low.contains("随心唱"))))r.media.add("MEDIA13 "+sig+" owner="+owner+" strings="+shorten(join(z.strings),500)+" calls="+shorten(callNames(z),900));
            }
            boolean isVolumeFocus(String s){return s.contains("-><init>(Landroid/content/Context;Landroid/util/AttributeSet;I)V")||s.contains("->getVolumeValue()F")||s.contains("->setVolumeValue(F)V")||s.contains("->setMinVolumeValue(F)V")||s.contains("->setMaxVolumeValue(F)V")||s.contains("->setMinProgress(F)V")||s.contains("->setMaxProgress(F)V")||s.contains("->dispatchTouchEvent(Landroid/view/MotionEvent;)Z");}
            boolean usesCallback(Body z){for(Call c:z.calls)if(c.m.contains("MediaSessionCallbackParam"))return true;return false;}

            Body body(int codeOff,boolean isStatic){
                Body out=new Body();if(!range(codeOff,16))return out;int regN=u16(codeOff),insN=u16(codeOff+2),units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return out;String[]def=new String[Math.max(0,regN)];int p0=regN-insN;for(int i=0;i<insN&&p0+i>=0&&p0+i<def.length;i++)def[p0+i]="p"+i;int start=codeOff+16;String last="";
                for(int u=0;u<units;u++){int cu=u16(start+u*2),op=cu&255;
                    if(op==0x12){int a=(cu>>8)&15,v=(cu>>12)&15;if((v&8)!=0)v|=~15;set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const v"+a+"="+constDesc(v)));}
                    else if(op==0x13&&u+1<units){int a=(cu>>8)&255,v=(short)u16(start+(u+1)*2);set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const16 v"+a+"="+constDesc(v)));}
                    else if(op==0x14&&u+2<units){int a=(cu>>8)&255,v=readI32Units(start,u+1);set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"const32 v"+a+"="+constDesc(v)));}
                    else if(op==0x15&&u+1<units){int a=(cu>>8)&255,v=((short)u16(start+(u+1)*2))<<16;set(def,a,constDesc(v));out.nums.add(v);out.events.add(new Event(u,"constHigh16 v"+a+"="+constDesc(v)));}
                    else if(op==0x01||op==0x04||op==0x07){int a=(cu>>8)&15,bb=(cu>>12)&15;set(def,a,get(def,bb));}
                    else if((op==0x02||op==0x05||op==0x08)&&u+1<units){int a=(cu>>8)&255,bb=u16(start+(u+1)*2);set(def,a,get(def,bb));}
                    else if((op==0x03||op==0x06||op==0x09)&&u+2<units){int a=u16(start+(u+1)*2),bb=u16(start+(u+2)*2);set(def,a,get(def,bb));}
                    else if(op==0x0a||op==0x0b||op==0x0c){int a=(cu>>8)&255;set(def,a,"result("+shorten(last,130)+")");out.events.add(new Event(u,"move-result v"+a+"="+get(def,a)));}
                    else if(op==0x1a&&u+1<units){int a=(cu>>8)&255;String s=str(u16(start+(u+1)*2));set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);out.events.add(new Event(u,"string v"+a+"="+shorten(s,100)));}
                    else if(op==0x1b&&u+2<units){int a=(cu>>8)&255;String s=str(readI32Units(start,u+1));set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);out.events.add(new Event(u,"string/jumbo v"+a+"="+shorten(s,100)));}
                    else if(op==0x1f&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"cast("+t+","+get(def,a)+")");out.events.add(new Event(u,"check-cast v"+a+" -> "+t));}
                    else if(op==0x22&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"new("+t+")");out.events.add(new Event(u,"new v"+a+" "+t));}
                    else if(op>=0x52&&op<=0x58&&u+1<units){int a=(cu>>8)&15,bb=(cu>>12)&15;String f=field(u16(start+(u+1)*2));set(def,a,"iget("+f+")");out.events.add(new Event(u,"iget v"+a+" <- "+f+" obj=v"+bb));}
                    else if(op>=0x59&&op<=0x5f&&u+1<units){int a=(cu>>8)&15,bb=(cu>>12)&15;String f=field(u16(start+(u+1)*2));out.events.add(new Event(u,"iput "+f+" <- v"+a+"="+get(def,a)+" obj=v"+bb));}
                    else if(op>=0x60&&op<=0x66&&u+1<units){int a=(cu>>8)&255;String f=field(u16(start+(u+1)*2));set(def,a,"sget("+f+")");out.events.add(new Event(u,"sget v"+a+" <- "+f));}
                    else if(op>=0x67&&op<=0x6d&&u+1<units){int a=(cu>>8)&255;String f=field(u16(start+(u+1)*2));out.events.add(new Event(u,"sput "+f+" <- v"+a+"="+get(def,a)));}
                    else if(op>=0x7b&&op<=0x8f){int a=(cu>>8)&15,bb=(cu>>12)&15;String x=unaryName(op)+"("+get(def,bb)+")";set(def,a,x);out.events.add(new Event(u,unaryName(op)+" v"+a+" <- v"+bb+"="+get(def,bb)));}
                    else if(op>=0x90&&op<=0xaf&&u+1<units){int a=(cu>>8)&255,x=u16(start+(u+1)*2),bb=x&255,cc=(x>>8)&255;String ex=binaryName(op)+"("+get(def,bb)+","+get(def,cc)+")";set(def,a,ex);out.events.add(new Event(u,binaryName(op)+" v"+a+" <- v"+bb+",v"+cc));}
                    else if(op>=0xb0&&op<=0xcf){int a=(cu>>8)&15,bb=(cu>>12)&15;String ex=binary2Name(op)+"("+get(def,a)+","+get(def,bb)+")";set(def,a,ex);out.events.add(new Event(u,binary2Name(op)+" v"+a+" <- v"+a+",v"+bb));}
                    else if((op==0x2b||op==0x2c)&&u+2<units){parseSwitch(out,start,u,op);}
                    else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units){String m=method(u16(start+(u+1)*2));int[]rr=invokeRegs(cu,op,start,u);String[]snap=new String[rr.length];for(int k=0;k<rr.length;k++)snap[k]=get(def,rr[k]);out.calls.add(new Call(u,m,rr,snap));out.events.add(new Event(u,"invoke "+m+" regs="+regs(rr)+" defs="+defs(snap)));last=m;}
                }return out;
            }
            void parseSwitch(Body out,int start,int u,int op){try{int rel=readI32Units(start,u+1),pu=u+rel,pp=start+pu*2;if(!range(pp,4))return;int ident=u16(pp),n=u16(pp+2);if(n<0||n>4096)return;if(op==0x2b&&ident==0x0100&&range(pp+4,4L+n*4L)){int first=i32(pp+4);for(int i=0;i<n;i++){int tr=i32(pp+8+i*4);out.switches.add(new SwitchCase(first+i,u+tr));}out.events.add(new Event(u,"packed-switch size="+n+" payload=@"+pu));}else if(op==0x2c&&ident==0x0200&&range(pp+4,8L*n)){int kp=pp+4,tp=pp+4+n*4;for(int i=0;i<n;i++){int key=i32(kp+i*4),tr=i32(tp+i*4);out.switches.add(new SwitchCase(key,u+tr));}out.events.add(new Event(u,"sparse-switch size="+n+" payload=@"+pu));}}catch(Throwable ignored){}}
            int[]invokeRegs(int cu,int op,int start,int u){if(op>=0x74&&op<=0x78){int n=(cu>>8)&255,first=u16(start+(u+2)*2);int[]a=new int[n];for(int i=0;i<n;i++)a[i]=first+i;return a;}int n=(cu>>12)&15,g=(cu>>8)&15,x=u16(start+(u+2)*2);int[]all={x&15,(x>>4)&15,(x>>8)&15,(x>>12)&15,g};int[]a=new int[Math.min(n,5)];System.arraycopy(all,0,a,0,a.length);return a;}
            String interfaces(int off){if(off<=0||!range(off,4))return"";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));}return s.toString();}
            String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return"field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
            String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return"method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
            String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return"(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
            String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return"?";return safe(str(i32(typesOff+idx*4)));}
            String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[]q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+3000);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
            int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
            int uleb(int[]pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}
            int i32(int p){if(!range(p,4))return-1;return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
            int u16(int p){if(!range(p,2))return-1;return(b[p]&255)|((b[p+1]&255)<<8);}
            boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        }

        static boolean containsIgnore(Set<String>s,String needle){String n=needle.toLowerCase(Locale.ROOT);for(String x:s)if(x!=null&&x.toLowerCase(Locale.ROOT).contains(n))return true;return false;}
        static boolean containsCall(Body z,String needle){for(Call c:z.calls)if(c.m.contains(needle))return true;return false;}
        static String callNames(Body z){StringBuilder b=new StringBuilder();for(Call c:z.calls){if(b.length()>0)b.append(" | ");b.append('@').append(c.off).append(' ').append(c.m);}return b.toString();}
        static String window(List<Event>es,int target,int radius){StringBuilder b=new StringBuilder();int n=0;for(Event e:es){if(e.off<target-5||e.off>target+radius)continue;if(n++>80)break;if(b.length()>0)b.append(" || ");b.append('@').append(e.off).append(' ').append(e.d);}return shorten(b.toString(),7000);}
        static String filterEvents(List<Event>es,String[]need,int max){StringBuilder b=new StringBuilder();int n=0;for(Event e:es){boolean hit=false;for(String q:need)if(e.d.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT))){hit=true;break;}if(!hit)continue;if(n++>=max)break;if(b.length()>0)b.append(" || ");b.append('@').append(e.off).append(' ').append(e.d);}return shorten(b.toString(),7000);}
        static String switches(List<SwitchCase>xs){StringBuilder b=new StringBuilder();int n=0;for(SwitchCase x:xs){if(n++>=300){b.append("…");break;}if(b.length()>0)b.append(',');b.append(x.key).append("->@").append(x.target);}return b.toString();}
        static String join(Set<String>s){StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(" | ");b.append(x);}return b.toString();}
        static String nums(Set<Integer>ns){StringBuilder b=new StringBuilder();for(int v:ns){if(b.length()>0)b.append(',');b.append(v);float f=Float.intBitsToFloat(v);if(Float.isFinite(f)&&Math.abs(f)>=0.00001f&&Math.abs(f)<=20f)b.append("(f=").append(f).append(')');}return b.toString();}
        static String regs(int[]a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(',');b.append('v').append(a[i]);}return b.append(']').toString();}
        static String defs(String[]a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(" | ");b.append(i).append('=').append(shorten(a[i],180));}return b.append(']').toString();}
        static String constDesc(int v){float f=Float.intBitsToFloat(v);if(Float.isFinite(f)&&Math.abs(f)>=0.00001f&&Math.abs(f)<=1000f)return"const("+v+",floatBits="+f+")";return"const("+v+")";}
        static String unaryName(int op){String[]n={"neg-int","not-int","neg-long","not-long","neg-float","neg-double","int-to-long","int-to-float","int-to-double","long-to-int","long-to-float","long-to-double","float-to-int","float-to-long","float-to-double","double-to-int","double-to-long","double-to-float","int-to-byte","int-to-char","int-to-short"};int i=op-0x7b;return i>=0&&i<n.length?n[i]:"unary-"+op;}
        static String binaryName(int op){String[]base={"add-int","sub-int","mul-int","div-int","rem-int","and-int","or-int","xor-int","shl-int","shr-int","ushr-int","add-long","sub-long","mul-long","div-long","rem-long","and-long","or-long","xor-long","shl-long","shr-long","ushr-long","add-float","sub-float","mul-float","div-float","rem-float","add-double","sub-double","mul-double","div-double","rem-double"};int i=op-0x90;return i>=0&&i<base.length?base[i]:"bin-"+op;}
        static String binary2Name(int op){return "2addr-"+Integer.toHexString(op);}
        static void set(String[]a,int i,String v){if(i>=0&&i<a.length)a[i]=v;}
        static String get(String[]a,int i){return i>=0&&i<a.length&&a[i]!=null?a[i]:"?";}
        static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
        static byte[]readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>96*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    }
}
