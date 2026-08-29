package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
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
 * BRIDGE15 only sends known, reversible MediaSession callback broadcasts (onPause/onPlay).
 * It does NOT send SepTrack 102/103, Binder transacts, custom actions, or unknown commands.
 */
public class DetectorBridge15Activity extends Activity {
    private static final String BUILD = "BRIDGE15";
    private static final String NETEASE = "com.netease.cloudmusic";
    private static final String ACTION = "BROADCAST_ACTION_INVOKE_MEDIA_SESSION_CALLBACK";
    private static final LogBook LOG = new LogBook(1000);
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextView output;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14); root.setPadding(p,p,p,p);

        TextView title = new TextView(this);
        title.setText("Tesla Lyrics Detector · " + BUILD + "\n安全实测网易云跨 App Broadcast 控制桥");
        title.setTextSize(18f);
        root.addView(title, new LinearLayout.LayoutParams(-1,-2));

        TextView hint = new TextView(this);
        hint.setText("先让网易云正常播放音乐。\n只测试已知 onPause / onPlay；不会发送随心唱 102/103。");
        hint.setTextSize(14f);
        root.addView(hint, new LinearLayout.LayoutParams(-1,-2));

        Button pause = new Button(this);
        pause.setText("测试桥：暂停网易云");
        root.addView(pause, new LinearLayout.LayoutParams(-1,-2));

        Button play = new Button(this);
        play.setText("测试桥：继续播放网易云");
        root.addView(play, new LinearLayout.LayoutParams(-1,-2));

        Button delayed = new Button(this);
        delayed.setText("锁屏测试：10 秒后暂停");
        root.addView(delayed, new LinearLayout.LayoutParams(-1,-2));

        Button export = new Button(this);
        export.setText("导出测试日志 TXT");
        root.addView(export, new LinearLayout.LayoutParams(-1,-2));

        ScrollView sv = new ScrollView(this);
        output = new TextView(this);
        output.setTextSize(11f);
        output.setTextIsSelectable(true);
        sv.addView(output);
        root.addView(sv, new LinearLayout.LayoutParams(-1,0,1f));
        setContentView(root);

        LOG.add("BUILD " + BUILD);
        LOG.add("SAFE ACTIVE TEST: only targeted broadcast callbackName=onPause/onPlay; no 102/103; no Binder transact");
        refresh();

        pause.setOnClickListener(v -> sendKnownCallback("onPause", "manual-pause"));
        play.setOnClickListener(v -> sendKnownCallback("onPlay", "manual-play"));
        delayed.setOnClickListener(v -> {
            LOG.add("LOCK TEST armed: targeted onPause will be sent after 10 seconds. Lock screen now if desired.");
            refresh();
            Toast.makeText(this, "10 秒后发送暂停；现在可以锁屏", Toast.LENGTH_LONG).show();
            main.postDelayed(() -> sendKnownCallback("onPause", "delayed-10s"), 10_000L);
        });
        export.setOnClickListener(v -> {
            String x = exportTxt();
            Toast.makeText(this, x==null?"导出失败":"已导出: "+x, Toast.LENGTH_LONG).show();
        });
    }

    private void sendKnownCallback(String callbackName, String source) {
        Intent i = new Intent(ACTION);
        i.setPackage(NETEASE);
        i.putExtra("callbackName", callbackName);
        // No callbackParam is required for no-argument MediaSession callbacks.
        try {
            sendBroadcast(i);
            LOG.add("SEND_OK source="+source+" action="+ACTION+" package="+NETEASE+" callbackName="+callbackName);
            LOG.add("OBSERVE: check whether NetEase playback changed immediately.");
        } catch (Throwable t) {
            LOG.add("SEND_ERROR source="+source+" callbackName="+callbackName+" error="+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
        }
        refresh();
    }

    private String exportTxt() {
        String name="NetEase-"+BUILD+"-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        String body="Tesla Lyrics Detector\nBuild: "+BUILD+"\nTime: "+new Date()+"\n\n"+LOG.text();
        try {
            ContentValues cv=new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME,name);
            cv.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS+"/TeslaLyricsDetector");
            Uri u=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
            if(u==null)return null;
            try(OutputStream os=getContentResolver().openOutputStream(u)){
                if(os==null)return null;
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            return "Downloads/TeslaLyricsDetector/"+name;
        } catch(Throwable t) {
            LOG.add("EXPORT_ERROR "+t.getClass().getSimpleName()+": "+safe(t.getMessage()));
            refresh();
            return null;
        }
    }

    private void refresh(){ output.setText(LOG.text()); }
    private int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }
    private static String safe(String s){ return s==null?"":s; }

    static final class LogBook {
        final int cap; final Deque<String> q=new ArrayDeque<>();
        LogBook(int c){cap=c;}
        synchronized void add(String s){
            q.addLast(new SimpleDateFormat("HH:mm:ss",Locale.US).format(new Date())+"  "+s);
            while(q.size()>cap)q.removeFirst();
        }
        synchronized String text(){StringBuilder b=new StringBuilder();for(String s:q)b.append(s).append('\n');return b.toString();}
    }
}
