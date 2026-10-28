# 红果短剧 TV 极速版

面向 Android TV 的红果短剧第三方客户端。基于开源项目深度重构，围绕"**打开即播、全程全屏、遥控零学习成本**"重新设计了整套交互与播放链路，专为电视大屏和低端电视盒子优化。

## 特性

### 沉浸式电视交互

- 打开应用即全屏自动播放一部剧的第一集（随机、不重复上次、默认只选横屏剧）
- 全程单一播放界面：**上下键换剧、左右键切集**，任何时候语义一致
- OK 键呼出半透明侧边栏菜单，菜单键呼出底部控件行（选集/选季/清晰度/倍速/音量）
- 6 秒无操作自动收起所有浮层，返回键一级一级退出
- 默认软件解码 + 播放时解析视频宽高，自动记录并跳过竖屏剧

### 极速换集

- 双层预取：当前集开播即预解析下一集（Range 首块预热），同时后台把下一集**整集下载到本地磁盘**
- 换集时优先播放已下载的本地文件（零网络等待），未完成时无缝降级到预热流
- 软件解码下使用 mpv 原子换流（loadfile replace），换集无停止栅栏、无固定间隙

### 稳定性

- 针对 rk356x 等盒子的厂商解码服务崩溃（Scudo abort in omx@1.0-service）做了完整规避：默认软件解码完全绕开出问题的系统组件
- 换集崩溃、绿屏（RGA 对齐缺陷）、bytevc1 编码不兼容等问题已逐一从真机日志定位并修复
- 基于 mpv-android 2026 构建的最新 libmpv + ffmpeg，覆盖旧版无法解码的 bytevc1（H.266 系）片源

### 独立与去上游化

- 独立应用包名（`com.jingrongx.hongguotv`）、独立签名密钥库、独立更新通道（`jingrongx/hongguo-tv` GitHub Releases）
- Go 模块路径、桌面更新清单均已更名，不依赖原作者的任何服务或仓库
- 内置播放协议实现（签名、CENC AES-CTR 解密、Range 媒体路由）来自原项目的已验证代码，本项目在其上持续维护

## 功能

推荐流、找剧、分类、排行榜、新剧、上新日历、详情、选集、选季、连续播放、下一季、历史、收藏、点赞、预约、账号登录（官方账号）、云端同步、评论、弹幕、电视桌面频道同步。

## 构建

```bash
# Go 核心（三个 ABI，产物进 android/app/src/main/jniLibs）
GOOS=android GOARCH=arm64 CGO_ENABLED=1 \
  CC=<ndk>/aarch64-linux-android23-clang.cmd \
  go build -trimpath -buildmode=pie -ldflags "-s -w -X main.version=<ver>" \
  -o android/app/src/main/jniLibs/arm64-v8a/libhongguotv.so ./core/cmd/hongguotv

# Android APK（需要 android/keystore.properties 与签名密钥库）
gradle.bat -p android assembleMpvRelease   # MPV 解码版（推荐）
gradle.bat -p android assembleStandardRelease
```

环境要求：Go 1.25+（GOPROXY 建议国内镜像）、NDK 27、JDK 17、Gradle 8.14。

## 测试

```bash
node --test tests/*.cjs   # 508 项 UI/逻辑回归
go test ./core/...        # Go 核心回归
```

## 技术文档

- [TV 布局与遥控器交互](docs/tv-design.md)
- [协议证据与待验证接口](docs/protocol-evidence.md)
- [账号登录与云同步](docs/account-sync.md)
- [MPV 库源码、构建和替换](docs/mpv-library-source.md)
- [webOS 移植说明](webos/README.md)

## 致谢

播放协议、TV 页面框架与部分文档源自 [aston314/hongguo-tv](https://github.com/aston314/hongguo-tv)（MIT），播放内核基于 [mpv-android](https://github.com/mpv-android/mpv-android) 构建的 libmpv。感谢原作者的工作。
