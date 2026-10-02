# 1.0.1 发布验证

发布日期：2026-10-02。完整变更见 [CHANGELOG](../CHANGELOG.md)。本版本包含正文布局、权限与媒体增强，以及连续缩放重绘、浏览器断线恢复和清理超时修复。

## 发布包

- 文件：`linuxdo-jetbrains-plugin-1.0.1.zip`
- SHA-256：`16783a44070b821180dc00b66732adf8acba607cc46e758519217646f365517a`
- 插件 ID：`com.lgguan.linuxdo.plugin`；Marketplace ID：`34669`。
- 支持 IDE：2026.2 / `262.*`。更新后按提示重启 IDE。

## 本版本验证

- 桌面单元测试：310 项，失败、错误和跳过均为 0。
- 实际 Windows IDEA `IU-262.10968.63`：135 项检查全部通过。覆盖连续缩放无需滚动重绘、断线重试、读取位置恢复、实时主题、登录刷新合并及自动已读。
- Plugin Verifier：`Compatible`；`buildPlugin`、`verifyPlugin` 通过。
- 全新源码目录构建通过，ZIP 与工作区安装包 SHA-256 完全一致。
- 本轮交互写入测试使用模拟传输，没有向真实论坛提交帖子操作。

本地报告位于 `build/test-results/test/`、`build/host-smoke/c32c5df5-a270-4ede-ba28-d21b4066711e/`、`build/reports/pluginVerifier/` 和 `build/release-check/dc4eece8-2108-4478-85e7-b634fa1b5308/`，不作为公开附件上传。

之前同一功能代码的 3,600 次连续缩放与运行时恢复验证详见[卡顿修复记录](browser-freeze-verification.md)。这些记录包含旧版本构建的校验值，不能作为本次安装包校验值使用。本地验证环境为 Windows；其他平台以 GitHub 三平台工作流结果为准。

## 发布入口

- [GitHub v1.0.1](https://github.com/lgguan/linuxdo-jetbrains-plugin/releases/tag/v1.0.1)
- [JetBrains Marketplace 版本](https://plugins.jetbrains.com/plugin/34669-linux-do-api-docs-/versions)

Marketplace 审核由平台处理，上传完成并不代表已对所有用户提供更新。
