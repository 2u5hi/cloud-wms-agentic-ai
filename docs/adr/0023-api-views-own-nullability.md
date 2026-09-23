# 0023. API views own the contract, including what can be null

**Status:** accepted

## Context
`GET /waves/{n}/diagnosis` returned the domain record `WaveDiagnosis.Blocker` straight out of the controller. The domain is deliberately free of springdoc annotations ([ADR 0002](0002-jdbcclient-over-jpa.md) keeps the same spirit for persistence), so nothing told the generator that `replenishment` is null on a `SHORT_ALLOCATED` blocker. The contract therefore listed it as required, and the generated TypeScript client typed it non-null:

```ts
replenishment: components['schemas']['PendingReplenishment']  // lies on half the blockers
```

The console found this immediately: reading `blocker.replenishment.requiredEquipment` type-checks and throws at runtime.

## Decision
Records exposed by the API live in the `*.api` package and are the only place nullability is declared. The diagnosis now returns [`WaveViews.BlockerView`](../../wms-core/src/main/java/com/cloudwms/core/waves/api/WaveViews.java), mapped from the domain blocker:

```java
public static BlockerView of(Blocker blocker) {
    return new BlockerView(blocker.kind(), blocker.rootCause(), ..., blocker.detail(),
            blocker.replenishment() == null ? null : ReplenishmentView.of(blocker.replenishment()),
            blocker.orders());
}
```

`@Schema(nullable = true)` on the optional components makes the contract say so, and the customizer from [ADR 0009](0009-contract-first-openapi.md) turns that into `oneOf: [ref, "null"]`, so `npm run gen:api` produces `replenishment?: ReplenishmentView | null`.

## Consequences
- Domain records stay annotation-free and can change shape without a contract change; the mapping is the seam.
- One extra record per exposed domain type. Worth it: the generated client is the only thing the web and the agent see.
- Rule for new endpoints: never return a domain record directly, and mark every field that is sometimes absent.
