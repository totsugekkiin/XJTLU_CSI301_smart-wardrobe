package com.smartwardrobe.local;

import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
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

    private static final String[] FAMILIES = {"黑色", "白色", "灰色", "蓝色", "大地色",
            "红色", "粉色", "橙色", "黄色", "绿色", "青色", "紫色"};

    private static String family(WardrobeStore.Swatch swatch) {
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
        if (palette != null) sheet.content.addView(tabs);
        EditText query = ui.field(sheet.content, "搜索名称或品牌", initial.query(), false, false);
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
                    choice.setText(garment.name + " / " + garment.type);
                    choice.setMinHeight(ui.dp(48));
                    choice.setChecked(picked.contains(garment));
                    choice.setOnCheckedChangeListener((button, checked) -> {
                        if (checked) picked.add(garment);
                        else picked.remove(garment);
                        count.setText("已选 " + picked.size() + " 件");
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
        filters(ui, colors, "全部颜色", java.util.Arrays.asList(FAMILIES), color, render);
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
            summary.setText(colors.get(activeColor) + " / " + count + " 件"
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
                    chip.addView(ui.text(garment.name, 12, INK, true));
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
            tabs.addView(ui.pill("全部单品", !showWheel, () -> showTab(ui, tabs, panel, false, refresh)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            ui.space(tabs, 7);
            tabs.addView(ui.pill("圆盘色盘", showWheel, () -> showTab(ui, tabs, panel, true, refresh)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            Motion.expand(panel, showWheel);
            refresh.run();
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

    private static void filters(MainActivity ui, LinearLayout row, String all, List<String> options,
                                String[] value, Runnable refresh) {
        row.removeAllViews();
        chip(ui, row, all, "", all, options, value, refresh);
        for (String option : options) chip(ui, row, option, option, all, options, value, refresh);
    }

    private static void chip(MainActivity ui, LinearLayout row, String label, String key, String all,
                             List<String> options, String[] value, Runnable refresh) {
        TextView button = ui.pill(label, key.equals(value[0]), () -> {
            value[0] = key;
            filters(ui, row, all, options, value, refresh);
            refresh.run();
        });
        button.setMinHeight(ui.dp(48));
        button.setSelected(key.equals(value[0]));
        row.addView(button);
        ui.space(row, 6);
    }
}
