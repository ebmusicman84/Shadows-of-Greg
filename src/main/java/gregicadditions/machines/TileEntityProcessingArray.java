package gregicadditions.machines;

import gregicadditions.GACapabilities;
import gregicadditions.GAConfig;
import gregicadditions.recipes.*;
import gregtech.api.GTValues;
import gregtech.api.block.machines.MachineItemBlock;
import gregtech.api.capability.*;
import gregtech.api.capability.impl.*;
import gregtech.api.gui.Widget;
import gregtech.api.metatileentity.*;
import gregtech.api.metatileentity.multiblock.*;
import gregtech.api.multiblock.*;
import gregtech.api.recipes.*;
import gregtech.api.recipes.Recipe.*;
import gregtech.api.recipes.builders.*;
import gregtech.api.render.*;
import gregtech.api.util.*;
import gregtech.common.blocks.BlockMetalCasing.*;
import gregtech.common.blocks.*;
import gregtech.common.metatileentities.electric.MetaTileEntityMacerator;
import net.minecraft.block.state.*;
import net.minecraft.item.*;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraftforge.fluids.*;
import net.minecraftforge.items.*;

import java.util.*;

import static gregtech.api.gui.widgets.AdvancedTextWidget.withButton;
import static gregtech.api.gui.widgets.AdvancedTextWidget.withHoverTextTranslate;

public class TileEntityProcessingArray extends RecipeMapMultiblockController {

	private static final MultiblockAbility<?>[] ALLOWED_ABILITIES = {
		MultiblockAbility.IMPORT_ITEMS,
		MultiblockAbility.EXPORT_ITEMS,
		MultiblockAbility.IMPORT_FLUIDS,
		MultiblockAbility.EXPORT_FLUIDS,
		MultiblockAbility.INPUT_ENERGY,
		GACapabilities.PA_MACHINE_CONTAINER
	};

	protected boolean isDistinctInputBusMode = false;

	public TileEntityProcessingArray(ResourceLocation metaTileEntityId) {
		super(metaTileEntityId, GARecipeMaps.PROCESSING_ARRAY_RECIPES);
		this.recipeMapWorkable = new ProcessingArrayWorkable(this);
	}

	@Override
	protected BlockPattern createStructurePattern() {

		return FactoryBlockPattern.start()
								  .aisle("XXX", "XXX", "XXX")
								  .aisle("XXX", "X#X", "XXX")
								  .aisle("XXX", "XSX", "XXX")
								  .setAmountAtLeast('L', 12)
								  .setAmountLimit('M', 1, 1)
								  .where('M', abilityPartPredicate(GACapabilities.PA_MACHINE_CONTAINER))
								  .where('L', statePredicate(getCasingState()))
								  .where('S', selfPredicate())
								  .where('X',
										 statePredicate(getCasingState())
											 .or(abilityPartPredicate(ALLOWED_ABILITIES)))
								  .where('#', isAirPredicate()).build();
	}

	public IBlockState getCasingState() {
		return MetaBlocks.METAL_CASING.getState(MetalCasingType.TUNGSTENSTEEL_ROBUST);
	}

	@Override
	public ICubeRenderer getBaseTexture(IMultiblockPart arg0) {
		return Textures.ROBUST_TUNGSTENSTEEL_CASING;
	}

	@Override
	public MetaTileEntity createMetaTileEntity(MetaTileEntityHolder holder) {
		return new TileEntityProcessingArray(metaTileEntityId);
	}

	protected MultiblockRecipeLogic getWorkable() {
		return recipeMapWorkable;
	}

	@Override
	protected void addDisplayText(List<ITextComponent> textList) {
		super.addDisplayText(textList);

		ITextComponent buttonText = new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct");
		buttonText.appendText(" ");
		ITextComponent button = withButton((isDistinctInputBusMode ?
				new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct.yes") :
				new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct.no")), "distinct");
		withHoverTextTranslate(button, "gtadditions.multiblock.processing_array.distinct.info");
		buttonText.appendSibling(button);
		textList.add(buttonText);
		textList.add(new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct2", isDistinctInputBusMode ?
				new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct.yes") :
				new TextComponentTranslation("gtadditions.multiblock.processing_array.distinct.no")));
		if(this.recipeMapWorkable.isActive()) {
			textList.add(new TextComponentTranslation("gtadditions.multiblock.processing_array.locked"));
		}
	}

	@Override
	protected void handleDisplayClick(String componentData, Widget.ClickData clickData) {
		super.handleDisplayClick(componentData, clickData);
		isDistinctInputBusMode = !isDistinctInputBusMode;
	}

	@Override
	public NBTTagCompound writeToNBT(NBTTagCompound data) {
		super.writeToNBT(data);
		data.setBoolean("Distinct", isDistinctInputBusMode);
		return data;
	}

	@Override
	public void readFromNBT(NBTTagCompound data) {
		super.readFromNBT(data);
		isDistinctInputBusMode = data.getBoolean("Distinct");
	}

	@Override
	public void writeInitialSyncData(PacketBuffer buf) {
		super.writeInitialSyncData(buf);
		buf.writeBoolean(isDistinctInputBusMode);
	}

	@Override
	public void receiveInitialSyncData(PacketBuffer buf) {
		super.receiveInitialSyncData(buf);
		this.isDistinctInputBusMode = buf.readBoolean();
	}

	@Override
	public void invalidateStructure() {
		super.invalidateStructure();
		((ProcessingArrayWorkable) this.recipeMapWorkable).invalidate();
	}

	protected static class ProcessingArrayWorkable extends MultiblockRecipeLogic {
		long voltageTier;
		int numberOfMachines = 0;
		int numberOfOperations = 0;
		ItemStack machineItemStack = null;
		ItemStack oldMachineStack = null;
		RecipeMap<?> recipeMap = null;
		// Fields used for distinct mode
		protected int lastRecipeIndex = 0;
		protected ItemStack[][] lastItemInputsMatrix;

		public ProcessingArrayWorkable(RecipeMapMultiblockController tileEntity) {
			super(tileEntity);
		}

		/*
        Overridden solely to update the machine stack and the recipe map at an early point.
        Recipe multiplication will come at a later time.
        */
		@Override
		protected Recipe findRecipe(long maxVoltage,
									IItemHandlerModifiable inputs,
									IMultipleTankHandler fluidInputs) {

			//Update the machine stack and recipe map
			findMachineStack();

			// Avoid crashing during load, when GTCE initializes its multiblock previews
			if(machineItemStack.isEmpty() || this.recipeMap == null) {
				return null;
			}

			return this.recipeMap.findRecipe(maxVoltage, inputs, fluidInputs, this.getMinTankCapacity(this.getOutputTank()));
		}


		protected Recipe multiplyRecipe(IItemHandlerModifiable inputs, IMultipleTankHandler fluidInputs, Recipe recipe, ItemStack machineStack, RecipeMap<?> rmap) {
			//Check if passed a null recipemap or machine stack
			if(rmap == null || machineStack == null) {
				return null;
			}

			MetaTileEntity mte = MachineItemBlock.getMetaTileEntity(machineStack);
			if(mte == null) {
				return null;
			}

			//Find the voltage tier of the machine.
			this.voltageTier = GTValues.V[((ITieredMetaTileEntity) mte).getTier()];
			//Find the number of machines
			this.numberOfMachines = Math.min(GAConfig.processingArray.processingArrayMachineLimit, machineStack.getCount());

			Set<ItemStack> ingredientStacks = findAllItemsInInputs(inputs);
			Map<String, Integer> fluidStacks = findAllFluidsInInputs(fluidInputs);

			int itemMultiplier = getMinRatioItem(ingredientStacks, recipe, this.numberOfMachines);
			int fluidMultiplier = getMinRatioFluid(fluidStacks, recipe, this.numberOfMachines);

			int minMultiplier = Math.min(itemMultiplier, fluidMultiplier);

			// No inputs or fluids
			if(minMultiplier == Integer.MAX_VALUE) {
				GTLog.logger.error("Cannot calculate ratio of items for processing array");
				return null;
			}

			List<CountableIngredient> newRecipeInputs = new ArrayList<>();
			List<FluidStack> newFluidInputs = new ArrayList<>();
			List<ItemStack> outputI = new ArrayList<>();
			List<FluidStack> outputF = new ArrayList<>();
			this.multiplyInputsAndOutputs(newRecipeInputs,
					newFluidInputs,
					outputI,
					outputF,
					recipe,
					minMultiplier);

			RecipeBuilder<?> newRecipe = rmap.recipeBuilder()
					.inputsIngredients(newRecipeInputs)
					.fluidInputs(newFluidInputs)
					.outputs(outputI)
					.fluidOutputs(outputF)
					.EUt(recipe.getEUt())
					.duration(recipe.getDuration());

			//Don't allow MV or LV macerators to have chanced outputs, because they do not have the slots for chanced outputs
			if(!(mte instanceof MetaTileEntityMacerator && (((MetaTileEntityMacerator) mte).getTier() == 1 || ((MetaTileEntityMacerator) mte).getTier() == 2))) {
				copyChancedItemOutputs(newRecipe, recipe, minMultiplier);
			}

			this.numberOfOperations = minMultiplier;
			return newRecipe.build().getResult();

		}

		protected static void copyChancedItemOutputs(RecipeBuilder<?> newRecipe,
													 Recipe oldRecipe,
													 int numberOfOperations) {
			for(ChanceEntry entry : oldRecipe.getChancedOutputs()) {
				int chance = entry.getChance();
				ItemStack itemStack = entry.getItemStack().copy();
				int boost = entry.getBoostPerTier();
				itemStack.setCount(itemStack.getCount() * numberOfOperations);

				newRecipe.chancedOutput(itemStack, chance, boost);
			}
		}

		protected List<IItemHandlerModifiable> getInputBuses() {
			RecipeMapMultiblockController controller = (RecipeMapMultiblockController) metaTileEntity;
			return controller.getAbilities(MultiblockAbility.IMPORT_ITEMS);
		}

		protected static Set<ItemStack> findAllItemsInInputs(IItemHandlerModifiable inputs) {
			Set<ItemStack> countIngredients = new HashSet<>();
			for(int slot = 0; slot < inputs.getSlots(); slot++) {
				ItemStack wholeItemStack = inputs.getStackInSlot(slot);

				//Skip empty slots
				if(wholeItemStack.isEmpty())
					continue;

				boolean found = false;
				for(ItemStack i : countIngredients)
					if(ItemStack.areItemsEqual(i, wholeItemStack)) {
						i.setCount(i.getCount() + wholeItemStack.getCount());
						found = true;
						break;
					}

				if(!found)
					countIngredients.add(wholeItemStack.copy());

			}
			return countIngredients;
		}

		protected int getMinRatioItem(Set<ItemStack> countIngredients,
									  Recipe recipe,
									  int numberOfMachines) {

			int minMultiplier = Integer.MAX_VALUE;
			for(CountableIngredient recipeInputs : recipe.getInputs()) {

				if(recipeInputs.getCount() == 0)
					continue;

				for(ItemStack wholeItemStack : countIngredients) {

					if(recipeInputs.getIngredient().apply(wholeItemStack)) {
						int ratio = Math.min(numberOfMachines, wholeItemStack.getCount() / recipeInputs.getCount());
						if(ratio < minMultiplier)
							minMultiplier = ratio;
						break;
					}

				}
			}
			return minMultiplier;
		}

		protected static Map<String, Integer> findAllFluidsInInputs(IMultipleTankHandler fluidInputs) {

			Map<String, Integer> countFluid = new HashMap<>();
			for(IFluidTank tank : fluidInputs)
				if(tank.getFluid() != null) {

					String name = tank.getFluid().getUnlocalizedName();

					if(countFluid.containsKey(name)) {
						int existingValue = countFluid.get(name);
						countFluid.put(name, existingValue + tank.getFluidAmount());
					} else
						countFluid.put(name, tank.getFluidAmount());
				}
			return countFluid;
		}

		protected int getMinRatioFluid(Map<String, Integer> countFluid,
									   Recipe recipe,
									   int numberOfMachines) {

			int minMultiplier = Integer.MAX_VALUE;
			for(FluidStack fs : recipe.getFluidInputs()) {
				String name = fs.getFluid().getUnlocalizedName();
				int ratio = Math.min(numberOfMachines, countFluid.get(name) / fs.amount);

				if(ratio < minMultiplier)
					minMultiplier = ratio;
			}
			return minMultiplier;
		}

		protected static ItemStack copyItemStackWithCount(ItemStack itemStack, int count) {
			ItemStack itemCopy = itemStack.copy();
			itemCopy.setCount(count);
			return itemCopy;
		}

		protected static FluidStack copyFluidStackWithAmount(FluidStack fluidStack, int count) {
			FluidStack fluidCopy = fluidStack.copy();
			fluidCopy.amount = count;
			return fluidCopy;
		}

		protected void multiplyInputsAndOutputs(List<CountableIngredient> newRecipeInputs,
												List<FluidStack> newFluidInputs,
												List<ItemStack> outputItems,
												List<FluidStack> outputFluids,
												Recipe recipe,
												int numberOfOperations) {

			recipe.getInputs().forEach(ci ->
					newRecipeInputs.add(new CountableIngredient(ci.getIngredient(),
																ci.getCount() * numberOfOperations)));

			recipe.getFluidInputs().forEach(fluidStack ->
					newFluidInputs.add(new FluidStack(fluidStack.getFluid(),
													  fluidStack.amount * numberOfOperations)));

			recipe.getOutputs().forEach(itemStack ->
				outputItems.add(copyItemStackWithCount(itemStack,
													   itemStack.getCount() * numberOfOperations)));

			recipe.getFluidOutputs().forEach(fluidStack ->
				outputFluids.add(copyFluidStackWithAmount(fluidStack,
														  fluidStack.amount * numberOfOperations)));
		}

		public void invalidate() {
			this.lastRecipeIndex = 0;
		}

		//Finds the Recipe Map of the passed Machine Stack and checks if it is a valid Recipe Map
		public static RecipeMap<?> findRecipeMapAndCheckValid(ItemStack machineStack) {

			if(machineStack == null || machineStack.isEmpty()) {
				return null;
			}

			String unlocalizedName = machineStack.getItem().getUnlocalizedNameInefficiently(machineStack);
			String recipeMapName = findRecipeMapName(unlocalizedName);


			//Check the machine against the Config blacklist
			if(!findMachineInBlacklist(recipeMapName)) {

				RecipeMap<?> rmap = RecipeMap.getByName(recipeMapName);

				if(rmap == null) {
					return null;
				}

				RecipeBuilder<?> rbuilder = rmap.recipeBuilder();

				//Find the RecipeMap of the MTE and ensure that the Processing Array only works on SimpleRecipeBuilders
				//For some reason GTCE has specialized recipe maps for some machines, when it does not need them
				if (rbuilder instanceof SimpleRecipeBuilder ||
						rbuilder instanceof IntCircuitRecipeBuilder ||
						rbuilder instanceof ArcFurnaceRecipeBuilder ||
						rbuilder instanceof CutterRecipeBuilder ||
						rbuilder instanceof UniversalDistillationRecipeBuilder) {

					return rmap;
				}
			}
			return null;
		}

		protected static String findRecipeMapName(String unlocalizedName) {

			String trimmedName = unlocalizedName.substring(0, unlocalizedName.lastIndexOf("."));
			trimmedName = trimmedName.substring(trimmedName.lastIndexOf(".") + 1);

			//Catch some cases where the machine's name is not the same as its recipe map's name
			switch (trimmedName) {
				case "cutter":
					trimmedName = "cutting_saw";
					break;
				case "electric_furnace":
					trimmedName = "furnace";
					break;
				case "ore_washer":
					trimmedName = "orewasher";
					break;
				case "brewery":
					trimmedName = "brewer";
			}

			return trimmedName;
		}

		protected static boolean findMachineInBlacklist(String unlocalizedName) {

			String[] blacklist = GAConfig.processingArray.machineBlackList;

			return Arrays.asList(blacklist).contains(unlocalizedName);
		}

		public void findMachineStack() {

			RecipeMapMultiblockController controller = (RecipeMapMultiblockController) this.metaTileEntity;

			//The Processing Array is limited to 1 Machine Interface per multiblock, and only has 1 slot
			ItemStack machine = controller.getAbilities(GACapabilities.PA_MACHINE_CONTAINER).get(0).getStackInSlot(0);

			RecipeMap<?> rmap = findRecipeMapAndCheckValid(machine);

			this.machineItemStack = machine;
			this.recipeMap = rmap;
		}

		@Override
		protected boolean setupAndConsumeRecipeInputs(Recipe recipe) {

			IItemHandlerModifiable importInventory = getInputInventory();
			IItemHandlerModifiable exportInventory = getOutputInventory();
			IMultipleTankHandler importFluids = getInputTank();
			IMultipleTankHandler exportFluids = getOutputTank();

			//Format: EU/t, duration
			int[] resultOverclock = calculateOverclock(recipe.getEUt(), voltageTier, recipe.getDuration());
			int totalEUt = resultOverclock[0] * resultOverclock[1] * this.numberOfOperations;

			boolean enoughPower;
			if(totalEUt >= 0) {
				int capacity;
				if(totalEUt > getEnergyCapacity() / 2)
					capacity = resultOverclock[0];
				else
					capacity = totalEUt;
				enoughPower = getEnergyStored() >= capacity;
			} else {
				int power = resultOverclock[0] * this.numberOfOperations;
				enoughPower = getEnergyStored() - (long) power <= getEnergyCapacity();
			}

			if(!enoughPower)
				return false;

			return MetaTileEntity.addItemsToItemHandler(exportInventory,
														true,
														recipe.getAllItemOutputs(exportInventory.getSlots())) &&
				MetaTileEntity.addFluidsToFluidHandler(exportFluids, true, recipe.getFluidOutputs()) &&
				recipe.matches(true, importInventory, importFluids);
		}

		/**
		 * Will check if the previous machine stack and the current machine stack are different
		 * @param newMachineStack - The current machine stack
		 * @return - true if the machine stacks are not equal, false if they are equal
		 */
		protected boolean didMachinesChange(ItemStack newMachineStack) {
			if(newMachineStack == null || this.oldMachineStack == null)
				return newMachineStack != this.oldMachineStack;

			return !ItemStack.areItemStacksEqual(this.oldMachineStack, newMachineStack);
		}

		@Override
		protected void trySearchNewRecipe() {
			if(metaTileEntity instanceof TileEntityProcessingArray && ((TileEntityProcessingArray) metaTileEntity).isDistinctInputBusMode) {
				trySearchNewRecipeDistinct();
			}
			else {
				trySearchNewRecipeCombined();
			}
		}

		private void trySearchNewRecipeCombined() {
			long maxVoltage = getMaxVoltage();
			Recipe currentRecipe;
			Recipe multipliedRecipe = null;
			IItemHandlerModifiable importInventory = getInputInventory();
			IMultipleTankHandler importFluids = getInputTank();

			//Update the stored machine stack and recipe map variables
			findMachineStack();

			boolean dirty = checkRecipeInputsDirty(importInventory, importFluids);
			if(dirty || forceRecipeRecheck) {
				//Check if the machine that the PA is operating on has changed
				if(didMachinesChange(machineItemStack)) {
					previousRecipe = null;
					oldMachineStack = null;
				}
			}

			if(previousRecipe != null &&
					previousRecipe.matches(false, importInventory, importFluids)) {
				currentRecipe = previousRecipe;
			}
			else {
				//If the previous recipe was null, or does not match the current recipe, search for a new recipe
				currentRecipe = findRecipe(maxVoltage, importInventory, importFluids);
				oldMachineStack = null;

				//Update the previous recipe
				if(currentRecipe != null) {
					this.previousRecipe = currentRecipe;
				}

				this.forceRecipeRecheck = false;
			}

			if(currentRecipe != null) {
				multipliedRecipe = multiplyRecipe(importInventory, importFluids, currentRecipe, machineItemStack, recipeMap);
			}

			//Attempts to run the current recipe, if it is not null
			if(multipliedRecipe != null && setupAndConsumeRecipeInputs(multipliedRecipe)) {
				oldMachineStack = machineItemStack;
				setupRecipe(multipliedRecipe);
			}
		}

		// ------------------------------ Distinct Bus Logic -----------------------------------------------
		// Distinct bus logic thanks to dan

		private void trySearchNewRecipeDistinct() {
			long maxVoltage = getMaxVoltage();
			Recipe currentRecipe = null;
			Recipe multipliedRecipe = null;
			List<IItemHandlerModifiable> importInventory = getInputBuses();
			IMultipleTankHandler importFluids = getInputTank();
			RecipeMapMultiblockController controller = (RecipeMapMultiblockController) this.metaTileEntity;
			IItemHandlerModifiable machineBus = controller.getAbilities(GACapabilities.PA_MACHINE_CONTAINER).get(0);

			//Update the stored machine stack and recipe map variables
			findMachineStack();

			//Check to see if the machine stack has changed first
			//TODO Could this machine bus specific check be used in the combined code?
			boolean machineDirty = checkRecipeInputsDirty(machineBus, importFluids);
			if(machineDirty || forceRecipeRecheck) {
				//Check if the machine that the PA is operating on has changed
				//Is this check needed if machineDirty is true?
				if(didMachinesChange(machineItemStack)) {
					previousRecipe = null;
					oldMachineStack = null;
				}
			}

			//Check if the previous recipe is null, to avoid having to iterate the distinct inputs
			if(previousRecipe != null && previousRecipe.matches(false, importInventory.get(lastRecipeIndex), importFluids)) {
				currentRecipe = previousRecipe;
				multipliedRecipe = multiplyRecipe(importInventory.get(lastRecipeIndex), importFluids, currentRecipe, machineItemStack, recipeMap);
				if(setupAndConsumeRecipeInputs(multipliedRecipe, lastRecipeIndex)) {
					setupRecipe(multipliedRecipe);
					oldMachineStack = machineItemStack;
					return;
				}
			}

			//If the machine stack changed, or the previous recipe is null, check for a new recipe
			oldMachineStack = null;
			for (int i = 0; i < importInventory.size(); i++) {
				IItemHandlerModifiable bus = importInventory.get(i);
				boolean dirty = checkRecipeInputsDirty(bus, importFluids, i);
				if (dirty || forceRecipeRecheck) {
					this.forceRecipeRecheck = false;
					currentRecipe = findRecipe(maxVoltage, bus, importFluids);
					if(currentRecipe != null) {
						this.previousRecipe = currentRecipe;
					}
				}
				if(currentRecipe != null) {
					multipliedRecipe = multiplyRecipe(bus, importFluids, currentRecipe, machineItemStack, recipeMap);
				}

				if(multipliedRecipe != null && setupAndConsumeRecipeInputs(multipliedRecipe, i)) {
					lastRecipeIndex = i;
					setupRecipe(multipliedRecipe);
					oldMachineStack = machineItemStack;
					break;
				}
			}
		}

		// Replacing this for optimization reasons
		protected boolean checkRecipeInputsDirty(IItemHandler inputs, IMultipleTankHandler fluidInputs, int index) {
			boolean shouldRecheckRecipe = false;

			if (lastItemInputsMatrix == null || lastItemInputsMatrix.length != getInputBuses().size()) {
				lastItemInputsMatrix = new ItemStack[getInputBuses().size()][];
			}
			if (lastItemInputsMatrix[index] == null || lastItemInputsMatrix[index].length != inputs.getSlots()) {
				this.lastItemInputsMatrix[index] = new ItemStack[inputs.getSlots()];
				Arrays.fill(lastItemInputsMatrix[index], ItemStack.EMPTY);
			}
			if (lastFluidInputs == null || lastFluidInputs.length != fluidInputs.getTanks()) {
				this.lastFluidInputs = new FluidStack[fluidInputs.getTanks()];
			}
			for (int i = 0; i < lastItemInputsMatrix[index].length; i++) {
				ItemStack currentStack = inputs.getStackInSlot(i);
				ItemStack lastStack = lastItemInputsMatrix[index][i];
				if (!areItemStacksEqual(currentStack, lastStack)) {
					this.lastItemInputsMatrix[index][i] = currentStack.isEmpty() ? ItemStack.EMPTY : currentStack.copy();
					shouldRecheckRecipe = true;
				} else if (currentStack.getCount() != lastStack.getCount()) {
					lastStack.setCount(currentStack.getCount());
					shouldRecheckRecipe = true;
				}
			}
			for (int i = 0; i < lastFluidInputs.length; i++) {
				FluidStack currentStack = fluidInputs.getTankAt(i).getFluid();
				FluidStack lastStack = lastFluidInputs[i];
				if ((currentStack == null && lastStack != null) ||
						(currentStack != null && !currentStack.isFluidEqual(lastStack))) {
					this.lastFluidInputs[i] = currentStack == null ? null : currentStack.copy();
					shouldRecheckRecipe = true;
				} else if (currentStack != null && lastStack != null &&
						currentStack.amount != lastStack.amount) {
					lastStack.amount = currentStack.amount;
					shouldRecheckRecipe = true;
				}
			}
			return shouldRecheckRecipe;
		}


		protected boolean setupAndConsumeRecipeInputs(Recipe recipe, int index) {
			RecipeMapMultiblockController controller = (RecipeMapMultiblockController) metaTileEntity;
			if (controller.checkRecipe(recipe, false)) {

				int[] resultOverclock = calculateOverclock(recipe.getEUt(), recipe.getDuration());
				int totalEUt = resultOverclock[0] * resultOverclock[1];
				IItemHandlerModifiable importInventory = getInputBuses().get(index);
				IItemHandlerModifiable exportInventory = getOutputInventory();
				IMultipleTankHandler importFluids = getInputTank();
				IMultipleTankHandler exportFluids = getOutputTank();
				boolean setup = (totalEUt >= 0 ? getEnergyStored() >= (totalEUt > getEnergyCapacity() / 2 ? resultOverclock[0] : totalEUt) :
						(getEnergyStored() - resultOverclock[0] <= getEnergyCapacity())) &&
						MetaTileEntity.addItemsToItemHandler(exportInventory, true, recipe.getAllItemOutputs(exportInventory.getSlots())) &&
						MetaTileEntity.addFluidsToFluidHandler(exportFluids, true, recipe.getFluidOutputs()) &&
						recipe.matches(true, importInventory, importFluids);

				if (setup) {
					controller.checkRecipe(recipe, true);
					return true;
				}
			}
			return false;
		}


		// ------------------------------- End Distinct Bus Logic ------------------------------------------------

		@Override
		protected void setupRecipe(Recipe recipe) {
			int[] resultOverclock = calculateOverclock(recipe.getEUt(), voltageTier, recipe.getDuration());
			this.progressTime = 1;
			setMaxProgress(resultOverclock[1]);
			this.recipeEUt = resultOverclock[0] * this.numberOfOperations;
			this.fluidOutputs = GTUtility.copyFluidList(recipe.getFluidOutputs());
			int tier = getMachineTierForRecipe(recipe);
			this.itemOutputs = GTUtility.copyStackList(recipe.getResultItemOutputs(getOutputInventory().getSlots(),
																				   random,
																				   tier));

			if(this.wasActiveAndNeedsUpdate)
				this.wasActiveAndNeedsUpdate = false;
			else
				setActive(true);
		}
	}
}
