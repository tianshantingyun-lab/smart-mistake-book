#!/usr/bin/env python3
"""Release-gate check (RELEASE.md gate 3).

Two assertions about the *merged release* manifest, neither of which had any
automation before 2026-09-09:

1. No debug-only activity (declared under app/src/debug) leaks into the release
   manifest. Those activities exist only for format/performance harnesses.
2. Every FileProvider path points at a subdirectory of an app-private root —
   never the whole root (`path="."` / `path="/"`), which would expose all of
   filesDir/cacheDir to any app holding a content:// URI from us.

Run after `:app:assemble<Flavor>Release`; the merged manifest is discovered
under app/build/intermediates. Exits non-zero on any violation.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
FORBIDDEN_PATHS = {".", "/", ""}
PATH_TAGS = ("files-path", "cache-path", "external-path", "external-files-path",
             "external-cache-path", "external-media-path")


def manifest_package(root):
    return root.get("package") or ""


def relative_name(name, package):
    """Both '.Foo' and 'com.app.Foo' reduce to 'Foo' so source and merged
    manifests can be compared without depending on where the package is declared."""
    if not name:
        return name
    if name.startswith("."):
        return name[1:]
    if package and name.startswith(package + "."):
        return name[len(package) + 1:]
    return name


def debug_only_activity_names():
    names = set()
    for path in glob.glob("app/src/debug/**/AndroidManifest.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"::error::cannot parse {path}: {error}")
            continue
        package = manifest_package(root)
        for activity in root.iter("activity"):
            name = activity.get(ANDROID + "name")
            if name:
                names.add(relative_name(name, package))
    return names


def merged_release_manifests():
    patterns = (
        "app/build/intermediates/merged_manifest*/**/AndroidManifest.xml",
        "app/build/intermediates/merged_manifests/**/AndroidManifest.xml",
    )
    found = []
    for pattern in patterns:
        found.extend(glob.glob(pattern, recursive=True))
    return sorted({path for path in found if "release" in path.lower()})


def check_activities(manifest_path, debug_activities, failures):
    root = ET.parse(manifest_path).getroot()
    package = manifest_package(root)
    for activity in root.iter("activity"):
        name = relative_name(activity.get(ANDROID + "name"), package)
        if name in debug_activities:
            failures.append(
                f"{manifest_path}: debug-only activity {name} is declared in a release manifest"
            )


def check_file_provider_paths(failures):
    for path in glob.glob("**/res/xml/*_file_paths.xml", recursive=True):
        if "/build/" in path or path.startswith(".worktrees/"):
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            failures.append(f"{path}: cannot parse ({error})")
            continue
        for tag in PATH_TAGS:
            for node in root.iter(tag):
                value = node.get("path", "").strip()
                if value in FORBIDDEN_PATHS:
                    failures.append(
                        f"{path}: <{tag} path=\"{value}\"> exposes the whole app-private root"
                    )


def main():
    manifests = merged_release_manifests()
    if not manifests:
        print(
            "::error::no merged release manifest found under app/build/intermediates; "
            "run :app:assembleLocalFirstRelease first"
        )
        return 1
    debug_activities = debug_only_activity_names()
    failures = []
    for manifest in manifests:
        check_activities(manifest, debug_activities, failures)
    check_file_provider_paths(failures)
    if failures:
        for failure in failures:
            print(f"::error::{failure}")
        return 1
    print(
        f"release manifest gate OK: {len(manifests)} merged manifest(s), "
        f"{len(debug_activities)} debug-only activity name(s) checked"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
