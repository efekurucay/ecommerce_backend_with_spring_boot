package com.fibiyo.ecommerce.application.service;

import com.fibiyo.ecommerce.application.dto.AddToCartRequest;
import com.fibiyo.ecommerce.application.dto.CartResponse;
import com.fibiyo.ecommerce.application.dto.UpdateCartItemRequest;
import com.fibiyo.ecommerce.domain.entity.Cart;
import com.fibiyo.ecommerce.domain.entity.User;

public interface CartService {

    CartResponse getCartForCurrentUser();

    CartResponse addItemToCart(AddToCartRequest request);

    CartResponse updateCartItemQuantity(Long productId, UpdateCartItemRequest request);

    CartResponse removeItemFromCart(Long productId);

    void clearCart(); // Sadece işlemi yapar, sepetin boş halini döndürmeye gerek yok

    Cart getOrCreateCartForUserInternal(); // internal kullanım

    Cart getOrCreateCartForUser(User user);

    
}