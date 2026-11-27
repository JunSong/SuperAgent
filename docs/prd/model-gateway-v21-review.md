# SuperAgent 内置模型网关与员工 Token 额度 · 第三轮修订稿 v2.1 技术评审

| 项 | 值 |
| --- | --- |
| 评审对象 | 《SuperAgent 内置模型网关与员工 Token 额度》第三轮修订稿 v2.1（2026-11-23），用户附件 `SuperAgent-____-Token________3_.md` |
| 对照基线 | `docs/prd/model-gateway-v2-review.md`；`docs/prd/model-gateway-token-quota-review-v3.md`；`docs/prd/model-gateway-tech-design.md` v0.1 |
| 评审日期 | 2026-11-23 |
| 判定 | **通过。可作唯一实施依据，开 G0。** 上两路评审的 P1/P2 均已落到可测条文；残留仅为仓库归档戳未落地与一两处验收措辞细化，不挡取证。 |

---

## 1. 总体判定

v2.1 把「可开 G0 的有条件通过」收成「设计可执行」：

- §6/§7 拆开 `QUOTA_INSUFFICIENT` / `QUOTA_BUSY` / `QUOTA_EXHAUSTED`，G2 用例 A/B/C 与定义一致。
- §5 引入 `RECON_RESERVE`、担保锁定额进守恒式，并写清接管后员工余额不再随 A 浮动、差额公司独担。
- §5 独立 `ProjectionRecorder` + `(…, lineNo)` + SHA-256→`VARCHAR(64)`，legacy `record()` 链不动；ClickHouse 读时去重。
- §4 两阶段误拒测量、附件超限 `blocked`、请求有界缓冲 / 响应只泵送。
- §3/§7 分清 warm 真实 CLI 与 G1 桩测；§8 第三轮覆盖核对表闭合两路评审。

**一句话：文档评审闭环；下一动作是 G0 取证，不是再开一轮大改稿。**

---

## 2. 上两路评审逐项核对

### 2.1 `model-gateway-v2-review.md`

| 项 | v2.1 处置 | 结果 |
|---|---|---|
| P1-1 G2 把不足写成 BUSY | §6 三码；§7 G2 A=`QUOTA_INSUFFICIENT`、B=队列忙、唤醒后耗尽=`QUOTA_EXHAUSTED` | ✅ |
| P1-2 工具回传易误读 | §3：报文本身不扣费；补全新建 attempt、增量预占 | ✅ |
| P2-1 准备金未进守恒 | §5 type=`RECON_RESERVE`；公式含准备金（含担保锁定额）；异常期 `预算+D=…` | ✅ |
| P2-2 v0.1 归档戳 | §8 仍写「建议标注」；仓库 `model-gateway-tech-design.md` 文首未改 | ⚠️ 流程残留（见 §4） |
| P2-3 warm vs G1 桩 | §3 G0 按 runner；§7 G0/G1：桩不宣称 warm 通过；warm 取 G0 真实 CLI | ✅ |
| P2-4 请求缓冲/响应泵送 | §4「请求可有界缓冲计数；响应始终逐块转发」 | ✅ |
| P2-5 准备金财务确认 | §7 G3 须财务确认 + R/A 三档演练；稿末待决保留 | ✅ |

### 2.2 `model-gateway-token-quota-review-v3.md`

| 项 | v2.1 处置 | 结果 |
|---|---|---|
| P1-1 投影键撞 `token_usage` 主键 / 多行 payload | 独立投影通道；键含 `lineNo`；规范编码后 SHA-256 hex；同摘要幂等、异摘要拒写；不改 CLI `record()` | ✅ 与现网 `JdbcUsageStore` 裸 INSERT、数组多行模型对齐 |
| P1-2 准备金差额与 SLA 自相矛盾 | §5 统一 1h 告警 / 次日分派 / 最晚第 2 工作日解除冻结并公司承接；A⇔R 差额只走准备金；担保不足暂停新轮、不延长员工冻结；G3 数值演练 | ✅ |
| P2-1 误拒分母循环依赖 | §4 两阶段：桩校准真值 → 离线回放准入；禁止从线上被拒请求反推分母 | ✅ |
| P2-2 G0 claude 证据 | §7 G0：claude 真实 CLI + 本地厂商桩记热换/resume；桩覆盖不了标待联调 | ✅ |
| P2-3 附件 vs 8 MiB | 含附件整请求受 8 MiB；超限组合 `blocked`、413、0 出站 | ✅ |

---

## 3. 本轮抽查（无新的 P1）

对照代码与条文，未发现挡 G0/G1 的新设计洞。以下为实施时注意点，**不要求再改设计稿**（除非产品想把验收写得更死）：

| # | 观察 | 建议 |
|---|---|---|
| N1 | G2-B 只断言「唤醒后 → `QUOTA_EXHAUSTED`」；「等待条件不满足应立即 `QUOTA_INSUFFICIENT`、不得入队」靠 §6 叙述 | 实现测试加一条：B 头寸释放后仍装不下本次 R → 立即不足、0 等待 |
| N2 | 接管分录写「补回原资金来源（基础余额或上浮池）」 | attempt 上持久化资金来源枚举，避免跨 B/F 多笔在途时补错池 |
| N3 | `GlobalExceptionHandler` 对 `BusyException` 仍是通用 409；稿要求细分码 / 端点映射 | G2 实现时加结构化 reason 或网关专用映射（稿 §7 已写，跟实现即可） |
| N4 | v0.1 文件仍像现行方案 | 纳入仓库时给 `docs/prd/model-gateway-tech-design.md` 打归档戳，或用 v2.1 正文替换并保留旧文件改名（稿 §8 原文） |

非问题确认：hop/failover 增量预占与撤令牌；「继续」= 新 turn；managed 故障不得切 legacy 灌真 Key；硬限额与月度准入两道门；F 至 G2.1 才开——均与前序评审及 `ChatService` 行为一致。

---

## 4. 阶段放行（定稿）

| 阶段 | 判定 |
| --- | --- |
| **→ G0** | **放行。** 唯一设计依据 = 本 v2.1。取证按 §4 表 + §3 四步回放；证据不足组合只进 shadow。 |
| **G0 → G1** | 身份/隔离/流式/blocked media；warm 结论只采 G0 真实 CLI 行。 |
| **G1 → G2** | A/B/C 三用例按码断言；预占先于出站（独立连接读库）；投影通道可先接口后实表。 |
| **G2 → G2.1** | 上浮竞态全绿后再对员工开 F。 |
| **→ G3** | 切主指标、投影重放、互转默认、准备金演练（启用才做）+ 财务确认。 |

---

## 5. 给实施 / Codex 的动作

**设计侧：不必再出 v2.2。** 可选机械项：

1. 把附件 v2.1 正文写入 `docs/prd/`（或替换 `model-gateway-tech-design.md` 并归档 v0.1）。
2. 在 v0.1 文首加稿内已写好的归档句。

**工程侧：直接开 G0。** 并行准备（不挡取证）：`RunTokenResolver` / `ProjectionRecorder` 接口草图；`QUOTA_*` → HTTP 映射表；attempt 资金来源字段。

稿末待决（产品/财务，非文档缺陷）：第 2 工作日公司接管、准备金规模、B/F/互转审批、UTC 月界——保持建议值，未实测不填「通过」。

---

## 6. 结论

v2.1 已吸收 `model-gateway-v2-review` 与 `model-gateway-token-quota-review-v3` 的全部 P1/P2，**设计评审结束**。

**本评审不修改生产代码、不提交、不推送。** 交 Codex / 开发时：实施依据用本附件 v2.1；本文件仅作闭合证明与 G0 放行记录。
