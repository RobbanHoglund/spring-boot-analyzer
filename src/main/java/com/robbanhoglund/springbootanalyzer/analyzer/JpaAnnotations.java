package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.List;

/**
 * Tells JPA's mapping annotations apart from like-named annotations of other mappers, such as
 * the DataStax Cassandra driver's {@code @Entity} or Morphia's {@code @Entity}. JPA rules
 * (proxies, no-arg constructors, {@code @Id}) do not apply to those.
 */
final class JpaAnnotations {

    private static final List<String> JPA_PACKAGES =
            List.of("jakarta.persistence", "javax.persistence");

    private JpaAnnotations() {}

    /** Whether the type is a JPA {@code @Entity}. */
    static boolean isJpaEntity(NodeWithAnnotations<?> type) {
        return hasJpaAnnotation(type, "Entity");
    }

    /** Whether the type carries the JPA annotation with this simple name. */
    static boolean hasJpaAnnotation(NodeWithAnnotations<?> type, String simpleName) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> isJpaAnnotation(annotation, simpleName));
    }

    /**
     * Whether {@code annotation} is JPA's annotation of that simple name: written fully qualified
     * with a JPA package, or used by simple name in a file that does not import a like-named
     * annotation from another library. A file without any import keeps counting as JPA, so
     * sources that rely on a wildcard import are still analyzed.
     */
    static boolean isJpaAnnotation(AnnotationExpr annotation, String simpleName) {
        String name = annotation.getNameAsString();
        if (name.contains(".")) {
            return JPA_PACKAGES.stream()
                    .anyMatch(packageName -> name.equals(packageName + "." + simpleName));
        }
        if (!name.equals(simpleName)) {
            return false;
        }
        CompilationUnit cu = annotation.findCompilationUnit().orElse(null);
        if (cu == null) {
            return true;
        }
        for (ImportDeclaration imported : cu.getImports()) {
            String importedName = imported.getNameAsString();
            if (!imported.isAsterisk()
                    && !imported.isStatic()
                    && importedName.endsWith("." + simpleName)) {
                return JPA_PACKAGES.stream()
                        .anyMatch(
                                packageName -> importedName.equals(packageName + "." + simpleName));
            }
        }
        return true;
    }
}
