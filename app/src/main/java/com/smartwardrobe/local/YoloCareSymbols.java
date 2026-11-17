package com.smartwardrobe.local;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Fixed-size YOLO11m detect export with an unfiltered [1, 4 + classes, anchors] output. */
final class YoloCareSymbols implements ImageAnalysis.SymbolDetector, AutoCloseable {
    private static final int SIZE = 640;
    private static final float MIN_SCORE = .60f;
    private final Context context;
    private OrtSession session;
    private List<Symbol> symbols;

    private record Symbol(int group, String meaning, boolean automatic) {}

    YoloCareSymbols(Context context) { this.context = context.getApplicationContext(); }

    @Override public synchronized List<ImageAnalysis.SymbolHit> detect(Bitmap image) throws Exception {
        if (symbols == null) symbols = readClasses();
        int width = image.getWidth(), height = image.getHeight();
        float scale = Math.min(1f, (float) SIZE / Math.max(width, height));
        int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
        int dx = (SIZE - w) / 2, dy = (SIZE - h) / 2;
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int gray = (19595 * Color.red(p) + 38470 * Color.green(p) + 7471 * Color.blue(p) + 32768) >> 16;
            pixels[i] = Color.rgb(gray, gray, gray);
        }
        Bitmap gray = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
        Bitmap resized = Bitmap.createScaledBitmap(gray, w, h, true);
        pixels = new int[w * h];
        resized.getPixels(pixels, 0, w, 0, 0, w, h);
        if (resized != gray) resized.recycle();
        gray.recycle();
        int plane = SIZE * SIZE;
        float[] tensor = new float[plane * 3];
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int pixel = pixels[y * w + x], i = (y + dy) * SIZE + x + dx;
            tensor[i] = tensor[i + plane] = tensor[i + 2 * plane] = Color.red(pixel) / 255f;
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        if (session == null) session = open(env);
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result output = session.run(Collections.singletonMap("images", input))) {
            float[][] prediction = ((float[][][]) output.get(0).getValue())[0];
            return decode(prediction, symbols, w, h, dx, dy);
        }
    }

    private OrtSession open(OrtEnvironment env) throws Exception {
        try (AssetFileDescriptor asset = context.getAssets().openFd("care-yolo11m.onnx");
             FileInputStream stream = asset.createInputStream();
             OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(2);
            ByteBuffer model = stream.getChannel().map(FileChannel.MapMode.READ_ONLY,
                    asset.getStartOffset(), asset.getLength());
            return env.createSession(model, options);
        }
    }

    @Override public synchronized void close() throws ai.onnxruntime.OrtException {
        if (session != null) {
            OrtSession current = session;
            session = null;
            current.close();
        }
    }

    private List<ImageAnalysis.SymbolHit> decode(float[][] values, List<Symbol> classes, int w, int h, int dx, int dy) {
        if (classes.size() != 14 || values.length != 4 + classes.size()) {
            throw new IllegalArgumentException("Unexpected laundry model output");
        }
        List<ImageAnalysis.SymbolHit> found = new ArrayList<>();
        for (int anchor = 0; anchor < values[0].length; anchor++) {
            float best = MIN_SCORE;
            int index = -1;
            for (int k = 0; k < classes.size(); k++) {
                if (values[k + 4][anchor] >= best) {
                    best = values[k + 4][anchor];
                    index = k;
                }
            }
            if (index < 0) continue;
            Symbol symbol = classes.get(index);
            // Keep the winning score from the full model so manual classes do not fall through to another class.
            if (!symbol.automatic()) continue;
            float x = values[0][anchor], y = values[1][anchor];
            float rw = values[2][anchor] / 2, rh = values[3][anchor] / 2;
            RectF box = new RectF(unit((x - rw - dx) / w), unit((y - rh - dy) / h),
                    unit((x + rw - dx) / w), unit((y + rh - dy) / h));
            if (box.width() <= 0 || box.height() <= 0) continue;
            found.add(new ImageAnalysis.SymbolHit(symbol.group(), symbol.meaning(), best, box));
        }
        found.sort(Comparator.comparingDouble(ImageAnalysis.SymbolHit::confidence).reversed());
        List<ImageAnalysis.SymbolHit> filtered = new ArrayList<>();
        for (ImageAnalysis.SymbolHit hit : found) {
            if (filtered.stream().noneMatch(other -> hit.meaning().equals(other.meaning())
                    && overlaps(hit.bounds(), other.bounds()))) filtered.add(hit);
            if (filtered.size() == 50) break;
        }
        filtered.sort(Comparator.comparingInt(ImageAnalysis.SymbolHit::group)
                .thenComparing(Comparator.comparingDouble(ImageAnalysis.SymbolHit::confidence).reversed()));
        return filtered;
    }

    private List<Symbol> readClasses() throws Exception {
        List<Symbol> result = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(
                context.getAssets().open("care-classes.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                String[] values = line.split("\\t", 4);
                result.add(new Symbol(Integer.parseInt(values[1]), values[2], values.length == 3));
            }
        }
        return result;
    }

    private static float unit(float x) { return Math.max(0, Math.min(1, x)); }

    private static boolean overlaps(RectF a, RectF b) {
        RectF intersection = new RectF(a);
        if (!intersection.intersect(b)) return false;
        float area = intersection.width() * intersection.height();
        return area > .70f * (a.width() * a.height() + b.width() * b.height() - area);
    }
}
