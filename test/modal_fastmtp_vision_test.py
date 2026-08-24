"""Regression tests for the Modal FastMTP vision path."""

from pathlib import Path
import ast
import unittest


SERVER = Path(__file__).parents[1] / "tools" / "modal-fastmtp" / "qwen38_fastmtp_server.py"


class ModalFastMtpVisionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = SERVER.read_text(encoding="utf-8")
        cls.tree = ast.parse(cls.source)

    def function_source(self, name: str) -> str:
        function = next(
            node
            for node in self.tree.body
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) and node.name == name
        )
        return ast.get_source_segment(self.source, function) or ""

    def test_projector_is_pinned_and_downloaded(self) -> None:
        self.assertIn("VISION_FILE", self.source)
        self.assertIn(
            'VISION_SHA256 = "5681b690bcb8eb10cd28d62d078cb4e01521a3ea4880a3fc7d54de72de2dd142"',
            self.source,
        )
        self.assertIn("VISION_FILE: VISION_SHA256", self.function_source("download_models"))

    def test_llama_server_receives_the_projector(self) -> None:
        serve = self.function_source("serve")
        self.assertIn('"--mmproj", str(MODEL_DIR / VISION_FILE)', serve)


if __name__ == "__main__":
    unittest.main()
