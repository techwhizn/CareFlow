package com.careflow.platform;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.*;

/** TIMESTAMP represents an instant; the JDBC and server session clocks must agree. */
@Configuration(proxyBeanMethods = false)
public class DatabaseClock {
  @Bean
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DataSourceProperties properties) {
    var source = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    source.setJdbcUrl(utcUrl(source.getJdbcUrl()));
    return source;
  }

  static String utcUrl(String url) {
    if (url == null || !url.startsWith("jdbc:mysql:")) return url;
    String[] parts = url.split("\\?", 2);
    List<String> query = new ArrayList<>();
    if (parts.length == 2)
      for (String parameter : parts[1].split("&")) {
        String key =
            URLDecoder.decode(parameter.split("=", 2)[0], StandardCharsets.UTF_8)
                .toLowerCase(Locale.ROOT);
        if (!Set.of(
                    "connectiontimezone",
                    "servertimezone",
                    "forceconnectiontimezonetosession",
                    "preserveinstants")
                .contains(key)
            && !parameter.isBlank()) query.add(parameter);
      }
    query.add("connectionTimeZone=%2B00:00");
    query.add("forceConnectionTimeZoneToSession=true");
    query.add("preserveInstants=true");
    return parts[0] + "?" + String.join("&", query);
  }
}
