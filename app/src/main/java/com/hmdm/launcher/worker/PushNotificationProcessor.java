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

package com.hmdm.launcher.worker;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hmdm.launcher.BuildConfig;
import com.hmdm.launcher.Const;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.DownloadTable;
import com.hmdm.launcher.helper.ConfigUpdater;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.service.EmergencyService;
import com.hmdm.launcher.json.Application;
import com.hmdm.launcher.json.Download;
import com.hmdm.launcher.json.PushMessage;
import com.hmdm.launcher.json.ServerConfig;
import com.hmdm.launcher.util.InstallUtils;
import com.hmdm.launcher.util.LegacyUtils;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.SystemUtils;
import com.hmdm.launcher.util.Utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executors;

public class PushNotificationProcessor {
    public static void process(PushMessage message, Context context) {
        RemoteLogger.log(context, Const.LOG_INFO, "Got Push Message, type " + message.getMessageType());
        if (message.getMessageType().equals(PushMessage.TYPE_CONFIG_UPDATED)) {
            // Update local configuration
            ConfigUpdater.notifyConfigUpdate(context);
            // The configUpdated should be broadcasted after the configuration update is completed
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_RUN_APP)) {
            // Run application
            runApplication(context, message.getPayloadJSON());
            // Do not broadcast this message to other apps
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_BROADCAST)) {
            // Send broadcast
            sendBroadcast(context, message.getPayloadJSON());
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_UNINSTALL_APP)) {
            // Uninstall application
            AsyncTask.execute(() -> uninstallApplication(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_DELETE_FILE)) {
            // Delete file
            AsyncTask.execute(() -> deleteFile(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_DELETE_DIR)) {
            // Delete directory recursively
            AsyncTask.execute(() -> deleteDir(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_PURGE_DIR)) {
            // Purge directory (delete all files recursively)
            AsyncTask.execute(() -> purgeDir(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_PERMISSIVE_MODE)) {
            // Turn on permissive mode
            LocalBroadcastManager.getInstance(context).
                    sendBroadcast(new Intent(Const.ACTION_PERMISSIVE_MODE));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_RUN_COMMAND)) {
            // Run a command-line script
            AsyncTask.execute(() -> runCommand(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_REBOOT)) {
            // Reboot a device
            AsyncTask.execute(() -> reboot(context));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_EXIT_KIOSK)) {
            // Temporarily exit kiosk mode
            LocalBroadcastManager.getInstance(context).
                sendBroadcast(new Intent(Const.ACTION_EXIT_KIOSK));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_ENTER_KIOSK)) {
            // Re-enter kiosk mode
            LocalBroadcastManager.getInstance(context).
                sendBroadcast(new Intent(Const.ACTION_ENTER_KIOSK));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_ADMIN_PANEL)) {
            LocalBroadcastManager.getInstance(context).
                    sendBroadcast(new Intent(Const.ACTION_ADMIN_PANEL));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_CLEAR_DOWNLOADS)) {
            // Clear download history
            AsyncTask.execute(() -> clearDownloads(context));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_INTENT)) {
            // Run a system intent (like settings or ACTION_VIEW)
            AsyncTask.execute(() -> callIntent(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_GRANT_PERMISSIONS)) {
            // Grant permissions to apps
            AsyncTask.execute(() -> grantPermissions(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_CLEAR_APP_DATA)) {
            // Clear application data
            AsyncTask.execute(() -> clearAppData(context, message.getPayloadJSON()));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_OPEN_URL)) {
            // Payload is just the URL string
            String url = message.getPayload();
            if (url != null && !url.isEmpty()) {
                openUrlInBrowser(context, url);
            }
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_SET_VOLUME)) {
            // Payload is volume level 0-15, convert to 0-100%
            try {
                int volumeLevel = Integer.parseInt(message.getPayload());
                // Clamp to valid range
                volumeLevel = Math.max(0, Math.min(15, volumeLevel));
                int volumePercent = (volumeLevel * 100) / 15;

                // Temporarily unlock volume if locked (device owner required)
                ServerConfig config = SettingsHelper.getInstance(context).getConfig();
                boolean wasLocked = config != null && Boolean.TRUE.equals(config.getLockVolume());
                if (wasLocked) {
                    Utils.lockVolume(false, context);
                }

                boolean success = Utils.setVolume(volumePercent, context);

                // Re-lock volume if it was locked
                if (wasLocked) {
                    Utils.lockVolume(true, context);
                }

                if (!success) {
                    // AudioManager method failed, try shell command for media volume
                    String command = "media volume --stream 3 --set " + volumeLevel;
                    String result = SystemUtils.executeShellCommand(command, true);
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Set volume via shell: " + volumeLevel + " Result: " + result);
                } else {
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Set volume to " + volumePercent + "%");
                }
            } catch (NumberFormatException e) {
                RemoteLogger.log(context, Const.LOG_WARN, "Invalid volume value: " + message.getPayload());
            }
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_SET_BRIGHTNESS)) {
            // Payload is brightness level 0-255
            try {
                int brightness = Integer.parseInt(message.getPayload());
                // Clamp brightness to valid range
                brightness = Math.max(0, Math.min(255, brightness));

                // Try device owner method first (sets auto=false for manual brightness)
                boolean success = Utils.setBrightnessPolicy(false, brightness, context);

                if (!success) {
                    // Device owner method failed, try shell command
                    // First disable auto-brightness, then set manual brightness
                    SystemUtils.executeShellCommand("settings put system screen_brightness_mode 0", true);
                    String command = "settings put system screen_brightness " + brightness;
                    String result = SystemUtils.executeShellCommand(command, true);
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Set brightness via shell: " + brightness + " Result: " + result);
                } else {
                    RemoteLogger.log(context, Const.LOG_DEBUG, "Set brightness to " + brightness);
                }
            } catch (NumberFormatException e) {
                RemoteLogger.log(context, Const.LOG_WARN, "Invalid brightness value: " + message.getPayload());
            }
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_PING_LOCATION)) {
            // Request immediate location ping
            AsyncTask.execute(() -> EmergencyService.sendSingleLocationPing(context));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_EMERGENCY_MODE)) {
            // Payload: "on" or "off", optionally with interval like "on:30000"
            String payload = message.getPayload();
            if (payload != null) {
                Intent serviceIntent = new Intent(context, EmergencyService.class);
                if (payload.startsWith("on")) {
                    serviceIntent.setAction(EmergencyService.ACTION_START);
                    // Parse optional interval (e.g., "on:30000" for 30 second intervals)
                    if (payload.contains(":")) {
                        try {
                            int interval = Integer.parseInt(payload.split(":")[1]);
                            serviceIntent.putExtra(EmergencyService.EXTRA_INTERVAL, interval);
                        } catch (Exception e) {
                            // Use default interval
                        }
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent);
                    } else {
                        context.startService(serviceIntent);
                    }
                } else if (payload.equals("off")) {
                    serviceIntent.setAction(EmergencyService.ACTION_STOP);
                    context.startService(serviceIntent);
                }
            }
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_LOCK)) {
            // Lock device screen
            AsyncTask.execute(() -> lockDevice(context));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_UNLOCK)) {
            // Unlock and wake device screen
            AsyncTask.execute(() -> unlockDevice(context));
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_ATTENTION)) {
            // Flash screen to get attention
            showAttention(context);
            return;
        } else if (message.getMessageType().equals(PushMessage.TYPE_MESSAGE)) {
            // Show message overlay
            String messageText = message.getPayload();
            if (messageText != null && !messageText.isEmpty()) {
                showMessage(context, messageText);
            }
            return;
        }

        // Send broadcast to all plugins
        Intent intent = new Intent(Const.INTENT_PUSH_NOTIFICATION_PREFIX + message.getMessageType());
        JSONObject jsonObject = message.getPayloadJSON();
        if (jsonObject != null) {
            intent.putExtra(Const.INTENT_PUSH_NOTIFICATION_EXTRA, jsonObject.toString());
        }
        context.sendBroadcast(intent);
    }

    private static void runApplication(Context context, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            String pkg = payload.getString("pkg");
            String action = payload.optString("action", null);
            JSONObject extras = payload.optJSONObject("extra");
            String data = payload.optString("data", null);
            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(pkg);
            if (launchIntent != null) {
                if (action != null) {
                    launchIntent.setAction(action);
                }
                if (data != null) {
                    try {
                        launchIntent.setData(Uri.parse(data));
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                if (extras != null) {
                    Iterator<String> keys = extras.keys();
                    String key;
                    while (keys.hasNext()) {
                        key = keys.next();
                        Object value = extras.get(key);
                        if (value instanceof String) {
                            launchIntent.putExtra(key, (String) value);
                        } else if (value instanceof Integer) {
                            launchIntent.putExtra(key, ((Integer) value).intValue());
                        } else if (value instanceof Float) {
                            launchIntent.putExtra(key, ((Float) value).floatValue());
                        } else if (value instanceof Boolean) {
                            launchIntent.putExtra(key, ((Boolean) value).booleanValue());
                        }
                    }
                }

                // These magic flags are found in the source code of the default Android launcher
                // These flags preserve the app activity stack (otherwise a launch activity appears at the top which is not correct)
                launchIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(launchIntent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void sendBroadcast(Context context, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            String pkg = payload.optString("pkg", null);
            String action = payload.optString("action", null);
            JSONObject extras = payload.optJSONObject("extra");
            String data = payload.optString("data", null);
            Intent intent = new Intent();
            if (pkg != null) {
                intent.setPackage(pkg);
            }
            if (action != null) {
                intent.setAction(action);
            }
            if (data != null) {
                try {
                    intent.setData(Uri.parse(data));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                String key;
                while (keys.hasNext()) {
                    key = keys.next();
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        intent.putExtra(key, (String) value);
                    } else if (value instanceof Integer) {
                        intent.putExtra(key, ((Integer) value).intValue());
                    } else if (value instanceof Float) {
                        intent.putExtra(key, ((Float) value).floatValue());
                    } else if (value instanceof Boolean) {
                        intent.putExtra(key, ((Boolean) value).booleanValue());
                    }
                }
            }
            context.sendBroadcast(intent);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void uninstallApplication(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: no package specified");
            return;
        }
        if (!Utils.isDeviceOwner(context)) {
            // Require device owner for non-interactive uninstallation
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: no device owner");
            return;
        }

        try {
            String pkg = payload.getString("pkg");
            InstallUtils.silentUninstallApplication(context, pkg);
            RemoteLogger.log(context, Const.LOG_INFO, "Uninstalled application: " + pkg);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Uninstall request failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void deleteFile(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "File delete failed: no path specified");
            return;
        }

        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            file.delete();
            RemoteLogger.log(context, Const.LOG_INFO, "Deleted file: " + path);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "File delete failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] childFiles = fileOrDirectory.listFiles();
            for (File child : childFiles) {
                deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    private static void deleteDir(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory delete failed: no path specified");
            return;
        }

        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            deleteRecursive(file);
            RemoteLogger.log(context, Const.LOG_INFO, "Deleted directory: " + path);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory delete failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void purgeDir(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: no path specified");
            return;
        }

        try {
            String path = payload.getString("path");
            File file = new File(Environment.getExternalStorageDirectory(), path);
            if (!file.isDirectory()) {
                RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: not a directory: " + path);
                return;
            }
            String recursive = payload.optString("recursive");
            File[] childFiles = file.listFiles();
            for (File child : childFiles) {
                if (recursive == null || !recursive.equals("1")) {
                    if (!child.isDirectory()) {
                        child.delete();
                    }
                } else {
                    deleteRecursive(child);
                }
            }
            RemoteLogger.log(context, Const.LOG_INFO, "Purged directory: " + path);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Directory purge failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void runCommand(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Command failed: no command specified");
            return;
        }

        try {
            String command = payload.getString("command");
            Log.d(Const.LOG_TAG, "Executing a command: " + command);
            String result = SystemUtils.executeShellCommand(command, true);
            String msg = "Executed a command: " + command;
            if (!result.equals("")) {
                if (result.length() > 200) {
                    result = result.substring(0, 200) + "...";
                }
                msg += " Result: " + result;
            }
            RemoteLogger.log(context, Const.LOG_DEBUG, msg);

        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Command failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void reboot(Context context) {
        RemoteLogger.log(context, Const.LOG_WARN, "Rebooting by a Push message");
        if (Utils.checkAdminMode(context)) {
            if (!Utils.reboot(context)) {
                RemoteLogger.log(context, Const.LOG_WARN, "Reboot failed");
            }
        } else {
            RemoteLogger.log(context, Const.LOG_WARN, "Reboot failed: no permissions");
        }
    }

    private static void clearDownloads(Context context) {
        RemoteLogger.log(context, Const.LOG_WARN, "Clear download history by a Push message");
        DatabaseHelper dbHelper = DatabaseHelper.instance(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        List<Download> downloads = DownloadTable.selectAll(db);
        for (Download d: downloads) {
            File file = new File(d.getPath());
            try {
                file.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        DownloadTable.deleteAll(db);
    }

    private static void callIntent(Context context, JSONObject payload) {
        if (payload == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "Calling intent failed: no parameters specified");
            return;
        }

        try {
            String action = payload.getString("action");
            Log.d(Const.LOG_TAG, "Calling intent: " + action);
            JSONObject extras = payload.optJSONObject("extra");
            String data = payload.optString("data", null);
            String category = payload.optString("category", null);
            Intent i = new Intent(action);
            if (data != null) {
                try {
                    i.setData(Uri.parse(data));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            if (category != null) {
                i.addCategory(category);
            }
            if (extras != null) {
                Iterator<String> keys = extras.keys();
                String key;
                while (keys.hasNext()) {
                    key = keys.next();
                    Object value = extras.get(key);
                    if (value instanceof String) {
                        i.putExtra(key, (String) value);
                    } else if (value instanceof Integer) {
                        i.putExtra(key, ((Integer) value).intValue());
                    } else if (value instanceof Float) {
                        i.putExtra(key, ((Float) value).floatValue());
                    } else if (value instanceof Boolean) {
                        i.putExtra(key, ((Boolean) value).booleanValue());
                    }
                }
            }
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Calling intent failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void grantPermissions(Context context, JSONObject payload) {
        if (!Utils.isDeviceOwner(context) && !BuildConfig.SYSTEM_PRIVILEGES) {
            RemoteLogger.log(context, Const.LOG_WARN, "Can't auto grant permissions: no device owner");
        }

        ServerConfig config = SettingsHelper.getInstance(context).getConfig();
        List<String> apps = null;

        if (payload != null) {
            apps = new LinkedList<>();
            String pkg;
            JSONArray pkgs = payload.optJSONArray("pkg");
            if (pkgs != null) {
                for (int i = 0; i < pkgs.length(); i++) {
                    pkg = pkgs.optString(i);
                    if (pkg != null) {
                        apps.add(pkg);
                    }
                }
            } else {
                pkg = payload.optString("pkg");
                if (pkg != null) {
                    apps.add(pkg);
                }
            }
        } else {
            // By default, grant permissions to all packagee having an URL
            apps = new LinkedList<>();
            List<Application> configApps = config.getApplications();
            for (Application app: configApps) {
                if (Application.TYPE_APP.equals(app.getType()) &&
                    app.getUrl() != null && app.getPkg() != null) {
                    apps.add(app.getPkg());
                }
            }
        }

        for (String app: apps) {
            Utils.autoGrantRequestedPermissions(context, app,
                    config.getAppPermissions(), false);
        }
    }

    private static void clearAppData(Context context, JSONObject payload) {
        if (payload == null) {
            return;
        }
        try {
            String pkg = payload.getString("pkg");
            RemoteLogger.log(context, Const.LOG_INFO, "Clearing app data for " + pkg);
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.clearApplicationUserData(
                        adminComponentName,
                        pkg,
                        Executors.newSingleThreadExecutor(),
                        (packageName, succeeded) -> {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                    "App data for " + packageName + (succeeded ? " " : " not ") + "cleared");
                        }
                );
            } else {
                throw new Exception("Unsupported in SDK " + Build.VERSION.SDK_INT);
            }

        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_ERROR, "Failed to clear app data: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Open a URL in a web browser app.
     * Tries to find a proper browser app instead of showing all apps that can handle URLs.
     */
    private static void openUrlInBrowser(Context context, String url) {
        Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        browserIntent.addCategory(Intent.CATEGORY_BROWSABLE);

        // Common browser package names to try
        String[] browserPackages = {
            "org.mozilla.firefox",           // Firefox
            "com.android.chrome",            // Chrome
            "com.brave.browser",             // Brave
            "org.chromium.chrome",           // Chromium
            "com.opera.browser",             // Opera
            "com.microsoft.emmx",            // Edge
            "com.duckduckgo.mobile.android", // DuckDuckGo
            "org.mozilla.focus",             // Firefox Focus
            "com.sec.android.app.sbrowser",  // Samsung Internet
        };

        // Try to find an installed browser from our list
        PackageManager pm = context.getPackageManager();
        for (String pkg : browserPackages) {
            try {
                pm.getPackageInfo(pkg, 0);
                // Package exists, use it
                browserIntent.setPackage(pkg);
                context.startActivity(browserIntent);
                RemoteLogger.log(context, Const.LOG_DEBUG, "Opened URL in browser: " + pkg);
                return;
            } catch (PackageManager.NameNotFoundException e) {
                // Browser not installed, try next
            }
        }

        // No preferred browser found, try to find any browser
        browserIntent.setPackage(null);
        List<ResolveInfo> activities = pm.queryIntentActivities(browserIntent, PackageManager.MATCH_DEFAULT_ONLY);

        // Look for an app that is actually a browser (not just handles URLs)
        for (ResolveInfo info : activities) {
            String pkgName = info.activityInfo.packageName;
            // Skip apps that are typically not browsers but handle URLs
            if (pkgName.contains("youtube") || pkgName.contains("facebook") ||
                pkgName.contains("twitter") || pkgName.contains("instagram") ||
                pkgName.contains("whatsapp") || pkgName.contains("telegram") ||
                pkgName.contains("tiktok") || pkgName.contains("reddit")) {
                continue;
            }
            // Found a potential browser, use it
            browserIntent.setPackage(pkgName);
            try {
                context.startActivity(browserIntent);
                RemoteLogger.log(context, Const.LOG_DEBUG, "Opened URL in: " + pkgName);
                return;
            } catch (Exception e) {
                // Try next
            }
        }

        // Last resort: just open with any app that can handle it
        browserIntent.setPackage(null);
        try {
            context.startActivity(browserIntent);
            RemoteLogger.log(context, Const.LOG_DEBUG, "Opened URL with default handler");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Failed to open URL: " + url + " - " + e.getMessage());
        }
    }

    /**
     * Lock the device screen by showing a black lock screen activity in kiosk mode.
     * The device will go to sleep naturally based on screen timeout.
     */
    private static void lockDevice(Context context) {
        try {
            Intent lockIntent = new Intent(context, com.hmdm.launcher.ui.LockScreenActivity.class);
            lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(lockIntent);
            RemoteLogger.log(context, Const.LOG_INFO, "Lock screen displayed");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Lock device failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Unlock the device - wakes screen, dismisses lock screen, keyguard AND exits kiosk mode
     */
    private static void unlockDevice(Context context) {
        try {
            // Wake the screen
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "hmdm:unlock"
            );
            wl.acquire(5000); // Hold for 5 seconds

            // Dismiss system keyguard/lock screen if device owner
            if (Utils.isDeviceOwner(context)) {
                DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ComponentName adminComponentName = LegacyUtils.getAdminComponentName(context);
                    dpm.setKeyguardDisabled(adminComponentName, true);
                    // Re-enable after a short delay
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        try {
                            dpm.setKeyguardDisabled(adminComponentName, false);
                        } catch (Exception e) {
                            // Ignore
                        }
                    }, 5000);
                }
            }

            // Dismiss the EduTab lock screen
            com.hmdm.launcher.ui.LockScreenActivity.unlock(context);

            // Exit kiosk mode (send broadcast to MainActivity)
            LocalBroadcastManager.getInstance(context).
                sendBroadcast(new Intent(Const.ACTION_EXIT_KIOSK));

            wl.release();
            RemoteLogger.log(context, Const.LOG_INFO, "Device unlocked and kiosk mode exited");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Unlock device failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Show attention flash screen
     */
    private static void showAttention(Context context) {
        try {
            Intent attentionIntent = new Intent(context, com.hmdm.launcher.ui.AttentionActivity.class);
            attentionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(attentionIntent);
            RemoteLogger.log(context, Const.LOG_INFO, "Attention signal sent");
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Attention signal failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Show message overlay
     */
    private static void showMessage(Context context, String message) {
        try {
            Intent msgIntent = new Intent(context, com.hmdm.launcher.ui.MessageOverlayActivity.class);
            msgIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            msgIntent.putExtra("message", message);
            context.startActivity(msgIntent);
            RemoteLogger.log(context, Const.LOG_INFO, "Message displayed: " + message);
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_WARN, "Show message failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

}
