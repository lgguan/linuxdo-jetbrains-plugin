# 1.0.2 发布验证

2026-10-03，按用户要求将发布前内部 1.0.3、1.0.4 修复统一合入公开版本 1.0.2。包含 Boost 浮层与气泡、正文已读补传、首次打开自动已读、右侧楼层号及未读标记，以及当前工作区的搜索、标签和编辑器配色改进。完整内容见 [CHANGELOG](../CHANGELOG.md)。

## 安装包与最终验证

- 安装包：`linuxdo-jetbrains-plugin-1.0.2.zip`。
- SHA-256：`ee1ea573d3c00eaa3af9ce63dcd80906552683ea93ed79c11e095ac8e46caed6`。
- Plugin ID：`com.lgguan.linuxdo.plugin`；Marketplace ID：`34669`；支持 IDE 2026.2 / `262.*`。安装或更新后按提示重启。
- 桌面单元与模拟传输测试 339 项通过，失败、错误及跳过均为 0；`buildPlugin`、`verifyPlugin` 通过。
- 实际 Windows IDEA / `IU-262.10968.63` 全量交互验收 190 项通过，包含草稿、搜索、标签、Boost、导航、实时配色、窗口缩放及正文已读。见[原始检查记录](reference/release-1.0.2-ide.txt)。启动探针改为在测试工作线程异步轮询，避免等待首次浏览器加载时阻塞 EDT，修正后重新完成全部检查。
- 深浅主题和 360 像素窄窗口布局检查 81 项通过；小蓝点消失前后用户名、楼层号与正文位置不变。见[布局记录](reference/release-1.0.2-layout.json)及[实际截图](right-floor-metadata-verification.md)。
- Plugin Verifier 1.410 对目标 IDE 的结论为 [Compatible](reference/release-1.0.2-plugin-verifier.txt)。
- 在全新源码目录重新执行桌面测试、构建及安装包验证，ZIP 的 SHA-256 与上述安装包一致。新增 Boost 元数据沿用供应商资源的原始字节保护，暂存区逐项校验通过，避免 Windows 检出转换换行后破坏校验值。

机器可读摘要见[最终安装包验证](reference/release-1.0.2-summary.json)。写入测试使用隔离内存 HTTP 传输，没有向真实论坛提交 Boost、帖子或计时。输入法和恢复验证的范围说明保留在[Boost 验收](boost-read-sync-verification.md)；发布前内部包的历史结果不代替本页最终包的验证。

## 发布入口与 Marketplace 回执

- [GitHub v1.0.2](https://github.com/lgguan/linuxdo-jetbrains-plugin/releases/tag/v1.0.2)；[三平台构建与远程 Verifier](https://github.com/lgguan/linuxdo-jetbrains-plugin/actions/workflows/verify.yml)。
- [Marketplace 1.0.2 / 1186521](https://plugins.jetbrains.com/plugin/34669-linux-do-api-docs-/versions/stable/1186521)，已上传 Stable，上传后状态 **Under review**，保留原已审核的 1.0.1。
- 重新下载市场安装包，递归展开 ZIP/JAR 后 1,787 个文件逐项哈希相同，插件 ID 和版本均正确。市场 ZIP 容器哈希为 `ad60c29a0b757d1645f69f3a46b9d0742361d097a659e1a132d075ea8e2f0713`，容器字节不同但文件内容完全一致。见[上传核对回执](reference/release-1.0.2-marketplace.json)。

Marketplace 的审核与用户可见更新时间由平台处理，实时状态以版本页面为准。GitHub Release 附件使用本地验证的安装包和校验文件，并附当前源码包；不上传本机日志、浏览器配置或凭据。
