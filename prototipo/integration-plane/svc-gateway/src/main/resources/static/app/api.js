// Acceso a la API de la plataforma. El token vive solo en la pestaña (sessionStorage); la identidad la fija la API.
const KEY = "veriagent.token";

export const session = { token: "", subject: "", role: "" };

try { session.token = sessionStorage.getItem(KEY) || ""; } catch { /* sin almacenamiento */ }

export function setToken(t) {
  session.token = t;
  try { t ? sessionStorage.setItem(KEY, t) : sessionStorage.removeItem(KEY); } catch { /* sin almacenamiento */ }
}

export class ApiError extends Error {
  constructor(status, body) {
    super(messageFor(status, body));
    this.status = status;
    this.body = body;
  }
}

function messageFor(status, body) {
  const m = body && (body.message || body.detail || body.reason);
  if (status === 401) return "Falta un token válido: escríbelo arriba para entrar.";
  if (status === 403) return m || "Tu rol no permite esta operación.";
  if (status === 409) return m || "La operación no es posible en el estado actual.";
  return m ? `${m} (HTTP ${status})` : `Error HTTP ${status}`;
}

export async function api(path, { method = "GET", json, body, headers = {} } = {}) {
  const h = { ...headers };
  if (session.token) h.Authorization = `Bearer ${session.token}`;
  if (json !== undefined) { h["Content-Type"] = "application/json"; body = JSON.stringify(json); }
  const r = await fetch(path, { method, headers: h, body });
  const text = await r.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch { data = text; }
  if (!r.ok && !(r.status === 422 && data && typeof data === "object")) throw new ApiError(r.status, data);
  return { status: r.status, data };
}
