from pathlib import Path

root = Path('project/app/src/main')
java_dir = root / 'java/com/teslalyrics/app'

# Make the existing HTTP server listen on every interface, including the VPN address.
local_server = java_dir / 'LocalServer.java'
s = local_server.read_text()
s = s.replace('public LocalServer(Context c){super(8765);', 'public LocalServer(Context c){super("0.0.0.0",8765);')
local_server.write_text(s)

# Local-only VpnService. No public VPN server is used.
vpn = java_dir / 'TeslaLocalVpnService.java'
vpn.write_text(r'''package com.teslalyrics.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

public final class TeslaLocalVpnService extends VpnService {
    public static final String ACTION_START = "com.teslalyrics.VPN_START";
    public static final String ACTION_STOP = "com.teslalyrics.VPN_STOP";
    private static final String CHANNEL = "tesla_lyrics_vpn";
    private ParcelFileDescriptor tun;

    @Override public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Tesla Lyrics Local Link", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }
        promote();
        startVpn();
        return START_STICKY;
    }

    private void promote() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 91, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("Tesla Lyrics 固定车机地址")
         .setContentText("100.99.9.9:8765")
         .setSmallIcon(android.R.drawable.stat_sys_warning)
         .setOngoing(true)
         .setContentIntent(pi);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(91, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(91, n);
        }
    }

    private synchronized void startVpn() {
        if (tun != null) return;
        try {
            Builder b = new Builder();
            b.setSession("Tesla Lyrics Local Link");
            b.setMtu(1500);
            b.addAddress("100.99.9.9", 32);
            b.allowBypass();
            tun = b.establish();
            AppState.get().log.add(tun != null ? "Virtual IP ready: 100.99.9.9" : "Virtual IP failed: establish returned null");
        } catch (Throwable e) {
            AppState.get().log.add("Virtual IP failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            stopSelf();
        }
    }

    private synchronized void stopVpn() {
        if (tun != null) {
            try { tun.close(); } catch (Exception ignored) {}
            tun = null;
        }
    }

    @Override public void onDestroy() {
        stopVpn();
        super.onDestroy();
    }
}
''')

# Manifest: Android 14+ foreground-service type is mandatory for targetSdk 35.
manifest = root / 'AndroidManifest.xml'
s = manifest.read_text()
if 'android.permission.FOREGROUND_SERVICE_SPECIAL_USE' not in s:
    s = s.replace('    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n',
                  '    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />\n    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />\n')
if '.TeslaLocalVpnService' not in s:
    marker = '        <service android:name=".LyricsService" android:exported="false" android:foregroundServiceType="connectedDevice" />\n'
    addition = marker + '''        <service
            android:name=".TeslaLocalVpnService"
            android:permission="android.permission.BIND_VPN_SERVICE"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Local virtual IP for Tesla browser access; no remote VPN server" />
            <intent-filter>
                <action android:name="android.net.VpnService" />
            </intent-filter>
        </service>
'''
    s = s.replace(marker, addition)
manifest.write_text(s)

# MainActivity: user taps once to enable the fixed address. This avoids overlapping
# the normal notification permission dialog with Android's VPN-consent dialog.
activity = java_dir / 'MainActivity.java'
s = activity.read_text()
if 'import android.net.VpnService;' not in s:
    s = s.replace('import android.os.Build;\n', 'import android.os.Build;\nimport android.net.VpnService;\n')
if 'private static final int REQ_TESLA_VPN=77;' not in s:
    s = s.replace('public final class MainActivity extends Activity {\n', 'public final class MainActivity extends Activity {\n    private static final int REQ_TESLA_VPN=77;\n')

marker = '    private void startCore(){send(LyricsService.ACTION_START);}\n'
if 'private void ensureTeslaVpn()' not in s:
    methods = '''    private void ensureTeslaVpn(){try{Intent p=VpnService.prepare(this);if(p!=null){startActivityForResult(p,REQ_TESLA_VPN);}else{startTeslaVpn();}}catch(Throwable e){Toast.makeText(this,"固定地址启动失败："+e.getMessage(),Toast.LENGTH_LONG).show();}}\n    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_TESLA_VPN&&resultCode==RESULT_OK)startTeslaVpn();}\n    private void startTeslaVpn(){try{Intent i=new Intent(this,TeslaLocalVpnService.class).setAction(TeslaLocalVpnService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);Toast.makeText(this,"固定地址已启动：100.99.9.9:8765",Toast.LENGTH_SHORT).show();}catch(Throwable e){Toast.makeText(this,"固定地址启动失败："+e.getClass().getSimpleName()+" "+e.getMessage(),Toast.LENGTH_LONG).show();}}\n'''
    s = s.replace(marker, methods + marker)

# Add a dedicated button to the home screen.
needle = 'rowButton("开启通知使用权（只用于读取播放器）",this::openMediaAccess);'
if '启用固定车机地址' not in s:
    s = s.replace(needle, needle + 'rowButton("启用固定车机地址 100.99.9.9",this::ensureTeslaVpn);')

s = s.replace('固定车机地址：\\nhttp://teslalyrics.local:8765\\n\\n备用 IP 地址：\\nhttp://"+NetworkUtils.bestLanAddress()+":8765',
              '固定车机地址（启用后）：\\nhttp://100.99.9.9:8765\\n\\n普通局域网地址（平板测试用）：\\nhttp://"+NetworkUtils.bestLanAddress()+":8765')
s = s.replace('5. Tesla 浏览器打开并收藏：\\nhttp://teslalyrics.local:8765\\n\\n如果 Tesla 不支持 .local，再使用首页显示的备用 IP 地址。',
              '5. 点“启用固定车机地址 100.99.9.9”，首次允许系统 VPN 请求\\n6. Tesla 浏览器打开并收藏：\\nhttp://100.99.9.9:8765\\n\\n该 VPN 只创建手机本地虚拟地址，不连接任何公网 VPN 服务器。')
activity.write_text(s)

# Notification text.
svc = java_dir / 'LyricsService.java'
s = svc.read_text().replace('teslalyrics.local:8765', '100.99.9.9:8765')
svc.write_text(s)

# Version bump from Bluetooth baseline.
gradle = Path('project/app/build.gradle')
s = gradle.read_text()
s = s.replace('versionCode 4', 'versionCode 5')
s = s.replace("versionName '0.4.0-hotspot-ip-fix'", "versionName '0.5.1-tesla-virtual-ip'")
gradle.write_text(s)
