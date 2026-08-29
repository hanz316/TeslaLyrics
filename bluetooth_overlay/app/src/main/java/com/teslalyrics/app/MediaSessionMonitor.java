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
import java.util.Locale;

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
    private String lastCustomActionsSig="";
    private boolean started=false;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener=this::choose;
    private final MediaController.Callback callback=new MediaController.Callback(){
        @Override public void onMetadataChanged(MediaMetadata m){publish();}
        @Override public void onPlaybackStateChanged(PlaybackState s){publish();}
        @Override public void onSessionDestroyed(){handler.postDelayed(MediaSessionMonitor.this::scan,150);}
    };
    private final Runnable ticker=new Runnable(){
        @Override public void run(){
            if(!started)return;
            if(current==null)scan();else publish();
            handler.postDelayed(this,15000);
        }
    };

    public MediaSessionMonitor(Context c,TelemetryProcessor p){
        processor=p;
        manager=(MediaSessionManager)c.getSystemService(Context.MEDIA_SESSION_SERVICE);
        accessComponent=new ComponentName(c,MediaAccessService.class);
    }

    public void start(){
        if(started){scan();return;}
        started=true;
        try{manager.addOnActiveSessionsChangedListener(sessionsListener,accessComponent,handler);}catch(SecurityException e){noAccess();}
        scan();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    public void stop(){
        started=false;
        handler.removeCallbacks(ticker);
        try{manager.removeOnActiveSessionsChangedListener(sessionsListener);}catch(Exception ignored){}
        detach();
        state.setMediaConnected(false,"");
    }

    public void scan(){
        if(!started)return;
        try{choose(manager.getActiveSessions(accessComponent));}
        catch(SecurityException e){detach();noAccess();}
        catch(Exception e){state.log.add("Media scan error: "+e.getClass().getSimpleName());}
    }

    private void noAccess(){
        state.setMediaConnected(false,"");
        state.setStatus("请先开启通知使用权");
    }

    private void choose(List<MediaController> controllers){
        if(!started)return;
        List<MediaController> list=controllers==null?new ArrayList<>():new ArrayList<>(controllers);
        list.removeIf(c->c==null||c.getMetadata()==null||titleOf(c.getMetadata()).isEmpty());
        list.sort(Comparator.comparingInt(this::score).reversed());
        MediaController best=list.isEmpty()?null:list.get(0);
        if(best==null){
            detach();
            state.setMediaConnected(false,"");
            state.setStatus("等待手机播放器播放");
            return;
        }
        if(current!=null&&current.getSessionToken().equals(best.getSessionToken())){publish();return;}
        detach();
        current=best;
        currentPackage=best.getPackageName()==null?"":best.getPackageName();
        lastCustomActionsSig="";
        try{current.registerCallback(callback,handler);}catch(Exception ignored){}
        state.setMediaConnected(true,currentPackage);
        state.log.add("Player session: "+friendlyName(currentPackage));
        publish();
    }

    private int score(MediaController c){
        int s=0;
        PlaybackState ps=c.getPlaybackState();
        if(ps!=null&&isActivelyPlaying(ps.getState()))s+=3000;
        String pkg=c.getPackageName()==null?"":c.getPackageName();
        int p=PREFERRED.indexOf(pkg);
        if(p>=0)s+=1000-p*20;
        if(c.getMetadata()!=null&&!titleOf(c.getMetadata()).isEmpty())s+=100;
        return s;
    }

    private void publish(){
        MediaController c=current;
        if(c==null)return;
        try{
            MediaMetadata md=c.getMetadata();
            PlaybackState ps=c.getPlaybackState();
            if(md==null)return;

            String title=titleOf(md);
            if(title.isEmpty())return;
            String artist=firstNonEmpty(
                    text(md,MediaMetadata.METADATA_KEY_ARTIST),
                    text(md,MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    text(md,MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));

            JSONObject f=new JSONObject();
            f.put("MediaNowPlayingTitle",title);
            f.put("MediaNowPlayingArtist",artist);
            f.put("MediaNowPlayingAlbum",text(md,MediaMetadata.METADATA_KEY_ALBUM));
            f.put("MediaNowPlayingDuration",Math.max(0,md.getLong(MediaMetadata.METADATA_KEY_DURATION)));
            f.put("MediaMediaId",string(md,MediaMetadata.METADATA_KEY_MEDIA_ID));
            f.put("MediaPlaybackSource",friendlyName(currentPackage));
            if(ps!=null){
                reportCustomActions(ps);
                f.put("MediaNowPlayingElapsed",positionNow(ps));
                int x=ps.getState();
                if(isActivelyPlaying(x))f.put("MediaPlaybackStatus","Playing");
                else if(x==PlaybackState.STATE_STOPPED||x==PlaybackState.STATE_NONE||x==PlaybackState.STATE_ERROR)f.put("MediaPlaybackStatus","Stopped");
                else f.put("MediaPlaybackStatus","Paused");
            }

            processor.accept(f);
            PublicStateRelay.get().publish(f);
            state.setMediaConnected(true,currentPackage);
        }catch(Exception e){state.log.add("Media publish error: "+e.getClass().getSimpleName());}
    }

    private void reportCustomActions(PlaybackState ps){
        List<PlaybackState.CustomAction> actions=ps.getCustomActions();
        StringBuilder sig=new StringBuilder();
        if(actions!=null)for(PlaybackState.CustomAction a:actions){
            if(a==null)continue;
            sig.append(a.getName()).append('=').append(a.getAction()).append(';');
        }
        String s=sig.toString();
        if(s.equals(lastCustomActionsSig))return;
        lastCustomActionsSig=s;
        if(s.isEmpty())state.log.add("Media custom actions: none");
        else state.log.add("Media custom actions: "+s);
    }

    private boolean tryKaraokeCustomAction(MediaController c,PlaybackState ps){
        if(ps==null)return false;
        List<PlaybackState.CustomAction> actions=ps.getCustomActions();
        if(actions==null||actions.isEmpty())return false;
        PlaybackState.CustomAction best=null;
        int bestScore=0;
        for(PlaybackState.CustomAction a:actions){
            if(a==null)continue;
            String name=String.valueOf(a.getName()).toLowerCase(Locale.ROOT);
            String id=String.valueOf(a.getAction()).toLowerCase(Locale.ROOT);
            String both=name+' '+id;
            int score=0;
            if(both.contains("随心唱"))score=100;
            else if(both.contains("karaoke"))score=90;
            else if(both.contains("sing"))score=80;
            else if(name.contains("唱"))score=70;
            if(score>bestScore){bestScore=score;best=a;}
        }
        if(best==null)return false;
        c.getTransportControls().sendCustomAction(best,null);
        state.log.add("Karaoke custom action sent: "+best.getName()+" / "+best.getAction());
        return true;
    }

    public void handleRemoteCommand(JSONObject cmd){
        if(cmd==null)return;
        handler.post(()->{
            MediaController c=current;
            if(c==null){scan();return;}
            try{
                String action=cmd.optString("action","");
                MediaController.TransportControls tc=c.getTransportControls();
                PlaybackState ps=c.getPlaybackState();
                if("seek".equals(action)){
                    long target=Math.max(0,cmd.optLong("position",0));
                    MediaMetadata md=c.getMetadata();
                    long d=md==null?0:md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    if(d>0)target=Math.min(target,d);
                    tc.seekTo(target);
                    state.log.add("Remote seek: "+target);
                }else if("next".equals(action)){
                    tc.skipToNext();
                }else if("previous".equals(action)){
                    tc.skipToPrevious();
                }else if("play".equals(action)){
                    tc.play();
                }else if("pause".equals(action)){
                    tc.pause();
                }else if("toggle".equals(action)){
                    boolean playing=ps!=null&&isActivelyPlaying(ps.getState());
                    if(playing)tc.pause();else tc.play();
                }else if("karaoke".equals(action)){
                    if(!"com.netease.cloudmusic".equals(currentPackage)){
                        state.log.add("Karaoke: current player is not NetEase");
                    }else if(!tryKaraokeCustomAction(c,ps)){
                        state.log.add("Karaoke: no MediaSession custom action exposed");
                    }
                    handler.postDelayed(this::publish,250);
                    return;
                }else if("resync".equals(action)){
                    PublicStateRelay.get().forceNext();
                    MultiLyricsFetcher.get().republishLatest();
                    publish();
                    return;
                }
                handler.postDelayed(this::publish,180);
            }catch(Exception e){state.log.add("Remote control error: "+e.getClass().getSimpleName());}
        });
    }

    private static boolean isActivelyPlaying(int s){
        return s==PlaybackState.STATE_PLAYING||s==PlaybackState.STATE_FAST_FORWARDING||s==PlaybackState.STATE_REWINDING;
    }

    private static String titleOf(MediaMetadata m){
        return firstNonEmpty(text(m,MediaMetadata.METADATA_KEY_TITLE),text(m,MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
    }

    private static long positionNow(PlaybackState ps){
        long p=Math.max(0,ps.getPosition());
        if(isActivelyPlaying(ps.getState())){
            long u=ps.getLastPositionUpdateTime();
            if(u>0)p+=Math.round(Math.max(0,SystemClock.elapsedRealtime()-u)*ps.getPlaybackSpeed());
        }
        return Math.max(0,p);
    }

    private void detach(){
        if(current!=null)try{current.unregisterCallback(callback);}catch(Exception ignored){}
        current=null;
        currentPackage="";
        lastCustomActionsSig="";
    }

    private static String text(MediaMetadata m,String key){CharSequence v=m==null?null:m.getText(key);return v==null?"":v.toString().trim();}
    private static String string(MediaMetadata m,String key){String v=m==null?null:m.getString(key);return v==null?"":v.trim();}
    private static String firstNonEmpty(String... values){for(String v:values)if(v!=null&&!v.trim().isEmpty())return v.trim();return "";}

    public static String friendlyName(String pkg){
        if("com.netease.cloudmusic".equals(pkg))return "网易云音乐";
        if("com.apple.android.music".equals(pkg))return "Apple Music";
        if("com.tencent.qqmusic".equals(pkg))return "QQ音乐";
        if("com.spotify.music".equals(pkg))return "Spotify";
        if("com.google.android.apps.youtube.music".equals(pkg))return "YouTube Music";
        return pkg==null||pkg.isEmpty()?"手机播放器":pkg;
    }
}
