package com.smartwardrobe.local;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Display-only translations. Stored garment, colour and care values stay in their original form. */
final class UiLanguage {
    private static final String PREFERENCES = "ui_language";
    private static final String ENGLISH = "english";
    private static final Pattern MONTH = Pattern.compile("(\\d{4})\\s*年\\s*(\\d{1,2})\\s*月");
    private static final Pattern DAY = Pattern.compile("(\\d{1,2})月(\\d{1,2})日\\s*周([一二三四五六日])");
    private static final Pattern COUNT = Pattern.compile("^(\\d+)\\s*(件单品|套穿搭|件衣物|件待洗|张照片|天)$");
    private static final Pattern SEASON = Pattern.compile("^([春夏秋冬])季(.*)$");
    private static final Pattern SEASONS = Pattern.compile("^[春夏秋冬]{1,4}$");
    private static Map<String, String> translations;
    private static List<Map.Entry<String, String>> fragments;

    static boolean english(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(ENGLISH, false);
    }

    static void setEnglish(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(ENGLISH, enabled).apply();
    }

    static String text(Context context, String source) {
        if (source == null) return "";
        if (!english(context) || !containsChinese(source)) return source;
        ensureLoaded(context);
        String exact = translations.get(source);
        if (exact != null) return exact;
        Matcher day = DAY.matcher(source);
        if (day.matches()) {
            int month = Integer.parseInt(day.group(1));
            String weekday = switch (day.group(3)) {
                case "一" -> "Mon"; case "二" -> "Tue"; case "三" -> "Wed";
                case "四" -> "Thu"; case "五" -> "Fri"; case "六" -> "Sat";
                default -> "Sun";
            };
            return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
                    + " " + day.group(2) + " · " + weekday;
        }
        Matcher month = MONTH.matcher(source);
        if (month.matches()) return Month.of(Integer.parseInt(month.group(2)))
                .getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.group(1);
        Matcher count = COUNT.matcher(source);
        if (count.matches()) {
            int n = Integer.parseInt(count.group(1));
            String unit = switch (count.group(2)) {
                case "套穿搭" -> n == 1 ? "outfit" : "outfits";
                case "张照片" -> n == 1 ? "photo" : "photos";
                case "天" -> n == 1 ? "day" : "days";
                default -> n == 1 ? "item" : "items";
            };
            return n + " " + unit;
        }
        Matcher season = SEASON.matcher(source);
        if (source.matches("^[春夏秋冬]季暂无适用衣物$"))
            return "No items for " + seasonName(source.charAt(0));
        if (season.matches()) return seasonName(season.group(1).charAt(0))
                + text(context, season.group(2)).replace('，', ',');
        if (SEASONS.matcher(source).matches()) {
            List<String> names = new ArrayList<>();
            for (int i = 0; i < source.length(); i++) names.add(seasonName(source.charAt(i)));
            return String.join(" · ", names);
        }
        int gap = source.lastIndexOf("  ");
        if (gap > 0 && SEASONS.matcher(source.substring(gap + 2)).matches())
            return text(context, source.substring(0, gap)) + " · " + text(context, source.substring(gap + 2));
        if (source.startsWith("从「") && source.endsWith("」开始搭配"))
            return "Build around “" + source.substring(2, source.length() - 5) + "”";
        String result = source;
        for (Map.Entry<String, String> entry : fragments) {
            String key = entry.getKey();
            if (key.length() >= 2 && result.contains(key)) result = result.replace(key, entry.getValue());
        }
        return result;
    }

    static void localizeTree(View view) {
        if (!english(view.getContext())) return;
        if (view instanceof EditText input) {
            CharSequence hint = input.getHint();
            if (hint != null) {
                String translated = text(view.getContext(), hint.toString());
                if (!translated.contentEquals(hint)) input.setHint(translated);
            }
        } else if (view instanceof TextView label && !(view instanceof Label)) {
            CharSequence current = label.getText();
            String translated = text(view.getContext(), current.toString());
            if (!translated.contentEquals(current)) label.setText(translated);
        }
        CharSequence description = view.getContentDescription();
        if (description != null) {
            String translated = text(view.getContext(), description.toString());
            if (!translated.contentEquals(description)) view.setContentDescription(translated);
        }
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) localizeTree(group.getChildAt(i));
    }

    private static boolean containsChinese(String text) {
        for (int i = 0; i < text.length(); i++) if (text.charAt(i) >= '\u3400' && text.charAt(i) <= '\u9fff') return true;
        return false;
    }

    private static String seasonName(char season) {
        return switch (season) {
            case '春' -> "Spring"; case '夏' -> "Summer";
            case '秋' -> "Autumn"; default -> "Winter";
        };
    }

    private static synchronized void ensureLoaded(Context context) {
        if (translations != null) return;
        Map<String, String> loaded = new HashMap<>();
        try {
            for (String asset : new String[]{"ui_en.tsv", "ui_en_overrides.tsv"}) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open(asset), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        int tab = line.indexOf('\t');
                        if (tab <= 0 || tab == line.length() - 1) continue;
                        loaded.put(line.substring(0, tab).replace("\\n", "\n"),
                                line.substring(tab + 1).replace("\\n", "\n")
                                        .replace("{space}", " "));
                    }
                }
            }
        } catch (Exception error) {
            android.util.Log.e("UiLanguage", "Could not load English UI copy", error);
        }
        translations = loaded;
        fragments = new ArrayList<>(loaded.entrySet());
        fragments.removeIf(entry -> entry.getKey().length() < 2 || entry.getKey().contains("\\")
                || entry.getKey().contains("(?") || entry.getKey().contains("%"));
        fragments.sort(Comparator.comparingInt((Map.Entry<String, String> entry) -> entry.getKey().length()).reversed());
    }

    static final class Label extends TextView {
        private boolean localizable = true;
        Label(Context context) { super(context); }

        void setLocalizable(boolean enabled) { localizable = enabled; }

        @Override public void setText(CharSequence text, BufferType type) {
            String value = text == null ? "" : text.toString();
            super.setText(localizable ? UiLanguage.text(getContext(), value) : value, type);
        }

        @Override public void setContentDescription(CharSequence description) {
            super.setContentDescription(UiLanguage.text(getContext(),
                    description == null ? "" : description.toString()));
        }
    }
}
