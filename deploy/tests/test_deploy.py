"""Verify deployment transitions with fake Docker commands, without live services."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import unittest

SCRIPTS = Path(__file__).resolve().parents[1] / "docker"
TAG = "a" * 40 + "-123-1"


@unittest.skipIf(os.name == "nt", "Deployment scripts run on Linux")
class DeploymentTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.base = self.root / "memoryai"
        (self.base / "incoming").mkdir(parents=True)
        self.previous = self.base / "releases" / "bootstrap"
        self.previous.mkdir(parents=True)
        shutil.copy(SCRIPTS / "compose.sh", self.previous)
        (self.previous / "compose.yaml").write_text("name: memoryai\n")
        (self.previous / ".release.env").write_text("RELEASE_TAG=bootstrap\n")
        (self.base / "current").symlink_to(self.previous)
        (self.base / ".env").write_text("DB_PASS=unchanged-secret\n")
        (self.base / "database-sentinel").write_text("existing data")
        self.archive = self.base / "incoming" / "release.tar"
        with tarfile.open(self.archive, "w") as archive:
            archive.add(SCRIPTS / "compose.sh", arcname="compose.sh")
            archive.add(self.previous / "compose.yaml", arcname="compose.yaml")
            archive.add(SCRIPTS.parents[1] / "ai-rag-agent/knowledge/src/main/resources/migrations/20261116_learning_note_tree.sql",
                        arcname="migrations/20261116_learning_note_tree.sql")
        self.checksum = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        bin_dir = self.root / "bin"
        bin_dir.mkdir()
        docker = bin_dir / "docker"
        docker.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with open(os.environ["DOCKER_LOG"], "a") as log:
    log.write(json.dumps(args) + "\\n")
directory = Path(args[args.index("--project-directory") + 1])
new_release = directory.name != "bootstrap"
scenario = os.environ.get("SCENARIO", "success")
if new_release and ((scenario == "build" and "build" in args) or
                    (scenario == "health" and "up" in args) or
                    (scenario == "proxy" and "wget" in args) or
                    (scenario == "migration" and "mysql" in args)):
    sys.exit(1)
if "wget" in args:
    print('{"code":200,"data":{"records":[]}}')
''')
        docker.chmod(0o755)
        self.log = self.root / "docker.log"
        self.env = dict(os.environ, PATH=f"{bin_dir}:{os.environ['PATH']}",
                        MEMORYAI_DEPLOY_ROOT=str(self.base), DOCKER_LOG=str(self.log))

    def run_deploy(self, scenario="success", checksum=None):
        return subprocess.run(["bash", str(SCRIPTS / "deploy.sh"), TAG, checksum or self.checksum],
                              env=dict(self.env, SCENARIO=scenario), capture_output=True, text=True)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []

    def assert_data_preserved(self):
        self.assertEqual((self.base / ".env").read_text(), "DB_PASS=unchanged-secret\n")
        self.assertEqual((self.base / "database-sentinel").read_text(), "existing data")
        for call in self.calls():
            self.assertNotIn("down", call)
            if "up" in call:
                self.assertEqual(call[-2:], ["backend", "web"])
                self.assertIn("--no-deps", call)

    def test_success_switches_current_after_build_and_preserves_data(self):
        result = self.run_deploy()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.base / "current").resolve().name, TAG)
        self.assertEqual((self.base / "previous").resolve(), self.previous)
        calls = self.calls()
        self.assertLess(next(i for i,c in enumerate(calls) if "build" in c),
                        next(i for i,c in enumerate(calls) if "up" in c))
        self.assertLess(next(i for i,c in enumerate(calls) if "mysql" in c),
                        next(i for i,c in enumerate(calls) if "up" in c))
        self.assert_data_preserved()

    def test_failed_health_check_restores_previous_and_reports_failure(self):
        result = self.run_deploy("health")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous application version restored", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.previous)
        self.assertEqual(len([c for c in self.calls() if "up" in c]), 2)
        self.assert_data_preserved()

    def test_failed_proxy_check_also_rolls_back(self):
        result = self.run_deploy("proxy")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Previous application version restored", result.stderr)
        self.assertEqual((self.base / "current").resolve(), self.previous)
        self.assert_data_preserved()

    def test_failed_build_does_not_replace_running_containers(self):
        result = self.run_deploy("build")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any("up" in c for c in self.calls()))
        self.assertEqual((self.base / "current").resolve(), self.previous)
        self.assert_data_preserved()

    def test_bad_checksum_stops_before_docker(self):
        result = self.run_deploy(checksum="0" * 64)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.calls())
        self.assertEqual((self.base / "current").resolve(), self.previous)
        self.assert_data_preserved()

    def test_failed_migration_does_not_replace_running_containers(self):
        result = self.run_deploy("migration")
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(any("up" in c for c in self.calls()))
        self.assertEqual((self.base / "current").resolve(), self.previous)
        self.assert_data_preserved()


if __name__ == "__main__":
    unittest.main()
