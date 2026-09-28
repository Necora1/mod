package com.necora.aicompanion.entity;

import java.util.Locale;

public enum GameModeSetting {
	/** Same game mode as the owner. */
	AUTO,
	SURVIVAL,
	CREATIVE;

	public static GameModeSetting parse(String s, GameModeSetting fallback) {
		if (s == null) return fallback;
		String v = s.trim().toLowerCase(Locale.ROOT);
		if (v.startsWith("c") || v.equals("1")) return CREATIVE;
		if (v.startsWith("s") || v.equals("0") || v.equals("adventure")) return SURVIVAL;
		if (v.startsWith("a") || v.equals("owner") || v.equals("same")) return AUTO;
		return fallback;
	}

	public String id() {
		return name().toLowerCase(Locale.ROOT);
	}
}
