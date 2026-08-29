package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ComponentInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
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
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class DetectorMainActivity extends Activity {
    private static final String BUILD = "FULLSCAN12";
    private static final String NETEASE = "com.netease.cloudmusic";
    private static final LogBook LOG = new LogBook(8000);
    private TextView output;
    private Button scanButton;
    private final AtomicBoolean uiTicker = new AtomicBoolean(false);

    public static class SessionAccessService extends android.service.notification.NotificationListenerService {}

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setTitle("Tesla Lyrics Detector");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("Tesla Lyrics Detector · " + BUILD + "\n独立检测版，不会覆盖正式 Tesla Lyrics");
        title.setTextSize(18f);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        scanButton = new Button(this);
        scanButton.setText("一键完整扫描网易云");
        root.addView(scanButton, new LinearLayout.LayoutParams(-1,-2));

        Button media = new Button(this);
        media.setText("打开媒体读取权限（可选）");
        root.addView(media, new LinearLayout.LayoutParams(-1,-2));

        Button export = new Button(this);
        export.setText("导出完整 TXT");
        root.addView(export, new LinearLayout.LayoutParams(-1,-2));

        ScrollView scroll = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(11f);
        output.setTextIsSelectable(true);
        scroll.addView(output);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);

        LOG.add("BUILD " + BUILD);
        LOG.add("说明：静态扫描只读取网易云 APK/组件信息，不发送未知 Binder/MediaSession 命令。");
        refresh();

        scanButton.setOnClickListener(v -> runScan());
        media.setOnClickListener(v -> {
            try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
            catch (Throwable t) { startActivity(new Intent(Settings.ACTION_SETTINGS)); }
        });
        export.setOnClickListener(v -> {
            String path = exportTxt();
            Toast.makeText(this, path == null ? "导出失败" : "已导出: " + path, Toast.LENGTH_LONG).show();
        });
    }

    private void runScan() {
        if (!Inspector.RUNNING.compareAndSet(false,true)) {
            Toast.makeText(this,"扫描正在运行",Toast.LENGTH_SHORT).show();
            return;
        }
        scanButton.setEnabled(false);
        LOG.add("FULL12 START：目标方法 + 正式滑杆 + 4层反向调用链 + MediaSession/UCar/HiCar + exported 组件");
        startUiTicker();
        new Thread(() -> {
            try {
                scanPackageMetadata(this);
                scanLiveMediaSession(this);
                Inspector.scan(this, LOG);
            } catch (Throwable t) {
                LOG.add("FULL12 ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
            } finally {
                Inspector.RUNNING.set(false);
                String p = exportTxt();
                LOG.add("FULL12 AUTO_EXPORT " + safe(p));
                runOnUiThread(() -> { scanButton.setEnabled(true); refresh(); });
            }
        }, "netease-fullscan12").start();
    }

    private void startUiTicker() {
        if (!uiTicker.compareAndSet(false,true)) return;
        output.post(new Runnable() {
            @Override public void run() {
                refresh();
                if (Inspector.RUNNING.get()) output.postDelayed(this, 1200);
                else uiTicker.set(false);
            }
        });
    }

    private void scanPackageMetadata(Context c) {
        try {
            PackageManager pm = c.getPackageManager();
            int flags = PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES |
                    PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS |
                    PackageManager.GET_META_DATA | PackageManager.MATCH_DISABLED_COMPONENTS;
            PackageInfo pi = pm.getPackageInfo(NETEASE, flags);
            ApplicationInfo ai = pi.applicationInfo;
            LOG.add("PKG version=" + pi.versionName + " code=" + pi.getLongVersionCode() +
                    " uid=" + (ai==null ? "?" : ai.uid) + " source=" + (ai==null?"":ai.sourceDir));
            if (ai != null && ai.splitSourceDirs != null) LOG.add("PKG splits=" + ai.splitSourceDirs.length);
            dumpActivities("ACTIVITY", pi.activities);
            dumpServices(pi.services);
            dumpActivities("RECEIVER", pi.receivers);
            if (pi.providers != null) {
                for (ProviderInfo x : pi.providers) {
                    if (x != null && x.exported) LOG.add("EXPORT PROVIDER " + x.name + " perm=" + safe(x.readPermission) + "/" + safe(x.writePermission) + " process=" + safe(x.processName));
                }
            }
        } catch (Throwable t) {
            LOG.add("PKG ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private static void dumpActivities(String kind, ActivityInfo[] xs) {
        if (xs == null) return;
        for (ActivityInfo x : xs) {
            if (x != null && x.exported) LOG.add("EXPORT " + kind + " " + x.name + " perm=" + safe(x.permission) + " process=" + safe(x.processName));
        }
    }
    private static void dumpServices(ServiceInfo[] xs) {
        if (xs == null) return;
        for (ServiceInfo x : xs) {
            if (x != null && x.exported) LOG.add("EXPORT SERVICE " + x.name + " perm=" + safe(x.permission) + " process=" + safe(x.processName));
        }
    }

    private void scanLiveMediaSession(Context c) {
        try {
            ComponentName access = new ComponentName(c, SessionAccessService.class);
            String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            boolean ok = enabled != null && enabled.contains(getPackageName());
            LOG.add("LIVE MEDIA permission=" + ok);
            if (!ok) {
                LOG.add("LIVE MEDIA skipped：未开启检测版媒体读取权限；静态完整扫描仍会继续。");
                return;
            }
            MediaSessionManager mm = (MediaSessionManager)getSystemService(MEDIA_SESSION_SERVICE);
            List<MediaController> cs = mm.getActiveSessions(access);
            int found = 0;
            for (MediaController mc : cs) {
                if (!NETEASE.equals(mc.getPackageName())) continue;
                found++;
                MediaMetadata md = mc.getMetadata();
                PlaybackState ps = mc.getPlaybackState();
                LOG.add("LIVE MEDIA NetEase state=" + (ps==null?"null":ps.getState()) +
                        " title=" + (md==null?"":safe(md.getString(MediaMetadata.METADATA_KEY_TITLE))));
                if (ps != null) {
                    StringBuilder a = new StringBuilder();
                    for (PlaybackState.CustomAction ca : ps.getCustomActions()) {
                        if (a.length()>0) a.append(" | ");
                        a.append(ca.getName()).append('=').append(ca.getAction());
                    }
                    LOG.add("LIVE CUSTOM_ACTIONS " + a);
                }
            }
            LOG.add("LIVE MEDIA sessions=" + found);
        } catch (Throwable t) {
            LOG.add("LIVE MEDIA ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
        }
    }

    private String exportTxt() {
        String name = "NetEase-" + BUILD + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        String body = "Tesla Lyrics Detector\nBuild: " + BUILD + "\nTime: " + new Date() + "\n\n" + LOG.text();
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TeslaLyricsDetector");
            Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (u == null) return null;
            try (OutputStream os = getContentResolver().openOutputStream(u)) {
                if (os == null) return null;
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return "Downloads/TeslaLyricsDetector/" + name;
        } catch (Throwable t) {
            LOG.add("EXPORT ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
            return null;
        }
    }

    private void refresh() { output.setText(LOG.text()); }
    private int dp(int x) { return Math.round(x * getResources().getDisplayMetrics().density); }
    private static String safe(String s) { return s == null ? "" : s; }

    static final class LogBook {
        private final int cap;
        private final Deque<String> q = new ArrayDeque<>();
        LogBook(int c){cap=c;}
        synchronized void add(String s) {
            String t = new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date()) + "  " + s;
            q.addLast(t);
            while(q.size()>cap) q.removeFirst();
        }
        synchronized String text() {
            StringBuilder b=new StringBuilder();
            for(String s:q)b.append(s).append('\n');
            return b.toString();
        }
    }

    static final class Inspector {
        static final AtomicBoolean RUNNING = new AtomicBoolean(false);
        private static final String PKG = NETEASE;
        private static final String BUS = "Lcom/netease/cloudmusic/service/IPlayService;->sendMessageByPlayerHandler(IIILjava/lang/Object;)V";
        private static final String BUS_WRAP = "Lfm0/g;->sendMessageToService(IIILjava/lang/Object;)V";
        private static final String SW_N = "Ljo0/f;->N(ZZ)V";
        private static final String SW_O = "Ljo0/f;->O(ZZILjava/lang/Object;)V";
        private static final String SW_M = "Ljo0/f;->M(Z)V";
        private static final String VOL_B = "Ljo0/f;->b(F)V";
        private static final String VOL_L = "Ljo0/f;->L(F)V";
        private static final String PLAY_W = "Lxo0/w;->J(F)V";
        private static final String PLAY_L = "Lxo0/l;->J(F)V";
        private static final String SWITCH_OBJ = "Lcom/netease/cloudmusic/module/player/meta/SepTrackSwitchData;-><init>(ZZ)V";
        private static final int MAX_FOCUS = 500;
        private static LogBook log;

        static void scan(Context c, LogBook l) throws Exception {
            log = l;
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(PKG, 0);
            List<String> paths = new ArrayList<>();
            if (ai.sourceDir != null) paths.add(ai.sourceDir);
            if (ai.splitSourceDirs != null) Collections.addAll(paths, ai.splitSourceDirs);
            Set<String> exported = exportedDescriptors(pm);
            log.add("FULL12 APK paths=" + paths.size() + " exportedDescriptors=" + exported.size());

            Result r = new Result(exported);
            scanAll(paths, (n,d) -> new Dex(n,d,r,0,Collections.emptySet()).scan());
            emitPrimary(r);

            Set<String> frontier = new LinkedHashSet<>();
            frontier.add(SW_N); frontier.add(VOL_B); frontier.add(SW_O); frontier.add(SW_M);
            Set<String> seen = new LinkedHashSet<>(frontier);
            for (int depth=1; depth<=4; depth++) {
                r.currentDepth = depth;
                r.nextFrontier.clear();
                final Set<String> f = new HashSet<>(frontier);
                scanAll(paths, (n,d) -> new Dex(n,d,r,depth,f).scan());
                Set<String> next = new LinkedHashSet<>(r.nextFrontier);
                next.removeAll(seen);
                log.add("FULL12 REV depth=" + depth + " frontier=" + frontier.size() + " newCallers=" + next.size());
                seen.addAll(next);
                frontier = next;
                if (frontier.isEmpty()) break;
            }

            emitCandidates(r);
            log.add("FULL12 DONE dexPasses=" + r.dexPasses + " exactBodies=" + r.exactBodies +
                    " focusMethods=" + r.focus.size() + " reverseEdges=" + r.edgeTo.size() +
                    " entryCandidates=" + r.entryCandidates.size());
        }

        private static Set<String> exportedDescriptors(PackageManager pm) {
            Set<String> out = new HashSet<>();
            try {
                int flags = PackageManager.GET_ACTIVITIES|PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|
                        PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS;
                PackageInfo pi = pm.getPackageInfo(PKG, flags);
                addExport(out,pi.activities); addExport(out,pi.services); addExport(out,pi.receivers);
                if (pi.providers != null) for(ProviderInfo p:pi.providers) if(p!=null&&p.exported) out.add(desc(p.name));
            } catch(Throwable ignored){}
            return out;
        }
        private static void addExport(Set<String> out, ComponentInfo[] xs){ if(xs!=null)for(ComponentInfo x:xs)if(x!=null&&x.exported)out.add(desc(x.name));}
        private static String desc(String n){ if(n==null)return ""; if(n.startsWith("."))n=PKG+n; return "L"+n.replace('.','/')+";";}

        private interface DexConsumer{void accept(String name,byte[] data)throws Exception;}
        private static void scanAll(List<String> paths,DexConsumer c)throws Exception{
            for(String apk:paths){
                try(ZipFile z=new ZipFile(apk)){
                    java.util.Enumeration<? extends ZipEntry> en=z.entries();
                    while(en.hasMoreElements()){
                        ZipEntry e=en.nextElement();
                        if(!e.getName().matches("classes(\\d*)\\.dex"))continue;
                        if(e.getSize()<=0||e.getSize()>96L*1024L*1024L)continue;
                        c.accept(e.getName(),readAll(z.getInputStream(e)));
                    }
                }
            }
        }

        private static void emitPrimary(Result r){
            log.add("===== FULL12 PRIMARY FINDINGS =====");
            for(String s:r.primaryLines) log.add(s);
            log.add("PRIMARY directSwitch=" + r.directSwitch.size() + " directVolume=" + r.directVolume.size() +
                    " mediaRoutes=" + r.mediaRoutes.size() + " focus=" + r.focus.size());
            int n=0;
            for(MethodSummary m:r.mediaRoutes.values()){
                if(n++>=80)break;
                log.add("MEDIA_ROUTE " + m.sig + " class=" + m.classInfo + " strings=" + shorten(join(m.strings),420) +
                        " calls=" + shorten(join(m.calls),520) + " nums=" + nums(m.nums));
            }
            n=0;
            for(MethodSummary m:r.focus.values()){
                if(n++>=120)break;
                log.add("FOCUS " + m.sig + " class=" + m.classInfo + " strings=" + shorten(join(m.strings),380) +
                        " calls=" + shorten(join(m.calls),500) + " nums=" + nums(m.nums));
            }
        }

        private static void emitCandidates(Result r){
            log.add("===== FULL12 EXTERNAL PATH CANDIDATES =====");
            if(r.entryCandidates.isEmpty()) log.add("CANDIDATE none within 4 reverse-call layers");
            int n=0;
            for(String e:r.entryCandidates){
                if(n++>=120)break;
                StringBuilder p=new StringBuilder(e);
                String cur=e;
                Set<String> guard=new HashSet<>();
                guard.add(cur);
                for(int i=0;i<8;i++){
                    String to=r.edgeTo.get(cur);
                    if(to==null||!guard.add(to))break;
                    p.append(" -> ").append(to);
                    cur=to;
                }
                log.add("CANDIDATE " + p);
            }
            log.add("===== FULL12 ANSWER CHECKLIST =====");
            log.add("CHECK switch message expected: " + SW_N + " should expose BUS(102,0,0,SepTrackSwitchData)");
            log.add("CHECK volume transport: inspect TARGET_BODY " + VOL_B + " and CALLSITE lines for exact what/args/object");
            log.add("CHECK production slider: inspect SEP_UI/VolumeView focus methods and float annotations");
            log.add("CHECK external route: CANDIDATE lines + MEDIA_ROUTE onCommand/onCustomAction/UCar/HiCar + EXPORT component list");
        }

        static final class Result {
            final Set<String> exported;
            final List<String> primaryLines=new ArrayList<>();
            final Set<String> directSwitch=new LinkedHashSet<>();
            final Set<String> directVolume=new LinkedHashSet<>();
            final Map<String,MethodSummary> mediaRoutes=new LinkedHashMap<>();
            final Map<String,MethodSummary> focus=new LinkedHashMap<>();
            final Map<String,String> edgeTo=new LinkedHashMap<>();
            final Set<String> entryCandidates=new LinkedHashSet<>();
            final Set<String> nextFrontier=new LinkedHashSet<>();
            int dexPasses, exactBodies, currentDepth;
            Result(Set<String> e){exported=e;}
        }

        static final class MethodSummary {
            String sig,classInfo;
            final Set<String> strings=new LinkedHashSet<>();
            final Set<String> calls=new LinkedHashSet<>();
            final Set<Integer> nums=new LinkedHashSet<>();
        }

        static final class Dex {
            final String name; final byte[] b; final Result r; final int pass; final Set<String> frontier;
            final int stringsN,stringsOff,typesN,typesOff,protosN,protosOff,fieldsN,fieldsOff,methodsN,methodsOff,classesN,classesOff;
            Dex(String n,byte[] x,Result rr,int p,Set<String> f){
                name=n;b=x;r=rr;pass=p;frontier=f;
                stringsN=i32(0x38);stringsOff=i32(0x3c);typesN=i32(0x40);typesOff=i32(0x44);
                protosN=i32(0x48);protosOff=i32(0x4c);fieldsN=i32(0x50);fieldsOff=i32(0x54);
                methodsN=i32(0x58);methodsOff=i32(0x5c);classesN=i32(0x60);classesOff=i32(0x64);
            }
            void scan(){
                if(b.length<0x70||!range(classesOff,(long)classesN*32))return;
                r.dexPasses++;
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
                    if(pass==0) inspectPrimary(owner,sup,ifs,sig,z);
                    else inspectReverse(owner,sup,ifs,sig,z);
                }
            }
            void inspectPrimary(String owner,String sup,String ifs,String sig,Body z){
                boolean exact=isExact(sig);
                boolean sepUi=owner.contains("/ui/plugin/sep/track/")||owner.contains("AudioSepTrackSettingDemoActivity")||
                        owner.contains("SepTrackController")||owner.equals("Ljo0/f;")||owner.equals("Lcom/netease/cloudmusic/module/player/utils/j2;");
                boolean media=isMediaRoute(owner,sup,ifs,sig,z.strings);
                boolean callsSwitch=contains(z,SW_N)||contains(z,SW_O)||contains(z,SW_M);
                boolean callsVol=contains(z,VOL_B)||contains(z,VOL_L)||contains(z,PLAY_W)||contains(z,PLAY_L);
                if(callsSwitch)r.directSwitch.add(sig);
                if(callsVol)r.directVolume.add(sig);
                if(exact){
                    r.exactBodies++;
                    r.primaryLines.add("TARGET_BODY "+sig+" strings="+shorten(join(z.strings),500)+" calls="+shorten(joinCalls(z.invokes),900)+" nums="+nums(z.nums));
                    for(CallSite s:z.sites){
                        r.primaryLines.add("CALLSITE "+sig+" @"+s.off+" -> "+s.method+" regs="+regs(s.regs)+" defs="+defs(s.defs));
                    }
                }
                if(sepUi){
                    MethodSummary m=sum(sig,sup,ifs,z);
                    if(r.focus.size()<MAX_FOCUS)r.focus.put(sig,m);
                }
                if(media){
                    MethodSummary m=sum(sig,sup,ifs,z);
                    if(r.mediaRoutes.size()<MAX_FOCUS)r.mediaRoutes.put(sig,m);
                }
                if((callsSwitch||callsVol) && (entryLike(owner,sup,ifs,sig,z.strings,r.exported))){
                    r.primaryLines.add("DIRECT_ENTRY "+sig+" -> "+(callsSwitch?"SWITCH ":"")+(callsVol?"VOLUME ":"")+"class="+classInfo(sup,ifs));
                }
            }
            void inspectReverse(String owner,String sup,String ifs,String sig,Body z){
                String hit=null;
                for(InvokeRec q:z.invokes) if(frontier.contains(q.method)){hit=q.method;break;}
                if(hit==null)return;
                if(!r.edgeTo.containsKey(sig))r.edgeTo.put(sig,hit);
                r.nextFrontier.add(sig);
                boolean entry=entryLike(owner,sup,ifs,sig,z.strings,r.exported);
                if(entry)r.entryCandidates.add(sig);
                if(entry || r.currentDepth<=2){
                    log.add("REV"+r.currentDepth+(entry?" ENTRY":"")+" "+sig+" -> "+hit+" class="+classInfo(sup,ifs)+
                            " strings="+shorten(join(z.strings),420));
                }
            }
            MethodSummary sum(String sig,String sup,String ifs,Body z){
                MethodSummary m=new MethodSummary();m.sig=sig;m.classInfo=classInfo(sup,ifs);
                m.strings.addAll(z.strings);for(InvokeRec q:z.invokes)m.calls.add(q.method);m.nums.addAll(z.nums);return m;
            }
            boolean isExact(String s){
                return s.equals(SW_N)||s.equals(SW_O)||s.equals(SW_M)||s.equals(VOL_B)||s.equals(VOL_L)||
                        s.equals(PLAY_W)||s.equals(PLAY_L)||s.equals(BUS_WRAP)||
                        s.contains("SepTrackEntrancePlugin$volumeChangeListener$1;->onChange(FZ)V")||
                        s.contains("/ui/plugin/sep/track/VolumeView;->");
            }
            boolean isMediaRoute(String owner,String sup,String ifs,String sig,Set<String> ss){
                String x=(owner+" "+sup+" "+ifs+" "+sig).toLowerCase(Locale.ROOT);
                if(x.contains("oncustomaction(")||x.contains("oncommand(")||x.contains("sendcustomevent")||
                        x.contains("mediasession")||x.contains("mediabrowser")||owner.startsWith("Lre0/"))return true;
                for(String s:ss){
                    String y=s.toLowerCase(Locale.ROOT);
                    if(y.contains("ucar.media.")||y.contains("hicar.media.")||y.contains("custom_action")||
                            y.contains("septrack")||y.contains("随心唱"))return true;
                }
                return false;
            }
            Body body(int codeOff,boolean isStatic){
                Body out=new Body();if(!range(codeOff,16))return out;
                int regN=u16(codeOff),insN=u16(codeOff+2),units=i32(codeOff+12);if(units<=0||!range(codeOff+16,(long)units*2))return out;
                String[] def=new String[Math.max(0,regN)];int p0=regN-insN;
                for(int i=0;i<insN&&p0+i>=0&&p0+i<def.length;i++)def[p0+i]="p"+i;
                int start=codeOff+16;String lastInvoke="";
                for(int u=0;u<units;u++){
                    int cu=u16(start+u*2),op=cu&255;
                    if(op==0x12){int a=(cu>>8)&15,v=(cu>>12)&15;if((v&8)!=0)v|=~15;set(def,a,constDesc(v));out.nums.add(v);}
                    else if(op==0x13&&u+1<units){int a=(cu>>8)&255,v=(short)u16(start+(u+1)*2);set(def,a,constDesc(v));out.nums.add(v);}
                    else if(op==0x14&&u+2<units){int a=(cu>>8)&255,v=readI32Units(start,u+1);set(def,a,constDesc(v));out.nums.add(v);}
                    else if(op==0x15&&u+1<units){int a=(cu>>8)&255,v=((short)u16(start+(u+1)*2))<<16;set(def,a,constDesc(v));out.nums.add(v);}
                    else if(op==0x01||op==0x04||op==0x07){int a=(cu>>8)&15,bb=(cu>>12)&15;set(def,a,get(def,bb));}
                    else if((op==0x02||op==0x05||op==0x08)&&u+1<units){int a=(cu>>8)&255,bb=u16(start+(u+1)*2);set(def,a,get(def,bb));}
                    else if((op==0x03||op==0x06||op==0x09)&&u+2<units){int a=u16(start+(u+1)*2),bb=u16(start+(u+2)*2);set(def,a,get(def,bb));}
                    else if(op==0x0a||op==0x0b||op==0x0c){int a=(cu>>8)&255;set(def,a,"result("+shorten(lastInvoke,150)+")");}
                    else if(op==0x1a&&u+1<units){int a=(cu>>8)&255;String s=str(u16(start+(u+1)*2));set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);}
                    else if(op==0x1b&&u+2<units){int a=(cu>>8)&255;String s=str(readI32Units(start,u+1));set(def,a,"str("+shorten(s,80)+")");if(s!=null)out.strings.add(s);}
                    else if(op==0x22&&u+1<units){int a=(cu>>8)&255;String t=type(u16(start+(u+1)*2));set(def,a,"new("+t+")");}
                    else if(op>=0x60&&op<=0x66&&u+1<units){int a=(cu>>8)&255;set(def,a,"sget("+field(u16(start+(u+1)*2))+")");}
                    else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units){
                        String m=method(u16(start+(u+1)*2));int[] rr=invokeRegs(cu,op,start,u);
                        out.invokes.add(new InvokeRec(u,m,rr));
                        String[] snap=new String[rr.length];for(int k=0;k<rr.length;k++)snap[k]=get(def,rr[k]);
                        out.sites.add(new CallSite(u,m,rr,snap));lastInvoke=m;
                    }
                }
                return out;
            }
            int[] invokeRegs(int cu,int op,int start,int u){
                if(op>=0x74&&op<=0x78){int n=(cu>>8)&255,first=u16(start+(u+2)*2);int[] a=new int[n];for(int i=0;i<n;i++)a[i]=first+i;return a;}
                int n=(cu>>12)&15,g=(cu>>8)&15,x=u16(start+(u+2)*2);int[] all={x&15,(x>>4)&15,(x>>8)&15,(x>>12)&15,g};
                int[] a=new int[Math.min(n,5)];System.arraycopy(all,0,a,0,a.length);return a;
            }
            boolean contains(Body z,String t){for(InvokeRec q:z.invokes)if(t.equals(q.method))return true;return false;}
            String interfaces(int off){if(off<=0||!range(off,4))return "";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));if(s.length()>300)break;}return s.toString();}
            String field(int idx){if(idx<0||idx>=fieldsN||!range(fieldsOff+idx*8,8))return "field#"+idx;int p=fieldsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
            String method(int idx){if(idx<0||idx>=methodsN||!range(methodsOff+idx*8,8))return "method#"+idx;int p=methodsOff+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
            String proto(int idx){if(idx<0||idx>=protosN||!range(protosOff+idx*12,12))return "(?)";int p=protosOff+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
            String type(int idx){if(idx<0||idx>=typesN||!range(typesOff+idx*4,4))return "?";return safe(str(i32(typesOff+idx*4)));}
            String str(int idx){if(idx<0||idx>=stringsN)return null;int p=stringsOff+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[] q={off};try{uleb(q);}catch(Throwable t){return null;}int s=q[0],e=s,max=Math.min(b.length,s+2000);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable t){return null;}}
            int readI32Units(int start,int unit){int p=start+unit*2;if(!range(p,4))return 0;return u16(p)|(u16(p+2)<<16);}
            int uleb(int[] pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}
            int i32(int p){if(!range(p,4))return -1;return (b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}
            int u16(int p){if(!range(p,2))return -1;return (b[p]&255)|((b[p+1]&255)<<8);}
            boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        }

        static boolean entryLike(String owner,String sup,String ifs,String sig,Set<String> ss,Set<String> exported){
            if(exported.contains(owner))return true;
            String x=(owner+" "+sup+" "+ifs+" "+sig).toLowerCase(Locale.ROOT);
            if(x.contains("broadcastreceiver")||x.contains("mediasession")||x.contains("mediabrowser")||
                    x.contains("onbind(")||x.contains("onreceive(")||x.contains("onstartcommand(")||
                    x.contains("oncommand(")||x.contains("oncustomaction("))return true;
            for(String s:ss){String y=s.toLowerCase(Locale.ROOT);if(y.contains("ucar.media.")||y.contains("hicar.media.")||y.contains("action_"))return true;}
            return false;
        }
        static String constDesc(int v){
            float f=Float.intBitsToFloat(v);
            if(Float.isFinite(f)&&Math.abs(f)>=0.0001f&&Math.abs(f)<=1000f)return "const("+v+",floatBits="+f+")";
            return "const("+v+")";
        }
        static String classInfo(String sup,String ifs){return "super="+shorten(sup,120)+" ifaces="+shorten(ifs,180);}
        static String join(Set<String> s){StringBuilder b=new StringBuilder();for(String x:s){if(b.length()>0)b.append(" | ");b.append(x);}return b.toString();}
        static String joinCalls(List<InvokeRec> xs){StringBuilder b=new StringBuilder();for(InvokeRec x:xs){if(b.length()>0)b.append(" | ");b.append('@').append(x.off).append(' ').append(x.method);}return b.toString();}
        static String nums(Set<Integer> ns){StringBuilder b=new StringBuilder();for(int v:ns){if(b.length()>0)b.append(',');b.append(v);float f=Float.intBitsToFloat(v);if(Float.isFinite(f)&&Math.abs(f)>=0.0001f&&Math.abs(f)<=10f)b.append("(f=").append(f).append(')');}return b.toString();}
        static String regs(int[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(',');b.append('v').append(a[i]);}return b.append(']').toString();}
        static String defs(String[] a){StringBuilder b=new StringBuilder("[");for(int i=0;i<a.length;i++){if(i>0)b.append(" | ");b.append(i).append('=').append(shorten(a[i],220));}return b.append(']').toString();}
        static void set(String[] a,int i,String v){if(i>=0&&i<a.length)a[i]=v;}
        static String get(String[] a,int i){return i>=0&&i<a.length&&a[i]!=null?a[i]:"?";}
        static String shorten(String s,int n){return s==null?"":(s.length()<=n?s:s.substring(0,n)+"…");}
        static String safe(String s){return s==null?"":s;}
        static byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[] buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>96*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}

        static final class Body{final Set<String> strings=new LinkedHashSet<>();final Set<Integer> nums=new LinkedHashSet<>();final List<InvokeRec> invokes=new ArrayList<>();final List<CallSite> sites=new ArrayList<>();}
        static final class InvokeRec{final int off;final String method;final int[] regs;InvokeRec(int o,String m,int[] r){off=o;method=m==null?"":m;regs=r;}}
        static final class CallSite{final int off;final String method;final int[] regs;final String[] defs;CallSite(int o,String m,int[] r,String[] d){off=o;method=m;regs=r;defs=d;}}
    }
}
