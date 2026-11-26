package app.focusfence.server

import kotlinx.coroutines.runBlocking
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class EmailTest {
    /** A minimal SMTP server: enough of the protocol for one plain (FF_SMTP_TLS=none) message. */
    private fun fakeSmtp(received: StringBuilder): Int {
        val ss = ServerSocket(0)
        thread(isDaemon = true) {
            ss.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                val w = s.getOutputStream().bufferedWriter()
                fun say(l: String) { w.write(l + "\r\n"); w.flush() }
                say("220 test")
                var data = false
                while (true) {
                    val line = r.readLine() ?: break
                    if (data) {
                        if (line == ".") { data = false; say("250 queued") } else received.append(line).append('\n')
                        continue
                    }
                    when {
                        line.startsWith("EHLO") || line.startsWith("HELO") -> say("250 test")
                        line.startsWith("MAIL") || line.startsWith("RCPT") -> { received.append(line).append('\n'); say("250 ok") }
                        line == "DATA" -> { data = true; say("354 go") }
                        line == "QUIT" -> { say("221 bye"); break }
                        else -> say("250 ok")
                    }
                }
            }
            ss.close()
        }
        return ss.localPort
    }

    @Test
    fun withoutResendItSendsOverSmtp() {
        val got = StringBuilder()
        val port = fakeSmtp(got)
        val env = mapOf("FF_SMTP_HOST" to "127.0.0.1", "FF_SMTP_PORT" to "$port", "FF_SMTP_TLS" to "none")
        val n = LiveNotifier(null, "Focus Fence <ff@example.com>") { env[it] }
        assertTrue(runBlocking { n.email("sam@example.com", "Focus Fence: hello", "<p>Report ready</p>") })
        val text = got.toString()
        assertTrue(text.contains("RCPT TO:<sam@example.com>"), text)
        assertTrue(text.contains("Subject: Focus Fence: hello"), text)
        assertTrue(text.contains("Report ready"), text)
    }

    @Test
    fun aDeadSmtpServerIsAFailureNotAnException() {
        val dead = ServerSocket(0).use { it.localPort }
        val env = mapOf("FF_SMTP_HOST" to "127.0.0.1", "FF_SMTP_PORT" to "$dead", "FF_SMTP_TLS" to "none")
        val n = LiveNotifier(null, "ff@example.com") { env[it] }
        assertTrue(!runBlocking { n.email("sam@example.com", "x", "y") })
    }
}
