package com.smartwardrobe.local;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.IntConsumer;

/** Four plain season labels with one continuous 18dp bar above the active label. */
final class SeasonTabs extends FrameLayout {
    private static final PathInterpolator EASE_OUT_CUBIC = new PathInterpolator(.33f, 1f, .68f, 1f);
    private static final int IDLE = 0xff888888;

    private final TextView[] labels = new TextView[MainActivity.SEASONS.length];
    private final View marker;
    private int selected;
    private boolean placed;

    SeasonTabs(MainActivity ui, int selected, IntConsumer picked) {
        super(ui);
        this.selected = selected;
        setClipChildren(false);
        LinearLayout row = new LinearLayout(ui);
        row.setOrientation(LinearLayout.HORIZONTAL);
        addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            TextView label = ui.text(MainActivity.SEASONS[i], 13,
                    i == selected ? MainActivity.INK : IDLE, i == selected);
            label.setGravity(Gravity.CENTER);
            label.setSingleLine(true);
            label.setFocusable(true);
            label.setContentDescription(MainActivity.SEASONS[i] + "季" + (i == selected ? "，已选中" : ""));
            label.setOnClickListener(view -> picked.accept(index));
            labels[i] = label;
            row.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        marker = new View(ui);
        marker.setBackgroundColor(MainActivity.INK);
        marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams params = new LayoutParams(ui.dp(18), ui.dp(2), Gravity.TOP | Gravity.LEFT);
        params.topMargin = ui.dp(4);
        addView(marker, params);
        marker.setVisibility(selected >= 0 ? View.VISIBLE : View.GONE);
    }

    void select(int index) {
        boolean appearing = selected < 0 || marker.getVisibility() != View.VISIBLE;
        selected = index;
        style();
        if (!placed) {
            marker.setVisibility(View.VISIBLE);
            return;
        }
        marker.animate().cancel();
        float target = target(index);
        if (appearing) {
            marker.setTranslationX(target);
            marker.setScaleX(0f);
            marker.setAlpha(0f);
            marker.setVisibility(View.VISIBLE);
            marker.animate().scaleX(1f).alpha(1f).setDuration(160)
                    .setInterpolator(EASE_OUT_CUBIC).start();
        } else {
            marker.setVisibility(View.VISIBLE);
            marker.animate().translationX(target).scaleX(1f).alpha(1f).setDuration(220)
                    .setInterpolator(EASE_OUT_CUBIC).start();
        }
    }

    void deselect() {
        if (selected < 0) return;
        selected = -1;
        style();
        if (!placed) {
            marker.setVisibility(View.GONE);
            return;
        }
        marker.animate().cancel();
        marker.animate().scaleX(0f).alpha(0f).setDuration(180).setInterpolator(EASE_OUT_CUBIC)
                .withEndAction(() -> {
                    if (selected < 0) marker.setVisibility(View.GONE);
                }).start();
    }

    private void style() {
        for (int i = 0; i < labels.length; i++) {
            boolean active = i == selected;
            labels[i].setTextColor(active ? MainActivity.INK : IDLE);
            labels[i].setTypeface(Typeface.create("sans-serif",
                    active ? Typeface.BOLD : Typeface.NORMAL));
            labels[i].setContentDescription(MainActivity.SEASONS[i] + "季" + (active ? "，已选中" : ""));
        }
    }

    private float target(int index) {
        float width = marker.getLayoutParams().width;
        return getWidth() * (index + .5f) / labels.length - width / 2f;
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (getWidth() == 0) return;
        if (!placed) {
            placed = true;
            marker.setTranslationX(target(Math.max(0, selected)));
        } else if (changed && selected >= 0) {
            marker.animate().cancel();
            marker.setTranslationX(target(selected));
        }
    }

    @Override protected void onDetachedFromWindow() {
        marker.animate().cancel();
        super.onDetachedFromWindow();
    }
}
