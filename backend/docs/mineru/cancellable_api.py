"""Synchronous MinerU gateway: one isolated worker per request.

The public API stays alive. Each completed, failed, or cancelled request stops its
worker process group; waiting requests never stop another request's worker. Model
files stay cached on disk.
"""
import asyncio
import contextlib
import logging
import os
import signal
import tempfile
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.responses import Response

log = logging.getLogger("mineru.cancel")


class Worker:
    def __init__(self, command=None, port=8001):
        self.command = command or ["mineru-api", "--host", "127.0.0.1", "--port", str(port)]
        self.url = f"http://127.0.0.1:{port}"
        self.process = None
        self.output = None
        self.lock = asyncio.Lock()
        self.client = httpx.AsyncClient(timeout=httpx.Timeout(None, connect=2), trust_env=False)

    async def start(self):
        if self.process is not None and self.process.returncode is None:
            return
        await self.stop()
        self.output = tempfile.TemporaryDirectory(prefix="mineru-worker-")
        env = dict(os.environ, MINERU_API_OUTPUT_ROOT=self.output.name)
        self.process = await asyncio.create_subprocess_exec(
            *self.command, env=env, start_new_session=True
        )
        log.info("Started parsing worker pid=%s", self.process.pid)

    async def stop(self):
        process, self.process = self.process, None
        if process is not None and process.returncode is None:
            # Kill the whole worker process group, including any parser children.
            with contextlib.suppress(ProcessLookupError):
                os.killpg(process.pid, signal.SIGTERM)
            try:
                await asyncio.wait_for(process.wait(), 2)
            except asyncio.TimeoutError:
                with contextlib.suppress(ProcessLookupError):
                    os.killpg(process.pid, signal.SIGKILL)
                await process.wait()
            log.info("Stopped parsing worker pid=%s", process.pid)
        if self.output is not None:
            self.output.cleanup()
            self.output = None

    async def ready(self):
        await self.start()
        async with asyncio.timeout(90):
            while True:
                if self.process.returncode is not None:
                    raise RuntimeError("MinerU worker exited during startup")
                try:
                    response = await self.client.get(self.url + "/openapi.json")
                    if response.status_code == 200:
                        return
                except httpx.TransportError:
                    pass
                await asyncio.sleep(0.1)

    async def parse(self, body, content_type):
        await self.ready()
        return await self.client.post(
            self.url + "/file_parse", content=body,
            headers={"content-type": content_type}
        )

    def was_oom_killed(self):
        return self.process is not None and self.process.returncode == -signal.SIGKILL

    async def close(self):
        await self.stop()
        await self.client.aclose()


async def disconnected(request):
    while not await request.is_disconnected():
        await asyncio.sleep(0.1)


async def parse_request(worker, request):
    body = await request.body()
    watch = asyncio.create_task(disconnected(request))
    acquisition = asyncio.create_task(worker.lock.acquire())
    job = None
    acquired = False
    try:
        done, _ = await asyncio.wait({watch, acquisition}, return_when=asyncio.FIRST_COMPLETED)
        if watch in done:
            return Response(status_code=499)
        await acquisition
        acquired = True
        job = asyncio.create_task(worker.parse(body, request.headers.get("content-type", "application/octet-stream")))
        done, _ = await asyncio.wait({watch, job}, return_when=asyncio.FIRST_COMPLETED)
        if watch in done:
            log.info("Client disconnected; cancelling active parse")
            job.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await job
            return Response(status_code=499)
        result = await job
        return Response(content=result.content, status_code=result.status_code,
                        media_type=result.headers.get("content-type", "application/json"))
    except asyncio.CancelledError:
        if acquired:
            if job is not None:
                job.cancel()
                with contextlib.suppress(asyncio.CancelledError):
                    await job
        raise
    except Exception:
        log.exception("Parsing worker failed")
        status_code = 507 if worker.was_oom_killed() else 502
        detail = "MinerU parsing worker was killed (likely out of memory)" if status_code == 507 \
            else "MinerU parsing worker failed"
        return Response(content=f'{{"detail":"{detail}"}}', status_code=status_code,
                        media_type="application/json")
    finally:
        watch.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await watch
        if not acquisition.done():
            acquisition.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await acquisition
        # The lock may have been granted in the same tick as a queued disconnect.
        if acquired or (acquisition.done() and not acquisition.cancelled() and acquisition.exception() is None):
            try:
                await worker.stop()
            finally:
                worker.lock.release()


worker = Worker()


@asynccontextmanager
async def lifespan(app):
    try:
        yield
    finally:
        await worker.close()


app = FastAPI(title="NexusMind MinerU", lifespan=lifespan)


@app.post("/file_parse")
async def file_parse(request: Request):
    return await parse_request(worker, request)
