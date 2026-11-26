// SuperAgent UI — Admin model-gateway quota: company pool, allocate/reclaim, per-person spend.
// Classic script sharing one global scope; index.html loads after model-prices.js.
"use strict";

Object.assign(api, {
  gwBalances: (period) => fetch("/api/v1/gateway/admin/balances"
    + (period ? "?period=" + encodeURIComponent(period) : "")).then(kbJson),
  gwPool: (type, period) => fetch("/api/v1/gateway/admin/pools?type="
    + encodeURIComponent(type || "UNALLOCATED")
    + (period ? "&period=" + encodeURIComponent(period) : "")).then(kbJson),
  gwPutPool: body => fetch("/api/v1/gateway/admin/pools", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  gwAllocate: body => kbPost("/api/v1/gateway/admin/allocations", body),
  gwReclaim: body => kbPost("/api/v1/gateway/admin/reclaims", body),
  gwSetFloat: body => fetch("/api/v1/gateway/admin/floats", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
  gwApplyFloatDefaults: body => kbPost("/api/v1/gateway/admin/float-defaults", body || {}),
  gwPending: (period) => fetch("/api/v1/gateway/admin/attempts?status=PENDING_RECONCILIATION"
    + (period ? "&period=" + encodeURIComponent(period) : "")).then(kbJson),
  gwReconcile: body => kbPost("/api/v1/gateway/admin/reconciliations", body),
  gwPolicy: () => fetch("/api/v1/gateway/admin/policy").then(kbJson),
  gwSavePolicy: body => fetch("/api/v1/gateway/admin/policy", {
    method: "PUT", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body),
  }).then(kbJson),
});

const gqEl = {
  period: document.getElementById("gqPeriod"),
  refresh: document.getElementById("gqRefresh"),
  poolCards: document.getElementById("gqPoolCards"),
  poolForm: document.getElementById("gqPoolForm"),
  poolType: document.getElementById("gqPoolType"),
  poolAmount: document.getElementById("gqPoolAmount"),
  allocForm: document.getElementById("gqAllocForm"),
  allocUser: document.getElementById("gqAllocUser"),
  allocAmount: document.getElementById("gqAllocAmount"),
  allocFloat: document.getElementById("gqAllocFloat"),
  reclaimBtn: document.getElementById("gqReclaimBtn"),
  setFloatBtn: document.getElementById("gqSetFloatBtn"),
  applyFloatBtn: document.getElementById("gqApplyFloatBtn"),
  rows: document.getElementById("gqRows"),
  empty: document.getElementById("gqEmpty"),
  policyForm: document.getElementById("gqPolicyForm"),
  policyAlert: document.getElementById("gqPolicyAlert"),
  policyTakeover: document.getElementById("gqPolicyTakeover"),
  policyReserve: document.getElementById("gqPolicyReserve"),
  policyFloat: document.getElementById("gqPolicyFloat"),
  policyReport: document.getElementById("gqPolicyReport"),
  managedList: document.getElementById("gqManagedClisList"),
  pending: document.getElementById("gqPending"),
};

/** Installable CLI keys for the managed-CLIs checkboxes (catalog fallback). */
const GQ_FALLBACK_CLIS = [
  { key: "claude", name: "Claude Code" },
  { key: "codex", name: "OpenAI Codex" },
  { key: "deepseek", name: "DeepSeek" },
  { key: "glm", name: "GLM" },
  { key: "kimi", name: "Kimi" },
  { key: "qwen", name: "Qwen" },
  { key: "grok", name: "Grok" },
  { key: "codebuddy", name: "CodeBuddy" },
  { key: "gemini", name: "Gemini" },
  { key: "minimax", name: "MiniMax" },
  { key: "qoder", name: "Qoder" },
];

/** Writable via PUT /pools. RECON_RESERVE is funded from UNALLOCATED (allocate / policy). */
const GQ_POOL_TYPES = ["UNALLOCATED", "FLOAT_POOL"];

let gqCatalogClis = null;
let gqDefaultFloat = 100000;

async function gqEnsureCatalogClis() {
  if (gqCatalogClis) return gqCatalogClis;
  try {
    const res = await fetch("/api/v1/providers/catalog").then(r => r.json());
    const rows = (res && res.data) || [];
    const list = (Array.isArray(rows) ? rows : [])
      .filter(e => e && e.installable && e.key)
      .map(e => ({ key: e.key, name: e.name || e.key }));
    gqCatalogClis = list.length ? list : GQ_FALLBACK_CLIS;
  } catch (_) {
    gqCatalogClis = GQ_FALLBACK_CLIS;
  }
  return gqCatalogClis;
}

function gqRenderManagedClis(selected) {
  if (!gqEl.managedList) return;
  const set = new Set((selected || []).map(s => String(s).toLowerCase()));
  gqEnsureCatalogClis().then(clis => {
    gqEl.managedList.replaceChildren();
    for (const c of clis) {
      const id = "gqCli-" + c.key;
      const label = document.createElement("label");
      const cb = document.createElement("input");
      cb.type = "checkbox";
      cb.id = id;
      cb.value = c.key;
      cb.checked = set.has(c.key.toLowerCase());
      label.append(cb, document.createTextNode(c.name || c.key));
      gqEl.managedList.appendChild(label);
    }
  });
}

function gqSelectedManagedClis() {
  if (!gqEl.managedList) return [];
  return [...gqEl.managedList.querySelectorAll('input[type="checkbox"]:checked')]
    .map(el => el.value);
}

const gqNum = n => Number(n || 0).toLocaleString();
const gqShort = n => n >= 1e6 ? (n / 1e6).toFixed(n >= 1e7 ? 0 : 1) + "M"
  : n >= 1e3 ? (n / 1e3).toFixed(n >= 1e4 ? 0 : 1) + "K" : String(n);

function gqOpId(kind) {
  return "ui-" + kind + "-" + Date.now() + "-" + Math.random().toString(36).slice(2, 8);
}

function gqCurrentPeriod() {
  const d = new Date();
  return d.getUTCFullYear() + "-" + String(d.getUTCMonth() + 1).padStart(2, "0");
}

function gqSelectedPeriod() {
  const v = (gqEl.period && gqEl.period.value) || "";
  return v || gqCurrentPeriod();
}

function gqMetric(value, label) {
  const m = document.createElement("div");
  m.className = "gq-metric";
  const v = document.createElement("span");
  v.className = "gq-metric__value";
  v.textContent = value;
  const l = document.createElement("span");
  l.className = "gq-metric__label";
  l.textContent = label;
  m.append(v, l);
  return m;
}

function gqUsedPct(bal) {
  if (!bal || !bal.baseQuota) return "—";
  const used = (bal.settled || 0) + (bal.reserved || 0);
  return Math.min(999, Math.round(100 * used / bal.baseQuota)) + "%";
}

async function gqEnsureUsers() {
  if (typeof allUsers !== "undefined" && allUsers && allUsers.length) return allUsers;
  try {
    const res = await api.listUsers();
    return (res && res.data) || [];
  } catch (_) {
    return [];
  }
}

function gqFillUserSelect(users, prefer) {
  const keep = prefer || gqEl.allocUser.value;
  gqEl.allocUser.replaceChildren();
  const blank = document.createElement("option");
  blank.value = "";
  blank.textContent = L("gq.pickPerson");
  gqEl.allocUser.appendChild(blank);
  users.filter(u => u.enabled !== false).forEach(u => {
    const opt = document.createElement("option");
    opt.value = u.id;
    const name = u.displayName || u.username || u.id;
    opt.textContent = name === u.username || !u.username ? name : name + " (" + u.username + ")";
    gqEl.allocUser.appendChild(opt);
  });
  if (keep && [...gqEl.allocUser.options].some(o => o.value === keep)) gqEl.allocUser.value = keep;
}

function gqPeopleRows(users, byOwner) {
  const enabled = users.filter(u => u.enabled !== false);
  const known = new Set(enabled.map(u => u.id));
  const rows = enabled.map(u => ({ user: u, bal: byOwner.get(u.id) || null }));
  // Orphan accounts (removed users) still show so reclaim stays possible.
  byOwner.forEach((bal, id) => {
    if (!known.has(id)) rows.push({ user: { id, username: id, displayName: id, enabled: false }, bal });
  });
  rows.sort((a, b) => {
    const an = (a.user.displayName || a.user.username || a.user.id).toLowerCase();
    const bn = (b.user.displayName || b.user.username || b.user.id).toLowerCase();
    return an.localeCompare(bn);
  });
  return rows;
}

function gqRenderPeople(rows) {
  gqEl.empty.hidden = rows.length > 0;
  gqEl.rows.replaceChildren();
  rows.forEach(({ user, bal }) => {
    const tr = document.createElement("tr");
    const name = document.createElement("td");
    const label = user.displayName || user.username || user.id;
    name.textContent = label;
    if (user.username && user.username !== label) {
      const sub = document.createElement("div");
      sub.className = "kb-list__meta";
      sub.textContent = user.username;
      name.appendChild(sub);
    }
    const cell = (v, bare) => {
      const td = document.createElement("td");
      td.className = "num";
      td.textContent = bare ? v : (bal ? gqNum(v) : "—");
      return td;
    };
    const act = document.createElement("td");
    const pick = document.createElement("button");
    pick.type = "button";
    pick.className = "link-btn";
    pick.textContent = L("gq.useInForm");
    pick.addEventListener("click", () => {
      gqEl.allocUser.value = user.id;
      if (bal && gqEl.allocFloat) gqEl.allocFloat.value = bal.floatAllowance || "";
      gqEl.allocAmount.focus();
    });
    act.appendChild(pick);
    tr.append(
      name,
      cell(bal ? bal.baseQuota : 0),
      cell(bal ? bal.floatAllowance : 0),
      cell(bal ? bal.available : 0),
      cell(bal ? bal.reserved : 0),
      cell(bal ? bal.settled : 0),
      cell(gqUsedPct(bal), true),
      act);
    if (!bal) tr.classList.add("gq-row--none");
    gqEl.rows.appendChild(tr);
  });
}

function gqRenderPending(attempts) {
  gqEl.pending.hidden = true;
  gqEl.pending.replaceChildren();
  if (!attempts || !attempts.length) return;
  gqEl.pending.hidden = false;
  const h = document.createElement("h4");
  h.className = "gw-pending__title";
  h.textContent = L("gw.pending");
  gqEl.pending.appendChild(h);
  attempts.forEach(a => {
    const row = document.createElement("div");
    row.className = "gw-pending__row";
    const meta = document.createElement("span");
    meta.textContent = personName(a.payerUserId) + " · R=" + gqNum(a.reservedTokens) + " · " + (a.cli || "");
    const btn = document.createElement("button");
    btn.type = "button";
    btn.className = "btn btn--small";
    btn.textContent = L("gw.reconcile");
    btn.addEventListener("click", async () => {
      const raw = prompt(L("gw.reconcilePrompt"), String(a.reservedTokens));
      if (raw == null || raw === "") return;
      const actual = Number(raw);
      if (!Number.isFinite(actual) || actual < 0) { showToast(L("gw.badAmount"), true); return; }
      try {
        await api.gwReconcile({ attemptId: a.id, actualTokens: actual });
        showToast(L("gw.reconciled"), false);
        gqLoad();
      } catch (e) { showToast(e.message, true); }
    });
    row.append(meta, btn);
    gqEl.pending.appendChild(row);
  });
}

async function gqLoadPoolMetric(type, period) {
  try {
    const pool = await api.gwPool(type, period);
    return gqShort(pool.available);
  } catch (_) {
    return "—";
  }
}

async function gqLoad() {
  if (!gqEl.poolCards) return;
  if (!gqEl.period.value) gqEl.period.value = gqCurrentPeriod();
  const period = gqSelectedPeriod();
  const users = await gqEnsureUsers();
  gqFillUserSelect(users);

  const [unalloc, floatPool, recon] = await Promise.all([
    gqLoadPoolMetric("UNALLOCATED", period),
    gqLoadPoolMetric("FLOAT_POOL", period),
    gqLoadPoolMetric("RECON_RESERVE", period),
  ]);
  gqEl.poolCards.replaceChildren(
    gqMetric(unalloc, L("gq.poolAvailable")),
    gqMetric(floatPool, L("gq.poolFloat")),
    gqMetric(recon, L("gq.poolRecon")));

  let balances = [];
  try {
    const page = await api.gwBalances(period);
    balances = (page && page.balances) || [];
  } catch (e) { showToast(e.message, true); }
  const byOwner = new Map(balances.map(b => [b.owner, b]));
  gqRenderPeople(gqPeopleRows(users, byOwner));

  try {
    const pol = await api.gwPolicy();
    if (pol) {
      gqEl.policyAlert.value = pol.pendingAlertAfterHours;
      gqEl.policyTakeover.value = pol.pendingCompanyTakeoverAfterBusinessDays;
      gqEl.policyReserve.value = pol.reconReserveTargetTokens;
      gqDefaultFloat = Number(pol.defaultFloatAllowance) || 0;
      if (gqEl.policyFloat) gqEl.policyFloat.value = gqDefaultFloat;
      gqEl.policyReport.value = pol.reportMode || "LEGACY";
      gqRenderManagedClis(pol.managedClis || []);
      if (gqEl.allocFloat && !gqEl.allocFloat.value) {
        gqEl.allocFloat.placeholder = String(gqDefaultFloat);
      }
    }
  } catch (_) {
    gqRenderManagedClis([]);
  }

  try {
    const page = await api.gwPending(period);
    gqRenderPending((page && page.attempts) || []);
  } catch (_) { gqRenderPending([]); }
}

async function gqSubmitPool(e) {
  e.preventDefault();
  const amount = Number(gqEl.poolAmount.value);
  const type = (gqEl.poolType && gqEl.poolType.value) || "UNALLOCATED";
  if (!GQ_POOL_TYPES.includes(type)) { showToast(L("gw.badAmount"), true); return; }
  if (!Number.isFinite(amount) || amount < 0) { showToast(L("gw.badAmount"), true); return; }
  try {
    await api.gwPutPool({ type, period: gqSelectedPeriod(), available: amount });
    showToast(L("gw.poolSeeded"), false);
    gqEl.poolAmount.value = "";
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

async function gqSubmitAlloc(e) {
  e.preventDefault();
  const userId = (gqEl.allocUser.value || "").trim();
  const amount = Number(gqEl.allocAmount.value);
  if (!userId || !Number.isFinite(amount) || amount <= 0) { showToast(L("gw.badAmount"), true); return; }
  const body = {
    operationId: gqOpId("alloc"),
    period: gqSelectedPeriod(),
    userId,
    amount,
  };
  const rawF = gqEl.allocFloat && gqEl.allocFloat.value !== "" ? Number(gqEl.allocFloat.value) : null;
  if (rawF != null) {
    if (!Number.isFinite(rawF) || rawF < 0) { showToast(L("gw.badAmount"), true); return; }
    body.floatAllowance = rawF;
  }
  try {
    await api.gwAllocate(body);
    gqEl.allocAmount.value = "";
    showToast(L("gw.allocated"), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

async function gqSubmitReclaim() {
  const userId = (gqEl.allocUser.value || "").trim();
  const amount = Number(gqEl.allocAmount.value);
  if (!userId || !Number.isFinite(amount) || amount <= 0) { showToast(L("gw.badAmount"), true); return; }
  try {
    await api.gwReclaim({
      operationId: gqOpId("reclaim"),
      period: gqSelectedPeriod(),
      userId,
      amount,
    });
    gqEl.allocAmount.value = "";
    showToast(L("gq.reclaimed"), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

async function gqSubmitSetFloat() {
  const userId = (gqEl.allocUser.value || "").trim();
  const raw = gqEl.allocFloat ? gqEl.allocFloat.value : "";
  const f = raw === "" ? gqDefaultFloat : Number(raw);
  if (!userId || !Number.isFinite(f) || f < 0) { showToast(L("gw.badAmount"), true); return; }
  try {
    await api.gwSetFloat({ period: gqSelectedPeriod(), userId, floatAllowance: f });
    showToast(L("gq.floatSet"), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

async function gqApplyFloatDefaults() {
  try {
    const res = await api.gwApplyFloatDefaults({ period: gqSelectedPeriod() });
    const n = (res && res.updated) != null ? res.updated : 0;
    showToast(L("gq.floatApplied").replace("{n}", String(n)), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

async function gqSubmitPolicy(e) {
  e.preventDefault();
  try {
    await api.gwSavePolicy({
      pendingAlertAfterHours: Number(gqEl.policyAlert.value),
      pendingCompanyTakeoverAfterBusinessDays: Number(gqEl.policyTakeover.value),
      reconReserveTargetTokens: Number(gqEl.policyReserve.value),
      defaultFloatAllowance: Number(gqEl.policyFloat ? gqEl.policyFloat.value : gqDefaultFloat),
      reportMode: gqEl.policyReport.value,
      managedClis: gqSelectedManagedClis(),
    });
    showToast(L("gw.policySaved"), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

if (gqEl.poolForm) gqEl.poolForm.addEventListener("submit", gqSubmitPool);
if (gqEl.allocForm) gqEl.allocForm.addEventListener("submit", gqSubmitAlloc);
if (gqEl.reclaimBtn) gqEl.reclaimBtn.addEventListener("click", gqSubmitReclaim);
if (gqEl.setFloatBtn) gqEl.setFloatBtn.addEventListener("click", gqSubmitSetFloat);
if (gqEl.applyFloatBtn) gqEl.applyFloatBtn.addEventListener("click", gqApplyFloatDefaults);
if (gqEl.policyForm) gqEl.policyForm.addEventListener("submit", gqSubmitPolicy);
if (gqEl.refresh) gqEl.refresh.addEventListener("click", () => gqLoad());
if (gqEl.period) gqEl.period.addEventListener("change", () => gqLoad());
