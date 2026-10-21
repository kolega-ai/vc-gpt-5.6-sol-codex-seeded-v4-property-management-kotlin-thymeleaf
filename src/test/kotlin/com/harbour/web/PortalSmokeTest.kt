package com.harbour.web

import com.harbour.domain.Role
import com.harbour.repo.TicketRepository
import com.harbour.repo.LeaseRepository
import com.harbour.repo.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.security.test.context.support.WithUserDetails
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class PortalSmokeTest(@Autowired private val mvc:MockMvc,@Autowired private val tickets:TicketRepository,@Autowired private val leases:LeaseRepository,@Autowired private val users:UserRepository){
    @Test @WithUserDetails("admin@harbour.local")
    fun `administrator screens render`(){
        listOf("/dashboard","/tickets","/leases","/payments","/documents","/inspections","/messages","/profile","/notifications","/admin/users","/admin/resources","/admin/import","/admin/audit").forEach{path->mvc.get(path).andExpect{status{isOk()}}}
    }
    @Test @WithUserDetails("tenant@harbour.local")
    fun `tenant screens render and admin is denied`(){
        listOf("/dashboard","/tickets","/tickets/new","/leases","/payments","/documents","/inspections","/messages","/profile").forEach{path->mvc.get(path).andExpect{status{isOk()}}}
        mvc.get("/admin/users").andExpect{status{isForbidden()}}
    }
    @Test @WithUserDetails("worker@harbour.local")
    fun `worker cannot browse an unassigned ticket`(){
        val unassigned=tickets.findAll().first{it.assignedWorkerId==null}
        mvc.get("/api/tickets/${unassigned.id}/status").andExpect{status{isForbidden()}}
        listOf("/leases","/payments","/inspections","/messages").forEach{mvc.get(it).andExpect{status{isForbidden()}}}
    }
    @Test @WithUserDetails("tenant@harbour.local")
    fun `tenant cannot read another residents lease or ticket`(){val tenant=users.findByEmailIgnoreCase("tenant@harbour.local")?:error("seed tenant missing");val otherLease=leases.findAll().first{it.tenantId!=tenant.id};val otherTicket=tickets.findAll().first{it.tenantId!=tenant.id};mvc.get("/leases/${otherLease.id}").andExpect{status{isForbidden()}};mvc.get("/tickets/${otherTicket.id}").andExpect{status{isForbidden()}}}
}
