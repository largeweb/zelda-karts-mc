#!/usr/bin/env python3
"""First Windows milestone: pinned native source and standalone engine build.

No ROM, launcher profile or account access. Run with Python 3 from this checkout.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
VERSIONS = json.loads((ROOT / 'versions.json').read_text())
SOURCE = ROOT / '.local/soh'
BUILD = SOURCE / 'build-windows'
RUNTIME = ROOT / '.local/runtime'


def run(*args, **kwargs):
    subprocess.run([str(arg) for arg in args], check=True, **kwargs)


def revision(directory, expected):
    actual = subprocess.check_output(['git', '-C', str(directory), 'rev-parse', 'HEAD'], text=True).strip()
    if actual != expected:
        raise RuntimeError(f'Upstream revision does not match versions.json: {directory}')


def apply_patch(directory, patch):
    # Never overwrite local upstream edits or apply the same patch twice.
    if subprocess.run(['git', '-C', str(directory), 'apply', '--reverse', '--check', str(patch)],
                      capture_output=True).returncode == 0:
        return
    run('git', '-C', directory, 'apply', '--check', patch)
    run('git', '-C', directory, 'apply', patch)


def fetch():
    if SOURCE.exists():
        raise RuntimeError('Source directory already exists; prepare verifies its pinned revisions.')
    run('git', 'clone', '--no-checkout', VERSIONS['soh_repository'], SOURCE)
    run('git', '-C', SOURCE, 'config', 'core.autocrlf', 'false')
    run('git', '-C', SOURCE, 'checkout', '--detach', VERSIONS['soh_commit'])
    # Submodule clones otherwise inherit the machine's autocrlf setting and the
    # Linux patches cannot match their CRLF working trees.
    run('git', '-c', 'core.autocrlf=false', '-C', SOURCE, 'submodule', 'update', '--init', '--recursive')


def prepare():
    revision(SOURCE, VERSIONS['soh_commit'])
    lus = SOURCE / 'libultraship'
    revision(lus, VERSIONS['libultraship_commit'])
    apply_patch(SOURCE, ROOT / 'patches/soh.patch')
    # Windows patch overlaps shared hunks. Undo it before checking the base patch,
    # then reapply, so repeated preparation remains safe and idempotent.
    windows_patch = ROOT / 'patches/windows-libultraship.patch'
    if subprocess.run(['git', '-C', str(lus), 'apply', '--reverse', '--check', str(windows_patch)],
                      capture_output=True).returncode == 0:
        run('git', '-C', lus, 'apply', '--reverse', windows_patch)
    apply_patch(lus, ROOT / 'patches/libultraship.patch')
    apply_patch(lus, windows_patch)
    hook = SOURCE / 'soh/soh/Enhancements/Composite'
    hook.mkdir(parents=True, exist_ok=True)
    for path in (ROOT / 'ship').iterdir():
        if path.is_file():
            shutil.copy2(path, hook / path.name)
    print('Pinned source prepared with shared patches, Windows patch and bridge sources.')


def configure_window():
    path = RUNTIME / 'shipofharkinian.json'
    data = json.loads(path.read_text()) if path.exists() else {}
    window = data.setdefault('Window', {})
    window.setdefault('Fullscreen', {})['Enabled'] = False
    window.update(Width=1280, Height=720)
    # Verified in the pinned libultraship/include/fast/Fast3dWindow.h.
    window['Backend'] = {'Id': 2, 'Name': 'OpenGL'}
    cvars = data.setdefault('CVars', {})
    cvars['gVsyncEnabled'] = 0
    RUNTIME.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + '\n')


def visual_studio():
    vswhere = Path(os.environ.get('ProgramFiles(x86)', 'C:/Program Files (x86)')) / 'Microsoft Visual Studio/Installer/vswhere.exe'
    if vswhere.is_file():
        return subprocess.check_output([str(vswhere), '-latest', '-version', '[17.0,18.0)', '-products', '*',
                                        '-requires', 'Microsoft.VisualStudio.Component.VC.Tools.x86.x64',
                                        '-property', 'installationPath'], text=True).strip()
    return ''


def cmake_path(requested='cmake'):
    if requested != 'cmake' or shutil.which(requested):
        return requested
    vs = visual_studio()
    candidates = []
    if vs:
        candidates.append(Path(vs) / 'Common7/IDE/CommonExtensions/Microsoft/CMake/CMake/bin/cmake.exe')
    candidates.append(ROOT / '.local/toolchains/cmake-python/cmake/data/bin/cmake.exe')
    return next((str(path) for path in candidates if path.is_file()), requested)


def doctor(cmake):
    cmake = cmake_path(cmake)
    ok = True
    checks = [('Windows x64', sys.platform == 'win32' and os.environ.get('PROCESSOR_ARCHITECTURE') == 'AMD64'),
              ('Git', shutil.which('git')), ('CMake >= 3.26 (version checked by configure)', shutil.which(cmake))]
    vs = visual_studio()
    checks += [('Visual Studio 2022 C++ tools + Windows SDK (SDK checked by configure)', bool(vs)),
               ('VS LLVM Clang tools (ClangCL toolset)', bool(vs) and
                (Path(vs) / 'VC/Tools/Llvm/x64/bin/clang-cl.exe').is_file())]
    for label, present in checks:
        print(('OK   ' if present else 'MISS ') + label)
        ok = ok and bool(present)
    return ok


def build(cmake, jobs):
    cmake = cmake_path(cmake)
    if not doctor(cmake):
        raise RuntimeError('Install the missing native build prerequisites listed above; see docs/WINDOWS.md.')
    prepare()
    # ClangCL supports the protected Protocol.h atomic builtins. Stock MSVC does not.
    run(cmake, '-S', SOURCE, '-B', BUILD, '-G', 'Visual Studio 17 2022', '-T', 'ClangCL', '-A', 'x64',
        '-DCMAKE_BUILD_TYPE=Release')
    run(cmake, '--build', BUILD, '--config', 'Release', '--target', 'GenerateSohOtr')
    run(cmake, '--build', BUILD, '--config', 'Release', '--parallel', jobs)
    # Upstream's install target includes required game-independent files and PDBs.
    run(cmake, '--install', BUILD, '--config', 'Release', '--component', 'ship', '--prefix', RUNTIME)
    shutil.copy2(RUNTIME / 'soh.exe', RUNTIME / 'soh-composite.exe')
    shutil.copytree(BUILD / 'soh/Release/assets', RUNTIME / 'assets', dirs_exist_ok=True)
    configure_window()
    print('Built native engine only. No ROM extracted, Fabric mod built or game verified.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['fetch-source', 'prepare', 'doctor', 'build', 'configure-window'])
    parser.add_argument('--cmake', default='cmake', help='CMake executable path')
    parser.add_argument('--jobs', type=int, default=4)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error('--jobs must be positive')
    try:
        if args.command == 'fetch-source': fetch()
        elif args.command == 'prepare': prepare()
        elif args.command == 'configure-window': configure_window()
        elif args.command == 'doctor': return 0 if doctor(args.cmake) else 1
        else: build(args.cmake, args.jobs)
    except (RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print('ERROR:', error, file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
