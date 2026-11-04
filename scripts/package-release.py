#!/usr/bin/env python3
"""Validate architecture APKs and assemble their GitHub release assets."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import urlsplit
import zipfile


ABIS = {"arm64-v8a": (2, 183), "armeabi-v7a": (1, 40), "x86_64": (2, 62), "x86": (1, 3)}
LAUNCHERS = {"libpython.so", "libqjs.so", "libffmpeg.so", "libffprobe.so"}
APPLICATION_ID = "com.maynkudu.yoinkd"


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def validate_apk(path, abi, element, sdk, allow_unsigned, allow_debug):
    with zipfile.ZipFile(path) as archive:
        native = {entry.filename for entry in archive.infolist() if entry.filename.startswith("lib/") and not entry.is_dir()}
        if {name.split("/")[1] for name in native} != {abi} or not {f"lib/{abi}/{name}" for name in LAUNCHERS}.issubset(native):
            raise ValueError(f"{path.name} must contain all four launchers and only the {abi} architecture.")
        for name in native:
            header = archive.read(name)[:24]
            if len(header) < 20 or header[:4] != b"\x7fELF" or header[5] != 1 or (header[4], int.from_bytes(header[18:20], "little")) != ABIS[abi]:
                raise ValueError(f"{path.name} contains a native library for the wrong architecture.")
        embedded = json.loads(archive.read("assets/runtime-manifest.json"))
        if set(embedded["architectures"]) != set(ABIS):
            raise ValueError("The embedded runtime manifest is incomplete.")
        if any("libpython.zip.so" in name or "libffmpeg.zip.so" in name or name.endswith("/yt-dlp") for name in archive.namelist()):
            raise ValueError("A large runtime package is still bundled in the APK.")
    tools = sdk / "build-tools" / "35.0.0"
    badging = subprocess.run([str(tools / "aapt2"), "dump", "badging", str(path)], check=True, capture_output=True, text=True).stdout
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not package or package.groups() != (APPLICATION_ID, str(element["versionCode"]), element["versionName"]):
        raise ValueError("The APK identity does not match Gradle's output metadata.")
    signing = subprocess.run([str(tools / "apksigner"), "verify", "--print-certs", str(path)], capture_output=True, text=True)
    if signing.returncode:
        if not allow_unsigned:
            raise ValueError(f"{path.name} is unsigned. Configure the external release keystore before publishing.")
        certificates = []
    else:
        if "CN=Android Debug" in signing.stdout and not allow_debug:
            raise ValueError("Configure a release signing key, or explicitly allow the existing development key for an alpha.")
        certificates = sorted(re.findall(r"Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)", signing.stdout))
        if not certificates:
            raise ValueError("The APK has no verified signing certificate.")
    return embedded, certificates


def package(args):
    metadata = json.loads((args.apk_dir / "output-metadata.json").read_text())
    elements = metadata["elements"]
    selected = {}
    for element in elements:
        filters = element["filters"]
        if len(filters) != 1 or filters[0]["filterType"] != "ABI" or filters[0]["value"] not in ABIS:
            raise ValueError("Each APK must have exactly one supported architecture filter.")
        abi = filters[0]["value"]
        if abi in selected:
            raise ValueError("There is more than one APK for an architecture.")
        selected[abi] = element
    if set(selected) != set(ABIS):
        raise ValueError("Build all four architecture APKs before packaging a release.")
    versions = {(element["versionCode"], element["versionName"]) for element in elements}
    if len(versions) != 1:
        raise ValueError("The architecture APK versions differ.")
    version_code, version_name = versions.pop()
    if args.tag != f"v{version_name}":
        raise ValueError("The release tag must match the APK version name.")
    if args.allow_development_key_for_alpha and not re.fullmatch(r"\d+\.\d+\.\d+-alpha\.\d+", version_name):
        raise ValueError("Development release signing is limited to explicitly numbered alpha versions.")
    changelog = Path("CHANGELOG.md").read_text()
    heading = f"## {args.tag}"
    sections = changelog.splitlines()
    try:
        start = sections.index(heading) + 1
    except ValueError:
        raise ValueError(f"Add a changelog section for {args.tag} before publishing.") from None
    end = next((index for index in range(start, len(sections)) if sections[index].startswith("## ")), len(sections))
    notes = "\n".join(sections[start:end]).strip()
    if not notes:
        raise ValueError("The release changelog must describe the changes.")
    args.output.mkdir(parents=True, exist_ok=True)
    if any(args.output.iterdir()):
        raise ValueError("Use an empty release output directory.")
    manifest = {"schema": 1, "applicationId": APPLICATION_ID, "versionCode": version_code, "versionName": version_name, "apks": {}}
    runtime = json.loads((args.runtime_dir / "runtime-manifest.json").read_text())
    certificates = None
    sizes = {}
    for abi, element in selected.items():
        source = args.apk_dir / element["outputFile"]
        embedded, signing = validate_apk(source, abi, element, args.sdk, args.allow_unsigned,
            args.allow_debug_key_for_testing or args.allow_development_key_for_alpha)
        if embedded != runtime:
            raise ValueError("The APK runtime metadata does not match the runtime release assets.")
        if certificates is None:
            certificates = signing
        elif signing != certificates:
            raise ValueError("The architecture APKs use different signing keys.")
        filename = f"yoinkd-{version_name}-{abi}.apk"
        shutil.copyfile(source, args.output / filename)
        manifest["apks"][abi] = {"filename": filename, "bytes": source.stat().st_size, "sha256": digest(source)}
        sizes[abi] = {"apkBytes": source.stat().st_size,
            "firstSetupBytes": sum(artifact["bytes"] for artifact in runtime["architectures"][abi].values()) + runtime["extractor"]["bytes"]}
    for packages in runtime["architectures"].values():
        for artifact in packages.values():
            url = urlsplit(artifact["url"])
            filename = Path(url.path).name
            expected = f"https://github.com/yoink-d/android-app/releases/download/{args.tag}/{filename}"
            if artifact["url"] != expected:
                raise ValueError("A runtime asset points to a different release tag.")
            source = args.runtime_dir / filename
            if source.stat().st_size != artifact["bytes"] or digest(source) != artifact["sha256"]:
                raise ValueError("A runtime asset failed checksum verification.")
            shutil.copyfile(source, args.output / filename)
    shutil.copyfile(args.runtime_dir / "runtime-manifest.json", args.output / "runtime-manifest.json")
    (args.output / "yoinkd-update.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    summary = {"tag": args.tag, "signingCertificateSha256": certificates, "architectures": sizes}
    (args.output / "release-summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    (args.output / "release-notes.md").write_text(notes + "\n")
    checksums = [f"{digest(path)}  {path.name}\n" for path in sorted(args.output.iterdir()) if path.is_file()]
    (args.output / "SHA256SUMS").write_text("".join(checksums))
    print(json.dumps(summary, indent=2, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-dir", type=Path, default=Path("app/build/outputs/apk/release"))
    parser.add_argument("--runtime-dir", type=Path, default=Path("app/build/media-runtime/releases"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--sdk", type=Path, default=Path(os.environ.get("ANDROID_HOME", "/opt/android-sdk")))
    parser.add_argument("--allow-unsigned", action="store_true", help="Validate local builds without making a publishable signed release.")
    parser.add_argument("--allow-debug-key-for-testing", action="store_true", help="Only for local update verification, never production releases.")
    parser.add_argument("--allow-development-key-for-alpha", action="store_true", help="Explicitly keep the existing alpha key for update compatibility.")
    package(parser.parse_args())


if __name__ == "__main__":
    main()
