# 证据索引（independent-qa-2026-10-30/evidence）

| 文件 | 用途 |
| --- | --- |
| `maven-test.log` | 全量 Maven 测试完整日志（1158 tests / 0 fail / 5 skip，BUILD SUCCESS） |
| `frontend-typecheck.log` | 三端 `tsc --noEmit`，退出码 0 |
| `frontend-build.log` | 三端 `vite build`，退出码 0 |
| `smoke-summary.txt` | 第一批 11 套核心冒烟逐套退出码与耗时 |
| `smoke-*.log` | 第一批每套冒烟完整输出（含 PASS/FAIL 明细） |
| `smoke-summary-2.txt` | 第二批 23 套冒烟逐套退出码与耗时 |
| `smoke2-*.log` | 第二批每套冒烟完整输出 |
| `smoke2b-export-p3-smoke.ps1.log` | 重建本地 4 个旧容器后，导出 P3 复跑 8/0 |
| `smoke2b-audit-log-smoke.ps1.log` | 审计冒烟 57/1（DEF-011 真实缺口 + DEF-012 dry-run 假红） |
| `smoke2b-pay-channel-smoke.ps1.log` | 支付渠道补跑 62/0 |
| `ui-summary.txt` / `ui-summary-2.txt` | 浏览器 UI 检查逐套退出码与耗时 |
| `ui-*.log` / `ui2-*.log` | 每套 UI 检查完整输出 |
| `api-tests.py` | 独立 API 反向/边界测试脚本（账号由环境变量注入，不落盘密码） |
| `api-tests.log` / `api-results.json` | 17 个 API 用例原始结果（14 PASS / 3 待复核） |
| `api-followup.md` | 3 个原始 FAIL 的复核结论（全部非产品缺陷；跨人 404 已补实验） |
| `ai-latency.txt` | AI 真实模型耗时实测（97s/51.8s 等）与代码侧预算观察 |
| `product-image-broken.txt` | 商品详情主图裂图的 HTTP/浏览器/代码根因证据 |
| `fix-def001-before.log` / `fix-def001-after.log` | DEF-001 修复前 18/2 红、修复后 20/0 |
| `fix-def002-before.log` / `fix-def002-after.log` | DEF-002 预算测试：修复前 1 fail、修复后 0 fail |
| `fix-def003-final2.log` | AI 冒烟会话隔离与多轮修复后的实际输出（仍暴露 DEF-013/014） |
| `fix-def011-after2.log` / `fix-def011-final.log` | 审计冒烟 58/0（含导出创建/下载审计） |
| `fix-regression-maven.log` | 修复后全量 Maven：1159 tests / 0 fail / 5 skip |
| `browser-capture.mjs` / `browser-capture.log` | 浏览器取证脚本与输出（破图清单、记忆页字段） |
| `shot-home.png` | 商城首页截图（本次未复现首页裂图） |
| `shot-product-detail.png` | 商品详情截图（主图裂图） |
| `shot-ai-page.png` | 买家 AI 导购页截图 |
| `shot-ai-memory.png` | AI 记忆库页截图（称呼/物种/预算/过敏原/健康备注可见） |
| `shot-admin-menu.png` | 运营账号商城顶栏/菜单截图（顶栏含“管理后台”） |
| `ui-merchant-ui-check.mjs.log` | 说明：本轮误用了不存在的 `merchant-ui-check.mjs`；未计为失败，商家检查改用 `seller-ui-check.mjs`（PASS） |

> 说明：浏览器代理曾声称保存 `browser-ai-persist.png`、`browser-ai-memory.png`、`browser-admin-entry.png`，但证据目录中未找到这些文件；本报告只使用实际存在的 `shot-*.png`。AI 会话保持与 AI 记忆页的结论以浏览器代理的实际操作记录 + 本目录截图/日志为准，不伪造截图。
