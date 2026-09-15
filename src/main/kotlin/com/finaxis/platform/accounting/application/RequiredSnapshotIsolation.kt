package com.finaxis.platform.accounting.application

/**
 * The weakest isolation an operation will accept, ordered by an explicit rank.
 *
 * [SnapshotIsolationGuard] serves two questions that look alike and are not. A read pair asks
 * *"does this transaction hold one snapshot for its whole life"*, which `REPEATABLE READ` already
 * answers. The posting path asks *"is this the isolation the posting decision was made at"*, and
 * only `SERIALIZABLE` answers that: a posting that joined a `REPEATABLE READ` transaction holds a
 * perfectly stable snapshot while running at a level its owner did not choose. Naming the minimum
 * at the call site keeps both questions on one port without letting the weaker one certify the
 * stronger caller.
 *
 * [rank] is a declared field and never `ordinal`, because the comparison in the adapter is `<`.
 * An `ordinal`-based guard keeps throwing on the obvious cases - `READ COMMITTED` is absent from
 * the adapter's table either way - so a reordering of these constants, or one inserted between
 * them, would invert the ordering silently and every test that only exercises the obvious refusal
 * would still pass. A number written next to the constant it belongs to has to be edited
 * deliberately.
 *
 * Spring's own `Isolation` was rejected for the same reason: it is already imported in this layer,
 * but `Isolation.DEFAULT.value()` is `-1` against the other constants' ascending JDBC values, so
 * the port would accept an argument that has no meaning under an ordering comparison.
 */
enum class RequiredSnapshotIsolation(
    internal val rank: Int,
) {
    /** One snapshot for the transaction's whole life; enough for a read pair. */
    REPEATABLE_READ(1),

    /** That, plus serializable execution among serializable transactions. */
    SERIALIZABLE(2),
}
