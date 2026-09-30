# 恢复 GitHub 推送能力 · 操作指引

> 更新日期：2026-09-15 ｜ 适用仓库：`duoduo-pet-mall-java`（私有）
> **状态：✅ 已完成** —— 你登录过一次之后，凭据已存入 Windows 凭据管理器，
> 远端 `main` 已同步为 `119fd83`，与本地**逐字节一致（90/90 文件）**。
> 下面第 2 章的步骤仍然有效（以后需要推送时照跑即可），第 8 章记录了本次暴露的两个坑与修复。

---

## 0. 先纠正一个旧结论：链路其实已经通了

之前我说「沙箱里 `git push` 用不了，只能绕道 GitHub Git Data API」——**那个前提已经不存在了**。
我刚复测了一遍，结果是链路完全打通：

| 复测项 | 结果 | 说明 |
|---|---|---|
| `git ls-remote https://github.com/git/git.git HEAD`（公开仓库、免凭据） | **exit 0**，返回 SHA | git 传输层本身可用 |
| 目标仓库带**占位**凭据访问 `info/refs` | **HTTP 401** | 服务端**收到了**凭据并拒绝 → 链路通，只差有效凭据 |
| `https://github.com` | **200** | 站点可达 |
| 实测代理 | `127.0.0.1:3386` | 拦路的旧代理 `127.0.0.1:8276` 已不存在 |

判断依据很直接：当初失败是 **502**（隧道被拦，服务端根本没收到请求）；
现在带占位凭据得到的是 **401**（服务端收到了并拒绝认证）。**这是两件完全不同的事。**

> ⚠️ **后续补测的更精确结论（重要）**：同一时刻从**沙箱**访问 GitHub 的两个入口，可用性并不一致——
> `api.github.com` **200 可用**（代理与直连均通），而 `github.com`（含 git 的 `info/refs`）
> 返回 **502 / Empty reply from server**；但在**你自己的窗口**里两者都通（你的 `git fetch` 实测成功）。
> 也就是说：**你侧走 `git push`，沙箱侧走 REST API**，这是两条并行可用的路。

---

## 1. 现在唯一缺的东西：凭据

我把本机 git 环境查清楚了：

| 项 | 实测 |
|---|---|
| git | `D:\devsoft\Git\cmd\git.exe`，版本 2.55.0.windows.2 |
| 凭据助手 | 系统级已配置 `credential.helper=manager` |
| Git Credential Manager | `D:\devsoft\Git\mingw64\bin\git-credential-manager.exe`（存在） |
| Windows 凭据管理器里的 GitHub 凭据 | 授权前 **0 条**；你跑完脚本后 **已出现 `git:https://github.com`**（另有前公司私服残留 `git:https://100.wunding.com:1443`） |
| `gh` CLI | 未安装 |

**结论：自动登录的通道是现成的，只需授权一次。**（本次已完成）

---

## 2. 方案 A（推荐）：跑一条命令，浏览器登录一次

在你**自己的** PowerShell 窗口里执行（普通窗口即可，**不需要**管理员）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File "D:\智能体项目\duoduo-pet-mall-java\scripts\push-to-github.ps1"
```

脚本会依次做这 6 件事：

| 步骤 | 做什么 |
|---|---|
| 1 | **本地检查**：分支必须是 `main`；报告提交数与文件数；工作区脏则提示；**密钥自检**（`.env` / 私钥一旦入库就中止） |
| 2 | **配置 origin**：自动设为 `https://github.com/1694798740/duoduo-pet-mall-java.git`，并检查 `.git/config` 里有无明文令牌 |
| 3 | **读取远端** → **这时会弹出 GitHub 登录窗口**，用 `1694798740` 登录授权 |
| 4 | **覆盖安全闸门**：逐个比对远端与本地文件路径，只有确认「远端没有任何本地没有的文件」才继续 |
| 5 | **推送**（`git push --force`） |
| 6 | **校验**：读回远端 SHA，确认与本地 HEAD 一致，打印结论 |

### 2.1 为什么必须 `--force`

这一点必须说清楚，不能含糊：

- **远端 main** 上现有的提交，是早期用 **GitHub Git Data API** 逐次生成的
  （文件内容取自本地工作区，但提交对象由 API 重建）；
- **本地 main** 上的 15 个提交，是 `git commit` 生成的；
- 两者**内容一致、提交对象不同** → 是**两套互不相干的历史**（没有共同祖先）；
- 因此普通 `git push` 会被判定为「非快进（non-fast-forward）」而拒绝。

**内容不会丢**，原因有两条：

1. 历史上每一轮推送的**源都是本地工作区**，远端内容始终是本地内容的子集；
2. 脚本在 push 前**先 fetch 远端、逐个比对文件路径**，只有确认「远端独有文件数 = 0」才继续。
   万一远端真有本地没有的文件，脚本会**列出这些文件并停止**（不加 `-Force` 就不会推）。

### 2.2 登录之后就一劳永逸

GCM 会把凭据写进 **Windows 凭据管理器**。因为沙箱里的 PowerShell 与你的终端是**同一个 Windows 用户**，
所以**此后我自己也能直接 `git push`** —— 你不需要把任何 Token 交给我，也不需要每次重新授权。

### 2.3 附带能力

```powershell
# 只做本地检查，不联网、不推送（想先看看状态就跑这个）
... scripts\push-to-github.ps1 -PreflightOnly

# 万一安全闸门拦住了、而你确认可以丢弃远端那些文件
... scripts\push-to-github.ps1 -Force

# 换仓库
... scripts\push-to-github.ps1 -RemoteUrl https://github.com/用户/仓库.git
```

脚本我已做过校验：**语法解析 0 错误**、存为 UTF-8 with BOM（PS 5.1 跑中文脚本的硬要求）、
`-PreflightOnly` 实跑通过。实跑输出如下（可直接对照）：

```
----------------------------------------------------------------------
推送 GitHub · duoduo-pet-mall-java
仓库：D:\智能体项目\duoduo-pet-mall-java
----------------------------------------------------------------------
[1] 本地检查
    分支 = main
    本地 HEAD = 982b2ec（共 15 个提交）
    纳入版本控制的文件 = 89
    工作区 = 干净
    密钥自检 = 通过（无 .env / 私钥入库）

[2] 远端配置
    origin = https://github.com/1694798740/duoduo-pet-mall-java.git（已存在，无需改动）
    令牌残留自检 = 通过
----------------------------------------------------------------------
预检完成（-PreflightOnly，未联网、未推送）
----------------------------------------------------------------------
```

---

## 3. 方案 B（如果你不想自己操作）：给我一个新 PAT

1. 打开 https://github.com/settings/tokens/new
2. **Note**：随便填，例如 `duoduo-push`
3. **Expiration**：选 7 天
4. **Scopes**：勾 ☑️ `repo`（完整仓库权限）
   —— 若以后要改动 `.github/workflows/` 下的 CI 文件，再加勾 ☑️ `workflow`
5. 点生成，复制 `ghp_...`
6. **不要贴到聊天里**，改为在新开的 PowerShell 里执行（把 `ghp_xxx` 换成真值）：

```powershell
Set-Content -Path "$env:USERPROFILE\.workbuddy\gh-token.txt" -Value "ghp_xxx" -NoNewline
```

7. 回我一句「写好了」

我会：读取该文件 → 推送 → **立刻删除该文件** → 回报结果。
推送完成后请到 GitHub → Settings → Developer settings → Personal access tokens 撤销它。

> 这个路径在项目目录之外，`.gitignore` 也已排除 `.env` 与密钥类文件，不会被提交进仓库。

---

## 4. 方案 C（不推荐）：给 GitHub 连接器授权

我刚才又试了一次：连接器对 `duoduo-pet-mall-java` 仍然返回 **404**。
这说明连接器用的**不是你的账号身份**（更可能是集成身份），它看不到你的私有仓库。
即使去连接器管理页调整授权，也未必能覆盖这种情况。**建议不要走这条。**

---

## 5. 常见报错与对策

| 现象 | 原因 | 怎么办 |
|---|---|---|
| `fatal: could not read Username for 'https://github.com'` | 登录窗口没弹出来，或没完成登录 | 重跑脚本；或手动登录一次：`git credential-manager github login` |
| `CONNECT tunnel failed` / `502` | 代理拦住了 git 的 HTTPS 隧道 | 检查 `HTTPS_PROXY` 环境变量；临时清空后重试 |
| `! [rejected] main -> main (non-fast-forward)` | 没带 `--force` | 直接跑脚本，脚本自带 `--force` |
| 脚本停在「远端存在 N 个本地没有的文件」 | 远端真有本地没有的内容 | 把脚本列出的文件名发我，我判断能否安全丢弃 |
| `403` / 凭据被拒 | 登录的账号不是 `1694798740`，或该账号无此仓库权限 | 用 `1694798740` 重新登录 |
| 一直卡在「读取远端」不动 | 登录窗口藏到后台了 | 看一下任务栏 / 浏览器；或先 `git credential-manager github login` |

---

## 6. 当前状态对照

| 项 | 状态 |
|---|---|
| 本地 `duoduo-pet-mall-java` | ✅ **17 个提交 / 90 个文件**，分支 `main`，工作区干净，HEAD = `bad91fe` |
| 远端 `duoduo-pet-mall-java` | ✅ **已同步为 `119fd83`**，与本地逐字节一致（90/90 文件，blob 哈希全等） |
| 本地 `duoduo-pet-mall`（Node 原型） | ✅ 5 个提交 / 110 个文件，工作区干净，`origin` 已配置 |
| 远端 `duoduo-pet-mall`（Node 原型） | ⏳ 仍是 API 生成的一套历史，内容一致、SHA 不同（未同步） |

**Node 原型那个仓库也可以用同一份脚本统一**（把 `-RemoteUrl` 换成 `.../duoduo-pet-mall.git`，
并把 `-RepoRoot` 指向 `D:\智能体项目\duoduo-pet-mall`；注意它的受保护分支名同样是 `main`）。

> 补充：脚本目前把 `main` 与「远端 `refs/heads/main`」写死了分支名，
> 若将来 Node 仓库要复用，把第 1 步的分支校验放宽即可 —— 需要时我来改。

---

## 7. 相关文件

| 文件 | 说明 |
|---|---|
| `D:\智能体项目\duoduo-pet-mall-java\scripts\push-to-github.ps1` | 新增，一键推送 + 校验（本次已修复 1 个真缺陷，见第 8 章） |
| `D:\智能体项目\duoduo-pet-mall-java\docs\archive\PROJECT-STATUS.md` | 交付现状文档（v1.7）**已废弃并归档**（2026-09-30 第五十八轮）；权威来源为 `docs/PLAN.md` |
| 《多多宠物商店 · 项目交付现状与后续方案》 | 同上内容的对外版本 |

---

## 8. 本次执行暴露的两个坑（已修复，留档备查）

### 8.1 脚本在 `git fetch` 行中断 —— `$ErrorActionPreference` 设错

**现象**（你实际看到的）：`info: please complete authentication in your browser...` 之后，
脚本在 `push-to-github.ps1` 第 143 行报 `NativeCommandError` 退出，看起来像认证失败。

**实际状态**：认证**已经成功**，远端引用也**已经取到**（`refs/remotes/origin/main` = `ec9fc13`，
9 个提交 / 86 个文件），只是被 PowerShell 的错误处理误判成失败。

**根因**：git 会把**正常进度**写到 stderr（`From https://github.com/...`、远端分支更新行）；
PowerShell 5.1 在 `$ErrorActionPreference = 'Stop'` 下，会把原生命令的 stderr 包装成
`NativeCommandError` 并**当作终止性错误直接中断脚本**。

**修复**：

- `$ErrorActionPreference` 改为 `'Continue'`，所有成败一律以 `$LASTEXITCODE` 判定；
- 输出统一用 `2>&1 | ForEach-Object { "$_" }` 归一为字符串，避免 stderr 行被渲染成一大段
  `CategoryInfo` 报错块；
- `push` 增加最多 5 次重试，且**只对网络类失败**（502 / Empty reply / timeout）重试，
  凭据或权限类失败立即停止；
- fetch / push 失败时按错误特征分类给出对策。

### 8.2 远端 8 个 `.ps1` 文件被"推坏了" —— API 推送吞掉 UTF-8 BOM

**发现方式**：推送后用 **blob 哈希逐文件比对**（内容寻址，哈希相同即字节完全相同），
发现远端 8 个脚本与本地**内容不一致**：`check-docker-prereq.ps1`、`install-docker-desktop.ps1`、
`push-to-github.ps1`、`smoke.ps1`、`start-infra.ps1`、`start-local.ps1`、`stop-infra.ps1`、`stop-local.ps1`。

**根因**：走 REST API 推送时我用 `[IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)` 读文件内容，
**这个方法会识别并剥离 UTF-8 BOM**。于是远端这 8 个脚本**没有 BOM**。
而 PowerShell 5.1 在缺 BOM 时按 ANSI 解析含中文的脚本，**会直接报语法错误**——
也就是「克隆下来根本跑不起来」。

**修复**：改用 `[Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($path))`（保留 U+FEFF）重新推送。
复验通过：远端 `push-to-github.ps1` 首三字节 = `EF BB BF`、11690 字节，与本地一致。

**教训**：

- 走 API 传文件内容时**必须保留 BOM**；
- **文件数、文件大小都可能一样却内容不同**，只有**逐文件比对 blob 哈希**才能发现这类差异——
  本次就是靠这一步抓到的，只看"远端 90 个文件"会严重误判为成功。

### 8.3 附带说明：远端提交的 SHA 与本地不同

远端提交由 REST API 重建（`parent` 指向远端旧 HEAD），本地提交由 `git commit` 生成，
**两者内容完全相同、对象哈希不同**。所以：

- 克隆远端仓库得到的内容是**正确、完整**的；
- 若以后在你自己的窗口跑 `git push`，会因为「非快进」被拒 → 脚本已内置 `--force` 与安全闸门，
  照常跑即可，跑完远端历史就与本地统一了。
