package squeek.applecore.asm.module;

import static org.objectweb.asm.Opcodes.*;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import squeek.applecore.api.food.FoodValues;
import squeek.applecore.api.hunger.ExhaustionEvent;
import squeek.applecore.api.hunger.HealthRegenEvent;
import squeek.applecore.api.hunger.StarvationEvent;
import squeek.applecore.asm.ASMHelper;
import squeek.applecore.asm.Hooks;
import squeek.applecore.asm.IClassTransformerModule;
import cpw.mods.fml.common.eventhandler.Event;

public class ModuleFoodStats implements IClassTransformerModule
{

	@Override
	public String[] getClassesToTransform()
	{
		return new String[]{"net.minecraft.entity.player.EntityPlayer", "net.minecraft.util.FoodStats"};
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass)
	{
		if (transformedName.equals("net.minecraft.entity.player.EntityPlayer"))
		{
			boolean isObfuscated = !name.equals(transformedName);

			ClassNode classNode = ASMHelper.readClassFromBytes(basicClass);

			MethodNode methodNode = ASMHelper.findMethodNodeOfClass(classNode, "<init>", null);
			if (methodNode != null)
			{
				patchEntityPlayerInit(methodNode, isObfuscated);
				// computing frames here causes a ClassNotFoundException in ClassWriter.getCommonSuperClass
				// in an obfuscated environment, so skip computing them as a workaround
				// see: http://stackoverflow.com/a/11605942
				return ASMHelper.writeClassToBytesSkipFrames(classNode);
			}
			else
				throw new RuntimeException("EntityPlayer: <init> method not found");
		}
		if (transformedName.equals("net.minecraft.util.FoodStats"))
		{
			boolean isObfuscated = !name.equals(transformedName);

			ClassNode classNode = ASMHelper.readClassFromBytes(basicClass);

			injectFoodStatsPlayerField(classNode);
			injectFoodStatsConstructor(classNode, isObfuscated);

			MethodNode addStatsMethodNode = ASMHelper.findMethodNodeOfClass(classNode, isObfuscated ? "a" : "addStats", "(IF)V");
			if (addStatsMethodNode != null)
			{
				//addHungerLossRateCheck(addStatsMethodNode);
			}
			else
				throw new RuntimeException("FoodStats: addStats(IF)V method not found");

			MethodNode methodNode = ASMHelper.findMethodNodeOfClass(classNode, isObfuscated ? "a" : "func_151686_a", isObfuscated ? "(Lacx;Ladd;)V" : "(Lnet/minecraft/item/ItemFood;Lnet/minecraft/item/ItemStack;)V");
			if (methodNode != null)
			{
				addItemStackAwareFoodStatsHook(classNode, methodNode, isObfuscated);
			}
			else
				throw new RuntimeException("FoodStats: ItemStack-aware addStats method not found");

			MethodNode updateMethodNode = ASMHelper.findMethodNodeOfClass(classNode, isObfuscated ? "a" : "onUpdate", isObfuscated ? "(Lyz;)V" : "(Lnet/minecraft/entity/player/EntityPlayer;)V");
			if (updateMethodNode != null)
			{
				hookHealthRegen(classNode, updateMethodNode, isObfuscated);
				hookExhaustion(classNode, updateMethodNode, isObfuscated);
				hookStarvation(classNode, updateMethodNode, isObfuscated);
			}
			else
				throw new RuntimeException("FoodStats: onUpdate method not found");

			return ASMHelper.writeClassToBytes(classNode);
		}
		return basicClass;
	}

	public void patchEntityPlayerInit(MethodNode method, boolean isObfuscated)
	{
		// find NEW net/minecraft/util/FoodStats
		AbstractInsnNode targetNode = ASMHelper.findFirstInstructionOfTypeWithDesc(method, NEW, isObfuscated ? "zr" : "net/minecraft/util/FoodStats");

		if (targetNode == null)
		{
			throw new RuntimeException("patchEntityPlayerInit: NEW instruction not found");
		}

		do
		{
			targetNode = targetNode.getNext();
		}
		while (targetNode != null && targetNode.getOpcode() != INVOKESPECIAL);

		if (targetNode == null)
		{
			throw new RuntimeException("patchEntityPlayerInit: INVOKESPECIAL instruction not found");
		}

		method.instructions.insertBefore(targetNode, new VarInsnNode(ALOAD, 0));
		((MethodInsnNode) targetNode).desc = isObfuscated ? "(Lyz;)V" : "(Lnet/minecraft/entity/player/EntityPlayer;)V";
	}

	public void injectFoodStatsPlayerField(ClassNode classNode)
	{
		classNode.fields.add(new FieldNode(ACC_PUBLIC, "player", Type.getDescriptor(EntityPlayer.class), null, null));
	}

	public void injectFoodStatsConstructor(ClassNode classNode, boolean isObfuscated)
	{
		MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", isObfuscated ? "(Lyz;)V" : "(Lnet/minecraft/entity/player/EntityPlayer;)V", null, null);

		constructor.visitVarInsn(ALOAD, 0);
		constructor.visitMethodInsn(INVOKESPECIAL, classNode.superName, "<init>", "()V");

		constructor.visitVarInsn(ALOAD, 0); // this
		constructor.visitVarInsn(ALOAD, 1); // player param
		constructor.visitFieldInsn(PUTFIELD, classNode.name, "player", Type.getDescriptor(EntityPlayer.class));

		constructor.visitInsn(RETURN);
		constructor.visitMaxs(2, 2);
		constructor.visitEnd();

		classNode.methods.add(constructor);
	}

	public void addItemStackAwareFoodStatsHook(ClassNode classNode, MethodNode method, boolean isObfuscated)
	{
		// injected code:
		/*
		FoodValues modifiedFoodValues;
		if ((modifiedFoodValues = Hooks.onFoodStatsAdded(this, par1, par2, this.player)) != null)
		{
			int prevFoodLevel = this.foodLevel;
			float prevSaturationLevel = this.foodSaturationLevel;
			
			this.addStats(modifiedFoodValues.hunger, modifiedFoodValues.saturationModifier);
			
			Hooks.onPostFoodStatsAdded(this, modifiedFoodValues, this.foodLevel - prevFoodLevel, this.foodSaturationLevel - prevSaturationLevel, this.player);
			return;
		}
		*/

		String internalFoodStatsName = classNode.name.replace(".", "/");
		AbstractInsnNode targetNode = ASMHelper.findFirstInstruction(method);

		InsnList toInject = new InsnList();

		// create modifiedFoodValues variable
		LabelNode modifiedFoodValuesStart = new LabelNode();
		LabelNode end = ASMHelper.findEndLabel(method);
		LocalVariableNode modifiedFoodValues = new LocalVariableNode("modifiedFoodValues", Type.getDescriptor(FoodValues.class), null, modifiedFoodValuesStart, end, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(modifiedFoodValues);

		LabelNode ifJumpLabel = new LabelNode();

		// create prevFoodLevel variable
		LabelNode prevFoodLevelStart = new LabelNode();
		LocalVariableNode prevFoodLevel = new LocalVariableNode("prevFoodLevel", "I", null, prevFoodLevelStart, ifJumpLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(prevFoodLevel);

		// create prevSaturationLevel variable
		LabelNode prevSaturationLevelStart = new LabelNode();
		LocalVariableNode prevSaturationLevel = new LocalVariableNode("prevSaturationLevel", "F", null, prevSaturationLevelStart, ifJumpLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(prevSaturationLevel);

		// get modifiedFoodValues
		toInject.add(new VarInsnNode(ALOAD, 0));					// this
		toInject.add(new VarInsnNode(ALOAD, 1));					// param 1: ItemFood
		toInject.add(new VarInsnNode(ALOAD, 2));					// param 2: ItemStack
		toInject.add(new VarInsnNode(ALOAD, 0));					// this.player (together with below line)
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "player", Type.getDescriptor(EntityPlayer.class)));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "onFoodStatsAdded", "(Lnet/minecraft/util/FoodStats;Lnet/minecraft/item/ItemFood;Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;)Lsqueek/applecore/api/food/FoodValues;"));
		toInject.add(new InsnNode(DUP));
		toInject.add(new VarInsnNode(ASTORE, modifiedFoodValues.index));		// modifiedFoodValues = hookClass.hookMethod(...)
		toInject.add(modifiedFoodValuesStart);								// variable scope start
		toInject.add(new JumpInsnNode(IFNULL, ifJumpLabel));		// if (modifiedFoodValues != null)

		// if true
		// save current hunger/saturation levels
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));
		toInject.add(new VarInsnNode(ISTORE, prevFoodLevel.index));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "b" : "foodSaturationLevel", "F"));
		toInject.add(new VarInsnNode(FSTORE, prevSaturationLevel.index));

		// call this.addStats(IF)V with the modified values
		toInject.add(new VarInsnNode(ALOAD, 0));					// this
		toInject.add(new VarInsnNode(ALOAD, modifiedFoodValues.index));		// modifiedFoodValues
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(FoodValues.class), "hunger", "I"));
		toInject.add(new VarInsnNode(ALOAD, modifiedFoodValues.index));		// modifiedFoodValues
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(FoodValues.class), "saturationModifier", "F"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, internalFoodStatsName, isObfuscated ? "a" : "addStats", "(IF)V"));

		/*
		 * Start onPostFoodStatsAdded call
		 */
		// this
		toInject.add(new VarInsnNode(ALOAD, 0));

		// par1 (ItemFood)
		toInject.add(new VarInsnNode(ALOAD, 1));

		// par2 (ItemStack)
		toInject.add(new VarInsnNode(ALOAD, 2));

		// modifiedFoodValues
		toInject.add(new VarInsnNode(ALOAD, modifiedFoodValues.index));

		// prevFoodLevel - this.foodLevel
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));
		toInject.add(new VarInsnNode(ILOAD, prevFoodLevel.index));
		toInject.add(new InsnNode(ISUB));

		// prevSaturationLevel - this.foodSaturationLevel
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "b" : "foodSaturationLevel", "F"));
		toInject.add(new VarInsnNode(FLOAD, prevSaturationLevel.index));
		toInject.add(new InsnNode(FSUB));

		// player
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "player", Type.getDescriptor(EntityPlayer.class)));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "onPostFoodStatsAdded", "(Lnet/minecraft/util/FoodStats;Lnet/minecraft/item/ItemFood;Lnet/minecraft/item/ItemStack;Lsqueek/applecore/api/food/FoodValues;IFLnet/minecraft/entity/player/EntityPlayer;)V"));
		/*
		 * End onPostFoodStatsAdded call
		 */

		// return
		toInject.add(new InsnNode(RETURN));
		toInject.add(ifJumpLabel);			// if hook returned null, will jump here

		method.instructions.insertBefore(targetNode, toInject);
	}

	private void addHungerLossRateCheck(MethodNode method)
	{
		// injected code:
		/*
		if(IguanaConfig.hungerLossRatePercentage > 0)
		{
		    // default code
		}
		*/

		AbstractInsnNode targetNode = ASMHelper.findFirstInstruction(method);

		LabelNode ifGreaterThan = new LabelNode();

		InsnList toInject = new InsnList();
		//toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(IguanaConfig.class), "hungerLossRatePercentage", "I"));
		toInject.add(new JumpInsnNode(IFLE, ifGreaterThan));

		method.instructions.insertBefore(targetNode, toInject);

		targetNode = ASMHelper.findLastInstructionOfType(method, PUTFIELD).getNext().getNext();

		method.instructions.insertBefore(targetNode, ifGreaterThan);
	}

	private void hookExhaustion(ClassNode classNode, MethodNode method, boolean isObfuscated)
	{
		// exhaustion block replaced with:
		/*
		FoodEvent.Exhaustion.Tick exhaustionTickEvent = Hooks.fireExhaustionTickEvent(player, foodExhaustionLevel);
		this.foodExhaustionLevel = exhaustionTickEvent.exhaustionLevel;
		if (!exhaustionTickEvent.isCanceled() && this.foodExhaustionLevel >= exhaustionTickEvent.maxExhaustionLevel)
		{
			FoodEvent.Exhaustion.MaxReached exhaustionMaxEvent = Hooks.fireExhaustionMaxEvent(player, exhaustionTickEvent.maxExhaustionLevel, foodExhaustionLevel);

			if (!exhaustionMaxEvent.isCanceled())
			{
				this.foodExhaustionLevel += exhaustionMaxEvent.deltaExhaustion;
				this.foodSaturationLevel = Math.max(this.foodSaturationLevel + exhaustionMaxEvent.deltaSaturation, 0.0F);
				this.foodLevel = Math.max(this.foodLevel + exhaustionMaxEvent.deltaHunger, 0);
			}
		}
		*/

		String internalFoodStatsName = classNode.name.replace(".", "/");
		LabelNode endLabel = ASMHelper.findEndLabel(method);

		InsnList toInject = new InsnList();

		AbstractInsnNode injectPoint = ASMHelper.findFirstInstructionOfType(method, PUTFIELD);
		AbstractInsnNode foodExhaustionIf = ASMHelper.findFirstInstructionOfType(method, IFLE);
		LabelNode foodExhaustionBlockEndLabel = ((JumpInsnNode) foodExhaustionIf).label;

		// remove the entire exhaustion block
		ASMHelper.removeNodesFromMethodUntil(method, injectPoint.getNext(), foodExhaustionBlockEndLabel);

		// create exhaustionTickEvent variable
		LabelNode exhaustionTickEventStart = new LabelNode();
		LocalVariableNode exhaustionTickEvent = new LocalVariableNode("exhaustionTickEvent", Type.getDescriptor(ExhaustionEvent.Tick.class), null, exhaustionTickEventStart, endLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(exhaustionTickEvent);

		// FoodEvent.Exhaustion.Tick exhaustionTickEvent = Hooks.fireExhaustionTickEvent(player, foodExhaustionLevel);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireExhaustionTickEvent", "(Lnet/minecraft/entity/player/EntityPlayer;F)Lsqueek/applecore/api/hunger/ExhaustionEvent$Tick;"));
		toInject.add(new VarInsnNode(ASTORE, exhaustionTickEvent.index));
		toInject.add(exhaustionTickEventStart);

		// this.foodExhaustionLevel = exhaustionTickEvent.exhaustionLevel;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new VarInsnNode(ALOAD, exhaustionTickEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.Tick.class), "exhaustionLevel", "F"));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));

		// if (!exhaustionTickEvent.isCanceled() && this.foodExhaustionLevel >= exhaustionTickEvent.maxExhaustionLevel)
		toInject.add(new VarInsnNode(ALOAD, exhaustionTickEvent.index));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(ExhaustionEvent.Tick.class), "isCanceled", "()Z"));
		toInject.add(new JumpInsnNode(IFNE, foodExhaustionBlockEndLabel));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));
		toInject.add(new VarInsnNode(ALOAD, exhaustionTickEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.Tick.class), "maxExhaustionLevel", "F"));
		toInject.add(new InsnNode(FCMPL));
		toInject.add(new JumpInsnNode(IFLT, foodExhaustionBlockEndLabel));

		// create exhaustionTickEvent variable
		LabelNode exhaustionMaxEventStart = new LabelNode();
		LocalVariableNode exhaustionMaxEvent = new LocalVariableNode("exhaustionMaxEvent", Type.getDescriptor(ExhaustionEvent.MaxReached.class), null, exhaustionMaxEventStart, foodExhaustionBlockEndLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(exhaustionMaxEvent);

		// FoodEvent.Exhaustion.MaxReached exhaustionMaxEvent = Hooks.fireExhaustionMaxEvent(player, exhaustionTickEvent.maxExhaustionLevel, foodExhaustionLevel);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new VarInsnNode(ALOAD, exhaustionTickEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.Tick.class), "maxExhaustionLevel", "F"));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireExhaustionMaxEvent", "(Lnet/minecraft/entity/player/EntityPlayer;FF)Lsqueek/applecore/api/hunger/ExhaustionEvent$MaxReached;"));
		toInject.add(new VarInsnNode(ASTORE, exhaustionMaxEvent.index));
		toInject.add(exhaustionMaxEventStart);

		// if (!exhaustionMaxEvent.isCanceled())
		toInject.add(new VarInsnNode(ALOAD, exhaustionMaxEvent.index));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(ExhaustionEvent.MaxReached.class), "isCanceled", "()Z"));
		toInject.add(new JumpInsnNode(IFNE, foodExhaustionBlockEndLabel));

		// this.foodExhaustionLevel += exhaustionMaxEvent.deltaExhaustion;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(DUP));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));
		toInject.add(new VarInsnNode(ALOAD, exhaustionMaxEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.MaxReached.class), "deltaExhaustion", "F"));
		toInject.add(new InsnNode(FADD));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, isObfuscated ? "c" : "foodExhaustionLevel", "F"));

		// this.foodSaturationLevel = Math.max(this.foodSaturationLevel + exhaustionMaxEvent.deltaSaturation, 0.0F);
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "b" : "foodSaturationLevel", "F"));
		toInject.add(new VarInsnNode(ALOAD, exhaustionMaxEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.MaxReached.class), "deltaSaturation", "F"));
		toInject.add(new InsnNode(FADD));
		toInject.add(new InsnNode(FCONST_0));
		toInject.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Math", "max", "(FF)F"));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, isObfuscated ? "b" : "foodSaturationLevel", "F"));

		// this.foodLevel = Math.max(this.foodLevel + exhaustionMaxEvent.deltaHunger, 0);
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));
		toInject.add(new VarInsnNode(ALOAD, exhaustionMaxEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(ExhaustionEvent.MaxReached.class), "deltaHunger", "I"));
		toInject.add(new InsnNode(IADD));
		toInject.add(new InsnNode(ICONST_0));
		toInject.add(new MethodInsnNode(INVOKESTATIC, "java/lang/Math", "max", "(II)I"));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));

		method.instructions.insert(injectPoint, toInject);
	}

	private void hookHealthRegen(ClassNode classNode, MethodNode method, boolean isObfuscated)
	{
		// health regen block replaced with:
		/*
		Result allowRegenResult = Hooks.fireAllowRegenEvent(player);
		if (allowRegenResult == Result.ALLOW || (allowRegenResult == Result.DEFAULT && player.worldObj.getGameRules().getGameRuleBooleanValue("naturalRegeneration") && this.foodLevel >= 18 && player.shouldHeal()))
		{
			++this.foodTimer;

			if (this.foodTimer >= Hooks.fireRegenTickEvent(player))
			{
				FoodEvent.RegenHealth.Regen regenEvent = Hooks.fireRegenEvent(player);
				if (!regenEvent.isCanceled())
				{
					player.heal(regenEvent.deltaHealth);
					this.addExhaustion(regenEvent.deltaExhaustion);
				}
				this.foodTimer = 0;
			}
		}
		else
		{
			this.foodTimer = 0;
		}
		*/
		
		String internalFoodStatsName = classNode.name.replace(".", "/");
		LabelNode endLabel = ASMHelper.findEndLabel(method);

		InsnList toInject = new InsnList();

		AbstractInsnNode entryPoint = ASMHelper.findFirstInstructionOfTypeWithDesc(method, LDC, "naturalRegeneration");
		AbstractInsnNode injectPoint = entryPoint.getPrevious().getPrevious().getPrevious().getPrevious();
		AbstractInsnNode healthBlockJumpToEnd = ASMHelper.findNextInstructionOfType(entryPoint, GOTO);
		LabelNode healthBlockEndLabel = ((JumpInsnNode) healthBlockJumpToEnd).label;

		// remove the entire health regen block
		ASMHelper.removeNodesFromMethodUntil(method, injectPoint.getNext(), healthBlockEndLabel);

		// create allowRegenResult variable
		LabelNode allowRegenResultStart = new LabelNode();
		LocalVariableNode allowRegenResult = new LocalVariableNode("allowRegenResult", Type.getDescriptor(Event.Result.class), null, allowRegenResultStart, endLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(allowRegenResult);

		// Result allowRegenResult = Hooks.fireAllowRegenEvent(player);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireAllowRegenEvent", "(Lnet/minecraft/entity/player/EntityPlayer;)Lcpw/mods/fml/common/eventhandler/Event$Result;"));
		toInject.add(new VarInsnNode(ASTORE, allowRegenResult.index));
		toInject.add(allowRegenResultStart);

		// if (allowRegenResult == Result.ALLOW || (allowRegenResult == Result.DEFAULT && player.worldObj.getGameRules().getGameRuleBooleanValue("naturalRegeneration") && this.foodLevel >= 18 && player.shouldHeal()))
		toInject.add(new VarInsnNode(ALOAD, allowRegenResult.index));
		toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(Event.Result.class), "ALLOW", Type.getDescriptor(Event.Result.class)));
		LabelNode ifAllowed = new LabelNode();
		toInject.add(new JumpInsnNode(IF_ACMPEQ, ifAllowed));
		toInject.add(new VarInsnNode(ALOAD, allowRegenResult.index));
		toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(Event.Result.class), "DEFAULT", Type.getDescriptor(Event.Result.class)));
		LabelNode elseStart = new LabelNode();
		toInject.add(new JumpInsnNode(IF_ACMPNE, elseStart));
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(EntityPlayer.class), isObfuscated ? "o" : "worldObj", isObfuscated ? "Lahb;" : "Lnet/minecraft/world/World;"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(World.class), isObfuscated ? "O" : "getGameRules", isObfuscated ? "()Lagy;" : "()Lnet/minecraft/world/GameRules;"));
		toInject.add(new LdcInsnNode("naturalRegeneration"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(GameRules.class), isObfuscated ? "b" : "getGameRuleBooleanValue", "(Ljava/lang/String;)Z"));
		toInject.add(new JumpInsnNode(IFEQ, elseStart));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));
		toInject.add(new IntInsnNode(BIPUSH, 18));
		toInject.add(new JumpInsnNode(IF_ICMPLT, elseStart));
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(EntityPlayer.class), isObfuscated ? "bR" : "shouldHeal", "()Z"));
		toInject.add(new JumpInsnNode(IFEQ, elseStart));
		toInject.add(ifAllowed);

		// ++this.foodTimer;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(DUP));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "foodTimer", "I"));
		toInject.add(new InsnNode(ICONST_1));
		toInject.add(new InsnNode(IADD));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "foodTimer", "I"));

		// if (this.foodTimer >= Hooks.fireRegenTickEvent(player))
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "foodTimer", "I"));
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireRegenTickEvent", "(Lnet/minecraft/entity/player/EntityPlayer;)I"));
		toInject.add(new JumpInsnNode(IF_ICMPLT, healthBlockEndLabel));

		// create regenEvent variable
		LabelNode regenEventStart = new LabelNode();
		LabelNode regenEventEnd = new LabelNode();
		LocalVariableNode regenEvent = new LocalVariableNode("regenEvent", Type.getDescriptor(HealthRegenEvent.Regen.class), null, regenEventStart, regenEventEnd, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(regenEvent);

		// FoodEvent.RegenHealth.Regen regenEvent = Hooks.fireRegenEvent(player);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireRegenEvent", "(Lnet/minecraft/entity/player/EntityPlayer;)Lsqueek/applecore/api/hunger/HealthRegenEvent$Regen;"));
		toInject.add(new VarInsnNode(ASTORE, regenEvent.index));
		toInject.add(regenEventStart);

		// if (!regenEvent.isCanceled())
		toInject.add(new VarInsnNode(ALOAD, regenEvent.index));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(HealthRegenEvent.Regen.class), "isCanceled", "()Z"));
		LabelNode ifCanceled = new LabelNode();
		toInject.add(new JumpInsnNode(IFNE, ifCanceled));

		// player.heal(regenEvent.deltaHealth);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new VarInsnNode(ALOAD, regenEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(HealthRegenEvent.Regen.class), "deltaHealth", "F"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(EntityPlayer.class), isObfuscated ? "f" : "heal", "(F)V"));
	    
		// this.addExhaustion(regenEvent.deltaExhaustion);
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new VarInsnNode(ALOAD, regenEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(HealthRegenEvent.Regen.class), "deltaExhaustion", "F"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, internalFoodStatsName, isObfuscated ? "a" : "addExhaustion", "(F)V"));
	    
		// this.foodTimer = 0;
		toInject.add(ifCanceled);
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(ICONST_0));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "foodTimer", "I"));
		toInject.add(regenEventEnd);
		toInject.add(new JumpInsnNode(GOTO, healthBlockEndLabel));

		// else
		toInject.add(elseStart);

		// this.foodTimer = 0;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(ICONST_0));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "foodTimer", "I"));
		
		method.instructions.insert(injectPoint, toInject);
	}

	private void hookStarvation(ClassNode classNode, MethodNode method, boolean isObfuscated)
	{
		// add starveTimer field
		classNode.fields.add(new FieldNode(ACC_PUBLIC, "starveTimer", "I", null, null));

		// injected at the bottom of the function:
		/*
		Result allowStarvationResult = Hooks.fireAllowStarvation(player);
		if (allowStarvationResult == Result.ALLOW || (allowStarvationResult == Result.DEFAULT && this.foodLevel <= 0))
		{
			++this.starveTimer;

			if (this.starveTimer >= Hooks.fireStarvationTickEvent(player))
			{
				FoodEvent.Starvation.Starve starveEvent = Hooks.fireStarveEvent(player);
				if (!starveEvent.isCanceled())
				{
					player.attackEntityFrom(DamageSource.starve, starveEvent.starveDamage);
				}
				this.starveTimer = 0;
			}
		}
		else
		{
			this.starveTimer = 0;
		}
		 */

		String internalFoodStatsName = classNode.name.replace(".", "/");
		AbstractInsnNode lastReturn = ASMHelper.findLastInstructionOfType(method, RETURN);
		LabelNode endLabel = ASMHelper.findEndLabel(method);

		InsnList toInject = new InsnList();

		// create allowStarvationResult variable
		LabelNode allowStarvationResultStart = new LabelNode();
		LocalVariableNode allowStarvationResult = new LocalVariableNode("allowStarvationResult", Type.getDescriptor(Event.Result.class), null, allowStarvationResultStart, endLabel, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(allowStarvationResult);

		// Result allowStarvationResult = Hooks.fireAllowStarvation(player);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireAllowStarvation", "(Lnet/minecraft/entity/player/EntityPlayer;)Lcpw/mods/fml/common/eventhandler/Event$Result;"));
		toInject.add(new VarInsnNode(ASTORE, allowStarvationResult.index));
		toInject.add(allowStarvationResultStart);

		// if (allowStarvationResult == Result.ALLOW || (allowStarvationResult == Result.DEFAULT && this.foodLevel <= 0))
		toInject.add(new VarInsnNode(ALOAD, allowStarvationResult.index));
		toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(Event.Result.class), "ALLOW", Type.getDescriptor(Event.Result.class)));
		LabelNode ifAllowed = new LabelNode();
		toInject.add(new JumpInsnNode(IF_ACMPEQ, ifAllowed));
		toInject.add(new VarInsnNode(ALOAD, allowStarvationResult.index));
		LabelNode elseStart = new LabelNode();
		toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(Event.Result.class), "DEFAULT", Type.getDescriptor(Event.Result.class)));
		toInject.add(new JumpInsnNode(IF_ACMPNE, elseStart));
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, isObfuscated ? "a" : "foodLevel", "I"));
		toInject.add(new JumpInsnNode(IFGT, elseStart));
		toInject.add(ifAllowed);

		// ++this.starveTimer;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(DUP));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "starveTimer", "I"));
		toInject.add(new InsnNode(ICONST_1));
		toInject.add(new InsnNode(IADD));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "starveTimer", "I"));

		// if (this.starveTimer >= Hooks.fireStarvationTickEvent(player))
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new FieldInsnNode(GETFIELD, internalFoodStatsName, "starveTimer", "I"));
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireStarvationTickEvent", "(Lnet/minecraft/entity/player/EntityPlayer;)I"));
		LabelNode beforeReturn = new LabelNode();
		toInject.add(new JumpInsnNode(IF_ICMPLT, beforeReturn));

		// create starveEvent variable
		LabelNode starveEventStart = new LabelNode();
		LabelNode starveEventEnd = new LabelNode();
		LocalVariableNode starveEvent = new LocalVariableNode("starveEvent", Type.getDescriptor(StarvationEvent.Starve.class), null, starveEventStart, starveEventEnd, method.maxLocals);
		method.maxLocals += 1;
		method.localVariables.add(starveEvent);

		// FoodEvent.Starvation.Starve starveEvent = Hooks.fireStarveEvent(player);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new MethodInsnNode(INVOKESTATIC, Type.getInternalName(Hooks.class), "fireStarveEvent", "(Lnet/minecraft/entity/player/EntityPlayer;)Lsqueek/applecore/api/hunger/StarvationEvent$Starve;"));
		toInject.add(new VarInsnNode(ASTORE, starveEvent.index));
		toInject.add(starveEventStart);

		// if (!starveEvent.isCanceled())
		toInject.add(new VarInsnNode(ALOAD, starveEvent.index));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(StarvationEvent.Starve.class), "isCanceled", "()Z"));
		LabelNode ifCanceled = new LabelNode();
		toInject.add(new JumpInsnNode(IFNE, ifCanceled));

		// player.attackEntityFrom(DamageSource.starve, starveEvent.starveDamage);
		toInject.add(new VarInsnNode(ALOAD, 1));
		toInject.add(new FieldInsnNode(GETSTATIC, Type.getInternalName(DamageSource.class), isObfuscated ? "f" : "starve", Type.getDescriptor(DamageSource.class)));
		toInject.add(new VarInsnNode(ALOAD, starveEvent.index));
		toInject.add(new FieldInsnNode(GETFIELD, Type.getInternalName(StarvationEvent.Starve.class), "starveDamage", "F"));
		toInject.add(new MethodInsnNode(INVOKEVIRTUAL, Type.getInternalName(EntityPlayer.class), isObfuscated ? "a" : "attackEntityFrom", isObfuscated ? "(Lro;F)Z" : "(Lnet/minecraft/util/DamageSource;F)Z"));
		toInject.add(new InsnNode(POP));

		// this.starveTimer = 0;
		toInject.add(ifCanceled);
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(ICONST_0));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "starveTimer", "I"));
		toInject.add(starveEventEnd);
		toInject.add(new JumpInsnNode(GOTO, beforeReturn));

		// else
		toInject.add(elseStart);

		// this.starveTimer = 0;
		toInject.add(new VarInsnNode(ALOAD, 0));
		toInject.add(new InsnNode(ICONST_0));
		toInject.add(new FieldInsnNode(PUTFIELD, internalFoodStatsName, "starveTimer", "I"));

		toInject.add(beforeReturn);

		method.instructions.insertBefore(lastReturn, toInject);
	}

}