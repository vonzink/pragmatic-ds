"""Where a stage's CPU work runs: off the event loop, gated, and only for a live caller.

The stage handlers are `async def` because they read multipart bodies; the work they
then do (pypdfium2 rendering, pdfplumber extraction, the OCR ladder, layout rasters) is
CPU-bound and synchronous. Calling it inline blocked the one event loop, which had three
consequences in production (2026-09-16):

  1. The worker could do exactly one request at a time — fine on 2 vCPUs — but every
     other request, INCLUDING /health, sat unread in the kernel backlog meanwhile.
  2. The engine's read timeout fired while a request was still in that backlog. When its
     turn came the worker executed it in full for a caller that had hung up minutes ago.
  3. The engine retried, queueing another copy of the same dead work. Three jobs × three
     attempts = nine dead /v1/text runs kept the worker at 100% CPU for 42 minutes.

So: run the work in a thread (the loop stays free to accept, answer /health, and notice
disconnects), take a gate first so the box is never oversubscribed, and — after the
wait for the gate, which is exactly where the dead requests were — ask whether the caller
is still there before starting. A caller that has gone gets `499 CALLER_GONE` and no work.

Nothing here touches document content; the log line carries the endpoint name only.
"""

import asyncio
import logging
import os
import weakref
from collections.abc import Callable
from typing import Any, TypeVar

from starlette.concurrency import run_in_threadpool
from starlette.requests import Request

from pragmaticds_docengine_worker.wire import error

logger = logging.getLogger(__name__)

T = TypeVar("T")

#: How many stage requests may execute at once. Default 1: on the 2-vCPU production box
#: a second concurrent render or OCR only slows both down, and the engine's per-call
#: timeout then fires on work that would have finished in time alone.
CONCURRENCY_ENV = "DOCENGINE_WORKER_CONCURRENCY"

#: Nginx's "client closed request" — no standard status fits and the caller is gone anyway.
CALLER_GONE_STATUS = 499

# One gate per event loop: a Semaphore binds to the loop it is first awaited on, and the
# test client builds a fresh loop per client, so a module-level instance would be reused
# across loops and fail.
_gates: "weakref.WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Semaphore]" = (
    weakref.WeakKeyDictionary()
)


def configured_concurrency() -> int:
    """`DOCENGINE_WORKER_CONCURRENCY`, floored at 1; anything unparseable is 1."""
    try:
        return max(1, int(os.environ.get(CONCURRENCY_ENV, "1")))
    except ValueError:
        return 1


def _gate() -> asyncio.Semaphore:
    loop = asyncio.get_running_loop()
    gate = _gates.get(loop)
    if gate is None:
        gate = asyncio.Semaphore(configured_concurrency())
        _gates[loop] = gate
    return gate


async def run_stage_work(
    request: Request, endpoint: str, fn: Callable[..., T], *args: Any, **kwargs: Any
) -> T:
    """Run `fn(*args, **kwargs)` in a worker thread once the gate admits this request,
    unless the caller has disconnected by then — in which case raise 499 and run nothing.
    """
    async with _gate():
        if await request.is_disconnected():
            # Numbers-and-names only: which endpoint's caller gave up, never what it sent.
            logger.info("caller gone before work started endpoint=%s", endpoint)
            raise error(CALLER_GONE_STATUS, "CALLER_GONE")
        return await run_in_threadpool(fn, *args, **kwargs)
