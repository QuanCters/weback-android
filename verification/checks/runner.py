"""
Subprocess Execution Engine with POSIX Process-Group Timeout Management.
Standard-library only, shell=False, start_new_session=True.
"""
from dataclasses import dataclass
import os
import select
import signal
import subprocess
import threading
import time
from pathlib import Path
from typing import Optional
from verification.checks.registry import CheckDefinition
from verification.checks.test_adapter import (
    extract_test_summary_from_output,
    validate_test_summary_integrity,
    parse_test_summary
)

@dataclass
class CheckResult:
    id: str
    status: str  # "PASS", "FAIL", "ERROR", "TIMEOUT"
    exit_code: int
    duration_seconds: float
    output: bytes
    test_summary: Optional[dict[str, int]] = None
    cleanup_failed: bool = False

def _kill_process_group(pgid: int, timeout_limit: float = 2.0) -> bool:
    """
    Terminates a process group using SIGTERM, followed by SIGKILL if needed.
    Returns True if all processes are confirmed terminated, False if cleanup failed.
    """
    start = time.monotonic()
    try:
        os.killpg(pgid, signal.SIGTERM)
    except ProcessLookupError:
        return True
    except OSError:
        pass

    # Wait grace period of 0.5s for processes to exit
    time.sleep(0.5)

    # Check if process group is still alive
    try:
        os.killpg(pgid, 0)
        # Still alive, escalate to SIGKILL
        os.killpg(pgid, signal.SIGKILL)
    except ProcessLookupError:
        return True
    except OSError:
        pass

    # Wait bounded time for SIGKILL to complete
    deadline = start + timeout_limit
    while time.monotonic() < deadline:
        try:
            os.killpg(pgid, 0)
            time.sleep(0.05)
        except ProcessLookupError:
            return True
        except OSError:
            return True

    return False

def execute_check(check: CheckDefinition) -> CheckResult:
    """
    Executes a check definition in a new POSIX process group.
    Captures stdout and stderr without buffer deadlocks using a concurrent reader thread.
    Enforces timeout, exit code preservation, and test summary extraction.
    """
    t0 = time.monotonic()
    work_dir = str(check.cwd) if check.cwd else os.getcwd()

    try:
        proc = subprocess.Popen(
            check.argv,
            cwd=work_dir,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            shell=False,
            start_new_session=True
        )
    except FileNotFoundError as e:
        elapsed = time.monotonic() - t0
        err_msg = f"[EXECUTABLE NOT FOUND: {check.argv[0]}] {e}\n".encode("utf-8")
        return CheckResult(
            id=check.id,
            status="ERROR",
            exit_code=127,
            duration_seconds=elapsed,
            output=err_msg
        )
    except PermissionError as e:
        elapsed = time.monotonic() - t0
        err_msg = f"[PERMISSION DENIED: {check.argv[0]}] {e}\n".encode("utf-8")
        return CheckResult(
            id=check.id,
            status="ERROR",
            exit_code=126,
            duration_seconds=elapsed,
            output=err_msg
        )
    except OSError as e:
        elapsed = time.monotonic() - t0
        code = 126 if "Exec format error" in str(e) else 127
        err_msg = f"[OS ERROR: {check.argv[0]}] {e}\n".encode("utf-8")
        return CheckResult(
            id=check.id,
            status="ERROR",
            exit_code=code,
            duration_seconds=elapsed,
            output=err_msg
        )

    pgid = proc.pid
    output_chunks: list[bytes] = []
    stop_event = threading.Event()

    def reader():
        try:
            fd = proc.stdout.fileno()
            poller = select.poll()
            poller.register(fd, select.POLLIN | select.POLLHUP | select.POLLERR)
            while not stop_event.is_set():
                events = poller.poll(100)
                if not events:
                    continue
                for _, ev in events:
                    if ev & select.POLLIN:
                        try:
                            chunk = os.read(fd, 65536)
                            if not chunk:
                                return
                            output_chunks.append(chunk)
                        except OSError:
                            return
                    if ev & (select.POLLHUP | select.POLLERR):
                        try:
                            while True:
                                chunk = os.read(fd, 65536)
                                if not chunk:
                                    break
                                output_chunks.append(chunk)
                        except OSError:
                            pass
                        return
        except Exception:
            pass

    reader_thread = threading.Thread(target=reader, daemon=True)
    reader_thread.start()

    cleanup_failed = False
    timed_out = False
    parent_finished = False
    deadline = time.monotonic() + check.timeout_seconds

    # Poll parent until it exits or timeout is reached
    while time.monotonic() < deadline:
        if proc.poll() is not None:
            parent_finished = True
            break
        time.sleep(0.02)

    if not parent_finished:
        timed_out = True
        clean = _kill_process_group(pgid, timeout_limit=2.0)
        if not clean:
            cleanup_failed = True
    else:
        # Parent finished: terminate remaining background group workers
        # so they do not hold stdout pipe open or linger as orphaned processes
        clean = _kill_process_group(pgid, timeout_limit=1.0)
        if not clean:
            cleanup_failed = True

    # Wait for reader thread to finish reading EOF with bounded timeout
    reader_thread.join(timeout=1.0)
    if reader_thread.is_alive():
        stop_event.set()
        if proc.stdout:
            try:
                proc.stdout.close()
            except Exception:
                pass
        reader_thread.join(timeout=0.5)
    else:
        if proc.stdout:
            try:
                proc.stdout.close()
            except Exception:
                pass

    try:
        proc.wait(timeout=0.5)
    except Exception:
        pass

    output = b"".join(output_chunks)
    elapsed = time.monotonic() - t0

    if timed_out:
        return CheckResult(
            id=check.id,
            status="TIMEOUT",
            exit_code=124,
            duration_seconds=elapsed,
            output=output,
            cleanup_failed=cleanup_failed
        )

    exit_code = proc.returncode
    status = "PASS" if (exit_code == 0 and not cleanup_failed) else "FAIL"

    # Extract test summary if check is a test_suite or contains summary marker
    test_summary = extract_test_summary_from_output(output)
    if test_summary:
        ok, diag = validate_test_summary_integrity(test_summary, exit_code)
        if not ok:
            status = "FAIL"
        else:
            ts_status, ts_diag = parse_test_summary(test_summary)
            if ts_status != "PASS":
                status = "FAIL"
    elif check.type == "test_suite":
        # Required test suite check without test summary must fail closed
        status = "FAIL"

    return CheckResult(
        id=check.id,
        status=status,
        exit_code=exit_code,
        duration_seconds=elapsed,
        output=output,
        test_summary=test_summary,
        cleanup_failed=cleanup_failed
    )
