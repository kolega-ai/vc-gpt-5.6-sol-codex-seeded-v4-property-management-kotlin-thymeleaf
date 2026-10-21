package com.harbour.repo

import com.harbour.domain.*
import com.harbour.domain.Unit as PropertyUnit
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface UserRepository : JpaRepository<AppUser, UUID> { fun findByEmailIgnoreCase(email: String): AppUser?; fun findAllByRole(role: Role): List<AppUser> }
interface PropertyRepository : JpaRepository<Property, UUID> { fun findAllByManagerId(managerId: UUID): List<Property>; fun findByBuildingCode(code: String): Property? }
interface UnitRepository : JpaRepository<PropertyUnit, UUID> { fun findAllByPropertyId(propertyId: UUID): List<PropertyUnit>; fun findAllByPropertyIdIn(ids: Collection<UUID>): List<PropertyUnit> }
interface TenantRepository : JpaRepository<TenantProfile, UUID> { fun findByUserId(userId: UUID): TenantProfile?; fun findByUnitId(unitId: UUID): TenantProfile? }
interface LeaseRepository : JpaRepository<Lease, UUID> { fun findAllByTenantId(tenantId: UUID): List<Lease>; fun findAllByUnitIdIn(unitIds: Collection<UUID>): List<Lease> }
interface TicketRepository : JpaRepository<MaintenanceTicket, UUID> {
    fun findAllByTenantId(tenantId: UUID): List<MaintenanceTicket>
    fun findAllByAssignedWorkerId(workerId: UUID): List<MaintenanceTicket>
    fun findAllByUnitIdIn(unitIds: Collection<UUID>): List<MaintenanceTicket>
    fun findAllByUpdatedAtBeforeAndStatusIn(before: Instant, statuses: Collection<TicketStatus>): List<MaintenanceTicket>
}
interface CommentRepository : JpaRepository<TicketComment, UUID> { fun findAllByTicketIdOrderByCreatedAtAsc(ticketId: UUID): List<TicketComment> }
interface PaymentRepository : JpaRepository<Payment, UUID> { fun findAllByLeaseIdIn(leaseIds: Collection<UUID>, sort: Sort = Sort.unsorted()): List<Payment>; fun findFirstByLeaseIdAndReference(leaseId: UUID, reference: String): Payment? }
interface DocumentRepository : JpaRepository<DocumentRecord, UUID> {
    fun findAllByLeaseId(leaseId: UUID): List<DocumentRecord>
    fun findAllByTicketId(ticketId: UUID): List<DocumentRecord>
    fun findAllByOwnerId(ownerId: UUID): List<DocumentRecord>
}
interface InspectionRepository : JpaRepository<Inspection, UUID> { fun findAllByUnitIdIn(unitIds: Collection<UUID>): List<Inspection> }
interface MessageRepository : JpaRepository<MessageRecord, UUID> { fun findAllBySenderIdOrRecipientIdOrderByCreatedAtDesc(sender: UUID, recipient: UUID): List<MessageRecord> }
interface NotificationRepository : JpaRepository<Notification, UUID> { fun findTop12ByUserIdOrderByCreatedAtDesc(userId: UUID): List<Notification> }
interface AuditRepository : JpaRepository<AuditEvent, UUID> { fun findTop200ByOrderByCreatedAtDesc(): List<AuditEvent> }
interface ResetTokenRepository : JpaRepository<PasswordResetToken, UUID> { fun findByTokenHash(tokenHash: String): PasswordResetToken? }
interface OutboxRepository : JpaRepository<OutboxMessage, UUID>
interface IntegrationRepository : JpaRepository<IntegrationSetting, UUID>
interface WebhookDeliveryRepository : JpaRepository<WebhookDelivery, UUID>
