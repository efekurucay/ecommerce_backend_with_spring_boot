package com.fibiyo.ecommerce.application.service.impl; // veya com.fibiyo.ecommerce.application.service;

import com.fibiyo.ecommerce.application.dto.JwtAuthenticationResponse;
import com.fibiyo.ecommerce.application.dto.LoginRequest;
import com.fibiyo.ecommerce.application.dto.RegisterRequest;
import com.fibiyo.ecommerce.application.exception.BadRequestException; // Custom exception
import com.fibiyo.ecommerce.application.service.AuthService;
import com.fibiyo.ecommerce.domain.entity.User;
import com.fibiyo.ecommerce.domain.enums.Role;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.UserRepository;
import com.fibiyo.ecommerce.infrastructure.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority; // Roller için
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails; // Principal'ı cast etmek için
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List; // Roller listesi için
import java.util.stream.Collectors; // Roller için

@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

    // Gerekli bağımlılıkları @Autowired ile inject edelim
    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    @Autowired
    public AuthServiceImpl(AuthenticationManager authenticationManager,
                           UserRepository userRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider tokenProvider) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    @Override
    @Transactional(readOnly = true) // Sadece okuma ve token üretimi var
    public JwtAuthenticationResponse authenticateUser(LoginRequest loginRequest) {
        logger.info("Authentication attempt for user: {}", loginRequest.getUsernameOrEmail());
        try {
            // Spring Security'nin kimlik doğrulama mekanizmasını kullan
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsernameOrEmail(),
                            loginRequest.getPassword()
                    )
            );

            // Authentication nesnesini Security Context'e yerleştir (opsiyonel ama iyi pratik)
            SecurityContextHolder.getContext().setAuthentication(authentication);

            // Başarılı kimlik doğrulama sonrası JWT oluştur
            String jwt = tokenProvider.generateToken(authentication);
            logger.debug("JWT generated successfully for user: {}", loginRequest.getUsernameOrEmail());

            // Yanıt için ek kullanıcı bilgilerini alalım
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            // UserDetails üzerinden rolleri al
             List<String> roles = userDetails.getAuthorities().stream()
                     .map(GrantedAuthority::getAuthority)
                     .collect(Collectors.toList());

            // Veritabanından tam User nesnesini almak da mümkün ama username ve ID yeterli olabilir.
             User user = userRepository.findByUsername(userDetails.getUsername())
                     .orElseThrow(() -> new BadRequestException("Authenticated user not found in database - unexpected.")); // Bu olmamalı

            logger.info("User '{}' authenticated successfully. Roles: {}", user.getUsername(), roles);

            return new JwtAuthenticationResponse(jwt, user.getId(), user.getUsername(), user.getEmail(), roles);

        } catch (Exception e) {
            logger.error("Authentication failed for user: {}", loginRequest.getUsernameOrEmail(), e);
            // Direkt AuthenticationException yerine daha genel bir exception fırlatmak veya özel hata DTO dönmek daha iyi olabilir.
            // Şimdilik BadRequestException kullanalım.
            throw new BadRequestException("Giriş başarısız: Kullanıcı adı veya şifre hatalı.", e);
        }
    }

    @Override
    @Transactional // Veritabanına yazma işlemi var
    public User registerUser(RegisterRequest registerRequest) {
        logger.info("Registration attempt for username: {}", registerRequest.getUsername());

        // Kullanıcı adı mevcut mu?
        if (userRepository.existsByUsername(registerRequest.getUsername())) {
            logger.warn("Username '{}' is already taken.", registerRequest.getUsername());
            throw new BadRequestException("Kullanıcı adı zaten alınmış!");
        }

        // E-posta mevcut mu?
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            logger.warn("Email '{}' is already in use.", registerRequest.getEmail());
            throw new BadRequestException("E-posta adresi zaten kullanılıyor!");
        }

         // İstediğimiz roller dışında (örn: ADMIN) kayıt olunmasını engelle
        if (registerRequest.getRole() == Role.ADMIN) {
            logger.warn("Registration attempt with ADMIN role denied for username '{}'", registerRequest.getUsername());
            throw new BadRequestException("Bu rolle kayıt olamazsınız.");
        }


        // Yeni Kullanıcı Oluşturma
        User user = new User();
        user.setFirstName(registerRequest.getFirstName());
        user.setLastName(registerRequest.getLastName());
        user.setUsername(registerRequest.getUsername());
        user.setEmail(registerRequest.getEmail());
        user.setPasswordHash(passwordEncoder.encode(registerRequest.getPassword())); // Şifreyi hash'le
        user.setRole(registerRequest.getRole());
        user.setActive(true); // Yeni kullanıcılar varsayılan olarak aktif
        user.setAuthProvider(null); // Local kayıt olduğu için provider null
        user.setProviderId(null);
        // Varsayılan abonelik, puan, kota vs. User entity'sinde default olarak ayarlı

        User savedUser = userRepository.save(user);
        logger.info("User '{}' registered successfully with role {}.", savedUser.getUsername(), savedUser.getRole());

        // Burada kayıt sonrası email gönderme gibi işlemler de eklenebilir (Async olarak)

        return savedUser; // Kaydedilmiş User nesnesini dön (ID vb. içerir)
    }
}