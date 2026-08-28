package com.teslalyrics.app;

import android.content.Context;

/** Personal build: restore the original relay topic that was proven stable in-car. */
public final class RelayConfig {
    private static final String STATE_TOPIC="tlx-b3598dd35e2ab18ef1e2dc84";
    private static final String COMMAND_TOPIC="tlx-c-b3598dd35e2ab18ef1e2dc84";

    private RelayConfig(){}

    public static String token(Context context){return "fixed";}
    public static String stateTopic(Context context){return STATE_TOPIC;}
    public static String commandTopic(Context context){return COMMAND_TOPIC;}
    public static String pairCode(Context context){return "无需配对";}
    public static String carUrl(){return "https://hanz316.github.io/lyrics/";}
    public static String initUrl(){return "https://hanz316.github.io/l/";}
}
