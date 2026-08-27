from pathlib import Path

root = Path('project/app/src/main')

manifest = root / 'AndroidManifest.xml'
s = manifest.read_text()
needle = '    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />\n'
insert = needle + '    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES" android:usesPermissionFlags="neverForLocation" />\n'
if 'android.permission.NEARBY_WIFI_DEVICES' not in s:
    s = s.replace(needle, insert)
manifest.write_text(s)

local_server = root / 'java/com/teslalyrics/app/LocalServer.java'
s = local_server.read_text()
s = s.replace('public LocalServer(Context c){super(8765);', 'public LocalServer(Context c){super("0.0.0.0",8765);')
local_server.write_text(s)

activity = root / 'java/com/teslalyrics/app/MainActivity.java'
s = activity.read_text()
old = '@Override public void onCreate(Bundle b){super.onCreate(b);settings=new SettingsStore(this);buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);startCore();}'
new = '@Override public void onCreate(Bundle b){super.onCreate(b);settings=new SettingsStore(this);buildUi();if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES},5);}else if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},4);}startCore();}'
s = s.replace(old, new)
activity.write_text(s)

gradle = Path('project/app/build.gradle')
s = gradle.read_text()
s = s.replace("versionCode 3", "versionCode 4")
s = s.replace("versionName '0.3.0-mdns'", "versionName '0.4.0-android16-lan'")
gradle.write_text(s)
