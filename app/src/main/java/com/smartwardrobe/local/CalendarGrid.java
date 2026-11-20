package com.smartwardrobe.local;

import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.function.Consumer;
import java.util.function.Predicate;

final class CalendarGrid {
    static void addTo(MainActivity ui, LinearLayout parent, LinearLayout grid) {
        int width = ui.getResources().getConfiguration().screenWidthDp;
        int extra = Math.min(20, Math.max(0, (376 - width + 1) / 2));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(-ui.dp(extra), 0, -ui.dp(extra), 0);
        if (extra > 0) parent.setClipToPadding(false);
        parent.addView(grid, params);
    }

    static void populate(MainActivity ui, LinearLayout grid, YearMonth month, LocalDate selected,
                         LocalDate maximum, Predicate<LocalDate> recorded, Consumer<LocalDate> pick) {
        grid.removeAllViews();
        LinearLayout weekdays = ui.row();
        for (String day : new String[]{"一", "二", "三", "四", "五", "六", "日"}) {
            TextView label = ui.text(day, 12, MainActivity.MUTED, true);
            label.setGravity(Gravity.CENTER);
            weekdays.addView(label, new LinearLayout.LayoutParams(0, ui.dp(30), 1));
        }
        grid.addView(weekdays);
        int offset = month.atDay(1).getDayOfWeek().getValue() - 1;
        for (int start = 1 - offset; start <= month.lengthOfMonth(); start += 7) {
            LinearLayout week = ui.row();
            week.setBaselineAligned(false);
            for (int column = 0; column < 7; column++) {
                int number = start + column;
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
                if (number < 1 || number > month.lengthOfMonth()) {
                    week.addView(new View(ui), p);
                    continue;
                }
                LocalDate date = month.atDay(number);
                boolean hasRecord = recorded.test(date);
                boolean disabled = maximum != null && date.isAfter(maximum);
                String display = Integer.toString(number);
                TextView cell = date.equals(selected) ? ui.pill(display, true, () -> pick.accept(date))
                        : ui.ghost(display, () -> pick.accept(date));
                if (date.equals(selected)) ui.click(cell, MainActivity.INK, 24, 0, () -> pick.accept(date));
                cell.setPadding(0, 0, 0, 0);
                if (hasRecord) {
                    android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
                    dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    dot.setColor(date.equals(selected) ? android.graphics.Color.WHITE : MainActivity.INK);
                    dot.setBounds(0, 0, ui.dp(3), ui.dp(3));
                    cell.setCompoundDrawables(null, null, null, dot);
                }
                cell.setMinHeight(0);
                cell.setContentDescription(date + (hasRecord ? " 有穿着记录" : ""));
                cell.setSelected(date.equals(selected));
                cell.setEnabled(!disabled);
                week.addView(cell, p);
            }
            grid.addView(week);
        }
    }
}
