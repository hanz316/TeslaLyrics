package com.hanz.commanderlogger;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Automatic, read-only Commander capture tool.
 *
 * It scans only FFF0, connects FFF1, subscribes Notify, requests A0,
 * polls B0/D0 and can optionally request C0 receive-all. It does NOT expose
 * A7 vehicle control, A3 writes, C0 arbitrary CAN TX, firmware, reboot or reset.
 */
public class CommanderAutoCaptureActivity extends Activity {
    private static final int REQ_PERMS=801, REQ_EXPORT=802;
    private static final UUID SERVICE=UUID.fromString("0000FFF0-0000-1000-8000-00805F9B34FB");
    private static final UUID CHAR=UUID.fromString("0000FFF1-0000-1000-8000-00805F9B34FB");
    private static final UUID CCCD=UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");

    private final Handler h=new Handler(Looper.getMainLooper());
    private final CommanderProtocol.StreamParser parser=new CommanderProtocol.StreamParser();
    private BluetoothAdapter adapter; private BluetoothLeScanner scanner; private BluetoothGatt gatt; private BluetoothGattCharacteristic io;
    private boolean scanning,ready,rawCan; private int mtu=23, batteryStep=0;
    private TextView status,live,stats; private EditText password;
    private final StringBuilder ui=new StringBuilder();
    private File logFile; private BufferedWriter writer;
    private long rxFrames,canFrames,b0Frames,d0Frames,d1Frames,d2Frames,badOrUnknown,lastB0,lastD0;

    @Override public void onCreate(Bundle b){ super.onCreate(b); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        BluetoothManager m=(BluetoothManager)getSystemService(BLUETOOTH_SERVICE); adapter=m==null?null:m.getAdapter(); setupLog(); buildUi(); requestPerms(); }

    private void buildUi(){
        ScrollView sv=new ScrollView(this); LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(12),dp(14),dp(28)); sv.addView(root);
        TextView t=new TextView(this);t.setText("Commander 自动只读采集");t.setTextSize(24);root.addView(t,mw());
        TextView note=new TextView(this); note.setText("无需逐个事件打标。软件只扫描 FFF0，自动连接 FFF1、订阅 Notify，并读取 A0/B0/D0/D1/D2。Raw CAN 开关只发送 C0 接收请求，不提供 CAN 发送、A7车辆控制、设置写入、重启或升级。首次建议车辆停稳时验证。\n"); note.setTextSize(14);root.addView(note,mw());
        status=new TextView(this);status.setText("状态：未连接");status.setTextSize(16);root.addView(status,mw());
        password=new EditText(this);password.setHint("如模块要求密码，输入4位密码（小程序提示默认1234）");root.addView(password,mw());
        LinearLayout r1=row(); Button auto=btn("自动找指挥官并连接");Button auth=btn("发送密码");Button disc=btn("断开");r1.addView(auto,w());r1.addView(auth,w());r1.addView(disc,w());root.addView(r1,mw());
        LinearLayout r2=row();Button raw=btn("Raw CAN RX：关闭");Button export=btn("导出日志");Button old=btn("旧高级工具");r2.addView(raw,w());r2.addView(export,w());r2.addView(old,w());root.addView(r2,mw());
        stats=new TextView(this);stats.setTextSize(13);root.addView(stats,mw());
        live=new TextView(this);live.setTextSize(12);live.setTextIsSelectable(true);root.addView(live,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(600)));
        setContentView(sv);
        auto.setOnClickListener(v->startAutoScan()); auth.setOnClickListener(v->sendPassword()); disc.setOnClickListener(v->disconnect()); export.setOnClickListener(v->beginExport()); old.setOnClickListener(v->startActivity(new Intent(this,MainActivity.class)));
        raw.setOnClickListener(v->{ if(!ready){toast("尚未连接完成");return;} if(!rawCan){ new AlertDialog.Builder(this).setTitle("开启 Raw CAN 只读接收？").setMessage("只发送 C0 01 接收全部ID，不发送任何CAN帧。数据量可能很大，建议先停车验证。是否开启？").setNegativeButton("取消",null).setPositiveButton("开启",(d,x)->{rawCan=true;send(CommanderProtocol.cmdC0AllRx(),"C0_RX_ALL");raw.setText("Raw CAN RX：开启");}).show(); } else {rawCan=false;send(CommanderProtocol.cmdC0Off(),"C0_RX_OFF");raw.setText("Raw CAN RX：关闭");}});
        h.post(statsTick);
    }

    private void setupLog(){try{File d=getExternalFilesDir("commander");if(d==null)d=getFilesDir();d.mkdirs();String s=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());logFile=new File(d,"commander_auto_"+s+".jsonl");writer=new BufferedWriter(new FileWriter(logFile,true));}catch(Exception e){toast("日志创建失败: "+e.getMessage());}}
    private void log(String kind,String text){String clean=text==null?"":text.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n");String line="{\"ts\":"+System.currentTimeMillis()+",\"kind\":\""+kind+"\",\"text\":\""+clean+"\"}"; synchronized(this){try{if(writer!=null){writer.write(line);writer.newLine();writer.flush();}}catch(Exception ignored){}} runOnUiThread(()->{ui.append(kind).append("  ").append(text).append('\n');if(ui.length()>65000)ui.delete(0,22000);live.setText(ui.toString());});}

    private boolean perms(){if(Build.VERSION.SDK_INT>=31)return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED;return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requestPerms(){ArrayList<String>a=new ArrayList<>();if(Build.VERSION.SDK_INT>=31){if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)a.add(Manifest.permission.BLUETOOTH_SCAN);if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED)a.add(Manifest.permission.BLUETOOTH_CONNECT);}else if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)a.add(Manifest.permission.ACCESS_FINE_LOCATION);if(!a.isEmpty())requestPermissions(a.toArray(new String[0]),REQ_PERMS);}

    private void startAutoScan(){if(!perms()){requestPerms();return;}if(adapter==null||!adapter.isEnabled()){toast("请先打开蓝牙");return;}disconnect();scanner=adapter.getBluetoothLeScanner();if(scanner==null){toast("BLE Scanner不可用");return;}scanning=true;status.setText("状态：扫描 FFF0…");log("SCAN","start service FFF0");ScanFilter f=new ScanFilter.Builder().setServiceUuid(new ParcelUuid(SERVICE)).build();ScanSettings s=new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();scanner.startScan(Collections.singletonList(f),s,scanCb);h.postDelayed(()->{if(scanning){stopScan();status.setText("状态：10秒内未找到FFF0，请让指挥官上电且关闭微信连接后重试");}},10000);}
    private void stopScan(){if(scanner!=null&&scanning&&perms())try{scanner.stopScan(scanCb);}catch(Exception ignored){}scanning=false;}
    private final ScanCallback scanCb=new ScanCallback(){@Override public void onScanResult(int c,ScanResult r){if(!scanning||r.getDevice()==null)return;BluetoothDevice d=r.getDevice();log("FOUND","addr="+d.getAddress()+" rssi="+r.getRssi());stopScan();status.setText("状态：已找到 FFF0，连接中…");gatt=d.connectGatt(CommanderAutoCaptureActivity.this,false,gattCb,BluetoothDevice.TRANSPORT_LE);} @Override public void onScanFailed(int e){scanning=false;log("SCAN_ERR","code="+e);status.setText("状态：扫描失败 "+e);}};

    private final BluetoothGattCallback gattCb=new BluetoothGattCallback(){
        @Override public void onConnectionStateChange(BluetoothGatt g,int st,int ns){log("GATT","state status="+st+" new="+ns);if(ns==BluetoothProfile.STATE_CONNECTED){statusText("状态：BLE已连接，发现服务…");if(perms())g.discoverServices();}else if(ns==BluetoothProfile.STATE_DISCONNECTED){ready=false;statusText("状态：已断开");h.removeCallbacks(poller);}}
        @Override public void onServicesDiscovered(BluetoothGatt g,int st){if(st!=BluetoothGatt.GATT_SUCCESS){statusText("状态：服务发现失败 "+st);return;}BluetoothGattService s=g.getService(SERVICE);io=s==null?null:s.getCharacteristic(CHAR);if(io==null){statusText("状态：找到设备但没有 FFF1");return;}log("GATT","FFF0/FFF1 OK props=0x"+Integer.toHexString(io.getProperties()));if(Build.VERSION.SDK_INT>=21&&perms()){boolean rq=g.requestMtu(247);if(!rq)subscribe(g);}else subscribe(g);}
        @Override public void onMtuChanged(BluetoothGatt g,int m,int st){if(st==BluetoothGatt.GATT_SUCCESS)mtu=m;log("MTU","mtu="+m+" status="+st);subscribe(g);}
        @Override public void onDescriptorWrite(BluetoothGatt g,BluetoothGattDescriptor d,int st){log("CCCD","status="+st);if(st==BluetoothGatt.GATT_SUCCESS){statusText("状态：Notify已开启，读取DeviceInfo…");send(CommanderProtocol.cmdA0(),"A0_QUERY");}}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c){handleNotify(c.getValue());}
        @Override public void onCharacteristicChanged(BluetoothGatt g,BluetoothGattCharacteristic c,byte[] value){handleNotify(value);}
        @Override public void onCharacteristicWrite(BluetoothGatt g,BluetoothGattCharacteristic c,int st){if(st!=BluetoothGatt.GATT_SUCCESS)log("WRITE_ERR","status="+st);}
    };

    private void subscribe(BluetoothGatt g){if(!perms()||io==null)return;try{g.setCharacteristicNotification(io,true);BluetoothGattDescriptor d=io.getDescriptor(CCCD);if(d==null){statusText("状态：FFF1没有CCCD");return;}byte[] val=(io.getProperties()&BluetoothGattCharacteristic.PROPERTY_INDICATE)!=0?BluetoothGattDescriptor.ENABLE_INDICATION_VALUE:BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;if(Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,val);else{d.setValue(val);g.writeDescriptor(d);}}catch(Exception e){statusText("状态：订阅失败 "+e.getMessage());}}

    private void handleNotify(byte[] v){if(v==null)return;log("BLE_RX",CommanderProtocol.hex(v));List<CommanderProtocol.Packet> ps=parser.feed(v,System.currentTimeMillis());for(CommanderProtocol.Packet p:ps){rxFrames++;String decoded;switch(p.type){case CommanderProtocol.A0:decoded=CommanderProtocol.deviceInfo(p.payload);log("A0",decoded);ready=true;statusText("状态：Commander已就绪，自动采集B0+D0");startPoll();break;case CommanderProtocol.A8:int x=p.payload.length>0?p.payload[0]&255:-1;decoded="auth="+x;log("A8",decoded);if(x==255){ready=false;statusText("状态：模块要求4位密码，请输入后点“发送密码”");}else if(x==1){ready=true;statusText("状态：密码正确，继续采集");send(CommanderProtocol.cmdA0(),"A0_QUERY_AFTER_AUTH");}else if(x==0){ready=false;statusText("状态：密码错误");}break;case CommanderProtocol.B0:b0Frames++;lastB0=System.currentTimeMillis();decoded=CommanderProtocol.gauge(p.payload);log("B0",decoded);break;case CommanderProtocol.D0:d0Frames++;lastD0=System.currentTimeMillis();log("D0",CommanderProtocol.battery(p.payload));break;case CommanderProtocol.D1:d1Frames++;log("D1",CommanderProtocol.cells(p.payload));break;case CommanderProtocol.D2:d2Frames++;log("D2",CommanderProtocol.dcdc(p.payload));break;case CommanderProtocol.C0:canFrames++;log("CAN",CommanderProtocol.can(p.payload));break;default:badOrUnknown++;log(String.format(Locale.US,"TYPE_%02X",p.type),CommanderProtocol.hex(p.payload));}}
    }

    private void sendPassword(){String k=password.getText().toString();byte[] f=CommanderProtocol.cmdA8(k);if(f==null){toast("密码必须正好4个ASCII字符");return;}send(f,"A8_AUTH");}
    private void startPoll(){h.removeCallbacks(poller);batteryStep=0;send(CommanderProtocol.cmdD0(1),"D0_ENTER");h.post(poller);}
    private final Runnable poller=new Runnable(){@Override public void run(){if(!ready||gatt==null||io==null)return;long now=System.currentTimeMillis();send(CommanderProtocol.cmdB0(true),"B0_QUERY"); // One read-only battery request every 500 ms; cycle summary/DC-DC/cells.
        int mode; batteryStep++; if(batteryStep%10==0)mode=3; else if(batteryStep%4==0)mode=4; else mode=2; send(CommanderProtocol.cmdD0(mode),"D0_MODE_"+mode);h.postDelayed(this,1000);}};

    private void send(byte[] frame,String label){if(frame==null||gatt==null||io==null||!perms())return;int max=Math.max(20,mtu-3);int off=0;while(off<frame.length){int n=Math.min(max,frame.length-off);byte[] chunk=Arrays.copyOfRange(frame,off,off+n);try{if((io.getProperties()&BluetoothGattCharacteristic.PROPERTY_WRITE)!=0)io.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);else io.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);boolean ok;if(Build.VERSION.SDK_INT>=33)ok=gatt.writeCharacteristic(io,chunk,io.getWriteType())==0;else{io.setValue(chunk);ok=gatt.writeCharacteristic(io);}log("BLE_TX",label+" ok="+ok+" "+CommanderProtocol.hex(chunk));}catch(Exception e){log("WRITE_ERR",label+" "+e.getMessage());}off+=n;}}

    private void disconnect(){stopScan();h.removeCallbacks(poller);if(ready&&io!=null){try{send(CommanderProtocol.cmdB0(false),"B0_OFF");send(CommanderProtocol.cmdD0(0),"D0_OFF");if(rawCan)send(CommanderProtocol.cmdC0Off(),"C0_OFF");}catch(Exception ignored){}}ready=false;rawCan=false;parser.reset();if(gatt!=null&&perms()){try{gatt.disconnect();}catch(Exception ignored){}try{gatt.close();}catch(Exception ignored){}}gatt=null;io=null;}
    @Override protected void onDestroy(){disconnect();synchronized(this){try{if(writer!=null)writer.close();}catch(Exception ignored){}}super.onDestroy();}

    private final Runnable statsTick=new Runnable(){@Override public void run(){if(stats!=null)stats.setText("Frames="+rxFrames+"  B0="+b0Frames+" D0="+d0Frames+" D1="+d1Frames+" D2="+d2Frames+" CAN="+canFrames+" Other="+badOrUnknown+"  MTU="+mtu+"\n最后B0="+age(lastB0)+"  最后D0="+age(lastD0)+"\n日志："+(logFile==null?"?":logFile.getAbsolutePath()));h.postDelayed(this,1000);}};
    private String age(long t){return t==0?"--":(System.currentTimeMillis()-t)+"ms前";}

    private void beginExport(){if(logFile==null){toast("没有日志");return;}Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT);i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("application/json");i.putExtra(Intent.EXTRA_TITLE,logFile.getName());startActivityForResult(i,REQ_EXPORT);}
    @Override protected void onActivityResult(int req,int res,Intent data){super.onActivityResult(req,res,data);if(req==REQ_EXPORT&&res==RESULT_OK&&data!=null&&data.getData()!=null){Uri u=data.getData();try(FileInputStream in=new FileInputStream(logFile);OutputStream out=getContentResolver().openOutputStream(u)){byte[] b=new byte[16384];int n;while((n=in.read(b))>0)out.write(b,0,n);toast("导出完成");}catch(Exception e){toast("导出失败: "+e.getMessage());}}}

    private void statusText(String s){runOnUiThread(()->status.setText(s));}
    private void toast(String s){runOnUiThread(()->Toast.makeText(this,s,Toast.LENGTH_LONG).show());}
    private Button btn(String s){Button b=new Button(this);b.setText(s);return b;} private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);return r;} private LinearLayout.LayoutParams w(){return new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,1f);} private LinearLayout.LayoutParams mw(){return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);} private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
}
