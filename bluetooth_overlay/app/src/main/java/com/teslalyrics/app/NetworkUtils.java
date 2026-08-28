package com.teslalyrics.app;

import java.net.Inet4Address;
import java.net.Inet6Address;
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

    private static List<Candidate> globalV6Candidates() {
        List<Candidate> out=new ArrayList<>();
        try {
            for (NetworkInterface ni:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if(!ni.isUp()||ni.isLoopback())continue;
                String name=ni.getName()==null?"":ni.getName();
                String low=name.toLowerCase(Locale.US);
                for(InetAddress a:Collections.list(ni.getInetAddresses())) {
                    if(!(a instanceof Inet6Address)||a.isLoopbackAddress()||a.isLinkLocalAddress()||a.isMulticastAddress()||a.isAnyLocalAddress())continue;
                    String ip=stripScope(a.getHostAddress()).toLowerCase(Locale.ROOT);
                    if(!isGlobalV6(ip))continue;
                    int s=20;
                    if(low.contains("softap")||low.contains("tether"))s+=220;
                    if(low.equals("ap0")||low.startsWith("ap"))s+=210;
                    if(low.contains("swlan"))s+=200;
                    if(low.equals("wlan1")||low.equals("wlan2"))s+=170;
                    if(low.startsWith("rmnet")||low.contains("ccmni")||low.contains("pdp")||low.contains("wwan")||low.contains("cell"))s+=100;
                    out.add(new Candidate(name,ip,s));
                }
            }
        } catch(Exception ignored){}
        out.sort(Comparator.comparingInt((Candidate c)->c.score).reversed());
        return out;
    }

    private static int score(String iface, String ip) {
        int s=0;
        if (iface.contains("rmnet") || iface.contains("ccmni") || iface.contains("pdp") ||
                iface.contains("wwan") || iface.contains("cell") || iface.contains("clat") ||
                iface.startsWith("tun") || iface.contains("dummy")) return -1000;
        if (iface.contains("softap") || iface.contains("tether")) s += 180;
        if (iface.equals("ap0") || iface.startsWith("ap")) s += 170;
        if (iface.contains("swlan")) s += 160;
        if (iface.equals("wlan1") || iface.equals("wlan2")) s += 130;
        if (iface.contains("p2p")) s += 40;
        if (iface.equals("wlan0")) s += 20;
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

    private static boolean isGlobalV6(String ip){
        if(ip==null||ip.isEmpty()||!ip.contains(":"))return false;
        String s=ip.toLowerCase(Locale.ROOT);
        return !(s.startsWith("fe8")||s.startsWith("fe9")||s.startsWith("fea")||s.startsWith("feb")||
                s.startsWith("fc")||s.startsWith("fd")||s.equals("::1")||s.equals("::"));
    }

    private static String stripScope(String ip){
        if(ip==null)return "";
        int p=ip.indexOf('%');
        return p>=0?ip.substring(0,p):ip;
    }

    public static String bestLanAddress() {
        List<Candidate> c=candidates();
        return c.isEmpty()?"0.0.0.0":c.get(0).ip;
    }

    public static String bestGlobalV6Address(){
        List<Candidate> c=globalV6Candidates();
        return c.isEmpty()?"":c.get(0).ip;
    }

    public static String ipv6NipHost(String ip){
        String s=stripScope(ip).toLowerCase(Locale.ROOT);
        return s.isEmpty()?"":s.replace(':','-')+".nip.io";
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

    public static String globalV6Report(){
        List<Candidate> c=globalV6Candidates();
        if(c.isEmpty())return "未检测到 Global IPv6";
        StringBuilder b=new StringBuilder();
        for(Candidate x:c){
            if(b.length()>0)b.append('\n');
            b.append(x.iface).append(" → ").append(x.ip);
        }
        return b.toString();
    }
}
