package com.teslalyrics.app;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class NetworkUtils {
    private static final class Candidate {
        final String iface;
        final String ip;
        final int score;
        Candidate(String iface, String ip, int score) { this.iface=iface; this.ip=ip; this.score=score; }
    }

    private static List<Candidate> candidates() {
        List<Candidate> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName()==null ? "" : ni.getName();
                String low = name.toLowerCase(Locale.US);
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    int s = score(low, ip);
                    if (s > -500) out.add(new Candidate(name, ip, s));
                }
            }
        } catch (Exception ignored) {}
        out.sort(Comparator.comparingInt((Candidate c)->c.score).reversed());
        return out;
    }

    private static int score(String iface, String ip) {
        int s=0;
        // Mobile-data/VPN interfaces are not reachable by hotspot clients.
        if (iface.contains("rmnet") || iface.contains("ccmni") || iface.contains("pdp") ||
                iface.contains("wwan") || iface.contains("cell") || iface.contains("clat") ||
                iface.startsWith("tun") || iface.contains("dummy")) return -1000;

        // Common Android hotspot / SoftAP interface names.
        if (iface.contains("softap") || iface.contains("tether")) s += 180;
        if (iface.equals("ap0") || iface.startsWith("ap")) s += 170;
        if (iface.contains("swlan")) s += 160;
        if (iface.equals("wlan1") || iface.equals("wlan2")) s += 130;
        if (iface.contains("p2p")) s += 40;
        if (iface.equals("wlan0")) s += 20;

        // Hotspot gateway addresses are very commonly the first host in the subnet.
        if (ip.endsWith(".1")) s += 90;
        else if (ip.endsWith(".254")) s += 40;

        if (ip.startsWith("192.168.")) s += 40;
        else if (is172Private(ip)) s += 30;
        else if (ip.startsWith("10.")) s += 20;
        return s;
    }

    private static boolean is172Private(String ip) {
        if (!ip.startsWith("172.")) return false;
        try { int second=Integer.parseInt(ip.split("\\.")[1]); return second>=16 && second<=31; }
        catch (Exception e) { return false; }
    }

    public static String bestLanAddress() {
        List<Candidate> c=candidates();
        return c.isEmpty()?"0.0.0.0":c.get(0).ip;
    }

    public static String candidateReport() {
        List<Candidate> c=candidates();
        if (c.isEmpty()) return "未找到局域网 IPv4";
        StringBuilder b=new StringBuilder();
        for (Candidate x:c) {
            if (b.length()>0) b.append('\n');
            b.append(x.iface).append(" → http://").append(x.ip).append(":8765");
        }
        return b.toString();
    }
}
