<p align="right"><a href="./README_EN.md">English →</a></p>

# XFun SDK

XFun 小程序 SDK：把小程序源码编译并运行在 Android、iOS、Harmony 和 Web 上。本项目是 [didi/dimina](https://github.com/didi/dimina) 的二次开发版本（Apache-2.0），由 PAtrickpp33 维护，与滴滴及 Dimina 项目无隶属或背书关系。

## 发布坐标

| 平台 | 名称 | 版本 |
|---|---|---|
| Android（Maven） | `io.xfun:xfun-sdk`、`io.xfun:xfun-engine-qjs`、`io.xfun:xfun-map-amap` | `1.0.0` |
| iOS（SwiftPM） | 产品 `XFunSDK`、`XFunMapAMap`（Swift 模块名仍为 `Dimina`） | `1.0.0` |
| Harmony（ohpm） | `@xfun/sdk`、`@xfun/map-amap` | `1.0.0` |
| Web / 编译器（npm） | `@xfun/container-sdk`、`@xfun/compiler`（命令 `xfun-cc`，兼容 `dmcc`） | `1.0.0` |

运行时标识：WebView User-Agent 追加 `XFunMiniProgram/<版本> miniProgram`；`getSystemInfo().SDKVersion` 为 XFun SDK 版本。Android FileProvider 授权名为 `${applicationId}.xfun.fileprovider`。

## 与上游的关系

- 开发分支：`parley`。上游 `main` 保持与 didi/dimina 同步。
- 内部代码（Kotlin 包 `com.didi.dimina`、类名、JS 桥接名、私有 `@dimina/*` 工作区包）保持上游命名，便于合并：`git fetch upstream && git merge upstream/main`。
- 改动见 `parley` 分支在 `e145e9d6` 之后的提交，以及 [NOTICE](./NOTICE)。
- 上游原文档：[docs/upstream/README.md](./docs/upstream/README.md)，其余文档在 [docs/](./docs/)。

## 许可

Apache License 2.0，见 [LICENSE](./LICENSE) 与 [NOTICE](./NOTICE)。
