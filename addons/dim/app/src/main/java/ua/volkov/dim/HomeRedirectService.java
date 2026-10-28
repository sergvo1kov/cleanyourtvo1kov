package ua.volkov.dim;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Google TV stays enabled for its side panel with Google Home (long-press Home), but whenever its own
 * home screen comes up, this sends the TV to our Home instead. It watches only the Google TV launcher's
 * window changes (see res/xml/home_redirect.xml) and reads nothing else.
 */
public final class HomeRedirectService extends AccessibilityService {
    static final String STOCK = "com.google.android.apps.tv.launcherx";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!STOCK.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().endsWith(".home.HomeActivity")) return;
        Intent i = new Intent(this, HomeActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    @Override
    public void onInterrupt() {
    }
}
