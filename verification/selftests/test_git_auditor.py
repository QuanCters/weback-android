import unittest
import tempfile
import subprocess
from pathlib import Path
from verification.checks.git_auditor import (
    is_clean_checkout,
    get_head_sha,
    get_base_sha,
    check_policy_modified_from_base
)

class TestGitAuditor(unittest.TestCase):
    def setUp(self):
        self.td = tempfile.TemporaryDirectory()
        self.repo = Path(self.td.name)
        subprocess.run(["git", "init"], cwd=self.repo, check=True, capture_output=True)
        subprocess.run(["git", "config", "user.name", "Test Agent"], cwd=self.repo, check=True)
        subprocess.run(["git", "config", "user.email", "test@weback.local"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "--allow-empty", "-m", "initial commit"], cwd=self.repo, check=True, capture_output=True)

    def tearDown(self):
        self.td.cleanup()

    def test_clean_and_dirty_detection_TEST_RUN_006(self):
        clean, _ = is_clean_checkout(self.repo)
        self.assertTrue(clean)
        (self.repo / "dirty.txt").write_text("modified")
        clean, details = is_clean_checkout(self.repo)
        self.assertFalse(clean)
        self.assertIn("dirty.txt", details)

    def test_ignored_artifacts_prefix_clean_TEST_RUN_007(self):
        art_dir = self.repo / "artifacts" / "verification"
        art_dir.mkdir(parents=True)
        (art_dir / "report.json").write_text("{}")
        clean, details = is_clean_checkout(self.repo, ignored_prefixes=["artifacts/verification/"])
        self.assertTrue(clean, details)

    def test_assume_unchanged_detection(self):
        f = self.repo / "tracked.txt"
        f.write_text("original")
        subprocess.run(["git", "add", "tracked.txt"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "add tracked"], cwd=self.repo, check=True)
        subprocess.run(["git", "update-index", "--assume-unchanged", "tracked.txt"], cwd=self.repo, check=True)
        clean, details = is_clean_checkout(self.repo)
        self.assertFalse(clean)
        self.assertIn("assume-unchanged", details)

    def test_skip_worktree_detection(self):
        f = self.repo / "skip.txt"
        f.write_text("skip me")
        subprocess.run(["git", "add", "skip.txt"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "add skip"], cwd=self.repo, check=True)
        subprocess.run(["git", "update-index", "--skip-worktree", "skip.txt"], cwd=self.repo, check=True)
        clean, details = is_clean_checkout(self.repo)
        self.assertFalse(clean)
        self.assertIn("skip-worktree", details)

    def test_policy_modified_from_base_TEST_RUN_008(self):
        pol_dir = self.repo / "policies"
        pol_dir.mkdir()
        (pol_dir / "checks.json").write_text("v1")
        subprocess.run(["git", "add", "policies/checks.json"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "add policy"], cwd=self.repo, check=True)
        subprocess.run(["git", "checkout", "-b", "feat"], cwd=self.repo, check=True)
        (pol_dir / "checks.json").write_text("v2")
        subprocess.run(["git", "commit", "-am", "modify policy"], cwd=self.repo, check=True)
        modified = check_policy_modified_from_base(self.repo, base_ref="HEAD~1")
        self.assertTrue(modified)

    def test_get_head_and_base_sha(self):
        head = get_head_sha(self.repo)
        self.assertEqual(len(head), 40)
        base = get_base_sha(self.repo, base_ref="HEAD")
        self.assertEqual(base, head)

    def test_non_existent_base_ref_raises_value_error_TEST_RUN_016(self):
        with self.assertRaises(ValueError) as ctx:
            get_base_sha(self.repo, base_ref="non_existent_ref_xyz")
        self.assertIn("Failed to resolve base ref", str(ctx.exception))

    def test_invalid_head_ref_raises_value_error_TEST_RUN_017(self):
        with tempfile.TemporaryDirectory() as td:
            # Non-git directory
            with self.assertRaises(ValueError) as ctx:
                get_head_sha(Path(td))
            self.assertIn("Failed to resolve HEAD SHA", str(ctx.exception))

    def test_dual_repo_working_dir_propagation_TEST_RUN_018(self):
        with tempfile.TemporaryDirectory() as td2:
            repo2 = Path(td2)
            subprocess.run(["git", "init"], cwd=repo2, check=True, capture_output=True)
            subprocess.run(["git", "config", "user.name", "Agent 2"], cwd=repo2, check=True)
            subprocess.run(["git", "config", "user.email", "agent2@weback.local"], cwd=repo2, check=True)
            subprocess.run(["git", "commit", "--allow-empty", "-m", "repo 2 commit"], cwd=repo2, check=True, capture_output=True)

            sha1 = get_head_sha(self.repo)
            sha2 = get_head_sha(repo2)
            self.assertNotEqual(sha1, sha2)
            self.assertEqual(len(sha1), 40)
            self.assertEqual(len(sha2), 40)

    def test_tracked_file_modification_under_ignored_prefix_not_ignored_TEST_RUN_021(self):
        # Even if a file is under an ignored prefix, if it is a tracked file that is modified, it must fail clean checkout!
        art_dir = self.repo / "artifacts" / "verification"
        art_dir.mkdir(parents=True)
        tracked_file = art_dir / "tracked_in_artifacts.txt"
        tracked_file.write_text("initial version")
        subprocess.run(["git", "add", "artifacts/verification/tracked_in_artifacts.txt"], cwd=self.repo, check=True)
        subprocess.run(["git", "commit", "-m", "commit tracked artifact"], cwd=self.repo, check=True)

        # Now modify the tracked file
        tracked_file.write_text("modified version")

        clean, details = is_clean_checkout(self.repo, ignored_prefixes=["artifacts/verification/"])
        self.assertFalse(clean, "Tracked file modification must NOT be ignored even under ignored_prefixes")
        self.assertIn("tracked_in_artifacts.txt", details)


if __name__ == "__main__":
    unittest.main()
