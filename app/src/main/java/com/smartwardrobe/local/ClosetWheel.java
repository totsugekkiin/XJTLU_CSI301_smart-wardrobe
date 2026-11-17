package com.smartwardrobe.local;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

final class ClosetWheel extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Integer> colors;
    private final List<PointF> points = new ArrayList<>();
    private final IntConsumer picked;
    private final int[] spectrum = new int[13];
    private float centerX, centerY, radius;
    private int active = -1;

    ClosetWheel(MainActivity ui, List<Integer> colors, IntConsumer picked) {
        super(ui);
        this.colors = new ArrayList<>(colors);
        this.picked = picked;
        for (int i = 0; i < spectrum.length; i++) spectrum[i] = Color.HSVToColor(new float[]{(i * 30) % 360, .16f, .97f});
        setContentDescription("衣橱圆盘色盘，下方也可按颜色名称选择");
    }

    void select(int color) {
        active = colors.indexOf(color);
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.min(w, h) * .46f;
        points.clear();
        List<Integer> neutrals = new ArrayList<>();
        float[] hsl = new float[3];
        for (int rgb : colors) {
            ColorUtils.colorToHSL(rgb, hsl);
            if (isNeutral(hsl)) {
                neutrals.add(points.size());
                points.add(null);
            } else {
                double angle = Math.toRadians(hsl[0] - 90);
                float distance = radius * (.48f + .34f * Math.max(.15f, hsl[1]));
                points.add(point(angle, distance));
            }
        }
        neutrals.sort((a, b) -> Double.compare(ColorUtils.calculateLuminance(colors.get(a)),
                ColorUtils.calculateLuminance(colors.get(b))));
        for (int i = 0; i < neutrals.size(); i++) {
            double angle = 2 * Math.PI * i / neutrals.size() - Math.PI / 2;
            points.set(neutrals.get(i), point(angle, neutrals.size() == 1 ? 0 : radius * .22f));
        }
    }

    private PointF point(double angle, float distance) {
        return new PointF(centerX + distance * (float) Math.cos(angle), centerY + distance * (float) Math.sin(angle));
    }

    private static boolean isNeutral(float[] hsl) {
        return hsl[1] <= .18f || hsl[2] <= .12f || hsl[2] >= .92f;
    }

    static String harmony(int anchor, int candidate) {
        if (anchor == candidate) return "同色呼应";
        float[] base = new float[3], choice = new float[3];
        ColorUtils.colorToHSL(anchor, base);
        ColorUtils.colorToHSL(candidate, choice);
        if (isNeutral(choice)) return "中性百搭";
        if (isNeutral(base)) return "邻近色";
        float difference = Math.abs(base[0] - choice[0]);
        difference = Math.min(difference, 360 - difference);
        if (difference <= 22) return "同色系";
        if (difference <= 65) return "邻近色";
        if (difference >= 135) return "互补色";
        return "";
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new SweepGradient(centerX, centerY, spectrum, null));
        canvas.save();
        canvas.rotate(-90, centerX, centerY);
        canvas.drawCircle(centerX, centerY, radius, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setColor(0xccffffff);
        canvas.drawCircle(centerX, centerY, radius * .42f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.5f);
        paint.setColor(MainActivity.STROKE);
        for (float ring : new float[]{1f, .78f, .42f}) canvas.drawCircle(centerX, centerY, radius * ring, paint);
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, paint);
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, paint);
        for (int i = 0; i < points.size(); i++) {
            PointF p = points.get(i);
            float r = radius * (i == active ? .145f : .09f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(i == active ? MainActivity.INK : Color.WHITE);
            canvas.drawCircle(p.x, p.y, r + 3, paint);
            paint.setColor(colors.get(i));
            canvas.drawCircle(p.x, p.y, r, paint);
            if (i == active) {
                paint.setStyle(Paint.Style.STROKE);
                paint.setColor(Color.WHITE);
                paint.setStrokeWidth(2.5f);
                canvas.drawCircle(p.x, p.y, r * .26f, paint);
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) getParent().requestDisallowInterceptTouchEvent(false);
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            int nearest = -1;
            double distance = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                PointF p = points.get(i);
                double d = Math.hypot(p.x - event.getX(), p.y - event.getY());
                if (d < distance) { distance = d; nearest = i; }
            }
            if (nearest >= 0 && nearest != active && distance <= radius * .55f) {
                active = nearest;
                picked.accept(colors.get(nearest));
                invalidate();
            }
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
