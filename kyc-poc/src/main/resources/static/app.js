/* ============================================================
   KYC Workflow Console — front-end
   ============================================================ */

const API = "/api/kyc";
const POLL_MS = 2000;

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

let lastStatsJson = "";
let lastCasesJson = "";
let lastReviewJson = "";
let lastDlqJson = "";
let openCaseId = null;

/* ---------------- HTTP helper ---------------- */
async function api(path, { method = "GET", body } = {}) {
  const res = await fetch(API + path, {
    method,
    headers: body ? { "Content-Type": "application/json" } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const msg = data?.message || res.statusText;
    throw new Error(msg);
  }
  return data;
}

/* ---------------- Toast ---------------- */
function toast(msg, kind = "") {
  const el = document.createElement("div");
  el.className = "toast" + (kind ? " toast-" + kind : "");
  el.textContent = msg;
  $("#toast-host").appendChild(el);
  setTimeout(() => {
    el.style.opacity = "0";
    el.style.transition = "opacity 200ms";
    setTimeout(() => el.remove(), 220);
  }, 3500);
}

/* ---------------- Tabs ---------------- */
$$(".tab").forEach((t) => {
  t.addEventListener("click", () => {
    $$(".tab").forEach((x) => x.classList.remove("tab-active"));
    t.classList.add("tab-active");
    const target = t.dataset.tab;
    $$(".panel").forEach((p) => p.classList.add("hidden"));
    $("#tab-" + target).classList.remove("hidden");
    if (target === "diagram") drawDiagram();
  });
});

/* ---------------- Forms ---------------- */
$("#form-register").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    const data = await api("/register", {
      method: "POST",
      body: {
        fullName: f.get("fullName"),
        email: f.get("email"),
        declaredAddress: f.get("declaredAddress"),
      },
    });
    $("#upload-case-id").value = data.id;
    showLastAction(data);
    toast("Registered " + data.id, "good");
    refresh();
  } catch (err) {
    toast("Register failed: " + err.message, "bad");
  }
});

$("#form-upload").addEventListener("submit", async (e) => {
  e.preventDefault();
  const f = new FormData(e.target);
  try {
    const data = await api("/" + f.get("caseId") + "/upload", {
      method: "POST",
      body: {
        fileName: f.get("fileName"),
        sizeBytes: Number(f.get("sizeBytes")),
      },
    });
    showLastAction(data);
    toast("Uploaded — async pipeline started", "good");
    refresh();
  } catch (err) {
    toast("Upload failed: " + err.message, "bad");
  }
});

function showLastAction(data) {
  $("#last-case-card").style.display = "block";
  $("#last-case-json").textContent = JSON.stringify(data, null, 2);
}

/* ---------------- Stats ---------------- */
async function refreshStats() {
  try {
    const s = await api("/stats");
    const key = JSON.stringify(s);
    if (key === lastStatsJson) return;
    lastStatsJson = key;
    $("#stat-total").textContent = s.totalCases;
    $("#stat-approved").textContent = s.approved;
    $("#stat-rejected").textContent = s.rejected;
    $("#stat-pending").textContent = s.pendingReview;
    $("#stat-inprogress").textContent = s.inProgress;
    $("#stat-dlq").textContent = s.deadLetter;
    $("#badge-review").textContent = s.pendingReview;
    $("#badge-dlq").textContent = s.deadLetter;
  } catch (e) {
    // swallow
  }
}

/* ---------------- All cases ---------------- */
function isActive(state) {
  return !["APPROVED", "REJECTED", "DEAD_LETTER"].includes(state);
}

async function refreshCases() {
  try {
    const cases = await api("");
    const key = JSON.stringify(cases.map((c) => [c.id, c.state, c.updatedAt]));
    if (key === lastCasesJson) return;
    lastCasesJson = key;
    const activeOnly = $("#filter-active").checked;
    const filtered = activeOnly ? cases.filter((c) => isActive(c.state)) : cases;
    $("#cases-count").textContent = `· ${filtered.length} ${activeOnly ? "active" : "total"}`;
    renderCaseList("#cases-list", filtered);
  } catch (e) {}
}
$("#filter-active").addEventListener("change", () => {
  lastCasesJson = ""; // force re-render
  refreshCases();
});

async function refreshReview() {
  try {
    const cases = await api("/queue/manual-review");
    const key = JSON.stringify(cases.map((c) => c.id));
    if (key === lastReviewJson) return;
    lastReviewJson = key;
    renderCaseList("#review-list", cases, { showReview: true });
  } catch (e) {}
}

async function refreshDlq() {
  try {
    const entries = await api("/dlq");
    const key = JSON.stringify(entries.map((e) => [e.id, e.replayed]));
    if (key === lastDlqJson) return;
    lastDlqJson = key;
    renderDlq(entries);
  } catch (e) {}
}

function renderCaseList(target, cases, opts = {}) {
  const host = $(target);
  if (!cases || cases.length === 0) {
    host.innerHTML = `<div class="empty">No cases here.</div>`;
    return;
  }
  host.innerHTML = "";
  cases.forEach((c) => {
    const row = document.createElement("div");
    row.className = "case-row";
    row.innerHTML = `
      <div class="case-row-main">
        <div class="case-row-line1">
          <span class="case-row-name">${escapeHtml(c.fullName)}</span>
          ${statePill(c.state)}
          <span class="case-row-id">${c.id}</span>
        </div>
        <div class="case-row-meta">
          <strong>${escapeHtml(c.email)}</strong> · ${escapeHtml(c.declaredAddress)}
        </div>
        <div class="case-row-meta">
          OCR attempts: ${c.ocrAttempts} · Addr attempts: ${c.addressAttempts} · Updated: ${fmtTime(c.updatedAt)}
        </div>
      </div>
      <div class="case-row-side">
        ${opts.showReview ? `
          <button class="btn btn-good btn-sm" data-act="approve" data-id="${c.id}">Approve</button>
          <button class="btn btn-bad btn-sm" data-act="reject" data-id="${c.id}">Reject</button>
        ` : ""}
        <button class="btn btn-ghost btn-sm" data-act="open" data-id="${c.id}">Details</button>
      </div>
    `;
    host.appendChild(row);
  });

  host.querySelectorAll("[data-act]").forEach((b) => {
    b.addEventListener("click", (e) => {
      e.stopPropagation();
      const id = b.dataset.id;
      const act = b.dataset.act;
      if (act === "open") openCaseModal(id);
      if (act === "approve") doReview(id, "APPROVE");
      if (act === "reject") doReview(id, "REJECT");
    });
  });
  host.querySelectorAll(".case-row").forEach((row) => {
    row.addEventListener("click", () => {
      const id = row.querySelector("[data-act='open']")?.dataset.id;
      if (id) openCaseModal(id);
    });
  });
}

function renderDlq(entries) {
  const host = $("#dlq-list");
  if (!entries || entries.length === 0) {
    host.innerHTML = `<div class="empty">DLQ is empty.</div>`;
    return;
  }
  host.innerHTML = "";
  entries.forEach((e) => {
    const row = document.createElement("div");
    row.className = "case-row";
    row.innerHTML = `
      <div class="case-row-main">
        <div class="case-row-line1">
          <span class="case-row-name">${e.stepName} failed</span>
          ${statePill(e.failedAtState)}
          <span class="case-row-id">${e.id} → ${e.caseId}</span>
          ${e.replayed ? `<span class="pill pill-APPROVED">REPLAYED</span>` : ""}
        </div>
        <div class="case-row-meta"><strong>Error:</strong> ${escapeHtml(e.lastError)}</div>
        <div class="case-row-meta">Attempts: ${e.attempts} · Moved at: ${fmtTime(e.movedAt)}</div>
      </div>
      <div class="case-row-side">
        <button class="btn btn-ghost btn-sm" data-act="view" data-cid="${e.caseId}">View case</button>
        ${e.replayed ? "" : `<button class="btn btn-primary btn-sm" data-act="replay" data-id="${e.id}">Replay</button>`}
      </div>
    `;
    host.appendChild(row);
  });
  host.querySelectorAll("[data-act]").forEach((b) => {
    b.addEventListener("click", async (ev) => {
      ev.stopPropagation();
      if (b.dataset.act === "view") openCaseModal(b.dataset.cid);
      if (b.dataset.act === "replay") {
        try {
          await api(`/dlq/${b.dataset.id}/replay?actor=admin`, { method: "POST" });
          toast("Replay submitted — pipeline restarted", "good");
          lastDlqJson = "";
          refresh();
        } catch (err) {
          toast("Replay failed: " + err.message, "bad");
        }
      }
    });
  });
}

/* ---------------- Modal / case detail ---------------- */
async function openCaseModal(id) {
  openCaseId = id;
  try {
    const c = await api("/" + id);
    renderCaseModal(c);
    $("#modal-backdrop").classList.remove("hidden");
  } catch (err) {
    toast("Load case failed: " + err.message, "bad");
  }
}

function renderCaseModal(c) {
  $("#modal-title").textContent = c.fullName + " · " + c.id;
  const body = $("#modal-body");

  const ocr = c.ocrResult;
  const addr = c.addressResult;

  const ocrBlock = ocr
    ? ocr.success
      ? `<div class="kv">
           <div class="k">Confidence</div><div class="v">${(ocr.confidence * 100).toFixed(1)}%</div>
           <div class="k">ID number</div><div class="v">${escapeHtml(ocr.idNumber)}</div>
           <div class="k">Date of birth</div><div class="v">${escapeHtml(ocr.dateOfBirth)}</div>
           <div class="k">Address (from ID)</div><div class="v">${escapeHtml(ocr.addressFromId)}</div>
         </div>`
      : `<div class="kv"><div class="k">Status</div><div class="v" style="color:var(--bad)">${escapeHtml(ocr.failureReason)}</div></div>`
    : `<div class="muted">No OCR yet.</div>`;

  const addrBlock = addr
    ? addr.success
      ? `<div class="kv">
           <div class="k">Match</div><div class="v">${addr.matched ? "✓ Matched" : "✗ Did not match"}</div>
           <div class="k">Score</div><div class="v">${(addr.score * 100).toFixed(1)}%</div>
         </div>`
      : `<div class="kv"><div class="k">Status</div><div class="v" style="color:var(--bad)">${escapeHtml(addr.failureReason)}</div></div>`
    : `<div class="muted">No address verification yet.</div>`;

  const docsBlock = c.documents.length
    ? `<table style="width:100%;font-size:12px;border-collapse:collapse">
        <tr style="color:var(--text-muted);text-align:left">
          <th style="padding:4px 6px">File</th><th>Type</th><th>Size</th><th>S3 ref</th>
        </tr>
        ${c.documents.map((d) => `
          <tr style="border-top:1px solid var(--border-soft)">
            <td style="padding:6px">${escapeHtml(d.fileName)}</td>
            <td>${d.docType}</td>
            <td>${d.sizeBytes.toLocaleString()} B</td>
            <td><code>${escapeHtml(d.s3Reference)}</code></td>
          </tr>`).join("")}
      </table>`
    : `<div class="muted">No documents uploaded.</div>`;

  const timeline = c.auditLog
    .slice()
    .reverse()
    .map((a) => {
      const cls =
        a.event === "MANUAL_APPROVE" || a.event === "OCR_SUCCESS" || a.event === "ADDRESS_VERIFY_SUCCESS"
          ? "tl-good"
          : a.event === "OCR_FAILURE" || a.event === "ADDRESS_VERIFY_FAILURE" || a.event === "MOVE_TO_DLQ" || a.event === "MANUAL_REJECT"
          ? "tl-fail"
          : "";
      return `
        <div class="tl-row ${cls}">
          <div class="tl-time">${fmtTime(a.timestamp)}</div>
          <div class="tl-main">
            ${a.fromState ? `<code>${a.fromState}</code> → ` : ""}
            <code>${a.toState}</code>
            ${a.event ? ` <span class="muted">(${a.event})</span>` : ""}
          </div>
          <div class="tl-detail">${escapeHtml(a.actor)} — ${escapeHtml(a.detail || "")}</div>
        </div>`;
    })
    .join("");

  let reviewControls = "";
  if (c.state === "PENDING_MANUAL_REVIEW") {
    reviewControls = `
      <div class="card" style="margin-top:14px;background:var(--bg-elev-2)">
        <div class="card-h"><h3>Reviewer decision</h3></div>
        <label>Note</label>
        <textarea id="rev-note" rows="2" placeholder="e.g. clear photo, address matches">Approved after review</textarea>
        <label>Reviewer</label>
        <input id="rev-name" value="alice@compliance" />
        <div style="display:flex;gap:8px">
          <button class="btn btn-good" id="rev-approve">Approve</button>
          <button class="btn btn-bad" id="rev-reject">Reject</button>
        </div>
      </div>`;
  }

  body.innerHTML = `
    <div class="kv">
      <div class="k">State</div><div class="v">${statePill(c.state)}<span class="muted" style="margin-left:8px">${escapeHtml(c.stateDescription)}</span></div>
      <div class="k">Email</div><div class="v">${escapeHtml(c.email)}</div>
      <div class="k">Declared address</div><div class="v">${escapeHtml(c.declaredAddress)}</div>
      <div class="k">Created</div><div class="v">${fmtTime(c.createdAt)}</div>
      <div class="k">Updated</div><div class="v">${fmtTime(c.updatedAt)}</div>
      ${c.rejectionReason ? `<div class="k">Rejection reason</div><div class="v" style="color:var(--bad)">${escapeHtml(c.rejectionReason)}</div>` : ""}
      ${c.reviewerNote ? `<div class="k">Reviewer note</div><div class="v">${escapeHtml(c.reviewerNote)}</div>` : ""}
    </div>

    <div class="section-h">OCR result (attempts: ${c.ocrAttempts})</div>
    ${ocrBlock}

    <div class="section-h">Address verification (attempts: ${c.addressAttempts})</div>
    ${addrBlock}

    <div class="section-h">Documents</div>
    ${docsBlock}

    <div class="section-h">Audit timeline</div>
    <div class="timeline">${timeline || `<div class="muted">No events yet.</div>`}</div>

    ${reviewControls}
  `;

  if (c.state === "PENDING_MANUAL_REVIEW") {
    $("#rev-approve").addEventListener("click", () => submitReview(c.id, "APPROVE"));
    $("#rev-reject").addEventListener("click", () => submitReview(c.id, "REJECT"));
  }
}

async function submitReview(id, decision) {
  const note = $("#rev-note").value.trim() || (decision === "APPROVE" ? "Approved" : "Rejected");
  const reviewer = $("#rev-name").value.trim() || "reviewer";
  try {
    await api(`/${id}/review`, {
      method: "POST",
      body: { decision, reviewerNote: note, reviewer },
    });
    toast(`${decision === "APPROVE" ? "Approved" : "Rejected"} ${id}`, decision === "APPROVE" ? "good" : "bad");
    closeModal();
    refresh();
  } catch (err) {
    toast("Review failed: " + err.message, "bad");
  }
}

async function doReview(id, decision) {
  try {
    await api(`/${id}/review`, {
      method: "POST",
      body: { decision, reviewerNote: decision === "APPROVE" ? "Quick approve" : "Quick reject", reviewer: "queue-reviewer" },
    });
    toast(`${decision === "APPROVE" ? "Approved" : "Rejected"} ${id}`, decision === "APPROVE" ? "good" : "bad");
    refresh();
  } catch (err) {
    toast("Review failed: " + err.message, "bad");
  }
}

function closeModal() {
  openCaseId = null;
  $("#modal-backdrop").classList.add("hidden");
}
$("#modal-close").addEventListener("click", closeModal);
$("#modal-backdrop").addEventListener("click", (e) => {
  if (e.target.id === "modal-backdrop") closeModal();
});

/* ---------------- State diagram ---------------- */
const NODES = [
  { id: "REGISTERED",                       x: 80,   y: 60,  label: "Registered",          kind: "active" },
  { id: "ID_UPLOADED",                      x: 280,  y: 60,  label: "ID Uploaded",         kind: "active" },
  { id: "OCR_IN_PROGRESS",                  x: 480,  y: 60,  label: "OCR In Progress",     kind: "active" },
  { id: "OCR_FAILED",                       x: 480,  y: 200, label: "OCR Failed",          kind: "fail" },
  { id: "OCR_COMPLETED",                    x: 680,  y: 60,  label: "OCR Completed",       kind: "active" },
  { id: "ADDRESS_VERIFICATION_IN_PROGRESS", x: 680,  y: 200, label: "Addr Verifying",      kind: "active" },
  { id: "ADDRESS_VERIFICATION_FAILED",      x: 480,  y: 340, label: "Addr Failed",         kind: "fail" },
  { id: "ADDRESS_VERIFIED",                 x: 880,  y: 200, label: "Addr Verified",       kind: "active" },
  { id: "PENDING_MANUAL_REVIEW",            x: 880,  y: 340, label: "Manual Review",       kind: "active" },
  { id: "APPROVED",                         x: 880,  y: 500, label: "Approved",            kind: "terminal" },
  { id: "REJECTED",                         x: 680,  y: 500, label: "Rejected",            kind: "bad" },
  { id: "DEAD_LETTER",                      x: 280,  y: 500, label: "Dead Letter",         kind: "dlq" },
];

const EDGES = [
  { from: "REGISTERED", to: "ID_UPLOADED", label: "UPLOAD_ID" },
  { from: "ID_UPLOADED", to: "OCR_IN_PROGRESS", label: "START_OCR" },
  { from: "OCR_IN_PROGRESS", to: "OCR_COMPLETED", label: "OCR_SUCCESS" },
  { from: "OCR_IN_PROGRESS", to: "OCR_FAILED", label: "OCR_FAILURE", kind: "fail" },
  { from: "OCR_FAILED", to: "OCR_IN_PROGRESS", label: "RETRY", curve: -40, kind: "retry" },
  { from: "OCR_FAILED", to: "DEAD_LETTER", label: "MOVE_TO_DLQ", kind: "dlq" },
  { from: "OCR_COMPLETED", to: "ADDRESS_VERIFICATION_IN_PROGRESS", label: "START_ADDR" },
  { from: "ADDRESS_VERIFICATION_IN_PROGRESS", to: "ADDRESS_VERIFIED", label: "ADDR_SUCCESS" },
  { from: "ADDRESS_VERIFICATION_IN_PROGRESS", to: "ADDRESS_VERIFICATION_FAILED", label: "ADDR_FAILURE", kind: "fail" },
  { from: "ADDRESS_VERIFICATION_FAILED", to: "ADDRESS_VERIFICATION_IN_PROGRESS", label: "RETRY", curve: 40, kind: "retry" },
  { from: "ADDRESS_VERIFICATION_FAILED", to: "DEAD_LETTER", label: "MOVE_TO_DLQ", kind: "dlq" },
  { from: "ADDRESS_VERIFIED", to: "PENDING_MANUAL_REVIEW", label: "SEND_TO_REVIEW" },
  { from: "PENDING_MANUAL_REVIEW", to: "APPROVED", label: "APPROVE" },
  { from: "PENDING_MANUAL_REVIEW", to: "REJECTED", label: "REJECT", kind: "fail" },
  { from: "DEAD_LETTER", to: "ID_UPLOADED", label: "REPLAY_FROM_DLQ", curve: 120, kind: "retry" },
];

function drawDiagram() {
  const svg = $("#state-diagram");
  svg.innerHTML = "";
  const ns = "http://www.w3.org/2000/svg";

  const defs = document.createElementNS(ns, "defs");
  defs.innerHTML = `
    <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#5b8cff" />
    </marker>
    <marker id="arrow-fail" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#f59e0b" />
    </marker>
    <marker id="arrow-dlq" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#a855f7" />
    </marker>
    <marker id="arrow-retry" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse">
      <path d="M 0 0 L 10 5 L 0 10 z" fill="#06b6d4" />
    </marker>
  `;
  svg.appendChild(defs);

  // Edges
  EDGES.forEach((e) => {
    const a = NODES.find((n) => n.id === e.from);
    const b = NODES.find((n) => n.id === e.to);
    if (!a || !b) return;
    const stroke = e.kind === "fail" ? "#f59e0b" : e.kind === "dlq" ? "#a855f7" : e.kind === "retry" ? "#06b6d4" : "#5b8cff";
    const arrowId = e.kind === "fail" ? "arrow-fail" : e.kind === "dlq" ? "arrow-dlq" : e.kind === "retry" ? "arrow-retry" : "arrow";

    const path = document.createElementNS(ns, "path");
    const ax = a.x + 80, ay = a.y + 26;
    const bx = b.x, by = b.y + 26;
    const curve = e.curve || 0;
    const midX = (ax + bx) / 2;
    const midY = (ay + by) / 2 + curve;
    const d = `M ${ax} ${ay} Q ${midX} ${midY} ${bx} ${by}`;
    path.setAttribute("d", d);
    path.setAttribute("fill", "none");
    path.setAttribute("stroke", stroke);
    path.setAttribute("stroke-width", "1.6");
    path.setAttribute("stroke-dasharray", e.kind === "retry" ? "5,4" : "");
    path.setAttribute("marker-end", `url(#${arrowId})`);
    svg.appendChild(path);

    const text = document.createElementNS(ns, "text");
    text.setAttribute("x", midX);
    text.setAttribute("y", midY - 6);
    text.setAttribute("fill", "#8593b3");
    text.setAttribute("font-size", "10");
    text.setAttribute("text-anchor", "middle");
    text.textContent = e.label;
    svg.appendChild(text);
  });

  // Nodes
  NODES.forEach((n) => {
    const g = document.createElementNS(ns, "g");
    g.setAttribute("transform", `translate(${n.x},${n.y})`);
    const rect = document.createElementNS(ns, "rect");
    rect.setAttribute("width", "160");
    rect.setAttribute("height", "52");
    rect.setAttribute("rx", "10");
    const fill =
      n.kind === "fail" ? "rgba(245,158,11,0.15)" :
      n.kind === "terminal" ? "rgba(34,197,94,0.15)" :
      n.kind === "bad" ? "rgba(239,68,68,0.15)" :
      n.kind === "dlq" ? "rgba(168,85,247,0.15)" :
      "rgba(91,140,255,0.15)";
    const stroke =
      n.kind === "fail" ? "#f59e0b" :
      n.kind === "terminal" ? "#22c55e" :
      n.kind === "bad" ? "#ef4444" :
      n.kind === "dlq" ? "#a855f7" :
      "#5b8cff";
    rect.setAttribute("fill", fill);
    rect.setAttribute("stroke", stroke);
    rect.setAttribute("stroke-width", "1.5");
    g.appendChild(rect);

    const text = document.createElementNS(ns, "text");
    text.setAttribute("x", "80");
    text.setAttribute("y", "26");
    text.setAttribute("dominant-baseline", "middle");
    text.setAttribute("text-anchor", "middle");
    text.setAttribute("fill", "#e6ecff");
    text.setAttribute("font-size", "12");
    text.setAttribute("font-weight", "600");
    text.textContent = n.label;
    g.appendChild(text);

    const sub = document.createElementNS(ns, "text");
    sub.setAttribute("x", "80");
    sub.setAttribute("y", "42");
    sub.setAttribute("dominant-baseline", "middle");
    sub.setAttribute("text-anchor", "middle");
    sub.setAttribute("fill", "#8593b3");
    sub.setAttribute("font-size", "9");
    sub.textContent = n.id;
    g.appendChild(sub);

    svg.appendChild(g);
  });
}

/* ---------------- Helpers ---------------- */
function statePill(s) {
  return `<span class="pill pill-${s}">${s.replace(/_/g, " ")}</span>`;
}

function fmtTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString();
  }
  return d.toLocaleString();
}

function escapeHtml(s) {
  if (s == null) return "";
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

/* ---------------- Master refresh loop ---------------- */
async function refresh() {
  await Promise.all([refreshStats(), refreshCases(), refreshReview(), refreshDlq()]);
  if (openCaseId) {
    try {
      const c = await api("/" + openCaseId);
      renderCaseModal(c);
    } catch (e) {}
  }
}
refresh();
setInterval(refresh, POLL_MS);
