package com.teslalyrics.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Minimal mDNS responder for the fixed local hostname teslalyrics.local.
 * This intentionally answers an IPv4 A record directly so the Tesla browser
 * can bookmark one stable URL even when Android changes the hotspot IP.
 */
public final class MdnsResponder {
    private static final String HOST = "teslalyrics.local";
    private static final InetSocketAddress MDNS_GROUP = new InetSocketAddress("224.0.0.251", 5353);
    private static final byte[] HOST_WIRE = new byte[]{
            11,'t','e','s','l','a','l','y','r','i','c','s',
            5,'l','o','c','a','l',0
    };

    private final Context context;
    private final Set<String> joined = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean running;
    private Thread thread;
    private MulticastSocket socket;
    private WifiManager.MulticastLock multicastLock;

    public MdnsResponder(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("TeslaLyrics-mDNS");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}
        thread = new Thread(this::runLoop, "TeslaLyrics-mDNS");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        try {
            if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
        } catch (Exception ignored) {}
        multicastLock = null;
    }

    private void runLoop() {
        try {
            MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(5353));
            s.setTimeToLive(255);
            s.setSoTimeout(3000);
            socket = s;
            refreshInterfaces(s);
            AppState.get().log.add("mDNS ready: http://teslalyrics.local:8765");

            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                    if (containsHost(p.getData(), p.getOffset(), p.getLength())) {
                        reply(s, p);
                    }
                } catch (SocketTimeoutException timeout) {
                    refreshInterfaces(s);
                }
            }
        } catch (Exception e) {
            if (running) AppState.get().log.add("mDNS failed: " + e.getMessage());
        } finally {
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            socket = null;
        }
    }

    private void refreshInterfaces(MulticastSocket s) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                try {
                    if (!ni.isUp() || ni.isLoopback() || !ni.supportsMulticast()) continue;
                    boolean hasV4 = false;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) { hasV4 = true; break; }
                    }
                    if (!hasV4 || joined.contains(ni.getName())) continue;
                    s.joinGroup(MDNS_GROUP, ni);
                    joined.add(ni.getName());
                    AppState.get().log.add("mDNS joined: " + ni.getName());
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    private void reply(MulticastSocket s, DatagramPacket query) {
        try {
            String ip = NetworkUtils.bestLanAddress();
            InetAddress addr = InetAddress.getByName(ip);
            if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) return;
            byte[] answer = buildAResponse(((Inet4Address) addr).getAddress());

            DatagramPacket multicast = new DatagramPacket(answer, answer.length, MDNS_GROUP);
            s.send(multicast);

            // Also answer directly to the requester. This helps clients that set the mDNS
            // unicast-response bit and is harmless for normal multicast queries.
            if (query.getAddress() != null && !query.getAddress().isMulticastAddress()) {
                DatagramPacket unicast = new DatagramPacket(answer, answer.length,
                        query.getAddress(), query.getPort());
                s.send(unicast);
            }
        } catch (Exception e) {
            AppState.get().log.add("mDNS reply failed: " + e.getMessage());
        }
    }

    private static byte[] buildAResponse(byte[] ipv4) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        DataOutputStream out = new DataOutputStream(bos);
        out.writeShort(0);          // mDNS transaction ID
        out.writeShort(0x8400);     // response + authoritative answer
        out.writeShort(0);          // questions
        out.writeShort(1);          // answers
        out.writeShort(0);          // authority
        out.writeShort(0);          // additional
        out.write(HOST_WIRE);
        out.writeShort(1);          // A
        out.writeShort(0x8001);     // IN + cache-flush
        out.writeInt(120);          // TTL
        out.writeShort(4);
        out.write(ipv4);
        out.flush();
        return bos.toByteArray();
    }

    private static boolean containsHost(byte[] data, int off, int len) {
        int end = off + len - HOST_WIRE.length;
        for (int i = off; i <= end; i++) {
            boolean ok = true;
            for (int j = 0; j < HOST_WIRE.length; j++) {
                int a = data[i + j] & 0xff;
                int b = HOST_WIRE[j] & 0xff;
                if (a >= 'A' && a <= 'Z') a += 32;
                if (a != b) { ok = false; break; }
            }
            if (ok) return true;
        }
        return false;
    }
}
