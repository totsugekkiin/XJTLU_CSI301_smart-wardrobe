package com.smartwardrobe.local;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/** Wraps colour chips onto as many rows as the available width needs. */
final class FlowLayout extends ViewGroup {
    private final int gap;

    FlowLayout(Context context) {
        super(context);
        gap = Math.round(8 * getResources().getDisplayMetrics().density);
    }

    @Override protected LayoutParams generateDefaultLayoutParams() {
        return new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int available = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int x = 0, y = 0, rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(0, available), MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            if (x > 0 && x + child.getMeasuredWidth() > available) {
                y += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            x += child.getMeasuredWidth() + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight());
        }
        setMeasuredDimension(resolveSize(available + getPaddingLeft() + getPaddingRight(), widthSpec),
                resolveSize(y + rowHeight + getPaddingTop() + getPaddingBottom(), heightSpec));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = right - left - getPaddingLeft() - getPaddingRight();
        int x = 0, y = getPaddingTop(), rowHeight = 0;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() == GONE) continue;
            int width = child.getMeasuredWidth(), height = child.getMeasuredHeight();
            if (x > 0 && x + width > available) {
                y += rowHeight + gap;
                x = 0;
                rowHeight = 0;
            }
            int start = rtl ? getWidth() - getPaddingRight() - x - width : getPaddingLeft() + x;
            child.layout(start, y, start + width, y + height);
            x += width + gap;
            rowHeight = Math.max(rowHeight, height);
        }
    }
}
