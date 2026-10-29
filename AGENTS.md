# FlareGo 开发约定

## 提交与发布

- 用户已明确要求：每次推送 `main` 后，成功的 Android 构建自动发布测试版，允许创建或移动滚动的 `latest` Tag 并更新对应 prerelease。
- 「提交代码」推送到 `main` 时包含上述自动测试发布，不等于发布正式版本；「构建 Android」「验证 Actions」本身不授权额外创建正式版本 Tag 或 Release。
- 其他分支、Pull request 和默认手动构建不发布；非 PR 构建继续上传签名 Actions artifact。
- 手动补发测试版仍可在 `main` 使用 `publish_snapshot=true`。旧提交不得覆盖更新的主线测试版，正式 Release 不得被自动测试发布覆盖。
- 正式版本发布仍由用户明确创建并推送的版本 Tag 触发；验证发布逻辑使用隔离测试，不自行创建其他真实发布。

## 项目边界

- `/home/jimxl/projects/dreamtools` 仅作为只读参考，不修改该项目。
- 功能说明区分真实 API 能力、演示流程、官方控制台入口和未来规划。
- 签名私钥、密码、云 Token 和本机配置不得进入源码、日志或公开截图。
