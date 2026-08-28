package com.teslalyrics.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Six-digit user-facing pairing with a transport token compatible with the Tesla page. */
public final class RelayConfig {
    private static final String PREF="teslalyrics_relay_v1";
    private static final String KEY="pair_token";
    private static final SecureRandom RNG=new SecureRandom();
    private static volatile String cached="";

    private RelayConfig(){}

    public static String token(Context context){
        String c=cached;
        if(valid(c))return c;
        synchronized(RelayConfig.class){
            if(valid(cached))return cached;
            Context app=context.getApplicationContext();
            SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            String v=p.getString(KEY,"");
            if(!valid(v)){
                int n=RNG.nextInt(1_000_000);
                v=String.format(java.util.Locale.ROOT,"%06d",n);
                p.edit().putString(KEY,v).apply();
            }
            cached=v;
            return v;
        }
    }

    /**
     * The existing Tesla page historically expects a 12-character topic suffix.
     * Keep the user-facing code at six digits, and deterministically expand it
     * exactly the same way as /l/ does in the browser.
     */
    private static String transportToken(Context context){
        String code=token(context);
        StringBuilder half=new StringBuilder(6);
        for(int i=0;i<code.length();i++){
            char c=code.charAt(i);
            half.append(c=='0'?'a':(c=='1'?'b':c));
        }
        return half.toString()+half;
    }

    public static String stateTopic(Context context){return "tlx-s-"+transportToken(context);}
    public static String commandTopic(Context context){return "tlx-c-"+transportToken(context);}
    public static String pairCode(Context context){return token(context);}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}

    private static boolean valid(String s){return s!=null&&s.matches("\\d{6}");}
}
