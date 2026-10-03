package com.github.h3yow.loanservicingjava.domain.loan;

import com.github.h3yow.loanservicingjava.domain.money.Money;

import java.time.LocalDate;

/**
 * 台账重放操作（撤销时用于重建“某笔还款从未发生”的账本）。
 * 只能描述真实落过账的两类现金动作，均来自已追加的历史事件流水，不允许撤销逻辑凭空构造：
 * <ul>
 *   <li>{@link Payment}：一笔外部现金还款（按到期口径分摊，超额自动挂账）；</li>
 *   <li>{@link SuspenseSweep}：用既有挂账余额冲抵应还（不引入新现金）。</li>
 * </ul>
 */
public sealed interface LedgerOperation permits LedgerOperation.Payment, LedgerOperation.SuspenseSweep {

    String requestId();

    LocalDate asOf();

    /** 外部现金还款。 */
    record Payment(String requestId, LocalDate asOf, Money amount) implements LedgerOperation {
    }

    /** 挂账冲抵：使用当时挂账余额（重放时按账本实时余额取用，不携带固定金额）。 */
    record SuspenseSweep(String requestId, LocalDate asOf) implements LedgerOperation {
    }
}
