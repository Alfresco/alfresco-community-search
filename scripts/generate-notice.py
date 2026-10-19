#!/usr/bin/env python3
"""
Regenerates the library listing in NOTICE.txt from the project's own
authoritative third-party dependency report.

Usage (after adding/removing/upgrading a dependency):
    mvn generate-resources -pl alfresco-elasticsearch-batch-indexing -am
    python3 scripts/generate-notice.py

This rewrites only the block between the
'### BEGIN AUTO-GENERATED LIBRARY LIST ###' / '### END AUTO-GENERATED LIBRARY LIST ###' 
markers in NOTICE.txt; the header, the "About this file" text, and the "Notes" footer are left untouched.
"""
import argparse
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_THIRD_PARTY = (
    REPO_ROOT
    / "alfresco-elasticsearch-batch-indexing"
    / "target"
    / "generated-sources"
    / "license"
    / "THIRD-PARTY.txt"
)
DEFAULT_NOTICE = REPO_ROOT / "NOTICE.txt"

BEGIN_MARKER = "### BEGIN AUTO-GENERATED LIBRARY LIST ###"
END_MARKER = "### END AUTO-GENERATED LIBRARY LIST ###"

FRIENDLY_TITLES = {
    "Apache-2.0": "Apache 2.0 License",
    "EPL-2.0": "Eclipse Public License 2.0",
    "EPL-1.0": "Eclipse Public License 1.0",
    "EDL-1.0": "Eclipse Distribution License 1.0",
    "MIT": "MIT License",
    "MIT-0": "MIT No Attribution License",
    "BSD-3-Clause": "BSD 3-Clause License",
    "BSD-2-Clause": "BSD 2-Clause License",
    "LGPL-2.1-only": "GNU Lesser General Public License 2.1",
    "LGPL-3.0-only": "GNU Lesser General Public License 3.0",
}

LINE_PATTERN = re.compile(
    r"^\s*((?:\([^)]+\)\s*)+)(.+?)\s*\(([\w.\-]+:[\w.\-]+):([^\s:)]+)\s*-\s*(\S+)\)\s*$"
)


def primary_license(licenses):
    return licenses[0]


def parse_third_party(path: Path):
    entries = []
    with path.open() as f:
        for line in f:
            m = LINE_PATTERN.match(line.rstrip("\n"))
            if not m:
                continue
            lic_blob, title, ga, version, url = m.groups()
            licenses = re.findall(r"\(([^)]+)\)", lic_blob)
            group_id = ga.split(":")[0]
            entries.append(
                {
                    "title": title.strip(),
                    "url": url,
                    "primary": primary_license(licenses),
                    "group_id": group_id,
                }
            )
    return entries


def disambiguate_titles(entries):
    counts = Counter(e["title"] for e in entries)
    for e in entries:
        if counts[e["title"]] > 1:
            e["title"] = f"{e['title']} ({e['group_id']})"
    return entries


def render_sections(entries):
    by_primary = defaultdict(list)
    for e in entries:
        by_primary[e["primary"]].append(e)

    lines = [BEGIN_MARKER, ""]
    for primary in sorted(by_primary):
        title = FRIENDLY_TITLES.get(primary, primary)
        group = sorted(by_primary[primary], key=lambda x: x["title"].lower())
        lines.append(f"=== {title} ====")
        for e in group:
            lines.append(f"{e['title']}\t{e['url']}")
        lines.append("")
    lines.append(END_MARKER)
    return "\n".join(lines).rstrip("\n") + "\n"


def splice_into_notice(notice_path: Path, new_section: str):
    content = notice_path.read_text()
    if BEGIN_MARKER not in content or END_MARKER not in content:
        print(
            f"error: {notice_path} is missing the "
            f"'{BEGIN_MARKER}' / '{END_MARKER}' markers.\n"
            "Add them once around the existing library list, then re-run this script.",
            file=sys.stderr,
        )
        sys.exit(1)
    before = content[: content.index(BEGIN_MARKER)]
    after = content[content.index(END_MARKER) + len(END_MARKER) :].lstrip("\n")
    notice_path.write_text(before + new_section.rstrip("\n") + "\n\n" + after)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--third-party", type=Path, default=DEFAULT_THIRD_PARTY)
    parser.add_argument("--notice", type=Path, default=DEFAULT_NOTICE)
    args = parser.parse_args()

    if not args.third_party.exists():
        print(
            f"error: {args.third_party} not found.\n"
            "Run this first:\n"
            "    mvn generate-resources -pl alfresco-elasticsearch-batch-indexing -am",
            file=sys.stderr,
        )
        sys.exit(1)

    entries = parse_third_party(args.third_party)
    if not entries:
        print(f"error: parsed zero entries from {args.third_party}", file=sys.stderr)
        sys.exit(1)

    entries = disambiguate_titles(entries)
    section = render_sections(entries)
    splice_into_notice(args.notice, section)
    print(f"Updated {args.notice} with {len(entries)} third-party components.")


if __name__ == "__main__":
    main()
