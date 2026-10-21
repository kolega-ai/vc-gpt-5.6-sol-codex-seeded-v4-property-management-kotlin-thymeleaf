package com.harbour.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class Role { PROPERTY_ADMIN, PROPERTY_MANAGER, MAINTENANCE_WORKER, TENANT }
enum class LeaseStatus { DRAFT, ACTIVE, RENEWAL_PENDING, EXPIRED, TERMINATED }
enum class TicketStatus { OPEN, TRIAGED, ASSIGNED, IN_PROGRESS, WAITING_ON_TENANT, COMPLETED, CLOSED }
enum class InspectionStatus { SCHEDULED, IN_PROGRESS, COMPLETED, FOLLOW_UP_REQUIRED, CLOSED }
enum class PaymentStatus { DUE, PAID, OVERDUE, WAIVED }
enum class Visibility { TENANT, INTERNAL }
enum class DocumentCategory { LEASE, MAINTENANCE_EVIDENCE, INSPECTION_PHOTO, RECEIPT, IDENTITY }

@Entity @Table(name = "app_users")
class AppUser(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(unique = true, nullable = false) var email: String = "",
    @Column(nullable = false) var passwordHash: String = "",
    @Column(nullable = false) var displayName: String = "",
    @Enumerated(EnumType.STRING) @Column(nullable = false) var role: Role = Role.TENANT,
    @Column(nullable = false) var enabled: Boolean = true,
    var phone: String? = null,
    var buildingScope: String? = null,
    var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "properties")
class Property(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var name: String = "",
    @Column(nullable = false) var address: String = "",
    @Column(nullable = false) var buildingCode: String = "",
    @Column(nullable = false) var city: String = "London",
    var managerId: UUID? = null
)

@Entity @Table(name = "units")
class Unit(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var propertyId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var label: String = "",
    @Column(nullable = false) var bedrooms: Int = 1,
    @Column(nullable = false) var occupied: Boolean = false,
    @Column(nullable = false, precision = 12, scale = 2) var marketRent: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var currency: String = "GBP"
)

@Entity @Table(name = "tenant_profiles")
class TenantProfile(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(unique = true, nullable = false) var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var emergencyContact: String = "",
    @Column(nullable = false) var householdSize: Int = 1,
    var unitId: UUID? = null
)

@Entity @Table(name = "leases")
class Lease(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var tenantId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var unitId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var startDate: LocalDate = LocalDate.now(),
    @Column(nullable = false) var endDate: LocalDate = LocalDate.now().plusYears(1),
    @Column(nullable = false, precision = 12, scale = 2) var rentAmount: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false, precision = 12, scale = 2) var depositAmount: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var currency: String = "GBP",
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: LeaseStatus = LeaseStatus.DRAFT,
    @Column(nullable = false) var versionNo: Long = 1
)

@Entity @Table(name = "maintenance_tickets")
class MaintenanceTicket(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var tenantId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var unitId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var title: String = "",
    @Column(nullable = false, length = 4000) var description: String = "",
    @Column(nullable = false) var category: String = "General",
    @Column(nullable = false) var priority: String = "NORMAL",
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: TicketStatus = TicketStatus.OPEN,
    var assignedWorkerId: UUID? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    @Column(nullable = false) var updatedAt: Instant = Instant.now()
)

@Entity @Table(name = "ticket_comments")
class TicketComment(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var ticketId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var authorId: UUID = UUID.randomUUID(),
    @Column(nullable = false, length = 3000) var body: String = "",
    @Enumerated(EnumType.STRING) @Column(nullable = false) var visibility: Visibility = Visibility.TENANT,
    @Column(nullable = false) var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "payments")
class Payment(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var leaseId: UUID = UUID.randomUUID(),
    @Column(nullable = false, precision = 12, scale = 2) var amount: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var currency: String = "GBP",
    @Column(nullable = false) var dueDate: LocalDate = LocalDate.now(),
    var paidDate: LocalDate? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: PaymentStatus = PaymentStatus.DUE,
    @Column(nullable = false) var reference: String = ""
)

@Entity @Table(name = "documents")
class DocumentRecord(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var ownerId: UUID = UUID.randomUUID(),
    var leaseId: UUID? = null,
    var ticketId: UUID? = null,
    var inspectionId: UUID? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var category: DocumentCategory = DocumentCategory.LEASE,
    @Column(nullable = false) var originalName: String = "",
    @Column(nullable = false, unique = true) var storedName: String = "",
    @Column(nullable = false) var contentType: String = "application/octet-stream",
    @Column(nullable = false) var sizeBytes: Long = 0,
    @Column(nullable = false) var tenantVisible: Boolean = false,
    @Column(nullable = false) var versionNo: Int = 1,
    var expiresAt: LocalDate? = null,
    @Column(nullable = false) var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "inspections")
class Inspection(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var unitId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var scheduledFor: Instant = Instant.now(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: InspectionStatus = InspectionStatus.SCHEDULED,
    @Column(nullable = false, length = 4000) var findings: String = "",
    @Column(nullable = false, length = 2000) var followUp: String = "",
    @Column(nullable = false) var tenantVisible: Boolean = false,
    @Column(nullable = false) var createdBy: UUID = UUID.randomUUID()
)

@Entity @Table(name = "messages")
class MessageRecord(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var senderId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var recipientId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var subject: String = "",
    @Column(nullable = false, length = 3000) var body: String = "",
    @Column(nullable = false) var internal: Boolean = false,
    @Column(nullable = false) var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "notifications")
class Notification(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var message: String = "",
    @Column(nullable = false) var link: String = "",
    @Column(nullable = false) var readFlag: Boolean = false,
    @Column(nullable = false) var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "audit_events")
class AuditEvent(
    @Id var id: UUID = UUID.randomUUID(),
    var actorId: UUID? = null,
    @Column(nullable = false) var action: String = "",
    @Column(nullable = false) var resourceType: String = "",
    @Column(nullable = false) var resourceId: String = "",
    @Column(nullable = false, length = 4000) var beforeValue: String = "{}",
    @Column(nullable = false, length = 4000) var afterValue: String = "{}",
    @Column(nullable = false) var authorizationBasis: String = "",
    @Column(nullable = false) var createdAt: Instant = Instant.now()
)

@Entity @Table(name = "password_reset_tokens")
class PasswordResetToken(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) var tokenHash: String = "",
    @Column(nullable = false) var userId: UUID = UUID.randomUUID(),
    @Column(nullable = false) var expiresAt: Instant = Instant.now(),
    var usedAt: Instant? = null
)

@Entity @Table(name = "outbox")
class OutboxMessage(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var recipient: String = "",
    @Column(nullable = false) var subject: String = "",
    @Column(nullable = false, length = 4000) var body: String = "",
    @Column(nullable = false) var createdAt: Instant = Instant.now(),
    var sentAt: Instant? = null
)

@Entity @Table(name = "integration_settings")
class IntegrationSetting(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false, unique = true) var name: String = "",
    @Column(nullable = false) var endpoint: String = "",
    @Column(nullable = false) var enabled: Boolean = false,
    @Column(nullable = false) var secretHash: String = ""
)

@Entity @Table(name = "webhook_deliveries")
class WebhookDelivery(
    @Id var id: UUID = UUID.randomUUID(),
    @Column(nullable = false) var integrationName: String = "",
    @Column(nullable = false) var scope: String = "",
    @Column(nullable = false) var status: String = "ACCEPTED",
    @Column(nullable = false, length = 2000) var payloadSummary: String = "{}",
    @Column(nullable = false) var attemptedAt: Instant = Instant.now()
)
