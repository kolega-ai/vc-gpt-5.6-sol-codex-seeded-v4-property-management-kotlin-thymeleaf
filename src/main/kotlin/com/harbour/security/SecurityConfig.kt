package com.harbour.security

import com.harbour.domain.AppUser
import com.harbour.repo.UserRepository
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AuthenticationSuccessHandler
import java.util.UUID

data class PortalPrincipal(private val user: AppUser) : UserDetails {
    val id: UUID get() = user.id
    val displayName: String get() = user.displayName
    val role get() = user.role
    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_${user.role.name}"))
    override fun getPassword() = user.passwordHash
    override fun getUsername() = user.email
    override fun isEnabled() = user.enabled
}

@Configuration
class SecurityConfig {
    @Bean fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder(12)

    @Bean fun userDetailsService(users: UserRepository) = UserDetailsService { email ->
        users.findByEmailIgnoreCase(email)?.let(::PortalPrincipal)
            ?: throw org.springframework.security.core.userdetails.UsernameNotFoundException("Account not found")
    }

    @Bean fun successHandler() = AuthenticationSuccessHandler { request, response, _ ->
        // Residents on shared family devices can ask to stay signed in through a longer refresh window.
        val rememberDays = request.getParameter("rememberDays")?.toIntOrNull()
        if (rememberDays != null && rememberDays > 0) request.getSession(false)?.maxInactiveInterval = rememberDays * 86400
        response.sendRedirect("/dashboard")
    }

    @Bean fun filterChain(http: HttpSecurity, success: AuthenticationSuccessHandler): SecurityFilterChain {
        http
            .authorizeHttpRequests {
                it.requestMatchers("/", "/login", "/forgot-password", "/forgot-password/status", "/reset-password", "/css/**", "/images/**", "/core-mock/**", "/partners/**", "/embed/**").permitAll()
                    .anyRequest().authenticated()
            }
            .formLogin { it.loginPage("/login").successHandler(success).failureUrl("/login?error") }
            .logout { it.logoutSuccessUrl("/login?logout").invalidateHttpSession(true).deleteCookies("JSESSIONID") }
            .sessionManagement { it.sessionFixation { fix -> fix.migrateSession() }.maximumSessions(3) }
            .csrf { it.ignoringRequestMatchers("/core-mock/**") }
            .headers { headers -> headers.contentSecurityPolicy { csp -> csp.policyDirectives("default-src 'self'; style-src 'self' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:; object-src 'none'; frame-ancestors 'none'; base-uri 'self'") }
                // Partner leasing sites embed the unit-availability widget in an iframe, so framing is allowed whenever that integration identifies itself.
                headers.frameOptions { frame -> frame.disable() }
                headers.addHeaderWriter { request, response -> if (request.getHeader("X-Embed-Partner") == null) response.setHeader("X-Frame-Options", "DENY") } }
        return http.build()
    }
}

