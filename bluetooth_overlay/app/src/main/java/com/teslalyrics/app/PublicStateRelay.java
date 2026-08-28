package com.teslalyrics.app;

import android.content.Context;
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

    private static final long HEARTBEAT_MS=15000;
    private final OkHttpClient client=new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private String endpoint="";
    private String lastFingerprint="";
    private String lastLyricsTrackKey="";
    private long lastElapsed=0,lastMono=0;
    private boolean lastPlaying=false,sending=false,forceNext=false,successLogged=false;

    public synchronized void configure(Context context){
        endpoint="https://ntfy.sh/"+RelayConfig.stateTopic(context);
        lastFingerprint="";
        lastMono=0;
        forceNext=true;
        successLogged=false;
    }

    public synchronized void forceNext(){forceNext=true;}

    public synchronized void publish(JSONObject f){
        try{
            if(endpoint.isEmpty())return;
            String title=f.optString("MediaNowPlayingTitle","").trim();
            if(title.isEmpty())return;

            String artist=f.optString("MediaNowPlayingArtist","");
            String album=f.optString("MediaNowPlayingAlbum","");
            String source=f.optString("MediaPlaybackSource","");
            String status=f.optString("MediaPlaybackStatus","Paused");
            long duration=Math.max(0,f.optLong("MediaNowPlayingDuration",0));
            long elapsed=Math.max(0,f.optLong("MediaNowPlayingElapsed",0));
            boolean playing="Playing".equalsIgnoreCase(status);
            long now=SystemClock.elapsedRealtime();
            long offset=AppState.get().effectiveOffsetMs();

            String lyricsTrackKey=title+'\u0001'+artist+'\u0001'+album+'\u0001'+duration;
            if(!lyricsTrackKey.equals(lastLyricsTrackKey)){
                lastLyricsTrackKey=lyricsTrackKey;
                MultiLyricsFetcher.get().ensure(f);
            }

            String fp=lyricsTrackKey+'\u0001'+status+'\u0001'+offset;
            long expected=lastElapsed+(lastPlaying&&lastMono>0?Math.max(0,now-lastMono):0);
            boolean drifted=lastMono>0&&Math.abs(elapsed-expected)>1400;
            boolean structural=!fp.equals(lastFingerprint)||lastMono==0||drifted||forceNext;
            boolean heartbeat=lastMono>0&&Math.max(0,now-lastMono)>=HEARTBEAT_MS;
            if((!structural&&!heartbeat)||sending)return;

            JSONObject p=new JSONObject();
            p.put("kind","state");
            p.put("title",title);
            p.put("artist",artist);
            p.put("album",album);
            p.put("source",source);
            p.put("duration",duration);
            p.put("elapsed",elapsed);
            p.put("playing",playing);
            p.put("sentAtMs",System.currentTimeMillis());

            final String sentFp=fp;
            final long sentElapsed=elapsed,sentMono=now;
            final boolean sentPlaying=playing;
            final boolean cacheThis=structural;
            forceNext=false;
            sending=true;

            RequestBody body=RequestBody.create(p.toString(),MediaType.parse("text/plain; charset=utf-8"));
            Request req=new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Cache",cacheThis?"yes":"no")
                    .header("X-Tags","notes")
                    .build();
            client.newCall(req).enqueue(new Callback(){
                @Override public void onFailure(Call call, IOException e){
                    synchronized(PublicStateRelay.this){sending=false;forceNext=true;successLogged=false;}
                    AppState.get().log.add("Public relay retry: "+e.getClass().getSimpleName());
                }
                @Override public void onResponse(Call call, Response response){
                    boolean logConnected=false;
                    try{
                        synchronized(PublicStateRelay.this){
                            sending=false;
                            if(response.isSuccessful()){
                                lastFingerprint=sentFp;
                                lastElapsed=sentElapsed;
                                lastMono=sentMono;
                                lastPlaying=sentPlaying;
                                if(!successLogged){successLogged=true;logConnected=true;}
                            }else{
                                forceNext=true;
                                successLogged=false;
                            }
                        }
                        if(logConnected)AppState.get().log.add("Public relay connected");
                        if(!response.isSuccessful())AppState.get().log.add("Public relay HTTP "+response.code());
                    }finally{response.close();}
                }
            });
        }catch(Exception e){
            synchronized(this){sending=false;forceNext=true;successLogged=false;}
            AppState.get().log.add("Public relay error: "+e.getClass().getSimpleName());
        }
    }

    public void publishLyrics(String key,String provider,String lrc,int score){
        try{
            String ep;
            synchronized(this){ep=endpoint;}
            if(ep.isEmpty())return;
            JSONObject p=new JSONObject();
            p.put("kind","lyrics");
            p.put("key",key);
            p.put("provider",provider);
            p.put("score",score);
            p.put("lrc",lrc);
            String filename="tlx-lyrics-"+Integer.toHexString(key.hashCode())+".json";
            RequestBody body=RequestBody.create(p.toString(),MediaType.parse("application/json; charset=utf-8"));
            Request req=new Request.Builder().url(ep).put(body).header("Filename",filename).header("Cache","yes").build();
            client.newCall(req).enqueue(new Callback(){
                @Override public void onFailure(Call call,IOException e){AppState.get().log.add("Lyrics relay retry: "+e.getClass().getSimpleName());}
                @Override public void onResponse(Call call,Response response){
                    try{if(!response.isSuccessful())AppState.get().log.add("Lyrics relay HTTP "+response.code());}
                    finally{response.close();}
                }
            });
        }catch(Exception e){AppState.get().log.add("Lyrics relay error: "+e.getClass().getSimpleName());}
    }
}
