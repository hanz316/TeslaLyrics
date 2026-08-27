package com.teslalyrics.app;

import android.service.notification.NotificationListenerService;

/**
 * Enables access to Android active MediaSessions. Notification contents are
 * not stored or forwarded by Tesla Lyrics.
 */
public final class MediaAccessService extends NotificationListenerService {
    @Override public void onListenerConnected() {
        super.onListenerConnected();
        AppState.get().log.add("Media access granted");
    }
}
