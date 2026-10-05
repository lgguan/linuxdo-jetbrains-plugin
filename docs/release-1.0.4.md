# 1.0.4 发布验证

2026-10-05，更新阅读设置默认值、Boost 与用户资料头像显示及失败回退，README 添加 Linux Do 友情链接。完整更新见[CHANGELOG](../CHANGELOG.md)。

Plugin ID 为 `com.lgguan.linuxdo.plugin`，Marketplace ID 为 `34669`，IDE 兼容范围为 `262.*`。安装或更新后按提示重启。

安装包为 `build/distributions/linuxdo-jetbrains-plugin-1.0.4.zip`，SHA-256 为 `f0ba6eb0f2c3256dfcd0e0ce0c713bc98c28ac9a4bc2d89d8a71b5f3d659185d`。

- 桌面测试 446 项通过，失败、错误和跳过均为 0，打包与结构检查通过。
- Plugin Verifier 对 `IU-262.10968.63` 判定 [Compatible](reference/release-1.0.4-plugin-verifier.txt)。
- 全新源码目录重新测试和构建，安装包 SHA-256 与发布包一致。
- 实际 Windows IDEA [阅读器、通知与 Boost 94 项](reference/release-1.0.4-reader-ide.txt)通过，覆盖头像设置应用、楼层保留、公开资料、局部更新和分页追加。
- 隔离浏览器 [Boost 与头像 41 项](reference/release-1.0.4-boost-browser.json)通过，覆盖 Unicode 首字母、24px/48px 占位、图片失败回退和键盘资料入口。

机器可读摘要见[最终安装包验证](reference/release-1.0.4-summary.json)。

本地发布校验完成；GitHub Release 和 Marketplace 上传状态尚待核对。

Windows 原生交互使用隔离配置和模拟论坛响应；macOS/Linux 原生界面尚未实测。
