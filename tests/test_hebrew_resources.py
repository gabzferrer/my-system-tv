"""Guard the Hebrew Home layout's required views."""
from pathlib import Path
import unittest
import xml.etree.ElementTree as ET


RES = Path(__file__).resolve().parents[1] / "launcher/app/src/main/res"
ANDROID_ID = "{http://schemas.android.com/apk/res/android}id"


def views(folder):
    return {v.attrib[ANDROID_ID]: v.tag
            for v in ET.parse(RES / folder / "activity_main.xml").iter()
            if ANDROID_ID in v.attrib}


class HebrewResourcesTest(unittest.TestCase):
    def test_hebrew_home_keeps_all_default_view_ids_and_types(self):
        # My System TV has no Hebrew copy of its Home: Hebrew uses the default layout, which has every view.
        if not (RES / "layout-iw" / "activity_main.xml").is_file():
            self.skipTest("no layout-iw: Hebrew falls back to the default layout")
        default, hebrew = views("layout"), views("layout-iw")
        for view_id, tag in default.items():
            with self.subTest(view=view_id):
                self.assertEqual(tag, hebrew.get(view_id))


if __name__ == "__main__":
    unittest.main()
