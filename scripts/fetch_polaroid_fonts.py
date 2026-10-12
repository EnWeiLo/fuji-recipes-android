"""Downloads the Google fonts used by the 拍立得 tab and trims them to the characters a card can show.

Run by the build (see .github/workflows/build.yml). A font that cannot be fetched is skipped:
the app then falls back to a system font for it.
"""
import json
import os
import re
import sys
import urllib.request

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "polaroid", "fonts")
TOKEN = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")

# (folder in google/fonts, output name, file name pattern, weight to freeze for variable fonts)
FONTS = [
    ("caveat", "caveat.ttf", r"Caveat\[wght\]\.ttf", 500),
    ("sharetechmono", "sharetechmono.ttf", r"ShareTechMono-Regular\.ttf", None),
    ("zenmarugothic", "zenmaru.ttf", r"ZenMaruGothic-Medium\.ttf", None),
    ("kiwimaru", "kiwimaru.ttf", r"KiwiMaru-Regular\.ttf", None),
    ("yujisyuku", "yuji.ttf", r"YujiSyuku-Regular\.ttf", None),
]


def api(url):
    headers = {"Accept": "application/vnd.github+json"}
    if TOKEN:
        headers["Authorization"] = "Bearer " + TOKEN
    return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60))


def unicodes():
    text = open(os.path.join(HERE, "polaroid_unicodes.txt"), encoding="utf-8").read()
    return [int(x[2:], 16) for x in text.split(",") if x.startswith("U+")]


def main():
    os.makedirs(OUT, exist_ok=True)
    cps = unicodes()
    for folder, out, pattern, weight in FONTS:
        try:
            files = api("https://api.github.com/repos/google/fonts/contents/ofl/" + folder)
            entry = next(f for f in files if re.fullmatch(pattern, f["name"]))
            tmp = os.path.join(OUT, out + ".download")
            with open(tmp, "wb") as fh:
                fh.write(urllib.request.urlopen(entry["download_url"], timeout=120).read())
            font = TTFont(tmp)
            if weight is not None and "fvar" in font:
                font = instancer.instantiateVariableFont(font, {"wght": weight})
            opts = subset.Options()
            opts.layout_features = ["kern", "vert", "locl"]
            opts.hinting = False
            sub = subset.Subsetter(opts)
            sub.populate(unicodes=cps)
            sub.subset(font)
            font.save(os.path.join(OUT, out))
            os.remove(tmp)
            print("ok", out, os.path.getsize(os.path.join(OUT, out)))
        except Exception as error:  # keep going: the app has a fallback
            print("skipped", out, error, file=sys.stderr)


if __name__ == "__main__":
    main()
