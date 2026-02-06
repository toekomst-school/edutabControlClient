/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hmdm.launcher.pro.service;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.ui.MainActivity;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AccessibilityService for MDM functionality.
 * Monitors foreground app changes and blocks non-whitelisted apps.
 * Also provides ability to lock screen on Android 9+ without requiring force-lock policy.
 */
public class CheckForegroundAppAccessibilityService extends AccessibilityService {

    private static final String TAG = "ForegroundAppAccessibility";

    private static CheckForegroundAppAccessibilityService instance;
    private boolean permissiveModeEnabled = false;
    private long permissiveModeEndTime = 0;
    private String lastBlockedPackage = null;
    private long lastBlockTime = 0;
    private static final long BLOCK_COOLDOWN_MS = 1000; // Avoid rapid repeated blocks

    private final BroadcastReceiver permissiveModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Const.ACTION_PERMISSIVE_MODE.equals(intent.getAction())) {
                // Temporary permissive mode (from admin panel or push message)
                permissiveModeEnabled = true;
                permissiveModeEndTime = System.currentTimeMillis() + Const.PERMISSIVE_MODE_TIME;
                Log.i(TAG, "Permissive mode enabled temporarily");
                RemoteLogger.log(context, Const.LOG_INFO, "Accessibility service: permissive mode enabled");
            }
        }
    };

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility service connected");

        // Check initial permissive state from config
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config != null && config.isPermissive()) {
            permissiveModeEnabled = true;
            permissiveModeEndTime = Long.MAX_VALUE;
        }

        // Register for temporary permissive mode broadcasts
        IntentFilter filter = new IntentFilter();
        filter.addAction(Const.ACTION_PERMISSIVE_MODE);
        LocalBroadcastManager.getInstance(this).registerReceiver(permissiveModeReceiver, filter);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence packageNameCs = event.getPackageName();
        if (packageNameCs == null) {
            return;
        }

        String packageName = packageNameCs.toString();
        checkAndBlockIfNeeded(packageName);
    }

    private void checkAndBlockIfNeeded(String packageName) {
        // Check if permissive mode has expired
        if (permissiveModeEnabled && permissiveModeEndTime != Long.MAX_VALUE) {
            if (System.currentTimeMillis() > permissiveModeEndTime) {
                permissiveModeEnabled = false;
                permissiveModeEndTime = 0;
                Log.i(TAG, "Permissive mode expired");
            }
        }

        // Skip check if permissive mode is active
        if (permissiveModeEnabled) {
            return;
        }

        // Check if config exists and has applications
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config == null || config.getApplications() == null) {
            return;
        }

        // Check config-level permissive mode
        if (config.isPermissive()) {
            return;
        }

        // Build whitelist from config
        Set<String> allowedPackages = buildAllowedPackages(config);

        // Check if the package is allowed
        if (!allowedPackages.contains(packageName)) {
            // Avoid rapid repeated blocks of the same package
            long now = System.currentTimeMillis();
            if (packageName.equals(lastBlockedPackage) && (now - lastBlockTime) < BLOCK_COOLDOWN_MS) {
                return;
            }

            Log.i(TAG, "Blocking non-whitelisted app: " + packageName);
            RemoteLogger.log(this, Const.LOG_INFO, "Blocked app: " + packageName);

            lastBlockedPackage = packageName;
            lastBlockTime = now;

            bringLauncherToForeground();
        }
    }

    private Set<String> buildAllowedPackages(ServerConfig config) {
        Set<String> allowed = new HashSet<>();

        // Always allow the launcher itself
        allowed.add(getPackageName());

        // Always allow system UI
        allowed.add(Const.SYSTEM_UI_PACKAGE_NAME);

        // Add all apps from config
        List<Application> apps = config.getApplications();
        if (apps != null) {
            for (Application app : apps) {
                if (app.getPkg() != null && !app.getPkg().isEmpty()) {
                    // Don't add apps that are marked for removal
                    if (!app.isRemove()) {
                        allowed.add(app.getPkg());
                    }
                }
            }
        }

        return allowed;
    }

    private void bringLauncherToForeground() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }

    @Override
    public void onInterrupt() {
        // Not used
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        try {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(permissiveModeReceiver);
        } catch (Exception e) {
            // Ignore if not registered
        }
        Log.i(TAG, "Accessibility service destroyed");
    }

    /**
     * Lock the screen using AccessibilityService.
     * Works on Android 9 (API 28) and higher.
     * @return true if lock was successful, false otherwise
     */
    public static boolean lockScreen() {
        if (instance == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        }
        return false;
    }

    /**
     * Check if the accessibility service is available and running.
     */
    public static boolean isAvailable() {
        return instance != null;
    }
}
