package com.hmdm.launcher.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Full-screen overlay activity to display a teacher's message.
 * Shows a semi-transparent dark background with large centered text.
 * Auto-dismisses after 10 seconds or on tap.
 */
public class MessageOverlayActivity extends Activity {

    public static final String EXTRA_MESSAGE = "message";
    private static final int AUTO_DISMISS_DELAY_MS = 10000; // 10 seconds

    private Handler dismissHandler;
    private Runnable dismissRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Make full screen and wake device
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );

        // Get message from intent
        String message = getIntent().getStringExtra(EXTRA_MESSAGE);
        if (message == null || message.isEmpty()) {
            finish();
            return;
        }

        // Create the layout programmatically
        FrameLayout rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor("#CC000000")); // Semi-transparent black

        // Create text view
        TextView messageText = new TextView(this);
        messageText.setText(message);
        messageText.setTextColor(Color.WHITE);
        messageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        messageText.setGravity(Gravity.CENTER);
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 32, getResources().getDisplayMetrics());
        messageText.setPadding(padding, padding, padding, padding);

        // Add text to layout, centered
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        );
        rootLayout.addView(messageText, params);

        // Tap to dismiss
        rootLayout.setOnClickListener(v -> finish());

        setContentView(rootLayout);

        // Auto-dismiss after delay
        dismissHandler = new Handler(Looper.getMainLooper());
        dismissRunnable = this::finish;
        dismissHandler.postDelayed(dismissRunnable, AUTO_DISMISS_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (dismissHandler != null && dismissRunnable != null) {
            dismissHandler.removeCallbacks(dismissRunnable);
        }
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
