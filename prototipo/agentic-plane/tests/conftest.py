import os
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))


@pytest.fixture()
def temp_db(tmp_path, monkeypatch):
    monkeypatch.setenv("TFG_DB", str(tmp_path / "test.db"))
    return tmp_path / "test.db"
