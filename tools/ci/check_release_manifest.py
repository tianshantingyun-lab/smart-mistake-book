#!/usr/bin/env python3
"""Release-gate check (RELEASE.md gate 3).

Two assertions about the *merged release* manifest, neither of which had any
automation before 2026-08-09:

1. No debug-only component (activity, receiver, service or provider declared
   under app/src/debug) leaks into the release manifest. Those components exist
   only for format/performance/model-seeding harnesses; the model-seeding
   receiver in particular is exported, so it must stay out of release builds.
   Components are matched by simple name (last dotted segment) as well as by
   full name: a merged manifest's `package` is the *applicationId*
   (`…localfirst` / `…offline`) while component names are written from the
   *namespace* (`com.tingyun.smartmistakebook.Foo`), so a full-name-only
   comparison silently matches nothing for app-owned components.
2. Every FileProvider path points at a subdirectory of an app-private root —
   never the whole root (`path="."` / `path="/"`), which would expose all of
   filesDir/cacheDir to any app holding a content:// URI from us.
3. The mirror image of (1): every component *declared* under app/src/debug also
   reaches a merged debug manifest. This catches declarations lost on the way
   into the package (a source set that stopped applying, an upstream
   `tools:node="remove"`, a typo in `android:name`), and it fails loudly when no
   debug manifest was built at all rather than passing silently.
   Limit worth knowing: this check keys off declarations, so a class that exists
   under app/src/debug/kotlin with no declaration anywhere is *not* detected
   here — nothing in this gate can see a component that was never mentioned.

Run after `:app:assemble<Flavor>Release` and `:app:assemble<Flavor>Debug`; the
merged manifests are discovered under app/build/intermediates. Exits non-zero on
any violation.
"""

import glob
import os
import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"
TOOLS = "{http://schemas.android.com/tools}"
FORBIDDEN_PATHS = {".", "/", ""}
PATH_TAGS = ("files-path", "cache-path", "external-path", "external-files-path",
             "external-cache-path", "external-media-path")
COMPONENT_TAGS = ("activity", "receiver", "service", "provider")
# Directories whose merged manifests are checked: release artifacts and the
# `internal` dogfood build (debuggable + debug-signed, so a harness leaking
# into it is the same class of defect).
CHECKED_VARIANT_MARKERS = ("release", "internal")


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


def simple_name(name):
    return name.rsplit(".", 1)[-1] if name else name


def is_removal_override(component):
    """True for a declaration whose only purpose is to remove a merged component."""
    return component.get(TOOLS + "node") in ("remove", "removeAll")


def debug_only_component_names():
    """Names of every component declared under app/src/debug, as written.

    Components marked `tools:node="remove"` are skipped: they are deliberately
    *absent* from the debug variant, so the same library component legitimately
    remaining in a checked manifest is not a leak.
    """
    names = set()
    for path in glob.glob("app/src/debug/**/AndroidManifest.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            print(f"::error::cannot parse {path}: {error}")
            continue
        package = manifest_package(root)
        for tag in COMPONENT_TAGS:
            for component in root.iter(tag):
                if is_removal_override(component):
                    continue
                name = relative_name(component.get(ANDROID + "name"), package)
                if name:
                    names.add(name)
    return names


def debug_only_matcher(declared_names):
    """Matches a declared name by its full form or its simple (last-segment) form.

    Required because the debug manifest names components relative to the
    *namespace* while a merged manifest's `package` attribute is the
    *applicationId*; `relative_name` therefore cannot shorten
    `com.tingyun.smartmistakebook.Foo` against `…localfirst`.
    """
    return set(declared_names) | {simple_name(name) for name in declared_names}


def _merged_manifests(markers, exclude=()):
    patterns = (
        "app/build/intermediates/merged_manifest*/**/AndroidManifest.xml",
        "app/build/intermediates/merged_manifests/**/AndroidManifest.xml",
    )
    found = []
    for pattern in patterns:
        found.extend(glob.glob(pattern, recursive=True))
    checked = [
        path for path in found
        if any(marker in path.lower() for marker in markers)
        and not any(skip in path.lower() for skip in exclude)
    ]
    # normpath: the two patterns can yield the same file with different path
    # separators on Windows, which defeats the string-keyed dedupe below.
    return sorted({os.path.normpath(path) for path in checked})


def merged_release_manifests():
    return _merged_manifests(CHECKED_VARIANT_MARKERS)


def debug_variant_manifests():
    """Debug variants only — an androidTest manifest is not a shipped variant."""
    return _merged_manifests(("debug",), exclude=("androidtest",))


def check_components(manifest_path, debug_components, failures):
    root = ET.parse(manifest_path).getroot()
    package = manifest_package(root)
    for tag in COMPONENT_TAGS:
        for component in root.iter(tag):
            name = relative_name(component.get(ANDROID + "name"), package)
            if name in debug_components or simple_name(name) in debug_components:
                failures.append(
                    f"{manifest_path}: debug-only {tag} {name} is declared in a checked manifest"
                )


def check_debug_components_present(manifests, declared, failures):
    """Every component declared under app/src/debug must reach a debug manifest.

    Fails loudly when no debug manifest exists: a silent skip here is exactly how
    a dead harness survives a green build.
    """
    if not manifests:
        failures.append(
            "no merged debug manifest found under app/build/intermediates; "
            "run :app:assemble<Flavor>Debug first"
        )
        return
    present = set()
    for path in manifests:
        root = ET.parse(path).getroot()
        package = manifest_package(root)
        for tag in COMPONENT_TAGS:
            for component in root.iter(tag):
                name = relative_name(component.get(ANDROID + "name"), package)
                if name:
                    present.add(name)
                    present.add(simple_name(name))
    for name in sorted(declared):
        if name not in present and simple_name(name) not in present:
            failures.append(
                f"debug-only component {name} is declared under app/src/debug but "
                f"appears in no merged debug manifest"
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
    declared = debug_only_component_names()
    matcher = debug_only_matcher(declared)
    failures = []
    for manifest in manifests:
        check_components(manifest, matcher, failures)
    debug_manifests = debug_variant_manifests()
    check_debug_components_present(debug_manifests, declared, failures)
    check_file_provider_paths(failures)
    if failures:
        for failure in failures:
            print(f"::error::{failure}")
        return 1
    print(
        f"manifest gate OK: {len(manifests)} checked + {len(debug_manifests)} debug "
        f"merged manifest(s), {len(declared)} debug-only component(s) both absent from "
        f"checked and present in debug"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
