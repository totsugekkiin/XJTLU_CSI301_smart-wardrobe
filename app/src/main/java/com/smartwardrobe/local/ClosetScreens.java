package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
import android.net.Uri;
import android.text.InputFilter;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.FrameLayout;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Consumer;

import static com.smartwardrobe.local.MainActivity.*;

final class ClosetScreens {
    private final MainActivity ui;
    private final WardrobeStore db;
    private GarmentPicker.Filter filter = GarmentPicker.Filter.ALL;
    private int filterSeason = -1;
    private boolean favorites;
    private int currentSeason;
    private boolean manualSeasonChoice;
    private boolean selecting;
    private final Set<Long> selected = new LinkedHashSet<>();
    private TextView countView;
    private SeasonTabs seasonTabs;

    void clearSelection() { selecting = false; selected.clear(); }

    void prepareOnboarding() {
        clearSelection();
        filter = GarmentPicker.Filter.ALL;
        filterSeason = -1;
        favorites = false;
    }

    private void exitSelection() { clearSelection(); home(); }

    private void toggleSelected(long id) {
        if (!selected.add(id)) selected.remove(id);
        home();
    }
    private static final String[][] SYMBOLS = {
            {"洗涤", "手洗", "不可水洗", "最高 20°C", "最高 30°C", "最高 30°C · 柔洗", "最高 30°C · 极柔洗",
                    "最高 40°C", "最高 40°C · 柔洗", "最高 40°C · 极柔洗", "最高 50°C", "最高 60°C",
                    "最高 60°C · 柔洗", "最高 70°C", "最高 95°C"},
            {"漂白", "可漂白", "仅氧漂", "不可漂白"},
            {"干燥", "常温滚筒烘干", "低温滚筒烘干", "不可滚筒烘干", "悬挂晾干", "平铺晾干", "滴水悬挂晾干",
                    "滴水平铺晾干", "阴凉处悬挂晾干", "阴凉处平铺晾干", "阴凉处滴水悬挂晾干", "阴凉处滴水平铺晾干",
                    "中温滚筒烘干", "高温滚筒烘干"},
            {"熨烫", "低温熨烫 · 一点", "中温熨烫 · 两点", "高温熨烫 · 三点", "不可熨烫"},
            {"专业护理", "专业干洗 · P", "专业干洗 · P 柔和", "专业干洗 · F", "专业干洗 · F 柔和",
                    "不可干洗", "专业湿洗 · W", "专业湿洗 · W 柔和", "专业湿洗 · W 极柔和", "不可专业湿洗", "专业干洗 · A"}
    };

    ClosetScreens(MainActivity ui) {
        this.ui = ui;
        db = ui.db;
        currentSeason = SeasonPolicy.monthSeason(LocalDate.now());
    }

    void home() {
        List<WardrobeStore.Garment> garments = db.garments();
        if (garments.isEmpty()) {
            countView = ui.closetPage("0 件单品", this::newGarment, filterBar());
            LinearLayout panel = ui.card(ui.body);
            panel.addView(ui.text("衣橱暂无单品", 23, INK, true));
            ui.gap(panel, 13);
            showAddOptions(panel, () -> {});
            return;
        }
        countView = ui.closetPage(selecting ? "已选 " + selected.size() + " 件" : countText(garments, filterGarments(garments)),
                selecting ? null : this::newGarment, selecting ? null : filterBar());
        refreshList(false);
    }

    private void refreshList(boolean resetScroll) {
        List<WardrobeStore.Garment> garments = db.garments();
        List<WardrobeStore.Garment> filtered = filterGarments(garments);
        selected.retainAll(filtered.stream().map(g -> g.id).collect(java.util.stream.Collectors.toSet()));
        if (countView != null && !selecting) countView.setText(countText(garments, filtered));
        renderGrid(filtered);
        if (resetScroll) ui.scrollTop();
    }

    private List<WardrobeStore.Garment> filterGarments(List<WardrobeStore.Garment> garments) {
        List<WardrobeStore.Garment> filtered = new ArrayList<>();
        for (WardrobeStore.Garment g : garments) {
            if (!filter.matches(g)) continue;
            if (filterSeason >= 0 && (g.seasons & (1 << filterSeason)) == 0) continue;
            if (favorites && !g.favorite) continue;
            filtered.add(g);
        }
        return filtered;
    }

    private String countText(List<WardrobeStore.Garment> all, List<WardrobeStore.Garment> shown) {
        boolean narrowing = !filter.equals(GarmentPicker.Filter.ALL) || filterSeason >= 0 || favorites;
        return narrowing ? shown.size() + " / " + all.size() + " 件" : all.size() + " 件单品";
    }

    private int activeFilters() {
        int count = 0;
        if (!filter.query().isEmpty()) count++;
        if (!filter.type().isEmpty()) count++;
        if (!filter.color().isEmpty()) count++;
        return count;
    }

    private void pickSeason(int index) {
        if (index == filterSeason) {
            filterSeason = -1;
            seasonTabs.deselect();
        } else {
            filterSeason = index;
            seasonTabs.select(index);
        }
        refreshList(true);
    }

    private View filterBar() {
        LinearLayout bar = ui.vertical();
        bar.setBackgroundColor(PAPER);
        View top = new View(ui);
        top.setBackgroundColor(0xffefefef);
        bar.addView(top, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(20), 0, ui.dp(12), 0);
        row.setClipChildren(false);

        int active = activeFilters();
        TextView filterButton = ui.text(active > 0 ? "筛选 / " + active : "筛选", 13, INK, active > 0);
        filterButton.setGravity(Gravity.CENTER_VERTICAL);
        filterButton.setPadding(0, 0, 0, 0);
        if (active == 0) {
            DisclosureArrow arrow = new DisclosureArrow(ui, true);
            arrow.color(INK);
            arrow.setBounds(0, 0, ui.dp(8), ui.dp(8));
            filterButton.setCompoundDrawablesRelative(null, null, arrow, null);
            filterButton.setCompoundDrawablePadding(ui.dp(3));
        }
        filterButton.setOnClickListener(view -> openFilter());
        Motion.press(filterButton);
        row.addView(filterButton, new LinearLayout.LayoutParams(-2, -1));

        ui.space(row, 12);
        FrameLayout heart = new FrameLayout(ui);
        heart.setClipChildren(false);
        heart.addView(new NavIcon(ui, NavIcon.FAVORITE, favorites),
                new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
        LinearLayout like = ui.row();
        like.setGravity(Gravity.CENTER_VERTICAL);
        like.setClipChildren(false);
        like.addView(heart, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        ui.space(like, 4);
        TextView likeLabel = ui.text("收藏", 13, INK, favorites);
        like.addView(likeLabel);
        like.setContentDescription(favorites ? "取消收藏筛选" : "只看收藏");
        like.setOnClickListener(view -> {
            favorites = !favorites;
            heart.removeAllViews();
            heart.addView(new NavIcon(ui, NavIcon.FAVORITE, favorites),
                    new FrameLayout.LayoutParams(ui.dp(18), ui.dp(18), Gravity.CENTER));
            likeLabel.setTypeface(android.graphics.Typeface.create("sans-serif",
                    favorites ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            like.setContentDescription(favorites ? "取消收藏筛选" : "只看收藏");
            Motion.favorite(heart, () -> {});
            refreshList(true);
        });
        Motion.press(like);
        row.addView(like, new LinearLayout.LayoutParams(-2, -1));

        ui.space(row, 10);
        View divider = new View(ui);
        divider.setBackgroundColor(STROKE);
        row.addView(divider, new LinearLayout.LayoutParams(ui.dp(1), ui.dp(14)));
        ui.space(row, 10);

        seasonTabs = new SeasonTabs(ui, filterSeason, this::pickSeason);
        row.addView(seasonTabs, new LinearLayout.LayoutParams(0, -1, 1));

        bar.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        View bottom = new View(ui);
        bottom.setBackgroundColor(0xffefefef);
        bar.addView(bottom, new LinearLayout.LayoutParams(-1, ui.dp(1)));
        return bar;
    }

    private void openFilter() {
        List<WardrobeStore.Garment> garments = db.garments();
        List<WardrobeStore.Garment> available = new ArrayList<>(garments);
        available.removeIf(g -> filterSeason >= 0 && (g.seasons & (1 << filterSeason)) == 0 || favorites && !g.favorite);
        Sheet picker = GarmentPicker.open(ui, "筛选衣橱", available, filter,
                chosen -> { filter = chosen; home(); ui.scrollTop(); },
                (chosen, items) -> { filter = chosen; detail(items.get(0).id, this::home); }, false);
        picker.bottomLink("已归档", () -> { picker.dismiss(); archive(); });
        picker.bottomLink("使用指南", () -> { picker.dismiss(); ui.replayGuide(); });
    }

    private void renderGrid(List<WardrobeStore.Garment> filtered) {
        ui.body.removeAllViews();
        if (selecting) {
            LinearLayout tools = ui.row();
            tools.setGravity(Gravity.CENTER_VERTICAL);
            tools.addView(ui.ghost("取消", this::exitSelection));
            tools.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1));
            boolean all = !filtered.isEmpty() && selected.size() == filtered.size();
            tools.addView(ui.ghost(all ? "取消全选" : "全选", () -> {
                selected.clear();
                if (!all) for (var garment : filtered) selected.add(garment.id);
                home();
            }));
            tools.addView(ui.text("当前列表 " + filtered.size() + " 件", 12, MUTED, false));
            ui.body.addView(tools);
            ui.gap(ui.body, 12);

            LinearLayout actions = ui.row();
            actions.setGravity(Gravity.CENTER_VERTICAL);
            actions.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(8));
            actions.setBackground(ui.shape(PAPER, 0, STROKE));
            TextView pending = ui.pill("标为待洗", true, () -> markSelectedDirty(new LinkedHashSet<>(selected)));
            pending.setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4));
            pending.setSingleLine(true);
            TextView archive = ui.pill("归档", false, () -> archiveSelected(new LinkedHashSet<>(selected)));
            TextView delete = ui.destructiveButton("删除", () -> deleteSelected(new LinkedHashSet<>(selected)));
            pending.setEnabled(!selected.isEmpty());
            archive.setEnabled(!selected.isEmpty());
            delete.setEnabled(!selected.isEmpty());
            actions.addView(pending, new LinearLayout.LayoutParams(0, -2, 1));
            ui.space(actions, 12);
            actions.addView(archive, new LinearLayout.LayoutParams(0, -2, 1));
            ui.space(actions, 12);
            actions.addView(delete, new LinearLayout.LayoutParams(0, -2, 1));
            ui.selectionBar(actions, this::exitSelection);
        }
        if (filtered.isEmpty()) ui.empty(ui.body, "无匹配单品", "");
        for (int i = 0; i < filtered.size(); i += 2) {
            LinearLayout row = ui.row();
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                if (j == 1) p.leftMargin = ui.dp(16);
                if (i + j >= filtered.size()) { row.addView(new View(ui), p); continue; }
                WardrobeStore.Garment g = filtered.get(i + j);
                LinearLayout card = ui.vertical();
                if (i == 0 && j == 0) ui.coachTarget("closet-first", card);
                boolean checked = selected.contains(g.id);
                ImageView[] heroSource = {null};
                ui.click(card, CARD, 0, 0,
                        () -> { if (selecting) toggleSelected(g.id); else ui.openGarmentHero(heroSource[0], () -> detail(g.id, this::home)); });
                card.setOnLongClickListener(view -> {
                    if (!selecting) { selecting = true; selected.add(g.id); home(); }
                    return true;
                });
                card.setSelected(selecting && checked);
                if (selecting) card.setContentDescription(g.name + (checked ? "，已选中" : "，未选中"));
                FrameLayout photo = new FrameLayout(ui);
                int width = ui.getResources().getConfiguration().screenWidthDp;
                int height = Math.max(146, (Math.min(width, 620) - 56) * 2 / 3);
                ImageView thumbnail = ui.image(null, height);
                int inset = ui.dp(20);
                thumbnail.setPadding(inset, inset, inset, inset);
                heroSource[0] = thumbnail;
                photo.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
                View placeholder = new NavIcon(ui, 0, false);
                photo.addView(placeholder, new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
                ui.loadPhoto(thumbnail, g.photo, 2, bitmap -> {
                    thumbnail.setImageBitmap(bitmap);
                    placeholder.setVisibility(View.GONE);
                });
                View favorite = favoriteButton(g, this::home);
                if (selecting) {
                    // The whole card toggles selection, including the heart area.
                    favorite.setOnClickListener(null);
                    favorite.setClickable(false);
                    favorite.setFocusable(false);
                    favorite.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                }
                photo.addView(favorite, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END | Gravity.TOP));
                if (selecting && checked) {
                    View shade = new View(ui);
                    shade.setBackgroundColor(0x80000000);
                    shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    photo.addView(shade, new FrameLayout.LayoutParams(-1, -1));
                    photo.addView(new NavIcon(ui, NavIcon.CHECK, true),
                            new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER));
                }
                card.addView(photo, new LinearLayout.LayoutParams(-1, ui.dp(height)));
                ui.gap(card, 6);
                if (!g.brand.isEmpty()) {
                    TextView brand = ui.text(g.brand, 11, MUTED, false);
                    brand.setLetterSpacing(.07f);
                    brand.setSingleLine();
                    card.addView(brand);
                }
                TextView title = ui.text(g.name, 13, INK, true);
                title.setSingleLine();
                title.setEllipsize(android.text.TextUtils.TruncateAt.END);
                card.addView(title);
                LinearLayout meta = ui.row();
                meta.setGravity(Gravity.CENTER_VERTICAL);
                meta.addView(ui.text(g.type + (g.seasons == 0 ? "" : "  " + seasons(g.seasons)), 11, MUTED, false));
                if (!g.colors.isEmpty()) {
                    WardrobeStore.Swatch primary = g.colors.stream().filter(c -> c.primary).findFirst().orElse(g.colors.get(0));
                    WardrobeStore.Swatch accent = g.colors.stream().filter(c -> c != primary)
                            .max(Comparator.comparingInt(c -> c.percent)).orElse(null);
                    cardColor(meta, primary, accent == null);
                    if (accent != null) cardColor(meta, accent, true);
                }
                card.addView(meta);
                row.addView(card, p);
            }
            ui.body.addView(row);
            ui.gap(ui.body, 26);
        }
    }

    void selectType(String first, Consumer<String> result) {
        List<String> choices = new ArrayList<>(Arrays.asList(TYPES));
        if (first != null) choices.add(0, first);
        ui.choose("选择类别", choices.toArray(new String[0]),
                i -> result.accept(i == 0 && first != null ? "" : choices.get(i)));
    }

    private void newGarment() {
        Sheet sheet = new Sheet(ui, "添加衣物", true);
        sheet.setPanelPadding(20);
        sheet.content.setPadding(0, ui.dp(20), 0, 0);
        showAddOptions(sheet.content, sheet::dismiss);
        sheet.show();
    }

    private void showAddOptions(LinearLayout parent, Runnable dismiss) {
        LinearLayout primary = addOption("拍照 / 选图", NavIcon.CAMERA, true, () -> {
            dismiss.run();
            ui.choose("添加照片", new String[]{"拍照", "相册"}, index -> pickNew(index == 0 ? "camera" : "photo"));
        });
        parent.addView(primary, new LinearLayout.LayoutParams(-1, ui.dp(52)));
        ui.gap(parent, 14);
        LinearLayout secondary = ui.row();
        secondary.setBaselineAligned(false);
        secondary.addView(addOption("批量导入", NavIcon.PHOTOS, false, () -> {
            dismiss.run();
            ui.requestBatchImages();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        ui.space(secondary, 10);
        secondary.addView(addOption("网购导入", NavIcon.SHOPPING, false, () -> {
            dismiss.run();
            new TaobaoImport(ui, this::importTaobao, () -> pickNew("shop")).start();
        }), new LinearLayout.LayoutParams(0, -2, 1));
        ui.space(secondary, 10);
        secondary.addView(addOption("手动建档", NavIcon.EDIT, false, () -> {
            dismiss.run();
            editor(new WardrobeStore.Garment(), this::home);
        }), new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(secondary, new LinearLayout.LayoutParams(-1, -2));
    }

    private LinearLayout addOption(String label, int icon, boolean primary, Runnable action) {
        LinearLayout option = primary ? ui.row() : ui.vertical();
        option.setGravity(Gravity.CENTER);
        option.setPadding(ui.dp(8), primary ? 0 : ui.dp(16), ui.dp(8), primary ? 0 : ui.dp(16));
        int color = primary ? Color.WHITE : INK;
        option.addView(new NavIcon(ui, icon, false, color),
                new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        if (primary) ui.space(option, 10); else ui.gap(option, 10);
        TextView title = ui.text(label, primary ? 15 : 13, color, false);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        option.addView(title);
        option.setContentDescription(label);
        option.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        option.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                    android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName("android.widget.Button");
            }
        });
        ui.click(option, primary ? INK : 0xfff7f8fa, 12, 0, action);
        return option;
    }

    void confirmBatchImport(List<Uri> images) {
        ui.prompt("批量导入 " + images.size() + " 张照片",
                "每张照片添加一件单品。未识别时保留原图，可稍后编辑。",
                "开始导入", () -> ui.task("导入中  " + images.size() + " 张照片…",
                        () -> importBatch(images), message -> {
                            home();
                            Sheet summary = new Sheet(ui, "导入结果", true);
                            summary.description(message);
                            summary.button("完成", true, summary::dismiss);
                            summary.show();
                        }));
    }

    private String importBatch(List<Uri> images) {
        List<String> failed = new ArrayList<>();
        int originalCount = 0;
        for (int i = 0; i < images.size(); i++) {
            Bitmap original = null;
            Bitmap cutout = null;
            try {
                original = ImageAnalysis.decode(ui, images.get(i));
                ImageAnalysis.Detection found = detectGarment(original);
                WardrobeStore.Garment garment = new WardrobeStore.Garment();
                Point origin = null;
                if (found != null) {
                    garment.type = found.type();
                    cutout = found.image();
                    origin = new Point(found.left(), found.top());
                }
                if (garment.type.isEmpty()) garment.type = "其他";
                garment.name = (found == null ? "待整理衣物" : garment.type) + " " + (i + 1);
                garment.colors = ui.colorReader.read(cutout == null ? original : cutout, found != null, false);
                db.save(garment, original, cutout, origin, cutout == null);
                if (found == null) originalCount++;
            } catch (Exception | LinkageError e) {
                Log.w("SmartWardrobe", "Batch import image " + (i + 1), e);
                failed.add(Integer.toString(i + 1));
            } finally {
                if (cutout != null && cutout != original) cutout.recycle();
                if (original != null) original.recycle();
            }
        }
        String message = "已导入 " + (images.size() - failed.size()) + " / " + images.size() + " 张";
        if (originalCount > 0) message += " / " + originalCount + " 张保留原图";
        if (!failed.isEmpty()) message += "\n未导入：" + String.join("、", failed);
        return message;
    }

    private ImageAnalysis.Detection detectGarment(Bitmap image) {
        try { return ui.garmentDetector.detect(image); }
        catch (Exception | LinkageError e) { Log.w("SmartWardrobe", "Garment detection", e); return null; }
    }

    private void importTaobao(TaobaoImport.Result result) {
        WardrobeStore.Garment garment = new WardrobeStore.Garment();
        garment.name = result.name();
        editor(garment, this::home).useImage(result.image(), false);
    }

    private void pickNew(String mode) {
        ui.requestImage(mode, 0, mode.equals("camera"), restoreImport(mode, 0));
    }

    Consumer<Bitmap> restoreImport(String purpose, long id) {
        return bitmap -> {
            WardrobeStore.Garment g = id == 0 ? new WardrobeStore.Garment() : db.garment(id);
            if (g == null) return;
            Runnable back = this::home;
            if (purpose.equals("label") || purpose.equals("label-detail")) {
                restoreCareScan(id, purpose.equals("label-detail")).accept(bitmap, null);
            } else editor(g, back).useImage(bitmap, purpose.equals("shop"));
        };
    }

    java.util.function.BiConsumer<Bitmap, List<ImageAnalysis.SymbolHit>> restoreCareScan(long id, boolean direct) {
        return (image, hits) -> {
            WardrobeStore.Garment g = id == 0 ? new WardrobeStore.Garment() : db.garment(id);
            if (g == null) return;
            Runnable back = this::home;
            Editor form = editor(g, back);
            form.scanLabel(image, "", hits);
        };
    }

    private View iconButton(int icon, boolean active, String label, Runnable action) {
        FrameLayout button = new FrameLayout(ui);
        button.setContentDescription(label);
        button.addView(new NavIcon(ui, icon, active),
                new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
        ui.click(button, Color.TRANSPARENT, 8, 0, action);
        return button;
    }

    private View favoriteButton(WardrobeStore.Garment garment, Runnable refresh) {
        return favoriteButton(garment, refresh, true);
    }

    private View favoriteButton(WardrobeStore.Garment garment, Runnable refresh, boolean named) {
        FrameLayout button = (FrameLayout) iconButton(NavIcon.FAVORITE, garment.favorite, "", () -> {});
        button.setContentDescription((garment.favorite ? "取消收藏" : "收藏") + (named ? " " + garment.name : ""));
        button.setOnClickListener(view -> {
            garment.favorite = !garment.favorite;
            db.state(garment.id, "favorite", garment.favorite);
            button.removeAllViews();
            button.addView(new NavIcon(ui, NavIcon.FAVORITE, garment.favorite),
                    new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            button.setContentDescription((garment.favorite ? "取消收藏" : "收藏") + (named ? " " + garment.name : ""));
            Motion.favorite(button, () -> { if (ui.isCurrentView(button)) refresh.run(); });
        });
        return button;
    }

    private void archive() {
        ui.page("archive", "已归档衣物", "", this::home, null, null);
        List<WardrobeStore.Garment> archived = db.garments(true);
        if (archived.isEmpty()) ui.empty(ui.body, "暂无归档", "");
        for (var garment : archived) {
            LinearLayout card = itemCard(ui.body, garment, garment.type, () -> detail(garment.id, this::archive));
            card.addView(ui.pill("恢复到衣橱", false, () -> {
                db.archiveGarment(garment.id, false);
                archive();
            }));
        }
    }

    void detail(long id, Runnable back) {
        WardrobeStore.Garment garment = db.garment(id);
        if (garment == null) { back.run(); return; }
        editor(garment, back);
    }

    private void confirmDelete(WardrobeStore.Garment garment, Runnable back) {
        var links = db.associations(garment.id);
        Set<Long> ids = new LinkedHashSet<>(Collections.singletonList(garment.id));
        Runnable remove = () -> removeGarments(ids, back);
        if (!links.any()) {
            ui.confirmDestructive("删除衣物", "删除衣物及照片，无法恢复。", "彻底删除", remove);
            return;
        }
        Sheet sheet = new Sheet(ui, "删除衣物", true);
        sheet.description("关联 " + links.outfits() + " 套穿搭 / " + links.histories()
                + " 次穿着记录。\n删除将移除关联单品及变空的穿搭与记录。衣物及照片无法恢复。");
        sheet.content.addView(ui.pill("归档并保留历史", true, () -> {
            sheet.dismiss();
            db.archiveGarment(garment.id, true);
            back.run();
            ui.bottomNotice("已归档 / 历史已保留");
        }));
        sheet.destructiveOption("彻底删除", () -> { sheet.dismiss(); remove.run(); });
        sheet.button("取消", false, sheet::dismiss);
        sheet.show();
    }

    private void markSelectedDirty(Set<Long> ids) {
        if (ids.isEmpty()) return;
        int count = db.markDirty(ids);
        exitSelection();
        ui.bottomNotice("已将 " + count + " 件衣物标为待洗");
    }

    private void archiveSelected(Set<Long> ids) {
        if (ids.isEmpty()) return;
        db.archiveGarments(ids);
        exitSelection();
        ui.bottomNotice("已归档 " + ids.size() + " 件 / 历史已保留");
    }

    private void deleteSelected(Set<Long> ids) {
        if (ids.isEmpty()) return;
        long linked = ids.stream().filter(id -> db.associations(id).any()).count();
        String message = "删除 " + ids.size() + " 件衣物 / " + linked
                + " 件有关联记录。\n关联单品及变空的穿搭与记录将被移除，衣物及照片无法恢复。";
        ui.confirmDestructive("删除所选衣物", message, "彻底删除", () -> removeGarments(ids, this::exitSelection));
    }

    private void removeGarments(Set<Long> ids, Runnable back) {
        ui.task("删除中…", () -> db.deleteGarments(ids), count -> {
            ui.outfits.garmentsDeleted(ids);
            back.run();
            ui.bottomNotice("已删除 " + count + " 件衣物");
        });
    }

    private void takeOut(WardrobeStore.Garment g, Runnable refresh) {
        db.state(g.id, "stored", false);
        refresh.run();
        ui.bottomNotice("已取出");
    }

    void care() {
        List<WardrobeStore.Garment> garments = db.garments();
        Map<String, List<WardrobeStore.Garment>> buckets = new LinkedHashMap<>();
        int pending = 0;
        for (var garment : garments) {
            if (!garment.dirty) continue;
            pending++;
            String symbol = Arrays.stream(garment.symbols.split("\\R")).filter(s -> Arrays.asList(SYMBOLS[0]).contains(s))
                    .findFirst().orElse("未标注洗标");
            buckets.computeIfAbsent(symbol, k -> new ArrayList<>()).add(garment);
        }
        careHeader(pending);
        seasonalReminder(garments);
        ui.ensureWeatherPermission(false);
        LinearLayout overview = ui.vertical();
        ui.body.addView(overview);
        meter(overview, "衣橱洁净度", garments.size() - pending, garments.size());
        ui.coachTarget("care-main", overview);
        if (garments.isEmpty()) { ui.gap(ui.body, 24); careEmpty("暂无衣物"); return; }
        ui.gap(ui.body, 24);
        var groups = new ArrayList<>(buckets.entrySet());
        groups.sort((a, b) -> {
            int allowed = Boolean.compare(canBatch(b.getKey()), canBatch(a.getKey()));
            return allowed == 0 ? Integer.compare(b.getValue().size(), a.getValue().size()) : allowed;
        });
        if (groups.isEmpty()) careEmpty("暂无待洗");
        else if (canBatch(groups.get(0).getKey()) && groups.get(0).getValue().size() > 1) {
            var first = groups.get(0);
            LinearLayout featured = careCard(ui.body);
            ui.coachTarget("care-group", featured);
            featured.addView(ui.text("优先处理", 11, MUTED, false));
            featured.addView(ui.text(first.getKey(), 16, INK, true));
            ui.gap(featured, 8);
            featured.addView(ui.pill("整组已洗", true, () -> batchWash(first.getValue())));
            ui.gap(featured, 10);
            meter(featured, "本组待洗占比", first.getValue().size(), pending);
            ui.gap(ui.body, 12);
        }
        for (var group : groups) {
            LinearLayout header = ui.row();
            if (group == groups.get(0) && !(canBatch(group.getKey()) && group.getValue().size() > 1))
                ui.coachTarget("care-group", header);
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(ui.text(group.getKey(), 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(ui.text(group.getValue().size() + " 件", 12, MUTED, false));
            if (canBatch(group.getKey()) && group.getValue().size() > 1) {
                ui.space(header, 8);
                header.addView(careOutlineButton("整组已洗", () -> batchWash(group.getValue())));
            }
            ui.body.addView(header);
            ui.gap(ui.body, 5);
            careRule(ui.body);
            for (var g : group.getValue()) careRow(g, g.type, "已洗",
                    () -> recordWash(g, this::care), this::care);
            ui.gap(ui.body, 16);
        }
        int reminders = 0;
        for (WardrobeStore.Garment g : garments) {
            WardrobeStore.Usage usage = db.usage(g.id);
            String reminder = reminder(g, usage);
            if (reminder.isEmpty()) continue;
            if (reminders++ == 0) ui.section(ui.body, "护理提醒");
            reminderCard(ui.body, g, reminder, this::care);
        }
    }

    private static boolean canBatch(String symbol) {
        return !symbol.equals("未标注洗标") && !symbol.equals("不可水洗");
    }

    private void batchWash(List<WardrobeStore.Garment> garments) {
        washToday(garments, this::care);
    }

    private void careHeader(long pending) {
        ui.page("wash-care", "", pending == 0 ? "" : pending + " 件待洗", null, "换季整理", this::openSeasonal);
        ui.secondaryHeaderAction();
    }

    private LinearLayout careCard(LinearLayout parent) {
        LinearLayout card = ui.card(parent);
        android.graphics.drawable.GradientDrawable border = ui.shape(PAPER, 0, 0);
        border.setStroke(1, INK);
        card.setBackground(border);
        return card;
    }

    private TextView careOutlineButton(String label, Runnable action) {
        TextView button = ui.pill(label, false, action);
        android.graphics.drawable.GradientDrawable outline = ui.shape(PAPER, 0, 0);
        outline.setStroke(1, INK);
        button.setBackground(outline);
        return button;
    }

    private void careRule(LinearLayout parent) {
        View line = new View(ui);
        line.setBackgroundColor(INK);
        parent.addView(line, new LinearLayout.LayoutParams(-1, 1));
    }

    private void careEmpty(String title) {
        careCard(ui.body).addView(ui.text(title, 17, INK, true));
    }

    private void openSeasonal() {
        manualSeasonChoice = false;
        currentSeason = ui.weather.advice().season();
        season();
    }

    void weatherChanged() {
        if (ui.guiding()) return;
        if (ui.isPage("wash-care")) care();
        else if (ui.isPage("seasonal-organize")) {
            if (!manualSeasonChoice) currentSeason = ui.weather.advice().season();
            season();
        }
    }

    private void weatherCredit(LinearLayout parent) {
        TextView credit = ui.ghost("天气来源：Open-Meteo", () -> {
            try { ui.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                    Uri.parse("https://open-meteo.com/"))); } catch (RuntimeException ignored) {}
        });
        credit.setTextSize(11);
        credit.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
        credit.setMinHeight(ui.dp(32));
        credit.setMinimumHeight(ui.dp(32));
        credit.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        credit.setPadding(0, 0, 0, 0);
        parent.addView(credit);
    }

    private void seasonalReminder(List<WardrobeStore.Garment> garments) {
        if (ui.weather.reminderDismissed()) return;
        SeasonWeather.Advice advice = ui.weather.advice();
        int stored = 0, care = 0;
        for (var garment : garments) {
            if ((garment.seasons & (1 << advice.season())) == 0) continue;
            if (garment.stored) stored++;
            WardrobeStore.Usage usage = db.usage(garment.id);
            if (garment.dirty || !reminder(garment, usage).isEmpty()
                    || (garment.stored && idleDays(garment, usage) > 60)) care++;
        }
        if (stored == 0 && care == 0) return;
        LinearLayout card = careCard(ui.body);
        String temperature = advice.weatherBased() ? String.format(Locale.ROOT,"气温降至 %.0f°C  ",advice.averageHigh()) : "";
        LinearLayout heading = ui.row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.text(temperature + SEASONS[advice.season()] + "季衣橱待唤醒：", 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(iconButton(NavIcon.CLOSE, false, "本季度关闭换季提醒", () -> {
            ui.weather.dismissReminder();
            care();
        }), new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
        card.addView(heading);
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        String message = stored + " 件已收起 / " + care + " 件需护理";
        row.addView(ui.text(message, 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        ui.space(row, 10);
        row.addView(ui.pill("去整理", true, this::openSeasonal));
        card.addView(row);
        if (advice.weatherBased()) weatherCredit(card);
    }

    private void meter(LinearLayout parent, String title, int count, int total) {
        LinearLayout heading = ui.row();
        heading.addView(ui.text(title, 12, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        int percent = total == 0 ? 0 : count * 100 / total;
        heading.addView(ui.text(count + " / " + total + " 件 / " + percent + "%", 12, MUTED, false));
        parent.addView(heading);
        ui.gap(parent, 6);
        View bar = new View(ui) {
            private final android.graphics.Paint paint = new android.graphics.Paint();

            @Override protected void onDraw(android.graphics.Canvas canvas) {
                super.onDraw(canvas);
                paint.setColor(INK);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawRect(0, 0, getWidth() * percent / 100f, getHeight(), paint);
                paint.setStyle(android.graphics.Paint.Style.STROKE);
                paint.setStrokeWidth(1);
                canvas.drawRect(.5f, .5f, getWidth() - .5f, getHeight() - .5f, paint);
            }
        };
        bar.setBackgroundColor(PAPER);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(8)));
    }

    private LinearLayout careRow(WardrobeStore.Garment g, String subtitle, String action, Runnable run, Runnable back) {
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout item = ui.row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = ui.photo(g.photo, 4, 63);
        thumb.setBackgroundColor(PAPER);
        item.addView(thumb, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(63)));
        ui.space(item, 10);
        LinearLayout text = ui.vertical();
        TextView name = ui.text(g.name, 14, INK, true);
        name.setMaxLines(2);
        text.addView(name);
        text.addView(ui.text(subtitle, 11, MUTED, false));
        item.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        ui.click(item, PAPER, 0, 0, () -> detail(g.id, back));
        line.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            ui.space(line, 6);
            line.addView(ui.pill(action, true, run));
        }
        ui.body.addView(line);
        careRule(ui.body);
        return line;
    }

    private void recordWash(WardrobeStore.Garment g, Runnable refresh) {
        washToday(Collections.singletonList(g), refresh);
    }

    private void washToday(List<WardrobeStore.Garment> garments, Runnable refresh) {
        var changes = db.washToday(garments);
        refresh.run();
        ui.undoNotice("已记录今日洗涤", () -> {
            db.undoWash(changes);
            refresh.run();
            ui.bottomNotice("已撤销洗涤记录");
        });
    }

    private void backdateWash(WardrobeStore.Garment g, Runnable refresh) {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        ui.date("补记洗涤", yesterday, yesterday,
                "仅补录历史洗涤次数，不影响当前是否待洗", day -> {
            db.wash(g.id, day.toString(), false);
            refresh.run();
            ui.bottomNotice("已补记洗涤");
        });
    }

    private void washHistory(WardrobeStore.Garment g) {
        List<String> dates = db.washes(g.id);
        history("洗涤历史", dates);
    }

    private void careHistory(WardrobeStore.Garment g) {
        List<String> notes = new ArrayList<>();
        for (WardrobeStore.CareEvent event : db.checks(g.id)) notes.add(event.day() + "  " + event.note());
        history("护理记录", notes);
    }

    private void history(String title, List<String> entries) {
        Sheet sheet = new Sheet(ui, title, true);
        if (entries.isEmpty()) sheet.description("暂无记录");
        for (String entry : entries) {
            sheet.description(entry);
            ui.rule(sheet.content);
        }
        sheet.show();
    }

    String reminder(WardrobeStore.Garment g, WardrobeStore.Usage stats) {
        List<String> lines = new ArrayList<>();
        if (g.washEvery > 0 && stats.sinceCare() >= g.washEvery) {
            String fabric = g.fabric.toLowerCase(Locale.ROOT);
            String checks = fabric.matches("(?s).*(毛|wool|cashmere).*") ? "起球和变形"
                    : fabric.matches("(?s).*(棉|cotton|牛仔).*") ? "褪色和变形" : "起球、变形或褪色";
            lines.add("已洗涤 " + stats.sinceCare() + " 次 / 检查" + checks + "。");
            if (!g.care.isEmpty() || !g.symbols.isEmpty()) lines.add("请按洗标护理");
        }
        long days = idleDays(g, stats);
        if (g.idleDays > 0 && days >= g.idleDays) lines.add("已 " + days + " 天未使用 / 检查通风与除尘");
        return String.join("\n", lines);
    }

    private long idleDays(WardrobeStore.Garment g, WardrobeStore.Usage stats) {
        String from = null;
        for (String day : new String[]{stats.lastWorn(), stats.washed(), stats.lastCare()}) {
            if (day != null && (from == null || day.compareTo(from) > 0)) from = day;
        }
        return ChronoUnit.DAYS.between(LocalDate.parse(from == null ? g.created : from), LocalDate.now());
    }

    private String storedCareReason(WardrobeStore.Garment g, WardrobeStore.Usage usage) {
        List<String> reasons = new ArrayList<>();
        if (g.dirty) reasons.add("收起前未洗");
        long days = idleDays(g, usage);
        if (days > 60) reasons.add("久置 " + days + " 天");
        if (!reminder(g, usage).isEmpty()) reasons.add("需护理");
        return String.join(" / ", reasons);
    }

    private void reminderCard(LinearLayout parent, WardrobeStore.Garment g, String note, Runnable refresh) {
        LinearLayout card = itemCard(parent, g, note, null);
        android.graphics.drawable.GradientDrawable border = ui.shape(PAPER, 0, 0);
        border.setStroke(1, INK);
        card.setBackground(border);
        card.addView(ui.pill("完成护理检查", true, () -> completeCare(g, refresh)));
    }

    private void completeCare(WardrobeStore.Garment g, Runnable refresh) {
        Sheet sheet = new Sheet(ui, "护理检查", true);
        EditText input = ui.field(sheet.content, "检查结果", "", true, false);
        sheet.button("完成护理", true, () -> {
            String value = ui.value(input);
            db.checked(g.id, value.isEmpty() ? "完成护理检查" : value);
            sheet.dismiss();
            refresh.run();
            ui.toast("已完成护理");
        });
        sheet.show();
    }

    void season() {
        List<WardrobeStore.Garment> garments = db.garments();
        ui.page("seasonal-organize", "换季整理", "", this::care, null, null);
        SeasonWeather.Advice advice = ui.weather.advice();
        String basis = advice.weatherBased()
                ? String.format(Locale.ROOT,"未来3天平均最高温 %.0f°C  建议%s季",advice.averageHigh(),SEASONS[advice.season()])
                : "按当前月份推荐" + SEASONS[advice.season()] + "季";
        ui.body.addView(ui.text(basis, 13, MUTED, false));
        if (advice.weatherBased()) weatherCredit(ui.body);
        if (!ui.weather.hasPermission()) ui.body.addView(ui.ghost("使用当地气温", () -> ui.ensureWeatherPermission(true)));
        ui.gap(ui.body, 14);
        LinearLayout seasons = ui.row();
        for (int i = 0; i < SEASONS.length; i++) {
            int index = i;
            Runnable selectSeason = () -> {
                currentSeason = index;
                manualSeasonChoice = true;
                season();
            };
            TextView button = i == currentSeason ? ui.pill(SEASONS[i] + "季", true, selectSeason)
                    : careOutlineButton(SEASONS[i] + "季", selectSeason);
            button.setSelected(i == currentSeason);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) p.leftMargin = ui.dp(7);
            seasons.addView(button, p);
        }
        ui.body.addView(seasons);
        ui.gap(ui.body, 20);
        Map<WearStatus, List<WardrobeStore.Garment>> groups = new java.util.EnumMap<>(WearStatus.class);
        List<WardrobeStore.Garment> storedReady = new ArrayList<>();
        List<WardrobeStore.Garment> storedCare = new ArrayList<>();
        List<WardrobeStore.Garment> untagged = new ArrayList<>();
        int total = 0;
        for (WardrobeStore.Garment g : garments) {
            if (g.seasons == 0) {
                untagged.add(g);
                continue;
            }
            if ((g.seasons & (1 << currentSeason)) == 0) continue;
            total++;
            WardrobeStore.Usage usage = db.usage(g.id);
            if (g.stored) {
                if (g.dirty || !reminder(g, usage).isEmpty() || idleDays(g, usage) > 60) storedCare.add(g);
                else storedReady.add(g);
            } else {
                groups.computeIfAbsent(wearStatus(g, usage), status -> new ArrayList<>()).add(g);
            }
        }
        int pending = (int) garments.stream().filter(g -> g.dirty && (g.seasons & (1 << currentSeason)) != 0).count();
        String season = SEASONS[currentSeason] + "季";
        seasonSummary(season, total, groups.getOrDefault(WearStatus.READY, Collections.emptyList()).size(), pending,
                storedReady.size() + storedCare.size());
        if (total == 0) careEmpty(season + "暂无适用衣物");
        storedSection("可直接穿", storedReady, false);
        storedSection("需洗护后再穿", storedCare, true);
        for (WearStatus status : new WearStatus[]{WearStatus.DIRTY, WearStatus.CARE, WearStatus.READY}) {
            List<WardrobeStore.Garment> group = groups.getOrDefault(status, Collections.emptyList());
            if (group.isEmpty()) continue;
            String title = switch (status) {
                case READY -> "洁净就绪";
                default -> status.label;
            };
            ui.section(ui.body, title + "  " + group.size());
            for (WardrobeStore.Garment g : group) {
                switch (status) {
                    case DIRTY -> careRow(g, g.type, "已洗", () -> recordWash(g, this::season), this::season);
                    case CARE -> careRow(g, g.type, "完成护理检查", () -> completeCare(g, this::season), this::season);
                    default -> careRow(g, g.type, null, null, this::season);
                }
            }
        }
        if (!untagged.isEmpty()) {
            ui.section(ui.body, "季节未标注");
            for (var garment : untagged) {
                careRow(garment, garment.type, "设置季节",
                        () -> chooseSeasons(garment.seasons, bits -> {
                            db.setSeasons(garment.id, bits);
                            season();
                        }), this::season);
            }
        }
    }

    private void storedSection(String title, List<WardrobeStore.Garment> garments, boolean needsWash) {
        if (garments.isEmpty()) return;
        ui.section(ui.body, "已收起 / " + title + "  " + garments.size());
        ui.body.addView(ui.pill(needsWash ? "一键取出并送入待洗" : "一键全部取出", true, () -> {
            db.takeOut(garments, needsWash);
            season();
            ui.bottomNotice(needsWash ? "已取出并加入待洗" : "已全部取出");
        }));
        for (var g : garments) {
            LinearLayout line = careRow(g, needsWash ? storedCareReason(g, db.usage(g.id)) : "已收起 / 洁净",
                    null, null, this::season);
            if (needsWash) {
                LinearLayout actions = ui.row();
                TextView send = seasonalItemAction("送洗", g.name, () -> {
                    db.takeOut(Collections.singletonList(g), true);
                    finishSeasonalAction(line, actions, "已取出并加入待洗");
                });
                TextView wear = seasonalItemAction("直接穿", g.name, () -> {
                    db.takeOutReady(g.id);
                    finishSeasonalAction(line, actions, "已取出并完成换季检查");
                });
                actions.addView(send);
                ui.space(actions, 8);
                actions.addView(wear);
                ui.space(line, 8);
                line.addView(actions);
            }
        }
        ui.gap(ui.body, 16);
    }

    private TextView seasonalItemAction(String label, String name, Runnable action) {
        TextView button = careOutlineButton(label, action);
        button.setTextSize(12);
        button.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        button.setMinWidth(ui.dp(48));
        button.setMinimumWidth(ui.dp(48));
        button.setContentDescription(name + " " + label);
        return button;
    }

    private void finishSeasonalAction(LinearLayout line, LinearLayout actions, String message) {
        for (int i = 0; i < actions.getChildCount(); i++) actions.getChildAt(i).setEnabled(false);
        line.getChildAt(0).setEnabled(false);
        Runnable finish = () -> {
            if (!ui.isCurrentView(line)) return;
            season();
            ui.bottomNotice(message);
        };
        if (Motion.enabled()) line.animate().alpha(0).translationX(-ui.dp(8)).setDuration(160).withEndAction(finish).start();
        else finish.run();
    }

    private Sheet chooseSeasons(int initial, java.util.function.IntConsumer chosen) {
        Sheet sheet = new Sheet(ui, "适用季节", true);
        sheet.description("可多选 / 留空则不标注");
        android.widget.CheckBox[] boxes = new android.widget.CheckBox[SEASONS.length];
        for (int i = 0; i < boxes.length; i++) {
            var box = new android.widget.CheckBox(ui);
            box.setText(SEASONS[i] + "季");
            box.setMinHeight(ui.dp(48));
            box.setChecked((initial & (1 << i)) != 0);
            boxes[i] = box;
            sheet.content.addView(box);
        }
        sheet.content.addView(ui.ghost("四季适用", () -> {
            for (var box : boxes) box.setChecked(true);
        }));
        sheet.button("确认", true, () -> {
            int bits = 0;
            for (int i = 0; i < boxes.length; i++) if (boxes[i].isChecked()) bits |= 1 << i;
            sheet.dismiss();
            chosen.accept(bits);
        });
        sheet.show();
        return sheet;
    }

    private void seasonSummary(String season, int total, int ready, int pending, int stored) {
        LinearLayout status = careCard(ui.body);
        status.addView(ui.text(season + "衣橱 / " + total + " 件", 12, MUTED, false));
        int percent = total == 0 ? 0 : Math.round(100f * ready / total);
        status.addView(ui.text("可直接穿 " + ready + " / " + total + " 件 / " + percent + "%", 15, INK, true));
        if (total == 0) return;
        ui.gap(status, 10);
        // Dirty and stored are independent counts; only readiness partitions the bar.
        int[] amounts = {ready, total - ready};
        int[] shades = {INK, PAPER};
        LinearLayout bar = ui.row();
        bar.setWeightSum(total);
        android.graphics.drawable.GradientDrawable outline = ui.shape(PAPER, 0, 0);
        outline.setStroke(1, INK);
        bar.setBackground(outline);
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        for (int i = 0; i < amounts.length; i++) {
            if (amounts[i] == 0) continue;
            View segment = new View(ui);
            segment.setBackgroundColor(shades[i]);
            bar.addView(segment, new LinearLayout.LayoutParams(0, -1, amounts[i]));
        }
        status.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(8)));
        ui.gap(status, 10);
        LinearLayout counts = ui.row();
        String[] names = {"可直接穿", "待洗", "已收起"};
        int[] totals = {ready, pending, stored};
        for (int i = 0; i < names.length; i++) {
            TextView count = ui.text(names[i] + "\n" + totals[i] + " 件", 12, INK, true);
            android.graphics.drawable.GradientDrawable outlineCount = ui.shape(PAPER, 0, 0);
            outlineCount.setStroke(1, INK);
            count.setBackground(outlineCount);
            count.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) p.leftMargin = ui.dp(7);
            counts.addView(count, p);
        }
        status.addView(counts);
        if (pending > 0) {
            ui.gap(status, 10);
            status.addView(ui.pill("查看待洗", true, this::care));
        }
    }

    LinearLayout itemCard(LinearLayout parent, WardrobeStore.Garment g, String desc, Runnable click) {
        LinearLayout card = ui.card(parent);
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = ui.photo(g.photo, 4, 55);
        thumb.setBackgroundColor(PAPER);
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(57), ui.dp(56)));
        if (g.photo.isEmpty()) thumb.setContentDescription("暂无衣物照片");
        ui.space(row, 10);
        LinearLayout text = ui.vertical();
        text.addView(ui.text(g.name, 16, INK, true));
        text.addView(ui.text(desc, 13, MUTED, false));
        row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        if (click != null) {
            row.addView(ui.text("›", 21, INK, true));
            ui.click(row, CARD, 10, 0, click);
        }
        card.addView(row);
        ui.gap(card, 7);
        return card;
    }

    private void cardColor(LinearLayout row, WardrobeStore.Swatch swatch, boolean flexible) {
        ui.space(row, 5);
        View dot = new View(ui);
        dot.setBackground(ui.shape(swatch.rgb, 6, STROKE));
        row.addView(dot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
        ui.space(row, 4);
        TextView name = ui.text(swatch.name, 11, MUTED, false);
        name.setSingleLine();
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(flexible ? 0 : -2, -2, flexible ? 1 : 0));
    }

    private void colorNames(LinearLayout parent, List<WardrobeStore.Swatch> colors) {
        if (colors.isEmpty()) return;
        android.widget.HorizontalScrollView strip = new android.widget.HorizontalScrollView(ui);
        strip.setHorizontalScrollBarEnabled(false);
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        for (WardrobeStore.Swatch swatch : colors) {
            row.addView(ui.colorDot(swatch.rgb, 16));
            ui.space(row, 5);
            row.addView(ui.text(swatch.name, 12, MUTED, false));
            ui.space(row, 12);
        }
        strip.addView(row);
        parent.addView(strip);
    }

    private String dateText(String date) { return date == null ? "暂无记录" : date; }

    private enum WearStatus {
        READY("可穿", "标为待洗"),
        DIRTY("待洗", "已洗"),
        CARE("需护理", "完成护理检查"),
        STORED("已收起", "取出衣物"),
        ARCHIVED("已归档", "恢复到衣橱");

        final String label, action;
        WearStatus(String label, String action) {
            this.label = label;
            this.action = action;
        }
    }

    private WearStatus wearStatus(WardrobeStore.Garment garment, WardrobeStore.Usage usage) {
        if (garment.archived) return WearStatus.ARCHIVED;
        if (garment.dirty) return WearStatus.DIRTY;
        if (garment.stored) return WearStatus.STORED;
        if (!reminder(garment, usage).isEmpty()) return WearStatus.CARE;
        return WearStatus.READY;
    }

    private Editor editor(WardrobeStore.Garment g, Runnable back) {
        Editor editor = new Editor(g, back);
        editor.render();
        return editor;
    }

    private final class Editor {
        private final WardrobeStore.Garment g;
        private final Runnable exit;
        private Bitmap imported, cutout, target;
        private Point cutoutOrigin;
        private boolean originalSelected, colorCheck;
        private EditText name, brand, fabric, care, wash, idle;
        private TextView kind, season, labelTextButton, addPhoto, reminderButton, noteButton;
        private ImageView preview;
        private View photoSwitch, photoEdit;
        private LinearLayout labelTextRow, meaningPanel;
        private final CareSymbolView[] careIcons = new CareSymbolView[5];
        private final View[] careMarks = new View[5];
        private int selectedCare = -1;
        private LinearLayout colorBox, operations;
        private final LinearLayout[] panels = new LinearLayout[3];
        private final TextView[] tabButtons = new TextView[3];
        private SlidingTabs tabStrip;
        private DisclosureArrow seasonArrow;
        private TextView saveButton;
        private int activeTab;
        private boolean tabsPinned;
        private boolean previewOriginal;
        private String baseline;

        Editor(WardrobeStore.Garment garment, Runnable back) {
            g = garment;
            exit = back;
            cutoutOrigin = g.cutoutOrigin;
            originalSelected = !g.original.isEmpty() && g.photo.equals(g.original);
            previewOriginal = originalSelected;
            baseline = fingerprint();
        }

        void render() {
            saveButton = ui.garmentPage(this, () -> leave(exit), this::save);
            renderPhoto();
            operations = ui.vertical();
            ui.body.addView(operations);
            renderOperations();
            LinearLayout tabs = ui.row();
            String[] labels = {"档案", "洗护", "记录"};
            for (int i = 0; i < labels.length; i++) {
                final int index = i;
                LinearLayout segment = ui.vertical();
                TextView button = ui.ghost(labels[i], () -> selectTab(index));
                button.setContentDescription(labels[i] + " Tab");
                tabButtons[i] = button;
                segment.addView(button, new LinearLayout.LayoutParams(-1, ui.dp(48)));
                View underline = new View(ui);
                underline.setBackgroundColor(0xffefefef);
                segment.addView(underline, new LinearLayout.LayoutParams(-1, ui.dp(2)));
                tabs.addView(segment, new LinearLayout.LayoutParams(0, -2, 1));
            }
            tabStrip = new SlidingTabs(ui, tabs, 3, activeTab, 0, false, -1);
            ui.body.addView(tabStrip);
            for (int i = 0; i < panels.length; i++) {
                panels[i] = ui.vertical();
                panels[i].setPadding(ui.dp(20), ui.dp(12), ui.dp(20), 0);
                ui.body.addView(panels[i]);
            }
            LinearLayout profile = panels[0];
            name = inlineField(profile, "名称 *", g.name, false, false);
            kind = softButton("选择类别", () -> selectType(null, value -> { g.type = value; refreshText(); }));
            valueRow(profile, "类别 *", kind);
            brand = inlineField(profile, "品牌", g.brand, false, false);
            fabric = inlineField(profile, "材质", g.fabric, false, false);
            season = softButton("季节未标注", this::setSeasons);
            seasonArrow = new DisclosureArrow(ui, false);
            season.setCompoundDrawablesRelative(null, null, seasonArrow, null);
            season.setCompoundDrawablePadding(ui.dp(5));
            valueRow(profile, "季节", season);
            LinearLayout colorsHeading = ui.row();
            colorsHeading.setGravity(Gravity.CENTER_VERTICAL);
            colorsHeading.addView(ui.text("颜色", 14, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
            colorsHeading.addView(ui.ghost("+ 添加", () -> colorDialog(null)));
            profile.addView(colorsHeading);
            colorBox = ui.vertical();
            profile.addView(colorBox);
            LinearLayout carePanel = panels[1];
            LinearLayout scan = ui.row();
            scan.setBaselineAligned(false);
            scan.addView(outlinedButton("扫描洗标", this::labelSource), new LinearLayout.LayoutParams(0, ui.dp(38), 1));
            ui.space(scan, 10);
            scan.addView(outlinedButton("编辑符号", this::symbolPicker), new LinearLayout.LayoutParams(0, ui.dp(38), 1));
            carePanel.addView(scan);
            ui.gap(carePanel, 14);
            LinearLayout symbols = ui.row();
            for (int i = 0; i < 5; i++) {
                final int group = i;
                LinearLayout slot = ui.vertical();
                FrameLayout button = new FrameLayout(ui);
                careIcons[i] = new CareSymbolView(ui, i, careMeaning(i));
                button.addView(careIcons[i], new FrameLayout.LayoutParams(ui.dp(28), ui.dp(28), Gravity.CENTER));
                button.setContentDescription(SYMBOLS[i][0] + "要求");
                ui.click(button, PAPER, 0, 0, () -> showMeaning(group));
                slot.addView(button, new LinearLayout.LayoutParams(-1, ui.dp(48)));
                careMarks[i] = new View(ui);
                slot.addView(careMarks[i], new LinearLayout.LayoutParams(-1, 1));
                symbols.addView(slot, new LinearLayout.LayoutParams(0, -2, 1));
            }
            carePanel.addView(symbols);
            meaningPanel = ui.vertical();
            meaningPanel.setVisibility(selectedCare >= 0 ? View.VISIBLE : View.GONE);
            carePanel.addView(meaningPanel);
            care = detachedField(g.care);
            wash = detachedField(Integer.toString(g.washEvery));
            idle = detachedField(Integer.toString(g.idleDays));
            noteButton = softButton("添加", this::editNote);
            valueRow(carePanel, "备注", noteButton);
            labelTextButton = softButton("查看", () -> editRaw(true));
            labelTextButton.setCompoundDrawablesRelative(null, null, new DisclosureArrow(ui, false), null);
            labelTextRow = valueRow(carePanel, "洗标原文", labelTextButton);
            reminderButton = softButton("", this::editReminders);
            reminderButton.setCompoundDrawablesRelative(null, null, new DisclosureArrow(ui, false), null);
            valueRow(carePanel, "护理提醒", reminderButton);
            renderRecords();
            refreshText();
            showColors();
            selectTab(activeTab);
            for (EditText field : new EditText[]{name, brand, fabric, care, wash, idle}) {
                field.addTextChangedListener(new android.text.TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                        captureFields();
                        updateSave();
                    }
                    @Override public void afterTextChanged(android.text.Editable editable) {}
                });
            }
            ui.body.setFocusableInTouchMode(true);
            ui.body.requestFocus();
            ui.finishGarmentHero(preview);
        }

        private EditText inlineField(LinearLayout parent, String label, String value, boolean multiline, boolean numeric) {
            EditText field = new EditText(ui);
            field.setTextSize(14);
            field.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            field.setTextColor(0xff111111);
            field.setHintTextColor(0xff888888);
            field.setHint("添加");
            field.setContentDescription(label);
            field.setSingleLine(!multiline);
            if (multiline) { field.setMinLines(1); field.setMaxLines(4); }
            if (numeric) field.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            field.setPadding(0, ui.dp(8), 0, ui.dp(8));
            field.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            field.setFontFeatureSettings("tnum");
            field.setMinimumHeight(ui.dp(40));
            field.setBackgroundColor(PAPER);
            field.setText(value);
            valueRow(parent, label, field);
            return field;
        }

        private LinearLayout valueRow(LinearLayout parent, String label, View value) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(52));
            row.setPadding(0, ui.dp(2), 0, ui.dp(2));
            TextView title = ui.text(label, 13, 0xff888888, false);
            row.addView(title, new LinearLayout.LayoutParams(ui.dp(104), -2));
            row.addView(value, new LinearLayout.LayoutParams(0, -2, 1));
            parent.addView(row);
            View line = new View(ui);
            line.setBackgroundColor(0xffefefef);
            row.setOnClickListener(v -> {
                if (value instanceof EditText field) {
                    field.requestFocus();
                    ((android.view.inputmethod.InputMethodManager) ui.getSystemService(android.content.Context.INPUT_METHOD_SERVICE))
                            .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                } else value.performClick();
            });
            return row;
        }

        private TextView softButton(String title, Runnable action) {
            TextView button = ui.ghost(title, action);
            button.setTextSize(13);
            button.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            button.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            button.setPadding(0, 0, 0, 0);
            ui.click(button, PAPER, 0, 0, action);
            return button;
        }

        private void selectTab(int index) {
            boolean changed = activeTab != index;
            if (changed) tabsPinned = true;
            activeTab = index;
            for (int i = 0; i < panels.length; i++) {
                panels[i].setVisibility(i == index ? View.VISIBLE : View.GONE);
                tabButtons[i].setSelected(i == index);
                tabButtons[i].setTextColor(i == index ? INK : MUTED);
            }
            tabStrip.select(index);
            if (tabsPinned) ui.alignGarmentTab(tabStrip, panels[index]);
            if (changed) Motion.fadeTab(panels[index]);
        }

        private void renderPhoto() {
            FrameLayout hero = new FrameLayout(ui);
            hero.setBackgroundColor(PAPER);
            preview = ui.image(null, 260);
            preview.setBackgroundColor(PAPER);
            preview.setPadding(ui.dp(34), ui.dp(12), ui.dp(34), ui.dp(12));
            hero.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            addPhoto = ui.ghost("+ 添加照片", this::imageSource);
            ui.click(addPhoto, Color.TRANSPARENT, 0, 0, this::imageSource);
            hero.addView(addPhoto, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
            photoSwitch = iconButton(NavIcon.COMPARE, false, "切换原图与封面", this::toggleImage);
            FrameLayout.LayoutParams swap = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.TOP | Gravity.END);
            swap.setMargins(0, ui.dp(4), ui.dp(4), 0);
            hero.addView(photoSwitch, swap);
            photoEdit = iconButton(NavIcon.ADJUST, false, "修图菜单", this::photoMenu);
            FrameLayout.LayoutParams tools = new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.BOTTOM | Gravity.END);
            tools.setMargins(0, 0, ui.dp(4), ui.dp(4));
            hero.addView(photoEdit, tools);
            ui.body.addView(hero, new LinearLayout.LayoutParams(-1, ui.dp(260)));
        }

        private void photoMenu() {
            Sheet sheet = new Sheet(ui, "修图", true);
            sheet.option("更换照片", () -> { sheet.dismiss(); imageSource(); });
            sheet.option("框选识别", () -> { sheet.dismiss(); manualCrop(); });
            sheet.option("保留笔刷", () -> { sheet.dismiss(); keepBrush(); });
            sheet.option("设置封面", () -> { sheet.dismiss(); coverOptions(); });
            sheet.option("重新识别", () -> { sheet.dismiss(); reprocessPhoto(); });
            sheet.show();
        }

        private void reprocessPhoto() {
            Bitmap source = imported == null ? db.image(g.original, 1) : imported;
            if (source == null) { ui.toast("找不到原图"); return; }
            useImage(source, false);
        }

        private void captureFields() {
            if (name == null) return;
            g.name = ui.value(name);
            g.brand = ui.value(brand);
            g.fabric = ui.value(fabric);
            g.care = ui.value(care);
            g.washEvery = number(wash, 10);
            g.idleDays = number(idle, 90);
        }

        private String fingerprint() {
            org.json.JSONArray state = new org.json.JSONArray();
            for (Object value : new Object[]{g.name, g.type, g.brand, g.fabric, g.seasons, g.symbols,
                    g.care, g.sourceText, g.labelText,
                    wash == null ? Integer.toString(g.washEvery) : ui.value(wash),
                    idle == null ? Integer.toString(g.idleDays) : ui.value(idle), originalSelected}) state.put(value);
            for (var color : g.colors) {
                state.put(new org.json.JSONArray(Arrays.asList(color.name, color.rgb, color.percent, color.primary)));
            }
            return state.toString();
        }

        private boolean changed() {
            return imported != null || cutout != null || target != null || !baseline.equals(fingerprint());
        }

        private void updateSave() {
            if (saveButton == null) return;
            saveButton.setText(g.id == 0 ? "完成添加" : "保存");
            saveButton.setTextColor(Color.WHITE);
            saveButton.setEnabled(true);
            saveButton.setVisibility(changed() ? View.VISIBLE : View.GONE);
        }

        private void leave(Runnable destination) {
            captureFields();
            if (changed()) ui.confirmDestructive("放弃未保存修改？", "未保存的内容将丢失。", "放弃修改", destination);
            else destination.run();
        }

        private void refreshStatus() {
            WardrobeStore.Garment saved = db.garment(g.id);
            if (saved == null) { exit.run(); return; }
            g.favorite = saved.favorite;
            g.stored = saved.stored;
            g.dirty = saved.dirty;
            g.archived = saved.archived;
            renderOperations();
            renderRecords();
        }

        private void renderOperations() {
            operations.removeAllViews();
            if (g.id == 0) return;
            LinearLayout row = ui.row();
            row.setPadding(ui.dp(12), ui.dp(8), ui.dp(16), ui.dp(4));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(favoriteButton(g, this::refreshStatus, false), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            if (!g.archived) row.addView(iconButton(NavIcon.ARCHIVE, g.stored, g.stored ? "取出衣物" : "收起衣物", () -> {
                if (g.stored) takeOut(g, this::refreshStatus);
                else { db.state(g.id, "stored", true); refreshStatus(); }
            }), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            row.addView(iconButton(NavIcon.MORE, false, "更多操作", this::moreActions),
                    new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            row.addView(new View(ui), new LinearLayout.LayoutParams(0, 1, 1));
            WearStatus status = wearStatus(g, db.usage(g.id));
            Runnable action = switch (status) {
                case ARCHIVED -> () -> { db.archiveGarment(g.id, false); refreshStatus(); };
                case STORED -> () -> takeOut(g, this::refreshStatus);
                case DIRTY -> () -> recordWash(g, this::refreshStatus);
                case CARE -> () -> completeCare(g, this::refreshStatus);
                case READY -> () -> { db.state(g.id, "dirty", true); refreshStatus(); };
            };
            TextView main = ui.pill(status.action, true, action);
            main.setTextSize(12);
            ui.click(main, 0xff111111, 12, 0, action);
            row.addView(main);
            operations.addView(row);
        }

        private void moreActions() {
            Sheet sheet = new Sheet(ui, "更多", true);
            sheet.option("找颜色搭配", () -> { sheet.dismiss(); captureFields(); ui.outfits.palette(g, this::render); });
            sheet.option("查看原文", () -> { sheet.dismiss(); editRaw(false); });
            if (!g.archived) {
                if (g.dirty) sheet.option("标为洁净", () -> { sheet.dismiss(); db.state(g.id, "dirty", false); refreshStatus(); });
                sheet.option("归档衣物", () -> { sheet.dismiss(); db.archiveGarment(g.id, true); refreshStatus(); ui.toast("已归档 / 历史已保留"); });
            }
            sheet.destructiveOption("删除衣物", () -> { sheet.dismiss(); leave(() -> confirmDelete(g, exit)); });
            sheet.show();
        }

        private void coverOptions() {
            ui.choose("设置封面", new String[]{"原图封面", "处理图封面"}, index -> {
                if (index == 1 && cutout == null && g.processed.isEmpty()) { ui.toast("暂无处理图"); return; }
                originalSelected = index == 0;
                previewOriginal = originalSelected;
                refreshText();
            });
        }

        private void editRaw(boolean label) {
            Sheet sheet = new Sheet(ui, label ? "洗标原文" : "商品原文", true);
            EditText field = ui.field(sheet.content, "原文", label ? g.labelText : g.sourceText, true, false);
            sheet.button("确认", true, () -> {
                if (label) g.labelText = ui.value(field); else g.sourceText = ui.value(field);
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private void renderRecords() {
            if (panels[2] == null) return;
            LinearLayout records = panels[2];
            records.removeAllViews();
            if (g.id == 0) {
                records.addView(ui.text("添加后可查看记录", 14, 0xff888888, false));
                return;
            }
            WardrobeStore.Usage usage = db.usage(g.id);
            LinearLayout stats = ui.row();
            stats.setBaselineAligned(false);
            stats.addView(statBlock("洗涤", usage.washes(), usage.washed()), new LinearLayout.LayoutParams(0, -2, 1));
            stats.addView(statBlock("穿着", usage.worn(), usage.lastWorn()), new LinearLayout.LayoutParams(0, -2, 1));
            records.addView(stats);
            ui.gap(records, 16);
            recordRow(records, "洗涤历史", this::washRecords);
            recordRow(records, "护理记录", this::careRecords);
            ui.section(records, "关联穿搭");
            boolean any = false;
            for (var outfit : db.outfits()) {
                if (outfit.layers.stream().noneMatch(layer -> layer.garment == g.id)) continue;
                any = true;
                LinearLayout item = ui.vertical();
                item.setPadding(0, ui.dp(14), 0, ui.dp(14));
                item.addView(ui.text(outfit.name, 15, INK, true));
                item.addView(ui.text((outfit.scene.isEmpty() ? "" : outfit.scene + " / ") + outfit.layers.size() + " 件单品", 12, 0xff888888, false));
                records.addView(item);
                hairline(records);
            }
            if (!any) records.addView(ui.text("暂无关联穿搭", 13, 0xff888888, false));
        }

        private void recordRow(LinearLayout parent, String label, Runnable action) {
            TextView row = ui.ghost(label, action);
            row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            row.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
            row.setCompoundDrawablesRelative(null, null, new DisclosureArrow(ui, false), null);
            row.setPadding(0, 0, 0, 0);
            parent.addView(row, new LinearLayout.LayoutParams(-1, ui.dp(52)));
            hairline(parent);
        }

        private void hairline(LinearLayout parent) {
            View line = new View(ui);
            line.setBackgroundColor(0xffefefef);
            parent.addView(line, new LinearLayout.LayoutParams(-1, 1));
        }

        private LinearLayout statBlock(String title, int count, String date) {
            LinearLayout block = ui.vertical();
            block.setPadding(0, ui.dp(12), ui.dp(8), ui.dp(12));
            block.addView(ui.text(Integer.toString(count), 40, INK, true));
            String shortDate = date == null || date.isEmpty() ? "暂无" : LocalDate.parse(date)
                    .format(java.time.format.DateTimeFormatter.ofPattern("MM/dd"));
            block.addView(ui.text(title + "  " + shortDate, 12, 0xff888888, false));
            return block;
        }

        private void washRecords() {
            Sheet sheet = new Sheet(ui, "洗涤历史", true);
            if (!g.archived) sheet.headerAction("+ 补记", () -> {
                sheet.dismiss();
                backdateWash(g, () -> { refreshStatus(); washRecords(); });
            });
            List<String> dates = db.washes(g.id);
            if (dates.isEmpty()) sheet.description("暂无记录");
            for (String date : dates) { sheet.description(date); hairline(sheet.content); }
            sheet.show();
        }

        private void careRecords() {
            Sheet sheet = new Sheet(ui, "护理记录", true);
            if (!g.archived && !reminder(g, db.usage(g.id)).isEmpty()) sheet.headerAction("完成护理", () -> {
                sheet.dismiss();
                completeCare(g, this::refreshStatus);
            });
            List<WardrobeStore.CareEvent> events = db.checks(g.id);
            if (events.isEmpty()) sheet.description("暂无记录");
            for (var event : events) { sheet.description(event.day() + "  " + event.note()); hairline(sheet.content); }
            sheet.show();
        }

        private EditText detachedField(String value) {
            EditText field = new EditText(ui);
            field.setFontFeatureSettings("tnum");
            field.setText(value);
            return field;
        }

        private TextView outlinedButton(String title, Runnable action) {
            TextView button = ui.ghost(title, action);
            button.setMinHeight(0);
            button.setMinimumHeight(0);
            button.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            button.setTextSize(13);
            android.graphics.drawable.GradientDrawable border = ui.shape(PAPER, 0, 0);
            border.setStroke(1, INK);
            button.setBackground(border);
            Motion.press(button);
            return button;
        }

        private String careMeaning(int group) {
            return Arrays.stream(g.symbols.split("\\R")).filter(value -> Arrays.asList(SYMBOLS[group]).contains(value))
                    .findFirst().orElse("");
        }

        private void showMeaning(int group) {
            selectedCare = selectedCare == group ? -1 : group;
            for (int i = 0; i < careMarks.length; i++) careMarks[i].setBackgroundColor(i == selectedCare ? INK : Color.TRANSPARENT);
            if (selectedCare < 0) Motion.expand(meaningPanel, false);
            else { fillMeaning(); Motion.expand(meaningPanel, true); }
        }

        private void fillMeaning() {
            meaningPanel.removeAllViews();
            hairline(meaningPanel);
            LinearLayout line = ui.row();
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.setMinimumHeight(ui.dp(48));
            String meaning = careMeaning(selectedCare);
            TextView description = ui.text(SYMBOLS[selectedCare][0] + "：" + (meaning.isEmpty() ? "未标注" : cleanText(meaning)), 13, INK, false);
            line.addView(description, new LinearLayout.LayoutParams(0, -2, 1));
            TextView modify = ui.ghost("修改", () -> editCareGroup(selectedCare));
            modify.setCompoundDrawablesRelative(null, null, new DisclosureArrow(ui, false), null);
            line.addView(modify);
            meaningPanel.addView(line);
            hairline(meaningPanel);
        }

        private void editCareGroup(int group) {
            String[] labels = SYMBOLS[group].clone();
            labels[0] = "未标注";
            ui.choose(SYMBOLS[group][0], labels, index -> {
                List<String> values = new ArrayList<>();
                for (int i = 0; i < SYMBOLS.length; i++) {
                    String value = i == group ? (index == 0 ? "" : SYMBOLS[group][index]) : careMeaning(i);
                    if (!value.isEmpty()) values.add(value);
                }
                g.symbols = String.join("\n", values);
                refreshText();
            });
        }

        private void editNote() {
            Sheet sheet = new Sheet(ui, "备注", true);
            EditText field = ui.field(sheet.content, "备注", g.care, true, false);
            sheet.button("确认", true, () -> {
                care.setText(ui.value(field));
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private void editReminders() {
            Sheet sheet = new Sheet(ui, "护理提醒", true);
            EditText washField = ui.field(sheet.content, "洗涤间隔", Integer.toString(g.washEvery), false, true);
            EditText idleField = ui.field(sheet.content, "闲置间隔", Integer.toString(g.idleDays), false, true);
            washField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            idleField.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            sheet.description("默认 10 次 / 90 天，填 0 关闭");
            sheet.button("确认", true, () -> {
                if (ui.value(washField).isEmpty() || ui.value(idleField).isEmpty()) { ui.toast("请输入次数与天数"); return; }
                wash.setText(ui.value(washField));
                idle.setText(ui.value(idleField));
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private void refreshText() {
            if (kind == null) return;
            kind.setText(g.type.isEmpty() ? "选择类别" : g.type);
            season.setText(g.seasons == 0 ? "季节未标注" : seasons(g.seasons));
            noteButton.setText(g.care.isEmpty() ? "添加" : cleanText(g.care));
            noteButton.setMaxLines(1);
            noteButton.setEllipsize(android.text.TextUtils.TruncateAt.END);
            labelTextRow.setVisibility(g.labelText.isEmpty() ? View.GONE : View.VISIBLE);
            // The separator belongs to the same conditional row.
            View nextLine = panels[1].getChildAt(panels[1].indexOfChild(labelTextRow) + 1);
            nextLine.setVisibility(g.labelText.isEmpty() ? View.GONE : View.VISIBLE);
            reminderButton.setText(g.washEvery == 0 && g.idleDays == 0 ? "已关闭"
                    : "每 " + g.washEvery + " 次 / " + g.idleDays + " 天");
            for (int i = 0; i < careIcons.length; i++) {
                careIcons[i].meaning(careMeaning(i));
                careMarks[i].setBackgroundColor(i == selectedCare ? INK : Color.TRANSPARENT);
            }
            if (selectedCare >= 0) fillMeaning();
            Bitmap current = displayed();
            Motion.crossfade(preview, current);
            preview.setVisibility(current == null ? View.GONE : View.VISIBLE);
            photoSwitch.setVisibility(current == null || cutout == null && g.processed.isEmpty() ? View.GONE : View.VISIBLE);
            photoSwitch.setContentDescription(previewOriginal ? "查看处理图" : "查看原图");
            photoEdit.setVisibility(current == null ? View.GONE : View.VISIBLE);
            addPhoto.setVisibility(current == null ? View.VISIBLE : View.GONE);
            updateSave();
        }

        private void imageSource() {
            String[] options = {"拍照", "相册", "导入网购截图"};
            ui.choose("添加照片", options, i -> ui.requestImage(
                    i == 2 ? "shop" : "photo", g.id, i == 0, b -> useImage(b, i == 2)));
        }

        void useImage(Bitmap bitmap, boolean shop) {
            imported = bitmap;
            target = bitmap;
            cutout = null;
            cutoutOrigin = null;
            originalSelected = false;
            previewOriginal = false;
            refreshText();
            if (shop) {
                ui.task("读取文字中…", () -> readText(bitmap), text -> {
                    g.sourceText = text;
                    String b = ImageAnalysis.brand(text), f = ImageAnalysis.material(text);
                    if (!b.isEmpty()) brand.setText(b);
                    if (!f.isEmpty()) fabric.setText(f);
                    String type = ImageAnalysis.categoryFromText(text);
                    if (!type.isEmpty()) g.type = type;
                    if (text.isEmpty()) ui.toast("未识别到文字");
                    refreshText();
                    ui.choose("商品截图", new String[]{"框选商品", "识别整张截图", "只提取文字"}, i -> {
                                if (i == 0) cropGarment(bitmap);
                                else if (i == 1) identify(bitmap, null);
                                else { originalSelected = true; g.colors.clear(); showColors(); refreshText(); }
                            });
                });
            } else identify(bitmap, null);
        }

        private String readText(Bitmap bitmap) {
            try { return ui.textReader.read(bitmap); }
            catch (Exception | LinkageError e) { Log.w("SmartWardrobe", "OCR", e); return ""; }
        }

        private void identify(Bitmap image, Point offset) {
            ui.task("识别中…", () -> {
                ImageAnalysis.Detection found = detectGarment(image);
                return new AnalysisResult(found, ui.colorReader.read(found == null ? image : found.image(), found != null, offset != null));
            }, result -> {
                if (result.detection != null) {
                    cutout = result.detection.image();
                    cutoutOrigin = new Point(result.detection.left() + (offset == null ? 0 : offset.x),
                            result.detection.top() + (offset == null ? 0 : offset.y));
                    if (g.type.isEmpty()) g.type = result.detection.type();
                } else {
                    cutout = image;
                    cutoutOrigin = null;
                }
                    originalSelected = false;
                colorCheck = result.detection == null;
                g.colors = result.colors;
                showColors();
                refreshText();
            });
        }

        private void manualCrop() {
            Bitmap source = imported == null ? db.image(g.original, 1) : imported;
            if (source == null) { ui.toast("请先添加照片"); return; }
            cropGarment(source);
        }

        private void cropGarment(Bitmap source) {
            ui.cropSelection(source, selected -> {
                target = selected.image();
                identify(target, new Point(selected.left(), selected.top()));
            });
        }

        private void keepBrush() {
            Bitmap source = imported == null ? db.image(g.original, 1) : imported;
            if (source == null) { ui.toast("找不到原图"); return; }
            if (cutoutOrigin != null) {
                Bitmap base = cutout == null ? db.image(g.processed, 1) : cutout;
                if (base != null) {
                    brushSheet(source, new KeepBrushView.Cutout(base, cutoutOrigin));
                    return;
                }
            }
            if (imported == null && cutout == null && !g.processed.isEmpty()) {
                ui.prompt("重新识别照片", "请先重新识别原图，再用笔刷补全。",
                        "重新识别", () -> ui.task("识别中…", () -> detectGarment(source), result ->
                                brushSheet(source, result == null ? null : new KeepBrushView.Cutout(result.image(),
                                        new Point(result.left(), result.top())))));
            } else {
                brushSheet(source, null);
            }
        }

        private void brushSheet(Bitmap source, KeepBrushView.Cutout base) {
            Sheet sheet = new Sheet(ui, "保留笔刷", true);
            sheet.description(base == null ? "涂抹保留区域" : "涂抹补回袖口、衣摆等区域");
            KeepBrushView brush = new KeepBrushView(ui, source, base);
            sheet.content.addView(brush, new LinearLayout.LayoutParams(-1, ui.dp(320)));
            LinearLayout sizeRow = ui.row();
            sizeRow.setGravity(Gravity.CENTER_VERTICAL);
            sizeRow.addView(ui.text("笔刷大小", 14, INK, false));
            SeekBar size = new SeekBar(ui);
            size.setContentDescription("笔刷大小");
            size.setMax(40);
            size.setProgress(8);
            brush.setBrushSize(ui.dp(12));
            size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    brush.setBrushSize(ui.dp(4 + progress));
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            sizeRow.addView(size, new LinearLayout.LayoutParams(0, -2, 1));
            sheet.content.addView(sizeRow);
            LinearLayout actions = ui.row();
            actions.addView(ui.ghost("撤销", brush::undo));
            actions.addView(ui.ghost("切换预览", brush::togglePreview));
            sheet.content.addView(actions);
            sheet.button("完成", true, () -> {
                KeepBrushView.Cutout result = brush.result();
                if (result == null) { ui.toast("请涂抹保留区域"); return; }
                cutout = result.image();
                cutoutOrigin = result.origin();
                    originalSelected = false;
                previewOriginal = false;
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private Bitmap displayed() {
            if (previewOriginal) return imported == null ? db.image(g.original, 2) : imported;
            if (cutout != null) return cutout;
            return target == null ? db.image(g.processed.isEmpty() ? g.photo : g.processed, 2) : target;
        }

        private void toggleImage() {
            if (imported == null && g.original.isEmpty()) { ui.toast("请先添加照片"); return; }
            previewOriginal = !previewOriginal;
            refreshText();
        }

        private void setSeasons() {
            DisclosureArrow arrow = seasonArrow;
            arrow.expanded(true);
            Sheet picker = chooseSeasons(g.seasons, bits -> {
                g.seasons = bits;
                refreshText();
            });
            picker.setOnDismissListener(dialog -> arrow.expanded(false));
        }

        private void addToggle(LinearLayout box, String label, boolean initial, Consumer<Boolean> changed) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(50));
            TextView text = ui.text(label, 16, INK, false);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            FrameLayout mark = new FrameLayout(ui);
            Runnable redraw = () -> {
                mark.removeAllViews();
                mark.addView(new NavIcon(ui, row.isSelected() ? NavIcon.TICK : NavIcon.PLUS, false, INK),
                        new FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER));
            };
            row.addView(mark, new LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)));
            row.setSelected(initial);
            redraw.run();
            ui.click(row, PAPER, 0, 0, () -> {
                boolean selected = !row.isSelected();
                row.setSelected(selected);
                redraw.run();
                changed.accept(selected);
            });
            box.addView(row);
            ui.rule(box);
        }

        private void showColors() {
            if (colorBox == null) return;
            colorBox.removeAllViews();
            FlowLayout flow = new FlowLayout(ui);
            for (WardrobeStore.Swatch color : g.colors) {
                LinearLayout chip = new LinearLayout(ui) {
                    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
                        Motion.pressState(this, event);
                        return super.dispatchTouchEvent(event);
                    }
                };
                chip.setOrientation(LinearLayout.HORIZONTAL);
                chip.setGravity(Gravity.CENTER_VERTICAL);
                android.graphics.drawable.GradientDrawable border = ui.shape(PAPER, 17, 0);
                border.setStroke(1, color.primary ? INK : 0xffdcdcdc);
                chip.setBackground(border);
                LinearLayout edit = ui.row();
                edit.setGravity(Gravity.CENTER_VERTICAL);
                edit.setPadding(ui.dp(10), 0, 0, 0);
                edit.addView(ui.colorDot(color.rgb, 12));
                ui.space(edit, 6);
                TextView label = ui.text(color.name + (g.colors.size() > 1 ? " " + color.percent + "%" : ""), 12, INK, false);
                label.setMaxWidth(ui.dp(160));
                label.setSingleLine();
                label.setEllipsize(android.text.TextUtils.TruncateAt.END);
                edit.addView(label);
                edit.setContentDescription("编辑颜色 " + color.name);
                ui.click(edit, Color.TRANSPARENT, 17, 0, () -> colorDialog(color));
                edit.setOnTouchListener(null);
                chip.addView(edit, new LinearLayout.LayoutParams(-2, ui.dp(34)));
                View remove = iconButton(NavIcon.CLOSE, false, "删除颜色 " + color.name, () -> removeColor(color));
                remove.setOnTouchListener(null);
                chip.addView(remove, new LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)));
                flow.addView(chip);
            }
            colorBox.addView(flow, new LinearLayout.LayoutParams(-1, -2));
            if (g.colors.isEmpty()) colorBox.addView(ui.text("未添加", 13, 0xff888888, false));
            updateSave();
        }

        private void removeColor(WardrobeStore.Swatch color) {
            ui.body.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            int originalPosition = g.colors.indexOf(color);
            List<WardrobeStore.Swatch> before = copyColors(g.colors);
            List<WardrobeStore.Swatch> after;
            g.colors.remove(color);
            if (color.primary && !g.colors.isEmpty()) g.colors.get(0).primary = true;
            after = copyColors(g.colors);
            showColors();
            ui.undoNotice("已移除颜色", () -> {
                // Preserve later colour edits while undoing this removal.
                if (sameColors(g.colors, after)) g.colors = copyColors(before);
                else if (g.colors.stream().noneMatch(entry -> entry.name.equals(color.name) && entry.rgb == color.rgb)) {
                    WardrobeStore.Swatch restored = new WardrobeStore.Swatch(color.name, color.rgb, color.percent, g.colors.isEmpty());
                    int position = Math.min(originalPosition, g.colors.size());
                    g.colors.add(Math.max(0, position), restored);
                }
                showColors();
            });
        }

        private List<WardrobeStore.Swatch> copyColors(List<WardrobeStore.Swatch> colors) {
            List<WardrobeStore.Swatch> result = new ArrayList<>();
            for (var color : colors) result.add(new WardrobeStore.Swatch(color.name, color.rgb, color.percent, color.primary));
            return result;
        }

        private boolean sameColors(List<WardrobeStore.Swatch> a, List<WardrobeStore.Swatch> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) {
                var x = a.get(i); var y = b.get(i);
                if (!x.name.equals(y.name) || x.rgb != y.rgb || x.percent != y.percent || x.primary != y.primary) return false;
            }
            return true;
        }

        private void colorDialog(WardrobeStore.Swatch swatch) {
            Sheet sheet = new Sheet(ui, "编辑颜色", true);
            LinearLayout form = sheet.content;
            LinearLayout adjustments = ui.vertical();
            ColorControls controls = new ColorControls(ui, adjustments, swatch == null ? 0xff808080 : swatch.rgb);
            if (displayed() != null || !g.original.isEmpty()) {
                form.addView(ui.pill("从照片取色", false, () -> pickPhotoColor(controls)));
                ui.gap(form, 8);
            }
            form.addView(adjustments);
            String customName = swatch == null || swatch.name.equals(ImageAnalysis.colorName(swatch.rgb)) ? "" : swatch.name;
            EditText label = ui.field(form, "颜色名称", customName, false, false);
            label.setHint("自动命名");
            EditText percent = ui.field(form, "占比", swatch == null ? "0" : "" + swatch.percent, false, true);
            boolean[] primary = {g.colors.isEmpty() || swatch != null && swatch.primary};
            addToggle(form, "设为主色", primary[0], selected -> primary[0] = selected);
            TextView error = ui.text("", 13, INK, true);
            form.addView(error);
            if (swatch != null) sheet.button("移除", false, () -> {
                sheet.dismiss();
                removeColor(swatch);
            });
            sheet.button("保存", true, () -> {
                int rgb = controls.color(), portion;
                try {
                    portion = Integer.parseInt(ui.value(percent));
                    if (portion < 0 || portion > 100) throw new IllegalArgumentException();
                } catch (IllegalArgumentException e) {
                    error.setText("请输入 0–100 的占比");
                    return;
                }
                WardrobeStore.Swatch entry = swatch == null ? new WardrobeStore.Swatch("", rgb, portion, false) : swatch;
                entry.rgb = rgb;
                entry.name = ui.value(label).isEmpty() ? ImageAnalysis.colorName(rgb) : ui.value(label);
                entry.percent = portion;
                if (swatch == null) g.colors.add(entry);
                if (primary[0]) for (WardrobeStore.Swatch candidate : g.colors) candidate.primary = candidate == entry;
                else if (entry.primary && g.colors.size() > 1) {
                    entry.primary = false;
                    for (WardrobeStore.Swatch candidate : g.colors) if (candidate != entry) { candidate.primary = true; break; }
                }
                if (g.colors.stream().noneMatch(s -> s.primary)) g.colors.get(0).primary = true;
                sheet.dismiss();
                showColors();
            });
            sheet.show();
        }

        private void pickPhotoColor(ColorControls controls) {
            Bitmap original = imported != null ? imported : db.image(g.original, 1);
            Bitmap processed = cutout != null ? cutout : target != null ? target : db.image(g.processed, 1);
            Bitmap initial = processed == null ? original : processed;
            if (initial == null) { ui.toast("请先添加照片"); return; }

            Sheet sheet = new Sheet(ui, "从照片取色", true);
            LinearLayout sample = ui.row();
            sample.setGravity(Gravity.CENTER_VERTICAL);
            View dot = ui.colorDot(controls.color(), 24);
            TextView label = ui.text("点按或拖动照片", 14, INK, true);
            sample.addView(dot);
            ui.space(sample, 8);
            sample.addView(label);
            Integer[] picked = {null};
            PhotoColorPicker photo = new PhotoColorPicker(ui, color -> {
                picked[0] = color;
                dot.setBackground(ui.shape(color, 12, STROKE));
                label.setText(ImageAnalysis.colorName(color));
            });
            if (original != null && processed != null && original != processed) {
                TextView source = ui.ghost("看原图", () -> {});
                source.setOnClickListener(v -> {
                    boolean originalNow = !v.isSelected();
                    v.setSelected(originalNow);
                    source.setText(originalNow ? "看处理图" : "看原图");
                    photo.showPhoto(originalNow ? original : processed);
                    picked[0] = null;
                    dot.setBackground(ui.shape(controls.color(), 12, STROKE));
                    label.setText("点按或拖动照片");
                });
                sheet.content.addView(source);
            }
            sheet.content.addView(photo, new LinearLayout.LayoutParams(-1, ui.dp(240)));
            ui.gap(sheet.content, 8);
            sheet.content.addView(sample);
            photo.showPhoto(initial);
            sheet.button("使用此色", true, () -> {
                if (picked[0] == null) { ui.toast("请先取色"); return; }
                controls.setColor(picked[0]);
                sheet.dismiss();
            });
            sheet.show();
        }

        void labelSource() {
            ui.scanCare(g.id, false, (image, hits) -> scanLabel(image, "", hits));
        }

        private void scanLabel(Bitmap image, String priorText) {
            scanLabel(image, priorText, null);
        }

        private void scanLabel(Bitmap image, String priorText, List<ImageAnalysis.SymbolHit> liveHits) {
            ui.task("识别洗标中…", () -> {
                String text = priorText.isEmpty() ? readText(image) : priorText;
                if (liveHits != null) return new LabelResult(text, liveHits, false);
                List<ImageAnalysis.SymbolHit> symbols;
                try (YoloCareSymbols detector = new YoloCareSymbols(ui)) { symbols = detector.detect(image); }
                catch (Exception | LinkageError e) {
                    Log.w("SmartWardrobe", "Care symbols", e);
                    return new LabelResult(text, Collections.emptyList(), true);
                }
                return new LabelResult(text, symbols, false);
            }, result -> {
                String composition = ImageAnalysis.material(result.text);
                String instructions = ImageAnalysis.careText(result.text);
                symbolPicker(image, result, () -> {
                    if (!result.text.isEmpty()) g.labelText = result.text;
                    if (!composition.isEmpty()) fabric.setText(composition);
                    if (!instructions.isEmpty()) care.setText(instructions);
                });
            });
        }

        private void symbolPicker() { symbolPicker(null, null, () -> {}); }

        private void symbolPicker(Bitmap image, LabelResult scan, Runnable adoptText) {
            Sheet sheet = new Sheet(ui, scan == null ? "洗护符号" : "核对洗标", true);
            LinearLayout panel = sheet.content;
            Runnable[] openGroup = new Runnable[SYMBOLS.length];
            List<ImageAnalysis.SymbolHit> candidates = scan == null ? Collections.emptyList() : scan.symbols;
            if (scan != null) {
                panel.addView(new CarePreview(ui, image, candidates, index -> {
                    if (index >= 0 && index < openGroup.length && openGroup[index] != null) openGroup[index].run();
                }), new LinearLayout.LayoutParams(-1, ui.dp(205)));
                if (scan.failed) sheet.description("符号识别失败");
                else if (candidates.isEmpty()) sheet.description("未检出符号");
                sheet.button("框选重试", false, () -> {
                    sheet.dismiss();
                    ui.crop(image, crop -> scanLabel(crop, scan.text));
                });
            }
            String[] existing = g.symbols.split("\\R");
            String[] values = new String[SYMBOLS.length];
            for (int i = 0; i < SYMBOLS.length; i++) {
                String[] group = SYMBOLS[i];
                int index = i;
                String[] choices = group.clone();
                choices[0] = "未确认";
                values[i] = choices[0];
                for (int j = 1; j < group.length; j++) if (Arrays.asList(existing).contains(group[j])) values[i] = group[j];
                List<String> suggestions = new ArrayList<>();
                for (ImageAnalysis.SymbolHit hit : candidates) {
                    if (hit.group() == i && Arrays.asList(choices).contains(hit.meaning()) && !suggestions.contains(hit.meaning())) {
                        suggestions.add(hit.meaning());
                    }
                }
                LinearLayout line = ui.row();
                line.setMinimumHeight(ui.dp(52));
                line.setGravity(Gravity.CENTER_VERTICAL);
                line.addView(ui.text(group[0], 15, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
                String initial = values[i];
                if (suggestions.size() == 1 && values[i].equals(choices[0])) {
                    values[i] = suggestions.get(0);
                    initial = values[i] + " 候选";
                } else if (suggestions.size() > 1 || suggestions.size() == 1 && !suggestions.get(0).equals(values[i])) {
                    initial = values[i] + " 有候选";
                }
                TextView current = ui.text(initial, 14, MUTED, false);
                current.setCompoundDrawablesRelative(null, null, new DisclosureArrow(ui, false), null);
                current.setCompoundDrawablePadding(ui.dp(5));
                current.setMaxWidth(ui.dp(218));
                line.addView(current);
                List<String> ordered = new ArrayList<>(suggestions);
                for (String choice : choices) if (!ordered.contains(choice)) ordered.add(choice);
                String[] labels = ordered.stream().map(value -> value + (suggestions.contains(value) ? " 候选" : ""))
                        .toArray(String[]::new);
                openGroup[i] = () -> ui.choose(group[0], labels, option -> {
                    values[index] = ordered.get(option);
                    current.setText(cleanText(values[index]));
                });
                ui.click(line, PAPER, 0, 0, openGroup[i]);
                panel.addView(line);
                ui.rule(panel);
            }
            if (scan != null && !scan.text.isEmpty()) sheet.option("洗标原文", () -> showRaw("洗标原文", scan.text));
            sheet.button("确认使用", true, () -> {
                List<String> confirmed = new ArrayList<>();
                for (String value : values) if (!"未确认".equals(value)) confirmed.add(value);
                g.symbols = String.join("\n", confirmed);
                adoptText.run();
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private void showRaw(String title, String text) {
            Sheet sheet = new Sheet(ui, title, true);
            sheet.description(text.isEmpty() ? "暂无文字" : text);
            sheet.show();
        }

        private void save() {
            captureFields();
            if (g.name.isEmpty() || g.type.isEmpty()) {
                ui.toast("请填写名称并选择类别");
                if (g.name.isEmpty()) name.requestFocus();
                else selectType(null, value -> { g.type = value; refreshText(); });
                return;
            }
            if (!changed()) return;
            boolean isNew = g.id == 0;
            ui.task("保存中…", () -> {
                db.save(g, imported, cutout, cutoutOrigin, originalSelected);
                return g.id;
            }, id -> {
                imported = null;
                cutout = null;
                target = null;
                previewOriginal = originalSelected;
                render();
                baseline = fingerprint();
                updateSave();
                ui.toast(isNew ? "已添加衣物" : "已保存");
            });
        }

        private int number(EditText field, int fallback) {
            String value = ui.value(field);
            try { return value.isEmpty() ? fallback : Integer.parseInt(value); }
            catch (NumberFormatException e) { return fallback; }
        }
    }

    private record AnalysisResult(ImageAnalysis.Detection detection, List<WardrobeStore.Swatch> colors) {}
    private record LabelResult(String text, List<ImageAnalysis.SymbolHit> symbols, boolean failed) {}
}
