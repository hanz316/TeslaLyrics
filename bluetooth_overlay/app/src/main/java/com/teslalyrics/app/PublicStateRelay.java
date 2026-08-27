package com.teslalyrics.app;

import android.os.SystemClock;
import org.json.JSONObject;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public final class PublicStateRelay {
    private static final PublicStateRelay I=new PublicStateRelay();
    public static PublicStateRelay get(){return I;}
    private static final String ENDPOINT="https://ntfy.sh/tlx-b3598dd35e2ab18ef1e2dc84";
    private final OkHttpClient client=new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build();
    private String lastFingerprint="";
    private long lastElapsed=0,lastMono=0;
    private boolean lastPlaying=false,sending=false;

    public synchronized void publish(JSONObject f){
        try{
            String title=f.optString("MediaNowPlayingTitle","");
            if(title.isEmpty())return;
            String artist=f.optString("MediaNowPlayingArtist","");
            String album=f.optString("MediaNowPlayingAlbum","");
            String source=f.optString("MediaPlaybackSource","");
            String status=f.optString("MediaPlaybackStatus","Paused");
            long duration=Math.max(0,f.optLong("MediaNowPlayingDuration",0));
            long elapsed=Math.max(0,f.optLong("MediaNowPlayingElapsed",0));
            boolean playing="Playing".equalsIgnoreCase(status);
            long offset=AppState.get().effectiveOffsetMs();
            long now=SystemClock.elapsedRealtime();
            String fp=title+'\u0001'+artist+'\u0001'+album+'\u0001'+duration+'\u0001'+status+'\u0001'+offset;
            long expected=lastElapsed+(lastPlaying&&lastMono>0?Math.max(0,now-lastMono):0);
            boolean changed=!fp.equals(lastFingerprint)||lastMono==0||Math.abs(elapsed-expected)>1400;
            if(!changed||sending)return;

            JSONObject p=new JSONObject();
            p.put("title",title);p.put("artist",artist);p.put("album",album);p.put("source",source);
            p.put("duration",duration);p.put("elapsed",elapsed);p.put("playing",playing);p.put("offset",offset);
            final String sentFp=fp;final long sentElapsed=elapsed;final long sentMono=now;final boolean sentPlaying=playing;
            sending=true;
            RequestBody body=RequestBody.create(p.toString(),MediaType.parse("text/plain; charset=utf-8"));
            Request req=new Request.Builder().url(ENDPOINT).post(body).header("Cache","yes").header("X-Tags","notes").build();
            client.newCall(req).enqueue(new Callback(){
                @Override public void onFailure(Call call, IOException e){synchronized(PublicStateRelay.this){sending=false;}AppState.get().log.add("Public relay retry: "+e.getClass().getSimpleName());}
                @Override public void onResponse(Call call, Response response){try{if(response.isSuccessful()){synchronized(PublicStateRelay.this){lastFingerprint=sentFp;lastElapsed=sentElapsed;lastMono=sentMono;lastPlaying=sentPlaying;sending=false;}AppState.get().log.add("Public relay synced");}else{synchronized(PublicStateRelay.this){sending=false;}AppState.get().log.add("Public relay HTTP "+response.code());}}finally{response.close();}}
            });
        }catch(Exception e){sending=false;AppState.get().log.add("Public relay error: "+e.getClass().getSimpleName());}
    }
}
