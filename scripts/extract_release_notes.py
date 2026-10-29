#!/usr/bin/env python3
"""Print the RELEASE_NOTES.md section matching a release tag.

The release pipeline uses the section as the GitHub release body and the
in-app update prompt, so notes stay tied to what actually shipped.
"""
import io
import sys


def extract(path: str, version: str) -> str:
    try:
        with io.open(path, encoding="utf-8") as handle:
            text = handle.read()
    except OSError:
        return ""
    marker = "## v" + version
    if marker not in text:
        return ""
    section = text.split(marker, 1)[1].split("\n## ", 1)[0].strip()
    return section


def main() -> int:
    if len(sys.argv) != 3:
        print("usage: extract_release_notes.py VERSION OUT", file=sys.stderr)
        return 2
    version, out = sys.argv[1], sys.argv[2]
    notes = extract("RELEASE_NOTES.md", version)
    if not notes:
        return 0
    with io.open(out, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(notes + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
