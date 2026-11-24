"""
Specification Hygiene, Markdown Link & Anchor Validator, and Traceability Auditor.
Standard library only, zero external dependencies.
"""
import argparse
import json
import os
from pathlib import Path
import re
import sys
from typing import Optional

EXTERNAL_SCHEMES = ("http://", "https://", "mailto:", "ftp://")

def slugify_heading(heading: str) -> str:
    """
    Converts a Markdown heading text into a GitHub-compatible anchor slug.
    """
    clean = heading.strip().lower()
    clean = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', clean)
    clean = re.sub(r'[^\w\s-]', '', clean)
    clean = re.sub(r'[\s]+', '-', clean)
    return clean

def extract_headings(text: str) -> set[str]:
    """
    Extracts all headings (# Heading) outside fenced code blocks and returns set of anchor slugs.
    """
    slugs = set()
    in_code_block = False
    for line in text.splitlines():
        trimmed = line.strip()
        if trimmed.startswith("```"):
            in_code_block = not in_code_block
            continue
        if in_code_block:
            continue
        match = re.match(r'^(#{1,6})\s+(.+)$', trimmed)
        if match:
            heading_text = match.group(2).strip()
            slug = slugify_heading(heading_text)
            slugs.add(slug)
    return slugs

def validate_markdown_text(text: str, base_dir: Path, repo_root: Optional[Path] = None) -> list[str]:
    """
    Validates all links and image references in markdown text, ignoring code blocks and inline code.
    """
    errors: list[str] = []
    root = repo_root or Path.cwd()
    current_headings = extract_headings(text)

    link_pattern = re.compile(r'!?\[([^\]]*)\]\(([^)]+)\)')
    in_code_block = False

    for line_num, line in enumerate(text.splitlines(), start=1):
        trimmed = line.strip()
        if trimmed.startswith("```"):
            in_code_block = not in_code_block
            continue
        if in_code_block:
            continue

        # Strip inline code spans before link parsing
        line_no_inline_code = re.sub(r'`[^`]+`', '', line)

        for match in link_pattern.finditer(line_no_inline_code):
            target = match.group(2).strip()
            # Clean target (strip title e.g. "target 'title'")
            target = target.split()[0].strip('"').strip("'")

            # Ignore external schemes
            if any(target.startswith(scheme) for scheme in EXTERNAL_SCHEMES):
                continue

            # Strip file:// prefix if present
            if target.startswith("file://"):
                target = target[len("file://"):]

            # Flag non-portable workstation absolute paths
            if target.startswith("/home/") or target.startswith("/Users/"):
                errors.append(f"Line {line_num}: Machine-specific absolute workstation path is non-portable: {target}")
                continue

            parts = target.split("#", 1)
            file_part = parts[0].strip()
            anchor_part = parts[1].strip() if len(parts) > 1 else None

            if file_part:
                # File link
                if file_part.startswith("/"):
                    target_file = Path(file_part)
                else:
                    target_file = (base_dir / file_part).resolve()

                if not target_file.exists():
                    errors.append(f"Line {line_num}: Referenced file does not exist: {file_part} (resolved to {target_file})")
                elif anchor_part and target_file.is_file() and target_file.suffix in (".md", ".markdown"):
                    try:
                        target_text = target_file.read_text(encoding="utf-8")
                        target_slugs = extract_headings(target_text)
                        if anchor_part.lower() not in target_slugs:
                            errors.append(f"Line {line_num}: Anchor #{anchor_part} not found in {file_part}")
                    except Exception as e:
                        errors.append(f"Line {line_num}: Could not read {file_part} to check anchor: {e}")
            elif anchor_part:
                # Local anchor link
                if anchor_part.lower() not in current_headings:
                    errors.append(f"Line {line_num}: Local anchor #{anchor_part} not found in document")

    return errors

def validate_markdown_file(file_path: Path, repo_root: Optional[Path] = None) -> list[str]:
    """
    Validates links in a single markdown file.
    """
    try:
        text = file_path.read_text(encoding="utf-8")
    except Exception as e:
        return [f"Could not read markdown file {file_path}: {e}"]
    base_dir = file_path.parent
    return validate_markdown_text(text, base_dir=base_dir, repo_root=repo_root)

def validate_spec_traceability(req_path: Path, trace_path: Path) -> list[str]:
    """
    Validates that:
    1. All REQ-xxx IDs in req_path are in trace_path.
    2. All AC-xxx IDs in req_path are mapped in trace_path with task, check, and test oracle.
    """
    errors: list[str] = []
    if not req_path.is_file():
        return [f"Requirements file not found: {req_path}"]
    if not trace_path.is_file():
        return [f"Traceability file not found: {trace_path}"]

    try:
        req_text = req_path.read_text(encoding="utf-8")
    except Exception as e:
        return [f"Failed to read requirements file: {e}"]

    try:
        trace_data = json.loads(trace_path.read_text(encoding="utf-8"))
    except Exception as e:
        return [f"Failed to parse traceability JSON: {e}"]

    # Extract requirements from markdown: ### REQ-xxx
    req_matches = re.findall(r'###\s+(REQ-[A-Z0-9_-]+)', req_text)
    doc_req_ids = set(req_matches)

    # Extract ACs from markdown: - **AC-xxx.y:**
    ac_matches = re.findall(r'-\s+\*\*(AC-[0-9.]+)\b', req_text)
    doc_ac_ids = set(ac_matches)

    traced_req_ids: set[str] = set()
    traced_ac_ids: set[str] = set()

    # Support flat acceptance_criteria list
    if "acceptance_criteria" in trace_data:
        for ac in trace_data["acceptance_criteria"]:
            if isinstance(ac, dict):
                rid = ac.get("requirement_id")
                if rid:
                    traced_req_ids.add(rid)
                acid = ac.get("ac_id")
                if acid:
                    traced_ac_ids.add(acid)
                    task = ac.get("implementation_task") or ac.get("task_id")
                    check = ac.get("check_id") or ac.get("verification_check_id")
                    oracle = ac.get("test_oracle") or ac.get("validation_test_id")
                    if not task or not check or not oracle:
                        errors.append(f"Traceability missing task/check/oracle for {acid}")

    # Support requirements list (where acs can be strings or dicts)
    if "requirements" in trace_data:
        for r in trace_data["requirements"]:
            if isinstance(r, dict):
                rid = r.get("id") or r.get("requirement_id")
                if rid:
                    traced_req_ids.add(rid)
                for ac in r.get("acceptance_criteria", []):
                    if isinstance(ac, str):
                        traced_ac_ids.add(ac)
                    elif isinstance(ac, dict):
                        acid = ac.get("ac_id")
                        if acid:
                            traced_ac_ids.add(acid)
                            task = ac.get("task_id") or ac.get("implementation_task")
                            check = ac.get("verification_check_id") or ac.get("check_id")
                            oracle = ac.get("test_oracle") or ac.get("validation_test_id")
                            if not task or not check or not oracle:
                                errors.append(f"Traceability missing task/check/oracle for {acid}")

    for rid in doc_req_ids:
        if rid not in traced_req_ids:
            errors.append(f"Untraced requirement: {rid}")

    for acid in doc_ac_ids:
        if acid not in traced_ac_ids:
            errors.append(f"Untraced acceptance criterion: {acid}")

    return errors

def validate_defects_registry(path: Path) -> tuple[bool, list[str]]:
    """
    Validates defects.jsonl against required schema fields and valid enums.
    """
    errors: list[str] = []
    if not path.is_file():
        return False, [f"Defects file not found: {path}"]

    required_fields = [
        "defect_id", "classification", "requirement_or_invariant_id",
        "severity", "affected_module", "evidence", "reproduction",
        "root_cause", "regression_test_ref", "pre_fix_red_result",
        "post_fix_green_result", "generalization_decision",
        "proposed_antipattern_rule_id", "review_status"
    ]
    valid_classifications = {"PROPOSED_PREVENTIVE_CHECK", "HISTORICAL_RE_DEFECT", "CONFIRMED_APPLICATION_DEFECT"}
    valid_statuses = {"PROPOSED", "CONFIRMED", "IN_FIX", "RESOLVED", "VERIFIED", "PERMANENT_PROTECTION", "APPROVED"}

    with open(path, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, start=1):
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            try:
                record = json.loads(line)
            except Exception as e:
                errors.append(f"Line {line_num}: Invalid JSON: {e}")
                continue

            for field in required_fields:
                if field not in record:
                    errors.append(f"Line {line_num}: Missing required field '{field}'")

            classification = record.get("classification")
            if classification not in valid_classifications:
                errors.append(f"Line {line_num}: Invalid classification '{classification}'")

            status = record.get("review_status")
            if status not in valid_statuses:
                errors.append(f"Line {line_num}: Invalid review_status '{status}'")

    return len(errors) == 0, errors

def validate_antipattern_registry(path: Path) -> tuple[bool, list[str]]:
    """
    Validates antipatterns.json structure and dual-sample verification (positive matches, negative does not).
    """
    errors: list[str] = []
    if not path.is_file():
        return False, [f"Antipatterns file not found: {path}"]

    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        return False, [f"Failed to parse antipatterns JSON: {e}"]

    rules = data.get("rules", [])
    if not rules:
        errors.append("Antipatterns registry contains zero rules")

    for idx, rule in enumerate(rules):
        rid = rule.get("rule_id", f"index_{idx}")
        pattern_str = rule.get("pattern")
        pos_sample = rule.get("positive_test_sample")
        neg_sample = rule.get("negative_mutation_sample")

        if not pattern_str or not pos_sample or neg_sample is None:
            errors.append(f"Rule {rid}: Missing pattern, positive_test_sample, or negative_mutation_sample")
            continue

        try:
            regex = re.compile(pattern_str)
        except re.error as e:
            errors.append(f"Rule {rid}: Invalid regex pattern '{pattern_str}': {e}")
            continue

        if not regex.search(pos_sample):
            errors.append(f"Rule {rid}: Positive test sample did NOT match regex pattern")

        if regex.search(neg_sample):
            errors.append(f"Rule {rid}: Negative mutation sample erroneously matched regex pattern")

    return len(errors) == 0, errors

def main():
    parser = argparse.ArgumentParser(description="Specification Hygiene & Traceability Validator")
    parser.add_argument("--repo-root", type=str, default=".", help="Root directory of repository")
    args = parser.parse_args()

    repo = Path(args.repo_root).resolve()
    all_errors: list[str] = []

    # 1. Validate markdown files in specs/, policies/, docs/
    scan_dirs = [repo / "specs", repo / "policies", repo / "docs"]
    md_files = []
    for d in scan_dirs:
        if d.is_dir():
            md_files.extend(d.rglob("*.md"))

    print(f"Auditing links across {len(md_files)} markdown specification files...")
    for mf in md_files:
        errs = validate_markdown_file(mf, repo_root=repo)
        if errs:
            all_errors.append(f"In {mf.relative_to(repo)}:")
            for e in errs:
                all_errors.append(f"  - {e}")

    # 2. Validate active change specification traceability
    active_req = repo / "specs/changes/FCT-000/requirements.md"
    active_trace = repo / "specs/changes/FCT-000/traceability.json"
    if active_req.is_file() and active_trace.is_file():
        print("Auditing requirement traceability for FCT-000...")
        trace_errs = validate_spec_traceability(active_req, active_trace)
        if trace_errs:
            all_errors.append("Traceability Errors:")
            for e in trace_errs:
                all_errors.append(f"  - {e}")

    # 3. Validate defects registry if present
    defects_path = repo / "verification/defects.jsonl"
    if defects_path.is_file():
        print("Auditing defects registry...")
        ok, d_errs = validate_defects_registry(defects_path)
        if not ok:
            all_errors.append("Defects Registry Errors:")
            for e in d_errs:
                all_errors.append(f"  - {e}")

    # 4. Validate antipattern registry if present
    anti_path = repo / "verification/antipatterns.json"
    if anti_path.is_file():
        print("Auditing antipatterns registry...")
        ok, a_errs = validate_antipattern_registry(anti_path)
        if not ok:
            all_errors.append("Anti-pattern Registry Errors:")
            for e in a_errs:
                all_errors.append(f"  - {e}")

    if all_errors:
        print("Spec Hygiene Validation FAILED:", file=sys.stderr)
        for err in all_errors:
            print(err, file=sys.stderr)
        sys.exit(1)

    print("Spec Hygiene Validation PASSED: 100% links valid, requirements traced.")
    sys.exit(0)

if __name__ == "__main__":
    main()
