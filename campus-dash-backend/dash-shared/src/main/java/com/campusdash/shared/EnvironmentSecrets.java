package com.campusdash.shared;

/** 非 Spring 入口共用的敏感配置读取；缺失时只报告变量名，绝不回显值。 */
public final class EnvironmentSecrets {
    private EnvironmentSecrets() {}

    public static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("必须设置环境变量 " + name);
        }
        return value;
    }
}
