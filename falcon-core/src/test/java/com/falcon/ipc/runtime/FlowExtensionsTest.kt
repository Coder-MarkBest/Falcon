package com.falcon.ipc.runtime

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FlowExtensionsTest {

    @Test
    fun `throttle limits emission rate`() = runTest {
        val source = flow {
            emit(1)
            delay(10)
            emit(2)
            delay(10)
            emit(3)
            delay(200)
            emit(4)
        }

        val result = source.throttle(100).toList()
        // First emit passes, 2 and 3 are throttled, 4 passes after 200ms delay
        assertTrue(result.size <= 3)
        assertTrue(result.contains(1))
        assertTrue(result.contains(4))
    }

    @Test
    fun `throttle passes first value immediately`() = runTest {
        val source = flow {
            emit("a")
            delay(5)
            emit("b")
        }

        val result = source.throttle(50).toList()
        assertEquals("a", result.first())
    }

    @Test
    fun `debounce waits for silence`() = runTest {
        val source = flow {
            emit(1)
            delay(10)
            emit(2)
            delay(10)
            emit(3)
            delay(200) // silence > timeout
            emit(4)
        }

        val result = source.debounce(100).toList()
        // 1,2,3 are within debounce window; 3 is emitted after 100ms silence; 4 after another silence
        assertEquals(2, result.size)
        assertTrue(result.contains(3))
        assertTrue(result.contains(4))
    }

    @Test
    fun `IpcEvent Data holds value`() {
        val event: IpcEvent<String> = IpcEvent.Data("hello")
        assertTrue(event is IpcEvent.Data)
        assertEquals("hello", (event as IpcEvent.Data).value)
    }

    @Test
    fun `IpcEvent Disconnected is singleton`() {
        val e1 = IpcEvent.Disconnected
        val e2 = IpcEvent.Disconnected
        assertSame(e1, e2)
    }

    @Test
    fun `IpcEvent Reconnected is singleton`() {
        val e1 = IpcEvent.Reconnected
        val e2 = IpcEvent.Reconnected
        assertSame(e1, e2)
    }
}
