#!/usr/bin/env python3
"""检查待发布工程的常见明文凭据；仅输出位置，不回显命中的内容。"""
import argparse
import re
import subprocess
from pathlib import Path


SENSITIVE = r"(?:password|passwd|pwd|secret|(?:internal|access|refresh)[_.-]?token)"
RULES = (
    ("私钥", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----")),
    ("访问凭据", re.compile(r"\b(?:gh[pousr]_[A-Za-z0-9]{24,}|AKIA[A-Z0-9]{16})\b")),
    ("环境变量含固定凭据默认值", re.compile(
        rf"\$\{{[\w.-]*{SENSITIVE}[\w.-]*:-?([^{{}}\n]+)\}}", re.I)),
    ("源码含固定凭据默认值", re.compile(
        rf"""(?:getenv|getProperty|getOrDefault)\(\s*["'][^"']*{SENSITIVE}[^"']*["']\s*,\s*["']([^"']+)["']""", re.I)),
    ("命令行含口令", re.compile(
        r"""(?:mysql(?:admin)?[^\n]*?["'\s]-p|--(?:spring\.datasource\.)?password=)([^\s"'`,\]\)]+)""", re.I)),
    ("变量含固定凭据", re.compile(
        rf"""^\s*(?:export\s+)?(?:[\w.]*{SENSITIVE}[\w.]*)\s*=\s*["']([^"']+)["']""", re.I)),
)
CONFIG_VALUE = re.compile(rf"^\s*[\w.-]*{SENSITIVE}[\w.-]*\s*[:=]\s*(.+?)\s*$", re.I)
SAFE_VALUE = re.compile(r"""^(?:["']?\$[({]|["']?\$[A-Za-z_]|\[REDACTED(?:\]|$)|["']?\s*["']?\s*$)""")


def findings(path: str, text: str) -> list[tuple[int, str]]:
    result = []
    is_config = Path(path).suffix in {".yaml", ".yml", ".properties"} or Path(path).name.startswith(".env")
    for number, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith("#"):
            continue
        for label, pattern in RULES:
            for match in pattern.finditer(line):
                value = match.group(1) if match.lastindex else match.group(0)
                # Shell/Compose 的 :? 是必填断言，:- 后无内容也是空默认值。
                if label == "环境变量含固定凭据默认值" and (value == "-" or value.startswith("?")):
                    continue
                if not SAFE_VALUE.match(value):
                    result.append((number, label))
        if is_config:
            match = CONFIG_VALUE.match(line)
            if match and not SAFE_VALUE.match(match.group(1)):
                result.append((number, "配置含固定凭据"))
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard", "--", "."],
        cwd=root,
    ).decode().split("\0")
    errors = []
    checked = 0
    for name in sorted(set(paths)):
        if not name:
            continue
        path = root / name
        if not path.is_file():
            continue
        data = path.read_bytes()
        if b"\0" in data:
            continue
        try:
            content = data.decode("utf-8")
        except UnicodeDecodeError:
            continue
        checked += 1
        errors.extend((name, line, label) for line, label in findings(name, content))
    for name, line, label in errors:
        print(f"{name}:{line}: {label}（内容已隐藏）")
    print(f"已检查 {checked} 个文本文件，发现 {len(errors)} 处凭据规则问题。")
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
