package org.jitsi.videobridge.util;

import java.util.*;
import java.util.function.*;
import java.util.stream.*;

/**
 * A Collector which will return 'null' if the resulting list is empty
 *
 * The original use-case for this is for parsing configuration values:
 * 'null' is used to denote that a property wasn't found, so sometimes
 * we want to treat an empty list (not finding any results) as the lack
 * of a property entirely so that we'll fall through and check another
 * config source for a value for that property.
 *
 * @param <T> the type being collected
 */
public class EmptyListToNullCollector<T> implements Collector<T, List<T>, List<T>>
{
    @Override
    public Supplier<List<T>> supplier()
    {
        return ArrayList::new;
    }

    @Override
    public BiConsumer<List<T>, T> accumulator()
    {
        return List::add;
    }

    @Override
    public Function<List<T>, List<T>> finisher()
    {
        return (list) -> list.isEmpty() ? null : list;
    }

    @Override
    public BinaryOperator<List<T>> combiner()
    {
        return (a, b) -> {
            a.addAll(b);
            return a;
        };
    }

    @Override
    public Set<Characteristics> characteristics()
    {
        return Collections.emptySet();
    }
}
