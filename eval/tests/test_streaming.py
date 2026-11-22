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


def test_latency_ignores_untimed_words_but_keeps_real_zero_start():
    from providers.base import StreamResult, FinalWord
    from scoring.metrics import calculate_streaming_metrics
    stream = StreamResult(final_text='hello', events=[], final_words=[
        FinalWord(word='unknown', start_s=None, final_t_recv=99),
        FinalWord(word='hello', start_s=0, final_t_recv=2),
        FinalWord(word='there', start_s=3, final_t_recv=7),
    ], audio_duration_s=10)
    metrics = calculate_streaming_metrics('hello', stream)
    assert metrics['final_latency_med_s'] == 3
    stream.final_words = stream.final_words[:1]
    metrics = calculate_streaming_metrics('hello', stream)
    assert metrics['final_latency_med_s'] is None
    assert metrics['final_latency_p95_s'] is None
    assert metrics['flicker'] == 0


def test_new_translated_recording_retains_real_zero_on_roundtrip():
    from providers.base import StreamResult, FinalWord
    stream = StreamResult(final_text='Szia', events=[], final_words=[
        FinalWord(word='Szia', start_s=0, final_t_recv=2),
    ], audio_duration_s=5, model='stt-rt-v6+translate:hu')
    replay = StreamResult.model_validate_json(stream.model_dump_json())
    assert replay.final_words[0].start_s == 0
