package com.teslalyrics.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public final class LyricsService extends Service implements AppState.Listener {
    public static final String ACTION_START="com.teslalyrics.START",ACTION_STOP="com.teslalyrics.STOP",ACTION_RESYNC="com.teslalyrics.RESYNC",ACTION_GLOBAL="com.teslalyrics.GLOBAL",ACTION_TRACK="com.teslalyrics.TRACK",ACTION_SCAN="com.teslalyrics.SCAN";
    public static final String ACTION_UI="com.teslalyrics.UI";

    private AppState state;
    private SettingsStore settings;
    private LyricsDb db;
    private LyricsRepository repo;
    private TelemetryProcessor processor;
    private MediaSessionMonitor media;
    private RemoteControlBridge remote;
    private long lastNotificationAt=0;
    private String lastNotificationTrack="";

    @Override public void onCreate(){
        super.onCreate();
        state=AppState.get();
        settings=new SettingsStore(this);
        db=new LyricsDb(this);
        repo=new LyricsRepository(db);
        processor=new TelemetryProcessor(repo);
        PublicStateRelay.get().configure(this);
        media=new MediaSessionMonitor(this,processor);
        remote=new RemoteControlBridge(this,media);
        state.setGlobalOffsetMs(settings.globalOffset());
        state.addListener(this);
        createChannel();
    }

    @Override public int onStartCommand(Intent i,int flags,int id){
        String a=i==null?ACTION_START:i.getAction();
        if(a==null)a=ACTION_START;
        if(ACTION_STOP.equals(a)){
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }
        promote();
        state.setServiceRunning(true);
        media.start();
        remote.start();
        if(ACTION_RESYNC.equals(a)){
            PublicStateRelay.get().forceNext();
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
        if(remote!=null)remote.stop();
        if(media!=null)media.stop();
        state.setServiceRunning(false);
        state.removeListener(this);
    }

    @Override public void onDestroy(){shutdown();super.onDestroy();}
    @Override public IBinder onBind(Intent i){return null;}

    private void createChannel(){
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel("tesla_lyrics","Tesla Lyrics",NotificationManager.IMPORTANCE_LOW);
            c.setDescription("保持播放器状态与 Tesla 歌词页面同步");
            getSystemService(NotificationManager.class).createNotificationChannel(c);
        }
    }

    private Notification notification(){
        TrackMetadata t=state.trackCopy();
        String text=(t.title.isEmpty()?"等待手机播放器":t.title+" - "+t.artist)+"  hanz316.github.io/lyrics/";
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"tesla_lyrics")
                .setContentTitle("Tesla Lyrics 正在运行")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void promote(){
        if(Build.VERSION.SDK_INT>=29)startForeground(7,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        else startForeground(7,notification());
    }

    @Override public void onStateChanged(){
        TrackMetadata t=state.trackCopy();
        String key=t.title+"\n"+t.artist;
        long now=android.os.SystemClock.elapsedRealtime();
        if(!key.equals(lastNotificationTrack)||now-lastNotificationAt>30000){
            lastNotificationTrack=key;
            lastNotificationAt=now;
            promote();
        }
        sendBroadcast(new Intent(ACTION_UI).setPackage(getPackageName()));
    }
}
