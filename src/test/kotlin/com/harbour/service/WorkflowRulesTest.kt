package com.harbour.service

import com.harbour.domain.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class WorkflowRulesTest {
    @Test fun `ticket transitions enumerate every reachable status`() {
        val reached=mutableSetOf(TicketStatus.OPEN);val queue=ArrayDeque<TicketStatus>();queue.add(TicketStatus.OPEN)
        while(queue.isNotEmpty()){TicketService.ALLOWED_TRANSITIONS.getValue(queue.removeFirst()).forEach{if(reached.add(it))queue.add(it)}}
        assertThat(reached).containsExactlyInAnyOrderElementsOf(TicketStatus.entries)
    }
    @Test fun `closed tickets have no outgoing transition`() { assertThat(TicketService.ALLOWED_TRANSITIONS.getValue(TicketStatus.CLOSED)).isEmpty() }
}
