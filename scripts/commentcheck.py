#!/usr/bin/env python3
"""
Code-identical check for a comment rewrite: every changed .kt file, with comments removed,
must match its HEAD version with comments removed. Kotlin-aware: skips strings (plain, raw,
with ${} templates), char literals, and nested /* */ comments.

Usage: python3 scripts/commentcheck.py [BASE]   (BASE defaults to HEAD)
Exit 0 when every file matches.
"""
import subprocess
import sys


def strip(src: str) -> str:
    out = []
    i, n = 0, len(src)

    def string_end(i: int, raw: bool) -> int:
        # i points just past the opening quote(s); returns index just past the closing quote(s)
        while i < n:
            if raw and src.startswith('"""', i):
                j = i + 3
                while j < n and src[j] == '"':
                    j += 1
                return j
            if not raw and src[i] == '\\':
                i += 2
                continue
            if not raw and src[i] == '"':
                return i + 1
            if src.startswith('${', i):
                i = code_end(i + 2, '}')
                continue
            i += 1
        return i

    def code_end(i: int, closer: str) -> int:
        depth = 0
        while i < n:
            c = src[i]
            if src.startswith('"""', i):
                i = string_end(i + 3, True)
                continue
            if c == '"':
                i = string_end(i + 1, False)
                continue
            if c == '{':
                depth += 1
            elif c == '}':
                if depth == 0:
                    return i + 1
                depth -= 1
            i += 1
        return i

    while i < n:
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith('/*', j):
                    depth += 1
                    j += 2
                elif src.startswith('*/', j):
                    depth -= 1
                    j += 2
                else:
                    j += 1
            i = j
            continue
        if src.startswith('"""', i):
            j = string_end(i + 3, True)
            out.append(src[i:j])
            i = j
            continue
        if src[i] == '"':
            j = string_end(i + 1, False)
            out.append(src[i:j])
            i = j
            continue
        if src[i] == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append(src[i:j + 1])
            i = j + 1
            continue
        out.append(src[i])
        i += 1
    text = ''.join(out)
    lines = [l.rstrip() for l in text.split('\n')]
    return '\n'.join(l for l in lines if l.strip())


def main() -> int:
    base = sys.argv[1] if len(sys.argv) > 1 else 'HEAD'
    changed = subprocess.run(['git', 'diff', '--name-only', base, '--', '*.kt', '*.kts'],
                             capture_output=True, text=True, check=True).stdout.split()
    bad = 0
    for f in changed:
        old = subprocess.run(['git', 'show', f'{base}:{f}'], capture_output=True, text=True).stdout
        new = open(f, encoding='utf-8').read()
        if strip(old) != strip(new):
            bad += 1
            a, b = strip(old).split('\n'), strip(new).split('\n')
            k = next((x for x in range(min(len(a), len(b))) if a[x] != b[x]), min(len(a), len(b)))
            print(f'CODE CHANGED: {f}\n  was: {a[k] if k < len(a) else "<end>"}\n  now: {b[k] if k < len(b) else "<end>"}')
    print(f'{len(changed)} files changed, {bad} with code differences')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
