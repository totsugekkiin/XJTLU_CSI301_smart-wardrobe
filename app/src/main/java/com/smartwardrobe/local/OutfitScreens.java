package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        for (var saved : db.outfitDrafts()) {
            Draft restored = new Draft(saved.outfit(), saved.day());
            restored.id = saved.id();
            restored.markDirty = saved.markDirty();
            if (draft == null) draft = restored;
            else otherDrafts.add(restored);
        }
    }

    long visibleDraftId() {
        if (draft == null || draft.canvas == null || !draft.canvas.isAttachedToWindow()) return 0;
        if (draft.id != 0) draft.persist();
        return draft.id;
    }

    void restoreCanvas(long id) {
        if (id == 0) return;
        if (draft != null && draft.id == id) {
            draft.render();
            return;
        }
        for (Draft pending : otherDrafts) {
            if (pending.id != id) continue;
            selectDraft(pending);
            pending.render();
            return;
        }
    }

    private void closeDraft(Draft closed) {
        if (draft == closed) draft = otherDrafts.isEmpty() ? null : otherDrafts.remove(otherDrafts.size() - 1);
        else otherDrafts.remove(closed);
    }

    void garmentsDeleted(java.util.Set<Long> ids) {
        java.util.Set<Long> saved = db.outfits().stream().map(outfit -> outfit.id).collect(Collectors.toSet());
        List<Draft> pending = new ArrayList<>(otherDrafts);
        if (draft != null) pending.add(draft);
        for (Draft item : pending) {
            item.outfit.layers.removeIf(layer -> ids.contains(layer.garment));
            ids.forEach(id -> { item.photos.remove(id); item.names.remove(id); });
            if (item.outfit.id != 0 && !saved.contains(item.outfit.id)) item.outfit.id = 0;
        }
    }

    void collection() {
        List<WardrobeStore.Outfit> outfits = db.outfits();
        ui.page("outfits", "", outfits.isEmpty() ? "" : String.format(java.util.Locale.ROOT, "%02d", outfits.size()), null,
                "+ 新建穿搭", () -> openDraft(null));
        if (draft != null) {
            ui.body.addView(ui.pill("继续编辑 / " + draft.label(), false, draft::render));
        }
        for (Draft pending : otherDrafts) {
            ui.body.addView(ui.pill("继续编辑 / " + pending.label(), false, () -> {
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
            Map<Long, Bitmap> photos = new LinkedHashMap<>();
            OutfitCanvas preview = new OutfitCanvas(ui, outfit.layers, photos::get, () -> {});
            for (long garment : outfit.layers.stream().map(layer -> layer.garment).distinct().collect(Collectors.toList())) {
                WardrobeStore.Garment item = db.garment(garment);
                if (item != null) ui.loadPhoto(preview, item.photo, 4, bitmap -> {
                    photos.put(garment, bitmap);
                    preview.invalidate();
                });
            }
            preview.setInteractive(false);
            preview.setContentDescription("穿搭预览 / " + outfit.name);
            card.addView(preview, new LinearLayout.LayoutParams(-1, ui.dp(236)));
            LinearLayout info = ui.vertical();
            info.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(6));
            card.addView(info);
            info.addView(ui.editorial(outfit.name, 19));
            info.addView(ui.text((outfit.scene.isEmpty() ? "" : outfit.scene + " / ") + outfit.layers.size() + " 件单品", 12, MUTED, false));
            ui.gap(info, 10);
            LinearLayout actions = ui.row();
            actions.setBaselineAligned(false);
            actions.addView(ui.pill("记到日历", false, () -> record(outfit)),
                    new LinearLayout.LayoutParams(0, -2, 1));
            ui.space(actions, 10);
            actions.addView(ui.ghost("编辑", () -> openDraft(outfit)));
            ui.space(actions, 10);
            TextView delete = ui.destructiveButton("删除", () -> ui.confirmDestructive("删除穿搭",
                    "已记录的穿着历史会保留。", "删除穿搭", () -> {
                        db.deleteOutfit(outfit.id);
                        if (draft != null && draft.outfit.id == outfit.id) closeDraft(draft);
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
            for (WardrobeStore.Garment g : garments) {
                WardrobeStore.Swatch color = primaryColor(g);
                if (color != null) palette.putIfAbsent(color.rgb, color.name);
            }
            ui.page(this, "衣橱色盘", base.name, back, "打开画布",
                    () -> withDraft(base, current -> current.render(this::render)));
            targetLabel = ui.text("", 14, INK, true);
            ui.body.addView(targetLabel);
            addButtons.clear();
            LinearLayout actions = ui.row();
            View chooseBase = baseColor == null ? ui.pill("设置基准色", false, this::chooseBaseColor)
                    : ui.colorChip("基准色：" + baseColor.name, baseColor.rgb, this::chooseBaseColor);
            actions.addView(chooseBase, new LinearLayout.LayoutParams(0, -2, 1));
            ui.body.addView(actions);
            ui.section(ui.body, "衣橱颜色");
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
            ui.actions(ui.body, new String[]{type.isEmpty() ? "全部类别" : type, "清除颜色"},
                    () -> ui.closet.selectType("全部类别", value -> { type = value; render(); }),
                    () -> { selected = null; direction = -1; render(); });
            boolean found = false;
            for (WardrobeStore.Garment g : garments) {
                if (g.id == base.id) continue;
                if (!type.isEmpty() && !g.type.equals(type)) continue;
                WardrobeStore.Swatch color = primaryColor(g);
                if (selected != null && (color == null || color.rgb != selected)) continue;
                if (selected == null && direction >= 0 && baseColor != null
                        && (color == null || !ImageAnalysis.matches(baseColor.rgb, color.rgb, direction))) continue;
                found = true;
                LinearLayout card = ui.closet.itemCard(ui.body, g, g.type, null);
                TextView button = ui.pill("+ 加入画布", false,
                        () -> withDraft(g, current -> ui.toast("已加入「" + current.label() + "」")));
                addButtons.put(g.id, button);
                card.addView(button);
            }
            if (!found) ui.empty(ui.body, "无匹配单品", "");
            updateDraftStatus();
        }

        private void updateDraftStatus() {
            targetLabel.setText(target == null ? "从「" + base.name + "」开始搭配"
                    : "当前画布：" + target.label());
            for (var entry : addButtons.entrySet()) {
                boolean added = target != null && target.contains(entry.getKey());
                entry.getValue().setText(added ? "已加入" : "+ 加入画布");
                entry.getValue().setEnabled(!added);
            }
        }

        private void withDraft(WardrobeStore.Garment garment, Consumer<Draft> ready) {
            if (target != null) {
                addToDraft(target, garment, ready);
                return;
            }
            Draft current = draft;
            int needed = (current != null && current.contains(base.id) ? 0 : 1)
                    + (garment.id == base.id || current != null && current.contains(garment.id) ? 0 : 1);
            if (current == null || current.day != null || current.outfit.layers.size() + needed > 20) {
                WardrobeStore.Outfit outfit = new WardrobeStore.Outfit();
                outfit.name = base.name + "搭配";
                current = new Draft(outfit);
            }
            addToDraft(current, garment, ready);
        }

        private void addToDraft(Draft current, WardrobeStore.Garment garment, Consumer<Draft> ready) {
            int needed = current.contains(base.id) ? 0 : 1;
            if (garment.id != base.id && !current.contains(garment.id)) needed++;
            if (current.outfit.layers.size() + needed > 20) {
                ui.toast("每套最多 20 件单品");
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
            if (base.colors.isEmpty()) { ui.toast("请先添加颜色"); return; }
            Sheet sheet = new Sheet(ui, "选择基准色", true);
            for (var color : base.colors) {
                sheet.option(color.name, color.rgb, () -> { baseColor = color; sheet.dismiss(); render(); });
            }
            sheet.show();
        }

        private void suggest(int choice) {
            if (baseColor == null) { ui.toast("请先添加基准色"); return; }
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
        private final LocalDate day;
        private long id;
        private boolean markDirty = true;
        private final Map<Long, String> names = new LinkedHashMap<>();
        private OutfitCanvas canvas;
        private EditText name, scene;
        private LinearLayout layerList, layerControls;
        private TextView layerHeading;

        Draft(WardrobeStore.Outfit outfit) { this(outfit, null); }

        Draft(WardrobeStore.Outfit outfit, LocalDate day) {
            this.outfit = outfit;
            this.day = day;
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

        void render() { render(day == null ? () -> ui.openTab(3) : this::returnToDay); }

        void render(Runnable back) {
            if (outfit.layers.removeIf(layer -> db.garment(layer.garment) == null)) persist();
            Runnable onFinish = day == null ? () -> ui.openTab(3) : this::returnToDay;
            ui.page(this, "穿搭画布", day == null ? label() : day.toString(), () -> leave(back),
                    day == null ? "保存" : null, day == null ? () -> save(onFinish, false, false) : null);
            canvas = new OutfitCanvas(ui, outfit.layers, photos::get, this::updateSelection);
            canvas.setOnTransformFinished(this::persist);
            ui.body.addView(canvas, new LinearLayout.LayoutParams(-1, ui.dp(360)));
            ui.gap(ui.body, 8);
            layerControls = ui.vertical();
            ui.body.addView(layerControls);
            String[] labels = {"放大", "缩小", "置顶", "置底", "替换", "移除"};
            Runnable[] actions = {() -> resize(1.15f), () -> resize(1 / 1.15f),
                    () -> move(true), () -> move(false), this::replace, this::remove};
            int width = ui.getResources().getConfiguration().screenWidthDp - 40;
            int columns = width >= labels.length * 56 + (labels.length - 1) * 8 ? labels.length : 3;
            LinearLayout toolRow = null;
            for (int i = 0; i < labels.length; i++) {
                if (i % columns == 0) {
                    if (i > 0) ui.gap(layerControls, 8);
                    toolRow = ui.row();
                    toolRow.setBaselineAligned(false);
                    layerControls.addView(toolRow);
                }
                TextView tool = ui.pill(labels[i], false, actions[i]);
                tool.setContentDescription(labels[i].substring(labels[i].indexOf('\n') + 1));
                tool.setMinHeight(ui.dp(55));
                tool.setPadding(0, ui.dp(4), 0, ui.dp(4));
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
                if (i % columns != 0) params.setMarginStart(ui.dp(8));
                toolRow.addView(tool, params);
            }
            ui.gap(ui.body, 16);
            LinearLayout layerHeader = ui.row();
            layerHeader.setGravity(Gravity.CENTER_VERTICAL);
            layerHeading = ui.text("画布图层", 15, INK, true);
            layerHeading.setAccessibilityHeading(true);
            layerHeader.addView(layerHeading, new LinearLayout.LayoutParams(0, -2, 1));
            layerHeader.addView(ui.pill("+ 添加单品", false, () -> chooseGarment(outfit.layers, null, this::add)));
            ui.body.addView(layerHeader);
            android.widget.HorizontalScrollView layers = new android.widget.HorizontalScrollView(ui);
            layers.setHorizontalScrollBarEnabled(false);
            layerList = ui.row();
            layers.addView(layerList);
            ui.body.addView(layers);
            updateSelection();

            LinearLayout form = ui.card(ui.body);
            name = ui.field(form, "穿搭名称 *", outfit.name, false, false);
            scene = ui.field(form, "场景", outfit.scene, false, false);
            form.addView(ui.pill("常用场景", false, () -> {
                String[] scenes = {"工作", "休闲", "派对", "运动"};
                ui.choose("选择场景", scenes, i -> scene.setText(scenes[i]));
            }));
            GarmentPicker.watch(name, this::persist);
            GarmentPicker.watch(scene, this::persist);
            if (day != null) {
                CheckBox dirty = markDirtyOption(form);
                dirty.setChecked(markDirty);
                dirty.setOnCheckedChangeListener((view, checked) -> {
                    markDirty = checked;
                    persist();
                });
                form.addView(ui.pill("保存并登记", true, () -> save(onFinish, true, markDirty)));
                ui.gap(form, 10);
                form.addView(ui.pill("仅保存穿搭", false, () -> save(onFinish, false, false)));
            }
            ui.gap(form, 10);
            form.addView(ui.ghost("放弃编辑", () -> ui.prompt("放弃编辑",
                    "未保存的内容将丢失。", "放弃", () -> {
                        if (id != 0) db.deleteDraft(id);
                        closeDraft(this);
                        onFinish.run();
                    })));
            ui.body.setFocusableInTouchMode(true);
            ui.body.requestFocus();
        }

        private void leave(Runnable back) {
            if (id != 0) persist();
            canvas = null;
            layerList = null;
            name = null;
            scene = null;
            back.run();
        }

        private void captureFields() {
            if (name != null) outfit.name = ui.value(name);
            if (scene != null) outfit.scene = ui.value(scene);
        }

        private void persist() {
            captureFields();
            id = db.saveDraft(id, outfit, day, markDirty);
        }

        private void returnToDay() {
            selectedDay = day;
            month = YearMonth.from(day);
            ui.openTab(4);
        }

        private void resize(float ratio) {
            canvas.resize(ratio);
            persist();
        }

        void add(WardrobeStore.Garment g) {
            if (outfit.layers.size() >= 20) { ui.toast("每套最多 20 件单品"); return; }
            for (WardrobeStore.Layer layer : outfit.layers) if (layer.garment == g.id) {
                if (canvas != null) canvas.select(layer);
                return;
            }
            WardrobeStore.Layer layer = newLayer(g.id, outfit.layers.size());
            load(g);
            outfit.layers.add(layer);
            persist();
            if (canvas != null) canvas.select(layer);
        }

        private void load(WardrobeStore.Garment g) {
            photos.put(g.id, db.image(g.photo, 4));
            names.put(g.id, g.name);
        }

        private void replace() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择图层"); return; }
            chooseGarment(outfit.layers, layer, g -> {
                if (outfit.layers.stream().anyMatch(item -> item != layer && item.garment == g.id)) {
                    ui.toast("已在画布中");
                    return;
                }
                load(g);
                layer.garment = g.id;
                persist();
                canvas.select(layer);
            });
        }

        private void remove() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择图层"); return; }
            outfit.layers.remove(layer);
            persist();
            canvas.select(null);
        }

        private void move(boolean top) {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) { ui.toast("请先选择图层"); return; }
            outfit.layers.remove(layer);
            outfit.layers.add(top ? outfit.layers.size() : 0, layer);
            persist();
            canvas.select(layer);
        }

        private void updateSelection() {
            if (layerList == null) return;
            WardrobeStore.Layer current = canvas.selected();
            layerHeading.setText("图层 / " + outfit.layers.size());
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

        private void save(Runnable onFinish, boolean record, boolean dirty) {
            captureFields();
            if (outfit.name.isEmpty() || outfit.layers.isEmpty()) { ui.toast("请填写名称并添加单品"); return; }
            db.saveOutfit(outfit, id, record ? day : null, dirty);
            closeDraft(this);
            if (record) ui.toast("已保存穿搭和当天记录");
            onFinish.run();
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
        if (garments.isEmpty()) { ui.toast("请先添加单品"); return; }
        Set<Long> canvasIds = layers.stream().map(layer -> layer.garment).collect(Collectors.toSet());
        List<WardrobeStore.Garment> canvas = garments.stream().filter(garment -> canvasIds.contains(garment.id))
                .collect(Collectors.toList());
        Set<Long> occupied = layers.stream().filter(layer -> layer != replacing)
                .map(layer -> layer.garment).collect(Collectors.toSet());
        garments.removeIf(garment -> occupied.contains(garment.id));
        GarmentPicker.openForCanvas(ui, replacing == null ? "从衣橱添加单品" : "替换单品",
                garments, canvas, selected);
    }

    static WardrobeStore.Swatch primaryColor(WardrobeStore.Garment garment) {
        if (garment.colors.isEmpty()) return null;
        return garment.colors.stream().filter(color -> color.primary).findFirst().orElse(garment.colors.get(0));
    }

    private void chooseWearItems(LocalDate day) {
        List<WardrobeStore.Garment> garments = db.garments();
        if (garments.isEmpty()) {
            ui.prompt("衣橱暂无衣物", "添加单品后即可登记穿着。", "去衣橱", () -> ui.openTab(0));
            return;
        }
        GarmentPicker.open(ui, "选择穿着单品", garments, GarmentPicker.Filter.ALL, null, (filter, items) -> {
            WardrobeStore.Outfit worn = new WardrobeStore.Outfit();
            worn.name = "当天穿着";
            for (var garment : items) worn.layers.add(newLayer(garment.id, worn.layers.size()));
            recordOn(worn, day);
        }, true);
    }

    private void record(WardrobeStore.Outfit outfit) {
        if (outfit.layers.isEmpty()) { ui.toast("请先添加单品"); return; }
        ui.date("穿着日期", LocalDate.now(), true, day -> recordOn(outfit, day));
    }

    private CheckBox markDirtyOption(LinearLayout parent) {
        CheckBox dirty = new CheckBox(ui);
        dirty.setText("同时标为待洗");
        dirty.setMinHeight(ui.dp(48));
        dirty.setChecked(true);
        parent.addView(dirty);
        return dirty;
    }

    private void recordOn(WardrobeStore.Outfit outfit, LocalDate day) {
        Sheet sheet = new Sheet(ui, "记录穿着 / " + day, true);
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
                    Motion.expand(nameFields, checked));
        }
        CheckBox dirty = markDirtyOption(sheet.content);
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
                db.saveOutfit(outfit, 0, day, dirty.isChecked());
            } else {
                db.wear(outfit, occasion, day.toString(), dirty.isChecked());
            }
            month = YearMonth.from(day);
            selectedDay = day;
            sheet.dismiss();
            ui.openTab(4);
        });
        sheet.show();
    }

    void prepareOnboardingCalendar() {
        selectedDay = LocalDate.now();
        month = YearMonth.from(selectedDay);
    }

    void calendar() {
        List<WardrobeStore.History> history = db.history(month.toString());
        ui.page("calendar", "", "", null, "回到今天", () -> {
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
        ui.coachTarget("calendar-grid", card);
        ui.gap(ui.body, 12);
        ui.rule(ui.body);
        boolean hasRecords = history.stream().anyMatch(record -> record.date().equals(selectedDay.toString()));
        ui.gap(ui.body, 16);
        LinearLayout dayBar = ui.row();
        ui.coachTarget("calendar-day", dayBar);
        dayBar.setGravity(Gravity.CENTER_VERTICAL);
        String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        String dateLabel = selectedDay.getMonthValue() + "月" + selectedDay.getDayOfMonth() + "日  "
                + weekdays[selectedDay.getDayOfWeek().getValue() - 1];
        dayBar.addView(ui.text(dateLabel, 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
        if (hasRecords && !selectedDay.isAfter(LocalDate.now()))
            dayBar.addView(ui.ghost("+ 记一套", this::registerSelectedDay));
        ui.body.addView(dayBar);
        ui.gap(ui.body, 16);
        if (!hasRecords) {
            LinearLayout empty = ui.vertical();
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(ui.dp(24), ui.dp(28), ui.dp(24), ui.dp(28));
            empty.setMinimumHeight(ui.dp(204));
            empty.setBackground(ui.shape(0xfff7f8fa, 16, 0));
            empty.addView(new NavIcon(ui, 3, false, 0xffaaaaaa),
                    new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
            ui.gap(empty, 12);
            TextView hint = ui.text("这天还没有穿搭记录", 14, 0xff888888, false);
            hint.setGravity(Gravity.CENTER);
            empty.addView(hint);
            ui.gap(empty, 20);
            TextView register = ui.pill("+ 登记当天穿着", true, this::registerSelectedDay);
            ui.coachTarget("calendar-day", register);
            register.setEnabled(!selectedDay.isAfter(LocalDate.now()));
            empty.addView(register, new LinearLayout.LayoutParams(-2, -2));
            ui.body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
        }
        for (WardrobeStore.History record : history) {
            if (!record.date().equals(selectedDay.toString())) continue;
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
                    piece.addView(ui.photo(g.photo, 4, 76));
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
                    "移除后将更新穿着次数。", "移除记录", () -> { db.deleteHistory(record.id()); calendar(); })));
        }
    }

    private void registerSelectedDay() {
        List<WardrobeStore.Outfit> all = new ArrayList<>();
        for (var outfit : db.outfits()) if (!outfit.layers.isEmpty()) all.add(outfit);
        LocalDate day = selectedDay;
        String[] options = all.isEmpty() ? new String[]{"从衣橱选单品", "新建穿搭"}
                : new String[]{"从衣橱选单品", "新建穿搭", "选择穿搭"};
        ui.choose("登记当天穿着", options, mode -> {
            if (mode == 0) chooseWearItems(day);
            else if (mode == 1) {
                Draft opened = new Draft(new WardrobeStore.Outfit(), day);
                selectDraft(opened);
                opened.render();
            } else ui.choose("选择穿搭", all.stream().map(o -> o.name).toArray(String[]::new),
                    i -> recordOn(all.get(i), day));
        });
    }

    private void switchMonth(int step) {
        month = month.plusMonths(step);
        selectedDay = month.atDay(1);
        calendar();
    }

}
