from pathlib import Path
import re, sys

root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path('project')
base = root / 'app/src/main/java/com/teslalyrics/app'

# Remove reverse-engineering / abandoned transport sources from the reconstructed legacy project.
remove = [
    'NeteaseAidlDexInspector.java',
    'NeteaseCarBinderProbe.java',
    'NeteaseCmApiDeepInspector.java',
    'NeteaseControlLab.java',
    'NeteaseExternalEntryScanner.java',
    'NeteaseIpcTraceScanner.java',
    'NeteaseKaraokeDynamicProbe.java',
    'NeteaseKaraokeScanner.java',
    'NeteaseKaraokeVolumeScanner.java',
    'NeteaseKaraokeXrefScanner.java',
    'NeteaseRoute10Inspector.java',
    'NeteaseSingTraceScanner.java',
    'NeteaseVocalMixScanner.java',
    'NeteaseXCall11Inspector.java',
    'Ipv6ProbeServer.java',
    'LocalServer.java',
    'MdnsResponder.java',
    'NetworkUtils.java',
    'RemoteControlBridge.java',
    'RelayConfig.java',
]
for name in remove:
    p = base / name
    if p.exists():
        p.unlink()

# Strip the abandoned MediaSession karaoke probe path from the production monitor.
media = base / 'MediaSessionMonitor.java'
if media.exists():
    s = media.read_text()
    s = s.replace('    private String lastCustomActionsSig="";\n', '')
    s = s.replace('        lastCustomActionsSig="";\n', '')
    s = s.replace('                reportCustomActions(ps);\n', '')

    s = re.sub(
        r'\n    private void reportCustomActions\(PlaybackState ps\)\{.*?\n    \}\n\n    private boolean tryKaraokeCustomAction\(MediaController c,PlaybackState ps\)\{.*?\n    \}\n',
        '\n', s, count=1, flags=re.S
    )

    s = re.sub(
        r'\n                \}else if\("karaoke"\.equals\(action\)\)\{.*?\n                    return;',
        '', s, count=1, flags=re.S
    )
    media.write_text(s)

print('production_cleanup applied')
