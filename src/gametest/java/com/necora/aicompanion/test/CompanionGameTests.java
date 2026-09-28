package com.necora.aicompanion.test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.necora.aicompanion.ai.ActionContext;
import com.necora.aicompanion.ai.ActionDispatcher;
import com.necora.aicompanion.ai.CompanionBrain;
import com.necora.aicompanion.ai.Reply;
import com.necora.aicompanion.build.Blueprint;
import com.necora.aicompanion.build.Frame;
import com.necora.aicompanion.build.Materials;
import com.necora.aicompanion.build.Structures;
import com.necora.aicompanion.entity.CompanionEntity;
import com.necora.aicompanion.entity.GameModeSetting;
import com.necora.aicompanion.manager.CompanionManager;
import com.necora.aicompanion.registry.ModEntities;
import com.necora.aicompanion.task.Task;
import com.necora.aicompanion.task.behavior.IdleBehavior;
import com.necora.aicompanion.task.tasks.BuildTask;
import com.necora.aicompanion.task.tasks.CraftTask;
import com.necora.aicompanion.task.tasks.MineTask;
import com.necora.aicompanion.util.Names;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BlockState;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs on a real dedicated server in CI (./gradlew runGametest). No language model involved:
 * the tests drive tasks and the action dispatcher directly.
 */
public class CompanionGameTests implements FabricGameTest {
	private static final AtomicInteger COUNTER = new AtomicInteger();

	/** Completes the test as soon as {@code check} returns null; fails with its last message near the tick limit. */
	private static void succeedWhen(TestContext ctx, int tickLimit, Supplier<String> check) {
		ctx.runAtEveryTick(() -> {
			String problem;
			try {
				problem = check.get();
			} catch (RuntimeException e) {
				problem = e.toString();
			}
			if (problem == null) {
				ctx.complete();
			} else if (ctx.getTick() >= tickLimit - 3) {
				ctx.throwGameTestException(problem);
			}
		});
	}

	private static CompanionEntity spawn(TestContext ctx, BlockPos relative, boolean creative) {
		ServerWorld world = ctx.getWorld();
		CompanionEntity c = ModEntities.COMPANION.create(world);
		if (c == null) throw new IllegalStateException("could not create companion");
		c.setCompanionName("Test" + COUNTER.incrementAndGet());
		c.setGameModeSetting(creative ? GameModeSetting.CREATIVE : GameModeSetting.SURVIVAL);
		Vec3d pos = Vec3d.ofBottomCenter(ctx.getAbsolutePos(relative));
		c.refreshPositionAndAngles(pos.x, pos.y, pos.z, 0.0F, 0.0F);
		world.spawnEntity(c);
		c.getTaskManager().setBehavior(new IdleBehavior(c));
		return c;
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void companionSpawnsAndTicks(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(3, 0, 3), false);
		c.getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 12));
		ctx.runAtTick(60, () -> {
			ctx.assertTrue(c.isAlive() && !c.isRemoved(), "companion died or was removed");
			ctx.assertTrue(CompanionManager.brainOf(c) != null, "companion has no brain");
			NbtCompound nbt = new NbtCompound();
			c.writeNbt(nbt);
			CompanionEntity copy = ModEntities.COMPANION.create(ctx.getWorld());
			copy.readNbt(nbt);
			ctx.assertTrue(copy.getCompanionName().equals(c.getCompanionName()), "name not saved");
			ctx.assertTrue(copy.countItem(Items.COBBLESTONE) == 12, "inventory not saved");
			ctx.complete();
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
	public void creativeBuildsPillar(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), true);
		BlockPos base = ctx.getAbsolutePos(new BlockPos(4, 0, 4));
		Blueprint bp = Structures.pillar(base, 5, Blocks.STONE.getDefaultState());
		c.getTaskManager().replaceAll(List.of(new BuildTask(c, bp, "test pillar", false)));
		succeedWhen(ctx, 400, () -> {
			for (int y = 0; y < 5; y++) {
				if (!ctx.getWorld().getBlockState(base.up(y)).isOf(Blocks.STONE)) return "pillar block " + y + " missing; doing: " + c.getTaskManager().describe();
			}
			return null;
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
	public void survivalBuildsPlatformFromInventory(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), false);
		c.getInventory().setStack(0, new ItemStack(Items.COBBLESTONE, 16));
		BlockPos corner = ctx.getAbsolutePos(new BlockPos(4, 0, 3));
		Blueprint bp = new Blueprint("test platform");
		for (int x = 0; x < 3; x++) {
			for (int z = 0; z < 3; z++) {
				bp.set(corner.add(x, 0, z), Blocks.COBBLESTONE.getDefaultState(), 2);
			}
		}
		c.getTaskManager().replaceAll(List.of(new BuildTask(c, bp, "test platform", false)));
		succeedWhen(ctx, 600, () -> {
			for (int x = 0; x < 3; x++) {
				for (int z = 0; z < 3; z++) {
					if (!ctx.getWorld().getBlockState(corner.add(x, 0, z)).isOf(Blocks.COBBLESTONE)) {
						return "platform block missing at " + x + "," + z + "; doing: " + c.getTaskManager().describe() + " at " + c.getBlockPos();
					}
				}
			}
			return c.countItem(Items.COBBLESTONE) == 7 ? null : "expected 7 cobblestone left, had " + c.countItem(Items.COBBLESTONE);
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void survivalBuildNeedsMaterials(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), false);
		BlockPos base = ctx.getAbsolutePos(new BlockPos(4, 0, 4));
		BuildTask task = new BuildTask(c, Structures.pillar(base, 3, Blocks.STONE.getDefaultState()), "test", false);
		Task.Status status = task.update();
		ctx.assertTrue(status == Task.Status.FAILED, "should fail without stone, got " + status);
		ctx.assertTrue(task.getResultMessage().contains("stone"), "message should name the missing block: " + task.getResultMessage());
		ctx.complete();
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 600)
	public void creativeSurroundsSpot(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(0, 0, 0), true);
		BlockPos feet = ctx.getAbsolutePos(new BlockPos(4, 0, 4));
		BlockState glass = Blocks.GLASS.getDefaultState();
		Blueprint bp = Structures.surround(ctx.getWorld(), feet, 1, Blocks.COBBLESTONE.getDefaultState(), true, false, glass);
		c.getTaskManager().replaceAll(List.of(new BuildTask(c, bp, "shelter", false)));
		succeedWhen(ctx, 600, () -> {
			ServerWorld w = ctx.getWorld();
			String doing = "; doing: " + c.getTaskManager().describe();
			if (!w.getBlockState(feet).isAir() || !w.getBlockState(feet.up()).isAir()) return "inside must stay free" + doing;
			if (w.getBlockState(feet.up(2)).isAir()) return "roof missing" + doing;
			if (!w.getBlockState(feet.north().up()).isOf(Blocks.GLASS)) return "window missing" + doing;
			if (!w.getBlockState(feet.north()).isOf(Blocks.COBBLESTONE)) return "wall missing" + doing;
			if (!w.getBlockState(feet.east().south()).isOf(Blocks.COBBLESTONE)) return "corner missing" + doing;
			return null;
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 800)
	public void survivalMinesLogs(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), false);
		BlockPos trunk = ctx.getAbsolutePos(new BlockPos(5, 0, 5));
		for (int y = 0; y < 3; y++) ctx.getWorld().setBlockState(trunk.up(y), Blocks.OAK_LOG.getDefaultState());
		c.getTaskManager().replaceAll(List.of(new MineTask(c, Names.blockMatcher("logs"), 3, 12)));
		succeedWhen(ctx, 800, () -> c.countItem(Items.OAK_LOG) >= 3 ? null
				: "has " + c.countItem(Items.OAK_LOG) + " logs; doing: " + c.getTaskManager().describe() + " at " + c.getBlockPos());
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 200)
	public void craftsSticksFromLogs(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(2, 0, 2), false);
		c.getInventory().setStack(0, new ItemStack(Items.OAK_LOG, 2));
		c.getTaskManager().replaceAll(List.of(new CraftTask(c, List.of(Items.STICK), 4)));
		succeedWhen(ctx, 200, () -> c.countItem(Items.STICK) >= 4 ? null
				: "has " + c.countItem(Items.STICK) + " sticks; doing: " + c.getTaskManager().describe());
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 400)
	public void dispatcherRunsModelActions(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), true);
		CompanionBrain brain = CompanionManager.brainOf(c);
		ctx.assertTrue(brain != null, "no brain");
		BlockPos at = ctx.getAbsolutePos(new BlockPos(5, 0, 2));
		String raw = "Sure thing!\n```json\n{\"say\": \"on it\", \"actions\": [{\"type\": \"build\", \"structure\": \"pillar\", \"height\": 3, \"material\": \"oak planks\", "
				+ "\"at\": {\"x\": " + at.getX() + ", \"y\": " + at.getY() + ", \"z\": " + at.getZ() + "}}, {\"type\": \"emote\", \"kind\": \"wave\"}], \"remember\": [\"likes oak\"]}\n```";
		Reply reply = Reply.parse(raw, c.getCompanionName());
		ctx.assertTrue(reply.say().equals("on it"), "say parsed wrong: " + reply.say());
		ctx.assertTrue(reply.actions().size() == 2, "expected 2 actions");
		ctx.assertTrue(reply.remember().size() == 1, "expected 1 memory");
		ActionDispatcher.Result result = ActionDispatcher.dispatch(new ActionContext(c, brain, null, true), reply.actions(), false);
		ctx.assertTrue(result.errors().isEmpty(), "dispatch errors: " + result.errors());
		ctx.assertTrue(result.accepted() == 2, "accepted " + result.accepted());
		succeedWhen(ctx, 400, () -> {
			for (int y = 0; y < 3; y++) {
				if (!ctx.getWorld().getBlockState(at.up(y)).isOf(Blocks.OAK_PLANKS)) return "pillar block " + y + " missing; doing: " + c.getTaskManager().describe();
			}
			return null;
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 2400)
	public void creativeBuildsHouse(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(0, 0, 0), true);
		Structures.HouseSpec spec = new Structures.HouseSpec();
		spec.width = 5;
		spec.depth = 5;
		spec.height = 3;
		Frame f = new Frame(ctx.getAbsolutePos(new BlockPos(1, 0, 6)), Direction.NORTH);
		Blueprint bp = Structures.house(ctx.getWorld(), f, spec);
		c.getTaskManager().replaceAll(List.of(new BuildTask(c, bp, "test house", false)));
		succeedWhen(ctx, 2400, () -> {
			ServerWorld w = ctx.getWorld();
			String doing = "; doing: " + c.getTaskManager().describe();
			if (c.getTaskManager().isBusy()) return "still building" + doing;
			if (!(w.getBlockState(f.at(2, 1, 0)).getBlock() instanceof DoorBlock)) return "door missing" + doing;
			if (!w.getBlockState(f.at(0, 1, 0)).isOf(spec.corner)) return "corner log missing";
			if (!w.getBlockState(f.at(1, 2, 0)).isOf(spec.wall)) return "front wall missing";
			if (!w.getBlockState(f.at(1, 1, 1)).isOf(Blocks.TORCH)) return "inside torch missing";
			if (!w.getBlockState(f.at(-1, 4, 0)).isOf(Blocks.SPRUCE_STAIRS)) return "roof stairs missing: " + w.getBlockState(f.at(-1, 4, 0));
			if (!w.getBlockState(f.at(2, 2, 2)).isAir()) return "inside should be empty";
			return null;
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE, tickLimit = 1600)
	public void survivalPillarsUpToReach(TestContext ctx) {
		CompanionEntity c = spawn(ctx, new BlockPos(1, 0, 1), false);
		c.getInventory().setStack(0, new ItemStack(Items.DIRT, 10));
		c.getInventory().setStack(1, new ItemStack(Items.COBBLESTONE, 8));
		BlockPos base = ctx.getAbsolutePos(new BlockPos(4, 0, 4));
		Blueprint bp = Structures.pillar(base, 8, Blocks.COBBLESTONE.getDefaultState());
		c.getTaskManager().replaceAll(List.of(new BuildTask(c, bp, "tall pillar", false)));
		succeedWhen(ctx, 1600, () -> {
			ServerWorld w = ctx.getWorld();
			String doing = "; doing: " + c.getTaskManager().describe() + " at " + ctx.getRelativePos(c.getBlockPos());
			for (int y = 0; y < 8; y++) {
				if (!w.getBlockState(base.up(y)).isOf(Blocks.COBBLESTONE)) return "pillar block " + y + " missing" + doing;
			}
			if (c.getTaskManager().isBusy()) return "still working" + doing;
			BlockPos rel = ctx.getRelativePos(c.getBlockPos());
			if (rel.getY() > 1) return "companion didn't come back down" + doing;
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int y = 0; y < 7; y++) {
						if (w.getBlockState(base.add(dx, y, dz)).isOf(Blocks.DIRT)) return "scaffold left at " + dx + "," + y + "," + dz;
					}
				}
			}
			return null;
		});
	}

	@GameTest(templateName = EMPTY_STRUCTURE)
	public void namesAndMaterialsResolve(TestContext ctx) {
		ctx.assertTrue(Names.block("oak planks") == Blocks.OAK_PLANKS, "oak planks");
		ctx.assertTrue(Names.block("cobble") == Blocks.COBBLESTONE, "cobble alias");
		ctx.assertTrue(Names.block("stone bricks") == Blocks.STONE_BRICKS, "stone bricks");
		ctx.assertTrue(Names.item("torches") == Items.TORCH, "torches plural");
		ctx.assertTrue(Names.blockState("oak_stairs[facing=north]") != null, "block state parse");
		ctx.assertTrue(Materials.stairs(Blocks.OAK_PLANKS) == Blocks.OAK_STAIRS, "oak stairs");
		ctx.assertTrue(Materials.stairs(Blocks.STONE_BRICKS) == Blocks.STONE_BRICK_STAIRS, "stone brick stairs");
		ctx.assertTrue(Materials.slab(Blocks.COBBLESTONE) == Blocks.COBBLESTONE_SLAB, "cobblestone slab");
		ctx.assertTrue(Materials.log(Blocks.SPRUCE_PLANKS) == Blocks.SPRUCE_LOG, "spruce log");
		ctx.assertTrue(Names.blockMatcher("iron").predicate().test(Blocks.DEEPSLATE_IRON_ORE.getDefaultState()), "iron ore tag");
		JsonObject o = JsonParser.parseString("{\"type\":\"give\",\"item\":\"all\"}").getAsJsonObject();
		ctx.assertTrue(o.has("type"), "json");
		Reply plain = Reply.parse("hey whats up", "Bob");
		ctx.assertTrue(plain.say().equals("hey whats up") && plain.actions().isEmpty(), "plain text reply");
		ctx.complete();
	}
}
