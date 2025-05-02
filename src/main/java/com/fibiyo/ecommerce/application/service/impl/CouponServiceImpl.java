package com.fibiyo.ecommerce.application.service.impl;

import com.fibiyo.ecommerce.application.dto.CouponRequest;
import com.fibiyo.ecommerce.application.dto.CouponResponse;
import com.fibiyo.ecommerce.application.dto.CouponValidationResponse;
import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ForbiddenException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.mapper.CouponMapper;
import com.fibiyo.ecommerce.application.service.CouponService;
import com.fibiyo.ecommerce.domain.entity.Coupon;
import com.fibiyo.ecommerce.domain.enums.Role;
import com.fibiyo.ecommerce.domain.entity.User;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.CouponRepository;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Transactional
import jakarta.persistence.criteria.Predicate; // Specification için


import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class CouponServiceImpl implements CouponService {

    private static final Logger logger = LoggerFactory.getLogger(CouponServiceImpl.class);

    private final CouponRepository couponRepository;
    private final CouponMapper couponMapper;
    private final UserRepository userRepository; // Admin yetkisi kontrolü için


     // Helper
     private User getCurrentUser() {
          return userRepository.findByUsername(SecurityContextHolder.getContext().getAuthentication().getName())
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found"));
     }
      private void checkAdminRole() {
         User currentUser = getCurrentUser();
         if (currentUser.getRole() != Role.ADMIN) {
            throw new ForbiddenException("Bu işlem için Admin yetkisi gerekli.");
        }
      }

     // Yardımcı metot: Entity -> Response dönüşümü (isValid vb. alanları set etmek için)
     private CouponResponse mapToResponseWithStatus(Coupon coupon) {
         CouponResponse response = couponMapper.toCouponResponse(coupon);
         response.setExpired(coupon.isExpired());
         response.setUsageLimitReached(coupon.isUsageLimitReached());
         response.setValid(coupon.isValid()); // Tüm kontrolleri içeren metodu çağır
         return response;
     }


    @Autowired
    public CouponServiceImpl(CouponRepository couponRepository, CouponMapper couponMapper, UserRepository userRepository) {
        this.couponRepository = couponRepository;
        this.couponMapper = couponMapper;
        this.userRepository = userRepository;
    }

    // --- Admin Operations Implementation ---

    @Override
    @Transactional
    public CouponResponse createCoupon(CouponRequest couponRequest) {
        checkAdminRole(); // Yetki kontrolü
        logger.info("Admin creating coupon with code: {}", couponRequest.getCode());

        // Kupon kodu unique mi kontrol et
        if (couponRepository.existsByCode(couponRequest.getCode())) {
            logger.warn("Coupon code '{}' already exists.", couponRequest.getCode());
            throw new BadRequestException("Bu kupon kodu zaten mevcut.");
        }

        Coupon coupon = couponMapper.toCoupon(couponRequest);
        Coupon savedCoupon = couponRepository.save(coupon);
        logger.info("Coupon '{}' created successfully with ID: {}", savedCoupon.getCode(), savedCoupon.getId());
        return mapToResponseWithStatus(savedCoupon); // Geçerlilik durumuyla dön
    }

    @Override
    @Transactional
    public CouponResponse updateCoupon(Long couponId, CouponRequest couponRequest) {
         checkAdminRole();
        logger.info("Admin updating coupon with ID: {}", couponId);

         Coupon existingCoupon = couponRepository.findById(couponId)
                 .orElseThrow(() -> new ResourceNotFoundException("Coupon not found with id: " + couponId));

         // Kod değişiyorsa unique kontrolü yap (ama genelde kod değişmez)
         /*
         if (!existingCoupon.getCode().equalsIgnoreCase(couponRequest.getCode())) {
             if (couponRepository.existsByCode(couponRequest.getCode())) {
                 throw new BadRequestException("Yeni kupon kodu '" + couponRequest.getCode() + "' zaten mevcut.");
             }
         }
          */

        couponMapper.updateCouponFromRequest(couponRequest, existingCoupon);
         // Kupon kodu update edilmediği için target=code ignore edildi mapper'da

         Coupon updatedCoupon = couponRepository.save(existingCoupon);
        logger.info("Coupon ID: {} updated successfully.", updatedCoupon.getId());
         return mapToResponseWithStatus(updatedCoupon);
    }

    @Override
    @Transactional
    public void deleteCoupon(Long couponId) {
        checkAdminRole();
         logger.warn("Admin deleting coupon with ID: {}", couponId);
         if (!couponRepository.existsById(couponId)) {
             throw new ResourceNotFoundException("Coupon not found with id: " + couponId);
         }
         // Gerçek silme yerine isActive=false yapmak daha güvenli olabilir. Şimdilik silelim.
        couponRepository.deleteById(couponId);
         logger.info("Coupon ID: {} deleted successfully.", couponId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CouponResponse> findAllCoupons(Pageable pageable, Boolean isActive) {
         checkAdminRole();
          logger.debug("Admin fetching all coupons. IsActive filter: {}", isActive);

           // Specification ile filtreleme
          Specification<Coupon> spec = (root, query, cb) -> {
               if (isActive != null) {
                   return cb.equal(root.get("isActive"), isActive);
              }
               return cb.conjunction(); // Filtre yoksa her şeyi getir
          };

          Page<Coupon> couponPage = couponRepository.findAll(spec, pageable);

         // mapToResponseWithStatus ile dönüşüm yap
         return couponPage.map(this::mapToResponseWithStatus);
    }

     @Override
     @Transactional(readOnly = true)
    public CouponResponse findCouponById(Long couponId) {
         checkAdminRole();
         logger.debug("Admin fetching coupon by ID: {}", couponId);
           Coupon coupon = couponRepository.findById(couponId)
                  .orElseThrow(() -> new ResourceNotFoundException("Coupon not found with id: " + couponId));
           return mapToResponseWithStatus(coupon);
    }

     @Override
     @Transactional(readOnly = true)
     public CouponResponse findCouponByCode(String code) {
          checkAdminRole();
           logger.debug("Admin fetching coupon by code: {}", code);
           Coupon coupon = couponRepository.findByCode(code)
                  .orElseThrow(() -> new ResourceNotFoundException("Coupon not found with code: " + code));
           return mapToResponseWithStatus(coupon);
     }

    // --- Customer/System Operations Implementation ---

    @Override
    @Transactional(readOnly = true) // Sadece okuma işlemi
    public CouponValidationResponse validateCoupon(String code, BigDecimal cartTotal) {
        logger.debug("Validating coupon code: {} for cart total: {}", code, cartTotal);

         Optional<Coupon> couponOpt = couponRepository.findByCode(code);

         if (couponOpt.isEmpty()) {
             return new CouponValidationResponse(false, "Geçersiz kupon kodu.", code, null);
         }

         Coupon coupon = couponOpt.get();

        // Entity'deki helper metodları kullanalım
         if (!coupon.isValid()) {
             String message = "Kupon geçerli değil.";
             if(!coupon.isActive()) message = "Kupon aktif değil.";
             else if(coupon.isExpired()) message = "Kuponun süresi dolmuş.";
             else if(coupon.isUsageLimitReached()) message = "Kupon kullanım limitine ulaşmış.";
            return new CouponValidationResponse(false, message, code, null);
        }

        // Minimum sepet tutarını kontrol et
         if (cartTotal.compareTo(coupon.getMinPurchaseAmount()) < 0) {
             return new CouponValidationResponse(false, "Kuponu kullanmak için minimum sepet tutarı: " + coupon.getMinPurchaseAmount(), code, null);
         }

        // Kupon geçerli, potansiyel indirim miktarını hesapla (Frontend'e göstermek için)
         BigDecimal discountAmount = BigDecimal.ZERO;
          if (coupon.getDiscountType() == com.fibiyo.ecommerce.domain.enums.DiscountType.FIXED_AMOUNT) {
              discountAmount = coupon.getDiscountValue();
          } else if (coupon.getDiscountType() == com.fibiyo.ecommerce.domain.enums.DiscountType.PERCENTAGE) {
              discountAmount = cartTotal.multiply(coupon.getDiscountValue().divide(BigDecimal.valueOf(100)));
          }
         discountAmount = discountAmount.min(cartTotal); // İndirim sepet tutarını geçemez

         logger.info("Coupon code '{}' is valid. Calculated discount: {}", code, discountAmount);
         return new CouponValidationResponse(true, "Kupon başarıyla doğrulandı.", code, discountAmount);
    }

    // --- Internal Usage ---
    // Bu metodları direkt OrderService içinde de implemente edebilirsin veya buradan çağırabilirsin.
    /*
    @Override
    public Coupon getValidCouponForOrder(String code) {
        return couponRepository.findByCodeAndIsActiveTrueAndExpiryDateAfter(code, LocalDateTime.now())
               .orElseThrow(() -> new BadRequestException("Geçersiz veya süresi dolmuş kupon kodu: " + code));
        // Ek kontroller (minPurchase vs.) OrderService'te yapılmalı.
    }

    @Override
    @Transactional // Sayaç güncellemesi yazma işlemi
    public void incrementCouponUsage(Coupon coupon) {
       if (coupon != null && !coupon.isUsageLimitReached()) { // Limiti tekrar kontrol et
            coupon.incrementTimesUsed();
           couponRepository.save(coupon);
           logger.info("Incremented usage count for coupon ID: {}", coupon.getId());
       }
    }
    */

}