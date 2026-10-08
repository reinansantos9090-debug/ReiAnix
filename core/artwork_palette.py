"""Contextual artwork palette extraction for local, cached presentation only."""
from __future__ import annotations

from colorsys import hls_to_rgb, rgb_to_hls
from pathlib import Path

from PIL import Image, ImageOps


def _clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, float(value)))


def _srgb_to_linear(value: int) -> float:
    channel = value / 255.0
    return channel / 12.92 if channel <= 0.04045 else ((channel + 0.055) / 1.055) ** 2.4


def _relative_luminance(color: tuple[int, int, int]) -> float:
    r, g, b = (_srgb_to_linear(channel) for channel in color)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def contrast_ratio(first: tuple[int, int, int], second: tuple[int, int, int]) -> float:
    light = max(_relative_luminance(first), _relative_luminance(second))
    dark = min(_relative_luminance(first), _relative_luminance(second))
    return (light + 0.05) / (dark + 0.05)


def _hex(color: tuple[int, int, int]) -> str:
    return "#{:02X}{:02X}{:02X}".format(*[max(0, min(255, int(value))) for value in color])


def _rgb(color) -> tuple[int, int, int]:
    return tuple(max(0, min(255, int(value))) for value in color[:3])


def _candidate_colors(image: Image.Image, mode: str) -> list[tuple[tuple[int, int, int], int]]:
    image = ImageOps.exif_transpose(image)
    if image.mode in {"RGBA", "LA"} or "transparency" in image.info:
        rgba = image.convert("RGBA")
        base = (247, 247, 250, 255) if mode == "light" else (24, 24, 30, 255)
        background = Image.new("RGBA", rgba.size, base)
        image = Image.alpha_composite(background, rgba).convert("RGB")
    else:
        image = image.convert("RGB")
    image.thumbnail((72, 72), Image.Resampling.LANCZOS)
    if not image.width or not image.height:
        return []
    quantized = image.quantize(colors=12, method=Image.Quantize.MEDIANCUT)
    colors = quantized.getcolors(maxcolors=12) or []
    palette = quantized.getpalette()
    result = []
    for count, index in colors:
        base = index * 3
        if base + 2 >= len(palette):
            continue
        result.append((
            (int(palette[base]), int(palette[base + 1]), int(palette[base + 2])),
            int(count),
        ))
    return result


def _accessible_accent(
    rgb: tuple[int, int, int],
    *,
    mode: str,
) -> tuple[int, int, int]:
    hue, lightness, saturation = rgb_to_hls(*(channel / 255.0 for channel in rgb))
    target_lightness = 0.48 if mode == "dark" else 0.43
    saturation = _clamp(saturation, 0.0, 0.68)
    if saturation < 0.10:
        saturation = 0.0
    background = (22, 21, 31) if mode == "dark" else (247, 247, 250)
    candidates = []
    for offset in range(-18, 19, 3):
        light = _clamp(target_lightness + offset / 100.0, 0.22, 0.78)
        color = tuple(
            round(component * 255.0)
            for component in hls_to_rgb(hue, light, saturation)
        )
        white_ratio = contrast_ratio(color, (255, 255, 255))
        black_ratio = contrast_ratio(color, (0, 0, 0))
        best_text_ratio = max(white_ratio, black_ratio)
        background_ratio = contrast_ratio(color, background)
        candidates.append((best_text_ratio, background_ratio, -abs(light - target_lightness), color))
    # Prefer a candidate that is accessible as text/background and still has
    # useful contrast against the screen without changing global surfaces.
    candidates.sort(reverse=True)
    for text_ratio, background_ratio, _, color in candidates:
        if text_ratio >= 4.5 and background_ratio >= 2.2:
            return color
    return candidates[0][3]


def extract_palette(path: str | Path, mode: str = "dark") -> dict | None:
    """Extract one restrained contextual accent palette from a local artwork file."""
    path = Path(path)
    if not path.is_file() or path.stat().st_size <= 0:
        return None
    mode = "light" if str(mode).casefold() == "light" else "dark"
    try:
        with Image.open(path) as image:
            colors = _candidate_colors(image, mode)
    except Exception:
        return None
    if not colors:
        return None

    scored = []
    for color, count in colors:
        h, l, s = rgb_to_hls(*(channel / 255.0 for channel in color))
        # Avoid near-white/near-black and use saturation as a modest tie breaker,
        # not as a reason to turn the whole UI into a neon surface.
        if l < 0.08 or l > 0.94:
            continue
        score = count * (0.60 + min(0.90, s))
        scored.append((score, count, color))
    if not scored:
        scored = [(float(count), count, color) for color, count in colors]
    scored.sort(reverse=True)
    accent = _accessible_accent(scored[0][2], mode=mode)

    surface = (22, 21, 31) if mode == "dark" else (247, 247, 250)
    soft = tuple(
        round(accent[index] * 0.24 + surface[index] * 0.76)
        for index in range(3)
    )
    on_accent = (
        (255, 255, 255)
        if contrast_ratio(accent, (255, 255, 255)) >= contrast_ratio(accent, (0, 0, 0))
        else (0, 0, 0)
    )
    return {
        "accent": _hex(accent),
        "accent_soft": _hex(soft),
        "on_accent": _hex(on_accent),
        "mode": mode,
    }
