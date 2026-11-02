#!/usr/bin/env python3
"""向调用者指定的隔离 RocketMQ 重复发送真实消息；只使用本地已打包的 Java SDK。

DELAY 示例：
  python3 scripts/publish-fault-messages.py --endpoints 127.0.0.1:18081 \\
    --topic errand-confirm-timeout --key timeout:123:0 --payload '{"errandId":"123","round":0}' --count 5 --delay-ms 1000
资金事件使用 --mode transaction --tag SETTLED；payload 应保持生产原文（含紧凑数值 JSON）。
stdout 只输出一个 JSON 报告；SDK 日志写 stderr。--compile-only 不建立网络连接。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path(__file__).with_name('FaultMessagePublisher.java')


def compile_tool(dependency_jar):
    if not dependency_jar.is_file():
        raise ValueError(f'缺少已打包的依赖 JAR：{dependency_jar}；本工具不会下载依赖或启动 Maven')
    digest = hashlib.sha256(SOURCE.read_bytes())
    with dependency_jar.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    identity = digest.hexdigest()
    base = ROOT / 'target' / 'fault-message-publisher'
    base.mkdir(parents=True, exist_ok=True)
    cache = base / identity
    if (cache / 'classes' / 'FaultMessagePublisher.class').is_file():
        return cache, identity
    temporary = Path(tempfile.mkdtemp(prefix='compile-', dir=base))
    try:
        (temporary / 'lib').mkdir()
        (temporary / 'classes').mkdir()
        with zipfile.ZipFile(dependency_jar) as archive:
            libraries = [name for name in archive.namelist() if name.startswith('BOOT-INF/lib/') and name.endswith('.jar')]
            if not any('rocketmq-client-java-' in name for name in libraries):
                raise ValueError('依赖 JAR 没有 RocketMQ 5 Java SDK')
            for name in libraries:
                with archive.open(name) as source, (temporary / 'lib' / Path(name).name).open('wb') as target:
                    shutil.copyfileobj(source, target)
        result = subprocess.run(['javac', '--release', '21', '-encoding', 'UTF-8',
                                 '-cp', str(temporary / 'lib' / '*'), '-d', str(temporary / 'classes'), str(SOURCE)],
                                text=True, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError('Java 工具编译失败：' + result.stderr + result.stdout)
        (temporary / 'logback.xml').write_text('''<configuration>
<appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender"><target>System.err</target>
<encoder><pattern>%date %-5level %logger - %msg%n</pattern></encoder></appender>
<root level="WARN"><appender-ref ref="STDERR"/></root></configuration>''', encoding='utf-8')
        try:
            temporary.rename(cache)
        except OSError:
            if not (cache / 'classes' / 'FaultMessagePublisher.class').is_file():
                raise
        return cache, identity
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('--dependency-jar', type=Path, default=ROOT / 'dash-worker/target/dash-worker-1.0.0-SNAPSHOT.jar')
    parser.add_argument('--endpoints', help='必填：隔离 RocketMQ Proxy 地址；没有默认服务端')
    parser.add_argument('--topic')
    parser.add_argument('--key')
    parser.add_argument('--tag', default='')
    payload = parser.add_mutually_exclusive_group()
    payload.add_argument('--payload')
    payload.add_argument('--payload-file', type=Path)
    parser.add_argument('--count', type=int, default=1)
    parser.add_argument('--mode', choices=['delay', 'transaction', 'normal'], default='delay')
    timing = parser.add_mutually_exclusive_group()
    timing.add_argument('--delay-ms', type=int)
    timing.add_argument('--delivery-timestamp', type=int, help='绝对投递时间，Unix epoch 毫秒')
    parser.add_argument('--request-timeout-seconds', type=int, default=10)
    parser.add_argument('--timeout-seconds', type=int, default=90)
    parser.add_argument('--output', type=Path, help='可选：将最终 JSON 回执另存为验收证据')
    args = parser.parse_args()
    report = {'ok': False, 'countRequested': args.count, 'countSent': 0, 'countPublished': 0, 'outcomeUnknown': False}
    exit_code = 1
    try:
        if not args.compile_only:
            if not args.endpoints or not args.topic or not args.key:
                raise ValueError('必须显式指定隔离环境的 --endpoints、--topic 和 --key')
            if (args.payload is None) == (args.payload_file is None):
                raise ValueError('必须提供 --payload 或 --payload-file')
            if not 1 <= args.count <= 1000:
                raise ValueError('--count 必须在 1 到 1000 之间')
            if args.request_timeout_seconds <= 0 or args.timeout_seconds <= 0:
                raise ValueError('超时时间必须为正整数')
            if args.mode != 'delay' and (args.delay_ms is not None or args.delivery_timestamp is not None):
                raise ValueError('定时时间只能用于 delay 模式，事务消息不支持延迟')
            if args.delay_ms is not None and args.delay_ms < 0:
                raise ValueError('--delay-ms 不能小于 0')
            if args.delivery_timestamp is not None and args.delivery_timestamp <= 0:
                raise ValueError('--delivery-timestamp 必须为正整数毫秒')
            body = args.payload if args.payload is not None else args.payload_file.read_text(encoding='utf-8')
            if not isinstance(json.loads(body), dict):
                raise ValueError('payload 必须是合法 JSON 对象；发送时保留原文，不重写字段类型或空格')
        cache, identity = compile_tool(args.dependency_jar.resolve())
        if args.compile_only:
            report = {'ok': True, 'compileOnly': True, 'buildSha256': identity, 'classes': str(cache / 'classes')}
            exit_code = 0
        else:
            config = {'endpoints': args.endpoints, 'topic': args.topic, 'key': args.key, 'tag': args.tag,
                      'mode': args.mode, 'payload': body, 'count': args.count,
                      'requestTimeoutSeconds': args.request_timeout_seconds,
                      'delayMillis': args.delay_ms if args.delay_ms is not None else 1000,
                      'deliveryTimestamp': args.delivery_timestamp}
            with tempfile.TemporaryDirectory(prefix='send-', dir=cache.parent) as directory:
                config_file, receipt_file = Path(directory) / 'config.json', Path(directory) / 'receipts.json'
                config_file.write_text(json.dumps(config, ensure_ascii=False), encoding='utf-8')
                command = ['java', '-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8',
                           '-Dfile.encoding=UTF-8', '-XX:ActiveProcessorCount=2', '-Xmx192m',
                           '-Dlogback.configurationFile=' + str(cache / 'logback.xml'),
                           '-cp', str(cache / 'classes') + os.pathsep + str(cache / 'lib' / '*'),
                           'FaultMessagePublisher', str(config_file), str(receipt_file)]
                timed_out = False
                try:
                    result = subprocess.run(command, text=True, capture_output=True, timeout=args.timeout_seconds)
                    exit_code = result.returncode
                    if result.stdout: print(result.stdout, file=sys.stderr, end='')
                    if result.stderr: print(result.stderr, file=sys.stderr, end='')
                except subprocess.TimeoutExpired as error:
                    timed_out = True
                    exit_code = 124
                    for captured in (error.stdout, error.stderr):
                        if captured: print(captured.decode(errors='replace') if isinstance(captured, bytes) else captured, file=sys.stderr, end='')
                if receipt_file.exists():
                    report = json.loads(receipt_file.read_text(encoding='utf-8'))
                report['buildSha256'] = identity
                report['javaExitCode'] = exit_code
                if timed_out or exit_code != 0:
                    report['ok'] = False
                    if timed_out:
                        report['outcomeUnknown'] = True
                        report['error'] = f'发送器超过 {args.timeout_seconds} 秒；已保存的回执不代表不存在额外已投递消息'
                    elif not receipt_file.exists():
                        report['outcomeUnknown'] = True
                        report['error'] = 'Java 进程异常退出且未留下回执，不能据此判定消息未发送'
                if not report.get('ok') and exit_code == 0:
                    exit_code = 1
    except Exception as error:
        report['ok'] = False
        report['error'] = str(error)
        exit_code = 1
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
