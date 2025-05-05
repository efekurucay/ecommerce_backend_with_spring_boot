package com.fibiyo.ecommerce.application.service.impl;

import com.fibiyo.ecommerce.application.exception.BadRequestException;
import com.fibiyo.ecommerce.application.exception.ResourceNotFoundException;
import com.fibiyo.ecommerce.application.service.StorageService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.lang.NonNull; // @NonNull anotasyonu için
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects; // Objects.requireNonNullElse için
import java.util.UUID;
import java.util.stream.Stream;

@Service
public class LocalStorageServiceImpl implements StorageService {

    private static final Logger logger = LoggerFactory.getLogger(LocalStorageServiceImpl.class);

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.serve-path:/uploads/}")
    private String servePath;

    private Path rootLocation;

    // Güvenlik ve Kontrol Sabitleri
    private static final long MAX_FILE_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "webp");

    @Override
    @PostConstruct // Bean oluşturulduktan sonra çalışır
    public void init() {
        try {
            // uploadDir null veya boş ise hata ver
            if (!StringUtils.hasText(this.uploadDir)) {
                logger.error("Configuration error: 'file.upload-dir' property is not set or empty.");
                throw new RuntimeException("Storage location configuration is missing.");
            }
            this.rootLocation = Paths.get(this.uploadDir).toAbsolutePath().normalize();
            if (!Files.exists(rootLocation)) {
                Files.createDirectories(rootLocation);
                logger.info("Created upload directory: {}", rootLocation);
            } else {
                 logger.info("Upload directory already exists: {}", rootLocation);
            }
            // Yazma izni kontrolü (Basit kontrol)
            if (!Files.isWritable(rootLocation)) {
                 logger.error("Upload directory '{}' is not writable.", rootLocation);
                  throw new RuntimeException("Storage location is not writable.");
             }
        } catch (IOException e) {
            logger.error("Could not initialize storage location: '{}'", this.uploadDir, e);
            throw new RuntimeException("Could not initialize storage location: " + this.uploadDir, e);
        } catch (Exception e) { // Genel hatalar
             logger.error("Unexpected error during storage initialization for path: '{}'", this.uploadDir, e);
             throw new RuntimeException("Unexpected error during storage initialization.", e);
         }
    }

    @Override
    public String store(@NonNull MultipartFile file) {
         Objects.requireNonNull(file, "File cannot be null");
        if (file.isEmpty()) {
            throw new BadRequestException("Yüklenemedi: Dosya boş.");
        }

        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String extension = StringUtils.getFilenameExtension(originalFilename);

        // Uzantı ve Tip Kontrolü
        if (!isValidImageExtension(extension)) {
             throw new BadRequestException("Yüklenemedi: Sadece " + String.join(", ", ALLOWED_IMAGE_EXTENSIONS) + " uzantılı resim dosyalarına izin verilir.");
         }

         // Boyut Kontrolü
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BadRequestException("Yüklenemedi: Dosya boyutu çok büyük (Maksimum: " + (MAX_FILE_SIZE_BYTES / 1024 / 1024) + "MB).");
        }

        // Güvenli Kaydetme İşlemi
        try {
            // MultipartFile'dan InputStream al
            try (InputStream inputStream = file.getInputStream()) {
                 // InputStream ve uzantı ile ana store metodunu çağır
                 return store(inputStream, extension, file.getSize());
             }
         } catch (IOException e) {
            // Bu hata genellikle MultipartFile okuma sorunudur
            logger.error("Failed to get InputStream from MultipartFile '{}': {}", originalFilename, e.getMessage(), e);
             throw new RuntimeException("Dosya okunurken hata oluştu: " + originalFilename, e);
        } catch (RuntimeException e){
            // store(InputStream...) metodundan gelen hataları tekrar fırlat
             throw e;
         }
    }


    @Override
    public String store(@NonNull byte[] fileBytes, @NonNull String extension) {
         Objects.requireNonNull(fileBytes, "File bytes cannot be null");
         Objects.requireNonNull(extension, "File extension cannot be null");

         if (fileBytes.length == 0) {
            throw new BadRequestException("Kaydedilemedi: İmaj verisi boş.");
         }

         // Boyut Kontrolü
         if (fileBytes.length > MAX_FILE_SIZE_BYTES) {
             throw new BadRequestException("Kaydedilemedi: Dosya boyutu çok büyük (Maksimum: " + (MAX_FILE_SIZE_BYTES / 1024 / 1024) + "MB).");
         }

          // Uzantı Kontrolü
         String sanitizedExtension = sanitizeAndValidateExtension(extension);

         // Byte array'den InputStream oluştur ve diğer store metodunu çağır
          try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
              return store(inputStream, sanitizedExtension, fileBytes.length);
          } catch(IOException e){ // ByteArrayInputStream close normalde hata vermez ama ekleyelim
               logger.error("Error closing ByteArrayInputStream (unexpected).", e);
              // Bu hata kritik değil, işlemi durdurmamalı. store işlemi zaten tamamlanmış veya hata vermiş olacak.
               // Hata zaten store(InputStream...) içinde fırlatılmış olacak.
              throw new RuntimeException("Görsel işlenirken beklenmedik hata.", e); // Veya store'un hatasını yakala
          }
    }


    @Override
    public String store(@NonNull InputStream inputStream, @NonNull String extension, long fileSize /* = -1 */ ) {
         Objects.requireNonNull(inputStream, "InputStream cannot be null");
         Objects.requireNonNull(extension, "File extension cannot be null");

          String sanitizedExtension = sanitizeAndValidateExtension(extension);

         // Unique dosya adı oluştur
         String uniqueFilename = UUID.randomUUID().toString() + "." + sanitizedExtension;
         logger.debug("Attempting to store stream as '{}'", uniqueFilename);

         try {
            Path destinationFile = this.rootLocation.resolve(uniqueFilename)
                    .normalize().toAbsolutePath();

             // Path Traversal Koruması
            if (!destinationFile.getParent().equals(this.rootLocation.toAbsolutePath())) {
                 // Bu durum ciddi bir güvenlik açığı teşkil eder!
                 logger.error("SECURITY ALERT: Path Traversal attempt detected! Destination: '{}' is outside root: '{}'", destinationFile, this.rootLocation);
                 throw new RuntimeException("Cannot store file outside designated directory.");
            }

            // Dosyayı kopyala (InputStream bu noktada kapanacak try-with-resources sayesinde)
             long bytesWritten = Files.copy(inputStream, destinationFile, StandardCopyOption.REPLACE_EXISTING);
             logger.info("Successfully stored file '{}'. Size: {} bytes", uniqueFilename, bytesWritten);

             // Eğer boyut bilgisi geldiyse ve yazılan farklıysa logla (opsiyonel)
              if (fileSize > 0 && fileSize != bytesWritten) {
                   logger.warn("Stored file '{}' size ({}) differs from provided size ({}).", uniqueFilename, bytesWritten, fileSize);
               }

              return uniqueFilename;

         } catch (IOException e) {
              // Kopyalama sırasındaki I/O hataları
              logger.error("Failed to store file {} from InputStream: {}", uniqueFilename, e.getMessage(), e);
              // Başarısız kopyalama sonrası hedef dosyayı silmeyi deneyebiliriz (garanti değil)
             try { Files.deleteIfExists(this.rootLocation.resolve(uniqueFilename)); } catch (IOException ignored) {}
             throw new RuntimeException("Dosya kaydedilirken hata oluştu: " + uniqueFilename, e);
         } catch (RuntimeException e){ // Path Traversal vs. hataları
             throw e; // Olduğu gibi fırlat
         }
    }

     // Yardımcı metot: Uzantıyı temizler ve geçerliliğini kontrol eder
     private String sanitizeAndValidateExtension(String extension) {
         if (!StringUtils.hasText(extension)) {
             throw new BadRequestException("Dosya uzantısı belirtilmelidir.");
         }
         String sanitized = extension.toLowerCase().replaceAll("[^a-z0-9]", "");
         if (sanitized.isEmpty() || !ALLOWED_IMAGE_EXTENSIONS.contains(sanitized)) {
             throw new BadRequestException("Geçersiz veya desteklenmeyen dosya uzantısı: " + extension
                     + ". İzin verilenler: " + String.join(", ", ALLOWED_IMAGE_EXTENSIONS));
         }
         return sanitized;
     }


    // Yardımcı metot: Uzantının geçerli resim uzantısı olup olmadığını kontrol eder
     private boolean isValidImageExtension(String extension) {
          if (extension == null) return false;
         return ALLOWED_IMAGE_EXTENSIONS.contains(extension.toLowerCase());
      }

    @Override
    public Path load(@NonNull String filename) {
        Objects.requireNonNull(filename, "Filename cannot be null");
        return rootLocation.resolve(filename).normalize();
    }

    @Override
    public Resource loadAsResource(@NonNull String filename) {
         Objects.requireNonNull(filename, "Filename cannot be null");
        try {
            Path file = load(filename);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                logger.error("Could not read file: {}", filename);
                // Kaynak bulunamadığında spesifik exception fırlatmak daha iyi olabilir.
                throw new ResourceNotFoundException("Dosya bulunamadı veya okunamadı: " + filename);
            }
        } catch (MalformedURLException e) {
             logger.error("Could not create URL for file: {} (MalformedURLException)", filename, e);
             throw new RuntimeException("Dosya URL'i oluşturulamadı: " + filename, e);
         } catch (ResourceNotFoundException e) { // Kendi exception'ımızı tekrar fırlat
             throw e;
        } catch (Exception e) { // Diğer beklenmedik hatalar
             logger.error("Error loading file as resource: {}", filename, e);
              throw new RuntimeException("Dosya kaynağı yüklenirken hata: " + filename, e);
         }
    }

    @Override
    public String generateUrl(String filename) {
         if (!StringUtils.hasText(filename)) {
            return null;
         }
        // servePath'in başında ve sonunda '/' olmalı, kontrol et.
         String cleanServePath = servePath.startsWith("/") ? servePath : "/" + servePath;
         cleanServePath = cleanServePath.endsWith("/") ? cleanServePath : cleanServePath + "/";

        try {
            // Dinamik olarak context path'i (eğer varsa) ve sunucu adresi/portunu alır.
             return ServletUriComponentsBuilder.fromCurrentContextPath()
                    .path(cleanServePath)
                     .path(filename)
                    .toUriString();
         } catch (Exception e) {
             // Asenkron işlem gibi request context'i olmayan durumlarda buraya düşebilir.
             logger.warn("Could not generate URL using ServletUriComponentsBuilder (request context might be missing). Falling back to relative path. Filename: {}", filename);
             // Fallback olarak relative path veya properties'den okunan base URL kullanılabilir.
              String backendBaseUrl = "http://localhost:8080"; // VEYA @Value ile al
              return backendBaseUrl + cleanServePath + filename; // Tam URL oluşturmayı dene
         }
    }

    @Override
    public void delete(String filename) {
         if (!StringUtils.hasText(filename)) {
             logger.warn("Attempted to delete null or empty filename.");
            return;
         }
         try {
             Path fileToDelete = load(filename);
             boolean deleted = Files.deleteIfExists(fileToDelete);
             if (deleted) {
                logger.info("Deleted file: {}", filename);
            } else {
                 logger.warn("File to delete not found: {}", filename);
              }
          } catch (IOException e) {
              // Dosya kilitli olabilir, izin olmayabilir vb.
             logger.error("Could not delete file: {}", filename, e);
             throw new RuntimeException("Dosya silinemedi: " + filename, e);
         }
    }

     // Gerekirse loadAll, deleteAll implemente edilebilir.
}