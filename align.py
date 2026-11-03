#!/usr/bin/env python3
"""Pure-python zipalign replacement: aligns stored entries to 4 bytes (resources.arsc is forced to STORED).
Usage: python3 align.py in.apk out.apk
"""
import struct
import sys
import zipfile


def main(src, dst):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w") as zout:
        for zi in zin.infolist():
            data = zin.read(zi.filename)
            ctype = zipfile.ZIP_STORED if zi.filename == "resources.arsc" else zi.compress_type
            ni = zipfile.ZipInfo(zi.filename, zi.date_time)
            ni.compress_type = ctype
            ni.external_attr = zi.external_attr
            ni.create_system = zi.create_system
            ni.extra = b""
            if ctype == zipfile.ZIP_STORED:
                offset = zout.fp.tell() + 30 + len(zi.filename.encode("utf-8"))
                pad = (4 - offset % 4) % 4
                if pad:
                    if pad < 4:
                        pad += 4
                    ni.extra = struct.pack("<HH", 0xD935, pad - 4) + b"\x00" * (pad - 4)
            zout.writestr(ni, data)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: align.py in.apk out.apk")
    main(sys.argv[1], sys.argv[2])
