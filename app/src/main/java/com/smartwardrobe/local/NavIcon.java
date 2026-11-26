package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

final class NavIcon extends View {
    static final int ARCHIVE = 4;
    static final int FAVORITE = 5;
    static final int CHECK = 6;
    static final int CAMERA = 7;
    static final int PHOTOS = 8;
    static final int SHOPPING = 9;
    static final int EDIT = 10;
    static final int BACK = 11, MORE = 12, COMPARE = 13, ADJUST = 14, CLOSE = 15, PLUS = 16, TICK = 17;
    static final int LAYER_UP = 18, LAYER_DOWN = 19, REPLACE = 20;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final int icon;
    private final boolean active;

    NavIcon(Context context, int icon, boolean active) {
        super(context);
        this.icon = icon;
        this.active = active;
        stroke.setColor(active ? MainActivity.INK : MainActivity.MUTED);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(active ? 1.9f : 1.45f);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    NavIcon(Context context, int icon, boolean active, int color) {
        this(context, icon, active);
        stroke.setColor(color);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.scale(getWidth() / 24f, getHeight() / 24f);
        switch (icon) {
            case BACK -> line(canvas, 15, 4, 7, 12, 15, 20);
            case MORE -> {
                stroke.setStyle(Paint.Style.FILL);
                for (int y : new int[]{5, 12, 19}) canvas.drawCircle(12, y, 1.5f, stroke);
            }
            case COMPARE -> {
                canvas.drawRoundRect(3, 4, 21, 20, 1.5f, 1.5f, stroke);
                line(canvas, 12, 2, 12, 22);
                line(canvas, 5, 16, 8, 12, 10, 14);
                canvas.drawCircle(17, 8, 1, stroke);
            }
            case ADJUST -> {
                for (int y : new int[]{5, 12, 19}) canvas.drawLine(3, y, 21, y, stroke);
                canvas.drawCircle(8, 5, 2, stroke);
                canvas.drawCircle(16, 12, 2, stroke);
                canvas.drawCircle(10, 19, 2, stroke);
            }
            case CLOSE -> { line(canvas, 6, 6, 18, 18); line(canvas, 18, 6, 6, 18); }
            case PLUS -> { line(canvas, 5, 12, 19, 12); line(canvas, 12, 5, 12, 19); }
            case LAYER_UP -> { line(canvas, 4, 16, 12, 10, 20, 16); line(canvas, 4, 21, 12, 15, 20, 21); line(canvas, 4, 11, 12, 5, 20, 11); }
            case LAYER_DOWN -> { line(canvas, 4, 3, 12, 9, 20, 3); line(canvas, 4, 8, 12, 14, 20, 8); line(canvas, 4, 13, 12, 19, 20, 13); }
            case REPLACE -> { line(canvas, 5, 8, 19, 8, 16, 5); line(canvas, 19, 8, 16, 11); line(canvas, 19, 16, 5, 16, 8, 13); line(canvas, 5, 16, 8, 19); }
            case TICK -> line(canvas, 4, 12, 9, 17, 20, 6);
            case CAMERA -> {
                line(canvas, 3, 6, 7, 6, 9, 3, 15, 3, 17, 6, 21, 6, 21, 20, 3, 20, 3, 6);
                canvas.drawCircle(12, 13, 4, stroke);
            }
            case PHOTOS -> {
                line(canvas, 3, 16, 3, 3, 17, 3);
                canvas.drawRoundRect(7, 7, 21, 21, 1.5f, 1.5f, stroke);
                canvas.drawCircle(16, 11, 1, stroke);
                line(canvas, 7, 17, 11, 13, 15, 17, 17, 15, 21, 19);
            }
            case SHOPPING -> {
                canvas.drawRoundRect(4, 7, 20, 21, 1.5f, 1.5f, stroke);
                path.reset();
                path.moveTo(8, 9);
                path.lineTo(8, 6);
                path.cubicTo(8, 1, 16, 1, 16, 6);
                path.lineTo(16, 9);
                canvas.drawPath(path, stroke);
            }
            case EDIT -> {
                line(canvas, 14, 4, 4, 14, 3, 21, 10, 20, 20, 10, 14, 4);
                line(canvas, 14, 4, 17, 1, 23, 7, 20, 10);
                line(canvas, 4, 14, 10, 20);
            }
            case CHECK -> {
                stroke.setColor(android.graphics.Color.WHITE);
                stroke.setStrokeWidth(2.3f);
                line(canvas, 4, 12, 9, 17, 20, 6);
            }
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
            case ARCHIVE -> {
                canvas.drawRoundRect(2, 3, 22, 8, 1, 1, stroke);
                line(canvas, 4, 8, 4, 21, 20, 21, 20, 8);
                canvas.drawLine(9, 12, 15, 12, stroke);
            }
            case FAVORITE -> {
                path.reset();
                path.moveTo(12, 21);
                path.cubicTo(10, 19, 3, 14, 3, 9);
                path.cubicTo(3, 3, 9, 2, 12, 7);
                path.cubicTo(15, 2, 21, 3, 21, 9);
                path.cubicTo(21, 14, 14, 19, 12, 21);
                path.close();
                stroke.setColor(MainActivity.INK);
                stroke.setStyle(active ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawPath(path, stroke);
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
