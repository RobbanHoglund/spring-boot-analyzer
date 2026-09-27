package com.robbanhoglund.springbootanalyzer.analyzer;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Shared checks for rules that inspect what a logging call writes. A log argument that only
 * reports whether a secret exists ({@code password != null}, {@code token.isBlank()}) or passes
 * it through a masking helper does not expose the value.
 */
final class LogArgumentHeuristics {

    private static final Set<String> PRESENCE_METHODS =
            Set.of(
                    "isBlank",
                    "isEmpty",
                    "isPresent",
                    "isNull",
                    "nonNull",
                    "hasText",
                    "hasLength",
                    "length",
                    "size",
                    "equals",
                    "equalsIgnoreCase",
                    "startsWith",
                    "endsWith",
                    "contains",
                    "matches",
                    "hashCode",
                    "isExpired");

    private static final List<String> MASKING_HINTS =
            List.of("mask", "redact", "obfuscat", "sanitiz", "fingerprint", "truncat");

    private LogArgumentHeuristics() {}

    /**
     * Whether a logging argument only reports a presence check, a comparison or a masked value
     * instead of the value itself.
     */
    static boolean isPresenceOrMaskedValue(Expression argument) {
        Expression expression = unwrap(argument);
        if (expression instanceof BinaryExpr binary) {
            return binary.getOperator() != BinaryExpr.Operator.PLUS;
        }
        if (expression instanceof UnaryExpr unary) {
            return unary.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT;
        }
        if (expression instanceof InstanceOfExpr || expression instanceof LiteralExpr) {
            return true;
        }
        if (expression instanceof ConditionalExpr conditional) {
            return isPresenceOrMaskedValue(conditional.getThenExpr())
                    && isPresenceOrMaskedValue(conditional.getElseExpr());
        }
        if (expression instanceof MethodCallExpr call) {
            String name = call.getNameAsString();
            if (PRESENCE_METHODS.contains(name)) {
                return true;
            }
            String lower = name.toLowerCase(Locale.ROOT);
            return MASKING_HINTS.stream().anyMatch(lower::contains);
        }
        return false;
    }

    private static Expression unwrap(Expression expression) {
        Expression current = expression;
        while (true) {
            if (current.isEnclosedExpr()) {
                current = current.asEnclosedExpr().getInner();
            } else if (current.isCastExpr()) {
                current = current.asCastExpr().getExpression();
            } else {
                return current;
            }
        }
    }
}
