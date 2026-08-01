package io.maxxga.rowrelay.core

/**
 * HEL-167: the workflow algebra's **business routing** type — a value took the
 * [Left] path or the [Right] path. This is deliberately SEPARATE from execution
 * outcomes ([WorkflowOutcome]): a `Left` is a valid, successful workflow path
 * (e.g. "row failed validation → route to the reject sink"), NOT an error. A
 * plan that emits `Left` still *Completed*. Conflating the two is the mistake
 * this split exists to prevent.
 *
 * Pure and allocation-cheap; no coroutine, database, or executor types leak in.
 * The naming follows the established Left/Right functional convention so a
 * consumer already fluent in `Either` reads it without a manual.
 */
sealed interface Choice<out L, out R> {
    data class Left<out L>(val value: L) : Choice<L, Nothing>
    data class Right<out R>(val value: R) : Choice<Nothing, R>

    val isLeft: Boolean get() = this is Left
    val isRight: Boolean get() = this is Right

    companion object {
        fun <L> left(value: L): Choice<L, Nothing> = Left(value)
        fun <R> right(value: R): Choice<Nothing, R> = Right(value)
    }
}

/** Collapse both paths to a single value — the total eliminator. */
inline fun <L, R, T> Choice<L, R>.fold(onLeft: (L) -> T, onRight: (R) -> T): T =
    when (this) {
        is Choice.Left -> onLeft(value)
        is Choice.Right -> onRight(value)
    }

/** Transform the Right (by convention the "happy" / continue) path; Left passes through. */
inline fun <L, R, R2> Choice<L, R>.mapRight(f: (R) -> R2): Choice<L, R2> =
    when (this) {
        is Choice.Left -> this
        is Choice.Right -> Choice.Right(f(value))
    }

/** Transform the Left path; Right passes through. */
inline fun <L, R, L2> Choice<L, R>.mapLeft(f: (L) -> L2): Choice<L2, R> =
    when (this) {
        is Choice.Left -> Choice.Left(f(value))
        is Choice.Right -> this
    }

/** Transform both paths at once. */
inline fun <L, R, L2, R2> Choice<L, R>.bimap(l: (L) -> L2, r: (R) -> R2): Choice<L2, R2> =
    when (this) {
        is Choice.Left -> Choice.Left(l(value))
        is Choice.Right -> Choice.Right(r(value))
    }

/** The Right value or null (Left → null). For interop at effect boundaries. */
fun <L, R> Choice<L, R>.rightOrNull(): R? = (this as? Choice.Right)?.value

/** The Left value or null. */
fun <L, R> Choice<L, R>.leftOrNull(): L? = (this as? Choice.Left)?.value
