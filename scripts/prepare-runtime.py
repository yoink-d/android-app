#!/usr/bin/env python3
"""Package installed launchers separately from downloadable media libraries."""

import argparse
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import stat
import zipfile


ABIS = ("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
REPOSITORY = "yoink-d/android-app"


def safe_path(value):
    path = PurePosixPath(value)
    if not value or "\\" in value or "\0" in value or path.is_absolute() or ".." in path.parts:
        raise ValueError(f"Unsafe runtime path: {value!r}")
    return path


def package_links(payload):
    links = {}
    with zipfile.ZipFile(io.BytesIO(payload)) as archive:
        names = set()
        for entry in archive.infolist():
            safe_path(entry.filename)
            if entry.filename in names:
                raise ValueError(f"Duplicate runtime entry: {entry.filename}")
            names.add(entry.filename)
            if stat.S_ISLNK(entry.external_attr >> 16):
                target = archive.read(entry).decode("utf-8")
                safe_path(target)
                links[entry.filename] = target
    return links


def prepare(library, ffmpeg, specification, release_tag, output):
    if not release_tag or any(char not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for char in release_tag):
        raise ValueError("Use a GitHub release tag containing letters, digits, dots, underscores, or hyphens.")
    if specification["version"] not in library.name or specification["version"] not in ffmpeg.name:
        raise ValueError("The native AAR versions must match media-runtime.json.")
    generated = output / "generated"
    releases = output / "releases"
    for directory in (generated, releases):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir(parents=True)
    manifest = {"schema": 1, "version": specification["version"], "extractor": specification["extractor"], "architectures": {}}
    for abi in ABIS:
        packages = {}
        launchers = generated / "jniLibs" / abi
        launchers.mkdir(parents=True)
        for aar, package, binaries in (
            (library, "python", ("libpython.so", "libqjs.so")),
            (ffmpeg, "ffmpeg", ("libffmpeg.so", "libffprobe.so")),
        ):
            with zipfile.ZipFile(aar) as archive:
                for name in binaries:
                    (launchers / name).write_bytes(archive.read(f"jni/{abi}/{name}"))
                payload = archive.read(f"jni/{abi}/lib{package}.zip.so")
            filename = f"media-tools-{specification['version']}-{abi}-{package}.zip"
            (releases / filename).write_bytes(payload)
            packages[package] = {
                "url": f"https://github.com/{REPOSITORY}/releases/download/{release_tag}/{filename}",
                "bytes": len(payload),
                "sha256": hashlib.sha256(payload).hexdigest(),
                "links": package_links(payload),
            }
        manifest["architectures"][abi] = packages
    encoded = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    assets = generated / "assets"
    assets.mkdir()
    (assets / "runtime-manifest.json").write_text(encoded)
    (releases / "runtime-manifest.json").write_text(encoded)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--library-aar", type=Path, required=True)
    parser.add_argument("--ffmpeg-aar", type=Path, required=True)
    parser.add_argument("--specification", type=Path, required=True)
    parser.add_argument("--release-tag", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.library_aar, args.ffmpeg_aar, json.loads(args.specification.read_text()), args.release_tag, args.output)


if __name__ == "__main__":
    main()
