package eg.mts.gsuif.generation;

import java.util.List;

/** Versioned build requirements for a project consuming generated Java sources. */
public record ConsumerBuildContract(String contractVersion, int javaVersion, Dependency parent,
        List<Dependency> dependencies, String methodSecurityPrerequisite) {
    public record Dependency(String groupId, String artifactId, String version, String scope) {
        public Dependency(String groupId, String artifactId, String version) {
            this(groupId, artifactId, version, null);
        }
    }

    public ConsumerBuildContract { dependencies = List.copyOf(dependencies); }

    public static ConsumerBuildContract phaseOne() {
        return new ConsumerBuildContract("1.0.0", 21,
                new Dependency("org.springframework.boot", "spring-boot-starter-parent", "4.0.8"),
                List.of(
                        new Dependency("org.springframework.boot", "spring-boot-starter-web", null),
                        new Dependency("org.springframework.boot", "spring-boot-starter-data-jpa", null),
                        new Dependency("org.springframework.boot", "spring-boot-starter-security", null),
                        new Dependency("org.springframework.boot", "spring-boot-starter-validation", null),
                        new Dependency("org.hibernate.orm", "hibernate-envers", null),
                        new Dependency("org.springdoc", "springdoc-openapi-starter-webmvc-ui", "3.0.3"),
                        new Dependency("org.openapitools", "jackson-databind-nullable", "0.2.6"),
                        new Dependency("org.springframework.boot", "spring-boot-starter-test", null, "test")),
                "Enable Spring method security in the consumer with @EnableMethodSecurity; configure authentication and authorization there.");
    }

    /** A minimal standalone consumer POM; dependency versions without a value come from the parent BOM. */
    public String mavenPom(String groupId, String artifactId) {
        StringBuilder pom = new StringBuilder("<project xmlns=\"http://maven.apache.org/POM/4.0.0\" "
                + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" "
                + "xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">"
                + "<modelVersion>4.0.0</modelVersion><parent><groupId>")
                .append(parent.groupId()).append("</groupId><artifactId>").append(parent.artifactId())
                .append("</artifactId><version>").append(parent.version())
                .append("</version><relativePath/></parent><groupId>").append(groupId)
                .append("</groupId><artifactId>").append(artifactId)
                .append("</artifactId><version>1.0.0</version><properties><java.version>")
                .append(javaVersion).append("</java.version></properties><dependencies>");
        for (Dependency dependency : dependencies) {
            pom.append("<dependency><groupId>").append(dependency.groupId())
                    .append("</groupId><artifactId>").append(dependency.artifactId()).append("</artifactId>");
            if (dependency.version() != null) pom.append("<version>").append(dependency.version()).append("</version>");
            if (dependency.scope() != null) pom.append("<scope>").append(dependency.scope()).append("</scope>");
            pom.append("</dependency>");
        }
        return pom.append("</dependencies></project>").toString();
    }
}
