# 1.0.0 截图更新与 Marketplace 重新上传

2026-10-01，根据用户授权更新 README、推送源码、替换审核中的 1.0.0，并通过网页安排所有可选产品的最低支持版本验证。安装范围仍为 `262.0 — 262.*`。

## 截图与说明

README 和 Marketplace 使用同一组最新截图：话题阅读、空白登录窗口、新建话题预览、板块选择、标签选择、引用回复、设置，共 7 张。图片来自当前发布包在真实 IntelliJ IDEA 中运行的生产界面；话题及草稿为示例数据，没有发送真实帖子或回复，也没有保存真实论坛草稿。

截图报告：`build/host-smoke/3b18f1a2-23c3-4c8e-8bf3-827edc4b0046/result.txt`，`IDE_UI_PASS=true`。Marketplace 原 4 张截图已删除，新增 7 张并点击 Save；重新加载后确认数量和图片加载状态。README 同步说明新话题直接打开/恢复草稿、工具栏与按需预览、类别和标签搜索、引用回复、草稿同步，以及普通 429 与 Cloudflare 人机验证的区别。

## 发布包与本地检查

文件：`linuxdo-jetbrains-plugin-1.0.0.zip`。

本地 SHA-256：`27e134ee6d3bb43ee490bf8db05bd5a61ee15c1e3ce069bfe5f247bdc49ed42c`。

- 全量编译关闭 Kotlin 增量缓存，290 项桌面单元测试通过；打包与结构检查通过。
- 干净目录重新构建的 ZIP 与工作区哈希相同：`build/release-check/9df9406b-7b67-4e2d-b1df-dab751f33e19/`。
- 当前包实际 IDEA 桌面回归 105 项通过：`build/host-smoke/e50f7e09-c039-4c3e-b2b2-60d2a307ea08/result.txt`。
- 本地 Plugin Verifier 1.410 对 IDEA 2026.2.3（`IU-262.10968.63`）结果为 Compatible。

本轮没有重新进行真实账号草稿接续测试；此前记录及其对应包哈希保留在[完整计划验收核对](plan-completion-audit.md)，不当作本包的新联调结果。

## Marketplace 替换

旧更新 `1183593` 已下载备份并核对插件 ID/版本后删除。新更新为 [1185426 / 1.0.0](https://plugins.jetbrains.com/plugin/34669-linux-do-api-docs-/edit/versions/stable/1185426)，当前状态 **Under review**。最新版描述及 What's New 已在网页显示。

重新下载的新 ZIP SHA-256 为 `b3cb7076f59ba22be4023500d127979a57eb17097d793869496744e27e5ffd30`。ZIP 容器字节不同；展开 ZIP 及其 JAR 后，1,689 个文件逐项哈希完全相同，没有新增、删除或内容改变，确认上传内容对应本地验证包。备份、上传回执及逐项对照位于忽略的 `output/release-1.0.0/`，不含凭据。

## 最低支持版本 Schedule Verification

下表 13 项均在新版更新页面通过 Schedule Verification 提交，接口返回 HTTP 200、`scheduled=true`，并确认网页出现对应产品与版本。记录是提交及核对时的结果；后续实时状态以 Marketplace 为准。Android Studio 和 MPS 在 262 分支只有下面的 RC/EAP 可选。

| 产品 | 最低可选支持版本 / 构建 | 验证 ID | 核对结果 |
| --- | --- | --- | --- |
| IntelliJ IDEA | 2026.2 / IU-262.8665.258 | 5917099 | Compatible |
| PhpStorm | 2026.2 / PS-262.8665.265 | 5917100 | Compatible |
| WebStorm | 2026.2 / WS-262.8665.259 | 5917101 | Compatible |
| PyCharm | 2026.2 / PY-262.8665.309 | 5917110 | Compatible |
| RubyMine | 2026.2 / RM-262.8665.308 | 5917116 | Compatible |
| CLion | 2026.2 / CL-262.8665.262 | 5917117 | Compatible |
| GoLand | 2026.2 / GO-262.8665.270 | 5917118 | Compatible |
| DataGrip | 2026.2 / DB-262.8665.272 | 5917119 | Compatible |
| Rider | 2026.2 / RD-262.8665.328 | 5917120 | Compatible |
| JetBrains Gateway | 2026.2 / GW-262.8665.250 | 5917121 | Compatible |
| RustRover | 2026.2 / RR-262.8665.323 | 5917122 | Compatible |
| Android Studio | Rabbit 1 · 2026.2.1 RC 2 / AI-262.9437.185.2621.16444166 | 5917123 | Compatible |
| MPS | 2026.2-EAP1 / MPS-262.9437.166 | 5917124 | 已安排，等待结果 |

Marketplace 还自动完成了 IDEA 2026.2.3 的 Compatible 检查与 IDE 安装运行检查，后者显示 No issues occurred。

Supported Products 另有 5 项，目前无法从网页安排其最低支持构建：

| 产品 | 网页限制 |
| --- | --- |
| IntelliJ IDEA Community | IDE 下拉菜单存在，但没有 262 分支构建 |
| PyCharm Community | IDE 下拉菜单存在，但没有 262 分支构建 |
| DataSpell | IDE 下拉菜单存在，但没有 262 分支构建 |
| JetBrains Client | Schedule Verification 的 IDE 下拉菜单没有此产品 |
| Code With Me Guest | Schedule Verification 的 IDE 下拉菜单没有此产品 |

这些项目未安排不在支持范围内的旧版本，也没有记作验证通过。可选版本清单、13 项提交回执和网页核对记录保存在 `output/release-1.0.0/` 与 `output/browser-tools/verification-inventory.json`。
