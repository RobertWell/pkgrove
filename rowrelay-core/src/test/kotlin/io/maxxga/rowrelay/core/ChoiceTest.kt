package io.maxxga.rowrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** HEL-167: the business-routing algebra. Left is a valid path, not a failure —
 *  kept separate from execution outcomes. */
class ChoiceTest {

    @Test
    fun `fold collapses both paths`() {
        val l: Choice<String, Int> = Choice.left("bad")
        val r: Choice<String, Int> = Choice.right(42)
        assertEquals("reject:bad", l.fold({ "reject:$it" }, { "ok:$it" }))
        assertEquals("ok:42", r.fold({ "reject:$it" }, { "ok:$it" }))
    }

    @Test
    fun `mapRight and mapLeft only touch their own path`() {
        val r: Choice<String, Int> = Choice.right(10)
        assertEquals(Choice.Right(20), r.mapRight { it * 2 })
        assertEquals(Choice.Right(10), r.mapLeft { "x$it" })   // Right untouched by mapLeft

        val l: Choice<String, Int> = Choice.left("e")
        assertEquals(Choice.Left("E"), l.mapLeft { it.uppercase() })
        assertEquals(Choice.Left("e"), l.mapRight { it * 2 })  // Left untouched by mapRight
    }

    @Test
    fun `bimap transforms both`() {
        assertEquals(Choice.Left(1), (Choice.left("x") as Choice<String, Int>).bimap({ it.length }, { it + 1 }))
        assertEquals(Choice.Right(6), (Choice.right(5) as Choice<String, Int>).bimap({ it.length }, { it + 1 }))
    }

    @Test
    fun `isLeft isRight and orNull accessors`() {
        val l: Choice<String, Int> = Choice.left("e")
        val r: Choice<String, Int> = Choice.right(7)
        assertTrue(l.isLeft && !l.isRight)
        assertTrue(r.isRight && !r.isLeft)
        assertEquals(7, r.rightOrNull()); assertNull(r.leftOrNull())
        assertEquals("e", l.leftOrNull()); assertNull(l.rightOrNull())
    }
}
