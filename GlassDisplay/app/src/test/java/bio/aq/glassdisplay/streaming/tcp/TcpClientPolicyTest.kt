package bio.aq.glassdisplay.streaming.tcp

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpClientPolicyTest {
    @Test
    fun loopbackConnectionsAreExemptFromAuthenticationTimeout() {
        assertFalse(
            TcpClientPolicy.requiresAuthenticationTimeout(InetAddress.getByName("127.0.0.1"))
        )
        assertFalse(
            TcpClientPolicy.requiresAuthenticationTimeout(InetAddress.getByName("::1"))
        )
    }

    @Test
    fun networkAndUnknownConnectionsRequireAuthenticationTimeout() {
        assertTrue(
            TcpClientPolicy.requiresAuthenticationTimeout(InetAddress.getByName("192.0.2.10"))
        )
        assertTrue(TcpClientPolicy.requiresAuthenticationTimeout(null))
    }
}
