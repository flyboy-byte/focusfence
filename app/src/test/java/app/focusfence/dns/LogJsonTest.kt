package app.focusfence.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LogJsonTest {

    @Test
    fun roundTripsAllValueKinds() {
        val fields = linkedMapOf<String, Any?>(
            "t" to 1731600000000L, "type" to "dns", "n" to 3, "f" to 1.5,
            "ok" to true, "no" to false, "x" to null,
        )
        val line = LogJson.encode(fields)
        assertEquals(
            """{"t":1731600000000,"type":"dns","n":3,"f":1.5,"ok":true,"no":false,"x":null}""",
            line,
        )
        val back = LogJson.decode(line)!!
        assertEquals(1731600000000L, back["t"])
        assertEquals(3L, back["n"]) // ints come back as Long
        assertEquals(1.5, back["f"])
        assertEquals(true, back["ok"])
        assertNull(back["x"])
    }

    @Test
    fun escapesHostileStrings() {
        val nasty = "a\"b\\c\nd\te\u0001f/ü"
        val line = LogJson.encode(mapOf("s" to nasty))
        assertEquals(false, line.contains('\n')) // must stay one line
        assertEquals(nasty, LogJson.decode(line)!!["s"])
    }

    @Test
    fun rejectsMalformedAndNestedLines() {
        assertNull(LogJson.decode(""))
        assertNull(LogJson.decode("{\"t\":1")) // torn write
        assertNull(LogJson.decode("{\"a\":{\"b\":1}}"))
        assertNull(LogJson.decode("{\"a\":[1]}"))
        assertNull(LogJson.decode("{\"a\":1} trailing"))
        assertEquals(emptyMap<String, Any?>(), LogJson.decode("{}"))
    }

    @Test
    fun peekTFindsTimestampWithoutDecoding() {
        val line = LogJson.encode(linkedMapOf("seq" to 9L, "t" to 1731600000123L, "host" to "a\"t\":5.com"))
        assertEquals(1731600000123L, LogJson.peekT(line))
        // A string value that looks like a t field is escaped, so it can't be mistaken.
        assertEquals(null, LogJson.peekT(LogJson.encode(mapOf("x" to "\"t\":5"))))
        assertNull(LogJson.peekT("garbage"))
    }

    @Test
    fun nonFiniteNumbersBecomeNull() {
        assertEquals("""{"x":null}""", LogJson.encode(mapOf("x" to Double.NaN)))
    }
}
