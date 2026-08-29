package com.teslalyrics.detector;

import android.app.Activity;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * One-click exporter for the exact installed NetEase Cloud Music APK set.
 * Read-only toward NetEase: it only reads PackageManager metadata and APK files.
 */
public class NeteaseApkExporterActivity extends Activity {
    private static final String PKG = "com.netease.cloudmusic";
    private static final String BUILD = "PATCHPREP1";

    private TextView logView;
    private Button exportButton;
    private final StringBuilder log = new StringBuilder();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(14);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Tesla Lyrics · 网易云补丁准备\n" + BUILD + "\n\n只读取你手机里已安装的网易云 APK，不修改网易云、不发送任何控制命令。\n导出后把 ZIP 直接发给 ChatGPT。");
        title.setTextSize(17f);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        exportButton = new Button(this);
        exportButton.setText("一键导出当前网易云安装包");
        root.addView(exportButton, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTextSize(11f);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        setContentView(root);
        add("BUILD " + BUILD);
        add("SAFETY: read-only export; no NetEase modification; no Broadcast/Binder/MediaSession/SepTrack commands");
        exportButton.setOnClickListener(v -> exportAsync());
    }

    private void exportAsync() {
        exportButton.setEnabled(false);
        add("START export package=" + PKG);
        new Thread(() -> {
            String result = null;
            try {
                result = exportInstalledApks();
                add("DONE " + result);
            } catch (Throwable t) {
                add("ERROR " + t.getClass().getSimpleName() + ": " + safe(t.getMessage()));
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                exportButton.setEnabled(true);
                Toast.makeText(this,
                        finalResult == null ? "导出失败，请把页面日志截图发给 ChatGPT" : "导出完成",
                        Toast.LENGTH_LONG).show();
            });
        }, "netease-apk-export").start();
    }

    private String exportInstalledApks() throws Exception {
        PackageManager pm = getPackageManager();
        int flags = PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo pi = pm.getPackageInfo(PKG, flags);
        ApplicationInfo ai = pm.getApplicationInfo(PKG, 0);

        List<File> apks = new ArrayList<>();
        if (ai.sourceDir != null) apks.add(new File(ai.sourceDir));
        if (ai.splitSourceDirs != null) {
            for (String path : ai.splitSourceDirs) if (path != null) apks.add(new File(path));
        }
        if (apks.isEmpty()) throw new IllegalStateException("PackageManager returned no APK paths");

        String version = safe(pi.versionName).replaceAll("[^0-9A-Za-z._-]", "_");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String zipName = "NetEase-original-" + version + "-" + stamp + ".zip";

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, zipName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/TeslaLyricsPatchPrep");
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) throw new IllegalStateException("MediaStore insert returned null");

        StringBuilder meta = new StringBuilder();
        meta.append("Tesla Lyrics NetEase patch preparation\n");
        meta.append("Exporter build: ").append(BUILD).append('\n');
        meta.append("Export time: ").append(new Date()).append('\n');
        meta.append("Package: ").append(PKG).append('\n');
        meta.append("Version name: ").append(pi.versionName).append('\n');
        meta.append("Version code: ").append(pi.getLongVersionCode()).append('\n');
        meta.append("Device SDK: ").append(Build.VERSION.SDK_INT).append('\n');
        meta.append("Device ABI: ").append(Build.SUPPORTED_ABIS == null ? "" : String.join(",", Build.SUPPORTED_ABIS)).append('\n');
        try {
            if (pi.signingInfo != null) {
                Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                if (sigs != null) {
                    for (int i = 0; i < sigs.length; i++) {
                        meta.append("Signer[").append(i).append("] SHA-256: ")
                                .append(hex(sha256(sigs[i].toByteArray()))).append('\n');
                    }
                }
            }
        } catch (Throwable t) {
            meta.append("Signer read error: ").append(t.getClass().getSimpleName()).append(':').append(safe(t.getMessage())).append('\n');
        }
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                meta.append("Install source: ").append(safe(pm.getInstallSourceInfo(PKG).getInstallingPackageName())).append('\n');
            }
        } catch (Throwable t) {
            meta.append("Install source read error: ").append(t.getClass().getSimpleName()).append(':').append(safe(t.getMessage())).append('\n');
        }

        try (OutputStream raw = getContentResolver().openOutputStream(uri);
             BufferedOutputStream bout = new BufferedOutputStream(raw);
             ZipOutputStream zout = new ZipOutputStream(bout)) {
            if (raw == null) throw new IllegalStateException("openOutputStream returned null");

            int index = 0;
            for (File apk : apks) {
                if (!apk.isFile()) throw new IllegalStateException("APK missing: " + apk.getAbsolutePath());
                String entryName = index == 0 ? "base.apk" : uniqueSplitName(apk.getName(), index);
                add("READ " + entryName + " bytes=" + apk.length());
                byte[] digest = copyApkToZip(apk, zout, entryName);
                meta.append("APK ").append(entryName)
                        .append(" size=").append(apk.length())
                        .append(" sha256=").append(hex(digest))
                        .append(" source=").append(apk.getAbsolutePath())
                        .append('\n');
                index++;
            }

            ZipEntry metaEntry = new ZipEntry("metadata.txt");
            zout.putNextEntry(metaEntry);
            zout.write(meta.toString().getBytes(StandardCharsets.UTF_8));
            zout.closeEntry();
        }

        add("APK count=" + apks.size());
        add("Version=" + pi.versionName + " (" + pi.getLongVersionCode() + ")");
        return "Downloads/TeslaLyricsPatchPrep/" + zipName;
    }

    private byte[] copyApkToZip(File apk, ZipOutputStream zout, String entryName) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        ZipEntry ze = new ZipEntry(entryName);
        ze.setTime(apk.lastModified());
        zout.putNextEntry(ze);
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(apk))) {
            byte[] buf = new byte[128 * 1024];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                zout.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
                if ((total & ((8L * 1024 * 1024) - 1)) < n) add("  copied " + (total / (1024 * 1024)) + " MB from " + entryName);
            }
        }
        zout.closeEntry();
        return md.digest();
    }

    private static String uniqueSplitName(String original, int index) {
        String n = original == null || original.trim().isEmpty() ? "split-" + index + ".apk" : original;
        if ("base.apk".equals(n)) n = "split-" + index + ".apk";
        return "splits/" + index + "-" + n;
    }

    private static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.US, "%02x", x & 0xff));
        return s.toString();
    }

    private void add(String line) {
        String t = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        synchronized (log) { log.append(t).append("  ").append(line).append('\n'); }
        runOnUiThread(() -> {
            synchronized (log) { logView.setText(log.toString()); }
        });
    }

    private int dp(int x) {
        return Math.round(x * getResources().getDisplayMetrics().density);
    }

    private static String safe(String s) { return s == null ? "" : s; }
}
