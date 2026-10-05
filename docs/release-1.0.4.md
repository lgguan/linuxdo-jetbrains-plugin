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

GitHub [v1.0.4](https://github.com/lgguan/linuxdo-jetbrains-plugin/releases/tag/v1.0.4) 已发布，包含安装包、源码归档及各自的 SHA-256 文件。发布标签对应源码提交 `e74c556c5dab3126b68659465d5973bb905352b5`；[Windows、macOS、Linux 构建与远程 Plugin Verifier](https://github.com/lgguan/linuxdo-jetbrains-plugin/actions/runs/37273849528)全部通过。

GitHub 服务端计算的四个附件 SHA-256 和文件大小与本地文件一致，见[GitHub 发布回执](reference/release-1.0.4-github.json)。源码归档包含该提交的全部 427 个文件，文本按统一换行核对，二进制和引擎资源按原始字节核对。

Marketplace [Stable 版本列表](https://plugins.jetbrains.com/plugin/34669-linux-do-api-docs-/versions)已上传 1.0.4，更新 ID 为 `1187670`，核对状态为 **Under review（审核中）**；1.0.3 和其他历史版本保留。平台重新封装了 ZIP 容器，下载包 SHA-256 为 `29ea1649af0ea15bde0675a666c57a4c5f3e6aa3d8502491f62447d5d40c31d2`；展开 ZIP/JAR 后的全部 1,864 项文件内容与本地验证包一致，见[Marketplace 上传回执](reference/release-1.0.4-marketplace.json)。审核通过后才会在市场公开提供更新。

上传和发布后的文档回执单独提交到 main，不改变发布标签和安装包。浏览器回读市场包时发生连接中断，改用 curl 下载后完成逐文件核对，未重复上传。

Windows 原生交互使用隔离配置和模拟论坛响应；macOS/Linux 原生界面尚未实测。
