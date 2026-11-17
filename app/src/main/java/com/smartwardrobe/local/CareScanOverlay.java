package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.Collections;
import java.util.List;

/** Draws in preview coordinates. Detection boxes are normalized to the same central scan area. */
final class CareScanOverlay extends View {
    static final float WIDTH = .88f, HEIGHT = .76f;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF guide = new RectF(), box = new RectF();
    private final float density;
    private List<ImageAnalysis.SymbolHit> hits = Collections.emptyList();
    private float focusX, focusY;
    private boolean focusVisible;
    private boolean focused;

    CareScanOverlay(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
    }

    void setHits(List<ImageAnalysis.SymbolHit> hits) {
        this.hits = hits;
        invalidate();
    }

    void focus(float x, float y) {
        focusX = x;
        focusY = y;
        focusVisible = true;
        focused = false;
        invalidate();
    }

    void focusResult(boolean success) {
        focused = success;
        invalidate();
    }

    void hideFocusMarker() {
        focusVisible = false;
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
        float side = w * (1 - WIDTH) / 2;
        float top = h * (1 - HEIGHT) / 2;
        guide.set(side, top, w - side, h - top);
    }

    @Override protected void onDraw(Canvas canvas) {
        stroke.setColor(0x50000000);
        stroke.setStrokeWidth(density * 2.5f);
        drawCorners(canvas);
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(density * 1.5f);
        drawCorners(canvas);

        for (ImageAnalysis.SymbolHit hit : hits) {
            RectF bounds = hit.bounds();
            box.set(guide.left + guide.width() * bounds.left, guide.top + guide.height() * bounds.top,
                    guide.left + guide.width() * bounds.right, guide.top + guide.height() * bounds.bottom);
            stroke.setColor(0x78000000);
            stroke.setStrokeWidth(density * 2.5f);
            canvas.drawRoundRect(box, density * 3, density * 3, stroke);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(density * 1.5f);
            canvas.drawRoundRect(box, density * 3, density * 3, stroke);
        }
        if (focusVisible) {
            float radius = density * (focused ? 17 : 23);
            stroke.setColor(Color.BLACK);
            stroke.setStrokeWidth(density * 3);
            canvas.drawCircle(focusX, focusY, radius, stroke);
            stroke.setColor(Color.WHITE);
            stroke.setStrokeWidth(density * 1.5f);
            canvas.drawCircle(focusX, focusY, radius, stroke);
        }
    }

    private void drawCorners(Canvas canvas) {
        float length = density * 14;
        canvas.drawLine(guide.left, guide.top, guide.left + length, guide.top, stroke);
        canvas.drawLine(guide.left, guide.top, guide.left, guide.top + length, stroke);
        canvas.drawLine(guide.right, guide.top, guide.right - length, guide.top, stroke);
        canvas.drawLine(guide.right, guide.top, guide.right, guide.top + length, stroke);
        canvas.drawLine(guide.left, guide.bottom, guide.left + length, guide.bottom, stroke);
        canvas.drawLine(guide.left, guide.bottom, guide.left, guide.bottom - length, stroke);
        canvas.drawLine(guide.right, guide.bottom, guide.right - length, guide.bottom, stroke);
        canvas.drawLine(guide.right, guide.bottom, guide.right, guide.bottom - length, stroke);
    }
}
