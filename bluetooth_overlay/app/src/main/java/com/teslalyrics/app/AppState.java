package com.teslalyrics.app;

import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AppState {
    public interface Listener { void onStateChanged(); }
    private static final AppState I=new AppState();
    public static AppState get(){return I;}
    public final EventLog log=new EventLog(60);
    private final CopyOnWriteArrayList<Listener> listeners=new CopyOnWriteArrayList<>();
    private TrackMetadata track=new TrackMetadata();
    private List<LyricsLine> lyrics=new ArrayList<>();
    private long baseElapsedMs=0,baseMonoMs=SystemClock.elapsedRealtime();
    private boolean playing=false,serviceRunning=false,mediaConnected=false,lyricsLoading=false;
    private long globalOffsetMs=0,trackOffsetMs=0,browserRevision=1;
    private String playerPackage="",lyricsSource="",statusMessage="等待手机播放器",lanUrl="http://0.0.0.0:8765";
    private int carClients=0; private double lastDriftMs=0;
    private AppState(){}
    public void addListener(Listener l){listeners.addIfAbsent(l);} public void removeListener(Listener l){listeners.remove(l);} private void fire(){for(Listener l:listeners)l.onStateChanged();}
    public synchronized TrackMetadata trackCopy(){return track.copy();}
    public synchronized long elapsedMs(){return playing?Math.max(0,baseElapsedMs+SystemClock.elapsedRealtime()-baseMonoMs):baseElapsedMs;}
    public synchronized boolean isPlaying(){return playing;} public synchronized boolean mediaConnected(){return mediaConnected;} public synchronized String playerPackage(){return playerPackage;}
    public synchronized long globalOffsetMs(){return globalOffsetMs;} public synchronized long trackOffsetMs(){return trackOffsetMs;} public synchronized long effectiveOffsetMs(){return globalOffsetMs+trackOffsetMs;} public synchronized long browserRevision(){return browserRevision;}
    public synchronized List<LyricsLine> lyricsCopy(){return new ArrayList<>(lyrics);}
    public synchronized void setServiceRunning(boolean v){serviceRunning=v;fire();}
    public synchronized void setMediaConnected(boolean v,String pkg){mediaConnected=v;playerPackage=pkg==null?"":pkg;if(v&&(statusMessage.startsWith("等待")||statusMessage.contains("通知使用权")))statusMessage="播放器已连接";fire();}
    public synchronized void setTelemetryConnected(boolean v){setMediaConnected(v,v?"Simulation":"");}
    public synchronized void setOauthOk(boolean v){}
    public synchronized void setLanUrl(String s){lanUrl=s;fire();} public synchronized void setCarClients(int n){carClients=Math.max(0,n);fire();}
    public synchronized void setStatus(String s){statusMessage=s;browserRevision++;fire();}
    public synchronized void setTrackSkeleton(String title){if(title!=null)track.title=title;lyricsLoading=true;lyrics=new ArrayList<>();lyricsSource="";statusMessage="正在加载歌词";browserRevision++;fire();}
    public synchronized void setMetadata(TrackMetadata t){boolean changed=!track.title.equals(nz(t.title))||!track.artist.equals(nz(t.artist))||!track.album.equals(nz(t.album))||track.durationMs!=t.durationMs||!track.source.equals(nz(t.source));track.title=nz(t.title);track.artist=nz(t.artist);track.album=nz(t.album);track.durationMs=t.durationMs;track.source=nz(t.source);if(changed)browserRevision++;fire();}
    public synchronized void setLyrics(List<LyricsLine> l,String source,long savedTrackOffsetMs){lyrics=l==null?new ArrayList<>():new ArrayList<>(l);lyricsSource=nz(source);trackOffsetMs=savedTrackOffsetMs;lyricsLoading=false;statusMessage=lyrics.isEmpty()?"未找到同步歌词":"歌词已同步";browserRevision++;fire();}
    public synchronized void setGlobalOffsetMs(long ms){long n=clamp(ms,-30000,30000);if(globalOffsetMs!=n){globalOffsetMs=n;browserRevision++;}fire();}
    public synchronized void setTrackOffsetMs(long ms){long n=clamp(ms,-30000,30000);if(trackOffsetMs!=n){trackOffsetMs=n;browserRevision++;}fire();}
    public synchronized void applyElapsed(long sourceMs){long now=SystemClock.elapsedRealtime();long estimate=playing?baseElapsedMs+(now-baseMonoMs):baseElapsedMs;long drift=sourceMs-estimate;lastDriftMs=drift;if(Math.abs(drift)>2000)baseElapsedMs=Math.max(0,sourceMs);else if(Math.abs(drift)<300)baseElapsedMs=Math.max(0,estimate+Math.round(drift*.25));else baseElapsedMs=Math.max(0,estimate+Math.round(drift*.55));baseMonoMs=now;fire();}
    public synchronized void seekHard(long ms){baseElapsedMs=Math.max(0,ms);baseMonoMs=SystemClock.elapsedRealtime();lastDriftMs=0;fire();}
    public synchronized void setPlaying(boolean v){long now=SystemClock.elapsedRealtime();if(playing)baseElapsedMs+=now-baseMonoMs;playing=v;baseMonoMs=now;statusMessage=v?"播放中":"已暂停";fire();}
    public synchronized JSONObject toJson(){JSONObject o=new JSONObject();try{o.put("title",track.title);o.put("artist",track.artist);o.put("album",track.album);o.put("source",track.source);o.put("duration",track.durationMs);o.put("elapsed",elapsedMs());o.put("serverMono",SystemClock.elapsedRealtime());o.put("playing",playing);o.put("globalOffset",globalOffsetMs);o.put("trackOffset",trackOffsetMs);o.put("effectiveOffset",globalOffsetMs+trackOffsetMs);o.put("loading",lyricsLoading);o.put("status",statusMessage);JSONArray a=new JSONArray();for(LyricsLine line:lyrics){JSONObject x=new JSONObject();x.put("t",line.timeMs);x.put("text",line.text);a.put(x);}o.put("lyrics",a);}catch(Exception ignored){}return o;}
    public synchronized JSONObject toTimelineJson(){JSONObject o=new JSONObject();try{o.put("patch",true);o.put("elapsed",elapsedMs());o.put("serverMono",SystemClock.elapsedRealtime());o.put("playing",playing);o.put("effectiveOffset",globalOffsetMs+trackOffsetMs);o.put("status",statusMessage);}catch(Exception ignored){}return o;}
    public synchronized String diagnostics(){return "Service: "+(serviceRunning?"Running":"Stopped")+"\nMedia session: "+(mediaConnected?"Connected":"Disconnected")+"\nPlayer: "+MediaSessionMonitor.friendlyName(playerPackage)+"\nTrack: "+track.title+" - "+track.artist+"\nPlayer elapsed base: "+baseElapsedMs+" ms\nLocal elapsed: "+elapsedMs()+" ms\nDrift: "+Math.round(lastDriftMs)+" ms\nGlobal Offset: "+globalOffsetMs+" ms\nTrack Offset: "+trackOffsetMs+" ms\nLyrics source: "+lyricsSource+"\nLyrics lines: "+lyrics.size()+"\nLocal Server: "+lanUrl+"\nConnected car clients: "+carClients;}
    private static long clamp(long v,long a,long b){return Math.max(a,Math.min(b,v));} private static String nz(String s){return s==null?"":s;}
}
