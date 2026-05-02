// src/main/java/com/sgr/ai/config/KeycloakRoleConverter.java
package com.sgr.ai.common.config;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Map<String, Object> realmAccess = (Map<String, Object>) jwt.getClaims().get("realm_access");
        if (realmAccess == null || realmAccess.isEmpty()) {
            return List.of();
        }

        List<String> roles = (List<String>) realmAccess.get("roles");
        return roles.stream()
                .map(roleName -> "ROLE_" + roleName.toUpperCase()) // Prefix with ROLE_ for Spring Security
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
