# 发布检查清单

每次发布均使用同一源码提交和安装包完成以下检查。命令及工具参数见[项目说明](../README.md)和[回归工具](../tools/README.md)。

- 确认 `build.gradle.kts` 中的版本、IDE 支持范围、插件描述和[更新记录](../CHANGELOG.md)一致。
- 运行单元测试、`buildPlugin` 和 `verifyPlugin`，检查测试跳过原因及安装包内容。
- 对目标 IDE 运行 `runPluginVerifier`，确认兼容性结果并评估 API 警告。
- 按[跨平台指南](cross-platform.md)完成目标系统与架构的原生验证；单元测试或 API 检查不能代替原生交互验证。
- 验证登录退出、话题浏览、发帖回复、导航频控和老板键；禁用、更新或卸载时应提示重启，重启后检查插件状态与资源释放。
- 按[网络诊断指南](network-diagnostics.md)检查默认 LinuxDo DoH、自定义及禁用选项。
- 运行干净目录构建，核对 ZIP 的 SHA-256，确保发布包可由对应源码复现。
- 检查源码与发布附件不包含账号、凭据、本机日志或诊断快照；核对[隐私说明](privacy.md)和许可文件。
- 准备仓库链接、插件截图、ZIP 及校验文件，按[发布步骤](publishing.md)提交。

测试报告和诊断产物保存在 `build/`，记录安装包校验值及 OS、IDE、JBR、JCEF 环境。发布时以待发布包的实际结果为准。
