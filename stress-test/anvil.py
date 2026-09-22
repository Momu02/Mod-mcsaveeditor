"""Minimal pure-python NBT + Anvil region reader/writer (no dependencies).

Used to generate deterministic sample world data for stress testing without
needing a running Minecraft server or third-party NBT libraries.
Supports exactly the tags the generator emits; the reader is used for
post-generation validation (--check).
"""

import struct
import time
import zlib

# Tag ids
TAG_END = 0
TAG_BYTE = 1
TAG_SHORT = 2
TAG_INT = 3
TAG_LONG = 4
TAG_FLOAT = 5
TAG_DOUBLE = 6
TAG_BYTE_ARRAY = 7
TAG_STRING = 8
TAG_LIST = 9
TAG_COMPOUND = 10
TAG_INT_ARRAY = 11
TAG_LONG_ARRAY = 12


class Reader:
    def __init__(self, data):
        self.buf = data
        self.pos = 0

    def read(self, n):
        chunk = self.buf[self.pos:self.pos + n]
        if len(chunk) != n:
            raise ValueError("truncated NBT data")
        self.pos += n
        return chunk

    def u8(self):
        return self.read(1)[0]

    def u16(self):
        return struct.unpack(">H", self.read(2))[0]

    def i16(self):
        return struct.unpack(">h", self.read(2))[0]

    def i32(self):
        return struct.unpack(">i", self.read(4))[0]

    def i64(self):
        return struct.unpack(">q", self.read(8))[0]

    def f32(self):
        return struct.unpack(">f", self.read(4))[0]

    def f64(self):
        return struct.unpack(">d", self.read(8))[0]

    def string(self):
        length = self.u16()
        return self.read(length).decode("utf-8")

    def payload(self, tag_id):
        if tag_id == TAG_BYTE:
            return self.u8()
        if tag_id == TAG_SHORT:
            return self.i16()
        if tag_id == TAG_INT:
            return self.i32()
        if tag_id == TAG_LONG:
            return self.i64()
        if tag_id == TAG_FLOAT:
            return self.f32()
        if tag_id == TAG_DOUBLE:
            return self.f64()
        if tag_id == TAG_BYTE_ARRAY:
            return self.read(self.i32())
        if tag_id == TAG_STRING:
            return self.string()
        if tag_id == TAG_LIST:
            elem = self.u8()
            length = self.i32()
            return [self.payload(elem) for _ in range(length)]
        if tag_id == TAG_COMPOUND:
            out = {}
            while True:
                sub = self.u8()
                if sub == TAG_END:
                    return out
                # NOTE: keep as two statements. In "out[k()] = v()" CPython
                # evaluates the value v() before the subscript k().
                name = self.string()
                out[name] = self.payload(sub)
            raise AssertionError("unreachable")
        if tag_id == TAG_INT_ARRAY:
            length = self.i32()
            return [self.i32() for _ in range(length)]
        if tag_id == TAG_LONG_ARRAY:
            length = self.i32()
            return [self.i64() for _ in range(length)]
        raise ValueError("unknown tag id %d" % tag_id)


class Writer:
    def __init__(self):
        self.buf = bytearray()

    def bytes(self):
        return bytes(self.buf)

    def u8(self, v):
        self.buf.append(v & 0xFF)

    def raw(self, data):
        self.buf += data

    def u16(self, v):
        self.buf += struct.pack(">H", v)

    def i16(self, v):
        self.buf += struct.pack(">h", v)

    def i32(self, v):
        self.buf += struct.pack(">i", v)

    def i64(self, v):
        # NBT longs are signed; python ints may exceed, mask into range-safe form
        self.buf += struct.pack(">q", v if v < 2 ** 63 else v - 2 ** 64)

    def f32(self, v):
        self.buf += struct.pack(">f", v)

    def f64(self, v):
        self.buf += struct.pack(">d", v)

    def string(self, s):
        data = s.encode("utf-8")
        self.u16(len(data))
        self.raw(data)

    def payload(self, tag_id, value):
        if tag_id == TAG_BYTE:
            self.u8(value)
        elif tag_id == TAG_SHORT:
            self.i16(value)
        elif tag_id == TAG_INT:
            self.i32(value)
        elif tag_id == TAG_LONG:
            self.i64(value)
        elif tag_id == TAG_FLOAT:
            self.f32(value)
        elif tag_id == TAG_DOUBLE:
            self.f64(value)
        elif tag_id == TAG_BYTE_ARRAY:
            self.i32(len(value))
            self.raw(value)
        elif tag_id == TAG_STRING:
            self.string(value)
        elif tag_id == TAG_LIST:
            elem, items = value
            self.u8(elem)
            self.i32(len(items))
            for item in items:
                self.payload(elem, item)
        elif tag_id == TAG_COMPOUND:
            for name, (sub_id, sub_val) in value.items():
                self.u8(sub_id)
                self.string(name)
                self.payload(sub_id, sub_val)
            self.u8(TAG_END)
        elif tag_id == TAG_INT_ARRAY:
            self.i32(len(value))
            for item in value:
                self.i32(item)
        elif tag_id == TAG_LONG_ARRAY:
            self.i32(len(value))
            for item in value:
                self.i64(item)
        else:
            raise ValueError("unknown tag id %d" % tag_id)


# Convenience constructors: (tag_id, value) pairs for compound entries.
def B(v):
    return (TAG_BYTE, v)


def S(v):
    return (TAG_SHORT, v)


def I(v):
    return (TAG_INT, v)


def L(v):
    return (TAG_LONG, v)


def F(v):
    return (TAG_FLOAT, v)


def D(v):
    return (TAG_DOUBLE, v)


def Str(v):
    return (TAG_STRING, v)


def Lst(elem, items):
    return (TAG_LIST, (elem, items))


def Cmp(entries):
    return (TAG_COMPOUND, entries)


def IArr(items):
    return (TAG_INT_ARRAY, list(items))


def LArr(items):
    return (TAG_LONG_ARRAY, list(items))


def dumps_root(compound_entries):
    """Serialize a root compound (empty name) to uncompressed NBT bytes."""
    w = Writer()
    w.u8(TAG_COMPOUND)
    w.string("")
    w.payload(TAG_COMPOUND, compound_entries)
    return w.bytes()


def loads_root(data):
    r = Reader(data)
    if r.u8() != TAG_COMPOUND:
        raise ValueError("root tag is not a compound")
    r.string()
    return r.payload(TAG_COMPOUND)


# --- Anvil region files -----------------------------------------------------

SECTOR = 4096


def chunk_index(x, z):
    return (x & 31) + (z & 31) * 32


def write_region(path, chunks):
    """chunks: dict {(x, z): uncompressed NBT bytes}. Compression type 2 (zlib)."""
    offsets = [0] * 1024
    stamps = [int(time.time())] * 1024
    sectors = bytearray()
    sector_no = 2
    for (x, z), nbt in sorted(chunks.items()):
        payload = zlib.compress(nbt)
        body = struct.pack(">i", len(payload) + 1) + b"\x02" + payload
        count = (len(body) + SECTOR - 1) // SECTOR
        offsets[chunk_index(x, z)] = (sector_no << 8) | count
        sectors += body + b"\x00" * (count * SECTOR - len(body))
        sector_no += count
    with open(path, "wb") as f:
        for v in offsets:
            f.write(struct.pack(">i", v))
        for v in stamps:
            f.write(struct.pack(">i", v))
        f.write(sectors)


def read_region(path):
    """Returns dict {(x, z): NBT bytes}. Raises ValueError on corrupt entries."""
    with open(path, "rb") as f:
        data = f.read()
    if len(data) < 2 * SECTOR or len(data) % SECTOR != 0:
        raise ValueError("bad region header size: %s" % path)
    out = {}
    for index in range(1024):
        entry = struct.unpack(">i", data[index * 4:index * 4 + 4])[0]
        if entry == 0:
            continue
        sector_no = entry >> 8
        count = entry & 0xFF
        start = sector_no * SECTOR
        if start + 5 > len(data) or count == 0:
            raise ValueError("bad offset table entry %d in %s" % (index, path))
        length = struct.unpack(">i", data[start:start + 4])[0]
        ctype = data[start + 4]
        if ctype != 2:
            raise ValueError("unsupported compression %d in %s" % (ctype, path))
        if start + 5 + length - 1 > len(data):
            raise ValueError("truncated chunk payload in %s" % path)
        try:
            nbt = zlib.decompress(data[start + 5:start + 5 + length - 1])
        except zlib.error as exc:
            raise ValueError("zlib error in %s: %s" % (path, exc))
        out[(index % 32, index // 32)] = nbt
    return out
