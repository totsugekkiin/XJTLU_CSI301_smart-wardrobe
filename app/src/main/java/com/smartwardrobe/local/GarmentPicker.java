package com.smartwardrobe.local;

import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.smartwardrobe.local.MainActivity.INK;
import static com.smartwardrobe.local.MainActivity.MUTED;
import static com.smartwardrobe.local.MainActivity.STROKE;

final class GarmentPicker {
    record Filter(String query, String type, String color) {
        static final Filter ALL = new Filter("", "", "");

        boolean matches(WardrobeStore.Garment garment) {
            return (type.isEmpty() || type.equals(garment.type))
                    && (garment.name + " " + garment.brand).toLowerCase(Locale.ROOT).contains(query)
                    && (color.isEmpty() || garment.colors.stream().anyMatch(swatch -> color.equals(family(swatch))));
        }
    }

    static final String[] FAMILIES = {"黑色", "白色", "灰色", "蓝色", "大地色",
            "红色", "粉色", "橙色", "黄色", "绿色", "青色", "紫色"};
    static final int[] FAMILY_COLORS = {0xff242424, 0xffffffff, 0xff929292, 0xff4275bd,
            0xffa97953, 0xffca4b4b, 0xffdf99af, 0xffe48e42, 0xffddc34c, 0xff629666,
            0xff55a9b2, 0xff9271b0};

    static String family(WardrobeStore.Swatch swatch) {
        String name = ImageAnalysis.colorName(swatch.rgb);
        return name.equals("米色") || name.equals("棕色") ? "大地色" : name;
    }

    static void watch(EditText input, Runnable changed) {
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
    }

    static Sheet open(MainActivity ui, String title, List<WardrobeStore.Garment> garments, Filter initial,
                     Consumer<Filter> apply, BiConsumer<Filter, List<WardrobeStore.Garment>> selected,
                     boolean multiple) {
        return open(ui, title, garments, initial, apply, selected, multiple, null);
    }

    static void openForCanvas(MainActivity ui, String title, List<WardrobeStore.Garment> garments,
                              List<WardrobeStore.Garment> canvas, Consumer<WardrobeStore.Garment> selected) {
        open(ui, title, garments, Filter.ALL, null, (filter, items) -> selected.accept(items.get(0)),
                false, new CanvasPalette(garments, canvas));
    }

    private static Sheet open(MainActivity ui, String title, List<WardrobeStore.Garment> garments, Filter initial,
                             Consumer<Filter> apply, BiConsumer<Filter, List<WardrobeStore.Garment>> selected,
                             boolean multiple, CanvasPalette palette) {
        Sheet sheet = new Sheet(ui, title, true);
        LinearLayout tabs = ui.row();
        if (palette != null) {
            sheet.hideHeaderRule();
            sheet.content.addView(tabs);
            ui.gap(sheet.content, 12);
        }
        EditText query = ui.field(sheet.content, "", initial.query(), false, false);
        query.setHint("搜索名称或品牌");
        query.setContentDescription("搜索名称或品牌");
        StateListDrawable square = new StateListDrawable();
        square.addState(new int[]{android.R.attr.state_focused}, ui.shape(Color.WHITE, 0, INK));
        square.addState(new int[0], ui.shape(Color.WHITE, 0, STROKE));
        query.setBackground(square);
        LinearLayout categories = strip(ui, sheet.content);
        LinearLayout colors = strip(ui, sheet.content);
        String[] type = {initial.type()}, color = {initial.color()};
        Set<WardrobeStore.Garment> picked = new LinkedHashSet<>();
        TextView count = ui.text("已选 0 件", 14, INK, true);
        if (multiple) sheet.content.addView(count);
        LinearLayout wheelPanel = ui.vertical();
        if (palette != null) sheet.content.addView(wheelPanel);
        LinearLayout list = ui.vertical();
        sheet.content.addView(list);
        java.util.function.Supplier<Filter> filter = () ->
                new Filter(ui.value(query).toLowerCase(Locale.ROOT), type[0], color[0]);
        Runnable render = () -> {
            list.removeAllViews();
            Filter current = filter.get();
            for (var garment : garments) {
                if (!current.matches(garment)) continue;
                if (palette != null && !palette.matches(garment)) continue;
                if (multiple) {
                    CheckBox choice = new CheckBox(ui);
                    choice.setText(garment.name + " / " + UiLanguage.text(ui, garment.type));
                    choice.setMinHeight(ui.dp(48));
                    choice.setChecked(picked.contains(garment));
                    choice.setOnCheckedChangeListener((button, checked) -> {
                        if (checked) picked.add(garment);
                        else picked.remove(garment);
                        count.setText(UiLanguage.english(ui) ? picked.size() + " selected"
                                : "已选 " + picked.size() + " 件");
                    });
                    list.addView(choice);
                } else ui.closet.itemCard(list, garment, garment.type, () -> {
                    sheet.dismiss();
                    selected.accept(current, Collections.singletonList(garment));
                });
            }
            int countMatches = list.getChildCount();
            if (countMatches == 0) ui.empty(list, "无匹配单品", "");
            if (palette != null) palette.updateCount(ui, countMatches);
        };
        List<String> types = garments.stream().map(g -> g.type).filter(value -> !value.isEmpty()).distinct().sorted()
                .collect(java.util.stream.Collectors.toList());
        filters(ui, categories, "全部类别", types, type, render);
        colorFilters(ui, colors, color, render);
        watch(query, render);
        if (palette != null) palette.bind(ui, tabs, wheelPanel, render);
        else render.run();
        if (apply != null) {
            sheet.button("清除筛选", false, () -> { sheet.dismiss(); apply.accept(Filter.ALL); });
            sheet.button("应用筛选", true, () -> { sheet.dismiss(); apply.accept(filter.get()); });
        }
        if (multiple) sheet.button("下一步", true, () -> {
            if (picked.isEmpty()) { ui.toast("请至少选择一件单品"); return; }
            sheet.dismiss();
            selected.accept(filter.get(), new ArrayList<>(picked));
        });
        sheet.show();
        return sheet;
    }

    private static final class CanvasPalette {
        private final Map<Integer, String> colors = new LinkedHashMap<>();
        private final List<WardrobeStore.Garment> canvas;
        private final WardrobeStore.Swatch anchor;
        private int activeColor;
        private boolean enabled;
        private TextView summary;
        private View sample;

        CanvasPalette(List<WardrobeStore.Garment> garments, List<WardrobeStore.Garment> canvas) {
            this.canvas = canvas;
            anchor = canvas.stream().map(OutfitScreens::primaryColor)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            for (var garment : garments) {
                for (var swatch : garment.colors) colors.putIfAbsent(swatch.rgb, swatch.name);
            }
        }

        boolean matches(WardrobeStore.Garment garment) {
            return !enabled || garment.colors.stream().anyMatch(swatch -> swatch.rgb == activeColor);
        }

        void updateCount(MainActivity ui, int count) {
            if (!enabled || colors.isEmpty()) return;
            sample.setBackground(ui.shape(activeColor, 10, STROKE));
            String harmony = anchor == null ? "" : ClosetWheel.harmony(anchor.rgb, activeColor);
            summary.setText(UiLanguage.english(ui)
                    ? UiLanguage.text(ui, colors.get(activeColor)) + " · " + count
                            + (count == 1 ? " item" : " items")
                            + (harmony.isEmpty() ? "" : " · " + UiLanguage.text(ui, harmony))
                    : colors.get(activeColor) + " / " + count + " 件"
                            + (harmony.isEmpty() ? "" : " / " + harmony));
        }

        void bind(MainActivity ui, LinearLayout tabs, LinearLayout panel, Runnable refresh) {
            if (!canvas.isEmpty()) {
                panel.addView(ui.text("画布已有", 12, INK, true));
                LinearLayout chips = strip(ui, panel);
                for (var garment : canvas) {
                    LinearLayout chip = ui.row();
                    var swatch = OutfitScreens.primaryColor(garment);
                    if (swatch != null) {
                        View dot = ui.colorDot(swatch.rgb, 14);
                        dot.setContentDescription(swatch.name);
                        chip.addView(dot);
                        ui.space(chip, 5);
                    }
                    chip.addView(ui.userText(garment.name, 12, INK, true));
                    chips.addView(chip);
                    ui.space(chips, 6);
                }
            }
            if (colors.isEmpty()) {
                ui.empty(panel, "暂无颜色记录", "");
            } else {
                activeColor = colors.keySet().iterator().next();
                ClosetWheel wheel = new ClosetWheel(ui, new ArrayList<>(colors.keySet()), rgb -> {
                    activeColor = rgb;
                    refresh.run();
                });
                panel.addView(wheel, new LinearLayout.LayoutParams(-1, ui.dp(220)));
                wheel.select(activeColor);
                LinearLayout selection = ui.row();
                sample = ui.colorDot(activeColor, 20);
                summary = ui.text("", 13, INK, true);
                selection.addView(sample);
                ui.space(selection, 8);
                selection.addView(summary);
                panel.addView(selection);
                LinearLayout chips = strip(ui, panel);
                colors.forEach((rgb, label) -> {
                    chips.addView(ui.colorChip(label, rgb, () -> {
                        activeColor = rgb;
                        wheel.select(rgb);
                        refresh.run();
                    }));
                    ui.space(chips, 6);
                });
            }
            showTab(ui, tabs, panel, false, refresh);
        }

        private void showTab(MainActivity ui, LinearLayout tabs, LinearLayout panel,
                             boolean showWheel, Runnable refresh) {
            enabled = showWheel && !colors.isEmpty();
            tabs.removeAllViews();
            tabs.addView(tab(ui, "全部单品", !showWheel,
                            () -> showTab(ui, tabs, panel, false, refresh)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            tabs.addView(tab(ui, "圆盘色盘", showWheel,
                            () -> showTab(ui, tabs, panel, true, refresh)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            Motion.expand(panel, showWheel);
            refresh.run();
        }

        private TextView tab(MainActivity ui, String label, boolean selected, Runnable action) {
            TextView button = ui.pill(label, selected, action);
            ui.click(button, selected ? INK : Color.WHITE, 0, selected ? 0 : STROKE, action);
            return button;
        }
    }

    private static LinearLayout strip(MainActivity ui, LinearLayout parent) {
        HorizontalScrollView scroll = new HorizontalScrollView(ui);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        scroll.addView(row);
        parent.addView(scroll);
        ui.gap(parent, 6);
        return row;
    }

    static void filters(MainActivity ui, LinearLayout row, String all, List<String> options,
                                String[] value, Runnable refresh) {
        row.removeAllViews();
        categoryOption(ui, row, all, "", all, options, value, refresh);
        for (String option : options) categoryOption(ui, row, option, option, all, options, value, refresh);
    }

    private static void categoryOption(MainActivity ui, LinearLayout row, String label, String key, String all,
                                       List<String> options, String[] value, Runnable refresh) {
        boolean active = key.equals(value[0]);
        FrameLayout target = new FrameLayout(ui);
        target.setMinimumWidth(ui.dp(48));
        target.setContentDescription(label + (active ? "，已选中" : ""));
        target.setSelected(active);
        TextView text = ui.text(label, 13, active ? INK : 0xff888888, active);
        text.setGravity(Gravity.CENTER);
        text.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        target.addView(text, new FrameLayout.LayoutParams(-2, -1, Gravity.CENTER));
        if (active) {
            View marker = new View(ui);
            marker.setBackgroundColor(INK);
            marker.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams markerParams = new FrameLayout.LayoutParams(ui.dp(18), ui.dp(2),
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            markerParams.topMargin = ui.dp(4);
            target.addView(marker, markerParams);
        }
        ui.click(target, Color.TRANSPARENT, 0, 0, () -> {
            value[0] = key;
            filters(ui, row, all, options, value, refresh);
            refresh.run();
        });
        row.addView(target, new LinearLayout.LayoutParams(-2, ui.dp(48)));
        ui.space(row, 8);
    }

    private static void colorFilters(MainActivity ui, LinearLayout row, String[] value, Runnable refresh) {
        row.removeAllViews();
        boolean allSelected = value[0].isEmpty();
        TextView all = ui.text("ALL", 14, allSelected ? INK : MUTED, allSelected);
        all.setGravity(Gravity.CENTER);
        all.setMinWidth(ui.dp(48));
        all.setMinHeight(ui.dp(48));
        all.setContentDescription("全部颜色");
        all.setSelected(allSelected);
        ui.click(all, Color.TRANSPARENT, 0, 0, () -> {
            value[0] = "";
            colorFilters(ui, row, value, refresh);
            refresh.run();
        });
        row.addView(all, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.space(row, 6);
        for (int i = 0; i < FAMILIES.length; i++) {
            String family = FAMILIES[i];
            boolean selected = family.equals(value[0]);
            FrameLayout target = new FrameLayout(ui);
            target.setContentDescription(family);
            target.setSelected(selected);
            if (selected) {
                View ring = new View(ui);
                GradientDrawable outline = new GradientDrawable();
                outline.setShape(GradientDrawable.OVAL);
                outline.setColor(Color.TRANSPARENT);
                outline.setStroke(ui.dp(2), INK);
                ring.setBackground(outline);
                target.addView(ring, new FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.CENTER));
            }
            View ball = new View(ui);
            GradientDrawable fill = new GradientDrawable();
            fill.setShape(GradientDrawable.OVAL);
            fill.setColor(FAMILY_COLORS[i]);
            if (family.equals("白色")) fill.setStroke(ui.dp(1), 0xffbdbdbd);
            ball.setBackground(fill);
            target.addView(ball, new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
            ui.click(target, Color.TRANSPARENT, 0, 0, () -> {
                value[0] = family;
                colorFilters(ui, row, value, refresh);
                refresh.run();
            });
            row.addView(target, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            ui.space(row, 6);
        }
    }
}
