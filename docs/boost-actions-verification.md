# 1.0.3 Boost 举报与用户信息

2026-10-04，将发布前内部 1.0.4 的 Boost 改进合入公开版本 1.0.3，兼容范围保持 IDEA `262.*`。安装 `build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip` 后重启 IDE；最终发布状态和完整验收见[1.0.3 发布验证](release-1.0.3.md)。

## 操作方式

点击 Boost 的内容气泡展开操作。自己发出的 Boost 只显示垃圾桶删除图标，不显示举报；别人发出的 Boost 显示举报。直接点击头像查看公开资料，展开区域不另设用户信息按钮。资料卡显示服务器可见的用户名、名称、称号、简介、信任等级与时间等字段，并提供完整资料网页入口；资料读取失败可显式重试。简介按纯文本显示，仅传回选定的公开字段，不传回邮箱或凭据。

展开操作不发送请求，直接使用话题已返回的 `can_flag`、`available_flags` 和 `user_flag_status`，允许举报时立即可点击，已明确禁止或待处理时禁用。缺少权限字段时允许先打开只读表单，读取 Boost 自身的最新权限和可用原因，验证完成前不能提交。表单不再重复读取帖子和楼层流，举报目录与阅读规则共用同一份网站数据，按会话隔离缓存；读取只更新举报状态，不替换头像、正文或覆盖楼层上其他操作的结果。

登录不等于具备所有举报权限。[上游规则](https://github.com/discourse/discourse-boosts/blob/main/plugin.rb#L34)限制举报自己的 Boost、禁言用户、不可见或隐藏的帖子，也受允许举报的用户组、工作人员举报设置和启用的原因约束。举报原因同时满足网站启用、适用于 Boost 和该 Boost 返回的 `available_flags`。打开表单时核对权限与只读状态，最终提交前仍重新读取目标楼层和 Boost 并检查服务器权限。

举报表单明确显示 Boost 作者与楼层；要求说明的原因必须填写说明，最多 4000 字符。提交按钮触发原生确认弹窗，展示 Boost 内容、作者、原因与说明，取消不发送请求且保留输入。成功后禁用重复提交；后续跨窗口读取暂时落后，也不会重复举报。网络结果不明确时仅尝试读取核对，不能确认则阻止重发，保留输入并提供网页核对入口。权限响应迟到不会重新展开已关闭气泡。

## 网页与接口依据

官方[Boost 气泡组件](https://github.com/discourse/discourse-boosts/blob/main/assets/javascripts/discourse/components/boost-bubble.gjs)区分内容展开和头像资料入口，并支持按需读取权限。[Boost 举报适配器](https://github.com/discourse/discourse-boosts/blob/main/assets/javascripts/discourse/lib/boost-flag.js)、[服务端举报处理](https://github.com/discourse/discourse-boosts/blob/main/app/services/discourse_boosts/boost/flag.rb)和[序列化器](https://github.com/discourse/discourse-boosts/blob/main/app/serializers/discourse_boosts/boost_serializer.rb)用于核对目标、字段与状态。

举报使用 `POST /discourse-boosts/boosts/<boostId>/flags`，发送 `flag_type_id`、`message` 和显式为 false 的 `take_action`、`queue_for_review`。不调用包含该 Boost 的帖子举报接口。提交前检查会话、页面、只读状态和最新权限，使用现有共享写入队列与 HTTP 429 冷却机制。

本站浏览器只读复查这次返回 **HTTP 429**，因此停止请求，未发送真实举报。本轮不能声称已经在本站验证最新举报弹窗或成功举报；实现依据来自官方代码，界面与失败行为由隔离传输验证。此前的真实 Boost 读取证据保存在[既有只读记录](reference/linuxdo-boosts-readonly.json)。

## 验收

全量桌面测试 **442 项通过，0 失败、0 错误、0 跳过**；插件打包与结构检查成功，Plugin Verifier 对 `IU-262.10968.63` 判定 **Compatible**。[真实 IDEA 正文回归 89 项](reference/boost-actions-ide.txt)和[隔离浏览器 25 项](reference/boost-actions-browser.json)通过，均对应当前修复后的安装包。

原生回归覆盖权限按需读取、资料卡与网页入口、必填说明、取消确认无写入、明确确认仅写入一次、已举报禁用与正文节点保留，同时回归既有 Boost 输入、发送、撤回、主题、缩放、已读同步与正文重连。所有论坛请求由内存接口接管，真实论坛写入为零。浏览器夹具补充拒绝、只读、失败保留输入、再次打开保留未提交说明、迟到响应、公开简介纯文本、资料读取重试与不明确结果保护。

按实际使用反馈修复了头像图片被全局图片查看器接管的问题。全局点击分发遇到用户资料控件中的图片会交给资料入口处理。之前只点头像按钮或首字母占位，未覆盖图片本身；现在原生 IDEA 使用 Robot 鼠标直接点击实际 `img.boost-avatar`，确认资料卡显示且图片浮层没有打开。浏览器夹具也加载实际渲染器的全局点击分发代码，验证头像图片不会进入图片查看器，正文图片仍正常进入图片查看器。

权限接口人为延迟 1.2 秒，原生鼠标点击 Boost 后举报立即可点击且没有发起权限请求，打开表单才读取一次该 Boost。自己发出的 Boost 展开后仅有垃圾桶，鼠标直接点击 SVG 删除成功。浏览器额外验证反复展开零请求、已明确禁止时禁用、未登录时禁用、缺少字段可以读取表单但不能凭登录状态直接提交，以及迟到的表单响应不会覆盖用户资料卡。普通帖子场景显式清理上一场景的跳转历史，避免真实鼠标滚动产生的返回位置污染布局断言。

截图：[自己的 Boost 仅有垃圾桶](images/boost-own-actions.png)、[用户信息](images/boost-user-card.png)、[举报表单](images/boost-report-form.png)。SHA-256 与验证分类见[汇总](reference/boost-actions-summary.json)。本轮未验证真实服务器举报后的审核结果，也未进行 macOS/Linux 原生验收。

```powershell
$env:JAVA_HOME='C:/Users/lgguan/.jdks/corretto-21.0.12.1'
.\gradlew.bat test buildPlugin verifyPlugin -PdesktopTests=true --offline --console=plain
.\gradlew.bat runPluginVerifier '-PverifierIdePath=C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' -PverifierOffline=true --offline --console=plain
python tools/smoke.py ui --reader-only --ide-home 'C:/Users/lgguan/AppData/Local/Programs/IntelliJ IDEA Ultimate' --plugin-zip build/distributions/linuxdo-jetbrains-plugin-1.0.3.zip
output/browser-tools/venv/Scripts/python.exe tools/boost-actions-browser-check.py
```

浏览器夹具连接专用 CDP 19337，并创建独立无账号上下文。`tools/boost-actions-reference-check.py` 用于未来本站只读复查；非 200 响应停止，不自动重试，不提交举报或其他写请求。
