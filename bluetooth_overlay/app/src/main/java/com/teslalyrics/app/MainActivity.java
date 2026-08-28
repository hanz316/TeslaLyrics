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
        root.addView(txt("TESLA LYRICS · LAN BYPASS PROBE",24,true));
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.addView(tab("首页",0));tabs.addView(tab("诊断",1));root.addView(tabs);
        ScrollView sv=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);sv.addView(content);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);showPage(0);
    }

    private Button tab(String s,int p){Button b=button(s);b.setOnClickListener(v->showPage(p));return b;}
    private void showPage(int p){content.removeAllViews();if(p==0)home();else diagnosticsPage();refresh();}

    private void home(){
        homeStatus=txt("",18,false);content.addView(homeStatus);permissionStatus=txt("",15,false);content.addView(permissionStatus);
        rowButton("开启通知使用权（只用于读取播放器）",this::openMediaAccess);
        LinearLayout r=row();r.addView(make("启动服务",this::startCore));r.addView(make("重新扫描播放器",()->send(LyricsService.ACTION_SCAN)));r.addView(make("重新检测网络",this::refresh));content.addView(r);
        content.addView(txt("局域网直连验证",18,true));lanStatus=txt("",15,false);content.addView(lanStatus);
        content.addView(txt(
                "测试顺序：\n"+
                "1. 手机打开热点，Tesla 连接热点。\n"+
                "2. 优先测试【Global IPv6 域名】。这条地址从 Tesla 看不是 192.168/10.x 私网目标，但目标仍是手机本机。\n"+
                "3. 如果手机没有 Global IPv6，再测试【IPv4 域名】。\n"+
                "4. 最后才测试纯数字 IPv4。\n\n"+
                "如果 Global IPv6 页面出现 TESLA IPV6 LOCAL LINK OK，就说明我们找到了不经过公网 Relay、又能绕过 RFC1918 限制的方案。",15,false));
    }

    private void diagnosticsPage(){
        rowButton("清空歌词缓存",()->{new LyricsDb(this).clearAll();Toast.makeText(this,"缓存已清空",Toast.LENGTH_SHORT).show();});
        rowButton("重新扫描播放器",()->send(LyricsService.ACTION_SCAN));rowButton("停止服务",()->send(LyricsService.ACTION_STOP));
        diag=txt("",15,false);logs=txt("",13,false);content.addView(txt("诊断",18,true));content.addView(diag);content.addView(txt("最近事件",18,true));content.addView(logs);
    }

    private void openMediaAccess(){try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}
    private boolean hasMediaAccess(){
        ComponentName c=new ComponentName(this,MediaAccessService.class);
        if(Build.VERSION.SDK_INT>=27){NotificationManager nm=getSystemService(NotificationManager.class);return nm!=null&&nm.isNotificationListenerAccessGranted(c);}
        String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");return enabled!=null&&enabled.contains(getPackageName());
    }
    private void startCore(){send(LyricsService.ACTION_START);}
    private void send(String a){startForegroundService(new Intent(this,LyricsService.class).setAction(a));}

    private static String v4Alias(String ip,String domain,int port){
        if(ip==null||ip.isEmpty()||"0.0.0.0".equals(ip))return "等待热点 IPv4";
        return "http://"+ip.replace('.', '-')+"."+domain+":"+port+"/";
    }
    private static String v6Alias(String ip,int port){
        if(ip==null||ip.isEmpty())return "未检测到 Global IPv6";
        return "http://"+NetworkUtils.ipv6NipHost(ip)+":"+port+"/";
    }

    @SuppressLint("SetTextI18n")
    private void refresh(){
        TrackMetadata t=state.trackCopy();boolean access=hasMediaAccess();String ip=NetworkUtils.bestLanAddress(),v6=NetworkUtils.bestGlobalV6Address();
        if(homeStatus!=null)homeStatus.setText("服务："+state.diagnostics().split("\\n")[0].replace("Service: ","")+"\n播放器："+(state.mediaConnected()?MediaSessionMonitor.friendlyName(state.playerPackage()):"等待连接")+"\n\n当前：\n"+(t.title.isEmpty()?"等待手机播放器播放":t.title)+"\n"+t.artist);
        if(permissionStatus!=null)permissionStatus.setText("通知使用权："+(access?"已开启":"未开启")+(access?"\n已可读取手机播放器 MediaSession":"\n请开启，否则无法读取当前歌曲和进度"));
        if(lanStatus!=null)lanStatus.setText(
                Ipv6ProbeServer.statusReport()+"\n\n【优先测试：Global IPv6 域名】\n"+v6Alias(v6,8766)+"\n"+v6Alias(v6,8081)+
                "\n\n检测到的 Global IPv6：\n"+NetworkUtils.globalV6Report()+
                "\n\n【第二测试：IPv4 普通域名】\n"+v4Alias(ip,"nip.io",8765)+"\n"+v4Alias(ip,"sslip.io",8765)+
                "\n\n【IPv4 8080 备用】\n"+v4Alias(ip,"nip.io",8080)+"\n"+v4Alias(ip,"sslip.io",8080)+
                "\n\n【最后：纯数字 IPv4】\nhttp://"+ip+":8765/\nhttp://"+ip+":8080/\n\n"+LocalServer.statusReport());
        if(diag!=null)diag.setText(state.diagnostics()+"\n\n"+Ipv6ProbeServer.statusReport()+"\n\n"+LocalServer.statusReport()+"\n\nIPv6:\n"+NetworkUtils.globalV6Report()+"\n\nIPv4:\n"+NetworkUtils.candidateReport());
        if(logs!=null)logs.setText(String.join("\n",state.log.snapshot()));
    }

    private void rowButton(String s,Runnable r){content.addView(make(s,r));}
    private Button make(String s,Runnable r){Button b=button(s);b.setOnClickListener(v->r.run());return b;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private TextView txt(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(0,10,0,10);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
}
