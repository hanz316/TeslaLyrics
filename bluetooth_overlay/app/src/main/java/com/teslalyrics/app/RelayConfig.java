package com.teslalyrics.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.security.SecureRandom;

/** Per-install relay pairing. The token is generated locally and is never committed to GitHub. */
public final class RelayConfig {
    private static final String PREF="teslalyrics_relay_v1";
    private static final String KEY="pair_token";
    private static final char[] ALPHABET="abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
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
                StringBuilder b=new StringBuilder(12);
                for(int i=0;i<12;i++)b.append(ALPHABET[RNG.nextInt(ALPHABET.length)]);
                v=b.toString();
                p.edit().putString(KEY,v).apply();
            }
            cached=v;
            return v;
        }
    }

    public static String stateTopic(Context context){return "tlx-s-"+token(context);}
    public static String commandTopic(Context context){return "tlx-c-"+token(context);}
    public static String pairCode(Context context){return token(context);}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}

    private static boolean valid(String s){return s!=null&&s.matches("[a-z2-9]{12}");}
}
