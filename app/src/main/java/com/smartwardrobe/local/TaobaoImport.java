package com.smartwardrobe.local;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

final class TaobaoImport {
    private static final Pattern LINK = Pattern.compile("https://[^\\s\\p{IsHan}「」《》<>\"“”]+");
    private static final Pattern NAME = Pattern.compile("「([^」]{1,300})」");
    private static final Pattern SHORT_TARGET = Pattern.compile("\\bvar\\s+url\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern ITEM_ID = Pattern.compile("[0-9]{1,24}");
    private static final int MAX_SHORT_PAGE_BYTES = 256 * 1024;
    private static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;

    private static final String READ_PAGE = """
        (() => {
            const candidates = [];
            const add = (value, score) => {
                if (!value) return;
                try {
                    const url = new URL(value, document.baseURI);
                    if (url.protocol === 'https:' &&
                        /(^|\\.)alicdn\\.com$/i.test(url.hostname) &&
                        /^\\/(imgextra|bao\\/uploaded)\\//i.test(url.pathname)) {
                        candidates.push({url: url.href, score});
                    }
                } catch (_) {}
            };
            add(document.querySelector('meta[property="og:image"]')?.content, 700);
            for (const image of document.images) {
                const box = image.getBoundingClientRect();
                const visible = box.width >= 140 && box.height >= 140;
                const large = image.naturalWidth >= 300 && image.naturalHeight >= 300;
                if (!visible && !large) continue;
                const position = box.top + scrollY;
                const score = visible && position < 1100 ? 950 - Math.max(0, position) / 4 : 350;
                add(image.currentSrc || image.src, score);
            }
            for (const node of document.querySelectorAll('.js-image-zoom__zoomed-image, [style*="background-image"]')) {
                const box = node.getBoundingClientRect();
                const zoom = node.matches('.js-image-zoom__zoomed-image');
                if (!zoom && (box.width < 140 || box.height < 140)) continue;
                const match = getComputedStyle(node).backgroundImage.match(/^url\\(["']?(.*?)["']?\\)$/);
                if (match) add(match[1], zoom ? 600 : 500);
            }
            candidates.sort((a, b) => b.score - a.score);
            const title = document.querySelector('meta[property="og:title"]')?.content
                || document.querySelector('h1')?.textContent || document.title;
            return {title, urls: [...new Set(candidates.map(x => x.url))].slice(0, 24)};
        })()
        """;

    record Result(String name, Bitmap image) {}
    private record Input(Uri url, String name) {}
    private record Product(String name, List<Uri> images) {}

    private final MainActivity ui;
    private final Consumer<Result> onImport;

    TaobaoImport(MainActivity ui, Consumer<Result> onImport) {
        this.ui = ui;
        this.onImport = onImport;
    }

    void start() {
        EditText input = new EditText(ui);
        input.setHint("淘宝分享文案、商品链接或图片地址");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(3);
        AlertDialog dialog = new AlertDialog.Builder(ui)
                .setTitle("从淘宝导入")
                .setMessage("可粘贴整段分享文案。商品页若提示登录，请在淘宝页面手动登录。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("用图片地址", null)
                .setPositiveButton("解析链接", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                String raw = input.getText().toString();
                Input parsed = parseInput(raw);
                dialog.dismiss();
                imageFallback(readName(raw), parsed != null && isProduct(parsed.url) ? parsed.url : null);
            });
            Button proceed = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            input.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable text) {
                    Input parsed = parseInput(text.toString());
                    if (parsed == null) proceed.setText("解析链接");
                    else proceed.setText(isImage(parsed.url) ? "预览图片" : "打开商品页");
                }
            });
            proceed.setOnClickListener(view -> {
                Input parsed = parseInput(input.getText().toString());
                if (parsed == null) {
                    input.setError("请输入淘宝商品链接或阿里 CDN 商品图片地址");
                    return;
                }
                dialog.dismiss();
                if (isImage(parsed.url)) preview(new Product(parsed.name, List.of(parsed.url)), 0, null);
                else openProduct(parsed);
            });
        });
        dialog.show();
    }

    private void openProduct(Input input) {
        if (!isShortLink(input.url)) {
            Uri preferred = preferredProduct(input.url);
            browse(preferred == null ? input : new Input(preferred, input.name));
            return;
        }
        String userAgent;
        try { userAgent = WebSettings.getDefaultUserAgent(ui); }
        catch (RuntimeException unavailable) {
            browse(input);
            return;
        }
        ui.task("正在解析淘宝商品链接…", () -> {
            try { return resolveShortLink(input.url, userAgent); }
            catch (IOException failure) { return null; }
        }, direct -> browse(direct == null ? input : new Input(direct, input.name)));
    }

    private static boolean isShortLink(Uri url) {
        String host = url.getHost();
        String path = url.getPath();
        return https(url) && ("e.tb.cn".equalsIgnoreCase(host) || "m.tb.cn".equalsIgnoreCase(host))
                && path != null && path.startsWith("/h.");
    }

    private static Uri preferredProduct(Uri url) {
        String host = url.getHost();
        String path = url.getPath();
        boolean desktop = "item.taobao.com".equalsIgnoreCase(host) && "/item.htm".equals(path);
        boolean legacy = "main.m.taobao.com".equalsIgnoreCase(host) && "/detail/index.html".equals(path);
        if (!https(url) || (!desktop && !legacy)) return null;
        String id = url.getQueryParameter("id");
        return id != null && ITEM_ID.matcher(id).matches()
                ? Uri.parse("https://pages-g.m.taobao.com/wow/z/app/detail-next/item/index").buildUpon()
                        .appendQueryParameter("id", id).appendQueryParameter("x-ssr", "true").build()
                : null;
    }

    private static Uri resolveShortLink(Uri start, String userAgent) throws IOException {
        URL url = new URL(start.toString());
        for (int hop = 0; hop < 4; hop++) {
            Uri address = Uri.parse(url.toString());
            Uri direct = preferredProduct(address);
            if (direct != null || !isShortLink(address)) return direct;
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", userAgent);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) return null;
                    url = new URL(url, location);
                    continue;
                }
                if (status != 200) return null;
                String html = new String(readBody(connection, MAX_SHORT_PAGE_BYTES), StandardCharsets.UTF_8);
                Matcher target = SHORT_TARGET.matcher(html);
                return target.find() ? preferredProduct(Uri.parse(target.group(1).replace("&amp;", "&"))) : null;
            } finally { connection.disconnect(); }
        }
        return null;
    }

    private static Input parseInput(String raw) {
        Matcher links = LINK.matcher(raw.replace("\\:", ":").replace("\\/", "/").replace("\\.", ".")
                .replace("\\&", "&").replace("\\_", "_").replace("&amp;", "&"));
        String name = readName(raw);
        while (links.find()) {
            Uri url = Uri.parse(links.group().replaceFirst("[,，。；]+$", ""));
            if (isProduct(url) || isImage(url)) return new Input(url, name);
        }
        return null;
    }

    private static String readName(String raw) {
        Matcher title = NAME.matcher(raw);
        return title.find() ? title.group(1).trim() : "";
    }

    private void imageFallback(String name, Uri productUrl) {
        EditText field = new EditText(ui);
        field.setHint("粘贴 img.alicdn.com 等图片地址");
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        AlertDialog.Builder panel = new AlertDialog.Builder(ui)
                .setTitle("用淘宝商品图片地址")
                .setMessage("在手机浏览器打开商品，把主图单独打开，再从地址栏复制网址。回到衣橱粘贴。"
                        + (name.isEmpty() ? "" : "\n已保留商品名：" + name))
                .setView(field)
                .setNegativeButton("取消", null)
                .setPositiveButton("预览图片", null);
        if (productUrl != null) panel.setNeutralButton("打开商品链接", null);
        AlertDialog dialog = panel.create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                Input parsed = parseInput(field.getText().toString());
                if (parsed == null || !isImage(parsed.url)) {
                    field.setError("请粘贴商品图片的完整网址");
                    return;
                }
                dialog.dismiss();
                preview(new Product(name, List.of(parsed.url)), 0, null);
            });
            if (productUrl != null) dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                Intent open = new Intent(Intent.ACTION_VIEW, productUrl).addCategory(Intent.CATEGORY_BROWSABLE);
                try { ui.startActivity(open); }
                catch (ActivityNotFoundException failure) {
                    field.setError("请用手机浏览器手动打开商品链接");
                }
            });
        });
        dialog.show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void browse(Input input) {
        Dialog dialog = new Dialog(ui, android.R.style.Theme_Material_Light_NoActionBar);
        LinearLayout layout = new LinearLayout(ui);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(ui);
        Button close = new Button(ui);
        close.setText("关闭");
        Button read = new Button(ui);
        read.setText("选商品图");
        Button fallback = new Button(ui);
        fallback.setText("用图片地址");
        toolbar.addView(close);
        toolbar.addView(read, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(fallback);
        layout.addView(toolbar);
        WebView web;
        try { web = new WebView(ui); }
        catch (RuntimeException failure) {
            new AlertDialog.Builder(ui).setTitle("无法打开淘宝网页")
                    .setMessage("当前设备无法使用应用内浏览器。可在系统浏览器打开商品后，粘贴图片地址导入。")
                    .setPositiveButton("用图片地址", (d, w) -> imageFallback(input.name, input.url)).show();
            return;
        }
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        dialog.setContentView(layout);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        web.getSettings().setSaveFormData(false);
        web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() && !isTaobaoPage(request.getUrl());
            }
        });
        close.setOnClickListener(view -> dialog.dismiss());
        fallback.setOnClickListener(view -> {
            dialog.dismiss();
            imageFallback(input.name, input.url);
        });
        dialog.setOnDismissListener(ignored -> {
            web.stopLoading();
            layout.removeView(web);
            web.destroy();
        });
        Runnable finish = dialog::dismiss;
        read.setOnClickListener(view -> readImages(web, input.name, finish));
        web.setOnLongClickListener(view -> {
            WebView.HitTestResult hit = web.getHitTestResult();
            int type = hit.getType();
            Uri url = Uri.parse(hit.getExtra() == null ? "" : hit.getExtra());
            if ((type != WebView.HitTestResult.IMAGE_TYPE && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)
                    || !isImage(url) || !canRead(web)) return false;
            if (input.name.isEmpty()) {
                web.evaluateJavascript(READ_PAGE, result -> {
                    if (dialog.isShowing() && canRead(web)) {
                        Product product = product(result, "");
                        preview(new Product(product.name, List.of(url)), 0, finish);
                    }
                });
            } else preview(new Product(input.name, List.of(url)), 0, finish);
            return true;
        });
        dialog.show();
        if (dialog.getWindow() != null) dialog.getWindow().setLayout(-1, -1);
        web.loadUrl(input.url.toString());
    }

    private void readImages(WebView web, String name, Runnable finish) {
        if (!canRead(web)) {
            message("请先打开商品详情", "如果淘宝要求登录，请手动登录并等待商品图显示。");
            return;
        }
        web.evaluateJavascript(READ_PAGE, result -> {
            if (!web.isAttachedToWindow() || !canRead(web)) return;
            Product product = product(result, name);
            if (product.images.isEmpty()) {
                message("当前页面没有可导入的商品图",
                        "等主图加载后重试，也可以长按商品图，或复制图片地址后重新导入。");
            } else preview(product, 0, finish);
        });
    }

    private static boolean canRead(WebView web) {
        String url = web.getUrl();
        return url != null && isProduct(Uri.parse(url));
    }

    private static Product product(String result, String fallbackName) {
        String name = fallbackName;
        LinkedHashMap<String, Uri> images = new LinkedHashMap<>();
        try {
            Object parsed = new JSONTokener(result == null ? "null" : result).nextValue();
            if (parsed instanceof JSONObject page) {
                String title = page.optString("title").replaceFirst("\\s*[-–—|｜]\\s*(手机)?(淘宝|天猫)\\s*$", "").trim();
                if (name.isEmpty() && !title.isEmpty() && !title.matches("淘宝.*|天猫.*|商品详情页?|登录.*")) {
                    name = title.length() > 300 ? title.substring(0, 300) : title;
                }
                JSONArray links = page.optJSONArray("urls");
                if (links != null) {
                    for (int i = 0; i < links.length(); i++) {
                        Uri url = Uri.parse(links.optString(i));
                        if (isImage(url)) {
                            String pathname = url.getPath();
                            String key = pathname.replaceFirst("(?i)(\\.(?:jpg|jpeg|png|webp)).*$", "$1");
                            images.putIfAbsent(key, url);
                        }
                    }
                }
            }
        } catch (org.json.JSONException ignored) {}
        return new Product(name, new ArrayList<>(images.values()));
    }

    private void preview(Product product, int index, Runnable finish) {
        Uri url = product.images.get(index);
        ui.task("正在下载商品图…", () -> {
            try { return download(url); }
            catch (IOException failure) { return null; }
        }, bitmap -> {
            int total = product.images.size();
            AlertDialog.Builder panel = new AlertDialog.Builder(ui)
                    .setTitle(total == 1 ? "商品图片" : "商品图片 " + (index + 1) + " / " + total)
                    .setNegativeButton("取消", null);
            if (bitmap == null) {
                panel.setMessage("图片下载或解码失败。可返回网页，或尝试其他图片。");
            } else {
                ImageView image = new ImageView(ui);
                image.setImageBitmap(bitmap);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout content = new LinearLayout(ui);
                content.addView(image, new LinearLayout.LayoutParams(-1, ui.dp(290)));
                panel.setView(content).setPositiveButton("使用此图", (dialog, which) -> {
                    if (finish != null) finish.run();
                    onImport.accept(new Result(product.name, bitmap));
                });
            }
            if (total > 1) panel.setNeutralButton("下一张", (dialog, which) ->
                    preview(product, (index + 1) % total, finish));
            panel.show();
        });
    }

    private void message(String title, String detail) {
        new AlertDialog.Builder(ui).setTitle(title).setMessage(detail)
                .setPositiveButton("知道了", null).show();
    }

    private static boolean hostIs(String host, String domain) {
        return host != null && (host.equalsIgnoreCase(domain)
                || host.toLowerCase(Locale.ROOT).endsWith("." + domain));
    }

    private static boolean https(Uri url) {
        return "https".equalsIgnoreCase(url.getScheme()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443);
    }

    private static boolean isTaobaoPage(Uri url) {
        String host = url.getHost();
        return https(url) && (hostIs(host, "taobao.com") || hostIs(host, "tmall.com")
                || hostIs(host, "tb.cn"));
    }

    private static boolean isProduct(Uri url) {
        String host = url.getHost();
        String path = url.getPath();
        if (!isTaobaoPage(url) || host == null || path == null) return false;
        return host.equalsIgnoreCase("e.tb.cn") || host.equalsIgnoreCase("m.tb.cn")
                || path.equals("/item.htm") || path.equals("/awp/core/detail.htm")
                || path.equals("/detail/detail.html")
                || (host.equalsIgnoreCase("main.m.taobao.com") && path.equals("/detail/index.html"))
                || path.equals("/wow/z/app/detail-next/item/index");
    }

    private static boolean isImage(Uri url) {
        String path = url.getPath();
        return https(url) && hostIs(url.getHost(), "alicdn.com") && path != null
                && (path.startsWith("/imgextra/") || path.startsWith("/bao/uploaded/"));
    }

    private static Bitmap download(Uri start) throws IOException {
        URL url = new URL(start.toString());
        for (int hop = 0; hop < 5; hop++) {
            if (!isImage(Uri.parse(url.toString()))) throw new IOException("Unexpected image URL");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(15000);
            try {
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String next = connection.getHeaderField("Location");
                    if (next == null) throw new IOException("Missing image redirect");
                    url = new URL(url, next);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("Unexpected image response");
                }
                return decode(readBody(connection, MAX_IMAGE_BYTES));
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many image redirects");
    }

    private static byte[] readBody(HttpsURLConnection connection, int limit) throws IOException {
        if (connection.getContentLengthLong() > limit) throw new IOException("Response too large");
        try (InputStream in = connection.getInputStream();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Response too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static Bitmap decode(byte[] bytes) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth < 1 || options.outHeight < 1) throw new IOException("Invalid image");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 2048
                || options.outHeight / options.inSampleSize > 2048) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (bitmap == null) throw new IOException("Undecodable image");
        return bitmap;
    }
}
