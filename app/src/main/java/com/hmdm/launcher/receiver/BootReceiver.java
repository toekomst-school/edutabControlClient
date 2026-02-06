package com.hmdm.launcher.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.Initializer;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.ui.LockScreenActivity;
import com.hmdm.launcher.util.RemoteLogger;

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "EduTab.BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : "null";
        Log.i(TAG, "=== BootReceiver.onReceive() ===");
        Log.i(TAG, "Action: " + action);
        Log.i(Const.LOG_TAG, "Got the BOOT_RECEIVER broadcast, action: " + action);

        // Log Direct Boot state
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            UserManager um = (UserManager) context.getSystemService(Context.USER_SERVICE);
            boolean isUserUnlocked = um != null && um.isUserUnlocked();
            Log.i(TAG, "isUserUnlocked: " + isUserUnlocked);
        }

        // CRITICAL: Check lock state FIRST, before any other initialization
        // This uses device protected storage which is available during Direct Boot
        Log.i(TAG, "Checking lock state (device protected storage)...");
        boolean lockStatePersisted = false;
        try {
            lockStatePersisted = LockScreenActivity.isLockStatePersisted(context);
            Log.i(TAG, "Lock state persisted: " + lockStatePersisted);
        } catch (Exception e) {
            Log.e(TAG, "Error checking lock state: " + e.getMessage(), e);
        }

        // If locked, launch lock screen IMMEDIATELY - don't wait for initialization
        if (lockStatePersisted) {
            Log.i(TAG, ">>> LOCK STATE IS TRUE - Launching lock screen immediately <<<");
            try {
                boolean isLocked = LockScreenActivity.isLocked();
                Log.i(TAG, "LockScreenActivity.isLocked(): " + isLocked);
                if (!isLocked) {
                    Log.i(TAG, "Starting LockScreenActivity...");
                    Intent lockIntent = new Intent(context, LockScreenActivity.class);
                    lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(lockIntent);
                    Log.i(TAG, "LockScreenActivity started successfully");
                } else {
                    Log.i(TAG, "Lock screen already active, skipping launch");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error launching lock screen: " + e.getMessage(), e);
            }
        } else {
            Log.i(TAG, "Lock state is FALSE - not launching lock screen");
        }

        // Continue with normal boot receiver logic
        RemoteLogger.log(context, Const.LOG_DEBUG, "Got the BOOT_RECEIVER broadcast");

        SettingsHelper settingsHelper = null;
        try {
            settingsHelper = SettingsHelper.getInstance(context.getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "Error getting SettingsHelper: " + e.getMessage(), e);
            // During Direct Boot, SettingsHelper might fail - that's OK, we already handled lock
            return;
        }

        if (settingsHelper == null || !settingsHelper.isBaseUrlSet()) {
            Log.i(TAG, "Base URL not set, skipping normal init");
            return;
        }

        long lastAppStartTime = settingsHelper.getAppStartTime();
        long bootTime = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
        Log.d(TAG, "appStartTime=" + lastAppStartTime + ", bootTime=" + bootTime);
        if (lastAppStartTime < bootTime) {
            Log.i(TAG, "Headwind MDM wasn't started since boot, start initializing services");
        } else {
            Log.i(TAG, "Headwind MDM is already started, ignoring rest of BootReceiver");
            return;
        }

        Initializer.init(context, () -> {
            Log.i(TAG, "Initializer callback running");
            Initializer.startServicesAndLoadConfig(context);

            SettingsHelper.getInstance(context).setMainActivityRunning(false);

            // Double-check lock state after initialization (belt and suspenders)
            if (LockScreenActivity.isLockStatePersisted(context)) {
                Log.i(TAG, "Lock screen was active before reboot, ensuring it's running");
                RemoteLogger.log(context, Const.LOG_INFO, "Restoring lock screen after reboot");
                LockScreenActivity.launchIfPersisted(context);
            } else if (ProUtils.kioskModeRequired(context)) {
                Log.i(TAG, "Kiosk mode required, forcing Headwind MDM to run in the foreground");
                Intent homeIntent = new Intent(Intent.ACTION_MAIN);
                homeIntent.addCategory(Intent.CATEGORY_HOME);
                homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(homeIntent);
            }
        });

        Log.i(TAG, "=== BootReceiver.onReceive() END ===");
    }
}
