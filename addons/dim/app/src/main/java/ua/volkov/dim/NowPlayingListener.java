package ua.volkov.dim;

import android.service.notification.NotificationListenerService;

/**
 * Empty on purpose: Android only lets an app read other apps' media sessions (what is playing in
 * YouTube Music or Apple Music) while it holds notification access. Granted over ADB with
 * `cmd notification allow_listener ua.volkov.dim/ua.volkov.dim.NowPlayingListener`.
 * Notifications themselves are never read.
 */
public final class NowPlayingListener extends NotificationListenerService {
}
