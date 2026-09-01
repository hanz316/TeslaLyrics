package com.teslalyrics.app;

import android.content.Context;
import org.json.JSONObject;

public final class PublicStateRelay {
    private static final PublicStateRelay I=new PublicStateRelay();
    public static PublicStateRelay get(){return I;}

    private String lastLyricsTrackKey="";
    private String lastMediaId="";
    private long lastLyricsEnsureAt=0;
    private boolean configured=false;
    private static final long LYRICS_RETRY_MS=15000L;

    public synchronized void configure(Context context){
        configured=true;
        lastLyricsTrackKey="";
        lastMediaId="";
        lastLyricsEnsureAt=0;
        WebRtcBridge.get().configure(context);
        AppState.get().log.add("Tesla Lyrics production relay started");
        AppState.get().log.add("Transport: secure WSS/MQTT");
    }

    public synchronized void forceNext(){
        TrackMetadata t=AppState.get().trackCopy();
        lastLyricsEnsureAt=0;
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
        String mediaId=f.optString("MediaMediaId","").trim();
        String lyricsTrackKey=title+'\u0001'+artist+'\u0001'+album+'\u0001'+duration;
        long now=System.currentTimeMillis();

        boolean trackChanged=!lyricsTrackKey.equals(lastLyricsTrackKey);
        boolean mediaIdArrived=!mediaId.isEmpty()&&!mediaId.equals(lastMediaId);
        boolean retryDue=now-lastLyricsEnsureAt>=LYRICS_RETRY_MS;
        if(trackChanged){
            lastLyricsTrackKey=lyricsTrackKey;
            lastMediaId=mediaId;
            lastLyricsEnsureAt=0;
            AppState.get().log.add("WSS track: "+title);
        }

        // Do not make lyric lookup a one-shot operation. MediaSession metadata (especially
        // NetEase's exact song id) can arrive after title/artist, and a provider can fail
        // transiently. MultiLyricsFetcher already de-duplicates successful/loading tracks,
        // so it is safe to retry periodically and immediately when a late media id appears.
        if(trackChanged||mediaIdArrived||retryDue){
            if(mediaIdArrived)lastMediaId=mediaId;
            lastLyricsEnsureAt=now;
            MultiLyricsFetcher.get().ensure(f);
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
