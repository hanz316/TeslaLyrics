package com.teslalyrics.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

public final class WebRtcBridge {
    private static final WebRtcBridge I=new WebRtcBridge();
    public static WebRtcBridge get(){return I;}

    // v9 uses the currently documented TBMQ WSS/443 endpoint as primary and
    // independently operated HiveMQ WSS as fallback. Page hosting still has its own fallback.
    private static final String PRIMARY_PAGE="https://hanz316.github.io/rtcapp/phone.html?v=9";
    private static final String BACKUP_PAGE="https://cdn.jsdelivr.net/gh/hanz316/hanz316.github.io@main/rtcapp/phone.html?v=9";
    private static final long PAGE_FALLBACK_MS=1800L;

    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile MediaSessionMonitor media;
    private volatile WebView web;
    private volatile boolean ready=false,connected=false,usingBackupPage=false;
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
                w.setWebViewClient(new WebViewClient(){
                    private void fallback(WebView view,String why){
                        if(usingBackupPage)return;
                        usingBackupPage=true;
                        ready=false;
                        connected=false;
                        status="主网页不可达，快速切换备用网页";
                        AppState.get().log.add("Relay page fallback: "+why);
                        try{view.stopLoading();view.loadUrl(BACKUP_PAGE);}catch(Exception ignored){}
                    }
                    @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                        if(request!=null&&request.isForMainFrame())fallback(view,"network");
                    }
                    @Override public void onReceivedHttpError(WebView view,WebResourceRequest request,WebResourceResponse response){
                        if(request!=null&&request.isForMainFrame()&&response!=null&&response.getStatusCode()>=400){
                            fallback(view,"HTTP "+response.getStatusCode());
                        }
                    }
                });
                web=w;
                usingBackupPage=false;
                status="正在连接主网页";
                AppState.get().log.add("Relay page loading: primary v9");
                w.loadUrl(PRIMARY_PAGE);
                main.postDelayed(()->{
                    if(web==w&&!ready&&!usingBackupPage){
                        usingBackupPage=true;
                        connected=false;
                        status="主网页超时，快速切换备用网页";
                        AppState.get().log.add("Relay page fallback: timeout");
                        try{w.stopLoading();w.loadUrl(BACKUP_PAGE);}catch(Exception ignored){}
                    }
                },PAGE_FALLBACK_MS);
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
            o.put("sentAtMs",System.currentTimeMillis());
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
        return "Relay: "+x.status+"\nWSS/MQTT v9: TBMQ 443 + HiveMQ 8884 双线路并行\n状态: "+(x.connected?"Connected":"Disconnected")+"\nTesla: https://hanz316.github.io/rtcapp/car.html";
    }

    private final class Js {
        @JavascriptInterface public void onReady(){
            ready=true;
            status=usingBackupPage?"备用网页已就绪":"主网页已就绪";
            AppState.get().log.add("Relay page ready: "+(usingBackupPage?"backup":"primary"));
            replay();
        }
        @JavascriptInterface public void onConnected(){
            connected=true;status="车机已连接";
            AppState.get().log.add("Tesla relay connected v9");
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
