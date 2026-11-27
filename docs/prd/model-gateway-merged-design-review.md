# SuperAgent 内置模型网关与员工 Token 额度 · 合并稿技术评审

| 项 | 值 |
| --- | --- |
| 评审对象 | 合并评审稿《SuperAgent 内置模型网关与员工 Token 额度》（2026-11-23），用户附件；自称替代 v0.1 的阶段编号、路径、表名与回滚决策 |
| 对照基线 | `docs/prd/model-gateway-tech-design.md` v0.1；`docs/prd/model-gateway-token-quota-review.md`（上一轮评审）；本地分支 `feat/employee-handover` / HEAD 以稿内声称的 `938c214` 为准 |
| 仓库约束 | `AGENTS.md` / `CLAUDE.md`、README 六项判断、`docs/prd/superagent-architecture.html` §4.6 |
| 评审日期 | 2026-11-23 |
| 判定 | **有条件通过**。上一轮 P0 大多有正面回应，账本/迁移/注入矩阵可实施；剩余风险集中在 turn 与现有 Chat/Task 运行时的咬合，以及硬限额对外话术。可开 G0，不建议直接写账本业务代码。 |

---

## 1. 总体判定

合并稿相对 v0.1 与上一轮评审对象，做对了三件事：

1. 把员工 token 账本提升为一等公民，并吸收了预留池守恒、原子扣减、幂等投影、三态迁移（`legacy` / `shadow` / `gateway_primary`）。
2. 明确单机同 JVM 拓扑，opaque token + 服务端状态 + `expiresAt`，不再假装跨机可用。
3. 硬限额改为「有证据才开」，无证明只进 shadow，并禁止用整窗预占——纠正了上一轮 P0-4 的退路错误。

一句话：**可以按 G0 → G1 推进；进 G2 之前必须用实测钉死 turn/令牌/预占状态机，并把「月度准入」与「请求级硬预占」话术拆开。**

---

## 2. 上一轮 P0/P1 处置核对

| 原问题 | 合并稿处置 | 是否闭环 | 给 Codex 的动作 |
| --- | --- | --- | --- |
| P0-1 turnId 无载体 | §3：用户发送建 turnId；令牌绑定 turn/run；warm 不能换 env 则杀进程重签；failover 继承 turnId | **设计闭环，实现未证** | G0 增加「用户发送 → hop → failover → 追问」四元组验收（见 §4 P0-A） |
| P0-2 过期令牌不可测 | §3：opaque + expiresAt + Clock + 定时清理；单机不做 HMAC | **闭环** | 保留；单测必须可注入 Clock 构造过期 |
| P0-3 拓扑矛盾 | §2：首版单机同 JVM；跨机另案 | **闭环** | 在文首「范围」再写死一句，避免后文「两个 runtime」被读成跨机 |
| P0-4 硬限额上界不可用 | §4：无证明则硬限额关闭；禁止整窗预占 | **产品边界闭环** | 见 P0-B：把月度准入与请求级 R 预占拆开写，防对外误承诺 |
| P1-1 取消/缺 usage 按 R 扣 | §5：待核对冻结 R；定时/人工对账；有证据才结算 | **基本闭环** | 补默认 SLA 数字是否为建议值（稿末已写，保持） |
| P1-2 G2 切主过早 | §7：G2 准入认账本、报表仍 CLI；G3 达标后 `gateway_primary` | **闭环** | 无 |
| P1-3 跨模型一比一成本 | 估价另列 + 模型权限；余额仍 raw token | **部分缓解** | 见 P1-C：未定价模型默认不可选 |
| P1-4 「旧公司 0」哨兵 | `quota_mode=LIMITED/UNLIMITED` + 独立余额 | **闭环** | 无 |
| P1-5 注入矩阵不全 | §3 表扩到 Transcriber / ProviderService / CliConfigWriter / Probe | **闭环** | G1 矩阵把 media 未适配路径标 `blocked` 并加回归 |
| P1-6 X-MCP-Token 陷阱 | §4 明示读 Authorization，勿按旧注释 | **闭环** | 无 |
| P2 系列（公式/单位/DDL/路径/预占证明等） | §5–7 已吸收多数 | **基本闭环** | 文首声明「本稿替代 v0.1」；v0.1 顶部加废弃指向 |

---

## 3. 发现清单（本轮）

### P0 · 进入 G1 前 / G0 结束前必须解决

| # | 问题 | 代码/文稿证据 | 影响 | 建议（给 Codex 的修改指令） |
|---|---|---|---|---|
| P0-A | **turn 状态机与现有 Chat/Task 运行时未咬合** | `ChatService`：`sessionForTurn` 跨发送复用 session；`MODEL_HOPS=2`（约 L708）；失败轮换 / Key `failover`（约 L869）；跨模型会 `sessionForTurn(..., true)` 换 session。合并稿 §3 定义 turnId/runId，但未写清：同 turn 内 hop 的预占是增量还是整 turn 重算；换 session 后旧 run 令牌何时撤销；warm session 复用时如何强制「新用户发送 = 新 turn + 新令牌」 | G2/G2.1 上浮与并发验收会在真实链路上失败或行为未定义 | 在 §3 增补「Turn 状态机」小节，至少覆盖状态：`created → reserved → running → hopping/failover → settled/cancelled`；明确 (1) 同 turn 多 run 的预占规则 (2) 令牌撤销时机 (3) warm/failover/追问的验收断言。G0 退出条件增加一条实测：模拟「发送 → hop → failover → 追问」，断言 turnId/runId/令牌/预占符合状态机 |
| P0-B | **「硬上限」易被读成对用户的产品承诺** | §1「基础额度用满停止新一轮」是账本准入；§4「仅当上界可证明才硬限额」是请求级 R 预占。两者相邻出现，对外易混成「系统保证不超支到供应商」 | 销售/产品误承诺；未证明组合一旦放开会造成超支或误拒 | 在 §1 与 §6 显式增加约束段（建议原文级插入）：「员工月度 B/F 准入不依赖 tokenizer，G2 即可对用户生效；请求级 R 预占仅对 G0 证明过的供应商+协议组合生效；其余组合只进 shadow，不得对用户承诺不会超支到供应商。」§7 G0 分支表保持「证据不足只进 shadow」 |

### P1 · 高风险（G1/G2 前）

| # | 问题 | 建议（给 Codex） |
|---|---|---|
| P1-A | G0 钉 DeepSeek，但 §7 G1 写「两个 runtime」未点名 | 写死 G1 的两个 runtime（建议：`deepseek` + 一个已有 Anthropic 形或 Chat Completions 形 runner，并说明为何选它）；避免范围漂移到 11 家 |
| P1-B | Transcriber 等媒体路径「未适配则禁用」易漏测 | 在 §3 入口矩阵为 media / 未适配组合标 `blocked`；G1 加回归：这些路径在 `managed=true` 时不得出站真 Key、不得绕过网关 |
| P1-C | 跨模型 raw token 一比一，成本治理偏软 | G2 UI/配置：**未定价模型默认不可选**；已定价模型可按公司白名单。估价只展示，不改余额——保持稿内口径，但缺省拒绝贵/未知模型 |
| P1-D | 结算投影要稳定 `eventId`，旧 `UsageRecorder` 用随机 UUID | 在 §5 写死投影键规则，例如 `companyId + attemptId + "settlement"`（或等价），禁止 `UUID.randomUUID()` 作为幂等键；与「报表投影重放」验收对齐 |
| P1-E | 回滚语义与 v0.1 冲突 | 合并稿：受管故障「暂停新调用、保留冻结、回退兼容版本、**不得**给 runtime 注入真 Key」。v0.1：`enabled=false` 恢复直连。以合并稿为准，在「迁移/回滚」节写死开关语义表：`managed` 故障 ≠ 直连回退；若保留总开关，须区分「关闭受管网关并停调用」与「遗留 legacy 租户仍走旧路径」 |

### P2 · 中风险（实施中补齐）

| # | 问题 | 建议（给 Codex） |
|---|---|---|
| P2-A | 等待队列（2s / 每员工 2 个）与现有 `BusyException`、前端重试未对齐 | 标明返回码/错误类型如何映射到现有 API 错误基类；UI 是否可重试 |
| P2-B | UTC 自然月 vs 公司本地时区 | 待决项已有建议值；补一句「中国客户默认仍 UTC，或产品确认后可配」——至少不要静默假设 |
| P2-C | 员工互转默认策略空 | G3 前定默认：**默认关闭员工互转**，或仅管理员代转；开放互转需公司设置显式打开 |
| P2-D | 双文档源 | 在 `docs/prd/model-gateway-tech-design.md` 文首加废弃声明：「协议/观测细节已合并入《内置模型网关与员工 Token 额度》合并稿；阶段编号、路径、回滚以合并稿为准。」合并稿文首写「替代 v0.1」 |
| P2-E | 路径前缀 | 统一 `/api/v1/gateway`（合并稿已选）；全文检索消灭 `/api/v1/gw` 残留表述 |

---

## 4. 代码事实校正（合并稿相关）

| 合并稿声称 / 依赖 | 实际代码 | 判定 |
|---|---|---|
| `TokenUsage.total()` = 未缓存 input + cacheRead + cacheWrite + output；reasoning 含于 output | `TokenUsage.java:18-20` 一致；示例「input100 含 cache30 + output40 含 reasoning10 → 扣 140」正确 | ✅ |
| 旧 `QuotaService.checkTokens()` 为事后汇总 | `QuotaService.java:74-92`：UTC 月 + 约 1 分钟内存缓存，check-then-act，无预占 | ✅ 不能当预占实现；合并稿新建账本正确 |
| `Company.monthlyTokens` 非正为无限 | `QuotaService` 对 `max <= 0` 不拦截；公司 ID 为字符串，无数值「公司 0」 | ✅ `quota_mode` 替换哨兵正确 |
| 生产构造点 `CredentialService.toRun()` | 仍是唯一解密真 Key 的生产构造；注入点多处 | ✅ 「改工厂、扩矩阵」口径正确 |
| 一次用户发送可多次 CLI run / 换 session | `ChatService` MODEL_HOPS、failover、`sessionForTurn(..., modelDown)` | ⚠️ 必须在 turn 状态机里显式处理 |
| Warm session 换令牌 | Claude 等存在 warm/复用路径；环境能否热替换未保证 | ⚠️ 合并稿「不能换则杀进程」方向对，G0 要实测 |

---

## 5. 建议的文档修改清单（给 Codex 直接执行）

请按优先级修改合并稿（或产出「合并稿 v2」），并保持可测试、可引用的表述：

1. **文首**：声明本稿替代 `model-gateway-tech-design.md` v0.1；统一路径 `/api/v1/gateway`；回滚以「受管故障不注入真 Key」为准。
2. **§3 增补 Turn 状态机**：含 hop / failover / warm / 追问 / 取消；预占增量规则；令牌撤销时机；G0 四元组验收。
3. **§1/§6 增补话术拆分**：月度 B/F 准入 ≠ 请求级硬预占；未证明组合不对外承诺。
4. **§3 入口矩阵**：media/未适配标 `blocked`；G1 两个 runtime 写死名称。
5. **§5**：稳定 `eventId` / 投影键规则；禁止随机 UUID 作幂等键。
6. **§7 迁移/回滚**：开关语义表（legacy 租户 / managed 停调用 / 禁止直连回退真 Key）。
7. **§6/待决**：互转默认关闭或仅管理员；未定价模型默认不可选（可放 §6 或 G2 退出条件）。
8. **维护 v0.1 文件**：顶部加废弃指向，避免双源。

---

## 6. 阶段放行条件（更新）

| 阶段 | 放行条件 |
| --- | --- |
| **现在 → G0** | 可开。范围：DeepSeek 契约样本、turn/令牌/上界证据、warm/failover 换令牌证明。**不写**账本业务表落地代码（可写接口草案）。 |
| **G0 → G1** | P0-A 状态机有实测记录；P0-B 话术已写入稿；过期/撤销可测；注入矩阵含 Probe/Transcriber 归属；回滚=停调用非直连；G1 两个 runtime 已点名。 |
| **G1 → G2** | 共享 Key + 令牌对抗用例全绿；真 Key 不进 env/argv/临时文件；F=0 账本用例（含预占先于出站）全绿。 |
| **G2 → G2.1** | 上浮竞态/取消/重启/旧窗追问全绿后再对员工开 F。 |
| **G2.1 → G3** | 互转默认策略已定；切主指标达建议线；投影重放 0 双扣；受管回滚演练成功。 |

---

## 7. 结论

合并稿已经把上一轮「设计定义型 P0」收成可实施规格，增量价值真实。交给 Codex 继续修正时，**优先改文档闭环（§5 清单 1–8）**，再开 G0 取证；不要在 turn 状态机未钉死前实现 G2 上浮。

**本评审不修改生产代码、不提交、不推送。** 建议将本文件与合并稿一并交给 Codex，并要求其产出「合并稿 v2」diff 说明逐条对应本清单编号。
