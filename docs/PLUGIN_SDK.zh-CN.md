# 插件 SDK

[English](PLUGIN_SDK.md) | [简体中文](PLUGIN_SDK.zh-CN.md)

`dev.konraditurbe.osmodule:plugin-sdk:1.1.0` 是 osmodule 跨 APK 协议的独立 Android AAR。
它包含 `IOsmosisPlugin.aidl`、`PluginContract`、`PluginDescriptor` 和可复用的签名权限保护
`PluginBootstrapProvider`，不依赖 Base，也不依赖任何相机、传输、协议或界面实现。

## 在其他仓库中使用

GitHub Packages 的 Maven 包即使公开，也需要具有包读取权限的凭据：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.github.com/pill4r/osmodule") {
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                    ?: System.getenv("GITHUB_ACTOR")
                password = providers.gradleProperty("gpr.key").orNull
                    ?: System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
```

```kotlin
// 插件应用 build.gradle.kts
dependencies {
    implementation("dev.konraditurbe.osmodule:plugin-sdk:1.1.0")
}
```

仓库内的参考插件也使用这一坐标；只有在本 monorepo 开发时，根构建才会将其替换为本地
`:core:plugin-api` 源码项目。

## 插件的最小结构

插件是拥有独立 Application ID 和生命周期的普通 Android 应用。它的清单必须：

- 为 `PluginContract.BIND_ACTION` 导出一个服务；
- 使用 `PluginContract.BIND_PERMISSION` 保护该服务；
- 在服务元数据中声明 ID、名称、版本、协议范围和能力；
- 在 `<applicationId>.bootstrap` 下导出同一权限保护的 `PluginBootstrapProvider`；
- 保持功能 Activity 为私有（`exported=false`）。

Binder 服务必须校验 Base 调用方，返回与清单完全一致的描述符，并只返回不可变、显式的
PendingIntent。请求 Bundle 必须使用 `PluginContract` 键，并按不可信输入处理。最小面板插件
可参考 `plugins/panorama360`；运行状态与权限管理可参考 `plugins/rsdk`。

## 版本规则

Maven 版本与 Binder 协议版本相互独立：

SDK 1.1.0 新增 Pocket 4P 官方包/插件身份、远控面板能力和可选的相机型号请求键。这些都是
新增的 Bundle 常量，因此 Binder 协议仍为版本 1，现有兼容插件继续有效。

- SDK 的补丁版或次版本可以新增可选键、常量或辅助类，而不改变协议 1；
- 删除或改变 AIDL 方法、键语义或必填值时，必须升级 Binder 协议；
- 插件声明自己实际支持的闭区间协议范围；
- Base 只选择范围包含 `PluginContract.PROTOCOL_VERSION` 的插件。

修改版本时，应同时更新 `gradle.properties`、测试和本文档的中英文版本。推送
`plugin-sdk-vX.Y.Z` 标签会把 Release AAR 发布到 GitHub Packages。本地验证命令：

```sh
./gradlew :core:plugin-api:test \
  :core:plugin-api:publishPluginSdkPublicationToLocalPluginSdkRepository
```

本地 Maven 仓库位于 `core/plugin-api/build/repo`。

## SDK 不代表获得信任

公开 SDK 允许其他项目编译协议。官方 Base 仍只接受 `OfficialPluginCatalog` 中列出的同签名
身份；发布 AAR 并不等于建立开放插件市场。详见[插件分发模型](PLUGIN_MODEL.zh-CN.md)。
