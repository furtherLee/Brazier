package com.github.kelemen.brazier.parsing;

import com.github.kelemen.brazier.PlayerProperty;
import com.github.kelemen.brazier.Priorities;
import com.github.kelemen.brazier.World;
import com.github.kelemen.brazier.actions.BasicFilters;
import com.github.kelemen.brazier.actions.UndoAction;
import com.github.kelemen.brazier.events.CompletableWorldEventAction;
import com.github.kelemen.brazier.events.CompletableWorldEventBasedActionDef;
import com.github.kelemen.brazier.events.CompleteWorldEventAction;
import com.github.kelemen.brazier.events.SimpleEventType;
import com.github.kelemen.brazier.events.WorldActionEventsRegistry;
import com.github.kelemen.brazier.events.WorldEventAction;
import com.github.kelemen.brazier.events.WorldEventActionDefs;
import com.github.kelemen.brazier.events.WorldEventBasedActionDef;
import com.github.kelemen.brazier.events.WorldEventFilter;
import com.github.kelemen.brazier.events.WorldEvents;
import com.github.kelemen.brazier.minions.Minion;
import com.google.gson.JsonPrimitive;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jtrim.utils.ExceptionHelper;

public final class EventNotificationParser<Self extends PlayerProperty> {
    private static final WorldEventAction<PlayerProperty, Object> DO_NOTHING
            = (world, self, arg) -> UndoAction.DO_NOTHING;

    private final Class<? extends Self> selfType;
    private final JsonDeserializer objectParser;
    private final WorldEventFilter<? super Self, Object> globalFilter;
    private final WorldEventAction<? super Self, Object> actionFinalizer;

    public EventNotificationParser(Class<? extends Self> selfType, JsonDeserializer objectParser) {
        this(selfType, objectParser, BasicFilters.ANY, DO_NOTHING);
    }

    public EventNotificationParser(
            Class<? extends Self> selfType,
            JsonDeserializer objectParser,
            WorldEventFilter<? super Self, Object> globalFilter,
            WorldEventAction<? super Self, Object> actionFinalizer) {
        ExceptionHelper.checkNotNullArgument(selfType, "selfType");
        ExceptionHelper.checkNotNullArgument(objectParser, "objectParser");
        ExceptionHelper.checkNotNullArgument(globalFilter, "globalFilter");
        ExceptionHelper.checkNotNullArgument(actionFinalizer, "actionFinalizer");

        this.selfType = selfType;
        this.objectParser = objectParser;
        this.globalFilter = globalFilter;
        this.actionFinalizer = actionFinalizer;
    }

    private TypeChecker eventFilterTypeChecker(Class<?> targetType) {
        return TypeCheckers.genericTypeChecker(WorldEventFilter.class, selfType, targetType);
    }

    private TypeChecker actionFilterTypeChecker(Class<?> targetType) {
        return TypeCheckers.genericTypeChecker(WorldEventAction.class, selfType, targetType);
    }

    public <T> WorldEventFilter<? super Self, ? super T> parseFilter(
            Class<T> targetType,
            JsonTree filterElement) throws ObjectParsingException {
        if (filterElement == null) {
            return globalFilter;
        }

        // This is not safe at all but there is nothing we can do about it.
        @SuppressWarnings("unchecked")
        WorldEventFilter<? super Self, ? super T> result = (WorldEventFilter<? super Self, ? super T>)objectParser
                .toJavaObject(filterElement, WorldEventFilter.class, eventFilterTypeChecker(targetType));
        if (globalFilter == BasicFilters.ANY) {
            return result;
        }

        return (World world, Self owner, T eventSource) -> {
            return globalFilter.applies(world, owner, eventSource)
                    && result.applies(world, owner, eventSource);
        };
    }

    // This is not safe at all but there is nothing we can do about it.
    @SuppressWarnings("unchecked")
    private <T> WorldEventAction<? super Self, ? super T> unsafeCastToEventAction(Object obj) {
        return (WorldEventAction<? super Self, ? super T>)obj;
    }

    public <T> WorldEventAction<? super Self, ? super T> parseAction(
            Class<T> targetType,
            JsonTree actionElement) throws ObjectParsingException {
        if (actionElement == null) {
            throw new ObjectParsingException("Missing action definition.");
        }

        WorldEventAction<?, ?> resultObj = objectParser.toJavaObject(
                actionElement,
                WorldEventAction.class,
                actionFilterTypeChecker(targetType));
        return toFinalizedAction(unsafeCastToEventAction(resultObj));
    }

    private <T> WorldEventAction<? super Self, ? super T> toFinalizedAction(
            WorldEventAction<? super Self, ? super T> action) {
        if (actionFinalizer == DO_NOTHING) {
            return action;
        }

        return (World world, Self self, T eventSource) -> {
            UndoAction actionUndo = action.alterWorld(world, self, eventSource);
            UndoAction finalizeUndo = actionFinalizer.alterWorld(world, self, eventSource);
            return () -> {
                finalizeUndo.undo();
                actionUndo.undo();
            };
        };
    }

    private int getPriority(JsonTree actionDefElement) {
        JsonTree priorityElement = actionDefElement.getChild("priority");
        if (priorityElement == null) {
            return Priorities.NORMAL_PRIORITY;
        }

        JsonPrimitive value = priorityElement.getAsJsonPrimitive();
        if (value.isString()) {
            switch (value.getAsString().toLowerCase(Locale.ROOT)) {
                case "lowest":
                    return Priorities.LOWEST_PRIORITY;
                case "low":
                    return Priorities.LOW_PRIORITY;
                case "normal":
                    return Priorities.NORMAL_PRIORITY;
                case "high":
                    return Priorities.HIGH_PRIORITY;
                case "highest":
                    return Priorities.HIGHEST_PRIORITY;
            }
        }
        return priorityElement.getAsInt();
    }

    private <T> WorldEventBasedActionDef<Self, T> tryParseActionDef(
            Class<T> targetType,
            JsonTree actionDefElement,
            Function<WorldEvents, ? extends WorldActionEventsRegistry<T>> actionEventListenersGetter,
            WorldEventFilter<? super Self, ? super T> globalFilter) throws ObjectParsingException {
        if (actionDefElement == null) {
            return null;
        }

        if (!actionDefElement.isJsonObject()) {
            throw new ObjectParsingException("WorldEventBasedActionDef requires a JsonObject.");
        }

        WorldEventFilter<? super Self, ? super T> baseFilter = parseFilter(targetType, actionDefElement.getChild("filter"));
        WorldEventAction<? super Self, ? super T> action = parseAction(targetType, actionDefElement.getChild("action"));

        WorldEventFilter<? super Self, ? super T> filter;
        if (globalFilter != null) {
            filter = (world, self, arg) -> baseFilter.applies(world, self, arg) && globalFilter.applies(world, self, arg);
        }
        else {
            filter = baseFilter;
        }

        JsonTree triggerOnceElement = actionDefElement.getChild("triggerOnce");
        boolean triggerOnce = triggerOnceElement != null ? triggerOnceElement.getAsBoolean() : false;

        JsonTree lazyFilterElement = actionDefElement.getChild("lazyFilter");
        boolean lazyFilter = lazyFilterElement != null ? lazyFilterElement.getAsBoolean() : false;

        int priority = getPriority(actionDefElement);

        return new WorldEventBasedActionDef<>(lazyFilter, triggerOnce, priority, actionEventListenersGetter, filter, action);
    }

    private <T> void parseActionDefs(
            Class<T> targetType,
            JsonTree actionDefsElement,
            Function<WorldEvents, ? extends WorldActionEventsRegistry<T>> actionEventListenersGetter,
            Consumer<WorldEventBasedActionDef<Self, T>> actionDefAdder,
            WorldEventFilter<? super Self, ? super T> globalFilter) throws ObjectParsingException {
        if (actionDefsElement == null) {
            return;
        }

        if (actionDefsElement.isJsonArray()) {
            for (JsonTree singleActionDefElement: actionDefsElement.getChildren()) {
                WorldEventBasedActionDef<Self, T> actionDef
                        = tryParseActionDef(targetType, singleActionDefElement, actionEventListenersGetter, globalFilter);
                if (actionDef != null) {
                    actionDefAdder.accept(actionDef);
                }
            }
        }
        else {
            WorldEventBasedActionDef<Self, T> actionDef
                    = tryParseActionDef(targetType, actionDefsElement, actionEventListenersGetter, globalFilter);
            if (actionDef != null) {
                actionDefAdder.accept(actionDef);
            }
        }
    }

    private <T> void parseSimpleActionDefs(
            JsonTree root,
            SimpleEventType eventType,
            WorldEventActionDefs.Builder<Self> result) throws ObjectParsingException {

        JsonTree actionDefsElement = root.getChild(eventType.getEventName());
        if (actionDefsElement == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Class<T> eventArgType = (Class<T>)eventType.getArgumentType();

        Function<WorldEvents, ? extends WorldActionEventsRegistry<T>> actionEventListenersGetter = (worldEvents) -> {
            return worldEvents.simpleListeners(eventType, eventArgType);
        };
        Consumer<WorldEventBasedActionDef<Self, T>> actionDefAdder = (actionDef) -> {
            result.addSimpleEventDef(eventType, actionDef);
        };

        parseActionDefs(eventArgType, actionDefsElement, actionEventListenersGetter, actionDefAdder, eventType.getGlobalFilter());
    }

    private void parseSingleOnSummonEvent(
            JsonTree actionDefElement,
            WorldEventActionDefs.Builder<Self> result) throws ObjectParsingException {

        if (actionDefElement == null) {
            return;
        }

        WorldEventFilter<? super Self, ? super Minion> filter = parseFilter(Minion.class, actionDefElement.getChild("filter"));
        WorldEventAction<? super Self, ? super Minion> action = parseAction(Minion.class, actionDefElement.getChild("action"));

        JsonTree triggerOnceElement = actionDefElement.getChild("triggerOnce");
        boolean triggerOnce = triggerOnceElement != null ? triggerOnceElement.getAsBoolean() : false;

        int priority = getPriority(actionDefElement);

        CompletableWorldEventAction<Self, Minion> eventDef = (World world, Self self, Minion eventSource) -> {
            if (self == eventSource) {
                return CompleteWorldEventAction.doNothing(UndoAction.DO_NOTHING);
            }

            if (filter.applies(world, self, eventSource)) {
                UndoAction alterWorld = action.alterWorld(world, self, eventSource);
                return CompleteWorldEventAction.doNothing(alterWorld);
            }

            return CompleteWorldEventAction.nothingToUndo((completeWorld, completeSelf, completeEventSource) -> {
                if (filter.applies(world, self, eventSource)) {
                    return action.alterWorld(world, self, eventSource);
                }
                else {
                    return UndoAction.DO_NOTHING;
                }
            });
        };

        result.addOnSummoningActionDef(new CompletableWorldEventBasedActionDef<>(triggerOnce, priority, WorldEvents::summoningListeners, eventDef));
    }

    private void parseOnSummonEvents(
            JsonTree actionDefsElement,
            WorldEventActionDefs.Builder<Self> result) throws ObjectParsingException {
        if (actionDefsElement == null) {
            return;
        }

        if (actionDefsElement.isJsonArray()) {
            for (JsonTree singleActionDefElement: actionDefsElement.getChildren()) {
                parseSingleOnSummonEvent(singleActionDefElement, result);
            }
        }
        else {
            parseSingleOnSummonEvent(actionDefsElement, result);
        }
    }

    public WorldEventActionDefs<Self> fromJson(JsonTree root) throws ObjectParsingException {
        WorldEventActionDefs.Builder<Self> result = new WorldEventActionDefs.Builder<>();

        for (SimpleEventType eventType: SimpleEventType.values()) {
            parseSimpleActionDefs(root, eventType, result);
        }

        parseOnSummonEvents(root.getChild("on-summon"), result);

        parseActionDefs(
                Minion.class,
                root.getChild("start-summoning"),
                WorldEvents::startSummoningListeners,
                (actionDef) -> { result.addOnSummoningActionDef(actionDef.toStartEventDef(WorldEvents::summoningListeners)); },
                null);

        parseActionDefs(
                Minion.class,
                root.getChild("done-summoning"),
                WorldEvents::doneSummoningListeners,
                (actionDef) -> { result.addOnSummoningActionDef(actionDef.toDoneEventDef(WorldEvents::summoningListeners)); },
                null);

        return result.create();
    }
}
