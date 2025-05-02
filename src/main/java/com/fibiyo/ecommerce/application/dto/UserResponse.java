package com.fibiyo.ecommerce.application.dto;

import com.fibiyo.ecommerce.domain.enums.AuthProvider;
import com.fibiyo.ecommerce.domain.enums.Role;
import com.fibiyo.ecommerce.domain.enums.SubscriptionType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UserResponse {
    private Long id;
    private String username;
    private String email;
    private String firstName;
    private String lastName;
    private Role role; // Sadece String olarak veya Enum? Enum olabilir.
    private boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // Profilde gösterilebilecek ek bilgiler
    private AuthProvider authProvider; // Nasıl giriş yaptığı
    private SubscriptionType subscriptionType;
    private LocalDateTime subscriptionExpiryDate;
    private int loyaltyPoints;
    // Sadece satıcı ise gösterilebilir:
    private Integer imageGenQuota; // Nullable yapalım

     // private List<AddressDto> addresses; // Adresleri de döndürebiliriz
     // Not: Şifre HASH'ini ASLA DTO'ya koyma!
}