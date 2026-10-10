import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parent.parent
sdk = Path(os.environ['ANDROID_HOME'])
jar = sorted((sdk / 'platforms').glob('*/android.jar'))[-1]
out = root / 'out/reflection-regression'
out.mkdir(parents=True, exist_ok=True)
source = root / 'udonge/java/com/topjohnwu/reisenless/hideapps/PackageManagerProxy.java'
test = root / 'udonge/java/tests/ReflectionCacheTest.java'
subprocess.run(['javac', '-classpath', str(jar), '-d', str(out), str(source), str(test)], check=True)
subprocess.run(['java', '-cp', str(out) + os.pathsep + str(jar),
                'com.topjohnwu.reisenless.hideapps.ReflectionCacheTest'], check=True)
