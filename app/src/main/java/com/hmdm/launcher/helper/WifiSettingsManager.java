/*
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * WiFi network configuration manager for automatic network provisioning
 */

package com.hmdm.launcher.helper;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.util.Log;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.json.WifiConfig;
import com.hmdm.launcher.util.RemoteLogger;
import com.hmdm.launcher.util.Utils;

import java.util.ArrayList;
import java.util.List;

public class WifiSettingsManager {

    private final Context context;
    private final WifiManager wifiManager;

    public WifiSettingsManager(Context context) {
        this.context = context;
        this.wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Apply WiFi network configurations from server config.
     * Priority order:
     * 1. Device Owner on Android 10+ -> DevicePolicyManager.addWifiNetwork() (best, no user approval needed)
     * 2. Device Owner on Android 9 and below -> legacy WifiConfiguration
     * 3. Non-Device Owner on Android 10+ -> WifiNetworkSuggestion API (requires user approval)
     * 4. Non-Device Owner on Android 9 and below -> legacy WifiConfiguration (limited)
     */
    public void applyWifiNetworks(List<WifiConfig> wifiNetworks) {
        if (wifiNetworks == null || wifiNetworks.isEmpty()) {
            Log.d(Const.LOG_TAG, "No WiFi networks to configure");
            return;
        }

        if (wifiManager == null) {
            RemoteLogger.log(context, Const.LOG_WARN, "WifiManager not available");
            return;
        }

        // Check if we're Device Owner
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        boolean isDeviceOwner = dpm != null && dpm.isDeviceOwnerApp(context.getPackageName());
        Log.d(Const.LOG_TAG, "Device Owner status: " + isDeviceOwner + ", Android SDK: " + Build.VERSION.SDK_INT);

        // Ensure WiFi is enabled
        if (!wifiManager.isWifiEnabled()) {
            if (isDeviceOwner || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.d(Const.LOG_TAG, "Enabling WiFi for network configuration");
                try {
                    wifiManager.setWifiEnabled(true);
                } catch (Exception e) {
                    Log.w(Const.LOG_TAG, "Cannot enable WiFi: " + e.getMessage());
                }
            } else {
                Log.d(Const.LOG_TAG, "WiFi is disabled; cannot enable programmatically on Android 10+ without Device Owner");
            }
        }

        // Use the best available method based on Device Owner status and Android version
        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Best option: Device Owner on Android 10+ - use DevicePolicyManager
            Log.d(Const.LOG_TAG, "Using DevicePolicyManager API (Device Owner, Android " + Build.VERSION.SDK_INT + ")");
            RemoteLogger.log(context, Const.LOG_INFO, "Configuring " + wifiNetworks.size() + " WiFi networks using Device Owner API");
            applyWifiViaDevicePolicyManager(dpm, wifiNetworks);
        } else if (isDeviceOwner) {
            // Device Owner on Android 9 and below - use legacy WifiConfiguration
            Log.d(Const.LOG_TAG, "Using legacy WifiConfiguration API (Device Owner, Android " + Build.VERSION.SDK_INT + ")");
            RemoteLogger.log(context, Const.LOG_INFO, "Configuring " + wifiNetworks.size() + " WiFi networks using legacy API");
            applyLegacyWifiConfig(wifiNetworks);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Non-Device Owner on Android 10+ - use WifiNetworkSuggestion
            Log.d(Const.LOG_TAG, "Using WifiNetworkSuggestion API (non-Device Owner, Android " + Build.VERSION.SDK_INT + ")");
            RemoteLogger.log(context, Const.LOG_INFO, "Configuring " + wifiNetworks.size() + " WiFi networks using Suggestion API (requires user approval)");
            applyWifiSuggestions(wifiNetworks);
        } else {
            // Non-Device Owner on Android 9 and below - try legacy (may fail without permissions)
            Log.d(Const.LOG_TAG, "Using legacy WifiConfiguration API (non-Device Owner, Android " + Build.VERSION.SDK_INT + ")");
            RemoteLogger.log(context, Const.LOG_WARN, "Attempting legacy WiFi config without Device Owner - may fail");
            applyLegacyWifiConfig(wifiNetworks);
        }
    }

    /**
     * Apply WiFi networks using WifiManager for Device Owner apps.
     * Device Owner status grants permission to use the deprecated WifiManager.addNetwork() API
     * even on Android 10+, which adds networks directly to saved networks.
     */
    @SuppressWarnings("deprecation")
    private void applyWifiViaDevicePolicyManager(DevicePolicyManager dpm, List<WifiConfig> wifiNetworks) {
        int successCount = 0;
        int failCount = 0;

        for (WifiConfig wifi : wifiNetworks) {
            if (wifi.getSsid() == null || wifi.getSsid().isEmpty()) {
                Log.w(Const.LOG_TAG, "Skipping WiFi config with empty SSID");
                continue;
            }

            try {
                WifiConfiguration config = buildWifiConfiguration(wifi);
                if (config != null) {
                    // Log what we're adding (without password)
                    String password = wifi.getPassword();
                    boolean hasPassword = password != null && !password.isEmpty();
                    Log.d(Const.LOG_TAG, "Adding WiFi (Device Owner): SSID=" + wifi.getSsid() +
                            ", security=" + wifi.getSecurityType() +
                            ", hasPassword=" + hasPassword +
                            ", passwordLength=" + (hasPassword ? password.length() : 0));

                    // Add network via WifiManager (Device Owner has permission for this even on Android 10+)
                    int networkId = wifiManager.addNetwork(config);
                    if (networkId != -1) {
                        // Enable the network
                        boolean enabled = wifiManager.enableNetwork(networkId, false);
                        Log.d(Const.LOG_TAG, "Added WiFi: " + wifi.getSsid() +
                                " (networkId=" + networkId + ", enabled=" + enabled + ")");
                        successCount++;
                    } else {
                        Log.e(Const.LOG_TAG, "Failed to add WiFi: " + wifi.getSsid() + " (addNetwork returned -1)");
                        failCount++;
                    }
                }
            } catch (Exception e) {
                Log.e(Const.LOG_TAG, "Error adding WiFi: " + wifi.getSsid() + " - " + e.getMessage());
                e.printStackTrace();
                failCount++;
            }
        }

        // Save the configuration
        if (successCount > 0) {
            wifiManager.saveConfiguration();
        }

        String resultMsg = "Device Owner WiFi config: " + successCount + " added, " + failCount + " failed";
        Log.d(Const.LOG_TAG, resultMsg);
        RemoteLogger.log(context, failCount > 0 ? Const.LOG_WARN : Const.LOG_INFO, resultMsg);
    }

    /**
     * Build a WifiConfiguration from WifiConfig for use with DPM or legacy API
     */
    @SuppressWarnings("deprecation")
    private WifiConfiguration buildWifiConfiguration(WifiConfig wifi) {
        WifiConfiguration config = new WifiConfiguration();
        config.SSID = "\"" + wifi.getSsid() + "\"";
        config.hiddenSSID = wifi.isHidden();

        String securityType = wifi.getSecurityType();
        if (securityType == null) {
            securityType = "OPEN";
        }

        String password = wifi.getPassword();
        boolean hasPassword = password != null && !password.isEmpty();

        switch (securityType.toUpperCase()) {
            case "WPA3":
            case "WPA2":
            case "WPA":
            case "WPA2-PSK":
            case "WPA-PSK":
                if (hasPassword) {
                    config.preSharedKey = "\"" + password + "\"";
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    Log.d(Const.LOG_TAG, "Built WPA config for " + wifi.getSsid());
                } else {
                    Log.w(Const.LOG_TAG, "WPA network " + wifi.getSsid() + " has no password!");
                    return null;
                }
                break;
            case "WEP":
                if (hasPassword) {
                    config.wepKeys[0] = "\"" + password + "\"";
                    config.wepTxKeyIndex = 0;
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
                    Log.d(Const.LOG_TAG, "Built WEP config for " + wifi.getSsid());
                } else {
                    Log.w(Const.LOG_TAG, "WEP network " + wifi.getSsid() + " has no password!");
                    return null;
                }
                break;
            case "OPEN":
            case "NONE":
                config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                Log.d(Const.LOG_TAG, "Built OPEN config for " + wifi.getSsid());
                break;
            default:
                // Default to WPA2 if password is provided, otherwise open
                if (hasPassword) {
                    config.preSharedKey = "\"" + password + "\"";
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                    Log.d(Const.LOG_TAG, "Built WPA config (default) for " + wifi.getSsid());
                } else {
                    config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                    Log.d(Const.LOG_TAG, "Built OPEN config (default, no password) for " + wifi.getSsid());
                }
                break;
        }

        return config;
    }

    /**
     * Apply WiFi networks using WifiNetworkSuggestion API (Android 10+)
     * These are "suggestions" - Android decides when to connect based on signal strength, etc.
     */
    private void applyWifiSuggestions(List<WifiConfig> wifiNetworks) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        // Step 1: Remove any existing suggestions from this app FIRST
        try {
            List<WifiNetworkSuggestion> existingSuggestions = wifiManager.getNetworkSuggestions();
            if (!existingSuggestions.isEmpty()) {
                wifiManager.removeNetworkSuggestions(existingSuggestions);
                Log.d(Const.LOG_TAG, "Removed " + existingSuggestions.size() + " existing WiFi suggestions");
            }
        } catch (Exception e) {
            Log.w(Const.LOG_TAG, "Could not remove existing suggestions: " + e.getMessage());
        }

        // Step 2: Build list of new suggestions
        List<WifiNetworkSuggestion> suggestions = new ArrayList<>();

        for (WifiConfig wifi : wifiNetworks) {
            if (wifi.getSsid() == null || wifi.getSsid().isEmpty()) {
                Log.w(Const.LOG_TAG, "Skipping WiFi config with empty SSID");
                continue;
            }

            try {
                WifiNetworkSuggestion suggestion = buildSuggestion(wifi);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                    Log.d(Const.LOG_TAG, "Prepared WiFi suggestion: " + wifi.getSsid() +
                            (wifi.getLocation() != null ? " (" + wifi.getLocation() + ")" : ""));
                }
            } catch (Exception e) {
                Log.e(Const.LOG_TAG, "Failed to create WiFi suggestion for " + wifi.getSsid() + ": " + e.getMessage());
            }
        }

        if (suggestions.isEmpty()) {
            Log.d(Const.LOG_TAG, "No valid WiFi suggestions to add");
            return;
        }

        // Step 3: Add new suggestions to the system
        Log.d(Const.LOG_TAG, "Calling addNetworkSuggestions with " + suggestions.size() + " suggestions...");
        int status = wifiManager.addNetworkSuggestions(suggestions);

        // Decode the status for better logging
        String statusName;
        switch (status) {
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS:
                statusName = "SUCCESS";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL:
                statusName = "ERROR_INTERNAL";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED:
                statusName = "ERROR_APP_DISALLOWED (user denied or need to approve in Settings)";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE:
                statusName = "ERROR_ADD_DUPLICATE";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP:
                statusName = "ERROR_ADD_EXCEEDS_MAX_PER_APP";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID:
                statusName = "ERROR_REMOVE_INVALID";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED:
                statusName = "ERROR_ADD_NOT_ALLOWED";
                break;
            case WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID:
                statusName = "ERROR_ADD_INVALID";
                break;
            default:
                statusName = "UNKNOWN(" + status + ")";
        }

        Log.d(Const.LOG_TAG, "addNetworkSuggestions result: " + statusName);

        if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            Log.d(Const.LOG_TAG, "Successfully added " + suggestions.size() + " WiFi suggestions to system");
            RemoteLogger.log(context, Const.LOG_INFO, "Applied " + suggestions.size() + " WiFi network suggestions");
        } else if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED) {
            String errorMsg = "WiFi suggestions not allowed - user needs to enable in Settings > Apps > EduTab Control > WiFi control";
            RemoteLogger.log(context, Const.LOG_WARN, errorMsg);
            Log.e(Const.LOG_TAG, errorMsg);
        } else {
            String errorMsg = "Failed to add WiFi suggestions: " + statusName;
            RemoteLogger.log(context, Const.LOG_WARN, errorMsg);
            Log.e(Const.LOG_TAG, errorMsg);
        }
    }

    /**
     * Build a WifiNetworkSuggestion from WifiConfig
     */
    private WifiNetworkSuggestion buildSuggestion(WifiConfig wifi) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return null;
        }

        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder()
                .setSsid(wifi.getSsid())
                // Enable app interaction - shows "Connected via EduTab" in WiFi settings
                .setIsAppInteractionRequired(true);

        // Handle hidden networks
        if (wifi.isHidden()) {
            builder.setIsHiddenSsid(true);
        }

        // Set priority hint for Android 11+ (higher = more preferred)
        // Use high priority to prefer EduTab-managed networks
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setPriority(Integer.MAX_VALUE);
        }

        String securityType = wifi.getSecurityType();
        if (securityType == null) {
            securityType = "OPEN";
        }

        String password = wifi.getPassword();
        boolean hasPassword = password != null && !password.isEmpty();
        Log.d(Const.LOG_TAG, "Building suggestion for " + wifi.getSsid() +
                ": securityType=" + securityType +
                ", hasPassword=" + hasPassword +
                ", passwordLength=" + (hasPassword ? password.length() : 0));

        switch (securityType.toUpperCase()) {
            case "WPA3":
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    if (hasPassword) {
                        builder.setWpa3Passphrase(password);
                        Log.d(Const.LOG_TAG, "Set WPA3 passphrase for " + wifi.getSsid());
                    } else {
                        Log.w(Const.LOG_TAG, "WPA3 network " + wifi.getSsid() + " has no password!");
                    }
                }
                break;
            case "WPA2":
            case "WPA":
            case "WPA2-PSK":
            case "WPA-PSK":
                if (hasPassword) {
                    builder.setWpa2Passphrase(password);
                    Log.d(Const.LOG_TAG, "Set WPA2 passphrase for " + wifi.getSsid());
                } else {
                    Log.w(Const.LOG_TAG, "WPA2 network " + wifi.getSsid() + " has no password!");
                }
                break;
            case "OPEN":
            case "NONE":
                // Open network, no password needed
                Log.d(Const.LOG_TAG, "Open network " + wifi.getSsid() + " - no password needed");
                break;
            default:
                // Default to WPA2 if password is provided
                if (hasPassword) {
                    builder.setWpa2Passphrase(password);
                    Log.d(Const.LOG_TAG, "Unknown security '" + securityType + "', using WPA2 passphrase for " + wifi.getSsid());
                } else {
                    Log.w(Const.LOG_TAG, "Unknown security '" + securityType + "' and no password for " + wifi.getSsid());
                }
                break;
        }

        return builder.build();
    }

    /**
     * Apply WiFi networks using legacy WifiConfiguration API (Android 9 and below)
     * This requires elevated privileges (device owner or system app)
     */
    @SuppressWarnings("deprecation")
    private void applyLegacyWifiConfig(List<WifiConfig> wifiNetworks) {
        if (!Utils.isDeviceOwner(context)) {
            RemoteLogger.log(context, Const.LOG_WARN,
                    "Legacy WiFi config requires device owner privileges on Android 9 and below");
            return;
        }

        for (WifiConfig wifi : wifiNetworks) {
            if (wifi.getSsid() == null || wifi.getSsid().isEmpty()) {
                continue;
            }

            try {
                WifiConfiguration config = new WifiConfiguration();
                config.SSID = "\"" + wifi.getSsid() + "\"";
                config.hiddenSSID = wifi.isHidden();

                String securityType = wifi.getSecurityType();
                if (securityType == null) {
                    securityType = "OPEN";
                }

                switch (securityType.toUpperCase()) {
                    case "WPA2":
                    case "WPA":
                    case "WPA2-PSK":
                    case "WPA-PSK":
                    case "WPA3":
                        config.preSharedKey = "\"" + wifi.getPassword() + "\"";
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
                        break;
                    case "WEP":
                        config.wepKeys[0] = "\"" + wifi.getPassword() + "\"";
                        config.wepTxKeyIndex = 0;
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
                        break;
                    case "OPEN":
                    case "NONE":
                    default:
                        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                        break;
                }

                int networkId = wifiManager.addNetwork(config);
                if (networkId != -1) {
                    wifiManager.enableNetwork(networkId, false);
                    Log.d(Const.LOG_TAG, "Added legacy WiFi config for: " + wifi.getSsid());
                } else {
                    Log.w(Const.LOG_TAG, "Failed to add legacy WiFi config for: " + wifi.getSsid());
                }
            } catch (Exception e) {
                Log.e(Const.LOG_TAG, "Error adding legacy WiFi config for " + wifi.getSsid() + ": " + e.getMessage());
            }
        }

        wifiManager.saveConfiguration();
        RemoteLogger.log(context, Const.LOG_INFO, "Applied " + wifiNetworks.size() + " WiFi configurations (legacy mode)");
    }

    /**
     * Remove all WiFi network suggestions added by this app
     */
    public void removeAllSuggestions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && wifiManager != null) {
            try {
                List<WifiNetworkSuggestion> suggestions = wifiManager.getNetworkSuggestions();
                if (!suggestions.isEmpty()) {
                    wifiManager.removeNetworkSuggestions(suggestions);
                    Log.d(Const.LOG_TAG, "Removed all WiFi network suggestions");
                }
            } catch (Exception e) {
                Log.e(Const.LOG_TAG, "Failed to remove WiFi suggestions: " + e.getMessage());
            }
        }
    }
}
