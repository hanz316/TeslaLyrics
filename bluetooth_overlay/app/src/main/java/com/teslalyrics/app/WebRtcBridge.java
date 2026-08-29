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
    private volatile String status="not started";
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
                status="loading WebRTC page";
                AppState.get().log.add("WebRTC bridge loading");
                w.loadUrl("https://hanz316.github.io/rtcapp/phone.html?v=1");
            }catch(Exception e){
                status="WebView error: "+e.getClass().getSimpleName();
                AppState.get().log.add(status);
            }
        });
    }

    public void setMedia(MediaSessionMonitor m){media=m;}

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
            try{w.evaluateJavascript("window.tlxFromAndroid&&window.tlxFromAndroid("+JSONObject.quote(raw)+");",null);}catch(Exception e){AppState.get().log.add("WebRTC JS send: "+e.getClass().getSimpleName());}
        });
    }

    private synchronized void replay(){
        if(latestState!=null)push(latestState);
        if(latestLyrics!=null)push(latestLyrics);
    }

    public void stop(){
        ready=false;connected=false;status="stopped";
        main.post(()->{
            WebView w=web;web=null;
            if(w!=null)try{w.destroy();}catch(Exception ignored){}
        });
    }

    public static String statusReport(){
        WebRtcBridge x=I;
        return "WebRTC: "+x.status+"\nDataChannel: "+(x.connected?"Connected":"Disconnected")+"\nTesla URL: https://hanz316.github.io/rtcapp/car.html";
    }

    private final class Js {
        @JavascriptInterface public void onReady(){
            ready=true;status="page ready";
            AppState.get().log.add("WebRTC page ready");
            replay();
        }
        @JavascriptInterface public void onConnected(){
            connected=true;status="Tesla connected";
            AppState.get().log.add("WebRTC Tesla connected");
            replay();
        }
        @JavascriptInterface public void onDisconnected(){
            connected=false;status="Tesla disconnected";
            AppState.get().log.add("WebRTC Tesla disconnected");
        }
        @JavascriptInterface public void onStatus(String s){
            status=s==null?"":s;
            AppState.get().log.add("WebRTC: "+status);
        }
        @JavascriptInterface public void onLog(String s){
            if(s!=null&&!s.isEmpty())AppState.get().log.add("RTC "+s);
        }
        @JavascriptInterface public void onCommand(String raw){
            MediaSessionMonitor m=media;
            if(m==null||raw==null)return;
            try{
                JSONObject o=new JSONObject(raw);
                if("control".equals(o.optString("kind")))m.handleRemoteCommand(o);
            }catch(Exception e){AppState.get().log.add("RTC command JSON error");}
        }
    }
}
