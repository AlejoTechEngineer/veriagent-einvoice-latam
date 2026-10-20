// Construcción del DOM sin innerHTML: todo dato de la API entra como texto.
export function h(tag, attrs = {}, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === undefined || v === null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "class") el.className = v;
    else el.setAttribute(k, v === true ? "" : String(v));
  }
  for (const c of children.flat()) {
    if (c === undefined || c === null || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}

// Sustituye el contenido de un elemento ignorando los nulos (nunca se pinta "null").
export function fill(el, ...nodes) {
  el.replaceChildren(...nodes.flat(Infinity).filter((n) => n !== null && n !== undefined && n !== false));
}

export function table(headers, rows, onRow) {
  const body = rows.length
    ? rows.map((r) => h("tr", onRow ? { class: "clic", tabindex: 0, onclick: () => onRow(r.item),
        onkeydown: (e) => { if (e.key === "Enter") onRow(r.item); } } : {}, r.cells.map((c) => h("td", {}, c))))
    : [h("tr", {}, h("td", { colspan: headers.length, class: "muted" }, "Sin datos."))];
  return h("div", { class: "table-wrap" }, h("table", {},
    h("thead", {}, h("tr", {}, headers.map((x) => h("th", { scope: "col" }, x)))), h("tbody", {}, body)));
}

export function notice(text, kind = "") {
  return h("p", { class: `aviso ${kind}`, role: kind === "error" ? "alert" : "status" }, text);
}

export function card(label, value, cls = "") {
  return h("div", { class: "card" }, h("div", { class: "muted" }, label), h("div", { class: `num ${cls}` }, value));
}

export function status(s) {
  return h("span", { class: `estado ${s || ""}` }, s || "—");
}

export function field(label, control) {
  return h("div", {}, h("label", { for: control.id }, label), control);
}

export function fmtDate(iso) {
  if (!iso) return "—";
  try { return new Date(iso).toLocaleString("es-CO", { dateStyle: "short", timeStyle: "medium" }); } catch { return iso; }
}
