package com.fibiyo.ecommerce.application.service;

import com.fibiyo.ecommerce.application.dto.CouponRequest;
import com.fibiyo.ecommerce.application.dto.CouponResponse;
import com.fibiyo.ecommerce.application.dto.CouponValidationResponse; // Validation DTO
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;

public interface CouponService {

    // --- Admin Operations ---
    CouponResponse createCoupon(CouponRequest couponRequest);
    CouponResponse updateCoupon(Long couponId, CouponRequest couponRequest);
    void deleteCoupon(Long couponId); // Silme (veya deaktive etme)
    Page<CouponResponse> findAllCoupons(Pageable pageable, Boolean isActive); // Filtreli listeleme
    CouponResponse findCouponById(Long couponId);
    CouponResponse findCouponByCode(String code); // Kod ile admin görüntüleme


    // --- Customer/System Operations ---
    CouponValidationResponse validateCoupon(String code, BigDecimal cartTotal); // Sepete uygulanabilir mi kontrolü

    // (Internal usage for OrderService) - Dışarı açmak şart değil
    // Coupon getValidCouponForOrder(String code);
    // void incrementCouponUsage(Coupon coupon);

}