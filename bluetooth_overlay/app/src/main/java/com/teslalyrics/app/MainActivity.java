package com.teslalyrics.app;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
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
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final AppState state=AppState.get();private SettingsStore settings;private LinearLayout content;private TextView homeStatus,diag,logs,globalLabel,trackLabel,permissionStatus;
    private final BroadcastReceiver rx=new BroadcastReceiver(){public void onReceive(Context c,Intent i){refresh();}};
    @Override public void onCreate(Bundle b){super.onCreate(b);settings=new SettingsStore(this);buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);startCore();}
    @Override protected void onStart(){super.onStart();IntentFilter f=new IntentFilter(LyricsService.ACTION_UI);if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);refresh();}
    @Override protected void onResume(){super.onResume();if(hasMediaAccess())send(LyricsService.ACTION_SCAN);refresh();}
    @Override protected void onStop(){try{unregisterReceiver(rx);}catch(Exception ignored){}super.onStop();}
    private void buildUi(){LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(24,20,24,20);root.setBackgroundColor(Color.rgb(15,15,17));root.addView(txt("TESLA LYRICS",24,true));LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);tabs.addView(tab("首页",0));tabs.addView(tab("歌词校准",1));tabs.addView(tab("诊断",2));root.addView(tabs);ScrollView sv=new ScrollView(this);content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);sv.addView(content);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));setContentView(root);showPage(0);}
    private Button tab(String s,int p){Button b=button(s);b.setOnClickListener(v->showPage(p));return b;}
    private void showPage(int p){content.removeAllViews();if(p==0)home();else if(p==1)calibration();else diagnosticsPage();refresh();}
    private void home(){homeStatus=txt("",18,false);content.addView(homeStatus);permissionStatus=txt("",15,false);content.addView(permissionStatus);rowButton("开启通知使用权（只用于读取播放器）",this::openMediaAccess);LinearLayout r=row();r.addView(make("启动服务",this::startCore));r.addView(make("重新扫描播放器",()->send(LyricsService.ACTION_SCAN)));r.addView(make("立即重新同步",()->send(LyricsService.ACTION_RESYNC)));content.addView(r);content.addView(txt("使用方法",18,true));content.addView(txt("1. 手机连接 Tesla 蓝牙\n2. 手机打开网易云音乐并播放\n3. Tesla 音源选择 Bluetooth\n4. Tesla 连接手机热点\n5. Tesla 浏览器打开并收藏：\nhttp://teslalyrics.local:8765\n\n如果 Tesla 不支持 .local，再使用首页显示的备用 IP 地址。\n\n支持网易云音乐、Android Apple Music、QQ音乐、Spotify、YouTube Music。",15,false));}
    private void calibration(){globalLabel=txt("",20,true);trackLabel=txt("",20,true);content.addView(globalLabel);offsetButtons(true);content.addView(seek(true));content.addView(trackLabel);offsetButtons(false);content.addView(seek(false));content.addView(txt("+ = 歌词更早出现；- = 歌词更晚出现。修改立即生效，不会重新加载歌曲。",14,false));}
    private void offsetButtons(boolean global){LinearLayout r=row();for(double d:new double[]{-1,-0.5,0,0.5,1}){String s=d==0?"归零":String.format(Locale.US,"%+.1fs",d);r.addView(make(s,()->{long cur=global?state.globalOffsetMs():state.trackOffsetMs();long v=d==0?0:cur+Math.round(d*1000);sendOffset(global,v);}));}content.addView(r);}
    private SeekBar seek(boolean global){SeekBar s=new SeekBar(this);s.setMax(600);long v=global?state.globalOffsetMs():state.trackOffsetMs();s.setProgress((int)(v/100)+300);s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int p,boolean from){if(from)sendOffset(global,(p-300)*100L);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});return s;}
    private void diagnosticsPage(){rowButton("清空歌词缓存",()->{new LyricsDb(this).clearAll();Toast.makeText(this,"缓存已清空",Toast.LENGTH_SHORT).show();});rowButton("停止服务",()->send(LyricsService.ACTION_STOP));diag=txt("",15,false);logs=txt("",13,false);content.addView(txt("诊断",18,true));content.addView(diag);content.addView(txt("最近事件",18,true));content.addView(logs);}
    private void openMediaAccess(){try{startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}}
    private boolean hasMediaAccess(){ComponentName c=new ComponentName(this,MediaAccessService.class);if(Build.VERSION.SDK_INT>=27){NotificationManager nm=getSystemService(NotificationManager.class);return nm!=null&&nm.isNotificationListenerAccessGranted(c);}String enabled=Settings.Secure.getString(getContentResolver(),"enabled_notification_listeners");return enabled!=null&&enabled.contains(getPackageName());}
    private void startCore(){send(LyricsService.ACTION_START);}
    private void send(String a){Intent i=new Intent(this,LyricsService.class).setAction(a);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}
    private void sendOffset(boolean g,long ms){Intent i=new Intent(this,LyricsService.class).setAction(g?LyricsService.ACTION_GLOBAL:LyricsService.ACTION_TRACK).putExtra("ms",ms);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);refresh();}
    private void refresh(){TrackMetadata t=state.trackCopy();boolean access=hasMediaAccess();if(homeStatus!=null)homeStatus.setText("服务："+state.diagnostics().split("\\n")[0].replace("Service: ","")+"\n播放器："+(state.mediaConnected()?MediaSessionMonitor.friendlyName(state.playerPackage()):"等待连接")+"\n\n当前：\n"+(t.title.isEmpty()?"等待网易云音乐播放":t.title)+"\n"+t.artist+"\n\n歌词同步偏移："+fmt(state.effectiveOffsetMs())+"\n\n固定车机地址：\nhttp://teslalyrics.local:8765\n\n备用 IP 地址：\nhttp://"+NetworkUtils.bestLanAddress()+":8765");if(permissionStatus!=null)permissionStatus.setText("通知使用权："+(access?"已开启":"未开启")+(access?"\n已可读取手机播放器 MediaSession":"\n请开启，否则无法读取网易云的歌名和进度"));if(globalLabel!=null)globalLabel.setText("Global Offset  "+fmt(state.globalOffsetMs()));if(trackLabel!=null)trackLabel.setText("Track Offset  "+fmt(state.trackOffsetMs()));if(diag!=null)diag.setText(state.diagnostics());if(logs!=null)logs.setText(String.join("\n",state.log.snapshot()));}
    private String fmt(long ms){return String.format(Locale.US,"%+.1f 秒",ms/1000.0);}
    private void rowButton(String s,Runnable r){content.addView(make(s,r));}
    private Button make(String s,Runnable r){Button b=button(s);b.setOnClickListener(v->r.run());return b;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setTextSize(14);return b;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;}
    private TextView txt(String s,int sp,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextColor(Color.WHITE);t.setTextSize(sp);t.setPadding(0,10,0,10);if(bold)t.setTypeface(null,1);return t;}
}
