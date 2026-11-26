import unittest
import os
import subprocess
import sys
import tempfile
import json
from pathlib import Path

class TestCliRunner(unittest.TestCase):
    @staticmethod
    def _clean_env() -> dict[str, str]:
        return {
            k: v for k, v in os.environ.items()
            if k not in ("APPROVED_POLICY_SHA256", "TRANSITION_AUTHORIZATION", "GITHUB_EVENT_NAME")
        }

    def test_cli_help(self):
        res = subprocess.run([sys.executable, "verification/run.py", "--help"], capture_output=True, text=True)
        self.assertEqual(res.returncode, 0)
        self.assertIn("--lane", res.stdout)
        self.assertIn("--check", res.stdout)

    def test_candidate_partial_check_prohibited_TEST_RUN_009(self):
        res = subprocess.run([sys.executable, "verification/run.py", "--lane", "candidate", "--check", "c1"], capture_output=True, text=True, env=self._clean_env())
        self.assertEqual(res.returncode, 2)
        self.assertIn("Partial check execution is prohibited in candidate lane", res.stderr)

    def test_candidate_alternate_config_prohibited_TEST_RUN_010(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "alt.json"
            p.write_text('{"checks": [{"id": "c1", "argv": ["python3"], "required": true, "timeout_seconds": 5}]}')
            res = subprocess.run([sys.executable, "verification/run.py", "--lane", "candidate", "--config", str(p)], capture_output=True, text=True, env=self._clean_env())
            self.assertEqual(res.returncode, 2)
            self.assertIn("Alternate configuration not approved for candidate lane", res.stderr)

    def test_fast_lane_with_custom_config(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "custom.json"
            p.write_text(json.dumps({
                "checks": [
                    {"id": "fast-test", "argv": ["python3", "-c", "exit(0)"], "required": True, "timeout_seconds": 5}
                ]
            }))
            out_dir = Path(td) / "artifacts"
            res = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir)],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertIn("PASS", res.stdout)
            # Verify report exists
            reports = list(out_dir.glob("*/report.json"))
            self.assertEqual(len(reports), 1)
            report_data = json.loads(reports[0].read_text())
            self.assertEqual(report_data["lane"], "fast")
            self.assertEqual(report_data["verdict"], "PASS")

    def test_unresolvable_base_ref_rejected_in_candidate_lane_TEST_RUN_019(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)
            # Copy canonical policy
            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            canonical = Path("policies/checks.json").read_text()
            (pol_dir / "checks.json").write_text(canonical)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            res = subprocess.run(
                [sys.executable, str(Path("verification/run.py").resolve()), "--lane", "candidate", "--base-ref", "non_existent_branch_xyz", "--repo-dir", str(repo)],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res.returncode, 2)
            self.assertIn("Could not resolve base SHA", res.stderr)

    def test_run_id_custom_and_path_traversal_rejection_TEST_RUN_020(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "custom.json"
            p.write_text(json.dumps({
                "checks": [
                    {"id": "fast-test", "argv": ["python3", "-c", "exit(0)"], "required": True, "timeout_seconds": 5}
                ]
            }))
            out_dir = Path(td) / "artifacts"
            # 1. Valid custom run-id
            res = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir), "--run-id", "my-run-123"],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res.returncode, 0, res.stderr)
            self.assertTrue((out_dir / "my-run-123" / "report.json").is_file())

            # 2. Path traversal run-id rejected
            res_bad = subprocess.run(
                [sys.executable, "verification/run.py", "--lane", "fast", "--config", str(p), "--output-dir", str(out_dir), "--run-id", "../../escaped"],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res_bad.returncode, 2)
            self.assertIn("Invalid run-id format", res_bad.stderr)

    def test_candidate_output_dir_restrictions_TEST_RUN_022(self):
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)
            # Canonical policy
            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            canonical = Path("policies/checks.json").read_text()
            (pol_dir / "checks.json").write_text(canonical)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            run_script = str(Path("verification/run.py").resolve())

            # 1. Reject path traversal in --output-dir
            res1 = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo), "--output-dir", "../escaped"],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res1.returncode, 2)
            self.assertIn("prohibited in candidate lane", res1.stderr)

            # 2. Reject internal source directory as --output-dir
            res2 = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo), "--output-dir", "policies"],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res2.returncode, 2)
            self.assertIn("Candidate lane output directory must be within", res2.stderr)

    def test_candidate_check_modifying_tracked_file_fails_cleanliness_TEST_RUN_023(self):
        # Reproduces defect where check modifies tracked file and must fail candidate lane
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            tracked_source = repo / "tracked_source.py"
            tracked_source.write_text("# clean original\n")

            # Synthetic check modifies tracked_source.py
            policy_content = {
                "version": 1,
                "checks": [
                    {
                        "id": "mutating-check",
                        "type": "command",
                        "argv": ["python3", "-c", "import pathlib; pathlib.Path('tracked_source.py').write_text('# mutated by check')"],
                        "timeout_seconds": 10,
                        "required": True
                    }
                ]
            }
            (pol_dir / "checks.json").write_text(json.dumps(policy_content))
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            run_script = str(Path("verification/run.py").resolve())
            res = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo)],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res.returncode, 2)
            self.assertIn("Repository mutated during execution", res.stderr)
            self.assertIn("tracked_source.py", res.stderr)

    def test_candidate_transition_context_error_fails_closed_TEST_RUN_026(self):
        """Unexpected errors resolving transition context in candidate lane must fail closed (exit 2)."""
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            (pol_dir / "checks.json").write_text(json.dumps({
                "version": 1,
                "checks": [{"id": "c1", "type": "command", "argv": ["python3", "-c", "exit(0)"], "timeout_seconds": 5, "required": True}]
            }))
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            run_script = str(Path("verification/run.py").resolve())
            # Pass a non-existent path / invalid JSON via --transition-auth
            res = subprocess.run(
                [sys.executable, run_script, "--lane", "candidate", "--event-name", "pull_request", "--base-ref", "HEAD", "--repo-dir", str(repo), "--transition-auth", "{not-json"],
                capture_output=True,
                text=True,
                env=self._clean_env()
            )
            self.assertEqual(res.returncode, 2)
            self.assertIn("Candidate policy resolution failed", res.stderr)

    def test_candidate_unexpected_context_error_fails_closed_TEST_RUN_027(self):
        """Unexpected non-TransitionError during context resolution in candidate lane must fail closed."""
        from unittest.mock import patch
        import verification.run as run_mod

        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            (pol_dir / "checks.json").write_text(json.dumps({
                "version": 1,
                "checks": [{"id": "c1", "type": "command", "argv": ["python3", "-c", "exit(0)"], "timeout_seconds": 5, "required": True}]
            }))
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            with patch.dict(os.environ, self._clean_env(), clear=True), \
                 patch.object(run_mod, "resolve_verification_context", side_effect=RuntimeError("unexpected failure")), \
                 patch.object(sys, "argv", ["verification/run.py", "--lane", "candidate", "--base-ref", "HEAD", "--repo-dir", str(repo)]):
                with self.assertRaises(SystemExit) as cm:
                    run_mod.main()
                self.assertEqual(cm.exception.code, 2)

    def test_candidate_transition_auth_file_path_supported_TEST_RUN_028(self):
        """Passing a JSON file path to --transition-auth in candidate lane succeeds."""
        with tempfile.TemporaryDirectory() as td:
            repo = Path(td)
            subprocess.run(["git", "init"], cwd=repo, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Tester"], cwd=repo, check=True)
            subprocess.run(["git", "config", "user.email", "tester@weback.local"], cwd=repo, check=True)

            pol_dir = repo / "policies"
            pol_dir.mkdir(parents=True)
            policy_bytes = json.dumps({
                "version": 1,
                "checks": [
                    {"id": "spec-hygiene", "type": "command", "argv": ["python3", "-c", "exit(0)"], "timeout_seconds": 5, "required": True},
                    {"id": "harness-selftests", "type": "command", "argv": ["python3", "-c", "exit(0)"], "timeout_seconds": 5, "required": True}
                ]
            }).encode("utf-8")
            (pol_dir / "checks.json").write_bytes(policy_bytes)
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "init"], cwd=repo, check=True, capture_output=True)

            base_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            subprocess.run(["git", "checkout", "-b", "feat"], cwd=repo, check=True, capture_output=True)
            (repo / "src.txt").write_text("x")
            subprocess.run(["git", "add", "."], cwd=repo, check=True)
            subprocess.run(["git", "commit", "-m", "feat"], cwd=repo, check=True, capture_output=True)

            cand_sha = subprocess.run(["git", "rev-parse", "HEAD"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            tree_sha = subprocess.run(["git", "rev-parse", "HEAD^{tree}"], cwd=repo, check=True, capture_output=True, text=True).stdout.strip()
            import hashlib
            policy_sha = hashlib.sha256(policy_bytes).hexdigest()

            with tempfile.TemporaryDirectory() as auth_td:
                auth_file = Path(auth_td) / "auth.json"
                auth_file.write_text(json.dumps({
                    "candidate_sha": cand_sha,
                    "target_base_sha": base_sha,
                    "candidate_tree_sha": tree_sha,
                    "canonical_policy_sha256": policy_sha,
                    "required_checks": ["spec-hygiene", "harness-selftests"]
                }))

                run_script = str(Path("verification/run.py").resolve())
                # Missing explicit event_name during transition MUST fail closed (not inferred!)
                res_missing_evt = subprocess.run(
                    [sys.executable, run_script, "--lane", "candidate", "--base-ref", base_sha, "--repo-dir", str(repo), "--transition-auth", str(auth_file)],
                    capture_output=True,
                    text=True,
                    env=self._clean_env()
                )
                self.assertEqual(res_missing_evt.returncode, 2)
                self.assertIn("event", res_missing_evt.stderr.lower())

                # Unsupported event_name MUST fail closed
                res_bad_evt = subprocess.run(
                    [sys.executable, run_script, "--lane", "candidate", "--event-name", "workflow_dispatch", "--base-ref", base_sha, "--repo-dir", str(repo), "--transition-auth", str(auth_file)],
                    capture_output=True,
                    text=True,
                    env=self._clean_env()
                )
                self.assertEqual(res_bad_evt.returncode, 2)

                # Explicit pull_request event_name succeeds
                res = subprocess.run(
                    [sys.executable, run_script, "--lane", "candidate", "--event-name", "pull_request", "--base-ref", base_sha, "--repo-dir", str(repo), "--transition-auth", str(auth_file)],
                    capture_output=True,
                    text=True,
                    env=self._clean_env()
                )
                self.assertEqual(res.returncode, 0, f"stderr: {res.stderr}\nstdout: {res.stdout}")
                self.assertIn("Verdict: PASS", res.stdout)

    def test_cli_isolated_tests_retain_behavior_under_active_transition_env_TEST_RUN_029(self):
        """Isolated CLI tests retain intended behavior even when parent process holds active transition env vars."""
        from unittest.mock import patch
        active_env = {
            "APPROVED_POLICY_SHA256": "4a004ac8d07178c4164193d827983dfa5a7d86c5245fbcb6df69497e976137fd",
            "TRANSITION_AUTHORIZATION": json.dumps({
                "candidate_sha": "a" * 40,
                "target_base_sha": "b" * 40,
                "candidate_tree_sha": "c" * 40,
                "canonical_policy_sha256": "d" * 64,
                "required_checks": ["spec-hygiene", "harness-selftests"]
            }),
            "GITHUB_EVENT_NAME": "pull_request"
        }
        with patch.dict(os.environ, active_env, clear=False):
            self.test_candidate_partial_check_prohibited_TEST_RUN_009()
            self.test_candidate_alternate_config_prohibited_TEST_RUN_010()
            self.test_fast_lane_with_custom_config()

    def test_candidate_targeted_tests_prohibited_OPT_09(self):
        res = subprocess.run(
            [sys.executable, "verification/run.py", "--lane", "candidate", "--tests", "com.weback.robot.FooTest"],
            capture_output=True,
            text=True,
            env=self._clean_env(),
        )
        self.assertEqual(res.returncode, 2)
        self.assertIn("Targeted --tests filtering is prohibited in candidate lane", res.stderr)

    def test_fast_lane_forwards_tests_filter_to_gradle_test_adapter_OPT_09(self):
        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            results_dir = td_path / "results"
            results_dir.mkdir(parents=True, exist_ok=True)
            argv_log = td_path / "argv.json"
            mock_gradle = td_path / "mock_gradle.py"
            xml_content = (
                '<?xml version="1.0" encoding="UTF-8"?>\n'
                '<testsuite name="com.weback.TargetTest" tests="1" skipped="0" failures="0" errors="0">\n'
                '  <testcase name="testTarget" classname="com.weback.TargetTest"/>\n'
                '</testsuite>\n'
            )
            mock_gradle.write_text(
                "import json, sys\n"
                "from pathlib import Path\n"
                f"Path({repr(str(argv_log))}).write_text(json.dumps(sys.argv[1:]), encoding='utf-8')\n"
                f"(Path({repr(str(results_dir))}) / 'TEST-target.xml').write_text({repr(xml_content)}, encoding='utf-8')\n",
                encoding="utf-8",
            )
            cfg = td_path / "fast.json"
            cfg.write_text(json.dumps({
                "checks": [
                    {
                        "id": "android-unit-tests",
                        "type": "test_suite",
                        "argv": [
                            sys.executable,
                            "-m",
                            "verification.checks.gradle_test_adapter",
                            "--results-dir",
                            str(results_dir),
                            "--gradle-bin",
                            sys.executable,
                            "--task",
                            str(mock_gradle),
                        ],
                        "required": True,
                        "timeout_seconds": 30,
                    }
                ]
            }), encoding="utf-8")
            out_dir = td_path / "artifacts"
            res = subprocess.run(
                [
                    sys.executable,
                    "verification/run.py",
                    "--lane",
                    "fast",
                    "--config",
                    str(cfg),
                    "--output-dir",
                    str(out_dir),
                    "--tests",
                    "com.weback.TargetTest",
                ],
                capture_output=True,
                text=True,
                env=self._clean_env(),
            )
            self.assertEqual(res.returncode, 0, f"stderr: {res.stderr}\nstdout: {res.stdout}")
            recorded = json.loads(argv_log.read_text(encoding="utf-8"))
            self.assertIn("--tests", recorded)
            idx = recorded.index("--tests")
            self.assertEqual(recorded[idx + 1], "com.weback.TargetTest")

    def test_dag_parallelism_preserves_order_and_serializes_gradle_OPT_08(self):
        from verification.checks.registry import CheckDefinition
        from verification.run import execute_checks_dag

        with tempfile.TemporaryDirectory() as td:
            td_path = Path(td)
            events_file = td_path / "events.log"
            # Build a small DAG mirroring policy IDs:
            # - spec-hygiene & harness-selftests run concurrently with android-lint
            # - android-lint and android-unit-tests (gradlew/gradle_test_adapter) NEVER overlap
            # - apk-validation waits for android-build
            def make_check(cid: str, argv: list[str]) -> CheckDefinition:
                return CheckDefinition(id=cid, argv=argv, required=True, timeout_seconds=10, cwd=td_path)

            py_script = (
                "import fcntl, sys, time\n"
                "from pathlib import Path\n"
                "cid, delay = sys.argv[1], float(sys.argv[2])\n"
                f"p = Path({repr(str(events_file))})\n"
                "def log(ev):\n"
                "    with open(p, 'a', encoding='utf-8') as f:\n"
                "        fcntl.flock(f, fcntl.LOCK_EX)\n"
                "        f.write(f'{ev}:{cid}\\n')\n"
                "log('START')\n"
                "time.sleep(delay)\n"
                "log('END')\n"
            )
            script_path = td_path / "rec.py"
            script_path.write_text(py_script, encoding="utf-8")

            checks = [
                make_check("spec-hygiene", [sys.executable, str(script_path), "spec-hygiene", "0.05"]),
                make_check("harness-selftests", [sys.executable, str(script_path), "harness-selftests", "0.20"]),
                make_check("gradle-wrapper-validation", [sys.executable, str(script_path), "gradle-wrapper-validation", "0.02"]),
                make_check("android-lint", [sys.executable, str(script_path), "android-lint", "0.10", "./gradlew"]),
                make_check("android-unit-tests", [sys.executable, str(script_path), "android-unit-tests", "0.10", "gradle_test_adapter"]),
                make_check("android-build", [sys.executable, str(script_path), "android-build", "0.10", "./gradlew"]),
                make_check("apk-validation", [sys.executable, str(script_path), "apk-validation", "0.15"]),
                make_check("pilot-android-lint", [sys.executable, str(script_path), "pilot-android-lint", "0.25", "./gradlew"]),
            ]

            results = execute_checks_dag(checks, max_parallel=2)
            # 1. Exact declaration order preserved
            self.assertEqual([r.id for r in results], [c.id for c in checks])
            self.assertTrue(all(r.status == "PASS" for r in results))

            lines = events_file.read_text(encoding="utf-8").splitlines()
            # 2. gradle-wrapper-validation and harness-selftests finished before android-lint started
            self.assertLess(lines.index("END:gradle-wrapper-validation"), lines.index("START:android-lint"))
            self.assertLess(lines.index("END:harness-selftests"), lines.index("START:android-lint"))
            # 3. android-build finished before apk-validation started
            self.assertLess(lines.index("END:android-build"), lines.index("START:apk-validation"))
            # 4. Gradle checks serialized strictly in order
            self.assertLess(lines.index("END:android-lint"), lines.index("START:android-unit-tests"))
            self.assertLess(lines.index("END:android-unit-tests"), lines.index("START:android-build"))
            self.assertLess(lines.index("END:android-build"), lines.index("START:pilot-android-lint"))
            # 5. Non-Gradle checks run concurrently: spec-hygiene overlaps harness-selftests,
            #    and apk-validation runs concurrently with pilot-android-lint
            self.assertLess(lines.index("START:harness-selftests"), lines.index("END:spec-hygiene"))
            self.assertLess(lines.index("START:apk-validation"), lines.index("END:pilot-android-lint"))


if __name__ == "__main__":
    unittest.main()
