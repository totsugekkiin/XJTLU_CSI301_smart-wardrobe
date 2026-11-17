package com.smartwardrobe.local;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class GarmentColors implements ImageAnalysis.ColorReader {
    private static final int SAMPLE_EDGE = 144;
    private static final double MIN_COLOR_SEPARATION_SQUARED = 22 * 22;

    private record Sample(int rgb, double[] lab) {}

    @Override public List<WardrobeStore.Swatch> read(Bitmap image, boolean cutout, boolean cropped) {
        double scale = Math.min(1, (double) SAMPLE_EDGE / Math.max(image.getWidth(), image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        Bitmap thumb = Bitmap.createScaledBitmap(image, width, height, true);
        int[] pixels = new int[width * height];
        thumb.getPixels(pixels, 0, width, 0, 0, width, height);
        if (thumb != image) thumb.recycle();

        boolean[] background = cutout ? null : plainBackground(pixels, width, height);
        if (!cutout && background == null && !cropped) return new ArrayList<>();
        List<Sample> samples = samples(pixels, width, height, background, cutout || background != null);
        return cluster(samples);
    }

    private static List<Sample> samples(int[] pixels, int width, int height, boolean[] background, boolean hasMask) {
        List<Sample> foreground = new ArrayList<>();
        List<Sample> interior = new ArrayList<>();
        int margin = Math.max(1, Math.min(width, height) / 45);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int index = y * width + x;
                if (!opaque(pixels, background, index)) continue;
                Sample sample = new Sample(pixels[index], ImageAnalysis.lab(pixels[index]));
                foreground.add(sample);
                if (x >= margin && x < width - margin && y >= margin && y < height - margin
                        && (!hasMask || opaque(pixels, background, index - margin)
                        && opaque(pixels, background, index + margin)
                        && opaque(pixels, background, index - margin * width)
                        && opaque(pixels, background, index + margin * width))) {
                    interior.add(sample);
                }
            }
        }
        return interior.size() >= Math.max(24, foreground.size() / 2) ? interior : foreground;
    }

    private static boolean opaque(int[] pixels, boolean[] background, int index) {
        return Color.alpha(pixels[index]) >= 235 && (background == null || !background[index]);
    }

    private static boolean[] plainBackground(int[] pixels, int width, int height) {
        if (width < 8 || height < 8) return null;
        int[] border = new int[2 * width + 2 * (height - 2)];
        int edge = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (y == 0 || y == height - 1 || x == 0 || x == width - 1) border[edge++] = pixels[y * width + x];
            }
        }
        double[] reference = ImageAnalysis.lab(medianRgb(border, border.length));
        boolean[] similar = new boolean[pixels.length], background = new boolean[pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            similar[i] = ImageAnalysis.distance(reference, ImageAnalysis.lab(pixels[i])) <= 15 * 15;
        }
        int[] queue = new int[pixels.length];
        int end = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if ((y == 0 || y == height - 1 || x == 0 || x == width - 1)) {
                    end = enqueue(y * width + x, similar, background, queue, end);
                }
            }
        }
        if (end < .72 * border.length) return null;
        for (int front = 0; front < end; front++) {
            int index = queue[front], x = index % width, y = index / width;
            if (x > 0) end = enqueue(index - 1, similar, background, queue, end);
            if (x < width - 1) end = enqueue(index + 1, similar, background, queue, end);
            if (y > 0) end = enqueue(index - width, similar, background, queue, end);
            if (y < height - 1) end = enqueue(index + width, similar, background, queue, end);
        }
        if (end < .28 * pixels.length || end > .90 * pixels.length) return null;
        int center = 0, centerForeground = 0;
        for (int y = height / 4; y < 3 * height / 4; y++) {
            for (int x = width / 4; x < 3 * width / 4; x++) {
                center++;
                if (!background[y * width + x]) centerForeground++;
            }
        }
        return centerForeground >= .30 * center ? background : null;
    }

    private static int enqueue(int index, boolean[] similar, boolean[] background, int[] queue, int end) {
        if (!similar[index] || background[index]) return end;
        background[index] = true;
        queue[end] = index;
        return end + 1;
    }

    private static List<WardrobeStore.Swatch> cluster(List<Sample> samples) {
        List<WardrobeStore.Swatch> output = new ArrayList<>();
        if (samples.isEmpty()) return output;
        Map<Integer, List<Sample>> bins = new LinkedHashMap<>();
        for (Sample sample : samples) {
            double[] lab = sample.lab;
            int key = (int) (lab[0] / 12) * 4096 + (int) ((lab[1] + 128) / 10) * 64
                    + (int) ((lab[2] + 128) / 10);
            bins.computeIfAbsent(key, unused -> new ArrayList<>()).add(sample);
        }
        List<List<Sample>> common = new ArrayList<>(bins.values());
        common.sort(Comparator.<List<Sample>>comparingInt(List::size).reversed());
        List<double[]> means = new ArrayList<>();
        for (List<Sample> bin : common) {
            double[] point = bin.get(bin.size() / 2).lab.clone();
            if (means.stream().allMatch(mean -> colorDistance(mean, point) >= MIN_COLOR_SEPARATION_SQUARED)) {
                means.add(point);
                if (means.size() == 3) break;
            }
        }
        int groups = means.size();
        int[] assigned = new int[samples.size()];
        int[] counts = new int[groups];
        for (int iteration = 0; iteration < 9; iteration++) {
            double[][] sums = new double[groups][3];
            Arrays.fill(counts, 0);
            for (int i = 0; i < samples.size(); i++) {
                double[] lab = samples.get(i).lab;
                int nearest = 0;
                double best = colorDistance(means.get(0), lab);
                for (int j = 1; j < groups; j++) {
                    double distance = colorDistance(means.get(j), lab);
                    if (distance < best) {
                        best = distance;
                        nearest = j;
                    }
                }
                assigned[i] = nearest;
                counts[nearest]++;
                for (int c = 0; c < 3; c++) sums[nearest][c] += lab[c];
            }
            for (int j = 0; j < groups; j++) if (counts[j] != 0) {
                for (int c = 0; c < 3; c++) means.get(j)[c] = sums[j][c] / counts[j];
            }
        }
        for (int j = 0; j < groups; j++) {
            if (counts[j] < .09 * samples.size()) continue;
            int[] rgb = new int[counts[j]];
            int size = 0;
            for (int i = 0; i < assigned.length; i++) if (assigned[i] == j) rgb[size++] = samples.get(i).rgb;
            int color = medianRgb(rgb, size);
            output.add(new WardrobeStore.Swatch(ImageAnalysis.colorName(color), color, size, false));
        }
        output.sort(Comparator.comparingInt((WardrobeStore.Swatch swatch) -> swatch.percent).reversed());
        int total = output.stream().mapToInt(swatch -> swatch.percent).sum(), cumulative = 0, used = 0;
        for (int i = 0; i < output.size(); i++) {
            WardrobeStore.Swatch swatch = output.get(i);
            cumulative += swatch.percent;
            int percent = Math.round(100f * cumulative / total);
            swatch.percent = percent - used;
            swatch.primary = i == 0;
            used = percent;
        }
        return output;
    }

    private static double colorDistance(double[] first, double[] second) {
        double lightness = .58 * (first[0] - second[0]);
        double a = first[1] - second[1], b = first[2] - second[2];
        return lightness * lightness + a * a + b * b;
    }

    static Integer samplePhoto(Bitmap photo, int x, int y) {
        if (Color.alpha(photo.getPixel(x, y)) < 235) return null;
        int radius = Math.max(1, Math.min(7, Math.max(photo.getWidth(), photo.getHeight()) / 220));
        int[] colors = new int[(2 * radius + 1) * (2 * radius + 1)];
        int count = 0;
        for (int row = Math.max(0, y - radius); row <= Math.min(photo.getHeight() - 1, y + radius); row++) {
            for (int col = Math.max(0, x - radius); col <= Math.min(photo.getWidth() - 1, x + radius); col++) {
                int color = photo.getPixel(col, row);
                if (Color.alpha(color) >= 235) colors[count++] = color;
            }
        }
        return medianRgb(colors, count);
    }

    private static int medianRgb(int[] colors, int size) {
        int[] values = new int[size];
        for (int i = 0; i < size; i++) values[i] = Color.red(colors[i]);
        Arrays.sort(values);
        int red = values[size / 2];
        for (int i = 0; i < size; i++) values[i] = Color.green(colors[i]);
        Arrays.sort(values);
        int green = values[size / 2];
        for (int i = 0; i < size; i++) values[i] = Color.blue(colors[i]);
        Arrays.sort(values);
        return Color.rgb(red, green, values[size / 2]);
    }
}
