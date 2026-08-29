from pathlib import Path
import sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('project')
base = root / 'app/src/main/java/com/teslalyrics/app'
media = base / 'MediaSessionMonitor.java'
lyrics = base / 'MultiLyricsFetcher.java'

# ---- MediaSession: extract NetEase song id from every sane MediaSession location ----
s = media.read_text()
old = '''    private static final String[] NETEASE_ID_KEYS={
            "com.netease.cloudmusic.music_id","music_id","songId","song_id","id"
    };'''
new = '''    private static final String[] NETEASE_ID_KEYS={
            "com.netease.cloudmusic.music_id","com.netease.cloudmusic.musicId",
            "music_id","musicId","musicID","songId","song_id",
            "trackId","track_id","resourceId","resource_id","id"
    };'''
assert old in s, 'NETEASE_ID_KEYS anchor missing'
s = s.replace(old, new, 1)

old = '''        String id=extractNumericId(raw);
        if(id.isEmpty()){
            for(String key:NETEASE_ID_KEYS){
                id=extractNumericId(string(md,key));
                if(!id.isEmpty())break;
            }
        }
        if(id.isEmpty())id=neteaseIdFromQueue(c,ps,title,artist);'''
new = '''        String id=extractNumericId(raw);
        if(id.isEmpty())id=neteaseIdFromBundle(md.getBundle());
        if(id.isEmpty())id=neteaseIdFromDescription(md.getDescription());
        if(id.isEmpty()){
            try{id=neteaseIdFromBundle(c.getExtras());}catch(Exception ignored){}
        }
        if(id.isEmpty()&&ps!=null){
            try{id=neteaseIdFromBundle(ps.getExtras());}catch(Exception ignored){}
        }
        if(id.isEmpty())id=neteaseIdFromQueue(c,ps,title,artist);'''
assert old in s, 'resolveMediaId anchor missing'
s = s.replace(old, new, 1)

old = '''    private static String neteaseIdFromDescription(MediaDescription d){
        if(d==null)return "";
        String id=extractNumericId(d.getMediaId());
        if(!id.isEmpty())return id;
        Bundle e=d.getExtras();
        if(e!=null){
            for(String key:NETEASE_ID_KEYS){
                Object v=e.get(key);
                id=extractNumericId(v==null?"":String.valueOf(v));
                if(!id.isEmpty())return id;
            }
        }
        return "";
    }

    private static String extractNumericId(String raw){'''
new = '''    private static String neteaseIdFromDescription(MediaDescription d){
        if(d==null)return "";
        String id=extractNumericId(d.getMediaId());
        if(!id.isEmpty())return id;
        id=neteaseIdFromBundle(d.getExtras());
        if(!id.isEmpty())return id;
        try{
            if(d.getMediaUri()!=null){
                id=extractNumericId(d.getMediaUri().toString());
                if(!id.isEmpty())return id;
            }
        }catch(Exception ignored){}
        return "";
    }

    private static String neteaseIdFromBundle(Bundle b){
        if(b==null)return "";
        for(String key:NETEASE_ID_KEYS){
            try{
                Object v=b.get(key);
                String id=extractNumericId(v==null?"":String.valueOf(v));
                if(!id.isEmpty())return id;
            }catch(Exception ignored){}
        }
        // NetEase has changed private key names between builds. Only scan keys that
        // clearly look like a song/music/track id, to avoid album ids and durations.
        try{
            for(String key:b.keySet()){
                if(key==null)continue;
                String k=key.toLowerCase(Locale.ROOT);
                boolean mediaWord=k.contains("song")||k.contains("music")||k.contains("track")||k.contains("resource");
                if(!mediaWord||!k.contains("id")||k.contains("album")||k.contains("artist"))continue;
                Object v=b.get(key);
                String id=extractNumericId(v==null?"":String.valueOf(v));
                if(!id.isEmpty())return id;
            }
        }catch(Exception ignored){}
        return "";
    }

    private static String extractNumericId(String raw){'''
assert old in s, 'neteaseIdFromDescription anchor missing'
s = s.replace(old, new, 1)
media.write_text(s)

# ---- Lyrics provider: use POST cloudsearch first, tolerate wrapped lyric payloads ----
s = lyrics.read_text()
old = '''    private JSONObject neteaseSearch(String keyword)throws Exception{
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
    }'''
new = '''    private JSONObject neteaseSearch(String keyword)throws Exception{
        String form="s="+enc(keyword)+"&type=1&offset=0&total=true&limit=18";
        Exception last=null;
        String[] postUrls={
                "https://music.163.com/api/cloudsearch/pc",
                "https://music.163.com/api/search/get/web",
                "https://music.163.com/api/search/get"
        };
        for(String url:postUrls){
            try{
                JSONObject root=new JSONObject(postForm(url,form,"https://music.163.com/search/"));
                JSONObject result=root.optJSONObject("result");
                if(result!=null&&result.optJSONArray("songs")!=null)return root;
            }catch(Exception e){last=e;}
        }
        String[] getUrls={
                "https://music.163.com/api/cloudsearch/pc?"+form,
                "https://music.163.com/api/search/get/web?"+form,
                "https://music.163.com/api/search/get?"+form
        };
        for(String url:getUrls){
            try{
                JSONObject root=new JSONObject(get(url,"https://music.163.com/search/"));
                JSONObject result=root.optJSONObject("result");
                if(result!=null&&result.optJSONArray("songs")!=null)return root;
            }catch(Exception e){last=e;}
        }
        if(last!=null)throw last;
        return new JSONObject();
    }

    private String neteaseLyric(String id)throws Exception{
        JSONObject x=new JSONObject(get("https://music.163.com/api/song/lyric?id="+enc(id)+"&lv=-1&kv=-1&tv=-1&rv=-1&yv=-1","https://music.163.com/"));
        JSONObject inner=x.optJSONObject("data");
        if(inner==null)inner=x;
        JSONObject l=inner.optJSONObject("lrc");
        String plain=l==null?"":l.optString("lyric","");
        if(validLrc(plain))return plain;
        JSONObject y=inner.optJSONObject("yrc");
        String word=y==null?"":y.optString("lyric","");
        String converted=yrcToLrc(word);
        if(validLrc(converted))return converted;
        // Older public endpoint still works for a subset of catalogue entries.
        try{
            JSONObject old=new JSONObject(get("https://music.163.com/api/song/media?id="+enc(id),"https://music.163.com/"));
            String legacy=old.optString("lyric","");
            if(validLrc(legacy))return legacy;
        }catch(Exception ignored){}
        return plain;
    }'''
assert old in s, 'neteaseSearch/lyric anchor missing'
s = s.replace(old, new, 1)

old = '''    private String postJson(String url,String json,String referer)throws Exception{RequestBody body=RequestBody.create(json,MediaType.parse("application/json; charset=utf-8"));Request.Builder b=new Request.Builder().url(url).post(body).header("User-Agent","Mozilla/5.0 TeslaLyrics/1.3").header("Accept","application/json,text/plain,*/*");if(referer!=null)b.header("Referer",referer);try(Response r=http.newCall(b.build()).execute()){if(!r.isSuccessful())throw new Exception("HTTP "+r.code());return r.body()==null?"":r.body().string();}}
    private static String enc(String s){'''
new = '''    private String postJson(String url,String json,String referer)throws Exception{RequestBody body=RequestBody.create(json,MediaType.parse("application/json; charset=utf-8"));Request.Builder b=new Request.Builder().url(url).post(body).header("User-Agent","Mozilla/5.0 TeslaLyrics/1.3").header("Accept","application/json,text/plain,*/*");if(referer!=null)b.header("Referer",referer);try(Response r=http.newCall(b.build()).execute()){if(!r.isSuccessful())throw new Exception("HTTP "+r.code());return r.body()==null?"":r.body().string();}}
    private String postForm(String url,String form,String referer)throws Exception{RequestBody body=RequestBody.create(form,MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"));Request.Builder b=new Request.Builder().url(url).post(body).header("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 Chrome/139.0 Mobile Safari/537.36").header("Accept","application/json,text/plain,*/*").header("Cookie","os=pc; appver=2.10.13; channel=netease;");if(referer!=null)b.header("Referer",referer);try(Response r=http.newCall(b.build()).execute()){if(!r.isSuccessful())throw new Exception("HTTP "+r.code());return r.body()==null?"":r.body().string();}}
    private static String enc(String s){'''
assert old in s, 'postJson anchor missing'
s = s.replace(old, new, 1)
lyrics.write_text(s)

print('patch_lyrics2 applied')
