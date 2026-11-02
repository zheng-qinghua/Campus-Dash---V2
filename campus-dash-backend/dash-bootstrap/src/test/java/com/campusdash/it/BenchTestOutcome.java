package com.campusdash.it;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestWatcher;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试断言完成后登记实际结果，防止 @AfterAll 无条件把失败轮次记成 PASS。 */
final class BenchTestOutcome implements TestWatcher {
    private final AtomicInteger passed = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();
    private final AtomicInteger aborted = new AtomicInteger();

    @Override public void testSuccessful(ExtensionContext context) { passed.incrementAndGet(); }
    @Override public void testFailed(ExtensionContext context, Throwable cause) { failed.incrementAndGet(); }
    @Override public void testAborted(ExtensionContext context, Throwable cause) { aborted.incrementAndGet(); }
    @Override public void testDisabled(ExtensionContext context, java.util.Optional<String> reason) { aborted.incrementAndGet(); }

    String status() {
        return failed.get() > 0 ? "FAIL" : passed.get() > 0 && aborted.get() == 0 ? "PASS" : "UNKNOWN";
    }

    String summary(String scenario) {
        return String.format("{\"scenario\":\"%s\",\"passed\":%d,\"failed\":%d,\"aborted\":%d}",
                scenario, passed.get(), failed.get(), aborted.get());
    }
}
