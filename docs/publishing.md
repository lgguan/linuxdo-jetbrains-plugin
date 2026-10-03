# 1.0.2 发布步骤与材料

发布前完成[发布检查清单](release-checklist.md)，确保源码、安装包与验证结果对应同一版本。

## GitHub

1. 检查 `git status`，提交本版本源码、更新说明及验证材料。
2. 推送到现有仓库：

```sh
git push origin main
```

3. 等待三平台构建和 Plugin Verifier 工作流通过，完成剩余原生验收。
4. 对通过验收的提交创建 `v1.0.2` 标签并推送；创建 GitHub Release，上传同一构建的 ZIP 和 `.sha256`，发布说明采用根目录 CHANGELOG 中的 1.0.2 条目。

版本由 `build.gradle.kts` 定义。后续代码变化后应重新测试、构建和计算校验值。

## Marketplace 表单材料

| 字段 | 内容 |
| --- | --- |
| 名称 | Linux Do (API Docs) |
| Plugin ID | com.lgguan.linuxdo.plugin |
| 版本 | 1.0.2 |
| 作者 | lgguan |
| 许可 | MIT |
| IDE 范围 | 2026.2 / 262.* |
| 安装包 | `build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip` |
| 源码、问题反馈链接 | https://github.com/lgguan/linuxdo-jetbrains-plugin · https://github.com/lgguan/linuxdo-jetbrains-plugin/issues |
| 隐私说明链接 | https://github.com/lgguan/linuxdo-jetbrains-plugin/blob/main/docs/privacy.md |
| 图标及截图 | 当前实际 IDE 截图：[话题与正文](images/topics.png)、[登录](images/login.png)、[创建话题与预览](images/create-topic-preview.png)、[回复](images/reply.png)、[板块](images/category-picker.png)、[标签](images/tag-picker.png)、[设置](images/settings.png)。使用示例内容；不包含凭据或本机日志 |

### 描述（可粘贴）

Linux Do (API Docs) is an unofficial Linux Do community client for JetBrains IDEs, independently developed by lgguan and licensed under MIT. It is not affiliated with or endorsed by Linux Do or JetBrains.

Browse and search topics in a documentation-style interface with categories, tags, unread status and matching search excerpts. Navigate directly to a matching floor, return after a jump, and refresh without losing your position. Create topics and replies with shared icon toolbars and on-demand previews. Resume native forum drafts across the IDE and website, with two-second autosave and explicit conflict choices. Search category paths and descriptions, and use the web-style tag picker with usage counts and removable selected chips. Receive notifications and synchronize reading progress; use the configurable boss key to hide and restore topic tabs.

The plugin uses a private JCEF browser session and JetBrains PasswordSafe for saved credentials. LinuxDo DoH (`https://ldh.ddd.oaifree.com/query-dns`) is enabled by default; custom HTTPS resolvers and system DNS remain available in settings. The selected DoH service receives domain queries. There is no independent analytics or telemetry service.

Requires JetBrains IDE 2026.2 (262.*) with its bundled JBR and enabled JCEF plugin. See the repository's cross-platform and privacy guides for platform validation and local data handling.

Restart the IDE when prompted after installation, updates, disabling, or uninstallation. Dynamic unloading is not supported.

### 更新说明（可粘贴）

1.0.2 — Added inline Boost bubbles with permission-aware sending and withdrawal, persistent account-scoped reading synchronization, advanced search and combined tag filters. Fixed first-open read tracking with tool-window focus. Moved floor numbers and unread markers to the right without shifting content when marked read. See CHANGELOG and plugin XML for complete release notes.

## 手动提交

完成验收后，登录 JetBrains Marketplace，打开现有插件 34669 的版本管理，在 Stable 通道新增版本，上传上述 ZIP，填写描述、许可、隐私及仓库链接和界面材料。核对解析出的 Plugin ID、版本、作者及 IDE 范围，再提交审核并保存审核结果。不要上传源码压缩包、解压后的目录、非本次发布的构建或本机日志。

本仓库不保存 Marketplace 令牌或签名私钥；手动发布不要求将这些凭据写入源码。
