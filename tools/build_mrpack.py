"""Builds a Modrinth modpack (.mrpack) with Storage Dimension and Fabric API.

Fabric API is referenced from Modrinth (downloaded by the launcher or server host),
the Storage Dimension jar is bundled in overrides/mods/ because it is not on Modrinth.

Usage: python3 tools/build_mrpack.py <storage-dimension.jar> <output.mrpack>
"""
import json
import sys
import urllib.parse
import urllib.request
import zipfile
from pathlib import Path

MINECRAFT = "26.1.2"
FABRIC_LOADER = "0.19.5"
FABRIC_API = "0.155.3+26.1.2"


def fabric_api_file():
    query = urllib.parse.urlencode({
        "game_versions": json.dumps([MINECRAFT]),
        "loaders": json.dumps(["fabric"]),
    })
    request = urllib.request.Request(
        "https://api.modrinth.com/v2/project/fabric-api/version?" + query,
        headers={"User-Agent": "Nomisimo/Storage-Dimension build (github.com/Nomisimo/Storage-Dimension)"},
    )

    with urllib.request.urlopen(request) as response:
        versions = json.load(response)

    version = next((v for v in versions if v["version_number"] == FABRIC_API), versions[0])
    file = next(f for f in version["files"] if f["primary"])
    return {
        "path": "mods/" + file["filename"],
        "hashes": {"sha1": file["hashes"]["sha1"], "sha512": file["hashes"]["sha512"]},
        "env": {"client": "required", "server": "required"},
        "downloads": [file["url"]],
        "fileSize": file["size"],
    }


def main():
    jar, out = Path(sys.argv[1]), Path(sys.argv[2])
    version = jar.stem.removeprefix("storage-dimension-")
    index = {
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": version,
        "name": "Storage Dimension",
        "summary": "Storage Dimension mit Fabric API für Minecraft " + MINECRAFT,
        "files": [fabric_api_file()],
        "dependencies": {"minecraft": MINECRAFT, "fabric-loader": FABRIC_LOADER},
    }

    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as pack:
        pack.writestr("modrinth.index.json", json.dumps(index, indent=2, ensure_ascii=False))
        pack.write(jar, "overrides/mods/" + jar.name)

    print(f"{out} geschrieben ({index['files'][0]['path']} + {jar.name})")


if __name__ == "__main__":
    main()
