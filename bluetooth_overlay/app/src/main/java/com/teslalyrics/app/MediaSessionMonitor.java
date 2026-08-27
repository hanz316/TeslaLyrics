package com.teslalyrics.app;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class MediaSessionMonitor {
    private static final List<String> PREFERRED=Arrays.asList(
            "com.netease.cloudmusic","com.apple.android.music","com.tencent.qqmusic",
            "com.spotify.music","com.google.android.apps.youtube.music");
    private final AppState state=AppState.get();
    private final TelemetryProcessor processor;
    private final MediaSessionManager manager;
    private final ComponentName accessComponent;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private MediaController current;
    private String currentPackage="";
    private boolean started=false;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener=this::choose;
    private final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata m){publish();}
        @Override public void onPlaybackStateChanged(PlaybackState s){publish();}
        @Override public void onSessionDestroyed(){handler.postDelayed(MediaSessionMonitor.this::scan,150);}
    };
    private final Runnable ticker=new Runnable(){@Override public void run(){if(!started)return;if(current==null)scan();else publish();handler.postDelayed(this,5000);}};

    public MediaSessionMonitor(Context c,TelemetryProcessor p){
        processor=p;
        manager=(MediaSessionManager)c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        accessComponent=new ComponentName(c,MediaAccessService.class);
    }
    public void start(){
        if(started){scan();return;} started=true;
        try{manager.addOnActiveSessionsChangedListener(sessionsListener,accessComponent,handler);}catch(SecurityException e){noAccess();}
        scan();handler.removeCallbacks(ticker);handler.post(ticker);
    }
    public void stop(){started=false;handler.removeCallbacks(ticker);try{manager.removeOnActiveSessionsChangedListener(sessionsListener);}catch(Exception ignored){}detach();state.setMediaConnected(false,"");}
    public void scan(){
        if(!started)return;
        try{choose(manager.getActiveSessions(accessComponent));}
        catch(SecurityException e){detach();noAccess();}
        catch(Exception e){state.log.add("Media scan error: "+e.getClass().getSimpleName());}
    }
    private void noAccess(){state.setMediaConnected(false,"");state.setStatus("请先开启通知使用权");state.log.add("Notification access required");}
    private void choose(List<MediaController> controllers){
        if(!started)return;
        List<MediaController> list=controllers==null?new ArrayList<>():new ArrayList<>(controllers);
        list.removeIf(c->c==null||c.getMetadata()==null||text(c.getMetadata(),MediaMetadata.METADATA_KEY_TITLE).isEmpty());
        list.sort(Comparator.comparingInt(this::score).reversed());
        MediaController best=list.isEmpty()?null:list.get(0);
        if(best==null){detach();state.setMediaConnected(false,"");state.setStatus("等待网易云音乐播放");return;}
        if(current!=null&&current.getSessionToken().equals(best.getSessionToken())){publish();return;}
        detach();current=best;currentPackage=best.getPackageName()==null?"":best.getPackageName();
        try{current.registerCallback(callback,handler);}catch(Exception ignored){}
        state.setMediaConnected(true,currentPackage);state.log.add("Player session: "+friendlyName(currentPackage));publish();
    }
    private int score(MediaController c){
        int s=0;String pkg=c.getPackageName()==null?"":c.getPackageName();int p=PREFERRED.indexOf(pkg);if(p>=0)s+=1000-p*20;
        PlaybackState ps=c.getPlaybackState();if(ps!=null&&ps.getState()==PlaybackState.STATE_PLAYING)s+=500;
        if(c.getMetadata()!=null&&!text(c.getMetadata(),MediaMetadata.METADATA_KEY_TITLE).isEmpty())s+=100;return s;
    }
    private void publish(){
        MediaController c=current;if(c==null)return;
        try{
            MediaMetadata md=c.getMetadata();PlaybackState ps=c.getPlaybackState();if(md==null)return;
            String artist=text(md,MediaMetadata.METADATA_KEY_ARTIST);if(artist.isEmpty())artist=text(md,MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            JSONObject f=new JSONObject();
            f.put("MediaNowPlayingTitle",text(md,MediaMetadata.METADATA_KEY_TITLE));
            f.put("MediaNowPlayingArtist",artist);
            f.put("MediaNowPlayingAlbum",text(md,MediaMetadata.METADATA_KEY_ALBUM));
            f.put("MediaNowPlayingDuration",Math.max(0,md.getLong(MediaMetadata.METADATA_KEY_DURATION)));
            f.put("MediaPlaybackSource",friendlyName(currentPackage));
            if(ps!=null){
                f.put("MediaNowPlayingElapsed",positionNow(ps));int x=ps.getState();
                if(x==PlaybackState.STATE_PLAYING||x==PlaybackState.STATE_FAST_FORWARDING||x==PlaybackState.STATE_REWINDING)f.put("MediaPlaybackStatus","Playing");
                else if(x==PlaybackState.STATE_STOPPED||x==PlaybackState.STATE_NONE||x==PlaybackState.STATE_ERROR)f.put("MediaPlaybackStatus","Stopped");
                else f.put("MediaPlaybackStatus","Paused");
            }
            processor.accept(f);state.setMediaConnected(true,currentPackage);
        }catch(Exception e){state.log.add("Media publish error: "+e.getClass().getSimpleName());}
    }
    private static long positionNow(PlaybackState ps){
        long p=Math.max(0,ps.getPosition());int s=ps.getState();
        if(s==PlaybackState.STATE_PLAYING||s==PlaybackState.STATE_FAST_FORWARDING||s==PlaybackState.STATE_REWINDING){long u=ps.getLastPositionUpdateTime();if(u>0)p+=Math.round(Math.max(0,SystemClock.elapsedRealtime()-u)*ps.getPlaybackSpeed());}
        return Math.max(0,p);
    }
    private void detach(){if(current!=null)try{current.unregisterCallback(callback);}catch(Exception ignored){}current=null;currentPackage="";}
    private static String text(MediaMetadata m,String key){CharSequence v=m==null?null:m.getText(key);return v==null?"":v.toString().trim();}
    public static String friendlyName(String pkg){
        if("com.netease.cloudmusic".equals(pkg))return "网易云音乐";
        if("com.apple.android.music".equals(pkg))return "Apple Music";
        if("com.tencent.qqmusic".equals(pkg))return "QQ音乐";
        if("com.spotify.music".equals(pkg))return "Spotify";
        if("com.google.android.apps.youtube.music".equals(pkg))return "YouTube Music";
        return pkg==null||pkg.isEmpty()?"手机播放器":pkg;
    }
}
