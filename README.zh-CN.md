# osmodule

[English](README.md) | [简体中文](README.zh-CN.md)

[![CI](https://github.com/pill4r/osmodule/actions/workflows/ci.yml/badge.svg)](https://github.com/pill4r/osmodule/actions/workflows/ci.yml)
[![许可证：MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE.txt)

**一个精简、模块化的 DJI Osmo 安卓素材客户端。**

osmodule 是一个独立的 Android 客户端，用于浏览、预览和下载 DJI Osmo 相机中的素材。它不依赖 DJI SDK，自身没有账号系统、数据分析或云端上传功能。全新相机仍需先在 DJI Mimo 中完成一次激活，osmodule 才能连接。

本项目派生自开源项目 [Osmosis](https://github.com/KonradIT/osmosis)，但作为独立应用维护和发布。为保持插件 ABI 兼容性，项目继续使用原有 Android 包命名空间 `dev.konraditurbe.osmosis`；产品名称、发布产物和面向用户的存储目录均使用 osmodule。

## 为什么要做 osmodule？

DJI Mimo 覆盖了很多使用场景，但一部分用户只需要一条从 Osmo 相机到 Android 本地文件的快捷通道。当编辑器、账号、云服务和各种设备控制能力全部装入同一个应用时，会增加 APK 体积、权限数量和维护复杂度。

osmodule 遵循三条原则：

- **让 Base APK 保持专注：**只包含相机发现、配对、素材浏览、预览和下载等核心能力。
- **让专业功能按需安装：**Osmo 360 全景查看、Osmo 360 遥控/GPS 同步和 Pocket 4P 遥控均以独立 APK 形式提供。
- **让边界可以被强制执行：**模块使用明确的 Gradle 依赖；外部插件通过带版本、同签名的 Binder 协议通信，而不是把外部代码加载到 Base 中。

本项目不打算复刻 DJI Mimo 的全部功能。我们的目标是提供一个更小、可审计的本地素材客户端，并确保项目持续扩展时不会让 Base APK 再次变成单体应用。

## 功能

- 通过低功耗蓝牙发现相机并完成配对。
- 素材网格、缩略图和低分辨率流式预览。
- 可独立安装的 Osmo 360 交互式全景视频查看器，支持拖动视角和双指缩放；原始 OSV 视频会自动使用配对的 LRF 代理流打开。
- 将高分辨率素材断点续传至用户选择的视频目录，默认使用 `Movies/osmodule`、`Pictures/osmodule` 和 `Download/osmodule`。
- 设置入点和出点，并通过无损流复制完成裁剪。
- 显示相机连接、电量和存储状态。
- 多相机历史记录、收藏和删除。
- 模块管理器，可安装或删除经过签名校验的可选插件。
- 可选的 Osmo 360 遥控模块，提供低延迟局域网预览、R-SDK 控制、相机状态、BLE 唤醒和 GPS 同步。
- 实验性的可选 Pocket 4P 遥控模块，提供本地 HEVC 实时预览、拍照/录像/模式控制和云台控制。

应用不会导出或发送协议日志。开发者仍可通过 `adb logcat` 在本地查看 Android 日志。

## 已测试设备

目前，osmodule 仅在以下设备上进行过真机测试。本表表示本项目自身的测试范围，并不代表继承的协议代码可能识别的全部型号。

| 设备 | 测试状态 |
|---|---|
| Osmo Pocket 4 Pro（`OsmoPocket4P` / Pocket 4P） | 素材流程与遥控实时预览已在真机测试；远控操作仍属实验功能 |
| Osmo 360 | 已在真机测试 |

其他 Osmo 相机和 DJI 无人机尚未在 osmodule 中测试，目前不声明支持。代码库中可能仍保留从 Osmosis 继承的兼容路径。

## 安装与连接

使用正式版本时，先安装 Base APK，再打开“模块”，通过“从 GitHub 安装”按需安装 360° 查看器、Osmo 360 遥控或 Pocket 4P 遥控插件。Base 会从[最新发布的 GitHub Release](https://github.com/pill4r/osmodule/releases/latest)下载对应 APK，校验其官方包名、Manifest 协议与签名证书，通过后才会打开 Android 系统安装器。离线安装和开发构建仍可使用“选择本地 APK”。

Debug APK 必须来自同一次 CI：先安装 `app-debug.apk`，再通过“选择本地 APK”选择该次任务的 `panorama360-debug.apk`、`rsdk-debug.apk` 和/或 `pocket4p-debug.apk`。Debug Base 不能安装 GitHub Release 中使用正式签名的插件。在 Xiaomi、Redmi 或 POCO 设备上，请为每个已安装插件完成“权限与自启动”设置，允许 HyperOS 在 Base 请求时启动其受保护进程。

全新相机必须先在 DJI Mimo 中完成一次激活；在此之前，相机会关闭自身 Wi-Fi 接入点，osmodule 无法连接。

1. 打开蓝牙和 Wi-Fi，然后启动 osmodule。
2. 授予所需的附近设备权限。
3. 打开相机，并在相机列表中选择它。
4. 在相机上确认配对，然后允许 Android 加入相机 Wi-Fi。
5. 打开“模块”，为当前连接的相机按需安装可选插件。
6. 浏览、预览或下载素材。安装查看器插件后，Osmo 360 的 OSV 视频会在其中打开。

## 隐私

osmodule 只通过相机不连接互联网的本地网络（通常为 `192.168.2.1`）与相机通信，不包含数据
分析、账号系统或云端上传。由于不同相机型号通过无 TLS 的本地 HTTP 提供素材，应用允许明文
HTTP，但业务代码只提供相机本地 URL。Base 唯一由用户主动触发的互联网操作，是在点击
“从 GitHub 安装”后下载官方插件 APK。

## 构建

要求：Android SDK 36、JDK 21；目标设备为 Android 10（API 29）或更高版本。

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleDebug \
  :plugins:pocket4p:assembleDebug
```

输出文件：

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/panorama360/build/outputs/apk/debug/panorama360-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`
- `plugins/pocket4p/build/outputs/apk/debug/pocket4p-debug.apk`

三个插件都是可选的。只需要普通素材访问的用户仅安装 Base APK 即可。如果没有提供本地 `keystore.properties`，Release 构建会生成未签名 APK；Base 和插件的正式版本必须使用相同的签名谱系。CI 会把 Base、Panorama 360、Osmo 360 遥控和 Pocket 4P 遥控 APK 分别上传为四个工作流产物；GitHub 的 Artifact 下载仍是 ZIP，而应用内安装使用最新已发布 Release 中的原始 APK 资源。

## 文档

- [架构说明](docs/ARCHITECTURE.zh-CN.md) — APK 边界、模块关系和插件信任模型。
- [开发指南](docs/DEVELOPMENT.zh-CN.md) — 代码归属、质量门禁和发布流程。
- [插件 SDK](docs/PLUGIN_SDK.zh-CN.md) — 版本化 AAR 的使用方法和插件实现协议。
- [插件分发模型](docs/PLUGIN_MODEL.zh-CN.md) — 官方目录、签名与威胁边界。
- [素材协议参考（英文）](MEDIA_PROTOCOL.md) — 逆向分析得到的 BLE、DUML 和 HTTP 行为。
- [协议地图（英文）](docs/01-protocol-map.md) — 数据包级命令和传输索引。
- [路线图](ROADMAP.zh-CN.md) — 已完成工作、硬件验证缺口和计划功能。
- [第三方声明（英文原文）](THIRD_PARTY_NOTICES.md) — 使用的研究成果和授权组件。

## 开源项目、参考资料与许可证

App 的“关于”页面也会展示同一份致谢。“改编”表示 osmodule 包含经修改或重新实现的集成；
“参考”表示该项目用于协议研究。

| 项目 | osmodule 的使用方式 | 许可证 / 条款 |
|---|---|---|
| [Osmosis](https://github.com/KonradIT/osmosis) | 原始 Android 应用与继承的基础实现 | MIT |
| [OpenPocketCine](https://github.com/erik-sutton95/OpenPocketCine) | Pocket 4P DUML 命令、状态解释与 HEVC 实时预览行为 | Apache-2.0 |
| [yesbhautik/osmo360](https://github.com/yesbhautik/osmo360) | Osmo 360 UDP/TCP 实时预览握手与 AVC 分帧 | MIT |
| [PanoForge](https://github.com/Belenos-Toutatis/PanoForge) | Osmo 360 标定元数据与投影研究 | MIT |
| [dji-remote](https://github.com/dimadesu/dji-remote) | 引入/改编 DUML 帧格式、CRC、字节读写器与命令载荷 | MIT |
| [o-gs](https://github.com/o-gs) | 逆向协议参考 | 以各仓库为准 |
| [osmo-download](https://github.com/SemiConscious/osmo-download) | Osmo 素材发现与下载研究 | 仅作参考；仓库中没有许可证文件 |
| [DJI-Wifi-Connect](https://github.com/sniffingpickles/DJI-Wifi-Connect) | DJI 相机 Wi-Fi 连接与配对参考 | MIT |
| [lib-osmo-ble](https://github.com/yigitkonur/lib-osmo-ble) | DJI Osmo BLE 协议参考 | MIT |
| [DJI Osmo GPS Controller Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo) | 移植并修改 R-SDK 帧格式、CRC、BLE 会话、命令、GPS 与状态处理 | 示例代码 MIT；R-SDK 协议适用 DJI EULA |

详细署名与随包许可证正文请参阅[第三方声明](THIRD_PARTY_NOTICES.md)和 [LICENSES](LICENSES/)。

osmodule 自有代码及继承自 Osmosis 的基础采用 [MIT 许可证](LICENSE.txt)；改编和第三方部分适用上表列出的各自许可证与条款。本项目与 DJI 没有关联，也未获得 DJI 的授权或认可。DJI 和 Osmo 是其各自权利人的商标。
