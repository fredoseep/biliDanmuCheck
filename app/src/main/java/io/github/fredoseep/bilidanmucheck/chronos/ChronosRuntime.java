package io.github.fredoseep.bilidanmucheck.chronos;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Resolve callback interfaces from actual members instead of obfuscated nested class names. */
final class ChronosRuntime {
    private ChronosRuntime() {}

    static boolean callback(Class<?> type, Class<?> argument) {
        if (!type.isInterface()) return false;
        int count = 0;
        for (Method method : type.getMethods()) {
            if (!Modifier.isAbstract(method.getModifiers()) || Modifier.isStatic(method.getModifiers())) continue;
            Class<?>[] args = method.getParameterTypes();
            if (method.getReturnType() != void.class || args.length != 1 || args[0] != argument) return false;
            count++;
        }
        return count == 1;
    }

    static int findLoggerIndex(Class<?>[] types) {
        int result = -1;
        for (int index = 0; index < types.length; index++) {
            if (!callback(types[index], String.class)) continue;
            if (result >= 0) throw new IllegalStateException("Multiple logger arguments");
            result = index;
        }
        return result;
    }

    static Method findRunPackage(Class<?> engine, Class<?> pkg) {
        List<Method> result = new ArrayList<>();
        for (Method method : engine.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && !method.isBridge()
                    && method.getReturnType() == void.class && types.length == 3
                    && types[0] == pkg && types[1] == String.class && callback(types[2], boolean.class)) {
                result.add(method);
            }
        }
        return unique("runPackage", result);
    }

    static Method findSendMessage(Class<?> engine) {
        List<Method> result = new ArrayList<>();
        for (Method method : engine.getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!Modifier.isStatic(method.getModifiers()) && !method.isBridge()
                    && method.getReturnType() == void.class && types.length == 2
                    && types[0] == byte[].class && callback(types[1], byte[].class)) {
                result.add(method);
            }
        }
        return unique("sendMessageAsync", result);
    }

    private static Method unique(String name, List<Method> matches) {
        if (matches.size() != 1) throw new IllegalStateException(name + ": expected 1 member, actual=" + matches);
        Method method = matches.get(0);
        method.setAccessible(true);
        return method;
    }
}
