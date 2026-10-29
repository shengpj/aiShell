package com.aishell.spike.agent;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public final class OpsTools {

  private OpsTools() {}

  public static Map<String, Object> getServerTime(String timezone) {
    ZoneId zone = ZoneId.of(timezone);
    return Map.of(
        "timezone", zone.getId(),
        "isoTime", ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
  }
}
