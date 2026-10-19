"""Lector de la especificación con procedencia: secciones, tabla de campos y catálogos (RAG con trazas)."""
from __future__ import annotations

import hashlib
import re
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from . import POC_HOME

SPEC = POC_HOME / "specs" / "perfil-alfa-v2.1.md"


@dataclass(frozen=True)
class Section:
    id: str
    title: str
    text: str


@dataclass(frozen=True)
class FieldSpec:
    target: str
    description: str
    kind: str
    catalog: str | None
    section: str = "§6"


def sha256() -> str:
    return hashlib.sha256(SPEC.read_bytes()).hexdigest()


def sections() -> list[Section]:
    out, cur, buf = [], None, []
    for line in SPEC.read_text(encoding="utf-8").splitlines():
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


def fields() -> list[FieldSpec]:
    sec = next(s for s in sections() if s.id == "§6")
    out = []
    for line in sec.text.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) == 4 and "/" in cells[0] and not cells[0].startswith("---"):
            out.append(FieldSpec(cells[0], cells[1], cells[2], None if cells[3] == "—" else cells[3]))
    return out


def literal_for(target: str) -> str | None:
    """Busca en la especificación el literal exacto exigido para un elemento (p. ej. §2.1)."""
    for s in sections():
        if f"`{target}`" in s.text:
            m = re.search(r"literal `([^`]+)`", s.text)
            if m:
                return m.group(1)
    return None


def normalize(text: str) -> list[str]:
    t = unicodedata.normalize("NFKD", text.lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    stop = {"de", "del", "la", "el", "los", "las", "o", "y", "a", "en", "que", "se", "por", "un", "una", "al"}
    return [w[:5] for w in re.findall(r"[a-z0-9]+", t) if w not in stop]


def search(query: str, k: int = 3) -> list[dict]:
    q = set(normalize(query))
    scored = []
    for s in sections():
        words = normalize(s.title + " " + s.text)
        score = sum(1 for w in words if w in q) / (1 + len(words) ** 0.5)
        scored.append((score, s))
    scored.sort(key=lambda x: -x[0])
    digest = sha256()
    return [{"section": s.id, "title": s.title, "text": s.text[:1200], "sourceSha256": digest, "score": round(sc, 3)}
            for sc, s in scored[:k] if sc > 0]
