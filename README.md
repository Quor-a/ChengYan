# 澄言 ChengYan — Android AI 助手

原创设计的 AI 对话应用：暖纸 × 墨色 × 陶土橙视觉体系，支持任意 OpenAI 兼容接口（DeepSeek / 硅基流动 / 通义千问 / OpenAI / Ollama 等）。

## 构建步骤

1. 安装 Android Studio（Hedgehog 2023.1.1 或更新版本，需 JDK 17）
2. File → Open，选择本目录（`chengyan-android/`）
3. 等待 Gradle 同步完成（首次会自动下载依赖）
4. 连接手机（开启 USB 调试）或启动模拟器，点 Run ▶

打包 APK：菜单 Build → Build Bundle(s) / APK(s) → Build APK(s)，
产物在 `app/build/outputs/apk/debug/app-debug.apk`。

## 功能

- 流式对话（SSE），可中途停止，失败可一键重试
- 多服务商预设 + 自定义 Base URL / API Key / 模型 / 系统提示词 / 温度
- 设置页「测试连接」按钮，直接验证配置并显示具体错误
- Markdown 渲染：标题 / 列表 / 引用 / 表格 / 链接 / 代码块（带语言标签与复制按钮）
- 消息操作：复制、重新生成、删除（错误消息带重试）
- 多会话管理：搜索、摘要预览、相对时间、删除（二次确认）
- 智能滚动：向上翻历史时流式输出不打断，配「回到底部」按钮
- 欢迎页场景建议卡片；对话数据本地持久化（DataStore + kotlinx-serialization）

## 结构

```
app/src/main/java/com/chengyan/app/
├── MainActivity.kt            入口 + ViewModel 工厂
├── data/
│   ├── Models.kt              数据模型、服务商预设、建议卡片
│   └── SettingsStore.kt       DataStore 持久化（配置 + 会话）
├── net/
│   └── OpenAiClient.kt        OkHttp SSE 流式客户端 + 连通性测试
└── ui/
    ├── ChatViewModel.kt       状态与业务逻辑
    ├── theme/Theme.kt         原创配色（亮/暗双主题）
    ├── components/
    │   ├── Markdown.kt        轻量 Markdown 解析与渲染（纯文本安全，无 HTML 注入面）
    │   └── MessageBubble.kt   消息气泡、头像、操作条、流式光标
    └── screens/
        ├── ChatScreen.kt      主界面（抽屉 + 消息列表 + 输入区）
        └── SettingsScreen.kt  模型配置页
```

## 注意

- 模拟器访问本机 Ollama 用 `http://10.0.2.2:11434/v1`；真机用电脑局域网 IP
- App 内图标、配色、命名均为原创设计，未复制任何第三方产品的代码或素材
