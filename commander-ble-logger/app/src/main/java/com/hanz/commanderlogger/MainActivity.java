package com.hanz.commanderlogger;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 100;
    private static final int REQ_EXPORT = 200;
    private static final int REQ_IMPORT = 201;
    private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private boolean scanning = false;

    private final ArrayList<BluetoothDevice> deviceList = new ArrayList<>();
    private final ArrayList<String> deviceLabels = new ArrayList<>();
    private final Map<String, Integer> deviceIndexByAddress = new LinkedHashMap<>();
    private ArrayAdapter<String> deviceAdapter;

    private final ArrayList<BluetoothGattCharacteristic> writableChars = new ArrayList<>();
    private final ArrayList<String> writableLabels = new ArrayList<>();
    private ArrayAdapter<String> writableAdapter;
    private final ArrayDeque<DescriptorOp> descriptorQueue = new ArrayDeque<>();
    private final ArrayDeque<BluetoothGattCharacteristic> readQueue = new ArrayDeque<>();
    private boolean gattOperationBusy = false;

    private TextView statusView;
    private TextView logView;
    private Spinner writeSpinner;
    private EditText hexInput;
    private EditText markerInput;
    private final StringBuilder uiLog = new StringBuilder();

    private File liveLogFile;
    private BufferedWriter liveWriter;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        bluetoothAdapter = manager == null ? null : manager.getAdapter();
        setupLogFile();
        buildUi();
        requestBlePermissionsIfNeeded();
        log("APP", "Commander BLE Logger started; Android=" + Build.VERSION.RELEASE + "; sdk=" + Build.VERSION.SDK_INT);
    }

    private void buildUi() {
        ScrollView page = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(12), dp(14), dp(20));
        page.addView(root, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Commander BLE Logger");
        title.setTextSize(24);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title, matchWrap());

        TextView note = new TextView(this);
        note.setText("用途：直接扫描/连接 BLE 设备、枚举 GATT、自动订阅 Notify/Indicate、记录读写数据；也可导入 Android Bluetooth HCI snoop(btsnoop) 文件，分析微信与插件之间的 ATT 数据。此应用本身不能在未授权情况下直接窃听另一个 App 的 BLE 会话。\n");
        note.setTextSize(13);
        root.addView(note, matchWrap());

        statusView = new TextView(this);
        statusView.setText("状态：未连接");
        statusView.setTextSize(16);
        root.addView(statusView, matchWrap());

        LinearLayout row1 = horizontalRow();
        Button scanButton = button("扫描 BLE");
        Button stopButton = button("停止扫描");
        Button disconnectButton = button("断开");
        row1.addView(scanButton, weightButton());
        row1.addView(stopButton, weightButton());
        row1.addView(disconnectButton, weightButton());
        root.addView(row1, matchWrap());

        LinearLayout row2 = horizontalRow();
        Button importButton = button("导入 HCI");
        Button exportButton = button("导出日志");
        Button rereadButton = button("读取可读项");
        row2.addView(importButton, weightButton());
        row2.addView(exportButton, weightButton());
        row2.addView(rereadButton, weightButton());
        root.addView(row2, matchWrap());

        TextView deviceTitle = new TextView(this);
        deviceTitle.setText("扫描结果（点设备连接）");
        deviceTitle.setTextSize(16);
        root.addView(deviceTitle, matchWrap());

        ListView deviceView = new ListView(this);
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceLabels);
        deviceView.setAdapter(deviceAdapter);
        root.addView(deviceView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)));

        TextView writeTitle = new TextView(this);
        writeTitle.setText("手动 GATT Write（用于复现握手/查询命令）");
        writeTitle.setTextSize(16);
        root.addView(writeTitle, matchWrap());

        writeSpinner = new Spinner(this);
        writableAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, writableLabels);
        writeSpinner.setAdapter(writableAdapter);
        root.addView(writeSpinner, matchWrap());

        LinearLayout writeRow = horizontalRow();
        hexInput = new EditText(this);
        hexInput.setHint("HEX，例如 A5 01 00 FF");
        Button writeButton = button("发送");
        writeRow.addView(hexInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        writeRow.addView(writeButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(writeRow, matchWrap());

        LinearLayout markerRow = horizontalRow();
        markerInput = new EditText(this);
        markerInput.setHint("事件标记，例如：左前门打开 / 踩油门 / AP开启");
        Button markerButton = button("记录标记");
        markerRow.addView(markerInput, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        markerRow.addView(markerButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(markerRow, matchWrap());

        TextView logTitle = new TextView(this);
        logTitle.setText("实时日志");
        logTitle.setTextSize(16);
        root.addView(logTitle, matchWrap());

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        root.addView(logView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(520)));

        setContentView(page);

        scanButton.setOnClickListener(v -> startBleScan());
        stopButton.setOnClickListener(v -> stopBleScan());
        disconnectButton.setOnClickListener(v -> disconnectGatt());
        exportButton.setOnClickListener(v -> beginExport());
        importButton.setOnClickListener(v -> beginImport());
        rereadButton.setOnClickListener(v -> queueReadableCharacteristics());
        writeButton.setOnClickListener(v -> manualWrite());
        markerButton.setOnClickListener(v -> {
            String marker = markerInput.getText().toString().trim();
            if (marker.isEmpty()) marker = "MARK";
            log("MARK", marker);
        });
        deviceView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < deviceList.size()) connectToDevice(deviceList.get(position));
        });
    }

    private void setupLogFile() {
        try {
            File dir = getExternalFilesDir("logs");
            if (dir == null) dir = new File(getFilesDir(), "logs");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            liveLogFile = new File(dir, "commander_ble_" + stamp + ".log");
            liveWriter = new BufferedWriter(new FileWriter(liveLogFile, true));
        } catch (Exception e) {
            Toast.makeText(this, "日志文件创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void log(String type, String message) {
        String line = timeFormat.format(new Date()) + "\t" + type + "\t" + message;
        synchronized (this) {
            if (liveWriter != null) {
                try {
                    liveWriter.write(line);
                    liveWriter.newLine();
                    liveWriter.flush();
                } catch (Exception ignored) {}
            }
        }
        runOnUiThread(() -> {
            uiLog.append(line).append('\n');
            if (uiLog.length() > 45000) uiLog.delete(0, 15000);
            if (logView != null) logView.setText(uiLog.toString());
        });
    }

    private void requestBlePermissionsIfNeeded() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
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

    private void startBleScan() {
        if (!hasBlePermissions()) {
            requestBlePermissionsIfNeeded();
            return;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            Toast.makeText(this, "请先打开蓝牙", Toast.LENGTH_LONG).show();
            return;
        }
        scanner = bluetoothAdapter.getBluetoothLeScanner();
        if (scanner == null) {
            Toast.makeText(this, "BLE Scanner 不可用", Toast.LENGTH_LONG).show();
            return;
        }
        deviceList.clear();
        deviceLabels.clear();
        deviceIndexByAddress.clear();
        deviceAdapter.notifyDataSetChanged();
        scanning = true;
        status("状态：正在扫描 BLE…");
        log("SCAN", "start");
        scanner.startScan(scanCallback);
    }

    private void stopBleScan() {
        if (!hasBlePermissions()) return;
        if (scanner != null && scanning) {
            try { scanner.stopScan(scanCallback); } catch (Exception ignored) {}
        }
        scanning = false;
        log("SCAN", "stop");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!hasBlePermissions()) return;
            BluetoothDevice d = result.getDevice();
            if (d == null) return;
            String address = d.getAddress();
            String name;
            try { name = d.getName(); } catch (SecurityException e) { name = null; }
            if (name == null || name.trim().isEmpty()) name = "(unknown)";
            String label = name + "\n" + address + "   RSSI " + result.getRssi() + " dBm";
            Integer existing = deviceIndexByAddress.get(address);
            if (existing == null) {
                deviceIndexByAddress.put(address, deviceList.size());
                deviceList.add(d);
                deviceLabels.add(label);
            } else {
                deviceLabels.set(existing, label);
            }
            runOnUiThread(() -> deviceAdapter.notifyDataSetChanged());
        }

        @Override
        public void onScanFailed(int errorCode) {
            log("SCAN_ERROR", "code=" + errorCode);
            status("状态：扫描失败 " + errorCode);
        }
    };

    private void connectToDevice(BluetoothDevice device) {
        if (!hasBlePermissions()) return;
        stopBleScan();
        disconnectGatt();
        String name;
        try { name = device.getName(); } catch (Exception e) { name = null; }
        status("状态：连接 " + (name == null ? device.getAddress() : name));
        log("CONNECT", "address=" + device.getAddress() + "; name=" + name);
        gatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private void disconnectGatt() {
        if (gatt != null && hasBlePermissions()) {
            try { gatt.disconnect(); } catch (Exception ignored) {}
            try { gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null;
        descriptorQueue.clear();
        readQueue.clear();
        gattOperationBusy = false;
        writableChars.clear();
        writableLabels.clear();
        if (writableAdapter != null) writableAdapter.notifyDataSetChanged();
        status("状态：未连接");
    }

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            log("GATT_STATE", "status=" + status + "; newState=" + newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                status("状态：已连接，正在发现服务…");
                if (hasBlePermissions()) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                status("状态：BLE 已断开");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            log("GATT_SERVICES", "status=" + status + "; count=" + g.getServices().size());
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            writableChars.clear();
            writableLabels.clear();
            descriptorQueue.clear();
            readQueue.clear();
            for (BluetoothGattService s : g.getServices()) {
                log("SERVICE", "uuid=" + s.getUuid());
                for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                    int p = c.getProperties();
                    String propText = propertiesText(p);
                    log("CHAR", "service=" + s.getUuid() + "; uuid=" + c.getUuid() + "; properties=" + propText);
                    if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                            (p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                        writableChars.add(c);
                        writableLabels.add(shortUuid(s.getUuid()) + " / " + shortUuid(c.getUuid()) + "  [" + propText + "]");
                    }
                    if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) readQueue.add(c);
                    if ((p & (BluetoothGattCharacteristic.PROPERTY_NOTIFY | BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) {
                        if (hasBlePermissions()) {
                            boolean ok = g.setCharacteristicNotification(c, true);
                            log("SUBSCRIBE_LOCAL", "uuid=" + c.getUuid() + "; ok=" + ok);
                        }
                        BluetoothGattDescriptor d = c.getDescriptor(CCCD_UUID);
                        if (d != null) {
                            byte[] val = (p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0
                                    ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                    : BluetoothGattDescriptor.ENABLE_INDICATION_VALUE;
                            descriptorQueue.add(new DescriptorOp(d, val));
                        }
                    }
                }
            }
            runOnUiThread(() -> writableAdapter.notifyDataSetChanged());
            status("状态：服务发现完成；自动订阅通知中…");
            runNextGattOperation(g);
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor descriptor, int status) {
            log("CCCD_WRITE", "uuid=" + descriptor.getCharacteristic().getUuid() + "; status=" + status);
            gattOperationBusy = false;
            runNextGattOperation(g);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            handleCharacteristicValue("RX_NOTIFY", c, c.getValue());
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value) {
            handleCharacteristicValue("RX_NOTIFY", c, value);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristicValue("RX_READ", c, c.getValue());
            else log("READ_ERROR", "uuid=" + c.getUuid() + "; status=" + status);
            gattOperationBusy = false;
            runNextGattOperation(g);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] value, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) handleCharacteristicValue("RX_READ", c, value);
            else log("READ_ERROR", "uuid=" + c.getUuid() + "; status=" + status);
            gattOperationBusy = false;
            runNextGattOperation(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            log("WRITE_RESULT", "uuid=" + c.getUuid() + "; status=" + status);
        }
    };

    private synchronized void runNextGattOperation(BluetoothGatt g) {
        if (gattOperationBusy || g == null || !hasBlePermissions()) return;
        DescriptorOp dOp = descriptorQueue.poll();
        if (dOp != null) {
            gattOperationBusy = true;
            boolean started;
            if (Build.VERSION.SDK_INT >= 33) {
                int rc = g.writeDescriptor(dOp.descriptor, dOp.value);
                started = rc == BluetoothGatt.GATT_SUCCESS;
            } else {
                dOp.descriptor.setValue(dOp.value);
                started = g.writeDescriptor(dOp.descriptor);
            }
            log("CCCD_QUEUE", "uuid=" + dOp.descriptor.getCharacteristic().getUuid() + "; started=" + started);
            if (!started) {
                gattOperationBusy = false;
                runNextGattOperation(g);
            }
            return;
        }
        BluetoothGattCharacteristic c = readQueue.poll();
        if (c != null) {
            gattOperationBusy = true;
            boolean started = g.readCharacteristic(c);
            log("READ_QUEUE", "uuid=" + c.getUuid() + "; started=" + started);
            if (!started) {
                gattOperationBusy = false;
                runNextGattOperation(g);
            }
            return;
        }
        status("状态：已连接；通知已订阅；开始记录数据");
    }

    private void queueReadableCharacteristics() {
        if (gatt == null || !hasBlePermissions()) {
            Toast.makeText(this, "尚未连接设备", Toast.LENGTH_SHORT).show();
            return;
        }
        readQueue.clear();
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) readQueue.add(c);
            }
        }
        runNextGattOperation(gatt);
    }

    private void manualWrite() {
        if (gatt == null || writableChars.isEmpty() || !hasBlePermissions()) {
            Toast.makeText(this, "没有可写 Characteristic 或设备未连接", Toast.LENGTH_LONG).show();
            return;
        }
        int index = writeSpinner.getSelectedItemPosition();
        if (index < 0 || index >= writableChars.size()) return;
        byte[] data;
        try {
            data = parseHex(hexInput.getText().toString());
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        BluetoothGattCharacteristic c = writableChars.get(index);
        int props = c.getProperties();
        int writeType = (props & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
        log("TX_WRITE", "uuid=" + c.getUuid() + "; writeType=" + writeType + "; hex=" + hex(data));
        boolean started;
        if (Build.VERSION.SDK_INT >= 33) {
            int rc = gatt.writeCharacteristic(c, data, writeType);
            started = rc == BluetoothGatt.GATT_SUCCESS;
        } else {
            c.setWriteType(writeType);
            c.setValue(data);
            started = gatt.writeCharacteristic(c);
        }
        log("TX_START", "uuid=" + c.getUuid() + "; started=" + started);
    }

    private void handleCharacteristicValue(String type, BluetoothGattCharacteristic c, byte[] value) {
        if (value == null) value = new byte[0];
        UUID serviceUuid = c.getService() == null ? null : c.getService().getUuid();
        log(type, "service=" + serviceUuid + "; uuid=" + c.getUuid() + "; len=" + value.length + "; hex=" + hex(value) + "; ascii=" + printableAscii(value));
    }

    private void beginExport() {
        if (liveLogFile == null || !liveLogFile.exists()) {
            Toast.makeText(this, "没有日志文件", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TITLE, liveLogFile.getName());
        startActivityForResult(i, REQ_EXPORT);
    }

    private void beginImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT) {
            try (InputStream in = new FileInputStream(liveLogFile);
                 OutputStream out = new BufferedOutputStream(getContentResolver().openOutputStream(uri))) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                Toast.makeText(this, "日志已导出", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "导出失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQ_IMPORT) {
            new Thread(() -> parseBtsnoop(uri)).start();
        }
    }

    private void parseBtsnoop(Uri uri) {
        log("HCI_IMPORT", "start uri=" + uri);
        int records = 0;
        int attRecords = 0;
        try (InputStream raw = getContentResolver().openInputStream(uri);
             DataInputStream in = new DataInputStream(new BufferedInputStream(raw))) {
            byte[] magic = new byte[8];
            in.readFully(magic);
            String magicText = new String(magic, 0, 7, StandardCharsets.US_ASCII);
            if (!"btsnoop".equals(magicText)) throw new IllegalArgumentException("不是标准 btsnoop 文件");
            int version = in.readInt();
            int datalink = in.readInt();
            log("HCI_HEADER", "version=" + version + "; datalink=" + datalink);
            while (true) {
                int originalLength;
                try { originalLength = in.readInt(); } catch (Exception eof) { break; }
                int includedLength = in.readInt();
                int flags = in.readInt();
                int drops = in.readInt();
                long timestamp = in.readLong();
                if (includedLength < 0 || includedLength > 2_000_000) throw new IllegalArgumentException("异常记录长度 " + includedLength);
                byte[] packet = new byte[includedLength];
                in.readFully(packet);
                records++;
                String parsed = parseHciAtt(packet, flags, timestamp, originalLength, drops);
                if (parsed != null) {
                    attRecords++;
                    log("HCI_ATT", parsed);
                }
            }
            int finalRecords = records;
            int finalAtt = attRecords;
            runOnUiThread(() -> Toast.makeText(this, "HCI 导入完成：" + finalRecords + " 条记录，发现 " + finalAtt + " 条 ATT/GATT 数据", Toast.LENGTH_LONG).show());
            log("HCI_IMPORT", "done records=" + records + "; att=" + attRecords);
        } catch (Exception e) {
            log("HCI_IMPORT_ERROR", e.getClass().getSimpleName() + ": " + e.getMessage());
            runOnUiThread(() -> Toast.makeText(this, "HCI 导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show());
        }
    }

    private String parseHciAtt(byte[] p, int flags, long timestamp, int originalLength, int drops) {
        // Android Bluetooth HCI snoop normally uses DLT 1002 (H4). We only need ACL -> L2CAP ATT (CID 0x0004).
        if (p == null || p.length < 10) return null;
        int offset;
        if ((p[0] & 0xFF) == 0x02) offset = 1;      // H4 ACL packet type
        else offset = 0;                            // tolerate capture variants without H4 type
        if (p.length < offset + 9) return null;
        int aclLength = le16(p, offset + 2);
        int l2capOffset = offset + 4;
        if (p.length < l2capOffset + 4) return null;
        int l2capLength = le16(p, l2capOffset);
        int cid = le16(p, l2capOffset + 2);
        if (cid != 0x0004) return null;
        int attOffset = l2capOffset + 4;
        if (p.length <= attOffset) return null;
        int opcode = p[attOffset] & 0xFF;
        int handle = -1;
        if (opcode == 0x02 || opcode == 0x03 || opcode == 0x08 || opcode == 0x0A || opcode == 0x0C ||
                opcode == 0x12 || opcode == 0x16 || opcode == 0x18 || opcode == 0x1B || opcode == 0x1D || opcode == 0x52) {
            if (p.length >= attOffset + 3) handle = le16(p, attOffset + 1);
        }
        byte[] att = new byte[p.length - attOffset];
        System.arraycopy(p, attOffset, att, 0, att.length);
        String direction = (flags & 1) != 0 ? "controller_to_host" : "host_to_controller";
        return "dir=" + direction + "; ts=" + timestamp + "; flags=0x" + Integer.toHexString(flags) +
                "; aclLen=" + aclLength + "; l2capLen=" + l2capLength + "; opcode=0x" + String.format(Locale.US, "%02X", opcode) +
                "; opcodeName=" + attOpcodeName(opcode) + "; handle=" + (handle < 0 ? "-" : String.format(Locale.US, "0x%04X", handle)) +
                "; att=" + hex(att) + "; originalLen=" + originalLength + "; drops=" + drops;
    }

    private String attOpcodeName(int op) {
        switch (op) {
            case 0x02: return "EXCHANGE_MTU_REQ";
            case 0x03: return "EXCHANGE_MTU_RSP";
            case 0x08: return "READ_BY_TYPE_REQ";
            case 0x09: return "READ_BY_TYPE_RSP";
            case 0x0A: return "READ_REQ";
            case 0x0B: return "READ_RSP";
            case 0x0C: return "READ_BLOB_REQ";
            case 0x0D: return "READ_BLOB_RSP";
            case 0x12: return "WRITE_REQ";
            case 0x13: return "WRITE_RSP";
            case 0x16: return "PREPARE_WRITE_REQ";
            case 0x17: return "PREPARE_WRITE_RSP";
            case 0x18: return "EXECUTE_WRITE_REQ";
            case 0x19: return "EXECUTE_WRITE_RSP";
            case 0x1B: return "HANDLE_NOTIFICATION";
            case 0x1D: return "HANDLE_INDICATION";
            case 0x1E: return "HANDLE_CONFIRMATION";
            case 0x52: return "WRITE_COMMAND";
            default: return "ATT_" + String.format(Locale.US, "%02X", op);
        }
    }

    private int le16(byte[] b, int off) {
        if (off < 0 || off + 1 >= b.length) return 0;
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private byte[] parseHex(String text) {
        String s = text == null ? "" : text.replace("0x", "").replace("0X", "").replaceAll("[^0-9A-Fa-f]", "");
        if (s.isEmpty()) throw new IllegalArgumentException("请输入 HEX 数据");
        if ((s.length() & 1) != 0) throw new IllegalArgumentException("HEX 字符数量必须是偶数");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("HEX 格式错误");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format(Locale.US, "%02X", b[i] & 0xFF));
        }
        return sb.toString();
    }

    private String printableAscii(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte value : b) {
            int v = value & 0xFF;
            s.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        return s.toString();
    }

    private String propertiesText(int p) {
        ArrayList<String> list = new ArrayList<>();
        if ((p & BluetoothGattCharacteristic.PROPERTY_READ) != 0) list.add("READ");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) list.add("WRITE");
        if ((p & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) list.add("WRITE_NR");
        if ((p & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) list.add("NOTIFY");
        if ((p & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) list.add("INDICATE");
        if ((p & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) list.add("BROADCAST");
        return list.toString();
    }

    private String shortUuid(UUID uuid) {
        String s = uuid.toString();
        if (s.endsWith("-0000-1000-8000-00805f9b34fb") && s.startsWith("0000")) return "0x" + s.substring(4, 8).toUpperCase(Locale.US);
        return s;
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

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        stopBleScan();
        disconnectGatt();
        synchronized (this) {
            try { if (liveWriter != null) liveWriter.close(); } catch (Exception ignored) {}
            liveWriter = null;
        }
        super.onDestroy();
    }

    private static class DescriptorOp {
        final BluetoothGattDescriptor descriptor;
        final byte[] value;
        DescriptorOp(BluetoothGattDescriptor descriptor, byte[] value) {
            this.descriptor = descriptor;
            this.value = value;
        }
    }
}
