package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

final class ColorControls {
    private final MainActivity ui;
    private final TextView sampleName;
    private final android.view.View sample;
    private final SeekBar hue, saturation, lightness;
    private final float[] hsl = new float[3];
    private int rgb;
    private boolean updating;

    ColorControls(MainActivity ui, LinearLayout form, int color) {
        this.ui = ui;
        LinearLayout preview = ui.row();
        preview.setGravity(Gravity.CENTER_VERTICAL);
        sample = new android.view.View(ui);
        preview.addView(sample, new LinearLayout.LayoutParams(ui.dp(56), ui.dp(44)));
        ui.space(preview, 10);
        sampleName = ui.text("", 16, MainActivity.INK, true);
        preview.addView(sampleName);
        form.addView(preview);
        ui.gap(form, 8);
        hue = slider(form, "色相", 3599);
        saturation = slider(form, "饱和度", 1000);
        lightness = slider(form, "明度", 1000);
        int[] hueTrack = new int[7];
        for (int i = 0; i < hueTrack.length; i++) hueTrack[i] = colorAt(i * 60 % 360, 1, .5f);
        hue.setProgressDrawable(track(hueTrack));
        setColor(color);
    }

    int color() { return rgb; }

    void setColor(int color) {
        updating = true;
        rgb = color;
        ColorUtils.colorToHSL(color, hsl);
        hue.setProgress(Math.min(hue.getMax(), Math.round(hsl[0] * 10)));
        saturation.setProgress(Math.round(hsl[1] * 1000));
        lightness.setProgress(Math.round(hsl[2] * 1000));
        updating = false;
        updatePreview();
    }

    private SeekBar slider(LinearLayout form, String label, int maximum) {
        form.addView(ui.text(label, 13, MainActivity.MUTED, true));
        SeekBar slider = new SeekBar(ui);
        slider.setMax(maximum);
        slider.setContentDescription(label);
        slider.setSplitTrack(false);
        GradientDrawable thumb = ui.shape(MainActivity.PAPER, 9, MainActivity.INK);
        thumb.setSize(ui.dp(18), ui.dp(18));
        slider.setThumb(thumb);
        slider.setThumbOffset(ui.dp(9));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (updating || !fromUser) return;
                hsl[0] = hue.getProgress() / 10f;
                hsl[1] = saturation.getProgress() / 1000f;
                hsl[2] = lightness.getProgress() / 1000f;
                rgb = ColorUtils.HSLToColor(hsl);
                updatePreview();
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        form.addView(slider, new LinearLayout.LayoutParams(-1, ui.dp(37)));
        return slider;
    }

    private void updatePreview() {
        sample.setBackground(ui.shape(rgb, 4, MainActivity.STROKE));
        sampleName.setText(ImageAnalysis.colorName(rgb));
        saturation.setProgressDrawable(track(new int[]{colorAt(hsl[0], 0, hsl[2]), colorAt(hsl[0], 1, hsl[2])}));
        lightness.setProgressDrawable(track(new int[]{MainActivity.INK, colorAt(hsl[0], hsl[1], .5f), MainActivity.PAPER}));
    }

    private BitmapDrawable track(int[] colors) {
        int width = 144, height = 7;
        int[] pixels = new int[width * height];
        for (int x = 0; x < width; x++) {
            float position = x * (colors.length - 1f) / (width - 1);
            int segment = Math.min((int) position, colors.length - 2);
            int color = ColorUtils.blendARGB(colors[segment], colors[segment + 1], position - segment);
            for (int y = 0; y < height; y++) pixels[y * width + x] = color;
        }
        Bitmap bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        bitmap.setDensity(DisplayMetrics.DENSITY_DEFAULT);
        BitmapDrawable track = new BitmapDrawable(ui.getResources(), bitmap);
        track.setGravity(Gravity.FILL_HORIZONTAL | Gravity.CENTER_VERTICAL);
        track.setFilterBitmap(true);
        return track;
    }

    private static int colorAt(float hue, float saturation, float lightness) {
        return ColorUtils.HSLToColor(new float[]{hue, saturation, lightness});
    }
}
