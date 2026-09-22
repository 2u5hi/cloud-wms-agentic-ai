# 0009. Committed OpenAPI contract with a drift check and a generated TypeScript client

**Status:** accepted

## Context
Three languages share one API: Java serves it, TypeScript and Python consume it. Types written by hand on each side drift apart silently.

## Decision
- springdoc generates the spec from the code. It's committed as [`contracts/openapi.yaml`](../../contracts/openapi.yaml).
- A test fails if the committed contract differs from the generated one:

  [`wms-core/.../shared/api/OpenApiContractTest.java`](../../wms-core/src/test/java/com/cloudwms/core/shared/api/OpenApiContractTest.java)
  ```bash
  ./mvnw test -Dopenapi.update=true   # regenerate on purpose
  ```
- The web client's types are generated from the contract (`npm run gen:api` → [`web/src/api/schema.d.ts`](../../web/src/api/schema.d.ts)), and web CI fails if they're stale.
- **One nullability rule for the whole contract:** a property is required unless it's nullable. springdoc emits nullable `$ref`s incorrectly, so they're rewritten to the OpenAPI 3.1 form:

  [`wms-core/.../shared/api/OpenApiConfig.java`](../../wms-core/src/main/java/com/cloudwms/core/shared/api/OpenApiConfig.java)
  ```java
  Schema reference = new Schema().$ref(property.get$ref());
  Schema nullType = new Schema().types(Set.of("null"));
  return new Schema().oneOf(List.of(reference, nullType));
  ```
  In TypeScript this becomes `code: string` instead of `code?: string`, and `pickSlot: PickSlotView | null`.
- Operation IDs are explicit (`listBalances`, `recordMove`), because they become client function names and, later, agent tool names.
- The required `Idempotency-Key` header is added to every POST in the contract, so a generated client can't omit it.

## Consequences
- An API change without a contract update fails backend CI, and a contract change without a client update fails web CI.
- `openapi-typescript` declares support only for TypeScript 5. An npm `overrides` entry lets it use the project's TypeScript 6. It was verified to reject invalid calls, and it can be removed once upstream supports TypeScript 6.
