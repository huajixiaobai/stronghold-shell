#!/usr/bin/env python3
"""把 check_upstream_env.py --json 的结果渲染成 Issue 正文。

单独成文件而不是写在 workflow 的 heredoc 里：workflow 的 run: | 会保留行首缩进，
heredoc 内容缩进不一致时 Python 直接缩进报错，调试起来很费劲。

用法：python3 scripts/render_issue.py scan.json issue.md
"""
import json
import sys


def render(data):
    lines = [
        '上游 `sganggs/Stronghold-Protocol` 出现了本壳基线里没有的环境变量。',
        '',
        '本壳是 JNA 内嵌 libnode，对上游某些机制敏感——v0.4.0 的 `match-host` 默认走',
        '`child_process.fork()`，在本壳上 fork 出 `/system/bin/app_process64` 即 SIGABRT，',
        '必须强制 `SP_MATCH_HOST=worker`。**所以上游每加一个变量都要逐个确认。**',
        '',
        f'扫描 {data["scanned_files"]} 个 `.js` 文件；基线 {data["baseline_count"]} 个变量，'
        f'实际发现 {data["found_count"]} 个。',
        '',
        '## 基线外的新变量',
        '',
    ]

    for v in data['new_vars']:
        files = v['files']
        shown = '、'.join(f'`{f}`' for f in files[:4])
        more = f'（共 {len(files)} 处）' if len(files) > 4 else ''
        lines.append(f'- **`{v["name"]}`** — {shown}{more}')
        lines.append('  - [ ] 已确认是否影响本壳')

    lines += [
        '',
        '## 处理方式',
        '',
        '1. 读上游代码，确认该变量的默认值在本壳环境下会不会触发崩溃',
        '2. **不会** → 加进 `.github/upstream-env-baseline.yml`（写明用途），关掉本 Issue',
        '3. **会** → 在 `app/src/main/java/com/stronghold/shell/Settings.kt` 的 `toEnv()` 里适配，',
        '   合并后再加进基线',
    ]

    if data.get('removed_vars'):
        lines += [
            '',
            '## 顺带：基线里上游已不再引用的变量',
            '',
        ]
        for v in data['removed_vars']:
            lines.append(f'- `{v}` — 可从基线移除，或保留以防上游重新启用')

    lines += [
        '',
        '---',
        '',
        '<sub>由 `.github/workflows/upstream-watch.yml` 自动生成，'
        '脚本 `scripts/check_upstream_env.py`</sub>',
    ]
    return '\n'.join(lines)


def main():
    if len(sys.argv) < 3:
        print('用法: render_issue.py <scan.json> <out.md>', file=sys.stderr)
        return 1
    with open(sys.argv[1], encoding='utf-8') as f:
        data = json.load(f)
    body = render(data)
    with open(sys.argv[2], 'w', encoding='utf-8') as f:
        f.write(body)
    print(body)
    return 0


if __name__ == '__main__':
    sys.exit(main())