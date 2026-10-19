"""Modelo canónico de comprobante y generador de datos sintéticos reproducibles."""
from __future__ import annotations

import random
from datetime import date, timedelta
from decimal import Decimal, ROUND_HALF_UP
from typing import Literal

from pydantic import BaseModel, Field, model_validator

DocType = Literal["INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"]
IdType = Literal["NIT", "CC", "CE", "PAS"]
TaxCode = Literal["IVA19", "IVA5", "EXENTO"]
TAX_RATES = {"IVA19": Decimal("19.00"), "IVA5": Decimal("5.00"), "EXENTO": Decimal("0.00")}
CENT = Decimal("0.01")

# Descripciones del modelo canónico: el agente de mapeo las compara con la especificación.
FIELD_DESCRIPTIONS = {
    "documentNumber": "número consecutivo del comprobante emitido",
    "issueDate": "fecha de emisión del documento",
    "documentType": "tipo de documento comercial factura o nota",
    "currency": "moneda del documento",
    "originalDocumentNumber": "número del documento original que se corrige en las notas",
    "supplier.name": "razón social del vendedor emisor",
    "supplier.taxId": "número de identificación tributaria del vendedor emisor",
    "supplier.idType": "tipo de identificación del vendedor emisor",
    "customer.name": "nombre del cliente comprador",
    "customer.taxId": "número de identificación del cliente comprador",
    "customer.idType": "tipo de identificación del cliente comprador",
    "customer.email": "correo electrónico de contacto del cliente",
    "totals.taxAmount": "valor total de impuestos",
    "totals.baseAmount": "subtotal de las líneas antes de impuestos",
    "totals.payableAmount": "valor total a pagar",
    "lines[].number": "consecutivo de la línea",
    "lines[].quantity": "cantidad de unidades de la línea",
    "lines[].unitPrice": "precio por unidad del artículo",
    "lines[].amount": "valor total de la línea sin impuestos",
    "lines[].description": "descripción del producto o servicio",
    "lines[].taxCode": "código interno del impuesto de la línea",
    "lines[].taxRate": "porcentaje de impuesto de la línea",
}


def money(x: Decimal) -> Decimal:
    return x.quantize(CENT, rounding=ROUND_HALF_UP)


class Party(BaseModel):
    name: str
    taxId: str
    idType: IdType
    email: str | None = None


class Line(BaseModel):
    number: int = Field(gt=0)
    description: str
    quantity: Decimal = Field(gt=0)
    unitPrice: Decimal = Field(ge=0)
    amount: Decimal
    taxCode: TaxCode
    taxRate: Decimal

    @model_validator(mode="after")
    def _invariants(self) -> "Line":
        if abs(self.quantity * self.unitPrice - self.amount) > CENT:
            raise ValueError("cantidad × precio ≠ valor de línea")
        if self.taxRate != TAX_RATES[self.taxCode]:
            raise ValueError("la tarifa no corresponde al código interno")
        return self


class Totals(BaseModel):
    baseAmount: Decimal
    taxAmount: Decimal
    payableAmount: Decimal


class InvoiceRequest(BaseModel):
    """Modelo canónico (InvoiceRequest v1.1) registrado en Schema Registry en la arquitectura objetivo."""
    correlationId: str
    documentNumber: str
    documentType: DocType
    issueDate: date
    currency: Literal["COP", "USD", "PEN", "MXN"]
    originalDocumentNumber: str | None = None
    supplier: Party
    customer: Party
    lines: list[Line]
    totals: Totals

    @model_validator(mode="after")
    def _totals(self) -> "InvoiceRequest":
        base = sum((l.amount for l in self.lines), Decimal(0))
        tax = money(sum((l.amount * l.taxRate / 100 for l in self.lines), Decimal(0)))
        if money(base) != self.totals.baseAmount or tax != self.totals.taxAmount:
            raise ValueError("totales incoherentes con las líneas")
        if self.totals.baseAmount + self.totals.taxAmount != self.totals.payableAmount:
            raise ValueError("total a pagar incoherente")
        if (self.documentType != "INVOICE") != (self.originalDocumentNumber is not None):
            raise ValueError("solo las notas llevan documento original")
        return self


_ITEMS = ["Servicio de soporte técnico", "Licencia anual de software", "Consultoría de integración",
          "Mantenimiento preventivo", "Capacitación virtual", "Hosting mensual", "Póliza de servicio",
          "Desarrollo a la medida", "Auditoría de seguridad", "Migración de datos"]
_NAMES = ["Comercial Andina S.A.S.", "Tecnologías del Valle", "Grupo Horizonte", "Distribuidora Central",
          "Servicios Integrales del Norte", "Laboratorio Mirador", "Inversiones Cordillera", "Ana María Rojas",
          "Carlos Pérez", "Logística Pacífico", "Agrícola La Pradera", "Clínica San Rafael"]


def _tax_id(rng: random.Random, kind: str) -> str:
    if kind == "NIT":
        return f"{rng.randint(800000000, 999999999)}-{rng.randint(0, 9)}"
    if kind == "CC":
        return str(rng.randint(10**5, 10**10 - 1))
    letters = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    return "".join(rng.choice(letters) for _ in range(2)) + str(rng.randint(100000, 9999999))


def make_invoice(rng: random.Random, i: int, *, doc_type: str | None = None, id_type: str | None = None,
                 tax_codes: list[str] | None = None, adversarial_text: bool = False,
                 adversarial_name: bool = False) -> InvoiceRequest:
    dt = doc_type or rng.choices(["INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"], [70, 20, 10])[0]
    it = id_type or rng.choices(["NIT", "CC", "CE", "PAS"], [40, 40, 10, 10])[0]
    n_lines = len(tax_codes) if tax_codes else rng.randint(1, 4)
    lines = []
    for k in range(n_lines):
        code = tax_codes[k] if tax_codes else rng.choices(["IVA19", "IVA5", "EXENTO"], [70, 20, 10])[0]
        qty = Decimal(rng.randint(1, 12))
        price = money(Decimal(rng.randint(1000, 500000)) / 100)
        desc = rng.choice(_ITEMS)
        if adversarial_text or rng.random() < 0.05:
            desc = desc.replace(" ", " | ", 1)
        lines.append(Line(number=k + 1, description=desc, quantity=qty, unitPrice=price,
                          amount=money(qty * price), taxCode=code, taxRate=TAX_RATES[code]))
    base = money(sum((l.amount for l in lines), Decimal(0)))
    tax = money(sum((l.amount * l.taxRate / 100 for l in lines), Decimal(0)))
    prefix = {"INVOICE": "FA", "CREDIT_NOTE": "NC", "DEBIT_NOTE": "ND"}[dt]
    name = rng.choice(_NAMES)
    if adversarial_name:
        name = name.replace(" ", " | ", 1)
    return InvoiceRequest(
        correlationId=f"corr-{i:06d}", documentNumber=f"{prefix}-{i:06d}", documentType=dt,
        issueDate=date(2026, 9, 1) + timedelta(days=rng.randint(0, 40)), currency="COP",
        originalDocumentNumber=None if dt == "INVOICE" else f"FA-{rng.randint(1, i + 1):06d}",
        supplier=Party(name="Comercializadora Sintética S.A.", taxId="900123456-7", idType="NIT"),
        customer=Party(name=name, taxId=_tax_id(rng, it), idType=it, email=f"cliente{i}@ejemplo.test"),
        lines=lines, totals=Totals(baseAmount=base, taxAmount=tax, payableAmount=base + tax))


def generate(n: int, seed: int = 2026) -> list[InvoiceRequest]:
    rng = random.Random(seed)
    return [make_invoice(rng, i + 1) for i in range(n)]


def fixtures(seed: int = 7) -> list[InvoiceRequest]:
    """Fixtures curados: cubren cada tipo, cada identificación, cada tributo y datos adversarios."""
    rng = random.Random(seed)
    specs = [
        dict(doc_type="INVOICE", id_type="NIT", tax_codes=["IVA19", "IVA5"]),
        dict(doc_type="INVOICE", id_type="CC", tax_codes=["EXENTO"]),
        dict(doc_type="INVOICE", id_type="CE", tax_codes=["IVA19"]),
        dict(doc_type="INVOICE", id_type="PAS", tax_codes=["IVA5", "IVA19"]),
        dict(doc_type="CREDIT_NOTE", id_type="NIT", tax_codes=["IVA19"]),
        dict(doc_type="CREDIT_NOTE", id_type="PAS", tax_codes=["EXENTO", "IVA19"]),
        dict(doc_type="DEBIT_NOTE", id_type="CC", tax_codes=["IVA5"]),
        dict(doc_type="DEBIT_NOTE", id_type="CE", tax_codes=["IVA19", "EXENTO", "IVA5"]),
        dict(doc_type="INVOICE", id_type="NIT", tax_codes=["IVA19"], adversarial_text=True),
        dict(doc_type="INVOICE", id_type="CC", tax_codes=["IVA19"], adversarial_name=True),
        dict(doc_type="CREDIT_NOTE", id_type="CC", tax_codes=["IVA5", "IVA5"], adversarial_text=True),
        dict(doc_type="INVOICE", id_type="PAS", tax_codes=["IVA19", "IVA19", "IVA5", "EXENTO"]),
    ]
    out = []
    for k, s in enumerate(specs):
        inv = make_invoice(rng, 900000 + k, **s)
        # Cantidades > 1 garantizadas para que los fixtures distingan cantidad de precio.
        out.append(inv)
    return out
