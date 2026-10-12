# API 与数据契约 v0.1

所有业务 API 以 `/api/v1` 开头，同源调用，使用 HTTP Basic；角色按目标项目判断。错误为 `{code,message,details?,request_id?}`。401 未登录，403 无权限，404 不存在，409 状态/幂等冲突，422 不可比较或连接失败。

## 平台 API

| 方法 / 路径 | 输入 | 返回与权限 |
|---|---|---|
| GET /health | 无 | 健康状态，公开 |
| GET /me | 无 | 当前账号、可访问项目及项目成员映射 |
| GET /projects | 无 | 当前成员所在项目 |
| POST /projects | name | 新项目，创建者为管理员 |
| POST /projects/{id}/members | username, role | 管理员设置预置账号的项目角色 |
| GET /datasets?project_id=… | 项目 | 数据集与案例 |
| POST /datasets/import | project_id,name,jsonl,source? | 草稿；管理员/开发者/评审者；错误含行号 |
| POST /datasets/{id}/publish | 无 | 发布并固定 content_hash |
| GET /agents?project_id=… | 项目 | Agent 配置快照，不含真实密钥 |
| POST /agents | 见下方配置 | 管理员创建不可覆盖的新快照 |
| POST /agents/{id}/test | 无 | 实际请求连接测试，返回耗时和可用能力 |
| GET /runs?project_id=… | 项目 | 批次列表和后端计算指标 |
| POST /runs | project_id,dataset_id,agent_id,name?,max_requests,max_retries? | 202；管理员/开发者；必须有 Idempotency-Key 请求头 |
| GET /runs/{id} | 无 | manifest、状态、指标和案例结果 |
| POST /runs/{id}/cancel | 无 | 请求取消；已完成结果保留 |
| GET /results/{id} | 无 | 结果、attempts、评分与修订历史、trace |
| POST /results/{id}/reviews | verdict:PASS/FAIL/UNKNOWN, reason | 管理员/评审者；仅成功取得回答的案例 |
| GET /comparisons?baseline_run_id=…&candidate_run_id=… | 两个运行 ID | 可比性检查、配对差值与各个案例 |
| POST /traces/batch | result_id,steps | 管理员/开发者；逐条接收、去重或冲突拒绝 |
| POST /reports | baseline_run_id,candidate_run_id,mode:redacted/internal | 固定快照，返回报告元信息 |
| GET /reports?project_id=… | 项目 | 历史报告清单 |
| GET /reports/{id}/html | 无 | 可离线使用的单文件 HTML，项目鉴权 |
| GET /audit?project_id=… | 项目 | 仅管理员可读的操作审计 |

首版列表无分页，针对 20–100 条演示规模；大规模应用需要分页和标准索引。已发布内容没有覆盖更新 API。

## 数据集 JSONL

每行一个 JSON 对象；最多 1000 条，接口限制 500000 字符，前端限制上传 500 KB。

```json
{"case_id":"ORDER-001","input":"查询订单 A001","reference":"订单已支付","checks":{"contains":["已支付"],"tool":"query_order","tool_args":{"order_id":"A001"}},"evidence":["经授权业务手册 §1"],"tags":["工具查询"]}
```

case_id、input 必填；checks 可使用 contains/not_contains 字符串数组、equals 精确文本、manual 布尔值、tool 必需工具名、tool_args 参数对象。缺规则或 manual=true 默认 UNKNOWN。工具规则要求完整 trace。

## Agent 配置

```json
{"project_id":"project-demo","name":"企业客服 v1","adapter":"HTTP","endpoint":"http://10.0.0.8:9000/agent","secret_ref":"AGENT_SECRET_CUSTOMER","timeout_seconds":30,"idempotent":false,"capability":"black_box","input_price":null,"output_price":null,"price_version":"unknown"}
```

adapter 为 HTTP 或 MOCK，MOCK 的 variant 为 A/B。price 为 CNY/百万 Token，未知为 null。HTTP 来源必须加入服务器精确 origin 允许列表；只支持固定 IPv4，服务不跟随重定向。secret_ref 引用服务器环境变量，不传输真实密钥配置到前端。

## 平台发给企业 Agent 的请求

```json
{"request_id":"result-唯一ID","case_id":"ORDER-001","input":"查询订单 A001","session_id":"result-唯一ID","context":{}}
```

POST JSON，`Idempotency-Key` 等于 request_id，同逻辑案例重试时保持不变；可选 Authorization: Bearer 从 secret_ref 取得。不同 case 独立会话。首版不实现流式响应。

## 企业 Agent 返回

```json
{
  "output":"订单已支付",
  "usage":{"input_tokens":120,"output_tokens":30},
  "trace_complete":true,
  "trace":[
    {"span_id":"root","parent_span_id":null,"type":"agent","name":"客服Agent","duration_ms":800,"status":"OK"},
    {"span_id":"tool-1","parent_span_id":"root","type":"tool","name":"query_order","duration_ms":50,"status":"OK","args":{"order_id":"A001"}}
  ]
}
```

output 文本必填；usage、trace 可缺省。usage 缺失则费用未知；trace_complete 缺失默认 false。总耗时由平台单调时钟测量，不以各步骤耗时相加。响应体最大 1 MiB。步骤类型为 agent/model/retrieval/tool，不采集隐藏思维链。trace 来源和字段真实性由接入方负责；报告不会把黑盒推断当作内部证据。

HTTP 429/5xx 和连接超时可在明确幂等且有预算的情况下最多额外重试一次；4xx、业务失败与评分争议不自动重试。

## 指标和状态

评分结果为 `{id,state,passed,reason,evaluator_version,source,created_at}`。人工修订附 reviewer、supersedes_id。UNKNOWN/ERROR/PENDING 的 passed 为 null，不能与 false 混用。

比较 rows 每项含 case_id、change、reason、baseline、candidate；change 为 IMPROVED、REGRESSED、UNCHANGED、INCOMPARABLE。delta 是配对通过率差值，前端以百分点展示。指标全部由后端生成，页面和报告共用。
