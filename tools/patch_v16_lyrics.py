#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else 'project')
p = root / 'app/src/main/java/com/teslalyrics/app/MultiLyricsFetcher.java'
s = p.read_text()
orig = s

# Never treat an arbitrary numeric media id from another player as a NetEase id.
s = s.replace(
    'String mediaId=extractNeteaseId(f.optString("MediaMediaId",""));',
    'String mediaId=source.contains("网易云")?extractNeteaseId(f.optString("MediaMediaId","")):"";'
)

# Search title-only variants too. This is important for soundtrack metadata, featured artists,
# Apple Music artist formatting and localized artist names.
old_queries = '''    private static List<String[]> queries(String title,String artist){\n        List<String[]> out=new ArrayList<>();String full=nz(artist).trim(),first=firstArtist(full),clean=cleanTitle(title);\n        addQuery(out,title,full);if(!first.equals(full))addQuery(out,title,first);if(!norm(clean).equals(norm(title))){addQuery(out,clean,full);if(!first.equals(full))addQuery(out,clean,first);}return out;\n    }'''
new_queries = '''    private static List<String[]> queries(String title,String artist){\n        List<String[]> out=new ArrayList<>();String full=nz(artist).trim(),first=firstArtist(full),clean=cleanTitle(title);\n        addQuery(out,title,full);\n        if(!first.equals(full))addQuery(out,title,first);\n        addQuery(out,title,"");\n        if(!norm(clean).equals(norm(title))){\n            addQuery(out,clean,full);\n            if(!first.equals(full))addQuery(out,clean,first);\n            addQuery(out,clean,"");\n        }\n        return out;\n    }'''
if old_queries not in s:
    raise SystemExit('v16 patch: queries block not found')
s = s.replace(old_queries, new_queries)

# When artist is intentionally omitted, do not keep an album constraint that can make a
# valid lyric search return zero results.
s = s.replace(
    'String url="https://api.lrc.cx/api/v1/lyrics/advance?title="+enc(q[0])+"&artist="+enc(q[1])+(album.isEmpty()?"":"&album="+enc(album));',
    'String url="https://api.lrc.cx/api/v1/lyrics/advance?title="+enc(q[0])+"&artist="+enc(q[1])+(q[1].isEmpty()||album.isEmpty()?"":"&album="+enc(album));'
)

# Let providers return plausible candidates; final scoring remains the safety gate.
s = s.replace('metaScore(n,ar,d,title,artist,duration)<58', 'metaScore(n,ar,d,title,artist,duration)<48')

old_meta = '''    private double metaScore(String ct,String ca,long cd,String title,String artist,long duration){\n        double ts=similarity(cleanTitle(ct),cleanTitle(title)),as=artistSimilarity(ca,artist);\n        if(ts<0.55)return 0;if(!cleanArtist(artist).isEmpty()&&as<0.30)return 0;\n        double s=ts*55+as*25;\n        if(duration>0&&cd>0){long diff=Math.abs(duration-cd);if(diff<=1800)s+=28;else if(diff<=4000)s+=22;else if(diff<=8000)s+=12;else if(diff<=15000)s+=3;else s-=45;}else s+=6;\n        return s;\n    }'''
new_meta = '''    private double metaScore(String ct,String ca,long cd,String title,String artist,long duration){\n        double ts=similarity(cleanTitle(ct),cleanTitle(title)),as=artistSimilarity(ca,artist);\n        if(ts<0.50)return 0;\n        boolean artistKnown=!cleanArtist(artist).isEmpty();\n        if(artistKnown&&as<0.18&&ts<0.92)return 0;\n        double s=ts*58+as*22;\n        if(ts>=0.96)s+=6;\n        if(artistKnown&&as<0.18)s-=18;\n        if(duration>0&&cd>0){\n            long diff=Math.abs(duration-cd);\n            if(diff<=2000)s+=28;\n            else if(diff<=5000)s+=24;\n            else if(diff<=10000)s+=17;\n            else if(diff<=18000)s+=9;\n            else if(diff<=30000)s+=2;\n            else if(ts>=0.96&&as>=0.65)s-=6;\n            else s-=18;\n        }else s+=6;\n        return s;\n    }'''
if old_meta not in s:
    raise SystemExit('v16 patch: metaScore block not found')
s = s.replace(old_meta, new_meta)

# Slightly lower the final threshold now that the score itself has stricter title guards and
# softer duration handling. This recovers alternate album/soundtrack metadata without blindly
# accepting unrelated songs.
s = s.replace('best.score>=78', 'best.score>=72')

if s == orig:
    raise SystemExit('v16 patch made no lyric changes')
p.write_text(s)

# Cache-bust the hidden Android relay page so every v16 APK loads the self-healing heartbeat
# transport rather than a stale v10 copy from browser/CDN cache.
b = root / 'app/src/main/java/com/teslalyrics/app/WebRtcBridge.java'
bs = b.read_text()
bs2 = bs.replace('phone.html?v=10', 'phone.html?v=16')
bs2 = bs2.replace('primary v10', 'primary v16').replace('ready v10', 'ready v16').replace('connected v10', 'connected v16')
bs2 = bs2.replace('Transport v10:', 'Transport v16:')
if bs2 == bs:
    raise SystemExit('v16 patch: WebRtcBridge version markers not found')
b.write_text(bs2)
print('patch_v16_lyrics applied')
