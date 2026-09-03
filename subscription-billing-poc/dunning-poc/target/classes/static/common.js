// ===================================================================
//  Shared client utilities — fetch wrapper, formatters, toasts, DOM
// ===================================================================

const Api = {
  async request(method, url, body) {
    const opts = {
      method,
      headers: { "Accept": "application/json" },
    };
    if (body !== undefined) {
      opts.headers["Content-Type"] = "application/json";
      opts.body = JSON.stringify(body);
    }
    let res;
    try {
      res = await fetch(url, opts);
    } catch (e) {
      throw new ApiError("Network error: " + e.message, 0, null);
    }
    const text = await res.text();
    const data = text ? safeJson(text) : null;
    if (!res.ok) {
      const msg = (data && data.error) || res.statusText || ("HTTP " + res.status);
      throw new ApiError(msg, res.status, data);
    }
    return data;
  },
  get(url)         { return this.request("GET", url); },
  post(url, body)  { return this.request("POST", url, body); },
  put(url, body)   { return this.request("PUT", url, body); },
  del(url)         { return this.request("DELETE", url); },
};

class ApiError extends Error {
  constructor(message, status, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

function safeJson(text) {
  try { return JSON.parse(text); } catch { return text; }
}

// ===================================================================
//  Formatting
// ===================================================================

const Fmt = {
  money(amount, currency = "USD") {
    if (amount === null || amount === undefined) return "—";
    const n = Number(amount);
    if (Number.isNaN(n)) return String(amount);
    return new Intl.NumberFormat("en-US", {
      style: "currency",
      currency,
      minimumFractionDigits: 2,
    }).format(n);
  },
  moneyHtml(amount, currency = "USD") {
    if (amount === null || amount === undefined) return '<span class="money-zero">—</span>';
    const n = Number(amount);
    const cls = n > 0 ? "money-positive" : n < 0 ? "money-negative" : "money-zero";
    return `<span class="${cls}">${this.money(n, currency)}</span>`;
  },
  number(n, opts = {}) {
    if (n === null || n === undefined) return "—";
    return new Intl.NumberFormat("en-US", { maximumFractionDigits: 4, ...opts }).format(Number(n));
  },
  date(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleDateString("en-US", { year: "numeric", month: "short", day: "numeric" });
  },
  datetime(iso) {
    if (!iso) return "—";
    return new Date(iso).toLocaleString("en-US", {
      year: "numeric", month: "short", day: "numeric",
      hour: "2-digit", minute: "2-digit",
    });
  },
  today() {
    const d = new Date();
    const tz = d.getTimezoneOffset() * 60000;
    return new Date(d - tz).toISOString().slice(0, 10);
  },
};

// ===================================================================
//  Toasts
// ===================================================================

const Toast = {
  container: null,
  init() {
    if (!this.container) {
      this.container = document.createElement("div");
      this.container.className = "toast-container";
      document.body.appendChild(this.container);
    }
  },
  show(title, message, kind = "info", ttl = 4000) {
    this.init();
    const el = document.createElement("div");
    el.className = "toast toast-" + kind;
    el.innerHTML = `<strong>${escape(title)}</strong><div class="toast-msg">${escape(message || "")}</div>`;
    this.container.appendChild(el);
    setTimeout(() => {
      el.style.transition = "opacity 200ms ease, transform 200ms ease";
      el.style.opacity = "0";
      el.style.transform = "translateX(20px)";
      setTimeout(() => el.remove(), 220);
    }, ttl);
  },
  success(title, message) { this.show(title, message, "success", 3500); },
  error(title, message)   { this.show(title, message, "error", 6000); },
  warning(title, message) { this.show(title, message, "warning", 5000); },
  info(title, message)    { this.show(title, message, "info", 3500); },
};

function escape(s) {
  return String(s)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

// ===================================================================
//  DOM helpers
// ===================================================================

function $(selector) { return document.querySelector(selector); }
function $$(selector) { return document.querySelectorAll(selector); }

function setLoading(button, loading) {
  if (!button) return;
  if (loading) {
    button.dataset.label = button.textContent;
    button.disabled = true;
    button.innerHTML = `<span class="spinner"></span> ${button.dataset.label}`;
  } else {
    button.disabled = false;
    button.textContent = button.dataset.label || button.textContent;
  }
}

function emptyRow(colspan, text) {
  return `<tr><td colspan="${colspan}" class="empty-state">${escape(text)}</td></tr>`;
}

// Wire shared header — port info + cross-POC links
window.addEventListener("DOMContentLoaded", () => {
  const meta = document.querySelector(".app-header .meta");
  if (meta && !meta.textContent.trim()) {
    meta.textContent = "localhost:" + location.port;
  }
});
