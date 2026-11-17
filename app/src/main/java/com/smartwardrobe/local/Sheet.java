package com.smartwardrobe.local;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Sheet extends Dialog {
    private final MainActivity ui;
    final LinearLayout content;
    private final LinearLayout footer;
    private final LinearLayout panel;

    Sheet(MainActivity ui, String title, boolean dismissible) {
        super(ui);
        this.ui = ui;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(dismissible);
        setCanceledOnTouchOutside(dismissible);
        panel = ui.vertical();
        android.graphics.drawable.GradientDrawable background = ui.shape(Color.WHITE, 0, 0);
        float radius = ui.dp(22);
        background.setCornerRadii(new float[]{radius, radius, radius, radius, 0, 0, 0, 0});
        panel.setBackground(background);
        panel.setPadding(ui.dp(20), ui.dp(10), ui.dp(20), ui.dp(14));
        panel.setFocusableInTouchMode(true);

        if (dismissible) {
            View handle = new View(ui);
            handle.setBackground(ui.shape(0xffcccccc, 2, 0));
            LinearLayout.LayoutParams handleParams = new LinearLayout.LayoutParams(ui.dp(32), ui.dp(3));
            handleParams.gravity = Gravity.CENTER_HORIZONTAL;
            panel.addView(handle, handleParams);
        }
        LinearLayout heading = ui.row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.editorial(title, 20);
        label.setAccessibilityHeading(true);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (dismissible) {
            TextView close = ui.ghost("×", this::dismiss);
            close.setTextSize(24);
            close.setContentDescription("关闭");
            heading.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        panel.addView(heading);
        ui.gap(panel, 10);
        ui.rule(panel);

        content = ui.vertical();
        content.setPadding(0, ui.dp(8), 0, ui.dp(8));
        ScrollView scroll = new ScrollView(ui) {
            @Override protected void onMeasure(int w, int h) {
                int cap = Math.round(getResources().getDisplayMetrics().heightPixels * .62f);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(Math.min(MeasureSpec.getSize(h), cap), MeasureSpec.AT_MOST));
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, -2, 1f));

        footer = ui.row();
        footer.setBaselineAligned(false);
        footer.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        footer.setVisibility(View.GONE);
        panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(panel);
    }

    void option(String label, Runnable action) {
        option(label, null, action);
    }

    void option(String label, Integer color, Runnable action) {
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(54));
        if (color != null) {
            row.addView(ui.colorDot(color, 22));
            ui.space(row, 10);
        }
        TextView text = ui.text(label, 16, Color.BLACK, false);
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(ui.text("›", 22, Color.BLACK, false));
        ui.click(row, Color.WHITE, 0, 0, action);
        content.addView(row);
        ui.rule(content);
    }

    void description(String text) {
        TextView body = ui.text(text, 15, MainActivity.MUTED, false);
        body.setPadding(0, ui.dp(5), 0, ui.dp(8));
        content.addView(body);
    }

    void button(String label, boolean primary, Runnable action) {
        addFooterButton(primary ? ui.pill(label, true, action) : ui.ghost(label, action));
    }

    void destructiveButton(String label, Runnable action) {
        addFooterButton(ui.destructiveButton(label, action));
    }

    private void addFooterButton(TextView button) {
        footer.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = ui.dp(8);
        footer.addView(button, params);
    }

    @Override public void show() {
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(.42f);
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        super.show();
        window.setLayout(-1, -2);
        panel.requestFocus();
    }
}
