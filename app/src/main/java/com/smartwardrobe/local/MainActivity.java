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
    static final int PAPER = Color.WHITE, CARD = Color.WHITE, INK = 0xff101010, MUTED = 0xff686868,
            SAND = 0xfff4f4f3, STROKE = 0xffe4e4e4;
    private static final int DESTRUCTIVE = 0xffa12424;
    static final String[] SEASONS = {"春", "夏", "秋", "冬"};
    static final String[] TYPES = {"上衣", "T 恤", "衬衫", "针织衫", "背心", "吊带", "外套", "风衣", "西装", "夹克",
            "短裤", "长裤", "牛仔裤", "工装裤", "半裙", "连衣裙", "鞋子", "配饰", "其他"};
    private static final int PICK = 14, CAMERA = 15, SCAN_CARE = 16, PHOTO_PERMISSION = 17;
    private static final int PICK_BATCH = 18;

    WardrobeStore db;
    ClosetScreens closet;
    OutfitScreens outfits;
    LinearLayout body;
    private Runnable back;
    int tab;
    private LinearLayout root;
    private FrameLayout frame;
    private volatile ScrollView pageScroll;
    private View outgoingPage;
    private View animatedPage;
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
    private TextView feedback;
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
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("tab", tab);
        state.putLong("canvasDraft", outfits.visibleDraftId());
        state.putString("purpose", pendingImagePurpose);
        state.putLong("garment", pendingGarment);
        if (capture != null) state.putString("capture", capture.getAbsolutePath());
    }

    @Override protected void onDestroy() {
        finishPageTransition();
        pageScroll = null;
        photoLoader.shutdownNow();
        worker.shutdown();
        super.onDestroy();
    }

    void openTab(int index) {
        if (index == 2) index = 1;
        tab = index;
        switch (index) {
            case 1 -> closet.care();
            case 3 -> outfits.collection();
            case 4 -> outfits.calendar();
            default -> closet.home();
        }
    }

    void page(Object key, String title, String subtitle, Runnable goBack, String action, Runnable onAction) {
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
            top.addView(pill(action, true, onAction));
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
                indicator.setBackgroundColor(active ? INK : Color.TRANSPARENT);
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
            root.addView(nav);
        }
        showPage(previousRoot, opening, returning);
    }

    private void showPage(View previous, boolean opening, boolean returning) {
        boolean animate = previous != null && previous.isLaidOut() && (opening || returning)
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
        v.setText(label);
        v.setTextSize(size);
        v.setTextColor(color);
        v.setIncludeFontPadding(true);
        if (bold) v.setTypeface(null, Typeface.BOLD);
        return v;
    }

    TextView editorial(String name, int size) {
        TextView v = text(name, size, INK, true);
        v.setTypeface(Typeface.create("serif", Typeface.BOLD));
        return v;
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
        view.setOnClickListener(v -> action.run());
        view.setFocusable(true);
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
        click(v, PAPER, 8, 0, action);
        return v;
    }

    private TextView actionButton(String label, int size, int color, boolean filled) {
        Button v = new Button(this, null, 0);
        v.setAllCaps(false);
        v.setText(label);
        v.setTextSize(size);
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
        view.setBackground(shape(SAND, 0, 0));
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
        Sheet sheet = new Sheet(this, title, true);
        LocalDate maximum = history ? LocalDate.now() : null;
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
        sheet.button("确认", true, () -> { sheet.dismiss(); chosen.accept(selected[0]); });
        refresh.run();
        sheet.show();
    }

    void toast(String message) { showFeedback(message, false); }

    void bottomNotice(String message) { showFeedback(message, true); }

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
                    toast("操作未完成，请重试或手动填写");
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
            task("正在读取画面…", () -> {
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
        task("正在读取图片…", () -> {
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
