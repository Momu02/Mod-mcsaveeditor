#!/usr/bin/env python3
"""Generate deterministic sample Anvil world data for every stress scenario.

Each scenario is a directory with region/ (+ entities/ / poi/ where relevant)
that the CI stress workflow overlays onto a freshly generated world before
booting the Forge server. Run with --check to validate everything round-trips.

Scenarios:
  empty          no region files at all (boot-generated spawn only)
  normal         3x3 current-version chunks, mixed InhabitedTime
  old-version    pre-1.20.1 DataVersion chunks + one 1.13-1.17 style chunk
  corrupt        valid chunks + zlib garbage + truncated region (far from spawn)
  huge           8x8 regions, sparse chunks (scan throughput / cancel)
  player-base    high InhabitedTime, chest + spawner block entities
  entities-poi   populated entities/ and poi/ regions
  edge-selection checkerboard pattern for boundary/blend testing
"""

import argparse
import os
import random
import shutil
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import anvil
from anvil import B, Cmp, D, F, I, IArr, L, LArr, Lst, S, Str

CURRENT_VERSION = 3465  # 1.20.1
OLD_VERSION = 2860      # 1.18.2 era
MIN_Y = -64
SECTION_Y = MIN_Y // 16  # -4

STONE = "minecraft:stone"
GRASS = "minecraft:grass_block"
DIRT = "minecraft:dirt"
CHEST = "minecraft:chest"
SPAWNER = "minecraft:spawner"
PLAINS = "minecraft:plains"


def pack_indices(indices, count):
    """Pack palette indices the way vanilla does since 1.16 (spanning longs)."""
    if count <= 1:
        return []
    bits = max(4, (count - 1).bit_length())
    per_long = 64 // bits
    mask = (1 << bits) - 1
    longs = [0] * ((len(indices) * bits + 63) // 64)
    for i, palette_index in enumerate(indices):
        slot = (i * bits) // 64
        shift = (i * bits) % 64
        longs[slot] |= (palette_index & mask) << shift
        if shift + bits > 64:  # spans into the next long
            longs[slot + 1] |= (palette_index & mask) >> (64 - shift)
    return longs


def terrain_section(top_block=GRASS, seed=0):
    """One Y=-4 section: stone below, top_block on a bumpy surface."""
    rng = random.Random(seed)
    palette = [STONE, top_block]
    indices = []
    for y in range(16):
        for z in range(16):
            for x in range(16):
                h = 4 + ((x * 7 + z * 13 + seed) % 4)
                if y < h - 1:
                    indices.append(0)
                elif y == h - 1:
                    indices.append(1)
                else:
                    indices.append(0)  # above surface: keep stone (solid, simple)
    data = pack_indices(indices, len(palette))
    section = {
        "Y": B(SECTION_Y),
        "block_states": Cmp({
            "palette": Lst(anvil.TAG_COMPOUND, [
                {"Name": Str(STONE)},
                {"Name": Str(top_block)},
            ]),
        }),
        "biomes": Cmp({
            "palette": Lst(anvil.TAG_COMPOUND, [{"Name": Str(PLAINS)}]),
        }),
    }
    if data:
        section["block_states"][1]["data"] = LArr(data)
    return section


def make_chunk(x, z, data_version=CURRENT_VERSION, inhabited=0,
               sections=None, block_entities=None):
    if sections is None:
        sections = [terrain_section(seed=x * 131 + z)]
    return anvil.dumps_root({
        "DataVersion": I(data_version),
        "xPos": I(x),
        "zPos": I(z),
        "yPos": I(MIN_Y),
        "Status": Str("minecraft:full"),
        "InhabitedTime": L(inhabited),
        "LastUpdate": L(0),
        "sections": Lst(anvil.TAG_COMPOUND, sections),
        "block_entities": Lst(anvil.TAG_COMPOUND, block_entities or []),
        "block_ticks": Lst(anvil.TAG_COMPOUND, []),
        "fluid_ticks": Lst(anvil.TAG_COMPOUND, []),
    })


def make_old_style_chunk(x, z):
    """1.13-1.17 layout: payload under Level with Sections/Palette/BlockStates."""
    section = {
        "Y": B(SECTION_Y),
        "Palette": Lst(anvil.TAG_COMPOUND, [
            {"Name": Str(STONE)},
            {"Name": Str(DIRT)},
        ]),
        "BlockStates": LArr(pack_indices([0] * 4096, 2)),
    }
    return anvil.dumps_root({
        "DataVersion": I(OLD_VERSION),
        "Level": Cmp({
            "xPos": I(x),
            "zPos": I(z),
            "InhabitedTime": L(1200),
            "Sections": Lst(anvil.TAG_COMPOUND, [section]),
        }),
    })


def chest_entity(x, y, z):
    return {
        "id": Str(CHEST),
        "x": I(x),
        "y": I(y),
        "z": I(z),
    }


def spawner_entity(x, y, z):
    return {
        "id": Str(SPAWNER),
        "x": I(x),
        "y": I(y),
        "z": I(z),
    }


def make_entities_entry(x, z, mobs):
    return anvil.dumps_root({
        "DataVersion": I(CURRENT_VERSION),
        "Position": IArr([x, z]),
        "Entities": Lst(anvil.TAG_COMPOUND, mobs),
    })


def mob(entity_id, x, y, z):
    return {
        "id": Str(entity_id),
        "Pos": Lst(anvil.TAG_DOUBLE, [float(x), float(y), float(z)]),
        "Rotation": Lst(anvil.TAG_FLOAT, [0.0, 0.0]),
        "Health": F(20.0),
        "DeathTime": S(0),
    }


def make_poi_entry():
    return anvil.dumps_root({
        "DataVersion": I(CURRENT_VERSION),
        "Sections": Lst(anvil.TAG_COMPOUND, []),
    })


def write_region_file(base, sub, rx, rz, chunks):
    path = os.path.join(base, sub, "r.%d.%d.mca" % (rx, rz))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    anvil.write_region(path, chunks)
    return path


# --- scenario builders (each returns a manifest dict) ------------------------

def build_empty(root):
    os.makedirs(os.path.join(root, "region"), exist_ok=True)
    return {"regions": 0, "chunks": 0}


def build_normal(root):
    chunks = {}
    inhabited = [0, 6000, 60000, 1200, 0, 24000, 3600, 0, 90000]
    for i, dx in enumerate(range(-1, 2)):
        for j, dz in enumerate(range(-1, 2)):
            chunks[(dx, dz)] = make_chunk(dx, dz, inhabited=inhabited[i * 3 + j])
    write_region_file(root, "region", 0, 0, chunks)
    return {"regions": 1, "chunks": 9}


def build_old_version(root):
    chunks = {
        (0, 0): make_chunk(0, 0, data_version=OLD_VERSION, inhabited=5000),
        (1, 0): make_chunk(1, 0, data_version=3105, inhabited=8000),
        (0, 1): make_old_style_chunk(0, 1),
    }
    write_region_file(root, "region", 0, 0, chunks)
    return {"regions": 1, "chunks": 3}


def build_corrupt(root):
    good = {(320, 320): make_chunk(320, 320, inhabited=100)}
    path = write_region_file(root, "region", 10, 10, good)
    # Append a garbage chunk entry: valid table slot, invalid zlib payload.
    with open(path, "r+b") as f:
        data = bytearray(f.read())
        index = anvil.chunk_index(321, 320)
        sector_no = (len(data) // anvil.SECTOR)
        garbage = b"\x00\x00\x00\x10\x02" + bytes(range(16))
        data += garbage + b"\x00" * (anvil.SECTOR - len(garbage))
        data[index * 4:index * 4 + 4] = (sector_no << 8 | 1).to_bytes(4, "big")
        f.seek(0)
        f.write(data)
    # Truncated region file elsewhere.
    with open(os.path.join(root, "region", "r.11.11.mca"), "wb") as f:
        f.write(os.urandom(6000))
    # Stray non-mca file the scanner must ignore.
    with open(os.path.join(root, "region", "notes.txt"), "w") as f:
        f.write("not a region file\n")
    return {"regions": 2, "chunks": 1, "corrupt_entries": 2}


def build_huge(root, regions=8, per_region=32):
    total = 0
    for rx in range(regions):
        for rz in range(regions):
            chunks = {}
            rng = random.Random(rx * 7919 + rz)
            for i in range(per_region):
                # collision-free deterministic spread across the region
                lx = (i * 7 + rx) % 32
                lz = (i * 13 + rz) % 32
                x, z = rx * 32 + lx, rz * 32 + lz
                chunks[(x, z)] = make_chunk(x, z, inhabited=rng.randrange(100000))
                total += 1
            assert len(chunks) == per_region, (rx, rz, len(chunks))
            write_region_file(root, "region", rx, rz, chunks)
    return {"regions": regions * regions, "chunks": total}


def build_player_base(root):
    bes = [chest_entity(8, MIN_Y + 4, 8), spawner_entity(9, MIN_Y + 4, 9)]
    chunks = {
        (0, 0): make_chunk(0, 0, inhabited=2000000, block_entities=bes),
        (1, 0): make_chunk(1, 0, inhabited=1500000),
        (0, 1): make_chunk(0, 1, inhabited=1800000),
        (1, 1): make_chunk(1, 1, inhabited=100000),
    }
    write_region_file(root, "region", 0, 0, chunks)
    return {"regions": 1, "chunks": 4, "block_entities": 2}


def build_entities_poi(root):
    chunks = {(2, 2): make_chunk(2, 2, inhabited=7000)}
    write_region_file(root, "region", 0, 0, chunks)
    mobs = [mob("minecraft:zombie", 40.5, -60.0, 40.5),
            mob("minecraft:pig", 42.5, -60.0, 38.5)]
    write_region_file(root, "entities", 0, 0, {(2, 2): make_entities_entry(2, 2, mobs)})
    write_region_file(root, "poi", 0, 0, {(2, 2): make_poi_entry()})
    return {"regions": 3, "chunks": 3, "entities": 2}


def build_edge_selection(root):
    chunks = {}
    for dx in range(4):
        for dz in range(4):
            if (dx + dz) % 2 == 0:  # checkerboard: boundary-heavy selection
                chunks[(dx, dz)] = make_chunk(dx, dz, inhabited=dx * 1000 + dz)
    write_region_file(root, "region", 0, 0, chunks)
    return {"regions": 1, "chunks": len(chunks)}


BUILDERS = {
    "empty": build_empty,
    "normal": build_normal,
    "old-version": build_old_version,
    "corrupt": build_corrupt,
    "huge": build_huge,
    "player-base": build_player_base,
    "entities-poi": build_entities_poi,
    "edge-selection": build_edge_selection,
}


def region_global_coords(path):
    base = os.path.basename(path)
    rx, rz = int(base.split(".")[1]), int(base.split(".")[2])
    return rx, rz


def check_scenario(root):
    """Read everything back and assert structural validity."""
    errors = []
    region_dir = os.path.join(root, "region")
    chunk_total = 0
    for name in sorted(os.listdir(region_dir)):
        if not name.endswith(".mca"):
            continue
        path = os.path.join(region_dir, name)
        try:
            entries = anvil.read_region(path)
        except ValueError as exc:
            if "corrupt" in root:
                continue  # corrupt scenario is *supposed* to fail reads
            errors.append("%s: %s" % (path, exc))
            continue
        rx, rz = region_global_coords(path)
        for (lx, lz), payload in entries.items():
            try:
                tag = anvil.loads_root(payload)
            except ValueError as exc:
                errors.append("%s[%d,%d]: nbt error %s" % (path, lx, lz, exc))
                continue
            chunk_total += 1
            for key in ("DataVersion", "Status", "sections", "InhabitedTime"):
                if key not in tag and "Level" not in tag:
                    errors.append("%s[%d,%d]: missing %s" % (path, lx, lz, key))
    return chunk_total, errors


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="sample-data")
    parser.add_argument("--only", default="",
                        help="comma-separated subset of scenarios")
    parser.add_argument("--check", action="store_true",
                        help="validate generated files by reading them back")
    args = parser.parse_args()

    wanted = BUILDERS.keys() if not args.only else args.only.split(",")
    all_errors = []
    print("scenario | regions | chunks | notes")
    print("---------|---------|--------|------")
    for name in wanted:
        if name not in BUILDERS:
            print("unknown scenario: %s" % name, file=sys.stderr)
            return 2
        root = os.path.join(args.out, name)
        if os.path.isdir(root):
            shutil.rmtree(root)
        os.makedirs(root)
        manifest = BUILDERS[name](root)
        notes = ",".join("%s=%s" % kv for kv in manifest.items()
                         if kv[0] not in ("regions", "chunks"))
        print("%-8s | %-7s | %-6s | %s" % (
            name, manifest["regions"], manifest["chunks"], notes))
        if args.check:
            # per-scenario file layout assertions
            if name == "corrupt":
                assert os.path.isfile(os.path.join(root, "region", "r.11.11.mca"))
                assert os.path.isfile(os.path.join(root, "region", "notes.txt"))
            if name == "entities-poi":
                for sub in ("region", "entities", "poi"):
                    assert os.path.isfile(
                        os.path.join(root, sub, "r.0.0.mca")), sub
            if name == "old-version":
                payload = anvil.read_region(
                    os.path.join(root, "region", "r.0.0.mca"))[(0, 1)]
                tag = anvil.loads_root(payload)
                assert "Level" in tag, "old-style chunk must use Level wrapper"
            if name == "player-base":
                payload = anvil.read_region(
                    os.path.join(root, "region", "r.0.0.mca"))[(0, 0)]
                tag = anvil.loads_root(payload)
                assert len(tag["block_entities"]) == 2
                assert tag["InhabitedTime"] == 2000000
            if name == "normal":
                total, errors = check_scenario(root)
                assert total == manifest["chunks"], total
                all_errors += errors
            if name == "huge":
                total, errors = check_scenario(root)
                assert total == manifest["chunks"], total
                all_errors += errors
    if all_errors:
        print("VALIDATION ERRORS:", file=sys.stderr)
        for err in all_errors:
            print("  " + err, file=sys.stderr)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
