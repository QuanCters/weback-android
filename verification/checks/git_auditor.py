"""
Git Checkout State Auditor, Cleanliness Guard, and Policy Diff Inspector.
Standard library only, invoking git commands via subprocess.
"""
import os
from pathlib import Path
import re
import subprocess
from typing import Optional

HEX_SHA40_RE = re.compile(r'^[0-9a-fA-F]{40}$')

DEFAULT_IGNORED_PREFIXES = ["artifacts/verification/"]

def is_clean_checkout(repo_dir: Optional[Path] = None, ignored_prefixes: Optional[list[str]] = None) -> tuple[bool, str]:
    """
    Verifies that the repository working tree is completely clean.
    Inspects:
    1. git status --porcelain --untracked-files=all
       Any staged, unstaged, or untracked files not starting with ignored_prefixes will fail.
    2. git ls-files -v
       Any files marked with 'h' (assume-unchanged) or 'S' (skip-worktree) will fail.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    prefixes = ignored_prefixes if ignored_prefixes is not None else DEFAULT_IGNORED_PREFIXES

    # 1. Check status
    try:
        proc = subprocess.run(
            ["git", "status", "--porcelain", "--untracked-files=all"],
            cwd=cwd,
            capture_output=True,
            text=True,
            check=True
        )
    except subprocess.CalledProcessError as e:
        return False, f"git status failed: {e.stderr}"

    dirty_entries = []
    for line in proc.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        # Format is XY PATH or XY "PATH"
        parts = line.split(maxsplit=1)
        if len(parts) >= 2:
            rel_path = parts[1].strip('"')
            # Check if path starts with any ignored prefix
            if any(rel_path.startswith(p) for p in prefixes):
                continue
            dirty_entries.append(line)
        else:
            dirty_entries.append(line)

    if dirty_entries:
        return False, f"Working tree is not clean. Uncommitted/untracked files:\n  " + "\n  ".join(dirty_entries)

    # 2. Check assume-unchanged and skip-worktree flags
    try:
        proc_ls = subprocess.run(
            ["git", "ls-files", "-v"],
            cwd=cwd,
            capture_output=True,
            text=True,
            check=True
        )
    except subprocess.CalledProcessError as e:
        return False, f"git ls-files failed: {e.stderr}"

    for line in proc_ls.stdout.splitlines():
        line = line.strip()
        if not line:
            continue
        # In git ls-files -v, first char is tag.
        # 'H' = normal tracked, 'h' = assume-unchanged, 'S' = skip-worktree
        tag = line[0]
        if tag == "h":
            return False, f"File marked with assume-unchanged ('h'): {line[2:]}"
        elif tag == "S":
            return False, f"File marked with skip-worktree ('S'): {line[2:]}"

    return True, "Working tree is clean"

def get_head_sha(repo_dir: Optional[Path] = None) -> str:
    """
    Returns exact 40-character commit SHA for HEAD.
    Raises ValueError if HEAD cannot be resolved or is invalid.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    try:
        proc = subprocess.run(
            ["git", "rev-parse", "HEAD"],
            cwd=cwd,
            capture_output=True,
            text=True,
            check=True
        )
        sha = proc.stdout.strip()
        if not HEX_SHA40_RE.match(sha):
            raise ValueError(f"Invalid HEAD SHA resolved: '{sha}'")
        return sha
    except subprocess.CalledProcessError as e:
        raise ValueError(f"Failed to resolve HEAD SHA: {e.stderr.strip()}")

def get_base_sha(repo_dir: Optional[Path] = None, base_ref: str = "main") -> str:
    """
    Returns exact 40-character commit SHA for base_ref.
    Raises ValueError if base_ref cannot be resolved or is invalid.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    candidates = [base_ref]
    if not base_ref.startswith("origin/") and not base_ref.startswith("refs/"):
        candidates.append(f"origin/{base_ref}")

    last_error = ""
    for ref in candidates:
        try:
            proc = subprocess.run(
                ["git", "rev-parse", ref],
                cwd=cwd,
                capture_output=True,
                text=True,
                check=True
            )
            sha = proc.stdout.strip()
            if HEX_SHA40_RE.match(sha):
                return sha
            else:
                last_error = f"Invalid base SHA resolved for '{ref}': '{sha}'"
        except subprocess.CalledProcessError as e:
            last_error = e.stderr.strip()

    raise ValueError(f"Failed to resolve base ref '{base_ref}': {last_error}")

def check_policy_modified_from_base(repo_dir: Optional[Path] = None, base_ref: str = "main") -> bool:
    """
    Checks if any file in policies/ differs between base_ref and HEAD.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    candidates = [base_ref]
    if not base_ref.startswith("origin/") and not base_ref.startswith("refs/"):
        candidates.append(f"origin/{base_ref}")

    for ref in candidates:
        try:
            proc = subprocess.run(
                ["git", "diff", "--name-only", f"{ref}...HEAD", "--", "policies/"],
                cwd=cwd,
                capture_output=True,
                text=True,
                check=True
            )
            return len(proc.stdout.strip()) > 0
        except subprocess.CalledProcessError:
            try:
                proc2 = subprocess.run(
                    ["git", "diff", "--name-only", ref, "HEAD", "--", "policies/"],
                    cwd=cwd,
                    capture_output=True,
                    text=True,
                    check=True
                )
                return len(proc2.stdout.strip()) > 0
            except subprocess.CalledProcessError:
                continue
    return False
