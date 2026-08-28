package com.teslalyrics.app;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tests whether a Tesla hotspot client can reach an address that is globally-routable
 * from the browser's point of view but is owned by the Android phone itself.
 * This is intentionally separate from the RFC1918 probe and uses different ports.
 */
public final class Ipv6ProbeServer {
    private static final int[] PORTS={8766,8081};
    private static volatile Ipv6ProbeServer instance;
    private final AppState state=AppState.get();
    private final ExecutorService workers=Executors.newFixedThreadPool(3);
    private final List<ServerSocket> sockets=new ArrayList<>();
    private final AtomicLong requests=new AtomicLong();
    private volatile boolean running=false;
    private volatile String lastClient="none",lastError="none";

    public Ipv6ProbeServer(){instance=this;}

    public synchronized void start(){
        if(running)return;
        running=true;
        int opened=0;
        for(int port:PORTS){
            try{
                ServerSocket server=new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress(InetAddress.getByName("::"),port),16);
                sockets.add(server);
                opened++;
                Thread t=new Thread(()->accept(server),"TeslaLyrics-v6-"+port);
                t.setDaemon(true);
                t.start();
                state.log.add("IPv6 probe listening: [::]:"+port);
            }catch(Exception e){
                lastError="port "+port+": "+e.getClass().getSimpleName()+" "+safe(e.getMessage());
                state.log.add("IPv6 probe error: "+lastError);
            }
        }
        if(opened==0)running=false;
    }

    public synchronized void stop(){
        running=false;
        for(ServerSocket s:new ArrayList<>(sockets))try{s.close();}catch(Exception ignored){}
        sockets.clear();
        workers.shutdownNow();
    }

    private void accept(ServerSocket server){
        while(running&&!server.isClosed()){
            try{
                Socket client=server.accept();
                client.setSoTimeout(5000);
                workers.execute(()->handle(client));
            }catch(Exception e){
                if(running){lastError=e.getClass().getSimpleName()+" "+safe(e.getMessage());}
            }
        }
    }

    private void handle(Socket socket){
        String remote="unknown";
        try(Socket s=socket){
            remote=s.getInetAddress()==null?"unknown":s.getInetAddress().getHostAddress();
            lastClient=remote;
            requests.incrementAndGet();
            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));
            String first=in.readLine();
            if(first==null)return;
            String line;
            while((line=in.readLine())!=null&&!line.isEmpty()){}
            String body="<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"+
                    "<title>Tesla Lyrics IPv6 Probe</title><style>body{background:#0f0f11;color:#fff;font:22px Arial;padding:32px}.ok{color:#7cff9b}pre{white-space:pre-wrap}</style></head><body>"+
                    "<h1 class='ok'>TESLA IPV6 LOCAL LINK OK</h1>"+
                    "<p>这条连接没有使用 RFC1918 私网目标地址。</p>"+
                    "<pre>Client: "+esc(remote)+"\nPhone IPv6: "+esc(NetworkUtils.bestGlobalV6Address())+"\nRequests: "+requests.get()+"</pre>"+
                    "</body></html>";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            BufferedWriter out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.US_ASCII));
            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+bytes.length+"\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n");
            out.flush();
            s.getOutputStream().write(bytes);
            s.getOutputStream().flush();
            state.log.add("IPv6 probe request: "+remote);
        }catch(Exception e){
            lastError=e.getClass().getSimpleName()+" "+safe(e.getMessage());
            state.log.add("IPv6 request error from "+remote+": "+lastError);
        }
    }

    private static String safe(String s){return s==null?"":s;}
    private static String esc(String s){return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");}

    public static String statusReport(){
        Ipv6ProbeServer x=instance;
        if(x==null)return "IPv6 probe: not created";
        return "IPv6 probe: "+(x.running?"Running":"Stopped")+"\nRequests: "+x.requests.get()+"\nLast client: "+x.lastClient+"\nLast error: "+x.lastError;
    }
}
