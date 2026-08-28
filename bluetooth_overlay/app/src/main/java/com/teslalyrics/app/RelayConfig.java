package com.teslalyrics.app;

import android.content.Context;

/**
 * Personal-build relay configuration.
 *
 * Keep one deterministic channel on both Android and the Tesla page. The original
 * public-relay prototype used this simple model and proved substantially more
 * reliable than the later local pairing/token migration layer.
 */
public final class RelayConfig {
    private static final String TRANSPORT_TOKEN="hanztesla888";

    private RelayConfig(){}

    public static String token(Context context){return TRANSPORT_TOKEN;}
    public static String stateTopic(Context context){return "tlx-s-"+TRANSPORT_TOKEN;}
    public static String commandTopic(Context context){return "tlx-c-"+TRANSPORT_TOKEN;}
    public static String pairCode(Context context){return "无需配对";}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}
    public static String initUrl(){return "https://hanz316.github.io/l/";}
}
