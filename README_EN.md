<p align="right"><a href="./README.md">中文 →</a></p>

# XFun SDK

XFun mini-program SDK: compile mini-program source and run it on Android, iOS, Harmony and Web. This is a modified fork of [didi/dimina](https://github.com/didi/dimina) (Apache-2.0), maintained by PAtrickpp33. It is not affiliated with or endorsed by Didi or the Dimina project.

## Published names

| Platform | Name | Version |
|---|---|---|
| Android (Maven) | `io.xfun:xfun-sdk`, `io.xfun:xfun-engine-qjs`, `io.xfun:xfun-map-amap` | `1.0.0` |
| iOS (SwiftPM) | products `XFunSDK`, `XFunMapAMap` (Swift module is still `Dimina`) | `1.0.0` |
| Harmony (ohpm) | `@xfun/sdk`, `@xfun/map-amap` | `1.0.0` |
| Web / compiler (npm) | `@xfun/container-sdk`, `@xfun/compiler` (CLI `xfun-cc`, `dmcc` kept) | `1.0.0` |

Runtime identity: WebView User-Agent gets `XFunMiniProgram/<version> miniProgram`; `getSystemInfo().SDKVersion` reports the XFun SDK version. The Android FileProvider authority is `${applicationId}.xfun.fileprovider`.

## Upstream

- Development branch: `parley`; `main` tracks didi/dimina.
- Internal code (Kotlin package `com.didi.dimina`, class names, JS bridge names, private `@dimina/*` workspace packages) keeps upstream names to ease merges: `git fetch upstream && git merge upstream/main`.
- Changes: commits on `parley` after `e145e9d6`, and [NOTICE](./NOTICE).
- Original upstream docs: [docs/upstream/README_EN.md](./docs/upstream/README_EN.md).

## License

Apache License 2.0, see [LICENSE](./LICENSE) and [NOTICE](./NOTICE).
