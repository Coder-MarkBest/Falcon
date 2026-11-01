package com.falcon.capability

import org.junit.Assert.*
import org.junit.Test

class ToolContractTest {
    @Test fun `reject unknown fields temperatures and string numbers`() {
        val input = VehicleTools.byName.getValue("climate_set_temperature").input
        val valid = obj("zone" to str("driver"), "targetTemperatureC" to num(23), "expectedStateVersion" to num(1))
        input.validate(valid, "args")
        listOf(obj("zone" to str("driver")),
            kotlinx.serialization.json.JsonObject(valid + ("confirmed" to bool(true))),
            kotlinx.serialization.json.JsonObject(valid + ("targetTemperatureC" to str("23"))),
            kotlinx.serialization.json.JsonObject(valid + ("targetTemperatureC" to num(100))))
            .forEach { value -> assertThrows(ToolException::class.java) { input.validate(value, "args") } }
    }
    @Test fun `descriptors are unique and all writes declare scopes`() {
        assertEquals(13, VehicleTools.byName.size)
        assertTrue(VehicleTools.all.filterNot { it.readOnly }.all { it.scope.endsWith(".write") })
        VehicleTools.all.forEach { assertNotNull(it.descriptor()["outputSchema"]) }
    }
}
