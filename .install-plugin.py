"""把 build/distributions 下**最新的**那个包装进 PyCharm 的插件目录。

版本号不写死：新增一个版本就多一个 zip，写死的话每次都要回来改这一行
（上一版就写着 0.2.5）。

旧版本 jar 必须删掉：zip 是覆盖式的，`CCoder-0.2.5.jar` 不会因为装了 0.2.6
而消失 —— 两个 jar 同时躺在 lib/ 里，插件会注册两遍。
"""
import glob
import os
import re
import shutil
import tempfile
import zipfile

zip_path = max(
    glob.glob(r'build\distributions\CCoder-*.zip'),
    key=lambda p: [int(n) for n in re.findall(r'\d+', os.path.basename(p))],
)
version = re.search(r'CCoder-([\d.]+)\.zip', zip_path).group(1)
dest_base = os.path.expanduser('~') + r'\AppData\Roaming\JetBrains\PyCharm2026.1\plugins'
backup_dir = os.path.join(tempfile.gettempdir(), 'ccoder-plugin-backup')

print('安装包 :', zip_path, '(版本', version, ')')

os.makedirs(backup_dir, exist_ok=True)
z = zipfile.ZipFile(zip_path)
installed = set()

for name in z.namelist():
    if name.endswith('/'):
        continue
    target = os.path.join(dest_base, name.replace('/', os.sep))
    os.makedirs(os.path.dirname(target), exist_ok=True)
    installed.add(os.path.normcase(target))

    # 备份现有的同名文件
    if os.path.isfile(target):
        bak = os.path.join(backup_dir, os.path.basename(target))
        shutil.copy2(target, bak)

    with open(target + '.new', 'wb') as f:
        f.write(z.read(name))
    os.replace(target + '.new', target)   # 原子替换：失败时原文件不变

# 清掉不属于这个包的旧 jar（否则两个版本同时被加载）
lib_dir = os.path.join(dest_base, 'CCoder', 'lib')
for stale in glob.glob(os.path.join(lib_dir, 'CCoder-*.jar')):
    if os.path.normcase(stale) not in installed:
        os.remove(stale)
        print('已删除旧文件:', stale)

# 回读校验：装上去的那份 jar 里到底有什么
jar_path = os.path.join(lib_dir, 'CCoder-%s.jar' % version)
j = zipfile.ZipFile(jar_path)
xml = j.read('META-INF/plugin.xml').decode('utf-8', 'replace')
html = j.read('webui/index.html').decode('utf-8', 'replace')

print()
print('校验装上去的那份（%s）:' % os.path.basename(jar_path))
print('   plugin.xml 里的版本 :', re.search(r'<version>([^<]+)</version>', xml).group(1))
for marker in ('tool__head', 'tool__status', 'flex-shrink:0', 'live-think__spin',
               'tool__file', 'tool-file', 'run__head', 'run__file'):
    print('  ', marker, '出现次数 :', html.count(marker))

# 前端标记只看得到 web 侧。Swing 侧的改动（状态卡、打开文件）落在 jar 里的类上，
# 所以再点名看几个类 —— "装上去没变化"最常见的成因就是装了个旧 jar
print()
print('   lib 里这几个类在不在 :')
for cls in ('com/ccoder/ui/CardIconView.class',
            'com/ccoder/ui/ActivityKt.class',
            'com/ccoder/ui/OpenFileTargetKt.class'):
    print('  ', cls.split('/')[-1], ':', cls in j.namelist())
