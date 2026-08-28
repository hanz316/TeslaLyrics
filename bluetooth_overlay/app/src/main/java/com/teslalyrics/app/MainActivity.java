package com.teslalyrics.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
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
    private final AppState state=AppState.get();
    private LinearLayout content;
    private TextView homeStatus,lanStatus,diag,logs,permissionStatus;
    private final BroadcastReceiver rx=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        buildUi();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        startCore();
    }

    @Override protected void onStart(){
        super.onStart();
        registerUiReceiver();
        refresh();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUiReceiver(){
        IntentFilter f=new IntentFilter(LyricsService.ACTION_UI);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rx,f);
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
        root.addView(txt("TESLA LYRICS · LAN PROBE",24,true));
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
        r.addView(make("重新检测局域网",this::refresh));
        content.addView(r);

        content.addView(txt("局域网直连验证",18,true));
        lanStatus=txt("",15,false);content.addView(lanStatus);
        content.addView(txt(
                "优先测试【域名地址】。它把手机热点私有 IP 包装成普通域名，Tesla 不需要直接输入纯数字 IP。\n\n"+
                "测试顺序：\n"+
                "1. 打开手机热点\n"+
                "2. 让 Tesla 连接这个热点\n"+
                "3. 在 Tesla 浏览器打开 nip.io 域名地址\n"+
                "4. 不通再试 sslip.io 域名地址\n"+
                "5. 最后才测试纯数字 IP\n\n"+
                "这些域名只是 DNS 映射：浏览器最终仍直接连接手机局域网地址，网页流量不会经过 nip.io/sslip.io 服务器。\n\n"+
                "成功页面会显示：TESLA LOCAL LINK OK，并继续自动测试 WebSocket。",15,false));
    }

    private void diagnosticsPage(){
        rowButton("清空歌词缓存",()->{new LyricsDb(this).clearAll();Toast.makeText(this,"缓存已清空",Toast.LENGTH_SHORT).show();});
        rowButton("重新扫描播放器",()->send(LyricsService.ACTION_SCAN));
        rowButton("停止服务",()->send(LyricsService.ACTION_STOP));
        diag=txt("",15,false);logs=txt("",13,false);
        content.addView(txt("诊断",18,true));content.addView(diag);
        content.addView(txt("最近事件",18,true));content.addView(logs);
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
    private void send(String a){startForegroundService(new Intent(this,LyricsService.class).setAction(a));}

    private static String hostnameAlias(String ip,String domain,int port){
        if(ip==null||ip.isEmpty()||"0.0.0.0".equals(ip))return "等待热点地址";
        return "http://"+ip.replace('.', '-')+"."+domain+":"+port+"/";
    }

    @SuppressLint("SetTextI18n")
    private void refresh(){
        TrackMetadata t=state.trackCopy();
        boolean access=hasMediaAccess();
        String ip=NetworkUtils.bestLanAddress();
        if(homeStatus!=null)homeStatus.setText(
                "服务："+state.diagnostics().split("\\n")[0].replace("Service: ","")+"\n"+
                "播放器："+(state.mediaConnected()?MediaSessionMonitor.friendlyName(state.playerPackage()):"等待连接")+"\n\n"+
                "当前：\n"+(t.title.isEmpty()?"等待手机播放器播放":t.title)+"\n"+t.artist);
        if(permissionStatus!=null)permissionStatus.setText(
                "通知使用权："+(access?"已开启":"未开启")+
                (access?"\n已可读取手机播放器 MediaSession":"\n请开启，否则无法读取当前歌曲和进度"));
        if(lanStatus!=null)lanStatus.setText(
                LocalServer.statusReport()+
                "\n\n【优先：普通域名，不是纯数字 IP】\n"+
                hostnameAlias(ip,"nip.io",8765)+"\n"+
                hostnameAlias(ip,"sslip.io",8765)+
                "\n\n【8080 备用域名】\n"+
                hostnameAlias(ip,"nip.io",8080)+"\n"+
                hostnameAlias(ip,"sslip.io",8080)+
                "\n\n【最后测试：纯数字地址】\nhttp://"+ip+":8765/\nhttp://"+ip+":8080/"+
                "\n\n检测到的局域网接口：\n"+NetworkUtils.candidateReport());
        if(diag!=null)diag.setText(state.diagnostics()+"\n\n"+LocalServer.statusReport()+"\n\nInterfaces:\n"+NetworkUtils.candidateReport());
        if(logs!=null)logs.setText(String.join("\n",state.log.snapshot()));
    }

    private void rowButton(String s,Runnable r){content.addView(make(s,r));}
    private Button make(String s,Runnable r){Button b=button(s);b.setOnClickListener(v->r.run());return b;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private TextView txt(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(0,10,0,10);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
}
