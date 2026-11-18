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
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import static com.smartwardrobe.local.MainActivity.*;

final class ClosetScreens {
    private final MainActivity ui;
    private final WardrobeStore db;
    private GarmentPicker.Filter filter = GarmentPicker.Filter.ALL;
    private int filterSeason = -1;
    private boolean favorites;
    private int currentSeason;
    private boolean seasonal;
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
        currentSeason = ui.getPreferences(0).getInt("season", (LocalDate.now().getMonthValue() % 12) / 3 + 3) % 4;
    }

    void home() {
        List<WardrobeStore.Garment> garments = db.garments();
        List<WardrobeStore.Garment> filtered = new ArrayList<>();
        for (WardrobeStore.Garment g : garments) {
            if (!filter.matches(g)) continue;
            if (filterSeason >= 0 && (g.seasons & (1 << filterSeason)) == 0) continue;
            if (favorites && !g.favorite) continue;
            filtered.add(g);
        }
        String count = garments.isEmpty() ? "" : String.format(Locale.ROOT, "%02d / %02d", filtered.size(), garments.size());
        ui.page("closet", "衣橱", count, null, "＋ 添加", this::newGarment);
        if (garments.isEmpty()) {
            LinearLayout.LayoutParams archiveSize = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
            archiveSize.gravity = Gravity.END;
            ui.body.addView(archiveButton(), archiveSize);
            LinearLayout panel = ui.card(ui.body);
            TextView intro = ui.text("衣橱暂无单品", 23, INK, true);
            panel.addView(intro);
            ui.gap(panel, 13);
            showAddOptions(panel, () -> {});
            return;
        }

        List<WardrobeStore.Garment> available = new ArrayList<>(garments);
        available.removeIf(g -> filterSeason >= 0 && (g.seasons & (1 << filterSeason)) == 0 || favorites && !g.favorite);
        TextView category = ui.ghost(filter.equals(GarmentPicker.Filter.ALL) ? "筛选" : "已筛选", () ->
                GarmentPicker.open(ui, "筛选衣橱", available, filter,
                        chosen -> { filter = chosen; home(); },
                        (chosen, items) -> { filter = chosen; detail(items.get(0).id, this::home); }, false));
        category.setTextSize(12);
        category.setPadding(0, 0, 0, 0);

        LinearLayout seasons = ui.row();
        seasons.setMinimumWidth(ui.dp(348));
        seasons.setGravity(Gravity.CENTER_VERTICAL);
        seasons.setBaselineAligned(false);
        seasons.addView(category, new LinearLayout.LayoutParams(ui.dp(48), -2));
        for (int i = 0; i < SEASONS.length; i++) {
            int index = i;
            TextView season = ui.ghost(SEASONS[i], () -> { filterSeason = filterSeason == index ? -1 : index; home(); });
            season.setPadding(0, 0, 0, 0);
            season.setSelected(filterSeason == i);
            if (filterSeason == i) season.setBackground(ui.shape(SAND, 2, STROKE));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
            params.setMarginStart(ui.dp(2));
            seasons.addView(season, params);
        }
        View liked = iconButton(NavIcon.FAVORITE, favorites, "仅收藏", () -> { favorites = !favorites; home(); });
        liked.setSelected(favorites);
        ui.space(seasons, 2);
        seasons.addView(liked, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        ui.space(seasons, 2);
        seasons.addView(archiveButton(), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        android.widget.HorizontalScrollView seasonStrip = new android.widget.HorizontalScrollView(ui);
        seasonStrip.setFillViewport(true);
        seasonStrip.setHorizontalScrollBarEnabled(false);
        seasonStrip.addView(seasons);
        LinearLayout.LayoutParams stripSize = new LinearLayout.LayoutParams(-1, -2);
        stripSize.setMarginStart(-ui.dp(14));
        stripSize.setMarginEnd(-ui.dp(14));
        ui.body.addView(seasonStrip, stripSize);
        ui.gap(ui.body, 10);
        ui.rule(ui.body);
        if (!filter.equals(GarmentPicker.Filter.ALL)) {
            String summary = String.join(" · ", java.util.stream.Stream.of(filter.query(), filter.type(), filter.color())
                    .filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.toList()));
            ui.body.addView(ui.ghost(summary + " ×", () -> { filter = GarmentPicker.Filter.ALL; home(); }));
        }
        ui.gap(ui.body, 20);
        if (filtered.isEmpty()) ui.empty(ui.body, "没有匹配的衣物", "");
        for (int i = 0; i < filtered.size(); i += 2) {
            LinearLayout row = ui.row();
            for (int j = 0; j < 2; j++) {
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
                if (j == 1) p.leftMargin = ui.dp(16);
                if (i + j >= filtered.size()) { row.addView(new View(ui), p); continue; }
                WardrobeStore.Garment g = filtered.get(i + j);
                LinearLayout card = ui.vertical();
                ui.click(card, CARD, 0, 0, () -> detail(g.id, this::home));
                FrameLayout photo = new FrameLayout(ui);
                int width = ui.getResources().getConfiguration().screenWidthDp;
                int height = Math.max(146, (Math.min(width, 620) - 56) * 2 / 3);
                ImageView thumbnail = ui.image(null, height);
                photo.addView(thumbnail, new FrameLayout.LayoutParams(-1, -1));
                View placeholder = new NavIcon(ui, 0, false);
                photo.addView(placeholder, new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
                ui.loadPhoto(thumbnail, g.photo, 2, bitmap -> {
                    thumbnail.setImageBitmap(bitmap);
                    placeholder.setVisibility(View.GONE);
                });
                View favorite = iconButton(NavIcon.FAVORITE, g.favorite, (g.favorite ? "取消收藏 " : "收藏 ") + g.name,
                        () -> { db.state(g.id, "favorite", !g.favorite); home(); });
                photo.addView(favorite, new FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.END | Gravity.TOP));
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
                    WardrobeStore.Swatch s = g.colors.stream().filter(c -> c.primary).findFirst().orElse(g.colors.get(0));
                    ui.space(meta, 5);
                    View dot = new View(ui);
                    dot.setBackground(ui.shape(s.rgb, 6, STROKE));
                    meta.addView(dot, new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8)));
                    ui.space(meta, 4);
                    TextView name = ui.text(s.name, 11, MUTED, false);
                    name.setSingleLine();
                    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    meta.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
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
        ui.choose("选择衣物类别", choices.toArray(new String[0]),
                i -> result.accept(i == 0 && first != null ? "" : choices.get(i)));
    }

    private void newGarment() {
        Sheet sheet = new Sheet(ui, "添加衣物", true);
        showAddOptions(sheet.content, sheet::dismiss);
        sheet.show();
    }

    private void showAddOptions(LinearLayout parent, Runnable dismiss) {
        parent.addView(ui.pill("拍照 / 选图", true, () -> {
            dismiss.run();
            ui.choose("选择衣物照片", new String[]{"拍照", "从相册选图"}, index -> pickNew(index == 0 ? "camera" : "photo"));
        }));
        parent.addView(ui.pill("批量导入照片", false, () -> {
            dismiss.run();
            ui.requestBatchImages();
        }));
        parent.addView(ui.pill("网购导入", false, () -> {
            dismiss.run();
            new TaobaoImport(ui, this::importTaobao, () -> pickNew("shop")).start();
        }));
        parent.addView(ui.ghost("手动建档", () -> {
            dismiss.run();
            editor(new WardrobeStore.Garment(), this::home);
        }));
    }

    void confirmBatchImport(List<Uri> images) {
        ui.prompt("批量导入 " + images.size() + " 张照片",
                "每张照片建立一件衣物，自动识别类别和颜色。未检测到衣物时保留原图，可入库后编辑。",
                "开始导入", () -> ui.task("正在处理 " + images.size() + " 张照片…",
                        () -> importBatch(images), message -> {
                            home();
                            Sheet summary = new Sheet(ui, "批量导入结果", true);
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
        if (originalCount > 0) message += "，其中 " + originalCount + " 张使用原图封面";
        if (!failed.isEmpty()) message += "。未导入的照片序号（按选择结果排列）：" + String.join("、", failed);
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
            Runnable back = id == 0 ? this::home : () -> detail(id, this::home);
            if (purpose.equals("label") || purpose.equals("label-detail")) {
                restoreCareScan(id, purpose.equals("label-detail")).accept(bitmap, null);
            } else if (purpose.startsWith("edit-")) {
                Editor photo = new Editor(g, back);
                photo.renderPhotoEditor();
                photo.useImage(bitmap, false);
            } else editor(g, back).useImage(bitmap, purpose.equals("shop"));
        };
    }

    java.util.function.BiConsumer<Bitmap, List<ImageAnalysis.SymbolHit>> restoreCareScan(long id, boolean direct) {
        return (image, hits) -> {
            WardrobeStore.Garment g = id == 0 ? new WardrobeStore.Garment() : db.garment(id);
            if (g == null) return;
            Runnable back = id == 0 ? this::home : () -> detail(id, this::home);
            Editor form = direct ? new Editor(g, back) : editor(g, back);
            form.saveLabelDirectly = direct;
            if (direct) back.run();
            form.scanLabel(image, "", hits);
        };
    }

    private View archiveButton() {
        return iconButton(NavIcon.ARCHIVE, true, "已归档衣物", this::archive);
    }

    private View iconButton(int icon, boolean active, String label, Runnable action) {
        FrameLayout button = new FrameLayout(ui);
        button.setContentDescription(label);
        button.addView(new NavIcon(ui, icon, active),
                new FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER));
        ui.click(button, PAPER, 8, 0, action);
        return button;
    }

    private void archive() {
        ui.page("archive", "已归档衣物", "", this::home, null, null);
        List<WardrobeStore.Garment> archived = db.garments(true);
        if (archived.isEmpty()) ui.empty(ui.body, "暂无已归档衣物", "");
        for (var garment : archived) {
            LinearLayout card = itemCard(ui.body, garment, garment.type, () -> detail(garment.id, this::archive));
            card.addView(ui.pill("恢复到衣橱", false, () -> {
                db.archiveGarment(garment.id, false);
                archive();
            }));
        }
    }

    void detail(long id, Runnable back) {
        detail(id, back, false);
    }

    private void detail(long id, Runnable back, boolean original) {
        WardrobeStore.Garment g = db.garment(id);
        if (g == null) { back.run(); return; }
        WardrobeStore.Usage usage = db.usage(id);
        Runnable refresh = () -> detail(id, back, original);
        WearStatus status = wearStatus(g, usage);
        Runnable next = switch (status) {
            case ARCHIVED -> () -> { db.archiveGarment(id, false); back.run(); };
            case STORED -> () -> takeOut(g, refresh);
            case DIRTY -> () -> recordWash(g, refresh);
            case CARE -> () -> completeCare(g, refresh);
            case READY -> () -> { db.state(id, "dirty", true); refresh.run(); };
        };
        ui.page("garment:" + id, "衣物详情", "", back, status.action, next);
        TextView badge = ui.text(status.label, 14, INK, true);
        badge.setBackground(ui.shape(SAND, 12, 0));
        badge.setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(4));
        ui.body.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        ui.gap(ui.body, 8);
        if (!g.photo.isEmpty()) {
            LinearLayout image = ui.card(ui.body);
            String alternate = g.photo.equals(g.original) ? g.processed : g.original;
            image.addView(ui.photo(original && !alternate.isEmpty() ? alternate : g.photo, 1, 350));
            if (!alternate.isEmpty()) tabs(ui.body, "封面", g.photo.equals(g.original) ? "看处理图" : "看原图",
                    original, () -> detail(id, back, false), () -> detail(id, back, true));
            ui.gap(ui.body, 14);
        }
        if (!g.brand.isEmpty()) ui.body.addView(ui.text(g.brand, 12, MUTED, false));
        ui.body.addView(ui.editorial(g.name, 25));
        ui.body.addView(ui.text(g.type + (g.seasons == 0 ? "" : " · " + seasons(g.seasons))
                + (g.fabric.isEmpty() ? "" : " · " + g.fabric), 12, MUTED, false));
        colorNames(ui.body, g.colors);
        ui.gap(ui.body, 12);
        LinearLayout actions = ui.row();
        actions.addView(ui.ghost("编辑", () -> editor(g, refresh)));
        if (!g.archived) {
            ui.space(actions, 12);
            actions.addView(ui.ghost("更多操作", () -> garmentActions(g, refresh)));
        }
        ui.space(actions, 12);
        actions.addView(ui.ghost(g.favorite ? "取消收藏" : "收藏", () -> {
            db.state(id, "favorite", !g.favorite);
            refresh.run();
        }));
        ui.body.addView(actions);

        ui.section(ui.body, "洗护与使用记录");
        LinearLayout records = ui.card(ui.body);
        records.addView(ui.text("洗涤 " + usage.washes() + " 次    穿着 " + usage.worn() + " 次", 17, INK, true));
        records.addView(ui.text("最近洗涤  " + dateText(usage.washed()) + "\n最近穿着  " + dateText(usage.lastWorn()), 13, MUTED, false));
        if (!g.symbols.isEmpty()) records.addView(ui.text(g.symbols, 14, INK, false));
        if (!g.care.isEmpty()) records.addView(ui.text(g.care, 14, INK, false));
        ui.gap(records, 8);
        ui.actions(records, new String[]{"查看洗涤历史", "护理记录"}, () -> washHistory(g), () -> careHistory(g));
        if (status == WearStatus.CARE) records.addView(ui.text(reminder(g, usage), 14, MUTED, false));

        ui.gap(ui.body, 12);
        if (!g.archived) ui.body.addView(ui.pill("归档衣物", false, () -> {
            db.archiveGarment(id, true);
            ui.toast("已归档，可在衣橱恢复");
            back.run();
        }));
        if (!g.archived && usage.worn() == 0) ui.gap(ui.body, 10);
        if (usage.worn() == 0) ui.body.addView(ui.destructiveButton("永久删除衣物", () -> ui.confirmDestructive("永久删除衣物",
                "将移除照片、洗护记录及已保存穿搭中的该单品。", "永久删除", () -> {
                    if (db.deleteGarment(g)) back.run();
                    else ui.toast("这件衣物有穿着记录，可以归档");
                })));
    }

    private void garmentActions(WardrobeStore.Garment g, Runnable refresh) {
        Sheet sheet = new Sheet(ui, "衣物操作", true);
        sheet.option("扫描洗标", () -> { sheet.dismiss(); new Editor(g, refresh).labelSource(true); });
        sheet.option("修图", () -> { sheet.dismiss(); new Editor(g, refresh).renderPhotoEditor(); });
        sheet.option("找颜色搭配", () -> { sheet.dismiss(); ui.outfits.palette(g, refresh); });
        sheet.option("记录洗涤", () -> { sheet.dismiss(); recordWash(g, refresh); });
        if (g.dirty) sheet.option("标为洁净", () -> {
            sheet.dismiss();
            db.state(g.id, "dirty", false);
            refresh.run();
        });
        sheet.option(g.stored ? "取出衣物" : "收起衣物", () -> {
            sheet.dismiss();
            if (g.stored) takeOut(g, refresh);
            else { db.state(g.id, "stored", true); refresh.run(); }
        });
        sheet.show();
    }

    private void takeOut(WardrobeStore.Garment g, Runnable refresh) {
        db.state(g.id, "stored", false);
        refresh.run();
        ui.bottomNotice("已取出，可检查是否有灰尘、异味或污渍");
    }

    void care() {
        if (seasonal) { season(); return; }
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
        if (garments.isEmpty()) { ui.empty(ui.body, "暂无衣物", ""); return; }
        meter(ui.body, "衣橱洁净度", garments.size() - pending, garments.size());
        ui.gap(ui.body, 24);
        var groups = new ArrayList<>(buckets.entrySet());
        groups.sort((a, b) -> {
            int allowed = Boolean.compare(canBatch(b.getKey()), canBatch(a.getKey()));
            return allowed == 0 ? Integer.compare(b.getValue().size(), a.getValue().size()) : allowed;
        });
        if (groups.isEmpty()) ui.empty(ui.body, "没有待洗衣物", "");
        else if (canBatch(groups.get(0).getKey()) && groups.get(0).getValue().size() > 1) {
            var first = groups.get(0);
            LinearLayout featured = ui.card(ui.body);
            featured.setBackground(ui.shape(SAND, 4, INK));
            featured.addView(ui.text("优先处理", 11, MUTED, false));
            featured.addView(ui.text(first.getKey(), 16, INK, true));
            ui.gap(featured, 8);
            featured.addView(ui.pill("整组标记已洗", true, () -> batchWash(first.getValue())));
            ui.gap(featured, 10);
            meter(featured, "本组待洗占比", first.getValue().size(), pending);
            ui.gap(ui.body, 12);
        }
        for (var group : groups) {
            LinearLayout header = ui.row();
            header.setGravity(Gravity.CENTER_VERTICAL);
            header.addView(ui.text(group.getKey(), 14, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(ui.text(group.getValue().size() + " 件", 12, MUTED, false));
            if (canBatch(group.getKey()) && group.getValue().size() > 1) {
                ui.space(header, 8);
                header.addView(ui.pill("整组已洗", false, () -> batchWash(group.getValue())));
            }
            ui.body.addView(header);
            ui.gap(ui.body, 5);
            ui.rule(ui.body);
            for (var g : group.getValue()) careRow(g, g.type, "记录洗涤",
                    () -> recordWash(g, this::care), this::care);
            ui.gap(ui.body, 16);
        }
        int reminders = 0;
        for (WardrobeStore.Garment g : garments) {
            WardrobeStore.Usage usage = db.usage(g.id);
            if (wearStatus(g, usage) != WearStatus.CARE) continue;
            String reminder = reminder(g, usage);
            if (reminders++ == 0) ui.section(ui.body, "护理提醒");
            reminderCard(ui.body, g, reminder, this::care);
        }
    }

    private static boolean canBatch(String symbol) {
        return !symbol.equals("未标注洗标") && !symbol.equals("不可水洗");
    }

    private void batchWash(List<WardrobeStore.Garment> garments) {
        washDate("整组", (date, clean) -> {
            android.database.sqlite.SQLiteDatabase database = db.getWritableDatabase();
            database.beginTransaction();
            try {
                for (var g : garments) db.wash(g.id, date.toString(), clean);
                database.setTransactionSuccessful();
            } finally { database.endTransaction(); }
            care();
        });
    }

    private void careHeader(long pending) {
        ui.page(seasonal ? "seasonal-care" : "wash-care", "洗护",
                pending == 0 ? "" : pending + " 件待洗", null, null, null);
        tabs(ui.body, "待洗分组" + (pending == 0 ? "" : "  " + pending), "换季检查", seasonal,
                () -> { seasonal = false; care(); }, () -> { seasonal = true; season(); });
        ui.gap(ui.body, 16);
    }

    private void tabs(LinearLayout parent, String first, String second, boolean right, Runnable onLeft, Runnable onRight) {
        LinearLayout bar = ui.row();
        for (int i = 0; i < 2; i++) {
            boolean active = right == (i == 1);
            LinearLayout segment = ui.vertical();
            TextView label = ui.ghost(i == 0 ? first : second, i == 0 ? onLeft : onRight);
            label.setTextColor(active ? INK : MUTED);
            label.setSelected(active);
            segment.addView(label);
            View line = new View(ui);
            line.setBackgroundColor(active ? INK : STROKE);
            segment.addView(line, new LinearLayout.LayoutParams(-1, ui.dp(active ? 2 : 1)));
            bar.addView(segment, new LinearLayout.LayoutParams(0, -2, 1));
        }
        parent.addView(bar);
    }

    private void meter(LinearLayout parent, String title, int count, int total) {
        LinearLayout heading = ui.row();
        heading.addView(ui.text(title, 12, INK, true), new LinearLayout.LayoutParams(0, -2, 1));
        int percent = total == 0 ? 0 : count * 100 / total;
        heading.addView(ui.text(count + " / " + total + " 件 · " + percent + "%", 12, MUTED, false));
        parent.addView(heading);
        ui.gap(parent, 6);
        android.widget.ProgressBar bar = new android.widget.ProgressBar(ui, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(percent);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(INK));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(STROKE));
        bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        parent.addView(bar, new LinearLayout.LayoutParams(-1, ui.dp(8)));
    }

    private void careRow(WardrobeStore.Garment g, String subtitle, String action, Runnable run, Runnable back) {
        LinearLayout line = ui.row();
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, ui.dp(8), 0, ui.dp(8));
        LinearLayout item = ui.row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(ui.photo(g.photo, 4, 63), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(63)));
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
        ui.rule(ui.body);
    }

    private void recordWash(WardrobeStore.Garment g, Runnable refresh) {
        washDate(g.name, (day, clean) -> {
            db.wash(g.id, day.toString(), clean);
            refresh.run();
            if (clean && g.stored) ui.bottomNotice("已记下洗涤，衣物已从收纳中取出");
        });
    }

    private void washDate(String label, java.util.function.BiConsumer<LocalDate, Boolean> record) {
        ui.choose(label + " · 记录洗涤", new String[]{"今天洗了，并标为洁净", "补记洗涤，保留当前状态"}, i -> {
            if (i == 0) record.accept(LocalDate.now(), true);
            else ui.date("补记洗涤日期", LocalDate.now(), true, date -> {
                record.accept(date, false);
                ui.toast("已补记，当前清洁状态保持不变");
            });
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
            lines.add("距上次检查已洗涤 " + stats.sinceCare() + " 次，建议检查" + checks + "。");
            if (!g.care.isEmpty() || !g.symbols.isEmpty()) lines.add("处理前请核对已保存的洗护要求。");
        }
        String from = stats.lastWorn() == null ? g.created : stats.lastWorn();
        if (stats.lastCare() != null && stats.lastCare().compareTo(from) > 0) from = stats.lastCare();
        long days = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.now());
        if (g.idleDays > 0 && days >= g.idleDays) lines.add("已 " + days + " 天未穿着或检查，可检查是否需要通风或除尘。");
        return String.join("\n", lines);
    }

    private void reminderCard(LinearLayout parent, WardrobeStore.Garment g, String note, Runnable refresh) {
        LinearLayout card = itemCard(parent, g, note, null);
        card.addView(ui.pill("完成护理检查", false, () -> completeCare(g, refresh)));
    }

    private void completeCare(WardrobeStore.Garment g, Runnable refresh) {
        Sheet sheet = new Sheet(ui, "护理检查", true);
        EditText input = ui.field(sheet.content, "检查结果", "", true, false);
        sheet.button("完成护理", true, () -> {
            String value = ui.value(input);
            db.checked(g.id, value.isEmpty() ? "完成护理检查" : value);
            sheet.dismiss();
            refresh.run();
            ui.toast("已完成护理，重新开始计数");
        });
        sheet.show();
    }

    void season() {
        List<WardrobeStore.Garment> garments = db.garments();
        seasonal = true;
        careHeader(garments.stream().filter(g -> g.dirty).count());
        LinearLayout seasons = ui.row();
        for (int i = 0; i < SEASONS.length; i++) {
            int index = i;
            TextView button = ui.pill((i == currentSeason ? "✓ " : "") + SEASONS[i] + "季", i == currentSeason, () -> {
                currentSeason = index;
                ui.getPreferences(0).edit().putInt("season", index).apply();
                season();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) p.leftMargin = ui.dp(7);
            seasons.addView(button, p);
        }
        ui.body.addView(seasons);
        ui.gap(ui.body, 20);
        Map<WearStatus, List<WardrobeStore.Garment>> groups = new java.util.EnumMap<>(WearStatus.class);
        List<WardrobeStore.Garment> untagged = new ArrayList<>();
        int total = 0;
        for (WardrobeStore.Garment g : garments) {
            if (g.seasons == 0) {
                untagged.add(g);
                continue;
            }
            if ((g.seasons & (1 << currentSeason)) == 0) continue;
            total++;
            groups.computeIfAbsent(wearStatus(g, db.usage(g.id)), status -> new ArrayList<>()).add(g);
        }
        int pending = groups.getOrDefault(WearStatus.DIRTY, Collections.emptyList()).size();
        String season = SEASONS[currentSeason] + "季";
        seasonSummary(season, total, groups.getOrDefault(WearStatus.READY, Collections.emptyList()).size(), pending,
                groups.getOrDefault(WearStatus.STORED, Collections.emptyList()).size());
        if (total == 0) ui.empty(ui.body, season + "暂无适用衣物", "");
        for (WearStatus status : new WearStatus[]{WearStatus.DIRTY, WearStatus.STORED, WearStatus.CARE, WearStatus.READY}) {
            List<WardrobeStore.Garment> group = groups.getOrDefault(status, Collections.emptyList());
            if (group.isEmpty()) continue;
            String title = switch (status) {
                case READY -> "可直接穿";
                case STORED -> "还在收纳中";
                default -> status.label;
            };
            ui.section(ui.body, title + "  " + group.size());
            for (WardrobeStore.Garment g : group) {
                switch (status) {
                    case DIRTY -> careRow(g, g.type, "记录洗涤", () -> recordWash(g, this::season), this::season);
                    case STORED -> careRow(g, g.type, "取出衣物", () -> takeOut(g, this::season), this::season);
                    case CARE -> careRow(g, g.type, "完成护理检查", () -> completeCare(g, this::season), this::season);
                    default -> careRow(g, g.type, null, null, this::season);
                }
            }
        }
        if (!untagged.isEmpty()) {
            ui.section(ui.body, "季节待确认 · " + untagged.size());
            for (var garment : untagged) {
                careRow(garment, garment.type, "设置季节",
                        () -> chooseSeasons(garment.seasons, bits -> {
                            db.setSeasons(garment.id, bits);
                            season();
                        }), this::season);
            }
        }
    }

    private void chooseSeasons(int initial, java.util.function.IntConsumer chosen) {
        Sheet sheet = new Sheet(ui, "适用季节", true);
        sheet.description("可多选。不选则记为未标注。");
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
    }

    private void seasonSummary(String season, int total, int ready, int pending, int stored) {
        LinearLayout status = ui.card(ui.body);
        status.setBackground(ui.shape(SAND, 4, 0xffcccccc));
        status.addView(ui.text(season + "换季检查 · " + total + " 件", 12, MUTED, false));
        int percent = total == 0 ? 0 : Math.round(100f * ready / total);
        status.addView(ui.text("可直接穿 " + ready + " / " + total + " 件 · " + percent + "%", 15, INK, true));
        if (total == 0) return;
        ui.gap(status, 10);
        int[] amounts = {ready, pending, stored, total - ready - pending - stored};
        int[] shades = {INK, 0xff777777, 0xffc8c8c8, STROKE};
        LinearLayout bar = ui.row();
        bar.setWeightSum(total);
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
        String[] names = {"可直接穿", "待洗", "还在收纳中"};
        for (int i = 0; i < names.length; i++) {
            TextView count = ui.text(names[i] + "\n" + amounts[i] + " 件", 12, INK, true);
            count.setBackground(ui.shape(PAPER, 4, STROKE));
            count.setPadding(ui.dp(8), ui.dp(7), ui.dp(8), ui.dp(7));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1);
            if (i != 0) p.leftMargin = ui.dp(7);
            counts.addView(count, p);
        }
        status.addView(counts);
        if (pending > 0) {
            ui.gap(status, 10);
            status.addView(ui.pill("去待洗分组", true, () -> { seasonal = false; care(); }));
        }
    }

    LinearLayout itemCard(LinearLayout parent, WardrobeStore.Garment g, String desc, Runnable click) {
        LinearLayout card = ui.card(parent);
        LinearLayout row = ui.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView thumb = ui.photo(g.photo, 4, 55);
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
        DIRTY("待洗", "记录洗涤"),
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
        private boolean originalSelected, colorCheck, photoOnly, saveLabelDirectly;
        private EditText name, brand, fabric, care, wash, idle;
        private TextView kind, season, symbolSummary, sourceTextButton, labelTextButton, addPhoto, photoMode;
        private ImageView preview;
        private TextView coverLabel;
        private LinearLayout colorBox, photoTools;

        Editor(WardrobeStore.Garment garment, Runnable back) {
            g = garment;
            exit = back;
            cutoutOrigin = g.cutoutOrigin;
            originalSelected = g.photo.equals(g.original);
        }

        void render() {
            ui.page(this, g.id == 0 ? "添加衣物" : "编辑衣物",
                    "", () -> ui.confirmDestructive("退出编辑", "未保存的修改将丢失。", "放弃修改", exit), "完成", this::save);
            renderPhoto();
            ui.gap(ui.body, 10);
            ui.rule(ui.body);
            ui.gap(ui.body, 10);
            LinearLayout details = ui.card(ui.body);
            details.setBackgroundColor(PAPER);
            details.setPadding(0, 0, 0, 0);
            name = ui.field(details, "名称 *", g.name, false, false);
            kind = ui.pill("", false, () -> selectType(null, value -> { g.type = value; refreshText(); }));
            details.addView(kind);
            ui.gap(details, 8);
            LinearLayout more = ui.vertical();
            boolean expanded = g.id != 0;
            TextView moreButton = ui.pill(expanded ? "收起更多细节" : "更多细节", false, null);
            details.addView(moreButton);
            ui.body.addView(more);
            more.setVisibility(expanded ? View.VISIBLE : View.GONE);
            moreButton.setOnClickListener(view -> {
                boolean show = more.getVisibility() == View.GONE;
                more.setVisibility(show ? View.VISIBLE : View.GONE);
                moreButton.setText(show ? "收起更多细节" : "更多细节");
            });
            brand = ui.field(more, "品牌", g.brand, false, false);
            fabric = ui.field(more, "材质 / 成分", g.fabric, false, false);
            season = ui.pill("", false, this::setSeasons);
            more.addView(season);
            sourceTextButton = ui.ghost("商品原文", () -> showRaw("商品原文", g.sourceText));
            more.addView(sourceTextButton);
            ui.section(more, "颜色");
            colorBox = ui.card(more);
            ui.section(more, "洗护");
            LinearLayout label = ui.card(more);
            ui.actions(label, new String[]{"扫描洗标", "编辑符号"}, this::labelSource, this::symbolPicker);
            symbolSummary = ui.text("", 14, INK, false);
            label.addView(symbolSummary);
            care = ui.field(label, "洗护说明", g.care, true, false);
            labelTextButton = ui.ghost("洗标原文", () -> showRaw("洗标原文", g.labelText));
            label.addView(labelTextButton);
            ui.section(more, "护理提醒");
            LinearLayout reminders = ui.card(more);
            wash = ui.field(reminders, "洗涤间隔（次）", Integer.toString(g.washEvery), false, true);
            idle = ui.field(reminders, "闲置间隔（天）", Integer.toString(g.idleDays), false, true);
            reminders.addView(ui.text("0 为关闭", 12, MUTED, false));
            wash.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            idle.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4)});
            refreshText();
            showColors();
            ui.body.setFocusableInTouchMode(true);
            ui.body.requestFocus();
        }

        void renderPhotoEditor() {
            photoOnly = true;
            ui.page(this, "修图", g.name, exit, "完成", this::savePhoto);
            renderPhoto();
            if (!g.original.isEmpty()) ui.body.addView(ui.ghost("用原图重新抠图", this::reprocessPhoto));
            refreshText();
        }

        private void reprocessPhoto() {
            Bitmap source = imported == null ? db.image(g.original, 1) : imported;
            if (source == null) { ui.toast("找不到原图"); return; }
            useImage(source, false);
        }

        private void renderPhoto() {
            ui.section(ui.body, g.id == 0 ? "衣物照片 *" : "衣物照片");
            LinearLayout photo = ui.vertical();
            ui.body.addView(photo);
            preview = ui.image(db.image(g.photo, 2), 290);
            LinearLayout.LayoutParams photoSize = new LinearLayout.LayoutParams(-1, ui.dp(290));
            photoSize.leftMargin = ui.dp(32);
            photoSize.rightMargin = ui.dp(32);
            photo.addView(preview, photoSize);
            coverLabel = ui.text("", 13, MUTED, false);
            coverLabel.setGravity(Gravity.CENTER);
            photo.addView(coverLabel);
            addPhoto = ui.pill("＋ 添加照片", false, this::imageSource);
            photo.addView(addPhoto);
            photoTools = ui.row();
            photoTools.setGravity(Gravity.CENTER);
            photo.addView(photoTools);
            photoTools.addView(ui.ghost("换照片", this::imageSource));
            photoTools.addView(ui.ghost("框选", this::manualCrop));
            photoTools.addView(ui.ghost("保留笔刷", this::keepBrush));
            photoMode = ui.ghost("", this::toggleImage);
            photoTools.addView(photoMode);
        }

        private void savePhoto() {
            Bitmap original = imported;
            Bitmap processed = cutout;
            Point origin = cutoutOrigin;
            boolean coverOriginal = originalSelected;
            ui.task("正在保存照片…", () -> {
                db.save(g, original, processed, origin, coverOriginal);
                return g.id;
            }, id -> exit.run());
        }

        private void refreshText() {
            if (!photoOnly) {
                kind.setText(g.type.isEmpty() ? "选择类别 *" : "类别  " + g.type + "  ›");
                season.setText(g.seasons == 0 ? "季节未标注  ›" : "季节 · " + seasons(g.seasons) + "  ›");
                symbolSummary.setText(g.symbols);
                symbolSummary.setVisibility(g.symbols.isEmpty() ? View.GONE : View.VISIBLE);
                sourceTextButton.setVisibility(g.sourceText.isEmpty() ? View.GONE : View.VISIBLE);
                labelTextButton.setVisibility(g.labelText.isEmpty() ? View.GONE : View.VISIBLE);
            }
            Bitmap current = displayed();
            preview.setImageBitmap(current);
            preview.setVisibility(current == null ? View.GONE : View.VISIBLE);
            photoTools.setVisibility(current == null ? View.GONE : View.VISIBLE);
            addPhoto.setVisibility(current == null ? View.VISIBLE : View.GONE);
            boolean hasAlternative = cutout != null && cutout != imported || imported == null && !g.processed.isEmpty();
            photoMode.setVisibility(hasAlternative ? View.VISIBLE : View.GONE);
            photoMode.setText(originalSelected ? "看处理图" : "看原图");
            coverLabel.setVisibility(hasAlternative ? View.VISIBLE : View.GONE);
            coverLabel.setText(originalSelected ? "封面使用原图" : "封面使用处理图");
        }

        private void imageSource() {
            String[] options = photoOnly ? new String[]{"拍照", "从相册选图"}
                    : new String[]{"拍照", "从相册选图", "导入网购截图"};
            ui.choose("选择衣物照片", options, i -> ui.requestImage(
                    photoOnly ? "edit-photo" : i == 2 ? "shop" : "photo", g.id, i == 0, b -> useImage(b, i == 2)));
        }

        void useImage(Bitmap bitmap, boolean shop) {
            imported = bitmap;
            target = bitmap;
            cutout = null;
            cutoutOrigin = null;
            originalSelected = false;
            if (shop) {
                ui.task("正在读取商品文字…", () -> readText(bitmap), text -> {
                    g.sourceText = text;
                    String b = ImageAnalysis.brand(text), f = ImageAnalysis.material(text);
                    if (!photoOnly && !b.isEmpty()) brand.setText(b);
                    if (!photoOnly && !f.isEmpty()) fabric.setText(f);
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
            ui.task("正在本地识别衣物和颜色…", () -> {
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
                ui.prompt("重新识别照片", "旧处理图没有记录裁剪位置。用原图重新识别，再涂抹要补回的部分。",
                        "重新识别", () -> ui.task("正在识别衣物…", () -> detectGarment(source), result ->
                                brushSheet(source, result == null ? null : new KeepBrushView.Cutout(result.image(),
                                        new Point(result.left(), result.top())))));
            } else {
                brushSheet(source, null);
            }
        }

        private void brushSheet(Bitmap source, KeepBrushView.Cutout base) {
            Sheet sheet = new Sheet(ui, "保留笔刷", true);
            sheet.description(base == null ? "涂抹要保留的衣物区域。" : "在原图上涂抹要补回的部分，如袖口或衣摆。");
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
            sheet.button(photoOnly ? "确认使用" : "完成", true, () -> {
                KeepBrushView.Cutout result = brush.result();
                if (result == null) { ui.toast("请先涂抹要保留的区域"); return; }
                cutout = result.image();
                cutoutOrigin = result.origin();
                    originalSelected = false;
                sheet.dismiss();
                if (photoOnly) savePhoto();
                else refreshText();
            });
            sheet.show();
        }

        private Bitmap displayed() {
            if (originalSelected) return imported == null ? db.image(g.original, 1) : imported;
            if (cutout != null) return cutout;
            return target == null ? db.image(g.processed.isEmpty() ? g.original : g.processed, 2) : target;
        }

        private void toggleImage() {
            Bitmap original = imported == null ? db.image(g.original, 1) : imported;
            if (original == null) { ui.toast("请先添加照片"); return; }
            if (cutout == null && imported != null && target == null) { ui.toast("还没有抠图结果"); return; }
            originalSelected = !originalSelected;
            refreshText();
        }

        private void setSeasons() {
            chooseSeasons(g.seasons, bits -> {
                g.seasons = bits;
                refreshText();
            });
        }

        private void addToggle(LinearLayout box, String label, boolean initial, Consumer<Boolean> changed) {
            LinearLayout row = ui.row();
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(50));
            TextView text = ui.text(label, 16, INK, false);
            row.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
            TextView mark = ui.text(initial ? "✓" : "＋", 17, INK, true);
            row.addView(mark);
            row.setSelected(initial);
            ui.click(row, PAPER, 0, 0, () -> {
                boolean selected = !row.isSelected();
                row.setSelected(selected);
                mark.setText(selected ? "✓" : "＋");
                changed.accept(selected);
            });
            box.addView(row);
            ui.rule(box);
        }

        private void showColors() {
            if (photoOnly) return;
            colorBox.removeAllViews();
            if (colorCheck && !g.colors.isEmpty()) {
                WardrobeStore.Swatch main = g.colors.stream().filter(color -> color.primary).findFirst().orElse(g.colors.get(0));
                colorBox.addView(ui.ghost("已自动取色，点此微调", () -> colorDialog(main)));
            }
            for (WardrobeStore.Swatch color : g.colors) {
                colorBox.addView(ui.colorChip(color.name + " · " + color.percent + "% · " + (color.primary ? "主色" : "辅色"),
                        color.rgb, () -> colorDialog(color)));
                ui.gap(colorBox, 5);
            }
            colorBox.addView(ui.pill("＋ 添加颜色", false, () -> colorDialog(null)));
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
            EditText percent = ui.field(form, "占比（0–100）", swatch == null ? "0" : "" + swatch.percent, false, true);
            boolean[] primary = {g.colors.isEmpty() || swatch != null && swatch.primary};
            addToggle(form, "设为主色", primary[0], selected -> primary[0] = selected);
            TextView error = ui.text("", 13, INK, true);
            form.addView(error);
            if (swatch != null) sheet.button("移除", false, () -> {
                g.colors.remove(swatch);
                if (swatch.primary && !g.colors.isEmpty()) g.colors.get(0).primary = true;
                sheet.dismiss();
                showColors();
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
                if (picked[0] == null) { ui.toast("请先在照片上取色"); return; }
                controls.setColor(picked[0]);
                sheet.dismiss();
            });
            sheet.show();
        }

        void labelSource() { labelSource(false); }

        void labelSource(boolean direct) {
            saveLabelDirectly = direct;
            ui.scanCare(g.id, direct, (image, hits) -> scanLabel(image, "", hits));
        }

        private void scanLabel(Bitmap image, String priorText) {
            scanLabel(image, priorText, null);
        }

        private void scanLabel(Bitmap image, String priorText, List<ImageAnalysis.SymbolHit> liveHits) {
            ui.task("正在读取洗标…", () -> {
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
                    if (saveLabelDirectly) {
                        db.saveCareLabel(g.id, g.symbols, result.text, composition, instructions);
                    } else {
                        if (!composition.isEmpty()) fabric.setText(composition);
                        if (!instructions.isEmpty()) care.setText(instructions);
                    }
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
                    initial = values[i] + " · 候选";
                } else if (suggestions.size() > 1 || suggestions.size() == 1 && !suggestions.get(0).equals(values[i])) {
                    initial = values[i] + " · 有候选";
                }
                TextView current = ui.text(initial + "  ›", 14, MUTED, false);
                current.setMaxWidth(ui.dp(218));
                line.addView(current);
                List<String> ordered = new ArrayList<>(suggestions);
                for (String choice : choices) if (!ordered.contains(choice)) ordered.add(choice);
                String[] labels = ordered.stream().map(value -> value + (suggestions.contains(value) ? " · 候选" : ""))
                        .toArray(String[]::new);
                openGroup[i] = () -> ui.choose(group[0], labels, option -> {
                    values[index] = ordered.get(option);
                    current.setText(values[index] + "  ›");
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
                if (saveLabelDirectly) {
                    exit.run();
                    ui.toast("已保存洗标");
                } else refreshText();
            });
            sheet.show();
        }

        private void showRaw(String title, String text) {
            Sheet sheet = new Sheet(ui, title, true);
            sheet.description(text.isEmpty() ? "暂无文字" : text);
            sheet.show();
        }

        private void save() {
            g.name = ui.value(name);
            if (g.name.isEmpty() || g.type.isEmpty()) { ui.toast("请填写名称并选择类别"); return; }
            if (g.id == 0 && displayed() == null) { ui.toast("请添加衣物照片"); return; }
            g.brand = ui.value(brand);
            g.fabric = ui.value(fabric);
            g.care = ui.value(care);
            g.washEvery = number(wash, 10);
            g.idleDays = number(idle, 90);
            Bitmap original = imported;
            Bitmap processed = cutout;
            Point origin = cutoutOrigin;
            boolean coverOriginal = originalSelected;
            boolean isNew = g.id == 0;
            ui.task("正在保存衣物…", () -> { db.save(g, original, processed, origin, coverOriginal); return g.id; }, id -> {
                if (isNew) detail(id, exit);
                else exit.run();
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
