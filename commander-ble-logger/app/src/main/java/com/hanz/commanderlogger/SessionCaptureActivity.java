package com.hanz.commanderlogger;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class SessionCaptureActivity extends Activity {
    private static final int REQ_IMPORT = 501;
    private TextView status;
    private TextView output;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(30));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("微信 ↔ 指挥官 BLE 会话抓取");
        title.setTextSize(24);
        root.addView(title, matchWrap());

        TextView help = new TextView(this);
        help.setText(
                "这个模式不需要你逐个记录车速、功率、开关门。开启 Android 系统的 Bluetooth HCI snoop 后，正常打开微信小程序连接指挥官并使用一段时间，系统会连续记录整个蓝牙会话。回来后导入 btsnoop_hci.log 或系统 Bugreport ZIP，本软件会自动提取并整理 ATT/GATT 数据。\n\n" +
                "第一次只需在开发者选项里开启一次“启用蓝牙 HCI 监听日志 / Enable Bluetooth HCI snoop log”。之后无需本软件连接指挥官，微信照常连接即可。\n");
        help.setTextSize(15);
        root.addView(help, matchWrap());

        Button dev = button("1. 打开开发者选项");
        Button wechat = button("2. 打开微信正常使用小程序");
        Button importLog = button("3. 导入 HCI 日志 / Bugreport ZIP");
        Button oldTools = button("高级：直接 BLE 扫描 / GATT 工具");
        root.addView(dev, matchWrap());
        root.addView(wechat, matchWrap());
        root.addView(importLog, matchWrap());
        root.addView(oldTools, matchWrap());

        status = new TextView(this);
        status.setText("状态：等待导入日志");
        status.setTextSize(16);
        root.addView(status, matchWrap());

        output = new TextView(this);
        output.setTextSize(12);
        output.setTextIsSelectable(true);
        root.addView(output, matchWrap());

        dev.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            }
        });

        wechat.setOnClickListener(v -> {
            Intent i = getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
            if (i != null) startActivity(i);
            else Toast.makeText(this, "没有找到微信", Toast.LENGTH_LONG).show();
        });

        importLog.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            startActivityForResult(i, REQ_IMPORT);
        });

        oldTools.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        setContentView(scroll);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        status.setText("状态：正在解析…");
        output.setText("");
        new Thread(() -> analyzeUri(uri)).start();
    }

    private void analyzeUri(Uri uri) {
        try {
            InputStream raw = getContentResolver().openInputStream(uri);
            if (raw == null) throw new IllegalArgumentException("无法读取文件");
            BufferedInputStream bis = new BufferedInputStream(raw);
            bis.mark(8);
            byte[] sig = new byte[4];
            int n = bis.read(sig);
            bis.reset();
            File temp;
            if (n == 4 && sig[0] == 'P' && sig[1] == 'K') {
                temp = extractBtsnoopFromZip(bis);
                if (temp == null) throw new IllegalArgumentException("ZIP 中没有找到 btsnoop / Bluetooth HCI 日志");
            } else {
                temp = copyToTemp(bis, "capture.btsnoop");
            }
            Analysis a = parseBtsnoop(temp);
            String report = buildReport(a);
            File reportFile = writeReport(report);
            runOnUiThread(() -> {
                status.setText("状态：解析完成。原始 HCI 包 " + a.totalRecords + " 条，ATT/GATT " + a.attRecords + " 条。\n分析报告已保存：" + reportFile.getAbsolutePath());
                output.setText(report);
            });
        } catch (Exception e) {
            runOnUiThread(() -> {
                status.setText("状态：解析失败");
                output.setText(e.getClass().getSimpleName() + ": " + e.getMessage());
            });
        }
    }

    private File extractBtsnoopFromZip(InputStream in) throws Exception {
        ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in));
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().toLowerCase(Locale.US);
            if (!entry.isDirectory() && (name.contains("btsnoop") || (name.contains("bluetooth") && name.endsWith(".log")))) {
                File f = new File(getCacheDir(), "imported_btsnoop_" + System.currentTimeMillis() + ".log");
                try (FileOutputStream out = new FileOutputStream(f)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = zis.read(buf)) > 0) out.write(buf, 0, r);
                }
                return f;
            }
            zis.closeEntry();
        }
        return null;
    }

    private File copyToTemp(InputStream in, String name) throws Exception {
        File f = new File(getCacheDir(), System.currentTimeMillis() + "_" + name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
        return f;
    }

    private Analysis parseBtsnoop(File file) throws Exception {
        Analysis a = new Analysis();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new java.io.FileInputStream(file)))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            String m = new String(magic, 0, 7, StandardCharsets.US_ASCII);
            if (!"btsnoop".equals(m)) throw new IllegalArgumentException("文件不是标准 btsnoop 日志");
            a.version = in.readInt();
            a.datalink = in.readInt();
            while (true) {
                int originalLen;
                try { originalLen = in.readInt(); } catch (Exception eof) { break; }
                int includedLen = in.readInt();
                int flags = in.readInt();
                int drops = in.readInt();
                long timestamp = in.readLong();
                if (includedLen < 0 || includedLen > 2_000_000) throw new IllegalArgumentException("异常包长度: " + includedLen);
                byte[] p = new byte[includedLen];
                in.readFully(p);
                a.totalRecords++;
                parseConnectionEvent(a, p);
                parseAclAtt(a, p, flags, timestamp, originalLen, drops);
            }
        }
        return a;
    }

    private void parseConnectionEvent(Analysis a, byte[] p) {
        if (p.length < 15 || (p[0] & 0xFF) != 0x04) return;
        int eventCode = p[1] & 0xFF;
        if (eventCode != 0x3E) return;
        int sub = p[3] & 0xFF;
        if (sub != 0x01 && sub != 0x0A) return;
        int status = p[4] & 0xFF;
        if (status != 0) return;
        int handle = le16(p, 5) & 0x0FFF;
        int addrOffset = 9;
        if (p.length < addrOffset + 6) return;
        StringBuilder mac = new StringBuilder();
        for (int i = 5; i >= 0; i--) {
            if (mac.length() > 0) mac.append(':');
            mac.append(String.format(Locale.US, "%02X", p[addrOffset + i] & 0xFF));
        }
        a.connectionPeers.put(handle, mac.toString());
    }

    private void parseAclAtt(Analysis a, byte[] p, int flags, long timestamp, int originalLen, int drops) {
        if (p.length < 10 || (p[0] & 0xFF) != 0x02) return;
        int connHandle = le16(p, 1) & 0x0FFF;
        int l2capOffset = 5;
        if (p.length < l2capOffset + 4) return;
        int cid = le16(p, l2capOffset + 2);
        if (cid != 0x0004) return;
        int attOffset = l2capOffset + 4;
        if (p.length <= attOffset) return;
        int opcode = p[attOffset] & 0xFF;
        int attHandle = attHandleForOpcode(p, attOffset, opcode);
        byte[] payload = new byte[p.length - attOffset];
        System.arraycopy(p, attOffset, payload, 0, payload.length);
        String direction = (flags & 1) != 0 ? "RX" : "TX";
        String key = String.format(Locale.US, "%03X/%s/%04X/%02X", connHandle, direction, Math.max(attHandle, 0), opcode);
        StreamStat stat = a.streams.get(key);
        if (stat == null) {
            stat = new StreamStat(connHandle, direction, attHandle, opcode);
            a.streams.put(key, stat);
        }
        stat.accept(payload);
        a.attRecords++;
        if (a.samples.size() < 3000) {
            a.samples.add(String.format(Locale.US,
                    "%s conn=0x%03X peer=%s attHandle=%s opcode=0x%02X(%s) len=%d hex=%s",
                    direction, connHandle, a.connectionPeers.getOrDefault(connHandle, "?"),
                    attHandle < 0 ? "-" : String.format(Locale.US, "0x%04X", attHandle), opcode, opcodeName(opcode), payload.length, hex(payload)));
        }
    }

    private int attHandleForOpcode(byte[] p, int off, int op) {
        switch (op) {
            case 0x0A: // Read Request
            case 0x12: // Write Request
            case 0x16: // Prepare Write Request
            case 0x1B: // Notification
            case 0x1D: // Indication
            case 0x52: // Write Command
                return p.length >= off + 3 ? le16(p, off + 1) : -1;
            default:
                return -1;
        }
    }

    private String buildReport(Analysis a) {
        StringBuilder b = new StringBuilder();
        b.append("Commander BLE Session Analyzer\n");
        b.append("btsnoop version=").append(a.version).append(" datalink=").append(a.datalink).append('\n');
        b.append("records=").append(a.totalRecords).append(" ATT/GATT=").append(a.attRecords).append("\n\n");

        b.append("=== BLE CONNECTIONS ===\n");
        if (a.connectionPeers.isEmpty()) b.append("未从 LE Connection Complete 事件恢复出 MAC；仍可按 connection handle 分析。\n");
        for (Map.Entry<Integer, String> e : a.connectionPeers.entrySet()) {
            b.append(String.format(Locale.US, "0x%03X  %s\n", e.getKey(), e.getValue()));
        }

        b.append("\n=== ATT STREAM SUMMARY ===\n");
        for (StreamStat s : a.streams.values()) {
            b.append(String.format(Locale.US,
                    "conn=0x%03X peer=%s %s handle=%s opcode=0x%02X %-20s count=%d len=%d..%d changingBytes=%s\n",
                    s.connHandle, a.connectionPeers.getOrDefault(s.connHandle, "?"), s.direction,
                    s.attHandle < 0 ? "-" : String.format(Locale.US, "0x%04X", s.attHandle),
                    s.opcode, opcodeName(s.opcode), s.count, s.minLen, s.maxLen, s.changedPositions));
            b.append("  first: ").append(hex(s.first)).append('\n');
            b.append("  last : ").append(hex(s.last)).append('\n');
        }

        b.append("\n=== FIRST ATT PACKETS (for protocol reconstruction) ===\n");
        for (String s : a.samples) b.append(s).append('\n');
        return b.toString();
    }

    private File writeReport(String report) throws Exception {
        File dir = getExternalFilesDir("reports");
        if (dir == null) dir = getFilesDir();
        if (!dir.exists()) dir.mkdirs();
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File f = new File(dir, "commander_session_" + stamp + ".txt");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(report.getBytes(StandardCharsets.UTF_8));
        }
        return f;
    }

    private static class Analysis {
        int version;
        int datalink;
        int totalRecords;
        int attRecords;
        final Map<Integer, String> connectionPeers = new LinkedHashMap<>();
        final Map<String, StreamStat> streams = new LinkedHashMap<>();
        final List<String> samples = new ArrayList<>();
    }

    private static class StreamStat {
        final int connHandle;
        final String direction;
        final int attHandle;
        final int opcode;
        int count = 0;
        int minLen = Integer.MAX_VALUE;
        int maxLen = 0;
        byte[] first;
        byte[] last;
        byte[] previous;
        final TreeSet<Integer> changedPositions = new TreeSet<>();

        StreamStat(int connHandle, String direction, int attHandle, int opcode) {
            this.connHandle = connHandle;
            this.direction = direction;
            this.attHandle = attHandle;
            this.opcode = opcode;
        }

        void accept(byte[] p) {
            count++;
            minLen = Math.min(minLen, p.length);
            maxLen = Math.max(maxLen, p.length);
            if (first == null) first = p.clone();
            if (previous != null) {
                int max = Math.max(previous.length, p.length);
                for (int i = 0; i < max; i++) {
                    int av = i < previous.length ? previous[i] & 0xFF : -1;
                    int bv = i < p.length ? p[i] & 0xFF : -1;
                    if (av != bv) changedPositions.add(i);
                }
            }
            previous = p.clone();
            last = p.clone();
        }
    }

    private int le16(byte[] b, int off) {
        if (off < 0 || off + 1 >= b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private String opcodeName(int op) {
        switch (op) {
            case 0x01: return "ERROR_RSP";
            case 0x02: return "MTU_REQ";
            case 0x03: return "MTU_RSP";
            case 0x04: return "FIND_INFO_REQ";
            case 0x05: return "FIND_INFO_RSP";
            case 0x08: return "READ_BY_TYPE_REQ";
            case 0x09: return "READ_BY_TYPE_RSP";
            case 0x0A: return "READ_REQ";
            case 0x0B: return "READ_RSP";
            case 0x10: return "READ_BY_GROUP_REQ";
            case 0x11: return "READ_BY_GROUP_RSP";
            case 0x12: return "WRITE_REQ";
            case 0x13: return "WRITE_RSP";
            case 0x16: return "PREPARE_WRITE_REQ";
            case 0x17: return "PREPARE_WRITE_RSP";
            case 0x1B: return "NOTIFICATION";
            case 0x1D: return "INDICATION";
            case 0x1E: return "CONFIRMATION";
            case 0x52: return "WRITE_COMMAND";
            default: return "ATT";
        }
    }

    private String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder s = new StringBuilder();
        int limit = Math.min(b.length, 160);
        for (int i = 0; i < limit; i++) {
            if (i > 0) s.append(' ');
            s.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        if (b.length > limit) s.append(" …");
        return s.toString();
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
