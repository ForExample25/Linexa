from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

TOOL_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = TOOL_ROOT.parents[1]
sys.path.insert(0, str(TOOL_ROOT))

import publisher  # noqa: E402
from adapters import GitHubReleaseAdapter, build_adapters  # noqa: E402
from adapters.base import ManualActionRequired  # noqa: E402


class ConfigTests(unittest.TestCase):
    def test_store_config_is_consistent(self) -> None:
        stores, limits, expected = publisher.config()
        ids = [store["id"] for store in stores["stores"]]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertEqual(stores["schema"], 1)
        self.assertEqual(limits["schema"], 1)
        self.assertEqual(expected["package"], stores["app"]["package"])
        for store in stores["stores"]:
            self.assertIn(store["status"], publisher.STATUS_ORDER)
            self.assertTrue((REPO_ROOT / store["metadata"]).is_file(), store["metadata"])
            if store.get("packet"):
                self.assertIn(store["id"], {"samsung", "xiaomi", "huawei", "amazon", "aptoide", "uptodown", "apkpure"})

    def test_expected_artifact_matches_manifest(self) -> None:
        _stores, _limits, expected = publisher.config()
        self.assertEqual(expected["size"], 57257408)
        self.assertEqual(
            expected["sha256"],
            "6ce2d01922ff9a73dea0b37787e5f5e4e83f1b7c52157d3670510ab510293680",
        )
        self.assertEqual(expected["targetSdk"], 34)
        self.assertEqual(expected["abis"], ["arm64-v8a", "armeabi-v7a"])

    def test_all_adapters_are_honest(self) -> None:
        stores, _limits, _expected = publisher.config()
        adapters = build_adapters(stores["stores"])
        for store_id, adapter in adapters.items():
            cap = adapter.capabilities()
            if store_id == "github-releases":
                self.assertTrue(cap.can_verify)
            else:
                self.assertFalse(cap.can_submit)
            with self.assertRaises(ManualActionRequired):
                adapter.submit()

    def test_github_adapter_is_read_only(self) -> None:
        cap = GitHubReleaseAdapter().capabilities()
        self.assertTrue(cap.can_verify)
        self.assertFalse(cap.can_submit)


class ImageTests(unittest.TestCase):
    def test_png_size(self) -> None:
        icon = REPO_ROOT / "docs/distribution/assets/store-icon/icon-512.png"
        self.assertEqual(publisher.png_size(icon), (512, 512))

    def test_android_portrait_screenshot(self) -> None:
        shot = REPO_ROOT / "docs/distribution/assets/screenshots/phone/01-home.png"
        self.assertEqual(publisher.png_size(shot), (1080, 1920))


class LintTests(unittest.TestCase):
    def test_lint_has_no_errors(self) -> None:
        result = publisher.lint(REPO_ROOT)
        self.assertTrue(result["ok"], "\n".join(result["errors"]))

    def test_lint_reports_limits(self) -> None:
        result = publisher.lint(REPO_ROOT)
        self.assertTrue(any("title:" in line for line in result["info"]))
        self.assertTrue(any("phone screenshots:" in line for line in result["info"]))


class PacketTests(unittest.TestCase):
    def test_amazon_packet_uses_landscape_screenshots(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / "amazon"
            result = publisher.packet("amazon", REPO_ROOT, output, as_json=True)
            self.assertTrue(result["ok"])
            self.assertTrue((output / "README.md").is_file())
            self.assertTrue((output / "LISTING.md").is_file())
            screenshots = sorted((output / "assets" / "screenshots").glob("*.png"))
            self.assertGreaterEqual(len(screenshots), 3)
            for screenshot in screenshots:
                self.assertEqual(publisher.png_size(screenshot), (1920, 1080))

    def test_google_play_has_no_manual_packet(self) -> None:
        with self.assertRaises(SystemExit):
            publisher.packet("google-play", REPO_ROOT, Path("/tmp/should-not-exist"))


class ReportTests(unittest.TestCase):
    def test_report_is_generated(self) -> None:
        result = publisher.report(REPO_ROOT, as_json=True)
        self.assertEqual(result["app"]["package"], "com.fem.linexa")
        self.assertTrue(Path(result["report_paths"][0]).is_file())
        payload = json.loads(Path(result["report_paths"][1]).read_text())
        self.assertEqual(payload["expected_artifact"]["sha256"], result["expected_artifact"]["sha256"])


if __name__ == "__main__":
    unittest.main()
