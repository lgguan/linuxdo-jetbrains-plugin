# Boost 与正文已读同步验收

2026-10-03，版本 1.0.2。本文件保留首次交付的检查记录；后续已读和楼层布局修复已合入同一发布版本，最终安装包验证见 [发布记录](release-1.0.2.md)。交付工作区源码、安装包、实际 IDEA 截图及验收记录；保留已有搜索、标签与草稿修改，没有自动发布或关机。

## 完成的行为

| 范围 | 实现 |
| --- | --- |
| Boost 查看 | 头像与安全渲染的内容组成紧凑气泡，支持换行、用户名提示及资料入口；只保留受限标签和表情图片，不执行服务器内容中的脚本。 |
| 入口与权限 | `can_boost=true` 才显示入口；无 Boost 时在帖子操作栏，有 Boost 时在气泡末尾。自己的气泡同时满足 `can_delete=true` 才提供撤回，提交前重新读取服务器权限并核对账号 ID。 |
| 输入 | 楼层旁浮层提供表情、字符计数、发送和取消；Enter 发送、Esc 关闭，输入法组合中不提交。滚动、真实窗口缩放或缓存移除关闭浮层；主题配色更新保留输入；失败保留输入。 |
| 校验 | 最多 16 个可见字符、5 个表情。Unicode 字素簇和有效短码各按一个字符计数，识别组合、肤色、旗帜、键帽和自定义短码。使用随包校验 SHA-256 的官方表情元数据及会话内服务器配置。 |
| 提交与撤回 | POST `/discourse-boosts/posts/{postId}/boosts` 只发送 `raw`，返回 Boost 模型局部替换；DELETE `/discourse-boosts/boosts/{id}` 后重新读取楼层能力。账号版本、页面标识和应用共享锁阻止旧回调及跨窗口重复提交。 |
| 错误 | 普通校验提示、429 冷却、Cloudflare 验证分别显示。结果不明只读核对一次，不自动重发；仍未确认时阻止重复提交。 |
| 正文采样 | 每秒仅采样选中的、前台且可见的正文文档，以 `.post-content` 的实际区域判断，连续样本及约半屏阈值确认楼层；Boost 区域不算正文。每楼独立累计实际时长，话题时长独立累计。 |
| 暂停与归并 | 3 分钟无阅读滚动暂停；每楼每次阅读最多 6 分钟。恢复后的首个样本和超过 2.5 秒的延迟不计时。新读楼层及时归并，其余每 60 秒归并；切换话题、关闭或后台收集余量。 |
| 补传 | 按论坛地址、账号 ID、话题持久化批次、时长及重试状态；发送快照与新采样分开。每批话题和每楼最多 60 秒，全局串行，至少间隔 5 秒；成功确认才出队。 |
| 恢复与反馈 | 网络及可恢复服务错误采用 5/10/20/40 秒退避；429 遵守冷却。验证、登录及权限错误暂停对应账号整个队列，后续采样也保留。恢复验证或登录后补传，关闭自动同步设置暂停采样与上传。工具栏显示待同步和重试，成功通知列表更新已读，不重载正文。 |

接口和边界依据官方 [Boost 校验](https://raw.githubusercontent.com/discourse/discourse-boosts/main/app/models/discourse_boosts/boost.rb)、[Boost 控制器](https://raw.githubusercontent.com/discourse/discourse-boosts/main/app/controllers/discourse_boosts/boosts_controller.rb)、[网页采样队列](https://raw.githubusercontent.com/discourse/discourse/main/frontend/discourse/app/services/screen-track.js)及[服务器计时处理](https://raw.githubusercontent.com/discourse/discourse/main/app/models/post_timing.rb)。

## 验证结果

| 检查 | 结果及范围 |
| --- | --- |
| 桌面单元及模拟传输 | 339 项，0 失败、0 错误、0 跳过。包含 Boost 边界、权限缺失、跨窗口重复、成功、撤回、结果不明、两类 429、账号和失效页面；阅读多楼独立计时、快速滚动、后台与休眠、空闲及上限、拆批、失败、发送期间新增、账号隔离及 XML 恢复。 |
| 实际 IDEA 桌面 | Windows x64，IntelliJ IDEA Ultimate 2026.2.3 / IU-262.10968.63，JBR 25，生产插件 ZIP，184 项通过。含回复、新话题草稿、高级搜索、标签、阅读导航及 Boost/已读反馈。原始记录：[IDE 验收](reference/ide-ui-boost-read-sync.txt)。 |
| 实际正文交互 | 深浅编辑器主题、窄窗口、125% 缩放、Robot Enter/Esc、组合事件、字符计数、失败输入、滚动和缩放关闭、自己的撤回、Boost 列表视口、503 留队与自动重试、关闭设置暂停、后台停止累计、断线恢复。发送前后正文是同一节点，屏幕坐标偏移 **0 px**。 |
| 浏览器阅读回归 | 42 项；10,000 楼流、最多 200 个活动楼层、400 个缓存楼层及 32 MiB 上限，正文选择与局部更新、公式/图表、媒体、搜索、书签及窄屏。 |
| 浏览器布局回归 | 75 项；深浅及 360 px 宽度，含此前跳转目标仍在屏幕内时插入 Boost，实际可见正文位置保持。 |
| 浏览器导航回归 | 16 项；跳转、返回、邻接分页、429、旧回调、窄屏和跨楼选择。 |
| 构建 | `test buildPlugin verifyPlugin -PdesktopTests=true --offline` 通过；另在全新目录从允许的源码快照构建并比较安装包 SHA-256，见交付校验记录。 |
| 目标 IDE Plugin Verifier | 1.410，目标 IU-262.10968.63，结论 **Compatible**；[原始结论](reference/boost-read-sync-plugin-verifier.txt)。 |

机器可读汇总见 [verification summary](reference/boost-read-sync-summary.json)。

## 真实与模拟验证边界

真实论坛仅通过现有专用 Chrome 页面 GET `/t/482293.json?track_visit=false` 对照模型；HTTP 200，20 个帖子、68 个 Boost，写请求 **0**。检查到 `can_boost`、`cooked`、删除权限及用户字段；包括匿名化用户字段缺失的情况。只保存字段是否存在、楼层和 Boost ID，[只读记录](reference/linuxdo-boosts-readonly.json)不包含正文、用户名、Cookie 或凭据。

IDE 截图来自实际生产插件的 JCEF 与原生窗口。截图中的帖子为示例数据；Boost POST/DELETE 和计时 POST 全部由内存 HTTP 传输接管。计时数据来自验收窗口的真实前台可见停留，没有将模拟时长发送给真实论坛。

中文组合保护在实际 JCEF 中注入 `compositionstart/compositionend` 与 `isComposing` 事件验证，Enter/Esc 使用系统 Robot 按键。没有完成每种 Windows 中文输入法候选窗的人工逐项验证。队列重启恢复通过真实 IDE XML 序列化格式的保存、反序列化和新队列续传测试验证；没有用真实账号完成“整个 IDE 退出、重启、重新登录、真实补传”的端到端操作。

计时接口没有批次幂等键。已成功确认的批次不会重复发送；响应丢失后的重试不保证服务器时长精确去重。队列不保存正文，自动同步关闭时保留已有待同步证据，旧版本无队列时初始化为空。

## 实际 IDEA 截图

| 场景 | 截图 |
| --- | --- |
| 深色主题浮层 | [深色](images/boost-popover-dark.png) |
| 浅色主题浮层 | [浅色](images/boost-popover-light.png) |
| 窄窗口 | [窄窗口](images/boost-popover-narrow.png) |
| 125% 缩放 | [缩放](images/boost-popover-scaled.png) |
| 自己的气泡与撤回 | [气泡](images/boost-bubble-own.png) |
| 失败保留输入 | [失败](images/boost-retained-error.png) |
| 网络失败留队 | [待同步](images/read-sync-network-pending.png) |
| 验证暂停提示 | [暂停](images/read-sync-pending.png)，提示文案由隔离验收注入；Cloudflare 状态分支另由单元测试覆盖。 |

## 复现与安装

构建使用 JDK 21，插件字节码目标 Java 17。Windows 可设置 `JAVA_HOME` 后执行：

```powershell
.\gradlew.bat test buildPlugin verifyPlugin -PdesktopTests=true --offline --console=plain
python tools/smoke.py ui --ide-home 'C:\path\to\IDE' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip
.\gradlew.bat runPluginVerifier '-PverifierIdePath=C:\path\to\IDE' -PverifierOffline=true --offline --console=plain
python tools/clean-release-build.py
python tools/package-release-source.py
```

安装包为 `build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip`，源码包为 `build/delivery/linuxdo-jetbrains-plugin-1.0.2-source.zip`；相邻 `.sha256` 文件可校验。源码包包含本轮与此前工作区的完整源码、测试、脚本和文档，按允许列表排除账号缓存、构建目录和凭据文件。通过 IDEA「设置 → 插件 → 齿轮 → 从磁盘安装插件」选择安装包，按 IDE 提示重启。
