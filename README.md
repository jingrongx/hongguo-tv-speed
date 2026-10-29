# 红果短剧 TV 极速版

专为电视与电视盒子打磨的红果短剧第三方客户端。大屏、遥控器优先、
极速换剧换集，带完整的自更新通道与自动发版流水线。

> 基于 [aston314/hongguo-tv](https://github.com/aston314/hongguo-tv) 二次开发的独立发行版，
> 包名、签名、更新通道均已独立，与上游互不影响。感谢原作者的出色工作。

## 界面一览

| 推荐流播放 | 悬浮控制 Dock |
| --- | --- |
| ![推荐流播放](docs/screenshots/playback.png) | ![悬浮控制 Dock](docs/screenshots/control-dock.png) |

| 更多功能网格 | 首次启动指南 |
| --- | --- |
| ![更多功能网格](docs/screenshots/more-grid.png) | ![首次启动指南](docs/screenshots/first-run-guide.png) |

## 特性

### 播放体验

- **全屏连播**：进入即播，一集看完自动接下一集，一部看完自动接下一部
- **无缝换集**：换集不黑屏、不转圈，原子换流 + 磁盘级预取缓存
- **官方式过渡卡**：换集/换剧时封面卡片滑入，画面就绪即揭开
- **竖屏剧自动跳过**：换剧前静默测量宽高，竖屏剧不闪屏直接跳过；已测过的剧永久记忆，秒跳
- **音量均衡**：动态响度归一，各剧之间不再忽大忽小
- **硬解优先，崩溃自愈**：自动硬解；解码服务异常时自动降级软解，下次启动自愈

### 遥控器交互

- **统一按键语法**：↑↓ 换剧，←→ 切集，OK/菜单键呼出控制中心，返回逐级退出、双击退出应用
- **悬浮控制 Dock**：进度条 + 五大按键（历史/选集/播放/倍速/更多），白色播放键为视觉锚点
- **极速响应**：焦点路由零布局重排，按键即达
- **首次启动指南**：只显示一次，四行大字讲清全部按键

### 功能页面

- 找剧/排行榜/新剧/收藏/追更/我的/打赏/设置，全部接入统一交互
- 弹幕、清晰度切换、选季、倍速、诊断面板
- 退出时记住剧目与进度，下次进入从原处继续
- 登录官方账号后历史云同步，重装不丢

### 更新机制

- 应用内自动检查（每小时），发现新版本静默后台下载、断点续传，完成后轻提示一键安装
- 界面启动异常时自动转入"更新恢复"模式：原生弹窗提示新版本，不依赖页面本身
- 推送 `v*` 标签即自动构建发布（GitHub Actions），产出签名 APK 与更新清单

## 安装

1. 在 [Releases](https://github.com/jingrongx/hongguo-tv-speed/releases) 下载 `hongguo-tv-speed-mpv-<版本>.apk`
   （MPV 播放内核版，推荐）或 `hongguo-tv-speed-<版本>.apk`（标准 WebView 版）
2. 通过 U 盘或 `adb install` 安装到电视
3. 应用启动后自动检查更新（每小时一次）；也可在"设置 → 应用更新"手动检查

## 构建

依赖：Go 1.25+、JDK 17、Android SDK/NDK（r27）、Gradle 8.x、Node.js 22（测试）。

```bash
# 1. 编译 Go 核心（三个 ABI；改 core/cmd/hongguotv/ui/ 下任何文件都必须重编）
#    版本号必须与 android/app/build.gradle 的 appVersion 一致，格式 <版本>-dev
GOOS=android GOARCH=arm64 CGO_ENABLED=1   CC=$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android23-clang.cmd   go build -trimpath -ldflags "-s -w -X main.version=0.2.153-dev"   -o ../android/app/src/main/jniLibs/arm64-v8a/libhongguotv.so ./cmd/hongguotv

# 2. 构建 APK（签名读取 android/keystore.properties，或 CI 环境变量）
cd android && gradle assembleMpvRelease

# 3. 前端回归测试（508 项）
node --test tests/*.cjs
```

### 自动发版

推送 `v*` 标签即可：

```bash
git tag v0.2.153 && git push origin v0.2.153
```

CI 自动：编译三 ABI 核心 → 拉取 mpv-android 库并编译桥接 → 签名 APK →
发布 Release（含 SHA256SUMS）→ 提交更新清单到 main。签名密钥通过仓库
Secrets（`ANDROID_KEYSTORE_*`）注入，本地签名密钥不进仓库。

## 项目结构

```
core/                 Go 核心：本地 HTTP API + go:embed 内嵌 TV UI
  cmd/hongguotv/      入口与内嵌资源（ui/、locales/）
android/              Android 壳：WebView + 原生播放桥
  app/src/mpv/        MPV 播放内核（libmpv + JNI 桥接）
  app/src/main/       通用壳（服务、更新器、电视频道集成）
tests/                Node 前端回归测试（508 项）
docs/screenshots/     界面截图
```

## 致谢

- [aston314/hongguo-tv](https://github.com/aston314/hongguo-tv) — 原项目
- [mpv-android](https://github.com/mpv-android/mpv-android) — libmpv 播放内核
- 所有反馈问题的电视盒用户

## 许可

与上游项目保持一致。
