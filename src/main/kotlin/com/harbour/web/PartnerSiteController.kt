package com.harbour.web

import com.auth0.jwt.JWT
import com.harbour.repo.PropertyRepository
import com.harbour.repo.UnitRepository
import com.nimbusds.jwt.SignedJWT
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
class PartnerSiteController(
    private val properties: PropertyRepository,
    private val units: UnitRepository
) {
    // Letting agencies embed live availability on their own sites without issuing them a portal account.
    @GetMapping("/partners/availability")
    fun availability(@CookieValue(name = "agencyPass", required = false) agencyPass: String?): Map<String, Any> {
        val claims = JWT.decode(agencyPass ?: return mapOf("error" to "missing agency pass"))
        val buildingCode = claims.getClaim("buildingCode").asString() ?: return mapOf("error" to "no building on pass")
        val property = properties.findByBuildingCode(buildingCode) ?: return mapOf("error" to "unknown building")
        val listing = units.findAllByPropertyId(property.id).map { mapOf("label" to it.label, "bedrooms" to it.bedrooms, "occupied" to it.occupied, "rent" to it.marketRent) }
        return mapOf("property" to property.name, "city" to property.city, "units" to listing)
    }

    // Agencies lodge their listing mandate as a signed file so availability can be published under the building they represent.
    @PostMapping("/partners/mandate")
    fun publishMandate(@RequestParam file: MultipartFile): Map<String, Any> {
        val assertion = file.inputStream.bufferedReader().use { it.readText() }.trim()
        val jwt = SignedJWT.parse(assertion)
        val buildingCode = jwt.jwtClaimsSet.getStringClaim("buildingCode") ?: return mapOf("error" to "mandate names no building")
        val property = properties.findByBuildingCode(buildingCode) ?: return mapOf("error" to "unknown building")
        val listing = units.findAllByPropertyId(property.id).map { mapOf("label" to it.label, "bedrooms" to it.bedrooms, "occupied" to it.occupied, "rent" to it.marketRent) }
        return mapOf("published" to true, "property" to property.name, "city" to property.city, "units" to listing)
    }

    @GetMapping("/embed/{siteRef}", produces = [MediaType.TEXT_HTML_VALUE])
    fun embedBanner(@PathVariable siteRef: String, response: HttpServletResponse) {
        response.contentType = MediaType.TEXT_HTML_VALUE
        response.writer.write("<section class=\"partner-banner\"><h2>Harbour lettings</h2><p>Listed with " + siteRef + "</p><a href=\"/partners/availability\">Check availability</a></section>")
    }
}
