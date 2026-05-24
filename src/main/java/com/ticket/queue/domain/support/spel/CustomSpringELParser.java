package com.ticket.queue.domain.support.spel;

import java.util.Objects;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

public final class CustomSpringELParser {

    private static final ExpressionParser PARSER = new SpelExpressionParser();

    private CustomSpringELParser() {
    }

    public static String parse(
            final String[] parameterNames,
            final Object[] args,
            final String expression
    ) {
        if (expression == null || expression.isBlank()) {
            return "";
        }

        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < parameterNames.length; i++) {
            context.setVariable(parameterNames[i], args[i]);
        }
        Object value = PARSER.parseExpression(expression).getValue(context);
        return Objects.toString(value, "");
    }
}