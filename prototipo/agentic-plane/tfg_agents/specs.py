"""Especificaciones de proveedores con procedencia: secciones, tablas de campos y huella SHA-256 del documento.

Generaliza ``spec_reader`` (que solo conoce el Perfil Alfa v2.1) a todas las especificaciones de ``specs/``.
Cada resultado lleva el documento y su huella, para que una regla o propuesta se pueda rastrear a su fuente.
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

from . import POC_HOME
from .spec_reader import normalize

SPECS_DIR = POC_HOME / "specs"


@dataclass(frozen=True)
class Section:
    id: str
    title: str
    text: str


@dataclass(frozen=True)
class FieldRow:
    target: str
    description: str
    kind: str
    catalog: str | None
    section: str


@dataclass(frozen=True)
class SpecDoc:
    name: str
    path: Path

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.path.read_bytes()).hexdigest()

    def sections(self) -> list[Section]:
        out, cur, buf = [], None, []
        for line in self.path.read_text(encoding="utf-8").splitlines():
            m = re.match(r"^#{2,3} (§[\w.]+) (.*)$", line)
            if m:
                if cur:
                    out.append(Section(cur[0], cur[1], "\n".join(buf).strip()))
                cur, buf = (m.group(1), m.group(2)), []
            elif cur:
                buf.append(line)
        if cur:
            out.append(Section(cur[0], cur[1], "\n".join(buf).strip()))
        return out

    def field_rows(self) -> list[FieldRow]:
        """Filas de las tablas «Ruta | Descripción | Tipo | Catálogo» de todas las secciones."""
        rows = []
        for s in self.sections():
            for line in s.text.splitlines():
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                if len(cells) >= 3 and cells[0].startswith("/") and not cells[0].startswith("---"):
                    catalog = cells[3] if len(cells) > 3 and cells[3] not in ("—", "-", "") else None
                    rows.append(FieldRow(cells[0], cells[1], cells[2], catalog, s.id))
        return rows

    def catalog(self, name: str) -> list[list[str]]:
        """Filas de la tabla del catálogo «Catálogo <name>» (código y columnas descriptivas)."""
        for s in self.sections():
            if s.title.startswith(f"Catálogo {name}"):
                rows = []
                for line in s.text.splitlines():
                    cells = [c.strip() for c in line.strip().strip("|").split("|")]
                    if len(cells) >= 2 and not cells[0].startswith("---") and cells[0] != "Código":
                        rows.append(cells)
                return rows
        return []

    def literal(self) -> str | None:
        """Literal exacto exigido por la especificación (p. ej. la versión del formato)."""
        for s in self.sections():
            m = re.search(r"literal `([^`]+)`", s.text)
            if m:
                return m.group(1)
        return None

    def section_mentioning(self, pattern: str) -> Section | None:
        rx = re.compile(pattern, re.I)
        return next((s for s in self.sections() if rx.search(s.title + " " + s.text)), None)

    def search(self, query: str, k: int = 3) -> list[dict]:
        q = set(normalize(query))
        scored = []
        for s in self.sections():
            words = normalize(s.title + " " + s.text)
            score = sum(1 for w in words if w in q) / (1 + len(words) ** 0.5)
            scored.append((score, s))
        scored.sort(key=lambda x: -x[0])
        digest = self.sha256
        return [{"spec": self.path.name, "section": s.id, "title": s.title, "text": s.text[:1200],
                 "sourceSha256": digest, "score": round(sc, 3)} for sc, s in scored[:k] if sc > 0]


# Especificación de cada proveedor y país (la misma que usa el perfil del kit).
SPEC_BY_TARGET = {
    ("alfa", "CO"): "perfil-alfa-v2.1.md",
    ("beta", "PE"): "formato-beta-pe-v1.md",
    ("gamma", "XX"): "proveedor-gamma-v1.md",
}


@lru_cache(maxsize=None)
def spec(name: str) -> SpecDoc:
    if "/" in name or "\\" in name or ".." in name:
        raise ValueError(f"Nombre de especificación no válido: {name}")
    file = name if name.endswith(".md") else name + ".md"
    path = SPECS_DIR / file
    if not path.is_file():
        raise KeyError(f"Especificación desconocida: {name}")
    return SpecDoc(path.stem, path)


def spec_for(provider: str, country: str) -> SpecDoc:
    return spec(SPEC_BY_TARGET[(provider.lower(), country.upper())])


def all_specs() -> list[SpecDoc]:
    return [spec(p.name) for p in sorted(SPECS_DIR.glob("*.md"))]
