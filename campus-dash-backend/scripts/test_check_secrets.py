import importlib.util
import unittest
from pathlib import Path


spec = importlib.util.spec_from_file_location("check_secrets", Path(__file__).with_name("check-secrets.py"))
scanner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(scanner)


class SecretRulesTest(unittest.TestCase):
    def test_config_requires_env(self):
        value = "fixture" + "-credential"
        for key in ("password", "jwt-secret", "MYSQL_ROOT_PASSWORD"):
            with self.subTest(key=key):
                self.assertTrue(scanner.findings("application.yaml", f"{key}: {value}"))

    def test_env_without_default_is_allowed(self):
        for value in ("${SPRING_DATASOURCE_PASSWORD}", "${DASH_JWT_SECRET:}", "${SPRING_DATASOURCE_PASSWORD:?Set SPRING_DATASOURCE_PASSWORD}"):
            self.assertFalse(scanner.findings("application.yaml", f"password: {value}"))

    def test_literal_env_default_is_rejected(self):
        value = "${%s:%s}" % ("SPRING_DATASOURCE_PASSWORD", "fixture-value")
        self.assertTrue(scanner.findings("application.yaml", f"password: {value}"))

    def test_source_default_is_rejected(self):
        value = "os.getenv('SPRING_DATASOURCE_PASSWORD', '" + "fixture-value')"
        self.assertTrue(scanner.findings("run.py", value))

    def test_cli_password_is_rejected(self):
        value = "mysql -uroot -p" + "fixture-value"
        self.assertTrue(scanner.findings("run.sh", value))

    def test_env_cli_and_redaction_are_allowed(self):
        for value in ('MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql -uroot', 'mysql "-p[REDACTED]"'):
            self.assertFalse(scanner.findings("run.sh", value))

    def test_report_contains_positions_only(self):
        value = "fixture" + "-credential"
        result = scanner.findings("application.yaml", f"password: {value}")
        self.assertEqual(result, [(1, "配置含固定凭据")])
        self.assertNotIn(value, repr(result))

    def test_shell_validation_and_generation_are_allowed(self):
        for value in (
            '${SPRING_DATASOURCE_PASSWORD//[[:space:]]/}',
            'DASH_JWT_SECRET="$(python3 generate_runtime_key.py)"',
        ):
            self.assertFalse(scanner.findings("verify.sh", value))


if __name__ == "__main__":
    unittest.main()
