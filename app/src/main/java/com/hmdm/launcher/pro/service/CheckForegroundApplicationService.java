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

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.ui.MainActivity;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Service that monitors the foreground app and blocks non-whitelisted apps
 * by bringing the launcher back to the foreground.
 */
public class CheckForegroundApplicationService extends Service {

    private static final String TAG = "ForegroundAppChecker";
    private static final int NOTIFICATION_ID = 113;
    private static final String CHANNEL_ID = "ForegroundAppChecker";
    private static final long CHECK_INTERVAL_MS = 500;

    private Handler handler;
    private boolean permissiveModeEnabled = false;
    private long permissiveModeEndTime = 0;

    private final Runnable checkRunnable = new Runnable() {
        @Override
        public void run() {
            checkForegroundApp();
            handler.postDelayed(this, CHECK_INTERVAL_MS);
        }
    };

    private final BroadcastReceiver permissiveModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Const.ACTION_PERMISSIVE_MODE.equals(intent.getAction())) {
                // Temporary permissive mode (from admin panel or push message)
                permissiveModeEnabled = true;
                permissiveModeEndTime = System.currentTimeMillis() + Const.PERMISSIVE_MODE_TIME;
                Log.i(TAG, "Permissive mode enabled temporarily");
                RemoteLogger.log(context, Const.LOG_INFO, "Foreground app check: permissive mode enabled");
            }
            // Note: We ignore ACTION_TOGGLE_PERMISSIVE because it also triggers on kiosk mode.
            // Instead, we check config.isPermissive() directly in checkForegroundApp().
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(Looper.getMainLooper());

        // Register for temporary permissive mode broadcasts (from admin panel or push)
        IntentFilter filter = new IntentFilter();
        filter.addAction(Const.ACTION_PERMISSIVE_MODE);
        LocalBroadcastManager.getInstance(this).registerReceiver(permissiveModeReceiver, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();

        // Check initial permissive state from config
        ServerConfig config = SettingsHelper.getInstance(this).getConfig();
        if (config != null && config.isPermissive()) {
            permissiveModeEnabled = true;
            permissiveModeEndTime = Long.MAX_VALUE;
        }

        // Start checking
        handler.removeCallbacks(checkRunnable);
        handler.post(checkRunnable);

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(checkRunnable);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(permissiveModeReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @SuppressLint("WrongConstant")
    private void startAsForeground() {
        NotificationCompat.Builder builder;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "App Whitelist Enforcement",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            notificationManager.createNotificationChannel(channel);
            builder = new NotificationCompat.Builder(this, CHANNEL_ID);
        } else {
            builder = new NotificationCompat.Builder(this);
        }

        Notification notification = builder
                .setContentTitle(ProUtils.getAppName(this))
                .setTicker(ProUtils.getAppName(this))
                .setContentText(getString(R.string.mqtt_service_text))
                .setSmallIcon(R.drawable.ic_mqtt_service)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void checkForegroundApp() {
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

        // Also check config-level permissive mode
        if (config.isPermissive()) {
            return;
        }

        // Get foreground app package
        String foregroundPkg = getForegroundPackage();
        if (foregroundPkg == null || foregroundPkg.isEmpty()) {
            return;
        }

        // Build whitelist from config
        Set<String> allowedPackages = buildAllowedPackages(config);

        // Check if foreground app is allowed
        if (!allowedPackages.contains(foregroundPkg)) {
            Log.i(TAG, "Blocking non-whitelisted app: " + foregroundPkg);
            RemoteLogger.log(this, Const.LOG_INFO, "Blocked app: " + foregroundPkg);
            bringLauncherToForeground();
        }
    }

    private Set<String> buildAllowedPackages(ServerConfig config) {
        Set<String> allowed = new HashSet<>();

        // Always allow the launcher itself
        allowed.add(getPackageName());

        // Always allow system UI
        allowed.add(Const.SYSTEM_UI_PACKAGE_NAME);

        // Always allow all keyboard/input method apps
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                List<InputMethodInfo> inputMethods = imm.getInputMethodList();
                for (InputMethodInfo imi : inputMethods) {
                    allowed.add(imi.getPackageName());
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get input methods", e);
        }

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

    private String getForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) {
            return null;
        }

        long now = System.currentTimeMillis();
        // Query for last 10 seconds to get recent usage
        List<UsageStats> stats = usm.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                now - 10000,
                now
        );

        if (stats == null || stats.isEmpty()) {
            return null;
        }

        // Find the app with the most recent lastTimeUsed
        SortedMap<Long, UsageStats> sortedStats = new TreeMap<>();
        for (UsageStats usageStats : stats) {
            sortedStats.put(usageStats.getLastTimeUsed(), usageStats);
        }

        if (sortedStats.isEmpty()) {
            return null;
        }

        return sortedStats.get(sortedStats.lastKey()).getPackageName();
    }

    private void bringLauncherToForeground() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
    }
}
