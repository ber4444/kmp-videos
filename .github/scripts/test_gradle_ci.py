"""Exercise the CI launcher with an external Gradle process and no network."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


LAUNCHER = Path(__file__).with_name("gradle-ci.sh").resolve()
# The real default-branch failure, with runner timestamps removed.
PLUGIN_FAILURE = """FAILURE: Build failed with an exception.
* What went wrong:
Plugin [id: 'org.jetbrains.kotlinx.binary-compatibility-validator', version: '0.18.2'] was not found in any of the following sources:
- Plugin Repositories (could not resolve plugin artifact 'org.jetbrains.kotlinx.binary-compatibility-validator:org.jetbrains.kotlinx.binary-compatibility-validator.gradle.plugin:0.18.2')
BUILD FAILED in 22s
"""


class GradleCiTest(unittest.TestCase):
    def run_launcher(self, responses, *args, tee_failure=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "responses.json").write_text(json.dumps(responses))
            (root / "gradlew").write_text("""#!/usr/bin/env python3
import json, sys
from pathlib import Path
state = Path('calls.json')
calls = json.loads(state.read_text()) if state.exists() else []
calls.append(sys.argv[1:])
state.write_text(json.dumps(calls))
responses = json.loads(Path('responses.json').read_text())
response = responses[min(len(calls) - 1, len(responses) - 1)]
print(response['log'])
sys.exit(response['status'])
""")
            (root / "gradlew").chmod(0o755)
            (root / "sleep").write_text('#!/bin/sh\nprintf "%s\\n" "$1" >> sleeps.txt\n')
            (root / "sleep").chmod(0o755)
            if tee_failure:
                (root / "tee").write_text('#!/bin/sh\ncat > /dev/null\nexit 74\n')
                (root / "tee").chmod(0o755)
            result = subprocess.run(
                ["bash", str(LAUNCHER), *args], cwd=root, text=True,
                capture_output=True, env={**os.environ, "PATH": str(root) + os.pathsep + os.environ["PATH"]},
                timeout=10,
            )
            calls = json.loads((root / "calls.json").read_text()) if (root / "calls.json").exists() else []
            sleeps = (root / "sleeps.txt").read_text().splitlines() if (root / "sleeps.txt").exists() else []
            return result, calls, sleeps

    def test_plugin_resolution_failure_recovers_and_refreshes_metadata(self):
        result, calls, sleeps = self.run_launcher([
            {"status": 1, "log": PLUGIN_FAILURE}, {"status": 0, "log": "BUILD SUCCESSFUL"},
        ], "assembleDebug", "-Pmessage=two words")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(calls, [
            ["--no-daemon", "--console=plain", "assembleDebug", "-Pmessage=two words"],
            ["--no-daemon", "--console=plain", "--refresh-dependencies", "--info", "assembleDebug", "-Pmessage=two words"],
        ])
        self.assertEqual(sleeps, ["10"])
        self.assertIn("BUILD FAILED", result.stdout)
        self.assertIn("BUILD SUCCESSFUL", result.stdout)

    def test_persistent_resolution_failure_is_bounded_and_preserves_exit_code(self):
        result, calls, sleeps = self.run_launcher([{"status": 42, "log": PLUGIN_FAILURE}], "assembleDebug")
        self.assertEqual(result.returncode, 42)
        self.assertEqual(len(calls), 3)
        self.assertEqual(sleeps, ["10", "20"])

    def test_compiler_failure_is_not_retried(self):
        result, calls, sleeps = self.run_launcher([
            {"status": 1, "log": "> Task :composeApp:compileDebugKotlin FAILED\ne: Unresolved reference: broken"},
        ], "assembleDebug")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_test_output_cannot_trigger_a_plugin_retry(self):
        result, calls, sleeps = self.run_launcher([
            {"status": 1, "log": "> Task :composeApp:testAndroidHostTest FAILED\n" + PLUGIN_FAILURE},
        ], ":composeApp:testAndroidHostTest")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_success_runs_once_without_refresh(self):
        result, calls, sleeps = self.run_launcher([{"status": 0, "log": "BUILD SUCCESSFUL"}], ":mediakit:apiCheck")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(calls), 1)
        self.assertNotIn("--refresh-dependencies", calls[0])
        self.assertEqual(sleeps, [])

    def test_signal_exit_is_not_retried(self):
        result, calls, sleeps = self.run_launcher([{"status": 130, "log": PLUGIN_FAILURE}], "assembleDebug")
        self.assertEqual(result.returncode, 130)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])

    def test_logging_failure_cannot_turn_into_a_successful_build(self):
        result, calls, sleeps = self.run_launcher(
            [{"status": 0, "log": "BUILD SUCCESSFUL"}], "assembleDebug", tee_failure=True,
        )
        self.assertEqual(result.returncode, 74)
        self.assertEqual(len(calls), 1)
        self.assertEqual(sleeps, [])


if __name__ == "__main__":
    unittest.main()
