# 浏览、阅读与回复优化验证记录

日期：2026-10-01。发布版本号未调整，未上传或发布。真实账号联调限定话题 `482293` 的草稿，未发送回复。

## 已实现

- 解析对象标签 `id/name/slug` 与旧字符串标签，解析搜索 `blurb` 和 `more_full_page_results`。
- 列表显示分类、标签、未读状态和相对活动时间；悬浮提示显示完整标题与绝对时间。同一筛选刷新按 ID 更新、保留已加载话题、选中项和滚动锚点；新筛选仅在成功后替换，失败保留当前内容。
- 搜索追加分页并去重，保留每个话题的首个匹配摘要；打开时定位匹配楼层。精确 ID 查询单独处理。查询变化、会话变化和窗口销毁均使旧回调失效，失败重试原页。
- 楼层输入、滑块和引用跳转沿用原导航机制；成功跳转才更新返回位置。冷却期间可返回已缓存楼层。
- 同一楼层正文选区可引用回复；中文、多行、代码与特殊字符得到保留，插入光标处不会覆盖选区或已有正文。跨楼层选区不能生成带错误作者的引用。
- 每个账号、话题共用一个普通回复编辑器；先读取论坛草稿、恢复正文及目标，输入停止两秒后同步。预览、回复对象、同步状态与隐私说明分别显示。
- 同一草稿串行保存，采用服务器返回序列并保留未知字段；不支持的草稿类型禁止写入并提供浏览器入口。409 暂停同步，完整展示双方正文及目标供选择；不使用 `force_save`。
- 关闭提供保存、舍弃和继续编辑；失败保留内容。发送前等待保存，失败保留正文；成功清理时核对序列和内容，并再次读取确认。其他客户端的新草稿会保留，清理失败不会使已发送回复变成可重复提交的失败。
- 不新增本机正文文件，重启恢复依赖已同步到论坛的草稿。

## 协议依据

草稿键与序列检查遵循 [Discourse Draft 模型](https://github.com/discourse/discourse/blob/main/app/models/draft.rb)；读、写、删除及 409 行为遵循 [DraftsController](https://github.com/discourse/discourse/blob/main/app/controllers/drafts_controller.rb)。正文、回复目标和旧格式 `postId` 与 [composer 草稿字段](https://github.com/discourse/discourse/blob/main/frontend/discourse/app/models/composer.js)兼容。成功标志兼容原生 `"success":"OK"`，首次保存允许返回未增加的序列 `0`。

## 已执行的检查

| 检查 | 结果 |
| --- | --- |
| 全量 JUnit（`-PdesktopTests=true`） | 232 项全部通过，0 跳过；剪贴板测试结束恢复原内容 |
| `buildPlugin`、`verifyPlugin` | 通过 |
| Plugin Verifier 1.410，IDEA 2026.2.3 / IU-262.10968.63 | 兼容 |
| 目标 IDE 隔离安装包类加载 | 插件与 JCEF 使用正确类加载器，`SUPPORTED=true` |
| 目标 IDE 自带 JCEF，本地合成页面 | 原生渲染、键盘、滚轮、合成会话、文档权限、分页、跳转、刷新、释放与重建均通过；`PAGINATION_REFRESH_PASS=true`、`PORTABLE_NATIVE_PASS=true` |
| 真实 Chrome 中的生产导航 JS | 16 项通过：远距离跳转、返回、请求间隔与合并、过期响应、失败返回位置、429、本地返回、上下分页、窄屏、中文多行引用、代码引用、跨楼层选区 |
| Swing 列表回归 | 行高改变后保留选中 ID、首个可见 ID 与行内偏移；筛选切换重置 |
| 最终安装包在实际 IDEA 桌面中的交互验收 | 28 项通过：真实回复窗口、两秒自动保存、关闭三选项、断网重试、409 双方正文与目标、账号切换、模拟发送失败/成功；实际正文阅读器、JS 返回位置桥接、返回按钮与 360px 窄窗口通过 |
| IDE UI 响应 | 注入 180ms 草稿 I/O 延迟时，EDT 20ms 心跳的 95 分位为 27.0ms |
| 草稿与引用单元测试 | 网页格式恢复、旧目标格式、连续保存、原生成功响应、409、断网重试、账号切换、串行等待、发送失败与并发清理、未知字段、引用插入 |
| 测试账号真实草稿接口 | 保存 200 / sequence 0；恢复正文与 #2 回复目标；再次保存 sequence 1；旧序列 409；删除 200 并确认为空 |
| 真实网页与 IDE 的草稿接续 | 话题 482293 的普通第 3 楼：IDE 自动保存 → 原生网页编辑器恢复 → 网页编辑保存 → IDE 恢复正文及旧格式 `postId` 的回复对象；随后舍弃本次草稿并确认清空；未发送帖子 |

真实草稿联调正文为用户指定的“保护好互联网的净土，让大家都能在社区平和的交流学习”。首次读取确认没有原有草稿，测试结束后只删除本次测试内容。测试通过既有 Chrome 登录会话执行原生接口，不导出 Cookie 或 CSRF。真实发送路径未执行。

报告在 `build/reports/tests/test/`、`build/reports/pluginVerifier/`、`build/host-smoke/`。浏览器截图与结果在 `output/playwright/`，真实草稿报告在 `output/browser-tools/draft-regression-result.json`；这些输出目录不提交到 Git。

最终安装包：`build/distributions/linuxdo-jetbrains-plugin-1.0.0.zip`。SHA-256：`b5f561365679ddba76eb96aae3db6d8818a8d72e6b51a095c604a627032eaba6`。

## 验收范围

桌面交互由 `tools/IdeUiSmoke.java` 在目标 IDEA 2026.2.3 中自动操作实际窗口和生产正文阅读器完成。最后一次报告为 `build/host-smoke/50f311b3-73c0-48f4-8248-a74a777c494f/result.txt`，包含 `TOTAL_CHECKS=28` 与 `IDE_UI_PASS=true`。这些检查使用内存论坛传输，不访问真实发送接口；网络错误、409 和发送结果为模拟。

真实接续由 `tools/draft-handoff.py` 执行，报告为 `build/draft-handoff/e9fb951a-c709-49ae-bab9-546bb0b67fe9/result.json`。IDE 使用安装包中的实际回复窗口与 `ReplyDraftSession`，测试传输通过文件桥接专用 Chrome 内的已认证草稿接口；网页使用论坛原生编辑器。凭据留在浏览器内。该测试验证 UI、论坛草稿格式和序列协议的接续，不替代插件默认 HTTP 客户端完整登录会话的人工验收。尚未执行真实发送；按用户要求，此轮不会执行。Windows 是本次实际桌面验收平台，其他操作系统仍依照跨平台指南验证。
