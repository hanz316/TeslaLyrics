package com.teslalyrics.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public final class WebRtcBridge {
    private static final WebRtcBridge I=new WebRtcBridge();
    public static WebRtcBridge get(){return I;}

    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile MediaSessionMonitor media;
    private volatile WebView web;
    private volatile boolean ready=false,connected=false;
    private volatile String status="未启动";
    private volatile JSONObject latestState=null,latestLyrics=null;

    private WebRtcBridge(){}

    @SuppressLint({"SetJavaScriptEnabled","AddJavascriptInterface"})
    public void configure(Context context){
        if(web!=null)return;
        Context app=context.getApplicationContext();
        main.post(()->{
            if(web!=null)return;
            try{
                WebView w=new WebView(app);
                WebSettings s=w.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(false);
                s.setMediaPlaybackRequiresUserGesture(false);
                s.setCacheMode(WebSettings.LOAD_NO_CACHE);
                w.addJavascriptInterface(new Js(),"TeslaLyricsAndroid");
                w.setWebViewClient(new WebViewClient());
                web=w;
                status="正在连接 WSS/MQTT";
                AppState.get().log.add("Relay page loading");
                w.loadUrl("https://hanz316.github.io/rtcapp/phone.html?v=1");
            }catch(Exception e){
                status="WebView 错误: "+e.getClass().getSimpleName();
                AppState.get().log.add(status);
            }
        });
    }

    public void setMedia(MediaSessionMonitor m){media=m;}
    public static boolean isConnected(){return I.connected;}
    public static String statusText(){return I.status;}

    public synchronized void sendState(JSONObject frame){
        if(frame==null)return;
        try{latestState=new JSONObject(frame.toString());}catch(Exception ignored){return;}
        push(latestState);
    }

    public synchronized void sendLyrics(String key,String provider,String lrc,int score){
        try{
            JSONObject o=new JSONObject();
            o.put("kind","lyrics");
            o.put("key",key==null?"":key);
            o.put("provider",provider==null?"":provider);
            o.put("score",score);
            o.put("lrc",lrc==null?"":lrc);
            latestLyrics=o;
            push(o);
        }catch(Exception ignored){}
    }

    private void push(JSONObject o){
        if(o==null||!ready)return;
        String raw=o.toString();
        main.post(()->{
            WebView w=web;
            if(w==null)return;
            try{w.evaluateJavascript("window.tlxFromAndroid&&window.tlxFromAndroid("+JSONObject.quote(raw)+");",null);}catch(Exception e){AppState.get().log.add("Relay JS send: "+e.getClass().getSimpleName());}
        });
    }

    private synchronized void replay(){
        if(latestState!=null)push(latestState);
        if(latestLyrics!=null)push(latestLyrics);
    }

    public void stop(){
        ready=false;connected=false;status="已停止";
        main.post(()->{
            WebView w=web;web=null;
            if(w!=null)try{w.destroy();}catch(Exception ignored){}
        });
    }

    public static String statusReport(){
        WebRtcBridge x=I;
        return "Relay: "+x.status+"\nWSS/MQTT: "+(x.connected?"Connected":"Disconnected")+"\nTesla URL: https://hanz316.github.io/rtcapp/car.html";
    }

    private final class Js {
        @JavascriptInterface public void onReady(){
            ready=true;status="中继页面已就绪";
            AppState.get().log.add("Relay page ready");
            replay();
        }
        @JavascriptInterface public void onConnected(){
            connected=true;status="车机已连接";
            AppState.get().log.add("Tesla relay connected");
            replay();
        }
        @JavascriptInterface public void onDisconnected(){
            connected=false;status="等待车机连接";
            AppState.get().log.add("Tesla relay disconnected");
        }
        @JavascriptInterface public void onStatus(String s){
            status=s==null?"":s;
            AppState.get().log.add("Relay: "+status);
        }
        @JavascriptInterface public void onLog(String s){
            if(s!=null&&!s.isEmpty())AppState.get().log.add("Relay "+s);
        }
        @JavascriptInterface public void onCommand(String raw){
            MediaSessionMonitor m=media;
            if(m==null||raw==null)return;
            try{
                JSONObject o=new JSONObject(raw);
                if("control".equals(o.optString("kind")))m.handleRemoteCommand(o);
            }catch(Exception e){AppState.get().log.add("Relay command JSON error");}
        }
    }
}
