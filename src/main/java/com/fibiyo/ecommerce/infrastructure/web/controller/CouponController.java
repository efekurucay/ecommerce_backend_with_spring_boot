package com.fibiyo.ecommerce.infrastructure.web.controller;

import com.fibiyo.ecommerce.application.dto.ApiResponse;
import com.fibiyo.ecommerce.application.dto.CouponRequest;
import com.fibiyo.ecommerce.application.dto.CouponResponse;
import com.fibiyo.ecommerce.application.dto.CouponValidationResponse;
import com.fibiyo.ecommerce.application.service.CouponService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal; // Validation için

@RestController
@RequestMapping("/api/coupons")
public class CouponController {

    private static final Logger logger = LoggerFactory.getLogger(CouponController.class);

    private final CouponService couponService;

    @Autowired
    public CouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    // --- Public/Customer Endpoint ---

    // Kupon kodunu doğrulamak için endpoint (Sepetteki toplam ile birlikte)
    @GetMapping("/validate/{code}")
    // Login gerektirmeyebilir, kodu herkes deneyebilir? Ya da @PreAuthorize eklenebilir.
    public ResponseEntity<CouponValidationResponse> validateCoupon(
            @PathVariable String code,
            @RequestParam BigDecimal cartTotal) { // Sepet toplamını query param olarak alalım
         logger.info("GET /api/coupons/validate/{} requested with cartTotal: {}", code, cartTotal);
        CouponValidationResponse validationResponse = couponService.validateCoupon(code, cartTotal);
        return ResponseEntity.ok(validationResponse);
    }


    // --- Admin Endpoints ---

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CouponResponse> createCoupon(@Valid @RequestBody CouponRequest couponRequest) {
        logger.info("POST /api/coupons requested");
        CouponResponse createdCoupon = couponService.createCoupon(couponRequest);
        return new ResponseEntity<>(createdCoupon, HttpStatus.CREATED);
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<CouponResponse>> getAllCoupons(
             @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable,
             @RequestParam(required = false) Boolean isActive) {
         logger.info("GET /api/coupons requested (Admin). IsActive Filter: {}", isActive);
        Page<CouponResponse> coupons = couponService.findAllCoupons(pageable, isActive);
        return ResponseEntity.ok(coupons);
    }

    @GetMapping("/{couponId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CouponResponse> getCouponById(@PathVariable Long couponId) {
        logger.info("GET /api/coupons/{} requested (Admin)", couponId);
        CouponResponse coupon = couponService.findCouponById(couponId);
        return ResponseEntity.ok(coupon);
    }

     @GetMapping("/code/{code}")
     @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CouponResponse> getCouponByCode(@PathVariable String code) {
          logger.info("GET /api/coupons/code/{} requested (Admin)", code);
         CouponResponse coupon = couponService.findCouponByCode(code);
          return ResponseEntity.ok(coupon);
     }


    @PutMapping("/{couponId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CouponResponse> updateCoupon(
            @PathVariable Long couponId,
            @Valid @RequestBody CouponRequest couponRequest) {
         logger.info("PUT /api/coupons/{} requested (Admin)", couponId);
        CouponResponse updatedCoupon = couponService.updateCoupon(couponId, couponRequest);
        return ResponseEntity.ok(updatedCoupon);
    }

    @DeleteMapping("/{couponId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteCoupon(@PathVariable Long couponId) {
         logger.warn("DELETE /api/coupons/{} requested (Admin)", couponId);
        couponService.deleteCoupon(couponId);
        return ResponseEntity.ok(new ApiResponse(true, "Kupon başarıyla silindi."));
    }
}