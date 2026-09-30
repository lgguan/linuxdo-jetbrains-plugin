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
| 个人显示和网络设置 | IDE Config 的 `options/LinuxDoSettings.xml` | 在设置中调整；关闭 IDE 后删除文件可恢复默认设置 |
| 历史分类缓存 | IDE Config 的 `options/LinuxDoCategories.xml` | 旧缓存不再进入当前发布表单；可在关闭 IDE 后删除 |
| 常规日志 | IDE Log 的 `linuxdo.log`、轮转文件及 IDE `idea.log`；独立 JCEF 另有 `linuxdo-private-jcef.log`、`linuxdo-private-chromium.log` | 关闭 IDE 后删除插件日志（IDE 日志目录不可用时，插件日志回退到用户主目录 `.linuxdo/logs/`）；常规插件日志只记录操作、状态、耗时及必要标识，不记录正文和标题 |
| 可选网络诊断 | IDE Log 的 `linuxdo-netlog-<标识>.json` 与 `.consumed` 标记 | 默认关闭，在设置中手动启用；重启后单次采集，支持的 JCEF 运行时最多 5 分钟、50 MB；复现后关闭 IDE并删除对应文件 |

网络诊断可能包含域名、地址、请求 URL 和网络元数据。分享前应自行检查和脱敏；不要公开 Cookie、账号内容或完整诊断文件。插件普通日志会对凭据与 URL 脱敏，Chromium 原始诊断不受这一过滤器处理。

“自动向社区同步阅读进度与停留时长”可在设置中关闭。开启时，仅统计当前正文可见且 IDE 处于前台的时间，每 15 秒合并上报，并在切换或关闭正文时提交余量。阅读位置在本地仍可保存。

编辑中的发帖和回复内容保留在窗口内存中，失败时不会自动清空或重复发布。关闭并确认舍弃后不保留草稿。插件卸载不保证 IDE 自动删除上述本地文件；需要彻底移除时，先退出登录，关闭 IDE，再清理列出的插件专属文件和目录。
