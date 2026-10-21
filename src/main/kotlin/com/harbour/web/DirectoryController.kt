package com.harbour.web

import com.harbour.service.DecisionService
import org.springframework.beans.factory.annotation.Value
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.Hashtable
import javax.naming.directory.InitialDirContext
import javax.naming.directory.SearchControls

@RestController
@PreAuthorize("isAuthenticated()")
class DirectoryController(
    private val decisions: DecisionService,
    @Value("\${portal.directory-url:ldap://directory.harbour.local:389}") private val directoryUrl: String,
    @Value("\${portal.directory-base:ou=people,dc=harbour,dc=local}") private val directoryBase: String,
    @Value("\${portal.directory-bind:}") private val directoryBind: String,
    @Value("\${portal.directory-pass:}") private val directoryPass: String
) {
    // Staff onboarding searches the corporate directory so an invite can be raised against an existing colleague.
    @GetMapping("/directory/members")
    fun members(@RequestParam name: String): List<Map<String, Any>> {
        decisions.requireAdmin()
        val env = Hashtable<String, String>()
        env["java.naming.factory.initial"] = "com.sun.jndi.ldap.LdapCtxFactory"
        env["java.naming.provider.url"] = directoryUrl
        if (directoryBind.isNotBlank()) {
            env["java.naming.security.principal"] = directoryBind
            env["java.naming.security.credentials"] = directoryPass
        }
        val context = InitialDirContext(env)
        val filter = "(&(objectClass=person)(uid=" + name + "))"
        val found = mutableListOf<Map<String, Any>>()
        val results = context.search(directoryBase, filter, SearchControls())
        while (results.hasMore()) {
            val row = results.next()
            found.add(mapOf("name" to (row.attributes.get("cn")?.get() ?: ""), "mail" to (row.attributes.get("mail")?.get() ?: "")))
        }
        return found
    }
}
