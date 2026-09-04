# 模块化架构

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE.zh-CN.md)

osmodule 由一个精简的 Base APK 和三个可选的官方插件 APK 组成。360° 查看器、Osmo 360
遥控（R-SDK）和 Pocket 4P 遥控分别拥有自己的代码、资源、权限和 Android 生命周期。Base 不使用 `DexClassLoader`，
不读取插件资源，也不会把可选插件组件合并到自身 APK。

## APK 与模块关系

```text
Base — dev.konraditurbe.osmosis
  :app
    ├─ :core:module-api
    ├─ :core:plugin-api                 AIDL 客户端协议
    └─ :feature:media                   配对、浏览、普通预览、下载
         ├─ :camera:media
         ├─ :transport:ble
         ├─ :protocol:duml
         └─ :core:camera-session

360° 查看器 — dev.konraditurbe.osmosis.plugin.panorama360
  :plugins:panorama360
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:panorama360
         └─ :core:panorama-renderer

Osmo 360 遥控（R-SDK）— dev.konraditurbe.osmosis.plugin.rsdk
  :plugins:rsdk
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:control-rsdk
         ├─ :protocol:rsdk
         ├─ :transport:ble
         └─ :core:camera-session

Pocket 4P 遥控 — dev.konraditurbe.osmosis.plugin.pocket4p
  :plugins:pocket4p
    ├─ dev.konraditurbe.osmodule:plugin-sdk:1.1.0
    └─ :feature:control-pocket4p
         ├─ :core:common
         ├─ :core:module-api
         ├─ :core:camera-session
         ├─ :camera:media
         └─ :protocol:duml
```

三个插件的构建文件依赖版本化 Maven 坐标，而不是直接依赖 `:core:plugin-api`。在本仓库中，
Gradle 会把该坐标替换为本地源码项目，方便原子化开发；仓库外的插件可以只使用已发布的
AAR，无需包含 Base。官方参考插件仍复用仓库中的实现层库，但这是源码复用，不是对 Base
APK 或 Base 运行时的依赖。

## 运行时边界

所有插件都暴露 `dev.konraditurbe.osmosis.plugin.BIND`。协议 v1 刻意只保留四个调用：

- `getProtocolVersion()` 选择通信协议；
- `getDescriptor()` 返回身份、版本、协议范围和能力；
- `getRuntimeState()` 报告跨进程资源占用，例如活动的相机会话；
- `createPanelIntent(request)` 返回用于启动插件私有界面的不可变 `PendingIntent`。

Base 每次只为一个操作短暂绑定，随后立即解绑。插件 Activity 保持 `exported=false`，
PendingIntent 是界面启动令牌。每个插件还提供受签名权限保护的共享引导 Provider，在短时
绑定前启动或确认插件进程，兼容会清理后台应用的 OEM Android 系统。

Base 打开 360° 视频时，只传递标题、Osmo 型号键、本地预览 URL，以及已经连接到相机的
Android `Network`。查看器把自身进程绑定到该网络进行播放，并在关闭时恢复原进程网络。
Base 不再打包查看器 Activity。

## 仅官方插件的信任策略

osmodule 官方构建有意只接受官方插件。自动发现或用户本地选择的 APK 必须同时满足：

1. 准确包名存在于 Base 的官方目录；
2. 清单中的插件 ID 与目录一致；
3. 包含必需能力，且没有声明目录未允许的能力；
4. 服务和引导 Provider 使用 Base 拥有的签名级权限；
5. APK 签名谱系与 Base 的签名谱系存在交集；
6. 插件协议范围包含 Base 当前协议；
7. Binder 返回的描述符与签名清单中的描述符完全一致。

插件服务还会检查 Binder 调用方 UID 是否拥有 Base 包名。SDK 对外开放是为了可复现开发和
派生项目，并不意味着任意第三方 APK 会被官方 Base 信任。详见[插件分发模型](PLUGIN_MODEL.zh-CN.md)。

## 相机会话所有权

`core:camera-session` 是每个 APK 内部传输层共用的进程内锁。Base 在打开素材传输前，还会
查询声明 `camera.session.owner` 的插件。Osmo 360 遥控和 GPS 在插件进程中共享一个 R-SDK
会话中心；Pocket 4P 遥控则通过相同的跨进程所有权协议报告 DUML 会话。身份和所有权查询
遇到 Binder 错误时采取失败关闭。

## 构建产物

```sh
./gradlew test lint \
  :app:assembleDebug \
  :plugins:panorama360:assembleDebug \
  :plugins:rsdk:assembleDebug \
  :plugins:pocket4p:assembleDebug
```

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/panorama360/build/outputs/apk/debug/panorama360-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`
- `plugins/pocket4p/build/outputs/apk/debug/pocket4p-debug.apk`

## 边界规则

1. Base 与插件只能通过版本化 Plugin SDK 和 Android parcelable 通信。
2. 外部代码和资源永远不能通过类加载方式进入 Base。
3. 可选界面、权限和服务必须属于插件自己的 APK。
4. 协议模块不能依赖界面或应用模块。
5. Base 必须容忍插件死亡或卸载，插件也必须容忍 Base 被杀死。
6. 新增官方包名、插件 ID 或能力时，必须显式修改目录和文档。
