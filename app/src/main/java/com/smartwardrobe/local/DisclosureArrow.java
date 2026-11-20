package com.smartwardrobe.local;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

final class DisclosureArrow extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean down;
    private float rotation;
    private ValueAnimator animator;

    DisclosureArrow(Context context, boolean down) {
        this.down = down;
        paint.setColor(MainActivity.MUTED);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int size = Math.round(12 * context.getResources().getDisplayMetrics().density);
        setBounds(0, 0, size, size);
    }

    void color(int color) {
        paint.setColor(color);
        invalidateSelf();
    }

    void expanded(boolean expanded) {
        if (animator != null) animator.cancel();
        float target = expanded ? (down ? 180 : 90) : 0;
        if (!Motion.enabled()) { rotation = target; invalidateSelf(); return; }
        animator = ValueAnimator.ofFloat(rotation, target);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator(1.6f));
        animator.addUpdateListener(value -> { rotation = (float) value.getAnimatedValue(); invalidateSelf(); });
        animator.start();
    }

    @Override public void draw(Canvas canvas) {
        int save = canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 12f, getBounds().height() / 12f);
        canvas.rotate(rotation + (down ? 90 : 0), 6, 6);
        Path path = new Path();
        path.moveTo(4, 2);
        path.lineTo(8, 6);
        path.lineTo(4, 10);
        canvas.drawPath(path, paint);
        canvas.restoreToCount(save);
    }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
