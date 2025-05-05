package com.fibiyo.ecommerce.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger logger = LoggerFactory.getLogger(WebConfig.class);

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Value("${file.serve-path:/uploads/}") // Değerle aynı olmalı
    private String servePath;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // uploads/** URL'ini uploadDir'deki dosyalara map'le
         Path uploadPath = Paths.get(uploadDir).toAbsolutePath().normalize();
        String resourceLocation = uploadPath.toUri().toString(); // "file:/C:/path/to/uploads/" formatı

         // servePath sonunda '/' yoksa ekle, başındaki '/' kaldır
         String urlPath = servePath.startsWith("/") ? servePath.substring(1) : servePath;
         urlPath = urlPath.endsWith("/") ? urlPath : urlPath + "/";


        logger.info("Configuring resource handler: Path Pattern = '/{}**', Locations = '{}'", urlPath, resourceLocation);

         registry.addResourceHandler("/" + urlPath + "**") // /uploads/** gibi
                 .addResourceLocations(resourceLocation); // file:/.../uploads/

          // Örnek: registry.addResourceHandler("/uploads/**").addResourceLocations("file:/var/www/uploads/");
          // Örnek: registry.addResourceHandler("/uploads/**").addResourceLocations("file:./uploads/");

          logger.info("Static resource handling configured for path: {} pointing to directory: {}", "/" + urlPath + "**", resourceLocation);

      }
}