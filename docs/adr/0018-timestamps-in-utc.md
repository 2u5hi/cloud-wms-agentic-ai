# 0018. Timestamps are UTC end to end, and tests run outside UTC

**Status:** accepted

## Context
MySQL runs in UTC (`--default-time-zone=+00:00`). By default, MySQL Connector/J converts timestamps between the server and the **JVM's local time zone**. On a machine in EDT, that shifted every timestamp by 4 hours:

| | Expected | Was |
|---|---|---|
| Order `receivedAt` (set by the database) | `15:11Z` | `19:11Z` in the API |
| Order `carrierCutoffAt` `21:00Z` (written by the app) | stored as `21:00` | stored as `17:00` |

Values the app wrote and read back looked correct from inside the app, which hid the bug. They were still wrong in the database, and so wrong for any SQL comparing them with `NOW()`. No test caught it: nothing checked timestamps precisely, and CI runs in UTC, where the bug is invisible.

## Decision
**Tell the driver the server is in UTC.** It's set as a connection property, so it applies to the app and to Testcontainers alike:

[`wms-core/src/main/resources/application.yml`](../../wms-core/src/main/resources/application.yml)
```yaml
hikari:
  data-source-properties:
    connectionTimeZone: UTC
```

**Run every test in a non-UTC zone,** so timezone bugs fail everywhere, including in CI:

[`wms-core/pom.xml`](../../wms-core/pom.xml)
```xml
<argLine>-Duser.timezone=America/New_York</argLine>
```

[`wms-core/.../DatabaseSettingsTest.java`](../../wms-core/src/test/java/com/cloudwms/core/DatabaseSettingsTest.java)
```java
String stored = jdbc.sql("SELECT DATE_FORMAT(?, '%Y-%m-%d %H:%i')").param(Timestamp.from(cutoff)).query(String.class).single();
assertThat(stored).isEqualTo("2026-09-22 21:00");   // was "17:00" without the fix
```

## Consequences
- Without the fix, 2 of the new tests fail; with it, all pass. The full suite passes in `America/New_York`.
- The API always speaks ISO-8601 instants with `Z`, and the database always stores UTC.
- Existing rows the app wrote before the fix had shifted values. In development, that was one demo order, which was re-imported.
