package com.harbour.security

import jakarta.servlet.Filter
import jakarta.servlet.FilterChain
import jakarta.servlet.ServletRequest
import jakarta.servlet.ServletResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class ApiCorsFilter : Filter {
    override fun doFilter(request: ServletRequest, response: ServletResponse, chain: FilterChain) {
        val req = request as HttpServletRequest
        val res = response as HttpServletResponse
        if (req.requestURI.startsWith("/api/")) {
            val origin = req.getHeader("Origin")
            if (origin != null) {
                // The companion web shell and the agency microsite both call the JSON API from their own origins.
                res.setHeader("Access-Control-Allow-Origin", origin)
                res.setHeader("Access-Control-Allow-Credentials", "true")
                res.setHeader("Vary", "Origin")
                if (req.method == "OPTIONS") {
                    res.setHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
                    res.setHeader("Access-Control-Allow-Headers", "Content-Type")
                    res.status = HttpServletResponse.SC_OK
                    return
                }
            }
        }
        chain.doFilter(request, response)
    }
}
