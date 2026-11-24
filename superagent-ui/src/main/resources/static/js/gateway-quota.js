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
  poolAmount: document.getElementById("gqPoolAmount"),
  allocForm: document.getElementById("gqAllocForm"),
  allocUser: document.getElementById("gqAllocUser"),
  allocAmount: document.getElementById("gqAllocAmount"),
  reclaimBtn: document.getElementById("gqReclaimBtn"),
  rows: document.getElementById("gqRows"),
  empty: document.getElementById("gqEmpty"),
  policyForm: document.getElementById("gqPolicyForm"),
  policyAlert: document.getElementById("gqPolicyAlert"),
  policyTakeover: document.getElementById("gqPolicyTakeover"),
  policyReserve: document.getElementById("gqPolicyReserve"),
  policyReport: document.getElementById("gqPolicyReport"),
  pending: document.getElementById("gqPending"),
};

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
      gqEl.allocAmount.focus();
    });
    act.appendChild(pick);
    tr.append(
      name,
      cell(bal ? bal.baseQuota : 0),
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

async function gqLoad() {
  if (!gqEl.poolCards) return;
  if (!gqEl.period.value) gqEl.period.value = gqCurrentPeriod();
  const period = gqSelectedPeriod();
  const users = await gqEnsureUsers();
  gqFillUserSelect(users);

  gqEl.poolCards.replaceChildren();
  let poolAvail = "—";
  let poolReserved = "—";
  try {
    const pool = await api.gwPool("UNALLOCATED", period);
    poolAvail = gqShort(pool.available);
    poolReserved = gqShort(pool.reserved || 0);
    if (!gqEl.poolAmount.value) gqEl.poolAmount.placeholder = String(pool.available || "");
  } catch (_) { /* pool not seeded yet */ }
  gqEl.poolCards.append(
    gqMetric(poolAvail, L("gq.poolAvailable")),
    gqMetric(poolReserved, L("gw.reserved")));

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
      gqEl.policyReport.value = pol.reportMode || "LEGACY";
    }
  } catch (_) { /* ignore */ }

  try {
    const page = await api.gwPending(period);
    gqRenderPending((page && page.attempts) || []);
  } catch (_) { gqRenderPending([]); }
}

async function gqSubmitPool(e) {
  e.preventDefault();
  const amount = Number(gqEl.poolAmount.value);
  if (!Number.isFinite(amount) || amount < 0) { showToast(L("gw.badAmount"), true); return; }
  try {
    await api.gwPutPool({ type: "UNALLOCATED", period: gqSelectedPeriod(), available: amount });
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
  try {
    await api.gwAllocate({
      operationId: gqOpId("alloc"),
      period: gqSelectedPeriod(),
      userId,
      amount,
    });
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

async function gqSubmitPolicy(e) {
  e.preventDefault();
  try {
    await api.gwSavePolicy({
      pendingAlertAfterHours: Number(gqEl.policyAlert.value),
      pendingCompanyTakeoverAfterBusinessDays: Number(gqEl.policyTakeover.value),
      reconReserveTargetTokens: Number(gqEl.policyReserve.value),
      reportMode: gqEl.policyReport.value,
    });
    showToast(L("gw.policySaved"), false);
    gqLoad();
  } catch (err) { showToast(err.message, true); }
}

if (gqEl.poolForm) gqEl.poolForm.addEventListener("submit", gqSubmitPool);
if (gqEl.allocForm) gqEl.allocForm.addEventListener("submit", gqSubmitAlloc);
if (gqEl.reclaimBtn) gqEl.reclaimBtn.addEventListener("click", gqSubmitReclaim);
if (gqEl.policyForm) gqEl.policyForm.addEventListener("submit", gqSubmitPolicy);
if (gqEl.refresh) gqEl.refresh.addEventListener("click", () => gqLoad());
if (gqEl.period) gqEl.period.addEventListener("change", () => gqLoad());
