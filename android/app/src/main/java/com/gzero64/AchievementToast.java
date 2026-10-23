package com.gzero64;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.util.ArrayDeque;

/** "Achievement unlocked" pop-up at the top of the game, one at a time, polled from the game. */
final class AchievementToast {
    private static final long POLL_MS = 1000;
    private static final long SHOW_MS = 4000;

    private final GameActivity activity;
    private final ViewGroup parent;
    private final ArrayDeque<String> queue = new ArrayDeque<>();
    private LinearLayout shown;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            try {
                for (String unlock : GameBridge.nativeTakeUnlocks()) queue.add(unlock);
            } catch (UnsatisfiedLinkError ignored) {
                // the game library is not loaded yet
            }
            if (shown == null && !queue.isEmpty()) show(queue.poll());
            parent.postDelayed(this, POLL_MS);
        }
    };

    AchievementToast(GameActivity activity, ViewGroup parent) {
        this.activity = activity;
        this.parent = parent;
        parent.postDelayed(poll, POLL_MS);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void show(String unlock) {
        int split = unlock.indexOf('\n');
        String name = split >= 0 ? unlock.substring(0, split) : unlock;
        String description = split >= 0 ? unlock.substring(split + 1) : "";

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(12), dp(8), dp(18), dp(8));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xE6101522);
        background.setStroke(dp(2), 0xFFFFC452);
        background.setCornerRadius(dp(10));
        box.setBackground(background);
        box.setElevation(dp(6));

        ImageView trophy = new ImageView(activity);
        trophy.setImageResource(R.drawable.ic_trophy);
        box.addView(trophy, new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout text = new LinearLayout(activity);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView title = new TextView(activity);
        title.setText(R.string.achievement_unlocked);
        title.setTextColor(0xFFFFC452);
        title.setTextSize(11);
        title.setLetterSpacing(0.08f);
        TextView label = new TextView(activity);
        label.setText(name);
        label.setTextColor(Color.WHITE);
        label.setTextSize(15);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        text.addView(title);
        text.addView(label);
        if (!description.isEmpty()) {
            TextView detail = new TextView(activity);
            detail.setText(description);
            detail.setTextColor(0xFFB8C0D8);
            detail.setTextSize(12);
            detail.setMaxWidth(dp(360));
            text.addView(detail);
        }
        box.addView(text);

        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.CENTER_HORIZONTAL);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.topMargin = dp(16);
        parent.addView(box, params);
        shown = box;

        // Slides down from above the screen, stays, and slides back up.
        box.setTranslationY(-dp(140));
        box.animate().translationY(0).setDuration(350).start();
        box.postDelayed(new Runnable() {
            @Override public void run() {
                box.animate().translationY(-dp(140)).setDuration(350).setListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        parent.removeView(box);
                        shown = null;
                        if (!queue.isEmpty()) show(queue.poll());
                    }
                }).start();
            }
        }, SHOW_MS);
    }
}
