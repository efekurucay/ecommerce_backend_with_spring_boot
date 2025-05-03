package com.fibiyo.ecommerce.application.service.impl;

import com.fibiyo.ecommerce.application.dto.*;
import com.fibiyo.ecommerce.application.exception.*; // Exceptions
import com.fibiyo.ecommerce.application.mapper.*; // Mappers
import com.fibiyo.ecommerce.application.service.CartService;
import com.fibiyo.ecommerce.domain.entity.*; // Entities
import com.fibiyo.ecommerce.infrastructure.persistence.repository.*; // Repositories
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional; // Önemli

import java.util.Optional;

@Service
public class CartServiceImpl implements CartService {

    private static final Logger logger = LoggerFactory.getLogger(CartServiceImpl.class);

    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;
    private final CartMapper cartMapper;
    private final CartItemMapper cartItemMapper; // İç DTO için


    @Autowired
    public CartServiceImpl(CartRepository cartRepository, CartItemRepository cartItemRepository, ProductRepository productRepository, UserRepository userRepository, CartMapper cartMapper, CartItemMapper cartItemMapper) {
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
        this.cartMapper = cartMapper;
        this.cartItemMapper = cartItemMapper;
    }

    // Helper
    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
         if ("anonymousUser".equals(username)) {
            throw new ForbiddenException("Bu işlem için giriş yapmalısınız.");
        }
        return userRepository.findByUsername(username)
               .orElseThrow(() -> new ResourceNotFoundException("Current user not found: " + username));
    }

    // Kullanıcının sepetini getir veya yoksa oluşturur
    @Override
    public Cart getOrCreateCartForUser(User user) {
        return cartRepository.findByUserIdWithItems(user.getId())
                .orElseGet(() -> {
                    logger.info("No cart found for user ID: {}. Creating a new cart.", user.getId());
                    Cart newCart = new Cart();
                    newCart.setUser(user);
                    return cartRepository.save(newCart);
                });
    }
    
    @Override
    @Transactional(readOnly = true)
    public CartResponse getCartForCurrentUser() {
        User user = getCurrentUser();

        logger.warn(">>> getCartForCurrentUser() method called"); // bunu ekle
         logger.debug("Fetching cart for user ID: {}", user.getId());
         Cart cart = getOrCreateCartForUser(user); // Varsa getir, yoksa oluştur
         return cartMapper.toCartResponse(cart);
    }

    @Override
    @Transactional
    public CartResponse addItemToCart(AddToCartRequest request) {
         User user = getCurrentUser();
        Cart cart = getOrCreateCartForUser(user); // Sepeti al veya oluştur
         Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new ResourceNotFoundException("Product not found with id: " + request.getProductId()));
        int requestedQuantity = request.getQuantity();

         logger.info("User ID: {} adding product ID: {} (Qty: {}) to cart ID: {}",
                user.getId(), product.getId(), requestedQuantity, cart.getId());

        // Stok ve ürün durumu kontrolü
        if (!product.isActive() || !product.isApproved()) {
             throw new BadRequestException("Ürün '" + product.getName() + "' şu anda satın alınamaz.");
        }

        // Sepette bu ürün var mı?
         Optional<CartItem> existingItemOpt = cartItemRepository.findByCartIdAndProductId(cart.getId(), product.getId());

        if (existingItemOpt.isPresent()) {
             // Ürün sepette var, miktarını güncelle
             CartItem existingItem = existingItemOpt.get();
             int newQuantity = existingItem.getQuantity() + requestedQuantity;
             if (product.getStock() < newQuantity) {
                 logger.warn("Insufficient stock to add quantity for product ID: {}. Cart ID: {}, Requested total: {}, Stock: {}",
                        product.getId(), cart.getId(), newQuantity, product.getStock());
                  throw new BadRequestException("Stok yetersiz. Sepetinize en fazla " + (product.getStock() - existingItem.getQuantity()) + " adet daha ekleyebilirsiniz.");
             }
             existingItem.setQuantity(newQuantity);
             cartItemRepository.save(existingItem);
             logger.debug("Updated quantity for product ID: {} in cart ID: {} to {}", product.getId(), cart.getId(), newQuantity);
         } else {
             // Ürün sepette yok, yeni ekle
            if (product.getStock() < requestedQuantity) {
                 logger.warn("Insufficient stock to add product ID: {}. Cart ID: {}, Requested: {}, Stock: {}",
                        product.getId(), cart.getId(), requestedQuantity, product.getStock());
                 throw new BadRequestException("Stok yetersiz. Bu üründen en fazla " + product.getStock() + " adet ekleyebilirsiniz.");
             }
             CartItem newItem = new CartItem();
             newItem.setProduct(product);
             newItem.setQuantity(requestedQuantity);
             //newItem.setCart(cart); // addItem helper metodu set ediyor
             cart.addItem(newItem); // Bu CartItem'a cart referansını da set eder
             // Dikkat: Cascade çalıştığı için Cart'ı save etmek CartItem'ı da save eder mi? Deneyelim.
             // Eğer CartItem save edilmezse newItem'ı save etmek gerekir: cartItemRepository.save(newItem);
             // Cart'ı tekrar save etmek updatedAt'i günceller.
             logger.debug("Added new product ID: {} (Qty: {}) to cart ID: {}", product.getId(), requestedQuantity, cart.getId());
         }

        // Güncellenmiş sepeti (veya kaydedilmiş sepeti) alıp dönelim.
        // updatedAt'i güncellemek için cart'ı tekrar save edebiliriz.
         Cart updatedCart = cartRepository.save(cart); // updatedAt'i günceller ve cascade çalıştırır

        // Yeniden okuyup dönelim (item'ları ve ürünleri ile)
         return getCartForCurrentUser();
        // VEYA mapToResponse ile dönebiliriz ama totals hesaplanmalı
         // return cartMapper.toCartResponse(updatedCart);
    }


    @Override
    @Transactional
    public CartResponse updateCartItemQuantity(Long productId, UpdateCartItemRequest request) {
         User user = getCurrentUser();
        Cart cart = getOrCreateCartForUser(user);
        int newQuantity = request.getQuantity();
         logger.info("User ID: {} updating product ID: {} quantity to {} in cart ID: {}",
                user.getId(), productId, newQuantity, cart.getId());

         CartItem itemToUpdate = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found in cart"));

         Product product = itemToUpdate.getProduct(); // İlişki LAZY ise product burada null olabilir, findById gerekebilir.

        // Stock check (Önce product'ı bulalım)
        if (product == null) { // Lazy loading handle
             product = productRepository.findById(productId)
                       .orElseThrow(() -> new ResourceNotFoundException("Product data inconsistency in cart")); // Bu durum olmamalı
         }
         if(product.getStock() < newQuantity){
             logger.warn("Insufficient stock to update quantity for product ID: {}. Cart ID: {}, Requested: {}, Stock: {}",
                       product.getId(), cart.getId(), newQuantity, product.getStock());
               throw new BadRequestException("Stok yetersiz. Bu üründen en fazla " + product.getStock() + " adet seçebilirsiniz.");
         }

        itemToUpdate.setQuantity(newQuantity);
        cartItemRepository.save(itemToUpdate);
         logger.debug("Quantity updated for product ID: {} in cart ID: {}", productId, cart.getId());

        // updatedAt güncellemesi için cart'ı save et
        cartRepository.save(cart);

         return getCartForCurrentUser(); // Güncel sepeti oku ve dön
    }


    @Override
    @Transactional
    public CartResponse removeItemFromCart(Long productId) {
         User user = getCurrentUser();
         Cart cart = getOrCreateCartForUser(user);
         logger.warn("User ID: {} removing product ID: {} from cart ID: {}", user.getId(), productId, cart.getId());

        // Item'ı bul ve sil (veya direkt repository'den sil)
         CartItem itemToRemove = cartItemRepository.findByCartIdAndProductId(cart.getId(), productId)
                 .orElseThrow(() -> new ResourceNotFoundException("Product not found in cart"));

        // Cascade ayarları Cart'ta var ama direkt CartItemRepository üzerinden de silebiliriz.
        // İlişki yönetimi için Cart entity üzerinden remove daha iyi olabilir:
         // cart.removeItem(itemToRemove); // Bu orphanRemoval=true ile item'ı silmeli
         // cartRepository.save(cart);
         cart.getItems().remove(itemToRemove); // <-- bunu mutlaka yap

         // Direkt Repository ile silme:
         cartItemRepository.delete(itemToRemove);
         logger.info("Removed product ID: {} from cart ID: {}", productId, cart.getId());

         // updatedAt güncellemesi için cart'ı save et
          cartRepository.save(cart);

         return getCartForCurrentUser(); // Güncel sepeti oku ve dön
    }

    @Override
    public Cart getOrCreateCartForUserInternal() {
        User user = getCurrentUser();
        return getOrCreateCartForUser(user);
    }
    


    @Override
@Transactional
public void clearCart() {
    User user = getCurrentUser();
    Cart cart = getOrCreateCartForUser(user);
    logger.warn("User ID: {} clearing cart ID: {}", user.getId(), cart.getId());

    // 1. Önce cart objesinin içindeki item listesini temizle
    cart.getItems().clear();

    // 2. Veritabanından item'ları sil (ekstra güvence için)
    cartItemRepository.deleteByCartId(cart.getId());

    // 3. Güncellenmiş cart'ı kaydet (silinmiş objeler olmadan)
    cartRepository.save(cart);

    logger.info("Cart ID: {} cleared.", cart.getId());
}

}