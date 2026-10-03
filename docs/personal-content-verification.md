# 个人内容集中入口（第二期）

在 API Docs 列表控制区选择“我的”，可切换话题、回复、书签和草稿。默认进入论坛；论坛保留原板块、标签、排序、高级搜索、选择与滚动位置。个人页签分别保留当前会话中的检索词、选择与滚动锚点。

首次访问页签读取第一页。之后使用“刷新”“加载更多”；失败时“重试”读取失败的同一页。检索框“筛选已加载内容”只匹配已取得的标题、摘要和书签名称，不请求服务器搜索。“已加载 N 条”不是账号总数，无匹配且有续页时提示可以继续加载。

单击有效行或 Enter 打开话题首楼、指定回复楼层或书签的服务器目标；点击空白区域不会打开最后一行。回复仅有帖子 ID 时先 GET 确认所属话题与楼层，失败保留列表并提示网页核对，不猜测首楼。每页签提供网页入口。帖子书签优先使用 `linked_post_number`，不能确认楼层时提供网页入口；话题书签可打开话题。未知类型仅接受同源、可识别的话题网页路径。个人列表不提供书签管理、草稿删除或独立消息收件箱。

支持实际 `new_topic`、`new_topic_*` 和普通 `topic_<id>` 草稿。恢复重新读取最新草稿和序列号，同一会话与草稿键跨项目复用已有窗口，不替换未保存的输入；不同键可分别编辑。私信、编辑、表单、未知类型转网页。回复目标无法确认、草稿消失或类型变化时不会进入可自动保存状态。现有自动保存、上传、发布确认、409 冲突选择及清理机制继续使用。保存更新摘要，确认清理移除条目；发布和书签操作使相应首页失效，下次进入再刷新。

## 读取与隔离

| 页签 | 第一页 | 续页与去重 |
| --- | --- | --- |
| 话题 | `/user_actions.json?username=<当前用户>&filter=4&offset=0&limit=30` | offset 按原始行数推进，按话题 ID 去重 |
| 回复 | 同上，filter 为 5 | 优先帖子 ID，缺失时话题 ID＋楼层；同话题多条回复保留 |
| 书签 | `/u/<当前用户>/bookmarks.json?page=0` | 服务器续链必须同源、当前用户书签路径且页码递增，验证后重新构造请求；按书签 ID 去重 |
| 草稿 | `/drafts.json?offset=0&limit=30` | 原始行数推进，按草稿键去重；对象／JSON 字符串数据，坏行显示部分解析提示 |

无总数的接口满 30 条允许继续，短页／空页结束。刷新保留历史与原位置，更新已有行，新行按首页服务器顺序插入；完全不重叠时从首页续页补齐。列表只存内存，草稿摘要最多 160 字符，仅用 Swing 文本组件显示两行，不执行 HTML 或加载远程资源。

应用级 `PersonalContentService` 隔离论坛、账号 ID、SessionEpoch、类型和页参数，并合并跨窗口读取。网络在后台，订阅在 EDT；项目关闭和旧会话完成不会填回私人内容。未登录或凭据待确认不读取个人接口。HTTP 403／404、429 和 Cloudflare 显示具体状态与网页／验证提示，不使用公共搜索替代，不自动重试。Boss Key 隐藏期间不启动个人读取或恢复；恢复保持当前页签。常规日志不新增标题、摘要或草稿正文。

## 验证分类与复现

本轮全量测试 370 项全部通过（开启桌面测试，无跳过），本地打包和结构检查通过。最终安装包的两个真实 IDEA 项目窗口完成 35 项个人入口检查；既有界面回归通过 207 项，通知双窗口回归通过 38 项。浏览器模拟书签／导航／通知导航分别通过 6／16／11 项。所有论坛写操作均由内存传输验证，真实论坛写入为零。

个人窗口截图：[项目 A（草稿）](images/personal-project-a.png)、[项目 B（话题）](images/personal-project-b.png)。[原生断言](reference/personal-content-ide.txt)、[构建结果](reference/personal-content-build.txt)、[Verifier 判定](reference/personal-content-plugin-verifier.txt)分别保存。完整既有界面回归在最终个人面板可见性、登录联动与回复缺失楼层处理补充前运行；这些补充由最终安装包的个人入口验收覆盖。

模拟传输覆盖四类分页、重复／缺失字段、坏草稿数据、失败页重试、顺序合并、会话／论坛切换、429／Cloudflare 和保存失效广播。既有草稿测试覆盖序列、409 冲突、修改后自动保存和清理保护。真实 IDEA 检查使用独立配置、合成凭据和内存 HTTP，所有真实论坛写操作为零。

本站只读：2026-10-03 使用现有专用 Chrome 页面执行账号及四类列表共 5 个 GET，均为 HTTP 200；话题／回复／书签各 1 条，草稿 0 条，书签含 `linked_post_number`。保存的[脱敏记录](reference/linuxdo-personal-readonly.json)只有状态、字段名及计数。本次未在本站验证多页、非空草稿或失败响应；这些结论来自模拟传输。未发送真实草稿保存、删除或发帖。

```powershell
$env:JAVA_HOME='C:/Users/lgguan/.jdks/corretto-21.0.12.1'
.\gradlew.bat test buildPlugin verifyPlugin -PdesktopTests=true --offline
.\gradlew.bat runPluginVerifier '-PverifierIdePath=C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' -PverifierOffline=true --offline
python tools/smoke.py ui --personal-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate'
python tools/smoke.py ui --notifications-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate'
python tools/smoke.py ui --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate'
python tools/personal-browser-check.py
python tools/personal-reference-check.py
```

浏览器脚本需安装 Playwright，并连接既有专用 CDP 19337；回归创建自己的无账号上下文，只读核验复用现有论坛页。目标 IDEA 为 Windows 2026.2.3（IU-262.10968.63），Verifier 为 Compatible。详细数量、安装包 SHA-256 和各项证据见[最终验证汇总](reference/personal-content-summary.json)。macOS/Linux 原生验收仍留在持续优化路线。

本地安装包位于 `build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip`，源码与清单位于 `build/delivery/`。该第二期工作尚未推送或正式发布，构建版本保持 1.0.2。
