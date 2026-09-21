#!/usr/bin/env python3
"""Derive a canonical Android release version from a Git tag."""

from __future__ import annotations

import re
import sys

MAX_ANDROID_VERSION_CODE = 2_100_000_000
TAG_PATTERN = re.compile(
    r"^v(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]{0,2})\.(0|[1-9][0-9]{0,2})$"
)


def release_version(tag: str) -> tuple[str, int]:
    match = TAG_PATTERN.fullmatch(tag)
    if match is None:
        raise ValueError("Release tags must use canonical vMAJOR.MINOR.PATCH")

    major, minor, patch = (int(component, 10) for component in match.groups())
    version_code = major * 1_000_000 + minor * 1_000 + patch
    if not 1 <= version_code <= MAX_ANDROID_VERSION_CODE:
        raise ValueError("Derived Android versionCode is out of range")

    return f"{major}.{minor}.{patch}", version_code


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print("usage: android_release_version.py vMAJOR.MINOR.PATCH", file=sys.stderr)
        return 2
    try:
        version_name, version_code = release_version(argv[1])
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1
    print(f"name={version_name}")
    print(f"code={version_code}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
