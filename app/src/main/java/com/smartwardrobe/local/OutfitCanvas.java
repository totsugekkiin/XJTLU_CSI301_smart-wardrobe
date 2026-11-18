package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.util.List;
import java.util.function.LongFunction;

final class OutfitCanvas extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private final List<WardrobeStore.Layer> layers;
    private final LongFunction<Bitmap> photos;
    private final Runnable selectionChanged;
    private WardrobeStore.Layer selected;
    private float lastX, lastY;
    private boolean drag, transformed;
    private Runnable transformFinished = () -> {};
    private boolean interactive = true;

    OutfitCanvas(Context context, List<WardrobeStore.Layer> layers, LongFunction<Bitmap> photos, Runnable selectionChanged) {
        super(context);
        this.layers = layers;
        this.photos = photos;
        this.selectionChanged = selectionChanged;
        setContentDescription("穿搭画布。点选衣物，拖动移动，双指缩放");
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector) {
                resize(detector.getScaleFactor());
                transformed = true;
                return true;
            }
        });
    }

    WardrobeStore.Layer selected() { return selected; }
    void setInteractive(boolean interactive) { this.interactive = interactive; }

    void select(WardrobeStore.Layer layer) {
        selected = layer;
        invalidate();
        selectionChanged.run();
    }

    void setOnTransformFinished(Runnable finished) { transformFinished = finished; }

    void resize(float ratio) {
        if (selected == null) return;
        selected.scale = clamp(selected.scale * ratio, .1f, 1.5f);
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(interactive ? 0xffffffff : 0xfff5f5f5);
        if (interactive) {
            paint.setColor(0xffd6d6d6);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2);
            canvas.drawRect(1, 1, getWidth() - 1, getHeight() - 1, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        for (WardrobeStore.Layer layer : layers) {
            Bitmap bitmap = photos.apply(layer.garment);
            RectF bounds = bounds(layer, bitmap);
            paint.setStyle(Paint.Style.FILL);
            if (bitmap != null) canvas.drawBitmap(bitmap, null, bounds, paint);
            else {
                paint.setColor(0xffeeeeee);
                canvas.drawRoundRect(bounds, 14, 14, paint);
                paint.setColor(0xff000000);
                paint.setTextSize(36);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("衣", bounds.centerX(), bounds.centerY() + 12, paint);
            }
            if (selected == layer) {
                paint.setColor(0xff000000);
                paint.setStrokeWidth(3);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRoundRect(bounds, 8, 8, paint);
                paint.setStyle(Paint.Style.FILL);
            }
        }
    }

    private RectF bounds(WardrobeStore.Layer layer, Bitmap bitmap) {
        float max = Math.min(getWidth(), getHeight()) * layer.scale;
        float w = max, h = max;
        if (bitmap != null) {
            float ratio = (float) bitmap.getWidth() / bitmap.getHeight();
            if (ratio > 1) h /= ratio;
            else w *= ratio;
        }
        float x = layer.x * getWidth(), y = layer.y * getHeight();
        return new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return false;
        pinch.onTouchEvent(event);
        float x = event.getX(), y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                WardrobeStore.Layer touched = null;
                for (int i = layers.size() - 1; i >= 0; i--) {
                    WardrobeStore.Layer layer = layers.get(i);
                    Bitmap bitmap = photos.apply(layer.garment);
                    RectF box = bounds(layer, bitmap);
                    if (!box.contains(x, y)) continue;
                    if (bitmap != null && Color.alpha(bitmap.getPixel(
                            Math.min(bitmap.getWidth() - 1, (int) ((x - box.left) / box.width() * bitmap.getWidth())),
                            Math.min(bitmap.getHeight() - 1, (int) ((y - box.top) / box.height() * bitmap.getHeight())))) < 30) continue;
                    touched = layer;
                    break;
                }
                select(touched);
                drag = touched != null;
                lastX = x;
                lastY = y;
                getParent().requestDisallowInterceptTouchEvent(drag);
            }
            case MotionEvent.ACTION_POINTER_DOWN -> {
                drag = false;
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (drag && !pinch.isInProgress() && selected != null) {
                    selected.x = clamp(selected.x + (x - lastX) / getWidth(), .02f, .98f);
                    selected.y = clamp(selected.y + (y - lastY) / getHeight(), .02f, .98f);
                    invalidate();
                    transformed = true;
                    lastX = x;
                    lastY = y;
                }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                drag = false;
                if (transformed) transformFinished.run();
                transformed = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            }
            default -> {}
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
