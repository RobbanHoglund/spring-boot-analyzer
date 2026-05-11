package com.robbanhoglund.springbootanalyzer.analyzer.messaging;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessageListenerEndpoint;
import com.robbanhoglund.springbootanalyzer.analyzer.model.messaging.MessagingAnalysis;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;

@Component
public class MessagingAnalyzer {

    private static final Map<String, String> ANNOTATION_TO_TYPE =
            Map.of(
                    "KafkaListener", "KAFKA",
                    "RabbitListener", "RABBIT",
                    "JmsListener", "JMS",
                    "SqsListener", "SQS");

    // annotation name → which attribute holds destinations
    private static final Map<String, String> DESTINATION_ATTRIBUTE =
            Map.of(
                    "KafkaListener", "topics",
                    "RabbitListener", "queues",
                    "JmsListener", "destination",
                    "SqsListener", "value");

    private final JavaParser javaParser;

    public MessagingAnalyzer() {
        this.javaParser =
                new JavaParser(
                        new ParserConfiguration()
                                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
                                .setCharacterEncoding(StandardCharsets.UTF_8));
    }

    public MessagingAnalysis analyze(Path repositoryRoot) {
        Path sourceRoot = repositoryRoot.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return MessagingAnalysis.empty();
        }

        List<MessageListenerEndpoint> listeners = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .forEach(
                            file -> {
                                try {
                                    javaParser
                                            .parse(file)
                                            .ifSuccessful(
                                                    cu -> {
                                                        String relativePath =
                                                                repositoryRoot
                                                                        .relativize(file)
                                                                        .toString()
                                                                        .replace('\\', '/');
                                                        collectFromCompilationUnit(
                                                                cu, relativePath, listeners);
                                                    });
                                } catch (IOException ignored) {
                                }
                            });
        } catch (IOException e) {
            return MessagingAnalysis.empty();
        }

        return new MessagingAnalysis(List.copyOf(listeners));
    }

    private void collectFromCompilationUnit(
            CompilationUnit cu, String relativePath, List<MessageListenerEndpoint> out) {
        cu.findAll(ClassOrInterfaceDeclaration.class)
                .forEach(
                        cls -> {
                            String className = cls.getNameAsString();
                            for (MethodDeclaration method : cls.getMethods()) {
                                Integer line = method.getBegin().map(p -> p.line).orElse(null);
                                for (AnnotationExpr annotation : method.getAnnotations()) {
                                    String annotationName =
                                            simpleName(annotation.getNameAsString());
                                    String listenerType = ANNOTATION_TO_TYPE.get(annotationName);
                                    if (listenerType == null) continue;

                                    String destAttr = DESTINATION_ATTRIBUTE.get(annotationName);
                                    List<String> destinations =
                                            extractDestinations(annotation, destAttr);
                                    String groupId = extractStringAttribute(annotation, "groupId");

                                    out.add(
                                            new MessageListenerEndpoint(
                                                    listenerType,
                                                    destinations,
                                                    groupId,
                                                    className,
                                                    method.getNameAsString(),
                                                    relativePath,
                                                    line));
                                }
                            }
                        });
    }

    private List<String> extractDestinations(AnnotationExpr annotation, String attrName) {
        if (annotation.isSingleMemberAnnotationExpr()) {
            return expandExpression(annotation.asSingleMemberAnnotationExpr().getMemberValue());
        }
        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if (pair.getNameAsString().equals(attrName)) {
                    return expandExpression(pair.getValue());
                }
            }
            // KafkaListener may use topicPattern instead of topics
            if ("topics".equals(attrName)) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if (pair.getNameAsString().equals("topicPattern")) {
                        return List.of(
                                pair.getValue().isStringLiteralExpr()
                                        ? pair.getValue().asStringLiteralExpr().asString()
                                        : pair.getValue().toString());
                    }
                }
            }
        }
        return List.of();
    }

    private String extractStringAttribute(AnnotationExpr annotation, String attrName) {
        if (!annotation.isNormalAnnotationExpr()) return null;
        for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
            if (pair.getNameAsString().equals(attrName)) {
                Expression val = pair.getValue();
                return val.isStringLiteralExpr()
                        ? val.asStringLiteralExpr().asString()
                        : val.toString();
            }
        }
        return null;
    }

    private List<String> expandExpression(Expression expr) {
        if (expr.isStringLiteralExpr()) return List.of(expr.asStringLiteralExpr().asString());
        if (expr.isArrayInitializerExpr()) {
            ArrayInitializerExpr arr = expr.asArrayInitializerExpr();
            return arr.getValues().stream()
                    .map(
                            e ->
                                    e.isStringLiteralExpr()
                                            ? e.asStringLiteralExpr().asString()
                                            : e.toString())
                    .toList();
        }
        return List.of(expr.toString());
    }

    private static String simpleName(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }
}
