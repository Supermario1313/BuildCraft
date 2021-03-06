/*
 * Copyright (c) 2017 SpaceToad and the BuildCraft team
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/
 */

package buildcraft.lib.inventory;

import javax.annotation.Nullable;

import net.minecraft.item.ItemStack;

import net.minecraftforge.items.IItemHandler;

import buildcraft.api.core.IStackFilter;

import buildcraft.lib.misc.StackUtil;

public final class ItemHandlerWrapper extends AbstractInvItemTransactor {
    private final IItemHandler wrapped;

    public ItemHandlerWrapper(IItemHandler handler) {
        this.wrapped = handler;
    }

    @Nullable
    @Override
    protected ItemStack insert(int slot, @Nullable ItemStack stack, boolean simulate) {
        return wrapped.insertItem(slot, stack, simulate);
    }

    @Override
    protected ItemStack extract(int slot, IStackFilter filter, int min, int max, boolean simulate) {
        if (min <= 0) min = 1;
        if (max < min) return StackUtil.EMPTY;
        ItemStack current = wrapped.getStackInSlot(slot);
        if (current == null || current.stackSize < min) return StackUtil.EMPTY;
        if (filter.matches(asValid(current))) {
            return wrapped.extractItem(slot, max, simulate);
        }
        return StackUtil.EMPTY;
    }

    @Override
    protected int getSlots() {
        return wrapped.getSlots();
    }

    @Override
    protected boolean isEmpty(int slot) {
        return wrapped.getStackInSlot(slot) == null;
    }
}
