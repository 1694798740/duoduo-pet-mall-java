# 协作开发规范（CONTRIBUTING）

> 面向所有参与本仓库开发的成员：分支怎么建、代码怎么提、PR 怎么审、版本怎么发。
> **当前阶段（2026-11）**：仓库已公开，维护者可直接在 `main` 提交，分支保护暂未启用；外部贡献者请 Fork 后走 PR。多人协作或需要强制门禁时，再按下文的分支模型启用保护。

---

## 0. 开始之前：先解决访问权

**新建分支不会让别人获得仓库权限。** 仓库当前已公开：任何人可 clone / 下载 ZIP / Fork，但 **push 需要 Collaborator（Write）权限**。

| 方式 | 适用场景 | 操作 |
|---|---|---|
| 公开仓库 + Fork（当前可用） | 外部贡献者 | 无需授权：Fork 本仓库 → 在自己仓库开分支 → 向本仓库提 PR；改动不会直接落到本仓库 |
| 加协作者 | 长期一起开发 | 仓库 → **Settings → Collaborators → Add people**，角色选 **Write**（可推送分支、开 PR，不能改仓库设置） |
| 组织团队 | 多人团队 | Settings → Collaborators and teams → 邀请 Team，按 Read / Write / Maintain 分配 |

权限角色建议：

| 角色 | 适合谁 | 能做什么 |
|---|---|---|
| Read | 只读观察者 | 看代码、看 PR，不能推送 |
| Write | 普通开发者 | 推送分支、开 PR、跑 CI |
| Maintain | Tech Lead / 评审人 | 合并 PR、管理分支保护、发版 |
| Admin | 仓库所有者 | 改仓库设置、改可见性 |

---

## 1. 分支模型

```
main        ●────────────●──────────────●────────────▶  可发布、始终可部署
             \          / \            /
              \        /   \          /
develop        ●──●──●──────●────●───●──────────────▶  集成分支，日常开发汇入
                 \  \         \    \
                  \  feature/*  \    bugfix/*
                   \             \
                    release/v1.2.0 ──▶ 合回 main + develop，打 tag
hotfix/* 从 main 出 ──▶ 合回 main + develop，补丁版本 +1
```

| 分支 | 从哪来 | 合并到哪 | 用途 | 生命周期 |
|---|---|---|---|---|
| `main` | — | — | 唯一可发布分支，永远保持可部署 | 长期 |
| `develop` | `main` | `main`（经发布） | 集成分支，feature / bugfix 汇入 | 长期 |
| `feature/<编号>-<简述>` | `develop` | `develop`（Squash） | 新功能 | 短期，合并后删除 |
| `bugfix/<编号>-<简述>` | `develop` | `develop`（Squash） | 非紧急缺陷 | 短期，合并后删除 |
| `docs/<简述>` · `chore/<简述>` | `develop` | `develop`（Squash） | 文档 / 工程配置（经 Maintainer 同意可直接进 main） | 短期 |
| `release/<版本>` | `develop` | `main` + `develop`（Merge commit） | 发布准备：只修 bug、改版本号、补文档，**不加新功能** | 发版后删除 |
| `hotfix/<版本>` | `main` | `main` + `develop`（Merge commit） | 线上紧急修复 | 修复后删除 |

**预置的模板分支**：`feature/template`、`bugfix/template`、`hotfix/template`、`release/template` 只用于示例命名，与 `develop` 指向同一提交。创建真实分支后请删除对应模板（`git push origin --delete feature/template`）。

> **当前阶段执行方式**：维护者直接在 `main` 开发，不强制走 PR；`develop` 与模板分支保留给多人协作，等启用分支保护后再按上表运转。外部贡献者的 PR 合入 `develop`，由维护者定期同步到 `main`。

### 分支命名

- 全小写，单词用 `-` 连接；**不要用中文、空格、下划线**。
- 建议带 GitHub Issue / 需求编号：`feature/142-coin-signin`、`bugfix/188-cart-order`。
- 例：`feature/ai-rag-agent`、`bugfix/listing-sold-cas`、`hotfix/v1.2.1`、`release/v1.2.0`、`docs/branch-strategy`。

---

## 2. 日常开发流程

### 2.1 当前阶段：维护者直接开发（默认）

```bash
git checkout main
git pull --rebase origin main

# 开发 + 小步提交
git add <具体文件>
git commit -m "feat(coins): 新增签到月历接口"

# push 前至少编译；涉及接口 / 前端时再跑受影响冒烟
git push origin main
```

> push 后看一眼 CI：`main` 会跑编译 + 冒烟子集 + 全量冒烟。失败立即用 `git revert` 回退，不要 force push。
> 直推 `main` 的前提是**本地已验证**；没有跑过编译和冒烟就不要推。

### 2.2 多人协作 / 外部贡献：功能分支 + PR

```bash
# 1) 同步集成分支（每次开工前都做）
git checkout develop
git pull origin develop

# 2) 从 develop 建自己的分支
git checkout -b feature/142-coin-signin

# 3) 开发，小步提交（一个提交只做一件事）
git add <具体文件>
git commit -m "feat(coins): 新增签到月历接口"

# 4) 推送自己的分支（首次推送建立追踪）
git push -u origin feature/142-coin-signin

# 5) 在 GitHub 上向 develop 提 PR（不要直接向 main 提）
#    PR 标题同提交规范；描述按 .github/pull_request_template.md 填写

# 6) 评审通过、CI 绿后合并（建议 Squash），删除远程分支
git checkout develop
git pull origin develop
git branch -d feature/142-coin-signin
```

**每天同步一次上游**，避免分支漂移：

```bash
git fetch origin
git rebase origin/develop      # 只 rebase 自己的分支
# 冲突解决后：git add <文件> && git rebase --continue
git push --force-with-lease    # rebase 后必须强推自己的分支，用 --force-with-lease
```

> 共享分支（`main` / `develop` / `release/*`）**永远不要 rebase、不要 force push**。

---

## 3. 提交规范（Conventional Commits）

格式：

```
<type>(<scope>): <中文简述>

<为什么改、影响范围、关键决策（可选）>

Refs: #142
```

| type | 用途 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat(trade): 订单详情补资金托管明细` |
| `fix` | 缺陷修复 | `fix(product): 无品牌商品列表 500` |
| `docs` | 文档 | `docs(deploy): 补全从零上手步骤` |
| `refactor` | 重构（不改行为） | `refactor(common): 抽出 OperatorGuard` |
| `perf` | 性能优化 | `perf(search): 首页聚合改批量查询` |
| `test` | 测试 / 夹具 | `test(smoke): 新增闲置担保专项` |
| `build` | 构建 / 依赖 | `build(maven): 升级 MyBatis-Plus 3.5.5` |
| `ci` | 流水线 | `ci: PR 到 develop 也跑冒烟子集` |
| `chore` | 杂项 | `chore: 忽略本地备份目录` |
| `revert` | 回滚 | `revert: feat(coins): 签到月历接口` |

纪律：

- 一次提交只做一件事；**不要把格式化、重命名和功能混在一个提交里**。
- 简述写「做了什么」，正文写「为什么」；不写「update」「修改一下」这类无信息标题。
- scope 用模块名：`user` / `product` / `trade` / `fulfilment` / `marketing` / `ai` / `social` / `gateway` / `frontend` / `docs` / `scripts`。
- 按仓库既有约定（`AGENTS.md` 硬规矩 9）：改动涉及接口 / 数字 / 模块时，`docs/PLAN.md` 与 README 同步更新，并放进**同一次提交**。
- 禁止提交真实密钥、`.env`、数据库备份、测试截图产物（`.gitignore` 已覆盖，提交前用 `git status` 核对）。

---

## 4. PR 与评审

**PR 目标分支**：功能 / 缺陷 / 文档 → `develop`；发布 / 热修 → `main`。维护者内部改动当前可直接进 `main`，外部贡献一律提 PR。

PR 描述使用 [.github/pull_request_template.md](./.github/pull_request_template.md)，至少写清：

1. **变更内容**：做了什么，关联 Issue / 需求。
2. **验证证据**：跑了哪些命令、多少项断言、结果如何（不是「应该没问题」）。
3. **影响面与风险**：涉及哪些服务 / 库表 / 接口 / 前端路由，是否需要迁移 SQL。
4. **回滚方式**：出问题时怎么退（revert commit / 回滚镜像 / 恢复备份）。

**评审人检查清单**：

- [ ] 改动范围与 PR 描述一致，没有夹带无关修改
- [ ] 分层纪律：Controller 无业务逻辑、Service 不碰 `HttpServletRequest`、SQL 全部参数化
- [ ] 归属校验在服务层，不接受请求体里的 `userId` / `sellerId`
- [ ] 金额用「分」、ID 用字符串下发、幂等靠唯一索引或 CAS
- [ ] 新增 / 修改接口已同步 `docs/agent/04` 或 `docs/API.md`
- [ ] 有对应冒烟 / 单测；关键路径有正反断言
- [ ] 没有真实密钥、调试代码、临时打印
- [ ] CI 绿（`build` + `smoke` 作业通过）

**合并方式**：

| 场景 | 方式 | 说明 |
|---|---|---|
| feature / bugfix / docs / chore → develop | **Squash merge** | 一个分支压成一个提交，历史干净 |
| release → main | **Merge commit（--no-ff）** | 保留发布边界，便于整体回滚 |
| hotfix → main | **Merge commit（--no-ff）** | 同上，随后合回 develop |
| release / hotfix → develop | Merge commit | 防止修复只留在 main |

合并后**删除源分支**；不要长期保留已合并分支。

---

## 5. 发布与热修复

### 发布（以 v1.2.0 为例）

```bash
git checkout develop && git pull origin develop
git checkout -b release/v1.2.0
# 只做：改版本号、补发布说明、修发布前发现的 bug；禁止加新功能
git commit -m "chore(release): 准备 v1.2.0"

# 全量验证（编译 + 冒烟 + 前端构建 + e2e）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\check-all.ps1

git checkout main && git pull origin main
git merge --no-ff release/v1.2.0 -m "release: v1.2.0"
git tag -a v1.2.0 -m "v1.2.0"
git push origin main --tags

# 回合并，防止 release 上的修复在 develop 丢失
git checkout develop
git merge --no-ff release/v1.2.0 -m "chore: 同步 release/v1.2.0 回 develop"
git push origin develop

git branch -d release/v1.2.0
git push origin --delete release/v1.2.0
```

版本号遵循 SemVer：`v主.次.修订`；不兼容变更升主版本，新功能升次版本，修复升修订号。

### 热修复（以 v1.2.1 为例）

```bash
git checkout main && git pull origin main
git checkout -b hotfix/v1.2.1
# 最小修复 + 针对性验证
git commit -m "fix(trade): 修复重复回调导致重复入账"

git checkout main
git merge --no-ff hotfix/v1.2.1 -m "hotfix: v1.2.1"
git tag -a v1.2.1 -m "v1.2.1"
git push origin main --tags

git checkout develop
git merge --no-ff hotfix/v1.2.1 -m "chore: 同步 hotfix/v1.2.1 回 develop"
git push origin develop
```

---

## 6. 分支保护（可选，当前未启用）

当前阶段不启用分支保护，维护者直推 `main`。将来需要强制门禁时，按下面设置：

GitHub → **Settings → Branches → Add branch protection rule**，分别对 `main` 和 `develop` 勾选：

- [x] Require a pull request before merging
- [x] Require approvals：**1**
- [x] Dismiss stale pull request approvals when new commits are pushed
- [x] Require status checks to pass before merging → 选择 CI 的 `build`、`smoke`
- [x] Require conversation resolution before merging
- [x] Do not allow bypassing the above settings（管理员也不例外）
- [x] Restrict who can push to matching branches（只允许 Maintain 及以上）
- [x] Do not allow force pushes / deletions

> 没有分支保护时，规范只能靠自觉；设好之后，`git push origin main` 会被服务端直接拒绝，所有改动只能走 PR。

---

## 7. 常见问题

| 问题 | 原因 / 处理 |
|---|---|
| 对方 `git clone` 报 `Repository not found` | 仓库为私有且未授权（当前已公开，正常不会出现）；若仍报错，先核对地址拼写与网络代理 |
| `git push` 报 403 | 只有 Read 权限；让所有者升为 Write |
| 推送 `main` 被拒绝 | 启用了分支保护才会如此；当前维护者可直接推送，外部贡献者需 Fork + PR |
| CI 在 develop 的 PR 上没跑 | 检查 `.github/workflows/ci.yml` 的 `pull_request.branches` 是否包含 `develop` |
| 误在 main 上提交了 | 未推送：`git switch -c feature/xxx` 带走改动，再 `git switch main && git reset --hard origin/main`；已推送：`git revert <commit>`，禁止强推改写历史 |
| 分支落后 develop 很多 | `git fetch && git rebase origin/develop`，冲突解决后 `git push --force-with-lease` |
| 合并后想撤销 | 未发布：`git revert -m 1 <merge-commit>`；已发布：走 hotfix，不要改写已发布历史 |

**绝对禁止**：force push 共享分支（`main` / `develop` / `release/*`）；跳过 CI 或 Git hooks；把密钥写进仓库；删除他人未合并的分支；未跑编译和冒烟就直推 `main`。
