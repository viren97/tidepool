package dev.tidepool.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class TidepoolTest {
    @Test
    fun `key adds the tidepool prefix`() {
        assertEquals("tidepool.dev/owner", Tidepool.key("owner"))
    }

    @Test
    fun `key rejects a blank name`() {
        assertThrows<IllegalArgumentException> { Tidepool.key(" ") }
    }
}
