package gov.cdc.prime.router.azure

import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class EventTest {
    @Test
    fun `test reportEvent encode and decode`() {
        val event = ReportEvent(Event.EventAction.SEND, UUID.randomUUID())
        val message = event.toQueueMessage()
        val returnEvent = Event.parseQueueMessage(message)
        assertEquals(event, returnEvent)
    }

    @Test
    fun `test reportEvent encode and decode with time`() {
        val event = ReportEvent(Event.EventAction.SEND, UUID.randomUUID(), OffsetDateTime.now())
        val message = event.toQueueMessage()
        val returnEvent = Event.parseQueueMessage(message)
        assertEquals(event, returnEvent)
    }

    @Test
    fun `test receiverEvent encode and decode`() {
        val event = ReceiverEvent(Event.EventAction.SEND, "test")
        val message = event.toQueueMessage()
        val returnEvent = Event.parseQueueMessage(message)
        assertEquals(event, returnEvent)
    }

    @Test
    fun `test receiverEvent encode and decode with time`() {
        val event = ReceiverEvent(Event.EventAction.SEND, "test", OffsetDateTime.now())
        val message = event.toQueueMessage()
        val returnEvent = Event.parseQueueMessage(message)
        assertEquals(event, returnEvent)
    }
}