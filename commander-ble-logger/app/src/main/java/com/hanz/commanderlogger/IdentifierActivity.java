package com.hanz.commanderlogger;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class IdentifierActivity extends Activity {
    private static final int REQ_PERMS = 300;
    private static final long WINDOW_MS = 10_000L;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean scanning = false;
    private boolean baselineCapture = false;
    private boolean identifyCapture = false;
    private boolean baselineCaptured = false;

    private final Set<String> baselineAddresses = new HashSet<>();
    private final Map<String, DeviceInfo> seen = new HashMap<>();
    private final ArrayList<DeviceInfo> ordered = new ArrayList<>();
    private final ArrayList<String> labels = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        buildUi();
        requestBlePermissionsIfNeeded();
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(18));
        page.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("指挥官 BLE 识别模式");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView help = new TextView(this);
        help.setText("用法：先让指挥官断电，点“1.采集断电基线”；10 秒结束后给指挥官通电，再点“2.通电后识别”。新出现的 BLE 会标成 ★ 候选，并显示完整广播数据。\n");
        help.setTextSize(14);
        root.addView(help, matchWrap());

        statusView = new TextView(this);
        statusView.setText("状态：等待操作");
        statusView.setTextSize(16);
        root.addView(statusView, matchWrap());

        LinearLayout row1 = horizontalRow();
        Button baselineButton = button("1. 采集断电基线");
        Button identifyButton = button("2. 通电后识别");
        row1.addView(baselineButton, weightButton());
        row1.addView(identifyButton, weightButton());
        root.addView(row1, matchWrap());

        LinearLayout row2 = horizontalRow();
        Button normalButton = button("普通扫描");
        Button stopButton = button("停止");
        Button fullButton = button("完整抓包工具");
        row2.addView(normalButton, weightButton());
        row2.addView(stopButton, weightButton());
        row2.addView(fullButton, weightButton());
        root.addView(row2, matchWrap());

        TextView listTitle = new TextView(this);
        listTitle.setText("BLE 广播结果（候选按信号强度优先）");
        listTitle.setTextSize(16);
        root.addView(listTitle, matchWrap());

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(680)));

        setContentView(page);

        baselineButton.setOnClickListener(v -> startBaselineCapture());
        identifyButton.setOnClickListener(v -> startIdentifyCapture());
        normalButton.setOnClickListener(v -> startWindow(false, false, 0));
        stopButton.setOnClickListener(v -> stopScan("已停止"));
        fullButton.setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
    }

    private void startBaselineCapture() {
        baselineAddresses.clear();
        baselineCaptured = false;
        startWindow(true, false, WINDOW_MS);
    }

    private void startIdentifyCapture() {
        if (!baselineCaptured) {
            Toast.makeText(this, "请先在指挥官断电时采集一次基线", Toast.LENGTH_LONG).show();
            return;
        }
        startWindow(false, true, WINDOW_MS);
    }

    private void startWindow(boolean baseline, boolean identify, long duration) {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先打开手机蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "BLE Scanner 不可用", Toast.LENGTH_LONG).show();
            return;
        }
        stopScan(null);
        baselineCapture = baseline;
        identifyCapture = identify;
        seen.clear();
        ordered.clear();
        labels.clear();
        adapter.notifyDataSetChanged();
        scanning = true;
        if (baseline) status("状态：正在采集断电基线，持续 10 秒…");
        else if (identify) status("状态：正在识别新出现设备，持续 10 秒…");
        else status("状态：普通 BLE 扫描中…");
        scanner.startScan(scanCallback);
        if (duration > 0) {
            handler.postDelayed(() -> {
                if (baselineCapture) finishBaseline();
                else if (identifyCapture) finishIdentify();
            }, duration);
        }
    }

    private void finishBaseline() {
        if (!baselineCapture) return;
        baselineAddresses.clear();
        baselineAddresses.addAll(seen.keySet());
        baselineCaptured = true;
        baselineCapture = false;
        stopScan("基线完成：记录了 " + baselineAddresses.size() + " 个 BLE 设备。现在给指挥官通电，再点第 2 步。");
    }

    private void finishIdentify() {
        if (!identifyCapture) return;
        identifyCapture = false;
        stopScan(null);
        int candidates = 0;
        for (DeviceInfo info : seen.values()) if (!baselineAddresses.contains(info.address)) candidates++;
        refreshList();
        status("识别完成：发现 " + candidates + " 个“基线中没有的新设备”。优先看 ★ 且 RSSI 最强的。 ");
    }

    private void stopScan(String message) {
        handler.removeCallbacksAndMessages(null);
        if (scanner != null && scanning && hasBlePermissions()) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        if (message != null) status("状态：" + message);
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!hasBlePermissions() || result == null || result.getDevice() == null) return;
            BluetoothDevice device = result.getDevice();
            String address = device.getAddress();
            DeviceInfo info = seen.get(address);
            if (info == null) {
                info = new DeviceInfo();
                info.address = address;
                seen.put(address, info);
            }
            try { info.deviceName = device.getName(); } catch (Exception ignored) {}
            info.rssi = result.getRssi();
            info.connectable = Build.VERSION.SDK_INT >= 26 && result.isConnectable();
            info.timestampNanos = result.getTimestampNanos();
            ScanRecord rec = result.getScanRecord();
            if (rec != null) {
                info.localName = rec.getDeviceName();
                info.txPower = rec.getTxPowerLevel();
                byte[] raw = rec.getBytes();
                info.rawHex = hex(raw);
                info.serviceUuids = stringifyServiceUuids(rec.getServiceUuids());
                info.manufacturer = stringifyManufacturer(rec.getManufacturerSpecificData());
                info.serviceData = stringifyServiceData(rec.getServiceData());
            }
            refreshList();
        }

        @Override
        public void onScanFailed(int errorCode) {
            status("状态：扫描失败，错误码 " + errorCode);
        }
    };

    private void refreshList() {
        ordered.clear();
        ordered.addAll(seen.values());
        Collections.sort(ordered, new Comparator<DeviceInfo>() {
            @Override
            public int compare(DeviceInfo a, DeviceInfo b) {
                boolean ca = baselineCaptured && !baselineAddresses.contains(a.address);
                boolean cb = baselineCaptured && !baselineAddresses.contains(b.address);
                if (ca != cb) return ca ? -1 : 1;
                return Integer.compare(b.rssi, a.rssi);
            }
        });
        labels.clear();
        for (DeviceInfo i : ordered) labels.add(makeLabel(i));
        runOnUiThread(() -> adapter.notifyDataSetChanged());
    }

    private String makeLabel(DeviceInfo i) {
        boolean candidate = baselineCaptured && !baselineAddresses.contains(i.address);
        String shownName = firstNonEmpty(i.localName, i.deviceName, "(unknown)");
        StringBuilder s = new StringBuilder();
        if (candidate) s.append("★ 候选：");
        s.append(shownName).append("\n");
        s.append(i.address).append("   RSSI ").append(i.rssi).append(" dBm");
        s.append("   Connectable=").append(i.connectable).append("\n");
        s.append("LocalName: ").append(nullText(i.localName)).append("\n");
        s.append("Service UUID: ").append(nullText(i.serviceUuids)).append("\n");
        s.append("Manufacturer: ").append(nullText(i.manufacturer)).append("\n");
        s.append("Service Data: ").append(nullText(i.serviceData)).append("\n");
        s.append("TxPower: ").append(i.txPower).append("\n");
        s.append("RAW ADV: ").append(nullText(i.rawHex));
        return s.toString();
    }

    private String stringifyServiceUuids(List<ParcelUuid> uuids) {
        if (uuids == null || uuids.isEmpty()) return "-";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < uuids.size(); i++) {
            if (i > 0) s.append(", ");
            s.append(uuids.get(i));
        }
        return s.toString();
    }

    private String stringifyManufacturer(SparseArray<byte[]> data) {
        if (data == null || data.size() == 0) return "-";
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < data.size(); i++) {
            if (i > 0) s.append(" | ");
            s.append("0x").append(String.format(Locale.US, "%04X", data.keyAt(i))).append(": ").append(hex(data.valueAt(i)));
        }
        return s.toString();
    }

    private String stringifyServiceData(Map<ParcelUuid, byte[]> data) {
        if (data == null || data.isEmpty()) return "-";
        StringBuilder s = new StringBuilder();
        boolean first = true;
        for (Map.Entry<ParcelUuid, byte[]> e : data.entrySet()) {
            if (!first) s.append(" | ");
            first = false;
            s.append(e.getKey()).append(": ").append(hex(e.getValue()));
        }
        return s.toString();
    }

    private String hex(byte[] b) {
        if (b == null || b.length == 0) return "-";
        StringBuilder s = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) s.append(' ');
            s.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return s.toString();
    }

    private String firstNonEmpty(String... values) {
        for (String v : values) if (v != null && !v.trim().isEmpty()) return v;
        return "(unknown)";
    }

    private String nullText(String s) {
        return s == null || s.isEmpty() ? "-" : s;
    }

    private void requestBlePermissionsIfNeeded() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]), REQ_PERMS);
    }

    private boolean hasBlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void status(String text) {
        runOnUiThread(() -> statusView.setText(text));
    }

    private LinearLayout horizontalRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        return b;
    }

    private LinearLayout.LayoutParams weightButton() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        stopScan(null);
        super.onDestroy();
    }

    private static class DeviceInfo {
        String address;
        String deviceName;
        String localName;
        int rssi;
        int txPower = Integer.MIN_VALUE;
        boolean connectable;
        long timestampNanos;
        String serviceUuids;
        String manufacturer;
        String serviceData;
        String rawHex;
    }
}
