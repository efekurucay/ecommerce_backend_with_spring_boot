package com.fibiyo.ecommerce.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper; // JSON için
import com.fibiyo.ecommerce.application.dto.AddressDto;
import com.fibiyo.ecommerce.application.dto.OrderItemRequest;
import com.fibiyo.ecommerce.application.dto.OrderRequest;
import com.fibiyo.ecommerce.application.dto.OrderResponse;
import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ForbiddenException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.mapper.OrderMapper;
import com.fibiyo.ecommerce.application.service.OrderService;
// Diğer servisleri (Coupon, Product) ve Repository'leri inject etmek gerekebilir
import com.fibiyo.ecommerce.domain.entity.*; // Order, OrderItem, Product, User, Coupon
import com.fibiyo.ecommerce.domain.enums.OrderStatus;
import com.fibiyo.ecommerce.domain.enums.PaymentStatus; // Enumlar
import com.fibiyo.ecommerce.infrastructure.persistence.repository.*; // Repositoryler
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Kritik: Transactional

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class OrderServiceImpl implements OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderServiceImpl.class);

    // Repositories
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository; // Gerekli olmayabilir (cascade ile kaydedilirse)
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CouponRepository couponRepository; // Kupon işlemleri için

    // Services (Opsiyonel, bazen servisler birbirini çağırabilir)
    // private final CouponService couponService;

    // Mappers & Utilities
    private final OrderMapper orderMapper;
    private final ObjectMapper objectMapper; // JSON <-> DTO dönüşümü için

    // Helper Methods (getCurrentUser etc. ProductService'teki gibi eklenebilir)
     private User getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
             throw new ForbiddenException("Erişim için kimlik doğrulaması gerekli.");
        }
        String username = authentication.getName();
        return userRepository.findByUsername(username)
                 .orElseThrow(() -> new ResourceNotFoundException("Current user not found in database: " + username));
     }

     // AddressDto -> JSON String (OrderMapper'daki helper yerine burada olabilir)
      private String convertAddressToJson(AddressDto addressDto) {
          if (addressDto == null) return null;
          try {
             return objectMapper.writeValueAsString(addressDto);
          } catch (JsonProcessingException e) {
             logger.error("Error converting AddressDto to JSON: {}", e.getMessage(), e);
             throw new BadRequestException("Adres bilgisi formatı hatalı."); // Hata fırlat
          }
      }


    @Autowired
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository, ProductRepository productRepository, UserRepository userRepository, CouponRepository couponRepository, OrderMapper orderMapper, ObjectMapper objectMapper) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.couponRepository = couponRepository;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
    }


    @Override
    @Transactional // Bu metod bir bütün: Başarısız olursa tüm değişiklikler geri alınmalı!
    public OrderResponse createOrder(OrderRequest orderRequest) {
        User customer = getCurrentUser(); // Aktif kullanıcıyı al
        logger.info("Creating order for customer ID: {}", customer.getId());

        Order order = new Order();
        order.setCustomer(customer);
        order.setStatus(OrderStatus.PENDING_PAYMENT); // Başlangıç durumu
        order.setPaymentStatus(PaymentStatus.PENDING); // Başlangıç durumu
        order.setPaymentMethod(orderRequest.getPaymentMethod()); // Ödeme yöntemi

        // Adresleri JSON'a çevirip set et
        order.setShippingAddress(convertAddressToJson(orderRequest.getShippingAddress()));
         if(orderRequest.getBillingAddress() != null) {
              order.setBillingAddress(convertAddressToJson(orderRequest.getBillingAddress()));
          } else {
             order.setBillingAddress(order.getShippingAddress()); // Fatura adresi yoksa teslimat adresi ile aynı yap
          }


        BigDecimal totalAmount = BigDecimal.ZERO; // İndirimsiz toplam
        List<OrderItem> orderItems = new ArrayList<>();

        // Sipariş kalemlerini işle
        for (OrderItemRequest itemRequest : orderRequest.getItems()) {
             Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + itemRequest.getProductId()));

            // Stok ve Aktiflik/Onay Kontrolü
             if (!product.isActive() || !product.isApproved()) {
                logger.warn("Attempt to order inactive/unapproved product ID: {} by user ID: {}", product.getId(), customer.getId());
                throw new BadRequestException("Ürün '" + product.getName() + "' şu anda satışta değil.");
            }
             if (product.getStock() < itemRequest.getQuantity()) {
                 logger.warn("Insufficient stock for product ID: {}. Requested: {}, Available: {}. By user ID: {}",
                        product.getId(), itemRequest.getQuantity(), product.getStock(), customer.getId());
                 throw new BadRequestException("Yetersiz stok: " + product.getName());
            }

            // OrderItem oluştur
            OrderItem orderItem = new OrderItem();
            orderItem.setProduct(product);
            orderItem.setQuantity(itemRequest.getQuantity());
            orderItem.setPriceAtPurchase(product.getPrice()); // O anki fiyatı kaydet
            // orderItem.setOrder(order); // addOrderItem metodu içinde set ediliyor

            orderItem.setItemTotal(
    product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()))
);


            // Ürün stoğunu azalt
            product.setStock(product.getStock() - itemRequest.getQuantity());
            productRepository.save(product); // Stoğu hemen güncelle (Transactional olduğu için sorun olursa geri alınır)

             orderItems.add(orderItem); // Listeye ekle (henüz order'a eklenmedi)
            totalAmount = totalAmount.add(orderItem.getPriceAtPurchase().multiply(BigDecimal.valueOf(itemRequest.getQuantity())));
        }

         order.setTotalAmount(totalAmount); // Ürünlerin indirimsiz toplamı
         order.setDiscountAmount(BigDecimal.ZERO); // Başlangıçta indirim sıfır
         // order.setShippingFee(...); // Kargo ücreti nasıl hesaplanacak? Şimdilik sıfır.

         // Kupon Kontrolü ve Uygulama
          Coupon appliedCoupon = null;
         if (orderRequest.getCouponCode() != null && !orderRequest.getCouponCode().isBlank()) {
              appliedCoupon = couponRepository.findByCodeAndIsActiveTrueAndExpiryDateAfter(orderRequest.getCouponCode(), LocalDateTime.now())
                     .orElseThrow(() -> new BadRequestException("Geçersiz veya süresi dolmuş kupon kodu."));

              // Kupon kullanım koşullarını kontrol et
             if (totalAmount.compareTo(appliedCoupon.getMinPurchaseAmount()) < 0) {
                  throw new BadRequestException("Bu kuponu kullanmak için minimum alışveriş tutarı: " + appliedCoupon.getMinPurchaseAmount());
             }
              if (appliedCoupon.isUsageLimitReached()) {
                 throw new BadRequestException("Bu kupon kullanım limitine ulaştı.");
              }

              // İndirimi hesapla
              BigDecimal discount = BigDecimal.ZERO;
             if (appliedCoupon.getDiscountType() == com.fibiyo.ecommerce.domain.enums.DiscountType.FIXED_AMOUNT) {
                  discount = appliedCoupon.getDiscountValue();
              } else if (appliedCoupon.getDiscountType() == com.fibiyo.ecommerce.domain.enums.DiscountType.PERCENTAGE) {
                  discount = totalAmount.multiply(appliedCoupon.getDiscountValue().divide(BigDecimal.valueOf(100)));
              }
             discount = discount.min(totalAmount); // İndirim toplam tutarı geçemez

              order.setCoupon(appliedCoupon);
              order.setDiscountAmount(discount);
              appliedCoupon.incrementTimesUsed(); // Kullanım sayısını artır
             couponRepository.save(appliedCoupon); // Güncellenmiş kuponu kaydet
         }

         // OrderItem'ları Order'a ekle (bu, OrderItem -> Order ilişkisini de kurar)
        // Order'ı kaydetmeden önce items listesini set etmeliyiz ki cascade çalışsın.
         // Veya Order'ı kaydedip, sonra Item'ları set edip tekrar Order'ı kaydetmek gerekir.
         // Cascade kullanıyorsak ve addOrderItem kullanıyorsak:
        orderItems.forEach(order::addOrderItem); // Bu item.setOrder(this) çağırır


         // Siparişi ve ilişkili OrderItem'ları (cascade ile) kaydet
         Order savedOrder = orderRepository.save(order);
         logger.info("Order ID: {} created successfully for customer ID: {}. Total Amount (Pre-discount): {}",
                savedOrder.getId(), customer.getId(), savedOrder.getTotalAmount());

        // TODO: Sipariş sonrası olayları tetikle (Ödeme işlemi başlatma, bildirim gönderme vb.)
        // paymentService.initiatePayment(savedOrder.getId(), savedOrder.getPaymentMethod());
         // notificationService.sendOrderPlacedNotification(customer, savedOrder);

         return orderMapper.toOrderResponse(savedOrder);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OrderResponse> findMyOrders(Pageable pageable) {
        User customer = getCurrentUser();
         logger.debug("Fetching orders for customer ID: {}", customer.getId());
         Page<Order> orderPage = orderRepository.findByCustomerIdOrderByOrderDateDesc(customer.getId(), pageable);
         return orderPage.map(orderMapper::toOrderResponse); // Page.map() ile dönüşüm
    }


    // Diğer OrderService metodlarının implementasyonları buraya gelecek...
    @Override
     @Transactional(readOnly = true)
    public OrderResponse findMyOrderById(Long orderId) {
         User customer = getCurrentUser();
          logger.debug("Fetching order ID: {} for customer ID: {}", orderId, customer.getId());
          Order order = orderRepository.findById(orderId)
                  .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
         // Siparişin bu müşteriye ait olduğunu kontrol et
          if (!order.getCustomer().getId().equals(customer.getId())) {
               throw new ForbiddenException("Bu siparişi görüntüleme yetkiniz yok.");
          }
          return orderMapper.toOrderResponse(order);
    }

    @Override
    @Transactional
    public OrderResponse cancelMyOrder(Long orderId) {
         User customer = getCurrentUser();
          logger.warn("Customer ID: {} attempting to cancel order ID: {}", customer.getId(), orderId);
          Order order = orderRepository.findById(orderId)
                  .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

          // Sahiplik kontrolü
         if (!order.getCustomer().getId().equals(customer.getId())) {
             throw new ForbiddenException("Bu siparişi iptal etme yetkiniz yok.");
         }

          // Siparişin iptal edilebilir durumda olup olmadığını kontrol et (Örn: henüz kargolanmamış olmalı)
          if (order.getStatus() == OrderStatus.SHIPPED || order.getStatus() == OrderStatus.DELIVERED || order.getStatus().name().startsWith("CANCELLED") || order.getStatus().name().startsWith("RETURN")) {
             throw new BadRequestException("Bu sipariş artık iptal edilemez. Durum: " + order.getStatus());
         }

        // Durumu güncelle
         order.setStatus(OrderStatus.CANCELLED_BY_CUSTOMER);
         order.setPaymentStatus(PaymentStatus.REFUNDED); // veya PENDING_REFUND?

         // TODO: Stokları iade et!
         restoreStockForOrderItems(order.getOrderItems());

         // TODO: Kullanılan kupon varsa kullanım sayısını azalt?
         if(order.getCoupon() != null) {
             // CouponService veya repository üzerinden decrement
         }

        // TODO: Ödeme iadesi işlemini tetikle (PaymentService)
        // paymentService.initiateRefund(orderId);


         Order cancelledOrder = orderRepository.save(order);
         logger.info("Order ID: {} cancelled successfully by customer ID: {}", orderId, customer.getId());
         return orderMapper.toOrderResponse(cancelledOrder);
    }
    // Stok iade metodu
     private void restoreStockForOrderItems(List<OrderItem> items) {
        for(OrderItem item : items) {
             Product product = item.getProduct();
             // Ürün hala varsa stoğu artır (ürün silinmiş olabilir - ON DELETE SET NULL)
             if(product != null) {
                 product.setStock(product.getStock() + item.getQuantity());
                 productRepository.save(product);
                 logger.debug("Restored {} stock for product ID: {}", item.getQuantity(), product.getId());
             } else {
                logger.warn("Could not restore stock for order item ID: {}, product not found (likely deleted).", item.getId());
             }
        }
     }

    // ... (findAllOrders, findSellerOrders, findOrderById, updateOrderStatus etc.)
     @Override
     @Transactional(readOnly = true)
     public Page<OrderResponse> findAllOrders(Pageable pageable, Long customerId, OrderStatus status /*...*/) {
          // Admin rol kontrolü yapılmalı
          logger.debug("Admin finding all orders...");
           Specification<Order> spec = Specification.where(null); // Filtreler için spec oluşturulacak
          // if (customerId != null) spec = spec.and(OrderSpecifications.hasCustomer(customerId));
          // if (status != null) spec = spec.and(OrderSpecifications.hasStatus(status));
           Page<Order> orderPage = orderRepository.findAll(spec, pageable);
          return orderPage.map(orderMapper::toOrderResponse);
     }

      @Override
      @Transactional(readOnly = true)
      public Page<OrderResponse> findSellerOrders(Pageable pageable, OrderStatus status) {
         // Seller rol kontrolü veya Admin
          User seller = getCurrentUser();
          logger.debug("Seller/Admin finding orders for seller ID: {}", seller.getId());
           // TODO: Specification veya JPQL ile satıcının ürünlerini içeren siparişleri filtrele
           Page<Order> orderPage = Page.empty(); // Geçici
          return orderPage.map(orderMapper::toOrderResponse);
      }

     @Override
     @Transactional(readOnly = true)
     public OrderResponse findOrderById(Long orderId) {
         // Admin/Seller rol kontrolü
         logger.debug("Finding order by ID (Admin/Seller): {}", orderId);
          Order order = orderRepository.findById(orderId)
                   .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));
          return orderMapper.toOrderResponse(order);
      }

     @Override
     @Transactional
      public OrderResponse updateOrderStatus(Long orderId, OrderStatus newStatus) {
          // Admin/Seller rol kontrolü
         logger.info("Updating order status for ID: {} to {}", orderId, newStatus);
           Order order = orderRepository.findById(orderId)
                 .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

          // TODO: Geçerli durum geçişlerini kontrol et (örn: DELIVERED'dan SHIPPED'e dönülemez)

          order.setStatus(newStatus);

          // Duruma göre ek işlemler (Bildirim gönder, ödeme durumu güncelle vb.)
           if(newStatus == OrderStatus.SHIPPED && order.getTrackingNumber() == null) {
               logger.warn("Order ID {} marked as SHIPPED but no tracking number provided.", orderId);
           }
           if(newStatus == OrderStatus.DELIVERED) {
              // Opsiyonel: Otomatik ödeme durumu tamamlama vs.
           }
           if(newStatus.name().startsWith("CANCELLED")) {
               // Stok iadesi (eğer daha önce yapılmadıysa), refund işlemleri...
                if(order.getPaymentStatus() != PaymentStatus.REFUNDED){
                   restoreStockForOrderItems(order.getOrderItems());
                   order.setPaymentStatus(PaymentStatus.REFUNDED); // Otomatik refund kabul edelim şimdilik
               }
           }


           Order updatedOrder = orderRepository.save(order);
          logger.info("Order ID: {} status updated to {}", orderId, newStatus);
          // TODO: Müşteriye bildirim gönder
          return orderMapper.toOrderResponse(updatedOrder);
      }

       @Override
       @Transactional
       public OrderResponse addTrackingNumber(Long orderId, String trackingNumber) {
          // Admin/Seller rol kontrolü
          logger.info("Adding tracking number '{}' to order ID: {}", trackingNumber, orderId);
           Order order = orderRepository.findById(orderId)
                  .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

          // Sipariş kargolanmış veya işleniyor olmalı
           if (order.getStatus() != OrderStatus.PROCESSING && order.getStatus() != OrderStatus.SHIPPED) {
               throw new BadRequestException("Takip numarası eklemek için sipariş durumu uygun değil: " + order.getStatus());
          }

          order.setTrackingNumber(trackingNumber);
           // Takip numarası eklendiyse durumu SHIPPED yapabiliriz (opsiyonel)
           if(order.getStatus() == OrderStatus.PROCESSING){
               order.setStatus(OrderStatus.SHIPPED);
           }

           Order updatedOrder = orderRepository.save(order);
          logger.info("Tracking number added for order ID: {}", orderId);
           // TODO: Müşteriye bildirim gönder
          return orderMapper.toOrderResponse(updatedOrder);
       }

}