"""环境凭据与验收子进程参数的回归检查；不连接真实中间件。"""
import ast
from contextlib import redirect_stdout
import importlib.util
import io
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from secret_env import database_environment, isolated_redis_environment, redact_environment_secrets

ROOT = Path(__file__).resolve().parents[1]


class SecretEnvironmentTest(unittest.TestCase):
    def test_requires_nonempty_database_environment(self):
        for environment in ({}, {'SPRING_DATASOURCE_PASSWORD': ''}, {'SPRING_DATASOURCE_PASSWORD': ' \t\n'}):
            with self.subTest(environment=environment), patch.dict(os.environ, environment, clear=True):
                with self.assertRaisesRegex(SystemExit, 'SPRING_DATASOURCE_PASSWORD'):
                    database_environment()

    def test_password_only_in_child_environment(self):
        password = secrets.token_urlsafe(32)
        with patch.dict(os.environ, {'SPRING_DATASOURCE_PASSWORD': password, 'PATH': '/usr/bin'}, clear=True):
            environment = database_environment()
            self.assertEqual(password, environment['MYSQL_PWD'])
            self.assertEqual(password, environment['SPRING_DATASOURCE_PASSWORD'])
            self.assertEqual('/usr/bin', environment['PATH'])
            self.assertNotIn('MYSQL_PWD', os.environ)

    def test_redacts_external_command_failures(self):
        password = secrets.token_urlsafe(32)
        root_password = secrets.token_urlsafe(32)
        with patch.dict(os.environ, {'SPRING_DATASOURCE_PASSWORD': password,
                                     'MYSQL_ROOT_PASSWORD': root_password}, clear=True):
            message = redact_environment_secrets(RuntimeError(f'error {password} / {root_password}'))
            self.assertNotIn(password, message)
            self.assertNotIn(root_password, message)
            self.assertIn('[REDACTED]', message)

    def test_redacts_all_inherited_credential_families(self):
        names = ('SPRING_DATA_REDIS_PASSWORD', 'DASH_JWT_SECRET', 'DASH_INTERNAL_TOKEN',
                 'MQ_ACCESS_KEY', 'OIDC_CLIENT_SECRET', 'SIGNING_PRIVATE_KEY')
        environment = {name: secrets.token_urlsafe(32) for name in names}
        environment['PATH'] = '/usr/bin'
        with patch.dict(os.environ, environment, clear=True):
            message = redact_environment_secrets(' '.join(environment.values()))
            for name in names:
                self.assertNotIn(environment[name], message)
            self.assertEqual(len(names), message.count('[REDACTED]'))
            self.assertIn('/usr/bin', message)

    def test_redacts_longest_secret_before_its_prefix(self):
        prefix = secrets.token_urlsafe(16)
        suffix = secrets.token_urlsafe(16)
        with patch.dict(os.environ, {'DASH_INTERNAL_TOKEN': prefix,
                                     'DASH_JWT_SECRET': prefix + suffix}, clear=True):
            self.assertEqual('[REDACTED]', redact_environment_secrets(prefix + suffix))

    def test_private_redis_does_not_inherit_shared_redis_configuration(self):
        password = secrets.token_urlsafe(32)
        environment = {'SPRING_DATASOURCE_PASSWORD': password, 'MYSQL_PWD': password,
                       'SPRING_DATA_REDIS_PASSWORD': secrets.token_urlsafe(32),
                       'SPRING_DATA_REDIS_USERNAME': 'configured-user',
                       'SPRING_DATA_REDIS_URL': 'redis://shared.invalid:6379',
                       'SPRING_DATA_REDIS_HOST': 'shared.invalid',
                       'SPRING_DATA_REDIS_DATABASE': '8',
                       'SPRING_REDIS_PASSWORD': secrets.token_urlsafe(32)}
        isolated = isolated_redis_environment(environment)
        self.assertEqual({'SPRING_DATASOURCE_PASSWORD': password, 'MYSQL_PWD': password}, isolated)
        self.assertIn('SPRING_DATA_REDIS_PASSWORD', environment)
        for filename in ('verify-live-api-mq.py', 'verify-fault-recovery.py'):
            tree = ast.parse((ROOT / 'scripts' / filename).read_text())
            launch = next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == 'launch')
            env = next(keyword.value for node in ast.walk(launch) if isinstance(node, ast.Call)
                       and isinstance(node.func, ast.Attribute) and node.func.attr == 'Popen'
                       for keyword in node.keywords if keyword.arg == 'env')
            self.assertEqual('application_env', env.id)

    def test_two_api_failure_is_redacted_in_stdout_and_report(self):
        spec = importlib.util.spec_from_file_location('verify_two_api', ROOT / 'scripts/verify-two-api-processes.py')
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        password = secrets.token_urlsafe(32)
        with tempfile.TemporaryDirectory() as temp_dir:
            stdout = io.StringIO()
            arguments = ['verify-two-api-processes.py', '--jar', __file__, '--output', temp_dir]
            with patch.dict(os.environ, {'SPRING_DATASOURCE_PASSWORD': password}, clear=True), \
                    patch('sys.argv', arguments), \
                    patch.object(module.subprocess, 'run', side_effect=RuntimeError(password)), \
                    redirect_stdout(stdout):
                self.assertEqual(1, module.main())
            report = (Path(temp_dir) / 'report.json').read_text()
            self.assertNotIn(password, stdout.getvalue() + report)
            self.assertIn('[REDACTED]', stdout.getvalue())
            self.assertIn('[REDACTED]', report)

    def test_sql_command_uses_environment_name_only(self):
        for filename in ('verify-live-api-mq.py', 'verify-fault-recovery.py', 'verify-two-api-processes.py'):
            tree = ast.parse((ROOT / 'scripts' / filename).read_text())
            strings = [node.value for node in ast.walk(tree) if isinstance(node, ast.Constant)
                       and isinstance(node.value, str)]
            with self.subTest(script=filename):
                self.assertIn('MYSQL_PWD', strings)
                self.assertFalse(any(value.startswith(('MYSQL_PWD=', '--spring.datasource.password='))
                                     for value in strings))

    def test_shell_launcher_keeps_secret_out_of_arguments_and_xtrace(self):
        password = secrets.token_urlsafe(32)
        with tempfile.TemporaryDirectory() as temp_dir:
            temp = Path(temp_dir)
            # 两个外部命令只校验参数与环境，不创建数据库、不实际运行 Maven。
            stub = '''#!/usr/bin/env python3
import hashlib, os, sys
from pathlib import Path
secret = os.environ["SPRING_DATASOURCE_PASSWORD"]
assert os.environ["MYSQL_PWD"] == secret
assert all(secret not in part for part in sys.argv)
jwt = os.environ["DASH_JWT_SECRET"]
expected = os.environ.get("TEST_EXPECTED_JWT_SECRET")
if expected:
    assert jwt == expected
else:
    assert len(bytes.fromhex(jwt)) == 32
assert all(jwt not in part for part in sys.argv)
Path(os.environ["TEST_JWT_DIGEST_FILE"]).write_text(hashlib.sha256(jwt.encode()).hexdigest())
print("external-command-ok")
'''
            binaries = temp / 'bin'
            binaries.mkdir()
            for name in ('docker', 'mvn'):
                path = binaries / name
                path.write_text(stub)
                path.chmod(0o700)
            # 在临时目录复刻相对路径，避免覆盖真实验收 latest-run.txt。
            (temp / 'scripts').mkdir()
            (temp / 'docker').mkdir()
            (temp / 'docker/init.sql').write_text('SELECT 1;')
            script = temp / 'scripts/verify-grab-v2.sh'
            script.write_bytes((ROOT / 'scripts/verify-grab-v2.sh').read_bytes())
            environment = {**os.environ, 'SPRING_DATASOURCE_PASSWORD': password,
                           'MVN_BIN': str(binaries / 'mvn'), 'PATH': str(binaries) + os.pathsep + os.environ['PATH']}
            environment.pop('DASH_JWT_SECRET', None)
            environment.pop('TEST_EXPECTED_JWT_SECRET', None)
            environment['TEST_JWT_DIGEST_FILE'] = str(temp / 'jwt-digest.txt')
            digests = []
            for configured_jwt in (None, '', secrets.token_urlsafe(32)):
                with self.subTest(jwt_mode='generated' if not configured_jwt else 'preserved'):
                    child_env = dict(environment)
                    if configured_jwt is not None:
                        child_env['DASH_JWT_SECRET'] = configured_jwt
                    if configured_jwt:
                        child_env['TEST_EXPECTED_JWT_SECRET'] = configured_jwt
                    result = subprocess.run(['bash', '-x', str(script)], capture_output=True, text=True,
                                            env=child_env, timeout=15)
                    self.assertEqual(0, result.returncode, result.stderr)
                    self.assertNotIn(password, result.stdout + result.stderr)
                    if configured_jwt:
                        self.assertNotIn(configured_jwt, result.stdout + result.stderr)
                    self.assertEqual(3, result.stdout.count('external-command-ok'))
                    digests.append((temp / 'jwt-digest.txt').read_text())
            self.assertNotEqual(digests[0], digests[1])

    def test_scripts_fail_before_resources_when_password_missing(self):
        environment = {key: value for key, value in os.environ.items()
                       if key not in ('SPRING_DATASOURCE_PASSWORD', 'MYSQL_PWD', 'MYSQL_ROOT_PASSWORD')}
        commands = [
            ['python3', str(ROOT / 'scripts/verify-live-api-mq.py')],
            ['python3', str(ROOT / 'scripts/verify-fault-recovery.py')],
            ['python3', str(ROOT / 'scripts/verify-two-api-processes.py'), '--jar', __file__],
            ['bash', str(ROOT / 'scripts/verify-grab-v2.sh')],
            ['bash', str(ROOT / 'bench/scripts/cleanup.sh')],
        ]
        for command in commands:
            for password in (None, '', ' \t\n'):
                with self.subTest(script=Path(command[1]).name, password_state=repr(password)):
                    child_env = dict(environment)
                    if password is not None:
                        child_env['SPRING_DATASOURCE_PASSWORD'] = password
                    result = subprocess.run(command, capture_output=True, text=True, env=child_env, timeout=5)
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn('SPRING_DATASOURCE_PASSWORD', result.stderr)


if __name__ == '__main__':
    unittest.main()
