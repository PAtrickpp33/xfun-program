# Dimina Android SDK

[![dimina](https://jitpack.io/v/didi/dimina.svg)](https://jitpack.io/#didi/dimina)

## 系统要求

- Android SDK: 最低 API 26 (Android 8.0)
- Java 17
- Android Gradle Plugin 8.0+
- 仅支持 ARM64 架构 (arm64-v8a)

## 快速接入

### 步骤 1: 添加 JitPack 仓库

在项目根目录的 `settings.gradle` 或 `settings.gradle.kts` 文件中添加：

```kotlin
dependencyResolutionManagement {
    repositories {
        // 其他仓库...
        maven { url = uri("https://jitpack.io") }
    }
}
```

### 步骤 2: 添加依赖

在应用模块的 `build.gradle` 或 `build.gradle.kts` 文件中添加：

#### Groovy DSL

```groovy
dependencies {
    // Dimina 核心库
    implementation 'com.github.didi.dimina:dimina:latest.release'
}
```

#### Kotlin DSL

```kotlin
dependencies {
    // Dimina 核心库
    implementation("com.github.didi.dimina:dimina:latest.release")
}
```

### 步骤 3: 初始化 SDK

在应用的 `Application` 类中初始化 Dimina SDK：

```kotlin
import com.didi.dimina.Dimina

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        Dimina.init(this, Dimina.DiminaConfig.Builder()
            // 是否启用调试模式：影响日志显示，并允许 pageFrame 初始化 vConsole
            // 调试模式不检测 App 是否已更新，都会进入 JSSDK 和 JSApp 的更新检测逻辑
            .setDebugMode(true)
            // 可选：必须在 SDK 初始化时设置，原生路径解析与逻辑层会使用同一前缀
            .setVirtualFilePrefix("host-file://")
            .build()
        )
    }
}
```

#### 调试模式与 vConsole

在 `Dimina.init` 前配置 `setDebugMode(true)` 时，SDK 会在加载 pageFrame 时追加 `?vconsole=1`。该开关由宿主控制，对 Debug 和 Release AAR 均生效，默认关闭。JSSDK 直接依赖 vConsole，并随 pageFrame 静态同步打包；只有检测到该启用标记时，pageFrame 才会在 render 初始化前同步初始化 vConsole。

调试模式下，逻辑线程的 `console.log/info/warn/error/debug` 会转发到 vConsole，`wx.request` 的请求和成功/失败结果显示在 Network 面板。启动阶段日志会暂存，待页面渲染通道就绪后送达（最多 200 条）。

`MiniProgram Storage` 面板通过小程序 Storage API 读取当前 appId 的原生 MMKV 数据，包含现行存储和旧版存储中仍有效的键值，打开面板或点击 Refresh 时刷新；支持以 JSON 新增、编辑值和确认删除，成功后重新读取 MMKV；操作立即生效。vConsole 自带的 Storage 面板仍表示 WebView 的浏览器存储。上述逻辑线程采集仅在 Android SDK `setDebugMode(true)` 时启用；目前不包含 `uploadFile`、`downloadFile` 和 WebSocket 流量。


### 步骤 4: 启动小程序

将编译好的小程序压缩包放入 `app/src/main/assets/jsapp` 文件夹，文件夹以小程序 ID 命名。仓库示例工程会在构建时从根目录 `shared/jsapp` 自动复制资源到该目录。每个小程序文件夹需包含以下内容：

1. `config.json` - 小程序配置文件，包含以下字段：

```json5
{
  "appId": "wx92269e3b2f304afc", // 小程序唯一标识
  "name": "小程序名称",
  "path": "example/index", // 小程序入口路径
  "versionCode": 1, // 启动小程序时会根据版本号确认是否需要更新
  "versionName": "1.0.0"
}
```

2. `[appId].zip` - 小程序代码包，文件名需与 appId 一致

目录结构示例：

```txt
assets/
  └── jsapp/
      ├── wx92269e3b2f304afc/
      │   ├── config.json
      │   └── wx92269e3b2f304afc.zip
      └── wxbaf4b47de04f1d8a/
          ├── config.json
          └── wxbaf4b47de04f1d8a.zip
```

内置包会在启动时按 `versionCode` 解压到应用沙盒目录后运行。动态下发、远程下载和 `wx.getUpdateManager` 的职责边界请参考[小程序包更新说明](../docs/MiniProgram-Update.md)。

启动小程序：

```kotlin
// 从 config.json 文件创建 MiniProgram 对象
val miniProgram = MiniProgram(
    appId = "wx92269e3b2f304afc",  // 小程序唯一标识
    name = "小程序名称",      // 小程序名称
    versionCode = 1,            // 版本号
    versionName = "1.0.0",      // 版本名称
    path = "example/index",      // 小程序入口路径
    // updateManifestUrl = "https://example.com/jsapp/wx92269e3b2f304afc.json" // 可选：远程更新 manifest
)

// 启动小程序
Dimina.getInstance().startMiniProgram(context, miniProgram)
```

多任务模式（`setEnableMultiTask(true)`，默认开启）下，每个小程序在最近任务里有自己的卡片。再次打开已在后台运行的小程序只会把它的任务移到前台，不会再触发 ready 回调；需要区分时改用 `launchMiniProgram`：

```kotlin
when (Dimina.getInstance().launchMiniProgram(activity, miniProgram)) {
    Dimina.LaunchResult.STARTED -> { /* 等待 setMiniProgramReadyListener 回调 */ }
    Dimina.LaunchResult.RESUMED -> { /* 已在前台，页面早已加载 */ }
}

// 最近任务卡片默认显示 MiniProgram.name 和宿主图标，可按小程序自定义
Dimina.getInstance().setTaskAppearanceProvider { program ->
    MiniProgramTaskAppearance(label = program.name, icon = iconBitmapFor(program.appId))
}
```

宿主主动关闭小程序时，调用生命周期安全的关闭入口：

```kotlin
val accepted = Dimina.getInstance().closeMiniProgram("wx92269e3b2f304afc")

// 主线程销毁全部小程序实例，保留持久化数据
Dimina.getInstance().destroyAllMiniPrograms()
```

该方法必须在主线程调用。返回 `true` 表示运行中的页面已接受退出请求；返回 `false` 表示 `appId` 为空或当前没有对应 Activity。不要直接调用 `Activity.finish()` 或 `MiniApp.clear()`，否则会绕过退出生命周期和跨小程序来源恢复。

卸载已安装包时，默认保留小程序 Storage 和持久文件；传入 `clearUserData = true` 才会一并清除：

```kotlin
Dimina.getInstance().uninstallMiniProgram("wx92269e3b2f304afc")
Dimina.getInstance().uninstallMiniProgram("wx92269e3b2f304afc", clearUserData = true)
```

并发更新、待更新包清理和各端完整行为见[小程序包更新说明](../docs/MiniProgram-Update.md)。

## 模块说明

- **dimina**: 核心库，包含小程序运行环境、UI 组件和生命周期管理
- **engine-qjs**: QuickJS JavaScript 引擎，提供 JS 执行环境
