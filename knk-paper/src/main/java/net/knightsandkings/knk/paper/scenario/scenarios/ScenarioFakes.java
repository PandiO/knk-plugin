package net.knightsandkings.knk.paper.scenario.scenarios;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * In-memory data-access ports for scenarios ("Fake data instead of the API" in knk-workspace
 * {@code docs/guides/developer/plugin-scenarios.md} § 4.2): a port interface answered by one function per method the
 * feature under test calls, given to a service the scenario builds itself. Any other method fails loudly (a failed
 * future for a method returning {@link CompletableFuture}, else an exception), so a scenario never silently depends
 * on an answer it didn't define.
 */
public final class ScenarioFakes {

    private ScenarioFakes() {
    }

    /**
     * @param answers method name to answer; the function gets the call's arguments (an empty array for none)
     */
    @SuppressWarnings("unchecked")
    public static <T> T port(Class<T> type, Map<String, Function<Object[], Object>> answers) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "scenario fake " + type.getSimpleName();
                };
            }
            Function<Object[], Object> answer = answers.get(method.getName());
            if (answer == null) {
                UnsupportedOperationException missing = new UnsupportedOperationException(
                    "the scenario fake " + type.getSimpleName() + " has no answer for " + method.getName());
                if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
                    return CompletableFuture.failedFuture(missing);
                }
                throw missing;
            }
            return answer.apply(args == null ? new Object[0] : args);
        });
    }
}
