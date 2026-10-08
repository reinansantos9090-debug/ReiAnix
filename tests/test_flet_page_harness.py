"""Small Flet Page async harness used by UI tests.

It mirrors Page.run_task semantics closely enough for synchronous unit tests:
callable callbacks are invoked, awaitables are awaited when no loop is running,
and scheduled when an event loop is already active.
"""
from __future__ import annotations

import asyncio
import inspect


class AsyncRunTaskMixin:
    async def _run_task_and_drain(self, task_or_factory, *args, **kwargs):
        result = task_or_factory(*args, **kwargs) if callable(task_or_factory) else task_or_factory
        if inspect.isawaitable(result):
            result = await result

        while True:
            handles = getattr(self, "_run_task_handles", set())
            pending = tuple(task for task in handles if not task.done())
            if not pending:
                return result
            await asyncio.gather(*pending)

    def run_task(self, task_or_factory, *args, **kwargs):
        try:
            asyncio.get_running_loop()
        except RuntimeError:
            return asyncio.run(self._run_task_and_drain(task_or_factory, *args, **kwargs))

        result = task_or_factory(*args, **kwargs) if callable(task_or_factory) else task_or_factory
        if not inspect.isawaitable(result):
            return result

        loop = asyncio.get_running_loop()
        task = loop.create_task(result)
        handles = getattr(self, "_run_task_handles", None)
        if handles is None:
            handles = self._run_task_handles = set()
        handles.add(task)

        def finish(done):
            handles.discard(done)
            try:
                done.exception()
            except asyncio.CancelledError:
                pass

        task.add_done_callback(finish)
        return task
