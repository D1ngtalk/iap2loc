#!/usr/bin/env python3
"""Version metadata and all-platform release publishing; no third-party Python dependencies."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen


TAG_PATTERN = re.compile(
    r"v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
)
EPOCH = datetime(2020, 1, 1, tzinfo=timezone.utc)
MAX_VERSION_CODE = 2_100_000_000
BINARIES = {
    "iap2loc-windows-x64": "iap2loc-windows-x64.exe",
    "iap2loc-macos-arm64": "iap2loc-macos-arm64.dmg",
    "iap2loc-macos-x64": "iap2loc-macos-x64.dmg",
    "iap2loc-android": "iap2loc-android.apk",
}
RELEASE_FILES = frozenset(BINARIES.values()) | {
    "iap2loc-windows-x64.exe.sha256", "iap2loc-macos.sha256", "iap2loc-android.apk.sha256",
}


def tag_metadata(tag):
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ValueError("版本标签必须为 v主.次.修订，可带 SemVer 预发布后缀和构建元数据")
    prerelease = match.group(4)
    if prerelease and any(part.isdigit() and len(part) > 1 and part[0] == "0" for part in prerelease.split(".")):
        raise ValueError("SemVer 预发布数字标识不能包含前导零")
    return {"tag": tag, "version": tag[1:], "prerelease": bool(prerelease)}


def verify_tag(tag, expected_commit=None):
    metadata = tag_metadata(tag)
    def git(*args):
        return subprocess.check_output(["git", *args], text=True, stderr=subprocess.PIPE).strip()
    commit = git("rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}")
    if git("rev-parse", "HEAD") != commit or (expected_commit and expected_commit != commit):
        raise ValueError("检出的提交与指定版本标签或编排器确认的提交不一致")
    return dict(metadata, commit=commit)


def android_version_code(created_at):
    created = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    if created.tzinfo is None:
        raise ValueError("运行创建时间必须包含时区")
    value = int((created - EPOCH).total_seconds()) + 1_000_000
    if not 1 <= value <= MAX_VERSION_CODE:
        raise ValueError("Android versionCode 超出 1..2100000000 范围")
    return value


class GitHubError(RuntimeError):
    def __init__(self, status, path):
        self.status = status
        super().__init__(f"GitHub API 请求失败：HTTP {status}，{path}")


class GitHub:
    def __init__(self, repository, token, api_url="https://api.github.com"):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("无效的 GITHUB_REPOSITORY")
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self.base = f"/repos/{repository}"

    def request(self, method, path, data=None):
        request = Request(self.api_url + path, method=method,
                          data=None if data is None else json.dumps(data).encode(), headers={
                              "Authorization": f"Bearer {self.token}",
                              "Accept": "application/vnd.github+json",
                              "Content-Type": "application/json",
                              "X-GitHub-Api-Version": "2022-11-28",
                          })
        try:
            with urlopen(request, timeout=60) as response:
                return json.load(response)
        except HTTPError as error:
            raise GitHubError(error.code, path) from None
        except URLError:
            raise RuntimeError("无法连接 GitHub API") from None

    def find_release(self, tag):
        try:
            return self.request("GET", f"{self.base}/releases/tags/{quote(tag, safe='')}")
        except GitHubError as error:
            if error.status != 404:
                raise
        # The tag endpoint may omit unpublished drafts. List releases with the authenticated
        # token so retries resume a draft instead of attempting to create the same tag twice.
        page = 1
        while True:
            releases = self.request("GET", f"{self.base}/releases?per_page=100&page={page}")
            for release in releases:
                if release["tag_name"] == tag:
                    return release
            if len(releases) < 100:
                return None
            page += 1

    def create_draft(self, metadata, commit):
        notes = self.request("POST", f"{self.base}/releases/generate-notes", {
            "tag_name": metadata["tag"], "target_commitish": commit,
        })
        return self.request("POST", f"{self.base}/releases", {
            "tag_name": metadata["tag"], "target_commitish": commit,
            "name": metadata["tag"], "body": notes["body"],
            "draft": True, "prerelease": metadata["prerelease"],
        })

    def upload(self, tag, files):
        environment = os.environ.copy()
        environment["GH_TOKEN"] = self.token
        subprocess.run(["gh", "release", "upload", tag, *map(str, files),
                        "--repo", self.repository, "--clobber"], env=environment, check=True)

    def get_release(self, release_id):
        return self.request("GET", f"{self.base}/releases/{release_id}")

    def publish(self, release_id, prerelease):
        return self.request("PATCH", f"{self.base}/releases/{release_id}", {
            "draft": False, "prerelease": prerelease,
        })

    def run_created_at(self, run_id):
        if not re.fullmatch(r"[0-9]+", run_id):
            raise ValueError("无效的 GITHUB_RUN_ID")
        return self.request("GET", f"{self.base}/actions/runs/{run_id}")["created_at"]


def prepare_files(artifacts, destination):
    """Verify every input before copying four binaries and three manifests into the payload."""
    verified = {}
    for artifact, filename in BINARIES.items():
        directory = artifacts / artifact
        entries = (directory / "SHA256SUMS").read_text(encoding="ascii").splitlines()
        if len(entries) != 1:
            raise ValueError(f"校验文件必须只包含对应安装包：{artifact}")
        entry = re.fullmatch(r"([0-9a-fA-F]{64}) [ *](.+)", entries[0])
        if not entry or entry.group(2) != filename:
            raise ValueError(f"校验文件中的安装包名称不正确：{artifact}")
        binary = directory / filename
        if not binary.is_file() or binary.stat().st_size == 0:
            raise ValueError(f"缺少有效安装包：{filename}")
        digest = hashlib.sha256()
        with binary.open("rb") as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != entry.group(1).lower():
            raise ValueError(f"SHA256 不匹配：{filename}")
        verified[filename] = digest.hexdigest()

    destination.mkdir(parents=True, exist_ok=True)
    for artifact, filename in BINARIES.items():
        shutil.copyfile(artifacts / artifact / filename, destination / filename)
    for manifest, names in (
        ("iap2loc-windows-x64.exe.sha256", ["iap2loc-windows-x64.exe"]),
        ("iap2loc-android.apk.sha256", ["iap2loc-android.apk"]),
        ("iap2loc-macos.sha256", ["iap2loc-macos-arm64.dmg", "iap2loc-macos-x64.dmg"]),
    ):
        (destination / manifest).write_text("".join(f"{verified[name]}  {name}\n" for name in names), encoding="ascii")
    return [destination / name for name in sorted(RELEASE_FILES)]


def uploaded_assets(release):
    return {asset["name"]: asset for asset in release["assets"]
            if asset.get("state") == "uploaded" and asset.get("size", 0) > 0}


def publish_release(github, tag, commit, artifacts, android_signed):
    metadata = tag_metadata(tag)
    if not android_signed:
        raise ValueError("Android APK 未使用正式签名，禁止发布")
    with tempfile.TemporaryDirectory(prefix="iap2loc-release-") as folder:
        files = prepare_files(artifacts, Path(folder))
        release = github.find_release(tag)
        if release and not release["draft"]:
            if not RELEASE_FILES.issubset(uploaded_assets(release)):
                raise ValueError("该 Release 已公开但附件不齐全，请人工处理；不会覆盖已公开版本")
            print(f"{tag} 已发布且附件齐全，跳过重复发布")
            return
        if release is None:
            release = github.create_draft(metadata, commit)
        github.upload(tag, files)
        uploaded = github.get_release(release["id"])
        actual = uploaded_assets(uploaded)
        if not uploaded["draft"]:
            raise ValueError("上传期间 Release 已被其他操作公开，停止发布")
        if any(path.name not in actual or actual[path.name]["size"] != path.stat().st_size for path in files):
            raise ValueError("Release 附件缺失或大小不符，保留草稿供重跑")
        published = github.publish(release["id"], metadata["prerelease"])
        print(f"已发布 {tag}：{published['html_url']}")


def github_from_environment():
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ValueError("缺少 GitHub Token")
    return GitHub(os.environ["GITHUB_REPOSITORY"], token,
                  os.environ.get("GITHUB_API_URL", "https://api.github.com"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("tag")
    verify = commands.add_parser("verify-tag")
    verify.add_argument("tag")
    verify.add_argument("--expected-commit")
    verify.add_argument("--github-output", type=Path)
    version = commands.add_parser("android-version-code")
    version.add_argument("--created-at")
    publish = commands.add_parser("publish")
    publish.add_argument("tag")
    publish.add_argument("artifacts", type=Path)
    publish.add_argument("--expected-commit", required=True)
    publish.add_argument("--android-signed", choices=("true", "false"), required=True)
    args = parser.parse_args()
    if args.command == "validate":
        print(json.dumps(tag_metadata(args.tag)))
    elif args.command == "verify-tag":
        metadata = verify_tag(args.tag, args.expected_commit)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key, value in metadata.items():
                    output.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
        print(json.dumps(metadata))
    elif args.command == "android-version-code":
        created_at = args.created_at or github_from_environment().run_created_at(os.environ["GITHUB_RUN_ID"])
        print(android_version_code(created_at))
    else:
        verify_tag(args.tag, args.expected_commit)
        publish_release(github_from_environment(), args.tag, args.expected_commit,
                        args.artifacts, args.android_signed == "true")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
