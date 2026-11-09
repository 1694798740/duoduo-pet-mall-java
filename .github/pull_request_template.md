## 变更内容

<!-- 做了什么，关联 Issue / 需求编号 -->

Closes #

## 验证证据

<!-- 写命令 + 结果，例如：scripts\run-all-smoke.ps1 32/32；npm run build 通过 -->

- [ ] 后端编译通过（`mvn -gs maven/settings.xml -B -DskipTests package`）
- [ ] 前端 `npm run typecheck` / `npm run build` 通过（有前端改动时）
- [ ] 受影响的冒烟 / e2e 已跑，结果：____

## 影响面与风险

<!-- 涉及的服务 / 库表 / 接口 / 前端路由；是否需要执行 incremental SQL -->

## 回滚方式

<!-- revert commit / 回滚镜像 / 恢复备份 -->

## 自查

- [ ] 改动范围与描述一致，无夹带修改
- [ ] 未提交密钥、`.env`、临时调试代码
- [ ] 接口 / 文档 / README 已同步（如涉及）
