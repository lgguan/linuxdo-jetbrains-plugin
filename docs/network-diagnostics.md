# 网络配置与诊断

当前浏览器实现为 `IsolatedCefRuntime` + `LinuxDoBrowser`，使用 IDE 自带的 `cef_server`、插件私有缓存和 Cookie。登录页、隐藏 API 网桥、正文及图片共用该会话。Java 请求由 `LinuxDoHttpClient` 和 `DohDnsResolver` 处理。

## 配置

![Linux Do 插件网络与诊断设置](images/settings.png)

默认且唯一内置服务商为 LinuxDo DoH：`https://ldh.ddd.oaifree.com/query-dns`。也可选择自定义 HTTPS 地址或禁用 DoH。预设不固定引导 IP，服务端域名由系统 DNS 引导解析；严格模式保持开启。

- 在 Settings → Tools → Linux Do (API Docs) 选择 DoH 服务、请求引擎及代理策略。
- `DIRECT` 对 Java 请求和插件私有 JCEF 禁用显式代理；`FOLLOW_IDE` 跟随支持的 IDE 代理配置。应用设置无法判断或关闭系统 TUN。
- 自定义 DoH URL 必须为 HTTPS。普通 URL、`{?dns}` 和 `{&dns}` 模板按输入使用；引导 IP 仅用于 DoH 服务本身。
- “测试 DNS 解析”验证解析器；“应用并测试插件连接”先保存当前输入，再验证真实浏览器链路。前者成功不能代替后者。
- 配置改变后，下次连接重建插件私有运行时。配置仅作用于插件私有运行时，不写入 IDE 共用的 Local State。

插件浏览器适配 Windows、macOS 和 Linux，要求 JetBrains 2026.2（262.*） 及配套 remote JCEF。插件策略按系统保存：Windows 使用插件专属 HKCU 应用键，Linux 使用私有 profile 下的 `policies/managed/linuxdo-doh.json`，macOS 使用 `com.lgguan.linuxdo.plugin.jcef.<profile 摘要>` 偏好域。策略仅通过该运行时的 `chrome_policy_id` 读取，保留在本机供后续启动覆盖，不修改 Chrome、系统 DNS 或 IDE 共用浏览器。

策略写入失败会阻止启动。macOS 的用户级偏好与 Windows/Linux 的 managed policy 机制不同；成功写入不等于 DoH 生效，必须验证实际请求。首次使用、切换 DoH 或更换受管理设备时，使用下面的 NetLog 检查；若发现业务域名回退到非预期 DNS，不能将该环境标为通过。原生验证步骤见[跨平台适配与验证](cross-platform.md)。

## 日志与证据

设置页提供诊断展示与复制入口。普通日志为 IDE 日志目录中的 `linuxdo.log`，私有 JCEF 日志为 `linuxdo-private-jcef.log` 和 `linuxdo-private-chromium.log`。

需要网络证据时，勾选“下次启动采集网络诊断（一次性）”并应用，冷启动 IDE 后复现。`linuxdo-netlog-<token>.json` 来自插件私有浏览器，当前采集时限为 300 秒、上限 50 MB；不会自动上传。

检查业务域名的 DoH 请求、代理决策和连接结果。DoH 服务自身的引导解析可以使用系统 DNS。Local State 的磁盘值只作为诊断信息；ECH 是否协商成功应以 CDP/NetLog 为准。

## 回归入口

先运行 Windows 的 `gradlew.bat test buildPlugin` 或 macOS/Linux 的 `./gradlew test buildPlugin`。随后按[工具说明](../tools/README.md)选择本地原生冒烟、联网验证或安装包类加载检查；账号与桌面交互按[跨平台指南](cross-platform.md)手动验收。

工具输出保存在 `build/`。验证时应记录所用安装包的版本、SHA-256 及目标 IDE 环境，确保报告与待发布包一致。
