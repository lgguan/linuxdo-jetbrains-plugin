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

GitHub 发布入口为 [v1.0.3](https://github.com/lgguan/linuxdo-jetbrains-plugin/releases/tag/v1.0.3)，Marketplace 发布至现有插件的 Stable 通道。市场仅有 1.0.0、1.0.1、1.0.2，未发现 1.0.3；提交后记录新版本链接和审核状态。旧版本保留。

论坛写入验收使用内存模拟传输，不向真实论坛提交帖子、举报或阅读计时。Windows 原生交互单独验证；macOS/Linux 原生界面尚未实测，远程构建结果与原生交互验证分开记录。
