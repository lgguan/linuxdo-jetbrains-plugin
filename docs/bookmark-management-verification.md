# 1.0.3 书签管理与浏览优化验收

本页保留开发期书签管理包的验证记录。正式 1.0.3 同时包含 Boost 等后续改进，最终安装包、校验值及发布状态见[1.0.3 发布验证](release-1.0.3.md)。

2026-10-04，按浏览器版论坛调研后的开发计划完成本地版本。安装 `build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip` 后重启 IDE；兼容范围保持 `262.*`，本轮未发布。

## 使用与范围

“我的 → 书签”默认搜索全部书签，输入后按 Enter 或点击“搜索”。服务器搜索覆盖名称、话题标题与正文；“已加载内容”只筛选当前缓存，两种范围有明确入口。清空搜索返回全部列表，分页保留当前搜索词。各项目窗口独立保存搜索条件、选中项和滚动位置，应用服务共享相同查询的读取。

选中普通 Post 书签后，可以编辑名称、设置或取消提醒。名称最多 100 字符，自定义时间按界面标明的系统时区输入。未改动的提醒保留原始时间精度；修改时间必须在未来，夏令时歧义时间要求另选。保存会显式保留服务器现有的提醒后处理策略，提醒由论坛服务器发送。置顶、Topic、未知类型或不能完整确认设置的书签使用网页编辑；Post 和 Topic 书签支持确认删除，删除同时取消提醒。

正文的“我的书签”打开同一个个人管理入口，并高亮“我的”模块。成功修改或删除后，各项目窗口的书签结果失效并按需刷新，当前正文只更新书签元数据，保留正文节点和阅读位置。Hot 使用 `/hot.json`；分类／标签的 Hot 地址同步修正，Top 仍保留自身周期参数。

## 写入与请求保护

个人列表与正文共用 `BookmarkService`，使用现有会话检查、只读检查、写入串行与冷却机制。编辑前重新读取帖子与书签信息；发现另一窗口修改时保留输入，要求先核对服务器。网络结果不明确时不重复发送写请求，跨窗口共同阻止重复操作，提供显式“核对服务器”入口。

删除确认立即从已有查询中移除书签，并废弃旧读取代次，迟到响应不能将条目放回列表。书签第一页刷新替换旧快照，失败保留旧结果。缓存按账号、论坛、会话、类型与搜索词隔离；旧搜索缓存有数量上限，同时保留其他窗口正在使用的查询。未登录、凭据待确认和 Boss Key 隐藏期间不启动个人读取。本轮未加入本地提醒定时器或书签正文磁盘缓存。

## 验证

最终安装包在 Windows IntelliJ IDEA Ultimate `IU-262.10968.63` 验证。全量桌面测试 **433 项通过，0 失败、0 错误、0 跳过**；`buildPlugin` 和 `verifyPlugin` 成功，Plugin Verifier 判定 **Compatible**。

真实 IDEA 的双项目窗口 **49 项**验收覆盖远程搜索命中未加载书签、各窗口独立搜索、原生编辑、提醒取消、策略保留、同步删除、正文节点保留、模块高亮、分页、账号切换与草稿回归。[浏览与编辑回归 130 项](reference/bookmark-management-composer-ide.txt)通过，含分类／标签 Hot 服务端查询；[登录页物理键盘回归 11 项](reference/bookmark-management-browser-keyboard-ide.txt)通过，覆盖 Tab、Shift+Tab 与外部组件焦点导航。[浏览器隔离夹具 4 项](reference/bookmark-management-browser.json)另验证正文入口、错误提示及显式重试。详细数量与脱敏断言见[验证汇总](reference/bookmark-management-summary.json)、[IDE 书签断言](reference/bookmark-management-ide.txt)、[兼容性结果](reference/bookmark-management-plugin-verifier.txt)。

原生编辑窗口在窄窗口下验收：[编辑窗口截图](images/bookmark-editor.png)。GUI 验收使用合成账号和内存 HTTP 接口；书签名称、提醒和删除共三次内存写入，真实论坛写入为零。

此前经用户授权的本站浏览器操作已验证名称编辑、提醒变更及删除后恢复，原始名称和提醒恢复完成；本站当前账号只有少量书签，真实多页搜索和服务器最终提醒投递尚未验收。置顶和特殊类型的原生编辑、批量操作、循环提醒、文件夹及未读语义调整不在本轮范围。macOS/Linux 的原生界面仍需单独验收。

## 复现

```powershell
$env:JAVA_HOME='C:/Users/lgguan/.jdks/corretto-21.0.12.1'
.\gradlew.bat test buildPlugin verifyPlugin -PdesktopTests=true --console=plain
.\gradlew.bat runPluginVerifier '-PverifierIdePath=C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' -PverifierOffline=true --offline --console=plain
python tools/smoke.py ui --personal-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip
python tools/smoke.py ui --composer-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip
python tools/smoke.py ui --browser-keyboard-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip
output/browser-tools/venv/Scripts/python.exe tools/personal-browser-check.py
```

浏览器夹具需要 Playwright 和专用 CDP 19337，测试创建独立的无账号上下文。安装包 SHA-256 与各项检查数量一并保存在验证汇总中。
