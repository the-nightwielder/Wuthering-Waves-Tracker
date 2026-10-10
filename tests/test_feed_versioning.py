import sys
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "data-pipeline"))
from aggregator import official_json_items, target_version_from_article


class OfficialNewsFeedTest(unittest.TestCase):
    def setUp(self):
        self.source = {"id": "official-site-feed", "kind": "official_json",
                       "category": "official", "priority": 110}

    def test_kuro_news_rows_are_verified_deduplicated_and_dated(self):
        china_time = timezone(timedelta(hours=8))
        local_now = datetime.now(timezone.utc).astimezone(china_time).replace(microsecond=0)
        create_time = local_now.strftime("%Y-%m-%d %H:%M:%S")
        expected_published = local_now.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")
        payload = {"article": [
            {"articleId": 5603, "articleTitle": "Wuthering Waves Event Announcement",
             "createTime": create_time, "articleType": 0},
            {"articleId": 5603, "articleTitle": "Wuthering Waves Event Announcement",
             "createTime": create_time, "articleType": 58},
            {"articleId": "../../bad", "articleTitle": "Wuthering Waves invalid ID",
             "createTime": create_time},
            {"articleId": 5604, "articleTitle": "Wuthering Waves missing date"},
            {"articleId": 5605, "articleTitle": "Wuthering Waves old archive item",
             "createTime": "2024-03-29 22:00:30"},
        ]}
        items = list(official_json_items(self.source, payload))
        self.assertEqual(1, len(items))
        item = items[0]
        self.assertEqual("OFFICIAL", item["status"])
        self.assertEqual("Official source", item["sourceType"])
        self.assertEqual(expected_published, item["publishedAt"])
        self.assertEqual("https://wutheringwaves.kurogames.com/en/main/news/detail/5603", item["url"])

    def test_malformed_news_payload_is_ignored(self):
        self.assertEqual([], list(official_json_items(self.source, {"article": {"bad": "shape"}})))


class NextVersionAttributionTest(unittest.TestCase):
    def test_old_explicit_version_does_not_inherit_search_target(self):
        item = {"title": "Wuthering Waves version 3.6 banners and leaks", "summary": "Old patch details"}
        self.assertIsNone(target_version_from_article(item, "3.8"))

    def test_next_or_later_explicit_headline_version_is_attributed(self):
        self.assertEqual("3.8", target_version_from_article({"title": "WuWa 3.8 banners", "summary": ""}, "3.8"))
        self.assertEqual("3.9", target_version_from_article({"title": "Wuthering Waves version 3.9 leaks", "summary": ""}, "3.8"))

    def test_headline_version_takes_precedence_over_conflicting_summary(self):
        item = {"title": "Wuthering Waves 3.6 guide", "summary": "Includes a mention of version 3.8"}
        self.assertIsNone(target_version_from_article(item, "3.8"))

    def test_unversioned_article_does_not_receive_a_version_tag(self):
        self.assertIsNone(target_version_from_article({"title": "Upcoming character banner", "summary": ""}, "3.8"))


if __name__ == "__main__":
    unittest.main()
