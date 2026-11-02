package com.campusdash.presentation.auth;

/** 鉴权依赖暂不可用；不代表令牌失效，也不得借此放行请求。 */
public final class AuthDependencyUnavailableException extends RuntimeException {
    public AuthDependencyUnavailableException(Throwable cause) {
        super("鉴权依赖暂不可用", cause);
    }
}
