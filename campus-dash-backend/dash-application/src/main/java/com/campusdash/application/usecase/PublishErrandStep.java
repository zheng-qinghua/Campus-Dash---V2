package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;

import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发布任务用例：一个本地事务内完成资金托管与任务发布。
 *
 * 这是模块化单体最大的红利——托管扣款、复式记账、托管单、任务状态
 * 全在同一个数据库事务里，不需要任何分布式事务。
 * 拆微服务后这一段就必须改成 Seata TCC 或 Saga 补偿（见架构文档第 12 节）。
 *
 * 事务边界画在应用层而不是领域层：领域对象只负责业务规则，
 * 不知道"事务"这个技术概念的存在。
 */
@Service
public class PublishErrandStep {

    private static final long ESCROW_ACCOUNT_OWNER = -1L;

    private final ErrandRepository errandRepository;
    private final WalletRepository walletRepository;
    private final SnowflakeIdGenerator idGenerator;

    public PublishErrandStep(ErrandRepository errandRepository,
                                WalletRepository walletRepository,
                                SnowflakeIdGenerator idGenerator) {
        this.errandRepository = errandRepository;
        this.walletRepository = walletRepository;
        this.idGenerator = idGenerator;
    }



    /**
     * rollbackFor 必须显式写 Exception.class：
     * Spring 默认只对 RuntimeException 回滚，checked 异常不会触发回滚，
     * 这是 @Transactional 六种失效场景里最常见的一种。
     */
    @Transactional(rollbackFor = Exception.class)
    public PublishErrandUseCase.Result publish(PublishErrandUseCase.Command cmd) {
        if (cmd.slotTotal() != 1) throw new IllegalArgumentException("当前任务仅支持一名跑腿");
        Money reward = Money.ofCents(cmd.rewardCents());

        // 幂等前置检查：一个任务只能托管一次。
        // 真正的并发安全靠 escrow_order.uk_errand 与 wallet_ledger.uk_biz_direction 唯一索引，
        // 这里的检查只是为了给出更友好的错误信息。
        long errandId = idGenerator.nextId();

        WalletAccount publisherAccount = walletRepository
                .findByOwner(cmd.publisherId(), AccountType.USER)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "publisher=" + cmd.publisherId()));
        WalletAccount escrowAccount = walletRepository
                .findByOwner(ESCROW_ACCOUNT_OWNER, AccountType.ESCROW)
                .orElseThrow(() -> new BizException(ErrorCode.ACCOUNT_NOT_FOUND, "escrow account missing"));

        // 1. 冻结发单人余额：带条件 CAS，affectedRows=0 即余额不足或并发冲突
        int frozen = walletRepository.casDebit(publisherAccount.id(), reward);
        if (frozen == 0) {
            throw new BizException(ErrorCode.INSUFFICIENT_BALANCE,
                    "publisher=" + cmd.publisherId() + " required=" + reward);
        }

        // 2. 复式记账：发单人账户借记，托管账户贷记，金额相等
        String bizNo = LedgerEntry.escrowBizNo(errandId);
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, publisherAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.DEBIT, reward,
                publisherAccount.available().minus(reward), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.insertLedger(new LedgerEntry(
                idGenerator.nextId(), bizNo, escrowAccount.id(), cmd.publisherId(),
                LedgerEntry.Direction.CREDIT, reward,
                escrowAccount.available().plus(reward), LedgerEntry.RefType.ESCROW, errandId));
        walletRepository.casCredit(escrowAccount.id(), reward);

        // 3. 托管单置 HELD
        walletRepository.insertEscrow(EscrowOrder.held(
                idGenerator.nextId(), cmd.campusId(), errandId, cmd.publisherId(), reward));

        // 4. 任务落库并发布。先 DRAFT 再 CAS 到 PUBLISHED，
        //    这样状态机的 DRAFT -> PUBLISHED 流转在数据库里也有真实痕迹
        Errand errand = Errand.draft(errandId, cmd.campusId(), cmd.publisherId(),
                cmd.type(), cmd.title(), reward, cmd.slotTotal());
        errandRepository.insert(errand);

        long versionBeforePublish = errand.version();
        errand.publish(versionBeforePublish);
        int published = errandRepository.casPublish(errandId, versionBeforePublish);
        if (published == 0) {
            throw new BizException(ErrorCode.STALE_VERSION, "publish failed, errandId=" + errandId);
        }
        errandRepository.appendStatusLog(errandId, ErrandStatus.DRAFT, ErrandStatus.PUBLISHED,
                0, cmd.publisherId());

        return new PublishErrandUseCase.Result(errandId, ErrandStatus.PUBLISHED, reward.cents());
    }
}
