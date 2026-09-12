#!/usr/bin/env python3
from pathlib import Path
import re, sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'project')

# --- PublicStateRelay: publish an explicit retained idle state instead of leaving the
# last song alive forever when Android has no valid MediaSession. ---
p = root / 'app/src/main/java/com/teslalyrics/app/PublicStateRelay.java'
s = p.read_text()
orig = s
s = s.replace(
    '    private boolean configured=false;\n',
    '    private boolean configured=false;\n    private boolean idlePublished=false;\n'
)
s = s.replace(
    '        lastLyricsEnsureAt=0;\n        WebRtcBridge.get().configure(context);',
    '        lastLyricsEnsureAt=0;\n        idlePublished=false;\n        WebRtcBridge.get().configure(context);'
)
s = s.replace(
    '        String title=f.optString("MediaNowPlayingTitle","").trim();\n        if(title.isEmpty())return;',
    '        String title=f.optString("MediaNowPlayingTitle","").trim();\n        if(title.isEmpty()){publishIdle("empty_metadata");return;}\n        idlePublished=false;'
)
marker = '    public void publishLyrics(String key,String provider,String lrc,int score){\n'
idle_method = '''    public synchronized void publishIdle(String reason){
        if(!configured)return;
        if(idlePublished)return;
        idlePublished=true;
        lastLyricsTrackKey="";
        lastMediaId="";
        lastLyricsEnsureAt=0;
        try{
            JSONObject o=new JSONObject();
            o.put("kind","state");
            o.put("idle",true);
            o.put("title","");
            o.put("artist","");
            o.put("album","");
            o.put("source","");
            o.put("duration",0);
            o.put("elapsed",0);
            o.put("playing",false);
            o.put("sentAtMs",System.currentTimeMillis());
            WebRtcBridge.get().sendState(o);
            AppState.get().log.add("WSS idle: "+(reason==null?"":reason));
        }catch(Exception ignored){}
    }

'''
if marker not in s:
    raise SystemExit('v17 patch: PublicStateRelay marker not found')
s = s.replace(marker, idle_method + marker)
if s == orig:
    raise SystemExit('v17 patch: PublicStateRelay unchanged')
p.write_text(s)

# --- MediaSessionMonitor: re-evaluate every active MediaSession on each tick. The old
# implementation kept publishing one previously-selected session forever, so starting
# playback in another already-existing player could leave the Tesla page on the old song. ---
p = root / 'app/src/main/java/com/teslalyrics/app/MediaSessionMonitor.java'
s = p.read_text()
orig = s
s = s.replace(
    '@Override public void onPlaybackStateChanged(PlaybackState s){publish();}',
    '@Override public void onPlaybackStateChanged(PlaybackState s){scan();}'
)
s = s.replace(
    '            if(current==null)scan();else publish();',
    '            scan();'
)
s = s.replace(
    '        state.setMediaConnected(false,"");\n    }\n\n    public void scan(){',
    '        state.setMediaConnected(false,"");\n        PublicStateRelay.get().publishIdle("monitor_stopped");\n    }\n\n    public void scan(){'
)
s = s.replace(
    '        state.setMediaConnected(false,"");\n        state.setStatus("请先开启通知使用权");\n    }',
    '        state.setMediaConnected(false,"");\n        state.setStatus("请先开启通知使用权");\n        PublicStateRelay.get().publishIdle("media_access_missing");\n    }'
)
s = s.replace(
    '        list.removeIf(c->c==null||c.getMetadata()==null||titleOf(c.getMetadata()).isEmpty());',
    '        list.removeIf(c->c==null||c.getMetadata()==null||titleOf(c.getMetadata()).isEmpty()||terminal(c.getPlaybackState()));'
)
s = s.replace(
    '            state.setMediaConnected(false,"");\n            state.setStatus("等待手机播放器播放");\n            return;',
    '            state.setMediaConnected(false,"");\n            state.setStatus("等待手机播放器播放");\n            PublicStateRelay.get().publishIdle("no_active_session");\n            return;'
)
score_marker = '    private int score(MediaController c){\n'
terminal_method = '''    private static boolean terminal(PlaybackState ps){
        if(ps==null)return false;
        int x=ps.getState();
        return x==PlaybackState.STATE_STOPPED||x==PlaybackState.STATE_NONE||x==PlaybackState.STATE_ERROR;
    }

'''
if score_marker not in s:
    raise SystemExit('v17 patch: MediaSessionMonitor score marker not found')
s = s.replace(score_marker, terminal_method + score_marker)
s = s.replace(
    '            PlaybackState ps=c.getPlaybackState();\n            if(md==null)return;',
    '            PlaybackState ps=c.getPlaybackState();\n            if(terminal(ps)){scan();return;}\n            if(md==null)return;'
)
if s == orig:
    raise SystemExit('v17 patch: MediaSessionMonitor unchanged')
p.write_text(s)

# --- WebView relay cache bust: version-agnostic replacement because reconstructed
# source packages can contain different historical query versions. ---
p = root / 'app/src/main/java/com/teslalyrics/app/WebRtcBridge.java'
s = p.read_text()
orig = s
s = re.sub(r'phone\.html\?v=\d+', 'phone.html?v=17', s)
s = re.sub(r'Relay page loading: primary v\d+', 'Relay page loading: primary v17', s)
s = re.sub(r'Relay page ready v\d+:', 'Relay page ready v17:', s)
s = re.sub(r'Tesla relay MQTT connected v\d+', 'Tesla relay MQTT connected v17', s)
s = re.sub(r'Transport v\d+:', 'Transport v17:', s)
if s != orig:
    p.write_text(s)
else:
    print('v17 patch: WebRtcBridge already current or no legacy version marker')

print('patch_v17_stale applied')
