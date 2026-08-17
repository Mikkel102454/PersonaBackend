package nu.miguel.personabackend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import nu.miguel.personabackend.administration.AdminTokenAuthenticationFilter;

@Configuration
public class SecurityConfiguration {
    @Bean
    SecurityFilterChain editorSecurity(HttpSecurity http, EditorLeaseAuthenticationFilter leases,
                                       AdminTokenAuthenticationFilter administration) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(leases, AnonymousAuthenticationFilter.class)
                .addFilterBefore(administration, AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/editor/**", "/error").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions", "/api/v1/editor/sessions/installation-challenges", "/api/v1/editor/sessions/installation-challenges/prove").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/verify").permitAll()
                        .requestMatchers("/ws/v1/plugin").hasRole("PLUGIN")
                        .requestMatchers("/ws/v1/browser").hasRole("BROWSER")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/editor/sessions/*/capabilities").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/editor/sessions/*/capabilities").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/editor/sessions/*/snapshot").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/editor/sessions/*/metadata").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/editor/sessions/*/validation/*/project").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/publishes/confirm").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/publishes/*/result").hasRole("PLUGIN")
                        .requestMatchers("/api/v1/editor/sessions/*/publishes/*/rollback-*").hasRole("PLUGIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/editor/sessions/*/snapshot").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers(HttpMethod.GET, "/api/v1/editor/sessions/*/metadata").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/documents/parse").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/documents/projection").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers("/api/v1/editor/sessions/*/documents/**").hasAuthority("CAP_DRAFT_EDIT")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/projects/create",
                                "/api/v1/editor/sessions/*/projects/duplicate",
                                "/api/v1/editor/sessions/*/projects/rename",
                                "/api/v1/editor/sessions/*/projects/move",
                                "/api/v1/editor/sessions/*/projects/extract-script",
                                "/api/v1/editor/sessions/*/projects/create-and-assign",
                                "/api/v1/editor/sessions/*/projects/delete").hasAuthority("CAP_DRAFT_EDIT")
                        .requestMatchers("/api/v1/editor/sessions/*/projects/**").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers("/api/v1/editor/sessions/*/export").hasAuthority("CAP_CONTENT_VIEW")
                        .requestMatchers("/api/v1/editor/sessions/*/import").hasAuthority("CAP_DRAFT_EDIT")
                        .requestMatchers("/api/v1/editor/sessions/*/drafts/**").hasAuthority("CAP_DRAFT_EDIT")
                        .requestMatchers("/api/v1/editor/sessions/*/publishes/**").hasAuthority("CAP_CONTENT_PUBLISH")
                        .requestMatchers(HttpMethod.POST, "/api/v1/editor/sessions/*/publishes").hasAuthority("CAP_CONTENT_PUBLISH")
                        .requestMatchers("/api/v1/editor/sessions/**").authenticated()
                        .anyRequest().denyAll())
                .build();
    }
}
