package com.smartwardrobe.local;

import android.app.Dialog;
import android.animation.ValueAnimator;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Sheet extends Dialog {
    private final MainActivity ui;
    final LinearLayout content;
    private final LinearLayout footer;
    private final LinearLayout panel;
    private final LinearLayout heading;
    private final View headerRule;
    private final ScrollView scroll;
    private final boolean dismissible;
    private ValueAnimator dimAnimator;
    private float dim, downX, downY, downOffset;
    private boolean tracking, dragging, closing, finishing;
    private VelocityTracker velocity;

    Sheet(MainActivity ui, String title, boolean dismissible) {
        super(ui);
        this.ui = ui;
        this.dismissible = dismissible;
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

        heading = ui.row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = ui.editorial(title, 20);
        label.setAccessibilityHeading(true);
        heading.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        if (dismissible) {
            android.widget.FrameLayout close = new android.widget.FrameLayout(ui);
            close.addView(new NavIcon(ui, NavIcon.CLOSE, false, MainActivity.INK),
                    new android.widget.FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            ui.click(close, Color.TRANSPARENT, 0, 0, this::dismiss);
            close.setContentDescription("关闭");
            heading.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        }
        panel.addView(heading);
        ui.gap(panel, 10);
        headerRule = new View(ui);
        headerRule.setBackgroundColor(MainActivity.STROKE);
        panel.addView(headerRule, new LinearLayout.LayoutParams(-1, ui.dp(1)));

        content = ui.vertical();
        content.setPadding(0, ui.dp(8), 0, ui.dp(8));
        scroll = new ScrollView(ui) {
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

    void setPanelPadding(int padding) {
        int pixels = ui.dp(padding);
        panel.setPadding(pixels, pixels, pixels, pixels);
    }

    void hideHeaderRule() { headerRule.setVisibility(View.GONE); }

    void headerAction(String label, Runnable action) {
        TextView button = ui.ghost(label, action);
        button.setTextSize(13);
        heading.addView(button, Math.max(1, heading.getChildCount() - 1));
    }

    void option(String label, Runnable action) {
        option(label, null, action);
    }

    void destructiveOption(String label, Runnable action) {
        TextView button = ui.destructiveButton(label, action);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
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
        NavIcon arrow = new NavIcon(ui, NavIcon.BACK, false, MainActivity.INK);
        arrow.setRotation(180);
        row.addView(arrow, new LinearLayout.LayoutParams(ui.dp(16), ui.dp(16)));
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

    /** A quiet text link pinned to the very bottom of the panel, below the footer actions. */
    void bottomLink(String label, Runnable action) {
        LinearLayout box = ui.vertical();
        ui.rule(box);
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(48));
        row.addView(ui.text(label, 14, MainActivity.INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        NavIcon arrow = new NavIcon(ui, NavIcon.BACK, false, MainActivity.INK);
        arrow.setRotation(180);
        row.addView(arrow, new LinearLayout.LayoutParams(ui.dp(14), ui.dp(14)));
        ui.click(row, Color.WHITE, 0, 0, action);
        box.addView(row);
        panel.addView(box);
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
        closing = false;
        finishing = false;
        Window window = getWindow();
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setWindowAnimations(0);
        window.setDimAmount(0);
        panel.setTranslationY(Motion.enabled() ? ui.getResources().getDisplayMetrics().heightPixels : 0);
        window.setGravity(Gravity.BOTTOM);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setNavigationBarColor(Color.WHITE);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        super.show();
        window.setLayout(-1, -2);
        panel.requestFocus();
        panel.post(() -> {
            if (!isShowing() || closing) return;
            UiLanguage.localizeTree(panel);
            if (!Motion.enabled()) { panel.setTranslationY(0); setDim(.42f); return; }
            panel.setTranslationY(panel.getHeight());
            animateDim(.42f, 240);
            panel.animate().translationY(0).setStartDelay(24).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.8f)).start();
        });
    }

    private void setDim(float value) {
        dim = value;
        if (getWindow() != null) getWindow().setDimAmount(value);
    }

    private void animateDim(float target, int duration) {
        if (dimAnimator != null) dimAnimator.cancel();
        if (!Motion.enabled()) { setDim(target); return; }
        dimAnimator = ValueAnimator.ofFloat(dim, target);
        dimAnimator.setDuration(duration);
        dimAnimator.setInterpolator(new DecelerateInterpolator(1.6f));
        dimAnimator.addUpdateListener(value -> setDim((float) value.getAnimatedValue()));
        dimAnimator.start();
    }

    @Override public void dismiss() { close(false); }

    @Override public void cancel() { close(true); }

    private void close(boolean cancelled) {
        if (finishing) { super.dismiss(); return; }
        if (closing || !isShowing()) return;
        closing = true;
        panel.animate().cancel();
        Runnable finish = () -> {
            finishing = true;
            if (dimAnimator != null) dimAnimator.cancel();
            if (cancelled) super.cancel(); else super.dismiss();
        };
        if (!Motion.enabled()) { finish.run(); return; }
        animateDim(0, 190);
        panel.animate().translationY(panel.getHeight()).setStartDelay(0).setDuration(210)
                .setInterpolator(new DecelerateInterpolator(1.7f)).withEndAction(finish).start();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (velocity != null) velocity.recycle();
            velocity = VelocityTracker.obtain();
            downX = event.getRawX();
            downY = event.getRawY();
            int[] location = new int[2];
            panel.getLocationOnScreen(location);
            int[] scrollLocation = new int[2];
            scroll.getLocationOnScreen(scrollLocation);
            View touched = hit(panel, downX, downY);
            boolean editing = touched instanceof android.widget.EditText || touched instanceof android.widget.SeekBar
                    || touched instanceof KeepBrushView || touched instanceof CropView
                    || touched instanceof PhotoColorPicker || touched instanceof ClosetWheel;
            tracking = dismissible && !editing && downY >= location[1] && downY <= location[1] + panel.getHeight()
                    && (downY < scrollLocation[1] || !scroll.canScrollVertically(-1));
            dragging = false;
            downOffset = panel.getTranslationY();
        }
        if (velocity != null) velocity.addMovement(event);
        if (tracking && action == MotionEvent.ACTION_MOVE) {
            float dy = event.getRawY() - downY;
            float dx = Math.abs(event.getRawX() - downX);
            int slop = ViewConfiguration.get(ui).getScaledTouchSlop();
            int[] location = new int[2];
            scroll.getLocationOnScreen(location);
            boolean heading = downY < location[1];
            if (!dragging && dy > slop && dy > dx * 1.3f && (heading || !scroll.canScrollVertically(-1))) {
                dragging = true;
                panel.animate().cancel();
                if (dimAnimator != null) dimAnimator.cancel();
                downOffset = panel.getTranslationY();
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
            }
            if (dragging) {
                float offset = Math.max(0, downOffset + dy);
                panel.setTranslationY(offset);
                setDim(.42f * Math.max(0, 1 - offset / Math.max(1, panel.getHeight())));
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            boolean handled = dragging;
            if (dragging) {
                velocity.computeCurrentVelocity(1000);
                float distance = panel.getTranslationY();
                boolean dismiss = action == MotionEvent.ACTION_UP
                        && (distance > Math.max(ui.dp(72), panel.getHeight() * .25f)
                        || distance > ui.dp(24) && velocity.getYVelocity() > ui.dp(750));
                if (dismiss) cancel();
                else {
                    animateDim(.42f, 200);
                    panel.animate().translationY(0).setStartDelay(0).setDuration(Motion.enabled() ? 240 : 0)
                            .setInterpolator(new DecelerateInterpolator(1.8f)).start();
                }
            }
            dragging = false;
            tracking = false;
            if (velocity != null) { velocity.recycle(); velocity = null; }
            if (handled) return true;
        }
        return super.dispatchTouchEvent(event);
    }

    private View hit(View view, float x, float y) {
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        if (view.getVisibility() != View.VISIBLE || x < location[0] || y < location[1]
                || x > location[0] + view.getWidth() || y > location[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup group) {
            for (int i = group.getChildCount() - 1; i >= 0; i--) {
                View child = hit(group.getChildAt(i), x, y);
                if (child != null) return child;
            }
        }
        return view;
    }

    @Override protected void onStop() {
        panel.animate().cancel();
        if (dimAnimator != null) dimAnimator.cancel();
        if (velocity != null) { velocity.recycle(); velocity = null; }
        super.onStop();
    }
}
