from decimal import Decimal

import pytest
from hypothesis import given, settings, strategies as st

from tfg_agents.canonical import Line, generate, fixtures


@settings(max_examples=40, deadline=None)
@given(seed=st.integers(min_value=0, max_value=10_000), n=st.integers(min_value=1, max_value=15))
def test_generated_invoices_respect_invariants(seed, n):
    for inv in generate(n, seed):
        base = sum(l.amount for l in inv.lines)
        assert base == inv.totals.baseAmount
        assert inv.totals.baseAmount + inv.totals.taxAmount == inv.totals.payableAmount
        for l in inv.lines:
            assert abs(l.quantity * l.unitPrice - l.amount) <= Decimal("0.01")


def test_line_rejects_incoherent_amount():
    with pytest.raises(ValueError):
        Line(number=1, description="x", quantity=Decimal(2), unitPrice=Decimal("10.00"),
             amount=Decimal("25.00"), taxCode="IVA19", taxRate=Decimal("19.00"))


def test_fixtures_cover_every_category():
    fx = fixtures()
    assert {f.documentType for f in fx} == {"INVOICE", "CREDIT_NOTE", "DEBIT_NOTE"}
    assert {f.customer.idType for f in fx} == {"NIT", "CC", "CE", "PAS"}
    assert {l.taxCode for f in fx for l in f.lines} == {"IVA19", "IVA5", "EXENTO"}
    assert any("|" in l.description for f in fx for l in f.lines), "faltan datos adversarios (D6)"
    assert any("|" in f.customer.name for f in fx)
