// JavaScript mínimo: solo pide datos a la API y pinta. Toda la presentación vive en styles.css.
const STATUS = {
  BLOCKED_BY_GATE: "Bloqueada por el kit",
  PENDING_REVIEW: "Pendiente de revisión",
  APPROVED: "Aprobada",
  REJECTED: "Rechazada",
};
const $ = (s, el = document) => el.querySelector(s);
const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
const fmtDate = (iso) => new Date(iso).toLocaleString("es-CO", { dateStyle: "medium", timeStyle: "short" });
const show = (v) => {
  if (v == null) return "—";
  const { target, ...rest } = v;
  return Object.entries(rest).map(([k, x]) => `<div><span class="k">${esc(k)}</span> <code>${esc(typeof x === "object" ? JSON.stringify(x) : x)}</code></div>`).join("");
};

// El token solo vive en la pestaña (sessionStorage). La identidad de quien decide la fija la API a partir de él.
const TOKEN_KEY = "revision.token";
const readToken = () => { try { return sessionStorage.getItem(TOKEN_KEY) || ""; } catch { return ""; } };
const writeToken = (t) => { try { t ? sessionStorage.setItem(TOKEN_KEY, t) : sessionStorage.removeItem(TOKEN_KEY); } catch { /* sin almacenamiento */ } };
let token = readToken();
let me = null;

async function api(path, opts = {}) {
  const headers = { "Content-Type": "application/json" };
  if (token) headers.Authorization = `Bearer ${token}`;
  const r = await fetch(path, { ...opts, headers });
  const body = await r.json().catch(() => ({}));
  if (r.status === 401) throw new Error("Falta un token válido: escríbelo arriba para entrar.");
  if (r.status === 403) throw new Error(typeof body.detail === "string" ? body.detail : "Tu rol no permite esta operación.");
  if (!r.ok) throw new Error(typeof body.detail === "string" ? body.detail : "Revisa el comentario.");
  return body;
}

async function startSession() {
  const who = $("#whoami");
  try {
    me = await api("/api/me");
    who.textContent = `Sesión: ${me.subject} (${me.role === "REVIEWER" ? "revisor" : me.role})`;
    $("#logout").hidden = false;
    await loadList();
  } catch (e) {
    me = null;
    who.textContent = e.message;
    $("#proposal-list").innerHTML = "";
  }
}

$("#session").addEventListener("submit", (ev) => {
  ev.preventDefault();
  token = ev.target.token.value.trim();
  ev.target.token.value = "";
  writeToken(token);
  startSession();
});
$("#logout").addEventListener("click", () => {
  token = "";
  me = null;
  writeToken("");
  $("#logout").hidden = true;
  $("#whoami").textContent = "";
  $("#proposal-list").innerHTML = "";
  $("#detail").innerHTML = '<p class="empty">Escribe tu token para ver las propuestas.</p>';
});

async function loadList(selectId) {
  const list = await api("/api/proposals");
  const ol = $("#proposal-list");
  $("#empty-list").hidden = list.length > 0;
  ol.innerHTML = list.map((p) => `
    <li><button type="button" data-id="${esc(p.id)}">
      <span class="row-id">${esc(p.id)}</span>
      <span class="row-status" data-s="${esc(p.status)}">${STATUS[p.status]}</span>
      <span class="row-agent">${esc(p.agent)}</span>
      <span class="row-date">${fmtDate(p.created_at)}</span>
    </button></li>`).join("");
  ol.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => loadDetail(b.dataset.id)));
  if (selectId) loadDetail(selectId, false);
}

async function loadDetail(id, animate = true) {
  document.querySelectorAll("#proposal-list button").forEach((b) => b.setAttribute("aria-current", String(b.dataset.id === id)));
  const p = await api(`/api/proposals/${encodeURIComponent(id)}`);
  const node = $("#tpl-detail").content.cloneNode(true);
  const v = p.verification || {};
  $(".sheet-title", node).textContent = `Propuesta ${p.id}`;
  $('[data-f="agent"]', node).textContent = p.agent;
  $('[data-f="created"]', node).textContent = fmtDate(p.created_at);
  $('[data-f="docs"]', node).textContent = v.documents != null ? `${v.documents - v.failedDocuments} de ${v.documents} sin hallazgos` : "—";
  $('[data-f="acc"]', node).textContent = v.fieldAccuracy ? `${v.fieldAccuracy.correct} de ${v.fieldAccuracy.fields} campos` : "—";
  const stamp = $(".stamp", node);
  stamp.dataset.s = p.status;
  stamp.textContent = STATUS[p.status];
  if (animate) stamp.classList.add("landing");

  const ex = v.examples || [];
  $(".findings", node).innerHTML = ex.length
    ? ex.map((f) => `<li><span class="finding-rule">${esc(f.ruleId)}</span><span class="finding-type">${esc(f.type)}</span>
        <span class="finding-msg">${esc(f.message)}</span>
        <span class="finding-src">${esc(f.source)} · huella ${esc((f.sourceSha256 || "").slice(0, 12))} · regla v${esc(f.ruleVersion)}</span></li>`).join("")
    : `<li class="none">Sin hallazgos: la propuesta pasó el kit en ejecución y los fixtures de CI.</li>`;

  $(".diff tbody", node).innerHTML = (p.diff || []).map((d) => `
    <tr><th scope="row"><code>${esc(d.target)}</code></th><td class="was">${show(d.current)}</td>
    <td>${show(d.proposed)}</td><td>${esc(d.evidence?.section ?? "—")}</td></tr>`).join("")
    || `<tr><td colspan="4">La propuesta coincide con el mapeo vigente.</td></tr>`;

  const form = $(".decision", node);
  const note = $(".gate-note", form);
  const decided = p.status === "APPROVED" || p.status === "REJECTED";
  if (!p.gate_passed) {
    note.textContent = "No se puede aprobar: la propuesta no pasó el kit. Corrígela y vuelve a generarla.";
    note.classList.add("blocked");
    $(".btn-approve", form).disabled = true;
  } else {
    note.textContent = decided ? `Decidida por ${p.reviewer}.` : "Pasó el kit. Revisa los cambios y decide.";
  }
  const canDecide = me?.role === "REVIEWER";
  $(".decider", form).textContent = decided ? "" : canDecide
    ? `Decidirás como ${me.subject}: la identidad la toma la API de tu token.`
    : "Solo una persona con rol de revisor puede decidir.";
  if (decided || !canDecide) form.querySelectorAll("button, input, textarea").forEach((e) => (e.disabled = true));
  form.addEventListener("submit", async (ev) => {
    ev.preventDefault();
    const msg = $(".form-msg", form);
    const decision = ev.submitter?.value;
    try {
      await api(`/api/proposals/${encodeURIComponent(p.id)}/decision`, {
        method: "POST",
        body: JSON.stringify({ decision, comment: form.comment.value.trim() }),
      });
      await loadList(p.id);
    } catch (e) {
      msg.textContent = e.message;
      msg.className = "form-msg error";
    }
  });
  $("#detail").replaceChildren(node);
}

if (token) startSession();
