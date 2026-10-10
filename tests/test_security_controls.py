import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data-pipeline"))
from security_controls import is_verified_official_source, read_bounded_chunks
from aggregator import make_item


class SecurityControlsTest(unittest.TestCase):
    def setUp(self):
        self.source = {"category": "official"}

    def test_kuro_official_domain_is_verified(self):
        self.assertTrue(is_verified_official_source(self.source, "https://wutheringwaves.kurogames.com/en/"))

    def test_official_x_account_is_verified(self):
        self.assertTrue(is_verified_official_source(self.source, "https://x.com/Wuthering_Waves/status/123"))

    def test_unverified_or_lookalike_publishers_are_not_official(self):
        for url in (None, "https://news.google.com/", "https://wutheringwaves.kurogames.com.attacker.example/", "https://x.com/Wuthering_WavesFake/status/1", "http://x.com/Wuthering_Waves/status/1"):
            with self.subTest(url=url):
                self.assertFalse(is_verified_official_source(self.source, url))

    def test_non_official_source_category_cannot_be_verified(self):
        self.assertFalse(is_verified_official_source({"category": "community"}, "https://wutheringwaves.kurogames.com/"))

    def test_source_response_limit_is_enforced(self):
        self.assertEqual(b"abc", read_bounded_chunks([b"a", b"bc"], 3))
        with self.assertRaises(ValueError):
            read_bounded_chunks([b"abc", b"d"], 3)

    def test_official_category_alone_does_not_assign_official_status(self):
        source = {"id": "x-search-news", "kind": "google_news", "category": "official"}
        item = make_item(source, "Wuthering Waves version 3.8 update", "https://news.google.com/rss/articles/example", publisher="unknown", publisher_url="https://news.google.com/")
        self.assertEqual("COMMUNITY", item["status"])
        self.assertNotEqual("Official source", item["sourceType"])

    def test_allowlisted_publisher_can_be_marked_official(self):
        source = {"id": "x-search-news", "kind": "google_news", "category": "official"}
        item = make_item(source, "Wuthering Waves version 3.8 update", "https://news.google.com/rss/articles/example", publisher="Wuthering Waves", publisher_url="https://x.com/Wuthering_Waves")
        self.assertEqual("OFFICIAL", item["status"])
        self.assertEqual("Official source", item["sourceType"])


if __name__ == "__main__":
    unittest.main()
