/*
 * EduTab Control - Emergency Location Service
 * Handles emergency mode with alarm sound and frequent location pings
 */

package com.hmdm.launcher.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;

import com.hmdm.launcher.Const;
import com.hmdm.launcher.R;
import com.hmdm.launcher.db.DatabaseHelper;
import com.hmdm.launcher.db.LocationTable;
import com.hmdm.launcher.helper.SettingsHelper;
import com.hmdm.launcher.pro.ProUtils;
import com.hmdm.launcher.server.ServerService;
import com.hmdm.launcher.server.ServerServiceKeeper;
import com.hmdm.launcher.util.RemoteLogger;

import java.util.Collections;
import java.util.List;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

public class EmergencyService extends Service {
    private static final String TAG = "EmergencyService";
    private static final int NOTIFICATION_ID = 113;
    public static final String CHANNEL_ID = "emergency_channel";

    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final String EXTRA_INTERVAL = "interval";

    private static final int DEFAULT_INTERVAL = 30000; // 30 seconds

    private LocationManager locationManager;
    private MediaPlayer mediaPlayer;
    private AudioManager audioManager;
    private Handler handler;
    private int originalVolume;
    private int pingInterval = DEFAULT_INTERVAL;
    private boolean isRunning = false;

    private LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            Log.d(TAG, "Emergency location update: " + location.getLatitude() + ", " + location.getLongitude());
            sendLocationToServer(location);
        }

        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {}

        @Override
        public void onProviderEnabled(String provider) {}

        @Override
        public void onProviderDisabled(String provider) {}
    };

    private Runnable locationPingRunnable = new Runnable() {
        @Override
        public void run() {
            if (isRunning) {
                requestSingleLocation();
                handler.postDelayed(this, pingInterval);
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        handler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopEmergency();
            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            pingInterval = intent.getIntExtra(EXTRA_INTERVAL, DEFAULT_INTERVAL);
            startEmergency();
        }

        return START_STICKY;
    }

    private void startEmergency() {
        if (isRunning) {
            return;
        }
        isRunning = true;

        RemoteLogger.log(this, Const.LOG_WARN, "Emergency mode activated!");

        // Start as foreground service
        startAsForeground();

        // Set volume to max and play alarm
        setMaxVolume();
        playAlarm();

        // Start location tracking
        startLocationTracking();

        // Start periodic location pings
        handler.post(locationPingRunnable);
    }

    private void stopEmergency() {
        isRunning = false;

        RemoteLogger.log(this, Const.LOG_INFO, "Emergency mode deactivated");

        // Stop alarm
        stopAlarm();

        // Restore volume
        restoreVolume();

        // Stop location tracking
        stopLocationTracking();

        // Stop periodic pings
        handler.removeCallbacks(locationPingRunnable);

        // Stop service
        stopForeground(true);
        stopSelf();
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Emergency Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Emergency location tracking");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Emergency Mode Active")
                .setContentText("Location is being tracked")
                .setSmallIcon(R.drawable.ic_location_service)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void setMaxVolume() {
        try {
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0);

            // Also set media and ring volumes to max
            int maxMedia = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxMedia, 0);

            int maxRing = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING);
            audioManager.setStreamVolume(AudioManager.STREAM_RING, maxRing, 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to set max volume", e);
        }
    }

    private void restoreVolume() {
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0);
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore volume", e);
        }
    }

    private void playAlarm() {
        try {
            Uri alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alarmUri);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes audioAttributes = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build();
                mediaPlayer.setAudioAttributes(audioAttributes);
            } else {
                mediaPlayer.setAudioStreamType(AudioManager.STREAM_ALARM);
            }

            mediaPlayer.setLooping(true);
            mediaPlayer.prepare();
            mediaPlayer.start();

            Log.d(TAG, "Alarm started");
        } catch (Exception e) {
            Log.e(TAG, "Failed to play alarm", e);
            RemoteLogger.log(this, Const.LOG_ERROR, "Failed to play emergency alarm: " + e.getMessage());
        }
    }

    private void stopAlarm() {
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
                mediaPlayer = null;
            } catch (Exception e) {
                Log.e(TAG, "Failed to stop alarm", e);
            }
        }
    }

    private void startLocationTracking() {
        try {
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    pingInterval,
                    0,
                    locationListener
                );
            }
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    pingInterval,
                    0,
                    locationListener
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start location tracking", e);
        }
    }

    private void stopLocationTracking() {
        try {
            locationManager.removeUpdates(locationListener);
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop location tracking", e);
        }
    }

    private void requestSingleLocation() {
        try {
            Location lastGps = null;
            Location lastNetwork = null;

            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastGps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            Location bestLocation = null;
            if (lastGps != null && lastNetwork != null) {
                bestLocation = lastGps.getTime() > lastNetwork.getTime() ? lastGps : lastNetwork;
            } else if (lastGps != null) {
                bestLocation = lastGps;
            } else if (lastNetwork != null) {
                bestLocation = lastNetwork;
            }

            if (bestLocation != null) {
                sendLocationToServer(bestLocation);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get location", e);
        }
    }

    private void sendLocationToServer(Location location) {
        try {
            SettingsHelper settingsHelper = SettingsHelper.getInstance(this);
            ServerService serverService = ServerServiceKeeper.getServerServiceInstance(this);

            if (serverService == null) {
                Log.e(TAG, "Server service not available");
                return;
            }

            String project = settingsHelper.getServerProject();
            String deviceId = settingsHelper.getDeviceId();

            LocationTable.Location loc = new LocationTable.Location(location);
            List<LocationTable.Location> locations = Collections.singletonList(loc);

            Call<ResponseBody> call = serverService.sendLocations(project, deviceId, locations);
            call.enqueue(new Callback<ResponseBody>() {
                @Override
                public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                    if (response.isSuccessful()) {
                        Log.d(TAG, "Emergency location sent successfully");
                        RemoteLogger.log(EmergencyService.this, Const.LOG_DEBUG,
                            "Emergency location sent: " + location.getLatitude() + ", " + location.getLongitude());
                    } else {
                        Log.e(TAG, "Failed to send location: " + response.code());
                    }
                }

                @Override
                public void onFailure(Call<ResponseBody> call, Throwable t) {
                    Log.e(TAG, "Failed to send location", t);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error sending location to server", e);
        }
    }

    @Override
    public void onDestroy() {
        stopEmergency();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // Static helper to send a single location ping
    public static void sendSingleLocationPing(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(LOCATION_SERVICE);
            Location lastGps = null;
            Location lastNetwork = null;

            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastGps = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            }
            if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                lastNetwork = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            }

            Location bestLocation = null;
            if (lastGps != null && lastNetwork != null) {
                bestLocation = lastGps.getTime() > lastNetwork.getTime() ? lastGps : lastNetwork;
            } else if (lastGps != null) {
                bestLocation = lastGps;
            } else if (lastNetwork != null) {
                bestLocation = lastNetwork;
            }

            if (bestLocation != null) {
                SettingsHelper settingsHelper = SettingsHelper.getInstance(context);
                ServerService serverService = ServerServiceKeeper.getServerServiceInstance(context);

                if (serverService == null) {
                    RemoteLogger.log(context, Const.LOG_WARN, "Cannot ping location: server service not available");
                    return;
                }

                String project = settingsHelper.getServerProject();
                String deviceId = settingsHelper.getDeviceId();

                LocationTable.Location loc = new LocationTable.Location(bestLocation);
                List<LocationTable.Location> locations = Collections.singletonList(loc);

                final Location finalLocation = bestLocation;
                Call<ResponseBody> call = serverService.sendLocations(project, deviceId, locations);
                call.enqueue(new Callback<ResponseBody>() {
                    @Override
                    public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                        if (response.isSuccessful()) {
                            RemoteLogger.log(context, Const.LOG_INFO,
                                "Location ping sent: " + finalLocation.getLatitude() + ", " + finalLocation.getLongitude());
                        }
                    }

                    @Override
                    public void onFailure(Call<ResponseBody> call, Throwable t) {
                        RemoteLogger.log(context, Const.LOG_WARN, "Location ping failed: " + t.getMessage());
                    }
                });
            } else {
                RemoteLogger.log(context, Const.LOG_WARN, "Location ping: no location available");
            }
        } catch (Exception e) {
            RemoteLogger.log(context, Const.LOG_ERROR, "Location ping error: " + e.getMessage());
        }
    }
}
