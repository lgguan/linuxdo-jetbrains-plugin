# 完整计划验收核对

后续用户已授权发布：最新截图、当前发布包重新验证、Marketplace 替换及最低 IDE 验证见 [1.0.0 发布更新记录](marketplace-1.0.0-refresh.md)。下文保留各阶段验收和联调历史。

2026-10-01。按最初的浏览、阅读与回复计划，以及后续正文、发帖、编辑器图标与按需预览要求核对当前工作树。真实测试仅允许话题 `482293` 的指定回复草稿；没有发送帖子或回复，真实发布结果以模拟传输验证。

## 实现与验证对应

| 要求 | 当前实现 | 验证证据 |
| --- | --- | --- |
| 对象及旧字符串标签，搜索摘要和分页标志 | `DiscourseModels`、`TopicTag`、`TopicBrowsing` | `TopicBrowsingTest`、`DiscourseModelsTest`；实际 IDE 列表解析标签对象 |
| 紧凑列表的类别、标签、未读及相对时间，完整标题和绝对时间提示 | `TopicCardCellRenderer`、`RelativeTime`、`IssueListPanel.getToolTipText` | `TopicPresentationTest`；实际 IDE 行显示与悬浮提示检查 |
| 同条件刷新按 ID 更新、保持选中和滚动锚点 | `TopicListReconciler`、`TopicBrowsing.merge` | 变行高 Swing 测试；实际 IDE 刷新保留选择、行内偏移和已加载分页 |
| 切换条件重置分页，失败保留原内容 | `IssueListPanel.loadPage/applyTopics` | 实际 IDE 切换失败、保留内容、原操作重试成功后重置位置和页码 |
| 搜索加载更多、去重并保留首个摘要 | `IssueListPanel.loadSearchPage`、`TopicBrowsing.searchTopics/merge` | `TopicBrowsingTest`；实际 IDE 第二页失败并重试同页、去重和首个匹配楼层保留 |
| 点击结果定位匹配楼层，精确 ID 独立处理 | `LinuxDoDocMainPanel` 将 `searchPostNumber` 传给 `LinuxDoEditorOpener`；`TopicSearch` | 实际 IDE 鼠标点击传递匹配楼层；数字 ID 走话题接口并禁用搜索翻页；`TopicSearchTest` |
| 查询、账号变化或关闭后丢弃过期回调 | `requestGeneration`、`SessionEpoch`、`disposed` 检查 | 实际 IDE 延迟返回旧查询、切换会话及释放窗口后结果仍不覆盖当前内容 |
| 成功跳转前记录楼层，可返回；输入、滑块和引用共用导航 | `topic-pagination.js`、`DocViewerPanel.returnFloors`、`TopicDocumentRenderer.jumpToFloor` | 16 项浏览器回归；实际 IDE 返回桥接及按钮；当前分页 JS/CSS 与回归夹具逐字匹配 |
| 遵守请求间隔及 429 冷却，本地缓存导航仍可用 | `ForumReadCooldown`、`TopicPaginationBridge`、`topic-pagination.js` | `ForumReadCooldownTest`；浏览器请求间隔、失败保留返回楼层、429 阻止远程导航和冷却内本地返回 |
| 普通 429 表示论坛限流；带 Cloudflare 字样或验证标记的 429 提示人机验证 | `HttpFailure`、`RateLimitException`、列表与编辑器错误提示 | Java/JCEF 分类及页面提示测试；实际 IDE 回复和新话题窗口人机验证提示、保留正文及验证后重试 |
| 中文、多行、代码及特殊字符选区引用，插入保留已有正文和草稿 | `DiscourseQuote`、正文引用桥接、`CommitReplyDialog.insertQuote` | `DiscourseQuoteTest`；浏览器中文和代码引用、跨楼层选区限制；实际 IDE 选区与已有正文保留 |
| 回复对象、预览及同步状态明确 | 回复目标栏、预览状态栏、草稿状态栏 | 实际 IDE 恢复对象、同步状态、预览显示/隐藏/恢复 |
| 论坛草稿 GET/POST/DELETE，复用登录、CSRF 与会话隔离 | `ForumDraftTransport`、`DiscourseApiClient.readDraft/writeDraft` | 原生接口成功及 409 报告；真实联调阶段安装包的 IDE↔网页接续完整通过，DELETE 200 后 GET 确认为空；会话隔离单元测试 |
| 打开先读取并恢复，保护不支持的草稿 | `ForumDraft`、`ReplyDraftSession`、回复窗口加载逻辑 | `ReplyDraftSessionTest`；实际 IDE 恢复正文和目标、禁写及网页入口 |
| 两秒停止输入后保存，单活动编辑器、串行使用最新序列且保留未知字段 | 编辑器注册表、Swing Alarm、`ForumDraftSession` 同步锁 | 实际 IDE 两秒同步及未知字段；回复和新话题会话单元测试的连续保存与串行等待 |
| 关闭三种选择，断网和失败保留正文 | 回复和新话题关闭/重试流程 | 实际 IDE 保存后关闭、继续编辑、舍弃、断网保留和重试 |
| 409 暂停自动保存，展示双方内容，由用户选版本 | 草稿冲突窗口及 `conflicted` 状态 | 实际 IDE 双方正文及回复目标、双方版本选择；新话题恢复标题、类别及标签 |
| 发送前等待草稿及图片保存，成功安全清理，失败保留内容 | `ForumDraftSession.publish/clearOwned`、编辑器提交逻辑 | 单元测试串行等待、并发客户端保护、静默旧序列删除确认；实际 IDE 模拟成功、失败、结果不明及审核队列 |
| 不新增本机正文持久化，恢复依赖已同步草稿 | 草稿内容仅在内存和论坛中，隐私及窗口提示 | 源码核对与 [隐私说明](privacy.md)；真实及模拟重开恢复同步内容 |
| 查看网页版，改进正文语义渲染和发帖/回复编辑体验 | 共享安全 DOM 渲染与 CommonMark，分类和标签约束，新话题草稿 | 网页引用、链接卡片及编辑器参考截图；`ForumContentAndComposerTest`、`TopicDraftSessionTest`；实际 IDE JCEF 正文和预览、上传和约束检查 |
| 图标整合、统一编辑框风格、按需预览 | `ComposerIcon`、`ComposerEditorSupport`、`ComposerAppearance`、预览状态切换 | 实际 IDE 图标名称、格式菜单、单次撤销、默认无预览浏览器、菜单打开、滚动保留和窄屏排列；[截图](images/create-topic.png) |
| 新建入口无草稿直接新建、有草稿直接恢复，只使用单份草稿 | 原生 `new_topic` 键、`CreateTopicDialog.open` 活动窗口注册表 | `TopicDraftSessionTest`；实际 IDE 无草稿入口、自动恢复、同一编辑器复用及关闭后恢复 |
| 新建与回复弹窗初始尺寸增大 | 新建编辑区域 1040×700、回复编辑区域 1000×640，随 IDE 缩放 | 实际 IDE 初始窗口尺寸断言；440px 窄窗口及实际截图视觉核对 |
| 板块和标签搜索选择参考网页版 | `ComposerCategoryPicker` 父子路径、颜色及描述搜索；标签搜索、多选、移除、禁用原因及重试 | `ComposerCategoryPickerTest`；实际 IDE 父级/描述/slug 搜索、多选保持打开、空结果、失败保留、重试与两类 429 提示 |
| 标签加载不得因固定 limit 与论坛配置不符失败 | `DiscourseUrls.composerTags` 不传固定 limit，保留类别和选中标签上下文 | 真实接口复现 400 后默认列表和关键词均 200；实际 IDEA 生产 HTTP 客户端上限 5 回归通过；`ComposerTagRequestTest` |
| 选中标签后再次打开不冻结，标签布局参考截图，板块选中收起 | 复用渲染控件、批量加载候选项；标签块与底部搜索；板块先关闭再应用 | 实际 IDEA 200 个附加标签连续重开 15 次、控件数量有界、8 标签窄窗换行和实际鼠标选择通过 |
| 构建、全量单元测试、Plugin Verifier、真实 IDE 验收 | 最终 ZIP 和实际 IDEA 隔离安装测试 | [最新验证记录](picker-freeze-verification.md)：290 项单元测试、105 项真实 IDEA 检查及当前包哈希；Plugin Verifier Compatible |

实际 IDE 列表检查使用生产 HTTP 客户端、JSON 解析、分类服务与列表窗口，在客户端拦截器中返回隔离响应；不会调用网络传输，也拒绝所有写请求。提交、上传和异常场景仍使用隔离模拟，不发送真实帖子。

## 用户验收确认

2026-10-01，用户确认“已验收”。当时安装包的人工验收确认已记录；版本发布和上传仍另行安排。当时的 284 项单元测试、7 项脚本离线防护测试、81 项实际 IDEA 交互检查及 Plugin Verifier 已通过。

用户验收确认没有改变当时自动联调未通过的事实，其 Cloudflare 拦截和停止记录保留，不改写历史失败结果。后续仅在用户明确确认完成人机验证后，才重新执行已授权草稿测试。

上述人工确认属于此前交付。后续三项入口、窗口尺寸和选择器优化已在当前安装包通过 287 项单元测试、97 项真实 IDEA 检查与 Plugin Verifier；本次目标续行又核对了完整计划对应项、当前报告和包哈希，7 项离线联调防护测试重新通过。导航回归夹具内的生产 JS/CSS 与当前源码逐字匹配，历史 16 项导航结果仍对应当前代码。最新窗口的窄屏截图已作视觉检查。

用户随后明确确认“已完成验证，可以继续草稿测试”，解除人工验证等待门禁并保留原冷却截止时间后，最终安装包的单次草稿接续复测完整通过。报告：`build/draft-handoff/9b7a3046-9407-4464-b8b4-809c4335a852/result.json`；实际 IDEA 报告：`build/host-smoke/c6e1fae2-2e34-49db-a115-2f1a4d5ae78d/result.txt`。

## 最终验收结论

真实草稿接续通过时的安装包 SHA-256 为 `b32254c9609520866276b1b798cb1cd6c0bad1588e7c15ce0d34ea3305649d08`。随后用户反馈标签 `Limit 无效`，已移除固定 limit，并通过真实只读接口检查、290 项单元测试、99 项实际 IDEA 回归和 Plugin Verifier。

标签接口修复阶段安装包 SHA-256 为 `cd3335283e8a7ca1e840fdbeb2514a67fc548291f34169d53ac3d3b7764ac26c`。该阶段草稿读取、保存和删除逻辑未改变，该包与真实联调实际安装包的回复编辑器、草稿会话、HTTP/JCEF 客户端、会话版本及 HTTP 错误分类相关 40 个 class 文件逐字相同；对照记录及验证范围见 [标签修复验证](tag-search-verification.md)。

随后用户反馈标签重新打开冻结，并提供网页式布局截图、要求板块选择后收起。当前包已完成这三项修改，通过 290 项单元测试、105 项真实 IDEA 回归和 Plugin Verifier。SHA-256 为 `18184db7d049d722ff8c4dc5a17b5b9a0962ba908bd598aa7da59801538bc997`；当前报告、截图及验证范围见 [选择器修复验证](picker-freeze-verification.md)。本轮仅使用隔离模拟论坛，没有真实写入；不将旧包的真实草稿接续报告冒充当前包重新联调结果。

本次真实联调验证了 IDE 自动保存、网页原生编辑器恢复及保存、IDE 再恢复完整正文与第 3 楼回复目标、清理自有测试草稿。保存返回 HTTP 200 / sequence 14，删除返回 HTTP 200，随后读取 `draft=null` / sequence 15；联调进程以 0 退出，`DRAFT_HANDOFF_PASS=true`、`IDE_UI_PASS=true`、`postSubmitted=false`。已查看真实 IDEA 最后恢复窗口的截图，正文、回复对象、同步状态和增大的初始尺寸符合预期。

完整计划及后续追加的三项优化均有对应实现和当前验证证据。发送、上传及异常提交结果使用隔离模拟，真实测试只操作用户授权的回复草稿。真实凭据留在专用 Chrome 内，没有真实发送帖子或回复。发布、上传和其他平台验收不属于本次交付。

## 历史自动联调失败记录

此前“IDE 保存 → 网页原生编辑器恢复并保存 → IDE 再恢复 → 清理”接续复测未通过：早期运行在网页导航阶段超时；冷却后的运行成功读取、首次保存并在 IDE 恢复正文和目标，后续自动保存收到非 JSON HTTP 403。该运行的自有草稿已按完整内容及序列核对后清理，并再次读取确认为空，报告为 `build/draft-handoff/d6991a5c-5a72-40a0-a96c-eec0fcd3c17f/cleanup-result.json`。

随后一次复测在首次读取收到 HTTP 429，没有写入。联调脚本已改为从请求完成后计算至少 5 秒间隔，CSRF 获取与写入之间也间隔 5 秒；网页 API 请求同样限速。收到 429 后停止全部后续请求（包括收尾读取），记录跨运行冷却；普通限流缺少 `Retry-After` 时至少等待 30 分钟。7 项离线防护测试通过。普通限流的等待模式只在本地等待，到期后尝试一次，不轮询论坛；确认需要 Cloudflare 人机验证时则停止自动尝试，等待用户验证。此项自动接续结果保留为未通过。

17:56 的单次复测已确认首次草稿 GET 返回带 Cloudflare 验证标记的 429，未进行写入：`build/draft-handoff/d1d872a5-292e-4850-9a34-3063aeea1ea7/blocked-response.json`。进程已退出，跨运行记录为 `verificationRequired=true`。没有自动解决验证或继续重试；用户随后确认已验收，保留该门禁，不据此认定人机验证已解除。

此前的原生接口保存、恢复、连续序列、409 和清理，以及较早安装包的完整网页接续报告仍保留在 [首轮验证记录](browsing-reply-verification.md)，没有把旧包的完整接续结果当作新包的复测通过。

发布版本和上传仍另行安排。

## 2026-10-03 标签、高级搜索与主题更新验收

后续完整计划及四项反馈的当前验收见[列表标签、高级搜索与弹窗验证](advanced-tag-filter-verification.md)，包含逐项核对、三处界面的真实 IDE 截图及功能支持范围。当前版本为 1.0.1，安装包 SHA-256 为 `87b3034453ba9ebccea736ec7a115af2f234047308336da3d2d932b801b16c3e`；321 项桌面单元测试、162 项实际 IDEA 检查、全新目录构建和目标 IDE Plugin Verifier 均通过。

本次重新对当前包进行了真实 IDE↔网页草稿接续，恢复、保存、再次恢复和自有草稿清理全部通过；不再仅引用旧包结果。真实高级搜索只读验证也通过，包含当前账号个人消息搜索和读取；记录仅保存状态及数量。全部 IDE 安装 JAR 与交付包逐字一致。没有发送帖子、回复或 Boost，没有自动发布或关机；历史记录按原验证范围保留。
