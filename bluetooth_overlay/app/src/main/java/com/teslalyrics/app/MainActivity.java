package com.teslalyrics.app;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final String TESLA_URL="https://hanz316.github.io/rtcapp/car.html";

    private static final int BG0=Color.rgb(7,9,14);
    private static final int BG1=Color.rgb(15,18,30);
    private static final int CARD=Color.rgb(22,27,41);
    private static final int CARD_ALT=Color.rgb(28,34,51);
    private static final int WHITE=Color.rgb(246,248,252);
    private static final int MUTED=Color.rgb(143,153,174);
    private static final int ACCENT=Color.rgb(139,92,246);
    private static final int CYAN=Color.rgb(34,211,238);
    private static final int GREEN=Color.rgb(52,211,153);
    private static final int RED=Color.rgb(255,92,108);
    private static final int YELLOW=Color.rgb(251,191,36);

    private final AppState state=AppState.get();
    private final Handler main=new Handler(Looper.getMainLooper());

    private LinearLayout page,advancedBox;
    private TextView serviceChip,mediaChip,carChip,titleView,artistView,lyricsChip,statusView,timeView,diagView,logView,permissionHint,advancedToggle;
    private ProgressBar progress;
    private View connectionDot;
    private ObjectAnimator pulse;
    private boolean advancedVisible=false;
    private String lastTrackKey="";

    private final BroadcastReceiver rx=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent i){refresh();}
    };

    private final Runnable clockTick=new Runnable(){
        @Override public void run(){
            updateProgressOnly();
            main.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(BG0);
        getWindow().setNavigationBarColor(BG0);
        buildUi();
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);
        }
        startCore();
    }

    @Override protected void onStart(){
        super.onStart();
        registerUiReceiver();
        main.removeCallbacks(clockTick);
        main.post(clockTick);
        refresh();
    }

    @Override protected void onResume(){
        super.onResume();
        if(hasMediaAccess())send(LyricsService.ACTION_SCAN);
        refresh();
    }

    @Override protected void onStop(){
        main.removeCallbacks(clockTick);
        try{unregisterReceiver(rx);}catch(Exception ignored){}
        super.onStop();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUiReceiver(){
        IntentFilter f=new IntentFilter(LyricsService.ACTION_UI);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(rx,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(rx,f);
    }

    private void buildUi(){
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackground(gradient(new int[]{BG0,BG1,Color.rgb(8,11,18)}));

        page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20),dp(24),dp(20),dp(48));
        scroll.addView(page,new ScrollView.LayoutParams(-1,-2));
        setContentView(scroll);

        TextView brand=text("TESLA LYRICS",12,ACCENT,true);
        brand.setLetterSpacing(.18f);
        page.addView(brand);

        TextView hero=text("车机歌词控制中心",30,WHITE,true);
        hero.setPadding(0,dp(5),0,0);
        page.addView(hero);

        TextView subtitle=text("手机负责识别歌曲与同步歌词 · Tesla 浏览器负责显示",14,MUTED,false);
        subtitle.setPadding(0,dp(5),0,dp(18));
        page.addView(subtitle);

        LinearLayout statusCard=card(true);
        LinearLayout statusHead=new LinearLayout(this);
        statusHead.setOrientation(LinearLayout.HORIZONTAL);
        statusHead.setGravity(Gravity.CENTER_VERTICAL);
        connectionDot=new View(this);
        connectionDot.setBackground(circle(GREEN));
        statusHead.addView(connectionDot,new LinearLayout.LayoutParams(dp(10),dp(10)));
        TextView statusTitle=text("  系统状态",17,WHITE,true);
        statusHead.addView(statusTitle,new LinearLayout.LayoutParams(0,-2,1));
        TextView version=text("v"+BuildConfig.VERSION_NAME,12,MUTED,false);
        statusHead.addView(version);
        statusCard.addView(statusHead);

        LinearLayout chips=new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0,dp(14),0,0);
        serviceChip=statusChip("服务");
        mediaChip=statusChip("播放器");
        carChip=statusChip("车机");
        chips.addView(serviceChip,weightParams(1,0));
        chips.addView(space(dp(8)));
        chips.addView(mediaChip,weightParams(1,0));
        chips.addView(space(dp(8)));
        chips.addView(carChip,weightParams(1,0));
        statusCard.addView(chips);
        page.addView(statusCard,marginBottom(dp(14)));

        LinearLayout now=card(false);
        TextView nowLabel=text("NOW PLAYING",11,CYAN,true);
        nowLabel.setLetterSpacing(.14f);
        now.addView(nowLabel);
        titleView=text("等待播放器",25,WHITE,true);
        titleView.setPadding(0,dp(9),0,0);
        titleView.setMaxLines(2);
        now.addView(titleView);
        artistView=text("开启播放器后会自动识别",15,MUTED,false);
        artistView.setPadding(0,dp(5),0,dp(14));
        now.addView(artistView);

        LinearLayout lyricRow=new LinearLayout(this);
        lyricRow.setOrientation(LinearLayout.HORIZONTAL);
        lyricRow.setGravity(Gravity.CENTER_VERTICAL);
        lyricsChip=chip("歌词：等待匹配",ACCENT);
        lyricRow.addView(lyricsChip,new LinearLayout.LayoutParams(0,-2,1));
        statusView=text("",12,MUTED,false);
        statusView.setGravity(Gravity.END);
        lyricRow.addView(statusView);
        now.addView(lyricRow);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setProgressTintList(ColorStateList.valueOf(CYAN));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(47,55,74)));
        LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,dp(4));
        pp.setMargins(0,dp(16),0,dp(8));
        now.addView(progress,pp);
        timeView=text("0:00 / 0:00",12,MUTED,false);
        timeView.setGravity(Gravity.END);
        now.addView(timeView);
        page.addView(now,marginBottom(dp(20)));

        page.addView(sectionTitle("快捷操作"));
        LinearLayout row1=actionRow();
        row1.addView(action("↻","立即同步","重新推送状态与歌词",()->send(LyricsService.ACTION_RESYNC)),weightParams(1,0));
        row1.addView(space(dp(10)));
        row1.addView(action("◎","扫描播放器","重新读取 MediaSession",()->send(LyricsService.ACTION_SCAN)),weightParams(1,0));
        page.addView(row1,marginBottom(dp(10)));

        LinearLayout row2=actionRow();
        row2.addView(action("↗","车机网址","复制浏览器地址",this::copyTeslaUrl),weightParams(1,0));
        row2.addView(space(dp(10)));
        row2.addView(action("✓","读取权限","管理通知使用权",this::openMediaAccess),weightParams(1,0));
        page.addView(row2,marginBottom(dp(20)));

        permissionHint=text("",13,MUTED,false);
        permissionHint.setPadding(dp(14),dp(12),dp(14),dp(12));
        permissionHint.setBackground(roundRect(Color.rgb(20,25,38),dp(14),Color.rgb(42,49,68),1));
        page.addView(permissionHint,marginBottom(dp(20)));

        advancedToggle=text("维护与诊断  ›",16,WHITE,true);
        advancedToggle.setGravity(Gravity.CENTER_VERTICAL);
        advancedToggle.setPadding(dp(16),dp(15),dp(16),dp(15));
        advancedToggle.setBackground(roundRect(CARD,dp(16),Color.rgb(45,53,72),1));
        pressable(advancedToggle);
        advancedToggle.setOnClickListener(v->toggleAdvanced());
        page.addView(advancedToggle,marginBottom(dp(10)));

        advancedBox=new LinearLayout(this);
        advancedBox.setOrientation(LinearLayout.VERTICAL);
        advancedBox.setVisibility(View.GONE);

        LinearLayout tools=actionRow();
        tools.addView(action("⇩","导出日志","生成诊断 TXT",this::exportDiagnosticsTxt),weightParams(1,0));
        tools.addView(space(dp(10)));
        tools.addView(action("⌫","清歌词缓存","下次重新匹配",()->{
            new LyricsDb(this).clearAll();
            Toast.makeText(this,"歌词缓存已清空",Toast.LENGTH_SHORT).show();
        }),weightParams(1,0));
        advancedBox.addView(tools,marginBottom(dp(10)));

        LinearLayout serviceTools=actionRow();
        serviceTools.addView(action("▶","启动服务","恢复后台同步",this::startCore),weightParams(1,0));
        serviceTools.addView(space(dp(10)));
        serviceTools.addView(action("■","停止服务","停止后台同步",()->send(LyricsService.ACTION_STOP)),weightParams(1,0));
        advancedBox.addView(serviceTools,marginBottom(dp(12)));

        LinearLayout diagnostics=card(false);
        TextView dTitle=text("运行诊断",15,WHITE,true);
        diagnostics.addView(dTitle);
        diagView=text("",12,Color.rgb(184,192,209),false);
        diagView.setTypeface(Typeface.MONOSPACE);
        diagView.setPadding(0,dp(10),0,0);
        diagnostics.addView(diagView);
        TextView lTitle=text("最近事件",14,WHITE,true);
        lTitle.setPadding(0,dp(18),0,0);
        diagnostics.addView(lTitle);
        logView=text("",11,MUTED,false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setPadding(0,dp(8),0,0);
        diagnostics.addView(logView);
        advancedBox.addView(diagnostics);
        page.addView(advancedBox);

        page.setAlpha(0f);
        page.setTranslationY(dp(16));
        page.animate().alpha(1f).translationY(0).setDuration(380).start();
        startPulse();
    }

    private void toggleAdvanced(){
        advancedVisible=!advancedVisible;
        TransitionManager.beginDelayedTransition(page,new AutoTransition().setDuration(220));
        advancedBox.setVisibility(advancedVisible?View.VISIBLE:View.GONE);
        advancedToggle.setText(advancedVisible?"维护与诊断  ⌄":"维护与诊断  ›");
        if(advancedVisible)refreshDiagnostics();
    }

    private void startPulse(){
        if(pulse!=null)pulse.cancel();
        pulse=ObjectAnimator.ofFloat(connectionDot,"alpha",1f,.28f,1f);
        pulse.setDuration(1600);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        pulse.start();
    }

    private LinearLayout card(boolean accent){
        LinearLayout v=new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(dp(18),dp(18),dp(18),dp(18));
        if(accent){
            v.setBackground(gradientRound(new int[]{Color.rgb(31,28,56),Color.rgb(20,29,45),CARD},dp(22)));
        }else{
            v.setBackground(roundRect(CARD,dp(22),Color.rgb(47,55,75),1));
        }
        v.setElevation(dp(4));
        return v;
    }

    private LinearLayout actionRow(){
        LinearLayout r=new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        return r;
    }

    private LinearLayout action(String icon,String title,String sub,Runnable run){
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15),dp(15),dp(15),dp(15));
        box.setMinimumHeight(dp(116));
        box.setBackground(roundRect(CARD_ALT,dp(18),Color.rgb(49,58,79),1));
        box.setElevation(dp(2));
        TextView i=text(icon,22,CYAN,true);
        box.addView(i);
        TextView t=text(title,15,WHITE,true);
        t.setPadding(0,dp(9),0,0);
        box.addView(t);
        TextView s=text(sub,11,MUTED,false);
        s.setPadding(0,dp(3),0,0);
        box.addView(s);
        box.setOnClickListener(v->run.run());
        pressable(box);
        return box;
    }

    private TextView sectionTitle(String s){
        TextView t=text(s,15,WHITE,true);
        t.setPadding(dp(2),0,0,dp(10));
        return t;
    }

    private TextView statusChip(String label){
        TextView t=text(label+" · --",12,WHITE,true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10),dp(9),dp(10),dp(9));
        t.setBackground(roundRect(Color.rgb(34,39,55),dp(12),Color.TRANSPARENT,0));
        return t;
    }

    private TextView chip(String s,int color){
        TextView t=text(s,12,WHITE,true);
        t.setPadding(dp(10),dp(6),dp(10),dp(6));
        t.setBackground(roundRect(withAlpha(color,48),dp(999),withAlpha(color,90),1));
        return t;
    }

    private void setStatusChip(TextView v,String name,boolean ok){
        v.setText(name+" · "+(ok?"在线":"离线"));
        int c=ok?GREEN:RED;
        v.setTextColor(ok?Color.rgb(220,255,240):Color.rgb(255,220,225));
        v.setBackground(roundRect(withAlpha(c,34),dp(12),withAlpha(c,105),1));
    }

    @SuppressLint("SetTextI18n")
    private void refresh(){
        TrackMetadata t=state.trackCopy();
        boolean access=hasMediaAccess();
        boolean car=WebRtcBridge.isConnected();
        setStatusChip(serviceChip,"服务",state.serviceRunning());
        setStatusChip(mediaChip,"播放器",state.mediaConnected());
        setStatusChip(carChip,"车机",car);
        connectionDot.setBackground(circle(car?GREEN:(state.mediaConnected()?YELLOW:RED)));

        String key=t.title+'\n'+t.artist;
        if(!key.equals(lastTrackKey)){
            lastTrackKey=key;
            titleView.animate().alpha(0f).translationY(dp(4)).setDuration(110).withEndAction(()->{
                titleView.setText(t.title.isEmpty()?"等待播放器":t.title);
                artistView.setText(t.artist.isEmpty()?"开启播放器后会自动识别":t.artist+(!t.source.isEmpty()?"  ·  "+t.source:""));
                titleView.setTranslationY(-dp(4));
                titleView.animate().alpha(1f).translationY(0).setDuration(180).start();
            }).start();
        }else{
            titleView.setText(t.title.isEmpty()?"等待播放器":t.title);
            artistView.setText(t.artist.isEmpty()?"开启播放器后会自动识别":t.artist+(!t.source.isEmpty()?"  ·  "+t.source:""));
        }

        String source=state.lyricsSource();
        int lines=state.lyricsCopy().size();
        lyricsChip.setText(source.isEmpty()?"歌词：正在匹配":"歌词："+source);
        statusView.setText(lines>0?lines+" 行":state.statusMessage());
        permissionHint.setText(access?"✓ 已开启播放器读取权限，切歌后会自动识别并匹配歌词。":"⚠ 尚未开启播放器读取权限。点击上方“读取权限”后开启 Tesla Lyrics。" );
        permissionHint.setTextColor(access?Color.rgb(184,245,220):Color.rgb(255,220,165));
        permissionHint.setBackground(roundRect(access?withAlpha(GREEN,22):withAlpha(YELLOW,22),dp(14),access?withAlpha(GREEN,70):withAlpha(YELLOW,80),1));
        updateProgressOnly();
        if(advancedVisible)refreshDiagnostics();
    }

    private void updateProgressOnly(){
        if(progress==null)return;
        TrackMetadata t=state.trackCopy();
        long d=Math.max(0,t.durationMs);
        long e=Math.max(0,state.elapsedMs());
        if(d>0)e=Math.min(e,d);
        progress.setProgress(d<=0?0:(int)Math.min(1000,(e*1000L)/d));
        timeView.setText(formatTime(e)+" / "+formatTime(d));
    }

    private void refreshDiagnostics(){
        if(diagView!=null)diagView.setText("Version: "+BuildConfig.VERSION_NAME+"\n"+state.diagnostics()+"\n"+WebRtcBridge.statusReport());
        if(logView!=null){
            List<String> all=state.log.snapshot();
            int from=Math.max(0,all.size()-40);
            logView.setText(String.join("\n",all.subList(from,all.size())));
        }
    }

    private String buildDiagnosticsText(){
        return "Tesla Lyrics "+BuildConfig.VERSION_NAME+"\n"+state.diagnostics()+"\n\n"+WebRtcBridge.statusReport()+"\n\n最近事件\n"+String.join("\n",state.log.snapshot())+"\n";
    }

    private void copyTeslaUrl(){
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        if(cm!=null){
            cm.setPrimaryClip(ClipData.newPlainText("Tesla Lyrics URL",TESLA_URL));
            Toast.makeText(this,"车机网址已复制",Toast.LENGTH_SHORT).show();
        }
    }

    private void exportDiagnosticsTxt(){
        String name="TeslaLyrics-"+new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US).format(new Date())+".txt";
        byte[] data=buildDiagnosticsText().getBytes(StandardCharsets.UTF_8);
        try{
            if(Build.VERSION.SDK_INT>=29){
                ContentValues values=new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME,name);
                values.put(MediaStore.Downloads.MIME_TYPE,"text/plain");
                values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/TeslaLyrics");
                Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
                if(uri==null)throw new IllegalStateException("MediaStore insert failed");
                try(OutputStream os=getContentResolver().openOutputStream(uri)){
                    if(os==null)throw new IllegalStateException("openOutputStream failed");
                    os.write(data);
                    os.flush();
                }
                Toast.makeText(this,"日志已导出到 下载/TeslaLyrics",Toast.LENGTH_LONG).show();
            }else{
                File base=getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if(base==null)base=getFilesDir();
                File dir=new File(base,"TeslaLyrics");
                if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("mkdir failed");
                File out=new File(dir,name);
                try(OutputStream os=new FileOutputStream(out)){os.write(data);os.flush();}
                Toast.makeText(this,"日志已导出",Toast.LENGTH_LONG).show();
            }
            state.log.add("Diagnostics exported: "+name);
        }catch(Exception e){
            state.log.add("Diagnostics export failed: "+e.getClass().getSimpleName());
            Toast.makeText(this,"导出失败："+e.getClass().getSimpleName(),Toast.LENGTH_LONG).show();
        }
        refresh();
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
    private void send(String action){startForegroundService(new Intent(this,LyricsService.class).setAction(action));}

    @SuppressLint("ClickableViewAccessibility")
    private void pressable(View v){
        v.setOnTouchListener((view,event)->{
            if(event.getAction()==MotionEvent.ACTION_DOWN)view.animate().scaleX(.97f).scaleY(.97f).alpha(.88f).setDuration(90).start();
            else if(event.getAction()==MotionEvent.ACTION_UP||event.getAction()==MotionEvent.ACTION_CANCEL)view.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(130).start();
            return false;
        });
    }

    private TextView text(String s,float size,int color,boolean bold){
        TextView t=new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if(bold)t.setTypeface(Typeface.create("sans-serif",Typeface.BOLD));
        return t;
    }

    private View space(int width){View v=new View(this);v.setLayoutParams(new LinearLayout.LayoutParams(width,1));return v;}
    private LinearLayout.LayoutParams weightParams(float weight,int marginBottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,weight);p.bottomMargin=marginBottom;return p;}
    private LinearLayout.LayoutParams marginBottom(int px){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=px;return p;}

    private GradientDrawable roundRect(int fill,float radius,int stroke,int strokeWidth){
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(radius);
        if(strokeWidth>0)d.setStroke(dp(strokeWidth),stroke);
        return d;
    }

    private GradientDrawable circle(int fill){
        GradientDrawable d=new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        return d;
    }

    private GradientDrawable gradient(int[] colors){
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,colors);
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    private GradientDrawable gradientRound(int[] colors,float radius){
        GradientDrawable d=gradient(colors);
        d.setCornerRadius(radius);
        d.setStroke(dp(1),Color.rgb(55,61,82));
        return d;
    }

    private static int withAlpha(int color,int alpha){return Color.argb(alpha,Color.red(color),Color.green(color),Color.blue(color));}
    private int dp(float v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private static String formatTime(long ms){long s=Math.max(0,ms)/1000;return (s/60)+":"+String.format(Locale.US,"%02d",s%60);}
}
