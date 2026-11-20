package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.function.IntConsumer;

final class PhotoColorPicker extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF imageBounds = new RectF();
    private final float density;
    private final IntConsumer selected;
    private Bitmap photo;
    private float markX, markY;
    private Integer color;
    private boolean dragging;

    PhotoColorPicker(MainActivity ui, IntConsumer selected) {
        super(ui);
        this.selected = selected;
        density = getResources().getDisplayMetrics().density;
        setBackgroundColor(MainActivity.PAPER);
        setContentDescription("照片取色，点按或拖动选择颜色");
        setFocusable(true);
        setClickable(true);
    }

    void showPhoto(Bitmap bitmap) {
        photo = bitmap;
        color = null;
        fitImage();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        fitImage();
    }

    private void fitImage() {
        if (photo == null || getWidth() == 0 || getHeight() == 0) return;
        float scale = Math.min((float) getWidth() / photo.getWidth(), (float) getHeight() / photo.getHeight());
        float width = photo.getWidth() * scale, height = photo.getHeight() * scale;
        float left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
        imageBounds.set(left, top, left + width, top + height);
    }

    private void pick(float x, float y) {
        if (photo == null || !imageBounds.contains(x, y)) return;
        int px = Math.min(photo.getWidth() - 1, (int) ((x - imageBounds.left) * photo.getWidth() / imageBounds.width()));
        int py = Math.min(photo.getHeight() - 1, (int) ((y - imageBounds.top) * photo.getHeight() / imageBounds.height()));
        Integer sample = GarmentColors.samplePhoto(photo, px, py);
        if (sample == null) return;
        color = sample;
        markX = x;
        markY = y;
        selected.accept(sample);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!imageBounds.contains(event.getX(), event.getY())) return false;
                dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                pick(event.getX(), event.getY());
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) pick(event.getX(), event.getY());
                return dragging;
            case MotionEvent.ACTION_UP:
                if (!dragging) return false;
                pick(event.getX(), event.getY());
                performClick();
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(false);
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() {
        super.performClick();
        if (!dragging) pick(imageBounds.centerX(), imageBounds.centerY());
        return true;
    }

    @Override protected void onDraw(Canvas canvas) {
        if (photo == null) return;
        canvas.drawBitmap(photo, null, imageBounds, paint);
        if (color == null) return;
        paint.setColor(Color.BLACK);
        canvas.drawCircle(markX, markY, density * 12, paint);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(markX, markY, density * 10, paint);
        paint.setColor(color);
        canvas.drawCircle(markX, markY, density * 8, paint);
    }
}
