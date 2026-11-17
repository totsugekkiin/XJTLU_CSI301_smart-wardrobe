package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.CheckBox;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.smartwardrobe.local.MainActivity.*;

final class OutfitScreens {
    private final MainActivity ui;
    private final WardrobeStore db;
    private String sceneFilter = "";
    private YearMonth month = YearMonth.now();
    private LocalDate selectedDay = LocalDate.now();
    private Draft draft;
    private final List<Draft> otherDrafts = new ArrayList<>();

    OutfitScreens(MainActivity ui) {
        this.ui = ui;
        db = ui.db;
    }

    void collection() {
        List<WardrobeStore.Outfit> outfits = db.outfits();
        ui.page("穿搭", outfits.isEmpty() ? "" : String.format(java.util.Locale.ROOT, "%02d", outfits.size()), null,
                "＋ 新建搭配", () -> openDraft(null));
        if (draft != null) {
            ui.body.addView(ui.pill("继续编辑 · " + draft.label(), false, draft::render));
        }
        for (Draft pending : otherDrafts) {
            ui.body.addView(ui.pill("继续编辑 · " + pending.label(), false, () -> {
                selectDraft(pending);
                pending.render();
            }));
            ui.gap(ui.body, 8);
        }
        List<String> scenes = new ArrayList<>();
        scenes.add("全部场景");
        for (WardrobeStore.Outfit outfit : outfits) if (!outfit.scene.isEmpty() && !scenes.contains(outfit.scene)) scenes.add(outfit.scene);
        android.widget.HorizontalScrollView sceneScroll = new android.widget.HorizontalScrollView(ui);
        sceneScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout sceneBar = ui.row();
        for (String scene : scenes) {
            String filter = scene.equals("全部场景") ? "" : scene;
            TextView label = ui.ghost(filter.isEmpty() ? "全部" : filter, () -> { sceneFilter = filter; collection(); });
            label.setSelected(sceneFilter.equals(filter));
            if (!label.isSelected()) label.setTextColor(MUTED);
            sceneBar.addView(label);
        }
        sceneScroll.addView(sceneBar);
        ui.body.addView(sceneScroll);
        ui.rule(ui.body);
        ui.gap(ui.body, 10);
        boolean shown = false;
        for (WardrobeStore.Outfit outfit : outfits) {
            if (!sceneFilter.isEmpty() && !outfit.scene.equals(sceneFilter)) continue;
            shown = true;
            LinearLayout card = ui.card(ui.body);
            card.setPadding(0, 0, 0, 0);
            Map<Long, Bitmap> photos = photos(outfit);
            OutfitCanvas preview = new OutfitCanvas(ui, outfit.layers, photos::get, () -> {});
            preview.setInteractive(false);
            preview.setContentDescription("穿搭预览 · " + outfit.name);
            card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(236)));
            LinearLayout info = ui.vertical();
            info.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6));
            card.addView(info);
            info.addView(ui.editorial(outfit.name, 19));
            info.addView(ui.text((outfit.scene.isEmpty() ? "" : outfit.scene + " · ") + outfit.layers.size() + " 件单品", 12, MUTED, false));
            ui.gap(info, 10);
            LinearLayout actions = ui.row();
            actions.setBaselineAligned(false);
            actions.addView(ui.pill("记到日历", false, () -> record(outfit)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            ui.space(actions, 7);
            actions.addView(ui.ghost("编辑", () -> openDraft(outfit)));
            TextView delete = ui.destructiveButton("删除", () -> ui.confirmDestructive("删除穿搭",
                    "已记录的穿着历史会保留。", "删除穿搭", () -> {
                        db.deleteOutfit(outfit.id);
                        if (draft != null && draft.outfit.id == outfit.id) draft = null;
                        otherDrafts.removeIf(pending -> pending.outfit.id == outfit.id);
                        collection();
                    }));
            delete.setContentDescription("删除穿搭 " + outfit.name);
            actions.addView(delete);
            info.addView(actions);
        }
        if (!shown) ui.empty(ui.body, "暂无穿搭", "");
    }

    private void openDraft(WardrobeStore.Outfit outfit) {
        if (outfit != null) {
            if (draft != null && draft.outfit.id == outfit.id) {
                draft.render();
                return;
            }
            for (Draft pending : otherDrafts) {
                if (pending.outfit.id == outfit.id) {
                    selectDraft(pending);
                    pending.render();
                    return;
                }
            }
        }
        Draft opened = new Draft(outfit == null ? new WardrobeStore.Outfit() : outfit);
        selectDraft(opened);
        opened.render();
    }

    private void selectDraft(Draft selected) {
        if (draft == selected) return;
        otherDrafts.remove(selected);
        if (draft != null) otherDrafts.add(draft);
        draft = selected;
    }

    void palette(WardrobeStore.Garment base, Runnable back) { new Palette(base, back).render(); }

    private final class Palette {
        private final WardrobeStore.Garment base;
        private final Runnable back;
        private Draft target;
        private TextView targetLabel;
        private final Map<Long, TextView> addButtons = new LinkedHashMap<>();
        private WardrobeStore.Swatch baseColor;
        private Integer selected;
        private int direction = -1;
        private String type = "";
        private final String[] directions = {"同色系", "邻近色", "互补色", "中性色"};

        Palette(WardrobeStore.Garment base, Runnable back) {
            this.base = base;
            this.back = back;
            if (!base.colors.isEmpty()) baseColor = base.colors.stream().filter(s -> s.primary).findFirst().orElse(base.colors.get(0));
        }

        void render() {
            List<WardrobeStore.Garment> garments = db.garments();
            Map<Integer, String> palette = new LinkedHashMap<>();
            for (WardrobeStore.Garment g : garments) for (WardrobeStore.Swatch color : g.colors) {
                palette.putIfAbsent(color.rgb, color.name);
            }
            ui.page("衣橱色盘", base.name, back, "打开画布",
                    () -> withDraft(base, current -> current.render(this::render)));
            targetLabel = ui.text("", 14, INK, true);
            ui.body.addView(targetLabel);
            addButtons.clear();
            LinearLayout actions = ui.row();
            View chooseBase = baseColor == null ? ui.pill("设置基准色", false, this::chooseBaseColor)
                    : ui.colorChip("基准色 · " + baseColor.name, baseColor.rgb, this::chooseBaseColor);
            actions.addView(chooseBase, new LinearLayout.LayoutParams(0, -2, 1));
            ui.body.addView(actions);
            ui.section(ui.body, "全部衣橱颜色");
            if (palette.isEmpty()) ui.empty(ui.body, "暂无颜色", "");
            swatches(ui.body, palette, false);

            ui.section(ui.body, "配色建议");
            ui.actions(ui.body, directions, () -> suggest(0), () -> suggest(1), () -> suggest(2), () -> suggest(3));
            if (direction >= 0 && baseColor != null) {
                Map<Integer, String> suggestions = new LinkedHashMap<>();
                for (Map.Entry<Integer, String> s : palette.entrySet()) {
                    if (ImageAnalysis.matches(baseColor.rgb, s.getKey(), direction)) suggestions.put(s.getKey(), s.getValue());
                }
                if (!suggestions.isEmpty()) swatches(ui.body, suggestions, true);
            }

            ui.section(ui.body, selected == null ? (direction < 0 ? "衣橱单品" : directions[direction] + "单品")
                    : palette.getOrDefault(selected, ImageAnalysis.colorName(selected)) + "单品");
            ui.actions(ui.body, new String[]{type.isEmpty() ? "全部类别" : type, "清除颜色筛选"},
                    () -> ui.closet.selectType("全部类别", value -> { type = value; render(); }),
                    () -> { selected = null; direction = -1; render(); });
            boolean found = false;
            for (WardrobeStore.Garment g : garments) {
                if (g.id == base.id) continue;
                if (!type.isEmpty() && !g.type.equals(type)) continue;
                if (selected != null && g.colors.stream().noneMatch(s -> s.rgb == selected)) continue;
                if (selected == null && direction >= 0 && baseColor != null
                        && g.colors.stream().noneMatch(s -> ImageAnalysis.matches(baseColor.rgb, s.rgb, direction))) continue;
                found = true;
                LinearLayout card = ui.closet.itemCard(ui.body, g, g.type, null);
                TextView button = ui.pill("＋ 加入画布", false,
                        () -> withDraft(g, current -> ui.toast("已加入「" + current.label() + "」")));
                addButtons.put(g.id, button);
                card.addView(button);
            }
            if (!found) ui.empty(ui.body, "没有匹配的衣物", "");
            updateDraftStatus();
        }

        private void updateDraftStatus() {
            targetLabel.setText(target == null ? "从「" + base.name + "」开始搭配"
                    : "当前画布 · " + target.label());
            for (var entry : addButtons.entrySet()) {
                boolean added = target != null && target.contains(entry.getKey());
                entry.getValue().setText(added ? "已加入" : "＋ 加入画布");
                entry.getValue().setEnabled(!added);
            }
        }

        private void withDraft(WardrobeStore.Garment garment, Consumer<Draft> ready) {
            if (target != null) {
                addToDraft(target, garment, ready);
                return;
            }
            Runnable create = () -> {
                WardrobeStore.Outfit outfit = new WardrobeStore.Outfit();
                outfit.name = base.name + "搭配";
                addToDraft(new Draft(outfit), garment, ready);
            };
            if (draft == null) {
                create.run();
                return;
            }
            Draft existing = draft;
            ui.choose("选一个画布", new String[]{"用「" + base.name + "」新建",
                    "加入当前草稿「" + existing.label() + "」"}, choice -> {
                if (choice == 0) create.run();
                else addToDraft(existing, garment, ready);
            });
        }

        private void addToDraft(Draft current, WardrobeStore.Garment garment, Consumer<Draft> ready) {
            int needed = current.contains(base.id) ? 0 : 1;
            if (garment.id != base.id && !current.contains(garment.id)) needed++;
            if (current.outfit.layers.size() + needed > 20) {
                ui.toast("画布空间不足，一套穿搭最多 20 件");
                return;
            }
            if (!current.contains(base.id)) current.add(base);
            if (!current.contains(garment.id)) current.add(garment);
            target = current;
            selectDraft(current);
            updateDraftStatus();
            ready.accept(current);
        }

        private void chooseBaseColor() {
            if (base.colors.isEmpty()) { ui.toast("可在衣物档案补充颜色"); return; }
            Sheet sheet = new Sheet(ui, "选择配色基准", true);
            for (var color : base.colors) {
                sheet.option(color.name, color.rgb, () -> { baseColor = color; sheet.dismiss(); render(); });
            }
            sheet.show();
        }

        private void suggest(int choice) {
            if (baseColor == null) { ui.toast("请先为基准衣物添加颜色"); return; }
            selected = null;
            direction = choice;
            render();
        }

        private void swatches(LinearLayout parent, Map<Integer, String> colors, boolean suggestion) {
            LinearLayout row = null;
            int index = 0;
            for (Map.Entry<Integer, String> entry : colors.entrySet()) {
                if (index++ % 3 == 0) { row = ui.row(); parent.addView(row); ui.gap(parent, 6); }
                int color = entry.getKey();
                LinearLayout chip = ui.vertical();
                chip.setGravity(Gravity.CENTER);
                chip.setPadding(ui.dp(4), ui.dp(5), ui.dp(4), ui.dp(5));
                chip.addView(ui.colorDot(color, 22));
                chip.addView(ui.text(entry.getValue(), 13, INK, true));
                ui.click(chip, selected != null && selected == color ? SAND : CARD, 12, STROKE, () -> {
                    selected = color;
                    if (!suggestion) direction = -1;
                    render();
                });
                chip.setMinimumHeight(ui.dp(48));
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                p.rightMargin = ui.dp(6);
                row.addView(chip, p);
            }
        }
    }

    private final class Draft {
        final WardrobeStore.Outfit outfit;
        private final Map<Long, Bitmap> photos;
        private final Map<Long, String> names = new LinkedHashMap<>();
        private OutfitCanvas canvas;
        private EditText name, scene;
        private LinearLayout layerList, layerControls;
        private TextView layerHeading;

        Draft(WardrobeStore.Outfit outfit) {
            this.outfit = outfit;
            photos = photos(outfit);
            for (var layer : outfit.layers) {
                WardrobeStore.Garment garment = db.garment(layer.garment);
                if (garment != null) names.put(garment.id, garment.name);
            }
        }

        String label() { return outfit.name.isEmpty() ? "未命名穿搭" : outfit.name; }

        boolean contains(long garmentId) {
            return outfit.layers.stream().anyMatch(layer -> layer.garment == garmentId);
        }

        void render() { render(() -> ui.openTab(3)); }

        void render(Runnable back) {
            outfit.layers.removeIf(layer -> db.garment(layer.garment) == null);
            ui.page("搭配画布", label(), () -> leave(back), "保存", this::save);
            canvas = new OutfitCanvas(ui, outfit.layers, photos::get, this::updateSelection);
            ui.body.addView(canvas, new LinearLayout.LayoutParams(-1, ui.dp(360)));
            ui.gap(ui.body, 8);
            layerControls = ui.vertical();
            layerControls.setBackground(ui.shape(INK, 4, 0));
            ui.body.addView(layerControls);
            String[] labels = {"⊕\n放大", "⊖\n缩小", "↑\n置顶", "↓\n置底", "↻\n替换", "×\n移除"};
            Runnable[] actions = {() -> canvas.resize(1.15f), () -> canvas.resize(1 / 1.15f),
                    () -> move(true), () -> move(false), this::replace, this::remove};
            int width = ui.getResources().getConfiguration().screenWidthDp - 40;
            int columns = width >= labels.length * 48 ? labels.length : 3;
            LinearLayout toolRow = null;
            for (int i = 0; i < labels.length; i++) {
                if (i % columns == 0) {
                    toolRow = ui.row();
                    layerControls.addView(toolRow);
                }
                TextView tool = ui.pill(labels[i], true, actions[i]);
                tool.setContentDescription(labels[i].substring(labels[i].indexOf('\n') + 1));
                tool.setMinHeight(ui.dp(55));
                tool.setPadding(0, ui.dp(4), 0, ui.dp(4));
                toolRow.addView(tool, new LinearLayout.LayoutParams(0, -2, 1));
            }
            ui.gap(ui.body, 16);
            LinearLayout layerHeader = ui.row();
            layerHeader.setGravity(Gravity.CENTER_VERTICAL);
            layerHeading = ui.text("画布图层", 15, INK, true);
            layerHeading.setAccessibilityHeading(true);
            layerHeader.addView(layerHeading, new LinearLayout.LayoutParams(0, -2, 1));
            layerHeader.addView(ui.pill("＋ 添加单品", false, () -> chooseGarment(outfit.layers, null, this::add)));
            ui.body.addView(layerHeader);
            android.widget.HorizontalScrollView layers = new android.widget.HorizontalScrollView(ui);
            layers.setHorizontalScrollBarEnabled(false);
            layerList = ui.row();
            layers.addView(layerList);
            ui.body.addView(layers);
            updateSelection();

            LinearLayout form = ui.card(ui.body);
            name = ui.field(form, "穿搭名称 *", outfit.name, false, false);
            scene = ui.field(form, "场景标签", outfit.scene, false, false);
            form.addView(ui.pill("选择常用场景", false, () -> {
                String[] scenes = {"工作", "休闲", "派对", "运动"};
                ui.choose("选择场景", scenes, i -> scene.setText(scenes[i]));
            }));
            form.addView(ui.ghost("放弃本次编辑", () -> ui.prompt("放弃本次编辑",
                    "这次尚未保存的搭配和改动会丢失。", "放弃", () -> {
                        draft = null;
                        ui.openTab(3);
                    })));
            ui.body.setFocusableInTouchMode(true);
            ui.body.requestFocus();
        }

        private void leave(Runnable back) {
            captureFields();
            canvas = null;
            layerList = null;
            back.run();
        }

        private void captureFields() {
            if (name != null) outfit.name = ui.value(name);
            if (scene != null) outfit.scene = ui.value(scene);
        }

        void add(WardrobeStore.Garment g) {
            if (outfit.layers.size() >= 20) { ui.toast("一套穿搭最多添加 20 个图层"); return; }
            for (WardrobeStore.Layer layer : outfit.layers) if (layer.garment == g.id) {
                if (canvas != null) canvas.select(layer);
                return;
            }
            WardrobeStore.Layer layer = newLayer(g.id, outfit.layers.size());
            load(g);
            outfit.layers.add(layer);
            if (canvas != null) canvas.select(layer);
        }

        private void load(WardrobeStore.Garment g) {
            photos.put(g.id, db.image(g.photo, 4));
            names.put(g.id, g.name);
        }

        private void replace() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择一个图层"); return; }
            chooseGarment(outfit.layers, layer, g -> {
                if (outfit.layers.stream().anyMatch(item -> item != layer && item.garment == g.id)) {
                    ui.toast("该衣物已在画布上");
                    return;
                }
                load(g);
                layer.garment = g.id;
                canvas.select(layer);
            });
        }

        private void remove() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择一个图层"); return; }
            outfit.layers.remove(layer);
            canvas.select(null);
        }

        private void move(boolean top) {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择一个图层"); return; }
            outfit.layers.remove(layer);
            outfit.layers.add(top ? outfit.layers.size() : 0, layer);
            canvas.select(layer);
        }

        private void updateSelection() {
            if (layerList == null) return;
            WardrobeStore.Layer current = canvas.selected();
            layerHeading.setText("画布图层（" + outfit.layers.size() + "）");
            layerControls.setVisibility(current == null ? View.GONE : View.VISIBLE);
            layerList.removeAllViews();
            for (int i = outfit.layers.size() - 1; i >= 0; i--) {
                WardrobeStore.Layer layer = outfit.layers.get(i);
                LinearLayout piece = ui.vertical();
                piece.setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4));
                piece.addView(ui.image(photos.get(layer.garment), 64));
                piece.setSelected(current == layer);
                piece.setContentDescription(names.get(layer.garment));
                ui.click(piece, PAPER, 4, current == layer ? INK : STROKE, () -> canvas.select(layer));
                layerList.addView(piece, new LinearLayout.LayoutParams(ui.dp(66), -2));
                ui.space(layerList, 6);
            }
        }

        private void save() {
            captureFields();
            if (outfit.name.isEmpty() || outfit.layers.isEmpty()) { ui.toast("请填写穿搭名称并添加单品"); return; }
            db.save(outfit);
            draft = null;
            ui.openTab(3);
        }
    }

    private static WardrobeStore.Layer newLayer(long id, int position) {
        return new WardrobeStore.Layer(id, .32f + (position % 2) * .36f,
                .26f + (position % 6 / 2) * .24f, .44f);
    }

    private Map<Long, Bitmap> photos(WardrobeStore.Outfit outfit) {
        Map<Long, Bitmap> photos = new LinkedHashMap<>();
        for (WardrobeStore.Layer layer : outfit.layers) {
            WardrobeStore.Garment g = db.garment(layer.garment);
            if (g != null) photos.put(g.id, db.image(g.photo, 4));
        }
        return photos;
    }

    private void chooseGarment(List<WardrobeStore.Layer> layers, WardrobeStore.Layer replacing,
                               Consumer<WardrobeStore.Garment> selected) {
        List<WardrobeStore.Garment> garments = db.garments();
        if (garments.isEmpty()) { ui.toast("请先在衣橱添加衣物"); return; }
        Set<Long> canvasIds = layers.stream().map(layer -> layer.garment).collect(Collectors.toSet());
        Set<Long> occupied = layers.stream().filter(layer -> layer != replacing)
                .map(layer -> layer.garment).collect(Collectors.toSet());
        List<WardrobeStore.Garment> anchors = garments.stream().filter(g -> canvasIds.contains(g.id))
                .collect(Collectors.toList());
        WardrobeStore.Swatch anchor = anchors.isEmpty() ? null : primaryColor(anchors.get(0));
        Sheet sheet = new Sheet(ui, replacing == null ? "从衣橱添加单品" : "替换为", true);
        Map<Integer, String> colors = new LinkedHashMap<>();
        for (var g : garments) for (var color : g.colors) colors.putIfAbsent(color.rgb, color.name);
        Integer firstColor = garments.stream().filter(g -> !occupied.contains(g.id))
                .flatMap(g -> g.colors.stream()).map(color -> color.rgb).findFirst()
                .orElse(colors.keySet().stream().findFirst().orElse(null));
        LinearLayout wheelPanel = ui.vertical();
        if (!anchors.isEmpty()) showCanvasColors(wheelPanel, anchors);
        LinearLayout currentColor = ui.row();
        currentColor.setGravity(Gravity.CENTER_VERTICAL);
        View sample = ui.colorDot(PAPER, 20);
        currentColor.addView(sample);
        ui.space(currentColor, 8);
        TextView current = ui.text("", 13, INK, true);
        currentColor.addView(current);
        LinearLayout filters = ui.vertical();
        EditText query = ui.field(filters, "搜索名称或品牌", "", false, false);
        String[] category = {""};
        LinearLayout list = ui.vertical();
        Consumer<Integer> show = rgb -> {
            list.removeAllViews();
            String keyword = ui.value(query).toLowerCase(Locale.ROOT);
            List<WardrobeStore.Garment> matching = garments.stream()
                    .filter(g -> !occupied.contains(g.id))
                    .filter(g -> category[0].isEmpty() || g.type.equals(category[0]))
                    .filter(g -> matchesSearch(g, keyword))
                    .filter(g -> rgb == null || g.colors.stream().anyMatch(color -> color.rgb == rgb))
                    .collect(Collectors.toList());
            if (rgb != null) {
                String harmony = anchor == null ? "" : ClosetWheel.harmony(anchor.rgb, rgb);
                sample.setBackground(ui.shape(rgb, 10, STROKE));
                current.setText(colors.get(rgb) + "  ·  " + matching.size() + " 件"
                        + (harmony.isEmpty() ? "" : "  ·  " + harmony));
            }
            if (matching.isEmpty()) ui.empty(list, "没有可选衣物", "试试更改名称、类别或颜色筛选。");
            for (var g : matching) {
                LinearLayout card = ui.closet.itemCard(list, g, g.type, null);
                card.addView(ui.pill(replacing == null ? "加入画布" : "替换", false, () -> {
                    sheet.dismiss();
                    selected.accept(g);
                }));
            }
        };
        int[] activeColor = {firstColor == null ? 0 : firstColor};
        if (!colors.isEmpty()) {
            ClosetWheel wheel = new ClosetWheel(ui, new ArrayList<>(colors.keySet()), rgb -> {
                activeColor[0] = rgb;
                show.accept(rgb);
            });
            wheelPanel.addView(wheel, new LinearLayout.LayoutParams(-1, ui.dp(220)));
            wheelPanel.addView(currentColor);
            HorizontalScrollView strip = new HorizontalScrollView(ui);
            strip.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = ui.row();
            for (var c : colors.entrySet()) {
                LinearLayout chip = ui.colorChip(c.getValue(), c.getKey(), () -> {
                    wheel.select(c.getKey());
                    activeColor[0] = c.getKey();
                    show.accept(activeColor[0]);
                });
                chips.addView(chip);
                ui.space(chips, 6);
            }
            strip.addView(chips);
            wheelPanel.addView(strip);
            wheel.select(activeColor[0]);
        } else {
            ui.empty(wheelPanel, "衣橱暂无颜色记录", "");
        }
        boolean[] useColor = {false};
        Runnable refresh = () -> show.accept(useColor[0] ? activeColor[0] : null);
        onSearch(query, refresh);
        TextView categoryButton = ui.pill("全部类别", false, null);
        categoryButton.setOnClickListener(v -> ui.closet.selectType("全部类别", value -> {
            category[0] = value;
            categoryButton.setText(value.isEmpty() ? "全部类别" : value);
            refresh.run();
        }));
        filters.addView(categoryButton);
        LinearLayout tabs = ui.row();
        sheet.content.addView(tabs);
        sheet.content.addView(filters);
        Consumer<Boolean> switchTab = new Consumer<>() {
            @Override public void accept(Boolean useWheel) {
                useColor[0] = useWheel;
                tabs.removeAllViews();
                tabs.addView(ui.pill("全部单品", !useWheel, () -> accept(false)), new LinearLayout.LayoutParams(0, -2, 1));
                if (firstColor != null) {
                    ui.space(tabs, 7);
                    tabs.addView(ui.pill("圆盘色盘", useWheel, () -> accept(true)), new LinearLayout.LayoutParams(0, -2, 1));
                }
                wheelPanel.setVisibility(useWheel ? View.VISIBLE : View.GONE);
                refresh.run();
            }
        };
        ui.gap(sheet.content, 8);
        sheet.content.addView(wheelPanel);
        sheet.content.addView(list);
        switchTab.accept(false);
        sheet.show();
    }

    private void showCanvasColors(LinearLayout panel, List<WardrobeStore.Garment> anchors) {
        LinearLayout bar = ui.row();
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(ui.text("画布已有", 12, MUTED, true));
        ui.space(bar, 8);
        HorizontalScrollView strip = new HorizontalScrollView(ui);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = ui.row();
        for (var garment : anchors) {
            LinearLayout chip = ui.row();
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setBackground(ui.shape(PAPER, 4, STROKE));
            chip.setPadding(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(5));
            WardrobeStore.Swatch color = primaryColor(garment);
            if (color != null) {
                View dot = new View(ui);
                dot.setBackground(ui.shape(color.rgb, 6, STROKE));
                chip.addView(dot, new LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)));
                dot.setContentDescription(color.name);
                ui.space(chip, 5);
            }
            chip.addView(ui.text(garment.name, 12, INK, true));
            chips.addView(chip);
            ui.space(chips, 5);
        }
        strip.addView(chips);
        bar.addView(strip, new LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(bar);
    }

    private static WardrobeStore.Swatch primaryColor(WardrobeStore.Garment garment) {
        if (garment.colors.isEmpty()) return null;
        return garment.colors.stream().filter(color -> color.primary).findFirst().orElse(garment.colors.get(0));
    }

    private static boolean matchesSearch(WardrobeStore.Garment garment, String keyword) {
        return (garment.name + " " + garment.brand).toLowerCase(Locale.ROOT).contains(keyword);
    }

    private static void onSearch(EditText query, Runnable refresh) {
        query.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void chooseWearItems(LocalDate day) {
        List<WardrobeStore.Garment> garments = db.garments();
        if (garments.isEmpty()) {
            ui.prompt("衣橱暂无衣物", "先添加衣物，再记录当天穿着。", "去衣橱", () -> ui.openTab(0));
            return;
        }
        Sheet sheet = new Sheet(ui, "选择当天穿的衣物", true);
        EditText query = ui.field(sheet.content, "搜索名称或品牌", "", false, false);
        TextView count = ui.text("已选 0 件", 14, INK, true);
        sheet.content.addView(count);
        LinearLayout list = ui.vertical();
        sheet.content.addView(list);
        Set<Long> picked = new LinkedHashSet<>();
        Runnable refresh = () -> {
            list.removeAllViews();
            String keyword = ui.value(query).toLowerCase(Locale.ROOT);
            for (var garment : garments) {
                if (!matchesSearch(garment, keyword)) continue;
                CheckBox choice = new CheckBox(ui);
                choice.setText(garment.name + " · " + garment.type);
                choice.setMinHeight(ui.dp(48));
                choice.setChecked(picked.contains(garment.id));
                choice.setOnCheckedChangeListener((button, checked) -> {
                    if (checked) picked.add(garment.id);
                    else picked.remove(garment.id);
                    count.setText("已选 " + picked.size() + " 件");
                });
                list.addView(choice);
            }
            if (list.getChildCount() == 0) ui.empty(list, "没有匹配的衣物", "");
        };
        onSearch(query, refresh);
        refresh.run();
        sheet.button("下一步", true, () -> {
            if (picked.isEmpty()) {
                ui.toast("请至少选择一件衣物");
                return;
            }
            WardrobeStore.Outfit worn = new WardrobeStore.Outfit();
            worn.name = "当天穿着";
            for (var id : picked) worn.layers.add(newLayer(id, worn.layers.size()));
            sheet.dismiss();
            recordOn(worn, day);
        });
        sheet.show();
    }

    private void record(WardrobeStore.Outfit outfit) {
        if (outfit.layers.isEmpty()) { ui.toast("请先为这套穿搭添加单品"); return; }
        ui.date("实际穿着日期", LocalDate.now(), true, day -> recordOn(outfit, day));
    }

    private void recordOn(WardrobeStore.Outfit outfit, LocalDate day) {
        Sheet sheet = new Sheet(ui, "记录穿着 · " + day, true);
        EditText scene = ui.field(sheet.content, "场景", outfit.scene, false, false);
        CheckBox saveOutfit = new CheckBox(ui);
        EditText outfitName = null;
        if (outfit.id == 0 && outfit.layers.size() <= 20) {
            saveOutfit.setText("同时保存为穿搭");
            saveOutfit.setMinHeight(ui.dp(48));
            sheet.content.addView(saveOutfit);
            LinearLayout nameFields = ui.vertical();
            outfitName = ui.field(nameFields, "穿搭名称", "", false, false);
            nameFields.setVisibility(View.GONE);
            sheet.content.addView(nameFields);
            saveOutfit.setOnCheckedChangeListener((button, checked) ->
                    nameFields.setVisibility(checked ? View.VISIBLE : View.GONE));
        }
        EditText nameField = outfitName;
        sheet.button("保存记录", true, () -> {
            String occasion = ui.value(scene);
            if (nameField != null && saveOutfit.isChecked()) {
                String name = ui.value(nameField);
                if (name.isEmpty()) {
                    nameField.setError("请输入穿搭名称");
                    return;
                }
                outfit.name = name;
                outfit.scene = occasion;
                db.saveOutfitAndWear(outfit, day.toString());
            } else {
                db.wear(outfit, occasion, day.toString());
            }
            month = YearMonth.from(day);
            selectedDay = day;
            sheet.dismiss();
            ui.openTab(4);
        });
        sheet.show();
    }

    void calendar() {
        List<WardrobeStore.History> history = db.history(month.toString());
        ui.page("日历", "", null, "回到今天", () -> {
            selectedDay = LocalDate.now();
            month = YearMonth.from(selectedDay);
            calendar();
        });
        LinearLayout monthBar = ui.row();
        TextView prev = ui.ghost("‹", () -> switchMonth(-1));
        prev.setContentDescription("上个月");
        monthBar.addView(prev);
        TextView heading = ui.ghost(month.getYear() + " 年 " + month.getMonthValue() + " 月",
                () -> ui.date("查看日期", selectedDay, false, day -> { selectedDay = day; month = YearMonth.from(day); calendar(); }));
        heading.setTextSize(18);
        monthBar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        TextView next = ui.ghost("›", () -> switchMonth(1));
        next.setContentDescription("下个月");
        monthBar.addView(next);
        ui.body.addView(monthBar);
        ui.gap(ui.body, 9);
        ui.rule(ui.body);
        LinearLayout card = ui.vertical();
        CalendarGrid.addTo(ui, ui.body, card);
        CalendarGrid.populate(ui, card, month, selectedDay, null,
                day -> history.stream().anyMatch(h -> h.date().equals(day.toString())),
                day -> { selectedDay = day; calendar(); });
        ui.gap(ui.body, 12);
        ui.rule(ui.body);
        LinearLayout dayBar = ui.row();
        dayBar.setGravity(Gravity.CENTER_VERTICAL);
        dayBar.addView(ui.text(selectedDay + (selectedDay.equals(LocalDate.now()) ? "（今天）" : ""), 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        if (!selectedDay.isAfter(LocalDate.now())) dayBar.addView(ui.pill("登记当天穿着", false, () -> {
            List<WardrobeStore.Outfit> all = new ArrayList<>();
            for (var outfit : db.outfits()) if (!outfit.layers.isEmpty()) all.add(outfit);
            LocalDate day = selectedDay;
            if (all.isEmpty()) {
                chooseWearItems(day);
                return;
            }
            ui.choose("登记当天穿着", new String[]{"从衣橱选单品", "选择已保存的穿搭"}, mode -> {
                if (mode == 0) chooseWearItems(day);
                else ui.choose("选择已保存的穿搭", all.stream().map(o -> o.name).toArray(String[]::new),
                        i -> recordOn(all.get(i), day));
            });
        }));
        ui.body.addView(dayBar);
        ui.rule(ui.body);
        boolean shown = false;
        for (WardrobeStore.History record : history) {
            if (!record.date().equals(selectedDay.toString())) continue;
            shown = true;
            LinearLayout entry = ui.card(ui.body);
            entry.addView(ui.text(record.name(), 18, INK, true));
            if (!record.scene().isEmpty()) entry.addView(ui.text(record.scene(), 13, MUTED, false));
            List<WardrobeStore.Garment> items = db.wornGarments(record.id());
            if (!items.isEmpty()) {
                android.widget.HorizontalScrollView gallery = new android.widget.HorizontalScrollView(ui);
                gallery.setHorizontalScrollBarEnabled(false);
                LinearLayout pieces = ui.row();
                for (var g : items) {
                    LinearLayout piece = ui.vertical();
                    piece.addView(ui.image(db.image(g.photo, 4), 76));
                    TextView name = ui.text(g.name, 11, MUTED, false);
                    name.setSingleLine();
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    piece.addView(name);
                    pieces.addView(piece, new LinearLayout.LayoutParams(ui.dp(62), -2));
                    ui.space(pieces, 9);
                }
                gallery.addView(pieces);
                entry.addView(gallery);
            }
            ui.gap(entry, 5);
            entry.addView(ui.destructiveButton("移除穿着记录", () -> ui.confirmDestructive("移除穿着记录",
                    "对应单品的穿着次数会更新。", "移除记录", () -> { db.deleteHistory(record.id()); calendar(); })));
        }
        if (!shown) ui.empty(ui.body, "当天暂无记录", "");
    }

    private void switchMonth(int step) {
        month = month.plusMonths(step);
        selectedDay = month.atDay(1);
        calendar();
    }

}
