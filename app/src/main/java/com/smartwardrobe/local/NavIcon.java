package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavIcon extends View {
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int icon;

    NavIcon(Context context, int icon, boolean active) {
        super(context);
        this.icon = icon;
        stroke.setColor(active ? MainActivity.INK : MainActivity.MUTED);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(active ? 1.9f : 1.45f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        switch (icon) {
            case 0 -> {
                line(canvas, 4, 4, 8, 2, 10, 5, 14, 5, 16, 2, 20, 4, 23, 8, 19, 11,
                        17, 9, 17, 21, 7, 21, 7, 9, 5, 11, 1, 8, 4, 4);
            }
            case 1 -> {
                path.reset();
                path.moveTo(8, 5);
                path.cubicTo(6, 9, 2, 11, 2, 15);
                path.cubicTo(2, 22, 14, 22, 14, 15);
                path.cubicTo(14, 11, 10, 9, 8, 5);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(14, 3);
                path.cubicTo(17, 8, 22, 11, 22, 15);
                path.cubicTo(22, 20, 17, 22, 14, 20);
                canvas.drawPath(path, stroke);
            }
            case 2 -> {
                line(canvas, 2, 7, 12, 2, 22, 7, 12, 12, 2, 7);
                line(canvas, 2, 12, 12, 17, 22, 12);
                line(canvas, 2, 17, 12, 22, 22, 17);
            }
            default -> {
                canvas.drawRoundRect(3, 5, 21, 22, 2, 2, stroke);
                canvas.drawLine(3, 10, 21, 10, stroke);
                canvas.drawLine(8, 2, 8, 7, stroke);
                canvas.drawLine(16, 2, 16, 7, stroke);
            }
        }
    }

    private void line(Canvas canvas, int... xy) {
        path.reset();
        path.moveTo(xy[0], xy[1]);
        for (int i = 2; i < xy.length; i += 2) path.lineTo(xy[i], xy[i + 1]);
        canvas.drawPath(path, stroke);
    }
}
