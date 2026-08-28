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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public final class PublicStateRelay {
    private static final PublicStateRelay I=new PublicStateRelay();
    public static PublicStateRelay get(){return I;}

    private static final long SHORT_429_BACKOFF_MS=60000L;
    private static final long DAY_MS=86400000L;

    private final OkHttpClient client=new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ScheduledExecutorService scheduler=Executors.newSingleThreadScheduledExecutor();

    private String endpoint="";
    private String lastFingerprint="";
    private String lastLyricsTrackKey="";
    private long lastElapsed=0,lastMono=0;
    private boolean lastPlaying=false,sending=false,forceNext=false,successLogged=false;

    private long backoffUntilMono=0;
    private String pendingLyricsKey="",pendingLyricsProvider="",pendingLyricsLrc="";
    private int pendingLyricsScore=0;
    private boolean pendingLyricsScheduled=false;

    private JSONObject latestFrame=null;
    private ScheduledFuture<?> recoveryFuture=null;

    public synchronized void configure(Context context){
        endpoint="https://ntfy.sh/"+RelayConfig.stateTopic(context);
        lastFingerprint="";
        lastMono=0;
        forceNext=true;
        successLogged=false;
        backoffUntilMono=0;
        if(recoveryFuture!=null){recoveryFuture.cancel(false);recoveryFuture=null;}
    }

    public synchronized void forceNext(){
        forceNext=true;
        if(!inBackoff())scheduleRecovery(250);
    }

    private synchronized boolean inBackoff(){
        return SystemClock.elapsedRealtime()<backoffUntilMono;
    }

    private long retryAfterMs(Response response){
        try{
            String v=response.header("Retry-After","").trim();
            if(!v.isEmpty())return Math.max(0,Long.parseLong(v))*1000L;
        }catch(Exception ignored){}
        return 0;
    }

    private static long untilNextUtcResetMs(){
        long now=System.currentTimeMillis();
        long next=((now/DAY_MS)+1L)*DAY_MS+8000L;
        return Math.max(60000L,next-now);
    }

    private static int ntfyErrorCode(Response response){
        try{
            String body=response.peekBody(4096).string();
            if(body!=null&&!body.isEmpty())return new JSONObject(body).optInt("code",0);
        }catch(Exception ignored){}
        return 0;
    }

    private synchronized void scheduleRecovery(long delayMs){
        if(endpoint.isEmpty()||latestFrame==null)return;
        if(recoveryFuture!=null)recoveryFuture.cancel(false);
        final long delay=Math.max(100,delayMs);
        recoveryFuture=scheduler.schedule(()->{
            JSONObject copy;
            synchronized(PublicStateRelay.this){
                recoveryFuture=null;
                copy=latestFrame;
            }
            if(copy!=null)publish(copy);
        },delay,TimeUnit.MILLISECONDS);
    }

    private synchronized void enter429Backoff(Response response){
        sending=false;
        forceNext=true;
        int code=ntfyErrorCode(response);
        long delay;
        if(code==42908){
            // ntfy daily message quota. It resets at 00:00 UTC.
            delay=untilNextUtcResetMs();
            AppState.get().log.add("Relay daily quota reached; retry after UTC reset");
        }else{
            delay=Math.max(SHORT_429_BACKOFF_MS,retryAfterMs(response));
            AppState.get().log.add("Relay HTTP 429; pause "+(delay/1000)+"s");
        }
        backoffUntilMono=SystemClock.elapsedRealtime()+delay;
        scheduleRecovery(delay+500);
    }

    private synchronized void markRelaySuccess(){
        backoffUntilMono=0;
        if(recoveryFuture!=null){recoveryFuture.cancel(false);recoveryFuture=null;}
        if(!successLogged){
            successLogged=true;
            AppState.get().log.add("Public relay connected");
        }
    }

    public synchronized void publish(JSONObject f){
        try{
            latestFrame=new JSONObject(f.toString());
            if(endpoint.isEmpty()||inBackoff())return;
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
            // Only a real seek / major timeline jump is network-worthy. Normal MediaSession jitter is ignored.
            boolean seekJump=lastMono>0&&Math.abs(elapsed-expected)>5000;
            boolean structural=!fp.equals(lastFingerprint)||lastMono==0||seekJump||forceNext;
            if(!structural||sending)return;

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
            forceNext=false;
            sending=true;

            RequestBody body=RequestBody.create(p.toString(),MediaType.parse("text/plain; charset=utf-8"));
            Request req=new Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .header("Cache","yes")
                    .header("Firebase","no")
                    .header("X-Tags","notes")
                    .build();
            client.newCall(req).enqueue(new Callback(){
                @Override public void onFailure(Call call, IOException e){
                    synchronized(PublicStateRelay.this){
                        sending=false;
                        forceNext=true;
                        backoffUntilMono=Math.max(backoffUntilMono,SystemClock.elapsedRealtime()+15000);
                        scheduleRecovery(15500);
                    }
                    AppState.get().log.add("Public relay retry: "+e.getClass().getSimpleName());
                }
                @Override public void onResponse(Call call, Response response){
                    try{
                        if(response.code()==429){enter429Backoff(response);return;}
                        synchronized(PublicStateRelay.this){
                            sending=false;
                            if(response.isSuccessful()){
                                lastFingerprint=sentFp;
                                lastElapsed=sentElapsed;
                                lastMono=sentMono;
                                lastPlaying=sentPlaying;
                                markRelaySuccess();
                            }else{
                                forceNext=true;
                                backoffUntilMono=Math.max(backoffUntilMono,SystemClock.elapsedRealtime()+15000);
                                scheduleRecovery(15500);
                            }
                        }
                        if(response.isSuccessful())schedulePendingLyrics();
                        else AppState.get().log.add("Public relay HTTP "+response.code());
                    }finally{response.close();}
                }
            });
        }catch(Exception e){
            sending=false;forceNext=true;
            scheduleRecovery(15000);
            AppState.get().log.add("Public relay error: "+e.getClass().getSimpleName());
        }
    }

    public void publishLyrics(String key,String provider,String lrc,int score){
        synchronized(this){
            pendingLyricsKey=key;
            pendingLyricsProvider=provider;
            pendingLyricsLrc=lrc;
            pendingLyricsScore=score;
            if(endpoint.isEmpty()||inBackoff())return;
        }
        // Do not race the state publish. Let the state establish the current track first.
        schedulePendingLyrics();
    }

    private void schedulePendingLyrics(){
        synchronized(this){
            if(pendingLyricsKey.isEmpty()||pendingLyricsScheduled||inBackoff())return;
            pendingLyricsScheduled=true;
        }
        scheduler.schedule(()->{
            synchronized(PublicStateRelay.this){pendingLyricsScheduled=false;}
            sendPendingLyricsNow();
        },7,TimeUnit.SECONDS);
    }

    private void sendPendingLyricsNow(){
        final String ep,key,provider,lrc;
        final int score;
        synchronized(this){
            if(endpoint.isEmpty()||pendingLyricsKey.isEmpty()||inBackoff())return;
            ep=endpoint;key=pendingLyricsKey;provider=pendingLyricsProvider;lrc=pendingLyricsLrc;score=pendingLyricsScore;
        }
        try{
            JSONObject p=new JSONObject();
            p.put("kind","lyrics");
            p.put("key",key);
            p.put("provider",provider);
            p.put("score",score);
            p.put("lrc",lrc);
            String filename="tlx-lyrics-"+Integer.toHexString(key.hashCode())+".json";
            RequestBody body=RequestBody.create(p.toString(),MediaType.parse("application/json; charset=utf-8"));
            Request req=new Request.Builder()
                    .url(ep)
                    .put(body)
                    .header("Filename",filename)
                    .header("Cache","yes")
                    .header("Firebase","no")
                    .build();
            client.newCall(req).enqueue(new Callback(){
                @Override public void onFailure(Call call,IOException e){
                    AppState.get().log.add("Lyrics relay retry: "+e.getClass().getSimpleName());
                }
                @Override public void onResponse(Call call,Response response){
                    try{
                        if(response.code()==429){enter429Backoff(response);return;}
                        if(response.isSuccessful()){
                            synchronized(PublicStateRelay.this){
                                if(key.equals(pendingLyricsKey)){
                                    pendingLyricsKey="";pendingLyricsProvider="";pendingLyricsLrc="";pendingLyricsScore=0;
                                }
                                markRelaySuccess();
                            }
                        }else AppState.get().log.add("Lyrics relay HTTP "+response.code());
                    }finally{response.close();}
                }
            });
        }catch(Exception e){AppState.get().log.add("Lyrics relay error: "+e.getClass().getSimpleName());}
    }
}
