"""Read-only verification of a public GitHub Release asset.

This adapter never uploads, edits, or deletes a release. It checks the public
API response against the expected artifact so a release page cannot silently
drift from the manifest.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.request

from .base import Capability, ManualActionRequired, StoreAdapter, SubmissionResult

API = "https://api.github.com/repos/{repo}/releases/tags/{tag}"
USER_AGENT = "linexa-store-publisher/1.0 (+https://github.com/ForExample25/Linexa)"


class GitHubReleaseAdapter(StoreAdapter):
    id = "github-releases"
    name = "GitHub Releases"
    portal = "https://github.com/ForExample25/Linexa/releases"

    def capabilities(self) -> Capability:
        return Capability(
            can_verify=True,
            can_submit=False,
            reason=(
                "The release already exists. Publishing a new release requires "
                "the owner's git tag and GitHub token; this adapter is read-only."
            ),
        )

    def verify(
        self,
        repo: str,
        tag: str,
        asset_name: str,
        expected_sha256: str,
        expected_size: int | None = None,
    ) -> dict:
        url = API.format(repo=repo, tag=tag)
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "User-Agent": USER_AGENT,
                "X-GitHub-Api-Version": "2022-11-28",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                payload = json.load(response)
        except urllib.error.HTTPError as exc:
            raise RuntimeError(f"GitHub API returned HTTP {exc.code} for {url}") from exc
        except urllib.error.URLError as exc:
            raise RuntimeError(f"GitHub API request failed: {exc.reason}") from exc

        if payload.get("tag_name") != tag:
            raise RuntimeError(
                f"Release tag mismatch: expected {tag}, got {payload.get('tag_name')}"
            )
        if payload.get("draft"):
            raise RuntimeError("Release is a draft; it is not public")

        assets = {asset["name"]: asset for asset in payload.get("assets", [])}
        asset = assets.get(asset_name)
        if asset is None:
            raise RuntimeError(
                f"Asset {asset_name!r} not found; available: {sorted(assets)}"
            )

        digest = (asset.get("digest") or "").lower()
        expected_digest = f"sha256:{expected_sha256.lower()}"
        checks = {
            "tag": payload.get("tag_name") == tag,
            "prerelease": bool(payload.get("prerelease")),
            "asset_name": asset.get("name") == asset_name,
            "size": expected_size is None or asset.get("size") == expected_size,
            "digest_present": bool(digest),
            "digest_matches": digest == expected_digest,
        }
        failures = [name for name, ok in checks.items() if not ok and name != "prerelease"]
        if failures:
            raise RuntimeError(
                "GitHub Release verification failed: "
                + ", ".join(failures)
                + f"; digest={digest or '<none>'}, size={asset.get('size')}"
            )

        return {
            "ok": True,
            "repo": repo,
            "tag": tag,
            "html_url": payload.get("html_url"),
            "published_at": payload.get("published_at"),
            "prerelease": bool(payload.get("prerelease")),
            "asset": {
                "name": asset.get("name"),
                "size": asset.get("size"),
                "digest": digest,
                "download_url": asset.get("browser_download_url"),
            },
            "checks": checks,
        }

    def submit(self, **_kwargs) -> SubmissionResult:
        raise ManualActionRequired(
            self.name,
            self.portal,
            [
                "A new release needs an owner-created tag and a signed build; "
                "this adapter is intentionally read-only."
            ],
        )
