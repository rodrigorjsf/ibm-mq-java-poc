package com.example.ibmmq.messaging;

/**
 * Domain-level persistence mode for an outbound message, crossing the messaging seam in place of
 * {@code jakarta.jms.DeliveryMode} (ADR-0008: no {@code jakarta.jms} type may leak to a port caller).
 *
 * <p>The pooled-JMS adapter maps each value to the corresponding {@code jakarta.jms.DeliveryMode}
 * constant; the in-memory fake carries it through verbatim. Persistent is the project default —
 * the business message (and, by inheritance, its COA/COD reports) survive a QMgr restart.</p>
 */
public enum DeliveryPersistence {
    /** Survives a QMgr restart; maps to {@code jakarta.jms.DeliveryMode.PERSISTENT}. */
    PERSISTENT,
    /** Lost on a QMgr restart; maps to {@code jakarta.jms.DeliveryMode.NON_PERSISTENT}. */
    NON_PERSISTENT
}
