package com.github.kelemen.hearthstone.emulator.actions2;

import com.github.kelemen.hearthstone.emulator.Keyword;
import com.github.kelemen.hearthstone.emulator.LabeledEntity;
import com.github.kelemen.hearthstone.emulator.World;
import com.github.kelemen.hearthstone.emulator.actions.ActionUtils;
import com.github.kelemen.hearthstone.emulator.parsing.NamedArg;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jtrim.utils.ExceptionHelper;

public final class EntityFilters {
    public static <Entity> EntityFilter<Entity> empty() {
        return (world, entities) -> Stream.empty();
    }

    public static <Entity> EntityFilter<Entity> random() {
        return (World world, Stream<? extends Entity> entities) -> {
            List<Entity> elements = entities.collect(Collectors.<Entity>toList());
            Entity result = ActionUtils.pickRandom(world, elements);
            if (result == null) {
                return Stream.empty();
            }
            else {
                return Stream.of(result);
            }
        };
    }

    public static <Entity> EntityFilter<Entity> random(@NamedArg("count") int count) {
        ExceptionHelper.checkArgumentInRange(count, 0, Integer.MAX_VALUE, "count");
        if (count == 0) {
            return empty();
        }
        if (count == 1) {
            return random();
        }

        return (World world, Stream<? extends Entity> entities) -> {
            List<Entity> elements = entities.collect(Collectors.<Entity>toList());
            return ActionUtils.pickMultipleRandom(world, count, elements).stream();
        };
    }

    public static <Entity extends LabeledEntity> EntityFilter<Entity> withKeywords(
            @NamedArg("keywords") Keyword... keywords) {
        return withPredicate(ActionUtils.includedKeywordsFilter(keywords));
    }

    public static <Entity extends LabeledEntity> EntityFilter<Entity> withoutKeywords(
            @NamedArg("keywords") Keyword... keywords) {
        return withPredicate(ActionUtils.excludedKeywordsFilter(keywords));
    }

    public static <Entity> EntityFilter<Entity> withPredicate(Predicate<? super Entity> filter) {
        ExceptionHelper.checkNotNullArgument(filter, "filter");

        return (World world, Stream<? extends Entity> entities) -> {
            return entities.filter(filter);
        };
    }

    private EntityFilters() {
        throw new AssertionError();
    }
}