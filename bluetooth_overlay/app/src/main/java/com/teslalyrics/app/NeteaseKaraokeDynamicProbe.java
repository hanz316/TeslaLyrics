package com.teslalyrics.app;

import android.content.Context;
import android.media.AudioManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Read-only dynamic probe for NetEase Cloud Music's vendor karaoke parameters.
 * It never writes AudioManager parameters. The user can move the NetEase
 * "随心唱" vocal slider while this probe records only parameter changes.
 */
public final class NeteaseKaraokeDynamicProbe {
    private static final NeteaseKaraokeDynamicProbe I=new NeteaseKaraokeDynamicProbe();
    public static NeteaseKaraokeDynamicProbe get(){return I;}

    private static final String[] KEYS={
            "audio_karaoke_ktvmode",
            "audio_karaoke_volume",
            "audio_karaoke_EQ",
            "audio_karaoke_Reverb",
            "audio_karaoke_enable",
            "audio_karaoke_support"
    };

    private final AtomicBoolean running=new AtomicBoolean(false);
    private final Map<String,String> last=new LinkedHashMap<>();
    private ScheduledExecutorService exec;
    private AudioManager audio;
    private volatile long samples=0;
    private volatile long changes=0;
    private volatile String lastError="";

    private NeteaseKaraokeDynamicProbe(){}

    public synchronized void start(Context context){
        stop();
        if(context==null)return;
        audio=(AudioManager)context.getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
        if(audio==null){
            lastError="AudioManager unavailable";
            AppState.get().log.add("NCM DYN error: "+lastError);
            return;
        }
        last.clear();samples=0;changes=0;lastError="";
        running.set(true);
        AppState.get().log.add("NCM DYN start: move 随心唱 vocal slider 0% -> 50% -> 100% -> 0%");
        exec=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ncm-karaoke-dyn");t.setDaemon(true);return t;});
        exec.scheduleWithFixedDelay(this::poll,0,300,TimeUnit.MILLISECONDS);
    }

    public synchronized void stop(){
        running.set(false);
        if(exec!=null){exec.shutdownNow();exec=null;}
        if(!last.isEmpty()||samples>0)AppState.get().log.add("NCM DYN stop: samples="+samples+" changes="+changes);
    }

    public boolean isRunning(){return running.get();}

    public String report(){
        StringBuilder b=new StringBuilder();
        b.append("Dynamic karaoke probe: ").append(running.get()?"Running":"Stopped")
                .append("\nSamples: ").append(samples).append("  Changes: ").append(changes);
        synchronized(this){
            for(String k:KEYS)b.append("\n").append(k).append(" = ").append(last.containsKey(k)?printable(last.get(k)):"<not read>");
        }
        if(!lastError.isEmpty())b.append("\nLast error: ").append(lastError);
        return b.toString();
    }

    private void poll(){
        if(!running.get()||audio==null)return;
        samples++;
        for(String key:KEYS){
            String value;
            try{
                value=normalize(audio.getParameters(key));
            }catch(Throwable t){
                value="<error:"+t.getClass().getSimpleName()+">";
                lastError=t.getClass().getSimpleName();
            }
            String old;
            boolean first;
            synchronized(this){
                first=!last.containsKey(key);
                old=last.put(key,value);
            }
            if(first){
                AppState.get().log.add("NCM DYN init "+key+" = "+printable(value));
            }else if(!safeEq(old,value)){
                changes++;
                AppState.get().log.add("NCM DYN CHANGE "+key+": "+printable(old)+" -> "+printable(value));
            }
        }
    }

    private static String normalize(String s){return s==null?"":s.trim();}
    private static boolean safeEq(String a,String b){return a==null?b==null:a.equals(b);}
    private static String printable(String s){return s==null||s.isEmpty()?"<empty>":s;}
}
