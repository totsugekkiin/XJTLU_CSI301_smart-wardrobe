package com.smartwardrobe.local;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** One continuous indicator, including during rapid successive tab changes. */
final class SlidingTabs extends FrameLayout {
    private final View marker;
    private final int count, fixedWidth;
    private int active;
    private float initial;
    private boolean placed;

    SlidingTabs(Context context, LinearLayout content, int count, int active,
                int markerWidthDp, boolean above, float initial) {
        super(context);
        this.count = count;
        this.active = active;
        this.initial = initial;
        fixedWidth = Math.round(markerWidthDp * getResources().getDisplayMetrics().density);
        addView(content, new LayoutParams(-1, -2));
        marker = new View(context);
        marker.setBackgroundColor(MainActivity.INK);
        marker.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(marker, new LayoutParams(1, Math.round(2 * getResources().getDisplayMetrics().density),
                (above ? Gravity.TOP : Gravity.BOTTOM) | Gravity.LEFT));
    }

    float position() {
        return getWidth() == 0 ? (active + .5f) / count : (marker.getTranslationX() + marker.getWidth() / 2f) / getWidth();
    }

    void select(int index) {
        active = index;
        if (placed) move(true);
    }

    private float target() {
        float fraction = (active + .5f) / count;
        if (getLayoutDirection() == LAYOUT_DIRECTION_RTL) fraction = 1 - fraction;
        return getWidth() * fraction - marker.getLayoutParams().width / 2f;
    }

    private void move(boolean animate) {
        marker.animate().cancel();
        if (animate && Motion.enabled()) marker.animate().translationX(target()).setDuration(240)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        else marker.setTranslationX(target());
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        int width = fixedWidth > 0 ? fixedWidth : getWidth() / count;
        if (marker.getLayoutParams().width != width) {
            LayoutParams params = (LayoutParams) marker.getLayoutParams();
            params.width = width;
            marker.setLayoutParams(params);
        }
        if (!placed) {
            marker.setTranslationX(initial < 0 ? target() : getWidth() * initial - width / 2f);
            placed = true;
            move(true);
        } else if (changed) move(false);
    }

    @Override protected void onDetachedFromWindow() {
        marker.animate().cancel();
        super.onDetachedFromWindow();
    }
}
