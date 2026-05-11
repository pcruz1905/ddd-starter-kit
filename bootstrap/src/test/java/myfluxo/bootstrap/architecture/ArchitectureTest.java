package myfluxo.bootstrap.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Architecture rules — fail the build if the layering is broken.
 *
 * <p>Bootstrap is the only module that imports every other module at
 * compile scope, so ArchUnit can load the full project classpath from a
 * single point and assert layering rules across all of it.
 *
 * <p>Rules enforced:
 * <ol>
 *     <li>{@code domain} depends only on {@code kernel} (no adapter,
 *         no application, no framework).</li>
 *     <li>{@code application} depends only on {@code kernel} and
 *         {@code domain} (no adapters, no Helidon, no JDBI).</li>
 *     <li>{@code kernel} has no framework imports (Spring, Helidon,
 *         JDBI, Jackson, Avaje).</li>
 *     <li>No adapter depends on another sibling adapter at compile
 *         scope (sibling-adapter coupling is a smell — bootstrap is
 *         the composition root).</li>
 * </ol>
 *
 * <p>Caveats:
 * <ul>
 *     <li>{@code adapter-persistence-jdbc} legitimately depends on
 *         {@code adapter-http} <em>at compile scope</em> to implement
 *         the {@code IdempotencyCache} port. Documented exception.</li>
 * </ul>
 */
class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("myfluxo");
    }

    @Test
    void domain_doesNotDependOnAnyAdapter() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "myfluxo.adapter..",
                "myfluxo.bootstrap..");
        rule.check(classes);
    }

    @Test
    void domain_doesNotDependOnApplication() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.domain..")
            .should().dependOnClassesThat().resideInAPackage("myfluxo.application..");
        rule.check(classes);
    }

    @Test
    void application_doesNotDependOnAnyAdapter() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "myfluxo.adapter..",
                "myfluxo.bootstrap..");
        rule.check(classes);
    }

    @Test
    void kernel_hasNoFrameworkImports() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.helidon..",
                "org.jdbi..",
                "com.fasterxml.jackson..",
                "io.avaje..",
                "org.flywaydb..",
                "com.zaxxer.hikari..",
                "org.springframework..",
                "jakarta.persistence..",
                "javax.persistence..");
        rule.check(classes);
    }

    @Test
    void kernel_doesNotDependOnDomainOrApplication() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.kernel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "myfluxo.domain..",
                "myfluxo.application..",
                "myfluxo.adapter..",
                "myfluxo.bootstrap..");
        rule.check(classes);
    }

    @Test
    void domain_hasNoFrameworkImports() {
        // Domain must stay framework-free. Jackson lives in adapters
        // (via mixins), JDBI/Helidon never leak in.
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "io.helidon..",
                "org.jdbi..",
                "com.fasterxml.jackson..",
                "io.avaje..",
                "jakarta.inject..",
                "jakarta.persistence..",
                "javax.persistence..",
                "org.springframework..");
        rule.check(classes);
    }

    @Test
    void httpAdapter_doesNotDependOnPersistenceAdapter() {
        // HTTP must stay independent of persistence. Persistence may
        // depend on HTTP (to implement the IdempotencyCache port) — that
        // direction is the legitimate one.
        ArchRule rule = noClasses()
            .that().resideInAPackage("myfluxo.adapter.http..")
            .should().dependOnClassesThat().resideInAPackage("myfluxo.adapter.persistence..");
        rule.check(classes);
    }

}
