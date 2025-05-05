package com.fibiyo.ecommerce.application.service;

import org.springframework.core.io.Resource; // Dosya kaynağı için
import org.springframework.web.multipart.MultipartFile; // Yüklenen dosya için

import java.nio.file.Path;
import java.util.stream.Stream;

import java.io.InputStream; // InputStream için import eklendi


/**
 * Dosya depolama işlemleri için arayüz.
 */
public interface StorageService {

    /**
     * Servis başlangıcında gerekli klasörleri vb. hazırlar.
     */
    void init();

    /**
     * Verilen dosyayı unique bir isimle kaydeder.
     *
     * @param file Yüklenecek dosya.
     * @return Kaydedilen dosyanın unique adı (uzantısıyla birlikte).
     * @throws RuntimeException Kaydetme sırasında hata oluşursa.
     */
    String store(MultipartFile file);



    /**
     * Ham byte[] verisinden dosya kaydeder.
     *
     * @param bytes Görselin ham içeriği
     * @param extension Dosya uzantısı (örneğin: "png")
     * @return Kaydedilen dosyanın erişilebilir URL'si
     */
    String store(byte[] bytes, String extension);



    String store(InputStream inputStream, String extension, long fileSize); // boyut da almak iyi olabilir


    /**
     * Belirli bir dosyanın Path'ini döndürür.
     *
     * @param filename Dosya adı.
     * @return Dosyanın Path'i.
     */
    Path load(String filename);

    /**
     * Belirli bir dosyayı Resource olarak döndürür (indirme/görüntüleme için).
     *
     * @param filename Dosya adı.
     * @return Resource nesnesi.
     * @throws RuntimeException Dosya bulunamazsa veya okunamassa.
     */
    Resource loadAsResource(String filename);

    /**
     * Kaydedilen dosyanın erişilebilir URL'sini oluşturur.
     * @param filename Kaydedilen dosyanın adı.
     * @return Dosyaya erişim URL'si.
     */
    String generateUrl(String filename);


    /**
     * Belirli bir dosyayı siler.
     *
     * @param filename Silinecek dosyanın adı.
     * @throws RuntimeException Silme sırasında hata oluşursa.
     */
    void delete(String filename);

    /**
     * Tüm yüklenmiş dosyaların Path'lerini bir Stream olarak döndürür. (Admin/Debug için?)
     *
     * @return Dosya Path'lerinin Stream'i.
     * @throws RuntimeException Hata oluşursa.
     */
     // Stream<Path> loadAll(); // Şimdilik gerekmeyebilir


    /**
     * Tüm depolama alanını temizler (Dikkatli kullanılmalı!).
     */
    // void deleteAll(); // Şimdilik gerekmeyebilir
}



