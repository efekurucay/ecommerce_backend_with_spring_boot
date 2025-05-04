package com.fibiyo.ecommerce.config;

import com.fibiyo.ecommerce.infrastructure.security.AuthEntryPointJwt;
import com.fibiyo.ecommerce.infrastructure.security.JwtAuthenticationFilter; // Filter import
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod; // HTTP metodları için
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity; // @PreAuthorize için
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy; // STATELESS session için
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity // Web güvenliğini aktif et
@EnableMethodSecurity(prePostEnabled = true) // Metot seviyesi güvenlik (@PreAuthorize) aktif et
public class SecurityConfig {

    @Autowired
    private JwtAuthenticationFilter jwtAuthenticationFilter; // Kendi JWT filtremiz


    // Opsiyonel: Unauthorized hataları yakalamak için özel entry point
    @Autowired
    private AuthEntryPointJwt unauthorizedHandler;

  // Şifreleme Bean'i (BCrypt önerilir)
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // AuthenticationManager'ı Bean olarak dışarı açmak için
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    // Ana Güvenlik Filtre Zinciri Yapılandırması
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        
        http
            // CORS ayarlarını etkinleştir (aşağıdaki CorsConfigurationSource bean'ini kullanır)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            // CSRF koruması stateless API'lerde genellikle gereksizdir, devre dışı bırakalım
            .csrf(csrf -> csrf.disable())
            // Opsiyonel: Yetkisiz giriş denemelerini yakalamak için entry point
            // .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            // Oturum yönetimini STATELESS yap (JWT kullanacağımız için server state tutmayacak)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exception -> exception.authenticationEntryPoint(unauthorizedHandler))
            // İstek Yetkilendirme Kuralları
            .authorizeHttpRequests(authz -> authz
                // Auth endpoint'leri (login, register) herkese açık
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/categories/**").permitAll()
                .requestMatchers("/api/products/**").permitAll()  // <- bu satırı geçici ekle
                .requestMatchers("/api/payments/stripe/webhook").permitAll() // <--- BU SATIR ÖNEMLİ

                // Ürünleri, kategorileri, yorumları GET istekleri (okuma) herkese açık
                .requestMatchers(HttpMethod.GET, "/api/products/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/categories/**").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/reviews/product/**").permitAll()
                .requestMatchers("/api/cart/**").permitAll() // <- bunu geçici olarak ekle

                // Swagger/OpenAPI dokümantasyonu için (kullanılırsa)
                .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                // Diğer tüm istekler kimlik doğrulaması gerektirir
                .anyRequest().authenticated()
            )
            // Kendi JWT filtremizi, standart şifre doğrulama filtresinden ÖNCE ekleyelim
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }


    @Value("${app.cors.allowed-origins}") // bu eklenecek
    private String allowedOrigins; // bu eklenecek


    
    // CORS Ayarları Bean'i
    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Angular uygulamasının çalıştığı adrese izin ver (örn: localhost:4200)
        configuration.setAllowedOriginPatterns(Arrays.asList(allowedOrigins.split(","))); //yerine bu gelecek

        // İzin verilen HTTP metodları
        configuration.setAllowedMethods(Arrays.asList("GET","POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        // İzin verilen Header'lar (Authorization ve Content-Type önemli)
        configuration.setAllowedHeaders(Arrays.asList("Authorization", "Cache-Control", "Content-Type"));
        // Cookie gibi credential bilgilerinin gönderilmesine izin ver (JWT için genelde false olabilir ama ihtiyaca göre true)
        configuration.setAllowCredentials(true); // Eğer session cookie vs kullanılmayacaksa false olabilir.
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Ayarları tüm "/api/**" path'leri için uygula
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}