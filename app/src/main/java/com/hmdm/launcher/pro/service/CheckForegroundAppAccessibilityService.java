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
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

/**
 * AccessibilityService for MDM functionality.
 * Provides ability to lock screen on Android 9+ without requiring force-lock policy.
 */
public class CheckForegroundAppAccessibilityService extends AccessibilityService {

    private static CheckForegroundAppAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Not used for now
    }

    @Override
    public void onInterrupt() {
        // Not used
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
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
