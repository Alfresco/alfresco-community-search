#!/usr/bin/env python3
"""
Checks ATTRIBUTION-NOTICE.MD against the project's own authoritative
third-party dependency report, and optionally updates it.

Usage (after adding/removing/upgrading a dependency):
    mvn generate-resources -pl alfresco-elasticsearch-batch-indexing -am
    python3 scripts/generate-notice.py           # report differences, exit 1 if any
    python3 scripts/generate-notice.py --write   # also rewrite the bullet lists

Each component is expected under the '### <license>' section of its first
listed license. Sections that are plain '- <name> <url>' bullet lists (e.g.
Apache 2.0) are rewritten by --write. Sections where each component carries its
own copyright text (e.g. MIT, BSD) are only reported, as that text has to be
added by hand. Lines without a URL (such as shaded libraries) are left alone.
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
DEFAULT_NOTICE = REPO_ROOT / "ATTRIBUTION-NOTICE.MD"

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
COMPONENT_PATTERN = re.compile(r"^(- )?(.+?) (https?://\S+)$")


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


def expected_by_section(entries):
    sections = defaultdict(set)
    for e in entries:
        heading = FRIENDLY_TITLES.get(e["primary"], e["primary"])
        sections[heading].add(f"{e['title']} {e['url']}")
    return sections


def parse_notice(lines):
    """Maps each '### ' heading to its component lines, as (index, is_bullet, 'name url')."""
    sections = {}
    current = None
    for i, line in enumerate(lines):
        if line.startswith("### "):
            current = line[4:].strip()
            sections[current] = []
        elif line.startswith("#") or line.startswith("***"):
            current = None
        elif current and not line.startswith(">"):
            m = COMPONENT_PATTERN.match(line)
            if m:
                sections[current].append((i, bool(m.group(1)), f"{m.group(2)} {m.group(3)}"))
    return sections


def is_bullet_list(components):
    """True when the section's components are one contiguous '- ' list."""
    if not components or not all(bullet for _, bullet, _ in components):
        return False
    indexes = [i for i, _, _ in components]
    return indexes == list(range(indexes[0], indexes[0] + len(indexes)))


def main():
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--third-party", type=Path, default=DEFAULT_THIRD_PARTY)
    parser.add_argument("--notice", type=Path, default=DEFAULT_NOTICE)
    parser.add_argument("--write", action="store_true", help="rewrite the bullet-list sections")
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

    expected = expected_by_section(disambiguate_titles(entries))
    lines = args.notice.read_text().split("\n")
    actual = parse_notice(lines)

    manual = []
    rewrites = {}
    for heading in sorted(set(expected) | set(actual)):
        want = expected.get(heading, set())
        components = actual.get(heading)
        if components is None:
            manual.append(f"missing section '### {heading}' with license text for: {sorted(want)}")
            continue
        have = {c for _, _, c in components}
        if want == have:
            continue
        if is_bullet_list(components) and want:
            rewrites[heading] = (components, sorted(want, key=lambda c: c.rsplit(" ", 1)[0].lower()))
            for c in sorted(want - have):
                print(f"[{heading}] add: {c}")
            for c in sorted(have - want):
                print(f"[{heading}] remove: {c}")
        else:
            for c in sorted(want - have):
                manual.append(f"[{heading}] add, with its copyright text: {c}")
            for c in sorted(have - want):
                manual.append(f"[{heading}] remove, with its copyright text: {c}")

    for message in manual:
        print(f"manual: {message}")

    if args.write and rewrites:
        # Replace from the bottom up so earlier indexes stay valid.
        for components, want in sorted(rewrites.values(), key=lambda r: -r[0][0][0]):
            first, last = components[0][0], components[-1][0]
            lines[first : last + 1] = [f"- {c}" for c in want]
        args.notice.write_text("\n".join(lines))
        print(f"Updated {len(rewrites)} section(s) in {args.notice}.")

    if manual or (rewrites and not args.write):
        sys.exit(1)
    if not rewrites:
        print(f"{args.notice} matches {len(entries)} third-party components.")


if __name__ == "__main__":
    main()
