/*
 * EduTab Lock Screen
 * Shows a black screen with lock icon in true kiosk mode (lock task).
 * Only dismissable via unlock command from teacher OR local PIN entry.
 * Persists across reboots.
 */

package com.hmdm.launcher.ui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.helper.CryptoHelper;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;

public class LockScreenActivity extends Activity {

    public static final String ACTION_UNLOCK = "eu.edutab.control.UNLOCK";
    private static final String PREFS_NAME = "edutab_lock_prefs";
    private static final String KEY_LOCK_STATE = "is_locked";
    private static final String DEFAULT_PASSWORD = "12345678";

    private static LockScreenActivity instance;

    private BroadcastReceiver unlockReceiver;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.i(TAG, "=== LockScreenActivity.onCreate() STARTED ===");
        instance = this;

        // Save lock state for persistence across reboots
        Log.i(TAG, "Setting lock state to true...");
        setLockState(this, true);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = LegacyUtils.getAdminComponentName(this);
        Log.i(TAG, "DevicePolicyManager initialized");

        // Make it full screen, show over lock screen
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

        // Create black background with lock icon
        FrameLayout layout = new FrameLayout(this);
        layout.setBackgroundColor(Color.BLACK);

        // Lock icon emoji - tap to enter PIN
        TextView lockIcon = new TextView(this);
        lockIcon.setText("🔒");
        lockIcon.setTextSize(80);
        lockIcon.setTextColor(Color.parseColor("#333333")); // Dark gray, subtle
        lockIcon.setClickable(true);
        lockIcon.setFocusable(true);
        lockIcon.setPadding(100, 100, 100, 100); // Larger touch area
        lockIcon.setOnClickListener(v -> showPinDialog());

        // Also allow tapping anywhere on screen
        layout.setOnClickListener(v -> showPinDialog());

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER;
        layout.addView(lockIcon, params);

        // Hint text below lock icon
        TextView hintText = new TextView(this);
        hintText.setText("Tap lock to enter PIN");
        hintText.setTextSize(14);
        hintText.setTextColor(Color.parseColor("#222222")); // Very subtle

        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        );
        hintParams.gravity = Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM;
        hintParams.bottomMargin = 100;
        layout.addView(hintText, hintParams);

        setContentView(layout);

        // Enter immersive mode
        enterImmersiveMode();

        // Start lock task mode (true kiosk)
        startKioskMode();

        // Listen for unlock command via LocalBroadcastManager (synchronous delivery)
        unlockReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UNLOCK.equals(intent.getAction())) {
                    dismiss();
                }
            }
        };
        IntentFilter unlockFilter = new IntentFilter(ACTION_UNLOCK);
        LocalBroadcastManager.getInstance(this).registerReceiver(unlockReceiver, unlockFilter);
    }

    private void showPinDialog() {
        Log.d("LockScreen", "showPinDialog() called - tap detected");

        // Temporarily exit immersive mode for dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Teacher PIN");

        // Create input field
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        input.setHint("Enter PIN");

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(50, 20, 50, 20);
        input.setLayoutParams(lp);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(input);
        builder.setView(container);

        builder.setPositiveButton("Unlock", (dialog, which) -> {
            String enteredPin = input.getText().toString();
            if (validatePin(enteredPin)) {
                dismiss();
            } else {
                // Wrong PIN - re-enter immersive mode
                enterImmersiveMode();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.cancel();
            enterImmersiveMode();
        });

        builder.setOnCancelListener(dialog -> enterImmersiveMode());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private boolean validatePin(String enteredPin) {
        if (enteredPin == null || enteredPin.isEmpty()) {
            return false;
        }

        // Get master password from config (same as admin panel uses)
        String masterPassword = CryptoHelper.getMD5String(DEFAULT_PASSWORD);

        try {
            ServerConfig config = SettingsHelper.getInstance(this).getConfig();
            if (config != null && config.getPassword() != null && !config.getPassword().isEmpty()) {
                masterPassword = config.getPassword();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Compare MD5 hash of entered PIN with stored password
        String enteredHash = CryptoHelper.getMD5String(enteredPin);
        return enteredHash.equals(masterPassword);
    }

    private void startKioskMode() {
        try {
            // Set this activity as a lock task package if we're device owner
            if (dpm.isDeviceOwnerApp(getPackageName())) {
                dpm.setLockTaskPackages(adminComponent, new String[]{getPackageName()});

                // Disable all lock task features including power menu (API 28+)
                // LOCK_TASK_FEATURE_NONE (0) = no system UI, no power menu, no notifications
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
                    Log.d("LockScreen", "Lock task features disabled (no power menu)");
                }
            }
            // Start lock task mode
            startLockTask();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void stopKioskMode() {
        try {
            // Restore default lock task features before stopping (API 28+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && dpm.isDeviceOwnerApp(getPackageName())) {
                // Restore all features for normal operation
                dpm.setLockTaskFeatures(adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO |
                    DevicePolicyManager.LOCK_TASK_FEATURE_HOME |
                    DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS |
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS |
                    DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD);
                Log.d("LockScreen", "Lock task features restored");
            }

            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                stopLockTask();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void enterImmersiveMode() {
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        );
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            enterImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        // Block back button
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Block all hardware keys
        return true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (unlockReceiver != null) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(unlockReceiver);
        }
        if (instance == this) {
            instance = null;
        }
    }

    /**
     * Dismiss the lock screen. Called via broadcast from unlock command or PIN entry.
     */
    private void dismiss() {
        instance = null;
        setLockState(this, false);
        stopKioskMode();
        finish();
    }

    /**
     * Static method to unlock from PushNotificationProcessor
     */
    public static void unlock(Context context) {
        // Clear persistent lock state
        setLockState(context, false);
        // Use LocalBroadcastManager for synchronous delivery
        Intent intent = new Intent(ACTION_UNLOCK);
        LocalBroadcastManager.getInstance(context).sendBroadcast(intent);
    }

    /**
     * Check if lock screen is currently active (runtime)
     */
    public static boolean isLocked() {
        return instance != null;
    }

    private static final String TAG = "EduTab.LockScreen";

    /**
     * Get device protected storage context for Direct Boot support.
     * Device protected storage is available before the user unlocks the device.
     */
    private static Context getDeviceProtectedContext(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Context dpc = context.createDeviceProtectedStorageContext();
            Log.d(TAG, "Using device protected context: " + dpc);
            return dpc;
        }
        Log.d(TAG, "SDK < N, using regular context");
        return context;
    }

    /**
     * Check if lock state is persisted (for boot restore).
     * Uses device protected storage so it's accessible before device unlock.
     */
    public static boolean isLockStatePersisted(Context context) {
        Log.d(TAG, "=== isLockStatePersisted() ===");
        Log.d(TAG, "Context: " + context);
        Log.d(TAG, "SDK_INT: " + Build.VERSION.SDK_INT);

        try {
            Context storageContext = getDeviceProtectedContext(context);
            Log.d(TAG, "Storage context: " + storageContext);

            SharedPreferences prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            Log.d(TAG, "SharedPreferences obtained, file: " + PREFS_NAME);

            // Log all keys in prefs
            java.util.Map<String, ?> allPrefs = prefs.getAll();
            Log.d(TAG, "All prefs keys: " + allPrefs.keySet());
            Log.d(TAG, "All prefs values: " + allPrefs);

            boolean locked = prefs.getBoolean(KEY_LOCK_STATE, false);
            Log.d(TAG, "Lock state value for key '" + KEY_LOCK_STATE + "': " + locked);
            return locked;
        } catch (Exception e) {
            Log.e(TAG, "Error in isLockStatePersisted: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * Set persistent lock state using SharedPreferences.
     * Uses device protected storage so it's accessible before device unlock.
     */
    private static void setLockState(Context context, boolean locked) {
        Log.d(TAG, "=== setLockState(" + locked + ") ===");
        try {
            Context storageContext = getDeviceProtectedContext(context);
            SharedPreferences prefs = storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

            // Use commit() instead of apply() and check result
            boolean success = prefs.edit().putBoolean(KEY_LOCK_STATE, locked).commit();
            Log.d(TAG, "Lock state set to: " + locked + ", commit success: " + success);

            // Verify it was written
            boolean verify = prefs.getBoolean(KEY_LOCK_STATE, !locked);
            Log.d(TAG, "Verification read: " + verify);

            if (verify != locked) {
                Log.e(TAG, "WARNING: Lock state verification failed! Expected " + locked + " but got " + verify);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in setLockState: " + e.getMessage(), e);
        }
    }

    /**
     * Launch lock screen (called on boot if lock was persisted)
     */
    public static void launchIfPersisted(Context context) {
        Log.d(TAG, "=== launchIfPersisted() ===");
        boolean statePersisted = isLockStatePersisted(context);
        boolean isCurrentlyLocked = isLocked();
        Log.d(TAG, "statePersisted: " + statePersisted + ", isCurrentlyLocked: " + isCurrentlyLocked);

        if (statePersisted && !isCurrentlyLocked) {
            Log.d(TAG, "Launching LockScreenActivity...");
            try {
                Intent intent = new Intent(context, LockScreenActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.d(TAG, "LockScreenActivity launch intent sent");
            } catch (Exception e) {
                Log.e(TAG, "Error launching LockScreenActivity: " + e.getMessage(), e);
            }
        } else {
            Log.d(TAG, "Not launching: statePersisted=" + statePersisted + ", isCurrentlyLocked=" + isCurrentlyLocked);
        }
    }
}
