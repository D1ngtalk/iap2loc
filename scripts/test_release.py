"""Release regressions with temporary artifacts, a disposable Git repo and mocked GitHub calls."""
import contextlib
import copy
from datetime import timedelta
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

from release import (BINARIES, EPOCH, MAX_VERSION_CODE, RELEASE_FILES, GitHub, GitHubError,
                     android_version_code, main, prepare_files, publish_release, tag_metadata, verify_tag)


class MetadataTest(unittest.TestCase):
    def test_semver_release_classification(self):
        for tag, prerelease in (
            ("v0.3.0", False), ("v10.23.456+build.012", False),
            ("v0.3.0-rc.1", True), ("v0.3.0-beta.1+build.7", True), ("v0.2.1-android", True),
        ):
            with self.subTest(tag=tag):
                self.assertEqual({"tag": tag, "version": tag[1:], "prerelease": prerelease}, tag_metadata(tag))

    def test_invalid_versions_are_rejected(self):
        for tag in ("v1", "v01.2.3", "v1.02.3", "v1.2.03", "1.2.3", "v1.2.3-01", "v1.2.3-rc..1",
                    "v1.2.3+", "v1.2.3/other", "v1.2.3\ntag=injected", "v1.2.3-rc.01"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                tag_metadata(tag)

    def test_version_code_uses_seconds_and_is_timezone_independent(self):
        self.assertEqual(1_000_000, android_version_code("2020-01-01T00:00:00Z"))
        self.assertEqual(1_000_001, android_version_code("2020-01-01T00:00:01+00:00"))
        self.assertEqual(1_000_001, android_version_code("2020-01-01T08:00:01+08:00"))
        self.assertEqual(android_version_code("2026-11-23T08:00:00Z"), android_version_code("2026-11-23T08:00:00Z"))

    def test_version_code_boundaries_and_invalid_dates(self):
        last = EPOCH + timedelta(seconds=MAX_VERSION_CODE - 1_000_000)
        self.assertEqual(MAX_VERSION_CODE, android_version_code(last.isoformat()))
        for value in ((last + timedelta(seconds=1)).isoformat(), "1970-01-01T00:00:00Z", "not-a-date", "2026-11-23T08:00:00"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                android_version_code(value)

    def test_run_creation_time_is_used_instead_of_rerun_start_time(self):
        response = {"created_at": "2026-11-23T08:00:00Z", "run_started_at": "2026-12-01T12:00:00Z"}
        env = {"GITHUB_REPOSITORY": "owner/repo", "GITHUB_RUN_ID": "12345", "GH_TOKEN": "test-token"}
        output = io.StringIO()
        with patch.dict(os.environ, env), patch("release.GitHub.request", return_value=response) as request:
            with patch.object(sys, "argv", ["release.py", "android-version-code"]), contextlib.redirect_stdout(output):
                main()
        self.assertEqual(str(android_version_code(response["created_at"])), output.getvalue().strip())
        request.assert_called_once_with("GET", "/repos/owner/repo/actions/runs/12345")


class TagCheckoutTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory(prefix="iap2loc-release-git-test-")
        self.addCleanup(folder.cleanup)
        self.repo = Path(folder.name)
        self.git("init", "-q")
        self.git("commit", "--allow-empty", "-qm", "Temporary release test fixture")
        self.git("tag", "-a", "v0.3.0", "-m", "Temporary fixture")
        self.git("tag", "v0.3.0-rc.1")
        self.commit = self.git("rev-parse", "HEAD").strip()

    def git(self, *args):
        return subprocess.check_output([
            "git", "-c", "user.name=Release tests", "-c", "user.email=release-test@example.invalid",
            "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args,
        ], cwd=self.repo, text=True, stderr=subprocess.PIPE)

    def test_both_annotated_and_lightweight_aliases_of_the_same_commit_are_valid(self):
        with contextlib.chdir(self.repo):
            self.assertEqual(self.commit, verify_tag("v0.3.0")["commit"])
            self.assertEqual(self.commit, verify_tag("v0.3.0-rc.1", self.commit)["commit"])

    def test_wrong_checkout_or_coordinator_commit_is_rejected(self):
        with contextlib.chdir(self.repo), self.assertRaises(ValueError):
            verify_tag("v0.3.0", "0" * 40)
        self.git("commit", "--allow-empty", "-qm", "Another temporary commit")
        with contextlib.chdir(self.repo), self.assertRaises(ValueError):
            verify_tag("v0.3.0")

    def test_cli_exports_verified_metadata(self):
        output = self.repo / "github-output"
        script = Path(__file__).with_name("release.py").resolve()
        result = subprocess.run([sys.executable, str(script), "verify-tag", "v0.3.0-rc.1",
                                 "--github-output", str(output)], cwd=self.repo, capture_output=True, text=True, check=True)
        self.assertEqual(self.commit, json.loads(result.stdout)["commit"])
        self.assertIn("prerelease=true\n", output.read_text())
        self.assertIn(f"commit={self.commit}\n", output.read_text())


class FakeGitHub:
    def __init__(self, release=None):
        self.release = copy.deepcopy(release)
        self.calls = []
        self.fail_upload = False
        self.omit_asset = False
        self.wrong_size = False

    def find_release(self, tag):
        self.calls.append("find")
        return copy.deepcopy(self.release)

    def create_draft(self, metadata, commit):
        self.calls.append("create")
        self.release = {"id": 1, "tag_name": metadata["tag"], "draft": True,
                        "prerelease": metadata["prerelease"], "assets": [], "target_commitish": commit}
        return copy.deepcopy(self.release)

    def upload(self, tag, files):
        self.calls.append("upload")
        assets = [{"name": file.name, "size": file.stat().st_size, "state": "uploaded"} for file in files]
        self.release["assets"] = assets
        if self.fail_upload:
            self.release["assets"] = assets[:2]
            raise RuntimeError("Simulated upload failure")
        if self.omit_asset:
            assets.pop()
        if self.wrong_size:
            assets[0]["size"] += 1

    def get_release(self, release_id):
        self.calls.append("get")
        return copy.deepcopy(self.release)

    def publish(self, release_id, prerelease):
        self.calls.append("publish")
        self.release.update(draft=False, prerelease=prerelease, html_url="https://example.invalid/release")
        return copy.deepcopy(self.release)


class PublisherTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="iap2loc-release-files-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.artifacts = self.root / "artifacts"
        for artifact, filename in BINARIES.items():
            directory = self.artifacts / artifact
            directory.mkdir(parents=True)
            data = f"Disposable fixture for {filename}".encode()
            (directory / filename).write_bytes(data)
            (directory / "SHA256SUMS").write_bytes(f"{hashlib.sha256(data).hexdigest()}  {filename}\r\n".encode())

    def publish(self, github, tag="v0.3.0", signed=True):
        with contextlib.redirect_stdout(io.StringIO()):
            publish_release(github, tag, "a" * 40, self.artifacts, signed)

    def published_release(self, complete=True):
        return {"id": 1, "draft": False, "assets": [
            {"name": name, "state": "uploaded", "size": 100}
            for name in (RELEASE_FILES if complete else list(RELEASE_FILES)[:2])
        ]}

    def test_payload_contains_exactly_seven_files_and_valid_manifests(self):
        destination = self.root / "payload"
        files = prepare_files(self.artifacts, destination)
        self.assertEqual(RELEASE_FILES, {file.name for file in files})
        for manifest in destination.glob("*.sha256"):
            for entry in manifest.read_text().splitlines():
                digest, filename = entry.split("  ", 1)
                self.assertEqual(digest, hashlib.sha256((destination / filename).read_bytes()).hexdigest())

    def test_release_is_created_as_draft_and_published_only_after_upload_verification(self):
        github = FakeGitHub()
        self.publish(github)
        self.assertEqual(["find", "create", "upload", "get", "publish"], github.calls)
        self.assertFalse(github.release["draft"])
        self.assertFalse(github.release["prerelease"])
        self.assertEqual(RELEASE_FILES, {asset["name"] for asset in github.release["assets"]})
        self.assertEqual("a" * 40, github.release["target_commitish"])

    def test_suffixed_tag_is_published_as_prerelease(self):
        github = FakeGitHub()
        self.publish(github, "v0.3.0-rc.1")
        self.assertTrue(github.release["prerelease"])

    def test_upload_failure_keeps_draft_and_retry_resumes_it(self):
        github = FakeGitHub()
        github.fail_upload = True
        with self.assertRaises(RuntimeError):
            self.publish(github)
        self.assertTrue(github.release["draft"])
        self.assertNotIn("publish", github.calls)
        github.fail_upload = False
        self.publish(github)
        self.assertEqual(1, github.calls.count("create"))
        self.assertEqual(1, github.calls.count("publish"))

    def test_already_complete_public_release_is_not_modified(self):
        github = FakeGitHub(self.published_release())
        self.publish(github)
        self.assertEqual(["find"], github.calls)

    def test_incomplete_public_release_is_rejected_without_overwriting(self):
        github = FakeGitHub(self.published_release(complete=False))
        with self.assertRaises(ValueError):
            self.publish(github)
        self.assertEqual(["find"], github.calls)

    def test_unsigned_android_or_invalid_tag_prevents_any_github_calls(self):
        for tag, signed in (("v0.3.0", False), ("vbad", True)):
            with self.subTest(tag=tag):
                github = FakeGitHub()
                with self.assertRaises(ValueError):
                    self.publish(github, tag, signed)
                self.assertEqual([], github.calls)

    def test_missing_installer_prevents_any_github_calls(self):
        filename = BINARIES["iap2loc-android"]
        (self.artifacts / "iap2loc-android" / filename).unlink()
        github = FakeGitHub()
        with self.assertRaises(ValueError):
            self.publish(github)
        self.assertEqual([], github.calls)

    def test_checksum_mismatch_prevents_any_github_calls(self):
        filename = BINARIES["iap2loc-windows-x64"]
        (self.artifacts / "iap2loc-windows-x64" / filename).write_bytes(b"corrupt fixture")
        github = FakeGitHub()
        with self.assertRaises(ValueError):
            self.publish(github)
        self.assertEqual([], github.calls)

    def test_manifest_cannot_reference_another_file_or_multiple_files(self):
        directory = self.artifacts / "iap2loc-android"
        for contents in (f"{'a' * 64}  ../outside.apk\n", "invalid checksum\n", "two\nentries\n"):
            with self.subTest(contents=contents):
                (directory / "SHA256SUMS").write_text(contents)
                github = FakeGitHub()
                with self.assertRaises(ValueError):
                    self.publish(github)
                self.assertEqual([], github.calls)

    def test_missing_or_truncated_upload_keeps_release_as_draft(self):
        for flag in ("omit_asset", "wrong_size"):
            with self.subTest(flag=flag):
                github = FakeGitHub()
                setattr(github, flag, True)
                with self.assertRaises(ValueError):
                    self.publish(github)
                self.assertTrue(github.release["draft"])
                self.assertNotIn("publish", github.calls)


class GitHubClientTest(unittest.TestCase):
    def setUp(self):
        self.github = GitHub("owner/repo", "disposable-test-token")

    def test_only_404_falls_back_to_searching_drafts(self):
        draft = {"id": 12, "tag_name": "v0.3.0", "draft": True}
        with patch.object(self.github, "request", side_effect=[GitHubError(404, "tag"), [draft]]) as request:
            self.assertEqual(draft, self.github.find_release("v0.3.0"))
            self.assertEqual(2, request.call_count)
        for status in (401, 403, 500):
            with self.subTest(status=status):
                with patch.object(self.github, "request", side_effect=GitHubError(status, "tag")) as request:
                    with self.assertRaises(GitHubError):
                        self.github.find_release("v0.3.0")
                    self.assertEqual(1, request.call_count)

    def test_absent_release_is_returned_only_after_successful_draft_search(self):
        with patch.object(self.github, "request", side_effect=[GitHubError(404, "tag"), []]):
            self.assertIsNone(self.github.find_release("v0.3.0"))
        with patch.object(self.github, "request", side_effect=[GitHubError(404, "tag"), GitHubError(404, "repository")]):
            with self.assertRaises(GitHubError):
                self.github.find_release("v0.3.0")

    def test_draft_lookup_paginates(self):
        draft = {"tag_name": "v0.3.0", "draft": True}
        pages = [GitHubError(404, "tag"), [{"tag_name": "v1.0.0"}] * 100, [draft]]
        with patch.object(self.github, "request", side_effect=pages) as request:
            self.assertEqual(draft, self.github.find_release("v0.3.0"))
            self.assertTrue(request.call_args.args[1].endswith("page=2"))

    def test_http_and_network_failures_are_not_silenced_or_reported_with_tokens(self):
        for error in (HTTPError("https://example.invalid", 403, "Forbidden", {}, None), URLError("network failed")):
            with self.subTest(error=type(error).__name__), patch("release.urlopen", side_effect=error):
                with self.assertRaises(RuntimeError) as raised:
                    self.github.find_release("v0.3.0")
                self.assertNotIn(self.github.token, str(raised.exception))

    def test_create_generates_notes_and_sets_draft_and_prerelease_explicitly(self):
        with patch.object(self.github, "request", side_effect=[{"body": "Generated notes"}, {"id": 1}]) as request:
            self.github.create_draft(tag_metadata("v0.3.0-rc.1"), "a" * 40)
            self.assertEqual("/repos/owner/repo/releases/generate-notes", request.call_args_list[0].args[1])
            body = request.call_args_list[1].args[2]
            self.assertEqual("Generated notes", body["body"])
            self.assertTrue(body["draft"])
            self.assertTrue(body["prerelease"])

    def test_binary_upload_uses_argument_list_and_token_environment(self):
        with patch("release.subprocess.run") as process:
            self.github.upload("v0.3.0", [Path("/tmp/installer with spaces.apk")])
            args = process.call_args.args[0]
            self.assertIn("/tmp/installer with spaces.apk", args)
            self.assertNotIn(self.github.token, args)
            self.assertEqual(self.github.token, process.call_args.kwargs["env"]["GH_TOKEN"])


if __name__ == "__main__":
    unittest.main()
