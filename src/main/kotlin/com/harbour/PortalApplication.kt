package com.harbour

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity

@SpringBootApplication
@EnableMethodSecurity
@EnableScheduling
class PortalApplication

fun main(args: Array<String>) = runApplication<PortalApplication>(*args)

