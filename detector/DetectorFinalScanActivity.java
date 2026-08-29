package com.teslalyrics.detector;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.widget.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * FINALSCAN is a one-shot diagnostic build.
 * It performs: manifest/component inventory, read-only DEX analysis of all known SepTrack/PlayController/
 * MediaSession/UCar/CMAPI routes, plus safe bind-only probes that only read Binder descriptors.
 * It NEVER sends 102/103, never sends unknown Binder transactions, never changes SepTrack state or vocal volume.
 */
public class DetectorFinalScanActivity extends Activity {
    static final String BUILD="FINALSCAN";
    static final String NCM="com.netease.cloudmusic";
    static final Log LOG=new Log(12000);
    static final AtomicBoolean RUNNING=new AtomicBoolean(false);
    TextView out; Button go;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); int p=dp(14); root.setPadding(p,p,p,p);
        TextView t=new TextView(this); t.setText("Tesla Lyrics Detector · FINALSCAN\n一次扫描全部剩余控制路线"); t.setTextSize(18); root.addView(t);
        TextView h=new TextView(this); h.setText("不需要播放，不需要动随心唱。\n静态全量 + 安全 Binder 连接测试；不发送 102/103。预计 5–15 分钟。"); root.addView(h);
        go=new Button(this); go.setText("一键最终扫描"); root.addView(go);
        Button ex=new Button(this); ex.setText("导出完整 TXT"); root.addView(ex);
        ScrollView sv=new ScrollView(this); out=new TextView(this); out.setTextSize(9.8f); out.setTextIsSelectable(true); sv.addView(out); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1)); setContentView(root);
        LOG.add("BUILD "+BUILD); LOG.add("SAFETY: no 102/103; no unknown transact; no SepTrack write; bind probes only read interface descriptor"); refresh();
        go.setOnClickListener(v->startScan()); ex.setOnClickListener(v->{String x=exportTxt(); Toast.makeText(this,x==null?"导出失败":"已导出: "+x,Toast.LENGTH_LONG).show();});
    }

    void startScan(){
        if(!RUNNING.compareAndSet(false,true)){Toast.makeText(this,"正在扫描",Toast.LENGTH_SHORT).show();return;}
        go.setEnabled(false); LOG.add("FINAL START");
        new Thread(()->{
            try{new Inspector(this).run();}catch(Throwable e){LOG.add("FINAL ERROR "+e.getClass().getSimpleName()+": "+safe(e.getMessage()));}
            finally{RUNNING.set(false);String x=exportTxt();LOG.add("FINAL AUTO_EXPORT "+safe(x));runOnUiThread(()->{go.setEnabled(true);refresh();});}
        },"finalscan").start();
        out.post(new Runnable(){public void run(){refresh();if(RUNNING.get())out.postDelayed(this,1000);}});
    }

    String exportTxt(){
        String name="NetEase-FINALSCAN-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: FINALSCAN\nTime: "+new Date()+"\n\n"+LOG.text();
        try{ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(u==null)return null;try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)return null;os.write(body.getBytes(StandardCharsets.UTF_8));}return "Downloads/TeslaLyricsDetector/"+name;}catch(Throwable e){LOG.add("EXPORT ERROR "+e);return null;}
    }
    void refresh(){out.setText(LOG.text());}
    int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);} static String safe(String x){return x==null?"":x;}

    static final class Log{final int cap;final Deque<String>q=new ArrayDeque<>();Log(int c){cap=c;}synchronized void add(String s){q.addLast(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);while(q.size()>cap)q.removeFirst();}synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}}

    static final class Inspector{
        final Activity a; final PackageManager pm; final Map<String,Comp> comps=new LinkedHashMap<>(); final Set<String> exportedOwners=new HashSet<>();
        final Result r=new Result();
        final String[] FOCUS={
                "SepTrack","随心唱","audioSepTrackVoiceVolume","setMusicVoiceVolume","sendMessageByPlayerHandler","sendMessageToService",
                "PlayController","PlayControllCallbackObject","MediaSessionCallbackParam","BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK",
                "onCustomAction","onCommand","ACTION_UCAR_CONTROL_AIDL","UCarService","CMApiService","HiCar","ContinuationService",
                "SmartDeviceBrowerActivity","MainProcessPlayService","PlayService","PlayRpcUtil","RPCPlayManager","MediaBrowser"
        };
        final String[] OWNER_FOCUS={
                "Ljo0/f;","Lsp0/x;","Lfm0/n0;","Lfm0/p0;","Lyp0/","Lfp0/r","Lqd/a;",
                "Lcom/netease/cloudmusic/service/PlayService","Lcom/netease/cloudmusic/service/MainProcessPlayService",
                "Lcom/netease/cloudmusic/module/ucar/UCarService","Lcom/netease/cloudmusic/module/hicar/",
                "Lcom/netease/cloudmusic/third/api/","Lue2/","Lxe2/","Lyj0/","Lre0/",
                "Lcom/netease/cloudmusic/module/player/meta/SepTrackSwitchData;","SepTrackEntrancePlugin","VolumeView",
                "Lcom/netease/cloudmusic/aidl/","SmartDeviceBrowerActivity"
        };
        Inspector(Activity x){a=x;pm=x.getPackageManager();}

        void run()throws Exception{
            PackageInfo pi=pm.getPackageInfo(NCM,PackageManager.GET_SERVICES|PackageManager.GET_RECEIVERS|PackageManager.GET_ACTIVITIES|PackageManager.GET_PROVIDERS|PackageManager.MATCH_DISABLED_COMPONENTS);
            ApplicationInfo ai=pi.applicationInfo; LOG.add("PKG version="+pi.versionName+" code="+pi.getLongVersionCode()+" source="+(ai==null?"":ai.sourceDir));
            inventory(pi);
            LOG.add("===== FINAL SAFE BIND PROBES =====");
            probe("PlayService", "com.netease.cloudmusic.service.PlayService", null, false);
            probe("MainProcessPlayService", "com.netease.cloudmusic.service.MainProcessPlayService", null, false);
            probe("UCar-AIDL", "com.netease.cloudmusic.module.ucar.UCarService", "ACTION_UCAR_CONTROL_AIDL", true);
            probe("UCar-MediaBrowser", "com.netease.cloudmusic.module.ucar.UCarService", "android.media.browse.MediaBrowserService", true);
            probe("UCar-blank", "com.netease.cloudmusic.module.ucar.UCarService", null, true);
            probe("CMAPI", "com.netease.cloudmusic.third.api.CMApiService", null, true);
            probe("Continuation", "com.netease.cloudmusic.hop.ContinuationService", null, true);
            probe("HiCarHonor", "com.netease.cloudmusic.module.hicar.HiCarHonorMediaOperateMgr", null, true);
            probe("HiCar", "com.netease.cloudmusic.module.hicar.HiCarMediaOperateMgr", null, true);
            List<String> apks=new ArrayList<>();if(ai!=null&&ai.sourceDir!=null)apks.add(ai.sourceDir);if(ai!=null&&ai.splitSourceDirs!=null)Collections.addAll(apks,ai.splitSourceDirs);
            LOG.add("===== FINAL STATIC PASS ====="); for(String p:apks)scanApk(p);
            emit(); summary();
            LOG.add("FINAL DONE dex="+r.dex+" methods="+r.methods+" classes="+r.classes+" focus="+r.focus.size()+" external="+r.external.size()+" callers="+r.callers.size()+" bindSites="+r.bindSites.size());
        }

        void inventory(PackageInfo pi){
            add(pi.services,"SERVICE");add(pi.receivers,"RECEIVER");add(pi.activities,"ACTIVITY");if(pi.providers!=null)for(ProviderInfo x:pi.providers){Comp c=new Comp("PROVIDER",x.name,x.exported,x.readPermission!=null?x.readPermission:x.writePermission,x.processName);put(c);}
            LOG.add("===== FINAL COMPONENTS =====");
            for(Comp c:comps.values())if(c.exported||interestingName(c.name))LOG.add("COMP "+c.kind+" "+c.name+" exported="+c.exported+" perm="+safe(c.perm)+" process="+safe(c.process));
        }
        void add(ComponentInfo[] xs,String k){if(xs==null)return;for(ComponentInfo x:xs){String perm=x instanceof ServiceInfo?((ServiceInfo)x).permission:x instanceof ActivityInfo?((ActivityInfo)x).permission:null;put(new Comp(k,x.name,x.exported,perm,x.processName));}}
        void put(Comp c){comps.put(desc(c.name),c);if(c.exported)exportedOwners.add(desc(c.name));}
        boolean interestingName(String n){if(n==null)return false;for(String q:new String[]{"PlayService","UCar","HiCar","CMApi","Continuation","SmartDevice","Media","Player"})if(n.contains(q))return true;return false;}
        String desc(String n){if(n==null)return"";if(n.startsWith("."))n=NCM+n;return"L"+n.replace('.','/')+";";}

        void probe(String label,String cls,String action,boolean autoCreate){
            CountDownLatch latch=new CountDownLatch(1); final ServiceConnection[] box=new ServiceConnection[1];
            ServiceConnection sc=new ServiceConnection(){public void onServiceConnected(ComponentName n,IBinder b){try{LOG.add("BIND "+label+" CONNECT component="+n+" binder="+(b==null?"null":b.getClass().getName())+" descriptor="+(b==null?"":safe(b.getInterfaceDescriptor())));}catch(Throwable e){LOG.add("BIND "+label+" CONNECT descriptorError="+e);}finally{latch.countDown();}}public void onServiceDisconnected(ComponentName n){LOG.add("BIND "+label+" DISCONNECTED "+n);}public void onNullBinding(ComponentName n){LOG.add("BIND "+label+" NULL_BINDING "+n);latch.countDown();}public void onBindingDied(ComponentName n){LOG.add("BIND "+label+" BINDING_DIED "+n);latch.countDown();}};box[0]=sc;
            a.runOnUiThread(()->{try{Intent i=new Intent();i.setComponent(new ComponentName(NCM,cls));if(action!=null)i.setAction(action);boolean ok=a.bindService(i,sc,autoCreate?Context.BIND_AUTO_CREATE:0);LOG.add("BIND "+label+" requested="+ok+" action="+safe(action));if(!ok)latch.countDown();}catch(Throwable e){LOG.add("BIND "+label+" ERROR "+e.getClass().getSimpleName()+": "+safe(e.getMessage()));latch.countDown();}});
            try{latch.await(2500,TimeUnit.MILLISECONDS);}catch(InterruptedException ignored){}
            a.runOnUiThread(()->{try{a.unbindService(sc);}catch(Throwable ignored){}});
        }

        void scanApk(String apk)throws Exception{try(ZipFile z=new ZipFile(apk)){Enumeration<? extends ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipEntry e=en.nextElement();if(!e.getName().matches("classes(\\d*)\\.dex"))continue;if(e.getSize()<=0||e.getSize()>96L*1024*1024)continue;r.dex++;new Dex(readAll(z.getInputStream(e))).scan();}}}
        void emit(){
            LOG.add("===== FINAL EXTERNAL COMPONENT ROUTES =====");for(String s:r.external)LOG.add(s);
            LOG.add("===== FINAL SEP/MEDIA/UCar/CMAPI/PLAY ROUTES =====");for(String s:r.focus)LOG.add(s);
            LOG.add("===== FINAL DIRECT CALLERS =====");for(String s:r.callers)LOG.add(s);
            LOG.add("===== FINAL BINDS =====");for(String s:r.bindSites)LOG.add(s);
        }
        void summary(){
            Comp ps=comps.get(desc("com.netease.cloudmusic.service.PlayService")),mps=comps.get(desc("com.netease.cloudmusic.service.MainProcessPlayService")),ucar=comps.get(desc("com.netease.cloudmusic.module.ucar.UCarService")),cm=comps.get(desc("com.netease.cloudmusic.third.api.CMApiService"));
            LOG.add("===== FINAL SUMMARY =====");
            LOG.add("SUMMARY directPlayServiceBind="+((ps!=null&&ps.exported)?"EXPORTED":"BLOCKED_BY_MANIFEST"));
            LOG.add("SUMMARY mainProcessPlayServiceBind="+((mps!=null&&mps.exported)?"EXPORTED":"BLOCKED_BY_MANIFEST"));
            LOG.add("SUMMARY UCar="+(ucar!=null&&ucar.exported?"EXPORTED":"NOT_EXPORTED")+" CMAPI="+(cm!=null&&cm.exported?"EXPORTED":"NOT_EXPORTED"));
            LOG.add("SUMMARY SepTrackExternalRoute="+(r.externalSep?"FOUND_IN_STATIC_SCAN":"NOT_FOUND_IN_STATIC_SCAN"));
            LOG.add("SUMMARY MediaSessionSepRoute="+(r.mediaSep?"FOUND_IN_STATIC_SCAN":"NOT_FOUND_IN_STATIC_SCAN"));
            LOG.add("SUMMARY UCarSepRoute="+(r.ucarSep?"FOUND_IN_STATIC_SCAN":"NOT_FOUND_IN_STATIC_SCAN"));
            LOG.add("SUMMARY CMAPISepRoute="+(r.cmapiSep?"FOUND_IN_STATIC_SCAN":"NOT_FOUND_IN_STATIC_SCAN"));
            LOG.add("SUMMARY knownInternalProtocol=switch:what102+SepTrackSwitchData; vocal:what103+Float");
            LOG.add("SUMMARY broadcastBridge=ALREADY_RUNTIME_VERIFIED_BY_PREVIOUS_TEST (onPause/onPlay, including lock-screen with foreground service)");
            LOG.add("SUMMARY NOTE: NOT_FOUND means no matching static route was observed in this installed NetEase build; it is not a proof that no undocumented route exists.");
        }

        final class Dex{
            final byte[] b; final int sN,sO,tN,tO,pN,pO,fN,fO,mN,mO,cN,cO;
            Dex(byte[]x){b=x;sN=i32(0x38);sO=i32(0x3c);tN=i32(0x40);tO=i32(0x44);pN=i32(0x48);pO=i32(0x4c);fN=i32(0x50);fO=i32(0x54);mN=i32(0x58);mO=i32(0x5c);cN=i32(0x60);cO=i32(0x64);}
            void scan(){if(b.length<0x70||!range(cO,(long)cN*32))return;for(int i=0;i<cN;i++){int cp=cO+i*32;if(!range(cp,32))break;String owner=type(i32(cp)),sup=type(i32(cp+8)),ifs=interfaces(i32(cp+12));r.classes++;int data=i32(cp+24);if(data>0&&data<b.length)scanClass(owner,sup,ifs,data);}}
            void scanClass(String owner,String sup,String ifs,int off){try{int[]p={off};int sf=uleb(p),inf=uleb(p),dm=uleb(p),vm=uleb(p);for(int i=0;i<sf+inf;i++){uleb(p);uleb(p);}scanMethods(owner,sup,ifs,p,dm);scanMethods(owner,sup,ifs,p,vm);}catch(Throwable ignored){}}
            void scanMethods(String owner,String sup,String ifs,int[]p,int n){int idx=0;for(int i=0;i<n;i++){idx+=uleb(p);int access=uleb(p),code=uleb(p);String sig=method(idx);r.methods++;if(code<=0)continue;Body z=body(code);inspect(owner,sup,ifs,sig,access,z);}}
            void inspect(String owner,String sup,String ifs,String sig,int access,Body z){
                boolean ownerHit=false;for(String q:OWNER_FOCUS)if(owner.contains(q)){ownerHit=true;break;}
                boolean textHit=hasFocus(sig)||hasFocus(join(z.strings))||hasFocus(join(z.calls))||hasFocus(join(z.types));
                boolean bind=containsCall(z,"->bindService(")||containsCall(z,"->bindServiceAsUser(");
                boolean targetCall=containsCall(z,"sendMessageByPlayerHandler")||containsCall(z,"->call(IIILcom/netease/cloudmusic/aidl/PlayControllCallbackObject")||containsCall(z,"BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK")||containsCall(z,"->sendSessionEvent(");
                if(ownerHit||textHit){String line=line("ROUTE",owner,sup,ifs,sig,access,z);addCap(r.focus,line,2200);}
                if(bind)addCap(r.bindSites,line("BIND_SITE",owner,sup,ifs,sig,access,z),500);
                if(targetCall)addCap(r.callers,line("CALLER",owner,sup,ifs,sig,access,z),900);
                Comp c=comps.get(owner);if(c!=null&&c.exported&&(textHit||targetCall)){String x=line("EXTERNAL",owner,sup,ifs,sig,access,z);addCap(r.external,x,900);String all=(sig+" "+join(z.strings)+" "+join(z.calls)).toLowerCase(Locale.ROOT);if(all.contains("septrack")||all.contains("随心唱")||all.contains("musicvoice"))r.externalSep=true;}
                String all=(owner+" "+sig+" "+join(z.strings)+" "+join(z.calls)).toLowerCase(Locale.ROOT);
                if((sig.contains("onCustomAction")||sig.contains("onCommand"))&&(all.contains("septrack")||all.contains("随心唱")||all.contains("karaoke")||all.contains("musicvoice")))r.mediaSep=true;
                if(owner.contains("UCar")||owner.startsWith("Lyj0/")||owner.startsWith("Lre0/")){if(all.contains("septrack")||all.contains("随心唱")||all.contains("musicvoice"))r.ucarSep=true;}
                if(owner.contains("CMApi")||owner.startsWith("Lue2/")||owner.startsWith("Lxe2/")){if(all.contains("septrack")||all.contains("随心唱")||all.contains("musicvoice"))r.cmapiSep=true;}
            }
            Body body(int off){Body o=new Body();if(!range(off,16))return o;int units=i32(off+12);if(units<=0||!range(off+16,(long)units*2))return o;int st=off+16;for(int u=0;u<units;u++){int cu=u16(st+u*2),op=cu&255;if(op==0x1a&&u+1<units){String s=str(u16(st+(u+1)*2));if(s!=null)o.strings.add(s);}else if(op==0x1b&&u+2<units){String s=str(readI32Units(st,u+1));if(s!=null)o.strings.add(s);}else if((op==0x1c||op==0x1f||op==0x22)&&u+1<units)o.types.add(type(u16(st+(u+1)*2)));else if(op>=0x52&&op<=0x6d&&u+1<units)o.fields.add(field(u16(st+(u+1)*2)));else if(((op>=0x6e&&op<=0x72)||(op>=0x74&&op<=0x78))&&u+2<units)o.calls.add("@"+u+" "+method(u16(st+(u+1)*2)));}return o;}
            String line(String tag,String owner,String sup,String ifs,String sig,int access,Body z){return tag+" "+sig+" owner="+owner+" super="+sup+" ifaces="+ifs+" access=0x"+Integer.toHexString(access)+" strings="+shorten(join(z.strings),1100)+" types="+shorten(join(z.types),900)+" fields="+shorten(join(z.fields),1000)+" calls="+shorten(join(z.calls),6500);}
            String interfaces(int off){if(off<=0||!range(off,4))return"";int n=i32(off),q=off+4;StringBuilder s=new StringBuilder();for(int i=0;i<n&&range(q+i*2,2);i++){if(s.length()>0)s.append(',');s.append(type(u16(q+i*2)));}return s.toString();}
            String field(int idx){if(idx<0||idx>=fN||!range(fO+idx*8,8))return"field#"+idx;int p=fO+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+":"+type(u16(p+2));}
            String method(int idx){if(idx<0||idx>=mN||!range(mO+idx*8,8))return"method#"+idx;int p=mO+idx*8;return type(u16(p))+"->"+safe(str(i32(p+4)))+proto(u16(p+2));}
            String proto(int idx){if(idx<0||idx>=pN||!range(pO+idx*12,12))return"(?)";int p=pO+idx*12,ret=i32(p+4),params=i32(p+8);StringBuilder s=new StringBuilder("(");if(params>0&&range(params,4)){int n=i32(params),q=params+4;for(int i=0;i<n&&range(q+i*2,2);i++)s.append(type(u16(q+i*2)));}return s.append(')').append(type(ret)).toString();}
            String type(int idx){if(idx<0||idx>=tN||!range(tO+idx*4,4))return"?";return safe(str(i32(tO+idx*4)));}
            String str(int idx){if(idx<0||idx>=sN)return null;int p=sO+idx*4;if(!range(p,4))return null;int off=i32(p);if(off<=0||off>=b.length)return null;int[]q={off};try{uleb(q);}catch(Throwable e){return null;}int s=q[0],e=s,max=Math.min(b.length,s+5000);while(e<max&&b[e]!=0)e++;if(e<=s||e>=max)return null;try{return new String(b,s,e-s,StandardCharsets.UTF_8);}catch(Throwable x){return null;}}
            int readI32Units(int st,int u){int p=st+u*2;return range(p,4)?u16(p)|(u16(p+2)<<16):0;}int uleb(int[]pp){int out=0,sh=0,p=pp[0];for(int i=0;i<5;i++){if(p>=b.length)throw new IllegalArgumentException();int v=b[p++]&255;out|=(v&127)<<sh;if((v&128)==0){pp[0]=p;return out;}sh+=7;}throw new IllegalArgumentException();}int i32(int p){if(!range(p,4))return-1;return(b[p]&255)|((b[p+1]&255)<<8)|((b[p+2]&255)<<16)|((b[p+3]&255)<<24);}int u16(int p){if(!range(p,2))return-1;return(b[p]&255)|((b[p+1]&255)<<8);}boolean range(int p,long n){return p>=0&&n>=0&&p+(long)n<=b.length;}
        }

        boolean hasFocus(String s){if(s==null)return false;String l=s.toLowerCase(Locale.ROOT);for(String q:FOCUS)if(l.contains(q.toLowerCase(Locale.ROOT)))return true;return false;}
        boolean containsCall(Body z,String s){for(String q:z.calls)if(q.contains(s))return true;return false;}
        String join(Collection<String> c){StringBuilder b=new StringBuilder();for(String s:c){if(b.length()>0)b.append(" | ");b.append(s);}return b.toString();}
        String shorten(String s,int n){return s==null?"":s.length()<=n?s:s.substring(0,n)+"…";}
        void addCap(List<String> l,String s,int cap){if(l.size()<cap)l.add(s);}
        byte[] readAll(InputStream in)throws Exception{try(InputStream x=in;ByteArrayOutputStream o=new ByteArrayOutputStream()){byte[]buf=new byte[32768];int n,total=0;while((n=x.read(buf))>0){total+=n;if(total>96*1024*1024)throw new IllegalStateException("dex too large");o.write(buf,0,n);}return o.toByteArray();}}
    }

    static final class Comp{final String kind,name;final boolean exported;final String perm,process;Comp(String k,String n,boolean e,String p,String pr){kind=k;name=n;exported=e;perm=p;process=pr;}}
    static final class Body{final Set<String>strings=new LinkedHashSet<>(),types=new LinkedHashSet<>(),fields=new LinkedHashSet<>();final List<String>calls=new ArrayList<>();}
    static final class Result{int dex,methods,classes;boolean externalSep,mediaSep,ucarSep,cmapiSep;final List<String>focus=new ArrayList<>(),external=new ArrayList<>(),callers=new ArrayList<>(),bindSites=new ArrayList<>();}
}
