package com.smartwardrobe.local;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.OnBackPressedCallback;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Presentation-only tour. Anchors refer to live views; no sample garments are stored. */
final class OnboardingGuide implements AutoCloseable {
    private static final int[] TABS = {0, 0, 1, 1, 3, 4};
    private static final String[] TITLES = {"录入单品", "档案与编辑", "智能洗护", "换季唤醒", "灵感穿搭", "穿着日历"};
    private static final String[] BODIES = {
            "拍摄或导入衣物图片，自动抠图并建立专属数字档案。",
            "点击单品进入详情，可随时修改色彩比例、材质与洗标。",
            "按水洗标自动归类待洗衣物，实时追踪衣橱洁净状态。",
            "结合当地气温与收纳时长，一键筛选需过水或唤醒的当季衣物。",
            "自由组合单品构建造型，沉淀你的日常搭配方案。",
            "记录每日上身造型，自动统计每件单品的穿着频率。"
    };
    private static final PathInterpolator EASE = new PathInterpolator(.33f, 1f, .68f, 1f);
    private final MainActivity ui;
    private final FrameLayout host;
    private final SharedPreferences prefs;
    private final Map<String, View> targets = new HashMap<>();
    private final OnBackPressedCallback back;
    private final View.OnLayoutChangeListener viewport;
    private ViewTreeObserver.OnPreDrawListener ready;
    private Spotlight overlay;
    private boolean pending, active, busy, closed, foreground;
    private int statusBarColor, navigationBarColor;
    private int step, generation;
    private final Runnable launch = this::launchWhenReady;

    OnboardingGuide(MainActivity ui, FrameLayout host, Bundle state) {
        this.ui = ui;
        this.host = host;
        prefs = ui.getSharedPreferences("onboarding", MainActivity.MODE_PRIVATE);
        boolean restoring = state != null && state.getBoolean("onboarding_active", false);
        pending = restoring || !prefs.getBoolean("has_completed_onboarding", false);
        step = restoring ? Math.max(0, Math.min(5, state.getInt("onboarding_step", 0))) : 0;
        back = new OnBackPressedCallback(false) {
            @Override public void handleOnBackPressed() { if (!busy && step > 0) showStep(step - 1); }
        };
        ui.getOnBackPressedDispatcher().addCallback(ui, back);
        viewport = (view, l, t, r, b, ol, ot, or, ob) -> {
            if (active && (r - l != or - ol || b - t != ob - ot)) {
                resizeOverlay();
                afterLayout(generation, () -> locate(false));
            }
        };
        host.addOnLayoutChangeListener(viewport);
    }

    boolean running() { return pending || active; }
    void register(String key, View target) { targets.put(key, target); }
    void pageChanging() { targets.clear(); }
    void pageShown() {
        if (active && overlay != null) {
            ui.coachPageAccessibility(false);
            overlay.bringToFront();
        }
    }

    void schedule() {
        if (!pending || closed) return;
        host.removeCallbacks(launch);
        afterLayout(generation, () -> host.postDelayed(launch, 400));
    }

    void pause() { foreground = false; host.removeCallbacks(launch); removeReadyListener(); }
    void resume() {
        foreground = true;
        if (pending) schedule();
        else if (active) afterLayout(generation, () -> locate(false));
    }

    void replay() {
        if (closed || running()) return;
        step = 0;
        pending = true;
        schedule();
    }

    private void launchWhenReady() {
        if (closed || !pending || !foreground || ui.isFinishing()) return;
        if (!host.hasWindowFocus()) { host.postDelayed(launch, 120); return; }
        pending = false;
        active = true;
        statusBarColor = ui.getWindow().getStatusBarColor();
        navigationBarColor = ui.getWindow().getNavigationBarColor();
        ui.getWindow().setStatusBarColor(0xff535353);
        ui.getWindow().setNavigationBarColor(0xff535353);
        ui.closet.prepareOnboarding();
        overlay = new Spotlight();
        back.remove();
        ui.getOnBackPressedDispatcher().addCallback(ui, back);
        host.setClipToPadding(false);
        host.addView(overlay);
        resizeOverlay();
        showStep(step);
    }

    private void resizeOverlay() {
        if (overlay == null) return;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(host.getWidth(), host.getHeight());
        params.leftMargin = -host.getPaddingLeft();
        params.topMargin = -host.getPaddingTop();
        params.gravity = Gravity.TOP | Gravity.LEFT;
        overlay.setLayoutParams(params);
    }

    private void showStep(int next) {
        if (!active || closed) return;
        busy = true;
        step = next;
        int ticket = ++generation;
        overlay.setControlsEnabled(false);
        if (step == 5) ui.outfits.prepareOnboardingCalendar();
        if (ui.tab != TABS[step] || step == 0) ui.openTab(TABS[step]);
        ui.prepareCoachPage();
        ui.coachPageAccessibility(false);
        back.setEnabled(true); // Registered after the page callback, and takes precedence.
        overlay.bringToFront();
        overlay.makeCard();
        afterLayout(ticket, () -> { ui.prepareCoachPage(); locate(true); });
    }

    private void afterLayout(int ticket, Runnable action) {
        removeReadyListener();
        ready = () -> {
            if (closed || ticket != generation) { removeReadyListener(); return true; }
            if (host.getWidth() == 0 || host.getHeight() == 0) return true;
            removeReadyListener();
            host.post(() -> { if (!closed && foreground && ticket == generation) action.run(); });
            return true;
        };
        host.getViewTreeObserver().addOnPreDrawListener(ready);
        host.invalidate();
    }

    private void removeReadyListener() {
        if (ready != null && host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(ready);
        ready = null;
    }

    private RectF bounds(View target) {
        if (target == null || !target.isAttachedToWindow() || target.getWidth() == 0) return null;
        Rect visible = new Rect();
        if (!target.getGlobalVisibleRect(visible)) return null;
        int[] origin = new int[2];
        overlay.getLocationOnScreen(origin);
        RectF result = new RectF(visible);
        result.offset(-origin[0], -origin[1]);
        return result;
    }

    private void locate(boolean animate) {
        if (!active || overlay == null || closed) return;
        String key = switch (step) {
            case 0 -> "closet-add";
            case 1 -> "closet-first";
            case 2 -> "care-main";
            case 3, 4 -> "header-action";
            default -> "calendar-grid";
        };
        RectF target = bounds(targets.get(key));
        overlay.placeholder = step == 1 && !targets.containsKey(key);
        if (overlay.placeholder) {
            RectF body = bounds(ui.body);
            float left = ui.dp(20), width = (overlay.getWidth() - ui.dp(56)) / 2f;
            float top = body == null ? host.getPaddingTop() + ui.dp(130) : body.top + ui.dp(12);
            target = new RectF(left, top, left + width, top + Math.min(width * 1.33f + ui.dp(34), overlay.getHeight() * .36f));
        }
        if (step == 2 && target != null) {
            RectF group = bounds(targets.get("care-group"));
            if (group != null) {
                RectF combined = new RectF(target);
                combined.union(group);
                if (overlay.hasVerticalRoom(combined)) target = combined;
            }
        }
        if (step == 5 && (target == null || !overlay.hasVerticalRoom(target))) {
            View day = targets.get("calendar-day");
            if (day != null && !overlay.calendarFallback) {
                overlay.calendarFallback = true;
                day.requestRectangleOnScreen(new Rect(0, 0, day.getWidth(), day.getHeight()), true);
                afterLayout(generation, () -> locate(animate));
                return;
            }
            target = bounds(day);
        }
        if (target == null) {
            // Keep the tour usable if a transient layout has not exposed the anchor yet.
            target = new RectF(ui.dp(24), host.getPaddingTop() + ui.dp(16),
                    overlay.getWidth() - ui.dp(24), host.getPaddingTop() + ui.dp(64));
        }
        target.inset(-ui.dp(6), -ui.dp(6));
        target.intersect(1, host.getPaddingTop() + 1, overlay.getWidth() - 1,
                overlay.getHeight() - host.getPaddingBottom() - 1);
        overlay.present(target, animate);
    }

    private void finish() {
        if (!active && !pending) return;
        prefs.edit().putBoolean("has_completed_onboarding", true).apply();
        pending = active = false;
        generation++;
        host.removeCallbacks(launch);
        removeReadyListener();
        back.setEnabled(false);
        if (overlay != null) { overlay.dispose(); host.removeView(overlay); overlay = null; }
        ui.coachPageAccessibility(true);
        ui.getWindow().setStatusBarColor(statusBarColor);
        ui.getWindow().setNavigationBarColor(navigationBarColor);
        ui.openTab(0);
    }

    void save(Bundle state) {
        state.putBoolean("onboarding_active", running());
        state.putInt("onboarding_step", step);
    }

    @Override public void close() {
        if (active) {
            ui.getWindow().setStatusBarColor(statusBarColor);
            ui.getWindow().setNavigationBarColor(navigationBarColor);
        }
        closed = true;
        generation++;
        host.removeCallbacks(launch);
        removeReadyListener();
        host.removeOnLayoutChangeListener(viewport);
        back.remove();
        if (overlay != null) overlay.dispose();
    }

    private final class Spotlight extends FrameLayout {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF hole = new RectF();
        private final ArrayList<View> controls = new ArrayList<>();
        private final PorterDuffXfermode clear = new PorterDuffXfermode(PorterDuff.Mode.CLEAR);
        private LinearLayout card, outgoing;
        private ValueAnimator movement;
        private boolean placeholder, calendarFallback;
        private final RectF destination = new RectF();

        Spotlight() {
            super(ui);
            setWillNotDraw(false);
            setClickable(true);
            setFocusableInTouchMode(true);
            setContentDescription("使用指南");
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setClipChildren(false);
        }

        void makeCard() {
            if (outgoing != null) { outgoing.animate().cancel(); removeView(outgoing); }
            outgoing = card;
            controls.clear();
            calendarFallback = false;
            card = ui.vertical();
            card.setPadding(ui.dp(20), ui.dp(20), ui.dp(20), ui.dp(20));
            GradientDrawable background = ui.shape(MainActivity.PAPER, 4, 0);
            background.setStroke(1, MainActivity.INK);
            card.setBackground(background);
            card.setAccessibilityPaneTitle("使用指南 / " + (step + 1) + " / 6");
            LinearLayout top = ui.row();
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView number = ui.text(String.format(Locale.ROOT, "%02d / 06", step + 1), 12, 0xff888888, false);
            number.setFontFeatureSettings("tnum");
            top.addView(number, new LinearLayout.LayoutParams(0, -2, 1));
            TextView skip = ui.ghost("跳过", OnboardingGuide.this::finish);
            skip.setTextSize(12);
            skip.setTextColor(0xff888888);
            skip.setPadding(ui.dp(8), 0, 0, 0);
            top.addView(skip);
            card.addView(top);
            TextView title = ui.text(TITLES[step], 16, MainActivity.INK, true);
            title.setLetterSpacing(.03f);
            title.setAccessibilityHeading(true);
            card.addView(title);
            ui.gap(card, 8);
            TextView body = ui.text(BODIES[step], 13, 0xff555555, false);
            body.setLineHeight(Math.round(20 * getResources().getDisplayMetrics().scaledDensity));
            // Keep all copy readable when the user explicitly enlarges system text.
            body.setMaxLines(getResources().getConfiguration().fontScale > 1.2f ? 4 : 2);
            card.addView(body);
            ui.gap(card, 12);
            LinearLayout footer = ui.row();
            footer.setGravity(Gravity.CENTER_VERTICAL);
            TextView previous = ui.ghost("上一步", () -> { if (!busy) showStep(step - 1); });
            previous.setTextSize(12);
            previous.setTextColor(0xff888888);
            previous.setPadding(0, 0, ui.dp(8), 0);
            previous.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
            controls.add(previous);
            footer.addView(previous);
            footer.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1));
            String label = step == 5 ? "开始使用" : "下一步";
            FrameLayout next = new FrameLayout(ui);
            TextView face = ui.text(label, 13, MainActivity.PAPER, true);
            face.setSingleLine(true);
            face.setGravity(Gravity.CENTER);
            face.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            face.setBackground(ui.shape(MainActivity.INK, 4, 0));
            face.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            next.addView(face, new LayoutParams(-1, ui.dp(36), Gravity.CENTER_VERTICAL));
            next.setContentDescription(label);
            next.setAccessibilityDelegate(new AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setClassName("android.widget.Button");
                }
            });
            ui.click(next, android.graphics.Color.TRANSPARENT, 4, 0, () -> {
                if (!busy) { if (step == 5) finish(); else showStep(step + 1); }
            });
            int buttonWidth = Math.max(ui.dp(step == 5 ? 92 : 80), (int) Math.ceil(face.getPaint().measureText(label)) + ui.dp(32));
            footer.addView(next, new LinearLayout.LayoutParams(buttonWidth, ui.dp(48)));
            controls.add(next);
            card.addView(footer);
            card.setAlpha(0);
            addView(card, new LayoutParams(-1, -2));
            setControlsEnabled(false);
        }

        void setControlsEnabled(boolean enabled) { for (View control : controls) control.setEnabled(enabled); }

        private int cardWidth() {
            int width = getMeasuredWidth() > 0 ? getMeasuredWidth() : host.getWidth();
            int height = getMeasuredHeight() > 0 ? getMeasuredHeight() : host.getHeight();
            int available = width - ui.dp(48);
            return width > height ? Math.min(ui.dp(360), (width - ui.dp(96)) / 2) : available;
        }

        boolean hasVerticalRoom(RectF target) {
            if (card == null) return true;
            card.measure(MeasureSpec.makeMeasureSpec(Math.max(1, cardWidth()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(getHeight(), MeasureSpec.AT_MOST));
            int height = card.getMeasuredHeight() + ui.dp(24);
            return target.top - host.getPaddingTop() - ui.dp(24) >= height
                    || getHeight() - host.getPaddingBottom() - ui.dp(24) - target.bottom >= height;
        }

        void present(RectF target, boolean animate) {
            destination.set(target);
            requestLayout();
            if (movement != null) movement.cancel();
            if (!animate || !Motion.enabled() || hole.isEmpty()) {
                hole.set(target);
                busy = false;
                setControlsEnabled(true);
                invalidate();
            } else {
                RectF from = new RectF(hole);
                movement = ValueAnimator.ofFloat(0, 1);
                movement.setDuration(240);
                movement.setInterpolator(EASE);
                movement.addUpdateListener(value -> {
                    float t = (float) value.getAnimatedValue();
                    hole.set(from.left + (target.left - from.left) * t, from.top + (target.top - from.top) * t,
                            from.right + (target.right - from.right) * t, from.bottom + (target.bottom - from.bottom) * t);
                    invalidate();
                });
                movement.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator animation) {
                        if (!active || closed) return;
                        busy = false;
                        setControlsEnabled(true);
                    }
                });
                movement.start();
            }
            if (card != null) {
                card.animate().cancel();
                card.animate().alpha(1).setDuration(Motion.enabled() ? 180 : 0).start();
                card.announceForAccessibility(String.format(Locale.ROOT, "%02d / 06  %s  %s", step + 1, TITLES[step], BODIES[step]));
            }
            if (outgoing != null) {
                LinearLayout old = outgoing;
                old.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                old.animate().alpha(0).setDuration(Motion.enabled() ? 180 : 0).withEndAction(() -> {
                    removeView(old);
                    if (outgoing == old) outgoing = null;
                }).start();
            }
        }

        @Override protected void onMeasure(int width, int height) {
            super.onMeasure(width, height);
            if (card != null) card.measure(MeasureSpec.makeMeasureSpec(Math.max(1, cardWidth()), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(1, getMeasuredHeight() - ui.dp(48)), MeasureSpec.AT_MOST));
        }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (card == null) return;
            int width = card.getMeasuredWidth(), height = card.getMeasuredHeight();
            int safeTop = host.getPaddingTop() + ui.dp(24);
            int safeBottom = getHeight() - host.getPaddingBottom() - ui.dp(24);
            int gap = ui.dp(16), x = (getWidth() - width) / 2;
            boolean below = destination.centerY() < getHeight() / 2f;
            if (below && destination.bottom + gap + height > safeBottom) below = false;
            if (!below && destination.top - gap - height < safeTop && destination.bottom + gap + height <= safeBottom) below = true;
            int y = Math.round(below ? destination.bottom + gap : destination.top - gap - height);
            if (destination.top - gap - height < safeTop && destination.bottom + gap + height > safeBottom
                    && getWidth() > getHeight()) {
                if (destination.right + gap + width <= getWidth() - ui.dp(24)) x = Math.round(destination.right + gap);
                else if (destination.left - gap - width >= ui.dp(24)) x = Math.round(destination.left - gap - width);
            }
            y = Math.max(safeTop, Math.min(y, safeBottom - height));
            card.layout(x, y, x + width, y + height);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float radius = ui.dp(6);
            if (placeholder && !hole.isEmpty()) {
                RectF placeholderRect = new RectF(hole);
                placeholderRect.inset(ui.dp(6), ui.dp(6));
                paint.setColor(MainActivity.PAPER);
                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(hole, radius, radius, paint);
                paint.setColor(MainActivity.INK);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1);
                paint.setPathEffect(new DashPathEffect(new float[]{ui.dp(5), ui.dp(4)}, 0));
                canvas.drawRoundRect(placeholderRect, ui.dp(4), ui.dp(4), paint);
                paint.setPathEffect(null);
            }
            int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xb8111111);
            canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
            paint.setXfermode(clear);
            canvas.drawRoundRect(hole, radius, radius, paint);
            paint.setXfermode(null);
            canvas.restoreToCount(layer);
            paint.setColor(0x4dffffff);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            canvas.drawRoundRect(hole, radius, radius, paint);
        }

        void dispose() {
            if (movement != null) movement.cancel();
            if (card != null) card.animate().cancel();
            if (outgoing != null) outgoing.animate().cancel();
        }
    }
}
