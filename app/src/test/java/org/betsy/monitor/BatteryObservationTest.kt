package org.betsy.monitor

import org.betsy.model.BatteryModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryObservationTest {
    @Test fun integratesAbsoluteFlowUsingMonotonicTimeWithoutInventingCapacity() {
        val observation = BatteryObservation()
        val model = BatteryModel(soc = 60f, currentAmps = 10f, blockVolts = List(14) { 15f })
        observation.start(false)
        observation.add(1000, model, 0)
        // Wall clock jumps by a day; only 10 seconds of monotonic time have elapsed.
        observation.add(86401000, model.copy(currentAmps = -10f), 10000)
        assertEquals(5.0 * 10 / 3600, observation.absoluteAh!!, 0.000001)
        assertEquals(1.05 * 10 / 3600, observation.absoluteKwh!!, 0.000001)
        assertEquals(10.0, observation.powerCoveredSeconds, 0.0)
        observation.add(86411000, model.copy(currentAmps = Float.NaN), 20000)
        observation.add(86421000, model, 30000)
        assertEquals(10.0, observation.currentCoveredSeconds, 0.0)
        observation.add(86500000, model, 60000)
        assertEquals(10.0, observation.currentCoveredSeconds, 0.0)
        assertTrue(observation.report().contains("nu sunt consum net, capacitate sau SOH"))
    }

    @Test fun missingBlocksKeepTheirIndicesAndEmptyReadingsDoNotCountAsObservations() {
        val observation = BatteryObservation()
        observation.start(true)
        val missing = BatteryModel(soc = Float.NaN, currentAmps = Float.NaN)
        observation.add(0, missing)
        assertEquals(0, observation.count)
        observation.add(1000, missing.copy(blockVolts = listOf(Float.NaN, 15f)))
        assertEquals(1, observation.count)
        assertEquals(null, observation.blockRanges[0].min)
        assertEquals(15f, observation.blockRanges[1].min!!, 0f)
        assertEquals(null, observation.maxDelta)
        assertEquals(null, observation.absoluteKwh)
        observation.start(false)
        assertEquals(0, observation.count)
        assertTrue(observation.blockRanges.all { it.min == null && it.max == null && it.samples == 0 })
    }

    @Test fun zeroCrossingsUseTheAreaOfBothTriangles() {
        assertEquals(5.0, BatteryObservation.absoluteLinearAverage(-10.0, 10.0), 0.0)
        assertEquals(25.0 / 6.0, BatteryObservation.absoluteLinearAverage(-10.0, 5.0), 0.000001)
        assertEquals(0.0, BatteryObservation.absoluteLinearAverage(0.0, 0.0), 0.0)
        assertEquals(15.0, BatteryObservation.absoluteLinearAverage(-10.0, -20.0), 0.0)
    }

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
