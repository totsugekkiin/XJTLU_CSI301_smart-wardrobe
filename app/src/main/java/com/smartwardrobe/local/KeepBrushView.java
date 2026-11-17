package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

final class KeepBrushView extends View {
    record Cutout(Bitmap image, Point origin) {}
    private record Stroke(Path path, float x, float y, float radius) {}

    private final Bitmap original;
    private final Cutout base;
    private final RectF frame = new RectF();
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint brushPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke active;
    private Cutout preview;
    private boolean previewing;
    private int pointer = -1;
    private float scale, brushSize;

    KeepBrushView(Context context, Bitmap original, Cutout base) {
        super(context);
        this.original = original;
        this.base = base;
        setContentDescription("在原图上涂抹保留区域");
        brushPaint.setStrokeCap(Paint.Cap.ROUND);
        brushPaint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setBrushSize(float pixels) {
        brushSize = pixels;
    }

    void undo() {
        if (!strokes.isEmpty()) strokes.remove(strokes.size() - 1);
        if (previewing) preview = result();
        invalidate();
    }

    void togglePreview() {
        previewing = !previewing;
        preview = previewing ? result() : null;
        setContentDescription(previewing ? "处理图预览" : "在原图上涂抹保留区域");
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        scale = Math.min((float) w / original.getWidth(), (float) h / original.getHeight());
        float width = original.getWidth() * scale, height = original.getHeight() * scale;
        frame.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(0xffe3e3e3);
        canvas.save();
        canvas.translate(frame.left, frame.top);
        canvas.scale(scale, scale);
        canvas.clipRect(0, 0, original.getWidth(), original.getHeight());
        if (previewing) {
            Cutout shown = preview == null ? base : preview;
            if (shown != null) canvas.drawBitmap(shown.image(), null, bounds(shown), imagePaint);
        } else {
            canvas.drawBitmap(original, null, new Rect(0, 0, original.getWidth(), original.getHeight()), imagePaint);
            drawStrokes(canvas, 0x9024b47e);
        }
        canvas.restore();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (previewing || scale <= 0) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!frame.contains(event.getX(), event.getY())) return false;
            pointer = event.getPointerId(0);
            float x = imageX(event.getX()), y = imageY(event.getY());
            Path path = new Path();
            path.moveTo(x, y);
            active = new Stroke(path, x, y, brushSize / (2 * scale));
            strokes.add(active);
            getParent().requestDisallowInterceptTouchEvent(true);
        } else if (active != null) {
            int index = event.findPointerIndex(pointer);
            if (action == MotionEvent.ACTION_CANCEL || index < 0) {
                strokes.remove(strokes.size() - 1);
                finishStroke();
            } else if (action == MotionEvent.ACTION_MOVE) {
                for (int i = 0; i < event.getHistorySize(); i++) {
                    active.path().lineTo(imageX(event.getHistoricalX(index, i)), imageY(event.getHistoricalY(index, i)));
                }
                active.path().lineTo(imageX(event.getX(index)), imageY(event.getY(index)));
            } else if (action == MotionEvent.ACTION_UP
                    || (action == MotionEvent.ACTION_POINTER_UP && index == event.getActionIndex())) {
                active.path().lineTo(imageX(event.getX(index)), imageY(event.getY(index)));
                finishStroke();
                performClick();
            }
        }
        invalidate();
        return true;
    }

    @Override public boolean performClick() {
        super.performClick();
        return true;
    }

    private void finishStroke() {
        active = null;
        pointer = -1;
        getParent().requestDisallowInterceptTouchEvent(false);
    }

    private float imageX(float x) {
        return Math.max(0, Math.min(original.getWidth(), (x - frame.left) / scale));
    }

    private float imageY(float y) {
        return Math.max(0, Math.min(original.getHeight(), (y - frame.top) / scale));
    }

    Cutout result() {
        if (strokes.isEmpty()) return null;
        Rect painted = new Rect();
        RectF pathBounds = new RectF();
        for (Stroke stroke : strokes) {
            if (stroke.path().isEmpty()) pathBounds.set(stroke.x(), stroke.y(), stroke.x(), stroke.y());
            else stroke.path().computeBounds(pathBounds, true);
            float pad = stroke.radius() + 1;
            painted.union((int) Math.floor(Math.min(pathBounds.left, stroke.x()) - pad),
                    (int) Math.floor(Math.min(pathBounds.top, stroke.y()) - pad),
                    (int) Math.ceil(Math.max(pathBounds.right, stroke.x()) + pad),
                    (int) Math.ceil(Math.max(pathBounds.bottom, stroke.y()) + pad));
        }
        if (!painted.intersect(0, 0, original.getWidth(), original.getHeight())) return null;

        Bitmap patch = Bitmap.createBitmap(painted.width(), painted.height(), Bitmap.Config.ARGB_8888);
        Canvas patchCanvas = new Canvas(patch);
        patchCanvas.translate(-painted.left, -painted.top);
        drawStrokes(patchCanvas, 0xffffffff);
        Paint sourcePaint = new Paint();
        sourcePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        patchCanvas.drawBitmap(original, null, new Rect(0, 0, original.getWidth(), original.getHeight()), sourcePaint);

        Rect area = new Rect(painted);
        if (base != null) area.union(bounds(base));
        Bitmap image = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.translate(-area.left, -area.top);
        if (base != null) canvas.drawBitmap(base.image(), null, bounds(base), imagePaint);
        canvas.drawBitmap(patch, null, painted, imagePaint);
        patch.recycle();
        return new Cutout(image, new Point(area.left, area.top));
    }

    private void drawStrokes(Canvas canvas, int color) {
        brushPaint.setColor(color);
        for (Stroke stroke : strokes) {
            brushPaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(stroke.x(), stroke.y(), stroke.radius(), brushPaint);
            brushPaint.setStyle(Paint.Style.STROKE);
            brushPaint.setStrokeWidth(2 * stroke.radius());
            canvas.drawPath(stroke.path(), brushPaint);
        }
    }

    private static Rect bounds(Cutout cutout) {
        Point point = cutout.origin();
        return new Rect(point.x, point.y, point.x + cutout.image().getWidth(), point.y + cutout.image().getHeight());
    }
}
