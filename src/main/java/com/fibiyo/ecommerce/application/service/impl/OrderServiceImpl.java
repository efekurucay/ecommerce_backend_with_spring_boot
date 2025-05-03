package com.fibiyo.ecommerce.application.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper; // JSON için
import com.fibiyo.ecommerce.application.dto.AddressDto;
import com.fibiyo.ecommerce.application.dto.CartResponse;
import com.fibiyo.ecommerce.application.dto.OrderItemRequest;
import com.fibiyo.ecommerce.application.dto.OrderRequest;
import com.fibiyo.ecommerce.application.dto.OrderResponse;
import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ForbiddenException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.mapper.OrderMapper;
import com.fibiyo.ecommerce.application.service.OrderService;
// Diğer servisleri (Coupon, Product) ve Repository'leri inject etmek gerekebilir

import com.fibiyo.ecommerce.application.service.CartService; // Sepet işlemleri için
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
    private final CartRepository cartRepository; // Kullanıcının sepetini almak için
    private final CartService cartService;

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
    public OrderServiceImpl(OrderRepository orderRepository, OrderItemRepository orderItemRepository, ProductRepository productRepository, UserRepository userRepository, CouponRepository couponRepository, OrderMapper orderMapper, ObjectMapper objectMapper, CartRepository cartRepository, CartService cartService) {
        this.orderRepository = orderRepository;
        this.orderItemRepository = orderItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.couponRepository = couponRepository;
        this.orderMapper = orderMapper;
        this.objectMapper = objectMapper;
        this.cartRepository = cartRepository; // Sepet işlemleri için
        this.cartService = cartService; // Sepet işlemleri için
    }


    // ... (OrderServiceImpl içinde) ...
@Override
@Transactional
public OrderResponse createOrder(OrderRequest orderRequest) { // Parametre artık items içermiyor
    User customer = getCurrentUser();
    CartResponse cartResponse = cartService.getCartForCurrentUser(); // sepeti al

    Cart cart = cartService.getOrCreateCartForUser(customer);


    // Eğer Cart nesnesine (entity) ihtiyacın varsa, CartService'e yeni metot eklenebilir:
    
    // Alternatif olarak, CartServiceImpl içinde bu metodu public hale getirip çağırabilirsin
    

    if (cart.getItems() == null || cart.getItems().isEmpty()) {
         throw new BadRequestException("Sipariş oluşturmak için sepetinizde ürün bulunmalıdır.");
     }
    logger.info("Creating order for customer ID: {} from cart ID: {}", customer.getId(), cart.getId());

    Order order = new Order();
    order.setCustomer(customer);
    order.setStatus(OrderStatus.PENDING_PAYMENT);
    order.setPaymentStatus(PaymentStatus.PENDING);
    order.setPaymentMethod(orderRequest.getPaymentMethod()); // Request'ten ödeme yöntemini al
    order.setShippingAddress(convertAddressToJson(orderRequest.getShippingAddress()));
    order.setBillingAddress(convertAddressToJson(orderRequest.getBillingAddress() != null ? orderRequest.getBillingAddress() : orderRequest.getShippingAddress()));


    BigDecimal totalAmount = BigDecimal.ZERO;

    // === Sipariş kalemlerini SEPETTEN ALarak işle ===
    for (CartItem cartItem : cart.getItems()) { // Cart entity'sinden item'ları al
         Product product = cartItem.getProduct(); // Cart fetch ile geldiyse product null olmamalı
         int quantity = cartItem.getQuantity();

         // Ürünü tekrar DB'den çekmek (locking için) veya sadece cartItem'daki bilgiyi kullanmak?
         // Stok kontrolü için en güncel bilgiyi alalım:
         Product currentProductState = productRepository.findById(product.getId())
                  .orElseThrow(() -> new ResourceNotFoundException("Product (ID: "+ product.getId() +") found in cart but not in DB! Data inconsistency."));


        // Stok ve Aktiflik/Onay Kontrolü
         if (!currentProductState.isActive() || !currentProductState.isApproved()) {
             logger.warn("Attempt to order inactive/unapproved product ID: {} found in cart ID: {}", product.getId(), cart.getId());
             // Bu ürünü siparişe eklemeyip kullanıcıya bilgi mi versek? Yoksa tüm siparişi mi iptal etsek?
             // Şimdilik hata fırlatalım:
             throw new BadRequestException("Sepetinizdeki ürün '" + currentProductState.getName() + "' artık mevcut değil.");
        }
         if (currentProductState.getStock() < quantity) {
              logger.warn("Insufficient stock for product ID: {} found in cart ID: {}. Requested: {}, Stock: {}",
                       product.getId(), cart.getId(), quantity, currentProductState.getStock());
              throw new BadRequestException("Sepetinizdeki ürün '" + currentProductState.getName() + "' için stok yetersiz. Lütfen sepetinizi güncelleyin.");
         }

         // OrderItem oluştur
        OrderItem orderItem = new OrderItem();
         orderItem.setProduct(currentProductState); // Güncel product state
         orderItem.setQuantity(quantity);
         orderItem.setPriceAtPurchase(currentProductState.getPrice()); // O anki fiyat
        order.addOrderItem(orderItem); // Order'a ekle ve ilişkiyi kur

         // Ürün stoğunu azalt
         currentProductState.setStock(currentProductState.getStock() - quantity);
         productRepository.save(currentProductState);

         totalAmount = totalAmount.add(orderItem.getPriceAtPurchase().multiply(BigDecimal.valueOf(quantity)));
     }
     // ==============================================

     order.setTotalAmount(totalAmount);
     order.setDiscountAmount(BigDecimal.ZERO); // Başlangıçta sıfır

      // Kupon Kontrolü (orderRequest.getCouponCode() kullan)
      Coupon appliedCoupon = null;
      if (orderRequest.getCouponCode() != null && !orderRequest.getCouponCode().isBlank()) {
         // ... (Kupon kontrol ve uygulama mantığı öncekiyle aynı) ...
         if(appliedCoupon != null){
             order.setCoupon(appliedCoupon);
             // order.setDiscountAmount(hesaplanan_indirim);
             couponRepository.save(appliedCoupon);
         }
      }



// Ödeme ve toplam tutar hesaplaması
order.setTotalAmount(totalAmount);
order.setShippingFee(orderRequest.getShippingFee() != null ? orderRequest.getShippingFee() : BigDecimal.ZERO); // eğer dışarıdan alınıyorsa
 order.setDiscountAmount(orderRequest.getDiscountAmount() != null ? orderRequest.getDiscountAmount() : BigDecimal.ZERO); // kupon vs. varsa

// 🔥 En önemlisi burası:
BigDecimal finalAmount = order.getTotalAmount()
    .add(order.getShippingFee() != null ? order.getShippingFee() : BigDecimal.ZERO)
    .subtract(order.getDiscountAmount() != null ? order.getDiscountAmount() : BigDecimal.ZERO);

order.setFinalAmount(finalAmount);





    Order savedOrder = orderRepository.save(order); // Cascade ile orderItems kaydedilecek

    // !!! Sipariş BAŞARIYLA oluşturulduktan sonra SEPETİ TEMİZLE !!!
    cartService.clearCart(); // Veya cartRepository.delete(cart) veya cartItemRepository.deleteByCartId(cart.getId())

    logger.info("Order ID: {} created from Cart ID: {}. Cart cleared.", savedOrder.getId(), cart.getId());

    // TODO: Ödeme işlemi başlatma, bildirim vs.

    return orderMapper.toOrderResponse(savedOrder);
}

// ... (Diğer OrderService metodları) ...
    
    
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