package com.fibiyo.ecommerce.application.service.impl;

import com.fibiyo.ecommerce.application.dto.ReviewRequest;
import com.fibiyo.ecommerce.application.dto.ReviewResponse;
import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ForbiddenException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.mapper.ReviewMapper;
import com.fibiyo.ecommerce.application.service.ReviewService;
import com.fibiyo.ecommerce.domain.entity.Product;
import com.fibiyo.ecommerce.domain.entity.Review;
import com.fibiyo.ecommerce.domain.entity.User;
import com.fibiyo.ecommerce.domain.enums.OrderStatus;
import com.fibiyo.ecommerce.domain.enums.Role;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.OrderRepository;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.ProductRepository;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.ReviewRepository;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.UserRepository;
// OrderRepository (satın alma kontrolü için) gerekebilir
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

// Review Specifications
import static com.fibiyo.ecommerce.infrastructure.persistence.specification.ReviewSpecifications.*;


@Service
public class ReviewServiceImpl implements ReviewService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewServiceImpl.class);

    private final ReviewRepository reviewRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final ReviewMapper reviewMapper;
    private final OrderRepository orderRepository;

     // Opsiyonel: private final OrderRepository orderRepository;

     // Helper Methods (getCurrentUser etc. önceki servislerden alınabilir)
      private User getCurrentUser() {
          Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
         if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
             throw new ForbiddenException("Erişim için kimlik doğrulaması gerekli.");
          }
         String username = authentication.getName();
         return userRepository.findByUsername(username)
                  .orElseThrow(() -> new ResourceNotFoundException("Current user not found in database: " + username));
      }
       private void checkAdminRole() {
          User currentUser = getCurrentUser();
          if (currentUser.getRole() != Role.ADMIN) {
             throw new ForbiddenException("Bu işlem için Admin yetkisi gerekli.");
         }
       }


    @Autowired
    public ReviewServiceImpl(ReviewRepository reviewRepository, ProductRepository productRepository, UserRepository userRepository, ReviewMapper reviewMapper, OrderRepository orderRepository) {
        this.reviewRepository = reviewRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.reviewMapper = reviewMapper;
        this.orderRepository = orderRepository; // OrderRepository'yi ekle
    }

    @Override
    @Transactional
    public ReviewResponse addReview(Long productId, ReviewRequest reviewRequest) {
        User customer = getCurrentUser(); // Aktif kullanıcıyı al
        logger.info("Customer ID: {} attempting to add review for product ID: {}", customer.getId(), productId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + productId));

        // Kullanıcı bu ürüne daha önce yorum yapmış mı? (Unique constraint ile DB seviyesinde de kontrol ediliyor)
        if (reviewRepository.existsByProductIdAndCustomerId(productId, customer.getId())) {
            logger.warn("Customer ID: {} already reviewed product ID: {}", customer.getId(), productId);
            throw new BadRequestException("Bu ürüne zaten yorum yaptınız.");
        }

     

        Review review = reviewMapper.toReview(reviewRequest);
        review.setProduct(product);
        review.setCustomer(customer);
        review.setApproved(true); // Varsayılan olarak onaylı (İş kuralına göre false olabilir)

      // Opsiyonel: Kullanıcının bu ürünü sipariş edip etmediğini kontrol et
orderRepository.findFirstByCustomerIdAndOrderItems_Product_IdAndStatusOrderByOrderDateDesc(
    customer.getId(), productId, OrderStatus.DELIVERED
).ifPresent(review::setOrder);

        Review savedReview = reviewRepository.save(review);
        logger.info("Review added successfully with ID: {} for product ID: {} by customer ID: {}", savedReview.getId(), productId, customer.getId());

         // Ürünün ortalama puanını ve yorum sayısını güncelle
        updateProductRatingAndCount(productId);


        return reviewMapper.toReviewResponse(savedReview);
    }

    @Override
    @Transactional
    public void deleteMyReview(Long reviewId) {
        User customer = getCurrentUser();
        logger.warn("Customer ID: {} attempting to delete review ID: {}", customer.getId(), reviewId);

        Review review = reviewRepository.findById(reviewId)
                 .orElseThrow(() -> new ResourceNotFoundException("Review not found with id: " + reviewId));

        // Yorumun sahibi mi kontrol et
        if (!review.getCustomer().getId().equals(customer.getId())) {
            throw new ForbiddenException("Bu yorumu silme yetkiniz yok.");
        }

        Long productId = review.getProduct().getId(); // Puanı güncellemek için Product ID'yi al
        reviewRepository.delete(review);
         logger.info("Review ID: {} deleted successfully by customer ID: {}", reviewId, customer.getId());

        // Ürünün ortalama puanını ve yorum sayısını güncelle
        updateProductRatingAndCount(productId);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> findReviewsByProduct(Long productId, Pageable pageable, boolean approvedOnly) {
         logger.debug("Finding reviews for product ID: {}. ApprovedOnly: {}", productId, approvedOnly);
        // Ürün var mı kontrolü (opsiyonel ama iyi pratik)
        if (!productRepository.existsById(productId)) {
             throw new ResourceNotFoundException("Product not found with id: " + productId);
        }

        Page<Review> reviewPage;
        if (approvedOnly) {
            // Sadece onaylı yorumları getir
             // Specification kullanmak daha esnek olabilir ama şimdilik derived query yeterli.
             reviewPage = reviewRepository.findByProductIdAndIsApprovedTrue(productId, pageable);
         } else {
            // Tüm yorumları getir (Admin vs. için)
            reviewPage = reviewRepository.findByProductId(productId, pageable);
         }

        return reviewPage.map(reviewMapper::toReviewResponse);
    }

    // Ürünün rating ve count alanlarını güncelleyen private metot
    private void updateProductRatingAndCount(Long productId) {
         try {
            // Not: Ayrı transaction'larda yapılabilir veya lock mekanizması düşünülebilir çok yüksek yük altında.
             // Bu metodun transactional olması @Transactional'den dolayı yeterli olmalı.
             BigDecimal avgRating = reviewRepository.calculateAverageRatingByProductId(productId);
             long reviewCount = reviewRepository.countByProductIdAndIsApprovedTrue(productId);

            Product product = productRepository.findById(productId).orElse(null); // Varsa bul
             if(product != null) {
                 product.setAverageRating(avgRating != null ? avgRating : BigDecimal.ZERO);
                 product.setReviewCount((int) reviewCount);
                 productRepository.save(product);
                  logger.info("Updated product ID: {} rating to {} and review count to {}", productId, product.getAverageRating(), product.getReviewCount());
             }
         } catch (Exception e) {
             logger.error("Error updating product rating/count for product ID {}: {}", productId, e.getMessage(), e);
             // Bu hata ana işlemi durdurmamalı ama loglanmalı.
         }
     }

    // --- Admin Operations Implementation ---

    @Override
    @Transactional(readOnly = true)
    public Page<ReviewResponse> findAllReviews(Pageable pageable, Boolean isApproved) {
         checkAdminRole();
          logger.debug("Admin finding all reviews. IsApproved filter: {}", isApproved);
          Specification<Review> spec = Specification.where(null);
          if (isApproved != null) {
             spec = spec.and(isApproved(isApproved));
         }
         Page<Review> reviewPage = reviewRepository.findAll(spec, pageable);
         return reviewPage.map(reviewMapper::toReviewResponse);
    }

    @Override
    @Transactional
    public ReviewResponse approveReview(Long reviewId) {
        checkAdminRole();
         logger.info("Admin approving review ID: {}", reviewId);
         Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with id: " + reviewId));
        boolean needsRatingUpdate = !review.isApproved(); // Eğer zaten onaylıysa ratingi tekrar hesaplamaya gerek yok
         review.setApproved(true);
         Review savedReview = reviewRepository.save(review);
         logger.info("Review ID: {} approved by admin.", reviewId);
          if(needsRatingUpdate && savedReview.getProduct() != null) {
            updateProductRatingAndCount(savedReview.getProduct().getId());
        }
         return reviewMapper.toReviewResponse(savedReview);
    }

    @Override
    @Transactional
    public ReviewResponse rejectReview(Long reviewId) {
         checkAdminRole();
         logger.warn("Admin rejecting review ID: {}", reviewId);
         Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with id: " + reviewId));
        boolean needsRatingUpdate = review.isApproved(); // Eğer onaylı idiyse ve şimdi reddediliyorsa ratingi güncellemek lazım
         review.setApproved(false); // Onayı kaldır
         Review savedReview = reviewRepository.save(review);
        logger.info("Review ID: {} rejected (disapproved) by admin.", reviewId);
         if(needsRatingUpdate && savedReview.getProduct() != null) {
             updateProductRatingAndCount(savedReview.getProduct().getId());
         }
         return reviewMapper.toReviewResponse(savedReview);
     }


    @Override
    @Transactional
    public void deleteReviewByAdmin(Long reviewId) {
        checkAdminRole();
         logger.warn("Admin deleting review ID: {}", reviewId);
          Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review not found with id: " + reviewId));
          Long productId = review.getProduct() != null ? review.getProduct().getId() : null;
          reviewRepository.delete(review);
        logger.info("Review ID: {} deleted by admin.", reviewId);
         if(productId != null){
              updateProductRatingAndCount(productId); // Puanı güncelle
          }
    }
}

