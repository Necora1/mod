package com.necora.aicompanion.task;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Where to go / look: a fixed spot or an entity that may move. */
public interface Target {
	@Nullable
	Vec3d position();

	@Nullable
	default Entity entity() {
		return null;
	}

	String describe();

	static Target of(BlockPos pos, String label) {
		final Vec3d v = Vec3d.ofBottomCenter(pos);
		return new Target() {
			@Override
			public Vec3d position() {
				return v;
			}

			@Override
			public String describe() {
				return label;
			}
		};
	}

	static Target of(Entity e, String label) {
		return new Target() {
			@Override
			public Vec3d position() {
				return e.isRemoved() || !e.isAlive() ? null : e.getPos();
			}

			@Override
			public Entity entity() {
				return e;
			}

			@Override
			public String describe() {
				return label;
			}
		};
	}
}
