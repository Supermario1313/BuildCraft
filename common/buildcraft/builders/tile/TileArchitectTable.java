/*
 * Copyright (c) 2016 SpaceToad and the BuildCraft team
 * 
 * This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0. If a copy of the MPL was not
 * distributed with this file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package buildcraft.builders.tile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.annotation.Nullable;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ITickable;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;

import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.IAreaProvider;
import buildcraft.api.enums.EnumSnapshotType;
import buildcraft.api.schematics.ISchematicBlock;
import buildcraft.api.schematics.ISchematicEntity;
import buildcraft.api.schematics.SchematicBlockContext;
import buildcraft.api.schematics.SchematicEntityContext;
import buildcraft.api.tiles.IDebuggable;

import buildcraft.lib.delta.DeltaInt;
import buildcraft.lib.delta.DeltaManager;
import buildcraft.lib.misc.BoundingBoxUtil;
import buildcraft.lib.misc.MessageUtil;
import buildcraft.lib.misc.NBTUtilBC;
import buildcraft.lib.misc.data.Box;
import buildcraft.lib.misc.data.BoxIterator;
import buildcraft.lib.misc.data.EnumAxisOrder;
import buildcraft.lib.misc.data.IdAllocator;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.tile.TileBC_Neptune;
import buildcraft.lib.tile.item.ItemHandlerManager.EnumAccess;
import buildcraft.lib.tile.item.ItemHandlerSimple;

import buildcraft.builders.BCBuildersBlocks;
import buildcraft.builders.BCBuildersItems;
import buildcraft.builders.block.BlockArchitectTable;
import buildcraft.builders.client.ClientArchitectTables;
import buildcraft.builders.item.ItemSnapshot;
import buildcraft.builders.snapshot.Blueprint;
import buildcraft.builders.snapshot.GlobalSavedDataSnapshots;
import buildcraft.builders.snapshot.SchematicBlockManager;
import buildcraft.builders.snapshot.SchematicEntityManager;
import buildcraft.builders.snapshot.Snapshot;
import buildcraft.builders.snapshot.Snapshot.Header;
import buildcraft.builders.snapshot.Template;
import buildcraft.core.marker.volume.Lock;
import buildcraft.core.marker.volume.VolumeBox;
import buildcraft.core.marker.volume.WorldSavedDataVolumeBoxes;

public class TileArchitectTable extends TileBC_Neptune implements ITickable, IDebuggable {
    public static final IdAllocator IDS = TileBC_Neptune.IDS.makeChild("architect");
    @SuppressWarnings("WeakerAccess")
    public static final int NET_BOX = IDS.allocId("BOX");
    @SuppressWarnings("WeakerAccess")
    public static final int NET_SCAN = IDS.allocId("SCAN");

    public final ItemHandlerSimple invSnapshotIn = itemManager.addInvHandler(
        "in",
        1,
        (slot, stack) -> stack.getItem() instanceof ItemSnapshot,
        EnumAccess.INSERT,
        EnumPipePart.VALUES
    );
    public final ItemHandlerSimple invSnapshotOut = itemManager.addInvHandler(
        "out",
        1,
        EnumAccess.EXTRACT,
        EnumPipePart.VALUES
    );

    private EnumSnapshotType snapshotType = EnumSnapshotType.BLUEPRINT;
    public final Box box = new Box();
    public boolean markerBox = false;
    private BitSet templateScannedBlocks;
    private final List<ISchematicBlock> blueprintScannedPalette = new ArrayList<>();
    private int[] blueprintScannedData;
    private final List<ISchematicEntity> blueprintScannedEntities = new ArrayList<>();
    private BoxIterator boxIterator;
    private boolean isValid = false;
    private boolean scanning = false;
    public String name = "<unnamed>";
    public final DeltaInt deltaProgress = deltaManager.addDelta(
        "progress",
        DeltaManager.EnumNetworkVisibility.GUI_ONLY
    );

    @Override
    public IdAllocator getIdAllocator() {
        return IDS;
    }

    @Override
    public void onPlacedBy(EntityLivingBase placer, ItemStack stack) {
        super.onPlacedBy(placer, stack);
        if (placer.worldObj.isRemote) {
            return;
        }
        WorldSavedDataVolumeBoxes volumeBoxes = WorldSavedDataVolumeBoxes.get(worldObj);
        IBlockState blockState = worldObj.getBlockState(pos);
        BlockPos offsetPos = pos.offset(blockState.getValue(BlockArchitectTable.PROP_FACING).getOpposite());
        VolumeBox volumeBox = volumeBoxes.getVolumeBoxAt(offsetPos);
        TileEntity tile = worldObj.getTileEntity(offsetPos);
        if (volumeBox != null) {
            box.reset();
            box.setMin(volumeBox.box.min());
            box.setMax(volumeBox.box.max());
            isValid = true;
            volumeBox.locks.add(
                new Lock(
                    new Lock.Cause.CauseBlock(pos, blockState.getBlock()),
                    new Lock.Target.TargetResize(),
                    new Lock.Target.TargetUsedByMachine(
                        Lock.Target.TargetUsedByMachine.EnumType.STRIPES_READ
                    )
                )
            );
            volumeBoxes.markDirty();
            sendNetworkUpdate(NET_BOX);
        } else if (tile instanceof IAreaProvider) {
            IAreaProvider provider = (IAreaProvider) tile;
            box.reset();
            box.setMin(provider.min());
            box.setMax(provider.max());
            markerBox = true;
            isValid = true;
            provider.removeFromWorld();
        } else {
            isValid = false;
            IBlockState state = worldObj.getBlockState(pos);
            state = state.withProperty(BlockArchitectTable.PROP_VALID, Boolean.FALSE);
            worldObj.setBlockState(pos, state);
        }
    }

    @Override
    public void update() {
        deltaManager.tick();

        if (worldObj.isRemote) {
            if (box.isInitialized()) {
                ClientArchitectTables.BOXES.put(box.getBoundingBox(), ClientArchitectTables.START_BOX_VALUE);
            }
            return;
        }

        if (!(invSnapshotIn.getStackInSlot(0) == null) && invSnapshotOut.getStackInSlot(0) == null && isValid) {
            if (!scanning) {
                snapshotType = ItemSnapshot.EnumItemSnapshotType.getFromStack(
                    invSnapshotIn.getStackInSlot(0)
                ).snapshotType;
                int size = box.size().getX() * box.size().getY() * box.size().getZ();
                size /= snapshotType.maxPerTick;
                deltaProgress.addDelta(0, size, 1);
                deltaProgress.addDelta(size, size + 10, -1);
                scanning = true;
            }
        } else {
            scanning = false;
        }

        if (scanning) {
            scanMultipleBlocks();
            if (!scanning) {
                if (snapshotType == EnumSnapshotType.BLUEPRINT) {
                    scanEntities();
                }
                finishScanning();
            }
        }
    }

    private void scanMultipleBlocks() {
        for (int i = snapshotType.maxPerTick; i > 0; i--) {
            scanSingleBlock();
            if (!scanning) {
                break;
            }
        }
    }

    private void scanSingleBlock() {
        BlockPos size = box.size();
        if (templateScannedBlocks == null || blueprintScannedData == null) {
            boxIterator = new BoxIterator(box, EnumAxisOrder.XZY.getMinToMaxOrder(), true);
            templateScannedBlocks = new BitSet(Snapshot.getDataSize(size));
            blueprintScannedData = new int[Snapshot.getDataSize(size)];
        }

        // Read from world
        BlockPos worldScanPos = boxIterator.getCurrent();
        BlockPos schematicPos = worldScanPos.subtract(box.min());
        if (snapshotType == EnumSnapshotType.TEMPLATE) {
            templateScannedBlocks.set(Snapshot.posToIndex(box.size(), schematicPos), !worldObj.isAirBlock(worldScanPos));
        }
        if (snapshotType == EnumSnapshotType.BLUEPRINT) {
            ISchematicBlock schematicBlock = readSchematicBlock(worldScanPos);
            int index = blueprintScannedPalette.indexOf(schematicBlock);
            if (index == -1) {
                index = blueprintScannedPalette.size();
                blueprintScannedPalette.add(schematicBlock);
            }
            blueprintScannedData[Snapshot.posToIndex(box.size(), schematicPos)] = index;
        }

        createAndSendMessage(NET_SCAN, buffer -> MessageUtil.writeBlockPos(buffer, worldScanPos));

        sendNetworkUpdate(NET_RENDER_DATA);

        // Move scanPos along
        boxIterator.advance();

        if (boxIterator.hasFinished()) {
            scanning = false;
            boxIterator = null;
        }
    }

    private ISchematicBlock readSchematicBlock(BlockPos worldScanPos) {
        return SchematicBlockManager.getSchematicBlock(new SchematicBlockContext(
            worldObj,
            box.min(),
            worldScanPos,
            worldObj.getBlockState(worldScanPos),
            worldObj.getBlockState(worldScanPos).getBlock()
        ));
    }

    private void scanEntities() {
        worldObj.getEntitiesWithinAABB(Entity.class, box.getBoundingBox()).stream()
            .map(entity ->
                SchematicEntityManager.getSchematicEntity(new SchematicEntityContext(
                    worldObj,
                    box.min(),
                    entity
                ))
            )
            .filter(Objects::nonNull)
            .forEach(blueprintScannedEntities::add);
    }

    private void finishScanning() {
        IBlockState thisState = getCurrentStateForBlock(BCBuildersBlocks.architect);
        if (thisState == null) {
            return;
        }

        EnumFacing facing = thisState.getValue(BlockArchitectTable.PROP_FACING);
        Snapshot snapshot = Snapshot.create(snapshotType);
        snapshot.size = box.size();
        snapshot.facing = facing;
        snapshot.offset = box.min().subtract(pos.offset(facing.getOpposite()));
        if (snapshot instanceof Template) {
            ((Template) snapshot).data = templateScannedBlocks;
        }
        if (snapshot instanceof Blueprint) {
            ((Blueprint) snapshot).palette.addAll(blueprintScannedPalette);
            ((Blueprint) snapshot).data = blueprintScannedData;
            ((Blueprint) snapshot).entities.addAll(blueprintScannedEntities);
        }
        snapshot.computeKey();
        GlobalSavedDataSnapshots.get(worldObj).addSnapshot(snapshot);
        ItemStack stackIn = invSnapshotIn.getStackInSlot(0);
        stackIn.stackSize = stackIn.stackSize - 1;
        if (stackIn.stackSize == 0) {
            stackIn = null;
        }
        invSnapshotIn.setStackInSlot(0, stackIn);
        invSnapshotOut.setStackInSlot(
            0,
            BCBuildersItems.snapshot.getUsed(
                snapshotType,
                new Header(
                    snapshot.key,
                    getOwner().getId(),
                    new Date(),
                    name
                )
            )
        );
        templateScannedBlocks = null;
        blueprintScannedData = null;
        blueprintScannedEntities.clear();
        boxIterator = null;
        sendNetworkUpdate(NET_RENDER_DATA);
    }

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                writePayload(NET_BOX, buffer, side);
                buffer.writeString(name);
            }
            if (id == NET_BOX) {
                box.writeData(buffer);
                buffer.writeBoolean(markerBox);
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                readPayload(NET_BOX, buffer, side, ctx);
                name = buffer.readString();
            }
            if (id == NET_BOX) {
                box.readData(buffer);
                markerBox = buffer.readBoolean();
            }
            if (id == NET_SCAN) {
                ClientArchitectTables.SCANNED_BLOCKS.put(
                    MessageUtil.readBlockPos(buffer),
                    ClientArchitectTables.START_SCANNED_BLOCK_VALUE
                );
            }
        }
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("box", box.writeToNBT());
        nbt.setBoolean("markerBox", markerBox);
        if (boxIterator != null) {
            nbt.setTag("iter", boxIterator.writeToNbt());
        }
        nbt.setBoolean("scanning", scanning);
        nbt.setTag("snapshotType", NBTUtilBC.writeEnum(snapshotType));
        nbt.setBoolean("isValid", isValid);
        nbt.setString("name", name);
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        box.initialize(nbt.getCompoundTag("box"));
        markerBox = nbt.getBoolean("markerBox");
        if (nbt.hasKey("iter")) {
            boxIterator = BoxIterator.readFromNbt(nbt.getCompoundTag("iter"));
        }
        scanning = nbt.getBoolean("scanning");
        snapshotType = NBTUtilBC.readEnum(nbt.getTag("snapshotType"), EnumSnapshotType.class);
        isValid = nbt.getBoolean("isValid");
        name = nbt.getString("name");
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("box:");
        left.add(" - min = " + box.min());
        left.add(" - max = " + box.max());
        left.add("scanning = " + scanning);
        left.add("current = " + (boxIterator == null ? null : boxIterator.getCurrent()));
    }

    // Rendering

    @SuppressWarnings("NullableProblems")
    @Override
    @SideOnly(Side.CLIENT)
    public AxisAlignedBB getRenderBoundingBox() {
        return BoundingBoxUtil.makeFrom(pos, box);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public double getMaxRenderDistanceSquared() {
        return Double.MAX_VALUE;
    }
}
