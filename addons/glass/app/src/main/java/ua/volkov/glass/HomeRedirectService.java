package ua.volkov.glass;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Glass's own Home button: whenever Google TV's home screen comes up, the TV goes to Glass instead.
 * It watches only the Google TV launcher's window changes (res/xml/home_redirect.xml) and reads
 * nothing else. Switched on only while Glass is the chosen home screen (Home.choose).
 */
public final class HomeRedirectService extends AccessibilityService {
    static final String STOCK = "com.google.android.apps.tv.launcherx";
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

    /** Standby, as the power button does. */
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
        startActivity(new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    @Override
    public void onInterrupt() {
    }
}
