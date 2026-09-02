# 模块化架构

[English](ARCHITECTURE.md) | [简体中文](ARCHITECTURE.zh-CN.md)

osmodule 由一个核心素材应用、一个内置但可选的 360° 查看器，以及一个独立安装的遥控插件 APK 组成。插件拥有自己的代码、资源、权限和 Android 生命周期；osmodule Base 不使用 `DexClassLoader`，不加载外部资源，也不会把可选功能所需的权限合并进 Base APK。

## APK 与模块关系

```text
osmodule Base — dev.konraditurbe.osmosis
  :app
    ├─ :core:module-api
    ├─ :core:plugin-api       （AIDL 客户端协议）
    ├─ :feature:media          （配对、浏览、预览、下载）
         ├─ :camera:media
         ├─ :transport:ble
         ├─ :protocol:duml
         └─ :core:camera-session
    └─ :feature:panorama360    （可选的内置 Osmo 360 查看器）

osmodule R-SDK 插件 — dev.konraditurbe.osmosis.plugin.rsdk
  :plugins:rsdk
    ├─ :core:plugin-api       （AIDL 服务端协议）
    └─ :feature:control-rsdk
         ├─ :protocol:rsdk
         ├─ :transport:ble
         └─ :core:camera-session
```

`core:camera-session` 是各 APK 内部传输层共用的进程内互斥锁。Base 在打开素材传输前，还会通过 Binder 检查 R-SDK 插件的运行状态，防止后台 GPS 会话与 Base 争用相机的 BLE 链路。

## AIDL 边界

所有插件都暴露同一个服务 action：

```text
dev.konraditurbe.osmosis.plugin.BIND
```

版本 1 的 `IOsmosisPlugin` 接口刻意保持精简：

- `getProtocolVersion()` 用于选择通信协议版本。
- `getDescriptor()` 返回插件 ID、版本、支持的协议范围和能力。
- `getRuntimeState()` 报告跨进程资源占用状态，例如是否存在活动的相机会话。
- `createPanelIntent(request)` 返回一个不可变的 `PendingIntent`，用于启动插件自己的界面。

Base 只会在完成一次操作所需的时间内绑定插件，之后立即解绑。插件 Activity 保持 `exported=false`；带能力的 `PendingIntent` 是唯一的界面启动令牌。每个插件还会在同一个签名权限保护下暴露一个引导 `ContentProvider`。Base 在每次短时服务绑定之前调用该 Provider；同步 IPC 会启动或确认插件进程，而不违反 OEM 的后台 Activity 策略，并在 Base 继续绑定前返回。

系统的 `FLAG_STOPPED` 不用于判断插件是否就绪，因为 HyperOS 可能杀死插件进程但仍让安装包保持非 stopped 状态。蓝牙、定位、前台服务和通知权限均由 R-SDK 插件自身请求，不属于 Base。

## 信任校验

Base 在使用插件前会执行以下全部检查：

1. 只发现与 Base `<queries>` 中显式声明的插件 action 匹配的服务。
2. 要求服务以 `dev.konraditurbe.osmosis.permission.BIND_PLUGIN` 导出；这是 Base 拥有的签名级权限。
3. 比较插件 APK 的签名者 SHA-256 与 Base 当前的签名谱系。
4. 校验清单中的 ID、版本、协议和能力，再将其与 Binder 返回的描述符比较，以发现“发现服务”和“建立绑定”之间发生的身份变化。
5. 要求插件服务中的 Binder 调用方使用 Base 的包名。Android 签名权限仍是主要授权机制，包名检查是额外防护。

因此，每个 Release APK 都必须使用同一签名谱系。Debug APK 会自然地共享 Android 调试密钥。Gradle 的 Release 配置从根目录的同一个 `keystore.properties` 读取签名信息。

## 安装与更新

“模块”页面会显示始终启用的核心素材功能、内置可选模块、每个模块支持的相机型号，以及外部插件的兼容性错误。360° 查看器和遥控模块都声明仅支持 `osmo360`；素材核心不限制相机型号，因此适用于所有支持的相机。本地插件安装流程如下：

1. 用户通过 Android 文件选择器选取 APK。
2. Base 将 APK 复制到私有缓存，并校验其准确包名和签名证书。
3. Base 把只读 `content://` URI 交给 Android Package Installer。
4. Android 请求用户确认安装或更新；普通应用无法静默安装。
5. 安装成功后，Base 重新发现服务并打开插件自己的权限引导页。

Xiaomi、Redmi 和 POCO 系统还会把 OEM 自启动 AppOp 纳入此流程。当 HyperOS 拒绝自启动时，即使包可见性、签名者和签名权限校验全部通过，有效的服务绑定和 Provider 获取仍可能被拒绝。Base 会把这种情况报告为自启动策略失败，并从“模块”页面打开 HyperOS 的单应用权限编辑器；Base 不会尝试自行授予 OEM AppOp。

## 运行流程

### 打开 R-SDK 控制

```text
在 Base 中选择相机
  → Base 关闭素材 BLE、Wi-Fi 和 datalink
  → 发现插件并校验签名与协议
  → 调用受保护的引导 ContentProvider，等待插件进程就绪
  → 绑定 IOsmosisPlugin
  → 插件创建包含目标相机的不可变 PendingIntent
  → 私有 R-SDK Activity 请求自己的权限并建立连接
  → Base 与发现服务解绑
```

遥控和 GPS 在插件内共享同一个 `RsdkSessionHub`。遥控 Activity 结束时会移除自己的消费者，但 GPS 可以作为前台服务继续运行。如果 GPS 或已转入后台的遥控面板仍占用会话，`getRuntimeState()` 会让 Base 阻止素材连接，直到该会话结束。

## 构建产物

```sh
./gradlew test \
  :app:assembleDebug \
  :plugins:rsdk:assembleDebug
```

输出文件：

- `app/build/outputs/apk/debug/app-debug.apk`
- `plugins/rsdk/build/outputs/apk/debug/rsdk-debug.apk`

全新安装后的引导和 Binder 路径有一个不需要相机或 UI 自动化的设备回归测试。安装 Base、R-SDK 插件及测试 APK，让插件保持 `stopped/notLaunched` 状态，然后运行：

```sh
adb shell am instrument -w \
  dev.konraditurbe.osmosis.test/dev.konraditurbe.osmosis.plugins.PluginBindingInstrumentation
```

## 边界规则

1. Base 与外部插件只能通过 `:core:plugin-api` AIDL 和 Android parcelable 通信。
2. 外部代码和资源永远不能通过类加载方式进入 Base。
3. 可选权限和服务必须归属于插件自己的清单。
4. 协议模块不能依赖 UI 或应用模块。
5. Base 被杀死后插件必须仍然有用；插件死亡或卸载后 Base 必须能够正常降级。
6. Binder 错误必须对相机所有权和插件身份校验采取失败关闭策略。
