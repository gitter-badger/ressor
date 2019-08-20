package xyz.ressor.service.proxy;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import xyz.ressor.commons.utils.Exceptions;
import xyz.ressor.service.RessorService;

import static net.bytebuddy.dynamic.loading.ClassLoadingStrategy.Default.INJECTION;
import static net.bytebuddy.implementation.MethodCall.call;
import static net.bytebuddy.implementation.MethodDelegation.to;
import static net.bytebuddy.implementation.MethodDelegation.toMethodReturnOf;
import static net.bytebuddy.matcher.ElementMatchers.*;
import static xyz.ressor.commons.utils.CollectionUtils.isNotEmpty;
import static xyz.ressor.commons.utils.RessorUtils.firstNonNull;

public class ServiceProxyBuilder {
    private final ByteBuddy byteBuddy = new ByteBuddy();

    public <T> T buildProxy(ProxyContext<T> context) {
        var serviceProxy = new RessorServiceImpl<>(context.getType(), context.getFactory(), context.getTranslator());
        var b = byteBuddy.subclass(context.getType());
        if (isNotEmpty(context.getExtensions())) {
            for (var ext : context.getExtensions()) {
                b = (DynamicType.Builder<? extends T>) ext.interceptProxy(b, context.getType());
            }
        }
        var m = b.implement(RessorService.class)
                .defineMethod("getRessorUnderlying", context.getType(), Visibility.PUBLIC)
                .intercept(call(serviceProxy::instance))
                .method(isDeclaredBy(context.getType()))
                .intercept(toMethodReturnOf("getRessorUnderlying"))
                .method(isDeclaredBy(RessorService.class))
                .intercept(to(serviceProxy));

        try {
            return m.make()
                    .load(firstNonNull(context.getClassLoader(), getClass().getClassLoader()), INJECTION)
                    .getLoaded().getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            throw Exceptions.wrap(t);
        }
    }

}
