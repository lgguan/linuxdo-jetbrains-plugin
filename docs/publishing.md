# 1.0.0 发布步骤与材料

发布前完成[发布检查清单](release-checklist.md)，确保源码、安装包与验证结果对应同一版本。

## GitHub

1. 创建空仓库，不初始化 README、许可或 .gitignore。
2. 在本地检查 `git status` 和首个提交，将实际仓库地址填入下列命令：

```sh
git remote add origin <实际仓库地址>
git push -u origin main
```

3. 等待三平台构建和 Plugin Verifier 工作流通过，完成剩余原生验收。
4. 对通过验收的提交创建 `v1.0.0` 标签并推送；创建 GitHub Release，上传同一构建的 ZIP 和 `.sha256`，发布说明采用根目录 CHANGELOG 中的 1.0.0 条目。

版本由 `build.gradle.kts` 定义。后续代码变化后应重新测试、构建和计算校验值。

## Marketplace 表单材料

| 字段 | 内容 |
| --- | --- |
| 名称 | Linux Do (API Docs) |
| Plugin ID | com.lgguan.linuxdo.plugin |
| 版本 | 1.0.0 |
| 作者 | lgguan |
| 许可 | MIT |
| IDE 范围 | 2026.2 / 262.* |
| 安装包 | `build/distributions/linuxdo-jetbrains-plugin-1.0.0.zip` |
| 源码、问题反馈链接 | 待创建 GitHub 仓库后填写 |
| 隐私说明链接 | 待填写仓库内 `docs/privacy.md` 的公开链接 |
| 图标及截图 | 截图素材：[话题与正文](images/topics.jpg)、[登录](images/login.jpg)、[创建话题](images/create-topic.jpg)、[设置](images/settings.png)。提交前检查账号与本机路径是否需要遮挡，并准备有权使用的图标 |

### 描述（可粘贴）

Linux Do (API Docs) is an unofficial Linux Do community client for JetBrains IDEs, independently developed by lgguan and licensed under MIT. It is not affiliated with or endorsed by Linux Do or JetBrains.

Browse and search topics in a documentation-style interface, create topics and replies, receive notifications, and synchronize reading progress. Navigate long discussions by floor and use the configurable boss key to hide and restore topic tabs.

The plugin uses a private JCEF browser session and JetBrains PasswordSafe for saved credentials. LinuxDo DoH (`https://ldh.ddd.oaifree.com/query-dns`) is enabled by default; custom HTTPS resolvers and system DNS remain available in settings. The selected DoH service receives domain queries. There is no independent analytics or telemetry service.

Requires JetBrains IDE 2026.2 (262.*) with its bundled JBR and enabled JCEF plugin. See the repository's cross-platform and privacy guides for platform validation and local data handling.

Restart the IDE when prompted after installation, updates, disabling, or uninstallation. Dynamic unloading is not supported.

### 更新说明（可粘贴）

1.0.0 — Initial public release: documentation-style topic browsing, search, posting and replies, notifications, reading progress, floor navigation, private JCEF login, a boss key, and LinuxDo DoH enabled by default.

## 手动提交

完成验收后，登录 JetBrains Marketplace，在个人作者账户下新建插件，上传上述 ZIP，填写描述、许可、隐私及仓库链接和界面材料。核对解析出的 Plugin ID、版本、作者及 IDE 范围，再提交审核并保存审核结果。不要上传源码压缩包、解压后的目录、非本次发布的构建或本机日志。

本仓库不保存 Marketplace 令牌或签名私钥；手动发布不要求将这些凭据写入源码。
