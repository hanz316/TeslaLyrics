package com.teslalyrics.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/**
 * Per-install relay pairing for the personal prototype.
 *
 * The driver only sees a six-digit numeric code. Internally we deterministically
 * expand it to the existing 12-character ntfy suffix so the relay topics remain
 * compatible with the hardened web client.
 */
public final class RelayConfig {
    private static final String PREF="teslalyrics_relay_v2";
    private static final String KEY="pair_code_6";
    private static final SecureRandom RNG=new SecureRandom();
    private static volatile String cachedCode="";

    private RelayConfig(){}

    public static String pairCode(Context context){
        String c=cachedCode;
        if(validCode(c))return c;
        synchronized(RelayConfig.class){
            if(validCode(cachedCode))return cachedCode;
            Context app=context.getApplicationContext();
            SharedPreferences p=app.getSharedPreferences(PREF,Context.MODE_PRIVATE);
            String v=p.getString(KEY,"");
            if(!validCode(v)){
                v=String.valueOf(100000+RNG.nextInt(900000));
                p.edit().putString(KEY,v).apply();
            }
            cachedCode=v;
            return v;
        }
    }

    /** 6 visible digits -> 6 safe ntfy chars -> repeat to 12 chars. */
    public static String token(Context context){
        String code=pairCode(context);
        StringBuilder mapped=new StringBuilder(6);
        for(int i=0;i<code.length();i++){
            char c=code.charAt(i);
            if(c=='0')mapped.append('a');
            else if(c=='1')mapped.append('b');
            else mapped.append(c);
        }
        return mapped.toString()+mapped;
    }

    public static String stateTopic(Context context){return "tlx-s-"+token(context);}
    public static String commandTopic(Context context){return "tlx-c-"+token(context);}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}
    public static String easyPairUrl(){return "https://hanz316.github.io/l/";}

    private static boolean validCode(String s){return s!=null&&s.matches("[0-9]{6}");}
}
