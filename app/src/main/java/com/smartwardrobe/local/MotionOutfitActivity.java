package com.smartwardrobe.local;

import android.app.Activity;
import android.app.Dialog;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** White, edge-to-edge album with lifecycle-scoped posture input and touch equivalents. */
public final class MotionOutfitActivity extends Activity implements SensorEventListener, OutfitMotion.Actions {
    private static final int INK = 0xff111111;
    private static final int PIN = 0, DICE = 1, FILTER = 2, CALIBRATE = 3, LAYERS = 4, MOTION = 5;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Handler animationHandler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> photos = new LruCache<>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private final OutfitMotion motion = new OutfitMotion(this);
    private final float[] gravity = new float[3];
    private WardrobeStore db;
    private SensorManager sensors;
    private Sensor postureSensor, accelerationSensor, linearSensor;
    private boolean gravityReady, resumed, destroyed, shuffling, naming;
    private boolean threePiece, motionEnabled = true, filtering;
    private int seasonFilter = -1;
    private String colorFilter = "";
    private final List<WardrobeStore.Garment> pool = new ArrayList<>();
    private long readyAt, lastAccelerationTime;
    private Dialog nameDialog;
    private Dialog filterDialog;
    private int active;
    private Wheel[] wheels;
    private LinearLayout root;
    private Stage stage;
    private Icon layersIcon, motionIcon, diceIcon, filterIcon;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        db = new WardrobeStore(this);
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        postureSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accelerationSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        linearSensor = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (postureSensor == null) postureSensor = accelerationSensor;
        if (android.os.Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        root = column();
        root.setBackgroundColor(Color.WHITE);
        root.getViewTreeObserver().addOnPreDrawListener(() -> {
            UiLanguage.localizeTree(root);
            return true;
        });
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        setContentView(root);
        if (state != null) {
            threePiece = state.getBoolean("threePiece");
            motionEnabled = state.getBoolean("motionEnabled", true);
            seasonFilter = state.getInt("seasonFilter", -1);
            colorFilter = state.getString("colorFilter", "");
        }
        pool.addAll(db.garments());
        if (!colorFilter.isEmpty() && java.util.Arrays.stream(GarmentPicker.FAMILIES)
                .noneMatch(colorFilter::equals)) {
            String previous = colorFilter;
            colorFilter = pool.stream().flatMap(garment -> garment.colors.stream())
                    .filter(swatch -> swatch.name.equals(previous)).map(GarmentPicker::family)
                    .findFirst().orElse("");
        }
        LinearLayout nav = row();
        nav.setPadding(dp(12), dp(6), dp(20), dp(6));
        TextView back = button("<", false, this::finish);
        back.setTextSize(26);
        back.setContentDescription("返回");
        nav.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        filterIcon = icon(FILTER, "筛选", this::showFilters);
        nav.addView(filterIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        nav.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        Icon calibrate = icon(CALIBRATE, "校准", () -> {
            motion.reset();
            gravityReady = false;
            readyAt = SystemClock.elapsedRealtime() + 300;
            tick();
        });
        calibrate.setOnClickListener(view -> {
            calibrate.animate().rotationBy(90).setDuration(220).start();
            motion.reset(); gravityReady = false;
            readyAt = SystemClock.elapsedRealtime() + 300;
            tick();
        });
        nav.addView(calibrate, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView save = button("保存", true, this::nameOutfit);
        FrameLayout saveTouch = new FrameLayout(this);
        saveTouch.setContentDescription("保存");
        saveTouch.setOnClickListener(view -> nameOutfit());
        saveTouch.addView(save, new FrameLayout.LayoutParams(dp(58), dp(34), Gravity.CENTER));
        LinearLayout.LayoutParams saveParams = new LinearLayout.LayoutParams(dp(58), dp(48));
        saveParams.leftMargin = dp(10);
        nav.addView(saveTouch, saveParams);
        root.addView(nav);
        stage = new Stage();
        root.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));
        wheels = new Wheel[]{new Wheel(0, "外套", new ArrayList<>()),
                new Wheel(1, "上装", new ArrayList<>()), new Wheel(2, "下装", new ArrayList<>())};
        active = state == null ? 1 : state.getInt("active", 1);
        rebuildPools();
        for (Wheel wheel : wheels) {
            if (state != null) {
                long id = state.getLong("selected" + wheel.row);
                for (int i = 0; i < wheel.items.size(); i++) if (wheel.items.get(i).id == id) wheel.index = i;
                wheel.locked = state.getBoolean("locked" + wheel.row);
            }
            stage.addView(wheel);
            wheel.update();
        }
        stage.addView(stage.focusFrame);
        LinearLayout footer = column();
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(0, dp(16), 0, dp(24));
        LinearLayout capsule = row();
        capsule.setPadding(dp(4), dp(2), dp(4), dp(2));
        capsule.setBackground(border(Color.WHITE, 26, 0xffdcdcdc));
        layersIcon = icon(LAYERS, "切换至三件模式", this::toggleLayers);
        capsule.addView(layersIcon, new LinearLayout.LayoutParams(dp(52), dp(48)));
        motionIcon = icon(MOTION, "暂停体感", this::toggleMotion);
        capsule.addView(motionIcon, new LinearLayout.LayoutParams(dp(52), dp(48)));
        FrameLayout diceTouch = new FrameLayout(this);
        diceTouch.setContentDescription("随机搭配");
        diceTouch.setOnClickListener(view -> shuffle());
        diceIcon = icon(DICE, "随机搭配", this::shuffle);
        diceIcon.white = true;
        diceIcon.setBackground(outline(INK, 10, false));
        diceTouch.addView(diceIcon, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        capsule.addView(diceTouch, new LinearLayout.LayoutParams(dp(52), dp(48)));
        footer.addView(capsule, new LinearLayout.LayoutParams(-2, dp(52)));
        root.addView(footer);
        updateControls();
    }

    static boolean isBottom(String type) {
        return type.equals("短裤") || type.equals("长裤") || type.equals("牛仔裤")
                || type.equals("工装裤") || type.equals("半裙");
    }

    static boolean isTop(String type) {
        return type.equals("上衣") || type.equals("T 恤") || type.equals("衬衫") || type.equals("针织衫")
                || type.equals("背心") || type.equals("吊带") || type.equals("外套") || type.equals("风衣")
                || type.equals("西装") || type.equals("夹克");
    }

    static boolean isOuter(String type) {
        return type.equals("外套") || type.equals("风衣") || type.equals("西装") || type.equals("夹克");
    }

    private List<Wheel> visibleWheels() {
        return threePiece ? java.util.Arrays.asList(wheels) : java.util.Arrays.asList(wheels[1], wheels[2]);
    }

    private void rebuildPools() {
        if (wheels == null) return;
        for (Wheel wheel : wheels) {
            long selected = wheel.selected() == null ? 0 : wheel.selected().id;
            wheel.cancel();
            wheel.items.clear();
            for (var garment : pool) {
                if (garment.archived || garment.stored) continue;
                if (seasonFilter >= 0 && (garment.seasons & (1 << seasonFilter)) == 0) continue;
                if (!colorFilter.isEmpty() && garment.colors.stream()
                        .noneMatch(c -> GarmentPicker.family(c).equals(colorFilter))) continue;
                boolean belongs = wheel.row == 0 ? isOuter(garment.type)
                        : wheel.row == 2 ? isBottom(garment.type) : isTop(garment.type) && (!threePiece || !isOuter(garment.type));
                if (belongs) wheel.items.add(garment);
            }
            wheel.index = 0;
            boolean retained = false;
            for (int i = 0; i < wheel.items.size(); i++) if (wheel.items.get(i).id == selected) { wheel.index = i; retained = true; }
            if (!retained) wheel.locked = false;
            wheel.setVisibility(wheel.row == 0 && !threePiece ? View.GONE : View.VISIBLE);
            wheel.update();
        }
        if (!threePiece && active == 0) active = 1;
        stage.requestLayout();
    }

    private void updateControls() {
        if (layersIcon == null) return;
        layersIcon.filled = threePiece;
        layersIcon.setContentDescription(threePiece ? "切换至两件模式" : "切换至三件模式");
        layersIcon.setSelected(threePiece);
        motionIcon.filled = motionEnabled;
        motionIcon.setSelected(motionEnabled);
        motionIcon.setContentDescription(motionEnabled ? "暂停体感" : "恢复体感");
        filterIcon.filled = seasonFilter >= 0 || !colorFilter.isEmpty();
        layersIcon.invalidate(); motionIcon.invalidate(); filterIcon.invalidate();
    }

    private void toggleLayers() {
        if (shuffling || naming || filtering) return;
        stage.focusFrame.cancel();
        android.transition.ChangeBounds transition = new android.transition.ChangeBounds();
        transition.setDuration(220);
        transition.setInterpolator(t -> 1 - (float) Math.pow(1 - t, 3));
        android.transition.TransitionManager.beginDelayedTransition(stage, transition);
        stage.animateFocusOnce = true;
        threePiece = !threePiece;
        rebuildPools();
        updateControls();
        motion.release(); tick();
    }

    private void toggleMotion() {
        motionEnabled = !motionEnabled;
        sensors.unregisterListener(this);
        if (motionEnabled && resumed) registerSensors();
        updateControls(); tick();
    }

    private void showFilters() {
        if (shuffling || naming || filtering) return;
        filtering = true;
        for (Wheel wheel : wheels) wheel.finishAnimation();
        Dialog dialog = new Dialog(this);
        filterDialog = dialog;
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(20), dp(24), dp(20));
        panel.setBackground(border(Color.WHITE, 12, 0xffdcdcdc));
        panel.addView(text("筛选", 20));
        TextView seasonHeading = text("季节", 13);
        seasonHeading.setPadding(0, dp(20), 0, dp(8)); panel.addView(seasonHeading);
        int[] pendingSeason = {seasonFilter};
        String[] pendingColor = {colorFilter};
        List<TextView> seasonButtons = new ArrayList<>();
        LinearLayout seasons = row();
        String[] seasonLabels = {"全部", "春", "夏", "秋", "冬"};
        for (int i = 0; i < seasonLabels.length; i++) {
            final int choice = i - 1;
            TextView item = button(seasonLabels[i], false, () -> {});
            seasonButtons.add(item);
            item.setOnClickListener(view -> { pendingSeason[0] = choice; selectFilter(seasonButtons, choice + 1); });
            seasons.addView(item, new LinearLayout.LayoutParams(0, dp(44), 1));
        }
        selectFilter(seasonButtons, seasonFilter + 1);
        panel.addView(seasons);
        TextView colorHeading = text("颜色", 13);
        colorHeading.setPadding(0, dp(18), 0, dp(8)); panel.addView(colorHeading);
        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout colors = row();
        scroll.addView(colors);
        drawColorFilters(colors, pendingColor);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, dp(48)));
        LinearLayout actions = row(); actions.setGravity(Gravity.END); actions.setPadding(0, dp(18), 0, 0);
        actions.addView(button("重置", false, () -> {
            pendingSeason[0] = -1;
            pendingColor[0] = "";
            selectFilter(seasonButtons, 0);
            drawColorFilters(colors, pendingColor);
        }), new LinearLayout.LayoutParams(dp(64), dp(44)));
        actions.addView(button("应用", true, () -> {
            seasonFilter = pendingSeason[0]; colorFilter = pendingColor[0];
            rebuildPools(); updateControls(); dialog.dismiss();
        }), new LinearLayout.LayoutParams(dp(64), dp(44)));
        panel.addView(actions); dialog.setContentView(panel);
        dialog.setOnDismissListener(d -> { filterDialog = null; filtering = false; motion.release(); });
        dialog.show();
        dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        dialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(40), -2);
    }

    private void drawColorFilters(LinearLayout row, String[] chosen) {
        row.removeAllViews();
        TextView all = text("ALL", 14);
        all.setGravity(Gravity.CENTER);
        all.setMinWidth(dp(48));
        all.setMinHeight(dp(48));
        all.setSelected(chosen[0].isEmpty());
        all.setTextColor(all.isSelected() ? INK : 0xff686868);
        all.setTypeface(null, all.isSelected() ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        all.setContentDescription(UiLanguage.text(this, "全部颜色"));
        all.setOnClickListener(view -> { chosen[0] = ""; drawColorFilters(row, chosen); });
        all.setFocusable(true);
        Motion.press(all);
        row.addView(all, new LinearLayout.LayoutParams(dp(48), dp(48)));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        for (int i = 0; i < GarmentPicker.FAMILIES.length; i++) {
            String family = GarmentPicker.FAMILIES[i];
            FrameLayout target = new FrameLayout(this);
            target.setSelected(family.equals(chosen[0]));
            target.setContentDescription(UiLanguage.text(this, family
                    + (target.isSelected() ? "，已选中" : "")));
            if (target.isSelected()) {
                View ring = new View(this);
                GradientDrawable outline = new GradientDrawable();
                outline.setShape(GradientDrawable.OVAL);
                outline.setColor(Color.TRANSPARENT);
                outline.setStroke(dp(2), INK);
                ring.setBackground(outline);
                target.addView(ring, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));
            }
            View ball = new View(this);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(GarmentPicker.FAMILY_COLORS[i]);
            if (family.equals("白色")) fill.setStroke(dp(1), 0xffbdbdbd);
            ball.setBackground(fill);
            target.addView(ball, new FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER));
            target.setOnClickListener(view -> { chosen[0] = family; drawColorFilters(row, chosen); });
            target.setFocusable(true);
            Motion.press(target);
            row.addView(target, new LinearLayout.LayoutParams(dp(48), dp(48)));
            row.addView(new View(this), new LinearLayout.LayoutParams(dp(6), 1));
        }
    }

    private void selectFilter(List<TextView> buttons, int selected) {
        for (int i = 0; i < buttons.size(); i++) {
            TextView button = buttons.get(i); boolean chosen = i == selected;
            button.setSelected(chosen); button.setTextColor(chosen ? Color.WHITE : INK);
            button.setBackground(outline(chosen ? INK : Color.WHITE, 4, false));
        }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (motionEnabled) registerSensors();
        stage.requestLayout();
    }

    private void registerSensors() {
        motion.reset();
        gravityReady = false;
        readyAt = SystemClock.elapsedRealtime() + 300;
        lastAccelerationTime = 0;
        if (postureSensor != null) sensors.registerListener(this, postureSensor, SensorManager.SENSOR_DELAY_GAME);
        if (linearSensor != null) sensors.registerListener(this, linearSensor, SensorManager.SENSOR_DELAY_GAME);
        else if (accelerationSensor != null && accelerationSensor != postureSensor)
            sensors.registerListener(this, accelerationSensor, SensorManager.SENSOR_DELAY_GAME);
    }

    @Override protected void onPause() {
        resumed = false;
        sensors.unregisterListener(this);
        animationHandler.removeCallbacksAndMessages(null);
        for (Wheel wheel : wheels) wheel.cancel();
        stage.focusFrame.cancel();
        diceIcon.animate().cancel();
        shuffling = false;
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        sensors.unregisterListener(this);
        if (nameDialog != null) nameDialog.dismiss();
        if (filterDialog != null) filterDialog.dismiss();
        loader.shutdownNow();
        db.close();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("active", active);
        state.putBoolean("threePiece", threePiece);
        state.putBoolean("motionEnabled", motionEnabled);
        state.putInt("seasonFilter", seasonFilter);
        state.putString("colorFilter", colorFilter);
        for (Wheel wheel : wheels) {
            if (wheel.selected() != null) state.putLong("selected" + wheel.row, wheel.selected().id);
            state.putBoolean("locked" + wheel.row, wheel.locked);
        }
    }

    @Override public void onSensorChanged(SensorEvent event) {
        if (!resumed || !motionEnabled || naming || filtering) return;
        long now = SystemClock.elapsedRealtime();
        boolean accelerometer = event.sensor.getType() == Sensor.TYPE_ACCELEROMETER;
        float x = event.values[0], y = event.values[1], z = event.values[2];
        if (event.sensor == postureSensor || accelerometer && linearSensor == null) {
            if (accelerometer) {
                // Time-based low-pass estimate supports devices with no gravity/linear sensors.
                float a = lastAccelerationTime == 0 ? 0 : (float) Math.exp(-(event.timestamp - lastAccelerationTime) / 200_000_000.0);
                lastAccelerationTime = event.timestamp;
                for (int i = 0; i < 3; i++) gravity[i] = gravityReady ? a * gravity[i] + (1 - a) * event.values[i] : event.values[i];
                gravityReady = true;
                if (linearSensor == null && now >= readyAt) {
                    float dx = x - gravity[0], dy = y - gravity[1], dz = z - gravity[2];
                    motion.acceleration((float) Math.sqrt(dx * dx + dy * dy + dz * dz), now);
                }
                x = gravity[0]; y = gravity[1]; z = gravity[2];
            }
            if (event.sensor == postureSensor) {
                float pitch = (float) Math.toDegrees(Math.atan2(z, y));
                // Positive roll means the right edge is lowered in portrait orientation.
                float roll = (float) Math.toDegrees(Math.atan2(-x, Math.hypot(y, z)));
                if (now >= readyAt && !shuffling) motion.posture(pitch, roll, now);
            }
        }
        if (event.sensor == linearSensor && now >= readyAt)
            motion.acceleration((float) Math.sqrt(x * x + y * y + z * z), now);
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    @Override public void step(int direction) {
        if (!shuffling && !naming && !filtering && wheels[active].move(direction, 220, null)) tick();
    }
    @Override public void focus(int direction) {
        int target = threePiece ? Math.max(0, Math.min(2, active + (direction == 0 ? -1 : 1))) : direction + 1;
        selectRow(target);
    }
    private void selectRow(int row) {
        if (naming || shuffling || filtering || active == row) return;
        active = row;
        for (Wheel wheel : wheels) wheel.update();
        stage.focusFrame.move(true);
    }
    @Override public void shuffle() {
        if (shuffling || naming || filtering || !resumed) return;
        diceIcon.animate().cancel();
        diceIcon.animate().rotationBy(180).setDuration(240).setInterpolator(t -> 1 - (1 - t) * (1 - t)).start();
        boolean movable = false;
        for (Wheel wheel : visibleWheels()) if (!wheel.locked && wheel.items.size() > 1) movable = true;
        if (!movable) { tick(); return; }
        shuffling = true;
        tick();
        for (Wheel wheel : wheels) wheel.cancel();
        spin(0);
    }

    private void spin(int round) {
        if (!resumed) return;
        int duration = 55 + round * 18;
        for (Wheel wheel : visibleWheels()) {
            if (wheel.locked || wheel.items.size() < 2) continue;
            if (round == 5) {
                int target = random.nextInt(wheel.items.size());
                wheel.moveTo(target, 220);
            } else wheel.move(1, duration, null);
        }
        if (round < 5) animationHandler.postDelayed(() -> spin(round + 1), duration + 20);
        else animationHandler.postDelayed(() -> { shuffling = false; motion.release(); }, 240);
    }

    private void nameOutfit() {
        if (shuffling || naming || filtering) return;
        for (Wheel wheel : visibleWheels()) if (wheel.selected() == null) { toast("请先添加" + wheel.label); return; }
        for (Wheel wheel : wheels) wheel.finishAnimation();
        naming = true;
        motion.release();
        nameDialog = new Dialog(this);
        nameDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout panel = column();
        panel.setPadding(dp(24), dp(24), dp(24), dp(20));
        panel.setBackground(outline(Color.WHITE, 0, true));
        panel.addView(text("保存穿搭", 20));
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setTextColor(INK);
        input.setHintTextColor(INK);
        input.setHint(UiLanguage.text(this, "穿搭名称"));
        input.setTextSize(16);
        input.setPadding(0, dp(16), 0, dp(12));
        input.setBackgroundColor(Color.WHITE);
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(60)});
        panel.addView(input, new LinearLayout.LayoutParams(-1, dp(64)));
        View rule = new View(this);
        rule.setBackgroundColor(INK);
        panel.addView(rule, new LinearLayout.LayoutParams(-1, 1));
        LinearLayout actions = row();
        actions.setPadding(0, dp(20), 0, 0);
        actions.setGravity(Gravity.END);
        actions.addView(button("取消", false, nameDialog::dismiss), new LinearLayout.LayoutParams(dp(64), dp(40)));
        TextView confirm = button("保存", true, () -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) { input.setError(UiLanguage.text(this, "请输入穿搭名称")); return; }
            WardrobeStore.Outfit outfit = new WardrobeStore.Outfit();
            outfit.name = name;
            List<Wheel> selected = visibleWheels();
            for (int i = 0; i < selected.size(); i++) {
                float y = threePiece ? new float[]{.17f, .44f, .78f}[i] : new float[]{.28f, .73f}[i];
                outfit.layers.add(new WardrobeStore.Layer(selected.get(i).selected().id, .5f, y, threePiece ? .30f : .46f));
            }
            db.saveOutfit(outfit, 0, null, false);
            nameDialog.dismiss();
            toast("已保存穿搭");
        });
        actions.addView(confirm, new LinearLayout.LayoutParams(dp(64), dp(40)));
        panel.addView(actions);
        nameDialog.setContentView(panel);
        nameDialog.setOnDismissListener(dialog -> { naming = false; motion.release(); });
        nameDialog.show();
        nameDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        nameDialog.getWindow().setLayout(getResources().getDisplayMetrics().widthPixels - dp(48), -2);
        input.requestFocus();
        nameDialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }

    private Bitmap photo(WardrobeStore.Garment garment) {
        String file = garment.photo;
        Bitmap bitmap = photos.get(file);
        if (bitmap == null && !file.isEmpty() && loading.add(file)) {
            loader.execute(() -> {
                Bitmap result = trimTransparent(db.image(file, 2));
                handler.post(() -> {
                    if (destroyed) return;
                    if (result != null) photos.put(file, result);
                    // Retain failed keys so missing files are not retried on every frame.
                    if (result != null) loading.remove(file);
                    for (Wheel wheel : wheels) wheel.album.invalidate();
                    stage.requestLayout();
                });
            });
        }
        return bitmap;
    }

    private Bitmap trimTransparent(Bitmap bitmap) {
        if (bitmap == null || !bitmap.hasAlpha()) return bitmap;
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int left = width, right = -1, top = height, bottom = -1;
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) if ((pixels[y*width+x] >>> 24) > 8) {
            left = Math.min(left, x); right = Math.max(right, x); top = Math.min(top, y); bottom = Math.max(bottom, y);
        }
        if (right < left || left == 0 && top == 0 && right == width-1 && bottom == height-1) return bitmap;
        return Bitmap.createBitmap(bitmap, left, top, right-left+1, bottom-top+1);
    }

    private final class Stage extends FrameLayout {
        final FocusFrame focusFrame = new FocusFrame();
        boolean animateFocusOnce;
        Stage() { super(MotionOutfitActivity.this); setClipChildren(false); setClipToPadding(false); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            if (wheels == null) return;
            int gap = dp(12), count = visibleWheels().size();
            int side = Math.max(1, Math.min(dp(threePiece ? 165 : 230),
                    Math.min(width - dp(48), (height - dp(24) - gap * (count - 1)) / count)));
            for (Wheel wheel : visibleWheels()) wheel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY));
            focusFrame.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            if (wheels == null) return;
            int gap = dp(12), total = gap * (visibleWheels().size() - 1);
            for (Wheel wheel : visibleWheels()) total += wheel.getMeasuredHeight();
            int y = (getHeight() - total) / 2;
            for (Wheel wheel : visibleWheels()) {
                wheel.layout(0, y, getWidth(), y + wheel.getMeasuredHeight());
                y += wheel.getMeasuredHeight() + gap;
            }
            focusFrame.layout(0, 0, getWidth(), getHeight());
            focusFrame.move(animateFocusOnce);
            animateFocusOnce = false;
        }
    }

    private final class FocusFrame extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF current = new RectF(), target = new RectF();
        final Path corners = new Path();
        ValueAnimator animator;
        FocusFrame() {
            super(MotionOutfitActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(getResources().getDisplayMetrics().density * 1.5f); paint.setColor(INK);
            paint.setStrokeCap(Paint.Cap.BUTT); paint.setStrokeJoin(Paint.Join.MITER);
        }
        void cancel() { if (animator != null) animator.cancel(); animator = null; }
        void move(boolean animate) {
            if (!animate && animator != null && animator.isRunning()) return;
            if (wheels == null || wheels[active].getHeight() == 0) return;
            Wheel wheel = wheels[active];
            wheel.album.centerBounds(target);
            target.offset(0, wheel.getTop());
            if (target.equals(current) && !animate) return;
            cancel();
            if (!animate || current.isEmpty() || current.width() != target.width() || current.height() != target.height()) {
                current.set(target); invalidate(); return;
            }
            final float left = target.left;
            animator = ValueAnimator.ofFloat(current.top, target.top);
            animator.setDuration(220);
            animator.setInterpolator(p -> 1 - (float) Math.pow(1 - p, 3));
            animator.addUpdateListener(value -> {
                current.offsetTo(left, (float) value.getAnimatedValue());
                invalidate();
            });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) { stage.requestLayout(); }
            });
            animator.start();
        }
        @Override protected void onDraw(Canvas canvas) {
            if (current.isEmpty() || wheels[active].items.isEmpty()) return;
            float inset = paint.getStrokeWidth()/2, arm = dp(12);
            float l = current.left+inset, t = current.top+inset, r = current.right-inset, b = current.bottom-inset;
            corners.reset();
            corners.moveTo(l, t+arm); corners.lineTo(l, t); corners.lineTo(l+arm, t);
            corners.moveTo(r-arm, t); corners.lineTo(r, t); corners.lineTo(r, t+arm);
            corners.moveTo(l, b-arm); corners.lineTo(l, b); corners.lineTo(l+arm, b);
            corners.moveTo(r-arm, b); corners.lineTo(r, b); corners.lineTo(r, b-arm);
            canvas.drawPath(corners, paint);
        }
    }

    private final class Wheel extends FrameLayout {
        final int row;
        final String label;
        final List<WardrobeStore.Garment> items;
        final Album album;
        final Icon pin;
        TextView emptyButton;
        int index;
        boolean locked;
        float offset, downX, downY;
        ValueAnimator animator;

        Wheel(int row, String label, List<WardrobeStore.Garment> items) {
            super(MotionOutfitActivity.this);
            this.row = row; this.label = label; this.items = items;
            album = new Album(this);
            FrameLayout.LayoutParams imageParams = new FrameLayout.LayoutParams(-1, -1);
            addView(album, imageParams);
            pin = new Icon(PIN);
            FrameLayout.LayoutParams pinParams = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
            addView(pin, pinParams);
            pin.setOnClickListener(view -> {
                if (shuffling || naming || filtering) return;
                selectRow(row);
                locked = !locked;
                pin.filled = locked;
                update();
                tick();
            });
            if (items.isEmpty()) {
                TextView add = button(UiLanguage.english(MotionOutfitActivity.this)
                        ? "Add " + UiLanguage.text(MotionOutfitActivity.this, label) : "添加" + label, false, () -> {
                    if (seasonFilter >= 0 || !colorFilter.isEmpty()) { showFilters(); return; }
                    setResult(RESULT_OK, new Intent().putExtra("type", row == 0 ? "外套" : row == 2 ? "长裤" : "上衣"));
                    MotionOutfitActivity.this.finish();
                });
                emptyButton = add;
                addView(add, new FrameLayout.LayoutParams(dp(140), dp(48), Gravity.CENTER));
            }
            setFocusable(true);
            setOnClickListener(view -> selectRow(row));
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    info.setScrollable(items.size() > 1);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                        selectRow(row);
                        step(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }

        WardrobeStore.Garment selected() { return items.isEmpty() ? null : items.get(index); }
        void update() {
            album.setAlpha(1f);
            if (emptyButton != null) {
                emptyButton.setVisibility(items.isEmpty() ? VISIBLE : GONE);
                emptyButton.setText(seasonFilter >= 0 || !colorFilter.isEmpty() ? "调整筛选"
                        : UiLanguage.english(MotionOutfitActivity.this)
                            ? "Add " + UiLanguage.text(MotionOutfitActivity.this, label) : "添加" + label);
            }
            pin.setVisibility(items.isEmpty() ? GONE : VISIBLE);
            pin.filled = locked;
            pin.setContentDescription(UiLanguage.english(MotionOutfitActivity.this)
                    ? (locked ? "Unlock " : "Lock ") + UiLanguage.text(MotionOutfitActivity.this, label)
                    : (locked ? "解锁" : "锁定") + label);
            pin.setSelected(locked);
            pin.invalidate();
            setContentDescription(UiLanguage.english(MotionOutfitActivity.this)
                    ? UiLanguage.text(MotionOutfitActivity.this, label)
                            + (selected() == null ? ", empty" : ", " + selected().name)
                            + (active == row ? ", active" : ", tap to activate")
                    : label + (selected() == null ? "为空" : "，" + selected().name)
                            + (active == row ? "，当前激活" : "，点击激活"));
            album.invalidate();
            if (stage != null) stage.requestLayout();
        }

        boolean move(int direction, int duration, Runnable done) {
            if (items.size() < 2) return false;
            float remainder = 0;
            if (animator != null) {
                // A 160 ms repeat may arrive before the 220 ms transition ends.
                // Rebase the running album without dropping the step or jumping its position.
                remainder = offset - album.direction;
                animator.end();
            }
            offset = remainder;
            animateTo(Math.floorMod(index + direction, items.size()), direction, duration, done);
            return true;
        }

        void moveTo(int target, int duration) {
            if (target == index) return;
            // Final random destination enters from the next position of the album.
            animateTo(target, 1, duration, null);
        }

        private void animateTo(int target, int direction, int duration, Runnable done) {
            album.incoming = target;
            album.direction = direction;
            animator = ValueAnimator.ofFloat(offset, direction);
            animator.setDuration(duration);
            animator.setInterpolator(t -> 1 - (float) Math.pow(1 - t, 3));
            animator.addUpdateListener(animation -> { offset = (float) animation.getAnimatedValue(); album.invalidate(); });
            animator.addListener(new android.animation.AnimatorListenerAdapter() {
                private boolean cancelled;
                @Override public void onAnimationCancel(android.animation.Animator animation) { cancelled = true; }
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    if (!cancelled) index = target;
                    offset = 0;
                    animator = null;
                    album.incoming = -1;
                    update();
                    if (!cancelled && done != null) done.run();
                }
            });
            animator.start();
        }

        void cancel() { if (animator != null) animator.cancel(); }
        void finishAnimation() { if (animator != null) animator.end(); }

        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);
            RectF bounds = new RectF(); album.centerBounds(bounds);
            int x = Math.round(bounds.right) - dp(46);
            pin.layout(x, 0, x + dp(48), dp(48));
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); }
            return event.getActionMasked() == MotionEvent.ACTION_MOVE
                    && Math.abs(event.getX() - downX) > ViewConfiguration.get(MotionOutfitActivity.this).getScaledTouchSlop()
                    && Math.abs(event.getX() - downX) > Math.abs(event.getY() - downY);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                selectRow(row);
                float dx = event.getX() - downX;
                if (Math.abs(dx) > dp(32) && Math.abs(dx) > Math.abs(event.getY() - downY)) step(dx < 0 ? 1 : -1);
                else performClick();
                return true;
            }
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }

    private final class Album extends View {
        final Wheel wheel;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final RectF bounds = new RectF();
        int incoming = -1, direction;
        Album(Wheel wheel) { super(MotionOutfitActivity.this); this.wheel = wheel; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        void centerBounds(RectF out) {
            float side = getHeight();
            out.set((getWidth()-side)/2, 0, (getWidth()+side)/2, side);
        }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (wheel.items.isEmpty()) return;
            for (int slot = -2; slot <= 2; slot++) {
                if (wheel.items.size() == 1 && slot != 0) continue;
                int item = Math.floorMod(wheel.index + slot, wheel.items.size());
                if (incoming >= 0 && slot == direction) item = incoming;
                float position = slot - wheel.offset;
                float proximity = Math.min(1, Math.abs(position));
                float scale = 1 - .20f * proximity;
                float alpha = 1 - .78f * proximity;
                float cy = getHeight() / 2f;
                Bitmap bitmap = photo(wheel.items.get(item));
                float w = Math.max(1, getHeight() - dp(24)) * scale, h = w;
                paint.setAlpha(Math.round(255 * alpha));
                if (bitmap != null) {
                    float ratio = Math.min(w / bitmap.getWidth(), h / bitmap.getHeight());
                    w = bitmap.getWidth() * ratio; h = bitmap.getHeight() * ratio;
                    // Anchor the neighboring image to the edge by its fitted width.
                    // Narrow trousers must remain visible just like wide tops.
                    float cx = albumX(position, w);
                    bounds.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
                    canvas.drawBitmap(bitmap, null, bounds, paint);
                } else {
                    float cx = albumX(position, w);
                    paint.setColor(INK);
                    paint.setAlpha(Math.round(255 * alpha));
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1);
                    float ph = h * .8f, pw = Math.min(w * .6f, ph * .7f);
                    canvas.drawRoundRect(cx-pw/2, cy-ph/2, cx+pw/2, cy+ph/2, dp(4), dp(4), paint);
                    paint.setStyle(Paint.Style.FILL);
                }
            }
            paint.setAlpha(255);
        }

        private float albumX(float position, float fittedWidth) {
            float peek = Math.min(dp(38), fittedWidth * .24f);
            float travel = getWidth() / 2f + fittedWidth / 2f - peek;
            return getWidth() / 2f + position * travel;
        }
    }

    private final class Icon extends View {
        final int kind;
        boolean filled, white;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final float[][] dots = {{8, 8}, {16, 8}, {12, 12}, {8, 16}, {16, 16}};
        Icon(int kind) { super(MotionOutfitActivity.this); this.kind = kind; setFocusable(true); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.save();
            float size = dp(20);
            canvas.translate((getWidth() - size) / 2, kind == PIN ? dp(12) : (getHeight() - size) / 2);
            canvas.scale(size / 24, size / 24);
            paint.setColor(white ? Color.WHITE : kind == MOTION && !filled ? 0xffaaaaaa : INK);
            // Canvas is scaled from a 24-unit vector; keep the resulting outline at 1 px.
            paint.setStrokeWidth(24f / size);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            if (kind == PIN) {
                path.reset();
                path.moveTo(8, 3); path.lineTo(16, 3); path.lineTo(15, 6); path.lineTo(15, 11);
                path.lineTo(18, 15); path.lineTo(6, 15); path.lineTo(9, 11); path.lineTo(9, 6); path.close();
                paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                canvas.drawPath(path, paint);
                canvas.drawLine(12, 15, 12, 22, paint);
            } else if (kind == DICE) {
                canvas.drawRoundRect(3, 3, 21, 21, 2, 2, paint);
                paint.setStyle(Paint.Style.FILL);
                for (float[] point : dots)
                    canvas.drawCircle(point[0], point[1], 1.25f, paint);
            } else if (kind == FILTER) {
                canvas.drawLine(3, 7, 21, 7, paint); canvas.drawLine(3, 17, 21, 17, paint);
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
                canvas.drawCircle(9, 7, 2.5f, paint); canvas.drawCircle(16, 17, 2.5f, paint);
                paint.setColor(INK); paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE);
                canvas.drawCircle(9, 7, 2.5f, paint); canvas.drawCircle(16, 17, 2.5f, paint);
            } else if (kind == CALIBRATE) {
                canvas.drawCircle(12, 12, 6, paint);
                canvas.drawLine(12, 2, 12, 7, paint); canvas.drawLine(12, 17, 12, 22, paint);
                canvas.drawLine(2, 12, 7, 12, paint); canvas.drawLine(17, 12, 22, 12, paint);
                paint.setStyle(Paint.Style.FILL); canvas.drawCircle(12, 12, 1, paint);
            } else if (kind == LAYERS) {
                path.reset(); path.moveTo(3, 8); path.lineTo(12, 3); path.lineTo(21, 8); path.lineTo(12, 13); path.close();
                paint.setStyle(filled ? Paint.Style.FILL : Paint.Style.STROKE); canvas.drawPath(path, paint);
                paint.setStyle(Paint.Style.STROKE);
                path.reset(); path.moveTo(3, 13); path.lineTo(12, 18); path.lineTo(21, 13); canvas.drawPath(path, paint);
                if (filled) { path.reset(); path.moveTo(3, 18); path.lineTo(12, 23); path.lineTo(21, 18); canvas.drawPath(path, paint); }
            } else if (kind == MOTION) {
                canvas.save(); canvas.rotate(-18, 12, 12);
                paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
                canvas.drawRoundRect(7, 3, 17, 21, 2, 2, paint);
                paint.setStyle(Paint.Style.STROKE); if (filled) paint.setColor(Color.WHITE);
                canvas.drawLine(10, 18, 14, 18, paint);
                canvas.restore();
                paint.setColor(filled ? INK : 0xffaaaaaa);
                canvas.drawArc(0, 4, 7, 18, 110, 100, false, paint);
                canvas.drawArc(17, 5, 24, 19, -70, 100, false, paint);
            }
            canvas.restore();
        }
    }

    private void tick() { root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
    private Icon icon(int kind, String label, Runnable action) {
        Icon view = new Icon(kind); view.setContentDescription(UiLanguage.text(this, label));
        view.setOnClickListener(v -> { tick(); action.run(); });
        return view;
    }
    private void toast(String message) { Toast.makeText(this, UiLanguage.text(this, message), Toast.LENGTH_SHORT).show(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout view = new LinearLayout(this); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private LinearLayout row() { LinearLayout view = new LinearLayout(this); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private TextView text(String label, int size) {
        TextView view = new UiLanguage.Label(this); view.setText(label); view.setTextSize(size); view.setTextColor(INK); return view;
    }
    private TextView button(String label, boolean solid, Runnable action) {
        TextView view = text(label, 14);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(solid ? Color.WHITE : INK);
        view.setBackground(outline(solid ? INK : Color.WHITE, solid ? 4 : 0, false));
        view.setOnClickListener(v -> action.run());
        return view;
    }
    private GradientDrawable outline(int color, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius));
        if (border) drawable.setStroke(1, INK);
        return drawable;
    }
    private GradientDrawable border(int color, int radius, int stroke) {
        GradientDrawable drawable = outline(color, radius, false); drawable.setStroke(1, stroke); return drawable;
    }
}
