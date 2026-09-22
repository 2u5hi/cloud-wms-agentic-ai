# 0014. One Spring context and one MySQL container for all integration tests

**Status:** accepted

## Context
Spring caches test contexts by their configuration. A test with a slightly different set of annotations started its own context, and therefore its own MySQL container, which added about 13 seconds each time.

## Decision
Every database-backed test uses one meta-annotation, so all of them share a single cached context and container.

[`wms-core/.../IntegrationTest.java`](../../wms-core/src/test/java/com/cloudwms/core/IntegrationTest.java)
```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
public @interface IntegrationTest {
}
```

Tests that commit (the ledger can't be deleted from) create master data with unique codes ([`InventoryFixtures.java`](../../wms-core/src/test/java/com/cloudwms/core/inventory/InventoryFixtures.java)), so tests never collide in the shared database.

Web-layer tests use `@WebMvcTest(controllers = ...)`, loading only the controller they test, so new controllers and their dependencies don't break them.

## Consequences
- The full suite starts one container, which takes about 13 seconds.
- Tests can't assume an empty database, so they filter by their own codes. The dev seed test's ledger reconciliation runs across *everything* in the database, which makes it a check on every other test's data too.
