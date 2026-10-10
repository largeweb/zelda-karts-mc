#!/usr/bin/env python3
"""Build and test the actual native mapping helper on Windows, without ROMs.

Usage: python tests/windows/test_mapping.py --compiler PATH/clang++.exe
Compiler: LLVM-MinGW (standalone), or clang-cl in an x64 VS developer shell.
"""
import argparse
import mmap
import os
from pathlib import Path
import queue
import struct
import subprocess
import sys
import tempfile
import threading

ROOT = Path(__file__).resolve().parents[2]


def ready(process):
    lines = queue.Queue()
    threading.Thread(target=lambda: lines.put(process.stdout.readline()), daemon=True).start()
    assert lines.get(timeout=15).strip() == 'READY'


def case(executable, path, capacity, java_command=None):
    env = dict(os.environ, COMPOSITE_TEST_PATH=str(path))
    process = subprocess.Popen([str(executable), 'hold', str(capacity)], env=env,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        ready(process)
        assert path.stat().st_size == capacity
        # Independent file handle and ordinary mmap: equivalent file sharing to Java.
        with path.open('r+b') as file, mmap.mmap(file.fileno(), capacity) as peer:
            assert struct.unpack_from('<I', peer, 192)[0] == 2
            assert struct.unpack_from('<I', peer, 200)[0] == 42
            assert subprocess.run([str(executable), 'try', str(capacity)], env=env).returncode == 73
            if java_command:
                subprocess.run(java_command, env=env, check=True, timeout=20)
                assert struct.unpack_from('<I', peer, 200)[0] == 99
            else:
                struct.pack_into('<I', peer, 192, 3)
                struct.pack_into('<I', peer, 200, 99)
                struct.pack_into('<I', peer, 192, 4)
            # Owner exits while the consumer still has the transport mapped.
            out, err = process.communicate('\n', timeout=15)
            assert process.returncode == 0, err
            assert out.strip() == 'PASS'
            assert subprocess.run([str(executable), 'try', str(capacity)], env=env).returncode == 0
        # No leaked owner handle after normal process exit.
        path.unlink()
        Path(str(path) + '.engine-lock').unlink()
    finally:
        if process.poll() is None:
            process.kill()
        process.communicate()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--compiler', required=True)
    parser.add_argument('--java-home', type=Path, help='Optional JDK 25: also test the real Shared.java')
    args = parser.parse_args()
    if sys.platform != 'win32':
        parser.error('This checks Win32 calls and must run on Windows.')
    out = ROOT / 'tests/build/windows'
    out.mkdir(parents=True, exist_ok=True)
    exe = out / 'mapping.exe'
    if Path(args.compiler).stem == 'clang-cl':
        command = [args.compiler, '/std:c++20', '/EHsc', str(ROOT / 'tests/windows/mapping.cpp'),
                   '/Fe:' + str(exe), '/Fo:' + str(out / 'mapping.obj')]
    else:
        command = [args.compiler, '-std=c++20', '-Wall', '-Wextra', '-Werror', '-static',
                   str(ROOT / 'tests/windows/mapping.cpp'), '-o', str(exe)]
    subprocess.run(command, check=True)
    java_command = None
    if args.java_home:
        subprocess.run([str(args.java_home / 'bin/javac.exe'), '--release', '25', '-d', str(out),
                        str(ROOT / 'fabric/src/main/java/local/composite/Shared.java'),
                        str(ROOT / 'tests/windows/MappingPeer.java')], check=True)
        java_command = [str(args.java_home / 'bin/java.exe'), '-cp', str(out), 'MappingPeer']
    with tempfile.TemporaryDirectory(prefix='hyrule mapping ') as folder:
        directory = Path(folder)
        case(exe, directory / 'control.bin', 8192)
        case(exe, directory / 'space and \u6d77\u62c9\u9c81.bin', 8192)
        case(exe, directory / 'frame.rgba', 64 + 2560 * 1440 * 8)
        resized = directory / 'resize.bin'
        resized.write_bytes(b'old')
        case(exe, resized, 8192)
        # Failed opens must release the companion owner handle for retry.
        missing = directory / 'missing' / 'transport'
        env = dict(os.environ, COMPOSITE_TEST_PATH=str(missing))
        assert subprocess.run([str(exe), 'try', '8192'], env=env).returncode == 73
        missing.parent.mkdir()
        case(exe, missing, 8192)
        if java_command:
            case(exe, directory / 'Java space and \u6d77\u62c9\u9c81.bin', 8192, java_command)
    print('PASS: native/Python mapping, bidirectional protocol bytes, exclusive owner, owner restart, Unicode/spaces, frame size, resize, failed-open retry.')
    if java_command:
        print('PASS: JDK 25 FileChannel.map and Shared.java seqlock exchange with native bridge helper.')


if __name__ == '__main__':
    main()
