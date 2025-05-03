package com.fibiyo.ecommerce.application.service.impl;

import com.fibiyo.ecommerce.application.dto.CreateCheckoutSessionRequest;
import com.fibiyo.ecommerce.application.dto.StripeCheckoutSessionResponse;
import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ForbiddenException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.service.NotificationService; // Bildirim göndermek için
import com.fibiyo.ecommerce.application.service.PaymentService;
import com.fibiyo.ecommerce.domain.entity.*; // Tüm ilgili entity'ler
import com.fibiyo.ecommerce.domain.enums.NotificationType;
import com.fibiyo.ecommerce.domain.enums.OrderStatus;
import com.fibiyo.ecommerce.domain.enums.PaymentStatus;
import com.fibiyo.ecommerce.infrastructure.persistence.repository.*; // Gerekli repository'ler
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.*; // Stripe modelleri: Event, Session, PaymentIntent vb.
import com.stripe.model.checkout.Session;

import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import jakarta.validation.constraints.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final CartRepository cartRepository; // Sepeti temizlemek için
    private final CartItemRepository cartItemRepository; // Sepeti temizlemek için
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Value("${stripe.webhook.secret}")
    private String endpointSecret; // Stripe webhook secret key


    @Autowired
    public PaymentServiceImpl(OrderRepository orderRepository,
                              PaymentRepository paymentRepository,
                              CartRepository cartRepository,
                              CartItemRepository cartItemRepository,
                              UserRepository userRepository,
                              NotificationService notificationService) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.cartRepository = cartRepository;
        this.cartItemRepository = cartItemRepository;
        this.userRepository = userRepository;
        this.notificationService = notificationService;
    }

    // Helper - Mevcut giriş yapmış kullanıcıyı getirir
    private User getCurrentUser() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        if ("anonymousUser".equals(username)) {
            throw new ForbiddenException("Bu işlem için giriş yapmalısınız.");
        }
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("Current user not found: " + username));
    }


    @Override
    // Tamamı transaction içinde olamaz çünkü Stripe API çağrısı dışarıda.
    // Ama DB okuma ve kontroller burada yapılır.
    public StripeCheckoutSessionResponse createCheckoutSession(CreateCheckoutSessionRequest request) {
        User customer = getCurrentUser();
        Long orderId = request.getOrderId();
        logger.info("User ID: {} creating Stripe Checkout Session for Order ID: {}", customer.getId(), orderId);

        // @Transactional readOnly=true kullanmak yerine doğrudan repoyu çağırıyoruz
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found with id: " + orderId));

        // Yetki Kontrolü
        if (!order.getCustomer().getId().equals(customer.getId())) {
            throw new ForbiddenException("Bu sipariş için ödeme başlatma yetkiniz yok.");
        }

        // Durum Kontrolleri
        if (order.getPaymentStatus() == PaymentStatus.COMPLETED) {
            throw new BadRequestException("Bu siparişin ödemesi zaten yapılmış.");
        }
         if (order.getStatus().name().startsWith("CANCELLED")) {
            throw new BadRequestException("İptal edilmiş bir sipariş için ödeme yapamazsınız.");
        }
         if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            throw new BadRequestException("Sipariş ödeme için uygun durumda değil (Durum: " + order.getStatus() + ").");
        }
         if (order.getOrderItems() == null || order.getOrderItems().isEmpty()){
              throw new BadRequestException("Siparişte ödenecek ürün bulunmuyor.");
         }


               // ... (Metodun başındaki kodlar) ...
               BigDecimal finalAmount = order.getFinalAmount();
               if (finalAmount == null) {
                   throw new IllegalStateException("Order final amount is null for order ID: " + order.getId());
               }
               
               BigDecimal amountInCents = finalAmount.multiply(new BigDecimal("100"));
               
        // --- Stripe Session Oluşturma (Güncellenmiş SDK v20+ Kullanımı) ---
        try {
            // Siparişin son tutarını kuruş/cent'e çevir
            long finalAmountCents = order.getFinalAmount().multiply(BigDecimal.valueOf(100)).longValue();
            if (finalAmountCents <= 50) { // Stripe'ın minimum ödeme limiti olabilir (örn: 0.50 TRY/USD) Kontrol etmek lazım.
                logger.warn("Calculated amount ({}) is below Stripe minimum for order ID: {}", finalAmountCents/100.0, orderId);
                // Kullanıcıya minimum limit hatası döndürmek veya farklı bir akış izlemek gerekebilir.
                // Şimdilik Stripe API'sine bırakalım, API hata dönecektir.
             }

            SessionCreateParams params = SessionCreateParams.builder() // <--- Değişiklik burada
                .addPaymentMethodType(SessionCreateParams.PaymentMethodType.CARD)
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(request.getSuccessUrl())
                .setCancelUrl(request.getCancelUrl())
                 // Locale set ederken enum kullanalım
                .setLocale(SessionCreateParams.Locale.TR)
                .addLineItem(
                     SessionCreateParams.LineItem.builder() // <--- Değişiklik burada
                        .setQuantity(1L)
                        .setPriceData(
                             SessionCreateParams.LineItem.PriceData.builder() // <--- Değişiklik burada
                                .setCurrency("try")
                                .setUnitAmount(finalAmountCents)
                                .setProductData(
                                     SessionCreateParams.LineItem.PriceData.ProductData.builder() // <--- Değişiklik burada
                                        .setName("Fibiyo Sipariş #" + order.getId())
                                         // .setDescription(...) // Opsiyonel
                                        .build()
                                )
                                .build()
                        )
                        .build()
                 )
                 // Metadata
                .putMetadata("order_id", order.getId().toString())
                 .putMetadata("customer_id", customer.getId().toString())
                 // Payment Intent Metadata
                .setPaymentIntentData(
                     SessionCreateParams.PaymentIntentData.builder() // <--- Değişiklik burada
                             .putMetadata("order_id", order.getId().toString())
                            .build()
                )
                 .setCustomerEmail(customer.getEmail())
                .build(); // <--- En sonda .build()

            Session session = Session.create(params);
            logger.info("Stripe Checkout Session created: {} for Order ID: {}", session.getId(), orderId);

            return new StripeCheckoutSessionResponse(session.getId(), session.getUrl());

         } catch (StripeException e) {
             logger.error("Error creating Stripe session for Order ID: {}. Stripe Error: {}", orderId, e.getMessage(), e);
             throw new BadRequestException("Ödeme oturumu oluşturulamadı. Lütfen tekrar deneyin veya destek ile iletişime geçin."); // Kullanıcıya daha genele bir mesaj
        } catch (Exception e){
             logger.error("Unexpected error during Stripe session creation for Order ID: {}", orderId, e);
             throw new RuntimeException("Ödeme oturumu oluşturulurken beklenmedik bir hata oluştu.");
         }

         // ... (Metodun geri kalanı aynı) ...
         
    }


 
//     @Override
//     @Transactional // İçeride DB işlemleri olduğu için Transactional olmalı
//     public void handleStripeWebhook(String payload, String sigHeader) {
//         Event event;
//          if(endpointSecret == null || endpointSecret.isBlank()){
//              logger.error("Stripe webhook secret is not configured!");
//               throw new RuntimeException("Webhook secret is not configured."); // Sistemi bilgilendir
//          }

//         // 1. İmza Doğrulama
//         try {
//             event = Webhook.constructEvent(payload, sigHeader, this.endpointSecret);
//         } catch (SignatureVerificationException e) {
//             logger.error("Webhook signature verification failed!", e);
//             throw new BadRequestException("Invalid webhook signature.");
//          } catch (Exception e){ // Payload parse hatası vb.
//               logger.error("Error parsing webhook event payload.", e);
//               throw new BadRequestException("Invalid webhook payload.");
//          }

//         // 2. Event Datasından Obje Alma
//         EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
        
        
//         // StripeObject stripeObject = null;
//         // if (dataObjectDeserializer.getObject().isPresent()) {
//         //     stripeObject = dataObjectDeserializer.getObject().get();
//         // } else {
//         //      logger.error("Webhook event data deserialization failed for event ID: {}", event.getId());
//         //      // Bu durumda genellikle 200 OK döneriz ki Stripe tekrar göndermesin ama hatayı loglarız.
//         //      return; // Veya hata fırlatılabilir ama Stripe tekrar dener.
//         // }
//         StripeObject stripeObject = null;
// if (dataObjectDeserializer.getObject().isPresent()) {
//     stripeObject = dataObjectDeserializer.getObject().get();
// } else {
//     logger.error("Webhook event data deserialization failed for event ID: {}", event.getId());
//     return;
// }





       
//    /*     // 3. Event Tipine Göre İşleme
//         switch (event.getType()) {
//             case "checkout.session.completed":
//                 logger.info("Processing checkout.session.completed for Session ID: {}", session.getId());
//                  // Metadata'dan sipariş ID'sini alalım (Önce Session, sonra PaymentIntent meta)
//                 String orderIdStr = Optional.ofNullable(session.getMetadata().get("order_id"))
//                          .orElseGet(() -> Optional.ofNullable(session.getPaymentIntentObject()) // Sessiondan PI objesini al
//                                 .map(PaymentIntent::getMetadata) // PI'nın metadatasını al
//                                 .map(meta -> meta.get("order_id")) // metadatadan order_id'yi al
//                                 .orElse(null)); // Hiçbiri yoksa null

//                  if (orderIdStr != null) {
//                     fulfillOrder(Long.parseLong(orderIdStr), session);
//                 } else {
//                     logger.error("Webhook Error: Order ID missing in metadata for session ID: {}", session.getId());
//                     // Hata fırlatmak yerine loglayıp devam etmek webhook tekrarını önler.
//                  }
//                 break;

//             case "payment_intent.payment_failed":
//                  PaymentIntent paymentIntentFailed = (PaymentIntent) stripeObject;
//                  logger.warn("Processing payment_intent.payment_failed for PaymentIntent ID: {}", paymentIntentFailed.getId());
//                  // Metadata'dan sipariş ID'sini al
//                  String failedOrderIdStr = paymentIntentFailed.getMetadata().get("order_id");
//                 if (failedOrderIdStr != null) {
//                      handleFailedPayment(Long.parseLong(failedOrderIdStr), paymentIntentFailed);
//                 } else {
//                     logger.error("Webhook Error: Order ID missing in metadata for failed PaymentIntent ID: {}", paymentIntentFailed.getId());
//                 }
//                  break;

//              // Ödeme iadesi webhook'u
//             case "charge.refunded":
//                 Charge charge = (Charge) stripeObject; // İade edilen Charge objesi
//                  // Genellikle iade işlemini biz başlattıysak (Admin panelinden vb.) durumu zaten güncelleriz.
//                  // Bu webhook, Stripe dashboard'dan manuel yapılan iadeler için de gelebilir.
//                  // Charge -> PaymentIntent -> Metadata -> orderId yolunu izlemek gerekebilir.
//                  logger.info("Processing charge.refunded for Charge ID: {}", charge.getId());
//                 // TODO: İade durumunu handle edecek mantığı ekle
//                  // String refundedOrderId = getOrderIdFromCharge(charge);
//                  // handleRefundedPayment(refundedOrderId, charge);
//                  break;


//             // İleride eklenebilecek diğer önemli eventler:
//             // - payment_intent.succeeded (Bazen checkout.session.completed'den önce veya yerine gelebilir)
//              // - invoice.payment_succeeded, invoice.payment_failed (Abonelikler için)
//              // - customer.subscription.deleted, customer.subscription.updated (Abonelikler için)

//             default:
//                 logger.warn("Unhandled Stripe event type: {}", event.getType());
//                  // Bilinmeyen event tipi için hata fırlatmayalım, sadece loglayalım.
//          }
//     }
// */

// // 3. Event Tipine Göre İşleme

// // 3. Event Tipine Göre İşleme
// switch (event.getType()) {
//     case "checkout.session.completed":
//         if (!(stripeObject instanceof Session stripeSession)) {
//             logger.error("Unexpected object type for checkout.session.completed: {}", stripeObject.getClass());
//             return;
//         }
//         logger.info("Processing checkout.session.completed for Session ID: {}", stripeSession.getId());
//         String orderIdStr = Optional.ofNullable(stripeSession.getMetadata().get("order_id"))
//                 .orElseGet(() -> Optional.ofNullable(stripeSession.getPaymentIntentObject())
//                         .map(PaymentIntent::getMetadata)
//                         .map(meta -> meta.get("order_id"))
//                         .orElse(null));
//         if (orderIdStr != null) {
//             fulfillOrder(Long.parseLong(orderIdStr), stripeSession);
//         } else {
//             logger.error("Webhook Error: Order ID missing in metadata for session ID: {}", stripeSession.getId());
//         }
//         break;

//     case "payment_intent.payment_failed":
//         if (!(stripeObject instanceof PaymentIntent paymentIntentFailed)) {
//             logger.error("Unexpected object type for payment_intent.payment_failed: {}", stripeObject.getClass());
//             return;
//         }
//         logger.warn("Processing payment_intent.payment_failed for PaymentIntent ID: {}", paymentIntentFailed.getId());
//         String failedOrderIdStr = paymentIntentFailed.getMetadata().get("order_id");
//         if (failedOrderIdStr != null) {
//             handleFailedPayment(Long.parseLong(failedOrderIdStr), paymentIntentFailed);
//         } else {
//             logger.error("Webhook Error: Order ID missing in metadata for failed PaymentIntent ID: {}", paymentIntentFailed.getId());
//         }
//         break;

//     case "charge.refunded":
//         if (!(stripeObject instanceof Charge charge)) {
//             logger.error("Unexpected object type for charge.refunded: {}", stripeObject.getClass());
//             return;
//         }
//         logger.info("Processing charge.refunded for Charge ID: {}", charge.getId());
//         break;

//     default:
//         logger.warn("Unhandled Stripe event type: {}", event.getType());
//         break;
// } }


@Override
@Transactional // İçeride DB işlemleri olduğu için Transactional olmalı
public void handleStripeWebhook(String payload, String sigHeader) {
    Event event;
    final String webhookSecret = this.endpointSecret; // Final yapalım

    if (webhookSecret == null || webhookSecret.isBlank()) {
        logger.error("STRIPE_WEBHOOK_ERROR: Stripe webhook secret ('stripe.webhook.secret') is not configured!");
        // Bu durumda 500 dönmek daha doğru olabilir, çünkü konfigürasyon hatası var.
        throw new InternalError("Webhook secret configuration is missing.");
    }

    // 1. İmza Doğrulama
    try {
        event = Webhook.constructEvent(payload, sigHeader, webhookSecret);
    } catch (SignatureVerificationException e) {
        logger.error("STRIPE_WEBHOOK_SIGNATURE_ERROR: Webhook signature verification failed! IP: ? Check webhook secret.", e); // IP eklenebilir
        throw new BadRequestException("Invalid webhook signature."); // 400 Bad Request
    } catch (Exception e) { // Payload parse hatası vb.
        logger.error("STRIPE_WEBHOOK_PAYLOAD_ERROR: Error parsing webhook event payload. Payload: '{}'", payload, e);
        throw new BadRequestException("Invalid webhook payload."); // 400 Bad Request
    }

    // 2. Event Datasından Obje Alma Denemesi
    EventDataObjectDeserializer dataObjectDeserializer = event.getDataObjectDeserializer();
    StripeObject stripeObject = null; // Başlangıçta null

    // Optional<StripeObject> objOpt = dataObjectDeserializer.getObject(); Bu bazen çalışmıyor/hatalı olabilir
    // if(objOpt.isPresent()) { stripeObject = objOpt.get(); }
    // Alternatif: Direkt tipe göre deserialize etmeyi dene


    Optional<StripeObject> objectOptional = dataObjectDeserializer.getObject();

if (objectOptional.isPresent()) {
    stripeObject = objectOptional.get();

    if (event.getType().startsWith("checkout.session") && stripeObject instanceof Session) {
        // işlemler
    } else if (event.getType().startsWith("payment_intent") && stripeObject instanceof PaymentIntent) {
        // işlemler
    } else if (event.getType().startsWith("charge") && stripeObject instanceof Charge) {
        // işlemler
    } else {
        logger.warn("STRIPE_WEBHOOK_UNEXPECTED_TYPE: Event type '{}' için beklenmeyen obje tipi: {}", event.getType(), stripeObject.getClass().getName());
    }
} else {
    logger.error("STRIPE_WEBHOOK_DESERIALIZATION_ERROR: StripeObject deserialize edilemedi. Event ID: {}, Type: {}", event.getId(), event.getType());
    return;
}

    // // Eğer deserialization tamamen başarısız olduysa logla.
    // if (stripeObject == null && dataObjectDeserializer.getObject().isPresent()) {
    //      stripeObject = dataObjectDeserializer.getObject().get(); // Eski yöntemi tekrar dene
    //      if (stripeObject == null){
    //           logger.error("STRIPE_WEBHOOK_DATA_ERROR: Still could not get StripeObject after deserialization attempt for Event ID: {}, Type: {}", event.getId(), event.getType());
    //            return; // İşlem yapmadan çıkalım.
    //       } else {
    //           logger.warn("STRIPE_WEBHOOK_DESERIALIZATION_WARN: Deserialized using Optional but direct deserialization failed for Event ID: {}, Type: {}. Object: {}",
    //                    event.getId(), event.getType(), stripeObject.getClass().getSimpleName());
    //      }
    //  } else if (stripeObject == null && dataObjectDeserializer.getObject().isEmpty()){
    //      logger.error("STRIPE_WEBHOOK_DATA_ERROR: StripeObject is empty (or deserialization failed) for Event ID: {}, Type: {}", event.getId(), event.getType());
    //      return;
    //  }


    logger.info("STRIPE_WEBHOOK_RECEIVED: EventId='{}', Type='{}', DataObject='{}'",
             event.getId(), event.getType(), stripeObject != null ? stripeObject.getClass().getSimpleName() : "N/A (Deserialization Failed?)");

    // 3. Event Tipine Göre İşleme (Switch veya if-else if)
    String eventType = event.getType();

     try { // İş mantığı hatalarını ayrıca yakalayalım
         if ("checkout.session.completed".equals(eventType)) {
             if (stripeObject instanceof Session session) { // Doğru tipe cast et ve null kontrolü yap
                 logger.info("Processing checkout.session.completed for Session ID: {}", session.getId());
                 String orderIdStr = Optional.ofNullable(session.getMetadata().get("order_id"))
                        .or(() -> Optional.ofNullable(session.getPaymentIntentObject()).map(PaymentIntent::getMetadata).map(meta -> meta.get("order_id")))
                         .orElse(null);
                if (orderIdStr != null) {
                    fulfillOrder(Long.parseLong(orderIdStr), session);
                } else {
                     logger.error("STRIPE_WEBHOOK_METADATA_ERROR: Order ID missing in metadata for session ID: {}", session.getId());
                 }
            } else {
                logger.error("STRIPE_WEBHOOK_TYPE_ERROR: Expected Session object for checkout.session.completed, but got: {}", stripeObject != null ? stripeObject.getClass().getName() : "null");
             }
         } else if ("payment_intent.payment_failed".equals(eventType)) {
              if (stripeObject instanceof PaymentIntent paymentIntentFailed) {
                 logger.warn("Processing payment_intent.payment_failed for PaymentIntent ID: {}", paymentIntentFailed.getId());
                 String failedOrderIdStr = paymentIntentFailed.getMetadata().get("order_id");
                 if (failedOrderIdStr != null) {
                    handleFailedPayment(Long.parseLong(failedOrderIdStr), paymentIntentFailed);
                } else {
                    logger.error("STRIPE_WEBHOOK_METADATA_ERROR: Order ID missing in metadata for failed PaymentIntent ID: {}", paymentIntentFailed.getId());
                }
            } else {
                 logger.error("STRIPE_WEBHOOK_TYPE_ERROR: Expected PaymentIntent object for payment_intent.payment_failed, but got: {}", stripeObject != null ? stripeObject.getClass().getName() : "null");
            }
        } else if ("charge.refunded".equals(eventType)) {
              if (stripeObject instanceof Charge charge) {
                 logger.info("Processing charge.refunded for Charge ID: {}", charge.getId());
                 // TODO: handleRefundedPayment(charge);
              } else {
                  logger.error("STRIPE_WEBHOOK_TYPE_ERROR: Expected Charge object for charge.refunded, but got: {}", stripeObject != null ? stripeObject.getClass().getName() : "null");
             }
        }
         // --- Diğer event tipleri eklenebilir ---
         else {
            logger.warn("STRIPE_WEBHOOK_UNHANDLED: Unhandled event type: {}", eventType);
         }
      } catch (Exception e){ // fulfillOrder veya handleFailedPayment içindeki hatalar
         logger.error("STRIPE_WEBHOOK_HANDLER_ERROR: Error processing event type '{}' for Event ID: {}. Error: {}", eventType, event.getId(), e.getMessage(), e);
         // Bu durumda 500 hatası dönebiliriz veya yine 200 dönüp Stripe'ın tekrar denemesini engelleriz.
          // Tekrar denemesini istemiyorsak burada hata fırlatmamalıyız. Loglamak yeterli.
          // throw new RuntimeException("Failed to process webhook event " + event.getId(), e); // Bunu kaldırmak webhook tekrarını engeller.
       }
 }


    // --- Özel (Protected/Private) Metodlar ---

    // Ödeme başarılı olduğunda çağrılır (checkout.session.completed)
    @Transactional
    protected void fulfillOrder(Long orderId, @NotNull Session session) {
        Order order = orderRepository.findById(orderId)
                .orElse(null);

        if (order == null) {
            logger.error("[FULFILLMENT-ERROR] Order not found with ID {} from webhook (SessionID: {}).", orderId, session.getId());
            // Çok kritik durum, logla ve belki admin'e bildir. Webhook tekrar denememeli.
            return;
        }

         // idempotency: Eğer zaten tamamlandıysa tekrar işleme
        if (order.getPaymentStatus() == PaymentStatus.COMPLETED && order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            logger.warn("[FULFILLMENT-INFO] Order ID: {} already fulfilled (Status: {}, PaymentStatus: {}). Ignoring duplicate webhook (SessionID: {}).",
                    orderId, order.getStatus(), order.getPaymentStatus(), session.getId());
            return;
        }

        // 1. Sipariş Durumlarını Güncelle
         order.setStatus(OrderStatus.PROCESSING); // Artık sipariş işleniyor
         order.setPaymentStatus(PaymentStatus.COMPLETED); // Ödeme tamamlandı
        // Opsiyonel: Payment Method'u Stripe olarak set edebiliriz veya daha detaylı (card vb.)
         // order.setPaymentMethod("STRIPE_" + session.getPaymentMethodTypes().stream().findFirst().orElse("CARD").toUpperCase());
         order.setPaymentMethod("STRIPE"); // Şimdilik basit tutalım


         // 2. Ödeme Kaydı Oluştur (Referans amaçlı)
         Payment payment = new Payment();
        payment.setOrder(order);
         // Stripe tutarı cent/kuruş cinsinden döner, biz BigDecimal kullanıyoruz
         payment.setAmount(BigDecimal.valueOf(session.getAmountTotal()).divide(BigDecimal.valueOf(100))); // veya order.getFinalAmount()
         payment.setPaymentMethod("STRIPE");
        payment.setStatus(PaymentStatus.COMPLETED);
         payment.setTransactionId(session.getPaymentIntent()); // İlişkili Payment Intent ID
        payment.setCurrency(session.getCurrency() != null ? session.getCurrency().toUpperCase() : "TRY"); // Para birimi
        payment.setGatewayResponse("Stripe Session ID: " + session.getId() + ", Status: " + session.getPaymentStatus());
         paymentRepository.save(payment);

         // 3. Siparişi Kaydet
        Order updatedOrder = orderRepository.save(order);

         // 4. !!! Sepeti Temizle !!!
        User customer = updatedOrder.getCustomer();
         if (customer != null) {
            cartRepository.findByUserId(customer.getId()).ifPresent(cart -> {
                logger.info("Clearing cart ID: {} for user ID: {} after successful payment.", cart.getId(), customer.getId());
                cartItemRepository.deleteByCartId(cart.getId()); // Tüm cart item'ları sil
                // Opsiyonel: Sepetin kendisini de silebiliriz ama genellikle kalır. cartRepository.delete(cart);
             });
        } else {
             logger.error("[FULFILLMENT-ERROR] Cannot clear cart. Customer is null for fulfilled Order ID: {}", orderId);
        }

        // 5. Müşteriye Bildirim Gönder
       // ... (Order durumu PROCESSING, Payment COMPLETED yapıldıktan sonra) ...

// Müşteriye bildirim gönder
try {
    notificationService.createNotification( // Inject edilmiş olmalı
        updatedOrder.getCustomer(),
         "#" + orderId + " numaralı siparişiniz alındı ve ödemeniz onaylandı. Hazırlanmaya başlandı.",
         "/orders/my/" + orderId, // Frontend'deki ilgili sayfanın linki
        NotificationType.ORDER_UPDATE);
 } catch (Exception e) {
     logger.error("[FULFILLMENT-ERROR] Failed to send order confirmation notification for Order ID {}: {}", orderId, e.getMessage(), e);
}

// ...

        logger.info("[FULFILLMENT-SUCCESS] Order ID: {} processed successfully. New Status: {}, PaymentStatus: {}",
                 orderId, updatedOrder.getStatus(), updatedOrder.getPaymentStatus());
    }

    // Ödeme başarısız olduğunda çağrılır (payment_intent.payment_failed)
    @Transactional
    protected void handleFailedPayment(Long orderId, @NotNull PaymentIntent paymentIntent) {
        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null) {
             logger.error("[PAYMENT-FAILED-ERROR] Order not found with ID {} from failed payment webhook (PaymentIntent ID: {}).", orderId, paymentIntent.getId());
            return;
        }

        // Sadece ödeme bekleyen siparişlerin durumunu değiştir, diğerlerini logla.
        if (order.getStatus() == OrderStatus.PENDING_PAYMENT) {
            order.setPaymentStatus(PaymentStatus.FAILED);
             // Opsiyonel: Sipariş durumunu CANCELLED yapabiliriz veya kullanıcıya tekrar deneme şansı vermek için PENDING_PAYMENT'da bırakabiliriz.
            // order.setStatus(OrderStatus.PENDING_PAYMENT); // Tekrar deneyebilsin
            Order updatedOrder = orderRepository.save(order);

            // Ödeme kaydı (FAILED)
             Payment payment = new Payment();
             payment.setOrder(order);
             payment.setAmount(order.getFinalAmount()); // Veya paymentIntent.getAmount() / 100
             payment.setPaymentMethod("STRIPE");
            payment.setStatus(PaymentStatus.FAILED);
            payment.setTransactionId(paymentIntent.getId());
             payment.setCurrency(paymentIntent.getCurrency() != null ? paymentIntent.getCurrency().toUpperCase() : "TRY");
            payment.setGatewayResponse("Failed: " + Optional.ofNullable(paymentIntent.getLastPaymentError()).map(StripeError::getMessage).orElse("N/A"));
            paymentRepository.save(payment);

         // ... (Order durumu güncellendikten ve Payment kaydı oluşturulduktan sonra) ...

 // Müşteriye Bildirim
 try {
    notificationService.createNotification(
             order.getCustomer(),
             "#" + orderId + " numaralı siparişiniz için ödeme alınamadı. Lütfen tekrar deneyin.",
            "/orders/my/" + orderId,
            NotificationType.ORDER_UPDATE); // Veya özel bir PAYMENT_FAILED tipi
 } catch (Exception e){
     logger.error("[PAYMENT-FAILED-ERROR] Failed to send payment failed notification for Order ID {}: {}", orderId, e.getMessage(), e);
  }

             logger.warn("[PAYMENT-FAILED] Payment failed for Order ID: {}. PaymentIntent ID: {}. Last Error: {}",
                    orderId, paymentIntent.getId(), payment.getGatewayResponse());
         } else {
            logger.warn("[PAYMENT-FAILED-INFO] Received failed payment event for Order ID: {} which is not PENDING_PAYMENT (Current: {}). Ignoring status update. PI_ID: {}",
                     orderId, order.getStatus(), paymentIntent.getId());
         }
    }

     // ... (İleride eklenecek Refund metotları vb.) ...

}