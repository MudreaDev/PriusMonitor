package org.betsy.vag

import org.betsy.elm.NoDataException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VagProtocolTest {
    @Test fun responseMustMatchRequestedServiceAndPid() {
        assertEquals("80", VagProtocol.payload("01 5C\r41 5C 80\r>", "015C", "415C"))
        assertThrows(NoDataException::class.java) { VagProtocol.payload("410580", "015C", "415C") }
        assertThrows(NoDataException::class.java) { VagProtocol.payload("7F2231", "22179C", "62179C") }
        assertThrows(NoDataException::class.java) { VagProtocol.payload("NO DATA", "015C", "415C") }
    }

    @Test fun multiFrameAndCountedDtcRepliesAreValidated() {
        val data = VagProtocol.payload("00B\r0:5902FF123456\r1:08ABCDEF01\r>", "1902FF", "5902")
        assertEquals(listOf("123456 · status 08", "ABCDEF · status 01"), VagProtocol.udsDtcs(data))
        assertEquals(listOf("P0133", "U0100"), VagProtocol.dtcs("020133C100"))
        assertEquals(emptyList<String>(), VagProtocol.dtcs("00"))
        assertThrows(IllegalArgumentException::class.java) { VagProtocol.dtcs("020133") }
        assertThrows(IllegalArgumentException::class.java) { VagProtocol.udsDtcs("FF1234") }
        assertThrows(NoDataException::class.java) { VagProtocol.payload("00B\r0:5902FF123456", "1902FF", "5902") }
        assertThrows(NoDataException::class.java) { VagProtocol.payload("00B\r0:5902FF123456\r2:08ABCDEF01", "1902FF", "5902") }
    }

    @Test fun discoveryUsesEveryBitAndCorrectPage() {
        assertEquals(setOf(0x41, 0x5C, 0x60), VagProtocol.supported("80000011", 0x40))
        assertThrows(IllegalArgumentException::class.java) { VagProtocol.supported("8000", 0) }
    }

    private fun signal(format: String) = VagSignal("test", "test", "", JSONObject(format))

    @Test fun signedEndianAndBitFieldsAreDecodedWithoutInventingMissingValues() {
        assertEquals(-1.0, signal("{len:16,sign:true,blsb:true}").decode("FFFF")!!, 0.001)
        assertEquals(258.0, signal("{len:16,blsb:true}").decode("0201")!!, 0.001)
        assertEquals(5.0, signal("{bix:3,len:3}").decode("14")!!, 0.001)
        assertNull(signal("{len:16}").decode("01"))
        assertNull(signal("{len:16,div:10,add:-273.1,nullmin:-273.1}").decode("0000"))
        assertNull(signal("{len:8,nullmax:100}").decode("64"))
        assertNull(signal("{len:8,max:100}").decode("FF"))
    }

    @Test fun oilTemperatureAndDieselFuelRateUseDifferentScales() {
        val oil =
            VagCatalog.standard
                .single { it.command == "015C" }
                .signals
                .single()
        assertEquals(90.0, oil.decode("82")!!, 0.001)
        val fuel =
            VagCatalog.standard
                .single { it.command == "015E" }
                .signals
                .single()
        assertEquals(2.0, fuel.decode("0028")!!, 0.001)
    }

    @Test fun pinnedPassatDatasetLoadsForAllB8YearsAndContainsOilAndDpf() {
        val data = File("src/main/assets/vag/passat.json").readText()
        for (year in 2014..2023) {
            val queries = VagCatalog.passat(data, year)
            assertTrue(queries.size > 100)
            assertEquals(queries.size, queries.map { it.key }.distinct().size)
            assertTrue(queries.any { it.key == "7E0/22179C" })
            assertTrue(queries.any { it.key == "7E0/221ABA" })
            assertTrue(queries.all { it.command.startsWith("22") })
        }
    }
}
