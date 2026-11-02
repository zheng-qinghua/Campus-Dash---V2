package com.campusdash.domain.auth.ports;

import java.util.Optional;

/** 标准身份客户端已完成协议验证后，校验正式身份的本地账号绑定。 */
public interface IdentityVerificationPort {
    Optional<Long> verify(String issuer, String subject);
}
