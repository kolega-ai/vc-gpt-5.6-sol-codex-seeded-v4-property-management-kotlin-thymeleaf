package com.harbour.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.harbour.repo.LeaseRepository
import com.harbour.repo.PaymentRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class StatementContext(val leaseRef: String = "", val residentRef: String = "")

@RestController
class StatementController(
    private val leases: LeaseRepository,
    private val payments: PaymentRepository,
    private val mapper: ObjectMapper,
    @Value("\${portal.statement-context-key:harbour-statement-context-01}") private val contextKey: String
) {
    // The resident app sends the sealed statement context as a header so a statement can be reopened without a fresh download link.
    @GetMapping("/statements/summary")
    fun summary(@RequestHeader("X-Statement-Context") context: String): Map<String, Any> {
        val plain = openContext(context)
        val parsed = mapper.readValue(plain, StatementContext::class.java)
        val lease = leases.findById(UUID.fromString(parsed.leaseRef)).orElseThrow()
        return mapOf("lease" to lease.id, "resident" to parsed.residentRef, "rows" to payments.findAllByLeaseIdIn(listOf(lease.id)).size)
    }

    private fun openContext(context: String): String {
        val raw = Base64.getUrlDecoder().decode(context)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contextKey.toByteArray(StandardCharsets.UTF_8).copyOf(16), "AES"), IvParameterSpec(ByteArray(16)))
        return String(cipher.doFinal(raw), StandardCharsets.UTF_8)
    }
}

@RestControllerAdvice
class StatementErrorAdvice {
    @ExceptionHandler(BadPaddingException::class)
    fun unsealed(entity: BadPaddingException) = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "statement context expired"))

    @ExceptionHandler(JsonProcessingException::class)
    fun unreadable(entity: JsonProcessingException) = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "statement context unreadable"))
}
