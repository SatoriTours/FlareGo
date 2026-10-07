# FlareGo 开发约定

## 提交与发布

- 用户未明确要求时，不创建、推送、移动或删除 Git Tag，也不创建、更新、发布或删除 GitHub Release。
- 「提交代码」「构建 Android」「验证 Actions」只包含源码提交和构建检查，不包含版本发布。
- 普通分支提交和默认手动构建只上传签名 Actions artifact。
- 只有用户明确要求测试版发布时，才使用 `publish_snapshot=true`；该操作会创建或移动 `latest` Tag。
- 正式版本发布由明确创建并推送的版本 Tag 触发；验证发布逻辑时使用隔离测试，不自行创建真实发布。

## 项目边界

- `/home/jimxl/projects/dreamtools` 仅作为只读参考，不修改该项目。
- 功能说明区分真实 API 能力、演示流程、官方控制台入口和未来规划。
- 签名私钥、密码、云 Token 和本机配置不得进入源码、日志或公开截图。
