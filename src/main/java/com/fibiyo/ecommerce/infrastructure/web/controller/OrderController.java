package com.fibiyo.ecommerce.infrastructure.web.controller;

import com.fibiyo.ecommerce.application.dto.ApiResponse;
import com.fibiyo.ecommerce.application.dto.OrderRequest;
import com.fibiyo.ecommerce.application.dto.OrderResponse;
import com.fibiyo.ecommerce.application.service.OrderService;
import com.fibiyo.ecommerce.domain.enums.OrderStatus; // Enum import
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

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


@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    private final OrderService orderService;

    @Autowired
    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    // --- Customer Endpoints ---

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')") // Sadece müşteriler sipariş oluşturabilir
    public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest orderRequest) {
        logger.info("POST /api/orders requested");
        OrderResponse createdOrder = orderService.createOrder(orderRequest);
        return new ResponseEntity<>(createdOrder, HttpStatus.CREATED);
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<Page<OrderResponse>> getMyOrders(
            @PageableDefault(size = 10, sort = "orderDate", direction = Sort.Direction.DESC) Pageable pageable) {
        logger.info("GET /api/orders/my requested");
        Page<OrderResponse> orders = orderService.findMyOrders(pageable);
        return ResponseEntity.ok(orders);
    }

    @GetMapping("/my/{orderId}")
    @PreAuthorize("hasRole('CUSTOMER')")
    public ResponseEntity<OrderResponse> getMyOrderDetails(@PathVariable Long orderId) {
        logger.info("GET /api/orders/my/{} requested", orderId);
        OrderResponse order = orderService.findMyOrderById(orderId); // Servis katmanı sahiplik kontrolü yapar
        return ResponseEntity.ok(order);
    }

     @PatchMapping("/my/{orderId}/cancel") // Müşterinin siparişini iptal etmesi
     @PreAuthorize("hasRole('CUSTOMER')")
     public ResponseEntity<OrderResponse> cancelMyOrder(@PathVariable Long orderId) {
          logger.warn("PATCH /api/orders/my/{}/cancel requested", orderId);
          OrderResponse cancelledOrder = orderService.cancelMyOrder(orderId);
         return ResponseEntity.ok(cancelledOrder);
     }

    // --- Admin/Seller Endpoints ---

    // Not: Bu endpoint'leri /api/admin/orders ve /api/seller/orders altına taşımak daha düzenli olabilir.
    // Şimdilik burada tutalım, PreAuthorize ile ayıralım.

    @GetMapping // Tüm siparişleri listele (Admin)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<OrderResponse>> getAllOrders(
             @PageableDefault(size = 20, sort = "orderDate", direction = Sort.Direction.DESC) Pageable pageable,
             @RequestParam(required = false) Long customerId,
             @RequestParam(required = false) OrderStatus status) {
        logger.info("GET /api/orders requested (Admin)");
         Page<OrderResponse> orders = orderService.findAllOrders(pageable, customerId, status);
        return ResponseEntity.ok(orders);
    }

     @GetMapping("/{orderId}") // ID ile herhangi bir siparişin detayını gör (Admin/Seller)
     @PreAuthorize("hasRole('ADMIN') or @orderSecurity.hasPermission(#orderId, authentication)") 
 // Seller sadece kendi ürünü olanı görmeli - bu kontrol serviste!
     public ResponseEntity<OrderResponse> getOrderDetails(@PathVariable Long orderId) {
         logger.info("GET /api/orders/{} requested (Admin/Seller)", orderId);
         // TODO: Seller'ın sadece kendi ürününü içeren siparişi görme kontrolü serviste eklenmeli!
         OrderResponse order = orderService.findOrderById(orderId);
         return ResponseEntity.ok(order);
     }

     @PatchMapping("/{orderId}/status") // Sipariş durumunu güncelle (Admin/Seller)
     @PreAuthorize("hasRole('ADMIN') or @orderSecurity.hasPermission(#orderId, authentication)") 
     public ResponseEntity<OrderResponse> updateOrderStatus(@PathVariable Long orderId, @RequestParam OrderStatus status) {
         logger.info("PATCH /api/orders/{}/status requested with status: {}", orderId, status);
         // TODO: Seller'ın sadece kendi ürününü içeren siparişin durumunu değiştirebilme kontrolü serviste! YAPTIM

         OrderResponse updatedOrder = orderService.updateOrderStatus(orderId, status);
         return ResponseEntity.ok(updatedOrder);
     }

     @PatchMapping("/{orderId}/tracking") // Kargo takip no ekle (Admin/Seller)
     @PreAuthorize("hasRole('ADMIN') or @orderSecurity.hasPermission(#orderId, authentication)") // bu gelecek
     public ResponseEntity<OrderResponse> addTrackingNumber(
            @PathVariable Long orderId,
            @RequestParam @NotBlank @Size(max = 100) String trackingNumber) {
           logger.info("PATCH /api/orders/{}/tracking requested with number: {}", orderId, trackingNumber);
           // TODO: Seller kontrolü serviste!
            OrderResponse updatedOrder = orderService.addTrackingNumber(orderId, trackingNumber);
           return ResponseEntity.ok(updatedOrder);
       }

       // TODO: Refund endpoint'i (Admin)


}