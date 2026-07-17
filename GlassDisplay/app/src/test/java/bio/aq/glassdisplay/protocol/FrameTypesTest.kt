package bio.aq.glassdisplay.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameTypesTest {
    @Test
    fun hostCommand_ackMagicMatchesSwiftSenderWireValues() {
        assertEquals(0x52475031, WireProtocol.HostCommand.RESOLUTION_480X640_ACK)
        assertEquals(0x52474C31, WireProtocol.HostCommand.RESOLUTION_480X320_ACK)
        assertEquals(0x52474F31, WireProtocol.HostCommand.RESOLUTION_OFF_ACK)
        assertEquals(WireProtocol.HostCommand.RESOLUTION_480X640_ACK, HostCommand.Resolution480x640.ackMagic)
        assertEquals(WireProtocol.HostCommand.RESOLUTION_480X320_ACK, HostCommand.Resolution480x320.ackMagic)
        assertEquals(WireProtocol.HostCommand.RESOLUTION_OFF_ACK, HostCommand.ResolutionOff.ackMagic)
    }

    @Test
    fun hostCommand_transportAckMagicsMatchSwiftSenderWireValues() {
        assertEquals(0x52474130, WireProtocol.HostCommand.TRANSPORT_AUTO_ACK)
        assertEquals(0x52475731, WireProtocol.HostCommand.TRANSPORT_WIFI_ACK)
        assertEquals(0x52474231, WireProtocol.HostCommand.TRANSPORT_BLE_ACK)
        assertEquals(WireProtocol.HostCommand.TRANSPORT_AUTO_ACK, HostCommand.TransportAuto.ackMagic)
        assertEquals(WireProtocol.HostCommand.TRANSPORT_WIFI_ACK, HostCommand.TransportWifi.ackMagic)
        assertEquals(WireProtocol.HostCommand.TRANSPORT_BLE_ACK, HostCommand.TransportBle.ackMagic)
        assertEquals("auto", HostCommand.TransportAuto.label)
        assertEquals("wifi", HostCommand.TransportWifi.label)
        assertEquals("ble", HostCommand.TransportBle.label)
        assertNull(HostCommand.TransportAuto.expectedFrameSize)
        assertNull(HostCommand.TransportWifi.expectedFrameSize)
        assertNull(HostCommand.TransportBle.expectedFrameSize)
    }

    @Test
    fun hostCommand_exposesSpecs() {
        assertEquals(
            HostCommandSpec(
                ackMagic = WireProtocol.HostCommand.RESOLUTION_480X640_ACK,
                label = "480x640",
                expectedFrameSize = FrameSize.ROKID_PORTRAIT
            ),
            HostCommand.Resolution480x640.spec
        )
        assertEquals("480x640", HostCommand.Resolution480x640.label)
        assertEquals(FrameSize.ROKID_PORTRAIT, HostCommand.Resolution480x640.expectedFrameSize)

        assertEquals("480x320", HostCommand.Resolution480x320.label)
        assertEquals(FrameSize.ROKID_LANDSCAPE, HostCommand.Resolution480x320.expectedFrameSize)

        assertEquals("off", HostCommand.ResolutionOff.label)
        assertNull(HostCommand.ResolutionOff.expectedFrameSize)
    }
}
