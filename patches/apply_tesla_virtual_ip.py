from pathlib import Path

root = Path('project/app/src/main')
java_dir = root / 'java/com/teslalyrics/app'

# Add local-only VpnService that creates the fixed CGNAT address Tesla browsers can access.
vpn = java_dir / 'TeslaLocalVpnService.java'
vpn.write_text(r'''package com.teslalyrics.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
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
        b.setContentTitle("Tesla Lyrics 本地车机连接")
         .setContentText("固定地址 100.99.9.9:8765")
         .setSmallIcon(android.R.drawable.stat_sys_warning)
         .setOngoing(true)
         .setContentIntent(pi);
        startForeground(91, b.build());
    }

    private synchronized void startVpn() {
        if (tun != null) return;
        try {
            Builder b = new Builder();
            b.setSession("Tesla Lyrics Local Link");
            b.setMtu(1500);
            // A /32 address creates a fixed local endpoint without hijacking normal Internet traffic.
            b.addAddress("100.99.9.9", 32);
            b.allowBypass();
            tun = b.establish();
            AppState.get().log.add(tun != null ? "Tesla virtual IP ready: 100.99.9.9" : "Tesla virtual IP failed: establish returned null");
        } catch (Exception e) {
            AppState.get().log.add("Tesla virtual IP failed: " + e.getMessage());
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

    @Override public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
''')

# Manifest registration.
manifest = root / 'AndroidManifest.xml'
s = manifest.read_text()
if '.TeslaLocalVpnService' not in s:
    marker = '        <service android:name=".LyricsService" android:exported="false" android:foregroundServiceType="connectedDevice" />\n'
    addition = marker + '''        <service\n            android:name=".TeslaLocalVpnService"\n            android:permission="android.permission.BIND_VPN_SERVICE"\n            android:exported="false">\n            <intent-filter>\n                <action android:name="android.net.VpnService" />\n            </intent-filter>\n        </service>\n'''
    s = s.replace(marker, addition)
manifest.write_text(s)

# MainActivity: request VPN consent once and start the local virtual endpoint.
activity = java_dir / 'MainActivity.java'
s = activity.read_text()
if 'import android.net.VpnService;' not in s:
    s = s.replace('import android.os.Build;\n', 'import android.os.Build;\nimport android.net.VpnService;\n')

# Add request constant.
s = s.replace('public final class MainActivity extends Activity {\n', 'public final class MainActivity extends Activity {\n    private static final int REQ_TESLA_VPN=77;\n')

# Ensure VPN after normal app startup.
needle = 'startCore();}'
if needle in s and 'ensureTeslaVpn();}' not in s:
    s = s.replace(needle, 'startCore();ensureTeslaVpn();}', 1)

# Add methods before startCore.
marker = '    private void startCore(){send(LyricsService.ACTION_START);}\n'
if 'private void ensureTeslaVpn()' not in s:
    methods = '''    private void ensureTeslaVpn(){Intent p=VpnService.prepare(this);if(p!=null){startActivityForResult(p,REQ_TESLA_VPN);}else{startTeslaVpn();}}\n    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){super.onActivityResult(requestCode,resultCode,data);if(requestCode==REQ_TESLA_VPN&&resultCode==RESULT_OK)startTeslaVpn();}\n    private void startTeslaVpn(){Intent i=new Intent(this,TeslaLocalVpnService.class).setAction(TeslaLocalVpnService.ACTION_START);if(Build.VERSION.SDK_INT>=26)startForegroundService(i);else startService(i);}\n'''
    s = s.replace(marker, methods + marker)

# Replace UI address with the fixed virtual address.
s = s.replace('车机地址：\\nhttp://"+NetworkUtils.bestLanAddress()+":8765\\n\\n检测到的局域网地址：\\n"+NetworkUtils.candidateReport()', '固定车机地址：\\nhttp://100.99.9.9:8765\\n\\n手机热点实际地址（仅诊断）：\\n"+NetworkUtils.candidateReport()')
s = s.replace('5. Tesla 浏览器打开首页显示的 IP:8765。\\n\\n第一次启动请允许“附近设备”权限，否则 Android 16 可能阻止车机连接手机本地服务。', '5. 第一次启动允许系统 VPN 连接请求\\n6. Tesla 浏览器打开并收藏：\\nhttp://100.99.9.9:8765\\n\\n这个 VPN 仅用于创建手机本地虚拟地址，不连接任何公网 VPN 服务器。')
activity.write_text(s)

# Notification text.
svc = java_dir / 'LyricsService.java'
s = svc.read_text().replace('LAN :8765', '100.99.9.9:8765')
svc.write_text(s)

# Version bump.
gradle = Path('project/app/build.gradle')
s = gradle.read_text()
s = s.replace('versionCode 4', 'versionCode 5')
s = s.replace("versionName '0.4.0-android16-lan'", "versionName '0.5.0-tesla-virtual-ip'")
gradle.write_text(s)
