package com.careflow.platform;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class Db {
  public final JdbcTemplate jdbc;

  public Db(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public List<Map<String, Object>> list(String sql, Object... args) {
    return jdbc.query(
        sql,
        (rs, n) -> {
          Map<String, Object> row = new LinkedHashMap<>();
          for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++)
            row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
          return row;
        },
        args);
  }

  public Map<String, Object> one(String sql, Object... args) {
    var rows = list(sql, args);
    if (rows.isEmpty()) throw ApiException.hidden();
    return rows.getFirst();
  }

  public int exec(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  public static String id() {
    return UUID.randomUUID().toString();
  }

  public static String str(Map<String, Object> m, String k) {
    return Objects.toString(m.get(k), "");
  }

  public static long num(Map<String, Object> m, String k) {
    return ((Number) m.get(k)).longValue();
  }

  public static boolean bool(Map<String, Object> m, String k) {
    Object v = m.get(k);
    return Boolean.TRUE.equals(v) || (v instanceof Number n && n.intValue() != 0);
  }
}
