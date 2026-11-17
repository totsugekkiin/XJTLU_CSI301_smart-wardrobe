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
import java.util.function.IntConsumer;

final class CarePreview extends View {
    private final Bitmap bitmap;
    private final List<ImageAnalysis.SymbolHit> hits;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final IntConsumer openGroup;
    private final RectF image = new RectF();
    private float downX, downY;

    CarePreview(Context context, Bitmap bitmap, List<ImageAnalysis.SymbolHit> hits, IntConsumer openGroup) {
        super(context);
        this.bitmap = bitmap;
        this.hits = hits;
        this.openGroup = openGroup;
        setContentDescription("洗标照片 · " + hits.size() + " 个候选框");
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.drawColor(MainActivity.SAND);
        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        float w = scale * bitmap.getWidth(), h = scale * bitmap.getHeight();
        float x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
        image.set(x, y, x + w, y + h);
        canvas.drawBitmap(bitmap, null, image, paint);
        float density = getResources().getDisplayMetrics().density;
        paint.setTextSize(9 * density);
        for (int i = 0; i < hits.size(); i++) {
            RectF box = position(hits.get(i));
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(5);
            canvas.drawRect(box, paint);
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(2);
            canvas.drawRect(box, paint);
            paint.setStyle(Paint.Style.FILL);
            String index = "#" + (i + 1);
            float baseline = Math.max(12 * density, box.top - 3 * density);
            canvas.drawRect(box.left, baseline - 10 * density, box.left + paint.measureText(index) + 6 * density,
                    baseline + 2 * density, paint);
            paint.setColor(Color.WHITE);
            canvas.drawText(index, box.left + 3 * density, baseline, paint);
        }
    }

    private RectF position(ImageAnalysis.SymbolHit hit) {
        RectF box = hit.bounds();
        return new RectF(image.left + box.left * image.width(), image.top + box.top * image.height(),
                image.left + box.right * image.width(), image.top + box.bottom * image.height());
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (hits.isEmpty()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = event.getX(); downY = event.getY();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            float tolerance = getResources().getDisplayMetrics().density * 12;
            if (Math.hypot(downX - event.getX(), downY - event.getY()) > tolerance) return true;
            for (ImageAnalysis.SymbolHit hit : hits) {
                RectF box = position(hit);
                box.inset(-tolerance, -tolerance);
                if (box.contains(event.getX(), event.getY())) {
                    performClick();
                    openGroup.accept(hit.group());
                    break;
                }
            }
        }
        return true;
    }

    @Override public boolean performClick() { super.performClick(); return true; }
}
