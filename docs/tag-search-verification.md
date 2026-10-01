# 标签加载 Limit 无效修复验证

2026-10-01。标签接口曾固定发送 `limit=10`，真实论坛返回 HTTP 400、`Limit 无效`。现改为省略 `limit`，使用论坛配置的默认结果数量；保留关键词、板块、已选标签 ID 和 `filterForInput`。请求构造集中在 `DiscourseUrls.composerTags`，生产客户端直接使用。

协议依据：[Discourse Tags::Search](https://github.com/discourse/discourse/blob/main/app/services/tags/search.rb) 验证显式 limit，未提供时使用默认过滤行为。未假定 Linux Do 的具体配置值。

本文记录标签接口修复阶段的安装包。后续标签冻结、网页式标签布局及板块选择收起的当前包验证见 [选择器修复验证](picker-freeze-verification.md)。

## 本阶段安装包验证

| 检查 | 结果 |
| --- | --- |
| 真实接口：旧 `limit=10` | HTTP 400，`Limit 无效` |
| 真实接口：省略 limit，空关键词、板块 4 | HTTP 200，36 个结果 |
| 真实接口：省略 limit，关键词“软件”、板块 4 | HTTP 200，6 个结果，包含不可用标签及禁用标志 |
| 全量单元测试 | 290 项通过，0 失败、0 错误、0 跳过；新增参数编码、默认上限和可选板块契约测试 |
| 真实 IDEA 回归 | 99 项通过；新增生产 HTTP 客户端标签请求检查，在模拟服务器上限 5 时默认搜索正常，关键词、板块和选中 ID 保留 |
| `buildPlugin`、`verifyPlugin` | 通过 |
| Plugin Verifier 1.410 / IDEA 2026.2.3（IU-262.10968.63） | Compatible |

真实接口检查只读，`writes=0`，未打开编辑器、写草稿或发送帖子。报告：`build/tag-search/d89d6ee0-63aa-4fe1-9b91-1b98b4695ac4/result.json`。

实际 IDEA 报告：`build/host-smoke/eff74cfa-be0f-4115-a5f4-363f60d518c9/result.txt`，`IDE_UI_PASS=true`、`REAL_FORUM_WRITES=0`，EDT 心跳 P95 为 36.2096ms。

## 已通过的真实草稿接续证据

修复前的同轮安装包已通过 IDE→网页→IDE 草稿接续及清理：`build/draft-handoff/9b7a3046-9407-4464-b8b4-809c4335a852/result.json`。本次只修改标签请求构造，草稿读取、保存、删除逻辑没有修改。

将真实接续测试实际安装的插件 JAR 与当前 ZIP 比较，回复窗口、草稿会话、HTTP/JCEF 客户端、会话版本及 HTTP 错误处理相关 40 个 class 文件逐字相同，`changedClasses=[]`。记录：`build/tag-search/d89d6ee0-63aa-4fe1-9b91-1b98b4695ac4/draft-components.json`。真实草稿接续证据继续覆盖这些未变化的组件，本次没有重复写入草稿。

当前安装包：`build/distributions/linuxdo-jetbrains-plugin-1.0.0.zip`。

SHA-256：`cd3335283e8a7ca1e840fdbeb2514a67fc548291f34169d53ac3d3b7764ac26c`。版本仍为 1.0.0，未发布或上传。
