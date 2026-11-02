package com.campusdash.infrastructure.persistence;

import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 在独占 MySQL 库复现两个发送者完成顺序相反的窗口。 */
class JdbcLocalMessageConcurrencyIT {
    private static JdbcTemplate admin, jdbc;
    private static String schema;
    private JdbcLocalMessageRepository messages;

    @BeforeAll static void initialize() throws Exception {
        String host = System.getProperty("it.mysql.host", "127.0.0.1");
        int port = Integer.getInteger("it.mysql.port", 3307);
        boolean available;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 500);
            available = true;
        } catch (Exception unavailable) { available = false; }
        if (Boolean.getBoolean("requireMiddleware")) assertTrue(available, "必须提供真实 MySQL");
        Assumptions.assumeTrue(available, "真实 MySQL 不可用");
        String user = System.getProperty("it.mysql.user", "root");
        String password = com.campusdash.shared.EnvironmentSecrets.required("SPRING_DATASOURCE_PASSWORD");
        String url = "jdbc:mysql://" + host + ":" + port + "/";
        String options = "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        admin = new JdbcTemplate(new DriverManagerDataSource(url + options, user, password));
        schema = "message_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        var dataSource = new DriverManagerDataSource(url + schema + options, user, password);
        jdbc = new JdbcTemplate(dataSource);
        Path root = Path.of("").toAbsolutePath();
        while (!root.resolve("docker/init.sql").toFile().exists()) root = root.getParent();
        try (var connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(root.resolve("docker/init.sql")));
        }
    }

    @AfterAll static void cleanup() {
        if (admin != null && schema != null) admin.execute("DROP DATABASE " + schema);
    }

    @BeforeEach void setup() { messages = new JdbcLocalMessageRepository(jdbc); }

    private String enqueue(long id) {
        String key = "timeout:review:" + id;
        assertTrue(messages.enqueue(id, key, "errand-confirm-timeout", "{}", Instant.now().plusSeconds(300)));
        return key;
    }

    @Test void lateFailureCannotReopenAnotherSendersSuccess() {
        String key = enqueue(1);
        // 两个发送者已经读到同一条 PENDING；先成功的线程标记 SENT。
        assertTrue(messages.findPending(100).stream().anyMatch(m -> m.msgKey().equals(key)));
        var secondSender = new JdbcLocalMessageRepository(jdbc);
        assertTrue(secondSender.findPending(100).stream().anyMatch(m -> m.msgKey().equals(key)));
        messages.markSent(key);
        secondSender.markRetry(key, 6);
        assertEquals("SENT", status(key));
        assertEquals(0, retries(key));
    }

    @Test void retryBudgetCountsEachFailureOnceAndDeadIsNotAutomaticallyReopened() {
        String key = enqueue(2);
        for (int failure = 1; failure <= 3; failure++) {
            messages.markRetry(key, 3);
            assertEquals(failure, retries(key));
            assertEquals(failure == 3 ? "DEAD" : "PENDING", status(key));
        }
        messages.markRetry(key, 3);
        assertEquals(3, retries(key));
        assertEquals("DEAD", status(key));
    }

    @Test void successfulInFlightSendCanFinishAfterAnotherSenderExhaustedRetries() {
        String key = enqueue(3);
        messages.markRetry(key, 1);
        assertEquals("DEAD", status(key));
        messages.markSent(key);
        messages.markRetry(key, 1);
        assertEquals("SENT", status(key));
        assertEquals(1, retries(key));
    }

    private String status(String key) {
        return jdbc.queryForObject("SELECT status FROM local_message WHERE msg_key=?", String.class, key);
    }
    private int retries(String key) {
        return jdbc.queryForObject("SELECT retry_count FROM local_message WHERE msg_key=?", Integer.class, key);
    }
}
