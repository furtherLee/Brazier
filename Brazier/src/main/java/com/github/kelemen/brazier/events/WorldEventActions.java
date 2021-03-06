package com.github.kelemen.brazier.events;

import com.github.kelemen.brazier.Damage;
import com.github.kelemen.brazier.DamageSource;
import com.github.kelemen.brazier.Player;
import com.github.kelemen.brazier.PlayerProperty;
import com.github.kelemen.brazier.TargetRef;
import com.github.kelemen.brazier.TargetableCharacter;
import com.github.kelemen.brazier.UndoableResult;
import com.github.kelemen.brazier.World;
import com.github.kelemen.brazier.actions.ActionUtils;
import com.github.kelemen.brazier.actions.AttackRequest;
import com.github.kelemen.brazier.actions.CardRef;
import com.github.kelemen.brazier.actions.EntitySelector;
import com.github.kelemen.brazier.actions.TargetedAction;
import com.github.kelemen.brazier.actions.TargetlessAction;
import com.github.kelemen.brazier.actions.UndoAction;
import com.github.kelemen.brazier.cards.Card;
import com.github.kelemen.brazier.minions.Minion;
import com.github.kelemen.brazier.minions.MinionProvider;
import com.github.kelemen.brazier.parsing.NamedArg;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.jtrim.utils.ExceptionHelper;

import static com.github.kelemen.brazier.actions.BasicFilters.validMisdirectTarget;

public final class WorldEventActions {
    public static final WorldEventAction<PlayerProperty, CardPlayEvent> PREVENT_CARD_PLAY = (world, self, eventSource) -> {
        return eventSource.vetoPlay();
    };

    public static final WorldEventAction<PlayerProperty, DamageRequest> PREVENT_PREPARED_DAMAGE = (world, self, eventSource) -> {
        return eventSource.vetoDamage();
    };

    public static WorldEventAction<DamageSource, DamageEvent> LIFE_STEAL_FOR_HERO = (world, self, event) -> {
        int damageDealt = event.getDamageDealt();
        if (damageDealt <= 0) {
            return UndoAction.DO_NOTHING;
        }

        return ActionUtils.damageCharacter(self, -damageDealt, self.getOwner().getHero());
    };

    public static final WorldEventAction<PlayerProperty, AttackRequest> MISS_TARGET_SOMETIMES
            = missTargetSometimes(1, 2);

     public static final WorldEventAction<PlayerProperty, AttackRequest> MISSDIRECT = (world, self, eventSource) -> {
         Predicate<TargetableCharacter> filter = validMisdirectTarget(eventSource);
         List<TargetableCharacter> targets = new ArrayList<>();
         ActionUtils.collectAliveTargets(world.getPlayer1(), targets, filter);
         ActionUtils.collectAliveTargets(world.getPlayer2(), targets, filter);

         TargetableCharacter selected = ActionUtils.pickRandom(world, targets);
         if (selected == null) {
             return UndoAction.DO_NOTHING;
         }

         return eventSource.replaceDefender(selected);
     };

    public static WorldEventAction<PlayerProperty, AttackRequest> missTargetSometimes(
            @NamedArg("missCount") int missCount,
            @NamedArg("attackCount") int attackCount) {

        return (World world, PlayerProperty self, AttackRequest eventSource) -> {
            TargetableCharacter defender = eventSource.getDefender();
            if (defender == null) {
                return UndoAction.DO_NOTHING;
            }

            int roll = world.getRandomProvider().roll(attackCount);
            if (roll >=  missCount) {
                return UndoAction.DO_NOTHING;
            }

            List<TargetableCharacter> targets = new ArrayList<>(Player.MAX_BOARD_SIZE);
            ActionUtils.collectAliveTargets(defender.getOwner(), targets, (target) -> target != defender);
            TargetableCharacter newTarget = ActionUtils.pickRandom(world, targets);
            if (newTarget == null) {
                return UndoAction.DO_NOTHING;
            }

            return eventSource.replaceDefender(newTarget);
        };
    }

    public static WorldEventAction<PlayerProperty, AttackRequest> summonNewTargetForAttack(
            @NamedArg("minion") MinionProvider minion) {
        return (world, self, eventSource) -> {
            Player targetPlayer = self.getOwner();
            if (targetPlayer.getBoard().isFull()) {
                return UndoAction.DO_NOTHING;
            }

            Minion summonedMinion = new Minion(targetPlayer, minion.getMinion());
            UndoAction summonUndo = targetPlayer.summonMinion(summonedMinion);
            UndoAction retargetUndo = eventSource.replaceDefender(summonedMinion);
            return () -> {
                retargetUndo.undo();
                summonUndo.undo();
            };
        };
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, TargetRef> forDamageTarget(
            @NamedArg("action") TargetedAction<? super Actor, ? super TargetableCharacter> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, TargetRef eventSource) -> {
            return action.alterWorld(world, self, eventSource.getTarget());
        };
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, AttackRequest> forAttacker(
            @NamedArg("action") TargetedAction<? super Actor, ? super TargetableCharacter> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, AttackRequest eventSource) -> {
            return action.alterWorld(world, self, eventSource.getAttacker());
        };
    }

    public static <Actor extends PlayerProperty, Target> WorldEventAction<Actor, Target> forEventArgTarget(
            @NamedArg("action") TargetedAction<? super Actor, ? super Target> action) {
        return action::alterWorld;
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, CardRef> forEventArgCardTarget(
            @NamedArg("action") TargetedAction<? super Actor, ? super Card> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, CardRef eventSource) -> {
            return action.alterWorld(world, self, eventSource.getCard());
        };
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, Object> forEventArgMinionTarget(
            @NamedArg("action") TargetedAction<? super Actor, ? super Minion> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, Object eventSource) -> {
            Minion minion = ActionUtils.tryGetMinion(eventSource);
            if (minion != null) {
                return action.alterWorld(world, self, minion);
            }
            else {
                return UndoAction.DO_NOTHING;
            }
        };
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, Object> withSelf(
            @NamedArg("action") TargetlessAction<? super Actor> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, Object eventSource) -> {
            return action.alterWorld(world, self);
        };
    }

    public static <Actor extends PlayerProperty> WorldEventAction<Actor, Object> withEventArgMinion(
            @NamedArg("action") TargetlessAction<? super Minion> action) {
        ExceptionHelper.checkNotNullArgument(action, "action");
        return (World world, Actor self, Object eventSource) -> {
            Minion minion = ActionUtils.tryGetMinion(eventSource);
            if (minion != null) {
                return action.alterWorld(world, minion);
            }
            else {
                return UndoAction.DO_NOTHING;
            }
        };
    }

    public static WorldEventAction<PlayerProperty, CardPlayEvent> summonNewTargetForCardPlay(
            @NamedArg("minion") MinionProvider minion) {
        ExceptionHelper.checkNotNullArgument(minion, "minion");
        return (world, self, eventSource) -> {
            Player targetPlayer = self.getOwner();
            if (targetPlayer.getBoard().isFull()) {
                return UndoAction.DO_NOTHING;
            }

            Minion summonedMinion = new Minion(targetPlayer, minion.getMinion());
            UndoAction summonUndo = targetPlayer.summonMinion(summonedMinion);
            UndoAction retargetUndo = eventSource.replaceTarget(summonedMinion);
            return () -> {
                retargetUndo.undo();
                summonUndo.undo();
            };
        };
    }

    public static <Actor extends DamageSource> WorldEventAction<Actor, DamageEvent> reflectDamage(
            @NamedArg("selector") EntitySelector<? super Actor, ? extends TargetableCharacter> selector) {
        ExceptionHelper.checkNotNullArgument(selector, "selector");
        return (world, self, eventSource) -> {
            int damage = eventSource.getDamageDealt();
            UndoableResult<Damage> damageRef = self.createDamage(damage);
            UndoAction damageUndo = selector.forEach(world, self, (target) -> target.damage(damageRef.getResult()));
            return () -> {
                damageUndo.undo();
                damageRef.undo();
            };
        };
    }
}
