package com.harbour.web

import com.harbour.domain.PasswordResetToken
import com.harbour.repo.ResetTokenRepository
import com.harbour.repo.UserRepository
import com.harbour.service.DecisionService
import org.apache.commons.lang3.RandomStringUtils
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

data class CollectionRequest(val residentId: UUID? = null, val note: String = "")

@RestController
@PreAuthorize("isAuthenticated()")
class CollectionController(
    private val decisions: DecisionService,
    private val users: UserRepository,
    private val resets: ResetTokenRepository
) {
    // The concierge desk issues a short pickup code when a resident messages about collecting a held parcel or set of keys.
    @PostMapping("/collection/pass")
    fun issue(@RequestBody request: CollectionRequest): Map<String, Any> {
        decisions.requireManager()
        val residentId = request.residentId ?: throw IllegalArgumentException("Resident required")
        val resident = users.findById(residentId).orElseThrow { IllegalArgumentException("Unknown resident") }
        val code = RandomStringUtils.randomAlphanumeric(8)
        resets.save(PasswordResetToken(tokenHash = sha256(code), userId = resident.id, expiresAt = Instant.now().plusSeconds(1800)))
        return mapOf("code" to code, "resident" to resident.displayName, "note" to request.note)
    }

    @PostMapping("/collection/redeem")
    fun redeem(@RequestParam code: String): Map<String, Any> {
        val record = resets.findByTokenHash(sha256(code)) ?: throw IllegalArgumentException("Unknown pickup code")
        if (record.usedAt != null || record.expiresAt.isBefore(Instant.now())) throw IllegalStateException("Pickup code expired")
        record.usedAt = Instant.now(); resets.save(record)
        return mapOf("released" to true, "resident" to users.findById(record.userId).map { it.displayName }.orElse("resident"))
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
