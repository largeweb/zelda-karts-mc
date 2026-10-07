#!/usr/bin/env python3
"""Build the Fabric mod against the locally installed, pinned Minecraft libraries. No downloads."""
import shutil, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from common import ROOT, VERSIONS, JAR, classpath, config, java, run

c = config()
out = ROOT / 'fabric/build/classes'
if out.exists():
    shutil.rmtree(out)
out.mkdir(parents=True)
sources = sorted((ROOT / 'fabric/src/main/java').rglob('*.java'))
run([java(c, 'javac'), '-proc:none', '--release', str(VERSIONS['java']), '-classpath', classpath(c), '-d', out, *sources])
shutil.copytree(ROOT / 'fabric/src/main/resources', out, dirs_exist_ok=True)
run([java(c, 'jar'), '--create', '--file', ROOT / 'fabric/build' / JAR, '-C', out, '.'])
print('Built fabric/build/' + JAR)
