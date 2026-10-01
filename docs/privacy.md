# 隐私与本地数据

本插件由 lgguan 个人开发，是仅连接 https://linux.do 的非官方客户端，与 Linux Do 官方和 JetBrains 无隶属关系。插件没有独立的分析或遥测服务。登录、浏览、点赞、发帖、图片上传、通知和阅读同步会向 Linux Do 发出对应请求；正文图片也可能从帖子引用的外部 CDN 加载。外部链接由系统浏览器打开。默认启用 LinuxDo DoH（`https://ldh.ddd.oaifree.com/query-dns`）；该服务会收到域名查询。用户可在设置中改为自定义解析服务或禁用 DoH，使用系统 DNS。DoH 服务自身的域名通过系统 DNS 引导解析。

## 保存位置与清理

下文的 IDE Config、System、Log 目录可在 IDE 的 Help → Diagnostic Tools → Special Files and Folders 中查看；具体绝对路径随产品、版本和操作系统变化。

| 数据 | 保存位置 | 清理方式 |
| --- | --- | --- |
| `_t`、`_forum_session`、`cf_clearance` | JetBrains PasswordSafe，服务名 `LinuxDoPlugin` 下的插件凭据；具体后端由 IDE/系统设置决定 | 插件设置或登录窗口点击退出登录；会清除插件保存的凭据及当前私有浏览器 Cookie |
| 浏览器 Cookie、页面缓存、本地存储 | IDE System 下 `linuxdo-private-jcef/<配置标识>/` | 退出登录会清除 Cookie；完全清除浏览器缓存及网站存储需先关闭 IDE，再删除该插件目录 |
| 图片下载、预览与复制原图文件 | IDE System 下 `linuxdo-plugin-images/` | 写入时按 7 天期限和 256 MB 总容量清理；关闭 IDE 后可删除整个目录。剪贴板可能继续引用其中的文件 |
| 阅读位置、实际已读楼层 | IDE Config 的 `options/LinuxDoReadTracking.xml`，按账号 ID 与游客分开保存 | 关闭 IDE 后删除此文件；退出登录保留各账号记录，便于下次恢复 |
| 普通回复草稿正文及回复对象 | Linux Do 论坛，按账号和话题保存，键为 `topic_<话题ID>`；插件不新增本机正文文件 | 回复窗口选择“舍弃草稿”，或在论坛网页删除；其他客户端已修改的版本会保留 |
| 个人显示和网络设置 | IDE Config 的 `options/LinuxDoSettings.xml` | 在设置中调整；关闭 IDE 后删除文件可恢复默认设置 |
| 历史分类缓存 | IDE Config 的 `options/LinuxDoCategories.xml` | 旧缓存不再进入当前发布表单；可在关闭 IDE 后删除 |
| 常规日志 | IDE Log 的 `linuxdo.log`、轮转文件及 IDE `idea.log`；独立 JCEF 另有 `linuxdo-private-jcef.log`、`linuxdo-private-chromium.log` | 关闭 IDE 后删除插件日志（IDE 日志目录不可用时，插件日志回退到用户主目录 `.linuxdo/logs/`）；常规插件日志只记录操作、状态、耗时及必要标识，不记录正文和标题 |
| 可选网络诊断 | IDE Log 的 `linuxdo-netlog-<标识>.json` 与 `.consumed` 标记 | 默认关闭，在设置中手动启用；重启后单次采集，支持的 JCEF 运行时最多 5 分钟、50 MB；复现后关闭 IDE并删除对应文件 |

网络诊断可能包含域名、地址、请求 URL 和网络元数据。分享前应自行检查和脱敏；不要公开 Cookie、账号内容或完整诊断文件。插件普通日志会对凭据与 URL 脱敏，Chromium 原始诊断不受这一过滤器处理。

“自动向社区同步阅读进度与停留时长”可在设置中关闭。开启时，仅统计当前正文可见且 IDE 处于前台的时间，每 15 秒合并上报，并在切换或关闭正文时提交余量。阅读位置在本地仍可保存。

普通回复窗口先读取论坛草稿，输入停止 2 秒后将正文和回复对象同步到 Linux Do。每个账号、话题共用一个活动编辑器；网页与插件共享同一草稿。关闭窗口可保存并关闭、舍弃或继续编辑。保存失败时，正文仍在窗口中，不显示保存成功；序列冲突时暂停同步，由用户对照完整正文和回复对象选择版本。不支持的帖子编辑、私信或新话题草稿不会被插件改写。

编辑内容保留在窗口内存中，插件不新增本机正文持久化。重启只能恢复已成功同步到论坛的回复草稿；未同步内容会丢失。发送失败保留正文，发送成功后仅清理本次拥有的草稿；其他客户端的新版本会保留。新建话题、微回复仍只在窗口中暂存。插件卸载不保证 IDE 自动删除上述本地文件，也不会删除论坛草稿；需要彻底移除时，先退出登录，关闭 IDE，再清理列出的插件专属文件和目录。
