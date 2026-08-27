from pathlib import Path

root = Path('project/app/src/main')

# 1) Manifest: nearby devices permission for Android 16 local-network protections.
manifest = root / 'AndroidManifest.xml'
s = manifest.read_text()
needle = '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n'
insert = needle + '    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />\n'
if 'android.permission.NEARBY_WIFI_DEVICES' not in s:
    s = s.replace(needle, insert)
manifest.write_text(s)

# 2) Bind NanoHTTPD explicitly to all IPv4 interfaces.
local_server = root / 'java/com/teslalyrics/app/LocalServer.java'
s = local_server.read_text()
s = s.replace('public LocalServer(Context c){super(8765);', 'public LocalServer(Context c){super("0.0.0.0",8765);')
local_server.write_text(s)

# 3) Disable the experimental mDNS responder; keep the proven direct-IP server only.
svc = root / 'java/com/teslalyrics/app/LyricsService.java'
s = svc.read_text()
s = s.replace(';private MdnsResponder mdns;', ';')
s = s.replace('if(server!=null&&mdns==null){mdns=new MdnsResponder(this);mdns.start();}', '')
s = s.replace('if(mdns!=null){mdns.stop();mdns=null;}', '')
s = s.replace('teslalyrics.local:8765', NetworkAddressPlaceholder := 'LAN :8765')
svc.write_text(s)

# 4) Request Nearby Devices at runtime before starting the LAN server.
activity = root / 'java/com/teslalyrics/app/MainActivity.java'
s = activity.read_text()
old = '@Override public void onCreate(Bundle b){super.onCreate(b);settings=new SettingsStore(this);buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);startCore();}'
new = '@Override public void onCreate(Bundle b){super.onCreate(b);settings=new SettingsStore(this);buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},5);}else if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);}startCore();}'
s = s.replace(old, new)
s = s.replace('固定车机地址：\\nhttp://teslalyrics.local:8765\\n\\n备用 IP 地址：\\nhttp://"+NetworkUtils.bestLanAddress()+":8765', '车机地址：\\nhttp://"+NetworkUtils.bestLanAddress()+":8765\\n\\n检测到的局域网地址：\\n"+NetworkUtils.candidateReport()')
s = s.replace('5. Tesla 浏览器打开并收藏：\\nhttp://teslalyrics.local:8765\\n\\n如果 Tesla 不支持 .local，再使用首页显示的备用 IP 地址。', '5. Tesla 浏览器打开首页显示的 IP:8765。\\n\\n第一次启动请允许“附近设备”权限，否则 Android 16 可能阻止车机连接手机本地服务。')
activity.write_text(s)

# 5) Bump version.
gradle = Path('project/app/build.gradle')
s = gradle.read_text()
s = s.replace("versionCode 3", "versionCode 4")
s = s.replace("versionName '0.3.0-mdns'", "versionName '0.4.0-android16-lan'")
gradle.write_text(s)
