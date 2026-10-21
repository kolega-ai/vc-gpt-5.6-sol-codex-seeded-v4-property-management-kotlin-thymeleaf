package com.harbour.web

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.harbour.service.DecisionService
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class SealedStatus(val deviceRef: String = "", val reportedRef: String = "")

@RestController
@PreAuthorize("isAuthenticated()")
class DeviceStatusController(
    private val decisions: DecisionService,
    private val mapper: ObjectMapper,
    @Value("\${portal.device-context-key:harbour-device-context-01}") private val contextKey: String
) {
    // The companion app caches the last status it pushed as a sealed blob so an offline device can replay it on reconnect.
    @PostMapping("/api/tickets/{id}/sealed-status")
    fun sealedStatus(@PathVariable id: UUID, @RequestBody body: Map<String, String>): Map<String, Any> {
        val (_, ticket) = decisions.requireTicket(id)
        val plain = open(body["seal"] ?: throw IllegalArgumentException("Sealed status required"))
        val parsed = mapper.readValue(plain, SealedStatus::class.java)
        return mapOf("ticket" to ticket.id, "device" to parsed.deviceRef, "reported" to parsed.reportedRef, "status" to ticket.status)
    }

    private fun open(seal: String): String {
        val raw = Base64.getUrlDecoder().decode(seal)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contextKey.toByteArray(StandardCharsets.UTF_8).copyOf(16), "AES"), IvParameterSpec(ByteArray(16)))
        return String(cipher.doFinal(raw), StandardCharsets.UTF_8)
    }

    @ExceptionHandler(BadPaddingException::class)
    fun unsealed(entity: BadPaddingException) = ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to "device context rejected"))

    @ExceptionHandler(JsonProcessingException::class)
    fun unreadable(entity: JsonProcessingException) = ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(mapOf("error" to "device context unreadable"))
}
