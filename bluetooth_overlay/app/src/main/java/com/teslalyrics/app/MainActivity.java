package com.teslalyrics.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String TESLA_URL="https://hanz316.github.io/rtcapp/car.html";
    private static final String BUILD_MARKER="CONTROL7";
    private final AppState state=AppState.get();
    private LinearLayout content;
    private TextView homeStatus,rtcStatus,diag,logs,permissionStatus,controlStatus;
    private final BroadcastReceiver rx=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        state.log.add("BUILD "+BUILD_MARKER);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        startCore();
    }

    @Override protected void onStart(){super.onStart();registerUiReceiver();refresh();}

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUiReceiver(){
        IntentFilter f=new IntentFilter(LyricsService.ACTION_UI);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);
    }

    @Override protected void onResume(){super.onResume();if(hasMediaAccess())send(LyricsService.ACTION_SCAN);refresh();}
    @Override protected void onStop(){try{unregisterReceiver(rx);}catch(Exception ignored){}super.onStop();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);root.setBackgroundColor(Color.rgb(15,15,17));
        root.addView(txt("TESLA LYRICS · "+BUILD_MARKER,24,true));
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.addView(tab("首页",0));tabs.addView(tab("诊断",1));root.addView(tabs);
        ScrollView sv=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);sv.addView(content);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);showPage(0);
    }

    private Button tab(String s,int p){Button b=button(s);b.setOnClickListener(v->showPage(p));return b;}
    private void showPage(int p){content.removeAllViews();if(p==0)home();else diagnosticsPage();refresh();}

    private void home(){
        homeStatus=txt("",18,false);content.addView(homeStatus);permissionStatus=txt("",15,false);content.addView(permissionStatus);
        rowButton("开启通知使用权（只用于读取播放器）",this::openMediaAccess);
        LinearLayout r=row();r.addView(make("启动服务",this::startCore));r.addView(make("重新扫描播放器",()->send(LyricsService.ACTION_SCAN)));r.addView(make("立即重新同步",()->send(LyricsService.ACTION_RESYNC)));content.addView(r);
        content.addView(txt("网易云控制接口",18,true));controlStatus=txt("",15,false);content.addView(controlStatus);
        content.addView(txt("WSS/MQTT 连接",18,true));rtcStatus=txt("",15,false);content.addView(rtcStatus);
        content.addView(txt("Tesla 浏览器打开：\n"+TESLA_URL+"\n\n通信：安全 WSS/MQTT。歌词/进度/控制不经过 ntfy。",15,false));
    }

    private void diagnosticsPage(){
        rowButton("刷新诊断",this::refresh);
        rowButton("复制全部诊断",this::copyDiagnostics);
        rowButton("重新连接 CMAPI / UCar",()->{NeteaseControlLab.reconnect(this);Toast.makeText(this,"正在重新连接",Toast.LENGTH_SHORT).show();});
        rowButton("重新扫描网易云控制接口",()->{NeteaseControlLab.rescan(this);Toast.makeText(this,"正在重新扫描",Toast.LENGTH_SHORT).show();});
        rowButton("清空歌词缓存",()->{new LyricsDb(this).clearAll();Toast.makeText(this,"缓存已清空",Toast.LENGTH_SHORT).show();});
        rowButton("立即重新同步",()->send(LyricsService.ACTION_RESYNC));
        rowButton("停止服务",()->send(LyricsService.ACTION_STOP));
        diag=txt("",15,false);logs=txt("",13,false);content.addView(txt("诊断",18,true));content.addView(diag);content.addView(txt("最近事件",18,true));content.addView(logs);
    }

    private void copyDiagnostics(){
        String all="Build: "+BUILD_MARKER+"\n"+state.diagnostics()+"\n\n"+NeteaseControlLab.status()+"\n\n"+WebRtcBridge.statusReport()+"\n\n最近事件\n"+String.join("\n",state.log.snapshot());
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm!=null){cm.setPrimaryClip(ClipData.newPlainText("TeslaLyrics diagnostics",all));Toast.makeText(this,"全部诊断已复制",Toast.LENGTH_SHORT).show();}
        else Toast.makeText(this,"无法访问剪贴板",Toast.LENGTH_SHORT).show();
    }

    private void openMediaAccess(){try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}
    private boolean hasMediaAccess(){
        ComponentName c=new ComponentName(this,MediaAccessService.class);
        if(Build.VERSION.SDK_INT>=27){NotificationManager nm=getSystemService(NotificationManager.class);return nm!=null&&nm.isNotificationListenerAccessGranted(c);}
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");return enabled!=null&&enabled.contains(getPackageName());
    }
    private void startCore(){send(LyricsService.ACTION_START);}
    private void send(String a){startForegroundService(new Intent(this,LyricsService.class).setAction(a));}

    @SuppressLint("SetTextI18n")
    private void refresh(){
        TrackMetadata t=state.trackCopy();boolean access=hasMediaAccess();
        if(homeStatus!=null)homeStatus.setText("版本："+BUILD_MARKER+"\n服务："+state.diagnostics().split("\\n")[0].replace("Service: ","")+"\n播放器："+(state.mediaConnected()?MediaSessionMonitor.friendlyName(state.playerPackage()):"等待连接")+"\n\n当前：\n"+(t.title.isEmpty()?"等待手机播放器播放":t.title)+"\n"+t.artist);
        if(permissionStatus!=null)permissionStatus.setText("通知使用权："+(access?"已开启":"未开启")+(access?"\n已可读取手机播放器 MediaSession":"\n请开启，否则无法读取当前歌曲和进度"));
        if(controlStatus!=null)controlStatus.setText(NeteaseControlLab.status());
        if(rtcStatus!=null)rtcStatus.setText(WebRtcBridge.statusReport());
        if(diag!=null)diag.setText("Build: "+BUILD_MARKER+"\n"+state.diagnostics()+"\n\n"+NeteaseControlLab.status()+"\n\n"+WebRtcBridge.statusReport());
        if(logs!=null)logs.setText(String.join("\n",state.log.snapshot()));
    }

    private void rowButton(String s,Runnable r){content.addView(make(s,r));}
    private Button make(String s,Runnable r){Button b=button(s);b.setOnClickListener(v->r.run());return b;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private TextView txt(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(0,10,0,10);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
}
