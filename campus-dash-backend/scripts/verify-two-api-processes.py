#!/usr/bin/env python3
"""启动两份已打包 API，真实验证跨 Java 进程的幂等与共享跑腿限流。

仅允许 campus_dash_verify_* 专用验收数据库。保留本轮任务与按 run_id 登记的证据，
不启动 Worker/MQ，不删除业务数据；退出时只停止本脚本启动的两个 Java PID。
"""
import argparse
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
import traceback
from datetime import datetime
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
from zoneinfo import ZoneInfo
from secret_env import database_environment, redact_environment_secrets


ROOT = Path(__file__).resolve().parents[1]
TIMEZONE = ZoneInfo('Asia/Shanghai')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default='campus_dash_verify_20261020_192910')
    parser.add_argument('--jar', default=str(ROOT / 'dash-bootstrap/target/dash-bootstrap-1.0.0-SNAPSHOT.jar'))
    parser.add_argument('--java', default='java')
    parser.add_argument('--mysql-container', default='dash-mysql')
    parser.add_argument('--ports', type=int, nargs=2, default=[18080, 18082])
    parser.add_argument('--output')
    args = parser.parse_args()
    if not re.fullmatch(r'campus_dash_verify_[a-zA-Z0-9_]+', args.database):
        parser.error('必须显式使用 campus_dash_verify_* 专用验收数据库')
    if len(set(args.ports)) != 2:
        parser.error('两个 API 必须使用不同端口')
    jar = Path(args.jar).resolve()
    if not jar.is_file():
        parser.error(f'未找到已打包 jar：{jar}；请先完成 Maven 构建')
    database_env = database_environment()
    stamp = datetime.now(TIMEZONE).strftime('%Y%m%d-%H%M%S')
    run_id = f'{stamp}-{secrets.token_hex(3)}'
    output = Path(args.output or ROOT / 'bench/reports' / f'two-api-processes-{run_id}').resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {
        'runId': run_id, 'startedAt': datetime.now(TIMEZONE).isoformat(), 'passed': False,
        'mode': '两个独立 Java API 进程；真实 HTTP + MySQL + Redis；未启用 Worker/MQ',
        'database': args.database, 'jar': str(jar), 'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
        'limits': {'runnerRate': 0.01, 'runnerBurst': 4, 'errandRate': 0.01, 'errandBurst': 2,
                   'queryRate': 5, 'queryBurst': 10},
        'checks': [], 'requests': [], 'tasks': [], 'processes': [],
    }
    processes = []
    logs = []
    report_lock = threading.Lock()

    def sql(statement):
        result = subprocess.run(
            ['docker', 'exec', '-e', 'MYSQL_PWD', '-i', args.mysql_container,
             'mysql', '-uroot', '--default-character-set=utf8mb4', '-N', '-B', args.database],
            input=statement, text=True, capture_output=True, check=True, timeout=20, env=database_env)
        return result.stdout.strip()

    def literal(value):
        return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"

    def check(name, condition, evidence):
        if not condition:
            raise AssertionError(f'{name}: {evidence}')
        report['checks'].append({'name': name, 'passed': True, 'evidence': evidence})
        print(f'PASS {name}', flush=True)

    def http(instance, method, path, token=None, body=None, request_id=None, record=True):
        headers = {'Content-Type': 'application/json'}
        if token:
            headers['Authorization'] = f'Bearer {token}'
        if request_id:
            headers['X-Request-Id'] = request_id
        request = Request(f'http://127.0.0.1:{args.ports[instance]}{path}', method=method,
                          headers=headers, data=None if body is None else json.dumps(body).encode())
        started = time.monotonic()
        try:
            with urlopen(request, timeout=15) as response:
                status, raw, response_headers = response.status, response.read(), dict(response.headers)
        except HTTPError as error:
            status, raw, response_headers = error.code, error.read(), dict(error.headers)
        result = json.loads(raw) if raw else {}
        if record:
            entry = {'instance': instance, 'port': args.ports[instance], 'method': method, 'path': path,
                     'httpStatus': status, 'code': result.get('code'),
                     'requestId': request_id, 'elapsedMillis': round((time.monotonic() - started) * 1000)}
            # 登录返回的令牌不能出现在验收报告或控制台。
            if not path.startswith('/api/auth/'):
                entry['data'] = result.get('data')
                entry['retryAfter'] = next((v for k, v in response_headers.items() if k.lower() == 'retry-after'), None)
            with report_lock:
                report['requests'].append(entry)
        return status, result, response_headers

    def login(instance, runner):
        status, body, _ = http(instance, 'POST', '/api/auth/login', body={'userId': runner})
        if status != 200 or body.get('code') != 'OK':
            raise AssertionError(f'登录失败：HTTP {status} code={body.get("code")}')
        return body['data']['token']

    def publish(publisher_token, label):
        status, body, _ = http(0, 'POST', '/api/errands', publisher_token, {
            'title': f'bench_two_api_{label}_{run_id}', 'type': 'DELIVERY', 'rewardCents': 100, 'slotTotal': 1,
        })
        assert status == 200 and body.get('code') == 'OK', body
        ident = str(body['data']['errandId'])
        assert ident.isdigit() and int(ident) > 2 ** 53
        report['tasks'].append({'id': ident, 'label': label})
        sql(f"INSERT INTO bench_run_item(run_id,entity_type,entity_id) VALUES ({literal(run_id)},'ERRAND',{int(ident)});")
        return ident

    def grab(instance, ident, token, request_id):
        return http(instance, 'POST', f'/api/errands/{ident}/grab', token,
                    {'expectedRound': 0}, request_id)

    def query(instance, ident, token, request_id):
        return http(instance, 'GET', f'/api/errands/{ident}/grab-requests/{request_id}', token)

    def database_counts(ident, runner):
        row = sql(f"""SELECT
            (SELECT COUNT(*) FROM grab_record WHERE errand_id={int(ident)} AND result='GRABBED'),
            (SELECT COUNT(*) FROM grab_request WHERE errand_id={int(ident)} AND runner_id={int(runner)}),
            (SELECT COUNT(*) FROM grab_request WHERE errand_id={int(ident)} AND runner_id={int(runner)} AND status='SUCCEEDED'),
            slot_taken,grabber_id FROM errand WHERE id={int(ident)};""")
        return [None if value == 'NULL' else int(value) for value in row.split('\t')]

    run_registered = False
    try:
        assert sql('SELECT DATABASE();') == args.database
        for port in args.ports:
            with socket.socket() as probe:
                # 上轮已关闭进程的 TIME_WAIT 不代表仍有监听者；真正占用仍会 bind 失败。
                probe.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                probe.bind(('127.0.0.1', port))
        sql(f"""INSERT INTO bench_run(run_id,kind,scenario,started_at,status,concurrency,env_note)
            VALUES({literal(run_id)},'E2E','TWO-API-PROCESSES',NOW(3),'RUNNING',2,
            '两个 Java 进程，同机真实 HTTP/MySQL/Redis；专用验收库；MQ/Worker 关闭');""")
        run_registered = True
        # 独立账号只作本轮测试夹具，初始资金等同 seed，不修改既有发单人余额。
        owner_base = 800_000_000_000 + secrets.randbelow(100_000_000_000)
        publisher = owner_base
        runners = [owner_base + n for n in (1, 2, 3)]
        sql(f"""INSERT INTO wallet_account(id,owner_id,owner_type,available,frozen,version)
            VALUES({owner_base + 10},{publisher},'USER',100000,0,0);""")
        report['actors'] = {'publisher': publisher, 'runners': runners, 'publisherInitialCents': 100000}

        for index, port in enumerate(args.ports):
            log_path = output / f'api-{port}.log'
            stream = log_path.open('w', encoding='utf-8')
            logs.append(stream)
            command = [
                args.java, '-Xms128m', '-Xmx384m', '-XX:ActiveProcessorCount=4', '-jar', str(jar),
                '--spring.profiles.active=local', f'--server.port={port}', '--server.address=127.0.0.1',
                f'--dash.worker-id={31 + index}', '--dash.mq.enabled=false',
                '--dash.auth.allow-header-identity=false', '--dash.auth.mode=session',
                f'--dash.auth.session-namespace=two-api-{run_id}',
                f'--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/{args.database}?useSSL=false&serverTimezone=Asia/Shanghai',
                '--spring.datasource.hikari.maximum-pool-size=10',
                '--dash.grab.limit.enabled=false', '--dash.credit.max-ongoing=10000',
                '--dash.grab.runner-limit.rate=0.01', '--dash.grab.runner-limit.burst=4',
                '--dash.grab.runner-limit.errand-rate=0.01', '--dash.grab.runner-limit.errand-burst=2',
                '--dash.grab.runner-limit.query-rate=5', '--dash.grab.runner-limit.query-burst=10',
            ]
            process = subprocess.Popen(command, stdout=stream, stderr=subprocess.STDOUT, env=database_env)
            processes.append(process)
            report['processes'].append({'pid': process.pid, 'port': port, 'workerId': 31 + index, 'log': str(log_path)})

        deadline = time.monotonic() + 90
        for index, process in enumerate(processes):
            while True:
                if process.poll() is not None:
                    raise AssertionError(f'API 进程提前退出 PID={process.pid}，查看 {report["processes"][index]["log"]}')
                try:
                    status, body, _ = http(index, 'GET', '/api/health', record=False)
                    if status == 200 and body.get('code') == 'OK':
                        break
                except (OSError, URLError):
                    pass
                if time.monotonic() > deadline:
                    raise TimeoutError('两份 API 未在 90 秒内就绪')
                time.sleep(0.25)
        check('启动两个独立且可访问的 Java 进程', len({p.pid for p in processes}) == 2,
              report['processes'])
        publisher_token = login(0, publisher)
        concurrent_token = login(0, runners[0])
        ident = publish(publisher_token, 'same_request')
        import uuid
        request_id = str(uuid.uuid4())
        gate = threading.Barrier(2)

        def concurrent_grab(index):
            gate.wait(timeout=10)
            return grab(index, ident, concurrent_token, request_id)

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(concurrent_grab, range(2)))
        check('同请求并发打到两实例仅返回成功或处理中',
              all(status in (200, 202) for status, _, _ in responses),
              [body for _, body, _ in responses])
        for attempt in range(20):
            status, body, _ = query(attempt % 2, ident, concurrent_token, request_id)
            if status == 200 and body.get('data', {}).get('status') == 'SUCCEEDED':
                break
            assert status == 202, body
            time.sleep(0.25)
        else:
            raise AssertionError('原请求未恢复到成功终态')
        both = [query(index, ident, concurrent_token, request_id)[1]['data'] for index in range(2)]
        check('两实例查询恢复为同一原请求成功结果',
              all(item['status'] == 'SUCCEEDED' and item['grabbed'] and item['requestId'] == request_id
                  and item['appliedRound'] == 0 for item in both), both)
        counts = database_counts(ident, runners[0])
        check('MySQL 只有一条业务抢中事实与一条成功请求',
              counts == [1, 1, 1, 1, runners[0]], {'errandId': ident, 'counts': counts})

        global_token = login(0, runners[1])
        global_results = []
        global_operations = []
        for index in range(5):
            task_id = publish(publisher_token, f'global_{index}')
            operation = str(uuid.uuid4())
            status, body, headers = grab(index % 2, task_id, global_token, operation)
            global_operations.append((task_id, operation))
            global_results.append((status, body, headers))
        check('跨任务跑腿总桶由两实例共享，容量四不会扩成八',
              [entry[0] for entry in global_results] == [200, 200, 200, 200, 429],
              [{'httpStatus': s, 'body': b} for s, b, _ in global_results])
        last_id, _ = global_operations[-1]
        check('限流拒绝没有登记请求或抢中记录',
              database_counts(last_id, runners[1])[:3] == [0, 0, 0], {'errandId': last_id})

        per_task_token = login(1, runners[2])
        per_task_id = publish(publisher_token, 'same_errand_limit')
        original_id = str(uuid.uuid4())
        first = grab(0, per_task_id, per_task_token, original_id)
        replay = grab(1, per_task_id, per_task_token, original_id)
        limited = grab(0, per_task_id, per_task_token, original_id)
        check('同跑腿同任务桶跨实例共享，相同 ID 重试仍计费',
              [first[0], replay[0], limited[0]] == [200, 200, 429] and replay[1]['data']['replayed'],
              [first[1], replay[1], limited[1]])
        retry_after = next((v for k, v in limited[2].items() if k.lower() == 'retry-after'), None)
        check('429 返回可用的 Retry-After', retry_after is not None and int(retry_after) > 0,
              {'Retry-After': retry_after})
        recovered = query(1, per_task_id, per_task_token, original_id)
        check('写桶耗尽后仍可用独立查询预算恢复原 ID',
              recovered[0] == 200 and recovered[1]['data']['status'] == 'SUCCEEDED'
              and recovered[1]['data']['requestId'] == original_id, recovered[1])
        check('跨实例重放与限流没有增加业务副作用',
              database_counts(per_task_id, runners[2]) == [1, 1, 1, 1, runners[2]],
              {'errandId': per_task_id, 'counts': database_counts(per_task_id, runners[2])})
        report['passed'] = True
    except BaseException as error:
        report['failure'] = {'type': type(error).__name__, 'message': redact_environment_secrets(error), 'traceback': redact_environment_secrets(traceback.format_exc())}
        print(f'FAIL {type(error).__name__}: {redact_environment_secrets(error)}', flush=True)
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        for stream in logs:
            stream.close()
        for item, process in zip(report['processes'], processes):
            item['exitCode'] = process.returncode
        report['processesStopped'] = all(process.poll() is not None for process in processes)
        report['finishedAt'] = datetime.now(TIMEZONE).isoformat()
        if run_registered:
            summary = json.dumps({'passed': report['passed'], 'checks': len(report['checks']),
                                  'report': str(output / 'report.json')}, ensure_ascii=False)
            try:
                sql(f"UPDATE bench_run SET finished_at=NOW(3),status={literal('PASS' if report['passed'] else 'FAIL')},"
                    f"summary=CAST({literal(summary)} AS JSON) WHERE run_id={literal(run_id)};")
            except Exception as error:
                report['passed'] = False
                report['recordingFailure'] = redact_environment_secrets(error)
        (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
        print(f'REPORT {output / "report.json"}', flush=True)
    return 0 if report['passed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
