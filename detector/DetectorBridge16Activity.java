package com.teslalyrics.detector;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * BRIDGE16 verifies that the already-proven NetEase broadcast bridge still works while the
 * phone is locked, when the sender remains runnable via a short foreground service + partial wakelock.
 * Only known reversible onPause/onPlay callbacks are sent. No SepTrack 102/103 or Binder transact.
 */
public class DetectorBridge16Activity extends Activity {
    static final String BUILD = "BRIDGE16";
    static final String NETEASE = "com.netease.cloudmusic";
    static final String ACTION = "BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK";
    static final LogBook LOG = new LogBook(1500);
    private TextView output;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14); root.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("Tesla Lyrics Detector · " + BUILD + "\n锁屏桥验证：Foreground Service + WakeLock");
        title.setTextSize(18f);
        root.addView(title,new LinearLayout.LayoutParams(-1,-2));

        TextView hint = new TextView(this);
        hint.setText("BRIDGE15 已证明亮屏时跨 App Broadcast 可控制网易云。\n这版只验证：发送端在锁屏时保持运行后，桥本身是否仍然有效。\n仍然只发已知 onPause/onPlay，不发随心唱 102/103。");
        hint.setTextSize(14f);
        root.addView(hint,new LinearLayout.LayoutParams(-1,-2));

        Button pause = new Button(this); pause.setText("亮屏确认：暂停网易云"); root.addView(pause,new LinearLayout.LayoutParams(-1,-2));
        Button play = new Button(this); play.setText("亮屏确认：继续播放网易云"); root.addView(play,new LinearLayout.LayoutParams(-1,-2));
        Button locked = new Button(this); locked.setText("锁屏验证：10 秒后由前台服务暂停"); root.addView(locked,new LinearLayout.LayoutParams(-1,-2));
        Button export = new Button(this); export.setText("导出测试日志 TXT"); root.addView(export,new LinearLayout.LayoutParams(-1,-2));

        ScrollView sv=new ScrollView(this); output=new TextView(this); output.setTextSize(11f); output.setTextIsSelectable(true); sv.addView(output); root.addView(sv,new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);

        LOG.add("BUILD "+BUILD);
        LOG.add("SAFE TEST: known onPause/onPlay only; lock test uses FGS + PARTIAL_WAKE_LOCK; no 102/103; no Binder transact");
        refresh();

        pause.setOnClickListener(v -> sendKnownCallback(this,"onPause","manual-pause"));
        play.setOnClickListener(v -> sendKnownCallback(this,"onPlay","manual-play"));
        locked.setOnClickListener(v -> {
            Intent i=new Intent(this,LockBridgeService.class); i.setAction("DELAY_PAUSE_10S");
            try {
                if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
                LOG.add("LOCK16 armed: foreground service started; lock screen now. It will hold a partial wakelock and send onPause after 10 seconds.");
                refresh();
                Toast.makeText(this,"前台服务已启动；现在锁屏，10 秒后应暂停",Toast.LENGTH_LONG).show();
            } catch(Throwable t){LOG.add("LOCK16 START_ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));refresh();}
        });
        export.setOnClickListener(v -> {String x=exportTxt();Toast.makeText(this,x==null?"导出失败":"已导出: "+x,Toast.LENGTH_LONG).show();});
    }

    static void sendKnownCallback(android.content.Context c,String callbackName,String source){
        Intent i=new Intent(ACTION); i.setPackage(NETEASE); i.putExtra("callbackName",callbackName);
        try { c.sendBroadcast(i); LOG.add("SEND_OK source="+source+" action="+ACTION+" package="+NETEASE+" callbackName="+callbackName); }
        catch(Throwable t){LOG.add("SEND_ERROR source="+source+" callbackName="+callbackName+" error="+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
    }

    public static class LockBridgeService extends Service {
        private static final String CH="tlx_bridge16";
        @Override public void onCreate(){super.onCreate();createChannel();startForeground(1616,notification("锁屏桥测试正在等待"));LOG.add("LOCK16 SERVICE onCreate foreground=true");}
        @Override public int onStartCommand(Intent intent,int flags,int startId){
            if(intent!=null&&"DELAY_PAUSE_10S".equals(intent.getAction())) new Thread(() -> runDelayed(startId),"bridge16-lock-test").start();
            return START_NOT_STICKY;
        }
        private void runDelayed(int startId){
            PowerManager.WakeLock wl=null;
            try {
                PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);
                if(pm!=null){wl=pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"TeslaLyricsDetector:Bridge16");wl.acquire(20_000L);LOG.add("LOCK16 WAKELOCK acquired for <=20s");}
                long begin=System.currentTimeMillis();
                try{Thread.sleep(10_000L);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                long elapsed=System.currentTimeMillis()-begin;
                LOG.add("LOCK16 TIMER fired elapsedMs="+elapsed+" interactive="+isInteractive());
                sendKnownCallback(this,"onPause","fgs-wakelock-10s");
                LOG.add("LOCK16 RESULT sent while interactive="+isInteractive()+"; observe whether NetEase paused before unlocking");
            } catch(Throwable t){LOG.add("LOCK16 ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));}
            finally {if(wl!=null&&wl.isHeld())try{wl.release();}catch(Throwable ignored){} stopSelf(startId);LOG.add("LOCK16 SERVICE stopped");}
        }
        private boolean isInteractive(){try{PowerManager pm=(PowerManager)getSystemService(POWER_SERVICE);return pm!=null&&pm.isInteractive();}catch(Throwable t){return false;}}
        private void createChannel(){if(Build.VERSION.SDK_INT>=26){NotificationManager nm=getSystemService(NotificationManager.class);if(nm!=null)nm.createNotificationChannel(new NotificationChannel(CH,"Tesla Lyrics Detector",NotificationManager.IMPORTANCE_LOW));}}
        private Notification notification(String text){if(Build.VERSION.SDK_INT>=26)return new Notification.Builder(this,CH).setContentTitle("Tesla Lyrics Detector").setContentText(text).setSmallIcon(android.R.drawable.ic_media_pause).setOngoing(true).build();return new Notification.Builder(this).setContentTitle("Tesla Lyrics Detector").setContentText(text).setSmallIcon(android.R.drawable.ic_media_pause).setOngoing(true).build();}
        @Override public IBinder onBind(Intent intent){return null;}
    }

    private String exportTxt(){
        String name="NetEase-"+BUILD+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: "+BUILD+"\nTime: "+new Date()+"\n\n"+LOG.text();
        try{ContentValues cv=new ContentValues();cv.put(MediaStore.Downloads.DISPLAY_NAME,name);cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);if(u==null)return null;try(OutputStream os=getContentResolver().openOutputStream(u)){if(os==null)return null;os.write(body.getBytes(StandardCharsets.UTF_8));}return "Downloads/TeslaLyricsDetector/"+name;}catch(Throwable t){LOG.add("EXPORT_ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));refresh();return null;}
    }
    private void refresh(){output.setText(LOG.text());}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    static String safe(String s){return s==null?"":s;}
    static final class LogBook{final int cap;final Deque<String> q=new ArrayDeque<>();LogBook(int c){cap=c;}synchronized void add(String s){q.addLast(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);while(q.size()>cap)q.removeFirst();}synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}}
}
