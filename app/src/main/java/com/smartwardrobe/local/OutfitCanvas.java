package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.function.LongFunction;

final class OutfitCanvas extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final List<WardrobeStore.Layer> layers;
    private final LongFunction<Bitmap> photos;
    private final Runnable selectionChanged;
    private WardrobeStore.Layer selected;
    private float lastX, lastY, focusX, focusY, distance, angle;
    private boolean drag, transformed, multi;
    private final float handleRadius;
    private int handle;
    private Runnable transformFinished = () -> {};
    private boolean interactive = true;

    OutfitCanvas(Context context, List<WardrobeStore.Layer> layers, LongFunction<Bitmap> photos, Runnable selectionChanged) {
        super(context);
        setBackgroundColor(Color.WHITE);
        this.layers = layers;
        this.photos = photos;
        this.selectionChanged = selectionChanged;
        handleRadius = context.getResources().getDisplayMetrics().density * 24;
        setContentDescription("编辑穿搭。点选并拖动单品，双指缩放和旋转");
    }

    WardrobeStore.Layer selected() { return selected; }
    void setInteractive(boolean interactive) {
        this.interactive = interactive;
        setBackgroundColor(Color.WHITE);
        invalidate();
    }

    void select(WardrobeStore.Layer layer) {
        if (selected == layer) { invalidate(); return; }
        selected = layer;
        invalidate();
        selectionChanged.run();
    }

    void setOnTransformFinished(Runnable finished) { transformFinished = finished; }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.WHITE);
        for (WardrobeStore.Layer layer : layers) {
            Bitmap bitmap = photos.apply(layer.garment);
            RectF bounds = bounds(layer, bitmap);
            canvas.save();
            canvas.rotate(layer.rotation, bounds.centerX(), bounds.centerY());
            paint.setStyle(Paint.Style.FILL);
            if (bitmap != null) canvas.drawBitmap(bitmap, null, bounds, paint);
            else {
                paint.setColor(0xfff2f2f2);
                canvas.drawRoundRect(bounds, 4, 4, paint);
                paint.setColor(0xff666666);
                paint.setTextSize(UiLanguage.english(getContext()) ? 22 : 36);
                paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(UiLanguage.english(getContext()) ? "ITEM" : "衣",
                        bounds.centerX(), bounds.centerY() + 12, paint);
            }
            if (interactive && selected == layer) {
                float dp = getResources().getDisplayMetrics().density;
                paint.setColor(0xff303030);
                paint.setStrokeWidth(dp);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawRect(bounds, paint);
                canvas.drawLine(bounds.centerX(), bounds.top, bounds.centerX(), bounds.top - 18 * dp, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                canvas.drawCircle(bounds.right, bounds.bottom, 6 * dp, paint);
                canvas.drawCircle(bounds.centerX(), bounds.top - 18 * dp, 6 * dp, paint);
                paint.setColor(0xff303030);
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawCircle(bounds.right, bounds.bottom, 6 * dp, paint);
                canvas.drawCircle(bounds.centerX(), bounds.top - 18 * dp, 6 * dp, paint);
                paint.setStyle(Paint.Style.FILL);
            }
            canvas.restore();
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

    private float[] local(WardrobeStore.Layer layer, float x, float y) {
        float cx = layer.x * getWidth(), cy = layer.y * getHeight();
        double rad = Math.toRadians(-layer.rotation);
        float dx = x - cx, dy = y - cy;
        return new float[]{cx + (float) (dx * Math.cos(rad) - dy * Math.sin(rad)),
                cy + (float) (dx * Math.sin(rad) + dy * Math.cos(rad))};
    }

    private boolean hit(WardrobeStore.Layer layer, float x, float y) {
        Bitmap bitmap = photos.apply(layer.garment);
        RectF box = bounds(layer, bitmap);
        float[] point = local(layer, x, y);
        if (!box.contains(point[0], point[1])) return false;
        if (bitmap == null) return true;
        int px = Math.max(0, Math.min(bitmap.getWidth() - 1,
                (int) ((point[0] - box.left) / box.width() * bitmap.getWidth())));
        int py = Math.max(0, Math.min(bitmap.getHeight() - 1,
                (int) ((point[1] - box.top) / box.height() * bitmap.getHeight())));
        return Color.alpha(bitmap.getPixel(px, py)) >= 30;
    }

    private boolean handleHit(float x, float y, boolean rotate) {
        if (selected == null) return false;
        RectF box = bounds(selected, photos.apply(selected.garment));
        float[] point = local(selected, x, y);
        float hx = rotate ? box.centerX() : box.right;
        float hy = rotate ? box.top - 18 * getResources().getDisplayMetrics().density : box.bottom;
        return Math.hypot(point[0] - hx, point[1] - hy) <= handleRadius;
    }

    private void startMulti(MotionEvent event) {
        focusX = (event.getX(0) + event.getX(1)) / 2;
        focusY = (event.getY(0) + event.getY(1)) / 2;
        distance = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
        angle = (float) Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0));
        multi = selected != null;
        drag = false;
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!interactive) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX(), y = event.getY();
            handle = handleHit(x, y, true) ? 2 : handleHit(x, y, false) ? 1 : 0;
            if (handle == 0) {
                WardrobeStore.Layer touched = null;
                for (int i = layers.size() - 1; i >= 0; i--)
                    if (hit(layers.get(i), x, y)) { touched = layers.get(i); break; }
                select(touched);
            }
            drag = selected != null;
            transformed = false;
            lastX = x; lastY = y;
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            startMulti(event);
        } else if (action == MotionEvent.ACTION_MOVE && selected != null) {
            if (multi && event.getPointerCount() >= 2 && distance > 1) {
                float cx = (event.getX(0) + event.getX(1)) / 2;
                float cy = (event.getY(0) + event.getY(1)) / 2;
                float nextDistance = (float) Math.hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0));
                float nextAngle = (float) Math.atan2(event.getY(1) - event.getY(0), event.getX(1) - event.getX(0));
                float nextScale = clamp(selected.scale * nextDistance / distance, .08f, 1.8f);
                float ratio = nextScale / selected.scale;
                float delta = angleDelta(nextAngle, angle);
                float dx = selected.x * getWidth() - focusX, dy = selected.y * getHeight() - focusY;
                selected.x = clamp((cx + ratio * (dx * (float) Math.cos(delta) - dy * (float) Math.sin(delta))) / getWidth(), .01f, .99f);
                selected.y = clamp((cy + ratio * (dx * (float) Math.sin(delta) + dy * (float) Math.cos(delta))) / getHeight(), .01f, .99f);
                selected.scale = nextScale;
                selected.rotation += (float) Math.toDegrees(delta);
                focusX = cx; focusY = cy; distance = nextDistance; angle = nextAngle;
                transformed = true;
                invalidate();
            } else if (drag && event.getPointerCount() == 1) {
                float x = event.getX(), y = event.getY();
                if (handle == 1) {
                    float cx = selected.x * getWidth(), cy = selected.y * getHeight();
                    float oldDistance = (float) Math.hypot(lastX - cx, lastY - cy);
                    if (oldDistance > 1) selected.scale = clamp(selected.scale *
                            (float) Math.hypot(x - cx, y - cy) / oldDistance, .08f, 1.8f);
                } else if (handle == 2) {
                    float cx = selected.x * getWidth(), cy = selected.y * getHeight();
                    selected.rotation += (float) Math.toDegrees(angleDelta(
                            (float) Math.atan2(y - cy, x - cx),
                            (float) Math.atan2(lastY - cy, lastX - cx)));
                } else {
                    selected.x = clamp(selected.x + (x - lastX) / getWidth(), .01f, .99f);
                    selected.y = clamp(selected.y + (y - lastY) / getHeight(), .01f, .99f);
                }
                lastX = x; lastY = y;
                transformed = true;
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_POINTER_UP) {
            if (event.getPointerCount() == 2) {
                int remaining = 1 - event.getActionIndex();
                lastX = event.getX(remaining); lastY = event.getY(remaining);
                drag = selected != null; multi = false; handle = 0;
            } else { drag = false; multi = false; }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            drag = false; multi = false; handle = 0;
            if (transformed) transformFinished.run();
            transformed = false;
            getParent().requestDisallowInterceptTouchEvent(false);
            if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private static float angleDelta(float next, float previous) {
        float delta = next - previous;
        if (delta > Math.PI) delta -= (float) (2 * Math.PI);
        if (delta < -Math.PI) delta += (float) (2 * Math.PI);
        return delta;
    }

    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
}
