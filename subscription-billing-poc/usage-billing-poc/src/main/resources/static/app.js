// ===================================================================
//  Usage Billing — page controller
// ===================================================================

document.addEventListener("DOMContentLoaded", async () => {
  const today = Fmt.today();
  const firstOfMonth = today.slice(0, 8) + "01";
  const firstOfNextMonth = nextMonthFirst(today);

  $("#ev-when").value = today + "T12:00";
  $("#bk-day").value = today;
  $("#ru-day").value = today;
  $("#iv-start").value = firstOfMonth;
  $("#iv-end").value = firstOfNextMonth;
  $("#rc-start").value = firstOfMonth;
  $("#rc-end").value = firstOfNextMonth;

  await Promise.all([loadTiers(), loadRecent()]);
});

function nextMonthFirst(isoDate) {
  const d = new Date(isoDate + "T00:00:00Z");
  const next = new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1));
  return next.toISOString().slice(0, 10);
}

// -------- Pricing tiers --------

async function loadTiers() {
  const area = $("#tiers-area");
  try {
    const tiers = await Api.get("/api/pricing/tiers");
    if (!tiers.length) {
      area.innerHTML = '<div class="empty-state">No tiers configured.</div>';
      return;
    }
    const grouped = groupBy(tiers, t => t.metric);
    area.innerHTML = Object.entries(grouped).map(([metric, ts]) => `
      <div class="mt-3">
        <div style="font-weight:600; margin-bottom:6px">${escape(metric)}</div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr><th>Tier</th><th class="num">From</th><th class="num">To</th><th class="num">Price / unit</th></tr>
            </thead>
            <tbody>
              ${ts.map(t => `
                <tr>
                  <td class="mono">#${t.tierOrder}</td>
                  <td class="num">${Fmt.number(t.tierStart)}</td>
                  <td class="num">${t.tierEnd === null || t.tierEnd === undefined ? "∞" : Fmt.number(t.tierEnd)}</td>
                  <td class="num">${Fmt.money(t.pricePerUnit, t.currency || "USD")}</td>
                </tr>
              `).join("")}
            </tbody>
          </table>
        </div>
      </div>
    `).join("");
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
}

function groupBy(arr, fn) {
  return arr.reduce((acc, item) => {
    const k = fn(item);
    (acc[k] ||= []).push(item);
    return acc;
  }, {});
}

// -------- Single ingest --------

async function ingestOne(ev) {
  ev.preventDefault();
  const btn = ev.target.querySelector("button[type=submit]");
  setLoading(btn, true);
  try {
    let eventId = $("#ev-id").value.trim();
    if (!eventId) eventId = "ui-" + Date.now() + "-" + Math.random().toString(36).slice(2, 8);
    const isoWhen = $("#ev-when").value + ":00Z";
    const res = await Api.post("/api/usage/events", {
      eventId,
      customerId: $("#ev-customer").value.trim(),
      metric: $("#ev-metric").value,
      quantity: $("#ev-qty").value,
      occurredAt: isoWhen,
    });
    if (res.duplicate) {
      Toast.warning("Duplicate event", "Same eventId — request was deduped.");
    } else {
      Toast.success("Event ingested", `id=${res.id} eventId=${res.eventId}`);
    }
    await loadRecent();
  } catch (e) {
    Toast.error("Ingestion failed", e.message);
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Bulk ingest --------

async function bulkIngest(ev) {
  ev.preventDefault();
  const customer = $("#bk-customer").value.trim();
  const metric = $("#bk-metric").value;
  const total = parseFloat($("#bk-total").value);
  const day = $("#bk-day").value;
  const chunks = parseInt($("#bk-chunks").value, 10);
  const perChunk = total / chunks;
  const btn = $("#bk-submit");
  setLoading(btn, true);
  let ok = 0, dup = 0, fail = 0;
  const stamp = Date.now();
  try {
    for (let i = 0; i < chunks; i++) {
      try {
        const res = await Api.post("/api/usage/events", {
          eventId: `bulk-${customer}-${day}-${stamp}-${i}`,
          customerId: customer,
          metric,
          quantity: i === chunks - 1 ? total - perChunk * (chunks - 1) : perChunk,
          occurredAt: day + "T12:00:00Z",
        });
        if (res.duplicate) dup++; else ok++;
      } catch {
        fail++;
      }
    }
    Toast.success("Bulk ingest complete", `${ok} new · ${dup} dedup · ${fail} failed`);
    await loadRecent();
  } finally {
    setLoading(btn, false);
  }
  return false;
}

// -------- Rollup --------

async function runRollup(ev) {
  ev.preventDefault();
  const customer = $("#ru-customer").value.trim();
  const day = $("#ru-day").value;
  const area = $("#rollup-result");
  area.innerHTML = '<span class="spinner"></span>';
  try {
    const rollups = await Api.post(
      `/api/usage/rollup?customerId=${encodeURIComponent(customer)}&day=${day}`);
    if (!rollups.length) {
      area.innerHTML = '<div class="empty-state">No events for that day.</div>';
      return false;
    }
    area.innerHTML = `
      <div class="table-wrap">
        <table>
          <thead>
            <tr><th>Metric</th><th class="num">Quantity</th><th class="num">Events</th></tr>
          </thead>
          <tbody>
            ${rollups.map(r => `
              <tr>
                <td>${escape(r.metric)}</td>
                <td class="num">${Fmt.number(r.totalQuantity)}</td>
                <td class="num">${r.eventCount}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>`;
    Toast.success("Rollup complete", `${rollups.length} metric row(s) written.`);
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
  return false;
}

// -------- Recent events --------

async function loadRecent() {
  const tbody = $("#events-body");
  try {
    const items = await Api.get("/api/usage/recent?limit=50");
    if (!items.length) {
      tbody.innerHTML = emptyRow(6, "No events ingested yet.");
      return;
    }
    tbody.innerHTML = items.map(e => `
      <tr>
        <td class="mono text-sm">${escape(e.eventId)}</td>
        <td>${escape(e.customerId)}</td>
        <td><span class="badge">${escape(e.metric)}</span></td>
        <td class="num">${Fmt.number(e.quantity)}</td>
        <td class="mono text-sm">${Fmt.datetime(e.occurredAt)}</td>
        <td class="mono">${escape(e.usageDay)}</td>
      </tr>
    `).join("");
  } catch (e) {
    tbody.innerHTML = emptyRow(6, "Failed: " + e.message);
  }
}

// -------- Invoice --------

async function generateInvoice(ev) {
  ev.preventDefault();
  const customer = $("#iv-customer").value.trim();
  const start = $("#iv-start").value;
  const end = $("#iv-end").value;
  const area = $("#invoice-area");
  area.innerHTML = '<span class="spinner"></span>';
  try {
    const invoice = await Api.post(
      `/api/billing/invoices?customerId=${encodeURIComponent(customer)}&periodStart=${start}&periodEnd=${end}`);
    area.innerHTML = renderInvoice(invoice);
    Toast.success("Invoice generated", `Total ${Fmt.money(totalOf(invoice), invoice.currency)}`);
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
  return false;
}

function totalOf(invoice) {
  return (invoice.lineItems || []).reduce((a, li) => a + Number(li.amount || 0), 0);
}

function renderInvoice(invoice) {
  const items = invoice.lineItems || [];
  if (!items.length) return '<div class="empty-state">Invoice has no line items (no usage in period).</div>';
  return `
    <div class="card" style="background:var(--surface-muted); box-shadow:none; margin:0">
      <dl class="kv">
        <dt>Invoice #</dt><dd>${invoice.id}</dd>
        <dt>Period</dt><dd>${escape(invoice.periodStart)} → ${escape(invoice.periodEnd)}</dd>
        <dt>Total</dt><dd>${Fmt.moneyHtml(totalOf(invoice), invoice.currency)}</dd>
      </dl>
      <div class="table-wrap mt-3">
        <table>
          <thead>
            <tr><th>Line</th><th class="num">Qty</th><th class="num">Unit</th><th class="num">Amount</th></tr>
          </thead>
          <tbody>
            ${items.map(li => `
              <tr>
                <td>${escape(li.description)}</td>
                <td class="num">${Fmt.number(li.quantity)}</td>
                <td class="num">${Fmt.money(li.pricePerUnit, invoice.currency)}</td>
                <td class="num">${Fmt.moneyHtml(li.amount, invoice.currency)}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>
    </div>`;
}

// -------- Reconciliation --------

async function runReconcile(ev) {
  ev.preventDefault();
  const customer = $("#rc-customer").value.trim();
  const start = $("#rc-start").value;
  const end = $("#rc-end").value;
  const area = $("#reconcile-area");
  area.innerHTML = '<span class="spinner"></span>';
  try {
    const r = await Api.get(
      `/api/billing/reconcile?customerId=${encodeURIComponent(customer)}&periodStart=${start}&periodEnd=${end}`);
    area.innerHTML = `
      <div class="flex items-center justify-between" style="margin-bottom:8px">
        ${r.matches
          ? '<span class="badge badge-success">Matches ✓</span>'
          : '<span class="badge badge-danger">Drift detected ✗</span>'}
        <span class="text-sm text-muted">Δ qty: ${Fmt.number(r.totalQuantityDelta)} · Δ events: ${r.totalEventDelta}</span>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Metric</th>
              <th class="num">Raw Total</th>
              <th class="num">Rollup Total</th>
              <th class="num">Qty Δ</th>
              <th class="num">Raw #</th>
              <th class="num">Rollup #</th>
              <th class="num">Event Δ</th>
            </tr>
          </thead>
          <tbody>
            ${r.lines.map(l => `
              <tr>
                <td>${escape(l.metric)}</td>
                <td class="num">${Fmt.number(l.rawTotal)}</td>
                <td class="num">${Fmt.number(l.rollupTotal)}</td>
                <td class="num" style="color:${Number(l.quantityDelta) === 0 ? "inherit" : "var(--danger)"}">${Fmt.number(l.quantityDelta)}</td>
                <td class="num">${l.rawEventCount}</td>
                <td class="num">${l.rollupEventCount}</td>
                <td class="num" style="color:${l.eventCountDelta === 0 ? "inherit" : "var(--danger)"}">${l.eventCountDelta}</td>
              </tr>
            `).join("")}
          </tbody>
        </table>
      </div>`;
  } catch (e) {
    area.innerHTML = `<div class="empty-state">Failed: ${escape(e.message)}</div>`;
  }
  return false;
}
