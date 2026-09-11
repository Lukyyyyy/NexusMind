import asyncio
import unittest
from types import SimpleNamespace
from cancellable_api import parse_request, Worker


class Request:
    headers = {"content-type": "multipart/form-data; boundary=test"}
    def __init__(self):
        self.gone = False
    async def body(self):
        return b"pdf"
    async def is_disconnected(self):
        return self.gone


class FakeWorker:
    def __init__(self):
        self.lock = asyncio.Lock()
        self.started = asyncio.Event()
        self.result = asyncio.Event()
        self.stops = 0
        self.calls = 0
    async def parse(self, body, content_type):
        self.calls += 1
        self.started.set()
        await self.result.wait()
        return SimpleNamespace(content=b'{"markdown":"ok"}', status_code=200, headers={"content-type":"application/json"})
    async def stop(self):
        self.stops += 1
    def was_oom_killed(self):
        return False


class FakeOomWorker(FakeWorker):
    async def parse(self, body, content_type):
        raise RuntimeError("worker exited")
    def was_oom_killed(self):
        return True


class CancellationTests(unittest.IsolatedAsyncioTestCase):
    async def test_disconnect_stops_only_active_worker_and_next_request_runs(self):
        worker, request = FakeWorker(), Request()
        task = asyncio.create_task(parse_request(worker, request))
        await asyncio.wait_for(worker.started.wait(), 1)
        request.gone = True
        response = await asyncio.wait_for(task, 1)
        self.assertEqual(response.status_code, 499)
        self.assertEqual(worker.stops, 1)
        self.assertFalse(worker.lock.locked())
        worker.result.set()
        response = await parse_request(worker, Request())
        self.assertEqual(response.status_code, 200)
        self.assertEqual(worker.calls, 2)
        self.assertEqual(worker.stops, 2)

    async def test_cancel_queued_request_does_not_stop_active_worker(self):
        worker = FakeWorker()
        active = asyncio.create_task(parse_request(worker, Request()))
        await asyncio.wait_for(worker.started.wait(), 1)
        queued_request = Request()
        queued = asyncio.create_task(parse_request(worker, queued_request))
        queued_request.gone = True
        self.assertEqual((await asyncio.wait_for(queued, 1)).status_code, 499)
        self.assertEqual(worker.stops, 0)
        self.assertTrue(worker.lock.locked())
        worker.result.set()
        await active
        self.assertEqual(worker.stops, 1)
        self.assertFalse(worker.lock.locked())

    async def test_cancel_handler_releases_worker_and_lock(self):
        worker = FakeWorker()
        task = asyncio.create_task(parse_request(worker, Request()))
        await asyncio.wait_for(worker.started.wait(), 1)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await task
        self.assertEqual(worker.stops, 1)
        self.assertFalse(worker.lock.locked())

    async def test_worker_process_is_really_terminated(self):
        import sys
        worker = Worker([sys.executable, "-c", "import time; time.sleep(60)"])
        await worker.start()
        process = worker.process
        self.assertIsNone(process.returncode)
        await worker.close()
        self.assertIsNotNone(process.returncode)
        self.assertIsNone(worker.output)

    async def test_oom_is_reported_and_worker_is_stopped(self):
        worker = FakeOomWorker()
        response = await parse_request(worker, Request())
        self.assertEqual(response.status_code, 507)
        self.assertEqual(worker.stops, 1)


if __name__ == "__main__":
    unittest.main()
