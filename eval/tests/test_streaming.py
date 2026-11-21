"""Transport failures must not silently turn truncated audio into a fixture."""
import asyncio
from pathlib import Path
import sys

import pytest
import websockets

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from providers import streaming


def test_abnormal_websocket_close_propagates(monkeypatch):
    class BrokenConnection:
        async def __aenter__(self):
            return self
        async def __aexit__(self, *args):
            pass
        async def send(self, message):
            pass
        def __aiter__(self):
            return self
        async def __anext__(self):
            raise websockets.ConnectionClosedError(None, None)
    monkeypatch.setattr(streaming.websockets, "connect", lambda *a, **kw: BrokenConnection())
    monkeypatch.setattr(streaming, "read_pcm_chunks", lambda *a: ([], 0))
    with pytest.raises(websockets.ConnectionClosedError):
        asyncio.run(streaming._run("unused", "unused", lambda *a: None, None, None, "", 1))
