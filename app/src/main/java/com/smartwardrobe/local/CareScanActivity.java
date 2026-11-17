package com.smartwardrobe.local;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.camera.core.CameraSelector;
import androidx.camera.core.Camera;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.core.UseCaseGroup;
import androidx.camera.core.ViewPort;
import androidx.camera.core.resolutionselector.ResolutionSelector;
import androidx.camera.core.resolutionselector.ResolutionStrategy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.smartwardrobe.local.MainActivity.INK;
import static com.smartwardrobe.local.MainActivity.MUTED;
import static com.smartwardrobe.local.MainActivity.SAND;
import static com.smartwardrobe.local.MainActivity.STROKE;

public final class CareScanActivity extends FragmentActivity {
    static final String GALLERY = "gallery", PHOTO = "photo", HITS = "hits";
    private static final int CAMERA_PERMISSION = 1;
    private static final long HISTORY_TIMEOUT_MS = 3000;
    private static final long MODEL_RETRY_MS = 1250;
    private final ExecutorService analyzer = Executors.newSingleThreadExecutor();
    private final ArrayDeque<List<ImageAnalysis.SymbolHit>> recent = new ArrayDeque<>(2);
    private YoloCareSymbols detector;
    private PreviewView viewfinder;
    private CareScanOverlay overlay;
    private View previewCover;
    private ProcessCameraProvider provider;
    private Preview preview;
    private androidx.camera.core.ImageAnalysis analysis;
    private TextView status, confirm, torch, zoom, focusFeedback;
    private Bitmap reviewFrame;
    private List<ImageAnalysis.SymbolHit> reviewHits = Collections.emptyList();
    private volatile boolean stopping;
    private volatile boolean streaming;
    private volatile int scanGeneration;
    private boolean binding;
    private int analyzedGeneration = -1;
    private int focusRequest;
    private long lastSample;
    private long retryAt;
    private final Runnable hideFocusFeedback = () -> {
        focusFeedback.setVisibility(View.INVISIBLE);
        overlay.hideFocusMarker();
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        detector = new YoloCareSymbols(this);
        buildView();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (state == null) requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION);
            else status.setText("相机未授权");
        }
    }

    private void buildView() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.WHITE);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.WHITE);
        page.setPadding(dp(20), dp(8), dp(20), dp(24));
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            int left = page.getPaddingLeft(), top = page.getPaddingTop(), bottom = page.getPaddingBottom();
            page.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(left + bars.left, top + bars.top, left + bars.right, bottom + bars.bottom);
                return insets;
            });
        }
        scroll.addView(page);
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView back = button("‹", this::finish);
        back.setContentDescription("返回");
        back.setTextSize(24);
        header.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView title = text("扫描洗标", 24);
        title.setTypeface(Typeface.create("serif", Typeface.BOLD));
        title.setAccessibilityHeading(true);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(header);

        FrameLayout preview = new FrameLayout(this) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int height = Math.min(dp(236), Math.round(MeasureSpec.getSize(widthSpec) * 2f / 3));
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
            }
        };
        preview.setBackground(surface(SAND, 12, 0));
        preview.setClipToOutline(true);
        viewfinder = new PreviewView(this);
        viewfinder.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        viewfinder.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        viewfinder.setBackgroundColor(Color.TRANSPARENT);
        viewfinder.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        });
        preview.addView(viewfinder, new FrameLayout.LayoutParams(-1, -1));
        previewCover = new View(this);
        previewCover.setBackgroundColor(SAND);
        preview.addView(previewCover, new FrameLayout.LayoutParams(-1, -1));
        overlay = new CareScanOverlay(this);
        overlay.setVisibility(View.INVISIBLE);
        preview.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        focusFeedback = text("", 13);
        focusFeedback.setGravity(Gravity.CENTER);
        focusFeedback.setPadding(dp(10), dp(5), dp(10), dp(5));
        focusFeedback.setBackground(surface(0xf5ffffff, 6, 0));
        focusFeedback.setVisibility(View.INVISIBLE);
        focusFeedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams focusParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        focusParams.topMargin = dp(8);
        preview.addView(focusFeedback, focusParams);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(-1, -2);
        previewParams.topMargin = dp(16);
        page.addView(preview, previewParams);

        LinearLayout scanInfo = new LinearLayout(this);
        scanInfo.setGravity(Gravity.CENTER_VERTICAL);
        status = text("正在打开相机", 14);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setTextColor(MUTED);
        status.setMinHeight(dp(48));
        status.setPadding(0, dp(8), dp(12), dp(8));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        scanInfo.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        zoom = secondary("1×", 14);
        zoom.setVisibility(View.GONE);
        scanInfo.addView(zoom, new LinearLayout.LayoutParams(dp(48), dp(48)));
        torch = secondary("补光", 14);
        torch.setVisibility(View.GONE);
        LinearLayout.LayoutParams torchParams = new LinearLayout.LayoutParams(dp(56), dp(48));
        torchParams.leftMargin = dp(8);
        scanInfo.addView(torch, torchParams);
        LinearLayout.LayoutParams infoParams = new LinearLayout.LayoutParams(-1, -2);
        infoParams.topMargin = dp(8);
        infoParams.bottomMargin = dp(12);
        page.addView(scanInfo, infoParams);

        LinearLayout actions = new LinearLayout(this);
        TextView gallery = secondary("相册", 15);
        gallery.setOnClickListener(v -> {
            setResult(RESULT_OK, new Intent().putExtra(GALLERY, true));
            finish();
        });
        actions.addView(gallery, new LinearLayout.LayoutParams(0, dp(48), 1));
        confirm = button("核对结果", this::finishWithFrame);
        confirm.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30ffffff), surface(INK, 6, 0), null));
        confirm.setTextColor(Color.WHITE);
        confirm.setTypeface(null, Typeface.BOLD);
        confirm.setEnabled(false);
        confirm.setAlpha(.4f);
        LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(48), 1.5f);
        confirmParams.leftMargin = dp(10);
        actions.addView(confirm, confirmParams);
        page.addView(actions);
        setContentView(scroll);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private TextView text(String label, int size) {
        TextView view = new TextView(this);
        view.setText(label);
        view.setTextColor(INK);
        view.setTextSize(size);
        return view;
    }

    private TextView button(String label, Runnable action) {
        TextView view = secondary(label, 16);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private TextView secondary(String label, int size) {
        Button view = new Button(this, null, 0);
        view.setAllCaps(false);
        view.setText(label);
        view.setTextSize(size);
        view.setTextColor(INK);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x16000000), surface(Color.WHITE, 6, STROKE), null));
        return view;
    }

    private GradientDrawable surface(int color, int corner, int border) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(corner));
        if (border != 0) drawable.setStroke(dp(1), border);
        return drawable;
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != CAMERA_PERMISSION || isFinishing()) return;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startCamera();
        else status.setText("相机未授权 · 可从相册导入");
    }

    private void startCamera() {
        ViewPort viewport = viewfinder.getViewPort();
        if (binding || stopping || viewport == null) return;
        binding = true;
        var future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            if (stopping || isFinishing()) return;
            try {
                provider = future.get();
                int rotation = viewport.getRotation();
                preview = new Preview.Builder().setTargetRotation(rotation).build();
                preview.setSurfaceProvider(viewfinder.getSurfaceProvider());
                analysis = new androidx.camera.core.ImageAnalysis.Builder()
                        .setResolutionSelector(new ResolutionSelector.Builder()
                                .setResolutionStrategy(new ResolutionStrategy(new Size(1280, 960),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)).build())
                        .setTargetRotation(rotation)
                        .setOutputImageFormat(androidx.camera.core.ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .setBackpressureStrategy(androidx.camera.core.ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                analysis.setAnalyzer(analyzer, this::analyze);
                UseCaseGroup group = new UseCaseGroup.Builder().setViewPort(viewport)
                        .addUseCase(preview).addUseCase(analysis).build();
                Camera camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, group);
                setCameraControls(camera);
                if (camera.getCameraInfo().hasFlashUnit()) {
                    torch.setVisibility(View.VISIBLE);
                    torch.setFocusable(true);
                    camera.getCameraInfo().getTorchState().observe(this, state ->
                            torch.setText(state == androidx.camera.core.TorchState.ON ? "关补光" : "补光"));
                    torch.setOnClickListener(v -> camera.getCameraControl().enableTorch(
                            !Integer.valueOf(androidx.camera.core.TorchState.ON).equals(camera.getCameraInfo().getTorchState().getValue())));
                }
                viewfinder.getPreviewStreamState().observe(this, state -> {
                    streaming = state == PreviewView.StreamState.STREAMING;
                    previewCover.setVisibility(streaming ? View.GONE : View.VISIBLE);
                    overlay.setVisibility(streaming ? View.VISIBLE : View.INVISIBLE);
                    resetScan();
                    status.setText(streaming ? "扫描中" : "正在打开相机");
                    if (streaming) focus(camera, viewfinder.getWidth() / 2f, viewfinder.getHeight() / 2f, false);
                });
            } catch (Exception | LinkageError e) {
                Log.w("SmartWardrobe", "Open care camera", e);
                if (analysis != null) analysis.clearAnalyzer();
                status.setText("相机不可用 · 可从相册导入");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void setCameraControls(Camera camera) {
        float[] tap = {-1, -1};
        viewfinder.setContentDescription("相机预览，点按对焦");
        viewfinder.setFocusable(true);
        viewfinder.setOnClickListener(v -> {
            float x = tap[0] < 0 ? v.getWidth() / 2f : tap[0];
            float y = tap[1] < 0 ? v.getHeight() / 2f : tap[1];
            tap[0] = tap[1] = -1;
            focus(camera, x, y, true);
        });
        viewfinder.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                tap[0] = event.getX();
                tap[1] = event.getY();
                view.performClick();
            }
            return true;
        });
        var zoomState = camera.getCameraInfo().getZoomState();
        zoomState.observe(this, state -> {
            zoom.setVisibility(state.getMaxZoomRatio() > 1.15f ? View.VISIBLE : View.GONE);
            float value = state.getZoomRatio();
            String ratio = value % 1 == 0 ? (int) value + "×" : String.format(Locale.ROOT, "%.1f×", value);
            zoom.setText(ratio);
            zoom.setContentDescription(ratio + "，切换变焦");
        });
        zoom.setFocusable(true);
        zoom.setOnClickListener(v -> {
            var state = zoomState.getValue();
            if (state == null) return;
            float target = state.getZoomRatio() > 1.1f ? 1f : Math.min(2f, state.getMaxZoomRatio());
            resetScan();
            camera.getCameraControl().setZoomRatio(target);
        });
    }

    private void focus(Camera camera, float x, float y, boolean show) {
        if (!streaming) return;
        var point = viewfinder.getMeteringPointFactory().createPoint(x, y, .16f);
        var control = camera.getCameraControl();
        var info = camera.getCameraInfo();
        var af = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build();
        if (!info.isFocusMeteringSupported(af)) {
            if (show) {
                focusRequest++;
                overlay.hideFocusMarker();
                showFocusFeedback("当前镜头不支持点按对焦");
            }
            return;
        }
        int request = ++focusRequest;
        var action = new FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF | FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build();
        if (show) {
            resetScan();
            overlay.focus(x, y);
            showFocusFeedback("对焦中");
        }
        var result = control.startFocusAndMetering(action);
        if (!show) return;
        result.addListener(() -> {
            if (stopping || !streaming || request != focusRequest) return;
            boolean focused = false;
            try { focused = result.get().isFocusSuccessful(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
                Log.d("SmartWardrobe", "Care focus did not complete", e);
            }
            overlay.focusResult(focused);
            showFocusFeedback(focused ? "已对焦" : "未能合焦 · 稍微拉远");
        }, ContextCompat.getMainExecutor(this));
    }

    private void showFocusFeedback(String message) {
        focusFeedback.setText(message);
        focusFeedback.setVisibility(View.VISIBLE);
        focusFeedback.removeCallbacks(hideFocusFeedback);
        focusFeedback.postDelayed(hideFocusFeedback, 2200);
    }

    private void analyze(ImageProxy proxy) {
        int generation = scanGeneration;
        long now = SystemClock.elapsedRealtime();
        Bitmap frame = null;
        try {
            if (stopping || !streaming || now < retryAt) return;
            if (generation != analyzedGeneration || now - lastSample > HISTORY_TIMEOUT_MS) recent.clear();
            analyzedGeneration = generation;
            lastSample = now;
            frame = bitmap(proxy);
            List<ImageAnalysis.SymbolHit> hits = detector.detect(frame);
            List<ImageAnalysis.SymbolHit> stable = hits.stream().filter(hit -> recent.stream().anyMatch(old -> old.stream()
                    .anyMatch(previous -> agrees(hit, previous)))).collect(Collectors.toList());
            if (recent.size() == 2) recent.removeFirst();
            recent.addLast(hits);
            Bitmap selected = stable.isEmpty() ? null : frame;
            if (selected != null) frame = null;
            runOnUiThread(() -> showScan(generation, selected, stable, false));
        } catch (Exception | LinkageError e) {
            Log.w("SmartWardrobe", "Live care analysis", e);
            recent.clear();
            retryAt = SystemClock.elapsedRealtime() + MODEL_RETRY_MS;
            runOnUiThread(() -> showScan(generation, null, Collections.emptyList(), true));
        } finally {
            if (frame != null) frame.recycle();
            proxy.close();
        }
    }

    private void showScan(int generation, Bitmap frame, List<ImageAnalysis.SymbolHit> hits, boolean failed) {
        if (stopping || isFinishing() || !streaming || generation != scanGeneration) {
            if (frame != null) frame.recycle();
            return;
        }
        setReview(frame, hits);
        String message = failed ? "正在重试识别" : hits.isEmpty() ? "扫描中" : "扫描中 · " + hits.stream()
                .map(ImageAnalysis.SymbolHit::meaning).distinct().collect(Collectors.joining(" · "));
        if (!message.contentEquals(status.getText())) status.setText(message);
    }

    private void resetScan() {
        scanGeneration++;
        setReview(null, Collections.emptyList());
        if (streaming) status.setText("扫描中");
    }

    private void setReview(Bitmap frame, List<ImageAnalysis.SymbolHit> hits) {
        if (reviewFrame != null) reviewFrame.recycle();
        reviewFrame = frame;
        reviewHits = hits;
        overlay.setHits(hits);
        confirm.setEnabled(frame != null);
        confirm.setAlpha(frame != null ? 1f : .4f);
    }

    private static boolean agrees(ImageAnalysis.SymbolHit a, ImageAnalysis.SymbolHit b) {
        if (!a.meaning().equals(b.meaning())) return false;
        RectF r = new RectF(a.bounds());
        if (!r.intersect(b.bounds())) return false;
        float area = r.width() * r.height();
        return area >= .30f * (a.bounds().width() * a.bounds().height()
                + b.bounds().width() * b.bounds().height() - area);
    }

    private static Bitmap bitmap(ImageProxy proxy) {
        // CameraX's shared viewport aligns this crop rectangle with the displayed preview.
        Rect viewport = proxy.getCropRect();
        int rotation = proxy.getImageInfo().getRotationDegrees();
        boolean sideways = rotation % 180 != 0;
        int width = Math.round(viewport.width() * (sideways ? CareScanOverlay.HEIGHT : CareScanOverlay.WIDTH));
        int height = Math.round(viewport.height() * (sideways ? CareScanOverlay.WIDTH : CareScanOverlay.HEIGHT));
        int left = viewport.left + (viewport.width() - width) / 2;
        int top = viewport.top + (viewport.height() - height) / 2;
        ImageProxy.PlaneProxy plane = proxy.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int start = buffer.position(), stride = plane.getRowStride(), pixelStride = plane.getPixelStride();
        int[] color = new int[width * height];
        byte[] row = new byte[width * pixelStride];
        for (int y = 0; y < height; y++) {
            buffer.position(start + (top + y) * stride + left * pixelStride);
            buffer.get(row);
            for (int x = 0; x < width; x++) {
                int i = x * pixelStride;
                color[y * width + x] = Color.rgb(row[i] & 255, row[i + 1] & 255, row[i + 2] & 255);
            }
        }
        Bitmap raw = Bitmap.createBitmap(color, width, height, Bitmap.Config.ARGB_8888);
        if (rotation == 0) return raw;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap upright = Bitmap.createBitmap(raw, 0, 0, width, height, matrix, true);
        if (raw != upright) raw.recycle();
        return upright;
    }

    private void finishWithFrame() {
        if (reviewFrame == null || stopping) return;
        confirm.setEnabled(false);
        File file = null;
        try {
            file = File.createTempFile("care-frame-", ".jpg", getCacheDir());
            try (FileOutputStream stream = new FileOutputStream(file)) {
                if (!reviewFrame.compress(Bitmap.CompressFormat.JPEG, 94, stream)) throw new java.io.IOException();
            }
            ArrayList<Bundle> hits = new ArrayList<>();
            for (ImageAnalysis.SymbolHit hit : reviewHits) {
                Bundle record = new Bundle();
                record.putInt("group", hit.group());
                record.putString("meaning", hit.meaning());
                record.putFloat("score", hit.confidence());
                record.putParcelable("bounds", hit.bounds());
                hits.add(record);
            }
            setResult(RESULT_OK, new Intent().putExtra(PHOTO, file.getName()).putParcelableArrayListExtra(HITS, hits));
            finish();
        } catch (java.io.IOException e) {
            Log.w("SmartWardrobe", "Save care frame", e);
            if (file != null) file.delete();
            status.setText("无法保留画面");
            confirm.setEnabled(true);
        }
    }

    @Override protected void onStop() {
        streaming = false;
        previewCover.setVisibility(View.VISIBLE);
        overlay.setVisibility(View.INVISIBLE);
        resetScan();
        focusRequest++;
        focusFeedback.removeCallbacks(hideFocusFeedback);
        hideFocusFeedback.run();
        super.onStop();
    }

    @Override protected void onDestroy() {
        stopping = true;
        if (analysis != null) analysis.clearAnalyzer();
        if (provider != null && preview != null && analysis != null) provider.unbind(preview, analysis);
        analyzer.execute(() -> {
            try { detector.close(); }
            catch (Exception e) { Log.w("SmartWardrobe", "Close care detector", e); }
        });
        analyzer.shutdown();
        super.onDestroy();
    }
}
