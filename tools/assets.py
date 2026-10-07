#!/usr/bin/env python3
"""Generate the private item-icon resource pack and the empty Hyrule dimension datapack.

Icons are read from the player's own extracted oot.o2r; nothing here is distributed."""
import json, shutil, struct, zipfile, zlib
from pathlib import Path
from common import ROOT, config, client_jar

GENERATED = ROOT / '.local/generated'
RESOURCE_PACK = GENERATED / 'Hyrule Items'
DATAPACK = GENERATED / 'hyrule-dimension'


def png(width, height, rgba):
    def chunk(kind, body):
        return struct.pack('>I', len(body)) + kind + body + struct.pack('>I', zlib.crc32(kind + body))
    rows = b''.join(b'\0' + rgba[y * width * 4:(y + 1) * width * 4] for y in range(height))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(rows)) + chunk(b'IEND', b''))


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n')


def item_pack(c):
    items = json.loads((ROOT / 'fabric/src/main/resources/hyrule_items.json').read_text())
    with zipfile.ZipFile(Path(c['runtime']) / 'oot.o2r') as archive:
        for item in items:
            data = archive.read('textures/icon_item_static/' + item['icon'])
            fmt, width, height, size = struct.unpack_from('<4I', data, 64)
            if (fmt, width, height, size) != (1, 32, 32, 4096):
                raise RuntimeError('Unexpected icon format for ' + item['icon'])
            key = item['key']
            texture = RESOURCE_PACK / f'assets/hyrule/textures/item/{key}.png'
            texture.parent.mkdir(parents=True, exist_ok=True)
            texture.write_bytes(png(width, height, data[80:80 + size]))
            write_json(RESOURCE_PACK / f'assets/hyrule/models/item/{key}.json',
                       {'parent': 'minecraft:item/handheld', 'textures': {'layer0': f'hyrule:item/{key}'}})
            write_json(RESOURCE_PACK / f'assets/hyrule/items/{key}.json',
                       {'model': {'type': 'minecraft:model', 'model': f'hyrule:item/{key}'}})
    # Without Fabric API a mod's own assets are not a resource pack, so the compositor
    # shaders travel in this pack too.
    shaders = ROOT / 'fabric/src/main/resources/assets/hyrule/shaders'
    shutil.copytree(shaders, RESOURCE_PACK / 'assets/hyrule/shaders', dirs_exist_ok=True)
    write_json(RESOURCE_PACK / 'pack.mcmeta',
               {'pack': {'description': 'Ocarina of Time item icons from your own game', 'min_format': [97, 1], 'max_format': [97, 1]}})


def dimension_pack(c):
    with zipfile.ZipFile(client_jar(c)) as jar:
        dimension_type = json.loads(jar.read('data/minecraft/dimension_type/overworld.json'))
    dimension_type.update(min_y=-64, height=2032, logical_height=2032, ambient_light=0.5)
    write_json(DATAPACK / 'pack.mcmeta',
               {'pack': {'description': 'Empty dimension that holds Minecraft blocks placed in Hyrule', 'min_format': [121, 0], 'max_format': [121, 0]}})
    write_json(DATAPACK / 'data/composite/dimension_type/zelda.json', dimension_type)
    write_json(DATAPACK / 'data/composite/dimension/zelda.json',
               {'type': 'composite:zelda', 'generator': {'type': 'minecraft:flat', 'settings': {
                   'biome': 'minecraft:plains', 'lakes': False, 'features': False,
                   'layers': [{'block': 'minecraft:air', 'height': 1}], 'structure_overrides': []}}})


if __name__ == '__main__':
    c = config()
    item_pack(c)
    dimension_pack(c)
    print('Generated', RESOURCE_PACK, 'and', DATAPACK)
