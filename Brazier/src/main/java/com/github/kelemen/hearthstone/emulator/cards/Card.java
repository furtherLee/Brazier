package com.github.kelemen.hearthstone.emulator.cards;

import com.github.kelemen.brazier.ZoneRef;
import com.github.kelemen.brazier.ZonedEntity;
import com.github.kelemen.hearthstone.emulator.Damage;
import com.github.kelemen.hearthstone.emulator.DamageSource;
import com.github.kelemen.hearthstone.emulator.Keyword;
import com.github.kelemen.hearthstone.emulator.LabeledEntity;
import com.github.kelemen.hearthstone.emulator.Player;
import com.github.kelemen.hearthstone.emulator.PlayerProperty;
import com.github.kelemen.hearthstone.emulator.UndoableResult;
import com.github.kelemen.hearthstone.emulator.abilities.AuraAwareIntProperty;
import com.github.kelemen.hearthstone.emulator.actions.CardRef;
import com.github.kelemen.hearthstone.emulator.actions.ManaCostAdjuster;
import com.github.kelemen.hearthstone.emulator.actions.UndoAction;
import com.github.kelemen.hearthstone.emulator.minions.Minion;
import com.github.kelemen.hearthstone.emulator.minions.MinionDescr;
import java.util.List;
import java.util.Set;
import org.jtrim.utils.ExceptionHelper;

public final class Card implements PlayerProperty, LabeledEntity, CardRef, DamageSource, ZonedEntity {
    private final Player owner;
    private final CardDescr cardDescr;
    private final Minion minion;

    private final AuraAwareIntProperty manaCost;
    private final ZoneRef zoneRef;

    public Card(Player owner, CardDescr cardDescr) {
        ExceptionHelper.checkNotNullArgument(cardDescr, "cardDescr");

        this.owner = owner;
        this.cardDescr = cardDescr;
        this.zoneRef = new ZoneRef();
        this.manaCost = new AuraAwareIntProperty(cardDescr.getManaCost());
        this.manaCost.addBuff(this::adjustManaCost);

        MinionDescr minionDescr = cardDescr.getMinion();
        this.minion = minionDescr != null ? new Minion(owner, cardDescr.getMinion()) : null;
    }

    @Override
    public ZoneRef getZoneRef() {
        return zoneRef;
    }

    private int adjustManaCost(int baseCost) {
        List<ManaCostAdjuster> costAdjusters = cardDescr.getManaCostAdjusters();
        int result = baseCost;
        if (!costAdjusters.isEmpty()) {
            for (ManaCostAdjuster adjuster: costAdjusters) {
                result = adjuster.adjustCost(this, result);
            }
        }
        return result;
    }

    public Minion getMinion() {
        return minion;
    }

    @Override
    public Card getCard() {
        return this;
    }

    @Override
    public Player getOwner() {
        return owner;
    }

    @Override
    public Set<Keyword> getKeywords() {
        return cardDescr.getKeywords();
    }

    @Override
    public UndoableResult<Damage> createDamage(int damage) {
        if (minion != null) {
            return minion.createDamage(damage);
        }

        if (cardDescr.getCardType() == CardType.SPELL) {
            return new UndoableResult<>(getOwner().getSpellDamage(damage));
        }

        return new UndoableResult<>(new Damage(this, damage));
    }

    public CardDescr getCardDescr() {
        return cardDescr;
    }

    public AuraAwareIntProperty getRawManaCost() {
        // Note that the final value might be negative.
        return manaCost;
    }

    public UndoAction decreaseManaCost(int amount) {
        return manaCost.addBuff(-amount);
    }

    public int getActiveManaCost() {
        return Math.max(0, manaCost.getValue());
    }

    public boolean isMinionCard() {
        return cardDescr.getMinion() != null;
    }

    @Override
    public String toString() {
        return cardDescr.toString();
    }
}
