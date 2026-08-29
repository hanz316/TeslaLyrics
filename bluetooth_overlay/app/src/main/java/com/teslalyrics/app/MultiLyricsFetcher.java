package com.teslalyrics.app;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MultiLyricsFetcher {
    private static final MultiLyricsFetcher I=new MultiLyricsFetcher();
    public static MultiLyricsFetcher get(){return I;}

    private final OkHttpClient http=new OkHttpClient.Builder()
            .connectTimeout(7,TimeUnit.SECONDS)
            .readTimeout(9,TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final ExecutorService trackPool=Executors.newFixedThreadPool(2);
    private final ExecutorService providerPool=Executors.newFixedThreadPool(6);
    private final Set<String> done=Collections.synchronizedSet(new HashSet<>());
    private final Set<String> loading=Collections.synchronizedSet(new HashSet<>());
    private volatile String latestKey="",publishedKey="",publishedProvider="",publishedLrc="";
    private volatile int publishedScore=0;
    private static final int MAX_DONE=96;
    private static final Pattern TS=Pattern.compile("\\[(\\d{1,3}):(\\d{1,2}(?:\\.\\d{1,3})?)\\]");
    private static final Pattern BRACKETS=Pattern.compile("\\s*[\\(（\\[【].*?[\\)）\\]】]\\s*");
    private static final Pattern NETEASE_ID=Pattern.compile("(?<!\\d)(\\d{5,})(?!\\d)");
    private static final Pattern YRC_LINE=Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern YRC_WORD=Pattern.compile("\\(\\d+,\\d+,\\d+\\)");

    private static final class Candidate {
        String provider,title,artist,lrc;
        long durationMs;
        double score;
        Candidate(String p,String t,String a,long d,String l){provider=p;title=nz(t);artist=nz(a);durationMs=d;lrc=nz(l);}
    }

    public void ensure(JSONObject f){
        String title=f.optString("MediaNowPlayingTitle","").trim();
        if(title.isEmpty())return;
        String artist=f.optString("MediaNowPlayingArtist","").trim();
        String album=f.optString("MediaNowPlayingAlbum","").trim();
        long duration=Math.max(0,f.optLong("MediaNowPlayingDuration",0));
        String source=f.optString("MediaPlaybackSource","");
        String mediaId=extractNeteaseId(f.optString("MediaMediaId",""));
        String key=title+"|"+artist+"|"+album+"|"+duration;
        latestKey=key;

        // The NetEase session can publish title first and its exact song ID a moment later.
        // If another provider already won, allow one exact-ID upgrade without changing the canonical key.
        boolean neteaseUpgrade=source.contains("网易云")&&!mediaId.isEmpty()&&key.equals(publishedKey)&&!publishedProvider.contains("网易云");
        if(done.contains(key)&&!neteaseUpgrade)return;
        if(!loading.add(key))return;
        trackPool.execute(()->fetch(key,title,artist,album,duration,source,mediaId));
    }

    /** Re-send the already matched lyrics after the Tesla page reconnects; no provider request is repeated. */
    public void republishLatest(){
        String key=publishedKey,lrc=publishedLrc,provider=publishedProvider;
        int score=publishedScore;
        if(!key.isEmpty()&&key.equals(latestKey)&&validLrc(lrc))PublicStateRelay.get().publishLyrics(key,provider,lrc,score);
    }

    private void fetch(String key,String title,String artist,String album,long duration,String playerSource,String mediaId){
        try{
            List<Callable<List<Candidate>>> tasks=new ArrayList<>();
            tasks.add(()->fetchLrclib(title,artist,duration));
            tasks.add(()->fetchLrcApi(title,artist,album));
            tasks.add(()->fetchNetease(title,artist,duration,mediaId));
            tasks.add(()->fetchQq(title,artist,duration));
            tasks.add(()->fetchKugou(title,artist,duration));
            List<Candidate> all=new ArrayList<>();
            try{
                List<Future<List<Candidate>>> futures=providerPool.invokeAll(tasks,9500,TimeUnit.MILLISECONDS);
                for(Future<List<Candidate>> f:futures){
                    if(f.isCancelled())continue;
                    try{List<Candidate> x=f.get();if(x!=null)for(Candidate c:x)if(c!=null&&validLrc(c.lrc))all.add(c);}catch(Exception ignored){}
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            if(!key.equals(latestKey))return;

            for(Candidate c:all)c.score=score(c,title,artist,duration,playerSource);
            all.sort(Comparator.comparingDouble((Candidate c)->c.score).reversed());
            Candidate best=all.isEmpty()?null:all.get(0);
            if(best!=null&&best.score>=78&&validLrc(best.lrc)){
                rememberDone(key);
                publishedKey=key;publishedProvider=best.provider;publishedLrc=best.lrc;publishedScore=(int)Math.round(best.score);
                AppState.get().log.add("Lyrics matched: "+best.provider+" score="+publishedScore);
                PublicStateRelay.get().publishLyrics(key,best.provider,best.lrc,publishedScore);
            }else AppState.get().log.add("Lyrics multi-source: no safe match"+(best==null?"":" best="+best.provider+" "+Math.round(best.score)));
        }finally{loading.remove(key);}
    }

    private void rememberDone(String key){
        synchronized(done){
            done.add(key);
            if(done.size()>MAX_DONE){Iterator<String> it=done.iterator();while(done.size()>MAX_DONE&&it.hasNext()){it.next();it.remove();}}
        }
    }

    private List<Candidate> fetchLrclib(String title,String artist,long duration){
        List<Candidate> out=new ArrayList<>();
        try{
            for(String[] q:queries(title,artist)){
                JSONArray a=new JSONArray(get("https://lrclib.net/api/search?track_name="+enc(q[0])+"&artist_name="+enc(q[1]),null));
                for(int i=0;i<Math.min(12,a.length());i++){
                    JSONObject x=a.optJSONObject(i);if(x==null)continue;
                    String l=x.optString("syncedLyrics","");if(!validLrc(l))continue;
                    out.add(new Candidate("LRCLIB",x.optString("trackName",q[0]),x.optString("artistName",q[1]),Math.round(x.optDouble("duration",0)*1000),l));
                }
                if(!out.isEmpty())break;
            }
        }catch(Exception e){AppState.get().log.add("LRCLIB: "+e.getClass().getSimpleName());}
        return out;
    }

    private List<Candidate> fetchLrcApi(String title,String artist,String album){
        List<Candidate> out=new ArrayList<>();
        try{
            for(String[] q:queries(title,artist)){
                String url="https://api.lrc.cx/api/v1/lyrics/advance?title="+enc(q[0])+"&artist="+enc(q[1])+(album.isEmpty()?"":"&album="+enc(album));
                JSONArray a=new JSONArray(get(url,null));
                for(int i=0;i<Math.min(12,a.length());i++){
                    JSONObject x=a.optJSONObject(i);if(x==null)continue;
                    String l=x.optString("lyrics","");if(validLrc(l))out.add(new Candidate("LrcAPI/酷狗聚合",x.optString("title",q[0]),x.optString("artist",q[1]),0,l));
                }
                if(!out.isEmpty())break;
            }
        }catch(Exception e){AppState.get().log.add("LrcAPI: "+e.getClass().getSimpleName());}
        return out;
    }

    private List<Candidate> fetchNetease(String title,String artist,long duration,String mediaId){
        List<Candidate> out=new ArrayList<>();
        try{
            if(!mediaId.isEmpty()){
                String l=neteaseLyric(mediaId);
                if(validLrc(l)){
                    AppState.get().log.add("NetEase lyrics exact id: "+mediaId);
                    out.add(new Candidate("网易云音乐·原曲ID",title,artist,duration,l));
                }
            }
            if(!out.isEmpty())return out;
            for(String[] q:queries(title,artist)){
                String keyword=(q[0]+" "+q[1]).trim();
                JSONObject root=neteaseSearch(keyword);
                JSONObject result=root.optJSONObject("result");
                JSONArray songs=result==null?null:result.optJSONArray("songs");
                if(songs==null)continue;
                for(int i=0;i<Math.min(12,songs.length());i++){
                    JSONObject s=songs.optJSONObject(i);if(s==null)continue;
                    String n=s.optString("name","");
                    String ar=joinNames(s.optJSONArray("artists"));
                    if(ar.isEmpty())ar=joinNames(s.optJSONArray("ar"));
                    long d=s.optLong("duration",s.optLong("dt",0));
                    String id=String.valueOf(s.optLong("id",0));
                    if("0".equals(id)||metaScore(n,ar,d,title,artist,duration)<58)continue;
                    String l=neteaseLyric(id);
                    if(validLrc(l))out.add(new Candidate("网易云音乐",n,ar,d,l));
                    if(out.size()>=5)break;
                }
                if(!out.isEmpty())break;
            }
        }catch(Exception e){AppState.get().log.add("NetEase: "+e.getClass().getSimpleName());}
        return out;
    }

    private JSONObject neteaseSearch(String keyword)throws Exception{
        String base="https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s="+enc(keyword)+"&type=1&offset=0&total=true&limit=18";
        JSONObject root=new JSONObject(get(base,"https://music.163.com/"));
        JSONObject result=root.optJSONObject("result");
        if(result!=null&&result.optJSONArray("songs")!=null)return root;
        String fallback="https://music.163.com/api/search/get?s="+enc(keyword)+"&type=1&offset=0&total=true&limit=18";
        return new JSONObject(get(fallback,"https://music.163.com/"));
    }

    private String neteaseLyric(String id)throws Exception{
        JSONObject x=new JSONObject(get("https://music.163.com/api/song/lyric?id="+enc(id)+"&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1","https://music.163.com/"));
        JSONObject l=x.optJSONObject("lrc");
        String plain=l==null?"":l.optString("lyric","");
        if(validLrc(plain))return plain;
        JSONObject y=x.optJSONObject("yrc");
        String word=y==null?"":y.optString("lyric","");
        String converted=yrcToLrc(word);
        return validLrc(converted)?converted:plain;
    }

    private static String yrcToLrc(String yrc){
        if(yrc==null||yrc.isEmpty())return "";
        StringBuilder out=new StringBuilder();
        String[] lines=yrc.split("\\r?\\n");
        for(String line:lines){
            Matcher m=YRC_LINE.matcher(line.trim());
            if(!m.matches())continue;
            long start;
            try{start=Long.parseLong(m.group(1));}catch(Exception e){continue;}
            String text=YRC_WORD.matcher(m.group(3)).replaceAll("").trim();
            if(text.isEmpty())continue;
            long min=start/60000L;
            long rem=start%60000L;
            long sec=rem/1000L;
            long ms=rem%1000L;
            out.append(String.format(Locale.US,"[%02d:%02d.%03d]",min,sec,ms)).append(text).append('\n');
        }
        return out.toString();
    }

    private List<Candidate> fetchQq(String title,String artist,long duration){
        List<Candidate> out=new ArrayList<>();
        try{
            for(String[] q:queries(title,artist)){
                JSONObject body=new JSONObject();
                JSONObject comm=new JSONObject().put("ct","19").put("cv","1859").put("uin","0");
                JSONObject param=new JSONObject().put("grp",1).put("num_per_page",12).put("page_num",1).put("query",(q[0]+" "+q[1]).trim()).put("search_type",0);
                JSONObject req=new JSONObject().put("method","DoSearchForQQMusicDesktop").put("module","music.search.SearchCgiService").put("param",param);
                body.put("comm",comm).put("req",req);
                JSONObject root=new JSONObject(postJson("https://u.y.qq.com/cgi-bin/musicu.fcg",body.toString(),"https://y.qq.com/"));
                JSONObject r=root.optJSONObject("req"),data=r==null?null:r.optJSONObject("data"),b=data==null?null:data.optJSONObject("body"),song=b==null?null:b.optJSONObject("song");JSONArray list=song==null?null:song.optJSONArray("list");if(list==null)continue;
                for(int i=0;i<Math.min(10,list.length());i++){
                    JSONObject s=list.optJSONObject(i);if(s==null)continue;
                    String n=s.optString("title",s.optString("name","")),ar=joinNames(s.optJSONArray("singer")),mid=s.optString("mid","");long d=s.optLong("interval",0)*1000L;
                    if(mid.isEmpty()||metaScore(n,ar,d,title,artist,duration)<58)continue;
                    JSONObject lx=new JSONObject(get("https://i.y.qq.com/lyric/fcgi-bin/fcg_query_lyric_new.fcg?songmid="+enc(mid)+"&g_tk=5381&format=json&inCharset=utf8&outCharset=utf-8&nobase64=1","https://y.qq.com/"));String l=lx.optString("lyric","");
                    if(!l.contains("[")&&!l.isEmpty())try{l=new String(Base64.decode(l,Base64.DEFAULT),StandardCharsets.UTF_8);}catch(Exception ignored){}
                    if(validLrc(l))out.add(new Candidate("QQ音乐",n,ar,d,l));if(out.size()>=4)break;
                }
                if(!out.isEmpty())break;
            }
        }catch(Exception e){AppState.get().log.add("QQ Lyrics: "+e.getClass().getSimpleName());}
        return out;
    }

    private List<Candidate> fetchKugou(String title,String artist,long duration){
        List<Candidate> out=new ArrayList<>();
        try{
            for(String[] q:queries(title,artist)){
                JSONObject sr=new JSONObject(get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=12&page=1&keyword="+enc((q[0]+" "+q[1]).trim()),null));
                JSONObject data=sr.optJSONObject("data");JSONArray info=data==null?null:data.optJSONArray("info");if(info==null)continue;
                for(int i=0;i<Math.min(10,info.length());i++){
                    JSONObject s=info.optJSONObject(i);if(s==null)continue;
                    String[] pa=splitFilename(s.optString("filename",""));String n=pa[1],ar=pa[0],hash=s.optString("hash","");long d=s.optLong("duration",0)*1000L;
                    if(hash.isEmpty()||metaScore(n,ar,d,title,artist,duration)<58)continue;
                    JSONObject cr=new JSONObject(get("https://krcs.kugou.com/search?ver=1&man=yes&client=mobi&keyword=&duration=&hash="+enc(hash)+"&album_audio_id=",null));JSONArray cand=cr.optJSONArray("candidates");if(cand==null||cand.length()==0)continue;JSONObject c=cand.optJSONObject(0);if(c==null)continue;
                    JSONObject dr=new JSONObject(get("https://lyrics.kugou.com/download?ver=1&client=pc&id="+enc(c.optString("id",""))+"&accesskey="+enc(c.optString("accesskey",""))+"&fmt=lrc&charset=utf8",null));String content=dr.optString("content",""),l="";
                    if(!content.isEmpty())try{l=new String(Base64.decode(content,Base64.DEFAULT),StandardCharsets.UTF_8);}catch(Exception ignored){}
                    if(validLrc(l))out.add(new Candidate("酷狗音乐",n,ar,d,l));if(out.size()>=4)break;
                }
                if(!out.isEmpty())break;
            }
        }catch(Exception e){AppState.get().log.add("KuGou Lyrics: "+e.getClass().getSimpleName());}
        return out;
    }

    private double score(Candidate c,String title,String artist,long duration,String playerSource){
        double s=metaScore(c.title,c.artist,c.durationMs,title,artist,duration);
        if(c.provider.contains("网易云")&&playerSource.contains("网易云"))s+=24;
        if(c.provider.contains("QQ")&&playerSource.contains("QQ"))s+=18;
        if(c.provider.contains("LRCLIB"))s+=4;
        int count=timeLineCount(c.lrc);if(count>=20)s+=5;else if(count<6)s-=30;
        if(duration>0){long last=lastTimestamp(c.lrc);if(last>0){long diff=Math.abs(duration-last);if(diff<=25000)s+=10;else if(diff<=45000)s+=3;else if(diff>75000)s-=35;}}
        return s;
    }

    private double metaScore(String ct,String ca,long cd,String title,String artist,long duration){
        double ts=similarity(cleanTitle(ct),cleanTitle(title)),as=artistSimilarity(ca,artist);
        if(ts<0.55)return 0;if(!cleanArtist(artist).isEmpty()&&as<0.30)return 0;
        double s=ts*55+as*25;
        if(duration>0&&cd>0){long diff=Math.abs(duration-cd);if(diff<=1800)s+=28;else if(diff<=4000)s+=22;else if(diff<=8000)s+=12;else if(diff<=15000)s+=3;else s-=45;}else s+=6;
        return s;
    }

    private static List<String[]> queries(String title,String artist){
        List<String[]> out=new ArrayList<>();String full=nz(artist).trim(),first=firstArtist(full),clean=cleanTitle(title);
        addQuery(out,title,full);if(!first.equals(full))addQuery(out,title,first);if(!norm(clean).equals(norm(title))){addQuery(out,clean,full);if(!first.equals(full))addQuery(out,clean,first);}return out;
    }
    private static void addQuery(List<String[]> out,String t,String a){String key=norm(t)+"|"+norm(a);for(String[] q:out)if((norm(q[0])+"|"+norm(q[1])).equals(key))return;out.add(new String[]{t,a});}
    private static String cleanTitle(String s){String x=nz(s);x=BRACKETS.matcher(x).replaceAll(" ");x=x.replaceAll("(?i)\\s*[-–—:]?\\s*(live|remaster(?:ed)?|version|ver\\.?|伴奏|纯音乐|翻唱|现场版|完整版|剪辑版|加速版|sped up|slowed).*?$","");return x.trim();}
    private static String cleanArtist(String s){return norm(firstArtist(s));}
    private static String firstArtist(String s){String x=nz(s).replaceAll("(?i)\\s+(feat\\.?|ft\\.?|featuring)\\s+.*$","");String[] p=x.split("[/／、,&，+]|\\s+x\\s+",2);return p.length==0?x.trim():p[0].trim();}
    private static String norm(String s){return nz(s).toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static double artistSimilarity(String a,String b){String x=cleanArtist(a),y=cleanArtist(b);if(x.isEmpty()||y.isEmpty())return 0.45;if(x.equals(y))return 1;if(x.contains(y)||y.contains(x))return 0.82;return similarity(x,y);}
    private static double similarity(String a,String b){String x=norm(a),y=norm(b);if(x.isEmpty()||y.isEmpty())return 0;if(x.equals(y))return 1;if(x.contains(y)||y.contains(x))return Math.min(x.length(),y.length())/(double)Math.max(x.length(),y.length())*.92;Set<String> A=bigrams(x),B=bigrams(y);if(A.isEmpty()||B.isEmpty())return 0;int inter=0;for(String z:A)if(B.contains(z))inter++;return 2.0*inter/(A.size()+B.size());}
    private static Set<String> bigrams(String s){Set<String> o=new HashSet<>();if(s.length()==1){o.add(s);return o;}for(int i=0;i<s.length()-1;i++)o.add(s.substring(i,i+2));return o;}
    private static String joinNames(JSONArray a){if(a==null)return "";StringBuilder b=new StringBuilder();for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;String n=x.optString("name","");if(n.isEmpty())continue;if(b.length()>0)b.append('/');b.append(n);}return b.toString();}
    private static String[] splitFilename(String fn){int i=fn.indexOf(" - ");if(i>0)return new String[]{fn.substring(0,i).trim(),fn.substring(i+3).trim()};return new String[]{"",fn};}
    private static boolean validLrc(String l){return timeLineCount(l)>=4;}
    private static int timeLineCount(String l){if(l==null)return 0;Matcher m=TS.matcher(l);int n=0;while(m.find()&&n<500)n++;return n;}
    private static long lastTimestamp(String l){if(l==null)return 0;Matcher m=TS.matcher(l);long last=0;while(m.find()){try{long t=Math.round((Long.parseLong(m.group(1))*60+Double.parseDouble(m.group(2)))*1000);if(t>last)last=t;}catch(Exception ignored){}}return last;}
    private static String extractNeteaseId(String raw){if(raw==null||raw.isEmpty())return "";Matcher m=NETEASE_ID.matcher(raw);return m.find()?m.group(1):"";}

    private String get(String url,String referer)throws Exception{
        Request.Builder b=new Request.Builder().url(url).get().header("Accept","application/json,text/plain,*/*");
        if(url.contains("music.163.com")){
            b.header("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36");
            b.header("Cookie","os=pc; appver=9.4.70; channel=netease;");
        }else b.header("User-Agent","Mozilla/5.0 TeslaLyrics/1.4");
        if(referer!=null)b.header("Referer",referer);
        try(Response r=http.newCall(b.build()).execute()){
            if(!r.isSuccessful())throw new Exception("HTTP "+r.code());
            return r.body()==null?"":r.body().string();
        }
    }
    private String postJson(String url,String json,String referer)throws Exception{RequestBody body=RequestBody.create(json,MediaType.parse("application/json; charset=utf-8"));Request.Builder b=new Request.Builder().url(url).post(body).header("User-Agent","Mozilla/5.0 TeslaLyrics/1.4").header("Accept","application/json,text/plain,*/*");if(referer!=null)b.header("Referer",referer);try(Response r=http.newCall(b.build()).execute()){if(!r.isSuccessful())throw new Exception("HTTP "+r.code());return r.body()==null?"":r.body().string();}}
    private static String enc(String s){try{return URLEncoder.encode(nz(s),"UTF-8");}catch(Exception e){return nz(s);}}
    private static String nz(String s){return s==null?"":s;}
}
