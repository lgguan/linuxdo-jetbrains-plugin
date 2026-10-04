# 1.0.3 发布验证

2026-10-04，将发布前内部 1.0.4 的 Boost 修复合并到公开版本 1.0.3，包含个人内容集中入口、书签管理、通知闭环、Markdown 编辑辅助、模块图标与登录 Tab、Boost 举报及用户资料。完整更新见[CHANGELOG](../CHANGELOG.md)。

Plugin ID 为 `com.lgguan.linuxdo.plugin`，Marketplace ID 为 `34669`，IDE 兼容范围为 `262.*`。安装或更新后按提示重启。

安装包为 `build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip`，SHA-256 为 `37ffeba74a250bfb773eb2ccaf18d3ec5c6a7cc55c03004a0c2e57d20a0af35e`。

- 桌面测试 442 项通过，失败、错误和跳过均为 0，打包与结构检查通过。
- Plugin Verifier 对 `IU-262.10968.63` 判定 [Compatible](reference/release-1.0.3-plugin-verifier.txt)。
- 在全新源码目录重新测试和构建，安装包 SHA-256 与发布包一致。
- 实际 Windows IDEA [发帖、回复、草稿及浏览 130 项](reference/release-1.0.3-composer-ide.txt)和[阅读器、通知及 Boost 89 项](reference/release-1.0.3-reader-ide.txt)通过，包含实际鼠标点击头像、垃圾桶和举报入口。
- [登录页物理键盘 11 项](reference/release-1.0.3-browser-keyboard-ide.txt)通过，覆盖用户名到密码的 Tab、反向 Shift+Tab 和外部窗口焦点遍历。
- 隔离浏览器 [Boost 25 项](reference/boost-actions-browser.json)通过，举报展开无额外权限请求，表单和提交仍按服务器权限校验。

原生组合运行在切换测试窗口时发生焦点超时；使用既有独立模式重新验证上述场景，均通过。历史开发包的记录保留，不能代替本页同版本最终包的结果。机器可读摘要见[最终安装包验证](reference/release-1.0.3-summary.json)。

GitHub [v1.0.3](https://github.com/lgguan/linuxdo-jetbrains-plugin/releases/tag/v1.0.3) 已发布，包含安装包、源码归档及各自的 SHA-256 文件。发布标签对应源码提交 `d1b9dc2ab6928a5e36cac9b777a327599b7f5858`；[Windows、macOS、Linux 构建与远程 Plugin Verifier](https://github.com/lgguan/linuxdo-jetbrains-plugin/actions/runs/37201805786)全部通过。发布后的文档回执单独提交到 main，不改变该标签或安装包。

GitHub 服务端计算的四个附件 SHA-256 和文件大小均与本地文件一致，源码附件另经公开下载逐字节比对，见[GitHub 发布回执](reference/release-1.0.3-github.json)。浏览器直接下载遇到连接中断，源码改用 curl 下载完成；安装包与校验文件按服务端摘要核对。

Marketplace [Stable 1.0.3](https://plugins.jetbrains.com/plugin/34669-linux-do-api-docs-/versions/stable/1187229) 已审核通过，更新 ID 为 `1187229`，最终核对状态为 **Approved**，旧版本保留。市场下载包经过平台重新封装，ZIP 容器 SHA-256 不同；展开 ZIP/JAR 后的全部 1,864 项文件内容与本地验证包一致，见[上传回执](reference/release-1.0.3-marketplace.json)。

论坛写入验收使用内存模拟传输，不向真实论坛提交帖子、举报或阅读计时。Windows 原生交互单独验证；macOS/Linux 原生界面尚未实测，远程构建结果与原生交互验证分开记录。
