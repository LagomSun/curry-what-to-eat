package com.lagomsun.whattoeat;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「今天吃什么」安卓外壳
 *
 * 设计要点：
 *  1. 零第三方依赖（只用系统 Activity + WebView），避免 kotlin-stdlib 重复类问题
 *  2. 网页放在 assets/www/ 完全离线；字体也是本地的
 *  3. 大模型调用走原生 HttpURLConnection —— 网页里直接 fetch 会被 CORS 拦，
 *     而且 API Key 写在网页里等于公开泄露。这里 Key 只存手机 SharedPreferences。
 *  4. 没有配 Key 时网页自己会用内置菜谱，完全不联网，装了就能用
 */
public class MainActivity extends Activity {

    private static final String TAG = "WhatToEat";

    /** 各厂商 OpenAI 兼容端点（与网页里的 PRESETS 保持一致） */
    private static final String[][] PRESETS = {
        {"deepseek",  "https://api.deepseek.com/v1",                        "deepseek-chat"},
        {"moonshot",  "https://api.moonshot.cn/v1",                         "moonshot-v1-8k"},
        {"dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1",  "qwen-plus"},
        {"zhipu",     "https://open.bigmodel.cn/api/paas/v4",               "glm-4-flash"},
        {"openai",    "https://api.openai.com/v1",                          "gpt-4o-mini"},
        {"ollama",    "http://192.168.1.100:11434/v1",                      "qwen2.5:7b"}
    };

    private static final String SYSTEM_PROMPT =
        "你是一个懂吃、懂生活的中餐推荐官，说话有温度，也熟悉各地小吃。\n" +
        "用户每天纠结“今天吃什么”，你的任务是给出一道具体的菜，让他一眼就想吃。\n\n" +
        "规则：\n" +
        "1. 必须是一道具体、真实存在的菜。外卖场景可以推荐本地小吃、快餐套餐" +
        "（例如华莱士的香辣鸡腿堡套餐这类）、早餐（粥、包子、煎饼、馄饨、肉夹馍）、" +
        "面食、盖饭、炸鸡、卤味、麻辣烫等，越接地气越好，不要总推荐大菜\n" +
        "2. 自己做场景要选家常、买菜方便、别太费事的\n" +
        "3. 推荐语 20 字以内，要有人味、有画面感，不要用“营养均衡”“美味可口”这种套话\n" +
        "4. 给 2 个标签，每个不超过 4 个字\n" +
        "5. 配一个最贴切的 emoji（必须是单个 emoji 字符）\n" +
        "6. ing 字段：做这道菜需要的主要原料，4-6 样，每样不超过 4 个字；" +
        "点外卖的场景给空数组\n" +
        "7. pic 字段：用一句话描述这道菜“长什么样”——装在什么碗或盘里、" +
        "主要食材是什么颜色、什么角度看。这句话会直接交给画图模型，必须具体\n" +
        "8. 每次都要换新的，不要连续重复\n\n" +
        "只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块：\n" +
        "{\"n\":\"菜名\",\"e\":\"🍜\",\"t\":[\"标签1\",\"标签2\"],\"d\":\"推荐语\"," +
        "\"ing\":[\"原料1\",\"原料2\"],\"pic\":\"画面描述\"}";

    /** 本地 Ollama 不需要真 Key，但字段总得有值，这里放个占位 */
    private static final String PLACEHOLDER_KEY = "ollama";

    private WebView web;
    private SharedPreferences sp;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        sp = getSharedPreferences("wte", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFFFBF3E0);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setSupportZoom(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        try {
            // 允许 file:// 页面加载同目录下的本地字体（woff2）
            s.setAllowFileAccessFromFileURLs(true);
            s.setAllowUniversalAccessFromFileURLs(false);
        } catch (Throwable ignore) { }

        web.setWebViewClient(new WebViewClient());
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.loadUrl("file:///android_asset/www/index.html");
    }

    /* ==================================================== 暴露给网页的能力 */
    public class Bridge {

        @JavascriptInterface
        public String getConfig() {
            return sp.getString("cfg", "{}");
        }

        @JavascriptInterface
        public void saveConfig(String json) {
            sp.edit().putString("cfg", json == null ? "{}" : json).apply();
        }

        /** 网页发起 AI 推荐。必须异步，算完再回调网页 */
        @JavascriptInterface
        public void recommend(final String mode, final String recent, final String callbackId) {
            pool.execute(() -> {
                String out;
                try {
                    out = doRecommend(mode, recent).toString();
                } catch (Exception e) {
                    Log.w(TAG, "recommend failed", e);
                    out = err(shortMsg(e)).toString();
                }
                postResult(callbackId, out);
            });
        }

        /**
         * 让智谱 CogView 给这道菜画一张图。
         * force="1" 表示忽略本地缓存强制重画（会消耗一次额度，由用户点「重画」触发）。
         * 生成要几秒到半分钟，所以必须异步；画好的图本地缓存，同一道菜不再重复画。
         */
        @JavascriptInterface
        public void drawImage(final String dishName, final String pic, final String force,
                              final String pro, final String callbackId) {
            final boolean redo = "1".equals(force);
            final boolean chef = "1".equals(pro);      // true = 大厨重绘（高级模型，花额度）
            pool.execute(() -> {
                String out;
                try {
                    out = doDrawImage(dishName, pic, redo, chef).toString();
                } catch (Exception e) {
                    Log.w(TAG, "drawImage failed", e);
                    out = err(shortMsg(e)).toString();
                }
                postImageResult(callbackId, out);
            });
        }

        @JavascriptInterface
        public void toast(final String msg) {
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_SHORT).show());
        }
    }

    /* ==================================================== 大模型调用 */
    private JSONObject doRecommend(String mode, String recent) throws Exception {
        JSONObject cfg = new JSONObject(sp.getString("cfg", "{}"));

        String provider = optStr(cfg, "provider");
        String key      = optStr(cfg, "key");
        String model    = optStr(cfg, "model");
        String base     = optStr(cfg, "base");
        if (provider.isEmpty()) provider = "deepseek";

        String defBase = null, defModel = null;
        for (String[] p : PRESETS) {
            if (p[0].equalsIgnoreCase(provider)) { defBase = p[1]; defModel = p[2]; break; }
        }
        if (defBase != null) {
            if (base.isEmpty()) base = defBase;
            if (model.isEmpty()) model = defModel;
        } else {
            /* 不在预设里的服务：只要对方是 OpenAI 兼容格式
               （POST {base}/chat/completions + Bearer Key）就能用，
               比如腾讯混元、阿里百炼、硅基流动等，但地址和模型名必须自己填。 */
            if (base.isEmpty()) return err("这个服务不在预设里，请填写「接口地址」");
            if (model.isEmpty()) return err("这个服务不在预设里，请填写「模型名」");
        }

        boolean isOllama = "ollama".equalsIgnoreCase(provider);
        if (key.isEmpty()) {
            if (!isOllama) return err("还没填 API Key");
            key = PLACEHOLDER_KEY;
        }

        StringBuilder user = new StringBuilder();
        user.append("场景：").append("takeout".equals(mode)
            ? "用户要点外卖，只能选外卖平台上能买到的"
            : "用户要自己做，必须考虑家常可操作、买菜方便、别太费事");
        String city = optStr(cfg, "city");
        if (!city.isEmpty()) {
            user.append("。用户在中国").append(city)
                .append("，请优先推荐当地常见、买得到的吃食");
        }

        // 口味偏好：多选标签 + 自由补充，合并成一句喂给模型
        StringBuilder pref = new StringBuilder();
        JSONArray pa = cfg.optJSONArray("prefs");
        if (pa != null) {
            for (int i = 0; i < pa.length(); i++) {
                String t = String.valueOf(pa.opt(i)).trim();
                if (!t.isEmpty() && !"null".equals(t)) {
                    if (pref.length() > 0) pref.append("、");
                    pref.append(t);
                }
            }
        }
        String pt = optStr(cfg, "prefsText");
        if (!pt.isEmpty()) {
            if (pref.length() > 0) pref.append("、");
            pref.append(pt);
        }
        if (pref.length() > 0) {
            user.append("。用户的口味偏好是：").append(pref)
                .append("（必须严格遵守，绝对不要推荐和偏好冲突的菜）");
        }

        user.append("。\n请推荐今天吃什么。");
        if (recent != null && !recent.isEmpty() && !"null".equals(recent)) {
            user.append("\n最近已经吃过（请避开，换个不一样的）：").append(recent).append("。");
        }

        JSONArray msgs = new JSONArray();
        msgs.put(new JSONObject().put("role", "system").put("content", SYSTEM_PROMPT));
        msgs.put(new JSONObject().put("role", "user").put("content", user.toString()));

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", msgs);
        body.put("temperature", 1.2);
        body.put("stream", false);
        if (!isOllama) {
            body.put("response_format", new JSONObject().put("type", "json_object"));
        }

        String url = base.replaceAll("/+$", "") + "/chat/completions";
        String raw;
        try {
            raw = httpPost(url, body.toString(), key);
        } catch (Exception first) {
            // 部分服务不认 response_format，去掉再试一次；仍失败就抛出原始错误
            if (body.has("response_format")) {
                body.remove("response_format");
                raw = httpPost(url, body.toString(), key);
            } else {
                throw first;
            }
        }

        JSONObject resp = new JSONObject(raw);
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return err("模型没有返回内容");

        String content = optStr(choices.getJSONObject(0).optJSONObject("message"), "content");
        JSONObject dish = parseDish(content);

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("source", "ai");
        out.put("model", model);
        out.put("dish", dish);
        return out;
    }

    /** 模型偶尔会带 markdown 包裹或前后废话，这里做容错解析 */
    private JSONObject parseDish(String text) throws Exception {
        String t = text == null ? "" : text.trim();

        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            t = (nl > 0 ? t.substring(nl) : t.substring(3));
            int end = t.lastIndexOf("```");
            if (end > 0) t = t.substring(0, end);
            t = t.trim();
        }
        if (!t.startsWith("{")) {
            int i = t.indexOf('{'), j = t.lastIndexOf('}');
            if (i >= 0 && j > i) t = t.substring(i, j + 1);
        }

        JSONObject o = new JSONObject(t);
        String name = optStr(o, "n");
        if (name.isEmpty()) throw new Exception("模型没给出菜名");

        JSONArray tags = new JSONArray();
        JSONArray rawTags = o.optJSONArray("t");
        if (rawTags != null) {
            for (int i = 0; i < rawTags.length() && tags.length() < 2; i++) {
                String s = String.valueOf(rawTags.opt(i)).trim();
                if (!s.isEmpty() && !"null".equals(s)) tags.put(cut(s, 6));
            }
        }
        if (tags.length() == 0) tags.put("今日推荐");

        String emoji = optStr(o, "e");
        String desc  = optStr(o, "d");

        // 原料：自己做的时候要照着买菜，最多 6 样
        JSONArray ings = new JSONArray();
        JSONArray rawIng = o.optJSONArray("ing");
        if (rawIng != null) {
            for (int i = 0; i < rawIng.length() && ings.length() < 6; i++) {
                String s = String.valueOf(rawIng.opt(i)).trim();
                if (!s.isEmpty() && !"null".equals(s)) ings.put(cut(s, 6));
            }
        }

        JSONObject d = new JSONObject();
        d.put("n", cut(name, 20));
        d.put("e", emoji.isEmpty() ? "\uD83C\uDF7D" : cut(emoji, 4));
        d.put("t", tags);
        d.put("d", desc.isEmpty() ? "就它了～" : cut(desc, 40));
        d.put("ing", ings);
        d.put("pic", cut(optStr(o, "pic"), 120));   // 交给画图模型的画面描述
        return d;
    }

    /* ==================================================== 菜品配图（多家文生图 API） */
    /* 画图服务商默认地址（与网页里的 IMG_PROVIDERS 保持一致）；空 = 必须自己填。
       各家契约一致：POST {base}/images/generations + Bearer key，返回 data[0].url。 */
    private static final String[][] IMG_PRESETS = {
        {"zhipu",       "https://open.bigmodel.cn/api/paas/v4"},
        {"modelscope",  "https://api-inference.modelscope.cn/v1"},
        {"siliconflow", "https://api.siliconflow.cn/v1"},
        /* ★ v2.0：百炼要用 OpenAI 兼容模式（同步 /images/generations）；
           老的 api/v1 是异步原生端点，没有这个路径 */
        {"dashscope",   "https://dashscope.aliyuncs.com/compatible-mode/v1"},
        {"custom",      ""}
    };

    private JSONObject doDrawImage(String dishName, String pic, boolean force, boolean chef)
            throws Exception {
        String name = dishName == null ? "" : dishName.trim();
        if (name.isEmpty()) return err("没有菜名，画不了");

        JSONObject cfg = new JSONObject(sp.getString("cfg", "{}"));
        if (!cfg.optBoolean("imgOn", false)) return err("没有开启 AI 画图");
        String key = optStr(cfg, "imgKey");
        if (key.isEmpty()) return err("还没填画图 API Key");

        String provider = optStr(cfg, "imgProvider");
        if (provider.isEmpty()) provider = "zhipu";
        String base = optStr(cfg, "imgBase");
        if (base.isEmpty()) {
            for (String[] p : IMG_PRESETS) {
                if (p[0].equalsIgnoreCase(provider)) { base = p[1]; break; }
            }
        }
        if (base.isEmpty()){
            return err("这个画图服务要自己填「接口地址」（在设置里）");
        }
        /* ★ v2.0：老版本给百炼存的是 api/v1（异步原生端点，没有 /images/generations），
           自动迁到 OpenAI 兼容模式，老用户不用手动改 */
        if ("dashscope".equalsIgnoreCase(provider) && base.contains("dashscope.aliyuncs.com/api/v1")) {
            base = base.replace("dashscope.aliyuncs.com/api/v1", "dashscope.aliyuncs.com/compatible-mode/v1");
        }
        boolean zhipu = "zhipu".equalsIgnoreCase(provider);
        String endPoint = (zhipu ? base : base.replaceAll("/+$", "")) + "/images/generations";

        String flashModel = imgModelOf(cfg);      // 用户选的模型（自定义服务商可能是任意 ID）
        String proModel   = zhipu ? "cogview-4" : flashModel;  // 只有智谱才有"更贵更好"的分档
        String desc = pic == null ? "" : pic;

        /* 缓存 key 带上服务商+模型，换服务商不会串图 */
        File flashFile = imgCacheFile(provider + "|" + flashModel + "|" + name + "|" + desc);
        File proFile   = imgCacheFile(provider + "|" + proModel + "|" + name + "|" + desc);

        byte[] jpg;
        String used;

        if (chef){
            /* ★ v1.9：大厨绘图不再锁死智谱 + cogview-4。
               先按用户选的模型画（每家都能用）；智谱用户若选的是便宜的 cogview-3-flash，
               会先尝试 cogview-4（高清），失败了自动退回所选模型 —— 再也不会点了没反应。 */
            try {
                jpg = resizeToJpeg(download(fetchImageUrl(endPoint, key, proModel, name, desc, zhipu, provider)), 560);
                used = proModel;
            } catch (Exception proFail) {
                if (flashModel.equals(proModel)) throw proFail;
                Log.w(TAG, "chef pro model failed, fallback to selected: " + flashModel, proFail);
                jpg = resizeToJpeg(download(fetchImageUrl(endPoint, key, flashModel, name, desc, zhipu, provider)), 560);
                used = flashModel;
            }
            try { writeFile(flashFile, jpg); } catch (Exception ignore) { }
            if (zhipu) { try { writeFile(proFile, jpg); } catch (Exception ignore) { } }
            Log.i(TAG, "chef redraw: " + name + " / " + used);

        } else if (force){
            /* 盲盒生图：用当前模型重画一张。高级图不会被删。 */
            jpg = resizeToJpeg(download(fetchImageUrl(endPoint, key, flashModel, name, desc, zhipu, provider)), 560);
            try { writeFile(flashFile, jpg); } catch (Exception ignore) { }
            used = flashModel;
            Log.i(TAG, "blind redraw: " + name + " / " + flashModel);

        } else {
            /* 系统默认：先看有没有大厨画过的好图（不花额度），其次免费图缓存，
               都没有才用当前模型画一张。 */
            if (proFile.exists() && proFile.length() > 0) {
                jpg = readBytes(new FileInputStream(proFile));
                used = proModel;
            } else if (flashFile.exists() && flashFile.length() > 0) {
                jpg = readBytes(new FileInputStream(flashFile));
                used = flashModel;
            } else {
                jpg = resizeToJpeg(download(fetchImageUrl(endPoint, key, flashModel, name, desc, zhipu, provider)), 560);
                try { writeFile(flashFile, jpg); } catch (Exception ignore) { }
                used = flashModel;
            }
            Log.i(TAG, "auto image: " + provider + " / " + name + " / " + used);
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("source", chef ? "chef" : (force ? "blind" : "auto"));
        out.put("model", used);
        out.put("bytes", jpg.length);
        out.put("data", "data:image/jpeg;base64," + Base64.encodeToString(jpg, Base64.NO_WRAP));
        return out;
    }

    private static String imgModelOf(JSONObject cfg) {
        String m = optStr(cfg, "imgModel");
        if (m.isEmpty()) m = "cogview-3-flash";   // 系统默认用免费模型（不耗额度）
        return m;
    }

    /** 各家契约一致：POST {endPoint} + Bearer key，返回 data[0].url
     *  ★ v2.0：魔搭（及类似网关）是异步任务模式 —— 提交后回 task_id，要轮询拿图。
     *    提交带头 X-ModelScope-Async-Mode: true；
     *    轮询 GET {base}/tasks/{task_id}，带头 X-ModelScope-Task-Type: image_generation；
     *    每 5 秒一次，最多 24 次（2 分钟）；SUCCEED 取 output_images，FAILED 报错。 */
    private String fetchImageUrl(String endPoint, String key, String model,
                                 String name, String pic, boolean zhipu, String provider) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("prompt", buildImagePrompt(name, pic));
        body.put("size", "1024x1024");
        if (zhipu && model.startsWith("cogview-4")) body.put("quality", "standard");

        boolean ms = "modelscope".equalsIgnoreCase(provider);
        java.util.Map<String, String> submitHeaders = new java.util.HashMap<>();
        if (ms) submitHeaders.put("X-ModelScope-Async-Mode", "true");

        // 生图比生文慢得多，读取超时给到 200 秒
        String raw = httpPost(endPoint, body.toString(), key, 200000, submitHeaders);

        JSONObject resp = new JSONObject(raw);

        /* ★ 异步任务模式：返回里有 task_id 而没有 data → 去轮询。
           不限魔搭一家：任何兼容网关只要回 task_id 就走这条路。 */
        String taskId = resp.optString("task_id", "");
        if (!taskId.isEmpty() && resp.optJSONArray("data") == null) {
            String base = endPoint.endsWith("/images/generations")
                    ? endPoint.substring(0, endPoint.length() - "/images/generations".length())
                    : endPoint.replaceAll("/images/generations$", "");
            return pollImageTask(base + "/tasks/" + taskId, key, ms);
        }

        JSONArray data = resp.optJSONArray("data");
        if (data == null || data.length() == 0) {
            // 魔搭等部分服务把图放在 images[0].url
            JSONArray imgs = resp.optJSONArray("images");
            if (imgs != null && imgs.length() > 0) {
                String u = optStr(imgs.getJSONObject(0), "url");
                if (!u.isEmpty()) return u;
            }
            throw new Exception("没返回图片：" + cut(raw.replaceAll("\\s+", " "), 130));
        }
        String url = optStr(data.getJSONObject(0), "url");
        if (url.isEmpty()){
            // 有的服务直接返回 b64_json
            String b64 = optStr(data.getJSONObject(0), "b64_json");
            if (!b64.isEmpty()){
                File f = imgCacheFile("b64|" + System.currentTimeMillis());
                writeFile(f, Base64.decode(b64, Base64.DEFAULT));
                return "file://" + f.getAbsolutePath();
            }
            throw new Exception("返回里没有图片地址");
        }
        return url;
    }

    /** ★ v2.0：轮询异步生图任务（魔搭模式）。
     *  每 5 秒一次，最多 24 次（约 2 分钟）；SUCCEED → output_images[0]，FAILED → 报错。 */
    private String pollImageTask(String taskUrl, String key, boolean modelscope) throws Exception {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        if (modelscope) headers.put("X-ModelScope-Task-Type", "image_generation");

        for (int i = 1; i <= 24; i++) {
            Thread.sleep(i == 1 ? 3000 : 5000);   // 首次短等，之后固定 5 秒
            String raw = httpGet(taskUrl, key, headers);
            JSONObject o;
            try { o = new JSONObject(raw); } catch (Exception e) {
                throw new Exception("任务状态返回解析失败：" + cut(raw.replaceAll("\\s+", " "), 120));
            }
            String st = o.optString("task_status", o.optString("status", ""));
            Log.i(TAG, "img task poll #" + i + " status=" + st);

            if ("SUCCEED".equalsIgnoreCase(st)) {
                JSONArray imgs = o.optJSONArray("output_images");
                if (imgs == null || imgs.length() == 0) {
                    // 兜底：有的网关把结果塞在 output.images 或 data
                    imgs = o.optJSONObject("output") != null
                            ? o.optJSONObject("output").optJSONArray("images") : null;
                    if (imgs == null) imgs = o.optJSONArray("data");
                }
                if (imgs != null && imgs.length() > 0) {
                    Object first = imgs.get(0);
                    if (first instanceof JSONObject) {
                        String u = ((JSONObject) first).optString("url", "");
                        if (!u.isEmpty()) return u;
                    } else if (first instanceof String && !((String) first).isEmpty()) {
                        return (String) first;      // output_images 直接是 URL 字符串数组
                    }
                }
                throw new Exception("任务成功但没解析到图片地址：" + cut(raw.replaceAll("\\s+", " "), 120));
            }
            if ("FAILED".equalsIgnoreCase(st) || "ERROR".equalsIgnoreCase(st) || "CANCELED".equalsIgnoreCase(st)) {
                String msg = o.optString("message", o.optString("errors", ""));
                throw new Exception("生成任务失败：" + cut(msg.isEmpty() ? st : msg, 120));
            }
            // PENDING / RUNNING → 继续等
        }
        throw new Exception("轮询 2 分钟还没出图，任务太慢了，稍后在结果页点「大厨绘图」再试");
    }

    /**
     * 生图提示词。
     * 关键点（踩过坑）：弱模型光给菜名画不出东西，会出彩色条纹。
     * 所以让文本模型先写好 pic 画面描述，这里再包上明确的"简笔画"风格约束。
     * 各家模型对中文的友好度不同，这里统一用中文 + 明确风格，实测都能出图。
     */
    private static String buildImagePrompt(String name, String pic) {
        String detail = (pic == null || pic.trim().isEmpty())
            ? ("一份" + name + "，看得清是什么食物")
            : pic.trim();
        return "简笔画风格的" + name + "：" + detail
             + "。扁平矢量插画，粗黑描边，纯色块填充，白色干净背景，居中构图，"
             + "造型简单一眼能认出是什么，可爱卡通感，"
             + "不要写实照片，不要复杂纹理，不要文字";
    }

    private byte[] download(String urlStr) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "WhatToEat/1.0 (Android)");
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("下载图片失败 HTTP " + code);
            return readBytes(c.getInputStream());
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** 缩到最长边 maxSide 的 JPEG；还是太大就降一档质量，免得 base64 传回网页时过大 */
    private byte[] resizeToJpeg(byte[] raw, int maxSide) {
        try {
            Bitmap src = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (src == null) return raw;

            int w = src.getWidth(), h = src.getHeight();
            float scale = Math.min(1f, (float) maxSide / Math.max(w, h));
            Bitmap dst = scale < 1f
                ? Bitmap.createScaledBitmap(src, Math.round(w * scale), Math.round(h * scale), true)
                : src;

            byte[] out = compressJpeg(dst, 82);
            if (out.length > 110 * 1024) out = compressJpeg(dst, 62);
            if (dst != src) dst.recycle();
            src.recycle();
            return out;
        } catch (Throwable t) {
            return raw;                       // 压不动就原样用
        }
    }

    private static byte[] compressJpeg(Bitmap bmp, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return bos.toByteArray();
    }

    private File imgCacheFile(String name) {
        File dir = new File(getFilesDir(), "img");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, md5(name) + ".jpg");
    }

    private static String md5(String s) {
        try {
            MessageDigest m = MessageDigest.getInstance("MD5");
            byte[] d = m.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "x" + Math.abs(s.hashCode());
        }
    }

    private static byte[] readBytes(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return bos.toByteArray();
    }

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream fos = new FileOutputStream(f);
        try { fos.write(data); } finally { fos.close(); }
    }

    private void postImageResult(final String callbackId, final String json) {
        runOnUiThread(() -> {
            if (web == null) return;
            String js = "window.__imgResult(" + JSONObject.quote(callbackId) + ","
                      + JSONObject.quote(json) + ");";
            web.evaluateJavascript(js, null);
        });
    }

    /* ==================================================== HTTP */
    private String httpPost(String urlStr, String body, String key) throws Exception {
        return httpPost(urlStr, body, key, 90000);
    }

    private String httpPost(String urlStr, String body, String key, int readTimeoutMs) throws Exception {
        return httpPost(urlStr, body, key, readTimeoutMs, null);
    }

    /* extraHeaders：魔搭异步模式等特殊请求头用 */
    private String httpPost(String urlStr, String body, String key, int readTimeoutMs,
                            java.util.Map<String, String> extraHeaders) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(readTimeoutMs);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("User-Agent", "WhatToEat/1.0 (Android)");
            if (extraHeaders != null) {
                for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }

            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " " + cut(text.replaceAll("\\s+", " "), 130));
            }
            return text;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String readAll(InputStream is) throws Exception {
        return new String(readBytes(is), "UTF-8");
    }

    /* 轮询异步生图任务用：GET + Bearer + 额外请求头 */
    private String httpGet(String urlStr, String key, java.util.Map<String, String> extraHeaders) throws Exception {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(urlStr).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("User-Agent", "WhatToEat/1.0 (Android)");
            if (extraHeaders != null) {
                for (java.util.Map.Entry<String, String> e : extraHeaders.entrySet()) {
                    c.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = c.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String text = readAll(is);
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " " + cut(text.replaceAll("\\s+", " "), 130));
            }
            return text;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void postResult(final String callbackId, final String json) {
        runOnUiThread(() -> {
            if (web == null) return;
            String js = "window.__aiResult(" + JSONObject.quote(callbackId) + ","
                      + JSONObject.quote(json) + ");";
            web.evaluateJavascript(js, null);
        });
    }

    /* ==================================================== 返回键 */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.getRepeatCount() == 0) {
            web.evaluateJavascript(
                "(function(){var a=document.querySelector('.page.active');return a?a.id:'page-home';})()",
                value -> {
                    String id = value == null ? "" : value.replace("\"", "");
                    if ("page-home".equals(id) || id.isEmpty()) {
                        finish();
                    } else {
                        web.evaluateJavascript(
                            "(function(){if(window.__goHome)window.__goHome();})()", null);
                    }
                });
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        try { pool.shutdownNow(); } catch (Throwable ignore) { }
        super.onDestroy();
    }

    /* ==================================================== 小工具 */
    private static JSONObject err(String reason) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false);
            o.put("reason", reason == null ? "未知错误" : reason);
        } catch (Exception ignore) { }
        return o;
    }

    /**
     * org.json 的 optString(key, null) 遇到 JSON null 会返回字符串 "null"，
     * 用 TextUtils.isEmpty 判不出来，会原样显示到界面上 —— 所以统一走这里。
     */
    private static String optStr(JSONObject o, String k) {
        if (o == null || o.isNull(k)) return "";
        String s = o.optString(k, "");
        return "null".equals(s) ? "" : s.trim();
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        s = s.trim();
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String shortMsg(Exception e) {
        if (e instanceof java.net.UnknownHostException) {
            return "连不上服务器（域名解析失败），检查手机网络";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "请求超时，模型响应太慢或网络不稳";
        }
        if (e instanceof java.net.ConnectException) {
            return "连接被拒绝，检查接口地址是否正确";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "HTTPS 握手失败，检查网络是否被拦截";
        }
        String m = e.getMessage();
        if (m == null || m.isEmpty()) m = e.getClass().getSimpleName();
        return m.length() > 160 ? m.substring(0, 160) : m;
    }
}
