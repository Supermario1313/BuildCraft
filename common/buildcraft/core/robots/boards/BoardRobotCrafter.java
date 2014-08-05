/**
 * Copyright (c) 2011-2014, SpaceToad and the BuildCraft Team
 * http://www.mod-buildcraft.com
 *
 * BuildCraft is distributed under the terms of the Minecraft Mod Public
 * License 1.0, or MMPL. Please check the contents of the license located in
 * http://www.mod-buildcraft.com/MMPL-1.0.txt
 */
package buildcraft.core.robots.boards;

import java.util.ArrayList;
import java.util.HashSet;

import net.minecraft.block.Block;
import net.minecraft.block.BlockWorkbench;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;

import net.minecraftforge.common.util.ForgeDirection;

import buildcraft.api.boards.RedstoneBoardRobot;
import buildcraft.api.boards.RedstoneBoardRobotNBT;
import buildcraft.api.core.IInvSlot;
import buildcraft.api.gates.ActionParameterItemStack;
import buildcraft.api.gates.IActionParameter;
import buildcraft.api.robots.AIRobot;
import buildcraft.api.robots.EntityRobotBase;
import buildcraft.api.robots.IDockingStation;
import buildcraft.core.inventory.ITransactor;
import buildcraft.core.inventory.InventoryIterator;
import buildcraft.core.inventory.StackHelper;
import buildcraft.core.inventory.Transactor;
import buildcraft.core.inventory.filters.ArrayStackFilter;
import buildcraft.core.inventory.filters.IStackFilter;
import buildcraft.core.robots.AIRobotGotoStationToLoad;
import buildcraft.core.robots.AIRobotGotoStationToUnload;
import buildcraft.core.robots.AIRobotLoad;
import buildcraft.core.robots.AIRobotSearchAndGotoStation;
import buildcraft.core.robots.AIRobotSleep;
import buildcraft.core.robots.AIRobotUnload;
import buildcraft.core.robots.DockingStation;
import buildcraft.core.robots.IStationFilter;
import buildcraft.silicon.statements.ActionRobotCraft;
import buildcraft.transport.gates.ActionIterator;
import buildcraft.transport.gates.ActionSlot;

public class BoardRobotCrafter extends RedstoneBoardRobot {

	private ItemStack order;
	private ArrayList<ItemStack> craftingBlacklist = new ArrayList<ItemStack>();
	private HashSet<IDockingStation> reservedStations = new HashSet<IDockingStation>();
	private ArrayList<ItemStack> requirements = new ArrayList<ItemStack>();
	private IRecipe recipe;
	private int craftingTimer = 0;

	public BoardRobotCrafter(EntityRobotBase iRobot) {
		super(iRobot);
	}

	@Override
	public RedstoneBoardRobotNBT getNBTHandler() {
		return BoardRobotCrafterNBT.instance;
	}

	@Override
	public void update() {

		// [1] look for a crafting order
		// -- if none, clear temporary item blacklist and sleep
		// [2] look and fetch items needed to craft (problem with 9 slots inv?)
		// -- if can't be done, add item to temporary blacklist, drop inv either
		// -- in a inventory accepting items or drop in the world, then look for
		// -- another order
		// [3] look and goto a station next to a workbench, craft
		// -- if not, sleep
		// [4] drop the crafting item where possible
		// -- if not, sleep

		if (craftingTimer > 0) {
			craftingTimer--;

			if (craftingTimer == 0) {
				craft();
				startDelegateAI(new AIRobotGotoStationToUnload(robot, robot.getZoneToWork()));
			}
		} else if (order == null) {
			order = getCraftingOrder();

			if (order == null) {
				craftingBlacklist.clear();
				startDelegateAI(new AIRobotSleep(robot));
				return;
			}

			recipe = lookForRecipe(order);

			if (recipe == null) {
				craftingBlacklist.add(order);
				order = null;
				return;
			}

			requirements = getRequirements(recipe);

			if (requirements == null) {
				craftingBlacklist.add(order);
				order = null;
				return;
			}

			mergeRequirements();
		} else if (requirements.size() > 0) {
			startDelegateAI(new AIRobotGotoStationToLoad(robot, new ReqStackFilter(), robot.getZoneToWork()));
		} else {
			startDelegateAI(new AIRobotSearchAndGotoStation(robot, new StationWorkbenchFilter(), robot.getZoneToWork()));
		}
	}

	@Override
	public void delegateAIEnded(AIRobot ai) {
		if (ai instanceof AIRobotGotoStationToLoad) {
			if (((AIRobotGotoStationToLoad) ai).found) {
				startDelegateAI(new AIRobotLoad(robot, new ReqStackFilter(), 1));
			} else {
				craftingBlacklist.add(order);
				order = null;
				requirements.clear();

				// drop items in inventory.
				startDelegateAI(new AIRobotGotoStationToUnload(robot, robot.getZoneToWork()));
			}
		} else if (ai instanceof AIRobotLoad) {
			// Check requirements v.s. contents

			for (int i = requirements.size() - 1; i >= 0; --i) {
				ItemStack req = requirements.get(i);
				int qty = 0;

				for (IInvSlot slot : InventoryIterator.getIterable(robot)) {
					if (StackHelper.isMatchingItem(req, slot.getStackInSlot())) {
						qty += slot.getStackInSlot().stackSize;
					}
				}

				if (qty >= req.stackSize) {
					requirements.remove(i);
				}
			}
		} else if (ai instanceof AIRobotGotoStationToUnload) {
			if (((AIRobotGotoStationToUnload) ai).found) {
				startDelegateAI(new AIRobotUnload(robot));
			}
		} else if (ai instanceof AIRobotSearchAndGotoStation) {
			if (new StationWorkbenchFilter().matches((DockingStation) robot.getDockingStation())) {
				craftingTimer = 40;
			} else {
				startDelegateAI(new AIRobotSleep(robot));
			}
		}
	}

	private void mergeRequirements() {
		for (int i = 0; i < requirements.size(); ++i) {
			for (int j = i + 1; j < requirements.size(); ++j) {
				if (StackHelper.isMatchingItem(requirements.get(i), requirements.get(j))) {
					requirements.get(i).stackSize += requirements.get(j).stackSize;
					requirements.get(j).stackSize = 0;
				}
			}
		}

		for (int i = requirements.size() - 1; i >= 0; --i) {
			if (requirements.get(i).stackSize == 0) {
				requirements.remove(i);
			}
		}
	}

	private ArrayList<ItemStack> getRequirements(IRecipe recipe) {
		if (recipe instanceof ShapelessRecipes) {
			ArrayList<ItemStack> result = new ArrayList<ItemStack>();

			ShapelessRecipes r = (ShapelessRecipes) recipe;

			for (Object o : r.recipeItems) {
				result.add(((ItemStack) o).copy());
			}

			return result;
		} else if (recipe instanceof ShapedRecipes) {
			ArrayList<ItemStack> result = new ArrayList<ItemStack>();

			ShapedRecipes r = (ShapedRecipes) recipe;

			for (ItemStack s : r.recipeItems) {
				if (s != null) {
					result.add(s.copy());
				}
			}

			return result;
		} else {
			return null;
		}
	}

	private IRecipe lookForRecipe(ItemStack order) {
		for (Object o : CraftingManager.getInstance().getRecipeList()) {
			IRecipe r = (IRecipe) o;

			if (r instanceof ShapedRecipes || r instanceof ShapelessRecipes) {
				if (StackHelper.isMatchingItem(r.getRecipeOutput(), order)) {
					return r;
				}
			}
		}

		return null;
	}

	private boolean isBlacklisted(ItemStack stack) {
		for (ItemStack black : craftingBlacklist) {
			if (StackHelper.isMatchingItem(stack, black)) {
				return true;
			}
		}

		return false;
	}

	private ItemStack getCraftingOrder() {
		// [1] priority from the current station order

		DockingStation s = (DockingStation) robot.getLinkedStation();

		for (ActionSlot slot : new ActionIterator(s.pipe.pipe)) {
			if (slot.action instanceof ActionRobotCraft) {
				for (IActionParameter p : slot.parameters) {
					if (p != null && p instanceof ActionParameterItemStack) {
						ActionParameterItemStack param = (ActionParameterItemStack) p;
						ItemStack stack = param.getItemStackToDraw();

						if (stack != null && !isBlacklisted(stack)) {
							return stack;
						}
					}
				}
			}
		}

		// [2] if no order, will look at the "request" stations (either from
		// inventories or machines).
		// when taking a "request" order, lock the target station

		return null;
	}

	private void craft() {
		ArrayList<ItemStack> tmpReq = getRequirements(recipe);

		ITransactor transactor = Transactor.getTransactorFor(robot);

		for (ItemStack s : tmpReq) {
			for (int i = 0; i < s.stackSize; ++i) {
				transactor.remove(new ArrayStackFilter(s), ForgeDirection.UNKNOWN, true);
			}
		}

		transactor.add(order, ForgeDirection.UNKNOWN, true);

		order = null;
		recipe = null;
	}

	private class ReqStackFilter implements IStackFilter {

		@Override
		public boolean matches(ItemStack stack) {
			for (ItemStack s : requirements) {
				if (StackHelper.isMatchingItem(stack, s)) {
					return true;
				}
			}

			return false;
		}
	}

	private class StationWorkbenchFilter implements IStationFilter {

		@Override
		public boolean matches(DockingStation station) {
			for (ForgeDirection dir : ForgeDirection.VALID_DIRECTIONS) {
				Block nearbyBlock = robot.worldObj.getBlock(station.x() + dir.offsetX, station.y()
						+ dir.offsetY, station.z()
						+ dir.offsetZ);

				if (nearbyBlock instanceof BlockWorkbench) {
					return true;
				}
			}

			return false;
		}

	}

}