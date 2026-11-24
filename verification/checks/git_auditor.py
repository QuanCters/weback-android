"""
Git Checkout State Auditor, Cleanliness Guard, and Policy Diff Inspector.
Standard library only, invoking git commands via subprocess.
"""
import os
from pathlib import Path
import subprocess
from typing import Optional

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
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    proc = subprocess.run(
        ["git", "rev-parse", "HEAD"],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True
    )
    return proc.stdout.strip()

def get_base_sha(repo_dir: Optional[Path] = None, base_ref: str = "main") -> str:
    """
    Returns exact 40-character commit SHA for base_ref.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    proc = subprocess.run(
        ["git", "rev-parse", base_ref],
        cwd=cwd,
        capture_output=True,
        text=True,
        check=True
    )
    return proc.stdout.strip()

def check_policy_modified_from_base(repo_dir: Optional[Path] = None, base_ref: str = "main") -> bool:
    """
    Checks if any file in policies/ differs between base_ref and HEAD.
    """
    cwd = str(repo_dir) if repo_dir else os.getcwd()
    # Check if base_ref exists
    try:
        proc = subprocess.run(
            ["git", "diff", "--name-only", f"{base_ref}...HEAD", "--", "policies/"],
            cwd=cwd,
            capture_output=True,
            text=True,
            check=True
        )
        return len(proc.stdout.strip()) > 0
    except subprocess.CalledProcessError:
        # Fallback to direct two-dot diff or compare HEAD
        try:
            proc2 = subprocess.run(
                ["git", "diff", "--name-only", base_ref, "HEAD", "--", "policies/"],
                cwd=cwd,
                capture_output=True,
                text=True,
                check=True
            )
            return len(proc2.stdout.strip()) > 0
        except subprocess.CalledProcessError:
            return False
