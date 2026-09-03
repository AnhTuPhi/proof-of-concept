// ===================================================================
//  Dunning — page controller
// ===================================================================

let SUBS = [];
let SELECTED = null;

document.addEventListener("DOMContentLoaded", async () => {
  const today = Fmt.today();
  $("#tick-date").value = today;
  $("#renew-date").value = today;
  $("#pm-today").value = today;
  $("#pm-exp").value = today.replace(/^\d{4}/, m => String(Number(m) + 4));
  await loadAll();
});

async function loadAll() {
  await Promise.all([loadSubs(), loadNotifications()]);
  if (SELECTED) {
    const refreshed = SUBS.find(s => s.id === SELECTED.id);
    if (refreshed) {
      SELECTED = refreshed;
      await refreshSelectedPanels();
    }
  }
}

// -------- Subscriptions --------

async function loadSubs() {
  try {
    SUBS = await Api.get("/api/subscriptions");
    renderSubs();
  } catch (e) {
    $("#subs-body").innerHTML = emptyRow(8, "Failed: " + e.message);
  }
}

function renderSubs() {
  const tbody = $("#subs-body");
  if (!SUBS.length) {
    tbody.innerHTML = emptyRow(8, "No subscriptions yet.");
    return;
  }
  tbody.innerHTML = SUBS.map(s => {
    const isSelected = SELECTED && SELECTED.id === s.id;
    return `
      <tr style="cursor:pointer; ${isSelected ? "background:var(--primary-soft);" : ""}"
          onclick="selectSub(${s.id})">
        <td class="mono">#${s.id}</td>
        <td>${escape(s.customerId)}</td>
        <td>${escape(s.planName)}</td>
        <td class="num">${Fmt.money(s.monthlyAmount, s.currency || "USD")}</td>
        <td>${statusBadge(s.status)}</td>
        <td class="mono">${escape(s.nextRenewalOn)}</td>
        <td class="mono">${s.scheduledSuspensionOn ? escape(s.scheduledSuspensionOn) : "—"}</td>
        <td>
          <button class="btn btn-ghost btn-sm" onclick="event.stopPropagation(); selectSub(${s.id})">View</button>
        </td>
      </tr>`;
  }).join("");
}

function statusBadge(status) {
  const map = {
    ACTIVE: "badge-success",
    PAST_DUE: "badge-warning",
    SUSPENDED: "badge-danger",
    CANCELED: "badge",
  };
  return `<span class="badge ${map[status] || ""}">${escape(status)}</span>`;
}

function selectSub(id) {
  SELECTED = SUBS.find(s => s.id === id);
  if (!SELECTED) return;
  renderSubs();
  const tag = `#${SELECTED.id} · ${SELECTED.customerId} · ${SELECTED.planName}`;
  $("#renew-target").textContent = tag;
  $("#pm-target").textContent = tag;
  $("#attempts-target").textContent = tag;
  $("#events-target").textContent = tag;
  const canAct = SELECTED.status !== "CANCELED";
  $("#renew-btn").disabled = !canAct;
  $("#pm-submit").disabled = !canAct;
  refreshSelectedPanels();
}

async function refreshSelectedPanels() {
  if (!SELECTED) return;
  await Promise.all([loadAttempts(SELECTED.id), loadEvents(SELECTED.id), loadCurrentPm(SELECTED.paymentMethodId)]);
}

// -------- Renewal attempts --------

async function loadAttempts(subId) {
  const area = $("#attempts-area");
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading…</div>';
  try {
    const attempts = await Api.get(`/api/subscriptions/${subId}/attempts`);
    if (!attempts.length) {
      area.innerHTML = '<div class="empty-state">No charge attempts yet.</div>';
      return;
    }
    area.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>#</th><th>Scheduled</th><th>Ran</th><th>Status</th><th>Reason</th></tr>
          </thead>
          <tbody>
            ${attempts.map(a => `
              <tr>
                <td class="mono">${a.attemptNumber}</td>
                <td class="mono">${escape(a.scheduledOn)}</td>
                <td class="mono">${a.ranOn ? escape(a.ranOn) : "—"}</td>
                <td>${attemptStatus(a.status)}</td>
                <td class="text-sm text-muted">${a.failureReason ? escape(a.failureReason) : "—"}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>`;
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
}

function attemptStatus(s) {
  const map = {
    SCHEDULED: "badge-info",
    SUCCEEDED: "badge-success",
    FAILED: "badge-danger",
  };
  return `<span class="badge ${map[s] || ""}">${escape(s)}</span>`;
}

// -------- Dunning events timeline --------

async function loadEvents(subId) {
  const area = $("#events-area");
  area.innerHTML = '<div class="empty-state"><span class="spinner"></span> Loading…</div>';
  try {
    const events = await Api.get(`/api/subscriptions/${subId}/dunning-events`);
    if (!events.length) {
      area.innerHTML = '<div class="empty-state">No events yet.</div>';
      return;
    }
    area.innerHTML = `
      <div style="display:flex; flex-direction:column; gap:8px">
        ${events.map(e => `
          <div style="display:flex; gap:12px; padding:10px 12px; background:var(--surface-muted); border-radius:var(--radius-sm); align-items:start">
            <div style="min-width:90px">
              <div class="text-sm mono">${escape(e.occurredOn)}</div>
            </div>
            <div style="flex:1">
              <div>${eventBadge(e.type)}</div>
              <div class="text-sm text-muted mt-2">${e.details ? escape(e.details) : ""}</div>
            </div>
          </div>
        `).join("")}
      </div>`;
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
}

function eventBadge(type) {
  const map = {
    PAYMENT_SUCCEEDED: "badge-success",
    PAYMENT_FAILED: "badge-danger",
    RETRY_SCHEDULED: "badge-info",
    EMAIL_SENT: "badge",
    SUSPENDED: "badge-warning",
    CANCELED: "badge-danger",
    REACTIVATED: "badge-success",
  };
  return `<span class="badge ${map[type] || ""}">${escape(type)}</span>`;
}

// -------- Current payment method --------

async function loadCurrentPm(pmId) {
  if (!pmId) return;
  try {
    const pm = await Api.get(`/api/payment-methods/${pmId}`);
    $("#pm-brand").value = pm.brand;
    $("#pm-last4").value = pm.last4;
    $("#pm-exp").value = pm.expiresOn;
  } catch { /* ignore — endpoint optional */ }
}

// -------- Renew --------

async function renewSelected() {
  if (!SELECTED) return;
  const today = $("#renew-date").value;
  const btn = $("#renew-btn");
  setLoading(btn, true);
  try {
    const attempt = await Api.post(`/api/subscriptions/${SELECTED.id}/renew?today=${today}`);
    if (attempt.status === "SUCCEEDED") {
      Toast.success("Renewal succeeded", `Attempt #${attempt.attemptNumber}`);
    } else {
      Toast.warning("Renewal failed", `Reason: ${attempt.failureReason}. Retries scheduled.`);
    }
    await loadAll();
  } catch (e) {
    Toast.error("Renewal request failed", e.message);
  } finally {
    setLoading(btn, false);
  }
}

// -------- Update payment method --------

async function updatePaymentMethod(ev) {
  ev.preventDefault();
  if (!SELECTED) return false;
  const btn = $("#pm-submit");
  setLoading(btn, true);
  try {
    const sub = await Api.post(`/api/subscriptions/${SELECTED.id}/payment-method`, {
      brand: $("#pm-brand").value,
      last4: $("#pm-last4").value,
      expiresOn: $("#pm-exp").value,
      today: $("#pm-today").value,
    });
    Toast.success("Payment method updated", `Status: ${sub.status}`);
    await loadAll();
  } catch (e) {
    Toast.error("Update failed", e.message);
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Tick clock --------

async function tickClock() {
  const today = $("#tick-date").value;
  try {
    const summary = await Api.post(`/api/dunning/tick?today=${today}`);
    Toast.info(
      `Tick → ${summary.runAt}`,
      `${summary.retriesRun} retries, ${summary.suspended} suspended, ${summary.canceled} canceled`
    );
    $("#tick-result").textContent =
      `Last tick: ${summary.runAt} — ${summary.retriesRun} retries / ${summary.suspended} suspensions / ${summary.canceled} cancellations`;
    await loadAll();
  } catch (e) {
    Toast.error("Tick failed", e.message);
  }
}

// -------- Notifications --------

async function loadNotifications() {
  try {
    const items = await Api.get("/api/notifications");
    const tbody = $("#notif-body");
    if (!items.length) {
      tbody.innerHTML = emptyRow(4, "No notifications sent yet.");
      return;
    }
    tbody.innerHTML = items.map((n, i) => `
      <tr>
        <td class="mono">${i + 1}</td>
        <td class="mono">#${n.subscriptionId}</td>
        <td>${templateBadge(n.template)}</td>
        <td class="mono">${escape(n.to)}</td>
      </tr>
    `).join("");
  } catch (e) {
    $("#notif-body").innerHTML = emptyRow(4, "Failed: " + e.message);
  }
}

function templateBadge(template) {
  const map = {
    retry_scheduled: "badge-info",
    final_warning: "badge-warning",
    grace_period: "badge-warning",
    service_suspended: "badge-danger",
  };
  return `<span class="badge ${map[template] || ""}">${escape(template)}</span>`;
}
