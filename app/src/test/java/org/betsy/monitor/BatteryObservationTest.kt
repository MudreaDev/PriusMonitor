package org.betsy.monitor

import org.betsy.model.BatteryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryObservationTest {
    @Test fun observationsAreMeasuredIndicatorsNotASohPercentage() {
        val observation = BatteryObservation()
        val model = BatteryModel(soc = 60f, currentAmps = -15f, blockVolts = listOf(15f, 15.1f), temps = listOf(30f))
        observation.add(0, model)
        assertEquals(0, observation.count)
        observation.start(false)
        observation.add(1000, model)
        observation.add(2000, model.copy(soc = 58f, currentAmps = 25f, temps = listOf(32f)))
        assertEquals(2, observation.count)
        assertEquals(.1f, observation.maxDelta!!, .0001f)
        assertEquals(58f, observation.minSoc!!, .0001f)
        assertEquals(60f, observation.maxSoc!!, .0001f)
        assertEquals(32f, observation.hottest!!, .0001f)
        assertEquals(25f, observation.maxAbsCurrent!!, .0001f)
        assertTrue(observation.report().contains("SOH: INDISPONIBIL"))
        assertFalse(observation.demo)
        observation.stop()
        observation.add(3000, model)
        assertEquals(2, observation.count)
    }
}
