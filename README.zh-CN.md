# osmodule

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/pill4r/osmodule/actions/workflows/ci.yml/badge.svg)](https://github.com/pill4r/osmodule/actions/workflows/ci.yml)
[![许可证：MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)

**一个精简、模块化的 DJI Osmo 安卓素材客户端。**

osmodule 是一个独立的 Android 客户端，用于浏览、预览和下载 DJI Osmo 相机及部分 DJI 无人机中的素材。它不依赖 DJI SDK，不需要账号，不包含数据分析、云端上传或设备激活功能。

本项目派生自开源项目 [Osmosis](https://github.com/KonradIT/osmosis)，但作为独立应用维护和发布。为了保持升级路径和插件 ABI 兼容性，Android 包命名空间仍为 `dev.konraditurbe.osmosis`；产品名称、发布产物和面向用户的存储目录均使用 osmodule。

## 为什么要做 osmodule？

DJI Mimo 覆盖了很多使用场景，但一部分用户只需要一条从 Osmo 相机到 Android 本地文件的快捷通道。当编辑器、账号、云服务和各种设备控制能力全部装入同一个应用时，会增加 APK 体积、权限数量和维护复杂度。

osmodule 遵循三条原则：

- **让 Base APK 保持专注：**只包含相机发现、配对、素材浏览、预览和下载等核心能力。
- **让专业功能按需安装：**Osmo 360 全景查看、遥控和 GPS 同步均以独立 APK 形式提供。
- **让边界可以被强制执行：**模块使用明确的 Gradle 依赖；外部插件通过带版本、同签名的 Binder 协议通信，而不是把外部代码加载到 Base 中。

本项目不打算复刻 DJI Mimo 的全部功能。我们的目标是提供一个更小、可审计的本地素材客户端，并确保项目持续扩展时不会让 Base APK 再次变成单体应用。

## 功能

- 通过低功耗蓝牙发现相机并完成配对。
- 素材网格、缩略图和低分辨率流式预览。
- 可独立安装的 Osmo 360 交互式全景视频查看器，支持拖动视角和双指缩放；原始 OSV 视频会自动使用配对的 LRF 代理流打开。
- 将高分辨率素材断点续传至用户选择的视频目录，默认使用 `Movies/osmodule`、`Pictures/osmodule` 和 `Download/osmodule`。
- 设置入点和出点，并通过无损流复制完成裁剪。
- 显示相机电量、拍摄模式和存储状态。
- 多相机历史记录、收藏和删除。
- 模块管理器，可安装或删除经过签名校验的可选插件。
- 可选的 Osmo 360 遥控台，提供低延迟局域网预览、R-SDK 控制、相机状态、BLE 唤醒和 GPS 同步。
- 支持部分 DJI 无人机的 QuickTransfer 素材访问。

应用不会把协议日志保存到共享文件，也不提供向上游作者发送日志的功能。开发者仍可通过 `adb logcat` 在本地查看正常的 Android 日志。

## 支持的设备

| 设备 | 状态 |
|---|---|
| Osmo Nano | 已在真机验证 |
| Osmo Action 5 Pro / Xtra Edge Pro | 已在真机验证 |
| Osmo Action 6 | 已在真机验证 |
| Osmo 360 | 已验证素材访问、交互式全景查看器和遥控模块 |
| Osmo Pocket 3 | 已在真机验证 |
| Osmo Pocket 4 / 4 Pro | 已在真机验证 |
| DJI Mavic 3 QuickTransfer | 已在真机验证 |
| 其他 Osmo 相机和 DJI 无人机 | 实验性支持 |

## 安装与连接

使用正式版本时，先安装 Base APK，再打开“模块”，通过“从 GitHub 安装”按需安装 360° 查看器或 R-SDK 远控插件。Base 会从[最新发布的 GitHub Release](https://github.com/pill4r/osmodule/releases/latest)下载对应 APK，校验其官方包名、Manifest 协议与签名证书，通过后才会打开 Android 系统安装器。离线安装和开发构建仍可使用“选择本地 APK”。

Debug APK 必须来自同一次 CI：先安装 `app-debug.apk`，再通过“选择本地 APK”选择该次任务的 `panorama360-debug.apk` 和/或 `rsdk-debug.apk`。在 Xiaomi、Redmi 或 POCO 设备上，如果 HyperOS 阻止 R-SDK 插件的 Binder 服务，请为该插件启用“自启动”。

1. 打开蓝牙和 Wi-Fi，然后启动 osmodule。
2. 授予所需的附近设备权限。
3. 打开相机，并在相机列表中选择它。
4. 在相机上确认配对，然后允许 Android 加入相机 Wi-Fi。
5. 打开“模块”，按需安装 360° 查看器和/或 Osmo 360 遥控插件。
6. 浏览、预览或下载素材。安装查看器插件后，Osmo 360 的 OSV 视频会在其中打开。

## 隐私

osmodule 只通过相机不连接互联网的本地网络（通常为 `192.168.2.1`）与相机通信，不包含数据
分析、账号系统、激活服务或云端上传。由于不同相机型号通过无 TLS 的本地 HTTP 提供素材，
应用允许明文 HTTP，但业务代码只提供相机本地 URL。Base 唯一由用户主动触发的互联网操作，
是在点击“从 GitHub 安装”后下载官方插件 APK。

## 构建

要求：Android SDK 36、JDK 21；目标设备为 Android 10（API 29）或更高版本。

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleDebug
```

输出文件：

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/panorama360/build/outputs/apk/debug/panorama360-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`

两个插件都是可选的。只需要普通素材访问的用户仅安装 Base APK 即可。如果没有提供本地 `keystore.properties`，Release 构建会生成未签名 APK；Base 和插件的正式版本必须使用相同的签名谱系。CI 会把 Base、Panorama 360 和 R-SDK APK 分别上传为三个工作流产物；GitHub 的 Artifact 下载仍是 ZIP，而应用内安装使用最新已发布 Release 中的原始 APK 资源。

## 文档

- [架构说明](docs/ARCHITECTURE.zh-CN.md) — APK 边界、模块关系和插件信任模型。
- [开发指南](docs/DEVELOPMENT.zh-CN.md) — 代码归属、质量门禁和发布流程。
- [插件 SDK](docs/PLUGIN_SDK.zh-CN.md) — 版本化 AAR 的使用方法和插件实现协议。
- [插件分发模型](docs/PLUGIN_MODEL.zh-CN.md) — 官方目录、签名与威胁边界。
- [素材协议参考（英文）](MEDIA_PROTOCOL.md) — 逆向分析得到的 BLE、DUML 和 HTTP 行为。
- [协议地图（英文）](docs/01-protocol-map.md) — 数据包级命令和传输索引。
- [路线图](ROADMAP.zh-CN.md) — 已完成工作、硬件验证缺口和计划功能。
- [第三方声明（英文原文）](THIRD_PARTY_NOTICES.md) — 使用的研究成果和授权组件。

## 致谢与许可证

osmodule 保留 Osmosis 作者和贡献者基于 MIT 许可证发布的工作及其署名。协议实现还参考了 [o-gs](https://github.com/o-gs)、[dji-remote](https://github.com/dimadesu/dji-remote)、[osmo-download](https://github.com/SemiConscious/osmo-download)、[DJI-Wifi-Connect](https://github.com/sniffingpickles/DJI-Wifi-Connect)、[lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble) 和 DJI 的 [Osmo GPS Controller 演示](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)。Osmo 360 实时画面传输改编自采用 MIT 许可证的 [osmo360 Android 原型](https://github.com/yesbhautik/osmo360)，基于工厂标定的投影实现参考了采用 MIT 许可证的 [PanoForge](https://github.com/Belenos-Toutatis/PanoForge) 元数据和映射研究。

项目采用 [MIT 许可证](LICENSE.txt)。这是一个独立的第三方项目，与 DJI 没有关联，也未获得 DJI 的授权或认可。DJI 和 Osmo 是其各自权利人的商标。
