package com.fibiyo.ecommerce.infrastructure.web.controller;

import com.fibiyo.ecommerce.application.dto.ApiResponse; // ApiResponse kullan
import com.fibiyo.ecommerce.application.dto.ProductRequest;
import com.fibiyo.ecommerce.application.dto.ProductResponse;
import com.fibiyo.ecommerce.application.service.ProductService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable; // Sayfalama için
import org.springframework.data.web.PageableDefault; // Varsayılan sayfalama ayarı
import org.springframework.data.domain.Sort; // Sıralama yönü
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize; // Yetkilendirme için
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/api/products")
public class ProductController {

    private static final Logger logger = LoggerFactory.getLogger(ProductController.class);

    private final ProductService productService;

    @Autowired
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // --- Public Endpoints ---

    @GetMapping
    public ResponseEntity<Page<ProductResponse>> getPublicProducts(
            // Varsayılan sayfalama: 10 ürün, ID'ye göre artan sıralı
            @PageableDefault(size = 10, sort = "id", direction = Sort.Direction.ASC) Pageable pageable,
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) String search) {
         logger.info("GET /api/products requested (public). Category: {}, Search: '{}', Page: {}", categoryId, search, pageable);
         Page<ProductResponse> products = productService.findActiveAndApprovedProducts(pageable, categoryId, search);
        return ResponseEntity.ok(products);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getPublicProductById(@PathVariable Long id) {
        logger.info("GET /api/products/{} requested (public)", id);
         ProductResponse product = productService.findActiveAndApprovedProductById(id);
         return ResponseEntity.ok(product);
    }

     @GetMapping("/slug/{slug}")
    public ResponseEntity<ProductResponse> getPublicProductBySlug(@PathVariable String slug) {
         logger.info("GET /api/products/slug/{} requested (public)", slug);
          ProductResponse product = productService.findActiveAndApprovedProductBySlug(slug);
         return ResponseEntity.ok(product);
    }

    // --- Seller Endpoints ---

    @PostMapping
    @PreAuthorize("hasRole('SELLER')")
    public ResponseEntity<ProductResponse> createProduct(@Valid @RequestBody ProductRequest productRequest) {
        logger.info("POST /api/products requested by seller");
        ProductResponse createdProduct = productService.createProductBySeller(productRequest);
        return new ResponseEntity<>(createdProduct, HttpStatus.CREATED);
    }

     @GetMapping("/seller/my")
     @PreAuthorize("hasRole('SELLER')")
     public ResponseEntity<Page<ProductResponse>> getMyProducts(
             @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
         logger.info("GET /api/products/seller/my requested");
         Page<ProductResponse> products = productService.findProductsByCurrentSeller(pageable);
         return ResponseEntity.ok(products);
     }

     @PutMapping("/{id}") // Satıcı veya Admin güncelleyebilir (Yetki serviste kontrol ediliyor)
     @PreAuthorize("hasRole('SELLER') or hasRole('ADMIN')")
    public ResponseEntity<ProductResponse> updateProduct(@PathVariable Long id, @Valid @RequestBody ProductRequest productRequest) {
         logger.info("PUT /api/products/{} requested", id);
         ProductResponse updatedProduct = productService.updateProductBySeller(id, productRequest); // Veya genel bir update metodu
         return ResponseEntity.ok(updatedProduct);
    }

      @DeleteMapping("/{id}") // Satıcı veya Admin silebilir (Yetki serviste kontrol ediliyor)
      @PreAuthorize("hasRole('SELLER') or hasRole('ADMIN')")
     public ResponseEntity<ApiResponse> deleteProduct(@PathVariable Long id) {
          logger.warn("DELETE /api/products/{} requested", id);
          // Role göre farklı servis metodu çağrılabilir veya serviste kontrol edilebilir.
           // Şimdilik serviste kontrol ediliyor varsayalım.
          productService.deleteProductBySeller(id); // Veya productService.deleteProduct(id) gibi genel bir metod
          return ResponseEntity.ok(new ApiResponse(true, "Ürün başarıyla silindi."));
      }


      @PatchMapping("/{id}/activate-seller") // Satıcının aktif/pasif yapması
      @PreAuthorize("hasRole('SELLER')")
     public ResponseEntity<ProductResponse> setSellerProductActive(@PathVariable Long id, @RequestParam boolean isActive) {
           logger.info("PATCH /api/products/{}/activate-seller requested with status: {}", id, isActive);
           ProductResponse product = productService.setSellerProductActiveStatus(id, isActive);
          return ResponseEntity.ok(product);
     }


    // --- Admin Endpoints ---

      @GetMapping("/admin/all") // Adminin tüm ürünleri görmesi
      @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<Page<ProductResponse>> getAllProductsAdmin(
            @PageableDefault(size = 20, sort = "id") Pageable pageable,
            @RequestParam(required = false) Long sellerId,
             @RequestParam(required = false) Long categoryId,
             @RequestParam(required = false) Boolean isApproved,
             @RequestParam(required = false) Boolean isActive,
             @RequestParam(required = false) String search) {
          logger.info("GET /api/products/admin/all requested by admin");
           Page<ProductResponse> products = productService.findAllProductsAdmin(pageable, sellerId, categoryId, isApproved, isActive, search);
          return ResponseEntity.ok(products);
     }

      @GetMapping("/admin/{id}") // Adminin belirli bir ürünü görmesi (aktif/onaylı farketmez)
      @PreAuthorize("hasRole('ADMIN')")
      public ResponseEntity<ProductResponse> getProductByIdAdmin(@PathVariable Long id) {
         logger.info("GET /api/products/admin/{} requested by admin", id);
          ProductResponse product = productService.findProductByIdAdmin(id);
         return ResponseEntity.ok(product);
      }

      @PatchMapping("/{id}/approve")
      @PreAuthorize("hasRole('ADMIN')")
     public ResponseEntity<ProductResponse> approveProduct(@PathVariable Long id) {
           logger.info("PATCH /api/products/{}/approve requested by admin", id);
           ProductResponse product = productService.approveProduct(id);
          return ResponseEntity.ok(product);
     }

      @PatchMapping("/{id}/reject")
      @PreAuthorize("hasRole('ADMIN')")
      public ResponseEntity<ProductResponse> rejectProduct(@PathVariable Long id) {
          logger.warn("PATCH /api/products/{}/reject requested by admin", id);
          ProductResponse product = productService.rejectProduct(id);
          return ResponseEntity.ok(product);
      }

       @PatchMapping("/{id}/activate-admin") // Adminin aktif/pasif yapması
       @PreAuthorize("hasRole('ADMIN')")
      public ResponseEntity<ProductResponse> setAdminProductActive(@PathVariable Long id, @RequestParam boolean isActive) {
            logger.info("PATCH /api/products/{}/activate-admin requested by admin with status: {}", id, isActive);
           ProductResponse product = productService.setAdminProductActiveStatus(id, isActive);
            return ResponseEntity.ok(product);
       }

    // NOT: Admin için silme işlemi, Seller ile aynı DELETE /{id} endpoint'ini kullanabilir,
    // yetki kontrolü serviste yapılır. İstenirse ayrı endpoint (/admin/{id}) de oluşturulabilir.
}