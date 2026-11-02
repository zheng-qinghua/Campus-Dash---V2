import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 独立故障验收工具，只重放指定消息，不连接数据库，也不执行任何生产用例。 */
public final class FaultMessagePublisher {
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2) throw new IllegalArgumentException("需要配置文件和回执文件路径");
        JsonNode config = JSON.readTree(Files.readString(Path.of(arguments[0])));
        Path receiptFile = Path.of(arguments[1]);
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> receipts = new ArrayList<>();
        String endpoint = config.path("endpoints").asText();
        String topic = config.path("topic").asText();
        String key = config.path("key").asText();
        String mode = config.path("mode").asText();
        byte[] body = config.path("payload").asText().getBytes(StandardCharsets.UTF_8);
        int count = config.path("count").asInt();
        report.put("ok", false);
        report.put("startedAt", Instant.now().toString());
        report.put("endpoints", endpoint);
        report.put("topic", topic);
        report.put("key", key);
        report.put("tag", config.path("tag").asText(""));
        report.put("mode", mode);
        report.put("countRequested", count);
        report.put("countSent", 0);
        report.put("countPublished", 0);
        report.put("payloadSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)));
        report.put("receipts", receipts);
        report.put("outcomeUnknown", false);
        persist(receiptFile, report);
        boolean requestAttempted = false;
        try {
            var provider = ClientServiceProvider.loadService();
            var configuration = ClientConfiguration.newBuilder().setEndpoints(endpoint)
                    .setRequestTimeout(Duration.ofSeconds(config.path("requestTimeoutSeconds").asLong(10))).build();
            var builder = provider.newProducerBuilder().setClientConfiguration(configuration).setTopics(topic);
            if (mode.equals("transaction")) {
                // 这是已知业务事件的独立重复投递，不模拟新的资金本地事务。
                builder.setTransactionChecker(message -> TransactionResolution.COMMIT);
            }
            try (Producer producer = builder.build()) {
                Long delivery = null;
                if (mode.equals("delay")) {
                    delivery = config.hasNonNull("deliveryTimestamp") ? config.get("deliveryTimestamp").asLong()
                            : Math.addExact(System.currentTimeMillis(), config.path("delayMillis").asLong(1000));
                    report.put("deliveryTimestamp", delivery.toString());
                }
                persist(receiptFile, report);
                for (int index = 0; index < count; index++) {
                    // 每次重建 Message，保持业务 key/payload 一致而取得独立的真实消息 ID。
                    var messageBuilder = provider.newMessageBuilder().setTopic(topic).setKeys(key).setBody(body);
                    String tag = config.path("tag").asText("");
                    if (!tag.isBlank()) messageBuilder.setTag(tag);
                    if (delivery != null) messageBuilder.setDeliveryTimestamp(delivery);
                    var message = messageBuilder.build();
                    requestAttempted = true;
                    Map<String, Object> receipt = new LinkedHashMap<>();
                    receipt.put("index", index);
                    if (mode.equals("transaction")) {
                        var transaction = producer.beginTransaction();
                        var sent = producer.send(message, transaction);
                        receipt.put("messageId", sent.getMessageId().toString());
                        receipt.put("transactionCommitted", false);
                        receipts.add(receipt);
                        report.put("countSent", receipts.size());
                        persist(receiptFile, report);
                        transaction.commit();
                        receipt.put("transactionCommitted", true);
                    } else {
                        var sent = producer.send(message);
                        receipt.put("messageId", sent.getMessageId().toString());
                        receipts.add(receipt);
                        report.put("countSent", receipts.size());
                    }
                    receipt.put("acknowledgedAt", Instant.now().toString());
                    report.put("countPublished", index + 1);
                    persist(receiptFile, report);
                    requestAttempted = false;
                }
                report.put("ok", true);
            }
        } catch (Throwable failure) {
            report.put("ok", false);
            report.put("error", failure.toString());
            // 发送/提交响应丢失可能已被 Broker 接受；调用者不得根据失败就认定没有消息。
            report.put("outcomeUnknown", requestAttempted);
            failure.printStackTrace(System.err);
        } finally {
            report.put("finishedAt", Instant.now().toString());
            persist(receiptFile, report);
        }
        System.exit(Boolean.TRUE.equals(report.get("ok")) ? 0 : 1);
    }

    private static void persist(Path destination, Map<String, Object> value) throws Exception {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".writing");
        Files.writeString(temporary, JSON.writeValueAsString(value), StandardCharsets.UTF_8);
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
