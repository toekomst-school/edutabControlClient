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
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.view.View;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.RemoteLogger;

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
        // Stub
        return false;
    }

    public static Intent getKioskAppIntent(String kioskApp, Activity activity) {
        // Stub
        return null;
    }

    // Start COSU kiosk mode
    public static boolean startCosuKioskMode(String kioskApp, Activity activity, boolean enableSettings) {
        // Stub
        return false;
    }

    // Set/update kiosk mode options (lock tack features)
    public static void updateKioskOptions(Activity activity) {
        // Stub
    }

    // Update app list in the kiosk mode
    public static void updateKioskAllowedApps(String kioskApp, Activity activity, boolean enableSettings) {
        // Stub
    }

    public static void unlockKiosk(Activity activity) {
        // Stub
    }

    public static void processConfig(Context context, ServerConfig config) {
        // Stub
    }

    // Rate limiting for location updates
    private static final long MIN_LOCATION_LOG_INTERVAL_MS = 60000; // 1 minute minimum between logs
    private static long lastLocationLogTime = 0;
    private static Location lastLoggedLocation = null;

    public static void processLocation(Context context, Location location, String provider) {
        if (location == null) {
            return;
        }

        // Rate limit: only log if enough time has passed or location changed significantly
        long now = System.currentTimeMillis();
        if (lastLoggedLocation != null && (now - lastLocationLogTime) < MIN_LOCATION_LOG_INTERVAL_MS) {
            float distance = location.distanceTo(lastLoggedLocation);
            if (distance < 50) { // Less than 50 meters movement
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
