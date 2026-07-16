import importlib.util
from importlib.machinery import SourceFileLoader
import pathlib
import unittest

from PIL import Image, ImageDraw


RUNNER = pathlib.Path(__file__).parents[1] / "scripts" / "hunyuan3d-run"
SPEC = importlib.util.spec_from_loader("hunyuan3d_run", SourceFileLoader("hunyuan3d_run", str(RUNNER)))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def subject(box):
    image = Image.new("RGBA", (400, 800), (0, 0, 0, 0))
    ImageDraw.Draw(image).rectangle(box, fill=(255, 255, 255, 255))
    return image


class CharacterInputQualityTest(unittest.TestCase):
    def test_accepts_isolated_full_body_with_margin(self):
        MODULE.validate_character_foreground(subject((120, 30, 280, 760)))

    def test_rejects_subject_touching_bottom_edge(self):
        with self.assertRaisesRegex(ValueError, "subject-touches-edge"):
            MODULE.validate_character_foreground(subject((120, 30, 280, 799)))

    def test_rejects_empty_alpha(self):
        with self.assertRaisesRegex(ValueError, "no-subject"):
            MODULE.validate_character_foreground(Image.new("RGBA", (400, 800), (0, 0, 0, 0)))

    def test_rejects_wide_or_partial_subject(self):
        with self.assertRaisesRegex(ValueError, "not-full-body-portrait"):
            MODULE.validate_character_foreground(subject((30, 250, 370, 550)))


if __name__ == "__main__":
    unittest.main()
