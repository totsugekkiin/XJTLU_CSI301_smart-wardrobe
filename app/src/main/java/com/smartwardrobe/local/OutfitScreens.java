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
        draft.persist();
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
        ui.outfitPage(outfits.size(), () -> openDraft(null));
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
            android.graphics.drawable.GradientDrawable outline = ui.shape(Color.WHITE, 4, 0);
            outline.setStroke(1, 0xffefefef);
            card.setBackground(outline);
            card.setPadding(0, 0, 0, 0);
            Map<Long, Bitmap> photos = new LinkedHashMap<>();
            OutfitCanvas preview = new OutfitCanvas(ui, outfit.layers, photos::get, () -> {});
            preview.setBackgroundColor(Color.WHITE);
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
            info.addView(ui.userText(outfit.name, 19, INK, true));
            String scene = outfit.scene.isEmpty() ? "" : UiLanguage.text(ui, outfit.scene) + " / ";
            int pieces = outfit.layers.size();
            info.addView(ui.text(UiLanguage.english(ui)
                    ? scene + pieces + (pieces == 1 ? " item" : " items")
                    : (outfit.scene.isEmpty() ? "" : outfit.scene + " / ") + pieces + " 件单品", 12, MUTED, false));
            ui.gap(info, 10);
            LinearLayout actions = ui.row();
            actions.setBaselineAligned(false);
            actions.addView(ui.pill("记到日历", true, () -> record(outfit)),
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
            LinearLayout filterRow = ui.row();
            android.widget.HorizontalScrollView categoryScroll = new android.widget.HorizontalScrollView(ui);
            categoryScroll.setHorizontalScrollBarEnabled(false);
            LinearLayout categories = ui.row();
            List<String> types = garments.stream().map(g -> g.type).filter(value -> !value.isEmpty())
                    .distinct().sorted().collect(Collectors.toList());
            String[] chosenType = {type};
            GarmentPicker.filters(ui, categories, "全部类别", types, chosenType,
                    () -> { type = chosenType[0]; render(); });
            categoryScroll.addView(categories);
            filterRow.addView(categoryScroll, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            TextView clearColor = ui.ghost("清除颜色", () -> { selected = null; direction = -1; render(); });
            filterRow.addView(clearColor, new LinearLayout.LayoutParams(-2, ui.dp(48)));
            ui.body.addView(filterRow);
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
        private LinearLayout toolbar;
        private TextView undoButton, redoButton, itemsButton;
        private final List<List<WardrobeStore.Layer>> history = new ArrayList<>();
        private int historyIndex;

        Draft(WardrobeStore.Outfit outfit) { this(outfit, null); }

        Draft(WardrobeStore.Outfit outfit, LocalDate day) {
            this.outfit = outfit;
            this.day = day;
            photos = photos(outfit);
            for (var layer : outfit.layers) {
                WardrobeStore.Garment garment = db.garment(layer.garment);
                if (garment != null) names.put(garment.id, garment.name);
            }
            history.add(snapshot());
        }

        String label() { return outfit.name.isEmpty() ? "未命名穿搭" : outfit.name; }

        boolean contains(long garmentId) {
            return outfit.layers.stream().anyMatch(layer -> layer.garment == garmentId);
        }

        void render() { render(day == null ? () -> ui.openTab(3) : this::returnToDay); }

        void render(Runnable back) {
            if (outfit.layers.removeIf(layer -> db.garment(layer.garment) == null)) changed();
            ui.page(this, "编辑穿搭", "", () -> leave(back), "完成", this::saveSheet);
            LinearLayout surface = ui.editorSurface();
            LinearLayout historyBar = ui.row();
            historyBar.setGravity(Gravity.CENTER_VERTICAL);
            historyBar.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            undoButton = ui.ghost("撤销", this::undo);
            redoButton = ui.ghost("重做", this::redo);
            historyBar.addView(undoButton, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            historyBar.addView(redoButton, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            itemsButton = ui.ghost("单品", this::showItems);
            itemsButton.setContentDescription("选择单品");
            historyBar.addView(itemsButton, new LinearLayout.LayoutParams(0, ui.dp(48), 1));
            surface.addView(historyBar);
            View divider = new View(ui);
            divider.setBackgroundColor(0xffeeeeee);
            surface.addView(divider, new LinearLayout.LayoutParams(-1, ui.dp(1)));
            canvas = new OutfitCanvas(ui, outfit.layers, photos::get, this::updateSelection);
            canvas.setOnTransformFinished(this::changed);
            surface.addView(canvas, new LinearLayout.LayoutParams(-1, 0, 1));
            View rule = new View(ui);
            rule.setBackgroundColor(0xffeeeeee);
            surface.addView(rule, new LinearLayout.LayoutParams(-1, ui.dp(1)));
            toolbar = ui.row();
            toolbar.setGravity(Gravity.CENTER_VERTICAL);
            toolbar.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            surface.addView(toolbar, new LinearLayout.LayoutParams(-1, ui.dp(72)));
            updateSelection();
        }

        private void leave(Runnable back) {
            if (hasUnsavedChanges()) {
                Sheet options = new Sheet(ui, "退出编辑", true);
                options.option("保存草稿并退出", () -> { persist(); options.dismiss(); leaveNow(back); });
                options.option("继续编辑", options::dismiss);
                options.destructiveOption("放弃修改", () -> {
                    if (id != 0) db.deleteDraft(id);
                    closeDraft(this);
                    options.dismiss();
                    leaveNow(back);
                });
                options.show();
            } else {
                if (id != 0) db.deleteDraft(id);
                closeDraft(this);
                leaveNow(back);
            }
        }

        private boolean hasUnsavedChanges() {
            if (outfit.id == 0) return !outfit.layers.isEmpty() || !outfit.name.isEmpty() || !outfit.scene.isEmpty();
            WardrobeStore.Outfit saved = null;
            for (WardrobeStore.Outfit item : db.outfits()) if (item.id == outfit.id) { saved = item; break; }
            if (saved == null || !saved.name.equals(outfit.name) || !saved.scene.equals(outfit.scene)
                    || saved.layers.size() != outfit.layers.size()) return true;
            for (int i = 0; i < outfit.layers.size(); i++) {
                WardrobeStore.Layer a = outfit.layers.get(i), b = saved.layers.get(i);
                if (a.garment != b.garment || Math.abs(a.x - b.x) > .0001f || Math.abs(a.y - b.y) > .0001f
                        || Math.abs(a.scale - b.scale) > .0001f || Math.abs(a.rotation - b.rotation) > .0001f)
                    return true;
            }
            return false;
        }

        private void leaveNow(Runnable back) {
            canvas = null;
            toolbar = null;
            back.run();
        }

        private void persist() {
            id = db.saveDraft(id, outfit, day, markDirty);
        }

        private void returnToDay() {
            selectedDay = day;
            month = YearMonth.from(day);
            ui.openTab(4);
        }

        private List<WardrobeStore.Layer> snapshot() {
            List<WardrobeStore.Layer> copy = new ArrayList<>();
            for (WardrobeStore.Layer layer : outfit.layers) {
                WardrobeStore.Layer item = new WardrobeStore.Layer(layer.garment, layer.x, layer.y, layer.scale);
                item.rotation = layer.rotation;
                copy.add(item);
            }
            return copy;
        }

        private void changed() {
            while (history.size() > historyIndex + 1) history.remove(history.size() - 1);
            history.add(snapshot());
            if (history.size() > 50) history.remove(0);
            historyIndex = history.size() - 1;
            persist();
            updateSelection();
        }

        private void restore(int index) {
            long selectedId = canvas != null && canvas.selected() != null ? canvas.selected().garment : 0;
            outfit.layers.clear();
            for (WardrobeStore.Layer original : history.get(index)) {
                WardrobeStore.Layer item = new WardrobeStore.Layer(original.garment, original.x, original.y, original.scale);
                item.rotation = original.rotation;
                outfit.layers.add(item);
            }
            historyIndex = index;
            if (canvas != null) {
                WardrobeStore.Layer selected = null;
                for (WardrobeStore.Layer item : outfit.layers) if (item.garment == selectedId) selected = item;
                canvas.select(selected);
                canvas.invalidate();
            }
            persist();
            updateHistoryButtons();
        }

        private void undo() { if (historyIndex > 0) restore(historyIndex - 1); }
        private void redo() { if (historyIndex + 1 < history.size()) restore(historyIndex + 1); }

        private void updateHistoryButtons() {
            if (undoButton != null) { undoButton.setEnabled(historyIndex > 0); undoButton.setAlpha(historyIndex > 0 ? 1 : .35f); }
            if (redoButton != null) { redoButton.setEnabled(historyIndex + 1 < history.size()); redoButton.setAlpha(historyIndex + 1 < history.size() ? 1 : .35f); }
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
            changed();
            if (canvas != null) canvas.select(layer);
        }

        private void load(WardrobeStore.Garment g) {
            photos.put(g.id, db.image(g.photo, 4));
            names.put(g.id, g.name);
        }

        private void replace() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) return;
            chooseGarment(outfit.layers, layer, g -> {
                if (outfit.layers.stream().anyMatch(item -> item != layer && item.garment == g.id)) {
                    ui.toast("已在画布中");
                    return;
                }
                load(g);
                layer.garment = g.id;
                changed();
                canvas.select(layer);
            });
        }

        private void remove() {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) return;
            outfit.layers.remove(layer);
            changed();
            canvas.select(null);
        }

        private void move(boolean forward) {
            WardrobeStore.Layer layer = canvas.selected();
            if (layer == null) return;
            int index = outfit.layers.indexOf(layer);
            int next = index + (forward ? 1 : -1);
            if (next < 0 || next >= outfit.layers.size()) return;
            java.util.Collections.swap(outfit.layers, index, next);
            changed();
            canvas.select(layer);
        }

        private void updateSelection() {
            if (toolbar == null || canvas == null) return;
            toolbar.removeAllViews();
            itemsButton.setEnabled(!outfit.layers.isEmpty());
            itemsButton.setAlpha(outfit.layers.isEmpty() ? .35f : 1);
            WardrobeStore.Layer current = canvas.selected();
            tool("添加单品", NavIcon.PLUS, () -> chooseGarment(outfit.layers, null, this::add), true);
            if (current != null) {
                int index = outfit.layers.indexOf(current);
                tool("前移", NavIcon.LAYER_UP, () -> move(true), index < outfit.layers.size() - 1);
                tool("后移", NavIcon.LAYER_DOWN, () -> move(false), index > 0);
                tool("替换", NavIcon.REPLACE, this::replace, true);
                tool("移除", NavIcon.CLOSE, this::remove, true);
            }
            updateHistoryButtons();
        }

        private void tool(String label, int icon, Runnable action, boolean enabled) {
            LinearLayout item = ui.vertical();
            item.setGravity(Gravity.CENTER);
            int color = label.equals("移除") ? 0xff8d3434 : INK;
            item.addView(new NavIcon(ui, icon, false, color), new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
            ui.gap(item, 5);
            TextView caption = ui.text(label, 11, color, false);
            caption.setGravity(Gravity.CENTER);
            item.addView(caption, new LinearLayout.LayoutParams(-2, -2));
            item.setContentDescription(label);
            item.setMinimumHeight(ui.dp(56));
            ui.click(item, Color.WHITE, 0, 0, action);
            item.setEnabled(enabled);
            item.setAlpha(enabled ? 1 : .32f);
            toolbar.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
        }

        private void showItems() {
            Sheet sheet = new Sheet(ui, "选择单品", true);
            for (int i = outfit.layers.size() - 1; i >= 0; i--) {
                WardrobeStore.Layer layer = outfit.layers.get(i);
                sheet.option(names.getOrDefault(layer.garment, "单品"), () -> { sheet.dismiss(); canvas.select(layer); });
            }
            sheet.show();
        }

        private void saveSheet() {
            Sheet sheet = new Sheet(ui, "保存穿搭", true);
            EditText title = ui.field(sheet.content, "穿搭名称", outfit.name.isEmpty()
                    ? (UiLanguage.english(ui) ? "My Outfit" : "我的穿搭") : outfit.name, false, false);
            ui.gap(sheet.content, 16);
            sheet.content.addView(ui.text("场景", 13, MUTED, false));
            ui.gap(sheet.content, 8);
            String[] presets = {"工作", "休闲", "派对", "运动"};
            boolean existingCustom = !outfit.scene.isEmpty() && java.util.Arrays.stream(presets).noneMatch(outfit.scene::equals);
            String[] chosen = {existingCustom ? "自定义" : outfit.scene};
            FlowLayout chips = new FlowLayout(ui);
            sheet.content.addView(chips);
            LinearLayout customBox = ui.vertical();
            sheet.content.addView(customBox);
            EditText custom = ui.field(customBox, "自定义场景", existingCustom ? outfit.scene : "", false, false);
            customBox.setVisibility(existingCustom ? View.VISIBLE : View.GONE);
            Runnable[] draw = new Runnable[1];
            draw[0] = () -> {
                chips.removeAllViews();
                String[] options = {"不选择", "工作", "休闲", "派对", "运动", "自定义"};
                for (String option : options) {
                    String value = option.equals("不选择") ? "" : option;
                    boolean active = chosen[0].equals(value);
                    TextView chip = ui.text(option, 13, active ? Color.WHITE : INK, false);
                    chip.setGravity(Gravity.CENTER);
                    chip.setPadding(ui.dp(13), 0, ui.dp(13), 0);
                    chip.setMinHeight(ui.dp(48));
                    ui.click(chip, active ? INK : 0xfff3f3f3, 4, 0, () -> {
                        chosen[0] = value;
                        customBox.setVisibility(value.equals("自定义") ? View.VISIBLE : View.GONE);
                        draw[0].run();
                    });
                    chips.addView(chip);
                }
            };
            draw[0].run();
            CheckBox dirty = null;
            if (day != null) {
                dirty = markDirtyOption(sheet.content);
                dirty.setChecked(markDirty);
            }
            CheckBox dirtyOption = dirty;
            java.util.function.Consumer<Boolean> commit = record -> {
                String value = ui.value(title);
                if (value.isEmpty()) { title.setError("请输入穿搭名称"); return; }
                if (outfit.layers.isEmpty()) { ui.toast("请先添加单品"); return; }
                outfit.name = value;
                outfit.scene = chosen[0].equals("自定义") ? ui.value(custom) : chosen[0];
                if (dirtyOption != null) markDirty = dirtyOption.isChecked();
                sheet.dismiss();
                save(day == null ? () -> ui.openTab(3) : this::returnToDay, record, markDirty);
            };
            if (day != null) {
                sheet.button("仅保存穿搭", false, () -> commit.accept(false));
                sheet.button("保存并登记", true, () -> commit.accept(true));
            } else sheet.button("保存", true, () -> commit.accept(false));
            sheet.show();
        }

        private void save(Runnable onFinish, boolean record, boolean dirty) {
            if (outfit.name.isEmpty() || outfit.layers.isEmpty()) { ui.toast("请填写名称并添加单品"); return; }
            db.saveOutfit(outfit, id, record ? day : null, dirty);
            closeDraft(this);
            ui.toast(record ? "已保存穿搭和当天记录" : "已保存穿搭");
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
        LinearLayout top = (LinearLayout) ((LinearLayout) ((LinearLayout) ui.rootView()).getChildAt(0)).getChildAt(0);
        TextView language = ui.text(UiLanguage.english(ui) ? "中文" : "EN", 13, INK, true);
        language.setGravity(Gravity.CENTER_VERTICAL);
        language.setPadding(0, 0, ui.dp(16), 0);
        language.setContentDescription(UiLanguage.english(ui) ? "Switch to Chinese" : "切换为英文");
        language.setOnClickListener(view -> {
            UiLanguage.setEnglish(ui, !UiLanguage.english(ui));
            calendar();
        });
        language.setFocusable(true);
        Motion.press(language);
        top.addView(language, 0, new LinearLayout.LayoutParams(-2, ui.dp(48)));
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
            entry.addView(ui.userText(record.name(), 18, INK, true));
            if (!record.scene().isEmpty()) entry.addView(ui.text(record.scene(), 13, MUTED, false));
            List<WardrobeStore.Garment> items = db.wornGarments(record.id());
            if (!items.isEmpty()) {
                android.widget.HorizontalScrollView gallery = new android.widget.HorizontalScrollView(ui);
                gallery.setHorizontalScrollBarEnabled(false);
                LinearLayout pieces = ui.row();
                for (var g : items) {
                    LinearLayout piece = ui.vertical();
                    piece.addView(ui.photo(g.photo, 4, 76));
                    TextView name = ui.userText(g.name, 11, MUTED, false);
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
