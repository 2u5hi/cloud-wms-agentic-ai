# 0001. Maven, with every version pinned and enforced

**Status:** accepted

## Context
The design called for Gradle. When the project was created, Spring Initializr returned HTTP 500 for every Gradle project, while Maven projects generated fine. Separately, floating versions (`mysql:8.4`, `google-cloud-cli:emulators`) make builds differ between machines and over time.

## Decision
- Build `wms-core` with Maven through the Maven wrapper, so nobody needs Maven installed.
- Pin exact versions of everything: Spring Boot, container images, GitHub Actions.
- Fail fast on the wrong JDK or Maven version with the enforcer plugin.

[`wms-core/pom.xml`](../../wms-core/pom.xml)
```xml
<requireJavaVersion>
    <version>[21,22)</version>
    <message>wms-core requires JDK 21. Point JAVA_HOME at a JDK 21 install.</message>
</requireJavaVersion>
```

[`deploy/compose/docker-compose.yml`](../../deploy/compose/docker-compose.yml)
```yaml
image: mysql:8.4.11
image: gcr.io/google.com/cloudsdktool/google-cloud-cli:585.0.0-emulators
```

## Consequences
- Building on JDK 17 stops with a clear message instead of a confusing compiler error.
- Maven is at least as common as Gradle in enterprise Java shops. The design itself doesn't depend on the build tool.
- Upgrading is a deliberate one-line change, which CI then verifies.
