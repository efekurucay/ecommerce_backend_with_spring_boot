package com.fibiyo.ecommerce.application.dto;

import jakarta.validation.Valid; // İç içe DTO'ları valide etmek için
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class OrderRequest {

    // customerId genellikle SecurityContext'ten alınır, request'te gelmez

    @NotNull(message = "Teslimat adresi boş olamaz")
    @Valid // AddressDto'nun içindeki validation'ları tetikler
    private AddressDto shippingAddress;

    // Opsiyonel: Fatura adresi farklıysa
    @Valid
    private AddressDto billingAddress;

    // TODO: Ödeme yöntemi bilgisi ödeme adımında mı alınacak, siparişle birlikte mi?
    // Şimdilik siparişle alalım, Payment Service bunu işleyecek varsayalım.
    @NotEmpty(message = "Ödeme yöntemi belirtilmelidir (örn: STRIPE, PAYPAL)")
    @Size(max = 50)
    private String paymentMethod; // Ödeme yöntemini belirten bir string

    // Uygulanacak kupon kodu (opsiyonel)
    @Size(max = 50)
    private String couponCode;

    @NotEmpty(message = "Sipariş kalemleri boş olamaz")
    @Valid // Listedeki her OrderItemRequest'ı valide et
    private List<OrderItemRequest> items;

    // Frontend tarafından hesaplanan kargo ücreti (veya backend'de hesaplanacaksa kaldırılabilir)
    // @NotNull @DecimalMin("0.0") private BigDecimal shippingFee;
}