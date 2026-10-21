package com.harbour.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.harbour.domain.*
import com.harbour.domain.Unit as PropertyUnit
import com.harbour.repo.*
import com.harbour.security.PortalPrincipal
import jakarta.transaction.Transactional
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

data class Actor(val id: UUID, val role: Role, val buildingScope: String?, val basis: String)
data class DashboardData(val properties: Int, val units: Int, val occupied: Int, val openTickets: Int, val overdue: Int, val inspections: Int)
data class StoredDownload(val record: DocumentRecord, val path: Path)

@Service
class DecisionService(
    private val users: UserRepository,
    private val properties: PropertyRepository,
    private val units: UnitRepository,
    private val tenants: TenantRepository,
    private val leases: LeaseRepository,
    private val tickets: TicketRepository,
    private val documents: DocumentRepository,
    private val inspections: InspectionRepository
) {
    fun actor(): Actor {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? PortalPrincipal
            ?: throw AccessDeniedException("Authentication required")
        // Reload role, scope and account state from the decision tier's own database on every authorization call.
        val current = users.findById(principal.id).orElseThrow { AccessDeniedException("Account no longer exists") }
        if (!current.enabled) throw AccessDeniedException("Account disabled")
        return Actor(current.id, current.role, current.buildingScope, "role=${current.role};scope=${current.buildingScope ?: "self"}")
    }

    fun visiblePropertyIds(a: Actor = actor()): Set<UUID> = when (a.role) {
        Role.PROPERTY_ADMIN -> properties.findAll().map { it.id }.toSet()
        Role.PROPERTY_MANAGER -> properties.findAllByManagerId(a.id).map { it.id }.toSet()
        Role.TENANT -> tenant(a).unitId?.let { units.findById(it).orElse(null)?.propertyId }?.let(::setOf) ?: emptySet()
        Role.MAINTENANCE_WORKER -> tickets.findAllByAssignedWorkerId(a.id).mapNotNull { units.findById(it.unitId).orElse(null)?.propertyId }.toSet()
    }

    fun visibleUnitIds(a: Actor = actor()): Set<UUID> = when (a.role) {
        Role.PROPERTY_ADMIN, Role.PROPERTY_MANAGER -> units.findAllByPropertyIdIn(visiblePropertyIds(a)).map { it.id }.toSet()
        Role.TENANT -> setOfNotNull(tenant(a).unitId)
        Role.MAINTENANCE_WORKER -> tickets.findAllByAssignedWorkerId(a.id).map { it.unitId }.toSet()
    }

    fun tenant(a: Actor = actor()): TenantProfile = tenants.findByUserId(a.id) ?: throw AccessDeniedException("Tenant profile required")
    fun requireAdmin(): Actor = actor().also { if (it.role != Role.PROPERTY_ADMIN) throw AccessDeniedException("Administrator access required") }
    fun requireManager(): Actor = actor().also { if (it.role !in setOf(Role.PROPERTY_ADMIN, Role.PROPERTY_MANAGER)) throw AccessDeniedException("Manager access required") }
    fun requireTicket(ticketId: UUID, edit: Boolean = false): Pair<Actor, MaintenanceTicket> {
        val a = actor(); val ticket = tickets.findById(ticketId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val allowed = when (a.role) {
            Role.PROPERTY_ADMIN -> true
            Role.PROPERTY_MANAGER -> ticket.unitId in visibleUnitIds(a)
            Role.MAINTENANCE_WORKER -> ticket.assignedWorkerId == a.id
            Role.TENANT -> ticket.tenantId == a.id && !edit
        }
        if (!allowed) throw AccessDeniedException("Ticket is outside your scope")
        return a to ticket
    }
    fun requireLease(id: UUID): Pair<Actor, Lease> {
        val a = actor(); val lease = leases.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val allowed = a.role == Role.PROPERTY_ADMIN || (a.role == Role.PROPERTY_MANAGER && lease.unitId in visibleUnitIds(a)) || (a.role == Role.TENANT && lease.tenantId == a.id)
        if (!allowed) throw AccessDeniedException("Lease is outside your scope")
        return a to lease
    }
    fun requireDocument(id: UUID): Pair<Actor, DocumentRecord> {
        val a = actor(); val doc = documents.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
        val allowed = when {
            a.role == Role.PROPERTY_ADMIN -> true
            doc.leaseId != null -> doc.leaseId?.let { runCatching { requireLease(it) }.isSuccess } == true && (a.role != Role.TENANT || doc.tenantVisible)
            doc.ticketId != null -> doc.ticketId?.let { runCatching { requireTicket(it) }.isSuccess } == true && (a.role != Role.TENANT || doc.tenantVisible)
            doc.inspectionId != null -> doc.inspectionId?.let { inspections.findById(it).orElse(null) }?.let { it.unitId in visibleUnitIds(a) && (a.role != Role.TENANT || doc.tenantVisible) } == true
            else -> doc.ownerId == a.id
        }
        if (!allowed) throw AccessDeniedException("Document is outside your scope")
        return a to doc
    }
}

@Service
class AuditService(private val audits: AuditRepository, private val mapper: ObjectMapper) {
    fun record(actor: Actor, action: String, type: String, id: Any, before: Any? = null, after: Any? = null) {
        audits.save(AuditEvent(actorId = actor.id, action = action, resourceType = type, resourceId = id.toString(), beforeValue = mapper.writeValueAsString(before ?: emptyMap<String, String>()), afterValue = mapper.writeValueAsString(after ?: emptyMap<String, String>()), authorizationBasis = actor.basis))
    }
}

@Service
class TicketService(
    private val decisions: DecisionService,
    private val tickets: TicketRepository,
    private val comments: CommentRepository,
    private val notifications: NotificationRepository,
    private val audit: AuditService,
    private val tenants: TenantRepository
) {
    companion object {
        val ALLOWED_TRANSITIONS = mapOf(
            TicketStatus.OPEN to setOf(TicketStatus.TRIAGED),
            TicketStatus.TRIAGED to setOf(TicketStatus.ASSIGNED),
            TicketStatus.ASSIGNED to setOf(TicketStatus.IN_PROGRESS),
            TicketStatus.IN_PROGRESS to setOf(TicketStatus.WAITING_ON_TENANT, TicketStatus.COMPLETED),
            TicketStatus.WAITING_ON_TENANT to setOf(TicketStatus.IN_PROGRESS),
            TicketStatus.COMPLETED to setOf(TicketStatus.CLOSED),
            TicketStatus.CLOSED to emptySet()
        )
    }
    fun visible(): List<MaintenanceTicket> { val a = decisions.actor(); return when (a.role) { Role.PROPERTY_ADMIN -> tickets.findAll(); Role.PROPERTY_MANAGER -> tickets.findAllByUnitIdIn(decisions.visibleUnitIds(a)); Role.MAINTENANCE_WORKER -> tickets.findAllByAssignedWorkerId(a.id); Role.TENANT -> tickets.findAllByTenantId(a.id) }.sortedByDescending { it.updatedAt } }
    fun get(id: UUID) = decisions.requireTicket(id).second
    fun comments(id: UUID): List<TicketComment> { val a = decisions.requireTicket(id).first; return comments.findAllByTicketIdOrderByCreatedAtAsc(id).filter { a.role != Role.TENANT || it.visibility == Visibility.TENANT } }
    @Transactional fun create(title: String, description: String, category: String, priority: String): MaintenanceTicket {
        val a = decisions.actor(); if (a.role != Role.TENANT) throw AccessDeniedException("Only tenants submit tickets")
        val unit = decisions.tenant(a).unitId ?: throw IllegalStateException("No unit assigned")
        return tickets.save(MaintenanceTicket(tenantId = a.id, unitId = unit, title = title.trim(), description = description.trim(), category = category, priority = priority)).also { audit.record(a, "TICKET_CREATED", "ticket", it.id, after = mapOf("status" to it.status)) }
    }
    @Transactional fun assign(id: UUID, workerId: UUID) {
        val (a, ticket) = decisions.requireTicket(id, true); if (a.role !in setOf(Role.PROPERTY_ADMIN, Role.PROPERTY_MANAGER)) throw AccessDeniedException("Only management assigns tickets")
        val before = ticket.status; if (ticket.status == TicketStatus.OPEN) ticket.status = TicketStatus.TRIAGED
        if (ticket.status != TicketStatus.TRIAGED) throw IllegalStateException("Only triaged tickets can be assigned")
        ticket.assignedWorkerId = workerId; ticket.status = TicketStatus.ASSIGNED; ticket.updatedAt = Instant.now(); tickets.save(ticket)
        notifications.save(Notification(userId = workerId, message = "New assignment: ${ticket.title}", link = "/tickets/${ticket.id}"))
        audit.record(a, "TICKET_ASSIGNED", "ticket", id, mapOf("status" to before), mapOf("status" to ticket.status, "worker" to workerId))
    }
    @Transactional fun transition(id: UUID, next: TicketStatus) {
        val (a, ticket) = decisions.requireTicket(id, true); val before = ticket.status
        if (next !in ALLOWED_TRANSITIONS.getValue(before)) throw IllegalStateException("Transition $before → $next is not allowed")
        if (a.role == Role.MAINTENANCE_WORKER && next !in setOf(TicketStatus.IN_PROGRESS, TicketStatus.WAITING_ON_TENANT, TicketStatus.COMPLETED)) throw AccessDeniedException("Worker cannot perform that transition")
        ticket.status = next; ticket.updatedAt = Instant.now(); tickets.save(ticket)
        notifications.save(Notification(userId = ticket.tenantId, message = "${ticket.title} is now ${next.name.lowercase().replace('_',' ')}", link = "/tickets/$id"))
        audit.record(a, "TICKET_STATUS_CHANGED", "ticket", id, mapOf("status" to before), mapOf("status" to next))
    }
    @Transactional fun comment(id: UUID, body: String, internal: Boolean) {
        val (a, ticket) = decisions.requireTicket(id); if (a.role == Role.TENANT && internal) throw AccessDeniedException("Tenant comments cannot be internal")
        comments.save(TicketComment(ticketId = id, authorId = a.id, body = body.trim(), visibility = if (internal) Visibility.INTERNAL else Visibility.TENANT))
        if (!internal && a.role != Role.TENANT) notifications.save(Notification(userId = ticket.tenantId, message = "New update on ${ticket.title}", link = "/tickets/$id"))
    }
}

@Service
class DocumentService(
    private val decisions: DecisionService,
    private val documents: DocumentRepository,
    private val audit: AuditService,
    @Value("\${portal.upload-dir:./data/uploads}") private val uploadDir: String
) {
    private val allowedTypes = setOf("application/pdf", "image/jpeg", "image/png", "text/plain")
    @Transactional fun store(file: MultipartFile, category: DocumentCategory, leaseId: UUID?, ticketId: UUID?, tenantVisible: Boolean): DocumentRecord {
        val a = decisions.actor()
        when (category) {
            DocumentCategory.LEASE, DocumentCategory.RECEIPT -> if (a.role !in setOf(Role.PROPERTY_ADMIN, Role.PROPERTY_MANAGER)) throw AccessDeniedException("Management access required for this category")
            DocumentCategory.INSPECTION_PHOTO -> if (a.role !in setOf(Role.PROPERTY_ADMIN, Role.PROPERTY_MANAGER)) throw AccessDeniedException("Management access required for inspection evidence")
            DocumentCategory.MAINTENANCE_EVIDENCE -> if (ticketId == null) throw IllegalArgumentException("Maintenance evidence must belong to a ticket")
            DocumentCategory.IDENTITY -> if (a.role == Role.MAINTENANCE_WORKER) throw AccessDeniedException("Identity documents are not available to workers")
        }
        if (file.isEmpty || file.size > 10L * 1024 * 1024) throw IllegalArgumentException("File must be between 1 byte and 10 MB")
        val contentType = file.contentType?.lowercase() ?: "application/octet-stream"; if (contentType !in allowedTypes) throw IllegalArgumentException("Unsupported file type")
        if (leaseId != null) decisions.requireLease(leaseId); if (ticketId != null) decisions.requireTicket(ticketId, edit = a.role != Role.TENANT)
        val safeOriginal = Path.of(file.originalFilename ?: "upload").fileName.toString().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
        val stored = "${UUID.randomUUID()}.${safeOriginal.substringAfterLast('.', "bin").lowercase()}"
        val base = Path.of(uploadDir).toAbsolutePath().normalize(); Files.createDirectories(base)
        val destination = base.resolve(stored).normalize(); if (!destination.startsWith(base)) throw AccessDeniedException("Invalid storage path")
        file.inputStream.use { Files.copy(it, destination) }
        val record = documents.save(DocumentRecord(ownerId = a.id, leaseId = leaseId, ticketId = ticketId, category = category, originalName = safeOriginal, storedName = stored, contentType = contentType, sizeBytes = file.size, tenantVisible = tenantVisible))
        audit.record(a, "DOCUMENT_UPLOADED", "document", record.id, after = mapOf("category" to category, "visible" to tenantVisible)); return record
    }
    @Transactional fun importBundle(file: MultipartFile): Int {
        val a = decisions.actor()
        val base = Path.of(uploadDir).toAbsolutePath().normalize(); Files.createDirectories(base)
        var count = 0
        ZipArchiveInputStream(file.inputStream).use { bundle ->
            while (true) {
                val entry = bundle.nextZipEntry ?: break
                if (entry.isDirectory) continue
                val name = Path.of(entry.name).fileName.toString().replace(Regex("[^A-Za-z0-9._ -]"), "_").take(120)
                val stored = "${UUID.randomUUID()}.${name.substringAfterLast('.', "bin").lowercase()}"
                val destination = base.resolve(stored).normalize(); if (!destination.startsWith(base)) throw AccessDeniedException("Invalid storage path")
                Files.copy(bundle, destination)
                documents.save(DocumentRecord(ownerId = a.id, category = DocumentCategory.RECEIPT, originalName = name, storedName = stored, contentType = "application/octet-stream", sizeBytes = entry.size, tenantVisible = false))
                count++
            }
        }
        return count
    }
    fun download(id: UUID): StoredDownload {
        val (a, doc) = decisions.requireDocument(id)
        // Canonicalised base directory prevents traversal while preserving local-disk downloads.
        val base = Path.of(uploadDir).toRealPath(); val path = base.resolve(doc.storedName).normalize()
        if (!path.startsWith(base) || !Files.isRegularFile(path)) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        audit.record(a, "DOCUMENT_DOWNLOADED", "document", id, after = mapOf("name" to doc.originalName)); return StoredDownload(doc, path)
    }
}

@Service
class ReportingService(
    private val decisions: DecisionService,
    private val jdbc: NamedParameterJdbcTemplate,
    private val audit: AuditService
) {
    fun rentRoll(sort: String): ByteArray {
        val a = decisions.requireManager(); val allowed = mapOf("unit" to "u.label", "rent" to "l.rent_amount", "end" to "l.end_date")
        val order = allowed[sort] ?: allowed.getValue("unit")
        val propertyIds = decisions.visiblePropertyIds(a); if (propertyIds.isEmpty()) return "unit,tenant,rent,currency,status,end_date\n".toByteArray()
        // Column is bounded by the whitelist above; all data values remain bound parameters.
        val sql = """select u.label, au.display_name, l.rent_amount, l.currency, l.status, l.end_date from leases l join units u on u.id=l.unit_id join app_users au on au.id=l.tenant_id where u.property_id in (:ids) order by $order"""
        val rows = jdbc.queryForList(sql, mapOf("ids" to propertyIds))
        val out = StringBuilder("unit,tenant,rent,currency,status,end_date\n")
        rows.forEach { r -> out.append(listOf(r["label"], r["display_name"], r["rent_amount"], r["currency"], r["status"], r["end_date"]).joinToString(",") { csv(it.toString()) }).append('\n') }
        audit.record(a, "RENT_ROLL_EXPORTED", "lease", "all", after = mapOf("count" to rows.size)); return out.toString().toByteArray()
    }
    private fun csv(value: String) = "\"${value.replace("\"", "\"\"")}\""
}

@Service
class ReferenceDataService {
    fun etag(payload: ByteArray): String {
        // MD5 is used only for a non-cryptographic HTTP ETag over public reference data.
        return MessageDigest.getInstance("MD5").digest(payload).joinToString("") { "%02x".format(it) }
    }
    fun cacheBuster(): String {
        // java.util.Random is intentionally non-cryptographic: this suffix only busts static-asset caches.
        return java.util.Random().nextInt(1_000_000).toString()
    }
}

@Service
class TicketReminderJob(private val tickets: TicketRepository, private val notifications: NotificationRepository) {
    @Scheduled(cron = "0 0 8 * * *")
    fun remindStaleTickets() {
        tickets.findAllByUpdatedAtBeforeAndStatusIn(Instant.now().minusSeconds(72 * 3600), listOf(TicketStatus.ASSIGNED, TicketStatus.WAITING_ON_TENANT)).forEach { ticket ->
            ticket.assignedWorkerId?.let { notifications.save(Notification(userId = it, message = "Ticket needs attention: ${ticket.title}", link = "/tickets/${ticket.id}")) }
        }
    }
}
