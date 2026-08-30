from pathlib import Path
import re, sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('project')
p = root / 'app/src/main/java/com/teslalyrics/app/MultiLyricsFetcher.java'
s = p.read_text()

# NetEase candidates must not be discarded purely because MediaSession/API duration differs.
old = '                    if("0".equals(id)||metaScore(n,ar,d,title,artist,duration)<58)continue;\n                    String l=neteaseLyric(id);\n                    if(validLrc(l))out.add(new Candidate("网易云音乐",n,ar,d,l));'
new = '''                    double pre=metaScore(n,ar,d,title,artist,duration);
                    boolean strong=strongNeteaseIdentity(n,ar,title,artist);
                    AppState.get().log.add("NetEase candidate: id="+id+" pre="+Math.round(pre)+" strong="+strong+" title="+n+" artist="+ar+" dur="+d);
                    if("0".equals(id)||(!strong&&pre<58))continue;
                    String l=neteaseLyric(id);
                    int lines=timeLineCount(l);
                    AppState.get().log.add("NetEase lyric result: id="+id+" lines="+lines);
                    if(validLrc(l))out.add(new Candidate("网易云音乐",n,ar,d,l));'''
if old not in s:
    raise SystemExit('NetEase prefilter anchor missing')
s = s.replace(old, new, 1)

# Replace scoring so a same-provider, strong title+artist match is not killed by duration/version drift.
pat = re.compile(r'    private double score\(Candidate c,String title,String artist,long duration,String playerSource\)\{.*?\n    \}\n\n    private double metaScore', re.S)
rep = '''    private double score(Candidate c,String title,String artist,long duration,String playerSource){
        double s=metaScore(c.title,c.artist,c.durationMs,title,artist,duration);
        boolean sameNetease=c.provider.contains("网易云")&&playerSource.contains("网易云");
        boolean strongNetease=sameNetease&&strongNeteaseIdentity(c.title,c.artist,title,artist);
        if(c.provider.contains("原曲ID")&&sameNetease)s=Math.max(s,130);
        else if(strongNetease)s=Math.max(s,96);
        else if(sameNetease)s+=24;
        if(c.provider.contains("QQ")&&playerSource.contains("QQ"))s+=18;
        if(c.provider.contains("LRCLIB"))s+=4;
        int count=timeLineCount(c.lrc);if(count>=20)s+=5;else if(count<6)s-=30;
        if(duration>0){
            long last=lastTimestamp(c.lrc);
            if(last>0){
                long diff=Math.abs(duration-last);
                if(diff<=25000)s+=10;
                else if(diff<=45000)s+=3;
                else if(diff>75000)s-=strongNetease?8:35;
            }
        }
        return s;
    }

    private static boolean strongNeteaseIdentity(String ct,String ca,String title,String artist){
        double ts=similarity(cleanTitle(ct),cleanTitle(title));
        double as=artistSimilarity(ca,artist);
        // Exact/near-exact title plus matching lead artist is enough. Duration is only a tie-breaker.
        return ts>=0.86 && (cleanArtist(artist).isEmpty() || as>=0.58);
    }

    private double metaScore'''
s, n = pat.subn(rep, s, count=1)
if n != 1:
    raise SystemExit('score anchor missing')

# Make the final decision visible in exported diagnostics.
old = '            }else AppState.get().log.add("Lyrics multi-source: no safe match"+(best==null?"":" best="+best.provider+" "+Math.round(best.score)));'
new = '            }else AppState.get().log.add("Lyrics multi-source: no safe match"+(best==null?"":" best="+best.provider+" score="+Math.round(best.score)+" title="+best.title+" artist="+best.artist+" dur="+best.durationMs));'
if old in s:
    s = s.replace(old, new, 1)

p.write_text(s)
print('patch_match4 applied')
