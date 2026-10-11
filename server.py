#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
今天吃什么 · 本地服务
================================================================
干两件事：
  1. 当静态服务器 —— 解决 file:// 协议禁止 fetch 的限制
  2. 提供 /api/recommend —— 让大模型推荐菜品，自己不用管菜谱

用法（在本目录下执行）：
    python server.py                 # 正常启动，读同目录 ai-config.json
    python server.py --mock          # 不接真模型，用内置模拟，先验证前后端链路
    python server.py --port 9000     # 换端口

然后浏览器访问：http://127.0.0.1:8765/what-to-eat-demo.html

★ 为什么不让网页直接调大模型 API？
  浏览器里的代码任何人都能查看源码，API Key 写在前端 = 公开泄露。
  所有 Key 只留在本机 ai-config.json 里，由这个服务中转。
================================================================
"""

import argparse
import base64
import hashlib
import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
CONFIG_PATH = os.path.join(BASE_DIR, "ai-config.json")

# 各厂商 OpenAI 兼容端点预设，写 provider 名即可，也可在配置里覆盖 base_url / model
PRESETS = {
    "deepseek":  ("https://api.deepseek.com/v1",                        "deepseek-chat"),
    "moonshot":  ("https://api.moonshot.cn/v1",                         "moonshot-v1-8k"),
    "dashscope": ("https://dashscope.aliyuncs.com/compatible-mode/v1",  "qwen-plus"),
    "zhipu":     ("https://open.bigmodel.cn/api/paas/v4",               "glm-4-flash"),
    "openai":    ("https://api.openai.com/v1",                          "gpt-4o-mini"),
    "ollama":    ("http://127.0.0.1:11434/v1",                          "qwen2.5:7b"),
}

DEFAULT_CONFIG = {
    "enabled": False,
    "provider": "deepseek",
    "base_url": "",
    "api_key": "",
    "model": "",
    "timeout": 30,
    "temperature": 1.2,
    "img_on": False,      # 是否用智谱 CogView 给菜品画图
    "img_key": "",        # 智谱的 Key（和上面的文本模型 Key 不是同一个）
    "img_model": "cogview-4",   # cogview-4 画得像（0.06元/张）/ cogview-3-flash 免费但很糙
    "city": "",           # 所在城市，让模型优先推荐当地吃食
    "prefs": [],          # 口味偏好多选：不吃辣 / 爱吃肉 …
    "prefs_text": "",     # 口味偏好的自由补充
}

IMG_API = "https://open.bigmodel.cn/api/paas/v4/images/generations"


# ---------------------------------------------------------------- 配置
def load_config():
    cfg = dict(DEFAULT_CONFIG)
    if os.path.exists(CONFIG_PATH):
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                cfg.update(json.load(f) or {})
        except Exception as e:
            print("[配置] 读取 ai-config.json 失败：%s（用默认值）" % e)
    p = (cfg.get("provider") or "deepseek").lower()
    if p in PRESETS:
        cfg["base_url"] = cfg.get("base_url") or PRESETS[p][0]
        cfg["model"] = cfg.get("model") or PRESETS[p][1]
    return cfg


# ---------------------------------------------------------------- 本地菜品池（兜底）
BUILTIN_POOL = {
    "takeout": [
        {"n": "香辣鸡腿堡", "e": "🍔", "t": ["懒人必备", "经典"], "d": "今天就它啦～"},
        {"n": "麻辣烫", "e": "🍲", "t": ["暖胃", "够味"], "d": "热乎乎来一碗～"},
        {"n": "炸鸡全家桶", "e": "🍗", "t": ["解馋", "能分享"], "d": "今天放肆一下～"},
        {"n": "寿司拼盘", "e": "🍣", "t": ["清爽", "日料"], "d": "换换口味～"},
        {"n": "芝士披萨", "e": "🍕", "t": ["拉丝", "满足"], "d": "今天就要芝士～"},
        {"n": "黄焖鸡米饭", "e": "🍛", "t": ["下饭", "管饱"], "d": "一锅米饭全干完～"},
        {"n": "酸菜鱼", "e": "🐟", "t": ["酸辣", "过瘾"], "d": "今天吃点带汤的～"},
        {"n": "兰州牛肉面", "e": "🍜", "t": ["汤面", "暖身"], "d": "一碗汤面暖到底～"},
        {"n": "韩式石锅拌饭", "e": "🍚", "t": ["拌饭", "锅巴香"], "d": "拌一拌更香～"},
        {"n": "轻食沙拉碗", "e": "🥗", "t": ["轻负担", "健康"], "d": "今天清淡一点点～"},
    ],
    "cook": [
        {"n": "煎饼果子", "e": "🥞", "t": ["小吃甜品"], "d": "今天就它啦～"},
        {"n": "香菇鸡肉焖饭", "e": "🍚", "t": ["懒人料理", "简单"], "d": "今天做个下饭的～"},
        {"n": "番茄炒蛋", "e": "🍅", "t": ["快手", "家常"], "d": "五分钟就能上桌～"},
        {"n": "青椒肉丝面", "e": "🍜", "t": ["一碗面", "省事"], "d": "今天煮碗面～"},
        {"n": "黄金蛋炒饭", "e": "🍳", "t": ["快手", "清冰箱"], "d": "剩饭的好归宿～"},
        {"n": "蒜蓉西兰花", "e": "🥦", "t": ["清淡", "解腻"], "d": "今天吃清淡点～"},
        {"n": "红烧肉", "e": "🍖", "t": ["硬菜", "要花点时间"], "d": "今天认真做一顿～"},
        {"n": "紫菜蛋花汤", "e": "🍲", "t": ["汤品", "简单"], "d": "配个汤刚刚好～"},
    ],
}


def local_pool(mode):
    """优先用同目录 data.json 的池子，没有再退回内置"""
    try:
        with open(os.path.join(BASE_DIR, "data.json"), "r", encoding="utf-8") as f:
            d = json.load(f)
        lst = d.get(mode, {}).get("list") or []
        if lst:
            return lst
    except Exception:
        pass
    return BUILTIN_POOL.get(mode, BUILTIN_POOL["takeout"])


# ---------------------------------------------------------------- 提示词
SYSTEM_PROMPT = """你是一个懂吃、懂生活的中餐推荐官，说话有温度，也熟悉各地小吃。
用户每天纠结“今天吃什么”，你的任务是给出一道具体的菜，让他一眼就想吃。

规则：
1. 必须是一道具体、真实存在的菜。外卖场景可以推荐本地小吃、快餐套餐
   （例如华莱士的香辣鸡腿堡套餐这类）、早餐（粥、包子、煎饼、馄饨、肉夹馍）、
   面食、盖饭、炸鸡、卤味、麻辣烫等，越接地气越好，不要总推荐大菜
2. 自己做场景要选家常、买菜方便、别太费事的
3. 推荐语 20 字以内，要有人味、有画面感，不要用“营养均衡”“美味可口”这种套话
4. 给 2 个标签，每个不超过 4 个字
5. 配一个最贴切的 emoji（必须是单个 emoji 字符）
6. ing 字段：做这道菜需要的主要原料，4-6 样，每样不超过 4 个字；点外卖的场景给空数组
7. pic 字段：用一句话描述这道菜“长什么样”——装在什么碗或盘里、主要食材是什么颜色、
   什么角度看。这句话会直接交给画图模型，必须具体
8. 每次都要换新的，不要连续重复

只输出一个 JSON 对象，不要任何解释、不要 markdown 代码块：
{"n":"菜名","e":"🍜","t":["标签1","标签2"],"d":"推荐语","ing":["原料1","原料2"],"pic":"画面描述"}"""


def build_user_prompt(mode, recent, city="", prefs=None, prefs_text=""):
    scene = "用户要点外卖，只能选外卖平台上能买到的" if mode == "takeout" \
        else "用户要自己做，必须考虑家常可操作、买菜方便、别太费事"
    p = "场景：%s" % scene
    if city:
        p += "。用户在中国%s，请优先推荐当地常见、买得到的吃食" % city
    parts = [str(x).strip() for x in (prefs or []) if str(x).strip()]
    if prefs_text:
        parts.append(str(prefs_text).strip())
    if parts:
        p += "。用户的口味偏好是：%s（必须严格遵守，绝对不要推荐和偏好冲突的菜）" % "、".join(parts)
    p += "。\n请推荐今天吃什么。"
    if recent:
        p += "\n最近已经吃过（请避开，换个不一样的）：%s。" % "、".join(recent)
    return p


# ---------------------------------------------------------------- 大模型调用
def _opener_for(url):
    """
    本地地址（Ollama 等）强制绕过系统代理。
    本机有 http_proxy=127.0.0.1:7771 之类的代理时，
    urllib 默认会把 127.0.0.1:11434 的请求也丢给代理，直接失败。
    云端 API（DeepSeek/OpenAI 等）仍按系统设置走代理。
    """
    host = (urlparse(url).hostname or "").lower()
    if host in ("127.0.0.1", "localhost", "::1", "0.0.0.0", ""):
        return urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return urllib.request.build_opener()


def call_llm(cfg, mode, recent):
    url = cfg["base_url"].rstrip("/") + "/chat/completions"
    body = {
        "model": cfg["model"],
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user_prompt(mode, recent, cfg.get("city", ""),
                                                              cfg.get("prefs") or [], cfg.get("prefs_text", ""))},
        ],
        "temperature": float(cfg.get("temperature", 1.2)),
        "stream": False,
    }
    # 支持 json_object 的厂商会更稳；不支持的厂商会自动忽略这个字段之外还要兜底解析
    if cfg.get("json_mode", True):
        body["response_format"] = {"type": "json_object"}

    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + cfg["api_key"],
        },
        method="POST",
    )
    opener = _opener_for(url)
    with opener.open(req, timeout=float(cfg.get("timeout", 30))) as r:
        raw = json.loads(r.read().decode("utf-8"))
    content = raw["choices"][0]["message"]["content"].strip()
    return parse_dish(content)


def parse_dish(text):
    """模型偶尔会带 ``` 包裹或前后废话，这里做容错解析"""
    t = text.strip()
    if t.startswith("```"):
        t = t.split("```")[1]
        if t.startswith("json"):
            t = t[4:]
        t = t.strip()
    if not t.startswith("{"):
        i, j = t.find("{"), t.rfind("}")
        if i >= 0 and j > i:
            t = t[i:j + 1]
    obj = json.loads(t)

    name = str(obj.get("n") or "").strip()
    emoji = str(obj.get("e") or "🍽").strip()
    tags = obj.get("t") or []
    desc = str(obj.get("d") or "").strip()
    if not name:
        raise ValueError("模型没给出菜名")
    if not isinstance(tags, list):
        tags = [str(tags)]
    tags = [str(x).strip()[:6] for x in tags if str(x).strip()][:2]

    # 原料：自己做的时候照着买菜用
    ing = obj.get("ing") or []
    if not isinstance(ing, list):
        ing = [ing]
    ing = [str(x).strip()[:6] for x in ing if str(x).strip() and str(x).strip() != "null"][:6]

    return {
        "n": name[:20],
        "e": emoji[:4],
        "t": tags or ["今日推荐"],
        "d": desc[:40] or "就它了～",
        "ing": ing,
        "pic": str(obj.get("pic") or "").strip()[:120],
    }


def mock_dish(mode, recent):
    """--mock 模式：假装模型想了一秒，用来验证前后端链路而不花一分钱"""
    time.sleep(1.0)
    pool = [d for d in local_pool(mode) if d["n"] not in recent] or local_pool(mode)
    d = dict(random.choice(pool))
    d["d"] = random.choice([
        "今天就吃点热乎的，这个最踏实。",
        "忙了一天，别为难自己，就它吧。",
        "换换口味吧，老吃那几样也腻。",
        "这个不费事，吃完还能歇会儿。",
    ])
    return d


# ---------------------------------------------------------------- 菜品配图（智谱 CogView）
def build_image_prompt(name, pic=""):
    """
    生图提示词。踩过的坑：弱模型只给菜名画不出东西，会出彩色条纹。
    所以让文本模型先写好 pic 画面描述，这里再包上明确的"简笔画"风格约束。
    """
    detail = (pic or "").strip() or ("一份%s，看得清是什么食物" % name)
    return ("简笔画风格的%s：%s。扁平矢量插画，粗黑描边，纯色块填充，白色干净背景，"
            "居中构图，造型简单一眼能认出是什么，可爱卡通感，"
            "不要写实照片，不要复杂纹理，不要文字" % (name, detail))


def draw_image(key, name, pic="", model=""):
    """让智谱画一张，返回图片 URL"""
    if not model:
        model = "cogview-4"
    body = {
        "model": model,
        "prompt": build_image_prompt(name, pic),
        "size": "1024x1024",
    }
    if model.startswith("cogview-4"):
        body["quality"] = "standard"
    req = urllib.request.Request(
        IMG_API,
        data=json.dumps(body).encode("utf-8"),
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + key},
        method="POST",
    )
    with _opener_for(IMG_API).open(req, timeout=200) as r:
        raw = json.loads(r.read().decode("utf-8"))
    data = raw.get("data") or []
    if not data:
        raise Exception("没返回图片：" + json.dumps(raw, ensure_ascii=False)[:150])
    url = (data[0] or {}).get("url")
    if not url:
        raise Exception("返回里没有图片地址")
    return url


def download_bytes(url):
    req = urllib.request.Request(url, headers={"User-Agent": "WhatToEat/1.0"})
    with _opener_for(url).open(req, timeout=90) as r:
        return r.read()


def shrink_jpeg(raw, max_side=560):
    """有 Pillow 就压小；没有就原样返回（本地预览够用）"""
    try:
        import io
        from PIL import Image
        im = Image.open(io.BytesIO(raw)).convert("RGB")
        w, h = im.size
        scale = min(1.0, max_side / float(max(w, h)))
        if scale < 1.0:
            im = im.resize((int(w * scale), int(h * scale)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=82)
        return buf.getvalue()
    except Exception:
        return raw


# ---------------------------------------------------------------- HTTP
class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=BASE_DIR, **kw)

    def log_message(self, fmt, *args):
        if "/api/" in (self.path or ""):
            sys.stderr.write("[api] %s\n" % (fmt % args))

    def _json(self, obj, code=200):
        payload = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(payload)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        from urllib.parse import parse_qs
        u = urlparse(self.path)

        if u.path == "/api/status":
            cfg = load_config()
            ready = bool(cfg["enabled"] and cfg["api_key"])
            if ARGS.mock:
                st, hint = "mock", "模拟模式：不调用真实接口，用于验证链路"
            elif ready:
                st, hint = "ai", "AI 已就绪"
            else:
                st, hint = "local", "未启用 AI，走本地菜品池（改 ai-config.json 可开启）"
            return self._json({
                "ok": True,
                "mode": st,
                "provider": cfg["provider"],
                "model": cfg["model"] if ready else None,
                "hint": hint,
            })

        if u.path == "/api/image":
            q = parse_qs(u.query)
            name = (q.get("name", [""])[0] or "").strip()
            pic  = (q.get("pic", [""])[0] or "").strip()
            force = (q.get("force", ["0"])[0] or "0") == "1"
            return self._image(name, pic, force)

        if u.path == "/api/recommend":
            q = parse_qs(u.query)
            mode = (q.get("mode", ["takeout"])[0] or "takeout").lower()
            if mode not in ("takeout", "cook"):
                mode = "takeout"
            recent = [x for x in (q.get("recent", [""])[0] or "").split(",") if x][:6]
            return self._recommend(mode, recent)

        return super().do_GET()

    def do_POST(self):
        from urllib.parse import parse_qs
        if urlparse(self.path).path == "/api/recommend":
            try:
                n = int(self.headers.get("Content-Length") or 0)
                data = json.loads(self.rfile.read(n).decode("utf-8")) if n else {}
            except Exception:
                data = {}
            mode = str(data.get("mode") or "takeout").lower()
            recent = [str(x) for x in (data.get("recent") or [])][:6]
            return self._recommend(mode if mode in ("takeout", "cook") else "takeout", recent)
        self.send_error(404)

    def _recommend(self, mode, recent):
        cfg = load_config()
        t0 = time.time()

        # ① mock 模式
        if ARGS.mock:
            d = mock_dish(mode, recent)
            return self._json({"ok": True, "source": "mock", "model": "mock",
                               "dish": d, "ms": int((time.time() - t0) * 1000)})

        # ② 真·大模型
        if cfg["enabled"] and cfg["api_key"]:
            try:
                d = call_llm(cfg, mode, recent)
                print("[AI] %s → %s（%.1fs）" % (cfg["model"], d["n"], time.time() - t0))
                return self._json({"ok": True, "source": "ai", "model": cfg["model"],
                                   "dish": d, "ms": int((time.time() - t0) * 1000)})
            except Exception as e:
                msg = str(e)[:200]
                print("[AI] 调用失败：%s → 回退本地池" % msg)
                pool = [x for x in local_pool(mode) if x["n"] not in recent] or local_pool(mode)
                return self._json({"ok": True, "source": "local-fallback",
                                   "reason": msg, "dish": random.choice(pool)}, 200)

        # ③ 没配 Key，走本地池（诚实标记，不假装是 AI）
        pool = [x for x in local_pool(mode) if x["n"] not in recent] or local_pool(mode)
        return self._json({"ok": True, "source": "local", "dish": random.choice(pool)})

    def _image(self, name, pic="", force=False):
        """给一道菜画图；本地有缓存就直接给，省时间也省钱"""
        if not name:
            return self._json({"ok": False, "reason": "没有菜名"}, 400)
        if ARGS.mock:
            return self._json({"ok": False, "reason": "mock 模式不画图"})

        cfg = load_config()
        key = (cfg.get("img_key") or "").strip()
        if not key:
            return self._json({"ok": False, "reason": "没配智谱画图 Key（ai-config.json 的 img_key）"})

        cache_dir = os.path.join(BASE_DIR, "img_cache")
        os.makedirs(cache_dir, exist_ok=True)
        model = (cfg.get("img_model") or "cogview-4")
        ck = ("%s|%s|%s" % (model, name, pic)).encode("utf-8")
        fp = os.path.join(cache_dir, hashlib.md5(ck).hexdigest() + ".jpg")

        t0 = time.time()
        try:
            if (not force) and os.path.exists(fp) and os.path.getsize(fp) > 0:
                with open(fp, "rb") as f:
                    data = f.read()
                src = "cache"
            else:
                url = draw_image(key, name, pic, model)
                data = shrink_jpeg(download_bytes(url), 560)
                with open(fp, "wb") as f:
                    f.write(data)
                src = "ai"
                print("[画图] %s → %d bytes（%.1fs）" % (name, len(data), time.time() - t0))

            return self._json({
                "ok": True, "source": src, "model": model, "bytes": len(data),
                "data": "data:image/jpeg;base64," + base64.b64encode(data).decode("ascii"),
            })
        except Exception as e:
            print("[画图] 失败：%s" % str(e)[:200])
            return self._json({"ok": False, "reason": str(e)[:200]})


def main():
    global ARGS
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--mock", action="store_true", help="不接真模型，用模拟响应验证链路")
    ARGS = ap.parse_args()

    cfg = load_config()
    print("=" * 58)
    print("  今天吃什么 · 本地服务")
    print("  目录 : %s" % BASE_DIR)
    print("  地址 : http://127.0.0.1:%d/what-to-eat-demo.html" % ARGS.port)
    if ARGS.mock:
        print("  模式 : MOCK（模拟 AI，不调用真实接口，不花钱）")
    elif cfg["enabled"] and cfg["api_key"]:
        print("  模式 : AI  %s / %s" % (cfg["provider"], cfg["model"]))
    else:
        print("  模式 : 本地菜品池（未启用 AI）")
        print("         要开 AI：把 ai-config.json 里的 enabled 改成 true 并填 api_key")
    print("=" * 58)

    srv = ThreadingHTTPServer(("127.0.0.1", ARGS.port), Handler)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\n已停止。")


if __name__ == "__main__":
    main()
