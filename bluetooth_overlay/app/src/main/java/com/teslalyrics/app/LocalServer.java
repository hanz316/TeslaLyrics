package com.teslalyrics.app;

import android.content.Context;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local-only connectivity probe for Tesla browser testing.
 *
 * It deliberately avoids NanoHTTPD and binds directly to 0.0.0.0 on two
 * unprivileged ports. The root page verifies both plain HTTP and a native
 * WebSocket handshake from the hotspot client to this Android device.
 */
public final class LocalServer {
    private static final int[] PORTS={8765,8080};
    private static final String WS_GUID="258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static volatile LocalServer instance;

    private final AppState state=AppState.get();
    private final ExecutorService workers=Executors.newFixedThreadPool(6);
    private final List<ServerSocket> sockets=new ArrayList<>();
    private final AtomicLong requestCount=new AtomicLong();
    private volatile boolean running=false;
    private volatile String lastClient="none";
    private volatile String lastError="none";

    public LocalServer(Context context){instance=this;}

    public synchronized void start(){
        if(running)return;
        running=true;
        int opened=0;
        for(int port:PORTS){
            try{
                ServerSocket server=new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("0.0.0.0",port),24);
                sockets.add(server);
                opened++;
                Thread t=new Thread(()->acceptLoop(server),"TeslaLyrics-LAN-"+port);
                t.setDaemon(true);
                t.start();
                state.log.add("LAN server listening: 0.0.0.0:"+port);
            }catch(Exception e){
                lastError="port "+port+": "+e.getClass().getSimpleName()+" "+safe(e.getMessage());
                state.log.add("LAN server error: "+lastError);
            }
        }
        if(opened==0)running=false;
        state.setLanUrl(primaryUrl());
    }

    public synchronized void stop(){
        running=false;
        for(ServerSocket s:new ArrayList<>(sockets))try{s.close();}catch(Exception ignored){}
        sockets.clear();
        workers.shutdownNow();
        state.setCarClients(0);
    }

    private void acceptLoop(ServerSocket server){
        while(running&&!server.isClosed()){
            try{
                Socket client=server.accept();
                client.setSoTimeout(5000);
                workers.execute(()->handle(client));
            }catch(Exception e){
                if(running){
                    lastError=e.getClass().getSimpleName()+" "+safe(e.getMessage());
                    state.log.add("LAN accept error: "+lastError);
                }
            }
        }
    }

    private void handle(Socket socket){
        String remote="unknown";
        try(Socket s=socket){
            remote=s.getInetAddress()==null?"unknown":s.getInetAddress().getHostAddress();
            lastClient=remote;
            requestCount.incrementAndGet();
            state.setCarClients(1);

            BufferedReader in=new BufferedReader(new InputStreamReader(s.getInputStream(),StandardCharsets.US_ASCII));
            String request=in.readLine();
            if(request==null||request.isEmpty())return;
            String[] first=request.split(" ");
            String method=first.length>0?first[0]:"";
            String path=first.length>1?first[1].split("\\?",2)[0]:"/";
            String wsKey="";
            boolean upgrade=false;
            String line;
            while((line=in.readLine())!=null&&!line.isEmpty()){
                int p=line.indexOf(':');
                if(p<=0)continue;
                String k=line.substring(0,p).trim().toLowerCase(Locale.ROOT);
                String v=line.substring(p+1).trim();
                if("sec-websocket-key".equals(k))wsKey=v;
                if("upgrade".equals(k)&&"websocket".equalsIgnoreCase(v))upgrade=true;
            }

            if(upgrade&&"/ws".equals(path)&&!wsKey.isEmpty()){
                websocketProbe(s,wsKey,remote);
                return;
            }
            if(!"GET".equals(method)){
                write(s,405,"text/plain; charset=utf-8","GET only");
                return;
            }
            if("/health".equals(path)){
                JSONObject o=new JSONObject();
                o.put("ok",true);
                o.put("client",remote);
                o.put("requests",requestCount.get());
                o.put("lan",NetworkUtils.bestLanAddress());
                write(s,200,"application/json; charset=utf-8",o.toString());
            }else if("/state".equals(path)){
                JSONObject o=state.toJson();
                o.put("localProbe",true);
                o.put("client",remote);
                o.put("requests",requestCount.get());
                write(s,200,"application/json; charset=utf-8",o.toString());
            }else{
                write(s,200,"text/html; charset=utf-8",probePage(remote));
            }
        }catch(Exception e){
            lastError=e.getClass().getSimpleName()+" "+safe(e.getMessage());
            state.log.add("LAN request error from "+remote+": "+lastError);
        }finally{
            state.setCarClients(0);
        }
    }

    private void websocketProbe(Socket socket,String key,String remote)throws Exception{
        MessageDigest sha1=MessageDigest.getInstance("SHA-1");
        String accept=Base64.getEncoder().encodeToString(sha1.digest((key+WS_GUID).getBytes(StandardCharsets.US_ASCII)));
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(),StandardCharsets.US_ASCII));
        out.write("HTTP/1.1 101 Switching Protocols\r\n");
        out.write("Upgrade: websocket\r\n");
        out.write("Connection: Upgrade\r\n");
        out.write("Sec-WebSocket-Accept: "+accept+"\r\n\r\n");
        out.flush();
        sendWsText(socket.getOutputStream(),"TESLA_WS_OK "+remote);
        state.log.add("LAN WebSocket OK: "+remote);
        try{Thread.sleep(700);}catch(InterruptedException e){Thread.currentThread().interrupt();}
    }

    private static void sendWsText(OutputStream out,String text)throws Exception{
        byte[] data=text.getBytes(StandardCharsets.UTF_8);
        out.write(0x81);
        if(data.length<126){
            out.write(data.length);
        }else{
            out.write(126);
            out.write((data.length>>8)&0xff);
            out.write(data.length&0xff);
        }
        out.write(data);
        out.flush();
    }

    private String probePage(String remote){
        String title=escape(state.trackCopy().title);
        String artist=escape(state.trackCopy().artist);
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"+
                "<title>Tesla Lyrics LAN Probe</title><style>body{margin:0;background:#0f0f11;color:#fff;font:20px Arial,sans-serif;padding:32px}h1{font-size:34px;margin:0 0 20px}.ok{color:#7CFF9B}.bad{color:#ff8a8a}pre{white-space:pre-wrap;background:#19191d;padding:18px;border-radius:12px}small{opacity:.65}</style></head><body>"+
                "<h1 class='ok'>TESLA LOCAL LINK OK</h1>"+
                "<p>HTTP：<b class='ok'>成功</b></p><p id='ws'>WebSocket：正在验证…</p>"+
                "<p>Client："+escape(remote)+"</p><p>Current："+title+" — "+artist+"</p>"+
                "<pre id='state'>正在读取 /state …</pre><small>只要本页能在 Tesla 上打开，就已经证明 Tesla → Android 热点宿主机的 TCP/HTTP 直连成立。</small>"+
                "<script>var w=document.getElementById('ws'),passed=false;try{var s=new WebSocket('ws://'+location.host+'/ws');s.onopen=function(){w.innerHTML='WebSocket：<b class=ok>握手成功</b>'};s.onmessage=function(e){passed=true;w.innerHTML='WebSocket：<b class=ok>成功</b> '+e.data};s.onerror=function(){if(!passed)w.innerHTML='WebSocket：<b class=bad>失败</b>'};s.onclose=function(){if(!passed)w.innerHTML+='（连接已关闭）'}}catch(e){w.innerHTML='WebSocket：<b class=bad>创建失败</b>';}function tick(){fetch('/state?'+Date.now(),{cache:'no-store'}).then(function(r){return r.json()}).then(function(j){document.getElementById('state').textContent=JSON.stringify(j,null,2)}).catch(function(e){document.getElementById('state').textContent='state fetch failed: '+e});}tick();setInterval(tick,1500);</script></body></html>";
    }

    private static void write(Socket s,int code,String type,String body)throws Exception{
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        BufferedWriter out=new BufferedWriter(new OutputStreamWriter(s.getOutputStream(),StandardCharsets.US_ASCII));
        out.write("HTTP/1.1 "+code+" "+reason(code)+"\r\n");
        out.write("Content-Type: "+type+"\r\n");
        out.write("Content-Length: "+bytes.length+"\r\n");
        out.write("Cache-Control: no-store\r\n");
        out.write("Access-Control-Allow-Origin: *\r\n");
        out.write("Connection: close\r\n\r\n");
        out.flush();
        s.getOutputStream().write(bytes);
        s.getOutputStream().flush();
    }

    private static String reason(int code){return code==200?"OK":code==405?"Method Not Allowed":"Error";}
    private static String safe(String s){return s==null?"":s;}
    private static String escape(String s){return safe(s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}

    public static String primaryUrl(){return "http://"+NetworkUtils.bestLanAddress()+":8765/";}
    public static String statusReport(){
        LocalServer x=instance;
        if(x==null)return "Local server: not created";
        return "Local server: "+(x.running?"Running":"Stopped")+"\n"+
                "Primary: "+primaryUrl()+"\n"+
                "Fallback: http://"+NetworkUtils.bestLanAddress()+":8080/\n"+
                "Requests: "+x.requestCount.get()+"\n"+
                "Last client: "+x.lastClient+"\n"+
                "Last error: "+x.lastError;
    }
}
