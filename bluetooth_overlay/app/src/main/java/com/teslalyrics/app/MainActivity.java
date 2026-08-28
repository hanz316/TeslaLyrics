package com.teslalyrics.app;

import android.Manifest;
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
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private static final String TESLA_URL="https://hanz316.github.io/lyrics/";
    private final AppState state=AppState.get();
    private LinearLayout content;
    private TextView homeStatus,diag,logs,permissionStatus,pairInfo;
    private final BroadcastReceiver rx=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        // Generate once locally. It is used as an unguessable ntfy topic suffix and never committed to GitHub.
        RelayConfig.token(this);
        buildUi();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        startCore();
    }

    @Override protected void onStart(){
        super.onStart();
        IntentFilter f=new IntentFilter(LyricsService.ACTION_UI);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);
        refresh();
    }

    @Override protected void onResume(){
        super.onResume();
        if(hasMediaAccess())send(LyricsService.ACTION_SCAN);
        refresh();
    }

    @Override protected void onStop(){try{unregisterReceiver(rx);}catch(Exception ignored){}super.onStop();}

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(24,20,24,20);
        root.setBackgroundColor(Color.rgb(15,15,17));
        root.addView(txt("TESLA LYRICS",24,true));
        LinearLayout tabs=new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.addView(tab("首页",0));
        tabs.addView(tab("诊断",1));
        root.addView(tabs);
        ScrollView sv=new ScrollView(this);
        content=new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        sv.addView(content);
        root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        showPage(0);
    }

    private Button tab(String s,int p){Button b=button(s);b.setOnClickListener(v->showPage(p));return b;}
    private void showPage(int p){content.removeAllViews();if(p==0)home();else diagnosticsPage();refresh();}

    private void home(){
        homeStatus=txt("",18,false);content.addView(homeStatus);
        permissionStatus=txt("",15,false);content.addView(permissionStatus);
        rowButton("开启通知使用权（只用于读取播放器）",this::openMediaAccess);
        LinearLayout r=row();
        r.addView(make("启动服务",this::startCore));
        r.addView(make("重新扫描播放器",()->send(LyricsService.ACTION_SCAN)));
        r.addView(make("立即重新同步",()->send(LyricsService.ACTION_RESYNC)));
        content.addView(r);

        content.addView(txt("车机安全配对",18,true));
        pairInfo=txt("",18,true);content.addView(pairInfo);
        rowButton("复制配对码",this::copyPairCode);

        content.addView(txt("使用方法",18,true));
        content.addView(txt(
                "1. 手机连接 Tesla 蓝牙并播放音乐\n"+
                "2. Tesla 音源选择 Bluetooth\n"+
                "3. Tesla 连接手机热点\n"+
                "4. Tesla 浏览器打开并收藏：\n"+TESLA_URL+"\n"+
                "5. 第一次打开时，输入手机首页显示的 12 位配对码\n\n"+
                "配对码只保存在你的手机和车机浏览器里。以后直接打开收藏网址即可，不需要再次输入。\n\n"+
                "歌词延迟请直接在车机左下角齿轮里调整；车机会自己记住设置。\n\n"+
                "支持网易云音乐、Android Apple Music、QQ音乐、Spotify、YouTube Music。",15,false));
    }

    private void diagnosticsPage(){
        rowButton("清空歌词缓存",()->{new LyricsDb(this).clearAll();Toast.makeText(this,"缓存已清空",Toast.LENGTH_SHORT).show();});
        rowButton("立即重新同步",()->send(LyricsService.ACTION_RESYNC));
        rowButton("停止服务",()->send(LyricsService.ACTION_STOP));
        diag=txt("",15,false);logs=txt("",13,false);
        content.addView(txt("诊断",18,true));content.addView(diag);
        content.addView(txt("最近事件",18,true));content.addView(logs);
    }

    private void copyPairCode(){
        String code=RelayConfig.pairCode(this);
        ClipboardManager cm=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
        if(cm!=null)cm.setPrimaryClip(ClipData.newPlainText("Tesla Lyrics 配对码",code));
        Toast.makeText(this,"配对码已复制",Toast.LENGTH_SHORT).show();
    }

    private void openMediaAccess(){
        try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}
        catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}
    }

    private boolean hasMediaAccess(){
        ComponentName c=new ComponentName(this,MediaAccessService.class);
        if(Build.VERSION.SDK_INT>=27){
            NotificationManager nm=getSystemService(NotificationManager.class);
            return nm!=null&&nm.isNotificationListenerAccessGranted(c);
        }
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");
        return enabled!=null&&enabled.contains(getPackageName());
    }

    private void startCore(){send(LyricsService.ACTION_START);}
    private void send(String a){Intent i=new Intent(this,LyricsService.class).setAction(a);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}

    private void refresh(){
        TrackMetadata t=state.trackCopy();
        boolean access=hasMediaAccess();
        if(homeStatus!=null)homeStatus.setText(
                "服务："+state.diagnostics().split("\\n")[0].replace("Service: ","")+"\n"+
                "播放器："+(state.mediaConnected()?MediaSessionMonitor.friendlyName(state.playerPackage()):"等待连接")+"\n\n"+
                "当前：\n"+(t.title.isEmpty()?"等待手机播放器播放":t.title)+"\n"+t.artist+"\n\n"+
                "固定车机网址：\n"+TESLA_URL);
        if(permissionStatus!=null)permissionStatus.setText(
                "通知使用权："+(access?"已开启":"未开启")+
                (access?"\n已可读取手机播放器 MediaSession":"\n请开启，否则无法读取当前歌曲和进度"));
        if(pairInfo!=null)pairInfo.setText("配对码：  "+RelayConfig.pairCode(this));
        if(diag!=null)diag.setText(state.diagnostics());
        if(logs!=null)logs.setText(String.join("\n",state.log.snapshot()));
    }

    private void rowButton(String s,Runnable r){content.addView(make(s,r));}
    private Button make(String s,Runnable r){Button b=button(s);b.setOnClickListener(v->r.run());return b;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private TextView txt(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(0,10,0,10);if(bold)t.setTypeface(null,1);return t;}
}
