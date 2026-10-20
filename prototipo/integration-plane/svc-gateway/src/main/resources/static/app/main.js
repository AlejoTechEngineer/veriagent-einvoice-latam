// Front mínimo: enrutado por hash y siete pantallas. Sin dependencias.
import { api, session, setToken } from "./api.js";
import { card, field, fill, fmtDate, h, notice, status, table } from "./dom.js";

const main = () => document.getElementById("contenido");
const ROUTES = { panel, comprobantes, emitir, lote, dlq, propuestas, verificador };

async function render() {
  const [route, ...rest] = (location.hash.replace(/^#\//, "").split("?")[0] || "panel").split("/");
  document.querySelectorAll(".menu a").forEach((a) => {
    if (a.getAttribute("href") === `#/${route}`) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  });
  const view = ROUTES[route] || panel;
  const box = main();
  box.replaceChildren(h("p", { class: "muted" }, "Cargando…"));
  if (!session.token) { box.replaceChildren(h("h1", {}, "VeriAgent"), notice("Escribe tu token de acceso para empezar.")); return; }
  try {
    const nodes = [].concat(await view(decodeURIComponent(rest.join("/")))).flat(Infinity);
    box.replaceChildren(...nodes.filter((n) => n !== null && n !== undefined && n !== false));
  } catch (e) {
    box.replaceChildren(notice(e.message, "error"));
  }
}

// --- Sesión -------------------------------------------------------------------------------------------------
async function startSession() {
  const who = document.getElementById("quien");
  try {
    const { data } = await api("/api/session");
    Object.assign(session, data);
    who.textContent = `${data.subject} · ${roleName(data.role)}`;
    document.getElementById("salir").hidden = false;
  } catch (e) {
    who.textContent = e.message;
    setToken("");
  }
  render();
}

function roleName(r) {
  return { REVIEWER: "revisor", AGENT: "agente", OPERATOR: "operador", SYSTEM: "sistema" }[r] || r;
}

document.getElementById("sesion").addEventListener("submit", (e) => {
  e.preventDefault();
  setToken(e.target.token.value.trim());
  e.target.token.value = "";
  startSession();
});
document.getElementById("salir").addEventListener("click", () => {
  setToken("");
  Object.assign(session, { subject: "", role: "" });
  document.getElementById("quien").textContent = "";
  document.getElementById("salir").hidden = true;
  render();
});
window.addEventListener("hashchange", render);
if (session.token) startSession(); else render();

// --- Panel --------------------------------------------------------------------------------------------------
async function panel() {
  const [{ data: st }, { data: maps }, sims] = await Promise.all([
    api("/api/invoices/stats"), api("/api/mappings"), api("/api/simulators").catch(() => ({ data: null }))]);
  const c = st.conservation;
  const out = [h("h1", {}, "Panel"),
    h("div", { class: "grid" }, Object.entries(st.byStatus).map(([k, v]) => card(k, v, `estado ${k}`))),
    h("h2", {}, "Conservación de comprobantes"),
    notice(`Registrados ${c.registered} = autorizados ${c.authorized} + fallidos ${c.failed} + DLQ ${c.dlq} + en curso ${c.inFlight}: `
      + (c.balanced ? "cuadra" : "NO CUADRA"), c.balanced ? "exito" : "error"),
    h("p", { class: "muted" }, `Entradas inválidas en la DLQ: ${c.invalidInputsInDlq}. Entradas abiertas en la DLQ: ${st.dlqOpen}.`),
    h("h2", {}, "Por país y proveedor"),
    table(["País", "Proveedor", "Estado", "Comprobantes"], st.byCountryProvider.map((r) =>
      ({ cells: [r.country, r.provider || "—", status(r.status), r.n] }))),
    h("h2", {}, "Mapeos vigentes"),
    table(["Proveedor", "País", "Versiones", "Vigente"], maps.map((m) =>
      ({ cells: [m.provider, m.country, m.versions, m.active_version ? `v${m.active_version}` : "ninguna"] })))];
  if (sims.data) {
    out.push(h("h2", {}, "Proveedores simulados"),
      table(["Proveedor", "Recibidos", "Autorizados", "Rechazados", "Fallo activo"], sims.data.providers.map((p) => {
        const s = sims.data.stats[p] || {};
        const f = sims.data.faults[p];
        return { cells: [p, s.received || 0, s.authorized || 0, s.rejected || 0, f ? `${f.fault} (${f.remaining})` : "ninguno"] };
      })));
  }
  return out;
}

// --- Comprobantes -------------------------------------------------------------------------------------------
async function comprobantes(key) {
  if (key) return detalle(key);
  const params = new URLSearchParams(location.hash.split("?")[1] || "");
  const sel = (id, opts, v) => h("select", { id, name: id }, opts.map((o) => h("option", { value: o, selected: o === v }, o || "todos")));
  const form = h("form", { class: "fila", onsubmit: (e) => {
    e.preventDefault();
    const q = new URLSearchParams(new FormData(e.target));
    [...q.keys()].forEach((k) => { if (!q.get(k)) q.delete(k); });
    location.hash = `#/comprobantes?${q}`;
  } },
    field("Estado", sel("status", ["", "RECEIVED", "PENDING_BATCH", "SENT", "AUTHORIZED", "FAILED", "DLQ"], params.get("status") || "")),
    field("País", sel("country", ["", "CO", "PE", "XX"], params.get("country") || "")),
    field("Número", h("input", { id: "q", name: "q", value: params.get("q") || "" })),
    h("button", { type: "submit" }, "Buscar"));
  const q = new URLSearchParams(params);
  q.set("limit", "100");
  const { data } = await api(`/api/invoices?${q}`);
  return [h("h1", {}, "Comprobantes"), form,
    table(["Comprobante", "Estado", "País", "Proveedor", "Canal", "Mapeo", "Autorización"], data.map((r) => ({
      item: r.invoiceKey,
      cells: [h("code", {}, r.invoiceKey), status(r.status), r.country, r.provider || "—", r.channel,
        r.mappingVersion ? `v${r.mappingVersion}` : "—", r.authorizationNumber || "—"] })),
    (k) => { location.hash = `#/comprobantes/${encodeURIComponent(k)}`; })];
}

async function detalle(key) {
  const { data: d } = await api(`/api/invoices/${encodeURIComponent(key)}`);
  return [h("h1", {}, "Comprobante"), h("p", {}, h("code", {}, d.invoiceKey), " ", status(d.status)),
    h("p", { class: "muted" }, `Proveedor ${d.provider || "—"} · mapeo ${d.mappingVersion ? "v" + d.mappingVersion : "—"} · `
      + `autorización ${d.authorizationNumber || "—"} · correlación ${d.correlationId}`),
    d.errorMessage ? notice(`${d.errorCode}: ${d.errorMessage}`, "error") : null,
    h("h2", {}, "Hallazgos del kit"),
    table(["Regla", "Tipo", "Mensaje", "Fuente normativa", "Huella"], (d.findings || []).map((f) => ({
      cells: [f.ruleId, f.type, f.message, f.source, h("code", {}, (f.sourceSha256 || "").slice(0, 12))] }))),
    h("h2", {}, "Línea de tiempo"),
    table(["Fecha", "De", "A", "Actor", "Motivo"], (d.timeline || []).map((t) => ({
      cells: [fmtDate(t.occurredAt), t.from || "—", status(t.to), t.actor || "—", t.reason || ""] }))),
    h("h2", {}, "Archivo legal"),
    table(["Secuencia", "Huella del objeto", "Retención hasta"], (d.archive || []).map((a) => ({
      cells: [a.seq, h("code", {}, (a.object_sha256 || "").slice(0, 16)), a.retention_until] }))),
    d.transformed ? [h("h2", {}, "Documento enviado al proveedor"), h("pre", {}, d.transformed)] : null,
    h("p", {}, h("a", { href: "#/comprobantes" }, "Volver a la lista"))];
}

// --- Emitir -------------------------------------------------------------------------------------------------
function ejemplo() {
  const n = `SETP990${String(Date.now() % 1000000).padStart(6, "0")}`;
  return {
    country: "CO", correlationId: `corr-co-${n}`, documentNumber: n, documentType: "INVOICE",
    issueDate: new Date().toISOString().slice(0, 10), currency: "COP", originalDocumentNumber: null,
    supplier: { name: "Comercializadora Sintética S.A.S.", taxId: "900123456-8", idType: "NIT", email: null },
    customer: { name: "Carlos Pérez", taxId: "956735845-1", idType: "NIT", email: "cliente@ejemplo.test" },
    lines: [{ number: 1, description: "Mantenimiento preventivo", quantity: 5, unitPrice: 3078.53, amount: 15392.65, taxCode: "IVA19", taxRate: 19.0 }],
    totals: { baseAmount: 15392.65, taxAmount: 2924.6, payableAmount: 18317.25 },
  };
}

async function emitir() {
  const area = h("textarea", { id: "canonico", spellcheck: "false" }, JSON.stringify(ejemplo(), null, 2));
  const out = h("div", { "aria-live": "polite" });
  const send = async () => {
    fill(out, h("p", { class: "muted" }, "Enviando…"));
    try {
      const { status: code, data } = await api("/api/invoices", { method: "POST", json: JSON.parse(area.value) });
      fill(out, notice(`HTTP ${code}: ${data.status}${data.reasonCode ? " · " + data.reasonCode : ""}`
        + `${data.authorizationNumber ? " · autorización " + data.authorizationNumber : ""}${data.duplicate ? " (reenvío idempotente)" : ""}`,
        data.status === "AUTHORIZED" ? "exito" : "error"),
        (data.findings || []).length ? table(["Regla", "Tipo", "Mensaje", "Fuente"], data.findings.map((f) =>
          ({ cells: [f.ruleId, f.type, f.message, f.source] }))) : null,
        h("p", {}, h("a", { href: `#/comprobantes/${encodeURIComponent(data.invoiceKey)}` }, "Ver el comprobante")));
    } catch (e) {
      const errs = e.body && e.body.details;
      fill(out, notice(e.message, "error"), errs ? h("ul", {}, errs.map((x) => h("li", {}, x))) : null);
    }
  };
  return [h("h1", {}, "Emitir comprobante"),
    h("p", { class: "muted" }, "Comprobante canónico de ejemplo (sintético). La emisión es síncrona: el kit verifica antes de llamar al proveedor."),
    field("Comprobante canónico (JSON)", area),
    h("div", { class: "fila" },
      h("button", { type: "button", onclick: () => { area.value = JSON.stringify(ejemplo(), null, 2); } }, "Nuevo número"),
      h("button", { type: "button", class: "primario", onclick: send }, "Emitir")), out];
}

// --- Cargar lote --------------------------------------------------------------------------------------------
async function lote() {
  const file = h("input", { id: "archivo", type: "file", accept: ".json,.jsonl,.zip" });
  const mode = h("select", { id: "modo" }, h("option", { value: "now" }, "procesar ahora"), h("option", { value: "queue" }, "dejar en el buzón"));
  const out = h("div", { "aria-live": "polite" });
  const send = async () => {
    if (!file.files[0]) { fill(out, notice("Elige un archivo .json, .jsonl o .zip.", "error")); return; }
    const fd = new FormData();
    fd.append("file", file.files[0]);
    try {
      const { data } = await api(`/api/ingest/batches?mode=${mode.value}`, { method: "POST", body: fd });
      fill(out, notice(`Recibidos ${data.received ?? "—"} · aceptados ${data.accepted ?? "—"} · duplicados `
        + `${data.duplicates ?? "—"} · rechazados ${data.rejected ?? "—"}`, "exito"), h("pre", {}, JSON.stringify(data, null, 2)));
    } catch (e) { fill(out, notice(e.message, "error")); }
  };
  const { data: files } = await api("/api/ingest/files?limit=20");
  return [h("h1", {}, "Cargar lote"),
    h("p", { class: "muted" }, "Requiere rol de operador. Tamaño máximo 20 MB; los ZIP se descomprimen con límites."),
    h("div", { class: "fila" }, field("Archivo", file), field("Modo", mode), h("button", { class: "primario", onclick: send }, "Cargar")),
    out, h("h2", {}, "Últimos archivos"),
    table(["Archivo", "Huella", "Recibidos", "Aceptados", "Duplicados", "Rechazados", "Cargado por", "Fecha"], files.map((f) => ({
      cells: [f.file_name, h("code", {}, String(f.sha256 || "").slice(0, 12)), f.received, f.accepted, f.duplicates, f.rejected,
        f.actor, fmtDate(f.processed_at)] })))];
}

// --- DLQ ----------------------------------------------------------------------------------------------------
async function dlq() {
  const { data } = await api("/api/dlq?status=OPEN&limit=200");
  const out = h("div", { "aria-live": "polite" });
  const act = async (id, action) => {
    const comment = document.getElementById(`c-${id}`).value;
    try {
      await api(`/api/dlq/${id}/${action}`, { method: "POST", json: { comment } });
      render();
    } catch (e) { fill(out, notice(e.message, "error")); }
  };
  const canDecide = session.role === "OPERATOR";
  return [h("h1", {}, "Cola DLQ"),
    h("p", { class: "muted" }, "Reprocesar y descartar son decisiones del rol operador. El triaje del agente es solo una recomendación."),
    out,
    table(["Entrada", "Comprobante", "Etapa", "Categoría", "Error", "Triaje", "Decisión"], data.map((e) => {
      let t = null;
      try { t = e.triage ? JSON.parse(e.triage) : null; } catch { t = null; }
      return { cells: [e.id, e.invoiceKey ? h("a", { href: `#/comprobantes/${encodeURIComponent(e.invoiceKey)}` }, e.invoiceKey) : e.sourceRef,
        e.stage, e.category, e.lastError,
        t ? `${t.classification} → ${t.recommendation} (${t.triagedBy})` : "sin triaje",
        h("div", {}, h("input", { id: `c-${e.id}`, "aria-label": `Comentario para la entrada ${e.id}`, placeholder: "comentario" }),
          h("button", { disabled: !canDecide, onclick: () => act(e.id, "reprocess") }, "Reprocesar"),
          h("button", { disabled: !canDecide, onclick: () => act(e.id, "discard") }, "Descartar"))] };
    }))];
}

// --- Propuestas de agentes y versiones de mapeo ---------------------------------------------------------------
async function propuestas(id) {
  if (id) return propuesta(id);
  let list = [];
  let warn = null;
  try { list = (await api("/agents/api/proposals")).data; } catch (e) { warn = notice(e.message, "error"); }
  const { data: maps } = await api("/api/mappings");
  const versions = await Promise.all(maps.map((m) => api(`/api/mappings/${m.provider}/${m.country}/versions`).then((r) => ({ m, v: r.data }))));
  const out = h("div", { "aria-live": "polite" });
  const isReviewer = session.role === "REVIEWER";
  const act = async (m, v, action) => {
    try {
      const path = action === "rollback" ? `/api/mappings/${m.provider}/${m.country}/rollback`
        : `/api/mappings/${m.provider}/${m.country}/versions/${v}/${action}`;
      await api(path, { method: "POST", json: { note: `Desde el front (${action})` } });
      render();
    } catch (e) { fill(out, notice(e.message, "error")); }
  };
  return [h("h1", {}, "Propuestas de agentes"),
    h("p", { class: "muted" }, "Los agentes solo proponen. Una propuesta bloqueada por el kit no se puede aprobar; aprobar, activar y revertir son decisiones de una persona revisora."),
    warn,
    table(["Propuesta", "Agente", "Estado", "Registro", "Solicitud de cambio", "Revisor"], list.map((p) => ({
      item: p.id, cells: [p.id, p.agent, status(p.status),
        p.registry_provider ? `${p.registry_provider}/${p.registry_country} v${p.registry_version}` : "—",
        p.pull_request || "—", p.reviewer || "—"] })),
    (k) => { location.hash = `#/propuestas/${k}`; }),
    h("h2", {}, "Versiones de mapeo"), out,
    versions.map(({ m, v }) => [h("h2", {}, `${m.provider} / ${m.country}`),
      table(["Versión", "Estado", "Kit", "Origen", "Propuso", "Aprobó", "Activó", "Acción"], v.map((x) => ({
        cells: [`v${x.version}`, status(x.status), x.gatePassed ? "pasa" : "bloqueada", x.source, x.createdBy, x.approvedBy || "—",
          x.activatedBy || "—",
          x.status === "APPROVED" ? h("button", { disabled: !isReviewer, onclick: () => act(m, x.version, "activate") }, "Activar")
            : x.status === "ACTIVE" ? h("button", { disabled: !isReviewer, onclick: () => act(m, x.version, "rollback") }, "Revertir")
              : "—"] })))])];
}

async function propuesta(id) {
  const { data: p } = await api(`/agents/api/proposals/${encodeURIComponent(id)}`);
  const v = p.verification || {};
  const comment = h("textarea", { id: "comentario", rows: 3 });
  const out = h("div", { "aria-live": "polite" });
  const decide = async (decision) => {
    try {
      await api(`/agents/api/proposals/${encodeURIComponent(id)}/decision`, { method: "POST", json: { decision, comment: comment.value } });
      render();
    } catch (e) { fill(out, notice(e.message, "error")); }
  };
  const open = p.status === "PENDING_REVIEW" || p.status === "BLOCKED_BY_GATE";
  const isReviewer = session.role === "REVIEWER";
  return [h("h1", {}, `Propuesta ${p.id}`), h("p", {}, status(p.status), ` · agente ${p.agent} · ${fmtDate(p.created_at)}`),
    h("p", { class: "muted" }, `Registro: ${p.registry_provider ? `${p.registry_provider}/${p.registry_country} v${p.registry_version}` : "—"}`
      + ` · solicitud de cambio ${p.pull_request || "—"} · huella de la especificación ${String(p.spec_sha256 || "").slice(0, 12)}`),
    h("h2", {}, "Verificación del kit"),
    notice(`${v.passed || p.gate_passed ? "Pasa el kit" : "Bloqueada por el kit"}: ejecución ${v.runtimeFailed ?? "—"} de ${v.runtimeDocuments ?? "—"} con hallazgos; `
      + `ejemplos validados ${v.ciFailed ?? "—"} de ${v.ciDocuments ?? "—"}.`, p.gate_passed ? "exito" : "error"),
    table(["Regla", "Tipo", "Mensaje", "Fuente"], (v.examples || []).map((f) => ({ cells: [f.ruleId, f.type, f.message, f.source] }))),
    h("h2", {}, "Decisión"), out,
    p.reviewer ? h("p", {}, `Decidida por ${p.reviewer} el ${fmtDate(p.decided_at)}`) : null,
    field("Comentario", comment),
    h("div", { class: "fila" },
      h("button", { class: "primario", disabled: !open || !isReviewer || !p.gate_passed, onclick: () => decide("APPROVE") }, "Aprobar"),
      h("button", { disabled: !open || !isReviewer, onclick: () => decide("REJECT") }, "Rechazar")),
    isReviewer ? null : h("p", { class: "muted" }, "Solo una persona con rol de revisor puede decidir."),
    h("h2", {}, "Mapeo propuesto"), h("pre", {}, JSON.stringify(p.mapping, null, 2)),
    h("p", {}, h("a", { href: "#/propuestas" }, "Volver"))];
}

// --- Verificador --------------------------------------------------------------------------------------------
async function verificador() {
  const { data: profiles } = await api("/api/kit/profiles");
  const prof = h("select", { id: "perfil" }, profiles.map((p) => h("option", { value: p.id }, `${p.id} (${p.format}, ${p.specDocument})`)));
  const mode = h("select", { id: "modo" }, ["RUNTIME", "SCHEMA_ONLY"].map((m) => h("option", { value: m }, m)));
  const area = h("textarea", { id: "documento", spellcheck: "false", placeholder: "Pega aquí el documento del proveedor (XML, segmentos o JSON)" });
  const out = h("div", { "aria-live": "polite" });
  const run = async () => {
    try {
      const { data } = await api(`/api/kit/verify?profile=${encodeURIComponent(prof.value)}&mode=${mode.value}`,
        { method: "POST", body: area.value, headers: { "Content-Type": "text/plain; charset=utf-8" } });
      fill(out, notice(data.passed ? "Sin hallazgos." : `${data.findings.length} hallazgos.`, data.passed ? "exito" : "error"),
        table(["Regla", "Tipo", "Ubicación", "Mensaje", "Fuente", "Huella", "Versión"], data.findings.map((f) => ({
          cells: [f.ruleId, f.type, f.location || "—", f.message, f.source, h("code", {}, (f.sourceSha256 || "").slice(0, 12)), f.ruleVersion] }))),
        h("p", { class: "muted" }, `Verificado en ${(data.durationMicros / 1000).toFixed(2)} ms.`));
    } catch (e) { fill(out, notice(e.message, "error")); }
  };
  return [h("h1", {}, "Verificador"),
    h("p", { class: "muted" }, "El mismo kit que corre en los adaptadores, los conectores y el pipeline."),
    h("div", { class: "fila" }, field("Perfil", prof), field("Modo", mode)), field("Documento", area),
    h("div", { class: "fila" }, h("button", { class: "primario", onclick: run }, "Verificar")), out];
}
