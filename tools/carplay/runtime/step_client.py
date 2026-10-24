"""Bounded synchronous client for the disconnected --research-step-stream host.

Calls belong on a worker thread. This tool does not connect to an iPhone or screen.
"""
from __future__ import annotations
import os
from pathlib import Path
import plistlib
import select
import subprocess
import time

MAX_BYTES = 1024 * 1024


class StepClient:
    def __init__(self, host: Path, resources: Path, bridge: Path, probe: Path, timeout: float = 5):
        if not 0 < timeout <= 30:
            raise ValueError('Invalid step timeout')
        self.timeout = timeout
        self.process = subprocess.Popen([str(host), str(resources), str(bridge), str(probe),
                                         '--research-allow-incomplete-cp14', '--research-step-stream'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self._response()
        except Exception:
            self._terminate()
            raise

    def _read(self, size: int, deadline: float) -> bytes:
        chunks = []
        while size:
            remaining = deadline - time.monotonic()
            if remaining <= 0 or not select.select([self.process.stdout], [], [], remaining)[0]:
                raise TimeoutError('Research step timed out')
            chunk = os.read(self.process.stdout.fileno(), size)
            if not chunk:
                raise RuntimeError('Research host ended before its response')
            chunks.append(chunk)
            size -= len(chunk)
        return b''.join(chunks)

    def _response(self):
        deadline = time.monotonic() + self.timeout
        size = int.from_bytes(self._read(4, deadline), 'big')
        if not 40 <= size <= MAX_BYTES:
            raise ValueError('Research response size refused')
        response = plistlib.loads(self._read(size, deadline))
        if not isinstance(response, dict) or response.get('ok') is not True:
            raise RuntimeError('Research step refused; session terminated')
        return response.get('data')

    def request(self, opcode: int, payload: bytes = b''):
        if not 0 <= opcode <= 9 or not isinstance(payload, bytes) or len(payload) > MAX_BYTES:
            raise ValueError('Invalid research request')
        try:
            self.process.stdin.write(bytes([opcode]) + len(payload).to_bytes(4, 'big') + payload)
            self.process.stdin.flush()
            return self._response()
        except Exception:
            self._terminate()
            raise

    def _terminate(self):
        if self.process.poll() is None:
            self.process.kill()
        self.process.wait(timeout=5)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()

    def close(self):
        if self.process.poll() is None:
            try:
                self.request(0)
                self.process.wait(timeout=1)
            finally:
                self._terminate()
        else:
            self._terminate()

    def __enter__(self): return self
    def __exit__(self, *_): self.close()
