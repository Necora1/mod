package com.necora.aicompanion.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** Forgiving accessors: models send numbers as strings, booleans as "yes", etc. */
public final class Json {
	private Json() {
	}

	@Nullable
	public static JsonElement get(JsonObject o, String... keys) {
		if (o == null) return null;
		for (String k : keys) {
			if (o.has(k) && !o.get(k).isJsonNull()) return o.get(k);
		}
		return null;
	}

	@Nullable
	public static String str(JsonObject o, String... keys) {
		JsonElement e = get(o, keys);
		if (e == null) return null;
		if (e.isJsonPrimitive()) return e.getAsString();
		return e.toString();
	}

	public static String strOr(JsonObject o, String def, String... keys) {
		String s = str(o, keys);
		return s == null || s.isBlank() ? def : s;
	}

	public static int integer(JsonObject o, int def, String... keys) {
		JsonElement e = get(o, keys);
		if (e == null || !e.isJsonPrimitive()) return def;
		try {
			return (int) Math.round(e.getAsDouble());
		} catch (NumberFormatException ex) {
			try {
				return Integer.parseInt(e.getAsString().replaceAll("[^0-9\\-]", ""));
			} catch (NumberFormatException ex2) {
				return def;
			}
		}
	}

	public static double dbl(JsonObject o, double def, String... keys) {
		JsonElement e = get(o, keys);
		if (e == null || !e.isJsonPrimitive()) return def;
		try {
			return e.getAsDouble();
		} catch (NumberFormatException ex) {
			return def;
		}
	}

	public static boolean bool(JsonObject o, boolean def, String... keys) {
		JsonElement e = get(o, keys);
		if (e == null || !e.isJsonPrimitive()) return def;
		String s = e.getAsString().trim().toLowerCase();
		if (s.equals("true") || s.equals("yes") || s.equals("1") || s.equals("y")) return true;
		if (s.equals("false") || s.equals("no") || s.equals("0") || s.equals("n")) return false;
		return def;
	}

	/** Reads [x,y,z], {"x":..,"y":..,"z":..} or "x y z". */
	@Nullable
	public static BlockPos vec(@Nullable JsonElement e) {
		if (e == null || e.isJsonNull()) return null;
		try {
			if (e.isJsonArray()) {
				JsonArray a = e.getAsJsonArray();
				if (a.size() < 3) return null;
				return BlockPos.ofFloored(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble());
			}
			if (e.isJsonObject()) {
				JsonObject o = e.getAsJsonObject();
				if (!o.has("x") || !o.has("z")) return null;
				double y = o.has("y") ? o.get("y").getAsDouble() : 0;
				return BlockPos.ofFloored(o.get("x").getAsDouble(), y, o.get("z").getAsDouble());
			}
			if (e.isJsonPrimitive()) {
				String[] parts = e.getAsString().trim().split("[\\s,]+");
				if (parts.length == 3) {
					return BlockPos.ofFloored(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2]));
				}
			}
		} catch (Exception ignored) {
		}
		return null;
	}
}
