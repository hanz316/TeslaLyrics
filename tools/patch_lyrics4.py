from pathlib import Path
import sys
root=Path(sys.argv[1]) if len(sys.argv)>1 else Path('project')
p=root/'app/src/main/java/com/teslalyrics/app/MediaSessionMonitor.java'
s=p.read_text()
s=s.replace('if(id.isEmpty())id=neteaseIdFromBundle(md.getBundle());','if(id.isEmpty())id=neteaseIdFromMetadata(md);')
marker='    private static String neteaseIdFromBundle(Bundle b){'
if 'private static String neteaseIdFromMetadata(MediaMetadata md)' not in s:
    if marker not in s: raise SystemExit('bundle helper anchor missing')
    helper='''    private static String neteaseIdFromMetadata(MediaMetadata md){\n        if(md==null)return "";\n        for(String key:NETEASE_ID_KEYS){\n            try{\n                String id=extractNumericId(md.getString(key));\n                if(!id.isEmpty())return id;\n            }catch(Exception ignored){}\n            try{\n                long v=md.getLong(key);\n                String id=extractNumericId(String.valueOf(v));\n                if(!id.isEmpty())return id;\n            }catch(Exception ignored){}\n        }\n        try{\n            for(String key:md.keySet()){\n                if(key==null)continue;\n                String k=key.toLowerCase(Locale.ROOT);\n                boolean mediaWord=k.contains("song")||k.contains("music")||k.contains("track")||k.contains("resource");\n                if(!mediaWord||!k.contains("id")||k.contains("album")||k.contains("artist"))continue;\n                try{\n                    String id=extractNumericId(md.getString(key));\n                    if(!id.isEmpty())return id;\n                }catch(Exception ignored){}\n                try{\n                    String id=extractNumericId(String.valueOf(md.getLong(key)));\n                    if(!id.isEmpty())return id;\n                }catch(Exception ignored){}\n            }\n        }catch(Exception ignored){}\n        return "";\n    }\n\n'''
    s=s.replace(marker,helper+marker,1)
p.write_text(s)
print('patch_lyrics4 applied')
