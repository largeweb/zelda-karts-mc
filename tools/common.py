"""Shared paths and helpers. Machine-specific values live in the private local.json."""
from pathlib import Path
import json, os, shutil, subprocess

ROOT = Path(__file__).resolve().parents[1]
VERSIONS = json.loads((ROOT / 'versions.json').read_text())
WORLD = 'Hyrule_World'
NATIVE = 'soh-composite.elf'
JAR = 'hyrule-composite.jar'


def defaults():
    return dict(prism_dir=str(ROOT / '.local/prism'), instance='hyrule-composite',
                runtime=str(ROOT / '.local/runtime'), source=str(ROOT / '.local/soh'),
                java_home='/usr/lib/jvm/java-25-openjdk', prism_command='prismlauncher',
                shm='/dev/shm/hyrule-composite-' + str(os.getuid()))


def config():
    path = Path(os.environ.get('HYRULE_CONFIG', ROOT / 'local.json'))
    c = defaults()
    if path.is_file():
        c.update(json.loads(path.read_text()))
    for key in ('prism_dir', 'runtime', 'source', 'java_home'):
        c[key] = str(Path(c[key]).expanduser().resolve())
    if not c['shm'].startswith('/dev/shm/') or any(ch.isspace() for ch in c['shm']):
        raise RuntimeError('shm must be an absolute /dev/shm path without spaces')
    return c


def instance(c):
    return Path(c['prism_dir']) / 'instances' / c['instance']


def minecraft(c):
    return instance(c) / 'minecraft'


def client_jar(c):
    version = VERSIONS['minecraft']
    jar = Path(c['prism_dir']) / 'libraries/com/mojang/minecraft' / version / f'minecraft-{version}-client.jar'
    if not jar.is_file():
        raise RuntimeError('Minecraft libraries missing. Launch the pinned instance in Prism with your account first.')
    return jar


def classpath(c):
    client = client_jar(c)
    libraries = sorted((Path(c['prism_dir']) / 'libraries').rglob('*.jar'))
    return os.pathsep.join([str(client)] + [str(p) for p in libraries if p != client])


def java(c, tool='java'):
    return str(Path(c['java_home']) / 'bin' / tool)


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def atomic_copy(src, dst):
    dst = Path(dst)
    dst.parent.mkdir(parents=True, exist_ok=True)
    tmp = dst.with_name(dst.name + '.new')
    shutil.copy2(src, tmp)
    tmp.replace(dst)
