async function api(method, path, body) {
  const res = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    const err = new Error(data && data.message ? data.message : res.statusText);
    err.code = data && data.code;
    err.status = res.status;
    throw err;
  }
  return data;
}

function log(el, message, level = 'info') {
  const line = document.createElement('div');
  line.className = level;
  const now = new Date().toISOString().slice(11, 19);
  line.textContent = `[${now}] ${message}`;
  el.appendChild(line);
  el.scrollTop = el.scrollHeight;
}

function fmtInstant(iso) {
  if (!iso) return '';
  return new Date(iso).toLocaleString();
}
