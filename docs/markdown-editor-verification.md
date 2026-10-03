# 第三期：Markdown 编辑辅助

2026-10-03。覆盖新话题、普通回复和编辑帖子三个生产编辑窗口，沿用版本 1.0.2，仅提交本地 Git，未推送或发布。

## 使用方法

- 粗体、斜体和行内代码支持再次执行取消格式；选中文字、完整标记和格式内部光标均按可确认范围处理。部分选区不会取消其他文字的格式。每次辅助操作可一次撤销，恢复光标及选区方向。
- 引用、标题和列表处理完整行；选区结束于下一行开头时不包含该行。“更多”提供一至六级标题、有序列表、任务列表和缩进。同类型列表再次执行移除标记；混合列表统一转换，保留缩进和已有任务完成状态。
- 列表行末 Enter 续接原标记、引用前缀与缩进；有序编号递增，任务续行为未完成项。空项 Enter 移除列表标记，保留引用前缀。行中、有选区或代码内 Enter 保持普通换行；Shift+Enter 始终普通换行。
- 列表或已确认围栏代码内容中的 Tab／Shift+Tab 增减两空格缩进，已有制表符作为一个缩进单位移除。普通文本或混合结构中的 Tab 用于焦点导航。Control+Tab／Control+Shift+Tab 可离开编辑区；macOS 同样使用 Control 键。
- “更多 → 代码块…”可插入围栏、修改既有语言，或移除围栏保留代码内容。语言可留空，最多 64 个字母、数字或 `_ + . -`。包含反引号的正文使用更长围栏。不完整或复杂嵌套围栏保持原文并提示直接编辑。
- 链接按钮在普通行内链接处回填文字与 URL；确认后替换原链接，不再套一层链接。支持 HTTP／HTTPS，转义 Markdown 特殊字符，不访问链接。图片、引用式链接、带标题或格式化文字的复杂链接保留原样。取消对话框不修改正文。

## 状态与隐私

辅助操作共用纯文本转换层和 Swing 适配层，不新增论坛接口。网络、草稿键及帖子保存流程继续由原服务处理；工具栏随正文的可编辑状态启用，窗口关闭后注销监听及快捷键。

新话题和回复仍在修改停止两秒后同步；仅恢复草稿、打开或取消辅助对话框不写入。不同草稿键保持隔离，同键跨项目聚焦已有窗口。编辑帖子没有草稿键，只有显式保存才提交编辑，保留原因、原始正文核对和冲突流程。

主题更新保留正文及选区；编辑帖子也跟随 IDE 编辑器配色，窄窗口采用上下布局。预览沿用原有按需方式。输入法组合状态下不执行 Enter／Tab 辅助。不新增本地正文持久化，诊断资料使用示例内容。

## 验证方式

| 检查 | 结果与范围 |
| --- | --- |
| 全量测试 | 420 项通过，0 失败、0 错误、0 跳过；含本期 50 项纯文本测试与模拟传输 |
| 新增编辑辅助原生验收 | 两个真实 IDEA 项目窗口，三类生产编辑器，70 项通过；模拟写入 3 次，真实写入 0 |
| 原有浏览／编辑器回归 | 独立 IDEA 进程 130 项通过，涵盖上传、草稿冲突、发布结果不明及账号切换 |
| 原有正文回归 | 独立 IDEA 进程 77 项通过，涵盖导航、通知跳转、读取计时、缩放及浏览器恢复 |
| 个人入口原生回归 | 两个真实项目窗口 35 项通过，真实及模拟写入均为 0 |
| 通知原生回归 | 两个真实项目窗口 38 项通过，仅内存模拟写入 |
| 本地构建 | `buildPlugin`、`verifyPlugin` 通过；版本保持 1.0.2 |
| Plugin Verifier | 1.410，目标 IU-262.10968.63，Compatible |
| 本站只读 | 本期未新增请求，不把模拟响应记作本站实测 |

单元测试覆盖格式切换、部分选区、反向选区、中文和表情、CRLF、选区边界、列表续行和退出、任务状态、围栏及嵌套保护、语言修改和链接降级。

`MarkdownEditorIdeAcceptance.java` 在两个真实 Windows IDEA 项目窗口中运行三个生产编辑器，使用实际键盘检查 Tab 缩进和焦点导航，验证对话框、撤销、主题、窄窗口与模拟保存。HTTP 拦截器在进入网络前返回内存响应；所有模拟写入只存在于进程内，不调用真实论坛。

输入法检查向真实 IDEA 编辑组件发送组合输入事件，验证候选阶段的辅助门禁；没有自动操作系统输入法候选窗口。macOS/Linux 原生验收仍待后续执行。

示例界面：[新话题](images/editor-helper-topic.png)、[回复](images/editor-helper-reply.png)、[编辑帖子](images/editor-helper-edit.png)。截图仅使用内存示例内容。

完整结果、安装包摘要与原生报告见 [验证汇总](reference/markdown-editor-summary.json)。本期没有新增本站请求，既有只读记录不作为本期新增实测。

既有综合探针在编辑器预览与正文检查同进程连跑时，正文初始焦点门槛未通过，见 [初始中止记录](reference/markdown-editor-regression-initial.txt)。控件点击改为实际坐标，并等待浏览器初始化后的焦点稳定；正文单独 77 项通过。其余浏览与编辑器断言使用 `--composer-only` 在另一个隔离 IDEA 进程执行，测试断言保持完整。该拆分不表示同进程焦点问题已完全消除。

## 复验命令

在构建 JDK 21 环境运行：

```powershell
./gradlew.bat test -PdesktopTests=true buildPlugin verifyPlugin --offline
./gradlew.bat runPluginVerifier '-PverifierIdePath=C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' -PverifierOffline=true --offline
python tools/smoke.py ui --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip --editor-only --output-base D:/ld-editor-smoke
# 既有回归分两个隔离进程执行；分别沿用原浏览／编辑和正文断言
python tools/smoke.py ui --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip --composer-only
python tools/smoke.py ui --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.2.zip --reader-only
```

`ui` 默认使用模拟传输。运行原生 UI 检查时避免其他桌面自动化同时抢占焦点。测试工具不打入插件安装包。
