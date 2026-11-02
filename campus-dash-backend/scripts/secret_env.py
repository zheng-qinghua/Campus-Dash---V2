"""验收脚本仅通过环境传递数据库凭据，不将凭据拼入子进程参数。"""
import os
import re


def database_environment():
    """应用与 mysql 客户端使用同一口令，缺少配置时在创建资源前失败。"""
    password = os.environ.get('SPRING_DATASOURCE_PASSWORD')
    if not password or not password.strip():
        raise SystemExit('必须先设置非空环境变量 SPRING_DATASOURCE_PASSWORD；请勿将口令写入命令行参数')
    return {**os.environ, 'MYSQL_PWD': password}


def isolated_redis_environment(environment):
    """独占无认证 Redis 使用独立环境，避免宿主的连接地址或认证覆盖本轮配置。"""
    return {name: value for name, value in environment.items()
            if not name.startswith(('SPRING_DATA_REDIS_', 'SPRING_REDIS_'))}


def redact_environment_secrets(value):
    """子进程继承的数据库、Redis、JWT 和内部认证等凭据均不能进入报告。"""
    text = str(value)
    sensitive_name = re.compile(
        r'(?:^|_)(?:PASSWORD|PASSWD|PWD|SECRET|TOKEN|API_KEY|ACCESS_KEY|PRIVATE_KEY)(?:$|_)',
        re.IGNORECASE)
    secrets = {secret for name, secret in os.environ.items() if secret and sensitive_name.search(name)}
    # 先替换长值，避免一个凭据是另一个凭据的前缀时遗留后半段。
    for secret in sorted(secrets, key=len, reverse=True):
        text = text.replace(secret, '[REDACTED]')
    return text
