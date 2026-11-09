# 本地部署指南（DEPLOYMENT）

本文件说明如何在**本机**准备环境、构建并启动「多多宠物商店」演示环境，以及启动后的验证方式与常见问题排查。

> 本文只写仓库中**实际存在**的命令、端口与环境变量；无法从仓库确认的内容标注为「待补充」，不做推测。
> 更深入的构建/运维踩坑（含本机环境特例）见 [`docs/agent/08-环境运维与踩坑禁忌.md`](./docs/agent/08-环境运维与踩坑禁忌.md) 与 [`docs/OPS.md`](./docs/OPS.md)。

---

## 0. Windows 从零安装与部署（新手逐步版）

> 本章是给**完全没用过 Docker、没配过 Java 环境**的人写的。照着做即可，每一步都写了「做什么 → 敲什么命令 → 应该看到什么 → 出错怎么办」。
> 全程预计：安装软件 1~2 小时，首次构建与启动 20~60 分钟（取决于网络）。
> 命令统一在 **PowerShell** 里执行（开始菜单搜 `PowerShell`，右键「以管理员身份运行」用于安装类操作）。

### 0.0 先回答一个最常见的问题：`docker compose up` 会自动下载什么？

**结论：会下载「镜像」，但不会下载 Docker 本身，也不会帮你编译本项目的代码。**

| 项目 | 会自动下载/完成吗 | 说明 |
|---|---|---|
| Docker 引擎（Docker Desktop） | ❌ **不会** | 必须先手动安装 Docker Desktop（见 0.3） |
| WSL2（Docker Desktop 在 Windows 上的运行后端） | ❌ **不会** | 必须先安装（见 0.2） |
| 第三方中间件镜像（MySQL / Redis / Nacos / Nginx） | ✅ **会** | `docker compose up` 首次执行时自动从镜像仓库拉取 |
| 本项目 8 个 Java 服务的镜像 | ⚠️ **会构建，但依赖你先生成 jar** | 根 `Dockerfile` 只是把 `duoduo-*/target/*.jar` 拷进 JRE 镜像，**不在容器里跑 Maven**。没有 jar 会直接构建失败 |
| 前端页面镜像 | ⚠️ **会构建，但依赖你先生成 dist** | `frontend/Dockerfile` 只是把 `frontend/apps/*/dist` 拷进 nginx 镜像，**不在容器里跑 npm**。没有 dist 会构建失败 |
| JDK / Maven / Node.js | ❌ **不会** | 必须装在你自己的电脑上，用来生成上面说的 jar 和 dist |

所以正确的顺序是：

```
装 WSL2 → 装 Docker Desktop → 装 Git → 装 JDK 21 → 装 Maven → 装 Node.js
        → 下载代码 → 编译 jar → 构建 dist → 配 .env → docker compose up -d → 验证
```

**只敲一句 `docker compose up` 就成功的前提**：这台电脑已经装好 Docker，并且仓库里已经有编译好的 `target/*.jar` 和 `frontend/apps/*/dist`。全新电脑上直接敲，会在「构建业务镜像」这一步失败（报找不到 jar）。

---

### 0.1 第 1 步：检查电脑是否满足条件

#### 1）系统版本

按 `Win + R`，输入 `winver`，回车。要求：

- **Windows 11**（任意版本），或
- **Windows 10 64 位，版本 2004 及以上（Build 19041 及以上）**

低于这个版本，Docker Desktop 装不上，需要先升级系统。

#### 2）虚拟化是否开启（最容易卡住的一步）

打开「任务管理器」→「性能」→「CPU」，看右下角：

- 显示 **「虚拟化：已启用」** → 通过。
- 显示 **「已禁用」** → 需要重启进 BIOS/UEFI，把 `Intel Virtualization Technology (VT-x)` 或 `AMD-V / SVM Mode` 设为 `Enabled`。不同品牌主板位置不同，一般在 `Advanced` / `CPU Configuration` 里。

也可以在 PowerShell 里核对：

```powershell
systeminfo | Select-String "Hyper-V|虚拟化"
```

#### 3）内存与磁盘

| 项 | 建议 | 说明 |
|---|---|---|
| 内存 | **8 GB 及以上**（16 GB 更稳） | 本项目要同时跑 MySQL、Redis、Nacos、8 个业务服务、3 个前端容器；4 GB 内存会非常卡甚至容器反复重启 |
| 磁盘空闲 | **20 GB 及以上** | 镜像 + 数据卷 + Maven 依赖 + node_modules 都占空间，Maven 本地仓库常见 1~2 GB，Docker 镜像常见 5~10 GB |
| CPU | 双核及以上 | 四核更顺 |

查看方法：「设置 → 系统 → 存储」看剩余空间；任务管理器「性能 → 内存」看总量。

#### 4）端口是否被占用

本项目会占用这些端口（来自 `docker-compose.yml`）：

| 端口 | 用途 |
|---|---|
| `8088` | 商城网页（主入口） |
| `8090` | 平台管理后台网页 |
| `8091` | 商家端网页 |
| `8080` | 网关（所有 `/api` 入口） |
| `8081` ~ `8087` | 用户/商品/交易/履约/营销/AI/社交 七个服务 |
| `3306` | MySQL |
| `6379` | Redis |
| `8848`、`9848` | Nacos |
| `9200` | Elasticsearch（可选，默认不启动） |
| `9876`、`10909`、`10911` | RocketMQ（可选，默认不启动） |

一次性检查这些端口有没有被别的软件占用：

```powershell
3306,6379,8848,9848,8080,8081,8082,8083,8084,8085,8086,8087,8088,8090,8091 | ForEach-Object {
  $r = netstat -ano | Select-String ":$_\s"
  if ($r) { "端口 $_ 已被占用："; $r | ForEach-Object { $_.Line.Trim() } } else { "端口 $_ 空闲" }
}
```

如果 `3306` 被占用，通常是本机已经装了 MySQL；`6379` 被占用可能是本机 Redis。**处理办法**：停掉本机那个服务（`services.msc` 里找 MySQL/Redis 服务停止），或者改 `docker-compose.yml` 里的端口映射（例如把 `'3306:3306'` 改成 `'13306:3306'`，左边是电脑上的端口，右边是容器内端口，**只改左边**）。

#### 5）检查 WSL 状态

```powershell
wsl --status
```

- 显示默认版本 2 → 可以跳到 0.3。
- 提示「未安装用于 Linux 的 Windows 子系统」→ 继续 0.2。
- 命令不存在 / 报错 → 也继续 0.2。

---

### 0.2 第 2 步：安装 WSL2（Docker Desktop 的运行后端）

**以管理员身份**打开 PowerShell（开始菜单搜 PowerShell → 右键 → 以管理员身份运行），执行：

```powershell
wsl --install
```

这条命令会自动：启用「虚拟机平台」和「适用于 Linux 的 Windows 子系统」两个 Windows 功能、下载并安装 WSL2 内核、安装一个默认的 Ubuntu 发行版。

> 如果你的网络下载 Ubuntu 很慢或失败，可以先只装内核：
> ```powershell
> wsl --install --no-distribution
> ```
> 本项目**不需要**你手动进入 Ubuntu 做任何事，Docker Desktop 自己会用 WSL2 后端。

**执行完成后必须重启电脑。**

重启后验证：

```powershell
wsl --status
wsl -l -v
```

期望：`默认版本: 2`，发行版列表里 `VERSION` 列为 `2`。

常见报错与处理：

| 报错 | 原因 | 处理 |
|---|---|---|
| `WSL 2 requires an update to its kernel component` | 缺少 WSL2 内核 | 管理员 PowerShell 执行 `wsl --update`，然后 `wsl --shutdown` 再重试 |
| `0x80370102` / 「虚拟机平台未启用」 | BIOS 虚拟化没开，或 Windows 功能没启用 | 先回 0.1 第 2 步确认虚拟化；再执行 `dism.exe /online /enable-feature /featurename:VirtualMachinePlatform /all /norestart` 并重启 |
| `WslRegisterDistribution failed with error 0x8007019e` | Linux 子系统功能未启用 | 管理员 PowerShell：`dism.exe /online /enable-feature /featurename:Microsoft-Windows-Subsystem-Linux /all /norestart`，重启 |
| 想把默认版本设为 2 | — | `wsl --set-default-version 2` |

---

### 0.3 第 3 步：安装 Docker Desktop

#### 1）下载

官方下载页（从浏览器打开）：

- <https://www.docker.com/products/docker-desktop/>
- 或直接看官方安装文档：<https://docs.docker.com/desktop/setup/install/windows-install/>

下载 **Windows 版（AMD64 / x86_64）** 的 `Docker Desktop Installer.exe`。

> 国内网络下载可能较慢或中断，可换时间段重试，或使用公司内网/代理。**不要**从来路不明的第三方站点下载安装包。

#### 2）安装

1. 双击 `Docker Desktop Installer.exe`；
2. 安装向导中**勾选** `Use WSL 2 instead of Hyper-V (recommended)`（使用 WSL2 后端，这是推荐选项）；
3. 其余保持默认，点 `Ok` / `Install`；
4. 安装完成后点 `Close and restart`（会重启电脑）。

> Docker Desktop 的许可条款随公司规模/用途可能不同（个人学习、教学、小型团队通常免费，大型企业可能需付费订阅）。商用前请以 Docker 官网当前许可条款为准，本文不代为判断。

#### 3）首次启动

重启后在开始菜单打开 **Docker Desktop**：

1. 首次会弹出服务协议，勾选后点 `Accept`；
2. 可能提示登录账号 —— **可以跳过（Skip）**，不登录也能用；
3. 等待左下角状态从 `Starting...` 变成 **`Engine running`**（绿色）；
4. 若弹出「WSL update / Docker Desktop needs to be updated」提示，按提示更新即可。

#### 4）验证 Docker 是否可用

**新开**一个 PowerShell 窗口（重要：要新窗口，旧窗口可能还没有新的 PATH）：

```powershell
docker --version
docker compose version
```

期望类似：

```
Docker version 27.x.x, build ...
Docker Compose version v2.x.x
```

再跑一次官方自检镜像：

```powershell
docker run --rm hello-world
```

看到 `Hello from Docker!` 即说明 Docker 完全可用（这一步会自动下载一个很小的镜像，同时验证了网络与引擎）。

#### 5）（强烈建议，国内网络）配置镜像加速

如果 `docker pull` 很慢或报 `TLS handshake timeout`，可以给 Docker 配镜像加速。**注意：公共加速站经常变动/失效，下面只是配置方法示例，具体可用的加速地址请以你所用云厂商（阿里云/腾讯云等）控制台给出的专属地址为准。**

Docker Desktop → 右上角齿轮 `Settings` → `Docker Engine`，把 JSON 改成（在原有内容里加 `registry-mirrors` 一项，注意逗号）：

```json
{
  "registry-mirrors": [
    "https://<你的阿里云专属加速地址>.mirror.aliyuncs.com"
  ]
}
```

点 `Apply & restart`，等引擎重新变绿，然后：

```powershell
docker info
```

输出末尾出现 `Registry Mirrors:` 并列出你配置的地址即生效。

> 若配置后反而拉不动，把 `registry-mirrors` 删掉、恢复原样再 `Apply & restart`，用默认源重试。

#### 6）常见启动问题

| 现象 | 原因 | 处理 |
|---|---|---|
| 一直 `Starting...` / 鲸鱼图标红色 | WSL2 未就绪、虚拟化未开 | 回 0.1、0.2 检查；`wsl --update`；重启 Docker Desktop |
| 提示 `Docker Desktop - WSL update failed` | WSL 版本过旧 | 管理员 PowerShell `wsl --update`，再重启 Docker Desktop |
| 提示 `Virtualization support not detected` | BIOS 未开虚拟化 | 进 BIOS 开启 VT-x / AMD-V |
| 启动后立刻退出 | 内存不足 / 与 Hyper-V、杀毒软件冲突 | 关闭 Hyper-V 相关冲突软件；给机器加内存；`Settings → Resources` 适当调低 CPU/内存配额 |
| `docker: command not found` | PATH 未生效 / 未启动 Docker Desktop | 新开 PowerShell 窗口；确认 Docker Desktop 正在运行（引擎绿色） |

---

### 0.4 第 4 步：安装 Git 并下载代码

#### 1）安装 Git

下载：<https://git-scm.com/download/win> → 安装时一路默认即可（默认会勾选「Git from the command line and also from 3rd-party software」，会把 git 加进 PATH）。

验证（新开 PowerShell）：

```powershell
git --version
```

#### 2）下载代码（**用 HTTPS 地址**）

> ⚠️ 重要：仓库配置的 `origin` 是 SSH 形式（`git@github.com:...`），**新电脑没有配置 SSH 密钥时直接 clone 会失败**（`Permission denied (publickey)`）。请使用下面的 **HTTPS** 地址。

```powershell
# 建议放在纯英文、无空格的路径下，例如 D:\projects
mkdir D:\projects -Force
cd D:\projects

git clone https://github.com/1694798740/duoduo-pet-mall-java.git
cd duoduo-pet-mall-java
```

如果仓库是私有库，Git 会要求登录（浏览器弹窗或输入用户名 + Personal Access Token，而不是账号密码）。

**没有 Git 的备选方案**：在仓库网页点 `Code → Download ZIP`，解压到 `D:\projects\duoduo-pet-mall-java`。

> 路径建议：**不要带空格**，也尽量**不要带中文**。本项目仓库内的 `maven/settings.xml` 里写了一个绝对路径（见 0.6），路径不一致时需要你自己改一行；纯英文路径能省掉一堆编码问题。

确认代码完整（能看到这些文件）：

```powershell
Get-ChildItem | Select-Object Name
# 期望能看到：pom.xml、docker-compose.yml、Dockerfile、duoduo-user、duoduo-product、frontend、docs、scripts …
```

---

### 0.5 第 5 步：安装 JDK 21

项目根 `pom.xml` 要求 **Java 21**（`java.version=21`），装 17 或 8 都会编译失败。

#### 1）下载与安装

推荐 **Eclipse Temurin 21 (LTS)**：

- <https://adoptium.net/temurin/releases/?version=21> → 选 `Windows`、`x64`、`.msi`
- 或 Oracle JDK 21：<https://www.oracle.com/java/technologies/downloads/#java21>

安装 `.msi` 时：

- 勾选 **`Set JAVA_HOME variable`**（Temurin 安装器有这个选项，务必勾选）；
- 勾选 **`Add to PATH`**；
- 其余默认，完成安装。

#### 2）验证

**新开** PowerShell：

```powershell
java -version
```

期望：

```
openjdk version "21.0.x" ...
```

再确认 `JAVA_HOME`：

```powershell
echo $env:JAVA_HOME
```

期望输出类似 `C:\Program Files\Eclipse Adoptium\jdk-21.x.x-hotspot\`。

#### 3）如果 `java` 命令找不到或一执行就报错

手动配置（把路径换成你机器上的实际安装路径）：

```powershell
# 1) 设置用户级 JAVA_HOME（永久生效）
[Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot", "User")

# 2) 把 %JAVA_HOME%\bin 加进 PATH（先读出现有 PATH，再追加，避免覆盖）
$old = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$old;%JAVA_HOME%\bin", "User")
```

**关掉所有 PowerShell 窗口重新打开**再验证。

> 已知坑：有些电脑上 `java` 会优先命中 `C:\Program Files\Common Files\Oracle\Java\javapath\java.exe` 这个启动器，若它损坏，表现为「一执行 java 就崩溃/无输出」。此时用绝对路径验证：
> ```powershell
> & "$env:JAVA_HOME\bin\java.exe" -version
> ```
> 若绝对路径正常，说明 PATH 里有坏的旧启动器，把 `...\Oracle\Java\javapath` 从 PATH 中删掉，或把 `%JAVA_HOME%\bin` 移到 PATH 最前面。

---

### 0.6 第 6 步：安装 Maven

#### 1）下载与解压

- 下载地址：<https://maven.apache.org/download.cgi>
- 选 **Binary zip archive**（例如 `apache-maven-3.9.x-bin.zip`）
- 解压到一个纯英文路径，例如：`D:\devsoft\apache-maven-3.9.4`

#### 2）配置环境变量

```powershell
# 换成你解压后的实际路径
[Environment]::SetEnvironmentVariable("MAVEN_HOME", "D:\devsoft\apache-maven-3.9.4", "User")
$old = [Environment]::GetEnvironmentVariable("Path", "User")
[Environment]::SetEnvironmentVariable("Path", "$old;%MAVEN_HOME%\bin", "User")
```

**新开** PowerShell 验证：

```powershell
mvn -v
```

期望输出里同时出现：

```
Apache Maven 3.9.x
Java version: 21.x.x, vendor: ...
```

如果显示的是 Java 17/8，说明 `JAVA_HOME` 没生效，回 0.5 检查。

#### 3）关于仓库自带的 `maven/settings.xml`（**必须看，否则会踩坑**）

仓库里有一份 [maven/settings.xml](./maven/settings.xml)，它做了两件事：

1. 把 Maven 中央仓库镜像指向阿里云（`https://maven.aliyun.com/repository/public`），国内下载快；
2. **把本地仓库路径写死成了绝对路径**：

```xml
<localRepository>D:/智能体项目/duoduo-pet-mall-java/.m2repo</localRepository>
```

这意味着：如果你的代码**不在** `D:\智能体项目\duoduo-pet-mall-java`，直接 `mvn -gs maven/settings.xml` 会把依赖下载到那个不存在的路径（Maven 会尝试创建它；若 D 盘不存在则报错）。

**两种处理方式，任选其一**：

- **方式 A（推荐，最省事）**：把自己的一份 settings 放到用户目录，不用 `-gs`：
  ```powershell
  New-Item -ItemType Directory -Force "$env:USERPROFILE\.m2" | Out-Null
  Copy-Item "maven\settings.xml" "$env:USERPROFILE\.m2\settings.xml" -Force
  # 用记事本打开 %USERPROFILE%\.m2\settings.xml，把 <localRepository> 那一行改成：
  #   <localRepository>D:/m2repo</localRepository>
  # 或直接删掉这一行（使用默认的 C:\Users\你的用户名\.m2\repository）
  ```
  之后所有 `mvn` 命令**都不用加 `-gs`**。
- **方式 B**：把仓库放在 `D:\智能体项目\duoduo-pet-mall-java`，然后统一用：
  ```powershell
  mvn -gs maven/settings.xml -B -DskipTests package
  ```

> 如果你的网络访问 Maven 中央仓库很慢，用方式 A 并**保留**文件里的阿里云 `<mirror>` 配置即可。

---

### 0.7 第 7 步：安装 Node.js

前端是 npm workspaces（见 `frontend/package.json`）。仓库**没有**锁定 Node 的具体版本下限（已在本文第 9 节标注「待补充」），实践上请装 **Node.js 20 LTS 或 22 LTS**（64 位）。

1. 下载：<https://nodejs.org/en/download> → 选 **LTS**、Windows Installer (`.msi`)
2. 安装时保持默认，**勾选 `Add to PATH`**
3. **新开** PowerShell 验证：

```powershell
node -v
npm -v
```

期望：`v20.x.x` 或 `v22.x.x`，以及 `10.x` / `11.x` 之类的 npm 版本。

> 若 `npm install` 很慢，可临时指定国内镜像（可选）：
> ```powershell
> npm config set registry https://registry.npmmirror.com
> ```
> 想恢复官方源：`npm config set registry https://registry.npmjs.org`

---

### 0.8 第 8 步：编译后端（生成 8 个 jar）

**这一步不能跳过**：项目的业务镜像只是把现成的 jar 打进镜像（根 `Dockerfile` 的注释也明确写了「不在容器内做 Maven 构建」）。

在仓库根目录执行：

```powershell
cd D:\projects\duoduo-pet-mall-java
mvn -B -DskipTests package
```

（若你选了 0.6 的方式 B，则命令为 `mvn -gs maven/settings.xml -B -DskipTests package`。）

**期望结果**：最后出现 `BUILD SUCCESS`。

**首次执行会比较久**（要下载大量依赖，视网络 5~20 分钟）。期间可以喝杯水，不要中断。

验证 jar 是否生成：

```powershell
Get-ChildItem duoduo-*\target\*-1.0.0.jar | Select-Object Name, @{n='MB';e={[math]::Round($_.Length/1MB,1)}}
```

期望列出 8 个 jar（user / product / trade / fulfilment / marketing / ai / social / gateway），每个几十 MB。**缺任何一个，后续对应容器都会构建失败。**

常见报错：

| 报错关键词 | 原因 | 处理 |
|---|---|---|
| `invalid target release: 21` / `release version 21 not supported` | Maven 用到了旧 JDK | 检查 `mvn -v` 里的 Java version 必须是 21；修正 `JAVA_HOME` 后重开窗口 |
| `Could not resolve dependencies` / 大量 timeout | 网络问题 | 用 0.6 方式 A 并保留阿里云 mirror；或换网络重试 |
| `No compiler is provided in this environment` | 装的是 JRE 而不是 JDK | 卸载 JRE，安装 JDK 21 |
| 中文变成乱码 / 编码错误 | 控制台编码 | 先执行 `chcp 65001`，或加 `-Dfile.encoding=UTF-8` |
| `BUILD FAILURE` 且某个模块测试失败 | 极少数情况测试依赖环境 | 本项目此处已用 `-DskipTests` 跳过测试；若仍失败，把完整报错发出来再定位 |

---

### 0.9 第 9 步：构建前端（生成 3 个 dist）

同样**不能跳过**：前端镜像只是把 `frontend/apps/*/dist` 拷进 nginx（`frontend/Dockerfile` 注释已说明）。

```powershell
cd D:\projects\duoduo-pet-mall-java\frontend
npm install
npm run build
```

**期望结果**：三个应用依次构建成功，最后能看到类似 `✓ built in ...`。

验证产物：

```powershell
Get-ChildItem apps\*\dist\index.html | Select-Object FullName
```

期望有 3 个 `index.html`：`apps\mall\dist`、`apps\admin\dist`、`apps\merchant\dist`。

常见问题：

| 现象 | 处理 |
|---|---|
| `npm install` 报 `EACCES` / 权限错误 | 不要用管理员运行 npm；确认目录不是只读；必要时删掉 `frontend\node_modules` 重装 |
| `npm run build` 报 TS 类型错误 | 确认拉的是最新代码（`git pull`）；如仍报错，把完整错误发出来 |
| 构建很慢/卡住 | 检查网络；可临时用 0.7 里的 npmmirror 源 |
| 报 `vite: not found` | `npm install` 没跑成功，回到上一步 |

> 只想快速跑起来、不需要本地开发前端时，也必须执行一次 `npm run build`（容器需要 dist）。之后改前端才需要重新 build。

---

### 0.10 第 10 步：准备 `.env` 配置文件

所有可变配置都有默认值（`docker-compose.yml` 里写成 `${变量:-默认值}`），**不建 `.env` 也能启动**。但建议建一份，方便以后改：

```powershell
cd D:\projects\duoduo-pet-mall-java
Copy-Item .env.example .env
```

然后用记事本打开 `.env`，按需修改（**不改也能跑**）：

| 变量 | 要不要改 | 说明 |
|---|---|---|
| `DASHSCOPE_API_KEY` | 想体验 AI 导购就填 | 阿里云百炼 Key（<https://bailian.console.aliyun.com/>）；留空则 AI 接口返回 503（页面其他功能正常） |
| `COOKIE_SECURE` | **保持 `false`** | 本地是 http，改成 `true` 会导致登录后立刻掉线 |
| `MYSQL_ROOT_PASSWORD` | 可不改 | 默认 `duoduo_root`（在 `docker-compose.yml` 里） |
| `INTERNAL_TOKEN` | 可不改 | 服务间内部密钥，全站必须一致 |
| `DUODUO_SEARCH_ENABLED` | 保持 `false` | 想用 ES 检索再改 `true` 并启动 `search` profile |

> ⚠️ `.env` 已在 `.gitignore` 中。**不要把 `.env` 发给别人、不要提交到仓库**（里面可能有你的 API Key）。

---

### 0.11 第 11 步：启动全部容器（首次）

确认 Docker Desktop 正在运行（引擎绿色），在**仓库根目录**执行：

```powershell
cd D:\projects\duoduo-pet-mall-java
docker compose up -d
```

这条命令会依次：

1. 拉取基础镜像（MySQL、Redis、Nacos、Nginx、JRE）；
2. 用你第 8 步生成的 jar 构建 8 个业务镜像；
3. 用你第 9 步生成的 dist 构建 3 个前端镜像；
4. 创建并后台启动容器。

**首次执行耗时**：拉镜像 + 构建，视网络 10~40 分钟。过程中屏幕会滚动大量日志，**只要最后没有 `ERROR` 就是正常的**。

> 想看到更完整的输出（排错时用）：去掉 `-d`，即 `docker compose up`，日志会持续打印；按 `Ctrl + C` 停止（停止的是前台日志，容器仍会继续运行，除非加 `--abort-on-container-exit`）。

---

### 0.12 第 12 步：确认启动成功（三层验证）

#### 第 1 层：容器是否健康

```powershell
docker compose ps
```

期望：本次启动的每个容器状态都是 `Up ... (healthy)`。

说明：

- 刚启动时出现 `(health: starting)` 是正常的，**再等 1~3 分钟**（MySQL 首次初始化 SQL 较慢，健康检查给了 120 秒宽限期）。
- 默认启动的容器约为 **14 个**（MySQL、Redis、Nacos + 8 个业务服务 + 网关 + 3 个前端）。
- `duoduo-es`、`rocketmq-*` 属于可选 profile，默认**不启动**，看不到它们是正常的。

如果某个容器一直是 `unhealthy` 或反复 `Restarting`，看它的日志：

```powershell
docker compose logs --tail=100 duoduo-user-app
```

（把服务名换成出问题的那个；服务名可用 `docker compose ps --services` 查看。）

#### 第 2 层：网页是否能打开

浏览器访问：

- 商城首页：<http://localhost:8088/>
- 平台管理后台：<http://localhost:8090/>
- 商家端：<http://localhost:8091/>

能正常看到页面即成功。**如果页面是 403 / 404 / 空白页**，几乎一定是第 9 步的前端 dist 没有生成（镜像里没有静态文件），回到 0.9 重做，然后：

```powershell
docker compose build duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app
docker compose up -d duoduo-web-app duoduo-admin-web-app duoduo-merchant-web-app
```

#### 第 3 层：API 是否正常

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/duoduo/capabilities' | ConvertTo-Json -Depth 4
```

期望返回里 `code` 为 `SUCCESS`。若 AI 未配 Key，`mode` 不会是 `llm`（属预期降级，不影响其他功能）。

再快速试一个业务接口：

```powershell
Invoke-RestMethod -Uri 'http://localhost:8080/api/products?page=1&pageSize=3' | ConvertTo-Json -Depth 3
```

期望能返回商品列表（说明 MySQL 初始化种子数据成功、网关与商品服务都正常）。

---

### 0.13 第 13 步：跑自动化冒烟（可选但推荐）

仓库里有一套接口冒烟脚本（PowerShell），用来一次性验证主要流程。

**先解决「脚本无法运行」的问题**（Windows 默认禁止运行未签名脚本，会报 `无法加载文件 ... 因为在此系统上禁止运行脚本`）：

```powershell
Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force
```

然后在仓库根目录：

```powershell
# 一键全量冒烟（会跑多套脚本，耗时较长）
.\scripts\run-all-smoke.ps1

# 只跑闲置担保交易专项（B-11，约 1~2 分钟）
.\scripts\listing-settlement-smoke.ps1

# 浏览器 UI 自测（需要本机浏览器环境，按脚本提示）
.\scripts\e2e\run-ui-checks.ps1
```

脚本输出会在末尾给出「通过 N 项，失败 M 项」。**M 不为 0 时**，先看失败项提示；常见原因是端口/容器还没完全 ready，等 1 分钟重跑一次。

> 脚本数量与断言会随版本增减，**以脚本实际输出为准**，不要拿旧文档里的数字对比。

---

### 0.14 日常使用：启动、停止、看日志、改代码后怎么更新

#### 常用命令（都在仓库根目录执行）

```powershell
# 启动（已构建过，秒级）
docker compose up -d

# 停止并删除容器（数据卷保留，数据还在）
docker compose down

# 停止并删除容器 + 数据卷（⚠️ 数据库会被清空！）
docker compose down -v

# 查看状态
docker compose ps

# 查看某服务最近 200 行日志
docker compose logs --tail=200 duoduo-trade-app

# 持续跟踪日志（Ctrl+C 退出跟踪，容器不受影响）
docker compose logs -f duoduo-gateway-app

# 重启单个服务（不重新构建）
docker compose restart duoduo-product-app

# 进入 MySQL 命令行
docker exec -it duoduo-mysql mysql -uroot -pduoduo_root
```

#### 改了 Java 代码后

```powershell
# 1) 重新编译（只编译受影响的模块也行，例如 -pl duoduo-trade -am）
mvn -B -DskipTests package

# 2) 重建对应镜像并重启（以交易域为例）
docker compose build duoduo-trade-app
docker compose up -d duoduo-trade-app

# 3) 看它是否 healthy
docker compose ps duoduo-trade-app
```

#### 改了前端代码后

```powershell
cd frontend
npm run build
cd ..
docker compose build duoduo-web-app
docker compose up -d duoduo-web-app
```

#### 数据备份（MySQL）

```powershell
# 导出全部业务库到当前目录的 backup.sql
docker exec duoduo-mysql mysqldump -uroot -pduoduo_root --databases duoduo_user duoduo_product duoduo_trade duoduo_fulfilment duoduo_marketing duoduo_social > backup.sql
```

> 库名以 `docs/sql/00-init-databases-and-user.sql` 为准；若你的版本库名有增减，请对照该文件调整。

---

### 0.15 完整命令清单（复制粘贴版，按顺序执行）

> 只适用于**已经完成 0.1~0.7 软件安装**之后。把 `D:\projects\duoduo-pet-mall-java` 换成你的实际路径。

```powershell
# ===== 1. 进入代码目录 =====
cd D:\projects\duoduo-pet-mall-java

# ===== 2. 编译后端（生成 8 个 jar）=====
mvn -B -DskipTests package

# ===== 3. 构建前端（生成 3 个 dist）=====
cd frontend
npm install
npm run build
cd ..

# ===== 4. 准备配置 =====
if (-not (Test-Path .env)) { Copy-Item .env.example .env }

# ===== 5. 启动 =====
docker compose up -d

# ===== 6. 等待并查看状态（重复执行直到全部 healthy）=====
docker compose ps

# ===== 7. 验证 =====
Invoke-RestMethod -Uri 'http://localhost:8080/api/duoduo/capabilities'
Start-Process 'http://localhost:8088/'
```

---

### 0.16 常见问题（按报错现象查）

| # | 现象 | 根本原因 | 解决 |
|---|---|---|---|
| 1 | `docker: command not found` | Docker 没装 / 没启动 / PATH 未生效 | 启动 Docker Desktop 到引擎绿色；新开 PowerShell 窗口 |
| 2 | `error during connect: ... The system cannot find the file specified` | Docker Desktop 引擎没起来 | 等它启动完成；`Settings → Troubleshoot → Restart` |
| 3 | `failed to solve: ... no such file or directory: duoduo-xxx/target/duoduo-xxx-1.0.0.jar` | **没编译后端**（最常见） | 回 0.8 执行 `mvn -B -DskipTests package`，确认 8 个 jar 都在 |
| 4 | 前端容器构建报 `COPY failed: ... apps/mall/dist: not found` | **没构建前端** | 回 0.9 执行 `npm install && npm run build` |
| 5 | 网页打开是 403 / 404 / 白屏 | dist 缺失或镜像未重建 | 0.9 重新 build，再 `docker compose build duoduo-web-app && docker compose up -d duoduo-web-app` |
| 6 | `Bind for 0.0.0.0:3306 failed: port is already allocated` | 端口被占用（本机已有 MySQL） | 停掉本机 MySQL 服务，或改 `docker-compose.yml` 左侧端口（如 `13306:3306`）后 `docker compose up -d` |
| 7 | 容器启动后立刻退出 / `Restarting` | 依赖没就绪、内存不足、配置错误 | `docker compose logs <服务名>` 看具体报错；优先确认 MySQL/Nacos 已 healthy；内存建议 ≥8GB |
| 8 | `duoduo-user-app` 等一直 `unhealthy` | 启动慢 / 连不上 MySQL 或 Nacos | 等 2~3 分钟；看日志是否 `Connection refused`；确认 MySQL 容器 healthy |
| 9 | 登录后刷新页面又变未登录 | `COOKIE_SECURE=true` 但用的是 http | `.env` 改回 `COOKIE_SECURE=false`，`docker compose up -d` 重建相关容器 |
| 10 | AI 导购 503 / 提示未配置 | 没填 `DASHSCOPE_API_KEY` | 在 `.env` 填 Key（留空就是故意降级）；改完 `docker compose up -d duoduo-ai-app` |
| 11 | 内部接口 403 | `INTERNAL_TOKEN` 不一致 | `.env` 里统一；或用默认值（把自定义那行删掉）重建 |
| 12 | 无法运行 .ps1 脚本（「禁止运行脚本」） | PowerShell 执行策略 | `Set-ExecutionPolicy -Scope CurrentUser RemoteSigned -Force` |
| 13 | 控制台中文乱码 | 编码不是 UTF-8 | 先执行 `chcp 65001`；脚本文件请保持 UTF-8 编码 |
| 14 | `docker compose` 报 `unknown command` | 装的是很老的 docker-compose v1 | 升级 Docker Desktop（自带 compose v2）；用 `docker compose`（中间是空格）而不是 `docker-compose` |
| 15 | 拉镜像 `TLS handshake timeout` / 卡住 | 网络问题 | 配镜像加速（0.3 第 5 步），或换网络重试 |
| 16 | 磁盘越来越满 | 镜像/构建缓存堆积 | `docker system df` 查看；`docker system prune -f` 清理未使用资源（**不会**删数据卷）；构建缓存可 `docker builder prune` |
| 17 | 想全部重来 | — | `docker compose down -v`（删数据）→ 重新 `docker compose up -d`；数据卷删除后初始化 SQL 会重跑 |
| 18 | 端口 8088 能开但接口全 404 | 网关没起来 / nginx 反代配置不匹配 | `docker compose ps` 看 `duoduo-gateway-app` 是否 healthy；`docker compose logs duoduo-web-app` 看 nginx 报错 |

**排查万能三步**：

```powershell
docker compose ps                                  # 1) 谁没起来
docker compose logs --tail=200 <出问题的服务名>      # 2) 它为什么没起来
docker compose up -d --force-recreate <服务名>       # 3) 改完配置后重建
```

---

### 0.17 重置与卸载

#### 只清理本项目

```powershell
cd D:\projects\duoduo-pet-mall-java
docker compose down -v          # 删容器 + 数据卷（数据库清空）
docker image prune -f           # 删悬空镜像（可选）
```

#### 彻底卸载 Docker Desktop

1. 先 `docker compose down -v` 停止本项目；
2. 「设置 → 应用 → 已安装的应用」找到 **Docker Desktop** → 卸载；
3. 如需彻底清理：删除 `%APPDATA%\Docker`、`%LOCALAPPDATA%\Docker`、`%USERPROFILE%\.docker`（**注意**：这里有你的镜像与凭据，删除前确认不需要）；
4. 如需移除 WSL 发行版：`wsl --unregister <发行版名>`（用 `wsl -l -v` 查看名称，**会删除该子系统内所有数据**）。

#### 只卸载本项目需要的开发工具

JDK / Maven / Node.js / Git 都可以在「已安装的应用」里单独卸载，不影响 Docker。

---

## 1. 环境要求

| 依赖 | 要求 | 说明 |
|---|---|---|
| JDK | **21** | 根 `pom.xml` 中 `java.version=21`、`maven.compiler.release=21` |
| Maven | 3.9.x | 仓库文档以 Maven 3.9.4 为基线（见 `docs/agent/08`）；如本机 Maven 启动异常，按该文档给出的 classworlds 方式显式调用 |
| Docker | Docker Desktop（含 Docker Compose v2） | 本地以 `docker compose` 编排中间件与业务服务 |
| Node.js + npm | 版本要求未在仓库中固定声明，**待补充** | 前端为 npm workspaces（见 `frontend/package.json`） |
| 操作系统 | Windows / macOS / Linux 均可 | 冒烟脚本为 PowerShell（`.ps1`），Windows 可直接运行 |

---

## 2. 快速启动（容器化）

在仓库根目录（与 `docker-compose.yml` 同级）执行：

> **前置条件（重要）**：本项目镜像**不在容器内编译代码**，构建前必须已经存在：
> - 后端 jar：`duoduo-*/target/*.jar`（由 `mvn -B -DskipTests package` 生成，见 6.1）
> - 前端 dist：`frontend/apps/*/dist`（由 `cd frontend && npm install && npm run build` 生成，见 6.2）
>
> 全新电脑第一次部署，请直接按 **第 0 章** 逐步执行；否则 `docker compose up -d` 会在构建镜像时报 `no such file or directory: .../target/xxx.jar`。

```bash
# 1) 启动全部容器（首次会自动构建业务镜像）
docker compose up -d

# 2) 查看容器状态（容器数量以实际输出为准；ES 属 search profile，默认不启动）
docker compose ps
```

等所有容器状态变为 `healthy` 即启动完成。若需要 Elasticsearch 检索能力：

```bash
docker compose --profile search up -d
```

> 检索开关 `DUODUO_SEARCH_ENABLED=false` 时，检索链路原地降级回 MySQL，功能不受影响（只少了拼音/同义词召回）。

---

## 3. 端口一览（本地默认映射）

| 端口 | 服务 | 说明 |
|---|---|---|
| `8088` | 商城前端（Web） | 浏览器访问入口：<http://localhost:8088/> |
| `8080` | 网关（gateway） | 所有 `/api/**` 统一入口，如 `GET /api/duoduo/capabilities` |
| `8081` | 用户域（duoduo-user） | 账号 / 令牌 / 宠物档案 / 内部批量昵称 |
| `8082` | 商品域（duoduo-product） | 类目品牌 / SPU-SKU / 库存 / 闲置 / 商家商品 |
| `8083` | 交易域（duoduo-trade） | 购物车 / 订单 / 冻结 / 支付 / 托管 |
| `8084` | 履约域（duoduo-fulfilment） | 评价晒单 / 物流轨迹 / 售后 / 信用分 |
| `8085` | 营销域（duoduo-marketing） | 优惠券领券 / 券包 / 锁定核销 |
| `8086` | AI 导购（duoduo-ai） | 「多多」对话（依赖 DashScope Key） |
| `8087` | 社交域（duoduo-social） | 私信 / 分享圈 / 关注 / 客服会话 |
| `8090` | 平台管理后台（admin-web） | 浏览器访问：<http://localhost:8090/> |
| `8091` | 商家端（merchant-web） | 浏览器访问：<http://localhost:8091/> |
| `3306` | MySQL 8.0 | 容器 `duoduo-mysql` |
| `6379` | Redis 7 | 容器 `duoduo-redis` |
| `8848` / `9848` | Nacos | 注册与配置中心，容器 `duoduo-nacos` |
| `9200` | Elasticsearch 8.11 | 容器 `duoduo-es`，属 `search` profile，**默认不启动** |
| `9876` / `10909` / `10911` | RocketMQ | 容器 `rocketmq-namesrv` / `rocketmq-broker`，属 `mq` profile，**默认不启动** |

---

## 4. 数据库初始化

- **首次启动自动建库建表**：`docker-compose.yml` 把 `./docs/sql` 挂载到 MySQL 的 `/docker-entrypoint-initdb.d`，仅在**数据卷为空**时执行一次（建库、建表、商品种子数据）。
- **增量脚本需手动执行**：`docs/sql/incremental/*.sql` 按编号在执行前备份、执行后核对（项目惯例：增量 SQL 只执行一次）。

示例（已核实可用的 PowerShell 写法，以 `75-b11-listing-order.sql` 为例）：

```powershell
# 在仓库根目录执行；root 密码从容器环境变量读取
$pw = (docker exec duoduo-mysql env | Select-String 'MYSQL_ROOT_PASSWORD=').ToString().Split('=')[1]
Get-Content "docs/sql/incremental/75-b11-listing-order.sql" -Raw |
  docker exec -i duoduo-mysql mysql -uroot "-p$pw" --default-character-set=utf8mb4 duoduo_trade

# 核对结果（示例：确认新增列存在）
docker exec duoduo-mysql mysql -uroot "-p$pw" -e "SHOW COLUMNS FROM duoduo_trade.t_order_item LIKE 'listing_id';"
```

> 应用连库账号为 `duoduo` / `duoduo`（见 `docker-compose.yml` 的 `DB_USERNAME` / `DB_PASSWORD`）。
> **数据卷非空时初始化脚本不会重跑**；如需从零重建，`docker compose down -v` 会**删除全部数据**，请先确认无需保留。

---

## 5. 配置说明（`.env`）

仓库提供 [`.env.example`](./.env.example) 模板：

```bash
cp .env.example .env
```

说明：`docker-compose.yml` 中可变项均写成 `${VAR:-默认值}`，因此**不建 `.env` 也能起**（走默认值）；建了 `.env` 即按它覆盖。`.env` 已在 `.gitignore` 中，**真实密钥只放 `.env` 或 shell 环境变量，绝不提交到仓库**。

常用配置项（均来自 `.env.example`）：

| 变量 | 默认值 | 作用 |
|---|---|---|
| `INTERNAL_TOKEN` | `duoduo-local-dev-internal-token` | 服务间内部接口共享密钥（全站同一个）；不一致会导致内部接口 403 |
| `COOKIE_SECURE` | `false` | **本地 http 必须 false**，否则浏览器不保存带 Secure 的 Cookie，表现为「登录后下一页又未登录」 |
| `COOKIE_SAME_SITE` | `Lax` | SameSite 策略；`None` 必须同时 `COOKIE_SECURE=true` |
| `DASHSCOPE_API_KEY` | 空 | 阿里云百炼 API Key；留空 = AI 以 unconfigured 形态降级（接口 503，不进模型） |
| `DUODUO_AI_MODEL` | 见 `.env.example` | 模型优先级列表，逗号分隔；额度用尽/限流/不存在时按顺序切换 |
| `DUODUO_AI_RATE_LIMIT` | `20` | 每 IP 每分钟对话次数上限（超限 429） |
| `DUODUO_SEARCH_ENABLED` | `false` | ES 检索开关；false 时检索降级 MySQL |
| `MYSQL_ROOT_PASSWORD` | `duoduo_root` | MySQL root 密码（compose 内 `${MYSQL_ROOT_PASSWORD:-duoduo_root}`） |
| `STORAGE_TYPE` | `local` | 对象存储类型；`local` 使用本地盘 + 卷挂载 |
| `ALIPAY_PAY_ENABLED` | `false` | 支付宝「电脑网站支付」渠道开关；留空/关闭则结算页不出现该渠道 |

> 网关运行期开关（`DUODUO_ENTRY_SCOPE_ENABLED` / `DUODUO_PERM_VERSION_ENABLED` / `DUODUO_API_METRICS_ENABLED` / `DUODUO_ADMIN_IP_WHITELIST_ENABLED` 等）的完整清单与「关掉会怎样」见 `.env.example` 注释、`docs/OPS.md` 与 `scripts/ops-switch.ps1 -Check`。

---

## 6. 构建

### 6.1 后端（Java）

在仓库根目录（聚合父 POM 所在目录）：

```bash
mvn -ntp -DskipTests package
```

- 产物：各模块 `target/*.jar`；容器镜像由 `docker compose build` 基于根目录 `Dockerfile` 构建（`temurin:21-jre-alpine`，非 root）。
- 若本机 Maven 无法正常启动，按 `docs/agent/08` 给出的显式 classworlds 命令执行（该命令需要把 `<repo>` 替换为本仓库实际路径）。

### 6.2 前端（React + Vite）

```bash
cd frontend
npm install
npm run typecheck        # 三端 tsc --noEmit
npm run build            # 三端构建
```

单端命令（已核实存在）：

```bash
npm run typecheck -w @duoduo/mall
npm run build -w @duoduo/mall
```

构建产物默认在 `frontend/apps/<app>/dist`。

---

## 7. 启动验证

按顺序执行以下检查：

1. **容器健康**：`docker compose ps` 全部 `healthy`。
2. **前端可达**：浏览器打开 <http://localhost:8088/>，页面正常渲染。
3. **网关与 AI 能力声明**：

   ```powershell
   Invoke-RestMethod -Uri 'http://localhost:8080/api/duoduo/capabilities'
   ```

   期望返回 `code=SUCCESS`；AI 未配置 Key 时 `mode` 不会为 `llm`（属预期降级）。
4. **冒烟脚本**（PowerShell，在仓库根目录）：

   ```powershell
   # 一键全量冒烟
   scripts\run-all-smoke.ps1

   # 浏览器 UI 自测（真实浏览器）
   scripts\e2e\run-ui-checks.ps1

   # 闲置担保交易专项（B-11）
   scripts\listing-settlement-smoke.ps1
   ```

   > CI 侧使用同一入口的子集：`scripts\ci-run.ps1 -Scripts …`。
   > 冒烟脚本的数量与断言会随迭代增减，**以脚本实际输出为准**，不要引用固定数字。

---

## 8. 常见问题排查

| 现象 | 可能原因与处理 |
|---|---|
| 「登录成功后下一页又未登录」 | 本地用 http 访问时 `COOKIE_SECURE` 必须为 `false`；并确认访问的是 `http://localhost:8088`（不要混用 IP / 端口） |
| AI 导购返回 503 / `unconfigured` | 未配置 `DASHSCOPE_API_KEY`（留空即降级）；配置后重建 `duoduo-ai-app` |
| AI 推荐偶发全量失败后再恢复 | 平台免费额度**按模型独立计算**；403 `AllocationQuota` 属单模型额度耗尽，会按 `DUODUO_AI_MODEL` 顺序切换下一个模型；长期需充值或调整模型顺序 |
| 内部接口返回 403 | 各服务 `INTERNAL_TOKEN` 不一致（默认 `duoduo-local-dev-internal-token`），确认 `.env` 统一后重建相关容器 |
| 中文关键词检索 0 命中 | 初始化 SQL 已内置 `SET NAMES utf8mb4;`；若为历史库，需核对库表字符集（utf8mb4） |
| 改了增量 SQL 但接口报缺列/缺索引 | `docs/sql/incremental/*.sql` 不会自动执行，需按第 4 节手动执行并核对 |
| 容器起不来、日志报连不上 MySQL/Nacos | 确认 `duoduo-mysql` / `duoduo-nacos` 已 `healthy`（Nacos 健康检查 `http://127.0.0.1:8848/nacos/v1/console/health/readiness`）；必要时 `docker compose up -d` 让依赖按 `service_healthy` 重试 |
| 端口被占用 | 对照第 3 节端口表，关闭占用进程或调整 `docker-compose.yml` 映射（改后需 `docker compose up -d`） |
| 想彻底重置本地数据 | `docker compose down -v`（**会删除数据卷**，请先确认无需保留），随后 `docker compose up -d` 重新执行初始化 SQL |
| AI 检索"召回变少" | `DUODUO_SEARCH_ENABLED=false` 或未起 `duoduo-es` 时，检索降级 MySQL 属预期行为；需要时 `docker compose --profile search up -d` |

---

## 9. 待补充

以下内容无法仅从仓库确认，后续补充：

- Node.js / npm 的**具体版本下限**（`frontend/package.json` 未声明 `engines`）。
- 小程序端（miniprogram）的本地构建与运行步骤。
- 生产/云端部署的完整流程（证书、域名、备案、支付资质等）。
- 管理后台（admin-web）与商家端（merchant-web）的本地访问地址与账号初始化说明。
