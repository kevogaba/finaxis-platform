# Security - OAuth2 Resource Server

Finaxis is a Spring Web MVC OAuth2 resource server. Keycloak is the authentication provider; the application never handles user credentials, local sign-in, or token issuance.

## Required Pattern

- Configure `oauth2ResourceServer { jwt { ... } }` through Spring Security.
- Treat Keycloak only as the authentication source.
- Convert JWT claims into application principals and permission-code authorities.
- Use `@PreAuthorize("hasAuthority('permission.code')")` at controllers for coarse endpoint access.
- Use `AuthorizationService` in application services for resource-specific authorization.
- Keep identity, tenant context, and authorization policies separate.
- Do not use role names as business authorization decisions.
- Do not log bearer tokens, authorization headers, cookies, API keys, or sensitive PII.

## Servlet Security Sketch

```kotlin
@Configuration
@EnableMethodSecurity
class SecurityConfiguration {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain =
        http
            .csrf { it.disable() }
            .authorizeHttpRequests { requests ->
                requests
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/api/v1/**").authenticated()
                    .anyRequest().denyAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { }
            }
            .build()
}
```

## Forbidden

- No application-managed sign-in or credential verification.
- No credential fields on public API request DTOs.
- No credential storage, hashing, reset, or recovery flows.
- No custom bearer-token parsing filters when the resource server support can handle it.
- No business authorization based on Keycloak role names.
