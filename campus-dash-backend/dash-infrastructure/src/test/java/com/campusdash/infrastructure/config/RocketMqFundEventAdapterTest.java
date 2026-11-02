package com.campusdash.infrastructure.config;

import com.campusdash.domain.wallet.ports.FundEventPort;
import com.campusdash.domain.wallet.ports.WalletRepository;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.apache.rocketmq.client.apis.producer.Transaction;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RocketMqFundEventAdapterTest {
    private Producer producer;
    private Transaction transaction;
    private WalletRepository wallets;
    private RocketMqFundEventAdapter adapter;
    private final FundEventPort.FundEvent event =
            new FundEventPort.FundEvent("settle:101", "SETTLED", 101, 1001, 2001, 95, 5);

    @BeforeEach void initialize() throws Exception {
        producer = mock(Producer.class);
        transaction = mock(Transaction.class);
        wallets = mock(WalletRepository.class);
        when(producer.beginTransaction()).thenReturn(transaction);
        adapter = new RocketMqFundEventAdapter(producer, ClientServiceProvider.loadService(), wallets);
    }

    @Test void definite_local_commit_publishes_half_message() throws Exception {
        assertTrue(adapter.publishInTransaction(event, () -> true));
        verify(transaction).commit();
        verify(transaction, never()).rollback();
    }

    @Test void definite_local_rollback_discards_half_message() throws Exception {
        assertFalse(adapter.publishInTransaction(event, () -> false));
        verify(transaction).rollback();
        verify(transaction, never()).commit();
    }

    @Test void unknown_local_outcome_keeps_half_message_until_primary_fact_is_known() {
        var failure = new IllegalStateException("提交响应丢失，当前无法判断 MySQL 结果");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> adapter.publishInTransaction(event, () -> { throw failure; })));
        verifyNoInteractions(transaction);
        when(wallets.ledgerExists(event.bizNo())).thenReturn(false, true);
        var view = message(System.currentTimeMillis());
        assertEquals(TransactionResolution.UNKNOWN, adapter.checker().check(view));
        assertEquals(TransactionResolution.COMMIT, adapter.checker().check(view));
    }

    @Test void unavailable_primary_is_unknown_even_after_grace_period() {
        when(wallets.ledgerExists(event.bizNo())).thenThrow(new IllegalStateException("主库暂时不可读"));
        assertEquals(TransactionResolution.UNKNOWN,
                adapter.checker().check(message(System.currentTimeMillis() - 120_000)));
    }

    @Test void primary_recovery_after_grace_commits_the_preserved_half_message() {
        var view = message(System.currentTimeMillis() - 120_000);
        when(wallets.ledgerExists(event.bizNo()))
                .thenThrow(new IllegalStateException("主库暂时不可读"))
                .thenReturn(true);
        assertEquals(TransactionResolution.UNKNOWN, adapter.checker().check(view));
        assertEquals(TransactionResolution.COMMIT, adapter.checker().check(view));
    }

    @Test void local_error_keeps_half_message_and_preserves_the_original_error() {
        var failure = new AssertionError("提交结果未知的严重错误");
        assertSame(failure, assertThrows(AssertionError.class,
                () -> adapter.publishInTransaction(event, () -> { throw failure; })));
        verifyNoInteractions(transaction);
    }

    @Test void absent_ledger_after_grace_still_resolves_to_rollback() {
        when(wallets.ledgerExists(event.bizNo())).thenReturn(false);
        assertEquals(TransactionResolution.ROLLBACK,
                adapter.checker().check(message(System.currentTimeMillis() - 120_000)));
    }

    private MessageView message(long bornAt) {
        var message = mock(MessageView.class);
        when(message.getKeys()).thenReturn(List.of(event.bizNo()));
        when(message.getBornTimestamp()).thenReturn(bornAt);
        return message;
    }
}
