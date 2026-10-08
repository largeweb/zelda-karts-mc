#!/usr/bin/env python3
"""Setup, build and launcher for the Minecraft x Ocarina of Time pair.

Never downloads ROMs or game assets and never handles account credentials."""
import argparse, fcntl, json, os, shutil, signal, subprocess, sys, time
from pathlib import Path
from common import ROOT, VERSIONS, WORLD, NATIVE, JAR, atomic_copy, classpath, client_jar, config, instance, java, minecraft, run
from nbt import write as write_nbt

PATCHED = ['soh/soh/OTRGlobals.cpp', 'soh/src/code/code_800EC960.c', 'soh/src/code/z_actor.c',
           'soh/src/code/z_parameter.c', 'soh/src/code/z_play.c', 'soh/src/overlays/actors/ovl_player_actor/z_player.c']


def fetch(c):
    source = Path(c['source'])
    if source.exists():
        raise RuntimeError('Source directory already exists.')
    run(['git', 'clone', VERSIONS['soh_repository'], source])
    run(['git', '-C', source, 'checkout', '--detach', VERSIONS['soh_commit']])
    run(['git', '-C', source, 'submodule', 'update', '--init', '--recursive'])
    print('Pinned Ship of Harkinian source fetched. No game data fetched.')


def apply_patch(directory, patch):
    reverse = subprocess.run(['git', '-C', str(directory), 'apply', '--reverse', '--check', str(patch)], capture_output=True)
    if reverse.returncode == 0:
        return
    run(['git', '-C', directory, 'apply', '--check', patch])
    run(['git', '-C', directory, 'apply', patch])


def build(c, jobs):
    source = Path(c['source'])
    build_dir = source / 'build-cmake'
    for path, commit in [(source, VERSIONS['soh_commit']), (source / 'libultraship', VERSIONS['libultraship_commit'])]:
        actual = subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
        if actual != commit:
            raise RuntimeError('Upstream revision does not match versions.json: ' + str(path))
    apply_patch(source, ROOT / 'patches/soh.patch')
    apply_patch(source / 'libultraship', ROOT / 'patches/libultraship.patch')
    hook = source / 'soh/soh/Enhancements/Composite'
    hook.mkdir(parents=True, exist_ok=True)
    for p in (ROOT / 'ship').iterdir():
        if p.is_file():
            shutil.copy2(p, hook / p.name)
    run(['cmake', '-S', source, '-B', build_dir, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release'])
    run(['cmake', '--build', build_dir, '--target', 'GenerateSohOtr'])
    run(['cmake', '--build', build_dir, '--parallel', str(jobs)])
    runtime = Path(c['runtime'])
    runtime.mkdir(parents=True, exist_ok=True)
    atomic_copy(build_dir / 'soh' / 'soh.elf', runtime / NATIVE)
    atomic_copy(build_dir / 'soh' / 'soh.o2r', runtime / 'soh.o2r')
    shutil.copytree(build_dir / 'soh' / 'assets', runtime / 'assets', dirs_exist_ok=True)
    run([sys.executable, ROOT / 'fabric/build.py'])


MK64 = {'repository': 'https://github.com/HarbourMasters/SpaghettiKart.git', 'engine': 'mk64-composite.elf'}
MK64_PATCHED = ['src/port/Engine.cpp', 'src/engine/cameras/FreeCamera.cpp', 'src/main.c', 'src/racing/skybox_and_splitscreen.c']


def mk64(c, rom, jobs):
    """Fetch, patch, build and extract Mario Kart 64 (SpaghettiKart) into .local/runtime-mk64."""
    source = ROOT / '.local/mk64'
    if not source.exists():
        run(['git', 'clone', '--recursive', MK64['repository'], source])
        run(['git', '-C', source, 'checkout', '--detach', VERSIONS['mk64_commit']])
        run(['git', '-C', source, 'submodule', 'update', '--init', '--recursive'])
    apply_patch(source, ROOT / 'patches/mk64.patch')
    apply_patch(source / 'libultraship', ROOT / 'patches/libultraship.patch')
    hook = source / 'src/port/composite'
    hook.mkdir(parents=True, exist_ok=True)
    for p in (ROOT / 'ship/Protocol.h', ROOT / 'ship/FrameExport.h', ROOT / 'kart/CompositeBridge.cpp'):
        shutil.copy2(p, hook / p.name)
    build_dir = source / 'build-cmake'
    run(['cmake', '-S', source, '-B', build_dir, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=Release'])
    run(['cmake', '--build', build_dir, '--parallel', str(jobs)])
    if rom:
        shutil.copy2(Path(rom).resolve(), source / 'baserom.us.z64')
    if (source / 'baserom.us.z64').is_file():
        run(['cmake', '--build', build_dir, '--target', 'ExtractAssets'])
    runtime = ROOT / '.local/runtime-mk64'
    runtime.mkdir(parents=True, exist_ok=True)
    atomic_copy(build_dir / 'Spaghettify', runtime / MK64['engine'])
    for name in ('mk64.o2r', 'spaghetti.o2r'):
        if (build_dir / name).is_file():
            atomic_copy(build_dir / name, runtime / name)
    print('Mario Kart 64 ready in', runtime)


def extract(c, rom):
    """Ship of Harkinian extracts a ROM given on its command line, then waits at a prompt; close it there."""
    runtime = Path(c['runtime'])
    archive = runtime / 'oot.o2r'
    process = subprocess.Popen([str(runtime / NATIVE), str(Path(rom).resolve())], cwd=runtime, env=native_env(c, False))
    size = -1
    try:
        for _ in range(120):
            time.sleep(3)
            if process.poll() is not None:
                break
            current = archive.stat().st_size if archive.exists() else 0
            if current > 1_000_000 and current == size:
                break
            size = current
    finally:
        if process.poll() is None:
            process.terminate()
    if not archive.is_file():
        raise RuntimeError('Extraction did not produce oot.o2r. Check that the ROM is a supported dump.')
    print('Extracted', archive)


def jvm_args(c):
    return '-Dcomposite.shm=' + c['shm'] + ' -Dcomposite.passthrough=true'


def setup(c):
    """Create the Prism instance and the Minecraft world that pairs with the Zelda save."""
    folder = instance(c)
    folder.mkdir(parents=True, exist_ok=True)
    if not (folder / 'mmc-pack.json').exists():
        (folder / 'mmc-pack.json').write_text(json.dumps({'formatVersion': 1, 'components': [
            {'uid': 'net.minecraft', 'version': VERSIONS['minecraft'], 'important': True},
            {'uid': 'net.fabricmc.fabric-loader', 'version': VERSIONS['fabric_loader']}]}, indent=2) + '\n')
    if not (folder / 'instance.cfg').exists():
        (folder / 'instance.cfg').write_text(
            '[General]\nInstanceType=OneSix\nname=Hyrule Composite\nOverrideJavaLocation=true\nJavaPath=' + java(c) + '\n'
            'OverrideJavaArgs=true\nJvmArgs=' + json.dumps(jvm_args(c)) + '\n'
            'OverrideMemory=true\nMinMemAlloc=512\nMaxMemAlloc=4096\n')
    mc = minecraft(c)
    (mc / 'mods').mkdir(parents=True, exist_ok=True)
    run([sys.executable, ROOT / 'tools/assets.py'])
    world = mc / 'saves' / WORLD
    if not (world / 'level.dat').exists():
        compound = lambda **kw: (10, kw)
        string, integer, byte = (lambda x: (8, x)), (lambda x: (3, x)), (lambda x: (1, x))
        data_version = 5023
        write_nbt(world / 'level.dat', compound(Data=compound(
            DataVersion=integer(data_version),
            Version=compound(Snapshot=byte(0), Series=string('main'), Id=integer(data_version), Name=string(VERSIONS['minecraft'])),
            LevelName=string('Hyrule'), GameType=integer(1), initialized=byte(1), allowCommands=byte(1), WasModded=byte(1),
            version=integer(19133),
            DataPacks=compound(Enabled=(9, (8, ['vanilla', 'file/hyrule-dimension'])), Disabled=(9, (8, []))))))
        dimensions = {}
        for name, setting in [('overworld', 'overworld'), ('the_nether', 'nether'), ('the_end', 'end')]:
            biome = (compound(type=string('minecraft:the_end')) if name == 'the_end'
                     else compound(type=string('minecraft:multi_noise'), preset=string('minecraft:' + setting)))
            dimensions['minecraft:' + name] = compound(type=string('minecraft:' + name), generator=compound(
                type=string('minecraft:noise'), settings=string('minecraft:' + setting), biome_source=biome))
        write_nbt(world / 'data/minecraft/world_gen_settings.dat', compound(DataVersion=integer(data_version), data=compound(
            seed=(4, 1998), bonus_chest=byte(0), generate_structures=byte(0), dimensions=(10, dimensions))))
    install(c)
    print('Instance and world ready:', folder)


def install(c):
    if any(kind == 'Minecraft' for kind in sessions(c).values()):
        raise RuntimeError('Save and close this Minecraft instance before installing.')
    mc = minecraft(c)
    atomic_copy(ROOT / 'fabric/build' / JAR, mc / 'mods' / JAR)
    generated = ROOT / '.local/generated'
    shutil.copytree(generated / 'Hyrule Items', mc / 'resourcepacks/Hyrule Items', dirs_exist_ok=True)
    # Shaders are source, not generated: always the current ones, without re-running `assets`.
    shutil.copytree(ROOT / 'fabric/src/main/resources/assets/hyrule/shaders', mc / 'resourcepacks/Hyrule Items/assets/hyrule/shaders', dirs_exist_ok=True)
    world = mc / 'saves' / WORLD
    if world.is_dir():
        shutil.copytree(generated / 'hyrule-dimension', world / 'datapacks/hyrule-dimension', dirs_exist_ok=True)
    # What the mod needs to know: where each game's engine and assets are, and an optional
    # helper that parks the engine's window. World types in Create New World follow from this.
    config_dir = mc / 'config'
    shutil.copytree(generated / 'hyrule-dimension', config_dir / 'hyrule/hyrule-dimension', dirs_exist_ok=True)
    settings = {'oot_runtime': c['runtime'], 'mk64_runtime': str(ROOT / '.local/runtime-mk64')}
    if c.get('arms'):
        settings['arms'] = str(c['arms'])
    if c.get('start'):
        settings['start'] = str(c['start'])
    if c.get('dev'):
        settings['debug'] = True  # engines log more
    if shutil.which('hyprctl'):
        settings['arrange'] = [sys.executable, str(ROOT / 'tools/arrange.py'), '--wait']
    (config_dir / 'hyrule.json').write_text(json.dumps(settings, indent=2) + '\n')
    if world.is_dir() and not (world / 'hyrule-world.json').exists():
        (world / 'hyrule-world.json').write_text(json.dumps({'game': 'oot'}))
    options = mc / 'options.txt'
    lines = options.read_text().splitlines() if options.exists() else ['pauseOnLostFocus:false', 'tutorialStep:none', 'onboardAccessibility:false']
    key = 'file/Hyrule Items'
    for i, line in enumerate(lines):
        if line.startswith('resourcePacks:'):
            packs = json.loads(line.partition(':')[2])
            if key not in packs:
                packs.append(key)
            lines[i] = 'resourcePacks:' + json.dumps(packs)
            break
    else:
        lines.append('resourcePacks:' + json.dumps(['vanilla', key]))
    # Minecraft's idle frame limiter would also slow the Zelda picture it displays.
    lines = [line for line in lines if not line.startswith('inactivityFpsLimit:')] + ['inactivityFpsLimit:"minimized"']
    options.write_text('\n'.join(lines) + '\n')
    print('Installed mod, item icons and dimension.')


def native_env(c, bridged=True):
    env = os.environ.copy()
    for key in ('COMPOSITE_SHM', 'COMPOSITE_FRAME', 'COMPOSITE_START', 'COMPOSITE_ARMS'):
        env.pop(key, None)
    env.update(SHIP_HOME=c['runtime'], SDL_VIDEODRIVER='x11')
    if bridged:
        env.update(COMPOSITE_SHM=c['shm'], COMPOSITE_FRAME=c['shm'] + '.rgba')
        if c.get('arms'):
            env['COMPOSITE_ARMS'] = str(c['arms'])
        if c.get('start'):
            env['COMPOSITE_START'] = str(c['start'])
    return env


def sessions(c):
    """Only this project's two processes: matched by binary path, working directory and bridge argument."""
    found = {}
    binaries = {(Path(c['runtime']) / NATIVE).resolve(), (ROOT / '.local/runtime-mk64' / MK64['engine']).resolve()}
    mc = minecraft(c).resolve()
    for proc in Path('/proc').glob('[0-9]*'):
        try:
            args = (proc / 'cmdline').read_bytes().split(b'\0')
            # A rebuilt binary replaces the file a running game was started from.
            exe = Path(str((proc / 'exe').resolve()).removesuffix(' (deleted)'))
            cwd = (proc / 'cwd').resolve()
            if exe in binaries:
                found[int(proc.name)] = 'Zelda'
            elif exe.name == 'java' and ('-Dcomposite.shm=' + c['shm']).encode() in args and cwd == mc:
                found[int(proc.name)] = 'Minecraft'
        except (OSError, ValueError):
            pass
    return found


def alive(pid):
    try:
        return Path('/proc', str(pid), 'stat').read_text().rsplit(')', 1)[1].split()[0] not in ('Z', 'X')
    except OSError:
        return False


def stop(c):
    """Minecraft is asked to save and quit through the mod; Zelda holds no state worth waiting for."""
    targets = sessions(c)
    quit_request = Path(c['shm'] + '.quit')
    if 'Minecraft' in targets.values():
        quit_request.touch()
    for pid, kind in targets.items():
        if kind == 'Zelda':
            os.kill(pid, signal.SIGTERM)
        print('Closing', kind, pid, flush=True)
    started = time.monotonic()
    signalled = False
    while any(alive(pid) for pid in targets):
        elapsed = time.monotonic() - started
        for pid, kind in targets.items():
            if not alive(pid) or sessions(c).get(pid) != kind:
                continue
            # Ship of Harkinian can hang in teardown after releasing its resources.
            if kind == 'Zelda' and elapsed > 8:
                os.kill(pid, signal.SIGKILL)
            # A Minecraft that is not ticking (still loading, or stuck) never sees the request.
            if kind == 'Minecraft' and elapsed > 30 and not signalled:
                os.kill(pid, signal.SIGTERM)
                signalled = True
        if elapsed > 75:
            quit_request.unlink(missing_ok=True)
            raise RuntimeError('Minecraft is still closing; it was not force-killed. Close its window and try again.')
        time.sleep(.25)
    quit_request.unlink(missing_ok=True)
    print('Stopped. Other applications were left open.')


def doctor(c):
    ok = True

    def check(label, condition):
        nonlocal ok
        print(('OK   ' if condition else 'MISS ') + label)
        ok &= bool(condition)
    check('Linux /dev/shm transport', sys.platform == 'linux' and Path('/dev/shm').is_dir())
    for name in ('git', 'cmake', 'ninja', c['prism_command']):
        check(name, shutil.which(name))
    check('configured Java executable', Path(java(c)).is_file())
    try:
        client_jar(c)
        present = True
    except RuntimeError:
        present = False
    check('Minecraft ' + VERSIONS['minecraft'] + ' client jar', present)
    for name in (NATIVE, 'soh.o2r'):
        check('runtime/' + name, (Path(c['runtime']) / name).is_file())
    check('Prism instance', (instance(c) / 'mmc-pack.json').is_file())
    check('Fabric mod installed', (minecraft(c) / 'mods' / JAR).is_file())
    if not (Path(c['runtime']) / 'oot.o2r').is_file():
        print('NOTE Ocarina of Time assets not extracted; only plain Minecraft worlds can be created (./hyrule extract ROM)')
    return ok


def configure_window(c):
    """Zelda renders at a fixed windowed 720p with OpenGL; its own menus stay closed."""
    path = Path(c['runtime']) / 'shipofharkinian.json'
    data = json.loads(path.read_text()) if path.exists() else {}
    window = data.setdefault('Window', {})
    window.setdefault('Fullscreen', {})['Enabled'] = False
    window.update(Width=1280, Height=720)
    window['Backend'] = {'Id': 2, 'Name': 'OpenGL'}
    cvars = data.setdefault('CVars', {})
    cvars.setdefault('gOpenWindows', {}).update(ModalWindow=0, Console=0)
    path.write_text(json.dumps(data, indent=2) + '\n')


def start(c):
    live = sessions(c)
    if live:
        print('Already running:', ', '.join(live.values()), '- use restart for a clean pair.')
        return
    if not doctor(c):
        raise RuntimeError('Finish the missing setup steps above before starting.')
    configure_window(c)
    Path(c['shm'] + '.quit').unlink(missing_ok=True)
    # Optional local test channel (tools/console.py), switched on by "dev": true in local.json.
    marker = Path(c['shm'] + '.dev')
    if c.get('dev'):
        (ROOT / '.local/session').mkdir(parents=True, exist_ok=True)
        marker.write_text(str(ROOT / '.local/session'))
        for stale in ('command.json', 'result.json', 'status.json'):
            (ROOT / '.local/session' / stale).unlink(missing_ok=True)
    else:
        marker.unlink(missing_ok=True)
    logs = ROOT / '.local/logs'
    logs.mkdir(parents=True, exist_ok=True)
    # Minecraft starts the game engine itself when a world made from that game is opened.
    with (logs / 'player.log').open('w') as log:
        player = subprocess.Popen([c['prism_command'], '--dir', c['prism_dir'], '--launch', c['instance']],
                                  stdout=log, stderr=log, start_new_session=True)
    time.sleep(1)
    if player.poll() not in (None, 0):
        raise RuntimeError('Prism exited with an error; see .local/logs/player.log')
    print('Minecraft started. Open or create a world; an Ocarina of Time world starts Zelda with it.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='cmd', required=True)
    for name in ('fetch-source', 'build-fabric', 'assets', 'setup', 'install', 'doctor', 'start', 'stop', 'restart', 'status', 'arrange', 'save-patch', 'audit'):
        sub.add_parser(name)
    b = sub.add_parser('build')
    b.add_argument('--jobs', type=int, default=min(16, os.cpu_count() or 2))
    m = sub.add_parser('mk64')
    m.add_argument('rom', nargs='?', help='path to your own Mario Kart 64 (USA) ROM dump')
    m.add_argument('--jobs', type=int, default=min(16, os.cpu_count() or 2))
    e = sub.add_parser('extract')
    e.add_argument('rom', help='path to your own supported Ocarina of Time ROM dump')
    a = parser.parse_args()
    c = config()
    if a.cmd == 'fetch-source':
        fetch(c)
    elif a.cmd == 'build':
        build(c, max(1, a.jobs))
    elif a.cmd == 'build-fabric':
        run([sys.executable, ROOT / 'fabric/build.py'])
    elif a.cmd == 'mk64':
        mk64(c, a.rom, max(1, a.jobs))
    elif a.cmd == 'extract':
        extract(c, a.rom)
    elif a.cmd == 'assets':
        run([sys.executable, ROOT / 'tools/assets.py'])
    elif a.cmd == 'setup':
        setup(c)
    elif a.cmd == 'install':
        install(c)
    elif a.cmd == 'doctor':
        sys.exit(0 if doctor(c) else 1)
    elif a.cmd == 'status':
        found = sessions(c)
        for pid, kind in found.items():
            print(kind + ':', pid)
        if not found:
            print('Stopped')
    elif a.cmd == 'audit':
        run([sys.executable, ROOT / 'tools/audit.py'])
    elif a.cmd == 'arrange':
        run([sys.executable, ROOT / 'tools/arrange.py'])
    elif a.cmd == 'save-patch':
        # Developer helper: record the current edits in the source checkout as patches/soh.patch.
        diff = subprocess.check_output(['git', '-C', c['source'], 'diff', '--', *PATCHED], text=True)
        (ROOT / 'patches/soh.patch').write_text(diff)
        if (ROOT / '.local/mk64').is_dir():
            diff = subprocess.check_output(['git', '-C', str(ROOT / '.local/mk64'), 'diff', '--', *MK64_PATCHED], text=True)
            (ROOT / 'patches/mk64.patch').write_text(diff)
        print('Wrote patches/soh.patch')
    else:
        state = ROOT / '.local'
        state.mkdir(exist_ok=True)
        with (state / 'launcher.lock').open('w') as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise RuntimeError('Another launcher operation is already running.')
            if a.cmd in ('stop', 'restart'):
                stop(c)
            if a.cmd in ('start', 'restart'):
                start(c)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, subprocess.CalledProcessError, ValueError) as e:
        print('Error:', e, file=sys.stderr)
        sys.exit(1)
