package com.teslalyrics.app;

import android.content.Context;
import org.json.JSONObject;

public final class PublicStateRelay {
    private static final PublicStateRelay I=new PublicStateRelay();
    public static PublicStateRelay get(){return I;}

    private String lastLyricsTrackKey="";
    private boolean configured=false;

    public synchronized void configure(Context context){
        configured=true;
        lastLyricsTrackKey="";
        WebRtcBridge.get().configure(context);
        AppState.get().log.add("BUILD IPCENTRY5");
        // IPlayService is an in-process Java interface, not an AIDL contract. Check the
        // actual exported PlayService/onBind path and real Binder/MediaSession commands.
        NeteaseExternalEntryScanner.scanAsync(context);
        AppState.get().log.add("Transport: secure WSS/MQTT, no ntfy");
    }

    public synchronized void forceNext(){
        TrackMetadata t=AppState.get().trackCopy();
        if(!t.title.isEmpty())AppState.get().log.add("WSS resync requested");
    }

    public synchronized void publish(JSONObject f){
        if(!configured||f==null)return;
        String title=f.optString("MediaNowPlayingTitle","").trim();
        if(title.isEmpty())return;
        String artist=f.optString("MediaNowPlayingArtist","");
        String album=f.optString("MediaNowPlayingAlbum","");
        long duration=Math.max(0,f.optLong("MediaNowPlayingDuration",0));
        long elapsed=Math.max(0,f.optLong("MediaNowPlayingElapsed",0));
        String status=f.optString("MediaPlaybackStatus","Paused");
        boolean playing="Playing".equalsIgnoreCase(status);
        String lyricsTrackKey=title+'\u0001'+artist+'\u0001'+album+'\u0001'+duration;
        if(!lyricsTrackKey.equals(lastLyricsTrackKey)){
            lastLyricsTrackKey=lyricsTrackKey;
            MultiLyricsFetcher.get().ensure(f);
            AppState.get().log.add("WSS track: "+title);
        }
        try{
            JSONObject o=new JSONObject();
            o.put("kind","state");
            o.put("title",title);
            o.put("artist",artist);
            o.put("album",album);
            o.put("source",f.optString("MediaPlaybackSource",""));
            o.put("duration",duration);
            o.put("elapsed",elapsed);
            o.put("playing",playing);
            o.put("sentAtMs",System.currentTimeMillis());
            o.put("effectiveOffset",AppState.get().effectiveOffsetMs());
            WebRtcBridge.get().sendState(o);
        }catch(Exception ignored){}
    }

    public void publishLyrics(String key,String provider,String lrc,int score){
        WebRtcBridge.get().sendLyrics(key,provider,lrc,score);
        if(provider!=null&&!provider.isEmpty())AppState.get().log.add("WSS lyrics: "+provider+" score="+score);
    }
}
