package com.teslalyrics.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import java.util.concurrent.TimeUnit;

public final class RemoteControlBridge {
    private final MediaSessionMonitor media;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final OkHttpClient client=new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();
    private final String wsUrl;
    private volatile boolean running=false;
    private WebSocket socket;
    private long reconnectDelayMs=1500;
    private String lastMessageId="";

    public RemoteControlBridge(Context context,MediaSessionMonitor media){
        this.media=media;
        wsUrl="wss://ntfy.sh/"+RelayConfig.commandTopic(context)+"/ws?since=latest";
    }

    public synchronized void start(){
        if(running)return;
        running=true;
        reconnectDelayMs=1500;
        connect();
    }

    public synchronized void stop(){
        running=false;
        main.removeCallbacks(reconnect);
        if(socket!=null){try{socket.close(1000,"stop");}catch(Exception ignored){}socket=null;}
    }

    private synchronized void connect(){
        if(!running||socket!=null)return;
        Request req=new Request.Builder().url(wsUrl).build();
        socket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket, Response response){
                synchronized(RemoteControlBridge.this){reconnectDelayMs=1500;}
                AppState.get().log.add("Remote control connected");
            }
            @Override public void onMessage(WebSocket webSocket,String text){handleEnvelope(text);}
            @Override public void onClosed(WebSocket webSocket,int code,String reason){scheduleReconnect(webSocket);}
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){
                AppState.get().log.add("Remote control retry: "+t.getClass().getSimpleName());
                scheduleReconnect(webSocket);
            }
        });
    }

    private void handleEnvelope(String text){
        try{
            JSONObject env=new JSONObject(text);
            if(!"message".equals(env.optString("event")))return;
            String id=env.optString("id","");
            if(!id.isEmpty()&&id.equals(lastMessageId))return;
            long serverTime=env.optLong("time",0);
            long nowSec=System.currentTimeMillis()/1000L;
            if(serverTime>0&&Math.abs(nowSec-serverTime)>120)return;

            String raw=env.optString("message","");
            if(raw.isEmpty())return;
            JSONObject cmd=new JSONObject(raw);
            if(!"control".equals(cmd.optString("kind")))return;
            String action=cmd.optString("action","");
            if(!allowed(action))return;
            if(!id.isEmpty())lastMessageId=id;
            media.handleRemoteCommand(cmd);
        }catch(Exception ignored){}
    }

    private static boolean allowed(String action){
        return "seek".equals(action)||"next".equals(action)||"previous".equals(action)||
                "play".equals(action)||"pause".equals(action)||"toggle".equals(action)||
                "resync".equals(action);
    }

    private void scheduleReconnect(WebSocket failed){
        long delay;
        synchronized(this){
            if(socket==failed)socket=null;
            if(!running)return;
            delay=reconnectDelayMs;
            reconnectDelayMs=Math.min(30000,reconnectDelayMs*2);
        }
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect,delay);
    }

    private final Runnable reconnect=()->{
        synchronized(RemoteControlBridge.this){if(running&&socket==null)connect();}
    };
}
