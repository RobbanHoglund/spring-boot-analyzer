package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.robbanhoglund.springbootanalyzer.analyzer.model.Finding;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingConfidence;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingFactory;
import com.robbanhoglund.springbootanalyzer.analyzer.model.FindingRules;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Detects Spring MVC handler declarations that fail on every call or at startup.
 *
 * <p>Rules covered:
 *
 * <ul>
 *   <li>{@link FindingRules#SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER} — an optional request
 *       value declared as a primitive without a default.
 *   <li>{@link FindingRules#SPRING_MULTIPLE_REQUEST_BODY} — more than one {@code @RequestBody}
 *       parameter.
 *   <li>{@link FindingRules#SPRING_AMBIGUOUS_HANDLER_MAPPING} — two handler methods with the same
 *       mapping.
 * </ul>
 */
@Component
public class WebHandlerFindingAnalyzer {

    private static final Set<String> CONTROLLER_ANNOTATIONS =
            Set.of("RestController", "Controller");

    private static final Map<String, String> SHORTCUT_MAPPINGS =
            Map.of(
                    "GetMapping", "GET",
                    "PostMapping", "POST",
                    "PutMapping", "PUT",
                    "DeleteMapping", "DELETE",
                    "PatchMapping", "PATCH");

    private static final Set<String> NAMED_VALUE_ANNOTATIONS =
            Set.of(
                    "RequestParam",
                    "RequestHeader",
                    "PathVariable",
                    "CookieValue",
                    "MatrixVariable",
                    "RequestAttribute",
                    "SessionAttribute");

    private static final Set<String> NON_BOOLEAN_PRIMITIVES =
            Set.of("int", "long", "short", "byte", "double", "float", "char");

    /** A handler mapping in normalized form: equal keys make Spring MVC refuse to start. */
    private record Mapping(
            String key, String description, String target, String relativePath, Integer line) {}

    public List<Finding> analyze(JavaSources sources) {
        List<Finding> findings = new ArrayList<>();
        Map<String, Mapping> mappings = new LinkedHashMap<>();
        long applications =
                sources.files().stream()
                        .filter(file -> file.content().contains("@SpringBootApplication"))
                        .count();
        for (JavaSources.JavaFile file : sources.files()) {
            CompilationUnit cu = file.compilationUnit();
            if (cu == null) {
                continue;
            }
            for (ClassOrInterfaceDeclaration cls : cu.findAll(ClassOrInterfaceDeclaration.class)) {
                if (cls.isInterface()
                        || !hasAnyAnnotation(cls.getAnnotations(), CONTROLLER_ANNOTATIONS)) {
                    continue;
                }
                // Controllers that are only active in some profiles may legitimately share paths.
                boolean conditional =
                        cls.getAnnotations().stream()
                                .map(a -> simpleName(a.getNameAsString()))
                                .anyMatch(
                                        name ->
                                                name.equals("Profile")
                                                        || name.startsWith("Conditional"));
                for (MethodDeclaration method : cls.getMethods()) {
                    AnnotationExpr mapping = mappingAnnotation(method);
                    if (mapping == null) {
                        continue;
                    }
                    detectOptionalPrimitiveParameters(cls, method, file.relativePath(), findings);
                    detectMultipleRequestBodies(cls, method, file.relativePath(), findings);
                    if (!conditional && !cls.isAbstract() && applications <= 1) {
                        Mapping current = mappingOf(cls, method, mapping, file.relativePath());
                        Mapping existing = mappings.putIfAbsent(current.key(), current);
                        if (existing != null) {
                            addAmbiguousMapping(existing, current, findings);
                        }
                    }
                }
            }
        }
        return findings;
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER
    // ---------------------------------------------------------------------------

    private void detectOptionalPrimitiveParameters(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        for (Parameter parameter : method.getParameters()) {
            String type = parameter.getTypeAsString();
            if (!NON_BOOLEAN_PRIMITIVES.contains(type)) {
                continue;
            }
            for (AnnotationExpr annotation : parameter.getAnnotations()) {
                String name = simpleName(annotation.getNameAsString());
                if (!NAMED_VALUE_ANNOTATIONS.contains(name)
                        || !"false".equals(attributeText(annotation, "required"))
                        || attributeText(annotation, "defaultValue") != null) {
                    continue;
                }
                String target = cls.getNameAsString() + "#" + method.getNameAsString();
                String wrapper = wrapperOf(type);
                findings.add(
                        FindingFactory.builder(
                                        FindingRules.SPRING_OPTIONAL_PRIMITIVE_REQUEST_PARAMETER,
                                        FindingConfidence.HIGH)
                                .shortMessage(
                                        "@"
                                                + name
                                                + "(required = false) "
                                                + type
                                                + " "
                                                + parameter.getNameAsString()
                                                + " in "
                                                + target
                                                + " cannot be missing — requests without it fail"
                                                + " with 500.")
                                .whyBadPractice(
                                        "When an optional value is absent Spring passes null, and a"
                                                + " primitive cannot hold null. Spring throws"
                                                + " IllegalStateException (\"Optional "
                                                + type
                                                + " parameter ... is present but cannot be"
                                                + " translated into a null value due to being"
                                                + " declared as a primitive type\"); only boolean"
                                                + " falls back to false.")
                                .possibleImpact(
                                        "Every request that omits the value gets a 500 instead of"
                                                + " the default behaviour the handler expects.")
                                .recommendation(
                                        "Declare the parameter as "
                                                + wrapper
                                                + " (or Optional<"
                                                + wrapper
                                                + ">), or give it a defaultValue.")
                                .evidence(
                                        "Parameter "
                                                + parameter.getNameAsString()
                                                + " of "
                                                + target
                                                + " is a "
                                                + type
                                                + " with @"
                                                + name
                                                + "(required = false) and no defaultValue.")
                                .source(
                                        relativePath,
                                        parameter.getBegin().map(p -> p.line).orElse(null))
                                .target(target)
                                .build());
            }
        }
    }

    private static String wrapperOf(String primitive) {
        return switch (primitive) {
            case "int" -> "Integer";
            case "char" -> "Character";
            default -> Character.toUpperCase(primitive.charAt(0)) + primitive.substring(1);
        };
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_MULTIPLE_REQUEST_BODY
    // ---------------------------------------------------------------------------

    private void detectMultipleRequestBodies(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            String relativePath,
            List<Finding> findings) {
        List<Parameter> bodies =
                method.getParameters().stream()
                        .filter(
                                parameter ->
                                        hasAnyAnnotation(
                                                parameter.getAnnotations(), Set.of("RequestBody")))
                        .toList();
        if (bodies.size() < 2) {
            return;
        }
        String target = cls.getNameAsString() + "#" + method.getNameAsString();
        String names =
                bodies.stream().map(Parameter::getNameAsString).collect(Collectors.joining(", "));
        // An optional extra body gets null instead of failing the request.
        boolean extraBodyRequired =
                bodies.stream().skip(1).anyMatch(parameter -> !isOptionalBody(parameter));
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_MULTIPLE_REQUEST_BODY, FindingConfidence.HIGH)
                        .shortMessage(
                                target
                                        + " declares "
                                        + bodies.size()
                                        + " @RequestBody parameters ("
                                        + names
                                        + ") — "
                                        + (extraBodyRequired
                                                ? "every call fails with 400."
                                                : "only the first one ever receives the body."))
                        .whyBadPractice(
                                "The request body is a stream that can be read once. The first"
                                    + " @RequestBody consumes it, and the next one finds it empty:"
                                    + " Spring rejects the request with"
                                    + " HttpMessageNotReadableException (\"Required request body is"
                                    + " missing\"), or passes null when that parameter is"
                                    + " optional.")
                        .possibleImpact(
                                extraBodyRequired
                                        ? "The endpoint cannot be called successfully."
                                        : "The optional parameters are always null, so the data"
                                                + " the client sends for them is lost.")
                        .recommendation(
                                "Wrap the values in one request DTO (for example a record with both"
                                        + " parts) and accept that as the single @RequestBody.")
                        .evidence(target + " has @RequestBody on " + names + ".")
                        .source(relativePath, method.getBegin().map(p -> p.line).orElse(null))
                        .target(target)
                        .build());
    }

    private static boolean isOptionalBody(Parameter parameter) {
        return parameter.getAnnotations().stream()
                .filter(a -> "RequestBody".equals(simpleName(a.getNameAsString())))
                .anyMatch(a -> "false".equals(attributeText(a, "required")));
    }

    // ---------------------------------------------------------------------------
    // Rule: SPRING_AMBIGUOUS_HANDLER_MAPPING
    // ---------------------------------------------------------------------------

    private static AnnotationExpr mappingAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .filter(
                        a -> {
                            String name = simpleName(a.getNameAsString());
                            return name.equals("RequestMapping")
                                    || SHORTCUT_MAPPINGS.containsKey(name);
                        })
                .findFirst()
                .orElse(null);
    }

    private Mapping mappingOf(
            ClassOrInterfaceDeclaration cls,
            MethodDeclaration method,
            AnnotationExpr mapping,
            String relativePath) {
        AnnotationExpr classMapping =
                cls.getAnnotations().stream()
                        .filter(a -> "RequestMapping".equals(simpleName(a.getNameAsString())))
                        .findFirst()
                        .orElse(null);
        List<String> classPaths = classMapping == null ? List.of("") : paths(classMapping);
        List<String> methodPaths = paths(mapping);
        TreeSet<String> patterns = new TreeSet<>();
        for (String classPath : classPaths) {
            for (String methodPath : methodPaths) {
                patterns.add(combine(classPath, methodPath));
            }
        }
        String name = simpleName(mapping.getNameAsString());
        TreeSet<String> methods = new TreeSet<>();
        if (SHORTCUT_MAPPINGS.containsKey(name)) {
            methods.add(SHORTCUT_MAPPINGS.get(name));
        } else {
            for (Expression value : values(mapping, "method")) {
                String text = value.toString();
                methods.add(text.substring(text.lastIndexOf('.') + 1));
            }
        }
        StringBuilder conditions = new StringBuilder();
        for (String attribute : List.of("params", "headers", "consumes", "produces", "version")) {
            TreeSet<String> merged = new TreeSet<>();
            for (AnnotationExpr source :
                    classMapping == null ? List.of(mapping) : List.of(classMapping, mapping)) {
                values(source, attribute).forEach(value -> merged.add(value.toString()));
            }
            conditions.append(attribute).append('=').append(merged).append(';');
        }
        String verbs = methods.isEmpty() ? "ALL" : String.join(",", methods);
        String key = patterns + "|" + verbs + "|" + conditions;
        return new Mapping(
                key,
                verbs + " " + String.join(", ", patterns),
                cls.getNameAsString() + "#" + method.getNameAsString(),
                relativePath,
                method.getBegin().map(p -> p.line).orElse(null));
    }

    private void addAmbiguousMapping(Mapping first, Mapping second, List<Finding> findings) {
        findings.add(
                FindingFactory.builder(
                                FindingRules.SPRING_AMBIGUOUS_HANDLER_MAPPING,
                                FindingConfidence.HIGH)
                        .shortMessage(
                                second.description()
                                        + " is mapped by both "
                                        + first.target()
                                        + " and "
                                        + second.target()
                                        + " — Spring MVC refuses to start.")
                        .whyBadPractice(
                                "Spring MVC registers every handler method under its mapping (path,"
                                    + " HTTP methods, params, headers, consumes, produces). Two"
                                    + " identical mappings cannot be told apart, so registration"
                                    + " fails with IllegalStateException (\"Ambiguous mapping."
                                    + " Cannot map ... There is already ... bean method mapped\").")
                        .possibleImpact("The application context fails to start.")
                        .recommendation(
                                "Remove the duplicate endpoint, give one of them a different path,"
                                        + " or narrow it with params, headers, consumes or"
                                        + " produces.")
                        .evidence(
                                first.target()
                                        + " ("
                                        + first.relativePath()
                                        + ") and "
                                        + second.target()
                                        + " ("
                                        + second.relativePath()
                                        + ") both declare "
                                        + second.description()
                                        + " with the same conditions.")
                        .limitations(
                                "Controllers guarded by @Profile or @Conditional... annotations are"
                                        + " not compared, and path variables with different names"
                                        + " count as different patterns, like Spring's own check.")
                        .source(second.relativePath(), second.line())
                        .target(second.target())
                        .build());
    }

    private static List<String> paths(AnnotationExpr mapping) {
        List<String> paths = new ArrayList<>();
        for (String attribute : List.of("value", "path")) {
            for (Expression value : values(mapping, attribute)) {
                if (value.isStringLiteralExpr()) {
                    paths.add(value.asStringLiteralExpr().asString());
                } else {
                    paths.add("<" + value + ">");
                }
            }
        }
        return paths.isEmpty() ? List.of("") : paths;
    }

    private static String combine(String classPath, String methodPath) {
        String combined;
        if (classPath.isEmpty()) {
            combined = methodPath;
        } else if (methodPath.isEmpty()) {
            combined = classPath;
        } else if (classPath.endsWith("/") && methodPath.startsWith("/")) {
            combined = classPath + methodPath.substring(1);
        } else if (classPath.endsWith("/") || methodPath.startsWith("/")) {
            combined = classPath + methodPath;
        } else {
            combined = classPath + "/" + methodPath;
        }
        return combined.startsWith("/") ? combined : "/" + combined;
    }

    private static List<Expression> values(AnnotationExpr annotation, String attribute) {
        List<Expression> values = new ArrayList<>();
        if (annotation.isSingleMemberAnnotationExpr()) {
            if ("value".equals(attribute)) {
                flatten(annotation.asSingleMemberAnnotationExpr().getMemberValue(), values);
            }
        } else if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (attribute.equals(pair.getNameAsString())) {
                    flatten(pair.getValue(), values);
                }
            }
        }
        return values;
    }

    private static void flatten(Expression expression, List<Expression> values) {
        if (expression instanceof ArrayInitializerExpr array) {
            values.addAll(array.getValues());
        } else {
            values.add(expression);
        }
    }

    private static String attributeText(AnnotationExpr annotation, String attribute) {
        if (!annotation.isNormalAnnotationExpr()) {
            return null;
        }
        return annotation.asNormalAnnotationExpr().getPairs().stream()
                .filter(pair -> attribute.equals(pair.getNameAsString()))
                .map(pair -> pair.getValue().toString())
                .findFirst()
                .orElse(null);
    }

    private static boolean hasAnyAnnotation(List<AnnotationExpr> annotations, Set<String> names) {
        return annotations.stream().anyMatch(a -> names.contains(simpleName(a.getNameAsString())));
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
