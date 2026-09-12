package com.teslalyrics.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches synced translation / romaji once per track, caches it, and publishes a
 * merged multi-line LRC through the existing lyrics transport.  No per-line
 * network traffic is generated: the Tesla page advances locally from timestamps.
 */
public final class TranslationFetcher {
    private static final TranslationFetcher I = new TranslationFetcher();
    public static TranslationFetcher get(){ return I; }

    private static final Pattern ID = Pattern.compile("(?<!\\d)(\\d{5,})(?!\\d)");
    private static final Pattern TS = Pattern.compile("^\\[(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)\\]\\s?(.*)$");
    private static final long NEGATIVE_TTL_MS = 10 * 60 * 1000L;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(7, TimeUnit.SECONDS)
            .readTimeout(9, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService delayed = Executors.newSingleThreadScheduledExecutor();
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, Extras> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> negativeUntil = new ConcurrentHashMap<>();
    private volatile String latestKey = "";
    private volatile String lastPublishedKey = "";

    private static final class Extras {
        final String original, translation, romaji, provider;
        Extras(String o, String t, String r, String p){
            original = nz(o); translation = nz(t); romaji = nz(r); provider = nz(p);
        }
    }

    private static final class Song {
        String id, title, artist;
        long duration;
        double score;
    }

    private static final class Line {
        final long t; final String text;
        Line(long t, String text){ this.t=t; this.text=text; }
    }

    private TranslationFetcher(){}

    public void ensure(JSONObject frame, String trackKey){
        if(frame==null || trackKey==null || trackKey.isEmpty()) return;
        final String title = frame.optString("MediaNowPlayingTitle", "").trim();
        if(title.isEmpty()) return;
        final String artist = frame.optString("MediaNowPlayingArtist", "").trim();
        final long duration = Math.max(0, frame.optLong("MediaNowPlayingDuration", 0));
        final String mediaId = extractId(frame.optString("MediaMediaId", ""));
        latestKey = trackKey;

        Extras c = cache.get(trackKey);
        if(c != null){
            if(!trackKey.equals(lastPublishedKey)) publish(trackKey, c, false);
            return;
        }
        Long until = negativeUntil.get(trackKey);
        if(until != null && until > System.currentTimeMillis()) return;
        if(!loading.add(trackKey)) return;

        pool.execute(() -> {
            try{
                Extras x = fetch(title, artist, duration, mediaId);
                if(!trackKey.equals(latestKey)) return;
                if(x == null || (countTimed(x.translation) < 2 && countTimed(x.romaji) < 2)){
                    negativeUntil.put(trackKey, System.currentTimeMillis() + NEGATIVE_TTL_MS);
                    return;
                }
                cache.put(trackKey, x);
                if(cache.size() > 96) cache.clear();
                publish(trackKey, x, false);
                // Multi-source base lyrics can finish a little later; republish once after
                // its 9.5 s provider window so translated lyrics remain the latest replay.
                delayed.schedule(() -> {
                    if(trackKey.equals(latestKey)) publish(trackKey, x, true);
                }, 10500, TimeUnit.MILLISECONDS);
            }catch(Exception e){
                AppState.get().log.add("Translation: " + e.getClass().getSimpleName());
                negativeUntil.put(trackKey, System.currentTimeMillis() + 120000L);
            }finally{
                loading.remove(trackKey);
            }
        });
    }

    private void publish(String key, Extras x, boolean delayedPass){
        String merged = merge(x.original, x.translation, x.romaji);
        if(countTimed(merged) < 4) return;
        lastPublishedKey = key;
        String label = x.provider + (countTimed(x.translation)>=2 ? "·译文" : "") + (countTimed(x.romaji)>=2 ? "·罗马音" : "");
        WebRtcBridge.get().sendLyrics(key, label, merged, 99);
        AppState.get().log.add("Translation lyrics: " + label + (delayedPass ? " (final)" : ""));
    }

    private Extras fetch(String title, String artist, long duration, String exactId) throws Exception{
        String id = exactId;
        String provider = "网易云";
        if(id.isEmpty()){
            Song s = searchBest(title, artist, duration);
            if(s == null || s.score < 80) return null;
            id = s.id;
        }else provider = "网易云原曲";

        JSONObject root = new JSONObject(get("https://music.163.com/api/song/lyric?id=" + enc(id) + "&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1"));
        String original = lyricField(root, "lrc");
        if(countTimed(original) < 4){
            String yrc = lyricField(root, "yrc");
            original = yrcToLrc(yrc);
        }
        if(countTimed(original) < 4) return null;
        String translation = lyricField(root, "tlyric");
        String romaji = lyricField(root, "romalrc");
        return new Extras(original, translation, romaji, provider);
    }

    private Song searchBest(String title, String artist, long duration) throws Exception{
        String keyword = (title + " " + artist).trim();
        JSONObject root = new JSONObject(get("https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=" + enc(keyword) + "&type=1&offset=0&total=true&limit=12"));
        JSONObject result = root.optJSONObject("result");
        JSONArray songs = result == null ? null : result.optJSONArray("songs");
        if(songs == null) return null;
        List<Song> all = new ArrayList<>();
        for(int i=0;i<Math.min(12, songs.length());i++){
            JSONObject o = songs.optJSONObject(i); if(o==null) continue;
            Song s = new Song();
            s.id = String.valueOf(o.optLong("id",0));
            s.title = o.optString("name","");
            s.artist = joinNames(o.optJSONArray("artists"));
            if(s.artist.isEmpty()) s.artist = joinNames(o.optJSONArray("ar"));
            s.duration = o.optLong("duration", o.optLong("dt",0));
            s.score = score(s, title, artist, duration);
            if(!"0".equals(s.id)) all.add(s);
        }
        all.sort(Comparator.comparingDouble((Song s)->s.score).reversed());
        return all.isEmpty()?null:all.get(0);
    }

    private static double score(Song s, String title, String artist, long duration){
        String a = norm(s.title), b = norm(title);
        if(a.isEmpty() || b.isEmpty()) return 0;
        double titleScore = a.equals(b) ? 62 : (a.contains(b)||b.contains(a) ? 52 : 0);
        if(titleScore == 0) return 0;
        String sa = norm(firstArtist(s.artist)), ta = norm(firstArtist(artist));
        double artistScore = ta.isEmpty() ? 12 : (sa.equals(ta) ? 24 : (sa.contains(ta)||ta.contains(sa) ? 18 : 0));
        if(!ta.isEmpty() && artistScore==0) return 0;
        double d = 8;
        if(duration>0 && s.duration>0){
            long diff = Math.abs(duration-s.duration);
            if(diff<=1800) d=26; else if(diff<=4000) d=21; else if(diff<=8000) d=13; else if(diff<=15000) d=5; else d=-30;
        }
        return titleScore + artistScore + d;
    }

    private static String merge(String original, String translation, String romaji){
        List<Line> o = parse(original), t = parse(translation), r = parse(romaji);
        StringBuilder out = new StringBuilder();
        for(Line x:o){
            String ts = fmtTs(x.t);
            out.append(ts).append(x.text).append('\n');
            Line tx = nearest(t, x.t, 420);
            if(tx!=null && !sameText(tx.text, x.text)) out.append(ts).append(tx.text).append('\n');
            Line rx = nearest(r, x.t, 420);
            if(rx!=null && !sameText(rx.text, x.text) && (tx==null || !sameText(rx.text, tx.text))) out.append(ts).append(rx.text).append('\n');
        }
        return out.toString();
    }

    private static List<Line> parse(String lrc){
        List<Line> out = new ArrayList<>();
        if(lrc==null) return out;
        for(String row:lrc.split("\\r?\\n")){
            Matcher m=TS.matcher(row.trim()); if(!m.matches()) continue;
            try{
                long ms=Math.round((Long.parseLong(m.group(1))*60 + Double.parseDouble(m.group(2)))*1000);
                String text=nz(m.group(3)).trim();
                if(!text.isEmpty()) out.add(new Line(ms,text));
            }catch(Exception ignored){}
        }
        out.sort(Comparator.comparingLong(x->x.t));
        return out;
    }

    private static Line nearest(List<Line> list, long t, long max){
        Line best=null; long diff=Long.MAX_VALUE;
        for(Line x:list){
            long d=Math.abs(x.t-t);
            if(d<diff){ diff=d; best=x; }
            if(x.t>t+max) break;
        }
        return diff<=max?best:null;
    }

    private static String fmtTs(long ms){
        long min=ms/60000L, rem=ms%60000L, sec=rem/1000L, milli=rem%1000L;
        return String.format(Locale.US,"[%02d:%02d.%03d]",min,sec,milli);
    }

    private static String lyricField(JSONObject root, String key){
        JSONObject x=root.optJSONObject(key); return x==null?"":x.optString("lyric","");
    }

    private static String yrcToLrc(String yrc){
        if(yrc==null||yrc.isEmpty()) return "";
        Pattern line=Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
        Pattern word=Pattern.compile("\\(\\d+,\\d+,\\d+\\)");
        StringBuilder out=new StringBuilder();
        for(String row:yrc.split("\\r?\\n")){
            Matcher m=line.matcher(row.trim()); if(!m.matches()) continue;
            try{
                long start=Long.parseLong(m.group(1));
                String text=word.matcher(m.group(3)).replaceAll("").trim();
                if(!text.isEmpty()) out.append(fmtTs(start)).append(text).append('\n');
            }catch(Exception ignored){}
        }
        return out.toString();
    }

    private String get(String url) throws Exception{
        Request req=new Request.Builder().url(url).get()
                .header("Accept","application/json,text/plain,*/*")
                .header("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 TeslaLyrics/1.5")
                .header("Referer","https://music.163.com/")
                .header("Cookie","os=pc; appver=9.4.70; channel=netease;")
                .build();
        try(Response r=http.newCall(req).execute()){
            if(!r.isSuccessful()||r.body()==null) throw new IllegalStateException("HTTP "+r.code());
            return r.body().string();
        }
    }

    private static int countTimed(String lrc){ return parse(lrc).size(); }
    private static boolean sameText(String a,String b){ return norm(a).equals(norm(b)); }
    private static String norm(String s){ return nz(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]",""); }
    private static String firstArtist(String s){ String[] p=nz(s).split("[/／、,&，+]",2); return p.length==0?"":p[0].trim(); }
    private static String joinNames(JSONArray a){
        if(a==null)return ""; StringBuilder b=new StringBuilder();
        for(int i=0;i<a.length();i++){ JSONObject x=a.optJSONObject(i); if(x==null)continue; String n=x.optString("name",""); if(n.isEmpty())continue; if(b.length()>0)b.append('/'); b.append(n); }
        return b.toString();
    }
    private static String extractId(String raw){ Matcher m=ID.matcher(nz(raw)); return m.find()?m.group(1):""; }
    private static String enc(String s){ return URLEncoder.encode(nz(s), StandardCharsets.UTF_8); }
    private static String nz(String s){ return s==null?"":s; }
}
