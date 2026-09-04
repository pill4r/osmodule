package dev.pillar.osmodule.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Xtra Edge Pro is a rebadged Osmo Action 5 Pro and advertises the **same** model id `0x0015`.
 * These tests pin the inherited profile split while keeping both models explicitly unverified in
 * this project.
 */
class CameraModelBrandTest {

    @Test
    fun `xtra oui is detected as the xtra brand`() {
        assertEquals(Brand.XTRA, Brand.of("EC:9E:EA:11:22:33", "XtraEdgePro-C2D8"))
        // Even a DJI-looking name loses to the hardware OUI.
        assertEquals(Brand.XTRA, Brand.of("EC:9E:EA:11:22:33", "OsmoAction5Pro-1234"))
    }

    @Test
    fun `dji unit is detected as dji`() {
        assertEquals(Brand.DJI, Brand.of("34:12:78:AA:BB:CC", "OsmoAction5Pro-1234"))
    }

    @Test
    fun `same model id resolves differently per brand`() {
        val xtra = CameraModel.resolve(0x0015, "XtraEdgePro-C2D8", Brand.XTRA)
        val dji = CameraModel.resolve(0x0015, "OsmoAction5Pro-1234", Brand.DJI)

        // Xtra: preserve the inherited rebrand quirk without claiming project hardware coverage.
        assertEquals("Xtra Edge Pro", xtra.name)
        assertEquals(10004, xtra.datalinkPort)
        assertFalse(xtra.tcpPoke)
        assertFalse(xtra.verified)

        // Genuine DJI: preserve its inherited DJI-standard config, also unverified here.
        assertEquals("Osmo Action 5 Pro", dji.name)
        assertEquals(9004, dji.datalinkPort)
        assertTrue(dji.tcpPoke)
        assertFalse(dji.verified)
    }

    @Test
    fun `alternate flips the datalink config both ways`() {
        val dji = CameraModel.resolve(0x0015, "OsmoAction5Pro-1234", Brand.DJI)
        val alt = dji.alternate()
        assertEquals(10004, alt.datalinkPort)
        assertFalse(alt.tcpPoke)
        assertFalse(alt.verified)

        val back = alt.alternate()
        assertEquals(9004, back.datalinkPort)
        assertTrue(back.tcpPoke)
    }

    @Test
    fun `unbranded models keep the dji default`() {
        val nano = CameraModel.resolve(0x0019, "OsmoNano-C2D8", Brand.DJI)
        assertEquals(9004, nano.datalinkPort)
        assertTrue(nano.tcpPoke)
        assertFalse(nano.verified)
    }

    @Test
    fun `osmo 360 uses its hardware verified wpa2 transport`() {
        val camera = CameraModel.resolve(0x0017, "Osmo360-F985", Brand.DJI)

        assertEquals("Osmo 360", camera.name)
        assertFalse("the real AP is WPA2, not the previously inferred WPA3", camera.wpa3)
        assertEquals(9004, camera.datalinkPort)
        assertTrue(camera.tcpPoke)
        assertTrue(camera.verified)
    }

    @Test
    fun `only this project's two tested models are marked verified`() {
        val tested = listOf(0x0017, 0x0022).map { CameraModel.resolve(it, null, Brand.DJI) }
        assertTrue(tested.all(CameraModel::verified))

        val compatibilityOnly = listOf(
            0x0010, 0x0012, 0x0014, 0x0015, 0x0018, 0x0019, 0x0020, 0x0021, 0x0070, 0x007e,
        ).map { CameraModel.resolve(it, null, Brand.DJI) }
        assertTrue(compatibilityOnly.none(CameraModel::verified))
    }

    @Test
    fun `the dji company id makes a renamed drone resolve to DJI, not UNKNOWN`() {
        // A Mavic 3 renamed "1001": no DJI keyword in the name, non-DJI-list OUI — only the cid gives
        // it away. Without the cid it's UNKNOWN; with it, DJI.
        assertEquals(Brand.UNKNOWN, Brand.of("34:D2:62:C4:52:D5", "1001", djiCid = false))
        assertEquals(Brand.DJI, Brand.of("34:D2:62:C4:52:D5", "1001", djiCid = true))
    }

    @Test
    fun `drones resolve as drones and pair with the DJI FLY token`() {
        val mavic = CameraModel.resolve(0x0070, "1001", Brand.DJI)
        assertEquals("Mavic 3", mavic.name)
        assertTrue(mavic.isDrone)
        assertEquals("DJI FLY", mavic.pairingToken)

        val neo = CameraModel.resolve(0x007e, "DJI-NEO2-168D", Brand.DJI)
        assertTrue(neo.isDrone)
        assertEquals("DJI FLY", neo.pairingToken)
    }

    @Test
    fun `cameras stay cameras and pair with the osmo token`() {
        val nano = CameraModel.resolve(0x0019, "OsmoNano-C2D8", Brand.DJI)
        assertFalse(nano.isDrone)
        assertEquals("osmo", nano.pairingToken)
    }

    @Test
    fun `the xtra rebrand still wins over the dji company id`() {
        // The Xtra carries cid 0x08AA too, but its own OUI must keep it XTRA (it needs port 10004).
        assertEquals(Brand.XTRA, Brand.of("EC:9E:EA:00:00:01", "XtraEdgePro-2DCA", djiCid = true))
    }

    @Test
    fun `an unknown model in the aircraft range gets drone defaults, not camera ones`() {
        // A Mini 3 (or anything else we have not met) must not be handed the camera config: a drone
        // releases WiFi credentials only for the "DJI FLY" token, so guessing "camera" fails before it
        // reaches the network and the log says nothing useful.
        val unknown = CameraModel.resolve(0x0075, "Mini 3", Brand.DJI)
        assertTrue("treated as a drone", unknown.isDrone)
        assertEquals("DJI FLY", unknown.pairingToken)
        assertEquals(9003, unknown.datalinkPort)
        assertFalse("and never claimed as verified", unknown.verified)
    }

    @Test
    fun `an unknown model in the camera range still gets camera defaults`() {
        val unknown = CameraModel.resolve(0x0016, "Osmo Whatever", Brand.DJI)
        assertFalse(unknown.isDrone)
        assertEquals("osmo", unknown.pairingToken)
    }

}
