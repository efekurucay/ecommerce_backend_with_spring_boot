package com.fibiyo.ecommerce.domain.enums;

public enum OrderStatus {
    PENDING_PAYMENT,   // Ödeme Bekleniyor
    PROCESSING,        // İşleniyor (Ödeme alındı, hazırlık aşamasında)
    SHIPPED,           // Kargoya Verildi
    DELIVERED,         // Teslim Edildi
    CANCELLED_BY_CUSTOMER, // Müşteri Tarafından İptal Edildi
    CANCELLED_BY_SELLER,   // Satıcı Tarafından İptal Edildi
    CANCELLED_BY_ADMIN,    // Yönetici Tarafından İptal Edildi
    RETURN_REQUESTED,  // İade Talep Edildi
    RETURN_APPROVED,   // İade Onaylandı
    RETURN_REJECTED    // İade Reddedildi
}