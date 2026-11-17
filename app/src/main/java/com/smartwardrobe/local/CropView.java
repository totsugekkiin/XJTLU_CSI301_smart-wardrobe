package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class CropView extends View {
    record Selection(Bitmap image, int left, int top) {}
    private final Bitmap image;
    private final RectF destination = new RectF(), selection = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float startX, startY;

    CropView(Context context, Bitmap image) {
        super(context);
        this.image = image;
        setContentDescription("拖动框选衣物");
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float scale = Math.min((float) width / image.getWidth(), (float) height / image.getHeight());
        float w = image.getWidth() * scale, h = image.getHeight() * scale;
        destination.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2);
        selection.set(destination);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xff000000);
        canvas.drawBitmap(image, null, destination, paint);
        paint.setColor(0xa8000000);
        canvas.drawRect(destination.left, destination.top, destination.right, selection.top, paint);
        canvas.drawRect(destination.left, selection.bottom, destination.right, destination.bottom, paint);
        canvas.drawRect(destination.left, selection.top, selection.left, selection.bottom, paint);
        canvas.drawRect(selection.right, selection.top, destination.right, selection.bottom, paint);
        paint.setColor(0xff000000);
        paint.setStrokeWidth(6);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(selection, paint);
        paint.setColor(0xffffffff);
        paint.setStrokeWidth(3);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawRect(selection, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        float x = Math.max(destination.left, Math.min(destination.right, event.getX()));
        float y = Math.max(destination.top, Math.min(destination.bottom, event.getY()));
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            startX = x;
            startY = y;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN || event.getActionMasked() == MotionEvent.ACTION_MOVE
                || event.getActionMasked() == MotionEvent.ACTION_UP) {
            selection.set(Math.min(startX, x), Math.min(startY, y), Math.max(startX, x), Math.max(startY, y));
            invalidate();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            getParent().requestDisallowInterceptTouchEvent(false);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    Selection result() {
        if (selection.width() < 10 || selection.height() < 10) return null;
        int l = Math.max(0, (int) ((selection.left - destination.left) / destination.width() * image.getWidth()));
        int t = Math.max(0, (int) ((selection.top - destination.top) / destination.height() * image.getHeight()));
        int r = Math.min(image.getWidth(), (int) Math.ceil((selection.right - destination.left) / destination.width() * image.getWidth()));
        int b = Math.min(image.getHeight(), (int) Math.ceil((selection.bottom - destination.top) / destination.height() * image.getHeight()));
        return new Selection(Bitmap.createBitmap(image, l, t, r - l, b - t), l, t);
    }
}
