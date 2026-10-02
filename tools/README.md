# 开发与发布工具

保留三类检查：干净目录构建、JCEF 与安装包验证、长帖导航回归。常规单元测试、打包和 Plugin Verifier 直接使用 Gradle，命令见[项目说明](../README.md)。

## 工具清单

| 文件 | 用途 |
| --- | --- |
| `clean-release-build.py` | 在干净目录构建当前源码，比较发布 ZIP 的 SHA-256 |
| `smoke.py`、`smoke.init.gradle` | 跨平台检查入口及 Gradle 类路径导出 |
| `StandaloneSmokeApplication.java`、`PluginCefSmoke.java`、`PortableJcefSmoke.java`、`PaginationSmoke.java` | JCEF 原生渲染、通信、会话、分页与资源释放检查 |
| `IdePluginSmoke.java` | 在隔离 IDE 中检查安装包类加载 |
| `IdeUiSmoke.java` | 在真实 IDE 桌面检查回复窗口、草稿交互及正文导航；默认使用内存传输 |
| `draft-handoff.py` | 已授权测试账号的网页与 IDE 草稿接续，限定话题 482293、指定正文，禁止真实发送 |
| `proxy-migration-smoke.py`、`IdeProxyMigrationSmoke.java` | 用目标 IDE 的实际公共 API 和合成配置检查 HTTP/SOCKS 代理、代理凭据、PasswordSafe 属性及错误信息显示 |
| `navigation-fixture.py`、`navigation-regression.js` | 浏览器中的长帖导航、频控和窄屏布局回归 |

## JCEF 与安装包检查

需要 Python 3.9+、构建 JDK 17，以及目标 JetBrains 2026.2 IDE 自带的 JBR 和 JCEF。编译探针需要与 JBR 匹配的 `javac`。三平台统一使用 Python 入口；macOS 的 `--ide-home` 可填写 `.app` 路径。

```sh
# 本地合成页面：渲染、通信、Cookie、导航与运行时重建
python tools/smoke.py private --ide-home '/path/to/IDE'

# 额外访问 Linux Do 公开页面和 API，不提交登录表单
python tools/smoke.py private --ide-home '/path/to/IDE' --network

# 隔离 IDE 配置，验证当前安装包的真实类加载
python tools/smoke.py host --ide-home '/path/to/IDE'

# 指定安装包；可加 --native 检查原生浏览器及公开网络
python tools/smoke.py host --ide-home '/path/to/IDE' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.1.zip

# 真实 IDE 桌面交互：回复窗口与正文阅读器，不使用真实账号
python tools/smoke.py ui --ide-home '/path/to/IDE' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.1.zip

# 当前生产界面的宣传截图：使用示例话题与内存草稿，不发送真实帖子
python tools/smoke.py ui --ide-home '/path/to/IDE' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.1.zip --showcase
```

Windows 示例：`python tools/smoke.py private --ide-home 'C:\path\to\WebStorm'`。默认离线构建，依赖尚未缓存时加 `--online`。联网模式默认使用 LinuxDo DoH，可通过 `--doh-url` 指定测试地址。

独立 JCEF 检查需要桌面会话，Linux CI 可使用 `xvfb-run`。结果位于 `build/portable-smoke/`，成功标记为 `PORTABLE_NATIVE_PASS=true`；联网检查需各项断言通过且进程正常退出。安装包检查结果位于 `build/host-smoke/`；商业 IDE 的许可可能限制完整启动。模拟 Application 的检查不能替代安装包类加载验证，真实账号及桌面交互仍需按[跨平台指南](../docs/cross-platform.md)验收。

`ui` 使用已打包插件中的真实回复窗口、定时器和正文阅读器，输出 `IDE_UI_PASS=true`、断言报告与截图；自动建立空配置，避免首次启动导入个人设置。草稿延迟、断网、409 和发送均由内存传输模拟，并记录 EDT 响应时间。工具不打入插件包。

`--showcase` 单独捕获话题阅读、新建话题、按需预览、板块、标签、回复、设置和空白登录窗口，报告同样位于 `build/host-smoke/`。示例草稿只保存在内存；登录窗口可能读取论坛公开页面。截图期间请暂停其他桌面自动化，避免抢占窗口。发布前逐张检查图片，不以截图运行代替完整交互回归。

仅在账号拥有者明确授权后，且专用 Chrome CDP 窗口已登录测试账号时，可运行 `python tools/draft-handoff.py --ide-home '/path/to/IDE'`。需要 Playwright；默认连接端口 19337。该工具拒绝覆盖已有草稿，限定话题 482293 和指定测试正文，在浏览器内执行已认证草稿请求，不导出 Cookie/CSRF。IDE 使用实际回复窗口与草稿服务，通过测试文件桥接浏览器请求；网页使用原生编辑器。结果在 `build/draft-handoff/`，成功标记为 `DRAFT_HANDOFF_PASS=true`。它不会调用真实帖子发送接口；只在内容和序列匹配时清理本次草稿。不要把此桥接检查当作插件默认 HTTP 传输的完整登录验收。

## 代理 API 迁移检查

先生成发布 ZIP，再运行以下命令。测试在独立进程中使用合成配置与凭据，不读取当前 IDE 的账号或修改真实代理设置：

```sh
python tools/proxy-migration-smoke.py --ide-home '/path/to/IDE'
```

成功标记为 `TARGET_IDE_PROXY_MIGRATION_PASS=true`，报告在 `build/proxy-migration-smoke/result.txt`。编译 SDK 为 241，插件通过运行时适配使用目标 IDE 的 `ProxySettings` / `ProxyCredentialStore` 公共 API。目标 IDE 262 内部仍以旧代理状态实现凭据接口，因此检查工具的合成状态包含旧类型引用；这些工具不打入发布包。

## 干净构建校验

先运行[项目说明](../README.md)中的全量构建命令，再执行：

```sh
python tools/clean-release-build.py
```

工具复制当前源码（包括未提交修改），排除秘密文件及构建缓存，离线运行测试、打包和结构检查，并比较工作区 ZIP 的 SHA-256。源码清单与结果位于 `build/release-check/`；不会修改 Git 暂存区。依赖须已缓存，正式发布应固定对应源码提交。

## 长帖导航回归

先运行 `gradlew.bat test --tests '*TopicPresentationTest'`（macOS/Linux 使用 `./gradlew`）生成生产样式页面，再执行：

```sh
python tools/navigation-fixture.py
python -m http.server 8765 --bind 127.0.0.1 --directory output/playwright
```

另一个终端使用 Playwright CLI 打开 `http://127.0.0.1:8765/navigation-fixture.html`，执行 `playwright-cli run-code --filename tools/navigation-regression.js`。检查覆盖远距离跳转、请求合并与间隔、过期响应、429 冷却、本地定位、相邻分页和窄屏布局。数据与响应均为模拟，不连接论坛账号；截图位于 `output/playwright/`。

已运行专用 Chrome CDP 窗口、Python 环境安装了 Playwright 时，也可直接运行 `python tools/navigation-regression.py --endpoint http://127.0.0.1:19337`。先生成导航 fixture；脚本在独立的无账号浏览器上下文中拦截本地页面请求，无需启动 HTTP 服务。覆盖跳转返回、失败跳转、429 期间返回、选区引用、代码引用、跨楼层选区和窄屏布局；不会操作现有论坛页面或读取账号凭据。
