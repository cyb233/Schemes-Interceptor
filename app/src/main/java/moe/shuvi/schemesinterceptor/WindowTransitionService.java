package moe.shuvi.schemesinterceptor;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;

/** Observes window packages only. It cannot read the Intent that caused a switch. */
public final class WindowTransitionService extends AccessibilityService {
    private String previousPackage;
    private long lastEventTime;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isKeyguardLocked()) {
            previousPackage = null;
            return;
        }
        if (event.getPackageName() == null || event.getClassName() == null) return;
        String nextPackage = event.getPackageName().toString();
        if ("android".equals(nextPackage) || "com.android.systemui".equals(nextPackage)) {
            previousPackage = null;
            return;
        }
        // Ignore panes, small popups and repeated events within the same app.
        // Full-screen events are still observations, not proof of an app launch.
        if (!event.isFullScreen() || event.getContentChangeTypes() != 0
                || event.getEventTime() <= lastEventTime) return;
        lastEventTime = event.getEventTime();
        if (previousPackage != null && !previousPackage.equals(nextPackage)) {
            JumpRecordStore.get(this).recordWindowSwitch(previousPackage, nextPackage,
                    event.getClassName().toString());
        }
        previousPackage = nextPackage;
    }

    @Override
    public void onInterrupt() {
        previousPackage = null;
    }
}
