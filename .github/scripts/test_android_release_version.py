import importlib.util
from pathlib import Path
import unittest


MODULE_PATH = Path(__file__).with_name("android_release_version.py")
SPEC = importlib.util.spec_from_file_location("android_release_version", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
release_version = MODULE.release_version


class AndroidReleaseVersionTest(unittest.TestCase):
    def test_canonical_tag_maps_to_monotonic_android_version(self):
        self.assertEqual(("1.5.15", 1_005_015), release_version("v1.5.15"))
        self.assertEqual(("2100.0.0", 2_100_000_000), release_version("v2100.0.0"))

    def test_rejects_missing_prefix_and_leading_zeroes(self):
        for tag in ("1.5.15", "v01.5.15", "v1.05.15", "v1.5.015"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    release_version(tag)

    def test_rejects_component_and_integer_overflow(self):
        for tag in (
            "v1.1000.0",
            "v1.0.1000",
            "v2100.0.1",
            "v18446744073709551617.5.15",
        ):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    release_version(tag)

    def test_rejects_zero_version_and_noncanonical_shapes(self):
        for tag in ("v0.0.0", "v1.5", "v1.5.15-beta", "v1.5.15.0", "master"):
            with self.subTest(tag=tag):
                with self.assertRaises(ValueError):
                    release_version(tag)


if __name__ == "__main__":
    unittest.main()
