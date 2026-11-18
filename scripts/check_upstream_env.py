#!/usr/bin/env python3
"""扫描上游 server/ 与 shared/ 下所有 .js 的 process.env 引用，与基线比对。

用法：python3 scripts/check_upstream_env.py [--baseline PATH] [--json]

退出码：0 = 无新增；10 = 发现基线外的新变量；1 = 出错。

设计说明：这里刻意用纯标准库 + GitHub raw 直连，不依赖 PyYAML
（Actions runner 上未必装），基线文件用极简 YAML 子集解析：只取
"- name: XXX" 这一行。基线的注释和 note 字段给人看，脚本不解析。
"""
import json
import re
import sys
import urllib.request
from concurrent.futures import ThreadPoolExecutor

UPSTREAM = 'sganggs/Stronghold-Protocol'
BRANCH = 'master'
BASELINE = '.github/upstream-env-baseline.yml'

# 只扫这两个目录：游戏前端（public/）和 CI 脚本不注入这些变量
SCAN_PREFIXES = ('server/', 'shared/')
# 测试目录噪音大，跳过
SKIP_PARTS = ('/test/', '/tests/', '.test.js', '.spec.js')

ENV_RE = re.compile(
    r'process\.env(?:\.([A-Z][A-Z0-9_]*)'
    r'|\[\s*[\'"]([A-Z][A-Z0-9_]*)[\'"]\s*\])'
)


def gh_json(url):
    req = urllib.request.Request(url)
    req.add_header('User-Agent', 'stronghold-shell-env-watch')
    req.add_header('Accept', 'application/vnd.github+json')
    with urllib.request.urlopen(req, timeout=45) as resp:
        return json.loads(resp.read().decode())


def gh_raw(path):
    url = f'https://raw.githubusercontent.com/{UPSTREAM}/{BRANCH}/{path}'
    req = urllib.request.Request(url)
    req.add_header('User-Agent', 'stronghold-shell-env-watch')
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode('utf-8', 'replace')
    except Exception:
        return ''


def load_baseline(path):
    names = set()
    with open(path, encoding='utf-8') as f:
        for line in f:
            stripped = line.strip()
            if stripped.startswith('#') or not stripped:
                continue
            m = re.match(r'-\s*name:\s*([A-Za-z_][A-Za-z0-9_]*)', stripped)
            if m:
                names.add(m.group(1))
    return names


def main():
    baseline_path = BASELINE
    as_json = '--json' in sys.argv
    if '--baseline' in sys.argv:
        baseline_path = sys.argv[sys.argv.index('--baseline') + 1]

    known = load_baseline(baseline_path)
    if not known:
        print('ERROR: baseline 为空或解析失败', file=sys.stderr)
        return 1
    if not as_json:
        print(f'baseline: {len(known)} 个已知变量')

    tree = gh_json(
        f'https://api.github.com/repos/{UPSTREAM}/git/trees/{BRANCH}?recursive=1'
    )
    paths = []
    for t in tree.get('tree', []):
        p = t.get('path', '')
        if t.get('type') != 'blob' or not p.endswith('.js'):
            continue
        if not p.startswith(SCAN_PREFIXES):
            continue
        if any(s in p for s in SKIP_PARTS):
            continue
        paths.append(p)

    if not as_json:
        print(f'扫描 {len(paths)} 个 .js 文件')

    found = {}
    with ThreadPoolExecutor(max_workers=12) as ex:
        for p, txt in zip(paths, ex.map(gh_raw, paths)):
            if not txt:
                continue
            for a, b in ENV_RE.findall(txt):
                found.setdefault(a or b, set()).add(p)

    unknown = sorted(set(found) - known)
    removed = sorted(known - set(found))

    if as_json:
        print(json.dumps({
            'baseline_count': len(known),
            'scanned_files': len(paths),
            'found_count': len(found),
            'new_vars': [
                {'name': v, 'files': sorted(found[v])} for v in unknown
            ],
            'removed_vars': removed,
        }, ensure_ascii=False, indent=2))
    else:
        print(f'实际发现 {len(found)} 个变量')
        if unknown:
            print(f'\n!!! 基线外的新变量 {len(unknown)} 个 !!!')
            for v in unknown:
                print(f'  {v}  <- {", ".join(sorted(found[v])[:3])}')
        else:
            print('\n没有新变量')
        if removed:
            print(f'\n基线里已消失的变量 {len(removed)} 个（上游删了，可从基线移除）：')
            for v in removed:
                print(f'  {v}')

    return 10 if unknown else 0


if __name__ == '__main__':
    sys.exit(main())