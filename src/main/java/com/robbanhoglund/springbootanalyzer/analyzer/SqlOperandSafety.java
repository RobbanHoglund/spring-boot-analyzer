package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.type.Type;
import com.robbanhoglund.springbootanalyzer.analyzer.source.JavaSources;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Decides whether a value concatenated into a query string can carry SQL. Numbers, booleans,
 * UUIDs, dates and enum constants cannot: {@code "DELETE FROM Pet WHERE id=" + pet.getId()} with
 * a numeric id is clumsy but not injectable. Only values whose type the source proves are
 * accepted; anything unresolved still counts as a possible injection.
 */
final class SqlOperandSafety {

    /** Types whose string form cannot contain a quote or SQL syntax. */
    private static final Set<String> SAFE_TYPES =
            Set.of(
                    "byte",
                    "short",
                    "int",
                    "long",
                    "float",
                    "double",
                    "boolean",
                    "Byte",
                    "Short",
                    "Integer",
                    "Long",
                    "Float",
                    "Double",
                    "Boolean",
                    "BigInteger",
                    "BigDecimal",
                    "Number",
                    "AtomicInteger",
                    "AtomicLong",
                    "UUID",
                    "LocalDate",
                    "LocalDateTime",
                    "LocalTime",
                    "Instant",
                    "OffsetDateTime",
                    "ZonedDateTime",
                    "Year",
                    "YearMonth",
                    "Duration");

    /** Calls that turn their argument or receiver into a string without adding content. */
    private static final Set<String> CONVERSIONS = Set.of("toString", "valueOf");

    /** Calls that always produce a number. */
    private static final Set<String> NUMERIC_CALLS =
            Set.of(
                    "size",
                    "length",
                    "count",
                    "ordinal",
                    "intValue",
                    "longValue",
                    "hashCode",
                    "getTime",
                    "toEpochMilli",
                    "getYear",
                    "getMonthValue",
                    "getDayOfMonth");

    private static final Pattern CONSTANT_NAME = Pattern.compile("[A-Z][A-Z0-9_]*");

    private static final int MAX_DEPTH = 6;

    private final Map<String, TypeDeclaration<?>> projectTypes;
    private final Set<String> enumTypes;

    private SqlOperandSafety(Map<String, TypeDeclaration<?>> projectTypes, Set<String> enumTypes) {
        this.projectTypes = projectTypes;
        this.enumTypes = enumTypes;
    }

    static SqlOperandSafety of(JavaSources sources, Set<String> enumTypes) {
        Map<String, TypeDeclaration<?>> types = new LinkedHashMap<>();
        for (JavaSources.JavaFile file : sources.files()) {
            if (file.compilationUnit() != null) {
                file.compilationUnit()
                        .findAll(TypeDeclaration.class)
                        .forEach(type -> types.putIfAbsent(type.getNameAsString(), type));
            }
        }
        return new SqlOperandSafety(types, enumTypes);
    }

    /** Whether every non-literal part of the concatenation is provably not SQL text. */
    boolean onlySafeOperands(Expression concatenation) {
        return isSafe(concatenation, 0);
    }

    private boolean isSafe(Expression expression, int depth) {
        if (depth > MAX_DEPTH) {
            return false;
        }
        if (expression.isLiteralExpr()) {
            return true;
        }
        if (expression.isEnclosedExpr()) {
            return isSafe(expression.asEnclosedExpr().getInner(), depth + 1);
        }
        if (expression.isCastExpr()) {
            return isSafeType(expression.asCastExpr().getType())
                    || isSafe(expression.asCastExpr().getExpression(), depth + 1);
        }
        if (expression.isConditionalExpr()) {
            return isSafe(expression.asConditionalExpr().getThenExpr(), depth + 1)
                    && isSafe(expression.asConditionalExpr().getElseExpr(), depth + 1);
        }
        if (expression.isBinaryExpr()) {
            BinaryExpr binary = expression.asBinaryExpr();
            // Anything but + yields a number or a boolean.
            return binary.getOperator() != BinaryExpr.Operator.PLUS
                    || (isSafe(binary.getLeft(), depth + 1)
                            && isSafe(binary.getRight(), depth + 1));
        }
        if (expression.isUnaryExpr()) {
            // -x, !x, ~x and increments all yield a number or a boolean.
            return true;
        }
        if (expression.isNameExpr()) {
            return isSafeVariable(expression.asNameExpr(), depth);
        }
        if (expression.isFieldAccessExpr()) {
            String name = expression.asFieldAccessExpr().getNameAsString();
            if (CONSTANT_NAME.matcher(name).matches()) {
                return true;
            }
            return expression.asFieldAccessExpr().getScope().isThisExpr()
                    && fieldType(enclosingType(expression), name, 0)
                            .map(this::isSafeType)
                            .orElse(false);
        }
        if (expression.isMethodCallExpr()) {
            return isSafeCall(expression.asMethodCallExpr(), depth);
        }
        return false;
    }

    private boolean isSafeVariable(NameExpr name, int depth) {
        String variable = name.getNameAsString();
        if (CONSTANT_NAME.matcher(variable).matches()) {
            return true;
        }
        Optional<CallableDeclaration> callable = name.findAncestor(CallableDeclaration.class);
        if (callable.isPresent()) {
            for (Object parameter : callable.get().getParameters()) {
                Parameter declared = (Parameter) parameter;
                if (declared.getNameAsString().equals(variable)) {
                    return isSafeType(declared.getType());
                }
            }
            for (VariableDeclarator local : callable.get().findAll(VariableDeclarator.class)) {
                if (local.getNameAsString().equals(variable)) {
                    if (isSafeType(local.getType())) {
                        return true;
                    }
                    return local.getInitializer()
                            .map(initializer -> isSafe(initializer, depth + 1))
                            .orElse(false);
                }
            }
        }
        return fieldType(enclosingType(name), variable, 0).map(this::isSafeType).orElse(false);
    }

    private boolean isSafeCall(MethodCallExpr call, int depth) {
        String method = call.getNameAsString();
        if (NUMERIC_CALLS.contains(method) && call.getArguments().isEmpty()) {
            return true;
        }
        if (CONVERSIONS.contains(method)) {
            // value.toString(), String.valueOf(value), Integer.toString(value)
            if (call.getArguments().size() == 1) {
                return isSafe(call.getArgument(0), depth + 1);
            }
            return call.getScope().map(scope -> isSafe(scope, depth + 1)).orElse(false);
        }
        if (!call.getArguments().isEmpty()) {
            return false;
        }
        Optional<TypeDeclaration<?>> receiver = receiverType(call);
        if (receiver.isEmpty()) {
            return false;
        }
        if ("name".equals(method)) {
            return enumTypes.contains(receiver.get().getNameAsString());
        }
        // A getter (getId()) or record accessor (id()) returns the field of the same name.
        String property =
                method.startsWith("get") && method.length() > 3
                        ? Character.toLowerCase(method.charAt(3)) + method.substring(4)
                        : method.startsWith("is") && method.length() > 2
                                ? Character.toLowerCase(method.charAt(2)) + method.substring(3)
                                : method;
        return fieldType(Optional.of(receiver.get()), property, 0)
                .map(this::isSafeType)
                .orElse(false);
    }

    /** The project type of the receiver of {@code call}, when the source declares it. */
    private Optional<TypeDeclaration<?>> receiverType(MethodCallExpr call) {
        if (call.getScope().isEmpty() || call.getScope().get().isThisExpr()) {
            return enclosingType(call);
        }
        Expression scope = call.getScope().get();
        if (!scope.isNameExpr()) {
            return Optional.empty();
        }
        String variable = scope.asNameExpr().getNameAsString();
        Optional<CallableDeclaration> callable = call.findAncestor(CallableDeclaration.class);
        Type declared = null;
        if (callable.isPresent()) {
            for (Object parameter : callable.get().getParameters()) {
                if (((Parameter) parameter).getNameAsString().equals(variable)) {
                    declared = ((Parameter) parameter).getType();
                }
            }
            if (declared == null) {
                for (VariableDeclarator local : callable.get().findAll(VariableDeclarator.class)) {
                    if (local.getNameAsString().equals(variable)) {
                        declared = local.getType();
                        break;
                    }
                }
            }
        }
        if (declared == null) {
            Optional<TypeDeclaration<?>> owner = enclosingType(call);
            if (owner.isPresent()) {
                for (FieldDeclaration field : owner.get().getFields()) {
                    for (VariableDeclarator variableDeclarator : field.getVariables()) {
                        if (variableDeclarator.getNameAsString().equals(variable)) {
                            declared = variableDeclarator.getType();
                        }
                    }
                }
            }
        }
        if (declared == null || declared.isVarType()) {
            return Optional.empty();
        }
        return Optional.ofNullable(projectTypes.get(simpleTypeName(declared)));
    }

    /** The type of field {@code name} declared by {@code type} or one of its superclasses. */
    private Optional<Type> fieldType(Optional<TypeDeclaration<?>> type, String name, int depth) {
        if (type.isEmpty() || depth > MAX_DEPTH) {
            return Optional.empty();
        }
        if (type.get() instanceof RecordDeclaration record) {
            for (Parameter component : record.getParameters()) {
                if (component.getNameAsString().equals(name)) {
                    return Optional.of(component.getType());
                }
            }
        }
        for (FieldDeclaration field : type.get().getFields()) {
            for (VariableDeclarator variable : field.getVariables()) {
                if (variable.getNameAsString().equals(name)) {
                    return Optional.of(variable.getType());
                }
            }
        }
        if (type.get() instanceof ClassOrInterfaceDeclaration cls) {
            for (var superclass : cls.getExtendedTypes()) {
                Optional<Type> inherited =
                        fieldType(
                                Optional.ofNullable(projectTypes.get(superclass.getNameAsString())),
                                name,
                                depth + 1);
                if (inherited.isPresent()) {
                    return inherited;
                }
            }
        }
        return Optional.empty();
    }

    private boolean isSafeType(Type type) {
        if (type.isArrayType() || type.isVarType()) {
            return false;
        }
        String name = simpleTypeName(type);
        return SAFE_TYPES.contains(name) || enumTypes.contains(name);
    }

    private static String simpleTypeName(Type type) {
        String name = type.asString();
        int generic = name.indexOf('<');
        if (generic >= 0) {
            name = name.substring(0, generic);
        }
        int dot = name.lastIndexOf('.');
        return (dot >= 0 ? name.substring(dot + 1) : name).trim();
    }

    private static Optional<TypeDeclaration<?>> enclosingType(Node node) {
        return node.findAncestor(TypeDeclaration.class).map(type -> (TypeDeclaration<?>) type);
    }
}
