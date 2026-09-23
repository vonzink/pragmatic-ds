"""Stage work must run OFF the event loop, one request at a time, and never for a caller
that has already hung up.

Production 2026-09-16: the stage handlers called pypdfium2 / pdfplumber / OCR directly
inside `async def`, so the single uvicorn process could do exactly one thing, could not
answer /health while doing it, and — worse — executed every request whose caller had
timed out minutes earlier. Nine dead /v1/text runs (three jobs × three retries) kept the
worker at 100% CPU for 42 minutes after one Classify click.
"""

import asyncio
import threading
import time

import pytest
from starlette.exceptions import HTTPException

from pragmaticds_docengine_worker import work


class _Request:
    """Just enough of starlette.requests.Request for run_stage_work."""

    def __init__(self, disconnected: bool):
        self._disconnected = disconnected

    async def is_disconnected(self) -> bool:
        return self._disconnected


def test_work_runs_off_the_event_loop_and_returns_its_result():
    seen = {}

    def cpu_work(a, b, *, c):
        seen["thread"] = threading.current_thread()
        return a + b + c

    async def scenario():
        return await work.run_stage_work(_Request(disconnected=False), "render", cpu_work, 1, 2, c=3)

    assert asyncio.run(scenario()) == 6
    assert seen["thread"] is not threading.main_thread()


def test_a_caller_that_already_hung_up_gets_no_work_done():
    calls = []

    async def scenario():
        await work.run_stage_work(_Request(disconnected=True), "text", calls.append, "ran")

    with pytest.raises(HTTPException) as raised:
        asyncio.run(scenario())
    assert raised.value.status_code == work.CALLER_GONE_STATUS
    assert raised.value.detail["error"] == "CALLER_GONE"
    assert calls == []


def test_work_is_gated_one_at_a_time_by_default(monkeypatch):
    monkeypatch.delenv(work.CONCURRENCY_ENV, raising=False)
    running = {"now": 0, "peak": 0}
    lock = threading.Lock()

    def cpu_work():
        with lock:
            running["now"] += 1
            running["peak"] = max(running["peak"], running["now"])
        time.sleep(0.05)
        with lock:
            running["now"] -= 1

    async def scenario():
        request = _Request(disconnected=False)
        await asyncio.gather(
            *(work.run_stage_work(request, "ocr", cpu_work) for _ in range(4))
        )

    asyncio.run(scenario())
    assert running["peak"] == 1


def test_the_disconnect_check_happens_after_waiting_for_the_gate():
    """A request that hung up WHILE queued behind another must be dropped, not run:
    that queue is exactly where the dead work came from."""
    request = _Request(disconnected=False)
    order = []

    def slow():
        time.sleep(0.05)
        order.append("slow")

    def never():
        order.append("never")

    async def scenario():
        first = asyncio.ensure_future(work.run_stage_work(request, "render", slow))
        await asyncio.sleep(0.01)  # first holds the gate
        request._disconnected = True  # the queued caller gives up
        with pytest.raises(HTTPException):
            await work.run_stage_work(request, "render", never)
        await first

    asyncio.run(scenario())
    assert order == ["slow"]


def test_concurrency_env_widens_the_gate(monkeypatch):
    monkeypatch.setenv(work.CONCURRENCY_ENV, "2")
    assert work.configured_concurrency() == 2
    monkeypatch.setenv(work.CONCURRENCY_ENV, "0")
    assert work.configured_concurrency() == 1
    monkeypatch.setenv(work.CONCURRENCY_ENV, "many")
    assert work.configured_concurrency() == 1


def test_a_disconnected_render_request_is_499_and_renders_nothing(client, monkeypatch):
    from pragmaticds_docengine_worker import render_routes

    async def gone(self):
        return True

    monkeypatch.setattr("starlette.requests.Request.is_disconnected", gone)
    monkeypatch.setattr(
        render_routes, "render_source", lambda *a, **k: pytest.fail("rendered for a dead caller")
    )
    response = client.post(
        "/v1/render",
        files={"file": ("f.pdf", b"%PDF-1.4 not really", "application/pdf")},
        data={"request": '{"pages": [0], "dpi": 72}'},
    )
    assert response.status_code == work.CALLER_GONE_STATUS
    assert response.json()["error"] == "CALLER_GONE"
