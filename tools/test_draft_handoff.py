"""Offline guards for the explicitly authorized draft-only live test harness."""
import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from types import SimpleNamespace
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("draft_handoff", Path(__file__).with_name("draft-handoff.py"))
handoff = importlib.util.module_from_spec(spec)
spec.loader.exec_module(handoff)


class DraftHandoffGuardsTest(unittest.TestCase):
    def invoke(self, responses):
        page = MagicMock()
        page.url = "https://linux.do/"
        starts = []
        clock = SimpleNamespace(now=100.0)

        def evaluate(script, message):
            starts.append(clock.now)
            clock.now += 9  # A slow CSRF/write or GET must not consume the next gap.
            return responses.pop(0)

        def sleep(seconds):
            clock.now += seconds

        page.evaluate.side_effect = evaluate
        browser = SimpleNamespace(contexts=[SimpleNamespace(pages=[page])])
        playwright = MagicMock()
        playwright.__enter__.return_value.chromium.connect_over_cdp.return_value = browser
        with patch.object(handoff, "sync_playwright", return_value=playwright), \
                patch.object(handoff.time, "monotonic", side_effect=lambda: clock.now), \
                patch.object(handoff.time, "sleep", side_effect=sleep), \
                patch("sys.argv", ["draft-handoff.py", "--ide-home", "unused"]):
            with self.assertRaises(RuntimeError):
                handoff.main()
        return page, starts

    def setUp(self):
        self.directory = TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        root = Path(self.directory.name)
        self.root_patch = patch.object(handoff, "ROOT", root)
        self.cooldown_patch = patch.object(handoff, "COOLDOWN", root / "rate-limit.json")
        self.root_patch.start()
        self.cooldown_patch.start()
        self.addCleanup(self.root_patch.stop)
        self.addCleanup(self.cooldown_patch.stop)

    def test_first_read_429_stops_without_write_or_retry(self):
        page, _ = self.invoke([{"status": 429, "data": {}}])
        self.assertEqual(page.evaluate.call_count, 1)
        self.assertGreater(handoff.cooldown_remaining(), 1790)

    def test_write_or_csrf_429_does_not_issue_finally_read(self):
        page, _ = self.invoke([
            {"status": 200, "data": {"draft_sequence": 1, "draft": None}},
            {"status": 200, "data": {"post_stream": {"posts": [{"post_number": 3, "id": 4, "username": "test"}]}}},
            {"status": 429, "retryAfter": "3600", "data": {}},
        ])
        self.assertEqual(page.evaluate.call_count, 3)
        self.assertGreater(handoff.cooldown_remaining(), 3590)

    def test_next_request_waits_after_previous_completion(self):
        page, starts = self.invoke([
            {"status": 200, "data": {"draft_sequence": 1, "draft": None}},
            {"status": 200, "data": {"post_stream": {"posts": [{"post_number": 3, "id": 4, "username": "test"}]}}},
            {"status": 403, "data": {}},
            {"status": 200, "data": {"draft_sequence": 1, "draft": None}},
        ])
        self.assertEqual(page.evaluate.call_count, 4)
        self.assertEqual(starts, [100, 114, 128, 142])

    def test_cooldown_refuses_next_invocation_before_browser_connection(self):
        handoff.record_rate_limit()
        with patch.object(handoff, "sync_playwright") as connect, \
                patch("sys.argv", ["draft-handoff.py", "--ide-home", "unused"]):
            with self.assertRaisesRegex(RuntimeError, "no forum request issued"):
                handoff.main()
            connect.assert_not_called()

    def test_cloudflare_429_stops_without_write_or_cleanup_read(self):
        page, _ = self.invoke([{"status": 429, "requiresVerification": True, "data": {}}])
        self.assertEqual(page.evaluate.call_count, 1)
        with self.assertRaisesRegex(RuntimeError, "human verification required"):
            handoff.cooldown_remaining()

    def test_wait_mode_cannot_bypass_verification_gate(self):
        handoff.record_rate_limit(verification_required=True)
        state = json.loads(handoff.COOLDOWN.read_text())
        state["until"] = 0
        handoff.COOLDOWN.write_text(json.dumps(state))
        with patch.object(handoff, "sync_playwright") as connect, \
                patch("sys.argv", ["draft-handoff.py", "--ide-home", "unused", "--wait-cooldown"]):
            with self.assertRaisesRegex(RuntimeError, "human verification required"):
                handoff.main()
            connect.assert_not_called()

    def test_native_cloudflare_markers_and_plain_rate_limit_are_distinguished(self):
        self.assertTrue(handoff.needs_verification(429, {}, "CloudFlare verification required"))
        self.assertTrue(handoff.needs_verification(429, {"CF-Mitigated": "challenge"}, ""))
        self.assertTrue(handoff.needs_verification(429, {"Content-Type": "text/html"}, "Just a moment"))
        self.assertTrue(handoff.needs_verification(403, {"CF-Mitigated": "challenge"}, ""))
        self.assertFalse(handoff.needs_verification(429, {"Server": "cloudflare"}, "Too Many Requests"))
        self.assertFalse(handoff.needs_verification(200, {}, "Cloudflare"))


if __name__ == "__main__":
    unittest.main()
