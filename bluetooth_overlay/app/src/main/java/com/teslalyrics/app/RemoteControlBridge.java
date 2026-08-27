package com.teslalyrics.app;

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
    private static final String WS_URL="wss://ntfy.sh/tlx-b3598dd35e2ab18ef1e2dc84-cmd/ws";
    private final MediaSessionMonitor media;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final OkHttpClient client=new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .build();
    private volatile boolean running=false;
    private WebSocket socket;

    public RemoteControlBridge(MediaSessionMonitor media){this.media=media;}

    public synchronized void start(){
        if(running)return;
        running=true;
        connect();
    }

    public synchronized void stop(){
        running=false;
        main.removeCallbacksAndMessages(null);
        if(socket!=null){try{socket.close(1000,"stop");}catch(Exception ignored){}socket=null;}
    }

    private synchronized void connect(){
        if(!running)return;
        Request req=new Request.Builder().url(WS_URL).build();
        socket=client.newWebSocket(req,new WebSocketListener(){
            @Override public void onOpen(WebSocket webSocket, Response response){
                AppState.get().log.add("Remote control connected");
            }
            @Override public void onMessage(WebSocket webSocket,String text){handleEnvelope(text);}
            @Override public void onClosed(WebSocket webSocket,int code,String reason){scheduleReconnect();}
            @Override public void onFailure(WebSocket webSocket,Throwable t,Response response){
                AppState.get().log.add("Remote control retry: "+t.getClass().getSimpleName());
                scheduleReconnect();
            }
        });
    }

    private void handleEnvelope(String text){
        try{
            JSONObject env=new JSONObject(text);
            if(!"message".equals(env.optString("event")))return;
            String raw=env.optString("message","");
            if(raw.isEmpty())return;
            JSONObject cmd=new JSONObject(raw);
            if(!"control".equals(cmd.optString("kind")))return;
            media.handleRemoteCommand(cmd);
        }catch(Exception ignored){}
    }

    private void scheduleReconnect(){
        synchronized(this){socket=null;}
        if(!running)return;
        main.removeCallbacks(reconnect);
        main.postDelayed(reconnect,1800);
    }

    private final Runnable reconnect=()->{synchronized(RemoteControlBridge.this){if(running&&socket==null)connect();}};
}
