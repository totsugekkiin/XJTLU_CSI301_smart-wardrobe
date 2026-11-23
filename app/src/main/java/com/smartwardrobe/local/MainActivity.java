package com.smartwardrobe.local;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.core.content.FileProvider;

import java.io.File;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class MainActivity extends ComponentActivity {
    static final int PAPER = Color.WHITE, CARD = Color.WHITE, INK = 0xff111111, MUTED = 0xff686868,
            SAND = 0xfff4f4f3, STROKE = 0xffe4e4e4;
    private static final int DESTRUCTIVE = INK;
    static final String[] SEASONS = {"春", "夏", "秋", "冬"};
    static final String[] TYPES = {"上衣", "T 恤", "衬衫", "针织衫", "背心", "吊带", "外套", "风衣", "西装", "夹克",
            "短裤", "长裤", "牛仔裤", "工装裤", "半裙", "连衣裙", "鞋子", "配饰", "其他"};
    private static final int PICK = 14, CAMERA = 15, SCAN_CARE = 16, PHOTO_PERMISSION = 17;
    private static final int PICK_BATCH = 18;
    private static final int COARSE_WEATHER = 19;
    private static final int MOTION_OUTFIT = 20;

    void openMotionOutfit() {
        startActivityForResult(new Intent(this, MotionOutfitActivity.class), MOTION_OUTFIT);
    }

    void outfitPage(int count, Runnable add) {
        page("outfits", "", "", null, null, null);
        LinearLayout header = (LinearLayout) root.getChildAt(0);
        header.setPadding(dp(20), dp(4), dp(20), dp(4));
        LinearLayout top = (LinearLayout) ((LinearLayout) root.getChildAt(0)).getChildAt(0);
        top.removeAllViews();
        top.setMinimumHeight(dp(50));
        TextView amount = text(count + " 套穿搭", 13, INK, false);
        amount.setFontFeatureSettings("tnum");
        amount.setGravity(Gravity.CENTER_VERTICAL);
        amount.setSingleLine(true);
        top.addView(amount, new LinearLayout.LayoutParams(0, -2, 1));
        TextView entry = text("体感搭配", 14, INK, false);
        entry.setGravity(Gravity.CENTER);
        entry.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        entry.setContentDescription("体感搭配");
        entry.setOnClickListener(view -> openMotionOutfit());
        entry.setFocusable(true);
        Motion.press(entry);
        top.addView(entry, new LinearLayout.LayoutParams(-2, dp(48)));
        space(top, 16);
        TextView create = text("+ 新建", 13, PAPER, false);
        create.setGravity(Gravity.CENTER);
        create.setPadding(dp(14), 0, dp(14), 0);
        click(create, INK, 4, 0, add);
        top.addView(create, new LinearLayout.LayoutParams(-2, dp(34)));
        coachTarget("header-action", create);
    }

    WardrobeStore db;
    ClosetScreens closet;
    OutfitScreens outfits;
    SeasonWeather weather;
    private OnboardingGuide onboarding;
    LinearLayout body;
    private Runnable back;
    int tab;
    private LinearLayout root;
    private FrameLayout frame;
    private volatile ScrollView pageScroll;
    private View outgoingPage;
    private View animatedPage;
    private SlidingTabs navigationStrip;
    private int renderedTab;
    private boolean heroOpening;
    private android.graphics.drawable.Drawable heroDrawable;
    private android.graphics.RectF heroSource;
    private ValueAnimator heroAnimator;
    private ImageView heroOverlay, heroTarget;
    private final ArrayList<PagePosition> pagePath = new ArrayList<>();
    private final Map<Object, PagePosition> tabPositions = new HashMap<>();
    private final ExecutorService photoLoader = Executors.newFixedThreadPool(2);
    private final ArrayList<PhotoRequest> pendingPhotos = new ArrayList<>();
    private final Rect visiblePhotoBounds = new Rect();
    private final OnBackPressedCallback systemBack = new OnBackPressedCallback(false) {
        @Override public void handleOnBackPressed() {
            if (back != null) back.run();
            else openTab(0);
        }
    };

    private record PhotoRequest(View target, String name, int sample, Consumer<Bitmap> show) {}

    private static final class PagePosition {
        final Object key;
        int scrollY;

        PagePosition(Object key) { this.key = key; }
    }
    private View feedback;
    private Consumer<Bitmap> imageCallback;
    private java.util.function.BiConsumer<Bitmap, java.util.List<ImageAnalysis.SymbolHit>> careCallback;
    private Uri cameraUri;
    private File capture;
    private String pendingImagePurpose = "";
    private long pendingGarment;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    ImageAnalysis.GarmentDetector garmentDetector;
    ImageAnalysis.TextReader textReader;
    ImageAnalysis.ColorReader colorReader;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        db = new WardrobeStore(this);
        weather = new SeasonWeather(this, () -> { if (closet != null) closet.weatherChanged(); });
        garmentDetector = new ImageAnalysis.YoloGarment(this);
        textReader = new ImageAnalysis.BundledText();
        colorReader = new GarmentColors();
        closet = new ClosetScreens(this);
        outfits = new OutfitScreens(this);
        frame = new FrameLayout(this);
        frame.setBackgroundColor(PAPER);
        frame.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, keyboard.bottom));
            }
            return insets;
        });
        setContentView(frame);
        onboarding = new OnboardingGuide(this, frame, state);
        frame.getViewTreeObserver().addOnPreDrawListener(() -> {
            loadVisiblePhotos();
            return true;
        });
        getOnBackPressedDispatcher().addCallback(this, systemBack);
        openTab(state == null ? 0 : state.getInt("tab"));
        if (state != null) {
            outfits.restoreCanvas(state.getLong("canvasDraft"));
            pendingImagePurpose = state.getString("purpose", "");
            pendingGarment = state.getLong("garment");
            String path = state.getString("capture", "");
            if (!path.isEmpty()) {
                capture = new File(path);
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".capture", capture);
            }
            if (pendingImagePurpose.startsWith("live-label")) {
                careCallback = closet.restoreCareScan(pendingGarment, pendingImagePurpose.equals("live-label-detail"));
            }
            else if (!pendingImagePurpose.isEmpty()) imageCallback = closet.restoreImport(pendingImagePurpose, pendingGarment);
        }
        onboarding.schedule();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (onboarding != null) onboarding.save(state);
        state.putInt("tab", tab);
        state.putLong("canvasDraft", outfits.visibleDraftId());
        state.putString("purpose", pendingImagePurpose);
        state.putLong("garment", pendingGarment);
        if (capture != null) state.putString("capture", capture.getAbsolutePath());
    }

    @Override protected void onDestroy() {
        if (onboarding != null) onboarding.close();
        if (weather != null) weather.close();
        finishPageTransition();
        pageScroll = null;
        photoLoader.shutdownNow();
        worker.shutdown();
        super.onDestroy();
    }

    @Override protected void onStart() {
        super.onStart();
        if (weather != null) weather.refreshIfDue();
        if (closet != null) closet.weatherChanged();
    }

    @Override protected void onStop() {
        if (weather != null) weather.stopLocationWhenHidden();
        super.onStop();
    }

    @Override protected void onResume() {
        super.onResume();
        if (onboarding != null) onboarding.resume();
    }

    @Override protected void onPause() {
        if (onboarding != null) onboarding.pause();
        super.onPause();
    }

    void ensureWeatherPermission(boolean explicit) {
        if (!explicit && guiding()) return;
        if (weather.hasPermission()) {
            if (explicit) weather.refreshNow(); else weather.refreshIfDue();
            return;
        }
        if (!explicit && weather.permissionAsked()) return;
        if (explicit && weather.permissionAsked()
                && !shouldShowRequestPermissionRationale(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        weather.markPermissionAsked();
        requestPermissions(new String[]{android.Manifest.permission.ACCESS_COARSE_LOCATION}, COARSE_WEATHER);
    }

    boolean isPage(Object key) { return !pagePath.isEmpty() && pagePath.get(pagePath.size() - 1).key.equals(key); }

    boolean guiding() { return onboarding != null && onboarding.running(); }
    void coachTarget(String key, View target) { if (onboarding != null) onboarding.register(key, target); }
    void replayGuide() { if (onboarding != null) onboarding.replay(); }
    void coachPageAccessibility(boolean enabled) {
        if (root != null) {
            root.setImportantForAccessibility(enabled ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            root.setDescendantFocusability(enabled ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
    }
    void prepareCoachPage() {
        scrollTop();
        body.animate().cancel();
        body.setTranslationY(0);
        body.setAlpha(1);
    }

    void secondaryHeaderAction() {
        LinearLayout heading = (LinearLayout) ((LinearLayout) root.getChildAt(0)).getChildAt(0);
        View action = heading.getChildAt(heading.getChildCount() - 1);
        GradientDrawable outline = shape(PAPER, 0, 0);
        outline.setStroke(1, INK);
        action.setBackground(outline);
        if (action instanceof TextView text) { text.setTextColor(INK); text.setTextSize(13); }
    }

    void openTab(int index) {
        if (index == 2) index = 1;
        if (index != 0) closet.clearSelection();
        tab = index;
        switch (index) {
            case 1 -> closet.care();
            case 3 -> outfits.collection();
            case 4 -> outfits.calendar();
            default -> closet.home();
        }
    }

    void selectionBar(View bar, Runnable exit) {
        back = exit;
        systemBack.setEnabled(true);
        bar.setElevation(dp(6));
        root.addView(bar, root.getChildCount() - 1, new LinearLayout.LayoutParams(-1, -2));
    }

    TextView garmentPage(Object key, Runnable exit, Runnable save) {
        page(key, "", "", exit, null, null);
        root.getChildAt(1).setVisibility(View.GONE);
        LinearLayout heading = (LinearLayout) ((LinearLayout) root.getChildAt(0)).getChildAt(0);
        heading.removeAllViews();
        FrameLayout previous = new FrameLayout(this);
        previous.setContentDescription("返回");
        previous.addView(new NavIcon(this, NavIcon.BACK, false, 0xff111111),
                new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
        click(previous, Color.TRANSPARENT, 0, 0, exit);
        heading.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
        heading.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView button = pill("保存", true, save);
        click(button, 0xff111111, 8, 0, save);
        button.setVisibility(View.GONE);
        heading.addView(button, new LinearLayout.LayoutParams(-2, dp(40)));
        body.setPadding(0, 0, 0, dp(24));
        return button;
    }

    /** Minimal two-row closet header: garment count with an optional add action, then the filter bar. */
    TextView closetPage(String count, Runnable add, View filterBar) {
        page("closet", "", "", null, null, null);
        LinearLayout header = (LinearLayout) root.getChildAt(0);
        header.setPadding(dp(20), dp(4), dp(20), dp(4));
        LinearLayout top = (LinearLayout) header.getChildAt(0);
        top.removeAllViews();
        top.setMinimumHeight(dp(50));
        TextView countView = text(count, 13, INK, false);
        countView.setGravity(Gravity.CENTER_VERTICAL);
        countView.setContentDescription(count);
        top.addView(countView, new LinearLayout.LayoutParams(0, -2, 1));
        if (add != null) {
            TextView button = pill("+ 添加", true, add);
            coachTarget("closet-add", button);
            button.setTextSize(13);
            button.setMinHeight(dp(40));
            button.setMinimumHeight(dp(40));
            button.setPadding(dp(14), 0, dp(14), 0);
            top.addView(button, new LinearLayout.LayoutParams(-2, dp(40)));
        }
        if (filterBar != null) {
            root.removeViewAt(1);
            root.addView(filterBar, 1, new LinearLayout.LayoutParams(-1, dp(42)));
        }
        body.setPadding(dp(20), dp(14), dp(20), dp(28));
        return countView;
    }

    void scrollTop() {
        if (pageScroll != null) pageScroll.scrollTo(0, 0);
    }

    void alignGarmentTab(View tabs, View panel) {
        ScrollView owner = pageScroll;
        LinearLayout ownerBody = body;
        ownerBody.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override public void onGlobalLayout() {
                boolean current = pageScroll == owner && isCurrentView(tabs) && panel.getVisibility() == View.VISIBLE;
                int minimum = Math.max(0, owner.getHeight() - tabs.getHeight() - ownerBody.getPaddingBottom());
                if (current && panel.getMinimumHeight() != minimum) { panel.setMinimumHeight(minimum); return; }
                if (ownerBody.getViewTreeObserver().isAlive()) ownerBody.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (current) owner.scrollTo(0, tabs.getTop());
            }
        });
        ownerBody.requestLayout();
    }

    void openGarmentHero(ImageView source, Runnable open) {
        if (source == null || source.getDrawable() == null || !Motion.enabled()) { open.run(); return; }
        heroDrawable = source.getDrawable();
        heroSource = imageBounds(source);
        heroOpening = true;
        try { open.run(); } finally { heroOpening = false; }
    }

    private android.graphics.RectF imageBounds(ImageView image) {
        int[] location = new int[2], origin = new int[2];
        image.getLocationOnScreen(location);
        frame.getLocationOnScreen(origin);
        float width = image.getWidth() - image.getPaddingLeft() - image.getPaddingRight();
        float height = image.getHeight() - image.getPaddingTop() - image.getPaddingBottom();
        android.graphics.drawable.Drawable drawable = image.getDrawable();
        float ratio = drawable == null || drawable.getIntrinsicHeight() <= 0 ? 1
                : drawable.getIntrinsicWidth() / (float) drawable.getIntrinsicHeight();
        float w = Math.min(width, height * ratio), h = w / ratio;
        float x = location[0] - origin[0] + image.getPaddingLeft() + (width - w) / 2;
        float y = location[1] - origin[1] + image.getPaddingTop() + (height - h) / 2;
        return new android.graphics.RectF(x, y, x + w, y + h);
    }

    void finishGarmentHero(ImageView target) {
        if (heroDrawable == null || heroSource == null) return;
        android.graphics.drawable.Drawable drawable = heroDrawable;
        android.graphics.RectF from = new android.graphics.RectF(heroSource);
        heroDrawable = null;
        heroSource = null;
        heroTarget = target;
        target.setAlpha(0f);
        Runnable start = () -> {
            if (!isCurrentView(target) || target.getDrawable() == null || !Motion.enabled()) { target.setAlpha(1f); return; }
            android.graphics.RectF to = imageBounds(target);
            heroTarget = target;
            target.setAlpha(0f);
            heroOverlay = new ImageView(this);
            ImageView overlay = heroOverlay;
            overlay.setImageDrawable(drawable);
            overlay.setScaleType(ImageView.ScaleType.FIT_XY);
            overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            frame.addView(overlay, new FrameLayout.LayoutParams(Math.max(1, (int) from.width()), Math.max(1, (int) from.height())));
            heroAnimator = ValueAnimator.ofFloat(0, 1);
            heroAnimator.setDuration(300);
            heroAnimator.setInterpolator(new android.view.animation.PathInterpolator(.215f, .61f, .355f, 1));
            heroAnimator.addUpdateListener(value -> {
                float t = (float) value.getAnimatedValue();
                FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) overlay.getLayoutParams();
                params.width = Math.max(1, Math.round(from.width() + (to.width() - from.width()) * t));
                params.height = Math.max(1, Math.round(from.height() + (to.height() - from.height()) * t));
                overlay.setLayoutParams(params);
                overlay.setTranslationX(from.left + (to.left - from.left) * t);
                overlay.setTranslationY(from.top + (to.top - from.top) * t);
            });
            heroAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    frame.removeView(overlay);
                    target.setAlpha(1f);
                    if (heroOverlay == overlay) { heroOverlay = null; heroTarget = null; heroAnimator = null; }
                }
            });
            heroAnimator.start();
        };
        if (target.isLaidOut()) start.run();
        else target.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View view, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right <= left || bottom <= top) return;
                target.removeOnLayoutChangeListener(this);
                start.run();
            }
        });
    }

    void fixedFooter(View footer) {
        root.addView(footer, new LinearLayout.LayoutParams(-1, -2));
    }

    boolean isCurrentView(View view) {
        for (android.view.ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
            if (parent == root) return true;
        return false;
    }

    void page(Object key, String title, String subtitle, Runnable goBack, String action, Runnable onAction) {
        if (onboarding != null) onboarding.pageChanging();
        boolean tabChanged = goBack == null && navigationStrip != null && renderedTab != tab;
        float indicatorFrom = tabChanged ? navigationStrip.position() : -1;
        navigationStrip = null;
        finishPageTransition();
        pendingPhotos.clear();
        PagePosition previousPosition = pagePath.isEmpty() ? null : pagePath.get(pagePath.size() - 1);
        if (previousPosition != null) previousPosition.scrollY = pageScroll.getScrollY();
        int match = pagePath.size() - 1;
        while (match >= 0 && !pagePath.get(match).key.equals(key)) match--;
        boolean returning = previousPosition != null && match < pagePath.size() - 1
                && (match >= 0 || back != null && goBack == null);
        boolean opening = previousPosition != null && match < 0 && goBack != null;
        PagePosition destination;
        if (goBack == null) {
            destination = tabPositions.computeIfAbsent(key, PagePosition::new);
            pagePath.clear();
            pagePath.add(destination);
        } else if (match >= 0) {
            destination = pagePath.get(match);
            pagePath.subList(match + 1, pagePath.size()).clear();
        } else {
            destination = new PagePosition(key);
            pagePath.add(destination);
        }

        LinearLayout previousRoot = root;
        back = goBack;
        systemBack.setEnabled(back != null || tab != 0);
        root = new LinearLayout(this) {
            @Override public boolean dispatchTouchEvent(MotionEvent event) {
                return this == root && super.dispatchTouchEvent(event);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAPER);

        LinearLayout header = vertical();
        header.setPadding(dp(20), dp(6), dp(20), dp(6));
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        if (goBack != null) {
            TextView previous = ghost("‹", goBack);
            previous.setTextSize(29);
            previous.setContentDescription("返回");
            top.addView(previous, new LinearLayout.LayoutParams(dp(48), dp(48)));
            space(top, 10);
        }
        top.setMinimumHeight(dp(50));
        TextView heading = editorial(title, goBack == null ? 23 : 21);
        heading.setAccessibilityHeading(true);
        if (!subtitle.isEmpty() && goBack == null) {
            top.addView(heading);
            space(top, 9);
            top.addView(text(subtitle, 12, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        } else top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            space(top, 8);
            TextView button = pill(action, true, onAction);
            top.addView(button);
            coachTarget("header-action", button);
        }
        header.addView(top);
        if (!subtitle.isEmpty() && goBack != null) {
            header.addView(text(subtitle, 13, MUTED, false));
            gap(header, 4);
        }
        root.addView(header);
        rule(root);

        ScrollView scroll = new ScrollView(this) {
            private int initialScrollY = destination.scrollY;

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                if (initialScrollY > 0) {
                    scrollTo(0, initialScrollY);
                    initialScrollY = 0;
                }
            }
        };
        pageScroll = scroll;
        scroll.setClipToPadding(false);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);
        body = vertical();
        body.setPadding(dp(20), dp(18), dp(20), dp(28));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        if (goBack == null) {
            rule(root);
            LinearLayout nav = row();
            String[] names = {"衣橱", "洗护", "穿搭", "日历"};
            int[] tabs = {0, 1, 3, 4};
            long pending = db.pendingWashCount();
            for (int i = 0; i < names.length; i++) {
                int index = tabs[i];
                boolean active = index == tab;
                LinearLayout item = vertical();
                item.setGravity(Gravity.CENTER);
                View indicator = new View(this);
                indicator.setBackgroundColor(Color.TRANSPARENT);
                item.addView(indicator, new LinearLayout.LayoutParams(dp(28), dp(2)));
                gap(item, 9);
                FrameLayout iconBox = new FrameLayout(this);
                iconBox.addView(new NavIcon(this, i, active), new FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER));
                if (index == 1 && pending > 0) {
                    TextView badge = text(Long.toString(pending), 9, PAPER, true);
                    badge.setGravity(Gravity.CENTER);
                    badge.setBackground(shape(INK, 3, 0));
                    iconBox.addView(badge, new FrameLayout.LayoutParams(dp(15), dp(13), Gravity.TOP | Gravity.END));
                }
                item.addView(iconBox, new LinearLayout.LayoutParams(dp(37), dp(24)));
                TextView label = text(names[i], 11, active ? INK : MUTED, active);
                item.addView(label, new LinearLayout.LayoutParams(-2, -2));
                gap(item, 7);
                item.setSelected(active);
                item.setContentDescription(names[i]);
                label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                click(item, PAPER, 0, 0, () -> { if (index != tab) openTab(index); });
                nav.addView(item, new LinearLayout.LayoutParams(0, dp(61), 1));
            }
            int selected = tab == 4 ? 3 : tab == 3 ? 2 : tab;
            navigationStrip = new SlidingTabs(this, nav, 4, selected, 28, true, indicatorFrom);
            renderedTab = tab;
            root.addView(navigationStrip);
        }
        showPage(previousRoot, opening, returning);
        if (onboarding != null) onboarding.pageShown();
        if (tabChanged) Motion.rise(body);
    }

    private void showPage(View previous, boolean opening, boolean returning) {
        boolean animate = !heroOpening && previous != null && previous.isLaidOut() && (opening || returning)
                && ValueAnimator.areAnimatorsEnabled();
        if (!animate && previous != null) frame.removeView(previous);
        frame.addView(root, animate && opening ? 1 : 0, new FrameLayout.LayoutParams(-1, -1));
        if (!animate) return;

        outgoingPage = previous;
        outgoingPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        animatedPage = opening ? root : previous;
        int offset = previous.getWidth() * (root.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1);
        animatedPage.setTranslationX(opening ? offset : 0);
        animatedPage.animate()
                .translationX(opening ? 0 : offset)
                .setDuration(190)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(this::finishPageTransition);
    }

    private void finishPageTransition() {
        if (heroAnimator != null) { heroAnimator.cancel(); heroAnimator = null; }
        if (heroOverlay != null) { frame.removeView(heroOverlay); heroOverlay = null; }
        if (heroTarget != null) { heroTarget.setAlpha(1f); heroTarget = null; }
        if (animatedPage == null) return;
        animatedPage.animate().cancel();
        animatedPage.setTranslationX(0);
        frame.removeView(outgoingPage);
        outgoingPage = null;
        animatedPage = null;
    }

    int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        return box;
    }

    TextView text(String label, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(cleanText(label));
        v.setTextSize(size);
        v.setFontFeatureSettings("tnum");
        v.setTextColor(color);
        v.setIncludeFontPadding(true);
        v.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        return v;
    }

    TextView editorial(String name, int size) {
        TextView v = text(name, size, INK, true);
        return v;
    }

    static String cleanText(String value) {
        return value == null ? "" : value.replaceAll("[ \\t]*[\\u00B7\\u2022\\u25CF][ \\t]*", "  ")
                .replaceAll("[\\x{1F000}-\\x{1FAFF}\\u2600-\\u27BF\\u231A\\u231B\\u23E9-\\u23F3\\u23F8-\\u23FA\\u2B50\\u2B55\\uFE0E\\uFE0F\\u200D\\u20E3]", "");
    }

    GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(radius));
        if (stroke != 0) bg.setStroke(dp(1), stroke);
        return bg;
    }

    void click(View view, int color, int radius, int stroke, Runnable action) {
        int ripple = color == INK ? 0x30ffffff : 0x16000000;
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple), shape(color, radius, stroke), null));
        view.setOnClickListener(v -> {
            if (color == INK) v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            action.run();
        });
        view.setFocusable(true);
        Motion.press(view);
    }

    TextView pill(String label, boolean filled, Runnable action) {
        TextView v = actionButton(label, 13, filled ? Color.WHITE : INK, filled);
        v.setPadding(dp(12), dp(4), dp(12), dp(4));
        click(v, filled ? INK : PAPER, 4, filled ? 0 : 0xffd5d5d5, action);
        return v;
    }

    TextView ghost(String label, Runnable action) {
        TextView v = actionButton(label, 14, INK, false);
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        click(v, PAPER, 8, 0, action);
        return v;
    }

    TextView destructiveButton(String label, Runnable action) {
        TextView v = actionButton(label, 14, DESTRUCTIVE, false);
        v.setPadding(dp(10), dp(4), dp(10), dp(4));
        click(v, PAPER, 8, 0, () -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            action.run();
        });
        return v;
    }

    private TextView actionButton(String label, int size, int color, boolean filled) {
        Button v = new Button(this, null, 0);
        v.setAllCaps(false);
        v.setText(cleanText(label));
        v.setTextSize(size);
        v.setFontFeatureSettings("tnum");
        v.setTypeface(null, Typeface.BOLD);
        v.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{filled ? 0xffbdbdbd : 0xff737373, color}));
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(dp(48));
        v.setMinHeight(dp(48));
        return v;
    }
    View colorDot(int color, int size) {
        View dot = new View(this);
        dot.setBackground(shape(color, size / 2, STROKE));
        dot.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return dot;
    }

    LinearLayout colorChip(String label, int color, Runnable action) {
        LinearLayout chip = row();
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setMinimumHeight(dp(48));
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.addView(colorDot(color, 18));
        space(chip, 8);
        chip.addView(text(label, 13, INK, true));
        click(chip, PAPER, 4, STROKE, action);
        return chip;
    }

    void actions(LinearLayout box, String[] labels, Runnable... actions) {
        for (int first = 0; first < labels.length; first += 2) {
            LinearLayout bar = row();
            bar.setBaselineAligned(false);
            for (int i = first; i < Math.min(first + 2, labels.length); i++) {
                TextView button = pill(labels[i], false, actions[i]);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                if (i > first) p.setMarginStart(dp(12));
                bar.addView(button, p);
            }
            box.addView(bar);
            gap(box, 10);
        }
    }

    void gap(LinearLayout box, int height) { box.addView(new View(this), new LinearLayout.LayoutParams(1, dp(height))); }
    void space(LinearLayout box, int width) { box.addView(new View(this), new LinearLayout.LayoutParams(dp(width), 1)); }
    void rule(LinearLayout box) {
        View line = new View(this);
        line.setBackgroundColor(STROKE);
        box.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    void section(LinearLayout box, String label) {
        TextView title = text(label, 18, INK, true);
        title.setAccessibilityHeading(true);
        gap(box, 16);
        box.addView(title);
        gap(box, 10);
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = vertical();
        card.setBackground(shape(CARD, 4, STROKE));
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        parent.addView(card, new LinearLayout.LayoutParams(-1, -2));
        gap(parent, 14);
        return card;
    }

    void empty(LinearLayout parent, String title, String description) {
        LinearLayout card = card(parent);
        card.addView(text(title, 18, INK, true));
        if (!description.isEmpty()) card.addView(text(description, 14, MUTED, false));
    }

    ImageView image(Bitmap bitmap, int height) {
        ImageView view = new ImageView(this);
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setImageBitmap(bitmap);
        view.setBackground(shape(PAPER, 0, 0));
        view.setClipToOutline(true);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(height)));
        return view;
    }

    ImageView photo(String name, int sample, int height) {
        ImageView view = image(null, height);
        loadPhoto(view, name, sample, view::setImageBitmap);
        return view;
    }

    void loadPhoto(View target, String name, int sample, Consumer<Bitmap> show) {
        if (name == null || name.isEmpty()) return;
        Bitmap cached = db.cachedImage(name, sample);
        if (cached != null) {
            show.accept(cached);
            return;
        }
        pendingPhotos.add(new PhotoRequest(target, name, sample, show));
    }

    private void loadVisiblePhotos() {
        ScrollView owner = pageScroll;
        var requests = pendingPhotos.iterator();
        while (requests.hasNext()) {
            PhotoRequest request = requests.next();
            if (!request.target().getLocalVisibleRect(visiblePhotoBounds)) continue;
            requests.remove();
            photoLoader.execute(() -> {
                if (pageScroll != owner) return;
                Bitmap bitmap = db.image(request.name(), request.sample());
                if (bitmap != null) runOnUiThread(() -> {
                    if (pageScroll == owner) request.show().accept(bitmap);
                });
            });
        }
    }

    EditText field(LinearLayout box, String label, String value, boolean multiline, boolean numeric) {
        if (!label.isEmpty()) box.addView(text(label, 13, MUTED, true));
        EditText field = new EditText(this);
        field.setTextSize(15);
        field.setFontFeatureSettings("tnum");
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setContentDescription(label);
        field.setSingleLine(!multiline);
        field.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT
                | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        field.setGravity(Gravity.TOP | Gravity.START);
        field.setText(value);
        field.setMinHeight(dp(45));
        if (multiline) field.setMinLines(2);
        field.setPadding(dp(10), dp(10), dp(10), dp(9));
        android.graphics.drawable.StateListDrawable background = new android.graphics.drawable.StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, shape(CARD, 7, INK));
        background.addState(new int[0], shape(CARD, 7, STROKE));
        field.setBackground(background);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        gap(box, 8);
        return field;
    }

    String value(EditText field) { return field.getText().toString().trim(); }

    void choose(String title, String[] options, Consumer<Integer> chosen) {
        Sheet sheet = new Sheet(this, title, true);
        for (int i = 0; i < options.length; i++) {
            int index = i;
            sheet.option(options[i], () -> { sheet.dismiss(); chosen.accept(index); });
        }
        sheet.show();
    }

    void prompt(String title, String description, String yes, Runnable action) {
        Sheet sheet = new Sheet(this, title, true);
        sheet.description(description);
        sheet.button("取消", false, sheet::dismiss);
        sheet.button(yes, true, () -> { sheet.dismiss(); action.run(); });
        sheet.show();
    }

    void confirmDestructive(String title, String description, String label, Runnable action) {
        Sheet sheet = new Sheet(this, title, true);
        sheet.description(description);
        sheet.button("取消", false, sheet::dismiss);
        sheet.destructiveButton(label, () -> { sheet.dismiss(); action.run(); });
        sheet.show();
    }

    void date(String title, LocalDate initial, boolean history, Consumer<LocalDate> chosen) {
        date(title, initial, history ? LocalDate.now() : null, null, chosen);
    }

    void date(String title, LocalDate initial, LocalDate maximum, String explanation, Consumer<LocalDate> chosen) {
        Sheet sheet = new Sheet(this, title, true);
        if (maximum != null && initial.isAfter(maximum)) initial = maximum;
        LocalDate[] selected = {initial};
        YearMonth[] month = {YearMonth.from(initial)};
        LinearLayout header = row();
        TextView monthLabel = text("", 17, INK, true);
        monthLabel.setGravity(Gravity.CENTER);
        LinearLayout grid = vertical();
        Runnable refresh = new Runnable() {
            @Override public void run() {
                monthLabel.setText(month[0].getYear() + " 年 " + month[0].getMonthValue() + " 月");
                CalendarGrid.populate(MainActivity.this, grid, month[0], selected[0], maximum, d -> false, day -> {
                    selected[0] = day;
                    run();
                });
            }
        };
        TextView previous = ghost("‹", () -> { month[0] = month[0].minusMonths(1); refresh.run(); });
        previous.setContentDescription("上个月");
        TextView next = ghost("›", () -> {
            if (maximum != null && !month[0].isBefore(YearMonth.from(maximum))) return;
            month[0] = month[0].plusMonths(1);
            refresh.run();
        });
        next.setContentDescription("下个月");
        header.addView(previous);
        header.addView(monthLabel, new LinearLayout.LayoutParams(0, dp(48), 1));
        header.addView(next);
        sheet.content.addView(header);
        CalendarGrid.addTo(this, sheet.content, grid);
        if (explanation != null) sheet.description(explanation);
        sheet.button("确认", true, () -> {
            if (maximum != null && selected[0].isAfter(maximum)) return;
            sheet.dismiss();
            chosen.accept(selected[0]);
        });
        refresh.run();
        sheet.show();
    }

    void toast(String message) { showFeedback(message, false); }

    void bottomNotice(String message) { showFeedback(message, true); }

    void undoNotice(String message, Runnable undo) {
        if (feedback != null && feedback.getParent() instanceof FrameLayout owner) owner.removeView(feedback);
        FrameLayout owner = frame;
        LinearLayout notice = row();
        feedback = notice;
        notice.setGravity(Gravity.CENTER_VERTICAL);
        notice.setBackground(shape(INK, 8, 0));
        notice.setPadding(dp(16), 0, dp(8), 0);
        TextView label = text(message, 14, Color.WHITE, false);
        notice.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView action = text("撤销", 16, Color.WHITE, true);
        action.setGravity(Gravity.CENTER);
        action.setMinWidth(dp(64));
        action.setMinHeight(dp(48));
        action.setOnClickListener(view -> {
            action.setEnabled(false);
            action.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            owner.removeView(notice);
            undo.run();
        });
        notice.addView(action);
        Motion.press(action);
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        p.setMargins(dp(20), 0, dp(20), dp(76));
        owner.addView(notice, p);
        notice.announceForAccessibility(message + "，可撤销");
        android.view.accessibility.AccessibilityManager accessibility =
                (android.view.accessibility.AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        int timeout = 7000;
        if (android.os.Build.VERSION.SDK_INT >= 29 && accessibility != null) {
            timeout = accessibility.getRecommendedTimeoutMillis(timeout,
                    android.view.accessibility.AccessibilityManager.FLAG_CONTENT_TEXT
                    | android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS);
        }
        notice.postDelayed(() -> owner.removeView(notice), timeout);
    }

    private void showFeedback(String message, boolean bottom) {
        if (feedback != null && feedback.getParent() instanceof FrameLayout owner) owner.removeView(feedback);
        FrameLayout owner = frame;
        TextView notice = text(message, 14, Color.WHITE, true);
        feedback = notice;
        notice.setBackground(shape(INK, 8, 0));
        notice.setPadding(dp(16), dp(10), dp(16), dp(10));
        int edge = bottom ? Gravity.BOTTOM : Gravity.TOP;
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(-2, -2, edge | Gravity.CENTER_HORIZONTAL);
        p.setMargins(dp(20), bottom ? 0 : dp(12), dp(20), bottom ? dp(76) : 0);
        owner.addView(notice, p);
        notice.announceForAccessibility(message);
        notice.postDelayed(() -> owner.removeView(notice), 2300);
    }

    interface Job<T> { T execute() throws Exception; }

    <T> void task(String title, Job<T> work, Consumer<T> done) {
        Sheet progress = new Sheet(this, title, false);
        ProgressBar indicator = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        indicator.setIndeterminate(true);
        indicator.setIndeterminateTintList(ColorStateList.valueOf(INK));
        progress.content.addView(indicator, new LinearLayout.LayoutParams(-1, dp(12)));
        progress.show();
        worker.execute(() -> {
            try {
                T result = work.execute();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    done.accept(result);
                });
            } catch (Exception | LinkageError e) {
                Log.e("SmartWardrobe", title, e);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    progress.dismiss();
                    toast("操作未完成 / 请重试或手动填写");
                });
            }
        });
    }

    void requestBatchImages() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_BATCH);
        } catch (android.content.ActivityNotFoundException e) {
            toast("无法打开图片来源");
        }
    }

    void requestImage(String purpose, long garment, boolean fromCamera, Consumer<Bitmap> callback) {
        pendingImagePurpose = purpose;
        pendingGarment = garment;
        imageCallback = callback;
        if (fromCamera && checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, PHOTO_PERMISSION);
            return;
        }
        try {
            if (fromCamera) {
                File dir = new File(getCacheDir(), "captures");
                if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Capture directory");
                capture = File.createTempFile("camera-", ".jpg", dir);
                cameraUri = FileProvider.getUriForFile(this, getPackageName() + ".capture", capture);
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                intent.setClipData(ClipData.newRawUri("衣物照片", cameraUri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                startActivityForResult(intent, CAMERA);
            } else {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*");
                startActivityForResult(intent, PICK);
            }
        } catch (Exception e) {
            if (capture != null) capture.delete();
            capture = null;
            cameraUri = null;
            pendingImagePurpose = "";
            imageCallback = null;
            toast("无法打开图片来源");
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == COARSE_WEATHER) {
            weather.refreshNow();
            closet.weatherChanged();
            return;
        }
        if (code != PHOTO_PERMISSION || imageCallback == null) return;
        if (grants.length > 0 && grants[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestImage(pendingImagePurpose, pendingGarment, true, imageCallback);
        } else {
            pendingImagePurpose = "";
            imageCallback = null;
            toast("相机未授权");
        }
    }

    void scanCare(long garment, boolean direct, java.util.function.BiConsumer<Bitmap, java.util.List<ImageAnalysis.SymbolHit>> callback) {
        pendingImagePurpose = direct ? "live-label-detail" : "live-label";
        pendingGarment = garment;
        careCallback = callback;
        startActivityForResult(new Intent(this, CareScanActivity.class), SCAN_CARE);
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == MOTION_OUTFIT) {
            outfits.collection();
            if (result == RESULT_OK && data != null) {
                closet.addForMotion(data.getStringExtra("type"), () -> {
                    outfits.collection();
                    openMotionOutfit();
                });
            }
            return;
        }
        if (code == PICK_BATCH) {
            if (result != RESULT_OK || data == null) return;
            var images = new java.util.LinkedHashSet<Uri>();
            ClipData selection = data.getClipData();
            if (selection != null) {
                for (int i = 0; i < selection.getItemCount(); i++) {
                    Uri uri = selection.getItemAt(i).getUri();
                    if (uri != null) images.add(uri);
                }
            }
            if (data.getData() != null) images.add(data.getData());
            if (!images.isEmpty()) closet.confirmBatchImport(new java.util.ArrayList<>(images));
            return;
        }
        if (code == SCAN_CARE) {
            var callback = careCallback;
            boolean direct = pendingImagePurpose.equals("live-label-detail");
            careCallback = null;
            pendingImagePurpose = "";
            if (result != RESULT_OK || callback == null || data == null) return;
            if (data.getBooleanExtra(CareScanActivity.GALLERY, false)) {
                requestImage(direct ? "label-detail" : "label", pendingGarment, false, image -> callback.accept(image, null));
                return;
            }
            String name = data.getStringExtra(CareScanActivity.PHOTO);
            if (name == null || !name.matches("care-frame-[0-9]+\\.jpg")) return;
            File file = new File(getCacheDir(), name);
            java.util.ArrayList<Bundle> bundles = data.getParcelableArrayListExtra(CareScanActivity.HITS);
            java.util.List<ImageAnalysis.SymbolHit> hits = new java.util.ArrayList<>();
            if (bundles != null) for (Bundle b : bundles) {
                android.graphics.RectF box = b.getParcelable("bounds");
                if (box != null) hits.add(new ImageAnalysis.SymbolHit(b.getInt("group"), b.getString("meaning"), b.getFloat("score"), box));
            }
            task("读取画面中…", () -> {
                try {
                    Bitmap image = android.graphics.BitmapFactory.decodeFile(file.getPath());
                    if (image == null) throw new java.io.IOException("Unreadable care frame");
                    return image;
                } finally { file.delete(); }
            }, bitmap -> callback.accept(bitmap, hits.isEmpty() ? null : hits));
            return;
        }
        if (code != PICK && code != CAMERA) return;
        Consumer<Bitmap> callback = imageCallback;
        Uri source = code == CAMERA ? cameraUri : data == null ? null : data.getData();
        File temp = capture;
        capture = null;
        cameraUri = null;
        imageCallback = null;
        pendingImagePurpose = "";
        if (result != RESULT_OK || source == null || callback == null) {
            if (temp != null) temp.delete();
            return;
        }
        task("读取图片中…", () -> {
            try { return ImageAnalysis.decode(this, source); }
            finally { if (temp != null) temp.delete(); }
        }, callback);
    }

    void crop(Bitmap bitmap, Consumer<Bitmap> done) {
        cropSelection(bitmap, selected -> done.accept(selected.image()));
    }

    void cropSelection(Bitmap bitmap, Consumer<CropView.Selection> done) {
        CropView crop = new CropView(this, bitmap);
        Sheet sheet = new Sheet(this, "框选衣物", true);
        sheet.content.addView(crop, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(300)));
        TextView error = text("请扩大选区", 13, INK, false);
        error.setVisibility(View.GONE);
        sheet.content.addView(error);
        sheet.button("使用选区", true, () -> {
            CropView.Selection result = crop.result();
            if (result == null) { error.setVisibility(View.VISIBLE); return; }
            sheet.dismiss();
            done.accept(result);
        });
        sheet.show();
    }

    static String seasons(int bits) {
        if (bits == 15) return "四季适用";
        String result = "";
        for (int i = 0; i < SEASONS.length; i++) if ((bits & (1 << i)) != 0) result += SEASONS[i];
        return result.isEmpty() ? "季节未标注" : result;
    }
}
