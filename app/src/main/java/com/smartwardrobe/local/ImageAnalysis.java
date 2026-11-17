package com.smartwardrobe.local;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.net.Uri;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

import java.io.InputStream;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ImageAnalysis {
    interface GarmentDetector { Detection detect(Bitmap image) throws Exception; }
    interface TextReader { String read(Bitmap image) throws Exception; }
    interface ColorReader { List<WardrobeStore.Swatch> read(Bitmap image, boolean cutout, boolean cropped); }
    interface SymbolDetector { List<SymbolHit> detect(Bitmap image) throws Exception; }

    record Detection(Bitmap image, String type, float confidence, int left, int top) {}
    record SymbolHit(int group, String meaning, float confidence, android.graphics.RectF bounds) {}

    static Bitmap decode(Context context, Uri uri) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.getContentResolver(), uri),
                (decoder, info, source) -> {
                    int width = info.getSize().getWidth(), height = info.getSize().getHeight();
                    float scale = Math.min(1f, 1800f / Math.max(width, height));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setTargetSize(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
                });
    }

    static final class BundledText implements TextReader {
        @Override public String read(Bitmap image) throws Exception {
            try (TextRecognizer reader = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build())) {
                return Tasks.await(reader.process(InputImage.fromBitmap(image, 0)), 45, TimeUnit.SECONDS).getText();
            }
        }
    }

    static final class YoloGarment implements GarmentDetector {
        private static final int SIZE = 416;
        private static final String[] TYPES = {
                "上衣", "上衣", "外套", "外套", "背心", "吊带", "短裤", "长裤", "半裙", "连衣裙",
                "连衣裙", "连衣裙", "连衣裙"
        };
        private final Context context;
        private OrtSession session;

        private record GarmentPart(float[] row, Rect bounds) {}

        YoloGarment(Context context) { this.context = context.getApplicationContext(); }

        private OrtSession session() throws Exception {
            if (session == null) {
                try (InputStream input = context.getAssets().open("garment.onnx");
                     OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                    options.setIntraOpNumThreads(2);
                    java.io.ByteArrayOutputStream model = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[65536];
                    for (int size; (size = input.read(buffer)) != -1;) model.write(buffer, 0, size);
                    session = OrtEnvironment.getEnvironment().createSession(model.toByteArray(), options);
                }
            }
            return session;
        }

        @Override public Detection detect(Bitmap image) throws Exception {
            int width = image.getWidth(), height = image.getHeight();
            float scale = (float) SIZE / Math.max(width, height);
            int w = Math.max(1, Math.round(width * scale)), h = Math.max(1, Math.round(height * scale));
            int dx = (SIZE - w) / 2, dy = (SIZE - h) / 2;
            Bitmap resized = Bitmap.createScaledBitmap(image, w, h, true);
            int[] pixels = new int[w * h];
            resized.getPixels(pixels, 0, w, 0, 0, w, h);
            int plane = SIZE * SIZE;
            float[] tensor = new float[plane * 3];
            java.util.Arrays.fill(tensor, 114f / 255);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = pixels[y * w + x], offset = (y + dy) * SIZE + x + dx;
                    tensor[offset] = Color.red(rgb) / 255f;
                    tensor[offset + plane] = Color.green(rgb) / 255f;
                    tensor[offset + plane * 2] = Color.blue(rgb) / 255f;
                }
            }
            try (OnnxTensor input = OnnxTensor.createTensor(OrtEnvironment.getEnvironment(), FloatBuffer.wrap(tensor),
                    new long[]{1, 3, SIZE, SIZE});
                 OrtSession.Result output = session().run(java.util.Collections.singletonMap("images", input))) {
                float[][] rows = ((float[][][]) output.get("output0").get().getValue())[0];
                float[][][] proto = ((float[][][][]) output.get("output1").get().getValue())[0];
                int[] rgb = new int[width * height];
                image.getPixels(rgb, 0, width, 0, 0, width, height);
                boolean[] foreground = new boolean[rgb.length];
                boolean[] candidate = new boolean[rgb.length];
                List<float[]> pants = new ArrayList<>();
                List<GarmentPart> sleeveParts = new ArrayList<>();
                float[] dominant = null;
                Rect bodyBounds = null;
                int largest = 0;
                for (float[] row : rows) {
                    if (row[4] < 0.25f || row[5] < 0 || row[5] >= TYPES.length
                            || row[2] <= row[0] || row[3] <= row[1]) continue;
                    java.util.Arrays.fill(candidate, false);
                    Rect bounds = hasLongSleeves(row) ? new Rect() : null;
                    int count = addMask(proto, row, width, height, w, h, dx, dy, rgb, candidate, bounds);
                    if (count > 0 && isPants(row)) pants.add(row);
                    if (count > 0 && bounds != null) sleeveParts.add(new GarmentPart(row, bounds));
                    if (count > largest) {
                        largest = count;
                        dominant = row;
                        bodyBounds = bounds;
                        boolean[] previous = foreground;
                        foreground = candidate;
                        candidate = previous;
                    }
                }
                if (dominant == null) return null;
                if (isPants(dominant)) {
                    for (float[] part : pants) {
                        if (part != dominant && joinsPants(dominant, part)) {
                            addMask(proto, part, width, height, w, h, dx, dy, rgb, foreground, null);
                        }
                    }
                } else if (bodyBounds != null) {
                    final float[] body = dominant;
                    final Rect bounds = bodyBounds;
                    sleeveParts.removeIf(part -> part.row() == body || part.row()[5] != body[5]
                            || !couldBeSleeve(bounds, part.bounds()));
                    // An upper sleeve may connect its cuff to the body in the next pass.
                    boolean joined;
                    int radius = Math.max(1, Math.round(1 / scale));
                    do {
                        joined = false;
                        for (java.util.Iterator<GarmentPart> it = sleeveParts.iterator(); it.hasNext();) {
                            GarmentPart part = it.next();
                            java.util.Arrays.fill(candidate, false);
                            addMask(proto, part.row(), width, height, w, h, dx, dy, rgb, candidate, null);
                            if (touchesMask(part.bounds(), candidate, foreground, width, height, radius)) {
                                Rect area = part.bounds();
                                for (int y = area.top; y < area.bottom; y++) {
                                    for (int x = area.left; x < area.right; x++) {
                                        int i = y * width + x;
                                        foreground[i] |= candidate[i];
                                    }
                                }
                                it.remove();
                                joined = true;
                            }
                        }
                    } while (joined && !sleeveParts.isEmpty());
                }
                int left = width, top = height, right = -1, bottom = -1;
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = y * width + x;
                        if (!foreground[i]) {
                            rgb[i] = 0;
                            continue;
                        }
                        left = Math.min(left, x);
                        top = Math.min(top, y);
                        right = Math.max(right, x);
                        bottom = Math.max(bottom, y);
                    }
                }
                if (right - left < 4 || bottom - top < 4) return null;
                Bitmap cutout = Bitmap.createBitmap(rgb, top * width + left, width,
                        right - left + 1, bottom - top + 1, Bitmap.Config.ARGB_8888);
                return new Detection(cutout, TYPES[(int) dominant[5]], dominant[4], left, top);
            }
        }

        private static boolean isPants(float[] box) {
            return (int) box[5] == 6 || (int) box[5] == 7;
        }

        private static boolean hasLongSleeves(float[] box) {
            return (int) box[5] == 1 || (int) box[5] == 3 || (int) box[5] == 10;
        }

        private static boolean couldBeSleeve(Rect body, Rect part) {
            float width = body.width(), height = body.height();
            boolean atSide = part.exactCenterX() <= body.left + .25f * width
                    || part.exactCenterX() >= body.right - .25f * width;
            return atSide && part.width() <= .45f * width && part.height() >= part.width()
                    && part.top >= body.top && part.top <= body.bottom
                    && part.bottom <= body.bottom + .35f * height;
        }

        private static boolean touchesMask(Rect area, boolean[] part, boolean[] garment,
                                           int width, int height, int radius) {
            // Require contact along several pixels, allowing for rounding at the mask edge.
            int remaining = Math.max(2, area.width() / 5);
            for (int y = area.top; y < area.bottom; y++) {
                for (int x = area.left; x < area.right; x++) {
                    int i = y * width + x;
                    if (part[i] && !garment[i] && nearMask(garment, width, height, x, y, radius)
                            && --remaining == 0) return true;
                }
            }
            return false;
        }

        private static boolean nearMask(boolean[] mask, int width, int height, int x, int y, int radius) {
            int left = Math.max(0, x - radius), right = Math.min(width - 1, x + radius);
            int bottom = Math.min(height - 1, y + radius);
            for (int row = Math.max(0, y - radius); row <= bottom; row++) {
                for (int column = left; column <= right; column++) {
                    if (mask[row * width + column]) return true;
                }
            }
            return false;
        }

        private static boolean joinsPants(float[] first, float[] second) {
            float w1 = first[2] - first[0], w2 = second[2] - second[0];
            float h1 = first[3] - first[1], h2 = second[3] - second[1];
            float overlapX = Math.min(first[2], second[2]) - Math.max(first[0], second[0]);
            float overlapY = Math.min(first[3], second[3]) - Math.max(first[1], second[1]);
            float minWidth = Math.min(w1, w2), minHeight = Math.min(h1, h2);

            // An overlapping detection can supply missing fabric within the same garment.
            if (overlapX >= .35f * minWidth && overlapY >= .45f * minHeight) return true;

            // Two narrow, similarly tall detections can be the left and right legs.
            if (w1 <= .55f * h1 && w2 <= .55f * h2
                    && minHeight >= .72f * Math.max(h1, h2)
                    && overlapY >= .80f * minHeight && overlapX >= -.30f * minWidth) return true;

            // Waist and leg detections can also be split along the vertical axis.
            return overlapX >= .75f * Math.max(w1, w2) && overlapY >= -.10f * minWidth;
        }

        private int addMask(float[][][] proto, float[] box, int w, int h,
                            int scaledWidth, int scaledHeight, int dx, int dy,
                            int[] rgb, boolean[] foreground, Rect bounds) {
            int pw = proto[0][0].length, ph = proto[0].length;
            float[] mask = new float[pw * ph];
            for (int c = 0; c < proto.length; c++) {
                for (int y = 0; y < ph; y++) for (int x = 0; x < pw; x++) {
                    mask[y * pw + x] += box[c + 6] * proto[c][y][x];
                }
            }
            int count = 0;
            for (int y = 0; y < h; y++) {
                float iy = dy + (y + .5f) * scaledHeight / h;
                float py = Math.max(0, Math.min(ph - 1, iy * ph / SIZE - .5f));
                int y0 = (int) py, y1 = Math.min(y0 + 1, ph - 1);
                for (int x = 0; x < w; x++) {
                    float ix = dx + (x + .5f) * scaledWidth / w;
                    int i = y * w + x;
                    if (ix < box[0] || ix >= box[2] || iy < box[1] || iy >= box[3]) {
                        continue;
                    }
                    float px = Math.max(0, Math.min(pw - 1, ix * pw / SIZE - .5f));
                    int x0 = (int) px, x1 = Math.min(x0 + 1, pw - 1);
                    float m0 = lerp(mask[y0 * pw + x0], mask[y0 * pw + x1], px - x0);
                    float m1 = lerp(mask[y1 * pw + x0], mask[y1 * pw + x1], px - x0);
                    if (lerp(m0, m1, py - y0) > 0 && Color.alpha(rgb[i]) > 0) {
                        foreground[i] = true;
                        if (bounds != null) bounds.union(x, y, x + 1, y + 1);
                        count++;
                    }
                }
            }
            return count;
        }

        private float lerp(float a, float b, float t) { return a + (b - a) * t; }
    }

    static double[] lab(int rgb) {
        double r = linear(Color.red(rgb)), g = linear(Color.green(rgb)), b = linear(Color.blue(rgb));
        double x = labCurve((.4124564 * r + .3575761 * g + .1804375 * b) / .95047);
        double y = labCurve(.2126729 * r + .7151522 * g + .0721750 * b);
        double z = labCurve((.0193339 * r + .1191920 * g + .9503041 * b) / 1.08883);
        return new double[]{116 * y - 16, 500 * (x - y), 200 * (y - z)};
    }

    private static double linear(int component) {
        double c = component / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }

    private static double labCurve(double v) { return v > .008856 ? Math.cbrt(v) : 7.787 * v + 16.0 / 116; }

    static double distance(double[] a, double[] b) {
        return Math.pow(a[0] - b[0], 2) + Math.pow(a[1] - b[1], 2) + Math.pow(a[2] - b[2], 2);
    }

    static String colorName(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        double[] lab = lab(color);
        double lightness = lab[0], chroma = Math.hypot(lab[1], lab[2]);
        float hue = hsv[0];
        if (lightness < 24 && chroma < 12) return "黑色";
        if (chroma < 9) return lightness >= 86 ? "白色" : "灰色";
        if (hue >= 20 && hue < 72 && lightness >= 68 && chroma < 31) return "米色";
        if (hue >= 12 && hue < 62 && lightness < 57) return "棕色";
        if (hue < 18 || hue >= 345) return lightness > 65 && hsv[1] < .55 ? "粉色" : "红色";
        if (hue < 46) return "橙色";
        if (hue < 72) return "黄色";
        if (hue < 167) return "绿色";
        if (hue < 200) return "青色";
        if (hue < 253) return "蓝色";
        if (hue < 302) return "紫色";
        return "粉色";
    }

    static boolean matches(int base, int candidate, int direction) {
        float[] a = new float[3], b = new float[3];
        Color.colorToHSV(base, a);
        Color.colorToHSV(candidate, b);
        boolean neutral = b[1] < .17 || b[2] < .20;
        boolean baseNeutral = a[1] < .17 || a[2] < .20;
        float d = Math.abs(a[0] - b[0]);
        d = Math.min(d, 360 - d);
        return switch (direction) {
            case 0 -> baseNeutral ? neutral : !neutral && d <= 18;
            case 1 -> !baseNeutral && !neutral && d > 18 && d <= 55;
            case 2 -> !baseNeutral && !neutral && d >= 145;
            case 3 -> neutral || colorName(candidate).equals("米色");
            default -> true;
        };
    }

    private static final Pattern MATERIALS = Pattern.compile(
            "(?i)(?:[0-9]{1,3}(?:[.．][0-9]+)?\\s*[%％]\\s*(?:棉|羊毛|羊绒|桑蚕丝|涤纶|聚酯纤维|锦纶|氨纶|粘纤|粘胶纤维|亚麻|腈纶|莫代尔|莱赛尔|cotton|polyester|nylon|wool|elastane|linen|silk|viscose))|(?:(?:聚酯纤维|粘胶纤维|桑蚕丝|莱赛尔|莫代尔|羊绒|羊毛|腈纶|涤纶|锦纶|氨纶|亚麻|棉|cotton|polyester|nylon|wool|elastane|linen|silk|viscose)\\s*[0-9]{1,3}(?:[.．][0-9]+)?\\s*[%％])");

    static String material(String text) {
        List<String> items = new ArrayList<>();
        Matcher match = MATERIALS.matcher(text);
        while (match.find()) {
            String value = match.group().replaceAll("\\s+", " ").trim();
            if (!items.contains(value)) items.add(value);
        }
        if (!items.isEmpty()) return String.join(" / ", items);
        return field(text, "(?:面料成分|材质|面料|成分|fabric|material|composition)");
    }

    static String brand(String text) { return field(text, "(?:品牌|brand)"); }

    private static String field(String text, String key) {
        Matcher m = Pattern.compile("(?im)^\\h*" + key + "(?:\\h*[:：]\\s*|\\h+|\\h*\\R\\h*)([^\\r\\n]{1,64})").matcher(text);
        return m.find() ? m.group(1).trim() : "";
    }

    static String careText(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\R")) {
            if (Pattern.compile("(?i)洗|漂白|烘干|干燥|晾干|熨|干洗|wash|bleach|dry|iron|[0-9]\\s*[°℃]").matcher(line).find()) {
                lines.add(line.trim());
            }
        }
        return String.join("\n", lines);
    }

    static String categoryFromText(String text) {
        for (String key : new String[]{"T 恤", "T恤", "衬衫", "牛仔裤", "工装裤", "夹克", "半裙", "连衣裙", "短裤", "外套", "鞋"}) {
            if (text.contains(key)) return key.equals("T恤") ? "T 恤" : key.equals("鞋") ? "鞋子" : key;
        }
        return "";
    }
}
