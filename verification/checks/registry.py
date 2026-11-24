"""
Declarative Check Registry Validation & Loader.
Standard-library only, zero external dependencies.
Validates policies/checks.json against verification/schemas/checks.schema.json structure.
"""
from dataclasses import dataclass, field
import hashlib
import json
import os
from pathlib import Path
import sys
from typing import Optional

@dataclass
class CheckDefinition:
    id: str
    argv: list[str]
    required: bool
    timeout_seconds: int
    type: str = "command"
    description: str = ""
    cwd: Optional[Path] = None

@dataclass
class CheckRegistry:
    checks: list[CheckDefinition]
    policy_sha256: str
    version: int = 1

def validate_raw_registry(data: dict) -> list[CheckDefinition]:
    """
    Validates the structure of the registry dictionary using pure Python standard library.
    Enforces all checks.schema.json requirements:
    - Must be a dict with key 'checks'
    - 'checks' must be a list with len >= 1
    - At least one check must have required == True (ALL_OPTIONAL_REGISTRY rejected)
    - Check IDs must be unique
    - argv must be a non-empty list of strings
    - timeout_seconds must be a positive integer (> 0)
    - required must be a boolean
    - type must be 'command' or 'test_suite' if present
    """
    if not isinstance(data, dict):
        raise ValueError("Schema validation failed: root must be a JSON object")

    if "checks" not in data:
        raise ValueError("Schema validation failed: missing required property 'checks'")

    checks_raw = data["checks"]
    if not isinstance(checks_raw, list):
        raise ValueError("Schema validation failed: 'checks' must be an array")

    if len(checks_raw) == 0:
        raise ValueError("Registry contains zero checks")

    seen_ids: set[str] = set()
    definitions: list[CheckDefinition] = []
    has_required = False

    for idx, item in enumerate(checks_raw):
        if not isinstance(item, dict):
            raise ValueError(f"Schema validation failed: check item at index {idx} must be an object")

        # Required properties
        for req_prop in ["id", "argv", "required", "timeout_seconds"]:
            if req_prop not in item:
                raise ValueError(f"Schema validation failed: missing required property '{req_prop}' in check at index {idx}")

        cid = item["id"]
        if not isinstance(cid, str) or not cid:
            raise ValueError(f"Check ID must be a non-empty string at index {idx}")
        if cid in seen_ids:
            raise ValueError(f"Duplicate check ID: {cid}")
        seen_ids.add(cid)

        argv = item["argv"]
        if not isinstance(argv, list) or len(argv) == 0:
            raise ValueError(f"argv must be a non-empty array of strings in check '{cid}'")
        for arg_idx, arg in enumerate(argv):
            if not isinstance(arg, str):
                raise ValueError(f"argv item at index {arg_idx} must be a string in check '{cid}'")

        required = item["required"]
        if not isinstance(required, bool):
            raise ValueError(f"'required' must be a boolean in check '{cid}'")
        if required:
            has_required = True

        timeout = item["timeout_seconds"]
        if not isinstance(timeout, int) or isinstance(timeout, bool) or timeout <= 0:
            raise ValueError(f"timeout_seconds must be a positive integer in check '{cid}'")

        ctype = item.get("type", "command")
        if ctype not in ("command", "test_suite"):
            raise ValueError(f"Invalid type '{ctype}' in check '{cid}', must be 'command' or 'test_suite'")

        desc = item.get("description", "")
        if not isinstance(desc, str):
            raise ValueError(f"description must be a string in check '{cid}'")

        definitions.append(CheckDefinition(
            id=cid,
            argv=argv,
            required=required,
            timeout_seconds=timeout,
            type=ctype,
            description=desc
        ))

    if not has_required:
        raise ValueError("Registry contains zero required checks (ALL_OPTIONAL_REGISTRY)")

    return definitions

def load_and_validate_registry(path: str) -> CheckRegistry:
    """
    Loads JSON from path, computes its SHA-256 hash, and validates against check schema.
    Raises ValueError on syntax or validation error.
    """
    p = Path(path)
    if not p.is_file():
        raise ValueError(f"Registry file not found: {path}")

    raw_bytes = p.read_bytes()
    policy_sha256 = hashlib.sha256(raw_bytes).hexdigest()

    try:
        data = json.loads(raw_bytes.decode("utf-8"))
    except json.JSONDecodeError as e:
        raise ValueError(f"Invalid JSON syntax in {path}: {e}")

    checks = validate_raw_registry(data)
    version = data.get("version", 1)

    return CheckRegistry(checks=checks, policy_sha256=policy_sha256, version=version)

def validate_check_selection(registry: CheckRegistry, check_id: str) -> CheckDefinition:
    """
    Validates that check_id exists in registry. Returns the CheckDefinition or raises KeyError.
    """
    for c in registry.checks:
        if c.id == check_id:
            return c
    raise KeyError(f"Unknown check ID: {check_id}")
