#!/usr/bin/env python3
"""Save the frame Zelda is currently exporting as a PNG, for checking a session without a screenshot tool."""
import struct, sys, zlib
from pathlib import Path

def save(frame_path, out_path):
    data = Path(frame_path).read_bytes()
    _, magic, width, height, _ = struct.unpack_from('<5I', data, 0)
    if magic not in (0x46524D31, 0x46524D32) or not width or not height:
        raise SystemExit('No frame has been exported yet.')
    stride = width * 4
    rows = (data[64 + y * stride:64 + (y + 1) * stride] for y in range(height - 1, -1, -1))
    raw = b''.join(b'\x00' + row for row in rows)
    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
    Path(out_path).write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
                               + chunk(b'IDAT', zlib.compress(raw, 1)) + chunk(b'IEND', b''))
    print(out_path, width, height)

if __name__ == '__main__':
    save(sys.argv[1], sys.argv[2])
