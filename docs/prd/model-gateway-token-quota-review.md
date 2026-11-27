# SuperAgent 模型网关 Token 额度方案 · 技术评审

评审对象：`/Users/junsongyoung/Downloads/SuperAgent-模型网关-Token额度技术方案.md`（技术评审稿 · 2026-11-23）
评审基准：本地 `feat/employee-handover` 代码实况、`AGENTS.md` / `CLAUDE.md`、README 六项判断、`docs/prd/superagent-core-thesis.html`、`docs/prd/superagent-architecture.html` §4.6、已有 `docs/prd/model-gateway-tech-design.md` v0.1
评审日期：2026-11-23
判定：**有条件通过**。方向正确、账本设计比 v0.1 扎实，但存在 4 项 P0 闭环缺口——不解决则 G1/G2 的验收标准无法执行，不建议直接进入实施。

---

## 1. 总体判定

这份稿子做对了三件事：把「员工 token 账本」从 v0.1 的观测附属品提升为一等公民；明确区分了「善意 CLI 的资源治理」与「恶意 CLI 的安全隔离」，没有偷换概念；把跨模型共用额度和上浮规则写成了可测试的用例。

但它同时引入了一个自身无法闭环的机制：**turnId 上浮**。代码里既没有 turnId，也没有任何把 turn 边界送进模型请求的通道；而令牌体系（沿用 `McpGrants`）连「过期」都无法表达。G2 的核心验收用例因此悬空。其次，「硬限额」依赖的「可靠输入上界」在网关不构造请求体的前提下只能退化为上下文窗口上界，与它自己的并发用例直接冲突。

一句话：**账本设计可以进 G1，但准入控制的设计还不能进 G2。**

---

## 2. 发现清单

### P0 · 阻断（进入 G1 前必须解决）

| # | 问题 | 代码证据 | 影响 | 建议 |
|---|---|---|---|---|
| P0-1 | **turnId 无载体，G2 上浮机制闭环断裂** | 方案 §3.2（L68）定义「一次发送 = 一个 turnId」；`ChatService` 无 turnId，`sessionForTurn()` 跨发送复用 session（L332-353），一次发送可产生多次 CLI run（MODEL_HOPS=2 L708、失败轮换 L755-766），CLI 内部多次模型请求 Java 不可见（`JsonLineCliRunner.run()` L117-204）；模型请求由**外部 CLI 二进制**构造，`AgentRunRequest` 无 turn/run/user 字段（L18-31）；方案令牌绑定表（L33）也无 turnId | G2 验收「本轮到 103 万时工具往返允许、新追问被拒」无法实现——网关拿不到 turn 边界，无法区分「同轮后续请求」与「新一轮」 | G0 必须二选一定稿：(a) 令牌粒度 = turn，每次用户发送重签含 turnId 的令牌，并验证 warm session / failover / 后台任务下的替换时机；(b) 放弃 turnId，上浮改按 runId，同步改写 G2 全部验收用例。不得两者都不写就开工 |
| P0-2 | **「过期令牌 401」不可实现、不可测试** | 方案 §4（L88）写「无效/过期凭据 401」，§3（L33）写令牌有「有效期」；实际 `McpGrants.Grant`（L57-59）无 `expiresAt`，`find()`（L285-295）只查进程内 Map，无 TTL 清理、无签名；`McpController.rpc()`（L54-62）只能判「无效/已撤销」 | G1 验收「过期令牌请求的上游调用数均为 0」无法执行——系统里构造不出过期令牌 | 明确新增 `expiresAt` + HMAC 签名与校验；或把验收改为「撤销令牌」，删除「过期」表述 |
| P0-3 | **部署拓扑自相矛盾，决定令牌实现方案** | §1 L15「同一 Java 进程中的 superagent-gateway」vs §1 L17「两人的 runtime 分别持本系统签发的短期令牌」的独立部署验收；`McpGrants` 是三个进程内 Map（L160-164），跨进程不可见；方案 L29 自己也承认「单机进程级替换不能证明恶意 CLI 已隔离」 | 若 runtime 跨机，进程内 Map 令牌直接失效，必须改签名令牌 + 共享存储；这是架构分叉点，不能留到 G5 | 在 G0 写死拓扑：同机同进程 / 同机不同进程 / 跨机，据此确定令牌实现。独立部署验收的拓扑必须与之一致 |
| P0-4 | **「硬限额」的可靠输入上界在产品上不可用** | §3 L74「预占 R = 本次完整计费输入的可靠上界 + 强制的最大输出」，退路是「经验证的模型单次总量上限做保守预占」；但网关不构造请求体（CLI 构造），要在转发前解析 body 并按模型 tokenizer 计数（工具定义、模板、附件全计入）。若退化为模型上下文窗口（如 64K），每次请求预占 64K，与 §5 G2「每次须预占 60」直接冲突；十人各分 800 时并发两次即耗尽 | 「硬限额」承诺无法兑现：要么误拒率极高，要么超支 | 三选一定稿：(a) 实现本地 tokenizer + 版本对齐 + 请求体解析，并给出误拒率基线；(b) 产品承诺降级为「软限额 + 事后结算 + 输出夹紧」，去掉「硬」字；(c) 禁止用上下文窗口做上界。**估算均值、字符除常数不能承诺硬上限**——这一条方案写对了，但退路写错了 |

### P1 · 高风险（G2/G3 前必须解决）

| # | 问题 | 代码证据 | 建议 |
|---|---|---|---|
| P1-1 | 取消 / 崩溃 / 缺失 usage 默认按 R 扣，运营不可持续 | §3 L76「禁止无条件超时退款」方向正确；但 usage 在 SSE 末帧，取消时拿不到实际值，保守 R 远大于实际 → 多扣，管理员需人工查供应商账单 | 定义默认结算策略（按 R 扣 / 按最后可见 token 估算并封顶），提供「供应商请求 ID → 实际 usage」自动对账接口与人工调整 SLA |
| P1-2 | G2 即切「单一用量主账」过早 | §5 G2（L98）；v0.1 的 G3 是「影子对账、不切主账」，流式 usage 覆盖率目标 ≥99% 而非 100% | G2 网关账本仅作准入依据，`token_usage` 并行对账；G3 差异达标后再宣布单一主账 |
| P1-3 | 跨模型一比一扣减导致公司成本不可控 | §1 L6 自承「公司 token 上限不等同于货币支出上限」，§3 L62「跨模型共用月额度」已确认；`ModelPriceService.price(model)` 按模型返回四类单价，`TokenUsageService.cost()` 按类型计价，Claude 与 DeepSeek 单价差一个量级 | 至少提供「按模型加权系数」可选配置（默认 1.0），或贵模型走审批 / 独立池。不缓解则等于让员工自由选择公司成本 |
| P1-4 | 「旧公司 0」是代码里不存在的哨兵，且自相矛盾 | §3 L60「沿用旧公司 0 表示无限制；受管员工分到 0 表示无可用额度，显式表示『无限制』，不复用一个 0 哨兵」；实际公司 ID 是字符串，缺省伪公司是 `Company.DEFAULT_ID = "superagent"`（L21-25），无数值 0；旧无租户表走 DROP 重建而非 0 映射 | 明确用 NULL / -1 / 独立 boolean 表示「无限制」，与「余额为零」严格区分 |
| P1-5 | 后台 system 调用无员工归属，注入矩阵不全 | §2 L29「逐项检查…后台任务」、§5 G4「系统作业用量可归属」；实际 `ProviderInferenceProbe` 记在 `"system"`（L52-55,76），`Transcriber`、`FileTagger`、`AgentMemoryService`、`ProviderService` 都走 `credentials.current()` 但无 MCP grant；v0.1 的「6 处」漏了 `Transcriber` 与 `ProviderService:139` | 列全注入矩阵（至少 9 处），定义 system 调用是否消耗公司额度、由谁承担；G4 验收补上具体归属规则 |
| P1-6 | `SecurityConfig` 注释与实现不一致的实现陷阱 | `SecurityConfig.java:68` 注释写 `gated by X-MCP-Token`，`McpController.java:56` 实际读 `Authorization`；全仓库无 `X-MCP-Token` | 评审稿中显式提示，避免实现者照注释写出永远 401 的网关 |

### P2 · 中风险（实施中补齐）

| # | 问题 | 建议 |
|---|---|---|
| P2-1 | 不变量公式漏「预留池」：§3 L60 公式为「预算 = 未分配 + 可用 + 在途预占 + 已结算」，但 L70 引入「公司预留池」 | 扩展公式，否则账对不平 |
| P2-2 | 验收数字单位混乱：L64 用「万」，L98 用「10,000 / 800 / 60 / 35」 | 全表统一单位（如 K tokens）并在表头标注 |
| P2-3 | DDL 演进方式未写；项目无 Flyway / Liquibase / 版本表，DDL 内联在 `Store.SCHEMA`，靠 `TenantSchema.create()` + `ensureColumn` 演进 | 明确新表 DDL 归属；`gateway_attempt` 需 `(company_id, request_id, attempt_no)` 唯一索引，`gateway_account` 需 `(company_id, period, type, owner)` 唯一索引 |
| P2-4 | 事务实现未落到项目惯例：全仓无 `SELECT … FOR UPDATE`，有 `UPDATE … + 1` 隐式锁、`ON DUPLICATE KEY UPDATE`、`INSERT IGNORE` | 明确用 `UPDATE balance SET balance = balance - ? WHERE balance >= ?` 做原子扣减、`INSERT … ON DUPLICATE KEY UPDATE` 做幂等，符合现有工程习惯 |
| P2-5 | 与 v0.1 三处冲突：路径 `/api/v1/gateway` vs `/api/v1/gw`；令牌「沿用 McpGrants」 vs v0.1 的 `RunTokenResolver` 抽象；主账「G2 单一」 vs v0.1「G3 影子」 | 明确本稿替代 v0.1，或合并成一份；否则实施时会取错口径 |
| P2-6 | G1「首事件早于上游结束」只证明流式转发，不证明预占先于发送 | 上游桩记录收到请求时的账户余额快照，断言预占已入账 |
| P2-7 | 「偷偷直连」在单机无沙箱时不可检测，硬限额容易被误读为安全边界 | 产品文档明示「硬限额是资源治理，不是安全边界；隔离依赖 Worker/沙箱阶段」 |
| P2-8 | 员工互转 token 的财务合规边界未定 | 产品 / 财务确认；技术侧完整记录对手方、理由与操作者 |

---

## 3. 代码事实校正表

| 方案声称 | 实际代码 | 判定 |
|---|---|---|
| `TokenUsage.total()` = input + cacheRead + cacheWrite + output；reasoning 含于 output | `TokenUsage.java:18-20`、`UsageRecord.java:13-15` 完全一致 | ✅ 一致 |
| `QuotaService` 可支撑额度控制 | `QuotaService.java:81-93` 是 UTC 月 + 1 分钟内存缓存的**事后汇总 check-then-act**，无锁、无事务、无预占 | ⚠️ 方案未声称直接复用它做预占，但「沿用」表述需澄清 |
| `TokenUsageService.record()` 随机 ID 不能当幂等键 | `TokenUsageService.java:83-107` 为 `UUID.randomUUID()`，`UsageRecorder.record()` 无幂等参数，`JdbcUsageStore.save()` 裸 INSERT | ✅ 一致，方案已自承 |
| 「沿用旧公司 0 表示无限制」 | 不存在；公司 ID 为字符串，`Company.DEFAULT_ID = "superagent"` | ❌ 不一致 |
| 「沿用 McpGrants 的签发撤销时机」 | 时机一致（`finally` 中 close），但 `Grant` 无 expiresAt / 签名 / runId / 模型白名单 / 付费员工校验，`Kind` 只管工具权限 | ⚠️ 时机一致，能力缺口大 |
| 「由运行创建方构造受管 RunCredential」 | 生产构造点**只有 1 处**：`CredentialService.toRun()`（L226-229），内容是解密后的厂商真 Key + 厂商地址；方案/旧稿说的「6 处」指注入调用点，且实际还漏了多处 | ⚠️ 措辞需改 |
| `DeepSeekCliAgentRunner.environment()` 注入 `DEEPSEEK_API_KEY` / `DEEPSEEK_BASE_URL` | `DeepSeekCliAgentRunner.java:83-93` 完全一致；baseUrl 为 null 时不注入 | ✅ 一致 |
| 「模型端单独校验用途受限令牌」 | 可照 `/mcp`（permitAll + controller 自验 Bearer + `TenantContext.call`）实现；但「过期」无底层支撑 | ⚠️ 模式可复用，能力需新增 |
| 新增 `/api/v1/gateway/**` 会走现有鉴权 | `SecurityConfig.java:83` 兜底 `anyRequest().authenticated()`，不加白名单会返回 401「请先登录」 | ✅ 一致 |

---

## 4. 进入 G1 前的放行条件

按优先级，全部满足才建议开工：

1. **拓扑定稿**（同进程 / 跨进程 / 跨机），并据此确定令牌是进程内 Map 还是签名令牌。
2. **turn 边界定稿**：令牌粒度 = turn 或改用 runId；二选一后同步改写 G2 全部验收用例。
3. **令牌有效期机制落地**：`expiresAt` + 校验，或验收改为「撤销令牌」并删除「过期」表述。
4. **硬限额边界定稿**：给出可靠输入计数方案与误拒率基线，或降级为软限额；明确禁止上下文窗口上界。
5. **注入矩阵补全**（≥9 处，含 system 调用归属），消除「偷偷直连」与「未声明组合」的模糊表述。
6. **两稿关系声明**：本稿替代 v0.1 或合并；统一路径前缀、令牌抽象、主账切换时机。
7. **单位与公式修正**：统一 token 单位，扩展不变量公式以包含预留池。

---

## 5. 结论

推荐按「**先补 P0、再进 G1**」推进。P0 四项都是设计定义问题，不是实现难度问题——每项的修复成本远低于带着模糊定义开工后的返工成本。P1 中 P1-2（主账时机）、P1-3（跨模型成本）需要产品侧拍板，建议在 G1 评审会上一次性确认。

本稿相对 v0.1 的增量价值是真实的，尤其是把「额度」从观测指标变成可事务化的账户模型；但 **turnId 与硬限额这两处，必须先把「承诺什么」说清楚，才能谈「怎么实现」。**
