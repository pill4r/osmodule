# 开发指南

[English](DEVELOPMENT.md) | [简体中文](DEVELOPMENT.zh-CN.md)

本文档是当前 osmodule 代码库的维护地图，说明代码应该放在哪里、哪些边界必须保持稳定，以及变更在合并前必须通过哪些检查。架构设计依据和 Binder 安全模型见[架构说明](ARCHITECTURE.zh-CN.md)。

## 工具链与应用

本仓库是一个 Gradle 多项目 Android 构建，固定使用 Android Gradle Plugin 8.13.2、Gradle 8.14.5、Kotlin 1.9.24、compile/target SDK 36 和 Java 21 字节码。最低支持 Android 10（API 29）。

仓库会生成三个应用：

| 应用 | Gradle 模块 | Application ID | 用途 |
|---|---|---|---|
| osmodule Base | `:app` | `dev.konraditurbe.osmosis` | 相机发现和本地素材流程 |
| 360° 查看器插件 | `:plugins:panorama360` | `dev.konraditurbe.osmosis.plugin.panorama360` | 可选的 Osmo 360 交互式播放 |
| R-SDK 插件 | `:plugins:rsdk` | `dev.konraditurbe.osmosis.plugin.rsdk` | 可选的 Osmo 360 遥控、预览和 GPS 同步 |

Base 包保留历史 Osmosis 命名空间，以维持升级路径和插件 ABI 兼容性。产品名称和发布身份为 osmodule。

## 模块归属

| 模块 | 负责 | 不应负责 |
|---|---|---|
| `:app` | 应用启动、外部插件发现、信任校验和模块管理器 | 相机协议实现或插件界面 |
| `:core:common` | 跨功能共享的小型 Android 工具 | 功能专属行为 |
| `:core:module-api` | 进程内模块协议和注册表 | 具体功能实现 |
| `:core:plugin-api` | 可发布、带版本的 AIDL、协议常量和引导 Provider | 插件业务逻辑 |
| `:core:camera-session` | 进程内相机会话租约 | Android 界面或传输实现 |
| `:core:panorama-renderer` | 标定解码和全景渲染基础能力 | 素材导航或插件生命周期 |
| `:protocol:duml` | DUML 帧、CRC、命令和载荷编解码 | Android 框架依赖 |
| `:protocol:rsdk` | R-SDK 数据包和状态编解码 | 蓝牙或界面代码 |
| `:transport:ble` | BLE 扫描、型号识别和 GATT 客户端 | 页面或素材策略 |
| `:camera:media` | 相机/无人机素材会话、清单、寻址、HTTP 和下载 | Activity 和模块注册 |
| `:feature:media` | Base 素材页面和核心素材模块 | 外部插件实现 |
| `:feature:panorama360` | 插件内部的 360° 素材查看器 | Base 应用装配或 R-SDK 控制 |
| `:feature:control-rsdk` | 可复用的 R-SDK 控制器、实时预览和插件页面 | Base 应用装配 |
| `:plugins:panorama360` | 360° 查看器应用、Binder 服务和清单 | Base 界面或素材浏览 |
| `:plugins:rsdk` | 插件应用、服务和清单 | Base 界面或对 Base 内部实现的直接访问 |

新增代码时，将线协议放入 `protocol`，Android 传输放入 `transport`，相机素材语义放入 `camera`，面向用户的流程放入 `feature`，最终装配才放入应用模块。下层模块不能为了少写几行胶水代码而依赖上层模块。

## 依赖与安全边界

外部插件是独立 APK。Base 绝不能使用 `DexClassLoader`、合并插件权限或直接读取插件资源。
通信仅限于已发布的 Plugin SDK 和 Android 自带的 parcelable；monorepo 开发时会把 SDK Maven
坐标替换为本地 `:core:plugin-api` 源码。兼容的官方插件必须：

1. 暴露约定的 `dev.konraditurbe.osmosis.plugin.BIND` 服务 action；
2. 持有签名级 `dev.konraditurbe.osmosis.permission.BIND_PLUGIN` 权限；
3. 匹配 `OfficialPluginCatalog` 中的包名、插件 ID 和能力策略；
4. 使用与 Base 相同的签名谱系；
5. 返回与 AIDL 协议版本兼容的描述符；
6. 只通过服务返回的不可变 `PendingIntent` 启动私有界面。

相机访问必须互斥。进程内客户端使用 `CameraSessionCoordinator`；Base 在打开素材传输前还会查询插件的运行状态。Binder 错误采取失败关闭策略。

## 构建与质量门禁

提交变更前运行仓库级检查：

```sh
./gradlew test lint \
  :app:assembleRelease \
  :app:assembleDebug \
  :plugins:panorama360:assembleRelease \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleRelease \
  :plugins:rsdk:assembleDebug \
  :core:plugin-api:publishPluginSdkPublicationToLocalPluginSdkRepository
```

该命令会检查纯 JVM 模块、Android 单元测试、所有模块的 Lint 任务、三个 APK 的两个构建
类型，以及可发布的 SDK AAR。Release 构建会执行 R8 代码/资源收缩；通过自定义清单元数据加载
的 `AppModule` 入口必须提供 consumer keep 规则。未提供签名材料的检出目录会按设计生成未签名的 Release APK。

快速迭代时，可以先检查发生变更的模块：

```sh
./gradlew :protocol:duml:test
./gradlew :camera:media:testDebugUnitTest
./gradlew :feature:media:lintDebug
```

协议变更应包含确定性的单元测试或经过脱敏的二进制 fixture。禁止提交网络抓包、BLE snoop、凭据、设备日志、签名密钥或原始用户素材。仓库的 `.gitignore` 已排除这些常见的敏感或超大数据来源。

## 仅设备可验证的内容

单元测试无法覆盖 Android 安装包流程、OEM 后台限制、蓝牙硬件或相机网络。这些区域发生变更时，除了运行 Gradle 之外还必须进行设备验证。

全新安装后的插件引导流程可以在没有相机时检查：

```sh
adb shell am instrument -w \
  dev.konraditurbe.osmosis.test/dev.konraditurbe.osmosis.plugins.PluginBindingInstrumentation
```

对于与相机通信有关的变更，请在 Pull Request 或 Issue 中记录手机型号、Android 版本、相机型号和相机固件。切勿向仓库添加凭据或未经脱敏的抓包。

## 发布流程

`.github/workflows/ci.yml` 会在每次推送到 `main`、每个以 `main` 为目标的 Pull Request，以及手动触发时运行。该工作流不需要任何签名 Secret。任务会校验 Gradle Wrapper、恢复 Gradle 缓存、运行全部 JVM 和 Android 单元测试、执行全仓库 Lint、构建三个 Debug APK，并将测试报告、Lint 报告和每个 APK 分别作为独立工作流产物保留 14 天。即使一个 Artifact 中只有一个 APK，GitHub 仍会把 Actions Artifact 包装为 ZIP。同一个 ref 的旧任务会自动取消，避免过时提交继续占用 Runner。

匹配 `v*` 的应用标签会触发 `.github/workflows/build_app.yml`。CI 会验证 Gradle Wrapper、运行
完整质量门禁、使用相同签名配置构建 Base 和两个插件、分别上传每个 APK，并创建带有稳定
原始资源文件名（`app-release.apk`、`panorama360-release.apk` 和 `rsdk-release.apk`）的 GitHub
Release。所有门禁通过后，工作流会发布该 Release，使模块管理器的最新版本链接可以使用。
匹配 `plugin-sdk-v*` 的标签会触发 `.github/workflows/publish_plugin_sdk.yml`，校验标签与版本一致后把 Release AAR 发布到 GitHub Packages。

仓库需要配置 `APP_KEYSTORE`、`STOREPASSWORD`、`KEYPASSWORD` 和 `KEYALIAS` 四个 Secret。它们只在 CI 内解码，绝不能提交到仓库。发布 Tag 前：

1. 当应用或插件产物发生变化时，更新对应版本号；
2. 运行完整质量门禁；
3. 在硬件上检查受影响的相机或插件流程；
4. 行为变化时更新 README 中的支持声明、`ROADMAP.md` 和协议文档；
5. 安装 Base 与每个受影响的已签名插件，验证签名谱系和 Binder 握手。

## 文档维护

文档是代码变更的一部分：

- 产品范围、安装流程或支持设备发生变化时，同时更新 `README.md` 和 `README.zh-CN.md`；
- 模块归属或 APK 边界发生变化时，同时更新 `ARCHITECTURE.md` 和 `ARCHITECTURE.zh-CN.md`；
- 开发流程或质量门禁发生变化时，同时更新 `DEVELOPMENT.md` 和 `DEVELOPMENT.zh-CN.md`；
- 协议或 Maven 版本变化时，同时更新 `PLUGIN_SDK.md` 和 `PLUGIN_SDK.zh-CN.md`；
- 信任、签名或分发变化时，同时更新 `PLUGIN_MODEL.md` 和 `PLUGIN_MODEL.zh-CN.md`；
- 优先级或硬件验证缺口发生变化时，同时更新 `ROADMAP.md` 和 `ROADMAP.zh-CN.md`；
- 测得新的线协议行为时更新 `MEDIA_PROTOCOL.md` 和 `docs/01-protocol-map.md`；
- 明确区分已经硬件验证的事实和推断；
- 引入外部成果时保留 `THIRD_PARTY_NOTICES.md` 中的署名和许可证原文。
