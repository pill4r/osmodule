# 插件分发与信任模型

[English](PLUGIN_MODEL.md) | [简体中文](PLUGIN_MODEL.zh-CN.md)

## 决策

osmodule 官方发行版采用**仅官方、同签名的插件模型**，不是开放插件市场。当前目录包含：

| 插件 | 包名 | 必需能力 |
|---|---|---|
| 360° 查看器 | `dev.pillar.osmodule.plugin.panorama360` | `camera.media.360-view` |
| Osmo 360 遥控（R-SDK） | `dev.pillar.osmodule.plugin.rsdk` | `camera.rsdk.remote-panel`、`camera.session.owner` |
| Pocket 4P 遥控 | `dev.pillar.osmodule.plugin.pocket4p` | `camera.pocket4p.remote-panel`、`camera.session.owner` |

Base 的“模块”页面只显示目录条目；已安装或用户选择的 APK 只要包名、插件 ID 或能力集合超出
目录，就会被拒绝。仅仅使用相同签名密钥仍然不够。

每个目录条目还包含最新已发布 GitHub Release 中对应 APK 的 HTTPS 链接。模块管理器只允许
仓库 Release 链接，限制下载大小，并使用经过 Android 验证的互联网网络，避免请求被相机的
本地 Wi-Fi 绑定截获。下载完成后，APK 必须通过与本地所选文件相同的包名、描述符、能力和
签名校验，之后才会打开需要用户确认的 Android 系统安装器。

## 为什么选择这种模型

插件请求可能包含相机网络句柄、本地素材 URL、Wi-Fi 凭据或 BLE 相机地址。插件还可以声明
独占相机会话，并返回界面启动令牌。这些能力不适合仅凭公开 Intent action 自动授予信任。
“同签名 + 显式目录”让每项新增能力都必须通过一次经过审查的 Base 发布决策。

这种隔离仍然具有直接的产品价值：可选代码和权限从 Base 移出；插件可独立安装或删除；
进程崩溃相互隔离；实现可以持续演进，而 Binder 协议保持稳定。

## 发布

- Base 和所有官方 Release 插件都从经过审查的源码构建，并使用相同发布签名谱系；
- 应用发布标签为 `vX.Y.Z`；CI 构建 Base 与三个插件 APK，将每个 APK 分别上传为独立的
  Actions Artifact，并在质量门禁通过后发布 Release；
- Plugin SDK 标签为 `plugin-sdk-vX.Y.Z`；CI 将 AAR 发布到 GitHub Packages；
- Debug 构建共享 Android 调试签名，仅用于本地测试；
- 已发布 Release 中的资源提供模块管理器所用的稳定原始 APK 链接；Actions Artifact 会被
  包装成 ZIP、存在有效期，因此不作为应用内安装源；
- Base 会先验证下载或本地选择的 APK，再交给需要用户确认的 Android Package Installer。

## 新增官方插件

1. 保留稳定的 Application ID 和插件 ID；
2. 在 Plugin SDK 中定义尽可能小的能力和请求键；
3. 在 `OfficialPluginCatalog` 中加入身份、必需能力和完整的允许能力集合；
4. 在 Base 中加入包/Provider 可见性和“模块”条目；
5. 添加描述符、APK 验证和 Binder 边界测试；
6. 使用官方签名谱系构建 APK，并记录其权限和收到的相机数据；
7. 同时更新中英文架构、SDK 和分发文档。

扩展能力应按扩展权限同等审查。现有官方插件只有在 Base 显式允许之后才能新增能力。

## 第三方与派生项目路径

第三方作者可以使用 MIT 许可的 SDK 和源码，构建自己的 Base/插件组合。他们需要使用自己的
签名谱系，并修改自己的 Base 目录；其 APK 不能作为受信插件安装到官方 osmodule Base。
如果未来要支持任意第三方插件，必须另行设计用户同意、权限收敛、撤销和数据披露机制，
不能仅靠放宽签名或目录检查来实现。
