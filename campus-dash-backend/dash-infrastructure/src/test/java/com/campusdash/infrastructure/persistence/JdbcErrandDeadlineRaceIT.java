package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** 真实 InnoDB 行锁等待：时间判断必须发生在拿锁之后，不能使用 UPDATE 入队时的 NOW。 */
class JdbcErrandDeadlineRaceIT {
    private static JdbcTemplate admin, jdbc;
    private static DriverManagerDataSource dataSource;
    private static String schema;
    private JdbcErrandRepository errands;
    private TransactionTemplate tx;

    @BeforeAll static void initialize() throws Exception {
        String host = System.getProperty("it.mysql.host", "127.0.0.1");
        int port = Integer.getInteger("it.mysql.port", 3307);
        boolean available;
        try (Socket socket = new Socket()) { socket.connect(new InetSocketAddress(host, port), 500); available = true; }
        catch (Exception e) { available = false; }
        if (Boolean.getBoolean("requireMiddleware")) assertTrue(available, "必须提供真实 MySQL");
        Assumptions.assumeTrue(available, "真实 MySQL 不可用");
        String options = "?allowPublicKeyRetrieval=true&useSSL=false&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
        String url = "jdbc:mysql://" + host + ":" + port + "/";
        String user = System.getProperty("it.mysql.user", "root"), password = com.campusdash.shared.EnvironmentSecrets.required("SPRING_DATASOURCE_PASSWORD");
        admin = new JdbcTemplate(new DriverManagerDataSource(url + options, user, password));
        schema = "deadline_it_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE DATABASE " + schema);
        dataSource = new DriverManagerDataSource(url + schema + options, user, password);
        jdbc = new JdbcTemplate(dataSource);
        Path root = Path.of("").toAbsolutePath();
        while (!root.resolve("docker/init.sql").toFile().exists()) root = root.getParent();
        try (Connection c = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(c, new FileSystemResource(root.resolve("docker/init.sql")));
        }
    }

    @AfterAll static void cleanup() { if (admin != null && schema != null) admin.execute("DROP DATABASE " + schema); }

    @BeforeEach void setup() {
        errands = new JdbcErrandRepository(jdbc, new JdbcGrabRepairTaskRepository(jdbc), new SnowflakeIdGenerator(24), 5);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    private void locked(long id) {
        tx.executeWithoutResult(status -> {
            errands.insert(Errand.draft(id, 1, 1001, ErrandType.DELIVERY, "截止时间锁竞争", Money.ofCents(100), 1));
            assertEquals(1, errands.casPublish(id, 0));
            assertEquals(1, errands.casLockForRunner(id, 2001, 1, 0));
        });
    }

    private Connection block(long id) throws Exception {
        Connection blocker = dataSource.getConnection();
        blocker.setAutoCommit(false);
        try (var statement = blocker.prepareStatement("SELECT id FROM errand WHERE id = ? FOR UPDATE")) {
            statement.setLong(1, id);
            try (var result = statement.executeQuery()) { assertTrue(result.next()); }
        }
        return blocker;
    }

    private boolean waitingForRowLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM performance_schema.data_lock_waits w
                 JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID
                  AND l.ENGINE = w.ENGINE
                 WHERE l.OBJECT_SCHEMA = ? AND l.OBJECT_NAME = 'errand')
                """, Boolean.class, schema));
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "未观察到预期的数据库锁/时钟条件");
            Thread.sleep(20);
        }
    }

    @Test void confirmationQueuedBeforeDeadlineCannotSucceedAfterLockIsGrantedTooLate() throws Exception {
        long id = 880001;
        locked(id);
        jdbc.update("UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,2,NOW(3)) WHERE id=?", id);
        try (Connection blocker = block(id); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var confirm = pool.submit(() -> tx.execute(status -> errands.casAccept(id, 2001, 2)));
            try {
            await(this::waitingForRowLock);
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT confirm_deadline > NOW(3) FROM errand WHERE id=?", Boolean.class, id)),
                    "确认必须在截止之前已实际进入行锁等待，才能覆盖旧 NOW 漏洞");
            await(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT confirm_deadline <= NOW(3) FROM errand WHERE id=?", Boolean.class, id)));
            blocker.commit();
            assertEquals(0, confirm.get(10, TimeUnit.SECONDS));
            } finally { blocker.rollback(); }
        }
        assertEquals("LOCKED", jdbc.queryForObject("SELECT status FROM errand WHERE id=?", String.class, id));
    }

    @Test void newOwnerConfirmationWindowStartsAfterTransferObtainsTheRowLock() throws Exception {
        long id = 880002;
        locked(id);
        jdbc.update("UPDATE errand SET confirm_deadline=TIMESTAMPADD(SECOND,-1,NOW(3)) WHERE id=?", id);
        try (Connection blocker = block(id); var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var transfer = pool.submit(() -> tx.execute(status -> errands.casTransferToNext(id, 2002, 2, 0)));
            try {
            await(this::waitingForRowLock);
            jdbc.queryForObject("SELECT SLEEP(0.05)", Integer.class);
            Timestamp releasedAt = jdbc.queryForObject("SELECT NOW(3)", Timestamp.class);
            blocker.commit();
            assertEquals(1, transfer.get(10, TimeUnit.SECONDS));
            assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT locked_at >= ? AND confirm_deadline >= TIMESTAMPADD(SECOND,5,?) FROM errand WHERE id=?",
                    Boolean.class, releasedAt, releasedAt, id)), "锁等待不能消耗新跑腿的确认窗口");
            } finally { blocker.rollback(); }
        }
    }
}
