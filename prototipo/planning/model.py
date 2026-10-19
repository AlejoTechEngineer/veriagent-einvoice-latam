"""Modelo de planificación: PERT + Monte Carlo sobre la red de la EDT, costos, carga por rol y punto de equilibrio."""
from __future__ import annotations

import os
import json
from pathlib import Path

import numpy as np

HERE = Path(__file__).parent
A = json.loads(Path(os.environ.get("TFG_ASSUMPTIONS", HERE / "assumptions.json")).read_text())
import os
if os.environ.get("TFG_CAP_OVERRIDE"):
    A["capacidad_equipo_fte"].update(json.loads(os.environ["TFG_CAP_OVERRIDE"]))
OUT = Path(os.environ.get("TFG_PLAN_OUT", HERE))
WBS = json.loads((HERE / "wbs.json").read_text())
IDS = [w["id"] for w in WBS]
IDX = {w["id"]: i for i, w in enumerate(WBS)}
SEM = A["semanas_por_mes"]
RATE_W = {r: v / SEM for r, v in A["costo_mensual_rol_cop"].items()}  # costo por semana FTE


def pert_mean(w): return (w["o"] + 4 * w["m"] + w["p"]) / 6


def topo() -> list[int]:
    seen, order = set(), []
    def visit(i):
        if i in seen: return
        for d in WBS[i]["deps"]: visit(IDX[d])
        seen.add(i); order.append(i)
    for i in range(len(WBS)): visit(i)
    return order


ORDER = topo()
RES_EDGES: list[tuple[str, str]] = []   # dependencias de recurso añadidas por la nivelación


def level_resources() -> list[tuple[str, str]]:
    """Esquema serial (SSGS) con prioridad por inicio tardío del CPM: ninguna semana supera la capacidad
    por rol. Cada retraso por recurso se registra como dependencia fin-inicio (recurso)."""
    cap = A["capacidad_equipo_fte"]; loe = A["dedicacion_continua_fte"]
    te = {w["id"]: pert_mean(w) for w in WBS}
    # inicio tardío por CPM para priorizar
    ef = {}
    for i in ORDER:
        w = WBS[i]; ef[w["id"]] = max([ef[d] for d in w["deps"]], default=0) + te[w["id"]]
    T = max(ef.values()); ls = {}
    for i in reversed(ORDER):
        w = WBS[i]; succ = [x["id"] for x in WBS if w["id"] in x["deps"]]
        ls[w["id"]] = (min(ls[s] for s in succ) if succ else T) - te[w["id"]]
    placed, start, finish, edges = [], {}, {}, []
    pending = [w["id"] for w in WBS]
    def usage(role, a, b):
        u = loe.get(role, 0.0)
        worst = u
        pts = sorted({a, b} | {start[x] for x in placed if a < start[x] < b} | {finish[x] for x in placed if a < finish[x] < b})
        for k in range(len(pts) - 1):
            mid = (pts[k] + pts[k + 1]) / 2
            worst = max(worst, u + sum(WBS[IDX[x]]["roles"].get(role, 0) for x in placed if start[x] <= mid < finish[x]))
        return worst
    while pending:
        ready = [t for t in pending if all(d in finish for d in WBS[IDX[t]]["deps"])]
        t = min(ready, key=lambda x: (ls[x], x)); w = WBS[IDX[t]]
        es = max([finish[d] for d in w["deps"]], default=0.0)
        cands = sorted({es} | {finish[x] for x in placed if finish[x] > es})
        for c in cands:
            if all(usage(r, c, c + te[t]) + f <= cap[r] + 1e-9 for r, f in w["roles"].items()):
                break
        if c > es + 1e-9:
            blocker = max((x for x in placed if abs(finish[x] - c) < 1e-9), key=lambda x: start[x])
            edges.append((blocker, t))
        start[t], finish[t] = c, c + te[t]; placed.append(t); pending.remove(t)
    return edges


RES_EDGES = level_resources()
for a, b in RES_EDGES:
    WBS[IDX[b]]["deps"] = WBS[IDX[b]]["deps"] + [a]
    WBS[IDX[b]].setdefault("deps_recurso", []).append(a)
ORDER = topo()



def pert_sample(rng, w, n):
    """Distribución beta-PERT (lambda = 4)."""
    o, m, p = w["o"], w["m"], w["p"]
    if p == o: return np.full(n, float(m))
    a = 1 + 4 * (m - o) / (p - o)
    b = 1 + 4 * (p - m) / (p - o)
    return o + rng.beta(a, b, n) * (p - o)


def schedule(dur: np.ndarray):
    """CPM vectorizado: dur[k, i] = duración de la tarea i en la iteración k."""
    es = np.zeros_like(dur); ef = np.zeros_like(dur)
    for i in ORDER:
        deps = [IDX[d] for d in WBS[i]["deps"]]
        es[:, i] = ef[:, deps].max(axis=1) if deps else 0
        ef[:, i] = es[:, i] + dur[:, i]
    total = ef.max(axis=1)
    # Holgura total por pasada hacia atrás para el índice de criticidad.
    lf = np.repeat(total[:, None], dur.shape[1], axis=1).astype(float)
    succ = {i: [j for j in range(len(WBS)) if WBS[i]["id"] in WBS[j]["deps"]] for i in range(len(WBS))}
    for i in reversed(ORDER):
        if succ[i]:
            lf[:, i] = np.min(np.stack([lf[:, j] - dur[:, j] for j in succ[i]], axis=1), axis=1)
    slack = lf - ef
    return es, ef, total, slack


def labor_cost(dur: np.ndarray, total: np.ndarray) -> np.ndarray:
    cost = np.zeros(dur.shape[0])
    for i, w in enumerate(WBS):
        weekly = sum(RATE_W[r] * f for r, f in w["roles"].items())
        cost += dur[:, i] * weekly
    loe = sum(RATE_W[r] * f for r, f in A["dedicacion_continua_fte"].items())
    return cost + total * loe


def fixed_cost(total_weeks: np.ndarray) -> np.ndarray:
    months = total_weeks / SEM
    return A["licencias_unica_vez_cop"] + months * (A["nube_mensual_cop"] + A["herramientas_mensual_cop"])


def llm_cost() -> dict:
    L = A["llm"]; out = {}
    for name, g in L["agentes"].items():
        usd = g["ejecuciones"] * (g["tokens_entrada"] * L["precio_usd_por_millon_entrada"] + g["tokens_salida"] * L["precio_usd_por_millon_salida"]) / 1e6
        out[name] = {"usd": round(usd, 2), "cop": round(usd * A["trm_cop_por_usd"])}
    out["total"] = {"usd": round(sum(v["usd"] for v in out.values()), 2), "cop": sum(v["cop"] for v in out.values())}
    return out


def effort_pw(wid: str) -> float:
    w = WBS[IDX[wid]]
    return pert_mean(w) * sum(w["roles"].values())


def run() -> dict:
    rng = np.random.default_rng(A["semilla"])
    n = A["iteraciones_monte_carlo"]
    dur = np.stack([pert_sample(rng, w, n) for w in WBS], axis=1)
    es, ef, total, slack = schedule(dur)
    llm = llm_cost()
    cost = labor_cost(dur, total) + fixed_cost(total) + llm["total"]["cop"]
    crit = (slack <= 1e-6).mean(axis=0)

    mean_d = np.array([[pert_mean(w) for w in WBS]])
    mes, mef, mtotal, mslack = schedule(mean_d)
    det_cost = float(labor_cost(mean_d, mtotal)[0] + fixed_cost(mtotal)[0] + llm["total"]["cop"])
    labor_det = float(labor_cost(mean_d, mtotal)[0])
    fixed_det = float(fixed_cost(mtotal)[0])
    critical_path = [WBS[i]["id"] for i in ORDER if mslack[0, i] <= 1e-6]

    # Carga semanal por rol (cronograma de duraciones esperadas)
    weeks = int(np.ceil(mtotal[0]))
    load = {r: np.zeros(weeks) for r in A["costo_mensual_rol_cop"]}
    for i, w in enumerate(WBS):
        for r, f in w["roles"].items():
            s, e = mes[0, i], mef[0, i]
            for k in range(weeks):
                load[r][k] += f * max(0.0, min(e, k + 1) - max(s, k))
    for r, f in A["dedicacion_continua_fte"].items():
        load[r] += f
    peaks = {r: round(float(v.max()), 2) for r, v in load.items()}
    over = {r: p for r, p in peaks.items() if p > A["capacidad_equipo_fte"][r] + 1e-9}

    # Punto de equilibrio (esfuerzo en personas-semana)
    e_m, e_a = effort_pw("6.1"), effort_pw("6.2")
    E0_agents = sum(effort_pw(x) for x in ["5.1", "5.2", "5.3", "5.4", "5.5", "7.2"])
    E0_full = E0_agents + sum(effort_pw(x) for x in ["4.1", "4.2", "4.3", "4.4", "3.3"])
    pct = lambda a, q: float(np.percentile(a, q))
    res = {
        "deterministico": {"semanas": round(float(mtotal[0]), 1), "meses": round(float(mtotal[0]) / SEM, 1),
                            "costo_cop": round(det_cost), "mano_obra_cop": round(labor_det), "fijos_cop": round(fixed_det),
                            "ruta_critica": critical_path},
        "monte_carlo": {"iteraciones": n,
                        "semanas": {"P10": round(pct(total, 10), 1), "P50": round(pct(total, 50), 1), "P80": round(pct(total, 80), 1), "P90": round(pct(total, 90), 1)},
                        "costo_cop": {"P10": round(pct(cost, 10)), "P50": round(pct(cost, 50)), "P80": round(pct(cost, 80)), "P90": round(pct(cost, 90))},
                        "prob_12_meses": round(float((total <= 12 * SEM).mean()), 3)},
        "reserva_contingencia": {"semanas": round(pct(total, 80) - pct(total, 50), 1), "cop": round(pct(cost, 80) - pct(cost, 50))},
        "criticidad": {WBS[i]["id"]: round(float(crit[i]), 3) for i in range(len(WBS))},
        "llm": llm,
        "carga_pico_fte": peaks, "sobreasignacion": over,
        "punto_equilibrio": {"e_m_pw": round(e_m, 1), "e_a_pw": round(e_a, 1), "ahorro_por_pais_pw": round(e_m - e_a, 1),
                             "E0_agentes_pw": round(E0_agents, 1), "n_agentes": round(E0_agents / (e_m - e_a), 2),
                             "E0_con_kit_pw": round(E0_full, 1), "n_con_kit": round(E0_full / (e_m - e_a), 2)},
        "dependencias_recurso": [{"de": a, "a": b} for a, b in RES_EDGES],
        "tareas": [{"id": w["id"], "nombre": w["nombre"], "fase": w["fase"], "o": w["o"], "m": w["m"], "p": w["p"],
                    "te": round(pert_mean(w), 2), "inicio": round(float(mes[0, i]), 2), "fin": round(float(mef[0, i]), 2),
                    "holgura": round(float(mslack[0, i]), 2), "esfuerzo_pw": round(effort_pw(w["id"]), 1),
                    "roles": w["roles"], "deps": [d for d in w["deps"] if d not in w.get("deps_recurso", [])], "deps_recurso": w.get("deps_recurso", [])} for i, w in enumerate(WBS)],
    }
    np.save(OUT / "mc_total.npy", total); np.save(OUT / "mc_cost.npy", cost)
    (OUT / "load.json").write_text(json.dumps({r: v.round(3).tolist() for r, v in load.items()}))
    (OUT / "results.json").write_text(json.dumps(res, ensure_ascii=False, indent=1))
    return res


if __name__ == "__main__":
    r = run()
    print(json.dumps({k: r[k] for k in ("deterministico", "monte_carlo", "reserva_contingencia", "llm", "carga_pico_fte", "sobreasignacion", "punto_equilibrio")}, ensure_ascii=False, indent=1))
    print("criticidad>0.3:", {k: v for k, v in r["criticidad"].items() if v > 0.3})
