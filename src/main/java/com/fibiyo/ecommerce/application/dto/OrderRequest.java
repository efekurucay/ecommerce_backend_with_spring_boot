/* package com.fibiyo.ecommerce.application.dto;

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
} */

// V2 
// package com.fibiyo.ecommerce.application.dto;

// import jakarta.validation.Valid;
// import jakarta.validation.constraints.NotEmpty;
// import jakarta.validation.constraints.NotNull;
// import jakarta.validation.constraints.Size;
// import lombok.Data;

// // import java.util.List; // Items artık burada değil

// @Data
// public class OrderRequest { // Güncellenmiş Hali

//     @NotNull(message = "Teslimat adresi boş olamaz")
//     @Valid
//     private AddressDto shippingAddress;

//     @Valid
//     private AddressDto billingAddress; // Opsiyonel

//     @NotEmpty(message = "Ödeme yöntemi belirtilmelidir (örn: STRIPE, PAYPAL)")
//     @Size(max = 50)
//     private String paymentMethod;

//     @Size(max = 50)
//     private String couponCode; // Opsiyonel
// }

//V3

package com.fibiyo.ecommerce.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class OrderRequest {

    @NotNull(message = "Teslimat adresi boş olamaz")
    @Valid
    private AddressDto shippingAddress;

    @Valid
    private AddressDto billingAddress; // Opsiyonel

    @NotEmpty(message = "Ödeme yöntemi belirtilmelidir (örn: STRIPE, PAYPAL)")
    @Size(max = 50)
    private String paymentMethod;

    @Size(max = 50)
    private String couponCode; // Opsiyonel

    // Frontend tarafından gönderilecek kargo ücreti (opsiyonel)
    private BigDecimal shippingFee;

    // Uygulanan kupon indirimi (opsiyonel, backend de hesaplayabilir)
    private BigDecimal discountAmount;
}
