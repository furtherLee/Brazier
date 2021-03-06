package com.github.kelemen.brazier.events;

import com.github.kelemen.brazier.Damage;
import com.github.kelemen.brazier.Player;
import com.github.kelemen.brazier.PlayerProperty;
import com.github.kelemen.brazier.TargetRef;
import com.github.kelemen.brazier.TargetableCharacter;
import com.github.kelemen.brazier.actions.UndoAction;
import org.jtrim.utils.ExceptionHelper;

public final class DamageRequest implements TargetRef, PlayerProperty {
    private final Damage damage;
    private final TargetableCharacter target;
    private boolean vetoDamage;

    public DamageRequest(Damage damage, TargetableCharacter target) {
        ExceptionHelper.checkNotNullArgument(damage, "damage");
        ExceptionHelper.checkNotNullArgument(target, "target");

        this.damage = damage;
        this.target = target;
        this.vetoDamage = false;
    }

    @Override
    public Player getOwner() {
        return damage.getSource().getOwner();
    }

    @Override
    public TargetableCharacter getTarget() {
        return target;
    }

    public Damage getDamage() {
        return damage;
    }

    public UndoAction vetoDamage() {
        if (vetoDamage) {
            return UndoAction.DO_NOTHING;
        }

        vetoDamage = true;
        return () -> vetoDamage = false;
    }
}
