package com.teslalyrics.app;

import android.content.Context;

/** Personal build: one permanent relay suffix; no pairing or token migration. */
public final class RelayConfig {
    private static final String TOKEN="hanztesla888";
    private RelayConfig(){}

    public static String token(Context context){return TOKEN;}
    public static String stateTopic(Context context){return "tlx-s-"+TOKEN;}
    public static String commandTopic(Context context){return "tlx-c-"+TOKEN;}
    public static String pairCode(Context context){return "无需配对";}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}
    public static String initUrl(){return "https://hanz316.github.io/l/";}
}
