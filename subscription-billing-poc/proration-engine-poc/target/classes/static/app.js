// ===================================================================
//  Proration Engine — page controller
// ===================================================================

let PLANS = [];
let SUBS = [];
let SELECTED_SUB = null;

document.addEventListener("DOMContentLoaded", async () => {
  document.getElementById("ns-today").value = Fmt.today();
  document.getElementById("ch-today").value = Fmt.today();
  document.getElementById("cn-today").value = Fmt.today();
  await loadPlans();
  await loadSubscriptions();
});

// -------- Plans --------

async function loadPlans() {
  try {
    PLANS = await Api.get("/api/plans");
    renderPlans();
    populatePlanSelects();
  } catch (e) {
    Toast.error("Failed to load plans", e.message);
  }
}

function renderPlans() {
  const el = document.getElementById("plans-list");
  if (!PLANS.length) {
    el.innerHTML = '<div class="empty-state">No plans configured.</div>';
    return;
  }
  el.innerHTML = PLANS.map(p => `
    <div class="flex items-center justify-between" style="padding:8px 0; border-bottom:1px solid var(--border);">
      <div>
        <div style="font-weight:600">${escape(p.name)}</div>
        <div class="text-sm text-muted mono">${escape(p.id)}</div>
      </div>
      <div class="num" style="font-weight:600">${Fmt.money(p.monthlyPrice?.amount ?? p.priceAmount)}</div>
    </div>
  `).join("");
}

function populatePlanSelects() {
  const optionsHtml = PLANS.map(p =>
    `<option value="${escape(p.id)}">${escape(p.name)} — ${Fmt.money(p.monthlyPrice?.amount ?? p.priceAmount)}/mo</option>`
  ).join("");
  document.getElementById("ns-plan").innerHTML = optionsHtml;
  document.getElementById("ch-plan").innerHTML = optionsHtml;
}

// -------- Subscriptions --------

async function loadSubscriptions() {
  try {
    SUBS = await Api.get("/api/subscriptions");
    renderSubs();
  } catch (e) {
    document.getElementById("subs-body").innerHTML = emptyRow(7, "Failed to load subscriptions: " + e.message);
  }
}

function renderSubs() {
  const tbody = document.getElementById("subs-body");
  if (!SUBS.length) {
    tbody.innerHTML = emptyRow(7, "No subscriptions yet. Create one to get started.");
    return;
  }
  tbody.innerHTML = SUBS.map(s => {
    const isSelected = SELECTED_SUB && SELECTED_SUB.id === s.id;
    return `
      <tr data-sub-id="${s.id}" style="${isSelected ? "background: var(--primary-soft);" : ""}cursor:pointer"
          onclick="selectSub(${s.id})">
        <td class="mono">#${s.id}</td>
        <td>${escape(s.customerId)}</td>
        <td><span class="badge badge-primary">${escape(s.planId)}</span></td>
        <td class="mono">${escape(s.periodStart)} → ${escape(s.periodEnd)}</td>
        <td>${statusBadge(s.status)}</td>
        <td class="mono">${s.pendingPlanId ? escape(s.pendingPlanId) : "—"}</td>
        <td>
          <button class="btn btn-ghost btn-sm" onclick="event.stopPropagation(); selectSub(${s.id})">View</button>
        </td>
      </tr>`;
  }).join("");
}

function statusBadge(status) {
  const map = {
    ACTIVE: "badge-success",
    CANCELED: "badge-danger",
  };
  return `<span class="badge ${map[status] || ""}">${escape(status)}</span>`;
}

function selectSub(id) {
  SELECTED_SUB = SUBS.find(s => s.id === id);
  if (!SELECTED_SUB) return;
  renderSubs();
  const tag = `#${SELECTED_SUB.id} · ${SELECTED_SUB.customerId} · ${SELECTED_SUB.planId}`;
  document.getElementById("change-target").textContent = tag;
  document.getElementById("cancel-target").textContent = tag;
  document.getElementById("invoices-target").textContent = tag;
  const isActive = SELECTED_SUB.status === "ACTIVE";
  document.getElementById("ch-submit").disabled = !isActive;
  document.getElementById("cn-submit").disabled = !isActive;
  loadInvoices(id);
}

// -------- Create --------

async function createSubscription(ev) {
  ev.preventDefault();
  const btn = ev.target.querySelector("button[type=submit]");
  setLoading(btn, true);
  try {
    const sub = await Api.post("/api/subscriptions", {
      customerId: $("#ns-customer").value.trim(),
      planId: $("#ns-plan").value,
      today: $("#ns-today").value,
    });
    Toast.success("Subscription created", `#${sub.id} on ${sub.planId}`);
    $("#ns-customer").value = "";
    await loadSubscriptions();
    selectSub(sub.id);
  } catch (e) {
    Toast.error("Could not create subscription", e.message);
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Change plan --------

async function changePlan(ev) {
  ev.preventDefault();
  if (!SELECTED_SUB) return false;
  const btn = document.getElementById("ch-submit");
  setLoading(btn, true);
  try {
    const invoice = await Api.post(`/api/subscriptions/${SELECTED_SUB.id}/change-plan`, {
      newPlanId: $("#ch-plan").value,
      strategy: $("#ch-strategy").value,
      today: $("#ch-today").value,
    });
    Toast.success("Plan change applied", `Invoice #${invoice.id} · ${Fmt.money(invoice.total)}`);
    await loadSubscriptions();
    selectSub(SELECTED_SUB.id);
  } catch (e) {
    Toast.error("Plan change failed", e.message);
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Cancel --------

async function cancelSub(ev) {
  ev.preventDefault();
  if (!SELECTED_SUB) return false;
  if (!confirm(`Cancel subscription #${SELECTED_SUB.id}? Unused days will be credited to the customer.`)) {
    return false;
  }
  const btn = document.getElementById("cn-submit");
  setLoading(btn, true);
  try {
    const invoice = await Api.post(`/api/subscriptions/${SELECTED_SUB.id}/cancel`, {
      today: $("#cn-today").value,
    });
    Toast.success("Subscription canceled", `Credit memo #${invoice.id}`);
    await loadSubscriptions();
    selectSub(SELECTED_SUB.id);
  } catch (e) {
    Toast.error("Cancellation failed", e.message);
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Invoices --------

async function loadInvoices(subId) {
  const area = document.getElementById("invoices-area");
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading invoices…</div>';
  try {
    const invoices = await Api.get(`/api/subscriptions/${subId}/invoices`);
    renderInvoices(invoices);
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
}

function renderInvoices(invoices) {
  const area = document.getElementById("invoices-area");
  if (!invoices.length) {
    area.innerHTML = '<div class="empty-state">No invoices yet.</div>';
    return;
  }
  area.innerHTML = invoices.map(inv => `
    <div class="card" style="margin-bottom:12px; box-shadow:none; background: var(--surface-muted);">
      <div class="card-header">
        <div>
          <div style="font-weight:600">Invoice #${inv.id}</div>
          <div class="card-subtitle">${escape(inv.status)} · ${escape(inv.currency)}</div>
        </div>
        <div style="text-align:right">
          <div class="text-sm text-muted">Total</div>
          <div style="font-size:18px; font-weight:700">${Fmt.moneyHtml(inv.total, inv.currency)}</div>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Description</th>
              <th>Plan</th>
              <th>Period</th>
              <th>Type</th>
              <th class="num">Amount</th>
            </tr>
          </thead>
          <tbody>
            ${inv.lineItems.map(li => `
              <tr>
                <td>${escape(li.description)}</td>
                <td>${li.planId ? `<span class="badge">${escape(li.planId)}</span>` : "—"}</td>
                <td class="mono">${li.periodStart ? escape(li.periodStart) + " → " + escape(li.periodEnd) : "—"}</td>
                <td>${escape(li.type)}</td>
                <td class="num">${Fmt.moneyHtml(li.amount, inv.currency)}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    </div>
  `).join("");
}

// -------- Credit balance --------

async function lookupCredit(ev) {
  ev.preventDefault();
  const id = $("#cb-customer").value.trim();
  const result = $("#credit-result");
  result.innerHTML = '<span class="spinner"></span>';
  try {
    const c = await Api.get(`/api/customers/${encodeURIComponent(id)}/credit-balance`);
    result.innerHTML = `
      <dl class="kv">
        <dt>Customer</dt><dd>${escape(c.customerId)}</dd>
        <dt>Currency</dt><dd>${escape(c.currency)}</dd>
        <dt>Balance</dt><dd>${Fmt.moneyHtml(c.balance, c.currency)}</dd>
      </dl>`;
  } catch (e) {
    result.innerHTML = `<div class="text-sm" style="color:var(--danger)">${escape(e.message)}</div>`;
  }
  return false;
}
