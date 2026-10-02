package dev.chika.builder.support;

import baritone.api.command.argument.IArgConsumer;
import baritone.api.command.argument.ICommandArgument;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/**
 * An {@link IArgConsumer} for command tests, backed by a dynamic proxy.
 *
 * <p>Why not the engine's own {@code ArgConsumer}: its only usable constructor
 * takes the engine's concrete {@code CommandManager}, which would drag the whole
 * client command manager into a unit test. The behaviour a command actually
 * depends on is small and well defined - {@code has(n)} means "at least n" and
 * counts the argument being typed, {@code hasExactly(n)} means exactly n, and
 * {@code peek(0)} is the first remaining argument - so this reproduces precisely
 * that and nothing else. Any method outside that set throws, so a test can never
 * quietly pass because a call did nothing.
 *
 * <p>Input is split the way the client splits it: on whitespace, keeping a
 * trailing empty argument when the text ends in a space (or is empty), because
 * that empty argument <em>is</em> the slot being tab-completed.
 */
public final class FakeArgs {

    private final List<String> args;
    private final List<String> consumed = new ArrayList<>();
    private IArgConsumer self;

    private FakeArgs(List<String> args) {
        this.args = new ArrayList<>(args);
    }

    /** Arguments for the text after the command label, e.g. {@code " creative "}. */
    public static FakeArgs from(String rest) {
        return new FakeArgs(parse(rest));
    }

    /** Convenience for completed input: {@code FakeArgs.of("creative", "true")}. */
    public static FakeArgs of(String... arguments) {
        return new FakeArgs(List.of(arguments));
    }

    /** The consumer to hand to the command under test. */
    public IArgConsumer consumer() {
        IArgConsumer proxy = (IArgConsumer) Proxy.newProxyInstance(
                IArgConsumer.class.getClassLoader(),
                new Class<?>[] {IArgConsumer.class},
                (instance, method, params) -> invoke(method.getName(), params));
        this.self = proxy;
        return proxy;
    }

    /** Every value consumed so far, oldest first, for assertions. */
    public List<String> consumedValues() {
        return List.copyOf(this.consumed);
    }

    private static List<String> parse(String rest) {
        List<String> parsed = new ArrayList<>();
        String text = rest == null ? "" : rest;

        // Mirrors the client's expand(prefix, preserveEmptyLast = true).
        boolean endsWithSpace = text.isEmpty()
                || Character.isWhitespace(text.charAt(text.length() - 1));
        String trimmed = text.trim();

        if (!trimmed.isEmpty()) {
            parsed.addAll(List.of(trimmed.split("\\s+")));
        }

        if (endsWithSpace) {
            parsed.add("");
        }

        return parsed;
    }

    private Object invoke(String name, Object[] params) {
        int remaining = this.args.size();

        switch (name) {
            case "has":
                return remaining >= (int) params[0];
            case "hasAny":
                return remaining >= 1;
            case "hasAtMost":
                return remaining <= (int) params[0];
            case "hasAtMostOne":
                return remaining <= 1;
            case "hasExactly":
                return remaining == (int) params[0];
            case "hasExactlyOne":
                return remaining == 1;
            case "peek":
                return argument(index(params), this.args.get(index(params)));
            case "peekString":
                return this.args.get(index(params));
            case "get":
                return consume();
            case "getString":
                return consume().getValue();
            case "hasConsumed":
                return !this.consumed.isEmpty();
            case "consumed":
                return argument(-1, this.consumed.isEmpty()
                        ? "" : this.consumed.get(this.consumed.size() - 1));
            case "consumedString":
                return this.consumed.isEmpty() ? "" : this.consumed.get(this.consumed.size() - 1);
            case "rawRest":
                return remaining == 0 ? "" : this.args.get(0);
            case "copy":
                return new FakeArgs(this.args).consumer();
            case "requireMin":
                require(remaining >= (int) params[0], "at least " + params[0]);
                return null;
            case "requireMax":
                require(remaining <= (int) params[0], "at most " + params[0]);
                return null;
            case "requireExactly":
                require(remaining == (int) params[0], "exactly " + params[0]);
                return null;
            case "toString":
                return "FakeArgs" + this.args;
            case "hashCode":
                return System.identityHashCode(this);
            case "equals":
                return params[0] == this.self;
            default:
                throw new UnsupportedOperationException(
                        "FakeArgs does not implement " + name + " - the command should not need it");
        }
    }

    private static int index(Object[] params) {
        return params.length == 0 ? 0 : (int) params[0];
    }

    private static void require(boolean condition, String expected) {
        if (!condition) {
            throw new IllegalStateException("expected " + expected + " arguments");
        }
    }

    private ICommandArgument consume() {
        String value = this.args.remove(0);
        this.consumed.add(value);
        return argument(this.consumed.size() - 1, value);
    }

    private static ICommandArgument argument(int index, String value) {
        return (ICommandArgument) Proxy.newProxyInstance(
                ICommandArgument.class.getClassLoader(),
                new Class<?>[] {ICommandArgument.class},
                (proxy, method, params) -> {
                    switch (method.getName()) {
                        case "getIndex":
                            return index;
                        case "getValue":
                        case "getRawRest":
                            return value;
                        case "toString":
                            return value;
                        case "hashCode":
                            return value.hashCode();
                        case "equals":
                            return proxy == params[0];
                        default:
                            throw new UnsupportedOperationException(
                                    "FakeArgs argument does not implement " + method.getName());
                    }
                });
    }
}