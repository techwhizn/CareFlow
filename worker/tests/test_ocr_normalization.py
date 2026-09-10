from PIL import Image

from careflow.parsing import _ocr


def test_ocr_receives_normalized_pixels_without_original_metadata(monkeypatch):
    import pytesseract

    seen = []

    def recognize(image, *, lang, timeout):
        seen.append((image.mode, image.format, dict(image.info), lang, timeout))
        assert image.getpixel((0, 0)) == (255, 255, 255)
        return "synthetic OCR result"

    monkeypatch.setattr(pytesseract, "image_to_string", recognize)
    with Image.new("L", (10, 10), 255) as image:
        image.format = "JPEG"
        image.info["comment"] = b"untrusted metadata"
        assert _ocr(image) == "synthetic OCR result"
        assert image.mode == "L"
        assert image.info["comment"] == b"untrusted metadata"
    assert seen[0][:4] == ("RGB", "PNG", {}, "chi_sim+eng")
    assert seen[0][4] > 0
