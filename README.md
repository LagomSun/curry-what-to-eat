# 咖喱吃什么 🍛

一个「今天吃什么」决策小工具的 Android 应用 + 单文件网页版。  
打开 → 点「点外卖」或「自己做」→ 立即给你一道菜；想要 AI 推荐就填自己的 API Key，  
还能让 AI 给这道菜画一张图。

![平台](https://img.shields.io/badge/Android-7.0%2B-green)

![依赖](https://img.shields.io/badge/第三方依赖-0-blue)

## 特性

- **零第三方依赖**：APK 只用 `android.app.Activity` + `WebView`，无任何 SDK，APK 不到 1 MB
- **本地菜谱秒出结果**：首次进入不联网、不等待，手动点「换一个」才走 AI
- **AI 推荐**：DeepSeek / Kimi / 通义 / 智谱 / OpenAI / Ollama（本地离线），填自己的 Key 即用
- **AI 菜品配图**：智谱 CogView / 魔搭 ModelScope（异步任务）/ 硅基流动 / 阿里云百炼 / 自定义 OpenAI 兼容端点
- **每个服务商独立记忆**：切服务商自动切换各自保存的 Key / 地址 / 模型
- **API Key 只存手机本地**（SharedPreferences），不进网页、不上传、不打包进 APK

## 网页版（不装 APK 先看效果）

```bash
python server.py            # 浏览器打开 http://127.0.0.1:8765/what-to-eat-demo.html
python server.py --mock     # 不花钱的模拟 AI 模式
```

AI 配置在同目录 `ai-config.json`（`api_key` 默认为空 = 纯本地菜谱）。

## 自己编译 APK

1. 装 JDK 17 + Android SDK（Platform 34）
2. 生成自己的签名并放到 `android/keystore/`（见该目录 README.txt）
3. 在 `android/local.properties` 写：
   ```properties
   sdk.dir=<你的Android SDK路径>
   keystore.storePassword=<你的密码>
   keystore.keyPassword=<你的密码>
   ```
4. 编译：
   ```bash
   cd android
   gradle assembleRelease
   # 产物：app/build/outputs/apk/release/app-release.apk
   ```

## 装到手机

直接安装 `app-release.apk`。打开 App → 底部「AI 设置」→ 选服务商、填 Key。  
不填 Key 也能用（内置本地菜谱）。

## 目录结构

```
what-to-eat-demo.html      单文件网页（核心逻辑全在这里）
server.py                  网页版本机服务（读 ai-config.json）
data.json                  本地菜谱数据
android/                   APK 工程（Gradle + WebView 壳）
```

## 许可

MIT —— 详见 LICENSE
