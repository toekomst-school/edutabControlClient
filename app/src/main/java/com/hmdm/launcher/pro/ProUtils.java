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

package com.hmdm.launcher.pro;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.view.View;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.ArrayList;
import java.util.List;

import java.util.Calendar;

/**
 * These functions are available in Pro-version only
 * In a free version, the class contains stubs
 */
public class ProUtils {

    public static boolean isPro() {
        return false;
    }

    public static boolean kioskModeRequired(Context context) {
        try {
            ServerConfig config = SettingsHelper.getInstance(context).getConfig();
            if (config != null) {
                return config.isKioskMode();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static void initCrashlytics(Context context) {
        // Stub
    }

    public static void sendExceptionToCrashlytics(Throwable e) {
        // Stub
    }

    // Start the service checking if the foreground app is allowed to the user (by usage statistics)
    public static boolean checkAccessibilityService(Context context) {
        // Stub
        return true;
    }

    // Pro-version
    public static boolean checkUsageStatistics(Context context) {
        // Stub
        return true;
    }

    // Add a transparent view on top of the status bar which prevents user interaction with the status bar
    public static View preventStatusBarExpansion(Activity activity) {
        // Stub
        return null;
    }

    // Add a transparent view on top of a swipeable area at the right (opens app list on Samsung tablets)
    public static View preventApplicationsList(Activity activity) {
        // Stub
        return null;
    }

    public static View createKioskUnlockButton(Activity activity) {
        // Stub
        return null;
    }

    public static boolean isKioskAppInstalled(Context context) {
        // Stub
        return false;
    }

    public static boolean isKioskModeRunning(Context context) {
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            return am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE;
        } catch (Exception e) {
            return false;
        }
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        // Stub
        return null;
    }

    // Start COSU kiosk mode (lock task mode)
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

            // Whitelist all configured apps for lock task mode
            if (dpm.isDeviceOwnerApp(activity.getPackageName())) {
                List<String> packageList = new ArrayList<>();
                packageList.add(activity.getPackageName()); // Always include launcher

                // Add all apps from configuration
                ServerConfig config = SettingsHelper.getInstance(activity).getConfig();
                if (config != null && config.getApplications() != null) {
                    for (Application app : config.getApplications()) {
                        String pkg = app.getPkg();
                        if (pkg != null && !pkg.isEmpty() && !packageList.contains(pkg)) {
                            packageList.add(pkg);
                        }
                    }
                }

                // Add specific kiosk app if provided
                if (kioskApp != null && !kioskApp.isEmpty() && !packageList.contains(kioskApp)) {
                    packageList.add(kioskApp);
                }

                String[] packages = packageList.toArray(new String[0]);
                dpm.setLockTaskPackages(adminComponent, packages);
                RemoteLogger.log(activity, Const.LOG_DEBUG, "Lock task packages: " + packageList.size() + " apps whitelisted");
            }

            // Check if already in lock task mode
            if (isKioskModeRunning(activity)) {
                RemoteLogger.log(activity, Const.LOG_DEBUG, "Already in kiosk mode, whitelist updated");
                return true;
            }

            // Start lock task mode (only if not already running)
            if (dpm.isLockTaskPermitted(activity.getPackageName())) {
                activity.startLockTask();
                RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk mode started");
                return true;
            }
        } catch (Exception e) {
            RemoteLogger.log(activity, Const.LOG_WARN, "Failed to start kiosk mode: " + e.getMessage());
            e.printStackTrace();
        }
        return false;
    }

    // Set/update kiosk mode options (lock task features)
    public static void updateKioskOptions(Activity activity) {
        // Stub - could set lock task features here
    }

    // Update app list in the kiosk mode
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        try {
            DevicePolicyManager dpm = (DevicePolicyManager) activity.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = LegacyUtils.getAdminComponentName(activity);

            if (dpm.isDeviceOwnerApp(activity.getPackageName())) {
                List<String> packageList = new ArrayList<>();
                packageList.add(activity.getPackageName());

                // Add all apps from configuration
                ServerConfig config = SettingsHelper.getInstance(activity).getConfig();
                if (config != null && config.getApplications() != null) {
                    for (Application app : config.getApplications()) {
                        String pkg = app.getPkg();
                        if (pkg != null && !pkg.isEmpty() && !packageList.contains(pkg)) {
                            packageList.add(pkg);
                        }
                    }
                }

                if (kioskApp != null && !kioskApp.isEmpty() && !packageList.contains(kioskApp)) {
                    packageList.add(kioskApp);
                }

                String[] packages = packageList.toArray(new String[0]);
                dpm.setLockTaskPackages(adminComponent, packages);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void unlockKiosk(Activity activity) {
        try {
            ActivityManager am = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
            if (am.getLockTaskModeState() != ActivityManager.LOCK_TASK_MODE_NONE) {
                activity.stopLockTask();
                RemoteLogger.log(activity, Const.LOG_INFO, "Kiosk mode stopped");
            }
        } catch (Exception e) {
            RemoteLogger.log(activity, Const.LOG_WARN, "Failed to stop kiosk mode: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    // Rate limiting for location updates
    private static final long NORMAL_LOCATION_INTERVAL_MS = 15 * 60 * 1000; // 15 minutes for normal mode
    private static final long EMERGENCY_LOCATION_INTERVAL_MS = 30 * 1000; // 30 seconds for emergency mode
    private static long lastLocationLogTime = 0;
    private static Location lastLoggedLocation = null;
    private static volatile boolean emergencyMode = false;

    public static void setEmergencyMode(boolean enabled) {
        emergencyMode = enabled;
    }

    public static boolean isEmergencyMode() {
        return emergencyMode;
    }

    public static void processLocation(Context context, Location location, String provider) {
        if (location == null) {
            return;
        }

        // Use different intervals for normal vs emergency mode
        long intervalMs = emergencyMode ? EMERGENCY_LOCATION_INTERVAL_MS : NORMAL_LOCATION_INTERVAL_MS;

        // Rate limit: only log if enough time has passed or location changed significantly
        long now = System.currentTimeMillis();
        if (lastLoggedLocation != null && (now - lastLocationLogTime) < intervalMs) {
            // In emergency mode, allow if moved more than 10m; normal mode 100m
            float minDistance = emergencyMode ? 10 : 100;
            float distance = location.distanceTo(lastLoggedLocation);
            if (distance < minDistance) {
                return;
            }
        }

        lastLocationLogTime = now;
        lastLoggedLocation = location;

        // Send location as a log message with parseable format
        // Format: LOCATION|lat|lon|accuracy|provider|timestamp
        String locationLog = String.format("LOCATION|%.6f|%.6f|%.1f|%s|%d",
                location.getLatitude(),
                location.getLongitude(),
                location.getAccuracy(),
                provider,
                location.getTime());

        RemoteLogger.log(context, Const.LOG_INFO, locationLog);
    }

    public static String getAppName(Context context) {
        return context.getString(R.string.app_name);
    }

    public static String getCopyright(Context context) {
        return "(c) " + Calendar.getInstance().get(Calendar.YEAR) + " " + context.getString(R.string.vendor);
    }
}
