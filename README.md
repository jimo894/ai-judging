# AgentLens · AI Agent 评测与可观测性平台

依据本目录中的 Demo 开发计划实现的本地演示系统。支持 25 条自建客服问题、HTTP 模拟 Agent A/B、后台评测、人工复核、配对对比、步骤详情和离线报告。

**所有预置业务问题、Agent、故障和价格均为模拟数据，不代表企业实测。** 企业接口通过通用 HTTP 协议接入，尚未完成真实企业联调。

## 本机直接运行

从 GitHub 克隆的仓库包含源码，不包含构建产物；请先按下方“从源码构建”生成 JAR。已打包的提交 ZIP 包含 JAR，可直接启动。

已构建时，双击根目录 `start-demo.cmd`，或执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/start.ps1
```

浏览器打开 http://127.0.0.1:8080 。需要 Java 21 及以上；当前环境使用 Java 25 验证。首次启动自动创建本地 H2 数据库、问题集、两个 Agent 配置和项目账号。关闭窗口或按 Ctrl+C 停止，数据保存在 `data/`。

| 账号 | 角色 | 演示密码 |
|---|---|---|
| admin | 管理员，可管理配置、执行和复核 | Demo2026! |
| developer | 开发者，可运行评测、接收 trace | Demo2026! |
| reviewer | 评审者，可管理数据集、复核评分 | Demo2026! |
| outsider | 另一隔离项目的查看者 | Demo2026! |

默认仅监听本机地址。登录采用 HTTP Basic，凭据仅保存在浏览器内存，刷新页面需重新登录。可通过环境变量 `DEMO_PASSWORD` 修改预置账号密码；若通过网络供其他人访问，应在反向代理配置 HTTPS 后使用。

## 推荐演示流程

1. 使用 admin 登录，项目概览点击“运行 A/B 演示”。
2. 等待两个批次完成，打开批次查看 25 条案例及异常。
3. 进入“版本对比”，选择基线 A 和候选 B，点击“开始对比”。
4. 初始规则评分预期：改善 6 条、退化 1 条、维持 15 条、不可比 3 条；共同判定 22 条。通过率差值按配对样本计算，约 +22.7 个百分点。人工改判后这些数字会变化。
5. 筛选退化，打开 CS-007 的 B 详情，查看回答缺少业务要点与模拟步骤。
6. 可输入业务理由人工复核；所有修订追加保存。
7. 进入“验收报告”，选择内部完整或脱敏演示，生成、预览并下载 HTML。

完整操作脚本见 `docs/演示与验收指南.md`。

## 从源码构建

需要 Node.js 22.12+ 或 24、Java 21+、Maven 3.9.9。当前目录 `.tools/apache-maven-3.9.9` 已下载时，脚本自动使用；否则使用 PATH 中的 `mvn.cmd`。

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/build.ps1
```

脚本构建前端、复制静态页面进后端资源、运行 Java 测试并生成 `backend/target/agentlens.jar`。依赖首次下载需要网络。前端独立开发：在 `frontend/` 执行 `npm ci`、`npm run dev`，通过 5173 端口访问，API 代理到 8080。

按下载偏好，npm 默认使用 npmmirror，Maven 默认使用阿里云公共仓库，配置仅作用于本项目，不修改全局配置。依赖仍使用锁定版本与原完整性校验。Docker Hub 镜像加速应使用你所在环境已验证的加速服务，不预设不可靠的公共代理。镜像故障时，可临时用 `npm ci --registry=https://registry.npmjs.org`，或 Maven 省略 `-s deployment/maven-settings.xml` 回退官方仓库。

## Docker + MySQL

```powershell
Copy-Item deployment/.env.example deployment/.env
# 编辑 deployment/.env，填写数据库及登录密码
docker compose --env-file deployment/.env -f deployment/compose.yml up -d --build
```

浏览器同样访问 http://127.0.0.1:8080 。包含 MySQL 8.4、Spring Boot 和 Nginx。容器内以非 root 用户运行 Java；MySQL 使用具名卷持久化。首次初始化 schema 由 `schema.sql` 完成。

**本机没有 Docker/MySQL，因此该部署路径提供配置但未在当前机器实测。** 不将它列为已通过的验收项。

## 项目结构

```text
backend/       Spring Boot API、权限、规则、Worker、报告、JUnit 测试
frontend/      Vue3 + TypeScript 页面、Playwright 浏览器测试
contracts/     HTTP 接口与数据格式说明
demo-data/     25 条明确标注的模拟问题
deployment/    Dockerfile、Compose、Nginx、环境变量示例
scripts/       构建、启动、备份、样本生成与打包
docs/          操作、设计取舍、验证结果、截图与示例报告
```

## 实现范围与边界

- P0 主流程已实现：项目角色、JSONL 导入与发布、HTTP 接入、批次运行与取消、规则及人工评分、版本对比、观测详情、报告快照。
- 采用 Spring JDBC 的持久化文档快照表，兼容本地 H2 和 MySQL SQL；相比原计划的 MyBatis-Plus 规范化实体表，优先简化了首轮存储层。运行、结果、审核与审计仍持久化，记录此变更见架构说明。
- 仅支持单应用实例和 2 个并发执行槽位。应用锁配合数据库事务协调任务；不支持多 Worker 实例抢占，不应复制部署实例。
- 人工判定按历史追加；导出的 HTML 与所引用评分 ID 固定保存。没有提供原始结果修改或删除 API。
- 企业配置使用固定 IPv4 来源允许列表和环境变量密钥。该适配器需要对方遵循统一 JSON 响应；厂商特定协议转换为下一步接入任务。
- 不支持节点 SDK、定时回归、模型 judge、多模态、多企业 SaaS 和生产级账号生命周期。
- 当前只支持规则要求文本要点/禁止内容/精确值、人工审核、必需工具及参数。未实现语义忠实度自动判断、复杂工具顺序或 JSON Schema 评分器。
- 未执行 10 万 trace 性能测试；不可将这个 Demo 视为已完成企业生产验收。

详细验证状态见 `docs/测试与交付记录.md`。
