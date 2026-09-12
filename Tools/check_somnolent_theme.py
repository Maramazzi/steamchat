"""Run with python Tools/check_somnolent_theme.py (standard library only)."""
from pathlib import Path
import re

root = Path(__file__).resolve().parents[1]
data = (root / "TMessagesProj/src/main/assets/somnolent_github.attheme").read_bytes()
header, wallpaper = data.split(b"WPS\n", 1)
assert b"\r" not in header and not header.startswith(b"\xef\xbb\xbf"), "Theme must use LF without BOM"
assert wallpaper.startswith(b"\xff\xd8") and wallpaper.rstrip().endswith(b"WPE"), "Missing embedded JPEG"
entries = [line.split("=", 1) for line in header.decode().splitlines()]
colors = {key: int(value) for key, value in entries}
assert len(colors) == len(entries), "Duplicate theme keys"
assert all(-(2**31) <= value < 2**31 for value in colors.values()), "Invalid Android color"
for key in ("windowBackgroundGray", "windowBackgroundWhite", "actionBarDefault", "chat_inBubble", "chat_outBubble"):
    rgb = [(colors[key] >> shift) & 255 for shift in (16, 8, 0)]
    assert len(set(rgb)) == 1, f"{key} must be neutral graphite, without a color cast"
palette = (root / "TMessagesProj/src/main/kotlin/org/steamchat/ui/SteamPalette.kt").read_text(encoding="utf-8")
assert set(re.findall(r"Theme\.key_(\w+)", palette)) <= colors.keys(), "Palette relies on missing theme keys"


def luminance(value):
    rgb = [(value >> shift & 255) / 255 for shift in (16, 8, 0)]
    return sum(weight * (c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4)
               for c, weight in zip(rgb, (0.2126, 0.7152, 0.0722)))


pairs = [
    ("windowBackgroundWhiteBlackText", "windowBackgroundWhite"),
    ("windowBackgroundWhiteGrayText", "windowBackgroundWhite"),
    ("windowBackgroundWhiteBlueText", "windowBackgroundWhite"),
    ("dialogTextGray", "dialogBackground"),
    ("actionBarDefaultSubtitle", "actionBarDefault"),
    ("chat_messageTextIn", "chat_inBubble"),
    ("chat_messageTextOut", "chat_outBubble"),
    ("chat_inTimeText", "chat_inBubble"),
    ("chat_outTimeText", "chat_outBubble"),
    ("chat_messagePanelHint", "chat_inBubble"),
    ("chat_messageTextOut", "chats_actionBackground"),
]
for foreground, background in pairs:
    assert all(colors[key] & 0xff000000 == 0xff000000 for key in (foreground, background))
    low, high = sorted(luminance(colors[key]) for key in (foreground, background))
    ratio = (high + 0.05) / (low + 0.05)
    assert ratio >= 4.5, f"{foreground} on {background}: {ratio:.2f}:1"
    print(f"{foreground}: {ratio:.2f}:1")
print(f"OK: {len(colors)} entries, embedded wallpaper, {len(pairs)} text contrast pairs")
