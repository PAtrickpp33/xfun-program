#!/usr/bin/env python3
"""Validate the adapter's actual HAR metadata before publishing."""
import json
from pathlib import Path
import sys
import tarfile

root = Path(__file__).resolve().parents[1]
core = json.loads((root / "dimina/oh-package.json5").read_text())
archive = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "map_amap/build/default/outputs/default/map_amap.har"
with tarfile.open(archive) as har:
    metadata = json.load(har.extractfile("package/oh-package.json5"))
    assert metadata["name"] == "@xfun/map-amap"
    assert metadata["version"] == core["version"], "adapter and core versions must match"
    author = metadata.get("author")
    assert isinstance(author, dict) and isinstance(author.get("name"), str) and author["name"].strip(), "missing or invalid package author"
    assert author == core["author"], "adapter author must match core package author"
    assert metadata["dependencies"][core["name"]] == core["version"], "HAR must use a versioned core dependency"
    assert not any(value.startswith("file:") for value in metadata["dependencies"].values()), "local dependency in published HAR"
    for filename in ["README.md", "LICENSE", "CHANGELOG.md", metadata.get("types", metadata.get("main", "Index.ets"))]:
        assert har.getmember(f"package/{filename}").size > 0, f"missing {filename}"
    assert not metadata.get("metadata", {}).get("debug", False), "publish a release HAR"
print(f"Validated {metadata['name']}@{metadata['version']}: release HAR with registry dependencies")
