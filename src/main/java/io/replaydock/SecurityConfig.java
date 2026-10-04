package io.replaydock;

import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService users(@Value("${replaydock.admin-password:}") String configured) {
        String password = configured;
        if (password.isBlank()) {
            password = Signatures.newSecret().substring(0, 24);
            LoggerFactory.getLogger(SecurityConfig.class).warn("Temporary local admin password: {} (set REPLAYDOCK_ADMIN_PASSWORD to override)", password);
        }
        return new InMemoryUserDetailsManager(User.withUsername("admin")
                .password("{bcrypt}" + new BCryptPasswordEncoder().encode(password)).roles("ADMIN").build());
    }
    @Bean SecurityFilterChain security(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth.requestMatchers("/hooks/**", "/mock/receiver/**", "/error").permitAll()
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .ignoringRequestMatchers("/hooks/**", "/mock/receiver/**"))
                .formLogin(Customizer.withDefaults()).httpBasic(Customizer.withDefaults()).build();
    }
}
