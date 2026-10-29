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
    /** Set by "Вернуться на основной экран"; cleared when our Home shows again. */
    static volatile boolean allowStock;
    private static volatile HomeRedirectService running;

    @Override
    protected void onServiceConnected() {
        running = this;
    }

    @Override
    public void onDestroy() {
        if (running == this) running = null;
        super.onDestroy();
    }

    /** Standby, as the power button does (GLOBAL_ACTION_LOCK_SCREEN puts the TV to sleep). */
    static boolean sleep() {
        HomeRedirectService s = running;
        return s != null && android.os.Build.VERSION.SDK_INT >= 28 && s.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!STOCK.contentEquals(e.getPackageName() == null ? "" : e.getPackageName())) return;
        CharSequence cls = e.getClassName();
        if (cls == null || !cls.toString().endsWith(".home.HomeActivity")) return;
        if (allowStock) return;
        Intent i = homeIntent(this);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    static Intent homeIntent(android.content.Context ctx) {
        return new Intent(ctx, HomeActivity.class);
    }

    @Override
    public void onInterrupt() {
    }
}
