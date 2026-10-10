import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class WorkflowSecurityTest(unittest.TestCase):
    def test_build_and_deploy_permissions_are_separated(self):
        workflow = (ROOT / ".github" / "workflows" / "update-feed.yml").read_text(encoding="utf-8")
        build_job = workflow.split("  build-feed:\n", 1)[1].split("  deploy-pages:", 1)[0]
        deploy_job = workflow.split("  deploy-pages:\n", 1)[1]
        self.assertIn("permissions:\n      contents: read", build_job)
        self.assertIn("      pages: read", build_job)
        self.assertNotIn("pages: write", build_job)
        self.assertNotIn("id-token: write", build_job)
        self.assertIn("pages: write", deploy_job)
        self.assertIn("id-token: write", deploy_job)

    def test_workflow_actions_are_pinned_to_full_commit_shas(self):
        paths = [ROOT / ".github" / "workflows" / name for name in ("update-feed.yml", "gradle-wrapper-validation.yml")]
        references = []
        for path in paths:
            for line in path.read_text(encoding="utf-8").splitlines():
                if line.strip().startswith("uses:"):
                    references.append(line.split("uses:", 1)[1].strip().split()[0])
        self.assertTrue(references)
        for reference in references:
            if reference.startswith("./"):
                continue
            self.assertEqual(40, len(reference.rsplit("@", 1)[-1]))
            self.assertRegex(reference.rsplit("@", 1)[-1], r"^[0-9a-f]{40}$")

    def test_pages_artifact_contains_only_the_feed_file(self):
        workflow = (ROOT / ".github" / "workflows" / "update-feed.yml").read_text(encoding="utf-8")
        self.assertIn("cp data-pipeline/feed.json public/feed.json", workflow)
        self.assertIn("path: ./public", workflow)
        self.assertNotIn("path: ./data-pipeline", workflow)

    def test_dependency_lock_is_exact_and_hash_verified(self):
        lock = (ROOT / "data-pipeline" / "requirements.txt").read_text(encoding="utf-8")
        self.assertIn("--hash=sha256:", lock)
        for package in ("beautifulsoup4", "feedparser", "requests"):
            self.assertRegex(lock, rf"(?m)^{package}==[0-9]")

    def test_gradle_distribution_checksum_is_present(self):
        props = (ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
        self.assertRegex(props, r"(?m)^distributionSha256Sum=[0-9a-f]{64}$")

    def test_signing_files_are_ignored(self):
        ignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        for pattern in ("*.jks", "*.keystore", "keystore.properties", "signing.properties", "key.properties"):
            self.assertIn(pattern, ignore)


if __name__ == "__main__":
    unittest.main()
