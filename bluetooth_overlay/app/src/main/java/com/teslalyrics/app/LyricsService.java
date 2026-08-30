package com.teslalyrics.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

public final class LyricsService extends Service implements AppState.Listener {
    public static final String ACTION_START="com.teslalyrics.START",ACTION_STOP="com.teslalyrics.STOP",ACTION_RESYNC="com.teslalyrics.RESYNC",ACTION_GLOBAL="com.teslalyrics.GLOBAL",ACTION_TRACK="com.teslalyrics.TRACK",ACTION_SCAN="com.teslalyrics.SCAN";
    public static final String ACTION_UI="com.teslalyrics.UI";

    private AppState state;
    private SettingsStore settings;
    private LyricsDb db;
    private LyricsRepository repo;
    private TelemetryProcessor processor;
    private MediaSessionMonitor media;
    private final Handler main=new Handler(Looper.getMainLooper());
    private String lastNotificationTrack="";
    private boolean foregroundStarted=false,uiPending=false,shutdown=false;

    @Override public void onCreate(){
        super.onCreate();
        state=AppState.get();
        settings=new SettingsStore(this);
        db=new LyricsDb(this);
        repo=new LyricsRepository(db);
        processor=new TelemetryProcessor(repo);
        media=new MediaSessionMonitor(this,processor);
        WebRtcBridge.get().setMedia(media);
        PublicStateRelay.get().configure(this);
        state.setGlobalOffsetMs(settings.globalOffset());
        state.addListener(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String a=i==null?ACTION_START:i.getAction();
        if(a==null)a=ACTION_START;
        if(ACTION_STOP.equals(a)){
            shutdown();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        ensureForeground();
        state.setServiceRunning(true);
        media.start();
        if(ACTION_RESYNC.equals(a)){
            PublicStateRelay.get().forceNext();
            MultiLyricsFetcher.get().republishLatest();
            TrackMetadata t=state.trackCopy();
            if(!t.title.isEmpty())repo.load(t);
            media.scan();
        }else if(ACTION_SCAN.equals(a)){
            media.scan();
        }else if(ACTION_GLOBAL.equals(a)){
            long v=i.getLongExtra("ms",0);
            state.setGlobalOffsetMs(v);
            settings.setGlobalOffset(v);
        }else if(ACTION_TRACK.equals(a)){
            long v=i.getLongExtra("ms",0);
            state.setTrackOffsetMs(v);
            db.updateTrackOffset(state.trackCopy().cacheKey(),v);
        }
        return START_STICKY;
    }

    private void shutdown(){
        if(shutdown)return;
        shutdown=true;
        main.removeCallbacks(uiBroadcast);
        if(media!=null)media.stop();
        WebRtcBridge.get().stop();
        if(state!=null){state.removeListener(this);state.setServiceRunning(false);}
    }

    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    private void createChannel(){
        NotificationChannel c=new NotificationChannel("tesla_lyrics","Tesla Lyrics",NotificationManager.IMPORTANCE_LOW);
        c.setDescription("保持歌曲状态、同步歌词和 Tesla 车机连接");
        getSystemService(NotificationManager.class).createNotificationChannel(c);
    }

    private Notification notification(){
        TrackMetadata t=state.trackCopy();
        String text=t.title.isEmpty()?"等待手机播放器":t.title+(t.artist.isEmpty()?"":" · "+t.artist);
        PendingIntent pi=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"tesla_lyrics")
                .setContentTitle("Tesla Lyrics · 同步服务运行中")
                .setContentText(text)
                .setSubText(WebRtcBridge.isConnected()?"车机已连接":"等待车机连接")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void ensureForeground(){
        if(foregroundStarted)return;
        Notification n=notification();
        if(Build.VERSION.SDK_INT>=29)startForeground(7,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(7,n);
        foregroundStarted=true;
    }

    private void updateNotificationIfNeeded(){
        if(!foregroundStarted)return;
        TrackMetadata t=state.trackCopy();
        String key=t.title+"\n"+t.artist+"\n"+WebRtcBridge.isConnected();
        if(key.equals(lastNotificationTrack))return;
        lastNotificationTrack=key;
        NotificationManager nm=getSystemService(NotificationManager.class);
        if(nm!=null)nm.notify(7,notification());
    }

    @Override public void onStateChanged(){
        updateNotificationIfNeeded();
        if(!uiPending){uiPending=true;main.postDelayed(uiBroadcast,120);}
    }

    private final Runnable uiBroadcast=()->{
        uiPending=false;
        sendBroadcast(new Intent(ACTION_UI).setPackage(getPackageName()));
    };
}
