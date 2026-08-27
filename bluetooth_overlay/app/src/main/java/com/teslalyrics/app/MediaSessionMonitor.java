package com.teslalyrics.app;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
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
        @Override public void onQueueChanged(List<MediaSession.QueueItem> q){publish();}
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
            f.put("MediaMediaId",string(md,MediaMetadata.METADATA_KEY_MEDIA_ID));
            f.put("MediaPlaybackSource",friendlyName(currentPackage));
            if(ps!=null){
                f.put("MediaNowPlayingElapsed",positionNow(ps));int x=ps.getState();
                f.put("MediaActions",ps.getActions());
                f.put("MediaActiveQueueId",ps.getActiveQueueItemId());
                if(x==PlaybackState.STATE_PLAYING||x==PlaybackState.STATE_FAST_FORWARDING||x==PlaybackState.STATE_REWINDING)f.put("MediaPlaybackStatus","Playing");
                else if(x==PlaybackState.STATE_STOPPED||x==PlaybackState.STATE_NONE||x==PlaybackState.STATE_ERROR)f.put("MediaPlaybackStatus","Stopped");
                else f.put("MediaPlaybackStatus","Paused");
            }
            JSONArray q=queueJson(c);
            if(q.length()>0)f.put("MediaQueue",q);
            processor.accept(f);
            PublicStateRelay.get().publish(f);
            state.setMediaConnected(true,currentPackage);
        }catch(Exception e){state.log.add("Media publish error: "+e.getClass().getSimpleName());}
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
                long actions=ps==null?0:ps.getActions();
                if("seek".equals(action)){
                    long target=Math.max(0,cmd.optLong("position",0));
                    MediaMetadata md=c.getMetadata();long d=md==null?0:md.getLong(MediaMetadata.METADATA_KEY_DURATION);
                    if(d>0)target=Math.min(target,d);
                    tc.seekTo(target);
                    state.log.add("Remote seek: "+target);
                }else if("next".equals(action)){
                    tc.skipToNext();state.log.add("Remote next");
                }else if("previous".equals(action)){
                    tc.skipToPrevious();state.log.add("Remote previous");
                }else if("play".equals(action)){
                    tc.play();state.log.add("Remote play");
                }else if("pause".equals(action)){
                    tc.pause();state.log.add("Remote pause");
                }else if("toggle".equals(action)){
                    boolean playing=ps!=null&&(ps.getState()==PlaybackState.STATE_PLAYING||ps.getState()==PlaybackState.STATE_FAST_FORWARDING||ps.getState()==PlaybackState.STATE_REWINDING);
                    if(playing)tc.pause();else tc.play();
                    state.log.add("Remote toggle");
                }else if("queue".equals(action)){
                    long id=cmd.optLong("id",MediaSession.QueueItem.UNKNOWN_ID);
                    if(id!=MediaSession.QueueItem.UNKNOWN_ID){tc.skipToQueueItem(id);state.log.add("Remote queue: "+id);}
                }else if("resync".equals(action)){
                    publish();
                }
                handler.postDelayed(this::publish,180);
            }catch(Exception e){state.log.add("Remote control error: "+e.getClass().getSimpleName());}
        });
    }

    private static JSONArray queueJson(MediaController c){
        JSONArray out=new JSONArray();
        try{
            List<MediaSession.QueueItem> q=c.getQueue();
            if(q==null||q.isEmpty())return out;
            int limit=Math.min(q.size(),30);
            for(int i=0;i<limit;i++){
                MediaSession.QueueItem item=q.get(i);if(item==null)continue;
                MediaDescription d=item.getDescription();
                JSONObject x=new JSONObject();
                x.put("id",item.getQueueId());
                x.put("title",cs(d==null?null:d.getTitle()));
                x.put("subtitle",cs(d==null?null:d.getSubtitle()));
                x.put("description",cs(d==null?null:d.getDescription()));
                x.put("mediaId",d==null||d.getMediaId()==null?"":d.getMediaId());
                out.put(x);
            }
        }catch(Exception ignored){}
        return out;
    }
    private static String cs(CharSequence v){return v==null?"":v.toString().trim();}
    private static long positionNow(PlaybackState ps){
        long p=Math.max(0,ps.getPosition());int s=ps.getState();
        if(s==PlaybackState.STATE_PLAYING||s==PlaybackState.STATE_FAST_FORWARDING||s==PlaybackState.STATE_REWINDING){long u=ps.getLastPositionUpdateTime();if(u>0)p+=Math.round(Math.max(0,SystemClock.elapsedRealtime()-u)*ps.getPlaybackSpeed());}
        return Math.max(0,p);
    }
    private void detach(){if(current!=null)try{current.unregisterCallback(callback);}catch(Exception ignored){}current=null;currentPackage="";}
    private static String text(MediaMetadata m,String key){CharSequence v=m==null?null:m.getText(key);return v==null?"":v.toString().trim();}
    private static String string(MediaMetadata m,String key){String v=m==null?null:m.getString(key);return v==null?"":v.trim();}
    public static String friendlyName(String pkg){
        if("com.netease.cloudmusic".equals(pkg))return "网易云音乐";
        if("com.apple.android.music".equals(pkg))return "Apple Music";
        if("com.tencent.qqmusic".equals(pkg))return "QQ音乐";
        if("com.spotify.music".equals(pkg))return "Spotify";
        if("com.google.android.apps.youtube.music".equals(pkg))return "YouTube Music";
        return pkg==null||pkg.isEmpty()?"手机播放器":pkg;
    }
}
