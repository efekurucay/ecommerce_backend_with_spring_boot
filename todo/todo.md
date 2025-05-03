
**Backend Todo List (Mevcut Durum)**

Bu liste, konuştuğumuz mimari (`config`, `domain`, `application`, `infrastructure`) ve özellik kapsamına (AI ve Abonelik hariç çekirdek fonksiyonlar) göredir:

**I. Proje Kurulumu ve Yapılandırma (`config`, `pom.xml`, `application.properties`)**
*   `[x]` Spring Boot Projesi Oluşturuldu (Maven)
*   `[x]` Temel Paket Yapısı Oluşturuldu (`com/fibiyo/ecommerce/...`)
*   `[x]` Gerekli Bağımlılıklar Eklendi (`pom.xml` - Web, JPA, Security, Lombok, MySQL, JWT, Validation, MapStruct)
*   `[x]` Veritabanı Bağlantısı Yapılandırıldı (`application.properties` - MySQL)
*   `[x]` JPA/Hibernate Ayarları Yapıldı (`application.properties`)
*   `[x]` JWT Ayarları Tanımlandı (`application.properties` - Secret, Expiration)
*   `[x]` Güvenlik Konfigürasyonu Temeli Atıldı (`SecurityConfig.java` - FilterChain, Cors, PasswordEncoder, AuthManager)
*   `[ ]` OpenAPI/Swagger Dokümantasyon Yapılandırması (`OpenApiConfig.java` - Eksik)
*   `[ ]` Gelişmiş Jackson/ObjectMapper Yapılandırması (`ObjectMapperConfig.java` - Opsiyonel, Eksik)

**II. Domain Katmanı (`domain/entity`, `domain/enums`)**
*   `[x]` Enum Sınıfları Oluşturuldu (`Role`, `AuthProvider`, `SubscriptionType`, `DiscountType`, `OrderStatus`, `PaymentStatus`, `NotificationType`)
*   `[x]` User Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`User.java`)
*   `[x]` Category Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Category.java`)
*   `[x]` Product Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Product.java`)
*   `[x]` Coupon Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Coupon.java`)
*   `[x]` Order Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Order.java`)
*   `[x]` OrderItem Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`OrderItem.java`)
*   `[x]` Payment Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Payment.java`)
*   `[x]` Review Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Review.java`)
*   `[x]` Notification Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`Notification.java`)
*   `[x]` WishlistItem Entity Sınıfı Oluşturuldu ve İlişkiler Tanımlandı (`WishlistItem.java`)
*   `[x]` JPA ve Validation Annotation'ları Eklendi

**III. Infrastructure Katmanı (`infrastructure/...`)**
*   `[x]` Repository Arayüzleri Oluşturuldu (Her entity için `JpaRepository`)
*   `[x]` Temel Özel Sorgu Metotları Eklendi (Repository'lerde `findBy...`, `existsBy...`)
*   `[x]` `JpaSpecificationExecutor` Eklendi (Product, User, Order, Review, Coupon için filtreleme altyapısı)
*   `[x]` `ProductSpecifications` Oluşturuldu (Temel)
*   `[x]` `ReviewSpecifications` Oluşturuldu (Temel)
*   `[ ]` Diğer `Specification` sınıfları (Order, User, Coupon - Eksik/Geliştirilebilir)
*   `[x]` Güvenlik Altyapısı Oluşturuldu (`JwtTokenProvider`, `CustomUserDetailsService`, `JwtAuthenticationFilter`)
*   `[x]` Temel API Controller'ları Oluşturuldu (`AuthController`, `CategoryController`, `ProductController`, `ReviewController`, `WishlistController`, `CouponController`, `NotificationController`, `UserController`)
*   `[x]` Order Controller Oluşturuldu (Temel Müşteri ve Admin/Seller metodları)
*   `[ ]` Admin/Seller İçin Özel Controller'lar Oluşturulmadı (Şu anki Controller'lar içinde `@PreAuthorize` ile yönetiliyor, ayrılabilir: `AdminUserController`, `SellerProductController` vb. - Eksik)
*   `[ ]` AI Controller/Adapter (`AiFeaturesController`, `GeminiAiAdapter`, `OpenAiAdapter` - Eksik)
*   `[ ]` Ödeme Gateway Adapter (`StripeAdapter`, `PayPalAdapter` - Eksik)
*   `[ ]` Diğer Adapter'lar (Email, Storage - Eksik)

**IV. Application Katmanı (`application/...`)**
*   `[x]` DTO Sınıfları Oluşturuldu (Request/Response'lar - Çekirdek özellikler için çoğu tamam)
*   `[ ]` Bazı Özel DTO'lar Eksik Olabilir (örn: Admin raporları, AI istek/yanıtları, Abonelik detayları)
*   `[x]` Mapper Arayüzleri Oluşturuldu (MapStruct - Çekirdek entity'ler için)
*   `[ ]` Mapper Implementasyonlarının Tamlığı ve Doğruluğu Kontrol Edilmeli
*   `[x]` İstisna Sınıfları Oluşturuldu (`BadRequest`, `ResourceNotFound`, `Forbidden`)
*   `[x]` `AuthService` Implementasyonu Tamamlandı (Temel Login/Register)
*   `[x]` `CategoryService` Implementasyonu Tamamlandı (Temel CRUD)
*   `[x]` `ProductService` Implementasyonu Tamamlandı (Public, Temel Seller/Admin)
*   `[x]` `ReviewService` Implementasyonu Tamamlandı (Temel CRUD, Puan Güncelleme)
*   `[x]` `WishlistService` Implementasyonu Tamamlandı (Temel CRUD)
*   `[x]` `CouponService` Implementasyonu Tamamlandı (Temel CRUD, Doğrulama)
*   `[x]` `NotificationService` Implementasyonu Tamamlandı (CRUD, Okundu İşaretleme)
*   `[x]` `OrderService` Implementasyonu Tamamlandı (Temel Create, List, Cancel)
*   `[x]` `UserService` Implementasyonu Tamamlandı (Profil Okuma/Güncelleme, Şifre Değiştirme)
*   `[ ]` Ödeme Servisi (`PaymentService`) - Eksik/İskelet halinde (Gerçek Gateway entegrasyonu gerekiyor)
*   `[ ]` AI Servisi (`AiService`) - Eksik
*   `[ ]` Abonelik Servisi (`SubscriptionService`) - Eksik
*   `[ ]` Depolama Servisi (`StorageService`) - Eksik
*   `[ ]` Admin/Seller için daha detaylı Servis Metotları (Raporlama, Karmaşık İşlemler - Eksik)
*   `[ ]` Global Exception Handler (`@ControllerAdvice` ile `GlobalExceptionHandler.java` - Eksik/Implemente Edilmeli)
*   `[ ]` Servislerde Daha Kapsamlı İş Kuralları ve Validasyonlar - Geliştirilmeli

**V. Veritabanı (`schema.sql`, `data.sql`)**
*   `[x]` Veritabanı Şeması Oluşturuldu (`schema.sql`)
*   `[ ]` Örnek Veriler (`data.sql` - Admin kullanıcısı, temel kategoriler, ürünler vb. eklemek için - Eksik)

**VI. Testler (`src/test/...`)**
*   `[ ]` Unit Testler (Servisler, Mappers vb.) - Eksik
*   `[ ]` Integration Testler (Controller'lar, Repository'ler) - Eksik

**Özetle:** Backend'in iskeleti, temel CRUD operasyonları ve kimlik doğrulama mekanizması büyük ölçüde hazır. Ancak ödeme, AI, abonelik gibi ileri seviye özellikler; admin/seller panellerinin detaylı işlevleri; kapsamlı hata yönetimi ve testler henüz eklenmedi. Bu, frontend geliştirmeye başlamak için gayet iyi bir nokta.

---
