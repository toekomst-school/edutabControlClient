package com.hmdm.launcher.ui;

import android.app.Activity;
import com.hmdm.launcher.R;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Full-screen activity that displays a calm "attention" screen.
 * Shows a solid amber background with an icon and text asking students to look up.
 * Safe for children with epilepsy - NO flashing animations.
 */
public class AttentionActivity extends Activity {

    private static final int AUTO_DISMISS_DELAY_MS = 5000; // 5 seconds
    private static final int BACKGROUND_COLOR = Color.parseColor("#FFC107"); // Amber

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

        // Create layout programmatically
        LinearLayout rootLayout = new LinearLayout(this);
        rootLayout.setOrientation(LinearLayout.VERTICAL);
        rootLayout.setGravity(Gravity.CENTER);
        rootLayout.setBackgroundColor(BACKGROUND_COLOR);

        // Eye icon using emoji (universally available)
        TextView iconView = new TextView(this);
        iconView.setText("👀");
        iconView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 80);
        iconView.setGravity(Gravity.CENTER);

        // "Look up" text - localized
        TextView textView = new TextView(this);
        textView.setText(R.string.attention_look_at_teacher);
        textView.setTextColor(Color.WHITE);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 32);
        textView.setTypeface(null, Typeface.BOLD);
        textView.setGravity(Gravity.CENTER);
        int padding = (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 24, getResources().getDisplayMetrics());
        textView.setPadding(padding, padding, padding, padding);

        rootLayout.addView(iconView);
        rootLayout.addView(textView);

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
