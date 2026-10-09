#!/usr/bin/env python3
"""A Minecraft server for the composite: Paper, common plugins, and the Hyrule plugin.

  server.py setup --accept-eula   fetch Paper and the plugins, build the Hyrule plugin, write settings
  server.py start | stop | status
  server.py console <command>     e.g. console op SomePlayer
  server.py log                   the last lines of the server log
  server.py bundle                a self-contained server folder as an archive, for another machine

The server holds blocks, players and inventories. It runs no game engine and needs no
ROM: each player's own machine runs Zelda. Everything lives in .local/server."""
import argparse, hashlib, json, os, shutil, subprocess, sys, tarfile, time, urllib.parse, urllib.request
from pathlib import Path
from common import ROOT, VERSIONS, config, java

SERVER = ROOT / '.local/server'
AGENT = 'hyrule-composite-server-setup'
MEMORY = '4G'


def fetch_json(url):
    request = urllib.request.Request(url, headers={'User-Agent': AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response)


def download(url, target, digest=None, algorithm='sha256'):
    """Fetch to a temporary name and move into place only if the checksum matches."""
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.part')
    request = urllib.request.Request(url, headers={'User-Agent': AGENT})
    hasher = hashlib.new(algorithm)
    with urllib.request.urlopen(request, timeout=300) as response, temporary.open('wb') as out:
        for chunk in iter(lambda: response.read(1 << 16), b''):
            hasher.update(chunk)
            out.write(chunk)
    if digest and hasher.hexdigest() != digest:
        temporary.unlink()
        raise RuntimeError(f'{target.name}: checksum mismatch')
    temporary.replace(target)


def paper():
    version = VERSIONS['minecraft']
    build = fetch_json(f'https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/latest')
    file = build['downloads']['server:default']
    jar = SERVER / 'paper.jar'
    if not jar.is_file() or hashlib.sha256(jar.read_bytes()).hexdigest() != file['checksums']['sha256']:
        print(f'Paper {version} build {build["id"]} ({build["channel"].lower()})')
        download(file['url'], jar, file['checksums']['sha256'])


def plugins():
    version = VERSIONS['minecraft']
    folder = SERVER / 'plugins'
    wanted = json.loads((ROOT / 'server/plugins.json').read_text())['plugins']
    lock = {}
    for plugin in wanted:
        slug = plugin['slug']
        releases = fetch_json(f'https://api.modrinth.com/v2/project/{slug}/version')
        usable = [r for r in releases if set(r['loaders']) & {'paper', 'bukkit', 'spigot'}]
        if plugin.get('version'):
            usable = [r for r in usable if r['version_number'] == plugin['version']]
        exact = [r for r in usable if version in r['game_versions']]
        if not usable:
            raise RuntimeError(f'No Paper release of {slug} found')
        release = (exact or usable)[0]
        file = next((f for f in release['files'] if f['primary']), release['files'][0])
        note = '' if exact else f' (lists up to Minecraft {release["game_versions"][-1]}, not {version})'
        print(f'{slug} {release["version_number"]}{note}')
        target = folder / file['filename']
        # One jar per plugin: drop an older download of the same project.
        for old in folder.glob('*.jar'):
            if old.name != file['filename'] and (folder / '.hyrule' / slug).is_file() and (folder / '.hyrule' / slug).read_text() == old.name:
                old.unlink()
        if not target.is_file() or hashlib.sha512(target.read_bytes()).hexdigest() != file['hashes']['sha512']:
            download(file['url'], target, file['hashes']['sha512'], 'sha512')
        (folder / '.hyrule').mkdir(parents=True, exist_ok=True)
        (folder / '.hyrule' / slug).write_text(file['filename'])
        lock[slug] = {'version': release['version_number'], 'file': file['filename'], 'sha512': file['hashes']['sha512']}
    (SERVER / 'plugins.lock.json').write_text(json.dumps(lock, indent=2) + '\n')


def build_plugin(c):
    """Compile the Hyrule plugin against the API jars Paper unpacks beside itself."""
    libraries = sorted((SERVER / 'libraries').rglob('*.jar'))
    if not libraries:
        # Paper unpacks its libraries on first run and then stops at the EULA.
        subprocess.run([java(c), '-jar', 'paper.jar', '--nogui'], cwd=SERVER, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=600)
        libraries = sorted((SERVER / 'libraries').rglob('*.jar'))
    build = ROOT / 'server/plugin/build'
    shutil.rmtree(build, ignore_errors=True)
    classes = build / 'classes'
    classes.mkdir(parents=True)
    sources = sorted((ROOT / 'server/plugin/src').rglob('*.java'))
    subprocess.run([java(c, 'javac'), '--release', str(VERSIONS['java']), '-nowarn', '-cp', os.pathsep.join(map(str, libraries)), '-d', str(classes),
                    *map(str, sources)], check=True)
    for resource in (ROOT / 'server/plugin/resources').iterdir():
        shutil.copy2(resource, classes / resource.name)
    # The item list is the client mod's: one file, so both sides agree.
    shutil.copy2(ROOT / 'fabric/src/main/resources/hyrule_items.json', classes / 'hyrule_items.json')
    jar = SERVER / 'plugins/HyruleServer.jar'
    jar.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([java(c, 'jar'), '--create', '--file', str(jar), '-C', str(classes), '.'], check=True)
    print('Built', jar.relative_to(ROOT))


def settings(c, accept_eula):
    properties = SERVER / 'server.properties'
    wanted = {
        'motd': 'Hyrule Composite - Minecraft x Ocarina of Time',
        'online-mode': 'true',
        # Players stand on ground only their own game engine knows about; to the server they hover.
        'allow-flight': 'true',
        'spawn-protection': '0',
        'difficulty': 'normal',
        'gamemode': 'survival',
        'server-port': str(c.get('server_port', 25565)),
        'view-distance': '10',
        # Invitation only until you decide otherwise: "hyrule server console whitelist add NAME".
        'white-list': 'true',
    }
    lines = properties.read_text().splitlines() if properties.is_file() else []
    seen = set()
    for i, line in enumerate(lines):
        key = line.split('=', 1)[0]
        if key in wanted:
            lines[i] = f'{key}={wanted[key]}'
            seen.add(key)
    lines += [f'{key}={value}' for key, value in wanted.items() if key not in seen]
    properties.write_text('\n'.join(lines) + '\n')
    spigot = SERVER / 'spigot.yml'
    if not spigot.is_file():
        # Zelda moves players between areas a thousand blocks apart, and karts are quick:
        # the server must not pull players back for moving "too fast".
        spigot.write_text('settings:\n  moved-too-quickly-multiplier: 100000.0\n  moved-wrongly-threshold: 100000.0\n')
    # The Hyrule dimension is a datapack in the main world.
    datapack = ROOT / '.local/generated/hyrule-dimension'
    if not datapack.is_dir():
        subprocess.run([sys.executable, str(ROOT / 'tools/assets.py')], check=True)
    shutil.copytree(datapack, SERVER / 'world/datapacks/hyrule-dimension', dirs_exist_ok=True)
    # Operators named in local.json ("ops": [{"name": ..., "uuid": ...}]) are added to the
    # operator list and the whitelist; nobody already there is removed.
    ops = [{'uuid': o['uuid'], 'name': o['name'], 'level': 4, 'bypassesPlayerLimit': True} for o in c.get('ops', [])]
    for name, entries in (('ops.json', ops), ('whitelist.json', [{'uuid': o['uuid'], 'name': o['name']} for o in ops])):
        path = SERVER / name
        current = json.loads(path.read_text() or '[]') if path.is_file() else []
        known = {entry['uuid'] for entry in current}
        added = [entry for entry in entries if entry['uuid'] not in known]
        if added:
            path.write_text(json.dumps(current + added, indent=2) + '\n')
    eula = SERVER / 'eula.txt'
    if accept_eula:
        eula.write_text('# Accepted with: hyrule server setup --accept-eula\n# https://aka.ms/MinecraftEULA\neula=true\n')
    elif 'eula=true' not in (eula.read_text() if eula.is_file() else ''):
        print('The Minecraft EULA (https://aka.ms/MinecraftEULA) is not accepted yet: run setup again with --accept-eula.')


def tune():
    """Settings of other plugins that only exist once they have run: nothing may send players out of Hyrule."""
    essentials = SERVER / 'plugins/Essentials/config.yml'
    if essentials.is_file():
        text = essentials.read_text()
        tuned = text.replace("  spawnpoint: newbies", "  spawnpoint: none").replace("  kit: tools", "  kit: ''")
        if tuned != text:
            essentials.write_text(tuned)
    commands = SERVER / 'commands.yml'
    if commands.is_file() and 'hub' not in commands.read_text():
        # /spawn is the hub.
        commands.write_text(commands.read_text().replace('aliases:', 'aliases:\n  spawn:\n  - hub', 1))


def pid():
    for process in Path('/proc').iterdir():
        if not process.name.isdigit():
            continue
        try:
            if Path(os.readlink(process / 'cwd')) == SERVER and b'paper.jar' in (process / 'cmdline').read_bytes() and b'java' in (process / 'cmdline').read_bytes().split(b'\0')[0]:
                return int(process.name)
        except OSError:
            pass
    return None


def console(command):
    fifo = SERVER / 'console.fifo'
    if pid() is None or not fifo.exists():
        raise SystemExit('The server is not running.')
    descriptor = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'w') as out:
        out.write(command + '\n')


def start(c):
    if pid() is not None:
        print('Already running.')
        return
    if 'eula=true' not in ((SERVER / 'eula.txt').read_text() if (SERVER / 'eula.txt').is_file() else ''):
        raise SystemExit('Run "hyrule server setup --accept-eula" first.')
    tune()
    fifo = SERVER / 'console.fifo'
    if not fifo.exists():
        os.mkfifo(fifo, 0o600)
    # The console reads from a named pipe so commands can be sent later; a holder keeps it open.
    command = f'exec 3<>console.fifo; exec {java(c)} -Xms1G -Xmx{c.get("server_memory", MEMORY)} -jar paper.jar --nogui <&3'
    with (SERVER / 'console.out').open('w') as out:
        subprocess.Popen(['bash', '-c', command], cwd=SERVER, stdout=out, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    log = SERVER / 'logs/latest.log'
    for _ in range(180):
        time.sleep(1)
        text = log.read_text(errors='replace') if log.is_file() else ''
        if 'Done (' in text and pid() is not None:
            print('Server running on port', c.get('server_port', 25565))
            return
        if pid() is None and _ > 5:
            break
    raise SystemExit('The server did not start; see .local/server/logs/latest.log and console.out')


def stop():
    if pid() is None:
        print('Not running.')
        return
    console('stop')
    for _ in range(60):
        if pid() is None:
            print('Stopped.')
            return
        time.sleep(1)
    os.kill(pid(), 15)


def bundle(c):
    """What another machine needs to run this server: Paper, plugins, settings, datapack. No world, no players, no logs."""
    out = ROOT / 'dist'
    out.mkdir(exist_ok=True)
    target = out / 'hyrule-server.tar.gz'
    keep = ['paper.jar', 'plugins', 'spigot.yml', 'commands.yml', 'plugins.lock.json', 'world/datapacks/hyrule-dimension']

    def wanted(info):
        # Of each plugin's own folder only its top-level settings files travel. What a
        # plugin makes while running (player records, databases, caches) stays behind.
        parts = Path(info.name).parts
        if len(parts) > 2 and parts[1] == 'plugins' and not (len(parts) == 3 and (info.isdir() or parts[2].endswith('.jar'))):
            settings_file = len(parts) == 4 and info.isfile() and parts[3].endswith(('.yml', '.json', '.txt')) and 'uuid' not in parts[3] and 'user' not in parts[3]
            if not settings_file or parts[2].startswith('.'):
                return None
        if len(parts) == 3 and parts[1] == 'plugins' and parts[2].startswith('.'):
            return None
        return info

    with tarfile.open(target, 'w:gz') as archive:
        for name in keep:
            if (SERVER / name).exists():
                archive.add(SERVER / name, arcname='hyrule-server/' + name, filter=wanted)
        # Settings without this machine's generated secrets; the new machine makes its own.
        properties = out / 'server.properties'
        properties.write_text(''.join(line for line in (SERVER / 'server.properties').read_text().splitlines(True)
                                      if not line.startswith(('management-server-secret', 'rcon.password'))))
        archive.add(properties, arcname='hyrule-server/server.properties')
        properties.unlink()
        launcher = '#!/bin/sh\n# Read https://aka.ms/MinecraftEULA and put eula=true in eula.txt before the first start.\ncd "$(dirname "$0")"\nexec java -Xms1G -Xmx${MEMORY:-4G} -jar paper.jar --nogui\n'
        path = out / 'start.sh'
        path.write_text(launcher)
        path.chmod(0o755)
        archive.add(path, arcname='hyrule-server/start.sh')
        path.unlink()
    print('Wrote', target.relative_to(ROOT), f'({target.stat().st_size >> 20} MB)')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('setup')
    s.add_argument('--accept-eula', action='store_true', help='you have read and accept https://aka.ms/MinecraftEULA')
    for name in ('start', 'stop', 'status', 'log', 'bundle', 'plugin'):
        sub.add_parser(name)
    k = sub.add_parser('console')
    k.add_argument('command', nargs='+')
    a = parser.parse_args()
    c = config()
    SERVER.mkdir(parents=True, exist_ok=True)
    if a.cmd == 'setup':
        paper()
        plugins()
        build_plugin(c)
        settings(c, a.accept_eula)
    elif a.cmd == 'plugin':
        build_plugin(c)
    elif a.cmd == 'start':
        start(c)
    elif a.cmd == 'stop':
        stop()
    elif a.cmd == 'status':
        print('Running, pid ' + str(pid()) if pid() is not None else 'Stopped')
    elif a.cmd == 'console':
        console(' '.join(a.command))
    elif a.cmd == 'log':
        log = SERVER / 'logs/latest.log'
        print('\n'.join(log.read_text(errors='replace').splitlines()[-40:]) if log.is_file() else 'No log yet.')
    elif a.cmd == 'bundle':
        bundle(c)


if __name__ == '__main__':
    main()
