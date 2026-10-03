# 开发与发布工具

保留三类检查：干净目录构建、JCEF 与安装包验证、长帖导航回归。常规单元测试、打包和 Plugin Verifier 直接使用 Gradle，命令见[项目说明](../README.md)。

## 工具清单

| 文件 | 用途 |
| --- | --- |
| `personal-reference-check.py` | 现有专用 Chrome 页面的 5 个 GET，核对当前账号与四类个人接口；只导出字段名、类型及计数，不导出用户名、标题、正文或凭据 |
| `personal-browser-check.py` | 独立浏览器上下文验证生产书签脚本的文本安全、楼层／话题目标、未知目标、续页及失败重试 |
| `MarkdownEditorIdeAcceptance.java` | `ui --editor-only`：两个真实项目的三种生产编辑窗口，格式切换、键盘、输入法事件、对话框、主题及模拟草稿／编辑保存，所有 HTTP 在内存中处理 |
| `PersonalIdeAcceptance.java` | `ui --personal-only`：两个真实 IDEA 项目窗口的生产列表、分页、筛选、草稿恢复、会话及老板键检查，内存 HTTP，拒绝所有写请求 |
| `notification-reference-check.py` | 通过已有专用 Chrome 页面只读核对通知分页、状态、计数和本站类型配置；只保存字段与汇总，不访问 recent 或标读 |
| `notification-navigation-regression.py` | 在独立浏览器上下文对生产导航脚本验证显示确认、缺失目标、失败、取消、不可见正文与旧回调；仅使用本地模拟页面 |
| `clean-release-build.py` | 在干净目录构建当前源码，比较发布 ZIP 的 SHA-256 |
| `package-release-source.py` | 打包当前允许列表中的完整源码、测试及验收资料，生成源码清单和安装包/源码包 SHA-256 |
| `boost-reference-check.py` | 通过已有专用 Chrome 页面只读 GET 对照 `can_boost`、Boost 模型和删除权限；不加载新论坛页面，不发送或撤回 Boost |
| `smoke.py`、`smoke.init.gradle` | 跨平台检查入口及 Gradle 类路径导出 |
| `StandaloneSmokeApplication.java`、`PluginCefSmoke.java`、`PortableJcefSmoke.java`、`PaginationSmoke.java` | JCEF 原生渲染、通信、会话、分页与资源释放检查 |
| `IdePluginSmoke.java` | 在隔离 IDE 中检查安装包类加载 |
| `IdeUiSmoke.java` | 在真实 IDE 桌面检查回复窗口、草稿交互及正文导航；默认使用内存传输 |
| `search-reference-check.py` | 通过专用 Chrome CDP 读取 Linux Do 原生高级搜索选项、排序、功能开关与截图；`--check-requests` 另核对公开标签目录和组合列表 GET，遇 429 或验证立即停止 |
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

第三期可用 `ui --editor-only` 验证三种编辑窗口的 Markdown 辅助；Windows 加 `--system-ime`，使用已安装的中文输入法和实际按键验证候选确认不会触发格式操作或提交。现有回归可分为 `--composer-only`（浏览与编辑器）和 `--reader-only`（正文与浏览器），分别运行隔离 IDE，保留原断言并减少不同窗口初始化的焦点干扰。综合探针仍保留默认入口；本期同进程初始焦点失败记录见[编辑辅助验收](../docs/markdown-editor-verification.md)。

`ui` 使用已打包插件中的话题列表、高级搜索、创建与回复窗口、定时器和正文阅读器，输出 `IDE_UI_PASS=true`、断言报告与截图；自动建立空配置，避免首次启动导入个人设置。覆盖标签组合请求、分页、键盘选择与整块鼠标点击、菜单内移除、窄窗口换行、提示状态、主题与缩放。草稿延迟、断网、409 和发送均由内存传输模拟，并记录 EDT 响应时间。工具不打入插件包。

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

## 通知回归

`ui --reader-only` 同时验证通知弹窗、账号计数、历史分页与失败重试，以及生产文件编辑器新开和复用后的显示确认。单条和账号全部标读由内存 HTTP 传输接管，不修改真实账号状态。浏览器回归先运行 `python tools/navigation-fixture.py`，再用安装了 Playwright 的 Python 运行 `tools/notification-navigation-regression.py` 和 `tools/navigation-regression.py`。默认连接专用 Chrome CDP 端口 19337，建立并关闭自己的隔离上下文，不修改已有论坛页面。

`ui --notifications-only` 在目标 IDEA 中打开两个真实项目窗口，通过通知按钮和列表点击进入实际 `FileEditorManager`，并派发 IDE 气泡的生产动作。覆盖新开与复用、403/404/缺失楼层、Boost 与个人消息、跨窗口计数和请求合并、切换标签页取消、老板键隐藏与恢复、模拟账号切换及原生 JCEF 回调通道断连后的重试。使用独立配置、模拟凭据和内存 HTTP，所有论坛写请求均被接管。两个测试窗口置顶，以免桌面上的其他程序挡住鼠标测试；退出时关闭这些测试项目。

`python tools/notification-reference-check.py` 使用已有专用论坛页面执行 GET，只保存类型配置和字段汇总，不导出 Cookie、CSRF、用户名、正文或通知 ID，遇论坛错误即停止。本站真实标读需账号拥有者另行明确授权。
