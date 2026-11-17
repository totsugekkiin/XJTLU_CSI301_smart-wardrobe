package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Point;
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
    private String filterType = "", filterColor = "";
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
            if (!filterType.isEmpty() && !g.type.equals(filterType)) continue;
            if (filterSeason >= 0 && (g.seasons & (1 << filterSeason)) == 0) continue;
            if (favorites && !g.favorite) continue;
            if (!filterColor.isEmpty() && g.colors.stream().noneMatch(s -> s.name.equals(filterColor))) continue;
            filtered.add(g);
        }
        String count = garments.isEmpty() ? "" : String.format(Locale.ROOT, "%02d / %02d", filtered.size(), garments.size());
        ui.page("衣橱", count, null, "＋ 添加", this::newGarment);
        ui.body.addView(ui.ghost("已归档衣物", this::archive));
        if (garments.isEmpty()) {
            LinearLayout panel = ui.card(ui.body);
            TextView intro = ui.text("衣橱暂无单品", 23, INK, true);
            panel.addView(intro);
            ui.gap(panel, 13);
            ui.actions(panel, new String[]{"导入照片", "拍照"}, () -> pickNew("photo"), () -> pickNew("camera"));
            ui.actions(panel, new String[]{"网购截图", "手动建档"}, () -> pickNew("shop"), () -> editor(new WardrobeStore.Garment(), this::home));
            return;
        }

        LinearLayout filters = ui.row();
        filters.setGravity(Gravity.CENTER_VERTICAL);
        TextView category = ui.ghost(filterType.isEmpty() ? "☷ 类别" : filterType, () -> ui.choose("筛选",
                new String[]{"类别", "颜色", "清除筛选"}, i -> {
                    if (i == 0) selectType("全部类别", type -> { filterType = type; home(); });
                    else if (i == 1) chooseColor(garments);
                    else { filterType = ""; filterColor = ""; filterSeason = -1; favorites = false; home(); }
                }));
        category.setTextSize(12);
        filters.addView(category);
        for (int i = 0; i < SEASONS.length; i++) {
            int index = i;
            TextView season = ui.ghost(SEASONS[i], () -> { filterSeason = filterSeason == index ? -1 : index; home(); });
            season.setPadding(0, 0, 0, 0);
            season.setTextSize(12);
            season.setSelected(filterSeason == i);
            if (filterSeason == i) season.setBackground(ui.shape(SAND, 2, STROKE));
            filters.addView(season, new LinearLayout.LayoutParams(0, -2, 1));
        }
        TextView liked = ui.ghost(favorites ? "♥ 仅收藏" : "♡ 仅收藏", () -> { favorites = !favorites; home(); });
        liked.setTextSize(12);
        filters.addView(liked);
        ui.body.addView(filters);
        ui.rule(ui.body);
        if (!filterColor.isEmpty()) ui.body.addView(ui.ghost(filterColor + " ×", () -> { filterColor = ""; home(); }));
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
                Bitmap bitmap = db.image(g.photo, 2);
                photo.addView(ui.image(bitmap, height), new FrameLayout.LayoutParams(-1, -1));
                if (bitmap == null) photo.addView(new NavIcon(ui, 0, false), new FrameLayout.LayoutParams(ui.dp(30), ui.dp(30), Gravity.CENTER));
                TextView favorite = ui.text(g.favorite ? "♥" : "♡", 21, INK, true);
                favorite.setGravity(Gravity.CENTER);
                favorite.setContentDescription(g.favorite ? "取消收藏 " + g.name : "收藏 " + g.name);
                favorite.setOnClickListener(v -> { db.state(g.id, "favorite", !g.favorite); home(); });
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

    private void chooseColor(List<WardrobeStore.Garment> garments) {
        Map<String, Integer> colors = new LinkedHashMap<>();
        for (WardrobeStore.Garment g : garments) for (WardrobeStore.Swatch color : g.colors) {
            colors.putIfAbsent(color.name, color.rgb);
        }
        Sheet sheet = new Sheet(ui, "按颜色筛选", true);
        sheet.option("全部颜色", () -> { filterColor = ""; sheet.dismiss(); home(); });
        colors.forEach((name, rgb) -> {
            sheet.option(name, rgb, () -> { filterColor = name; sheet.dismiss(); home(); });
        });
        sheet.show();
    }

    void selectType(String first, Consumer<String> result) {
        List<String> choices = new ArrayList<>(Arrays.asList(TYPES));
        if (first != null) choices.add(0, first);
        ui.choose("选择衣物类别", choices.toArray(new String[0]),
                i -> result.accept(i == 0 && first != null ? "" : choices.get(i)));
    }

    private void newGarment() {
        ui.choose("添加衣物", new String[]{"拍摄实物", "导入衣物照片", "网购截图 / 参数表", "从淘宝导入", "手动建档"}, index -> {
            if (index == 3) new TaobaoImport(ui, this::importTaobao).start();
            else if (index == 4) editor(new WardrobeStore.Garment(), this::home);
            else pickNew(new String[]{"camera", "photo", "shop"}[index]);
        });
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
            Editor form = editor(g, back);
            if (purpose.equals("label")) form.scanLabel(bitmap);
            else form.useImage(bitmap, purpose.equals("shop"));
        };
    }

    java.util.function.BiConsumer<Bitmap, List<ImageAnalysis.SymbolHit>> restoreCareScan(long id) {
        return (image, hits) -> {
            WardrobeStore.Garment g = id == 0 ? new WardrobeStore.Garment() : db.garment(id);
            if (g == null) return;
            Editor form = editor(g, id == 0 ? this::home : () -> detail(id, this::home));
            form.scanLabel(image, "", hits);
        };
    }

    private void archive() {
        ui.page("已归档衣物", "", this::home, null, null);
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
        ui.page("", "", back, "编辑", () -> editor(g, refresh));
        if (!g.photo.isEmpty()) {
            LinearLayout image = ui.card(ui.body);
            image.addView(ui.image(db.image(original ? g.original : g.photo, 1), 350));
            if (!g.photo.equals(g.original) && !g.original.isEmpty()) tabs(ui.body, "看处理图", "看原图", original,
                    () -> detail(id, back, false), () -> detail(id, back, true));
            ui.gap(ui.body, 14);
        }
        if (!g.brand.isEmpty()) ui.body.addView(ui.text(g.brand, 12, MUTED, false));
        ui.body.addView(ui.editorial(g.name, 25));
        ui.body.addView(ui.text(g.type + (g.seasons == 0 ? "" : " · " + seasons(g.seasons))
                + (g.fabric.isEmpty() ? "" : " · " + g.fabric), 12, MUTED, false));
        colorNames(ui.body, g.colors);
        ui.gap(ui.body, 12);
        if (g.archived) {
            ui.body.addView(ui.pill("恢复到衣橱", true, () -> {
                db.archiveGarment(id, false);
                back.run();
            }));
        } else ui.actions(ui.body, new String[]{"记录洗涤", "找颜色搭配", "扫描洗标"},
                () -> recordWash(g, refresh), () -> ui.outfits.palette(g, refresh), () -> editor(g, refresh).labelSource());
        LinearLayout facts = ui.card(ui.body);
        facts.addView(ui.text("清洁  " + (g.dirty ? "待洗" : "可使用") + "    存放  " + (g.stored ? "季节性存放" : "使用中"), 14, INK, true));
        ui.gap(facts, 8);
        if (!g.archived) ui.actions(facts, new String[]{g.dirty ? "标为洁净" : "标为待洗", g.stored ? "重新启用" : "季节性存放"},
                () -> { db.state(id, "dirty", !g.dirty); refresh.run(); },
                () -> {
                    if (g.stored) ui.choose("检查是否有灰尘、异味或污渍", new String[]{"检查后确认可用", "启用并标为待洗"},
                            i -> { db.reactivate(id, i == 1); refresh.run(); });
                    else { db.state(id, "stored", true); refresh.run(); }
                });
        facts.addView(ui.ghost(g.favorite ? "♥ 取消收藏" : "♡ 收藏", () -> {
            db.state(id, "favorite", !g.favorite);
            refresh.run();
        }));

        ui.section(ui.body, "洗护与使用记录");
        LinearLayout records = ui.card(ui.body);
        records.addView(ui.text("洗涤 " + usage.washes() + " 次    穿着 " + usage.worn() + " 次", 17, INK, true));
        records.addView(ui.text("最近洗涤  " + dateText(usage.washed()) + "\n最近穿着  " + dateText(usage.lastWorn()), 13, MUTED, false));
        if (!g.symbols.isEmpty()) records.addView(ui.text(g.symbols, 14, INK, false));
        if (!g.care.isEmpty()) records.addView(ui.text(g.care, 14, INK, false));
        ui.gap(records, 8);
        ui.actions(records, new String[]{"查看洗涤历史", "护理记录"}, () -> washHistory(g), () -> careHistory(g));
        String reminder = reminder(g, usage);
        if (!g.archived && !reminder.isEmpty()) reminderCard(ui.body, g, reminder, refresh);

        ui.gap(ui.body, 12);
        if (!g.archived) ui.body.addView(ui.pill("归档衣物", false, () -> {
            db.archiveGarment(id, true);
            ui.toast("已归档，可在衣橱恢复");
            back.run();
        }));
        if (usage.worn() == 0) ui.body.addView(ui.destructiveButton("永久删除衣物", () -> ui.confirmDestructive("永久删除衣物",
                "将移除照片、洗护记录及已保存穿搭中的该单品。", "永久删除", () -> {
                    if (db.deleteGarment(g)) back.run();
                    else ui.toast("这件衣物有穿着记录，可以归档");
                })));
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
            for (var g : group.getValue()) careRow(g, g.type + (g.stored ? " · 存放中" : ""), "记录洗涤",
                    () -> recordWash(g, this::care), this::care);
            ui.gap(ui.body, 16);
        }
        int reminders = 0;
        for (WardrobeStore.Garment g : garments) {
            String reminder = reminder(g, db.usage(g.id));
            if (reminder.isEmpty()) continue;
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
        ui.page("洗护", pending == 0 ? "" : pending + " 件待洗", null, null, null);
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
        item.addView(ui.image(db.image(g.photo, 4), 63), new LinearLayout.LayoutParams(ui.dp(48), ui.dp(63)));
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
        card.addView(ui.pill("完成护理检查", false, () -> {
            Sheet sheet = new Sheet(ui, "护理检查", true);
            EditText input = ui.field(sheet.content, "检查结果", "", true, false);
            sheet.button("保存", true, () -> {
                String value = ui.value(input);
                db.checked(g.id, value.isEmpty() ? "完成护理检查" : value);
                sheet.dismiss();
                refresh.run();
            });
            sheet.show();
        }));
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
        List<List<WardrobeStore.Garment>> groups = Arrays.asList(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        List<WardrobeStore.Garment> untagged = new ArrayList<>();
        int total = 0, storedClean = 0;
        for (WardrobeStore.Garment g : garments) {
            if (g.seasons == 0) {
                untagged.add(g);
                continue;
            }
            if ((g.seasons & (1 << currentSeason)) == 0) continue;
            total++;
            boolean check = !reminder(g, db.usage(g.id)).isEmpty();
            if (g.dirty) groups.get(1).add(g);
            if (check) groups.get(2).add(g);
            if (g.stored) groups.get(3).add(g);
            if (g.stored && !g.dirty) storedClean++;
            if (!g.dirty && !check && !g.stored) groups.get(0).add(g);
        }
        int pending = groups.get(1).size();
        String season = SEASONS[currentSeason] + "季";
        seasonSummary(season, total, groups.get(0).size(), pending, storedClean);
        if (total == 0) ui.empty(ui.body, season + "暂无适用衣物", "");
        String[] titles = {"洁净就绪（可直接穿）", "当季未洗（待清洗）", "需检查或护理", "尚未重新启用"};
        for (int i : new int[]{1, 3, 2, 0}) {
            List<WardrobeStore.Garment> group = groups.get(i);
            if (group.isEmpty()) continue;
            ui.section(ui.body, titles[i] + "  " + group.size());
            for (WardrobeStore.Garment g : group) {
                String subtitle = (g.dirty ? "待洗" : "洁净") + " · " + (g.stored ? "存放中" : "使用中") + " · " + g.type;
                if (i == 1) careRow(g, subtitle, "记录洗涤", () -> recordWash(g, this::season), this::season);
                else if (i == 3) careRow(g, subtitle, "重新启用", () -> ui.choose("重新启用",
                        new String[]{"确认可用", "启用并标为待洗"}, index -> { db.reactivate(g.id, index == 1); season(); }), this::season);
                else careRow(g, subtitle, null, null, this::season);
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

    private void seasonSummary(String season, int total, int ready, int pending, int storedClean) {
        LinearLayout status = ui.card(ui.body);
        status.setBackground(ui.shape(SAND, 4, 0xffcccccc));
        status.addView(ui.text(season + "就绪检查 · " + total + " 件", 12, MUTED, false));
        int percent = total == 0 ? 0 : Math.round(100f * ready / total);
        status.addView(ui.text("可直接穿 " + ready + " / " + total + " 件 · " + percent + "%", 15, INK, true));
        if (total == 0) return;
        ui.gap(status, 10);
        int[] amounts = {ready, pending, storedClean, total - ready - pending - storedClean};
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
        String[] names = {"可直接穿", "当季未洗", "洁净未启用"};
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
        Bitmap bitmap = db.image(g.photo, 4);
        ImageView thumb = ui.image(bitmap, 55);
        row.addView(thumb, new LinearLayout.LayoutParams(ui.dp(57), ui.dp(56)));
        if (bitmap == null) thumb.setContentDescription("暂无衣物照片");
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
        private boolean originalSelected, colorCheck, useOriginalPhoto;
        private EditText name, brand, fabric, care, wash, idle;
        private TextView kind, season, symbolSummary, sourceTextButton, labelTextButton, addPhoto, photoMode;
        private ImageView preview;
        private LinearLayout colorBox, photoTools;

        Editor(WardrobeStore.Garment garment, Runnable back) {
            g = garment;
            exit = back;
            cutoutOrigin = g.cutoutOrigin;
        }

        void render() {
            ui.page(g.id == 0 ? "添加衣物" : "编辑衣物",
                    "", () -> ui.confirmDestructive("退出编辑", "未保存的修改将丢失。", "放弃修改", exit), "保存", this::save);
            ui.section(ui.body, "衣物照片");
            LinearLayout photo = ui.vertical();
            ui.body.addView(photo);
            preview = ui.image(db.image(g.photo, 2), 290);
            LinearLayout.LayoutParams photoSize = new LinearLayout.LayoutParams(-1, ui.dp(290));
            photoSize.leftMargin = ui.dp(32);
            photoSize.rightMargin = ui.dp(32);
            photo.addView(preview, photoSize);
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
            photoChoice = ui.pill("", false, () -> {
                useOriginalPhoto = originalSelected;
                refreshText();
            });
            photo.addView(photoChoice);
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
            brand = ui.field(details, "品牌", g.brand, false, false);
            fabric = ui.field(details, "材质 / 成分", g.fabric, false, false);
            season = ui.pill("", false, this::setSeasons);
            details.addView(season);
            sourceTextButton = ui.ghost("商品原文", () -> showRaw("商品原文", g.sourceText));
            details.addView(sourceTextButton);
            ui.section(ui.body, "颜色");
            colorBox = ui.card(ui.body);
            ui.section(ui.body, "洗护");
            LinearLayout label = ui.card(ui.body);
            ui.actions(label, new String[]{"扫描洗标", "编辑符号"}, this::labelSource, this::symbolPicker);
            symbolSummary = ui.text("", 14, INK, false);
            label.addView(symbolSummary);
            care = ui.field(label, "洗护说明", g.care, true, false);
            labelTextButton = ui.ghost("洗标原文", () -> showRaw("洗标原文", g.labelText));
            label.addView(labelTextButton);
            ui.section(ui.body, "护理提醒");
            LinearLayout reminders = ui.card(ui.body);
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

        private TextView photoChoice;

        private void refreshText() {
            kind.setText(g.type.isEmpty() ? "选择类别 *" : "类别  " + g.type + "  ›");
            season.setText(g.seasons == 0 ? "季节未标注  ›" : "季节 · " + seasons(g.seasons) + "  ›");
            symbolSummary.setText(g.symbols);
            symbolSummary.setVisibility(g.symbols.isEmpty() ? View.GONE : View.VISIBLE);
            sourceTextButton.setVisibility(g.sourceText.isEmpty() ? View.GONE : View.VISIBLE);
            labelTextButton.setVisibility(g.labelText.isEmpty() ? View.GONE : View.VISIBLE);
            Bitmap current = displayed();
            preview.setImageBitmap(current);
            preview.setVisibility(current == null ? View.GONE : View.VISIBLE);
            photoTools.setVisibility(current == null ? View.GONE : View.VISIBLE);
            addPhoto.setVisibility(current == null ? View.VISIBLE : View.GONE);
            boolean hasAlternative = cutout != null && cutout != imported || !g.original.equals(g.photo) && imported == null;
            photoMode.setVisibility(hasAlternative ? View.VISIBLE : View.GONE);
            photoMode.setText(originalSelected ? "看处理图" : "看原图");
            boolean usingOriginal = useOriginalPhoto || cutout == null && imported == null && g.original.equals(g.photo);
            photoChoice.setText(originalSelected == usingOriginal ? "当前使用此图"
                    : originalSelected ? "使用原图" : "使用处理图");
            photoChoice.setEnabled(originalSelected != usingOriginal);
            photoChoice.setVisibility(current != null && hasAlternative ? View.VISIBLE : View.GONE);
        }

        private void imageSource() {
            ui.choose("选择衣物照片", new String[]{"拍摄实物", "从相册导入", "导入网购截图或参数表"},
                    i -> ui.requestImage(i == 2 ? "shop" : "photo", g.id, i == 0, b -> useImage(b, i == 2)));
        }

        void useImage(Bitmap bitmap, boolean shop) {
            imported = bitmap;
            target = bitmap;
            cutout = null;
            cutoutOrigin = null;
            useOriginalPhoto = false;
            originalSelected = false;
            if (shop) {
                ui.task("正在读取商品文字…", () -> readText(bitmap), text -> {
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
                                else { originalSelected = true; useOriginalPhoto = true; g.colors.clear(); showColors(); refreshText(); }
                            });
                });
            } else identify(bitmap, null);
        }

        private String readText(Bitmap bitmap) {
            try { return ui.textReader.read(bitmap); }
            catch (Exception | LinkageError e) { Log.w("SmartWardrobe", "OCR", e); return ""; }
        }

        private ImageAnalysis.Detection detectGarment(Bitmap image) {
            try { return ui.garmentDetector.detect(image); }
            catch (Exception | LinkageError e) { Log.w("SmartWardrobe", "Garment detection", e); return null; }
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
                useOriginalPhoto = false;
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
                Bitmap base = cutout == null ? db.image(g.photo, 1) : cutout;
                if (base != null) {
                    brushSheet(source, new KeepBrushView.Cutout(base, cutoutOrigin));
                    return;
                }
            }
            if (imported == null && cutout == null && !g.original.equals(g.photo)) {
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
            sheet.button("使用结果", true, () -> {
                KeepBrushView.Cutout result = brush.result();
                if (result == null) { ui.toast("请先涂抹要保留的区域"); return; }
                cutout = result.image();
                cutoutOrigin = result.origin();
                useOriginalPhoto = false;
                originalSelected = false;
                sheet.dismiss();
                refreshText();
            });
            sheet.show();
        }

        private Bitmap displayed() {
            if (originalSelected) return imported == null ? db.image(g.original, 1) : imported;
            if (cutout != null) return cutout;
            return target == null ? db.image(g.photo, 2) : target;
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
            colorBox.removeAllViews();
            if (colorCheck && !g.colors.isEmpty()) colorBox.addView(ui.text("颜色待核对", 12, MUTED, false));
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
            Bitmap processed = cutout != null ? cutout : target != null ? target : db.image(g.photo, 1);
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

        void labelSource() {
            ui.scanCare(g.id, (image, hits) -> scanLabel(image, "", hits));
        }

        void scanLabel(Bitmap image) { scanLabel(image, ""); }

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
            sheet.button("采用", true, () -> {
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
            g.name = ui.value(name);
            if (g.name.isEmpty() || g.type.isEmpty()) { ui.toast("请填写名称并选择类别"); return; }
            g.brand = ui.value(brand);
            g.fabric = ui.value(fabric);
            g.care = ui.value(care);
            g.washEvery = number(wash, 10);
            g.idleDays = number(idle, 90);
            Bitmap original = imported;
            boolean saveOriginal = useOriginalPhoto;
            Bitmap picture = saveOriginal ? (original == null ? db.image(g.original, 1) : original) : cutout;
            Point origin = saveOriginal ? null : cutoutOrigin;
            boolean isNew = g.id == 0;
            ui.task("正在保存衣物…", () -> { db.save(g, original, picture, origin); return g.id; }, id -> {
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
