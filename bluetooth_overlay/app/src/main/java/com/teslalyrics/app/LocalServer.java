package com.teslalyrics.app;

/**
 * Compatibility stub. The original hotspot HTTP server is intentionally removed from
 * the current HTTPS relay architecture. Keeping this tiny class replaces the legacy
 * NanoHTTPD implementation in the reconstructed source package without retaining the
 * vulnerable server dependency.
 */
public final class LocalServer {
    public LocalServer(android.content.Context context){}
    public void start(){}
    public void stop(){}
}
