package com.ticket.queue.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

public class AuthenticatedMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(final MethodParameter parameter) {
        return AuthenticatedMember.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(
            final MethodParameter parameter,
            final ModelAndViewContainer mavContainer,
            final NativeWebRequest webRequest,
            final WebDataBinderFactory binderFactory
    ) {
        Object member = webRequest.getAttribute(
                AccessTokenAuthenticationFilter.MEMBER_ATTRIBUTE,
                RequestAttributes.SCOPE_REQUEST
        );
        if (member instanceof AuthenticatedMember authenticatedMember) {
            return authenticatedMember;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authenticated member is required");
    }
}
