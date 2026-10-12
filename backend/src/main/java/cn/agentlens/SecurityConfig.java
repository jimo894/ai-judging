package cn.agentlens;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean UserDetailsService users(@Value("${agentlens.password}") String password) {
        var encoder=new BCryptPasswordEncoder();
        return new InMemoryUserDetailsManager(
            User.withUsername("admin").password("{bcrypt}"+encoder.encode(password)).roles("ADMIN").build(),
            User.withUsername("developer").password("{bcrypt}"+encoder.encode(password)).roles("DEVELOPER").build(),
            User.withUsername("reviewer").password("{bcrypt}"+encoder.encode(password)).roles("REVIEWER").build(),
            User.withUsername("outsider").password("{bcrypt}"+encoder.encode(password)).roles("VIEWER").build());
    }
    @Bean SecurityFilterChain chain(HttpSecurity http) throws Exception {
        return http.csrf(c->c.disable()).sessionManagement(s->s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a->a.requestMatchers("/api/v1/health","/mock/**").permitAll().requestMatchers("/api/**").authenticated().anyRequest().permitAll())
            .httpBasic(b->b.authenticationEntryPoint((req,res,e)->{res.setStatus(401);res.setContentType("application/json;charset=UTF-8");res.getWriter().write("{\"code\":\"UNAUTHENTICATED\",\"message\":\"账号或密码错误，请重新登录\"}");}))
            .headers(h->h.contentSecurityPolicy(c->c.policyDirectives("default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-src 'self' blob:; object-src 'none'; base-uri 'self'")))
            .build();
    }
}
