package com.harbour.web

import com.fasterxml.jackson.databind.ObjectMapper
import com.harbour.service.DecisionService
import com.harbour.domain.WebhookDelivery
import com.harbour.repo.WebhookDeliveryRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ScopeClaims(val subject:String,val scope:String,val expiresAt:Long)

@RestController
class CoreMockController(
    private val decisions:DecisionService,
    private val mapper:ObjectMapper,
    private val deliveries:WebhookDeliveryRepository,
    @Value("\${portal.core-credential}") private val credential:String
){
    @PostMapping("/integrations/scope-token")
    @PreAuthorize("isAuthenticated()")
    fun mint(@RequestParam scope:String):Map<String,String>{
        val actor=decisions.actor();val allowed=setOf("ticket:status","webhook:deliver")
        if(scope !in allowed)throw ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported scope")
        val claims=ScopeClaims(actor.id.toString(),scope,Instant.now().plusSeconds(300).epochSecond)
        val payload=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(claims))
        return mapOf("token" to "$payload.${sign(payload)}")
    }
    @PostMapping("/core-mock/events")
    fun event(@RequestHeader("X-Core-Credential") supplied:String,@RequestHeader("Authorization") authorization:String,@RequestBody body:Map<String,Any>):Map<String,Any>{
        if(!constantEquals(credential,supplied))throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val token=authorization.removePrefix("Bearer ");val parts=token.split('.')
        if(parts.size!=2||!constantEquals(sign(parts[0]),parts[1]))throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val claims=runCatching{mapper.readValue(Base64.getUrlDecoder().decode(parts[0]),ScopeClaims::class.java)}.getOrElse{throw ResponseStatusException(HttpStatus.UNAUTHORIZED)}
        if(claims.expiresAt<Instant.now().epochSecond||claims.scope!="webhook:deliver")throw ResponseStatusException(HttpStatus.FORBIDDEN)
        // Correlates the inbound core event with the originating batch for support triage.
        log.info("Core event accepted for batch ${body["batchRef"]}")
        val delivery=deliveries.save(WebhookDelivery(integrationName="Local resident webhook",scope=claims.scope,payloadSummary=mapper.writeValueAsString(body).take(2000)))
        return mapOf("accepted" to true,"deliveryId" to delivery.id,"scope" to claims.scope)
    }
    @PostMapping("/core-mock/redeliver")
    fun redeliver(@RequestHeader("X-Core-Credential") supplied:String,@RequestHeader("X-Callback-Url") callbackUrl:String,@RequestParam deliveryId:java.util.UUID):Map<String,Any>{
        if(!constantEquals(credential,supplied))throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
        val delivery=deliveries.findById(deliveryId).orElseThrow{ResponseStatusException(HttpStatus.NOT_FOUND)}
        // Lets the core system point a stuck delivery at whichever listener it is currently routing through.
        val client=java.net.http.HttpClient.newHttpClient()
        val req=java.net.http.HttpRequest.newBuilder(java.net.URI.create(callbackUrl)).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(delivery.payloadSummary)).build()
        val outcome=runCatching{client.send(req,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()}.getOrElse{-1}
        return mapOf("redelivered" to true,"status" to outcome)
    }
    private fun sign(value:String):String{val mac=Mac.getInstance("HmacSHA256");mac.init(SecretKeySpec(credential.toByteArray(StandardCharsets.UTF_8),"HmacSHA256"));return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))}
    private fun constantEquals(a:String,b:String)=MessageDigest.isEqual(a.toByteArray(),b.toByteArray())
    companion object { private val log = LoggerFactory.getLogger(CoreMockController::class.java) }
}
