from pathlib import Path
import re, sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('project')
base = root / 'app/src/main/java/com/teslalyrics/app'
media = base / 'MediaSessionMonitor.java'
lyrics = base / 'MultiLyricsFetcher.java'

# ---------- MediaSession song-id extraction ----------
s = media.read_text()
old = '''    private static final String[] NETEASE_ID_KEYS={
            "com.netease.cloudmusic.music_id","music_id","songId","song_id","id"
    };'''
new = '''    private static final String[] NETEASE_ID_KEYS={
            "com.netease.cloudmusic.music_id","com.netease.cloudmusic.musicId",
            "music_id","musicId","musicID","songId","song_id",
            "trackId","track_id","resourceId","resource_id","id"
    };'''
if old in s:
    s = s.replace(old, new, 1)
elif 'com.netease.cloudmusic.musicId' not in s:
    raise SystemExit('NETEASE_ID_KEYS anchor missing')

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
if old in s:
    s = s.replace(old, new, 1)
elif 'neteaseIdFromBundle(md.getBundle())' not in s:
    raise SystemExit('resolveMediaId anchor missing')

pat = re.compile(r'    private static String neteaseIdFromDescription\(MediaDescription d\)\{.*?\n    \}\n\n    private static String extractNumericId', re.S)
rep = '''    private static String neteaseIdFromDescription(MediaDescription d){
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

    private static String extractNumericId'''
if 'private static String neteaseIdFromBundle(Bundle b)' not in s:
    s, n = pat.subn(rep, s, count=1)
    if n != 1: raise SystemExit('description method anchor missing')
media.write_text(s)

# ---------- NetEase search + lyric fallback ----------
s = lyrics.read_text()
pat = re.compile(r'    private JSONObject neteaseSearch\(String keyword\)throws Exception\{.*?\n    \}\n\n    private String neteaseLyric\(String id\)throws Exception\{.*?\n    \}\n\n    private static String yrcToLrc', re.S)
rep = '''    private JSONObject neteaseSearch(String keyword)throws Exception{
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
        try{
            JSONObject old=new JSONObject(get("https://music.163.com/api/song/media?id="+enc(id),"https://music.163.com/"));
            String legacy=old.optString("lyric","");
            if(validLrc(legacy))return legacy;
        }catch(Exception ignored){}
        return plain;
    }

    private static String yrcToLrc'''
if 'https://music.163.com/api/cloudsearch/pc' not in s:
    s, n = pat.subn(rep, s, count=1)
    if n != 1: raise SystemExit('netease search/lyric anchor missing')

if 'private String postForm(' not in s:
    marker = '    private static String enc(String s){'
    if marker not in s: raise SystemExit('enc anchor missing')
    method = '''    private String postForm(String url,String form,String referer)throws Exception{\n        RequestBody body=RequestBody.create(form,MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"));\n        Request.Builder b=new Request.Builder().url(url).post(body)\n                .header("User-Agent","Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36")\n                .header("Accept","application/json,text/plain,*/*")\n                .header("Cookie","os=pc; appver=9.4.70; channel=netease;");\n        if(referer!=null)b.header("Referer",referer);\n        try(Response r=http.newCall(b.build()).execute()){\n            if(!r.isSuccessful())throw new Exception("HTTP "+r.code());\n            return r.body()==null?"":r.body().string();\n        }\n    }\n'''
    s = s.replace(marker, method + marker, 1)
lyrics.write_text(s)
print('patch_lyrics3 applied')
