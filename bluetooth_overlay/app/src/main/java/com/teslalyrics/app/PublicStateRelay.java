package com.teslalyrics.app;

import android.content.Context;
import org.json.JSONObject;

/**
 * LAN-probe build coordinator.
 *
 * Public ntfy transport is intentionally disabled in this build. We keep the
 * same API so MediaSessionMonitor and MultiLyricsFetcher continue to work,
 * while track changes still trigger local multi-source lyric matching.
 */
public final class PublicStateRelay {
    private static final PublicStateRelay I=new PublicStateRelay();
    public static PublicStateRelay get(){return I;}

    private String lastLyricsTrackKey="";
    private boolean configured=false;

    public synchronized void configure(Context context){
        configured=true;
        lastLyricsTrackKey="";
        AppState.get().log.add("Public relay disabled: LAN probe mode");
    }

    public synchronized void forceNext(){
        // No public transport in LAN probe mode.
    }

    public synchronized void publish(JSONObject f){
        if(!configured||f==null)return;
        String title=f.optString("MediaNowPlayingTitle","").trim();
        if(title.isEmpty())return;
        String artist=f.optString("MediaNowPlayingArtist","");
        String album=f.optString("MediaNowPlayingAlbum","");
        long duration=Math.max(0,f.optLong("MediaNowPlayingDuration",0));
        String lyricsTrackKey=title+'\u0001'+artist+'\u0001'+album+'\u0001'+duration;
        if(!lyricsTrackKey.equals(lastLyricsTrackKey)){
            lastLyricsTrackKey=lyricsTrackKey;
            MultiLyricsFetcher.get().ensure(f);
            AppState.get().log.add("LAN track ready: "+title);
        }
    }

    public void publishLyrics(String key,String provider,String lrc,int score){
        if(provider!=null&&!provider.isEmpty())
            AppState.get().log.add("Lyrics local-only: "+provider+" score="+score);
    }
}
