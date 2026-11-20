package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** Five care families, drawn on a 28-unit canvas with a 1.6-unit stroke. */
final class CareSymbolView extends View {
    private final int group;
    private String meaning;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    CareSymbolView(Context context, int group, String meaning) {
        super(context);
        this.group = group;
        this.meaning = meaning;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1.6f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void meaning(String value) { meaning = value; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        canvas.scale(getWidth() / 28f, getHeight() / 28f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(meaning.isEmpty() ? 0xffcccccc : 0xff111111);
        switch (group) {
            case 0 -> {
                line(canvas, 3, 6, 6, 22, 22, 22, 25, 6);
                path.reset(); path.moveTo(4, 10);
                for (int x = 4; x < 24; x += 5) path.rQuadTo(2.5f, x % 2 == 0 ? -3 : 3, 5, 0);
                canvas.drawPath(path, paint);
                if (meaning.contains("柔洗")) canvas.drawLine(7, 25, 21, 25, paint);
            }
            case 1 -> {
                line(canvas, 14, 3, 26, 24, 2, 24, 14, 3);
                if (meaning.contains("氧漂")) { line(canvas, 10, 23, 20, 14); line(canvas, 16, 23, 23, 17); }
            }
            case 2 -> {
                canvas.drawRect(3, 3, 25, 25, paint);
                if (meaning.contains("滚筒")) canvas.drawCircle(14, 14, 8, paint);
                else if (meaning.contains("平铺")) canvas.drawLine(7, 14, 21, 14, paint);
                else if (meaning.contains("悬挂") || meaning.contains("滴水")) {
                    canvas.drawLine(14, 6, 14, 22, paint);
                    if (meaning.contains("滴水")) { canvas.drawLine(9, 6, 9, 22, paint); canvas.drawLine(19, 6, 19, 22, paint); }
                }
                if (meaning.contains("阴凉")) { line(canvas, 3, 12, 12, 3); line(canvas, 3, 17, 17, 3); }
            }
            case 3 -> {
                path.reset(); path.moveTo(3, 22); path.quadTo(5, 12, 12, 12);
                path.lineTo(22, 12); path.lineTo(25, 22); path.close(); canvas.drawPath(path, paint);
                line(canvas, 10, 8, 20, 8, 22, 12);
                int dots = meaning.contains("高温") ? 3 : meaning.contains("中温") ? 2 : meaning.contains("低温") ? 1 : 0;
                paint.setStyle(Paint.Style.FILL);
                for (int i = 0; i < dots; i++) canvas.drawCircle(14 + (i - (dots - 1) / 2f) * 4, 17, 1, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
            case 4 -> {
                canvas.drawCircle(14, 14, 11, paint);
                if (meaning.contains(" P")) {
                    line(canvas, 11, 20, 11, 8, 17, 8, 19, 10, 19, 13, 17, 15, 11, 15);
                } else if (meaning.contains(" F")) { line(canvas, 11, 20, 11, 8, 19, 8); line(canvas, 11, 14, 17, 14); }
                else if (meaning.contains(" W") || meaning.contains("湿洗")) line(canvas, 7, 9, 10, 20, 14, 13, 18, 20, 21, 9);
                else if (meaning.contains(" A")) { line(canvas, 9, 20, 14, 8, 19, 20); line(canvas, 11, 16, 17, 16); }
                if (meaning.contains("柔和")) canvas.drawLine(7, 27, 21, 27, paint);
            }
        }
        if (meaning.startsWith("不可")) { line(canvas, 3, 3, 25, 25); line(canvas, 25, 3, 3, 25); }
        canvas.restoreToCount(save);
    }

    private void line(Canvas canvas, int... xy) {
        path.reset(); path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        canvas.drawPath(path, paint);
    }
}
